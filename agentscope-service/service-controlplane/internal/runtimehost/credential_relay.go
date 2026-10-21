// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package runtimehost

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"path"
	"strings"
	"time"

	"github.com/google/uuid"
)

// A CLI keeps its environment and MCP config for its entire lifetime. This
// attempt-local relay replaces its opaque loopback credential with the latest
// fenced task token. It never forwards daemon or human credentials.
type taskCredentialRelay struct {
	URL, MCP, Token string
	server          *http.Server
	client          *http.Client
}

func startTaskCredentialRelay(controlPlane, collaborationMCP string, currentToken func() string) (*taskCredentialRelay, error) {
	base, err := url.Parse(controlPlane)
	if err != nil || (base.Scheme != "http" && base.Scheme != "https") || base.Host == "" || base.User != nil {
		return nil, fmt.Errorf("task credential relay requires an HTTP Service URL")
	}
	var mcp *url.URL
	if collaborationMCP != "" {
		mcp, err = url.Parse(collaborationMCP)
		if err != nil || (mcp.Scheme != "http" && mcp.Scheme != "https") || mcp.Host == "" || mcp.User != nil {
			return nil, fmt.Errorf("task credential relay requires an HTTP MCP URL")
		}
	}
	secret := make([]byte, 32)
	if _, err = rand.Read(secret); err != nil {
		return nil, err
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	relay := &taskCredentialRelay{URL: "http://" + listener.Addr().String(),
		Token:  "aslocal_" + base64.RawURLEncoding.EncodeToString(secret),
		client: &http.Client{Timeout: 35 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}
	if mcp != nil {
		relay.MCP = relay.URL + "/mcp/collaboration"
	}
	slots := make(chan struct{}, 8)
	relay.server = &http.Server{ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second,
		WriteTimeout: 40 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 16 << 10,
		Handler: http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
			credential := strings.TrimPrefix(request.Header.Get("Authorization"), "Bearer ")
			if credential == "" {
				credential = request.Header.Get("X-Agent-Task-Token")
			}
			if subtle.ConstantTimeCompare([]byte(credential), []byte(relay.Token)) != 1 {
				http.Error(w, "invalid task relay credential", http.StatusUnauthorized)
				return
			}
			var target *url.URL
			if request.URL.Path == "/mcp/collaboration" && mcp != nil && request.Method == http.MethodPost {
				copy := *mcp
				target = &copy
			} else if relayTaskPath(request.URL.Path) && (request.Method == http.MethodGet || request.Method == http.MethodPost) {
				copy := *base
				copy.Path = strings.TrimRight(base.Path, "/") + request.URL.Path
				copy.RawPath, copy.RawQuery, copy.Fragment = "", request.URL.RawQuery, ""
				target = &copy
			} else {
				http.NotFound(w, request)
				return
			}
			token := currentToken()
			if token == "" {
				http.Error(w, "task authority is unavailable", http.StatusForbidden)
				return
			}
			select {
			case slots <- struct{}{}:
				defer func() { <-slots }()
			default:
				http.Error(w, "task relay capacity exhausted", http.StatusTooManyRequests)
				return
			}
			body, err := io.ReadAll(http.MaxBytesReader(w, request.Body, 2<<20))
			if err != nil {
				http.Error(w, "task request exceeds limit", http.StatusRequestEntityTooLarge)
				return
			}
			upstream, err := http.NewRequestWithContext(request.Context(), request.Method, target.String(), strings.NewReader(string(body)))
			if err != nil {
				http.Error(w, "invalid task request", http.StatusBadRequest)
				return
			}
			for _, name := range []string{"Content-Type", "Accept", "Mcp-Protocol-Version", "Mcp-Session-Id", "Last-Event-ID"} {
				if value := request.Header.Get(name); value != "" {
					upstream.Header.Set(name, value)
				}
			}
			upstream.Header.Set("Authorization", "Bearer "+token)
			upstream.Header.Set("X-Agent-Task-Token", token)
			response, err := relay.client.Do(upstream)
			if err != nil {
				http.Error(w, "Service is unavailable", http.StatusServiceUnavailable)
				return
			}
			defer response.Body.Close()
			data, err := io.ReadAll(io.LimitReader(response.Body, (8<<20)+1))
			if err != nil || len(data) > 8<<20 {
				http.Error(w, "invalid Service response", http.StatusBadGateway)
				return
			}
			for _, name := range []string{"Content-Type", "Mcp-Session-Id", "Retry-After"} {
				if value := response.Header.Get(name); value != "" {
					w.Header().Set(name, value)
				}
			}
			w.WriteHeader(response.StatusCode)
			_, _ = w.Write(data)
		})}
	go func() { _ = relay.server.Serve(listener) }()
	return relay, nil
}

func relayTaskPath(value string) bool {
	if path.Clean(value) != value {
		return false
	}
	for _, prefix := range []string{"/api/v1/agent-tasks/", "/api/v1/issues/", "/api/v1/teams/"} {
		if strings.HasPrefix(value, prefix) {
			_, err := uuid.Parse(strings.Split(strings.TrimPrefix(value, prefix), "/")[0])
			return err == nil
		}
	}
	return false
}

func (r *taskCredentialRelay) Close() {
	_ = r.server.Close()
	r.client.CloseIdleConnections()
}

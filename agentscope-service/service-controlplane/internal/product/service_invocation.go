/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package product

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

// ManagedServiceRequest is used only after public Session authorization.
// Callers supply fixed resource suffixes, never arbitrary upstream URLs.
type ManagedServiceError struct {
	StatusCode int
	Message    string
}

func (e *ManagedServiceError) Error() string {
	return fmt.Sprintf("managed service API HTTP %d: %s", e.StatusCode, e.Message)
}

func (s *Server) ManagedServiceRequest(ctx context.Context, owner, session, method, suffix, key string, body any) (json.RawMessage, error) {
	raw, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	endpoint := strings.TrimRight(s.cfg.DataURL, "/") + "/api/v1/agent-sessions/" + url.PathEscape(session) + suffix
	req, err := http.NewRequestWithContext(ctx, method, endpoint, bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Builder-Internal-Token", s.cfg.InternalToken)
	req.Header.Set("X-Builder-Internal-User", owner)
	if key != "" {
		req.Header.Set("Idempotency-Key", key)
	}
	response, err := (&http.Client{Timeout: 15 * time.Second}).Do(req)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	payload, err := io.ReadAll(io.LimitReader(response.Body, 32<<20))
	if err != nil {
		return nil, err
	}
	if response.StatusCode >= 300 {
		return nil, &ManagedServiceError{StatusCode: response.StatusCode, Message: string(payload)}
	}
	return payload, nil
}

func (s *Server) ManagedSessionView(ctx context.Context, owner, id string) (map[string]any, error) {
	v, err := s.loadSession(ctx, id)
	if err != nil {
		return nil, err
	}
	if v.OwnerID != owner {
		return nil, fmt.Errorf("session not found")
	}
	return v.toJSON(), nil
}
func (s *Server) UpdateManagedServiceSession(c *gin.Context, owner, id string) error {
	_, err := s.applySessionUpdate(c, id, owner, true)
	return err
}

// ManagedSessionResource performs an authenticated request after the control plane
// has authorized the public Session. Only fixed, locally constructed suffixes are accepted.
func (s *Server) ManagedSessionResource(ctx context.Context, owner, session, method, suffix string, headers http.Header, body io.Reader) (*http.Response, error) {
	endpoint := strings.TrimRight(s.cfg.DataURL, "/") + "/api/v1/agent-sessions/" + url.PathEscape(session) + suffix
	req, err := http.NewRequestWithContext(ctx, method, endpoint, body)
	if err != nil {
		return nil, err
	}
	for _, name := range []string{"Content-Type", "Idempotency-Key", "X-File-Name", "Last-Event-ID"} {
		if v := headers.Get(name); v != "" {
			req.Header.Set(name, v)
		}
	}
	req.Header.Set("X-Builder-Internal-Token", s.cfg.InternalToken)
	req.Header.Set("X-Builder-Internal-User", owner)
	timeout := 60 * time.Second
	if strings.Contains(suffix, "/events/stream") {
		timeout = 0
	}
	return (&http.Client{Timeout: timeout}).Do(req)
}

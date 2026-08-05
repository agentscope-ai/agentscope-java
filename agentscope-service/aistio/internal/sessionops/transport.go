// Copyright 2024-2026 the original author or authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package sessionops

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

const (
	defaultCommandTimeout  = 30 * time.Second
	compressCommandTimeout = 120 * time.Second
)

// commandTimeout returns the per-command HTTP/ASDP deadline.
func commandTimeout(command string) time.Duration {
	if strings.EqualFold(command, CommandCompress) {
		return compressCommandTimeout
	}
	return defaultCommandTimeout
}

// dpCommandResponse is the frozen Level-3 command success body.
type dpCommandResponse struct {
	Accepted  bool            `json:"accepted"`
	CommandID string          `json:"commandId"`
	Phase     string          `json:"phase"`
	Result    json.RawMessage `json:"result"`
}

// dpErrorBody is the optional error payload from the data plane.
type dpErrorBody struct {
	Error string `json:"error"`
	Code  string `json:"code"`
	Hint  string `json:"hint"`
}

// ASDPSender optionally delivers a session command over a live ASDP stream.
type ASDPSender interface {
	SendSessionCommand(namespace, instanceID, sessionID, command string) error
}

// sendHTTP posts the command to the data-plane contract endpoint.
func sendHTTP(ctx context.Context, client *http.Client, baseURL, sessionID, command, commandID string) (*dpCommandResponse, *Error) {
	if client == nil {
		client = &http.Client{Timeout: commandTimeout(command)}
	}
	url := fmt.Sprintf("%s/agentscope/sessions/%s/%s", strings.TrimRight(baseURL, "/"), sessionID, command)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, nil)
	if err != nil {
		return nil, errFailed("creating request: " + err.Error())
	}
	if commandID != "" {
		req.Header.Set("X-Command-Id", commandID)
	}

	resp, err := client.Do(req)
	if err != nil {
		return nil, errUnreachable("dispatch failed: " + err.Error())
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))

	switch resp.StatusCode {
	case http.StatusOK, http.StatusAccepted:
		out := &dpCommandResponse{Accepted: true}
		if len(body) > 0 {
			_ = json.Unmarshal(body, out)
			if !out.Accepted && out.CommandID == "" && out.Phase == "" {
				// Empty/legacy 200 with no JSON fields — treat as accepted.
				out.Accepted = true
			}
		}
		if out.Result == nil {
			out.Result = json.RawMessage(`{}`)
		}
		return out, nil

	case http.StatusNotFound:
		return nil, mapDPError(http.StatusNotFound, body, CodeNotFound, "session not found on data plane")
	case http.StatusConflict:
		return nil, mapDPError(http.StatusConflict, body, CodeBusy, "session busy on data plane")
	case http.StatusNotImplemented:
		return nil, mapDPError(http.StatusNotImplemented, body, CodeUnsupported, "command not implemented on data plane")
	case http.StatusServiceUnavailable:
		return nil, mapDPError(http.StatusServiceUnavailable, body, CodeUnreachable, "data plane unreachable")
	default:
		return nil, mapDPError(http.StatusInternalServerError, body, CodeFailed,
			fmt.Sprintf("data plane returned status %d", resp.StatusCode))
	}
}

func mapDPError(status int, body []byte, defaultCode, defaultMsg string) *Error {
	var eb dpErrorBody
	_ = json.Unmarshal(body, &eb)
	msg := eb.Error
	if msg == "" {
		msg = defaultMsg
	}
	code := eb.Code
	if code == "" {
		code = defaultCode
	}
	hint := eb.Hint
	if code == CodeBusy && hint == "" {
		hint = HintWaitIdle
	}
	return &Error{Status: status, Code: code, Msg: msg, Hint: hint}
}

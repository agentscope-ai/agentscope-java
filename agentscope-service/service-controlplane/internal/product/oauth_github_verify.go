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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package product

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

type githubAccountStatus struct {
	Login     string `json:"login,omitempty"`
	UserID    int64  `json:"userId,omitempty"`
	Scope     string `json:"scope,omitempty"`
	Status    string `json:"status"`
	ErrorCode string `json:"errorCode,omitempty"`
	ToolCount int    `json:"toolCount"`
	CheckedAt int64  `json:"checkedAt"`
}

func (s *Server) verifyGitHubConnection(c *gin.Context) {
	v, err := s.oauthConnection(c)
	if err != nil {
		writeErr(c, 404, "OAuth connection not found")
		return
	}
	if v.Provider != "github" || !isGitHubMCPEndpoint(v.Endpoint) {
		writeErr(c, 400, "Verification is available for managed GitHub connections")
		return
	}
	if v.CredentialID == nil {
		writeErr(c, 409, "Connect your GitHub account first")
		return
	}
	// Refresh, if needed, stays in the control plane. No bearer token is returned to the UI.
	secret, revision, err := s.refreshOAuthCredential(c.Request.Context(), *v.CredentialID, currentResourceOwner(c))
	status := githubAccountStatus{Status: "reauthorization_required", ErrorCode: "credential_unavailable", CheckedAt: nowMillis()}
	if err == nil {
		var credential struct {
			AccessToken string `json:"access_token"`
		}
		if json.Unmarshal([]byte(secret), &credential) == nil && credential.AccessToken != "" && !strings.ContainsAny(credential.AccessToken, "\r\n") {
			ctx, cancel := context.WithTimeout(c.Request.Context(), 30*time.Second)
			defer cancel()
			status = inspectGitHubAccount(ctx, s.oauthHTTP(), credential.AccessToken, v.Endpoint)
		}
	}
	// Do not publish a stale successful probe after a concurrent disconnect, rotation or reconnect.
	tag, err := s.db.Pool.Exec(c.Request.Context(), `UPDATE mcp_oauth_connections o SET account_json=$1 WHERE connection_id=$2 AND generation=$3 AND credential_id=$4 AND EXISTS(SELECT 1 FROM vault_credentials c WHERE c.credential_id=o.credential_id AND ($5=0 OR c.revision=$5))`, mustJSON(status), v.ID, v.Generation, *v.CredentialID, revision)
	if err != nil || tag.RowsAffected() != 1 {
		writeErr(c, 409, "GitHub connection changed; reload and retry verification")
		return
	}
	c.Header("Cache-Control", "no-store")
	c.JSON(200, status)
}

func githubRequest(ctx context.Context, method, endpoint, token, body string) (*http.Request, error) {
	req, err := http.NewRequestWithContext(ctx, method, endpoint, strings.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "AgentScope-GitHub-Connector")
	return req, nil
}

func githubHTTPError(status int) string {
	switch status {
	case 401:
		return "reauthorization_required"
	case 403:
		return "access_denied"
	case 429:
		return "rate_limited"
	default:
		return "connection_failed"
	}
}

func inspectGitHubAccount(ctx context.Context, client *http.Client, token, endpoint string) githubAccountStatus {
	status := githubAccountStatus{Status: "verification_failed", CheckedAt: nowMillis()}
	if !isGitHubMCPEndpoint(endpoint) {
		status.ErrorCode = "invalid_endpoint"
		return status
	}
	req, _ := githubRequest(ctx, http.MethodGet, "https://api.github.com/user", token, "")
	resp, err := client.Do(req)
	if err != nil {
		status.ErrorCode = "identity_connection_failed"
		return status
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		status.ErrorCode = githubHTTPError(resp.StatusCode)
		return status
	}
	var user struct {
		Login string `json:"login"`
		ID    int64  `json:"id"`
	}
	if json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&user) != nil || user.Login == "" || user.ID <= 0 {
		status.ErrorCode = "invalid_identity_response"
		return status
	}
	status.Login, status.UserID, status.Scope = user.Login, user.ID, resp.Header.Get("X-OAuth-Scopes")
	status.Status = "authorized"
	count, err := githubMCPTools(ctx, client, token, endpoint)
	if err != nil {
		status.ErrorCode = err.Error()
		return status
	}
	status.Status, status.ToolCount = "ready", count
	return status
}

// Probe only initializes a session and lists tools. It never executes a repository tool.
func githubMCPTools(ctx context.Context, client *http.Client, token, endpoint string) (int, error) {
	session, protocol := "", "2025-03-26"
	request := func(method string, id int, params any) (json.RawMessage, error) {
		body := map[string]any{"jsonrpc": "2.0", "method": method, "params": params}
		if id != 0 {
			body["id"] = id
		}
		req, err := githubRequest(ctx, http.MethodPost, endpoint, token, mustJSON(body))
		if err != nil {
			return nil, fmt.Errorf("mcp_request_failed")
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Accept", "application/json, text/event-stream")
		if method != "initialize" {
			req.Header.Set("MCP-Protocol-Version", protocol)
		}
		if session != "" {
			req.Header.Set("Mcp-Session-Id", session)
		}
		resp, err := client.Do(req)
		if err != nil {
			return nil, fmt.Errorf("mcp_connection_failed")
		}
		defer resp.Body.Close()
		if resp.StatusCode < 200 || resp.StatusCode >= 300 {
			return nil, fmt.Errorf("mcp_%s", githubHTTPError(resp.StatusCode))
		}
		if method == "initialize" {
			session = resp.Header.Get("Mcp-Session-Id")
		}
		if id == 0 {
			return nil, nil
		}
		return readMCPProbeResponse(resp, id)
	}
	defer func() {
		if session == "" {
			return
		}
		req, _ := githubRequest(ctx, http.MethodDelete, endpoint, token, "")
		req.Header.Set("Mcp-Session-Id", session)
		req.Header.Set("MCP-Protocol-Version", protocol)
		if resp, err := client.Do(req); err == nil {
			resp.Body.Close()
		}
	}()
	raw, err := request("initialize", 1, map[string]any{"protocolVersion": protocol, "capabilities": map[string]any{}, "clientInfo": map[string]string{"name": "agentscope-connection-check", "version": "1.0"}})
	if err != nil {
		return 0, err
	}
	var init struct {
		ProtocolVersion string `json:"protocolVersion"`
	}
	if json.Unmarshal(raw, &init) != nil || init.ProtocolVersion == "" {
		return 0, fmt.Errorf("mcp_invalid_initialize_response")
	}
	switch init.ProtocolVersion {
	case "2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25":
		protocol = init.ProtocolVersion
	default:
		return 0, fmt.Errorf("mcp_unsupported_protocol")
	}
	if _, err = request("notifications/initialized", 0, map[string]any{}); err != nil {
		return 0, err
	}
	count, cursor := 0, ""
	for page := 0; page < 20; page++ {
		params := map[string]string{}
		if cursor != "" {
			params["cursor"] = cursor
		}
		raw, err = request("tools/list", page+2, params)
		if err != nil {
			return 0, err
		}
		var list struct {
			Tools []struct {
				Name string `json:"name"`
			} `json:"tools"`
			NextCursor string `json:"nextCursor"`
		}
		if json.Unmarshal(raw, &list) != nil || list.Tools == nil {
			return 0, fmt.Errorf("mcp_invalid_tools_response")
		}
		for _, tool := range list.Tools {
			if tool.Name == "" {
				return 0, fmt.Errorf("mcp_invalid_tools_response")
			}
		}
		count += len(list.Tools)
		if list.NextCursor == "" {
			if count == 0 {
				return 0, fmt.Errorf("mcp_no_tools_available")
			}
			return count, nil
		}
		if list.NextCursor == cursor {
			return 0, fmt.Errorf("mcp_invalid_pagination")
		}
		cursor = list.NextCursor
	}
	return 0, fmt.Errorf("mcp_tool_list_limit")
}

func readMCPProbeResponse(resp *http.Response, expectedID int) (json.RawMessage, error) {
	decode := func(data []byte) (json.RawMessage, bool, error) {
		var message struct {
			ID     int             `json:"id"`
			Result json.RawMessage `json:"result"`
			Error  json.RawMessage `json:"error"`
		}
		if json.Unmarshal(data, &message) != nil {
			return nil, false, fmt.Errorf("mcp_invalid_response")
		}
		if message.ID != expectedID {
			return nil, false, nil
		}
		if len(message.Error) > 0 && string(message.Error) != "null" {
			return nil, false, fmt.Errorf("mcp_request_rejected")
		}
		if len(message.Result) == 0 {
			return nil, false, fmt.Errorf("mcp_invalid_response")
		}
		return message.Result, true, nil
	}
	reader := io.LimitReader(resp.Body, 2<<20)
	if strings.HasPrefix(resp.Header.Get("Content-Type"), "text/event-stream") {
		scanner := bufio.NewScanner(reader)
		scanner.Buffer(make([]byte, 4096), 2<<20)
		data := ""
		for scanner.Scan() {
			line := scanner.Text()
			if line == "" && data != "" {
				result, found, err := decode([]byte(data))
				data = ""
				if found || err != nil {
					return result, err
				}
			} else if strings.HasPrefix(line, "data:") {
				data += strings.TrimPrefix(strings.TrimPrefix(line, "data:"), " ") + "\n"
			}
		}
		return nil, fmt.Errorf("mcp_invalid_response")
	}
	data, err := io.ReadAll(reader)
	if err != nil {
		return nil, fmt.Errorf("mcp_invalid_response")
	}
	result, found, err := decode(data)
	if err != nil {
		return nil, err
	}
	if !found {
		return nil, fmt.Errorf("mcp_invalid_response")
	}
	return result, nil
}

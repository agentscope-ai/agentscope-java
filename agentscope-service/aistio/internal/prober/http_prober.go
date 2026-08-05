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

package prober

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// HTTPProber implements DataPlaneProber using HTTP calls to the contract API.
type HTTPProber struct {
	client *http.Client
}

// NewHTTPProber creates a new HTTP-based data plane prober.
func NewHTTPProber() *HTTPProber {
	return &HTTPProber{
		client: &http.Client{
			Timeout: 5 * time.Second,
		},
	}
}

func (p *HTTPProber) ProbeInfo(ctx context.Context, endpoint string) (*DataPlaneInfo, error) {
	url := fmt.Sprintf("%s/agentscope/info", endpoint)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("creating request: %w", err)
	}

	resp, err := p.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("probing %s: %w", url, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("probe %s returned status %d", url, resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("reading response: %w", err)
	}

	var info DataPlaneInfo
	if err := json.Unmarshal(body, &info); err != nil {
		return nil, fmt.Errorf("parsing info response: %w", err)
	}

	return &info, nil
}

func (p *HTTPProber) ProbeHealth(ctx context.Context, endpoint string) (bool, error) {
	url := fmt.Sprintf("%s/agentscope/health", endpoint)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return false, fmt.Errorf("creating request: %w", err)
	}

	resp, err := p.client.Do(req)
	if err != nil {
		return false, nil
	}
	defer resp.Body.Close()

	return resp.StatusCode == http.StatusOK, nil
}

func (p *HTTPProber) ProbeSessions(ctx context.Context, endpoint string) ([]SessionSnapshot, error) {
	result, err := p.ProbeSessionsDetailed(ctx, endpoint)
	if err != nil {
		return nil, err
	}
	return result.Sessions, nil
}

// ProbeSessionsDetailed parses truncation hints (truncated / hasMore) from the
// sessions list response when the data plane provides them.
func (p *HTTPProber) ProbeSessionsDetailed(ctx context.Context, endpoint string) (SessionsProbeResult, error) {
	url := fmt.Sprintf("%s/agentscope/sessions", endpoint)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return SessionsProbeResult{}, fmt.Errorf("creating request: %w", err)
	}

	resp, err := p.client.Do(req)
	if err != nil {
		return SessionsProbeResult{}, fmt.Errorf("fetching sessions: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return SessionsProbeResult{}, fmt.Errorf("sessions probe returned status %d", resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return SessionsProbeResult{}, fmt.Errorf("reading response: %w", err)
	}

	var parsed struct {
		Sessions  []SessionSnapshot `json:"sessions"`
		Truncated bool              `json:"truncated"`
		HasMore   bool              `json:"hasMore"`
	}
	if err := json.Unmarshal(body, &parsed); err != nil {
		return SessionsProbeResult{}, fmt.Errorf("parsing sessions response: %w", err)
	}

	return SessionsProbeResult{
		Sessions:  parsed.Sessions,
		Truncated: parsed.Truncated,
		HasMore:   parsed.HasMore,
	}, nil
}

func (p *HTTPProber) SendCompress(ctx context.Context, endpoint string, sessionID string) error {
	url := fmt.Sprintf("%s/agentscope/sessions/%s/compress", endpoint, sessionID)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, nil)
	if err != nil {
		return fmt.Errorf("creating request: %w", err)
	}

	resp, err := p.client.Do(req)
	if err != nil {
		return fmt.Errorf("sending compress to %s: %w", url, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("compress %s returned status %d", url, resp.StatusCode)
	}

	return nil
}

func (p *HTTPProber) SendTerminate(ctx context.Context, endpoint string, sessionID string) error {
	url := fmt.Sprintf("%s/agentscope/sessions/%s/terminate", endpoint, sessionID)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, nil)
	if err != nil {
		return fmt.Errorf("creating request: %w", err)
	}

	resp, err := p.client.Do(req)
	if err != nil {
		return fmt.Errorf("sending terminate to %s: %w", url, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("terminate %s returned status %d", url, resp.StatusCode)
	}

	return nil
}

func (p *HTTPProber) FetchSessionState(ctx context.Context, endpoint string, sessionID string) (*SessionState, error) {
	url := fmt.Sprintf("%s/agentscope/sessions/%s/state", endpoint, sessionID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("creating request: %w", err)
	}

	resp, err := p.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("fetching session state: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("session state returned status %d", resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("reading response: %w", err)
	}

	var state SessionState
	if err := json.Unmarshal(body, &state); err != nil {
		return nil, fmt.Errorf("parsing session state response: %w", err)
	}

	return &state, nil
}

// getJSON issues a GET request and decodes a 200 JSON response into out.
// A 404 is returned as ErrNotFoundOnDataPlane so callers can distinguish
// "session unknown" from transport failures.
func (p *HTTPProber) getJSON(ctx context.Context, url string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return fmt.Errorf("creating request: %w", err)
	}

	resp, err := p.client.Do(req)
	if err != nil {
		return fmt.Errorf("fetching %s: %w", url, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return ErrNotFoundOnDataPlane
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("GET %s returned status %d", url, resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("reading response: %w", err)
	}
	if err := json.Unmarshal(body, out); err != nil {
		return fmt.Errorf("parsing response from %s: %w", url, err)
	}
	return nil
}

func (p *HTTPProber) FetchContext(ctx context.Context, endpoint string, sessionID string) (*ContextSnapshot, error) {
	url := fmt.Sprintf("%s/agentscope/sessions/%s/context", endpoint, sessionID)
	var snap ContextSnapshot
	if err := p.getJSON(ctx, url, &snap); err != nil {
		return nil, err
	}
	return &snap, nil
}

func (p *HTTPProber) FetchMessages(ctx context.Context, endpoint string, sessionID string, offset, limit int) (*MessagePage, error) {
	url := fmt.Sprintf("%s/agentscope/sessions/%s/messages?offset=%d&limit=%d", endpoint, sessionID, offset, limit)
	var page MessagePage
	if err := p.getJSON(ctx, url, &page); err != nil {
		return nil, err
	}
	return &page, nil
}

func (p *HTTPProber) FetchSubagents(ctx context.Context, endpoint string) ([]SubagentInfo, error) {
	url := fmt.Sprintf("%s/agentscope/subagents", endpoint)
	var result struct {
		Subagents []SubagentInfo `json:"subagents"`
	}
	if err := p.getJSON(ctx, url, &result); err != nil {
		return nil, err
	}
	return result.Subagents, nil
}

func (p *HTTPProber) FetchWorkspaces(ctx context.Context, endpoint string) ([]WorkspaceInfo, error) {
	url := fmt.Sprintf("%s/agentscope/workspaces", endpoint)
	var result struct {
		Workspaces []WorkspaceInfo `json:"workspaces"`
	}
	if err := p.getJSON(ctx, url, &result); err != nil {
		return nil, err
	}
	return result.Workspaces, nil
}

func (p *HTTPProber) FetchTasks(ctx context.Context, endpoint string, sessionID string) ([]TaskInfo, error) {
	url := fmt.Sprintf("%s/agentscope/sessions/%s/tasks", endpoint, sessionID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("creating request: %w", err)
	}
	resp, err := p.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("fetching %s: %w", url, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return nil, ErrNotFoundOnDataPlane
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GET %s returned status %d", url, resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("reading response: %w", err)
	}
	var wrapped struct {
		Tasks []TaskInfo `json:"tasks"`
	}
	if err := json.Unmarshal(body, &wrapped); err == nil && wrapped.Tasks != nil {
		return wrapped.Tasks, nil
	}
	var bare []TaskInfo
	if err := json.Unmarshal(body, &bare); err == nil {
		return bare, nil
	}
	return nil, fmt.Errorf("parsing tasks response from %s", url)
}

func (p *HTTPProber) FetchSubagentTasks(ctx context.Context, endpoint string, sessionID string) ([]SubagentTaskInfo, error) {
	url := fmt.Sprintf("%s/agentscope/sessions/%s/subagent-tasks", endpoint, sessionID)
	var result struct {
		Tasks []SubagentTaskInfo `json:"tasks"`
	}
	if err := p.getJSON(ctx, url, &result); err != nil {
		return nil, err
	}
	return result.Tasks, nil
}

func (p *HTTPProber) CancelSubagentTask(ctx context.Context, endpoint string, sessionID, taskID string) error {
	url := fmt.Sprintf("%s/agentscope/sessions/%s/subagent-tasks/%s", endpoint, sessionID, taskID)
	req, err := http.NewRequestWithContext(ctx, http.MethodDelete, url, nil)
	if err != nil {
		return fmt.Errorf("creating request: %w", err)
	}
	resp, err := p.client.Do(req)
	if err != nil {
		return fmt.Errorf("DELETE %s: %w", url, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return ErrNotFoundOnDataPlane
	}
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusNoContent {
		return fmt.Errorf("DELETE %s returned status %d", url, resp.StatusCode)
	}
	return nil
}

func (p *HTTPProber) SendPlanMode(ctx context.Context, endpoint string, sessionID string, active bool) error {
	url := fmt.Sprintf("%s/agentscope/sessions/%s/plan-mode", endpoint, sessionID)
	body, err := json.Marshal(map[string]bool{"active": active})
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("creating request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := p.client.Do(req)
	if err != nil {
		return fmt.Errorf("POST %s: %w", url, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusAccepted {
		return fmt.Errorf("POST %s returned status %d", url, resp.StatusCode)
	}
	return nil
}

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

package connector_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/spring-ai-alibaba/aistio/connector"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
)

// stubProvider implements connector.ContractProvider against in-memory fixtures.
type stubProvider struct {
	info       *prober.DataPlaneInfo
	sessions   []prober.SessionSnapshot
	states     map[string]*prober.SessionState
	contexts   map[string]*prober.ContextSnapshot
	pages      map[string]*prober.MessagePage
	subagents  []prober.SubagentInfo
	workspaces []prober.WorkspaceInfo
	compressed []string
	terminated []string
}

func (s *stubProvider) Info() *prober.DataPlaneInfo { return s.info }

func (s *stubProvider) Sessions() ([]prober.SessionSnapshot, error) { return s.sessions, nil }

func (s *stubProvider) SessionState(id string) (*prober.SessionState, error) {
	if st, ok := s.states[id]; ok {
		return st, nil
	}
	return nil, connector.ErrNotFound
}

func (s *stubProvider) Context(id string) (*prober.ContextSnapshot, error) {
	if c, ok := s.contexts[id]; ok {
		return c, nil
	}
	return nil, connector.ErrNotFound
}

func (s *stubProvider) Messages(id string, offset, limit int) (*prober.MessagePage, error) {
	if p, ok := s.pages[id]; ok {
		p.Offset, p.Limit = offset, limit
		return p, nil
	}
	return nil, connector.ErrNotFound
}

func (s *stubProvider) Subagents() ([]prober.SubagentInfo, error) { return s.subagents, nil }

func (s *stubProvider) Workspaces() ([]prober.WorkspaceInfo, error) { return s.workspaces, nil }

func (s *stubProvider) Compress(id string) error {
	if _, ok := s.states[id]; !ok {
		return connector.ErrNotFound
	}
	s.compressed = append(s.compressed, id)
	return nil
}

func (s *stubProvider) Terminate(id string) error {
	if _, ok := s.states[id]; !ok {
		return connector.ErrNotFound
	}
	s.terminated = append(s.terminated, id)
	return nil
}

func newStubProvider() *stubProvider {
	return &stubProvider{
		info: &prober.DataPlaneInfo{
			Name:          "stub-agent",
			Runtime:       "agentscope-go",
			ContractLevel: 3,
			Capabilities:  []string{"session-reporting", "context-query", "message-query"},
		},
		sessions: []prober.SessionSnapshot{
			{ID: "s1", Phase: "active", MessageCount: 5, ContextHash: "h1"},
		},
		states: map[string]*prober.SessionState{
			"s1": {SessionID: "s1", Summary: "test session"},
		},
		contexts: map[string]*prober.ContextSnapshot{
			"s1": {
				SessionID:   "s1",
				ContextHash: "h1",
				Messages:    []prober.ContextMessage{{Role: "user", Content: "hi"}},
			},
		},
		pages: map[string]*prober.MessagePage{
			"s1": {
				SessionID: "s1",
				Total:     1,
				Messages:  []prober.MessageItem{{Seq: 1, Role: "user", Content: "hi"}},
			},
		},
		subagents:  []prober.SubagentInfo{{Name: "researcher", InvokeCount: 2}},
		workspaces: []prober.WorkspaceInfo{{Path: "/tmp/ws", Mode: "shared"}},
	}
}

// TestContractServerRoundTrip starts a ContractServer and reads every endpoint
// back through the control-plane prober, validating shape compatibility.
func TestContractServerRoundTrip(t *testing.T) {
	provider := newStubProvider()
	srv := connector.NewContractServer("127.0.0.1:0", provider)
	if err := srv.Start(); err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = srv.Stop(ctx)
	}()

	endpoint := "http://" + srv.Addr()
	p := prober.NewHTTPProber()
	ctx := context.Background()

	info, err := p.ProbeInfo(ctx, endpoint)
	if err != nil {
		t.Fatalf("ProbeInfo: %v", err)
	}
	if info.Name != "stub-agent" || info.ContractLevel != 3 || len(info.Capabilities) != 3 {
		t.Errorf("info = %+v", info)
	}

	healthy, err := p.ProbeHealth(ctx, endpoint)
	if err != nil || !healthy {
		t.Errorf("ProbeHealth = %v, %v", healthy, err)
	}

	sessions, err := p.ProbeSessions(ctx, endpoint)
	if err != nil {
		t.Fatalf("ProbeSessions: %v", err)
	}
	if len(sessions) != 1 || sessions[0].ID != "s1" || sessions[0].ContextHash != "h1" {
		t.Errorf("sessions = %+v", sessions)
	}

	state, err := p.FetchSessionState(ctx, endpoint, "s1")
	if err != nil {
		t.Fatalf("FetchSessionState: %v", err)
	}
	if state.Summary != "test session" {
		t.Errorf("state = %+v", state)
	}

	snap, err := p.FetchContext(ctx, endpoint, "s1")
	if err != nil {
		t.Fatalf("FetchContext: %v", err)
	}
	if snap.ContextHash != "h1" || len(snap.Messages) != 1 {
		t.Errorf("context = %+v", snap)
	}

	page, err := p.FetchMessages(ctx, endpoint, "s1", 0, 10)
	if err != nil {
		t.Fatalf("FetchMessages: %v", err)
	}
	if page.Total != 1 || len(page.Messages) != 1 || page.Messages[0].Content != "hi" {
		t.Errorf("page = %+v", page)
	}

	subs, err := p.FetchSubagents(ctx, endpoint)
	if err != nil {
		t.Fatalf("FetchSubagents: %v", err)
	}
	if len(subs) != 1 || subs[0].Name != "researcher" {
		t.Errorf("subagents = %+v", subs)
	}

	workspaces, err := p.FetchWorkspaces(ctx, endpoint)
	if err != nil {
		t.Fatalf("FetchWorkspaces: %v", err)
	}
	if len(workspaces) != 1 || workspaces[0].Path != "/tmp/ws" {
		t.Errorf("workspaces = %+v", workspaces)
	}

	if err := p.SendCompress(ctx, endpoint, "s1"); err != nil {
		t.Fatalf("SendCompress: %v", err)
	}
	if err := p.SendTerminate(ctx, endpoint, "s1"); err != nil {
		t.Fatalf("SendTerminate: %v", err)
	}
	if len(provider.compressed) != 1 || provider.compressed[0] != "s1" {
		t.Errorf("compressed = %v", provider.compressed)
	}
	if len(provider.terminated) != 1 || provider.terminated[0] != "s1" {
		t.Errorf("terminated = %v", provider.terminated)
	}
}

// TestContractServerNotFound verifies provider ErrNotFound maps to HTTP 404,
// which the prober surfaces as ErrNotFoundOnDataPlane.
func TestContractServerNotFound(t *testing.T) {
	srv := connector.NewContractServer("127.0.0.1:0", newStubProvider())
	if err := srv.Start(); err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = srv.Stop(ctx)
	}()

	endpoint := "http://" + srv.Addr()
	p := prober.NewHTTPProber()
	ctx := context.Background()

	if _, err := p.FetchContext(ctx, endpoint, "missing"); !errors.Is(err, prober.ErrNotFoundOnDataPlane) {
		t.Errorf("FetchContext missing: %v", err)
	}
	if _, err := p.FetchMessages(ctx, endpoint, "missing", 0, 10); !errors.Is(err, prober.ErrNotFoundOnDataPlane) {
		t.Errorf("FetchMessages missing: %v", err)
	}
	if err := p.SendCompress(ctx, endpoint, "missing"); err == nil {
		t.Error("expected error for compress on missing session")
	}
}

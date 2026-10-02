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
	"net/http"
	"testing"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/test/mock"
)

// CP behavior matrix: contract-level and capability conformance via memory store + Router + mock DP.

func TestMatrix_Level1_AppearsInRegistry_SessionsNotRequired(t *testing.T) {
	dp := mock.NewMockDataPlane(1)
	defer dp.Close()
	dp.SetCapabilities(nil) // Level 1: discovery only

	p := prober.NewHTTPProber()
	info, err := p.ProbeInfo(context.Background(), dp.Endpoint())
	if err != nil {
		t.Fatalf("ProbeInfo: %v", err)
	}
	if info.ContractLevel != 1 {
		t.Fatalf("expected contractLevel 1, got %d", info.ContractLevel)
	}

	reg := dataplane.NewRegistry()
	reg.Upsert(dataplane.Entry{
		AgentName:     "agent-l1",
		Namespace:     "default",
		InstanceID:    "inst-l1",
		BaseURL:       dp.Endpoint(),
		ContractLevel: info.ContractLevel,
		Capabilities:  info.Capabilities,
		Runtime:       info.Runtime,
	})

	list := reg.List()
	if len(list) != 1 || list[0].InstanceID != "inst-l1" {
		t.Fatalf("Level 1 instance must appear in registry, got %+v", list)
	}
	summaries := reg.AggregateAgents()
	if len(summaries) != 1 || summaries[0].ContractLevel != 1 {
		t.Fatalf("Level 1 agent must appear in aggregate list, got %+v", summaries)
	}

	// Sessions are not required for Level 1 — empty list is fine; poller would skip.
	sessions, err := p.ProbeSessions(context.Background(), dp.Endpoint())
	if err != nil {
		t.Fatalf("ProbeSessions: %v", err)
	}
	if len(sessions) != 0 {
		t.Fatalf("Level 1 need not advertise sessions, got %d", len(sessions))
	}
}

func TestMatrix_Level2_HasSessions_CompressUnsupported(t *testing.T) {
	dp := mock.NewMockDataPlane(2)
	defer dp.Close()
	dp.SetCapabilities([]string{v1alpha1.CapabilitySessionReporting})
	dp.AddSession(prober.SessionSnapshot{ID: "sess-l2", Phase: "Idle", MessageCount: 3})

	p := prober.NewHTTPProber()
	sessions, err := p.ProbeSessions(context.Background(), dp.Endpoint())
	if err != nil {
		t.Fatalf("ProbeSessions: %v", err)
	}
	if len(sessions) != 1 {
		t.Fatalf("Level 2 must expose sessions, got %d", len(sessions))
	}

	st := newTestStore(t)
	reg := dataplane.NewRegistry()
	reg.Upsert(dataplane.Entry{
		AgentName:     "agent-l2",
		Namespace:     "default",
		InstanceID:    "inst-l2",
		BaseURL:       dp.Endpoint(),
		ContractLevel: 2,
		Capabilities:  []string{v1alpha1.CapabilitySessionReporting}, // no session-command
	})
	sess, _ := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "sess-l2", AgentName: "agent-l2", Namespace: "default",
		Framework: "test", Phase: store.SessionPhaseIdle, Busy: boolPtr(false), InstanceRef: "inst-l2",
	})

	r := NewRouter(reg, st, p, nil)
	_, err = r.Execute(context.Background(), sess, Request{Command: CommandCompress, Operator: "tester"})
	opErr, ok := AsError(err)
	if !ok || opErr.Code != CodeUnsupported || opErr.Status != http.StatusNotImplemented {
		t.Fatalf("Level 2 compress must be unsupported (no session-command), got %v", err)
	}
	if dp.CompressCalledFor("sess-l2") {
		t.Fatal("CP must not hit DP compress without session-command")
	}
}

func TestMatrix_Level3_WithoutTaskQuery_NoPretendSupport(t *testing.T) {
	dp := mock.NewMockDataPlane(3)
	defer dp.Close()
	dp.SetCapabilities([]string{
		v1alpha1.CapabilitySessionReporting,
		v1alpha1.CapabilitySessionCommand,
		// intentionally no task-query
	})

	// DP endpoint itself returns 501 when capability undeclared.
	resp, err := http.Get(dp.Endpoint() + "/agentscope/sessions/sess-1/tasks")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNotImplemented {
		t.Fatalf("DP tasks without task-query must return 501, got %d", resp.StatusCode)
	}

	// CP must not pretend support: capability gate before probe.
	reg := dataplane.NewRegistry()
	reg.Upsert(dataplane.Entry{
		AgentName: "agent-l3", Namespace: "default", InstanceID: "inst-l3",
		BaseURL: dp.Endpoint(), ContractLevel: 3,
		Capabilities: []string{v1alpha1.CapabilitySessionCommand},
	})
	entry := reg.Get("inst-l3")
	for _, c := range entry.Capabilities {
		if c == v1alpha1.CapabilityTaskQuery {
			t.Fatal("CP must not see task-query when undeclared")
		}
	}
	// Mirror session_handler gate: without capability → 501 unsupported; do not call FetchTasks.
}

func TestMatrix_DeclaredBut501_HandledAsUnsupported(t *testing.T) {
	dp := mock.NewMockDataPlane(3)
	defer dp.Close()
	dp.SetCapabilities([]string{v1alpha1.CapabilitySessionCommand})
	dp.InjectFault501(v1alpha1.CapabilitySessionCommand)

	st := newTestStore(t)
	reg := dataplane.NewRegistry()
	reg.Upsert(dataplane.Entry{
		AgentName: "agent-a", Namespace: "default", InstanceID: "inst-1",
		BaseURL: dp.Endpoint(), ContractLevel: 3,
		Capabilities: []string{v1alpha1.CapabilitySessionCommand},
	})
	sess, _ := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "sess-1", AgentName: "agent-a", Namespace: "default",
		Framework: "test", Phase: store.SessionPhaseIdle, Busy: boolPtr(false), InstanceRef: "inst-1",
	})

	r := NewRouter(reg, st, prober.NewHTTPProber(), nil)
	_, err := r.Execute(context.Background(), sess, Request{Command: CommandCompress, Operator: "tester"})
	opErr, ok := AsError(err)
	if !ok || opErr.Code != CodeUnsupported || opErr.Status != http.StatusNotImplemented {
		t.Fatalf("declared-but-501 must map to unsupported, got %v", err)
	}
}

func TestMatrix_Returns409_WaitIdlePropagated(t *testing.T) {
	dp := mock.NewMockDataPlane(3)
	defer dp.Close()
	dp.SetCapabilities([]string{v1alpha1.CapabilitySessionCommand})
	dp.InjectFault409Compress()

	st := newTestStore(t)
	reg := dataplane.NewRegistry()
	reg.Upsert(dataplane.Entry{
		AgentName: "agent-a", Namespace: "default", InstanceID: "inst-1",
		BaseURL: dp.Endpoint(), ContractLevel: 3,
		Capabilities: []string{v1alpha1.CapabilitySessionCommand},
	})
	sess, _ := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "sess-1", AgentName: "agent-a", Namespace: "default",
		Framework: "test", Phase: store.SessionPhaseIdle, Busy: boolPtr(false), InstanceRef: "inst-1",
	})

	r := NewRouter(reg, st, prober.NewHTTPProber(), nil)
	_, err := r.Execute(context.Background(), sess, Request{Command: CommandCompress, Operator: "tester"})
	opErr, ok := AsError(err)
	if !ok || opErr.Code != CodeBusy || opErr.Hint != HintWaitIdle || opErr.Status != http.StatusConflict {
		t.Fatalf("expected busy/wait_idle 409, got %v", err)
	}
}

func TestMatrix_StaleInstance_UnreachableNoSiblingHit(t *testing.T) {
	staleDP := mock.NewMockDataPlane(3)
	defer staleDP.Close()
	staleDP.SetCapabilities([]string{
		v1alpha1.CapabilitySessionCommand,
		v1alpha1.CapabilitySessionAbort,
	})

	siblingDP := mock.NewMockDataPlane(3)
	defer siblingDP.Close()
	siblingDP.SetCapabilities([]string{
		v1alpha1.CapabilitySessionCommand,
		v1alpha1.CapabilitySessionAbort,
	})

	st := newTestStore(t)
	reg := dataplane.NewRegistry()
	reg.Upsert(dataplane.Entry{
		AgentName: "agent-a", Namespace: "default", InstanceID: "inst-stale",
		BaseURL: staleDP.Endpoint(), ContractLevel: 3,
		Capabilities: []string{v1alpha1.CapabilitySessionCommand, v1alpha1.CapabilitySessionAbort},
	})
	reg.Upsert(dataplane.Entry{
		AgentName: "agent-a", Namespace: "default", InstanceID: "inst-sibling",
		BaseURL: siblingDP.Endpoint(), ContractLevel: 3,
		Capabilities: []string{v1alpha1.CapabilitySessionCommand, v1alpha1.CapabilitySessionAbort},
	})
	// Flip stale after Upsert (which sets Healthy=true).
	reg.MarkStale(reg.Get("inst-stale").LastSeenAt.Add(dataplane.StaleAfter + 1))

	sess, _ := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "sess-1", AgentName: "agent-a", Namespace: "default",
		Framework: "test", Phase: store.SessionPhaseActive, Busy: boolPtr(true), InstanceRef: "inst-stale",
	})

	r := NewRouter(reg, st, prober.NewHTTPProber(), nil)

	_, err := r.Execute(context.Background(), sess, Request{Command: CommandCompress, Force: true, Operator: "tester"})
	opErr, ok := AsError(err)
	if !ok || opErr.Code != CodeUnreachable || opErr.Status != http.StatusServiceUnavailable {
		t.Fatalf("stale compress expected 503 unreachable, got %v", err)
	}

	_, err = r.Execute(context.Background(), sess, Request{Command: CommandAbort, Operator: "tester"})
	opErr, ok = AsError(err)
	if !ok || opErr.Code != CodeUnreachable || opErr.Status != http.StatusServiceUnavailable {
		t.Fatalf("stale abort expected 503 unreachable, got %v", err)
	}

	if staleDP.CompressCalledFor("sess-1") || staleDP.AbortCalledFor("sess-1") {
		t.Fatal("stale instance must not be contacted")
	}
	if siblingDP.CompressCalledFor("sess-1") || siblingDP.AbortCalledFor("sess-1") {
		t.Fatal("must NOT fall back to sibling while session is active")
	}
}

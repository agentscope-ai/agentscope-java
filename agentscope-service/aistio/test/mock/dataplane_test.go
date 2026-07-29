package mock

import (
	"context"
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/prober"
)

func TestMockDataPlane(t *testing.T) {
	mock := NewMockDataPlane(3)
	mock.AddSession(prober.SessionSnapshot{
		ID: "sess-1", Phase: "Active", MessageCount: 10,
	})
	defer mock.Close()

	p := prober.NewHTTPProber()

	// Test ProbeInfo
	info, err := p.ProbeInfo(context.Background(), mock.Endpoint())
	if err != nil {
		t.Fatalf("ProbeInfo: %v", err)
	}
	if info.ContractLevel != 3 {
		t.Errorf("expected contractLevel 3, got %d", info.ContractLevel)
	}
	if info.Name != "mock-agent" {
		t.Errorf("expected name mock-agent, got %s", info.Name)
	}

	// Test ProbeHealth
	healthy, err := p.ProbeHealth(context.Background(), mock.Endpoint())
	if err != nil {
		t.Fatalf("ProbeHealth: %v", err)
	}
	if !healthy {
		t.Error("expected healthy")
	}

	// Test ProbeSessions
	sessions, err := p.ProbeSessions(context.Background(), mock.Endpoint())
	if err != nil {
		t.Fatalf("ProbeSessions: %v", err)
	}
	if len(sessions) != 1 {
		t.Fatalf("expected 1 session, got %d", len(sessions))
	}
	if sessions[0].ID != "sess-1" {
		t.Errorf("expected session ID sess-1, got %s", sessions[0].ID)
	}
	if sessions[0].MessageCount != 10 {
		t.Errorf("expected messageCount 10, got %d", sessions[0].MessageCount)
	}

	// Test SendCompress
	err = p.SendCompress(context.Background(), mock.Endpoint(), "sess-1")
	if err != nil {
		t.Fatalf("SendCompress: %v", err)
	}
	if !mock.CompressCalledFor("sess-1") {
		t.Error("expected compress to be recorded for sess-1")
	}

	// Test SendTerminate
	err = p.SendTerminate(context.Background(), mock.Endpoint(), "sess-1")
	if err != nil {
		t.Fatalf("SendTerminate: %v", err)
	}
	if !mock.TerminateCalledFor("sess-1") {
		t.Error("expected terminate to be recorded for sess-1")
	}
}

func TestMockDataPlaneSessionState(t *testing.T) {
	mock := NewMockDataPlane(2)
	defer mock.Close()

	mock.SetSessionState("sess-1", &prober.SessionState{
		SessionID:   "sess-1",
		Summary:     "test session",
		CurrentIter: 5,
		ContextPressure: &prober.ContextPressureInfo{
			UsedTokens: 1000,
			MaxTokens:  4096,
			Ratio:      0.24,
		},
	})

	p := prober.NewHTTPProber()
	state, err := p.FetchSessionState(context.Background(), mock.Endpoint(), "sess-1")
	if err != nil {
		t.Fatalf("FetchSessionState: %v", err)
	}
	if state.SessionID != "sess-1" {
		t.Errorf("expected sessionId sess-1, got %s", state.SessionID)
	}
	if state.CurrentIter != 5 {
		t.Errorf("expected currentIter 5, got %d", state.CurrentIter)
	}
	if state.ContextPressure == nil || state.ContextPressure.UsedTokens != 1000 {
		t.Errorf("unexpected context pressure: %+v", state.ContextPressure)
	}
}

func TestMockDataPlaneEmptySessions(t *testing.T) {
	mock := NewMockDataPlane(1)
	defer mock.Close()

	p := prober.NewHTTPProber()
	sessions, err := p.ProbeSessions(context.Background(), mock.Endpoint())
	if err != nil {
		t.Fatalf("ProbeSessions: %v", err)
	}
	if len(sessions) != 0 {
		t.Errorf("expected 0 sessions, got %d", len(sessions))
	}
}

func TestMockDataPlaneMultipleSessions(t *testing.T) {
	mock := NewMockDataPlane(3)
	defer mock.Close()

	mock.AddSession(prober.SessionSnapshot{ID: "s1", Phase: "Active", MessageCount: 5})
	mock.AddSession(prober.SessionSnapshot{ID: "s2", Phase: "Idle", MessageCount: 3})
	mock.AddSession(prober.SessionSnapshot{ID: "s3", Phase: "Terminated", MessageCount: 20})

	p := prober.NewHTTPProber()
	sessions, err := p.ProbeSessions(context.Background(), mock.Endpoint())
	if err != nil {
		t.Fatalf("ProbeSessions: %v", err)
	}
	if len(sessions) != 3 {
		t.Fatalf("expected 3 sessions, got %d", len(sessions))
	}
}

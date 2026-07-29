package storetest

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// RunSuite exercises the full Store contract against s.
func RunSuite(t *testing.T, s store.Store) {
	t.Helper()
	ctx := context.Background()

	t.Run("Sessions", func(t *testing.T) { testSessions(t, ctx, s) })
	t.Run("Events", func(t *testing.T) { testEvents(t, ctx, s) })
	t.Run("ContextSnapshots", func(t *testing.T) { testContexts(t, ctx, s) })
	t.Run("Metrics", func(t *testing.T) { testMetrics(t, ctx, s) })
	t.Run("TeamMessages", func(t *testing.T) { testMessages(t, ctx, s) })
	t.Run("TeamTasks", func(t *testing.T) { testTasks(t, ctx, s) })
}

func testSessions(t *testing.T, ctx context.Context, s store.Store) {
	now := time.Now().UTC()
	sess, err := s.Sessions().Upsert(ctx, &store.Session{
		SessionID: "sess-1", AgentName: "agent-a", Namespace: "default",
		Framework: "claude-agent-sdk", Phase: store.SessionPhaseActive,
		TeamID: "team-1", TeamRole: "lead",
		StartedAt: &now, LastActiveAt: &now,
	})
	if err != nil {
		t.Fatalf("upsert: %v", err)
	}
	if sess.ID.String() == "" {
		t.Fatal("expected uuid")
	}

	got, err := s.Sessions().Get(ctx, "agent-a", "default", "sess-1")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if got.TeamID != "team-1" || got.Framework != "claude-agent-sdk" {
		t.Fatalf("unexpected get: %+v", got)
	}

	// Upsert updates phase.
	_, err = s.Sessions().Upsert(ctx, &store.Session{
		SessionID: "sess-1", AgentName: "agent-a", Namespace: "default",
		Phase: store.SessionPhaseIdle, Framework: "claude-agent-sdk",
	})
	if err != nil {
		t.Fatalf("upsert2: %v", err)
	}
	got, _ = s.Sessions().Get(ctx, "agent-a", "default", "sess-1")
	if got.Phase != store.SessionPhaseIdle {
		t.Fatalf("phase=%s", got.Phase)
	}

	_, err = s.Sessions().Upsert(ctx, &store.Session{
		SessionID: "sess-2", AgentName: "agent-a", Namespace: "default",
		Framework: "langgraph", Phase: store.SessionPhaseActive, TeamID: "team-1", TeamRole: "member",
	})
	if err != nil {
		t.Fatalf("upsert3: %v", err)
	}

	list, err := s.Sessions().List(ctx, store.SessionFilter{AgentName: "agent-a", Namespace: "default", TeamID: "team-1"})
	if err != nil || len(list) != 2 {
		t.Fatalf("list team: %v len=%d", err, len(list))
	}

	n, err := s.Sessions().CountActive(ctx, "agent-a", "default")
	if err != nil || n != 2 {
		t.Fatalf("count active: %v n=%d", err, n)
	}

	// TerminateMissing: keep sess-1, terminate sess-2 (make it old via UpdatePhase + TerminateMissing with 0 age).
	// First create an old session by using olderThan=0 so anything not in keep is terminated.
	terminated, err := s.Sessions().TerminateMissing(ctx, "agent-a", "default", []string{"sess-1"}, 0)
	if err != nil {
		t.Fatalf("terminate missing: %v", err)
	}
	if terminated < 1 {
		t.Fatalf("expected >=1 terminated, got %d", terminated)
	}
	got2, _ := s.Sessions().Get(ctx, "agent-a", "default", "sess-2")
	if got2.Phase != store.SessionPhaseTerminated {
		t.Fatalf("sess-2 phase=%s", got2.Phase)
	}

	if err := s.Sessions().UpdatePhase(ctx, sess.ID, store.SessionPhaseCompressing); err != nil {
		t.Fatalf("update phase: %v", err)
	}

	_, err = s.Sessions().Get(ctx, "nope", "default", "x")
	if !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("expected ErrNotFound, got %v", err)
	}
}

func testEvents(t *testing.T, ctx context.Context, s store.Store) {
	sess, err := s.Sessions().Upsert(ctx, &store.Session{
		SessionID: "evt-sess", AgentName: "agent-e", Namespace: "ns", Framework: "adk", Phase: store.SessionPhaseActive,
	})
	if err != nil {
		t.Fatalf("upsert: %v", err)
	}
	e1 := &store.SessionEvent{SessionFK: sess.ID, Seq: 1, EventType: "message", Role: "user", Content: "hi"}
	if err := s.Events().Append(ctx, e1); err != nil {
		t.Fatalf("append1: %v", err)
	}
	e2 := &store.SessionEvent{SessionFK: sess.ID, Seq: 2, EventType: "tool_call", ToolName: "bash"}
	if err := s.Events().Append(ctx, e2); err != nil {
		t.Fatalf("append2: %v", err)
	}
	// Duplicate seq should conflict (memory) or unique-violation (postgres).
	err = s.Events().Append(ctx, &store.SessionEvent{SessionFK: sess.ID, Seq: 1, EventType: "message"})
	if err == nil {
		t.Fatal("expected conflict on duplicate seq")
	}

	all, err := s.Events().List(ctx, sess.ID)
	if err != nil || len(all) != 2 {
		t.Fatalf("list: %v len=%d", err, len(all))
	}
	filtered, err := s.Events().List(ctx, sess.ID, store.WithEventType("tool_call"))
	if err != nil || len(filtered) != 1 {
		t.Fatalf("filter: %v len=%d", err, len(filtered))
	}
}

func testContexts(t *testing.T, ctx context.Context, s store.Store) {
	sess, _ := s.Sessions().Upsert(ctx, &store.Session{
		SessionID: "ctx-sess", AgentName: "agent-c", Namespace: "ns", Framework: "openclaw", Phase: store.SessionPhaseActive,
	})
	msgs, _ := json.Marshal([]map[string]string{{"role": "user", "content": "hello"}})
	changed, err := s.ContextSnapshots().PutIfChanged(ctx, &store.ContextSnapshot{
		SessionFK: sess.ID, ContextHash: "abc123", Messages: msgs, Framework: "openclaw",
	})
	if err != nil || !changed {
		t.Fatalf("put1: changed=%v err=%v", changed, err)
	}
	changed, err = s.ContextSnapshots().PutIfChanged(ctx, &store.ContextSnapshot{
		SessionFK: sess.ID, ContextHash: "abc123", Messages: msgs, Framework: "openclaw",
	})
	if err != nil || changed {
		t.Fatalf("put2 dedup: changed=%v err=%v", changed, err)
	}
	latest, err := s.ContextSnapshots().Latest(ctx, sess.ID)
	if err != nil || latest.ContextHash != "abc123" {
		t.Fatalf("latest: %v %+v", err, latest)
	}
}

func testMetrics(t *testing.T, ctx context.Context, s store.Store) {
	sess, _ := s.Sessions().Upsert(ctx, &store.Session{
		SessionID: "met-sess", AgentName: "agent-m", Namespace: "ns", Framework: "x", Phase: store.SessionPhaseActive,
	})
	fk := sess.ID
	if err := s.Metrics().RecordSnapshot(ctx, &store.SessionSnapshot{
		SessionFK: sess.ID, MessageCount: 3, PromptTokens: 100, CompletionTokens: 50,
		TotalTokens: 150, ContextPressure: 0.4,
	}); err != nil {
		t.Fatalf("snapshot: %v", err)
	}
	if err := s.Metrics().RecordTokenUsage(ctx, &store.TokenUsageMetric{
		SessionFK: &fk, AgentName: "agent-m", Namespace: "ns", Model: "gpt-4",
		PromptTokens: 100, CompletionTokens: 50, TotalTokens: 150,
	}); err != nil {
		t.Fatalf("token: %v", err)
	}
	if err := s.Metrics().RecordAgentMetric(ctx, &store.AgentMetric{
		AgentName: "agent-m", Namespace: "ns", ActiveSessions: 1,
	}); err != nil {
		t.Fatalf("agent metric: %v", err)
	}
	rows, err := s.Metrics().QueryTokenUsage(ctx, store.TokenFilter{AgentName: "agent-m", Namespace: "ns"})
	if err != nil || len(rows) != 1 {
		t.Fatalf("query: %v len=%d", err, len(rows))
	}
}

func testMessages(t *testing.T, ctx context.Context, s store.Store) {
	msg := &store.TeamMessage{
		TeamName: "t1", Namespace: "ns", FromMember: "lead", ToMember: "worker", Content: "hello",
	}
	if err := s.TeamMessages().Send(ctx, msg); err != nil {
		t.Fatalf("send: %v", err)
	}
	if msg.ID == 0 {
		t.Fatal("expected id")
	}
	pending, err := s.TeamMessages().ListPending(ctx, "t1", "ns")
	if err != nil || len(pending) != 1 {
		t.Fatalf("pending: %v len=%d", err, len(pending))
	}
	all, err := s.TeamMessages().ListPendingAll(ctx, 10)
	if err != nil || len(all) < 1 {
		t.Fatalf("pending all: %v", err)
	}
	if err := s.TeamMessages().IncrementAttempts(ctx, msg.ID); err != nil {
		t.Fatalf("inc: %v", err)
	}
	if err := s.TeamMessages().MarkDelivered(ctx, msg.ID); err != nil {
		t.Fatalf("deliver: %v", err)
	}
	pending, _ = s.TeamMessages().ListPending(ctx, "t1", "ns")
	if len(pending) != 0 {
		t.Fatalf("expected empty pending, got %d", len(pending))
	}
	hist, err := s.TeamMessages().History(ctx, "t1", "ns", 10)
	if err != nil || len(hist) != 1 || !hist[0].Delivered {
		t.Fatalf("history: %v %+v", err, hist)
	}
}

func testTasks(t *testing.T, ctx context.Context, s store.Store) {
	t1, err := s.TeamTasks().Create(ctx, "ns", "team", "do thing", "desc", nil)
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	t2, err := s.TeamTasks().Create(ctx, "ns", "team", "blocked", "", []string{t1.TaskID})
	if err != nil {
		t.Fatalf("create2: %v", err)
	}

	claimed, err := s.TeamTasks().Claim(ctx, "ns", "team", t1.TaskID, "worker", t1.Version)
	if err != nil {
		t.Fatalf("claim: %v", err)
	}
	if claimed.State != store.TaskStateInProgress || claimed.Owner != "worker" {
		t.Fatalf("claimed: %+v", claimed)
	}
	// Stale claim conflicts.
	_, err = s.TeamTasks().Claim(ctx, "ns", "team", t1.TaskID, "other", t1.Version)
	if !errors.Is(err, store.ErrConflict) {
		t.Fatalf("expected conflict, got %v", err)
	}

	unblocked, err := s.TeamTasks().GetUnblockedPending(ctx, "ns", "team")
	if err != nil {
		t.Fatalf("unblocked: %v", err)
	}
	// t2 is still blocked by t1.
	for _, u := range unblocked {
		if u.TaskID == t2.TaskID {
			t.Fatal("t2 should still be blocked")
		}
	}

	_, err = s.TeamTasks().Complete(ctx, "ns", "team", t1.TaskID, "done")
	if err != nil {
		t.Fatalf("complete: %v", err)
	}
	unblocked, _ = s.TeamTasks().GetUnblockedPending(ctx, "ns", "team")
	found := false
	for _, u := range unblocked {
		if u.TaskID == t2.TaskID {
			found = true
		}
	}
	if !found {
		t.Fatal("t2 should be unblocked after t1 complete")
	}

	total, pending, inProg, completed, err := s.TeamTasks().GetSummary(ctx, "ns", "team")
	if err != nil {
		t.Fatalf("summary: %v", err)
	}
	if total != 2 || pending != 1 || inProg != 0 || completed != 1 {
		t.Fatalf("summary: total=%d pending=%d inProg=%d completed=%d", total, pending, inProg, completed)
	}

	// Unclaim flow on a fresh task.
	t3, _ := s.TeamTasks().Create(ctx, "ns", "team", "unclaim-me", "", nil)
	c3, _ := s.TeamTasks().Claim(ctx, "ns", "team", t3.TaskID, "w", t3.Version)
	u3, err := s.TeamTasks().Unclaim(ctx, "ns", "team", c3.TaskID)
	if err != nil || u3.State != store.TaskStatePending || u3.Owner != "" {
		t.Fatalf("unclaim: %v %+v", err, u3)
	}
}

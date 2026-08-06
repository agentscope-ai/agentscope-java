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
	t.Run("Aggregations", func(t *testing.T) { testAggregations(t, ctx, s) })
	t.Run("Commands", func(t *testing.T) { testCommands(t, ctx, s) })
	t.Run("TeamMessages", func(t *testing.T) { testMessages(t, ctx, s) })
	t.Run("TeamTasks", func(t *testing.T) { testTasks(t, ctx, s) })
	t.Run("Teams", func(t *testing.T) { testTeams(t, ctx, s) })
	t.Run("KV", func(t *testing.T) { testKV(t, ctx, s) })
	t.Run("Locks", func(t *testing.T) { testLocks(t, ctx, s) })
	t.Run("Snapshots", func(t *testing.T) { testSnapshots(t, ctx, s) })
	t.Run("Bus", func(t *testing.T) { testBus(t, ctx, s) })
	t.Run("AsyncTools", func(t *testing.T) { testAsyncTools(t, ctx, s) })
	t.Run("DPTasks", func(t *testing.T) { testDPTasks(t, ctx, s) })
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

	// ArchiveMissing: keep sess-1, archive sess-2 (DP stopped listing ≠ hard destroy).
	archived, err := s.Sessions().ArchiveMissing(ctx, "agent-a", "default", []string{"sess-1"}, 0)
	if err != nil {
		t.Fatalf("archive missing: %v", err)
	}
	if archived < 1 {
		t.Fatalf("expected >=1 archived, got %d", archived)
	}
	got2, _ := s.Sessions().Get(ctx, "agent-a", "default", "sess-2")
	if got2.Phase != store.SessionPhaseArchived {
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

func testAggregations(t *testing.T, ctx context.Context, s store.Store) {
	busy := true
	start := time.Now().UTC().Add(-2 * time.Hour)
	active := time.Now().UTC()
	s1, err := s.Sessions().Upsert(ctx, &store.Session{
		SessionID: "agg-1", AgentName: "agent-agg", Namespace: "agg-ns",
		Framework: "x", Phase: store.SessionPhaseActive, Busy: &busy,
		StartedAt: &start, LastActiveAt: &active,
	})
	if err != nil {
		t.Fatalf("upsert1: %v", err)
	}
	s2Start := time.Now().UTC().Add(-30 * time.Minute)
	s2, err := s.Sessions().Upsert(ctx, &store.Session{
		SessionID: "agg-2", AgentName: "agent-agg", Namespace: "agg-ns",
		Framework: "x", Phase: store.SessionPhaseIdle,
		StartedAt: &s2Start, LastActiveAt: &active,
	})
	if err != nil {
		t.Fatalf("upsert2: %v", err)
	}
	_, err = s.Sessions().Upsert(ctx, &store.Session{
		SessionID: "agg-3", AgentName: "agent-agg", Namespace: "agg-ns",
		Framework: "x", Phase: store.SessionPhaseTerminated,
		StartedAt: &s2Start, LastActiveAt: &active,
	})
	if err != nil {
		t.Fatalf("upsert3: %v", err)
	}

	phases, err := s.Sessions().CountByPhase(ctx, store.SessionFilter{AgentName: "agent-agg", Namespace: "agg-ns"})
	if err != nil {
		t.Fatalf("count by phase: %v", err)
	}
	if phases[store.SessionPhaseActive] != 1 || phases[store.SessionPhaseIdle] != 1 || phases[store.SessionPhaseTerminated] != 1 {
		t.Fatalf("phases=%v", phases)
	}

	if err := s.Metrics().RecordSnapshot(ctx, &store.SessionSnapshot{
		SessionFK: s1.ID, ContextPressure: 0.9, TotalTokens: 200,
	}); err != nil {
		t.Fatalf("snap1: %v", err)
	}
	if err := s.Metrics().RecordSnapshot(ctx, &store.SessionSnapshot{
		SessionFK: s2.ID, ContextPressure: 0.3, TotalTokens: 50,
	}); err != nil {
		t.Fatalf("snap2: %v", err)
	}

	byPressure, err := s.Sessions().ListByPressure(ctx, store.SessionFilter{
		AgentName: "agent-agg", Namespace: "agg-ns",
	}, 0.5, 10)
	if err != nil {
		t.Fatalf("list by pressure: %v", err)
	}
	if len(byPressure) != 1 || byPressure[0].Session.SessionID != "agg-1" {
		t.Fatalf("expected agg-1 only, got %+v", byPressure)
	}
	if byPressure[0].Snapshot == nil || byPressure[0].Snapshot.ContextPressure != 0.9 {
		t.Fatalf("unexpected snapshot: %+v", byPressure[0].Snapshot)
	}

	fk1 := s1.ID
	fk2 := s2.ID
	now := time.Now().UTC()
	if err := s.Metrics().RecordTokenUsage(ctx, &store.TokenUsageMetric{
		SessionFK: &fk1, AgentName: "agent-agg", Namespace: "agg-ns",
		PromptTokens: 10, CompletionTokens: 20, TotalTokens: 30, RecordedAt: now,
	}); err != nil {
		t.Fatalf("tok1: %v", err)
	}
	if err := s.Metrics().RecordTokenUsage(ctx, &store.TokenUsageMetric{
		SessionFK: &fk2, AgentName: "agent-agg", Namespace: "agg-ns",
		PromptTokens: 40, CompletionTokens: 60, TotalTokens: 100, RecordedAt: now,
	}); err != nil {
		t.Fatalf("tok2: %v", err)
	}
	if err := s.Metrics().RecordTokenUsage(ctx, &store.TokenUsageMetric{
		AgentName: "agent-other", Namespace: "agg-ns",
		TotalTokens: 999, RecordedAt: now,
	}); err != nil {
		t.Fatalf("tok3: %v", err)
	}

	if err := s.Metrics().RecordAgentMetric(ctx, &store.AgentMetric{
		AgentName: "agent-agg", Namespace: "agg-ns", ActiveSessions: 2,
		AvgContextPressure: 0.6, ErrorCount: 3, RecordedAt: now,
	}); err != nil {
		t.Fatalf("agent metric: %v", err)
	}

	ams, err := s.Metrics().QueryAgentMetrics(ctx, store.AgentMetricFilter{
		AgentName: "agent-agg", Namespace: "agg-ns",
	})
	if err != nil || len(ams) != 1 {
		t.Fatalf("query agent metrics: %v len=%d", err, len(ams))
	}

	buckets, err := s.Metrics().AggregateTokens(ctx, store.TokenFilter{
		AgentName: "agent-agg", Namespace: "agg-ns",
	}, time.Hour)
	if err != nil || len(buckets) != 1 {
		t.Fatalf("aggregate: %v buckets=%+v", err, buckets)
	}
	if buckets[0].TotalTokens != 130 || buckets[0].SampleCount != 2 {
		t.Fatalf("bucket=%+v", buckets[0])
	}

	top, err := s.Metrics().TopAgents(ctx, now.Add(-time.Hour), 5)
	if err != nil {
		t.Fatalf("top agents: %v", err)
	}
	if len(top) < 2 {
		t.Fatalf("expected >=2 top agents, got %d", len(top))
	}
	if top[0].TotalTokens < top[1].TotalTokens {
		t.Fatalf("top not sorted: %+v", top)
	}

	byTok, err := s.Metrics().TopSessionsByTokens(ctx, now.Add(-time.Hour), 5)
	if err != nil {
		t.Fatalf("top sessions tokens: %v", err)
	}
	if len(byTok) < 2 {
		t.Fatalf("expected >=2 session token rows, got %d", len(byTok))
	}
	if byTok[0].TotalTokens < byTok[1].TotalTokens {
		t.Fatalf("session tokens not sorted: %+v", byTok)
	}
	foundAgg2 := false
	for _, u := range byTok {
		if u.SessionID == "agg-2" && u.TotalTokens == 100 {
			foundAgg2 = true
		}
	}
	if !foundAgg2 {
		t.Fatalf("expected agg-2 with 100 tokens in %+v", byTok)
	}

	if err := s.Turns().SyncOnPhase(ctx, s1.ID, store.SessionPhaseActive); err != nil {
		t.Fatalf("sync turn: %v", err)
	}

	byDur, err := s.Metrics().TopSessionsByDuration(ctx, now.Add(-time.Hour), 5)
	if err != nil {
		t.Fatalf("top sessions duration: %v", err)
	}
	if len(byDur) != 1 || byDur[0].SessionID != "agg-1" {
		t.Fatalf("expected only active agg-1 turn, got %+v", byDur)
	}
	if byDur[0].DurationMs < 0 {
		t.Fatalf("bad duration: %+v", byDur[0])
	}

	byActive, err := s.Metrics().TopAgentsByActiveSessions(ctx, now.Add(-time.Hour), 5)
	if err != nil {
		t.Fatalf("top agents active: %v", err)
	}
	if len(byActive) == 0 || byActive[0].ActiveSessions < 1 {
		t.Fatalf("expected active peak, got %+v", byActive)
	}

	avg, p95, err := s.Metrics().PressureStats(ctx, store.SessionFilter{
		AgentName: "agent-agg", Namespace: "agg-ns",
	})
	if err != nil {
		t.Fatalf("pressure stats: %v", err)
	}
	if avg <= 0 || p95 <= 0 {
		t.Fatalf("avg=%v p95=%v", avg, p95)
	}

	sum, err := s.Metrics().SumTokenUsage(ctx, store.TokenFilter{
		AgentName: "agent-agg", Namespace: "agg-ns",
	})
	if err != nil || sum != 130 {
		t.Fatalf("sum tokens: %v sum=%d", err, sum)
	}

	errs, err := s.Metrics().SumErrorCount(ctx, store.AgentMetricFilter{
		AgentName: "agent-agg", Namespace: "agg-ns",
	})
	if err != nil || errs != 3 {
		t.Fatalf("sum errors: %v errs=%d", err, errs)
	}
}

func testCommands(t *testing.T, ctx context.Context, s store.Store) {
	sess, err := s.Sessions().Upsert(ctx, &store.Session{
		SessionID: "cmd-sess", AgentName: "agent-cmd", Namespace: "cmd-ns",
		Framework: "x", Phase: store.SessionPhaseActive,
	})
	if err != nil {
		t.Fatalf("upsert: %v", err)
	}
	fk := sess.ID
	cmd := &store.SessionCommand{
		SessionFK: &fk, AgentName: "agent-cmd", Namespace: "cmd-ns",
		SessionID: "cmd-sess", Command: "compress", Operator: "admin",
		Source: "api", CommandID: "cmd-abc-1",
	}
	if err := s.Commands().Insert(ctx, cmd); err != nil {
		t.Fatalf("insert: %v", err)
	}
	if cmd.ID.String() == "" || cmd.Status != store.CommandStatusAccepted {
		t.Fatalf("insert defaults: %+v", cmd)
	}

	got, err := s.Commands().GetByCommandID(ctx, "cmd-abc-1")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if got.Command != "compress" || got.AgentName != "agent-cmd" {
		t.Fatalf("unexpected get: %+v", got)
	}

	list, err := s.Commands().List(ctx, store.SessionCommandFilter{
		AgentName: "agent-cmd", Namespace: "cmd-ns",
	})
	if err != nil || len(list) != 1 {
		t.Fatalf("list: %v len=%d", err, len(list))
	}

	_, err = s.Commands().GetByCommandID(ctx, "missing")
	if !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("expected ErrNotFound, got %v", err)
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
	t1, err := s.TeamTasks().Create(ctx, "ns", "team", "do thing", "desc", nil, "")
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	t2, err := s.TeamTasks().Create(ctx, "ns", "team", "blocked", "", []string{t1.TaskID}, "")
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
	t3, _ := s.TeamTasks().Create(ctx, "ns", "team", "unclaim-me", "", nil, "")
	c3, _ := s.TeamTasks().Claim(ctx, "ns", "team", t3.TaskID, "w", t3.Version)
	u3, err := s.TeamTasks().Unclaim(ctx, "ns", "team", c3.TaskID)
	if err != nil || u3.State != store.TaskStatePending || u3.Owner != "" {
		t.Fatalf("unclaim: %v %+v", err, u3)
	}

	// Lead-assign then assignee claim; other member cannot claim.
	t4, err := s.TeamTasks().Create(ctx, "ns", "team", "assigned", "", nil, "")
	if err != nil {
		t.Fatalf("create4: %v", err)
	}
	a4, err := s.TeamTasks().Assign(ctx, "ns", "team", t4.TaskID, "alice", t4.Version)
	if err != nil || a4.Owner != "alice" || a4.State != store.TaskStatePending {
		t.Fatalf("assign: %v %+v", err, a4)
	}
	_, err = s.TeamTasks().Claim(ctx, "ns", "team", t4.TaskID, "bob", a4.Version)
	if !errors.Is(err, store.ErrConflict) {
		t.Fatalf("bob should not claim alice task: %v", err)
	}
	c4, err := s.TeamTasks().Claim(ctx, "ns", "team", t4.TaskID, "alice", a4.Version)
	if err != nil || c4.State != store.TaskStateInProgress || c4.Owner != "alice" {
		t.Fatalf("alice claim: %v %+v", err, c4)
	}

	// Assigned pending tasks are not open-board self-claim candidates.
	t5, _ := s.TeamTasks().Create(ctx, "ns", "team", "owned-pending", "", nil, "carol")
	unblocked, _ = s.TeamTasks().GetUnblockedPending(ctx, "ns", "team")
	for _, u := range unblocked {
		if u.TaskID == t5.TaskID {
			t.Fatal("owned pending task must not appear in GetUnblockedPending")
		}
	}
	// Assignee can claim with expectedVersion 0 (current version).
	c5, err := s.TeamTasks().Claim(ctx, "ns", "team", t5.TaskID, "carol", 0)
	if err != nil || c5.State != store.TaskStateInProgress || c5.Owner != "carol" {
		t.Fatalf("carol claim with version 0: %v %+v", err, c5)
	}

	// Fail records the reason and is terminal.
	f5, err := s.TeamTasks().Fail(ctx, "ns", "team", t5.TaskID, "sandbox exploded")
	if err != nil || f5.State != store.TaskStateFailed || f5.Result != "sandbox exploded" {
		t.Fatalf("fail: %v %+v", err, f5)
	}
	if f5.CompletedAt == nil {
		t.Fatal("failed task should carry completedAt")
	}
	if _, err = s.TeamTasks().Fail(ctx, "ns", "team", t5.TaskID, "again"); !errors.Is(err, store.ErrConflict) {
		t.Fatalf("re-failing a terminal task must conflict, got %v", err)
	}
	if _, err = s.TeamTasks().Complete(ctx, "ns", "team", t5.TaskID, "late"); err == nil {
		t.Fatal("completing a failed task must fail")
	}

	// A pending task can fail directly (never claimed).
	t7, _ := s.TeamTasks().Create(ctx, "ns", "team", "unstartable", "", nil, "")
	f7, err := s.TeamTasks().Fail(ctx, "ns", "team", t7.TaskID, "no capacity")
	if err != nil || f7.State != store.TaskStateFailed {
		t.Fatalf("fail pending: %v %+v", err, f7)
	}
	unblocked, _ = s.TeamTasks().GetUnblockedPending(ctx, "ns", "team")
	for _, u := range unblocked {
		if u.TaskID == t7.TaskID {
			t.Fatal("failed task must not be claimable")
		}
	}

	// Concurrent self-claim: only one winner.
	t6, err := s.TeamTasks().Create(ctx, "ns", "team-race", "race", "", nil, "")
	if err != nil {
		t.Fatalf("create race: %v", err)
	}
	errCh := make(chan error, 2)
	go func() {
		_, e := s.TeamTasks().Claim(ctx, "ns", "team-race", t6.TaskID, "racer-a", t6.Version)
		errCh <- e
	}()
	go func() {
		_, e := s.TeamTasks().Claim(ctx, "ns", "team-race", t6.TaskID, "racer-b", t6.Version)
		errCh <- e
	}()
	var wins, conflicts int
	for i := 0; i < 2; i++ {
		e := <-errCh
		switch {
		case e == nil:
			wins++
		case errors.Is(e, store.ErrConflict):
			conflicts++
		default:
			t.Fatalf("race claim unexpected: %v", e)
		}
	}
	if wins != 1 || conflicts != 1 {
		t.Fatalf("race claim: wins=%d conflicts=%d", wins, conflicts)
	}
}

func testTeams(t *testing.T, ctx context.Context, s store.Store) {
	created, err := s.Teams().Create(ctx, &store.Team{
		Name: "research", Namespace: "default", Objective: "find facts",
		LeadRef: "lead-agent",
	})
	if err != nil {
		t.Fatalf("create team: %v", err)
	}
	if created.Phase != store.TeamPhasePending || created.ID == 0 {
		t.Fatalf("created: %+v", created)
	}
	_, err = s.Teams().Create(ctx, &store.Team{
		Name: "research", Namespace: "default", Objective: "dup", LeadRef: "x",
	})
	if !errors.Is(err, store.ErrConflict) {
		t.Fatalf("expected conflict, got %v", err)
	}

	lead, err := s.Teams().UpsertMember(ctx, &store.TeamMember{
		TeamName: "research", Namespace: "default", MemberName: "lead",
		AgentRef: "lead-agent", DeployMode: store.MemberDeployManaged,
		ManagedAgentID: "lead-agent", OwnerID: "user-1",
	})
	if err != nil || lead.Phase != store.MemberPhaseJoining {
		t.Fatalf("upsert lead: %v %+v", err, lead)
	}
	worker, err := s.Teams().UpsertMember(ctx, &store.TeamMember{
		TeamName: "research", Namespace: "default", MemberName: "writer",
		AgentRef: "writer-agent", DeployMode: store.MemberDeployBYO,
	})
	if err != nil {
		t.Fatalf("upsert worker: %v", err)
	}
	if err := s.Teams().BindMemberSession(ctx, "default", "research", "writer",
		"sess-w", "", "inst-1"); err != nil {
		t.Fatalf("bind: %v", err)
	}
	got, err := s.Teams().GetMember(ctx, "default", "research", "writer")
	if err != nil || got.SessionID != "sess-w" || got.InstanceRef != "inst-1" {
		t.Fatalf("get member: %v %+v", err, got)
	}
	_ = worker

	if err := s.Teams().UpdatePhase(ctx, "default", "research", store.TeamPhaseRunning); err != nil {
		t.Fatalf("phase: %v", err)
	}
	team, err := s.Teams().Get(ctx, "default", "research")
	if err != nil || team.Phase != store.TeamPhaseRunning || team.StartedAt == nil {
		t.Fatalf("running: %v %+v", err, team)
	}
	members, err := s.Teams().ListMembers(ctx, "default", "research")
	if err != nil || len(members) != 2 {
		t.Fatalf("members: %v %d", err, len(members))
	}
	if err := s.Teams().RemoveMember(ctx, "default", "research", "writer"); err != nil {
		t.Fatalf("remove: %v", err)
	}
	members, _ = s.Teams().ListMembers(ctx, "default", "research")
	if len(members) != 1 {
		t.Fatalf("after remove: %d", len(members))
	}
	if err := s.Teams().Delete(ctx, "default", "research"); err != nil {
		t.Fatalf("delete: %v", err)
	}
	_, err = s.Teams().Get(ctx, "default", "research")
	if !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("expected not found, got %v", err)
	}
}

func testKV(t *testing.T, ctx context.Context, s store.Store) {
	tenant := "default/agent-kv"
	ns := store.JoinNamespacePath([]string{"a"})
	child := store.JoinNamespacePath([]string{"a", "b"})
	val := json.RawMessage(`{"x":1}`)

	ver, err := s.KV().Put(ctx, tenant, ns, "k1", val)
	if err != nil || ver != 1 {
		t.Fatalf("put: ver=%d err=%v", ver, err)
	}
	got, err := s.KV().Get(ctx, tenant, ns, "k1")
	if err != nil || got.Version != 1 || string(got.Value) != string(val) {
		t.Fatalf("get: %+v err=%v", got, err)
	}

	ver, err = s.KV().Put(ctx, tenant, ns, "k1", json.RawMessage(`{"x":2}`))
	if err != nil || ver != 2 {
		t.Fatalf("put2: ver=%d err=%v", ver, err)
	}

	// create-if-absent: expectedVersion==0 conflicts when key exists
	cur, written, err := s.KV().PutIfVersion(ctx, tenant, ns, "k1", json.RawMessage(`{}`), 0)
	if err != nil || written || cur != 2 {
		t.Fatalf("putIfVersion create conflict: cur=%d written=%v err=%v", cur, written, err)
	}
	// CAS success
	cur, written, err = s.KV().PutIfVersion(ctx, tenant, ns, "k1", json.RawMessage(`{"x":3}`), 2)
	if err != nil || !written || cur != 3 {
		t.Fatalf("putIfVersion cas: cur=%d written=%v err=%v", cur, written, err)
	}
	// CAS conflict
	cur, written, err = s.KV().PutIfVersion(ctx, tenant, ns, "k1", json.RawMessage(`{}`), 2)
	if err != nil || written || cur != 3 {
		t.Fatalf("putIfVersion stale: cur=%d written=%v err=%v", cur, written, err)
	}
	// create-if-absent success
	cur, written, err = s.KV().PutIfVersion(ctx, tenant, ns, "k2", json.RawMessage(`{"n":1}`), 0)
	if err != nil || !written || cur != 1 {
		t.Fatalf("putIfVersion create: cur=%d written=%v err=%v", cur, written, err)
	}

	_, err = s.KV().Put(ctx, tenant, child, "child-k", json.RawMessage(`{"c":1}`))
	if err != nil {
		t.Fatalf("put child: %v", err)
	}
	// Sibling namespace must not match prefix search for "a"
	sib := store.JoinNamespacePath([]string{"aa"})
	_, _ = s.KV().Put(ctx, tenant, sib, "sib", json.RawMessage(`{}`))

	items, err := s.KV().Search(ctx, tenant, ns, 50, 0)
	if err != nil {
		t.Fatalf("search: %v", err)
	}
	foundK1, foundChild, foundSib := false, false, false
	for _, it := range items {
		switch {
		case it.Key == "k1" && it.NsPath == ns:
			foundK1 = true
		case it.Key == "child-k" && it.NsPath == child:
			foundChild = true
		case it.Key == "sib":
			foundSib = true
		}
	}
	if !foundK1 || !foundChild {
		t.Fatalf("search missing recursive hits: %+v", items)
	}
	if foundSib {
		t.Fatal("search must not match sibling ns_path aa")
	}

	if err := s.KV().Delete(ctx, tenant, ns, "k2"); err != nil {
		t.Fatalf("delete: %v", err)
	}
	_, err = s.KV().Get(ctx, tenant, ns, "k2")
	if !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("expected ErrNotFound after delete, got %v", err)
	}
	// Delete is idempotent
	if err := s.KV().Delete(ctx, tenant, ns, "k2"); err != nil {
		t.Fatalf("delete idempotent: %v", err)
	}
}

func testLocks(t *testing.T, ctx context.Context, s store.Store) {
	tenant := "default/agent-lock"
	lk, err := s.Locks().Acquire(ctx, tenant, "crit", "tok-a", "holder-a", time.Second)
	if err != nil || lk.OwnerToken != "tok-a" || lk.FencingToken < 1 {
		t.Fatalf("acquire: %+v err=%v", lk, err)
	}
	fencing := lk.FencingToken

	cur, err := s.Locks().Acquire(ctx, tenant, "crit", "tok-b", "holder-b", time.Second)
	if !errors.Is(err, store.ErrConflict) {
		t.Fatalf("expected conflict, got %v", err)
	}
	if cur == nil || cur.OwnerToken != "tok-a" || cur.Holder != "holder-a" {
		t.Fatalf("conflict lock: %+v", cur)
	}

	renewed, err := s.Locks().Renew(ctx, tenant, "crit", "tok-a", 2*time.Second)
	if err != nil || renewed.OwnerToken != "tok-a" {
		t.Fatalf("renew: %+v err=%v", renewed, err)
	}
	_, err = s.Locks().Renew(ctx, tenant, "crit", "tok-b", time.Second)
	if !errors.Is(err, store.ErrConflict) {
		t.Fatalf("renew foreign: %v", err)
	}

	if err := s.Locks().Release(ctx, tenant, "crit", "tok-b"); err != nil {
		t.Fatalf("release mismatch should be idempotent: %v", err)
	}
	if err := s.Locks().Release(ctx, tenant, "crit", "tok-a"); err != nil {
		t.Fatalf("release: %v", err)
	}
	if err := s.Locks().Release(ctx, tenant, "crit", "tok-a"); err != nil {
		t.Fatalf("release idempotent: %v", err)
	}

	// Re-acquire after release gets a new fencing token
	lk2, err := s.Locks().Acquire(ctx, tenant, "crit", "tok-c", "holder-c", 50*time.Millisecond)
	if err != nil || lk2.FencingToken <= fencing {
		t.Fatalf("reacquire: %+v err=%v", lk2, err)
	}

	// Wait for expiry then take over
	time.Sleep(80 * time.Millisecond)
	lk3, err := s.Locks().Acquire(ctx, tenant, "crit", "tok-d", "holder-d", time.Second)
	if err != nil || lk3.OwnerToken != "tok-d" {
		t.Fatalf("acquire after expire: %+v err=%v", lk3, err)
	}
	_ = s.Locks().Release(ctx, tenant, "crit", "tok-d")

	peeked, err := s.Locks().Peek(ctx, tenant, "crit")
	if !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("peek missing: err=%v lk=%+v", err, peeked)
	}
	lk4, err := s.Locks().Acquire(ctx, tenant, "crit", "tok-e", "holder-e", time.Second)
	if err != nil {
		t.Fatalf("acquire for peek: %v", err)
	}
	peeked, err = s.Locks().Peek(ctx, tenant, "crit")
	if err != nil || peeked == nil || peeked.OwnerToken != lk4.OwnerToken {
		t.Fatalf("peek held: err=%v lk=%+v", err, peeked)
	}
	_ = s.Locks().Release(ctx, tenant, "crit", "tok-e")
}

func testSnapshots(t *testing.T, ctx context.Context, s store.Store) {
	tenant := "default/agent-snap"
	payload := []byte("sandbox-blob")
	meta, err := s.Snapshots().Put(ctx, tenant, "snap-1", payload, store.SnapshotModeInline)
	if err != nil || meta.SizeBytes != int64(len(payload)) || meta.StorageMode != store.SnapshotModeInline {
		t.Fatalf("put: %+v err=%v", meta, err)
	}
	ok, err := s.Snapshots().Exists(ctx, tenant, "snap-1")
	if err != nil || !ok {
		t.Fatalf("exists: ok=%v err=%v", ok, err)
	}
	got, meta2, err := s.Snapshots().Get(ctx, tenant, "snap-1")
	if err != nil || string(got) != string(payload) || meta2.SnapshotID != "snap-1" {
		t.Fatalf("get: %q %+v err=%v", got, meta2, err)
	}
	if err := s.Snapshots().Touch(ctx, tenant, "snap-1"); err != nil {
		t.Fatalf("touch: %v", err)
	}
	_, _, err = s.Snapshots().Get(ctx, tenant, "missing")
	if !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("expected ErrNotFound, got %v", err)
	}
}

func testBus(t *testing.T, ctx context.Context, s store.Store) {
	tenant := "default/agent-bus"
	id1, err := s.Bus().QueuePush(ctx, tenant, "q1", json.RawMessage(`{"n":1}`))
	if err != nil || id1 == "" {
		t.Fatalf("push1: id=%s err=%v", id1, err)
	}
	id2, err := s.Bus().QueuePush(ctx, tenant, "q1", json.RawMessage(`{"n":2}`))
	if err != nil || id2 == "" || id2 == id1 {
		t.Fatalf("push2: id=%s err=%v", id2, err)
	}
	peek, err := s.Bus().QueuePeek(ctx, tenant, "q1")
	if err != nil || !peek {
		t.Fatalf("peek: %v err=%v", peek, err)
	}
	drained, err := s.Bus().QueueDrain(ctx, tenant, "q1", 1)
	if err != nil || len(drained) != 1 || drained[0].EntryID != id1 {
		t.Fatalf("drain1: %+v err=%v", drained, err)
	}
	drained, err = s.Bus().QueueDrain(ctx, tenant, "q1", 10)
	if err != nil || len(drained) != 1 || drained[0].EntryID != id2 {
		t.Fatalf("drain2: %+v err=%v", drained, err)
	}
	peek, _ = s.Bus().QueuePeek(ctx, tenant, "q1")
	if peek {
		t.Fatal("queue should be empty")
	}
	_, _ = s.Bus().QueuePush(ctx, tenant, "q1", json.RawMessage(`{"n":3}`))
	if err := s.Bus().QueueDelete(ctx, tenant, "q1"); err != nil {
		t.Fatalf("delete: %v", err)
	}
	peek, _ = s.Bus().QueuePeek(ctx, tenant, "q1")
	if peek {
		t.Fatal("queue should be empty after delete")
	}

	lid1, err := s.Bus().LogAppend(ctx, tenant, "log1", json.RawMessage(`{"a":1}`), 2)
	if err != nil {
		t.Fatalf("log append1: %v", err)
	}
	_, _ = s.Bus().LogAppend(ctx, tenant, "log1", json.RawMessage(`{"a":2}`), 2)
	lid3, err := s.Bus().LogAppend(ctx, tenant, "log1", json.RawMessage(`{"a":3}`), 2)
	if err != nil {
		t.Fatalf("log append3: %v", err)
	}
	entries, err := s.Bus().LogRead(ctx, tenant, "log1", "0", 10)
	if err != nil || len(entries) != 2 {
		t.Fatalf("log read after trim: len=%d err=%v", len(entries), err)
	}
	if entries[len(entries)-1].EntryID != lid3 {
		t.Fatalf("expected newest %s, got %+v", lid3, entries)
	}
	// since cursor
	since := entries[0].EntryID
	rest, err := s.Bus().LogRead(ctx, tenant, "log1", since, 10)
	if err != nil || len(rest) != 1 || rest[0].EntryID != lid3 {
		t.Fatalf("log read since: %+v err=%v", rest, err)
	}
	_ = lid1
	if err := s.Bus().LogTrim(ctx, tenant, "log1"); err != nil {
		t.Fatalf("log trim: %v", err)
	}
	entries, _ = s.Bus().LogRead(ctx, tenant, "log1", "0", 10)
	if len(entries) != 0 {
		t.Fatalf("expected empty log, got %d", len(entries))
	}
}

func testAsyncTools(t *testing.T, ctx context.Context, s store.Store) {
	tenant := "default/agent-async"
	rec := &store.AsyncToolRecord{
		ID: "r1", Tenant: tenant, SessionID: "sess-async",
		ToolName: "bash", ToolCallID: "tc-1", Status: store.AsyncToolRunning,
		CreatedAt: time.Now().UTC().Add(-2 * time.Second),
	}
	if err := s.AsyncTools().Register(ctx, rec); err != nil {
		t.Fatalf("register: %v", err)
	}
	stale, err := s.AsyncTools().FindStale(ctx, tenant, "sess-async", time.Second)
	if err != nil || len(stale) != 1 || stale[0].ID != "r1" {
		t.Fatalf("find stale: %+v err=%v", stale, err)
	}
	if err := s.AsyncTools().Complete(ctx, tenant, "r1", "ok"); err != nil {
		t.Fatalf("complete: %v", err)
	}
	stale, err = s.AsyncTools().FindStale(ctx, tenant, "sess-async", time.Second)
	if err != nil || len(stale) != 0 {
		t.Fatalf("stale after complete: %+v err=%v", stale, err)
	}

	rec2 := &store.AsyncToolRecord{
		ID: "r2", Tenant: tenant, SessionID: "sess-async", Status: store.AsyncToolRunning,
	}
	_ = s.AsyncTools().Register(ctx, rec2)
	if err := s.AsyncTools().Fail(ctx, tenant, "r2", "boom"); err != nil {
		t.Fatalf("fail: %v", err)
	}
	rec3 := &store.AsyncToolRecord{
		ID: "r3", Tenant: tenant, SessionID: "sess-async", Status: store.AsyncToolRunning,
	}
	_ = s.AsyncTools().Register(ctx, rec3)
	if err := s.AsyncTools().MarkTimeout(ctx, tenant, "r3"); err != nil {
		t.Fatalf("timeout: %v", err)
	}
	err = s.AsyncTools().Complete(ctx, tenant, "missing", "x")
	if !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("expected ErrNotFound, got %v", err)
	}
}

func testDPTasks(t *testing.T, ctx context.Context, s store.Store) {
	tenant := "default/agent-dptask"
	parentAgent := "agent-dptask"
	session := "sess-dp-1"

	task := &store.DPTask{
		Tenant: tenant, ParentAgentID: parentAgent, ParentSessionID: session,
		TaskID: "t1", SubAgentID: "sub-a", Status: store.DPTaskStatusRunning,
	}
	got, err := s.Tasks().Upsert(ctx, task)
	if err != nil || got.TaskID != "t1" || got.Version < 1 {
		t.Fatalf("upsert: %+v err=%v", got, err)
	}

	read, err := s.Tasks().Get(ctx, tenant, parentAgent, session, "t1")
	if err != nil || read.SubAgentID != "sub-a" {
		t.Fatalf("get: %+v err=%v", read, err)
	}

	list, err := s.Tasks().List(ctx, tenant, parentAgent, session, "")
	if err != nil || len(list) != 1 {
		t.Fatalf("list: len=%d err=%v", len(list), err)
	}

	before := read.LastUpdatedAt
	time.Sleep(5 * time.Millisecond)
	if err := s.Tasks().Heartbeat(ctx, tenant, parentAgent, []store.DPTaskRef{
		{ParentSessionID: session, TaskID: "t1"},
	}); err != nil {
		t.Fatalf("heartbeat: %v", err)
	}
	after, _ := s.Tasks().Get(ctx, tenant, parentAgent, session, "t1")
	if !after.LastUpdatedAt.After(before) {
		t.Fatalf("heartbeat should advance lastUpdatedAt: before=%v after=%v", before, after.LastUpdatedAt)
	}

	term := &store.DPTask{
		Tenant: tenant, ParentAgentID: parentAgent, ParentSessionID: session,
		TaskID: "t2", Status: store.DPTaskStatusCompleted, Terminal: true,
	}
	_, _ = s.Tasks().Upsert(ctx, term)
	written, err := s.Tasks().MarkDelivered(ctx, tenant, parentAgent, session, "t2")
	if err != nil || !written {
		t.Fatalf("mark delivered first: written=%v err=%v", written, err)
	}
	written, err = s.Tasks().MarkDelivered(ctx, tenant, parentAgent, session, "t2")
	if err != nil || written {
		t.Fatalf("mark delivered idempotent: written=%v err=%v", written, err)
	}

	pending, err := s.Tasks().ListPendingDeliveries(ctx, tenant, parentAgent, session)
	if err != nil || len(pending) != 0 {
		t.Fatalf("pending after deliver: %+v err=%v", pending, err)
	}

	stale := &store.DPTask{
		Tenant: tenant, ParentAgentID: parentAgent, ParentSessionID: session,
		TaskID: "orphan-local", Status: store.DPTaskStatusRunning,
	}
	_, _ = s.Tasks().Upsert(ctx, stale)
	remote := &store.DPTask{
		Tenant: tenant, ParentAgentID: parentAgent, ParentSessionID: session,
		TaskID: "orphan-remote", Status: store.DPTaskStatusRunning,
		TransportType: "agent-protocol",
	}
	_, _ = s.Tasks().Upsert(ctx, remote)
	time.Sleep(15 * time.Millisecond)

	swept, err := s.Tasks().SweepOrphaned(ctx, 10*time.Millisecond, "orphaned")
	if err != nil {
		t.Fatalf("sweep: %v", err)
	}
	sweptIDs := map[string]bool{}
	for _, sw := range swept {
		sweptIDs[sw.TaskID] = true
	}
	if !sweptIDs["orphan-local"] {
		t.Fatalf("expected orphan-local swept, got %+v", swept)
	}
	if sweptIDs["orphan-remote"] {
		t.Fatalf("agent-protocol task must not be swept: %+v", swept)
	}
	gotRemote, _ := s.Tasks().Get(ctx, tenant, parentAgent, session, "orphan-remote")
	if gotRemote.Status != store.DPTaskStatusRunning {
		t.Fatalf("remote task status=%s", gotRemote.Status)
	}
}

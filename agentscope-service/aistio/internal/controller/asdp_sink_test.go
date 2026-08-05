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

package controller

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func newSinkTestStore(t *testing.T) store.Store {
	t.Helper()
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatalf("memory.Open: %v", err)
	}
	return st
}

func newSinkTestAgent() *v1alpha1.Agent {
	return &v1alpha1.Agent{
		ObjectMeta: metav1.ObjectMeta{Name: "agent-1", Namespace: "default"},
		Spec:       v1alpha1.AgentSpec{Type: v1alpha1.AgentTypeDeclarative, Runtime: "claude-agent-sdk"},
	}
}

func TestApplySessionReportNewFields(t *testing.T) {
	st := newSinkTestStore(t)
	agent := newSinkTestAgent()
	c := fake.NewClientBuilder().WithScheme(newScheme()).WithObjects(agent).Build()
	sink := &SessionEventSink{Client: c, Store: st}

	sink.ApplySessionReport(context.Background(), "default", "agent-1", "pod-0", []ObservedSession{{
		ID:                    "sess-1",
		Phase:                 "active",
		MessageCount:          12,
		Framework:             "claude-agent-sdk",
		FrameworkVersion:      "0.5.0",
		ContextHash:           "hash-1",
		IsCompacted:           true,
		EffectiveMessageCount: 3,
	}})

	sess, err := st.Sessions().Get(context.Background(), "agent-1", "default", "sess-1")
	if err != nil {
		t.Fatalf("session not stored: %v", err)
	}
	if sess.Framework != "claude-agent-sdk" || sess.FrameworkVersion != "0.5.0" {
		t.Errorf("framework fields = %q/%q", sess.Framework, sess.FrameworkVersion)
	}
	if sess.InstanceRef != "pod-0" {
		t.Errorf("instanceRef = %q", sess.InstanceRef)
	}
}

func TestApplyEventReportIdempotent(t *testing.T) {
	st := newSinkTestStore(t)
	sink := &SessionEventSink{Store: st}
	ctx := context.Background()

	events := []ObservedEvent{
		{SessionID: "sess-x", Seq: 1, EventType: "message", Role: "user", Content: "hi", OccurredAt: time.Now().UTC()},
		{SessionID: "sess-x", Seq: 2, EventType: "message", Role: "assistant", Content: "hello", OccurredAt: time.Now().UTC()},
	}
	sink.ApplyEventReport(ctx, "default", "agent-1", "pod-0", events)

	// Placeholder session must have been created for the unknown session ID.
	sess, err := st.Sessions().Get(ctx, "agent-1", "default", "sess-x")
	if err != nil {
		t.Fatalf("placeholder session not created: %v", err)
	}

	list, err := st.Events().List(ctx, sess.ID)
	if err != nil {
		t.Fatalf("Events.List: %v", err)
	}
	if len(list) != 2 {
		t.Fatalf("expected 2 events, got %d", len(list))
	}

	// Re-applying the same batch must be idempotent (no duplicates).
	sink.ApplyEventReport(ctx, "default", "agent-1", "pod-0", events)
	list, err = st.Events().List(ctx, sess.ID)
	if err != nil {
		t.Fatalf("Events.List: %v", err)
	}
	if len(list) != 2 {
		t.Errorf("expected idempotent append, got %d events", len(list))
	}
}

func TestApplyContextReportDedup(t *testing.T) {
	st := newSinkTestStore(t)
	sink := &SessionEventSink{Store: st}
	ctx := context.Background()

	report := ObservedContext{
		SessionID:   "sess-c",
		ContextHash: "hash-1",
		SystemPrompt: "sys",
		Messages:    json.RawMessage(`[{"role":"user","content":"hi"}]`),
		Framework:   "claude-agent-sdk",
	}
	sink.ApplyContextReport(ctx, "default", "agent-1", "pod-0", report)

	sess, err := st.Sessions().Get(ctx, "agent-1", "default", "sess-c")
	if err != nil {
		t.Fatalf("placeholder session not created: %v", err)
	}
	latest, err := st.ContextSnapshots().Latest(ctx, sess.ID)
	if err != nil {
		t.Fatalf("ContextSnapshots.Latest: %v", err)
	}
	if latest.ContextHash != "hash-1" {
		t.Errorf("contextHash = %q", latest.ContextHash)
	}

	// Same hash again: no new row (dedup semantics via PutIfChanged).
	inserted, err := st.ContextSnapshots().PutIfChanged(ctx, &store.ContextSnapshot{
		SessionFK:   sess.ID,
		ContextHash: "hash-1",
		Messages:    json.RawMessage(`[]`),
	})
	if err != nil {
		t.Fatalf("PutIfChanged: %v", err)
	}
	if inserted {
		t.Error("expected dedup by context_hash (inserted=false)")
	}

	// Changed hash: Latest reflects the new row.
	report.ContextHash = "hash-2"
	sink.ApplyContextReport(ctx, "default", "agent-1", "pod-0", report)
	latest, err = st.ContextSnapshots().Latest(ctx, sess.ID)
	if err != nil {
		t.Fatalf("ContextSnapshots.Latest: %v", err)
	}
	if latest.ContextHash != "hash-2" {
		t.Errorf("expected latest hash-2, got %q", latest.ContextHash)
	}
}

func TestApplyInventoryReportRecordsMetric(t *testing.T) {
	st := newSinkTestStore(t)
	sink := &SessionEventSink{Store: st}

	// Must not panic with a full inventory payload.
	sink.ApplyInventoryReport(context.Background(), "default", "agent-1", "pod-0", ObservedInventory{
		Subagents:      []ObservedSubagent{{Name: "researcher", InvokeCount: 2}},
		Workspaces:     []ObservedWorkspace{{Path: "/tmp/ws", Mode: "shared"}},
		Healthy:        true,
		ActiveSessions: 3,
	})
}

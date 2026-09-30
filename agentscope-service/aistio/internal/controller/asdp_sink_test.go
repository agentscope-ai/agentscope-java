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
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestApplyExecutionAttemptReportRequiresSelectedTenantInstance(t *testing.T) {
	ctx := context.Background()
	st := newSinkTestStore(t)
	sink := &SessionEventSink{Store: st}
	actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "ns-a", Title: "work", Creator: actor,
	})
	if err != nil {
		t.Fatal(err)
	}
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "tenant-a", Namespace: "ns-a",
		AgentKey: "agent-a", Status: controlmodel.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	configuration := json.RawMessage(`{"instanceSelector":{}}`)
	catalogBinding, err := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{AgentID: agent.ID,
		Tenant: agent.Tenant, Namespace: agent.Namespace, Kind: controlmodel.DataPlaneExternalApplication,
		Configuration: configuration, Priority: 100, Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	_, task, err := st.Collaboration().AssignIssue(ctx, issue.ID, issue.Version,
		controlmodel.AssigneeAgent, agent.ID.String(), actor)
	if err != nil {
		t.Fatal(err)
	}
	selected, err := st.RuntimeRegistry().UpsertAgentInstance(ctx, &controlmodel.AgentInstance{
		Tenant: "tenant-a", Namespace: "ns-a", AgentID: agent.ID, BindingID: catalogBinding.ID,
		BackendKind: controlmodel.DataPlaneExternalApplication, InstanceKey: "instance-a",
	})
	if err != nil {
		t.Fatal(err)
	}
	binding, err := json.Marshal(controlmodel.RuntimeDispatchSnapshot{
		Binding:         controlmodel.RuntimeBinding{AgentID: agent.ID, BindingID: catalogBinding.ID, Kind: controlmodel.DataPlaneExternalApplication},
		AgentInstanceID: &selected.ID, ResolvedAt: time.Now().UTC(),
	})
	if err != nil {
		t.Fatal(err)
	}
	_, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx, store.TaskClaim{
		TaskID: task.ID, ExpectedVersion: task.Version, RuntimeBinding: binding,
	}, &controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneExternalApplication,
		AgentInstanceID: &selected.ID, State: controlmodel.ExecutionAssigned})
	if err != nil {
		t.Fatal(err)
	}

	for _, tc := range []struct {
		name, tenant, instance string
	}{
		{name: "wrong tenant", tenant: "tenant-b", instance: "instance-a"},
		{name: "wrong instance", tenant: "tenant-a", instance: "instance-b"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			err := sink.ApplyExecutionAttemptReport(ctx, tc.tenant, "ns-a", agent.ID.String(), catalogBinding.ID.String(), tc.instance, selected.Generation,
				attempt.ID, task.ID, task.OrchestrationRunID, task.RunNodeID, attempt.DispatchGeneration,
				"start", nil, "", nil, nil, nil, "", "", "")
			if !errors.Is(err, store.ErrNotFound) {
				t.Fatalf("expected not found, got %v", err)
			}
		})
	}
	if err := sink.ApplyExecutionAttemptReport(ctx, "tenant-a", "ns-a", agent.ID.String(), catalogBinding.ID.String(), "instance-a", selected.Generation,
		attempt.ID, task.ID, task.OrchestrationRunID, task.RunNodeID, attempt.DispatchGeneration,
		"start", nil, "", nil, nil, nil, "", "", ""); err != nil {
		t.Fatalf("selected instance report rejected: %v", err)
	}
	started, err := st.Collaboration().GetAgentTask(ctx, task.ID)
	if err != nil || started.Status != controlmodel.AgentTaskRunning {
		t.Fatalf("task was not started: task=%+v err=%v", started, err)
	}
	if started.ID == uuid.Nil {
		t.Fatal("task ID is nil")
	}

	diagnosticSession, err := st.Sessions().Upsert(ctx, &store.Session{
		Tenant: "tenant-a", Namespace: "ns-a", AgentID: agent.ID, BindingID: catalogBinding.ID,
		AgentInstanceID: selected.ID, InstanceGeneration: selected.Generation, AgentName: agent.AgentKey,
		SessionID: "diagnostic-session", Phase: store.SessionPhaseActive,
	})
	if err != nil {
		t.Fatal(err)
	}
	reportIdentity := RuntimeReportIdentity{Tenant: "tenant-a", Namespace: "ns-a",
		AgentID: agent.ID.String(), BindingID: catalogBinding.ID.String(), AgentKey: agent.AgentKey,
		InstanceKey: "instance-a", InstanceGeneration: selected.Generation}
	toolEvents := []ObservedEvent{
		{SessionID: diagnosticSession.SessionID, Seq: 1, EventType: "tool_call", ToolName: "issue.child.create",
			FrameworkMeta: json.RawMessage(`{"toolCallId":"call-1","state":"running"}`)},
		{SessionID: diagnosticSession.SessionID, Seq: 2, EventType: "tool_result", ToolName: "issue.child.create",
			ToolOutput:    "cannot scan NULL into *string",
			FrameworkMeta: json.RawMessage(`{"toolCallId":"call-1","state":"error"}`)},
	}
	if _, err = sink.ApplyEventReport(ctx, reportIdentity, toolEvents); err != nil {
		t.Fatalf("tool diagnostic report: %v", err)
	}
	// The Session event is authoritative even if task association briefly lags.
	// Linking the Session and replaying the same batch must repair the RunEvent.
	diagnosticSession, err = st.Sessions().Upsert(ctx, &store.Session{
		Tenant: "tenant-a", Namespace: "ns-a", AgentID: agent.ID, BindingID: catalogBinding.ID,
		AgentInstanceID: selected.ID, InstanceGeneration: selected.Generation, AgentName: agent.AgentKey,
		SessionID: "diagnostic-session", Phase: store.SessionPhaseActive, AgentTaskID: &task.ID,
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = sink.ApplyEventReport(ctx, reportIdentity, toolEvents); err != nil {
		t.Fatalf("tool diagnostic replay: %v", err)
	}
	if _, err = sink.ApplyEventReport(ctx, reportIdentity, toolEvents); err != nil {
		t.Fatalf("tool diagnostic second replay: %v", err)
	}
	runEvents, err := st.Orchestration().ListRunEvents(ctx, task.OrchestrationRunID, 0, 100)
	if err != nil {
		t.Fatal(err)
	}
	toolFailures := 0
	for _, event := range runEvents {
		if event.Type == "agent_tool.failed" {
			toolFailures++
			if event.AgentTaskID == nil || *event.AgentTaskID != task.ID ||
				!strings.Contains(string(event.Payload), "cannot scan NULL") ||
				!strings.Contains(string(event.Payload), diagnosticSession.ID.String()) {
				t.Fatalf("incomplete tool failure projection: %+v", event)
			}
		}
	}
	if toolFailures != 1 {
		t.Fatalf("tool failure projection count=%d events=%+v", toolFailures, runEvents)
	}
}

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

func newRuntimeReportIdentity(t *testing.T, st store.Store, tenant, namespace, agentKey, instanceKey string) RuntimeReportIdentity {
	t.Helper()
	ctx := context.Background()
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: tenant, Namespace: namespace,
		AgentKey: agentKey, DisplayName: agentKey, Status: controlmodel.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	binding, err := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{AgentID: agent.ID,
		Tenant: tenant, Namespace: namespace, Kind: controlmodel.DataPlaneExternalApplication,
		Configuration: json.RawMessage(`{"instanceSelector":{}}`), Priority: 100, Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	instance, err := st.RuntimeRegistry().UpsertAgentInstance(ctx, &controlmodel.AgentInstance{Tenant: tenant,
		Namespace: namespace, AgentID: agent.ID, BindingID: binding.ID,
		BackendKind: controlmodel.DataPlaneExternalApplication, InstanceKey: instanceKey,
		Health: controlmodel.RuntimeHealthHealthy, Capacity: 4})
	if err != nil {
		t.Fatal(err)
	}
	return RuntimeReportIdentity{Tenant: tenant, Namespace: namespace, AgentID: agent.ID.String(),
		BindingID: binding.ID.String(), AgentKey: agentKey, InstanceKey: instanceKey,
		InstanceGeneration: instance.Generation}
}

func TestApplySessionReportNewFields(t *testing.T) {
	st := newSinkTestStore(t)
	agent := newSinkTestAgent()
	c := fake.NewClientBuilder().WithScheme(newScheme()).WithObjects(agent).Build()
	sink := &SessionEventSink{Client: c, Store: st}
	identity := newRuntimeReportIdentity(t, st, "admin", "default", "agent-1", "pod-0")

	sink.ApplySessionReport(context.Background(), identity, []ObservedSession{{
		ID:                    "sess-1",
		Phase:                 "active",
		MessageCount:          12,
		Framework:             "claude-agent-sdk",
		FrameworkVersion:      "0.5.0",
		ContextHash:           "hash-1",
		IsCompacted:           true,
		EffectiveMessageCount: 3,
	}})

	sess, err := st.Sessions().Get(context.Background(), "admin", "agent-1", "default", "sess-1")
	if err != nil {
		t.Fatalf("session not stored: %v", err)
	}
	if sess.Framework != "claude-agent-sdk" || sess.FrameworkVersion != "0.5.0" {
		t.Errorf("framework fields = %q/%q", sess.Framework, sess.FrameworkVersion)
	}
	if sess.InstanceRef != "pod-0" {
		t.Errorf("instanceRef = %q", sess.InstanceRef)
	}
	if sess.AgentID.String() != identity.AgentID || sess.BindingID.String() != identity.BindingID {
		t.Errorf("stable runtime identity was not stored: %+v", sess)
	}
}

func TestApplySessionReportWithoutKubernetes(t *testing.T) {
	st := newSinkTestStore(t)
	sink := &SessionEventSink{Store: st}
	identity := newRuntimeReportIdentity(t, st, "admin", "standalone", "external-agent", "process-1")
	sink.ApplySessionReport(context.Background(), identity, []ObservedSession{{
		ID: "session-standalone", Phase: "idle", Framework: "agentscope-java",
	}})
	sess, err := st.Sessions().Get(context.Background(), "admin", "external-agent", "standalone", "session-standalone")
	if err != nil {
		t.Fatalf("standalone session not stored: %v", err)
	}
	if sess.InstanceRef != "process-1" || sess.Framework != "agentscope-java" {
		t.Fatalf("unexpected standalone session: %+v", sess)
	}
}

func TestApplySessionReportRejectsStaleGeneration(t *testing.T) {
	st := newSinkTestStore(t)
	sink := &SessionEventSink{Store: st}
	identity := newRuntimeReportIdentity(t, st, "admin", "default", "agent-1", "pod-0")
	identity.InstanceGeneration++
	sink.ApplySessionReport(context.Background(), identity, []ObservedSession{{ID: "stale", Phase: "active"}})
	rows, err := st.Sessions().List(context.Background(), store.SessionFilter{Tenant: identity.Tenant,
		AgentID: uuid.MustParse(identity.AgentID)})
	if err != nil || len(rows) != 0 {
		t.Fatalf("stale generation persisted runtime data: rows=%+v err=%v", rows, err)
	}
}

func TestApplyConversationTurnReportRequiresFrozenRuntimeIdentity(t *testing.T) {
	ctx := context.Background()
	st := newSinkTestStore(t)
	sink := &SessionEventSink{Store: st}
	identity := newRuntimeReportIdentity(t, st, "admin", "default", "agent-1", "pod-0")
	agentID := uuid.MustParse(identity.AgentID)
	bindingID := uuid.MustParse(identity.BindingID)
	instances, err := st.RuntimeRegistry().ListAgentInstances(ctx, identity.Tenant, identity.Namespace, agentID)
	if err != nil || len(instances) != 1 {
		t.Fatalf("runtime instance: rows=%+v err=%v", instances, err)
	}
	endpoint, err := st.Endpoints().Create(ctx, &controlmodel.Endpoint{Tenant: identity.Tenant,
		Namespace: identity.Namespace, Name: "chat", Slug: "chat", TargetType: controlmodel.EndpointTargetAgent,
		TargetRef: agentID, InvocationMode: controlmodel.EndpointConversationMode, Status: controlmodel.EndpointPublished})
	if err != nil {
		t.Fatal(err)
	}
	session, err := st.Sessions().Upsert(ctx, &store.Session{Tenant: identity.Tenant, Namespace: identity.Namespace,
		AgentID: agentID, BindingID: bindingID, AgentInstanceID: instances[0].ID,
		InstanceGeneration: identity.InstanceGeneration, AgentName: identity.AgentKey, InstanceRef: identity.InstanceKey,
		SessionID: "endpoint-session", OriginType: "endpoint", OriginRef: endpoint.ID.String(), Phase: store.SessionPhaseActive})
	if err != nil {
		t.Fatal(err)
	}
	conversation, err := st.Endpoints().CreateConversation(ctx, &controlmodel.EndpointConversation{EndpointID: endpoint.ID,
		AgentID: agentID, SessionID: session.SessionID, BindingID: bindingID, AgentInstanceID: instances[0].ID,
		InstanceGeneration: identity.InstanceGeneration, Status: controlmodel.EndpointConversationActive, PrincipalRef: "caller"})
	if err != nil {
		t.Fatal(err)
	}
	turnID := uuid.New()
	invocation, _, err := st.Endpoints().ReserveInvocation(ctx, &controlmodel.EndpointInvocation{EndpointID: endpoint.ID,
		Mode: controlmodel.EndpointConversationMode, PrincipalRef: "caller", Status: controlmodel.EndpointInvocationDispatching,
		ConversationID: &conversation.ID, TurnID: &turnID, SessionID: session.SessionID, CorrelationID: "corr-1"})
	if err != nil {
		t.Fatal(err)
	}
	report := ObservedConversationTurn{InvocationID: invocation.ID.String(), ConversationID: conversation.ID.String(),
		TurnID: turnID.String(), SessionID: session.SessionID, Generation: identity.InstanceGeneration,
		Action: "completed", Payload: json.RawMessage(`{"answer":"ok"}`)}
	stale := identity
	stale.InstanceGeneration++
	if err = sink.ApplyConversationTurnReport(ctx, stale, report); err == nil {
		t.Fatalf("stale generation was accepted: %v", err)
	}
	forged := identity
	forged.BindingID = uuid.NewString()
	if err = sink.ApplyConversationTurnReport(ctx, forged, report); err == nil {
		t.Fatalf("forged binding was accepted: %v", err)
	}
	if err = sink.ApplyConversationTurnReport(ctx, identity, report); err != nil {
		t.Fatalf("valid report rejected: %v", err)
	}
	stored, err := st.Endpoints().GetInvocation(ctx, invocation.ID)
	if err != nil || stored.Status != controlmodel.EndpointInvocationCompleted || stored.CompletedAt == nil {
		t.Fatalf("invocation not completed: invocation=%+v err=%v", stored, err)
	}
}

func TestApplyEventReportIdempotent(t *testing.T) {
	st := newSinkTestStore(t)
	sink := &SessionEventSink{Store: st}
	ctx := context.Background()
	identity := newRuntimeReportIdentity(t, st, "admin", "default", "agent-1", "pod-0")

	events := []ObservedEvent{
		{SessionID: "sess-x", Seq: 1, EventType: "message", Role: "user", Content: "hi", OccurredAt: time.Now().UTC()},
		{SessionID: "sess-x", Seq: 2, EventType: "message", Role: "assistant", Content: "hello", OccurredAt: time.Now().UTC()},
	}
	committed, err := sink.ApplyEventReport(ctx, identity, events)
	if err != nil || committed["sess-x"] != 2 {
		t.Fatalf("ApplyEventReport committed=%v err=%v", committed, err)
	}

	// Placeholder session must have been created for the unknown session ID.
	sess, err := st.Sessions().Get(ctx, "admin", "agent-1", "default", "sess-x")
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
	committed, err = sink.ApplyEventReport(ctx, identity, events)
	if err != nil || committed["sess-x"] != 2 {
		t.Fatalf("idempotent ApplyEventReport committed=%v err=%v", committed, err)
	}
	list, err = st.Events().List(ctx, sess.ID)
	if err != nil {
		t.Fatalf("Events.List: %v", err)
	}
	if len(list) != 2 {
		t.Errorf("expected idempotent append, got %d events", len(list))
	}
}

func TestApplyEventReportAcknowledgesOnlyContiguousDurablePrefix(t *testing.T) {
	st := newSinkTestStore(t)
	sink := &SessionEventSink{Store: st}
	ctx := context.Background()
	identity := newRuntimeReportIdentity(t, st, "admin", "default", "agent-1", "pod-0")

	committed, err := sink.ApplyEventReport(ctx, identity, []ObservedEvent{
		{SessionID: "sess-gap", Seq: 2, EventType: "message", Content: "too early"},
	})
	if err == nil || len(committed) != 0 {
		t.Fatalf("gap must not be acknowledged: committed=%v err=%v", committed, err)
	}
	committed, err = sink.ApplyEventReport(ctx, identity, []ObservedEvent{
		{SessionID: "sess-gap", Seq: 2, EventType: "message", Content: "second"},
		{SessionID: "sess-gap", Seq: 1, EventType: "message", Content: "first"},
	})
	if err != nil || committed["sess-gap"] != 2 {
		t.Fatalf("sorted contiguous report failed: committed=%v err=%v", committed, err)
	}
}

func TestApplyEventReportRepairsPreexistingStoredGap(t *testing.T) {
	ctx := context.Background()
	st := newSinkTestStore(t)
	sink := &SessionEventSink{Store: st}
	identity := newRuntimeReportIdentity(t, st, "admin", "default", "agent-1", "pod-0")
	if _, err := sink.ApplyEventReport(ctx, identity, []ObservedEvent{
		{SessionID: "sess-repair", Seq: 1, EventType: "message", Content: "stored"},
	}); err != nil {
		t.Fatal(err)
	}
	session, err := st.Sessions().Get(ctx, identity.Tenant, "agent-1", identity.Namespace, "sess-repair")
	if err != nil {
		t.Fatal(err)
	}
	if err := st.Events().Append(ctx, &store.SessionEvent{
		SessionFK: session.ID, Seq: 3, EventType: "message", Content: "stored",
	}); err != nil {
		t.Fatal(err)
	}

	committed, err := sink.ApplyEventReport(ctx, identity, []ObservedEvent{
		{SessionID: "sess-repair", Seq: 2, EventType: "message", Content: "replayed"},
	})
	if err != nil || committed["sess-repair"] != 3 {
		t.Fatalf("repair committed=%v err=%v", committed, err)
	}
	events, err := st.Events().List(ctx, session.ID)
	if err != nil || len(events) != 3 || events[1].Seq != 2 || events[1].Content != "replayed" {
		t.Fatalf("events=%+v err=%v", events, err)
	}
}

func TestApplyContextReportDedup(t *testing.T) {
	st := newSinkTestStore(t)
	sink := &SessionEventSink{Store: st}
	ctx := context.Background()
	identity := newRuntimeReportIdentity(t, st, "admin", "default", "agent-1", "pod-0")

	report := ObservedContext{
		SessionID:    "sess-c",
		ContextHash:  "hash-1",
		SystemPrompt: "sys",
		Messages:     json.RawMessage(`[{"role":"user","content":"hi"}]`),
		Framework:    "claude-agent-sdk",
	}
	sink.ApplyContextReport(ctx, identity, report)

	sess, err := st.Sessions().Get(ctx, "admin", "agent-1", "default", "sess-c")
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
	sink.ApplyContextReport(ctx, identity, report)
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
	identity := newRuntimeReportIdentity(t, st, "admin", "default", "agent-1", "pod-0")

	// Must not panic with a full inventory payload.
	sink.ApplyInventoryReport(context.Background(), identity, ObservedInventory{
		Subagents:      []ObservedSubagent{{Name: "researcher", InvokeCount: 2}},
		Workspaces:     []ObservedWorkspace{{Path: "/tmp/ws", Mode: "shared"}},
		Healthy:        true,
		ActiveSessions: 3,
	})
}

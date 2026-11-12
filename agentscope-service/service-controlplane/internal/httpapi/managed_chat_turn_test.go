// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/product"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
)

// managedChatRuntimeFake records the managed-runtime side effects so tests can
// assert the chat turn fenced and woke the data plane.
type managedChatRuntimeFake struct {
	fences []string
	wakes  []string
}

func (f *managedChatRuntimeFake) FindOrCreateSessionID(context.Context, string, string, string, string) (string, error) {
	return "sess-fake", nil
}

func (f *managedChatRuntimeFake) ClaimManagedRuntimeFence(_ context.Context, _ string,
	agentTaskID, attemptID uuid.UUID, dispatchGeneration int64, turnID string) error {
	f.fences = append(f.fences, agentTaskID.String()+":"+attemptID.String()+":"+
		strconv.FormatInt(dispatchGeneration, 10)+":"+turnID)
	return nil
}

func (f *managedChatRuntimeFake) PostSessionWakeEvent(_ context.Context, _ string, _ string, text string) error {
	f.wakes = append(f.wakes, text)
	return nil
}

func (f *managedChatRuntimeFake) PostManagedAttemptAbort(context.Context, string, string, product.ManagedAttemptAbort) error {
	return nil
}

func setupManagedConversationAgent(t *testing.T) (store.Store, *controlmodel.Agent, *controlmodel.AgentBinding) {
	t.Helper()
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "t", Namespace: "n",
		AgentKey: "managed-console-chat", DisplayName: "Managed Console Chat", Status: controlmodel.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	binding, err := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{AgentID: agent.ID,
		Tenant: "t", Namespace: "n", Kind: controlmodel.DataPlaneManaged,
		Configuration: json.RawMessage(`{"ownerRef":"user-1","managedDefinitionRef":"managed-def-1"}`),
		Enabled:       true})
	if err != nil {
		t.Fatal(err)
	}
	runtimeBinding, err := binding.RuntimeBinding()
	if err != nil {
		t.Fatal(err)
	}
	runtimeBinding.ManagedOwnerRef = "user-1"
	runtimeBinding.ManagedDefinitionRef = "managed-def-1"
	if _, err = st.Orchestration().PutRuntimePolicy(ctx, &controlmodel.AgentRuntimePolicy{Tenant: "t",
		Namespace: "n", AgentRef: agent.ID.String(), SelectionMode: "ordered", FallbackMode: "disabled",
		Candidates: []controlmodel.RuntimeBindingCandidate{{Binding: runtimeBinding}}}); err != nil {
		t.Fatal(err)
	}
	return st, agent, binding
}

// managedSessionState returns the runtime session, its current task, and the
// attempt that fence claims were recorded against.
func managedSessionState(t *testing.T, st store.Store, sessionID string) (*store.Session,
	*controlmodel.AgentTask, *controlmodel.ExecutionAttempt) {
	t.Helper()
	ctx := context.Background()
	sessions, err := st.Sessions().List(ctx, store.SessionFilter{SessionID: sessionID, Limit: 2})
	if err != nil || len(sessions) != 1 {
		t.Fatalf("runtime session: %v %v", sessions, err)
	}
	session := sessions[0]
	if session.AgentTaskID == nil {
		t.Fatalf("session has no admitted task: %+v", session)
	}
	task, err := st.Collaboration().GetAgentTask(ctx, *session.AgentTaskID)
	if err != nil {
		t.Fatal(err)
	}
	attempt, err := st.ExecutionAttempts().Get(ctx, *task.CurrentAttemptID)
	if err != nil {
		t.Fatal(err)
	}
	return session, task, attempt
}

func managedStatusEventBody(t *testing.T, task *controlmodel.AgentTask,
	attempt *controlmodel.ExecutionAttempt, seq int64) string {
	t.Helper()
	return `{"id":"evt-idle-` + strconv.FormatInt(seq, 10) + `","seq":` + strconv.FormatInt(seq, 10) +
		`,"type":"session.status_idle","payload":{"status":"idle"},` +
		`"agentTaskId":"` + task.ID.String() + `","attemptId":"` + attempt.ID.String() + `",` +
		`"dispatchGeneration":` + strconv.FormatInt(attempt.DispatchGeneration, 10) +
		`,"turnId":"` + attempt.TurnID + `"}`
}

func managedChatFixture(t *testing.T, st store.Store, agent *controlmodel.Agent,
	binding *controlmodel.AgentBinding, sessionID string) (*controlmodel.Chat, *store.Session) {
	t.Helper()
	ctx := context.Background()
	chat, err := st.Chats().Create(ctx, &controlmodel.Chat{ID: uuid.New(), Tenant: "t", Namespace: "n",
		AgentID: agent.ID, Title: "review", Status: controlmodel.ChatActive, CreatorRef: "system"})
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	if _, err = st.Sessions().Upsert(ctx, &store.Session{SessionID: sessionID, Tenant: "t",
		Namespace: "n", AgentID: agent.ID, BindingID: binding.ID, AgentName: agent.AgentKey,
		OriginType: "chat", OriginRef: chat.ID.String(), Phase: store.SessionPhaseIdle,
		StartedAt: &now, LastActiveAt: &now}); err != nil {
		t.Fatal(err)
	}
	sessions, err := st.Sessions().List(ctx, store.SessionFilter{SessionID: sessionID, Limit: 2})
	if err != nil || len(sessions) != 1 {
		t.Fatalf("fixture session: %v %v", sessions, err)
	}
	chat.SessionID = sessions[0].ID
	if _, err = st.Chats().Update(ctx, chat, chat.Version); err != nil {
		t.Fatal(err)
	}
	return chat, sessions[0]
}

// TestManagedChatTurnMirrorsEventsBackToTheConsole drives the full loop over
// HTTP: the chat turn must admit an AgentTask attempt and claim the fence, the
// mirrored status event must settle the attempt, and the settled session must
// accept the next message.
func TestManagedChatTurnMirrorsEventsBackToTheConsole(t *testing.T) {
	ctx := context.Background()
	st, agent, binding := setupManagedConversationAgent(t)
	fake := &managedChatRuntimeFake{}
	server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	server.runtimeBindings.Managed = fake

	// The Chat row and its runtime session are planted directly: Chat creation
	// itself needs the managed product to mint the data-plane session, which is
	// out of scope here — the turn admission under test is pure control-plane.
	chat, _ := managedChatFixture(t, st, agent, binding, "sess-console-chat-1")

	turnPath := "/api/v1/chats/" + chat.ID.String() + "/turns?tenant=t&namespace=n"
	turn := chatRequest(server, http.MethodPost, turnPath, `{"message":"hello"}`)
	if turn.Code != http.StatusAccepted {
		t.Fatalf("first turn: %d %s", turn.Code, turn.Body)
	}
	if len(fake.fences) != 1 || len(fake.wakes) != 1 || fake.wakes[0] != "hello" {
		t.Fatalf("turn must fence once and wake once: fences=%v wakes=%v", fake.fences, fake.wakes)
	}
	_, task, attempt := managedSessionState(t, st, "sess-console-chat-1")

	overlap := chatRequest(server, http.MethodPost, turnPath, `{"message":"overlap"}`)
	if overlap.Code != http.StatusConflict {
		t.Fatalf("overlapping turn: %d %s", overlap.Code, overlap.Body)
	}

	// The data plane runs the turn: it reports status scoped to the admitted
	// attempt, exactly as the outbox does on a real run (running first, then
	// idle when the reply is done).
	running := chatRequest(server, http.MethodPost,
		"/api/internal/runtime-sessions/sess-console-chat-1/events",
		`{"id":"evt-running-1","seq":1,"type":"session.status_running","payload":{"status":"running"},`+
			`"agentTaskId":"`+task.ID.String()+`","attemptId":"`+attempt.ID.String()+`",`+
			`"dispatchGeneration":`+strconv.FormatInt(attempt.DispatchGeneration, 10)+
			`,"turnId":"`+attempt.TurnID+`"}`)
	if running.Code != http.StatusNoContent {
		t.Fatalf("running report: %d %s", running.Code, running.Body)
	}
	started, err := st.Collaboration().GetAgentTask(ctx, task.ID)
	if err != nil {
		t.Fatal(err)
	}
	if started.Status != controlmodel.AgentTaskRunning {
		t.Fatalf("running report must start the task: %s", started.Status)
	}
	_, refreshedTask, settledAttempt := managedSessionState(t, st, "sess-console-chat-1")
	settled := chatRequest(server, http.MethodPost,
		"/api/internal/runtime-sessions/sess-console-chat-1/events",
		managedStatusEventBody(t, refreshedTask, settledAttempt, 2))
	if settled.Code != http.StatusNoContent {
		t.Fatalf("status report: %d %s", settled.Code, settled.Body)
	}
	finalTask, err := st.Collaboration().GetAgentTask(ctx, task.ID)
	if err != nil {
		t.Fatal(err)
	}
	if finalTask.Status != controlmodel.AgentTaskCompleted {
		t.Fatalf("mirrored idle must complete the chat attempt: status=%s code=%s message=%s",
			finalTask.Status, finalTask.ErrorCode, finalTask.ErrorMessage)
	}
	finalAttempt, err := st.ExecutionAttempts().Get(ctx, attempt.ID)
	if err != nil {
		t.Fatal(err)
	}
	if finalAttempt.State != controlmodel.ExecutionSucceeded {
		t.Fatalf("attempt must be terminal: %s", finalAttempt.State)
	}

	again := chatRequest(server, http.MethodPost, turnPath, `{"message":"second"}`)
	if again.Code != http.StatusAccepted {
		t.Fatalf("settled session must accept the next message: %d %s", again.Code, again.Body)
	}
	if len(fake.wakes) != 2 || fake.wakes[1] != "second" {
		t.Fatalf("second turn side effects: wakes=%v", fake.wakes)
	}
}

// TestManagedStatusEventLeavesSchedulerTasksAlone pins the guard: only
// chat-origin sessions settle from mirrored status events.
func TestManagedStatusEventLeavesSchedulerTasksAlone(t *testing.T) {
	ctx := context.Background()
	st, agent, binding := setupManagedConversationAgent(t)
	server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	server.runtimeBindings.Managed = &managedChatRuntimeFake{}

	// A scheduler-owned session: agent-task origin, open attempt, no fence.
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{ID: uuid.New(), Tenant: "t",
		Namespace: "n", Title: "scheduled work", Status: controlmodel.IssueInProgress, Priority: "normal",
		Kind: controlmodel.IssueKindUserWork, Visibility: controlmodel.IssueVisibilityOperational,
		CompletionPolicy: controlmodel.IssueCompletionAutomatic})
	if err != nil {
		t.Fatal(err)
	}
	run, err := st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{ID: uuid.New(),
		Tenant: "t", Namespace: "n", RootIssueID: issue.ID, Mode: controlmodel.RunModeDirect,
		TriggerType: "test", State: controlmodel.RunRunning})
	if err != nil {
		t.Fatal(err)
	}
	node, err := st.Orchestration().CreateNode(ctx, &controlmodel.RunNode{ID: uuid.New(), RunID: run.ID,
		Tenant: "t", Namespace: "n", NodeKey: "target", Type: controlmodel.RunNodeAgent,
		IssueID: &issue.ID, State: controlmodel.RunNodeReady, Iteration: 1})
	if err != nil {
		t.Fatal(err)
	}
	runtimeBinding, err := binding.RuntimeBinding()
	if err != nil {
		t.Fatal(err)
	}
	task, err := st.Collaboration().CreateRunAgentTask(ctx, store.RunTaskRequest{RunID: run.ID,
		NodeID: node.ID, IssueID: issue.ID, AgentRef: agent.ID.String(),
		Originator: controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "test"}})
	if err != nil {
		t.Fatal(err)
	}
	snapshot, _ := json.Marshal(controlmodel.RuntimeDispatchSnapshot{Binding: runtimeBinding,
		SessionID: "sess-agent-task", ResolvedAt: time.Now().UTC()})
	dispatched, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx, store.TaskClaim{
		TaskID: task.ID, ExpectedVersion: task.Version, RuntimeBinding: snapshot, SessionID: "sess-agent-task",
	}, &controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
		AgentID: agent.ID, BindingID: binding.ID, State: controlmodel.ExecutionAssigned,
		ManagedOwnerRef: "user-1", ManagedAgentRef: "managed-def-1",
		SessionID: "sess-agent-task", TurnID: uuid.NewString()})
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	if _, err = st.Sessions().Upsert(ctx, &store.Session{SessionID: "sess-agent-task", Tenant: "t",
		Namespace: "n", AgentID: agent.ID, BindingID: binding.ID, AgentName: agent.AgentKey,
		OriginType: "agent-task", OriginRef: dispatched.ID.String(), Phase: store.SessionPhaseActive,
		AgentTaskID: &dispatched.ID, StartedAt: &now, LastActiveAt: &now}); err != nil {
		t.Fatal(err)
	}

	reported := chatRequest(server, http.MethodPost, "/api/internal/runtime-sessions/sess-agent-task/events",
		managedStatusEventBody(t, dispatched, attempt, 2))
	if reported.Code != http.StatusNoContent {
		t.Fatalf("status report: %d %s", reported.Code, reported.Body)
	}
	finalTask, err := st.Collaboration().GetAgentTask(ctx, dispatched.ID)
	if err != nil {
		t.Fatal(err)
	}
	if finalTask.Status == controlmodel.AgentTaskCompleted {
		t.Fatal("scheduler task must not settle from a mirrored status event")
	}
}

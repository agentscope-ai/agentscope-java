// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package runtimebinding

import (
	"context"
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/product"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
)

// fakeManagedRuntime records the managed-runtime side effects so tests can
// assert the ceremony ordering: the fence claim must precede the wake, and the
// wake must carry the raw chat message.
type fakeManagedRuntime struct {
	fenceErr error
	fences   []string
	wakes    []string
}

func (f *fakeManagedRuntime) FindOrCreateSessionID(context.Context, string, string, string, string) (string, error) {
	return "sess-fake", nil
}

func (f *fakeManagedRuntime) ClaimManagedRuntimeFence(_ context.Context, _ string,
	agentTaskID, attemptID uuid.UUID, dispatchGeneration int64, turnID string) error {
	if f.fenceErr != nil {
		return f.fenceErr
	}
	f.fences = append(f.fences, agentTaskID.String()+":"+attemptID.String()+":"+
		strconv.FormatInt(dispatchGeneration, 10)+":"+turnID)
	return nil
}

func (f *fakeManagedRuntime) PostSessionWakeEvent(_ context.Context, _ string, _ string, text string) error {
	f.wakes = append(f.wakes, text)
	return nil
}

func (f *fakeManagedRuntime) PostManagedAttemptAbort(context.Context, string, string, product.ManagedAttemptAbort) error {
	return nil
}

func setupManagedChatFixture(t *testing.T) (store.Store, *controlmodel.Agent, controlmodel.RuntimeBinding) {
	t.Helper()
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "t", Namespace: "n",
		AgentKey: "managed-chat", DisplayName: "Managed Chat", Status: controlmodel.AgentActive})
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
	return st, agent, runtimeBinding
}

func managedChatSession(t *testing.T, st store.Store, agent *controlmodel.Agent,
	bindingID uuid.UUID, sessionID string) *store.Session {
	t.Helper()
	ctx := context.Background()
	chat, err := st.Chats().Create(ctx, &controlmodel.Chat{ID: uuid.New(), Tenant: "t", Namespace: "n",
		AgentID: agent.ID, Title: "review", Status: controlmodel.ChatActive, CreatorRef: "user-1"})
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	created, err := st.Sessions().Upsert(ctx, &store.Session{SessionID: sessionID, Tenant: "t",
		Namespace: "n", AgentID: agent.ID, BindingID: bindingID, AgentName: agent.AgentKey,
		OriginType: "chat", OriginRef: chat.ID.String(), Phase: store.SessionPhaseIdle,
		StartedAt: &now, LastActiveAt: &now})
	if err != nil {
		t.Fatal(err)
	}
	sessions, err := st.Sessions().List(ctx, store.SessionFilter{SessionID: sessionID, Limit: 2})
	if err != nil || len(sessions) != 1 {
		t.Fatalf("fixture session: %v %v", sessions, err)
	}
	created = sessions[0]
	chat.SessionID = created.ID
	if _, err = st.Chats().Update(ctx, chat, chat.Version); err != nil {
		t.Fatal(err)
	}
	return created
}

func TestDispatchManagedChatTurnRunsTheAgentTaskCeremony(t *testing.T) {
	ctx := context.Background()
	st, agent, runtimeBinding := setupManagedChatFixture(t)
	session := managedChatSession(t, st, agent, runtimeBinding.BindingID, "sess-chat-1")
	fake := &fakeManagedRuntime{}
	resolver := &Resolver{Store: st, Managed: fake}

	task, attempt, err := resolver.DispatchManagedChatTurn(ctx, session, runtimeBinding, "user-1", "hello there")
	if err != nil {
		t.Fatalf("dispatch: %v", err)
	}
	if task == nil || attempt == nil || attempt.TurnID == "" ||
		attempt.BackendKind != controlmodel.DataPlaneManaged || attempt.ManagedOwnerRef != "user-1" {
		t.Fatalf("admission incomplete: task=%+v attempt=%+v", task, attempt)
	}
	if len(fake.fences) != 1 || len(fake.wakes) != 1 || fake.wakes[0] != "hello there" {
		t.Fatalf("managed side effects: fences=%v wakes=%v", fake.fences, fake.wakes)
	}
	if !strings.HasPrefix(fake.fences[0], task.ID.String()+":"+attempt.ID.String()+":") {
		t.Fatalf("fence does not match the admitted attempt: %s", fake.fences[0])
	}

	runtime, err := st.Sessions().List(ctx, store.SessionFilter{SessionID: "sess-chat-1", Limit: 2})
	if err != nil || len(runtime) != 1 || runtime[0].AgentTaskID == nil || *runtime[0].AgentTaskID != task.ID {
		t.Fatalf("runtime session binding: %+v err=%v", runtime, err)
	}
	if runtime[0].OriginType != "chat" || runtime[0].Phase != store.SessionPhaseActive {
		t.Fatalf("chat identity must survive admission: %+v", runtime[0])
	}

	// The conversation issue is operational bookkeeping: hidden from the
	// default issue list, visible only under its explicit kind.
	items, listErr := st.Collaboration().ListIssues(ctx, store.IssueFilter{Tenant: "t", Namespace: "n"})
	if listErr != nil || len(items) != 0 {
		t.Fatalf("chat turn leaked into default issues: items=%+v err=%v", items, listErr)
	}
	turns, listErr := st.Collaboration().ListIssues(ctx, store.IssueFilter{Tenant: "t", Namespace: "n",
		Kind: controlmodel.IssueKindConversationTurn})
	if listErr != nil || len(turns) != 1 {
		t.Fatalf("conversation issue missing: items=%+v err=%v", turns, listErr)
	}

	// The runtime event log carries the user message immediately, before the
	// data plane reports anything back.
	events, err := st.Events().List(ctx, runtime[0].ID)
	if err != nil {
		t.Fatal(err)
	}
	sawUserMessage := false
	for _, event := range events {
		if event.EventType == "user.message" && event.Content == "hello there" {
			sawUserMessage = true
		}
	}
	if !sawUserMessage {
		t.Fatalf("user message missing from runtime events: %+v", events)
	}

	// A second message while the first attempt is open must conflict, mirroring
	// hosted conversations.
	_, _, err = resolver.DispatchManagedChatTurn(ctx, session, runtimeBinding, "user-1", "again")
	if !errors.Is(err, store.ErrConflict) {
		t.Fatalf("overlapping turn: err=%v want conflict", err)
	}
	if len(fake.wakes) != 1 {
		t.Fatalf("conflicting turn must not wake: wakes=%v", fake.wakes)
	}
}

func TestDispatchManagedChatTurnRequeuesWhenFenceClaimFails(t *testing.T) {
	ctx := context.Background()
	st, agent, runtimeBinding := setupManagedChatFixture(t)
	session := managedChatSession(t, st, agent, runtimeBinding.BindingID, "sess-chat-2")
	fake := &fakeManagedRuntime{fenceErr: errors.New("fence unavailable")}
	resolver := &Resolver{Store: st, Managed: fake}

	_, _, err := resolver.DispatchManagedChatTurn(ctx, session, runtimeBinding, "user-1", "hello")
	if err == nil {
		t.Fatal("fence failure must surface")
	}
	if len(fake.wakes) != 0 {
		t.Fatalf("no wake may be posted without a fence: wakes=%v", fake.wakes)
	}
	runtime, err := st.Sessions().List(ctx, store.SessionFilter{SessionID: "sess-chat-2", Limit: 2})
	if err != nil || len(runtime) != 1 {
		t.Fatalf("runtime session: %v %v", runtime, err)
	}
	if runtime[0].AgentTaskID == nil {
		t.Fatalf("expected the requeued task to stay bound for diagnosis")
	}
	requeued, err := st.Collaboration().GetAgentTask(ctx, *runtime[0].AgentTaskID)
	if err != nil {
		t.Fatal(err)
	}
	if requeued.Status != controlmodel.AgentTaskQueued {
		t.Fatalf("task must go back to the queue on fence failure: %s", requeued.Status)
	}
}

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"context"
	"testing"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestAttachAttemptSessionRefsUsesControlPlaneSessionIdentity(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	taskID, agentID := uuid.New(), uuid.New()
	runtimeSessionID := uuid.NewString()
	session, err := st.Sessions().Upsert(ctx, &store.Session{
		Tenant: "tenant-a", Namespace: "default", AgentID: agentID, AgentName: "leader",
		SessionID: runtimeSessionID, Phase: store.SessionPhaseIdle, AgentTaskID: &taskID,
	})
	if err != nil {
		t.Fatal(err)
	}
	attempt := &controlmodel.ExecutionAttempt{Tenant: session.Tenant, Namespace: session.Namespace,
		AgentID: agentID, AgentTaskID: taskID, SessionID: runtimeSessionID}
	server := &Server{store: st}
	server.attachAttemptSessionRefs(ctx, []*controlmodel.ExecutionAttempt{attempt})

	if attempt.SessionRef == nil || *attempt.SessionRef != session.ID || attempt.SessionID == session.ID.String() {
		t.Fatalf("attempt session identity was not disambiguated: attempt=%+v session=%+v", attempt, session)
	}
}

func TestHostedAttemptSessionRefSurvivesNextConversationTurn(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	oldTask, newTask, agent, binding := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	session, err := st.Sessions().Upsert(ctx, &store.Session{Tenant: "t", Namespace: "n", AgentID: agent, BindingID: binding, SessionID: "same-runtime", AgentTaskID: &newTask, Phase: store.SessionPhaseIdle})
	if err != nil {
		t.Fatal(err)
	}
	attempt := &controlmodel.ExecutionAttempt{Tenant: "t", Namespace: "n", AgentID: agent, BindingID: binding, AgentTaskID: oldTask, SessionID: session.SessionID, BackendKind: controlmodel.DataPlaneHostedRuntime}
	srv := &Server{store: st}
	srv.attachAttemptSessionRefs(ctx, []*controlmodel.ExecutionAttempt{attempt})
	if attempt.SessionRef == nil || *attempt.SessionRef != session.ID {
		t.Fatal("older hosted turn lost its shared session reference")
	}
	attempt.SessionRef = nil
	attempt.BindingID = uuid.New()
	srv.attachAttemptSessionRefs(ctx, []*controlmodel.ExecutionAttempt{attempt})
	if attempt.SessionRef != nil {
		t.Fatal("linked to another runtime binding")
	}
}

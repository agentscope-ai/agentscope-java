// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package httpapi

import (
	"context"
	"encoding/json"
	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"testing"
)

func TestSessionRuntimeUsesExactExternalGeneration(t *testing.T) {
	st, agent, _, instance := setupConversationAgent(t)
	ctx := context.Background()
	server := NewServer(ServerOptions{Store: st})
	session, err := server.resolveAgentConversation(ctx, agent, "", "runtime", "")
	if err != nil {
		t.Fatal(err)
	}
	instance.Framework = "adk"
	instance.FrameworkVersion = "2.1"
	instance, err = st.RuntimeRegistry().UpsertAgentInstance(ctx, instance)
	if err != nil {
		t.Fatal(err)
	}
	session.InstanceGeneration = instance.Generation
	item := SessionWithSnapshot{Session: session}
	server.enrichSessionRuntime(ctx, session, &item)
	if item.Runtime.Kind != model.DataPlaneExternalApplication || item.Runtime.Framework != "adk" || item.InstanceHealthy == nil || !*item.InstanceHealthy {
		t.Fatalf("wrong runtime: %+v", item)
	}
	session.InstanceGeneration++
	item = SessionWithSnapshot{Session: session}
	server.enrichSessionRuntime(ctx, session, &item)
	if item.InstanceHealthy != nil || len(item.Capabilities) != 0 {
		t.Fatal("stale generation provided health/capabilities")
	}
}

func TestSessionRuntimePrefersFrozenAttemptWithoutExposingConfiguration(t *testing.T) {
	st, agent, binding, _ := setupHostedConversationAgent(t)
	ctx := context.Background()
	server := NewServer(ServerOptions{Store: st})
	session, err := server.resolveAgentConversation(ctx, agent, "", "runtime", "")
	if err != nil {
		t.Fatal(err)
	}
	turn, err := server.dispatchHostedConversationTurn(ctx, session, binding, "hello", uuid.NewString(), "runtime", "")
	if err != nil {
		t.Fatal(err)
	}
	attempt, err := st.ExecutionAttempts().Create(ctx, &model.ExecutionAttempt{AgentTaskID: turn.AgentTaskID, AgentID: agent.ID, BindingID: binding.ID, Tenant: agent.Tenant, Namespace: agent.Namespace, SessionID: session.SessionID, SessionRef: &session.ID, BackendKind: model.DataPlaneHostedRuntime, State: model.ExecutionQueued, RuntimeBinding: json.RawMessage(`{"runtimeProfile":{"name":"Frozen profile","provider":"qoder","configuration":{"secret":"must-not-leak"}},"runtimePool":{"name":"Frozen pool"}}`)})
	if err != nil {
		t.Fatal(err)
	}
	item := SessionWithSnapshot{Session: session}
	server.enrichSessionRuntime(ctx, session, &item)
	if item.Runtime.Source != "execution_attempt" || item.Runtime.Provider != "qoder" || item.Runtime.AttemptID == nil || *item.Runtime.AttemptID != attempt.ID || item.Runtime.Pool != "Frozen pool" {
		t.Fatalf("not using frozen dispatch: %+v", item.Runtime)
	}
	data, _ := json.Marshal(item.Runtime)
	var fields map[string]any
	_ = json.Unmarshal(data, &fields)
	if _, ok := fields["configuration"]; ok {
		t.Fatal("runtime configuration leaked")
	}
}

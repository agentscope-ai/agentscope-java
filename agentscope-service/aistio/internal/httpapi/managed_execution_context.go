// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"context"
	"encoding/json"
	"time"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// managedExecutionContextForSession is intentionally internal-only. The task
// token is scoped to the current attempt and must never be persisted in the
// public session row or emitted as a user message.
func (s *Server) managedExecutionContextForSession(ctx context.Context, sessionID string) json.RawMessage {
	if s == nil || s.store == nil || sessionID == "" {
		return nil
	}
	sessions, err := s.store.Sessions().List(ctx, store.SessionFilter{SessionID: sessionID, Limit: 2})
	if err != nil || len(sessions) != 1 || sessions[0].AgentTaskID == nil {
		return nil
	}
	session := sessions[0]
	task, err := s.store.Collaboration().GetAgentTask(ctx, *session.AgentTaskID)
	if err != nil || task.CurrentAttemptID == nil {
		return nil
	}
	attempt, err := s.store.ExecutionAttempts().Get(ctx, *task.CurrentAttemptID)
	if err != nil || attempt.BackendKind != controlmodel.DataPlaneManaged ||
		attempt.SessionID != sessionID || controlmodel.IsExecutionAttemptTerminal(attempt.State) {
		return nil
	}
	envelope, err := (&collaboration.Service{Store: s.store}).BuildContext(ctx, task.ID)
	if err != nil {
		return nil
	}
	envelope.TaskToken, err = s.taskTokens.MintScoped(
		task.ID, attempt.ID, attempt.DispatchGeneration, time.Now().UTC())
	if err != nil {
		return nil
	}
	raw, err := json.Marshal(map[string]any{
		"taskContext":          envelope,
		"attemptId":            attempt.ID,
		"dispatchGeneration":   attempt.DispatchGeneration,
		"turnId":               attempt.TurnID,
		"collaborationMcpPath": "/mcp/collaboration",
	})
	if err != nil {
		return nil
	}
	return raw
}

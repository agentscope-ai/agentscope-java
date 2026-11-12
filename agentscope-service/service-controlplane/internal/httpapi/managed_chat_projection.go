// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
)

// projectManagedChatTurnTerminal closes a chat turn's AgentTask attempt when
// the data plane reports the turn outcome through the mirrored session status.
//
// Scheduler tasks complete through the collaboration tool's task.complete; a
// chat agent only answers, so the mirrored turn outcome is the completion
// signal: status_idle means the reply is done (a text-only reply is a legitimate
// completion for chat, unlike the scheduler's managed_turn_incomplete rule),
// and status_failed fails the attempt instead of leaving it running until the
// lease expires. Hosted conversations get the equivalent from the taskplane
// callbacks; managed chat gets it from the mirror.
//
// Only chat-origin sessions settle here. Scheduler-owned sessions report
// status_idle mid-life by design and must stay on the tool-completion path.
// Called from applyManagedSessionStatus after the report's attempt fence has
// been matched, so no additional fencing is needed. Returns true when this
// projection settled the attempt.
func (s *Server) projectManagedChatTurnTerminal(ctx context.Context, session *store.Session,
	task *controlmodel.AgentTask, report *managedSessionEventReport) (bool, error) {
	if session.OriginType != "chat" || task == nil || task.CurrentAttemptID == nil {
		return false, nil
	}
	var succeeded bool
	switch report.Type {
	case "session.status_idle":
		succeeded = true
	case "session.status_failed":
		succeeded = false
	default:
		// status_terminated and session.error keep the generic projections:
		// they carry more precise failure codes for scheduler tasks and chat
		// sessions alike.
		return false, nil
	}
	if controlmodel.IsAgentTaskTerminal(task.Status) {
		return false, nil
	}
	attempt, err := s.store.ExecutionAttempts().Get(ctx, *task.CurrentAttemptID)
	if err != nil || attempt.BackendKind != controlmodel.DataPlaneManaged ||
		attempt.SessionID != session.SessionID || controlmodel.IsExecutionAttemptTerminal(attempt.State) {
		return false, err
	}
	failureMessage := ""
	if succeeded {
		_, err = s.store.Collaboration().CompleteAgentTask(ctx, task.ID, store.TaskCompletion{
			ExpectedVersion:    task.Version,
			AttemptID:          attempt.ID,
			DispatchGeneration: attempt.DispatchGeneration,
			Summary:            "chat turn completed",
		})
	} else {
		failureMessage = managedChatFailureMessage(report)
		_, err = s.store.Collaboration().FailAgentTask(ctx, task.ID, task.Version,
			"chat_turn_failed", failureMessage)
	}
	if err != nil {
		return false, err
	}
	if err := s.appendManagedChatTurnOutcome(ctx, session.ID, attempt.ID, succeeded, failureMessage); err != nil {
		return false, err
	}
	return true, nil
}

// appendManagedChatTurnOutcome records the turn outcome as a session event,
// mirroring the hosted attempt projection so both conversation surfaces render
// the same timeline. The caller holds the session lock and has already synced
// the session phase for this report.
func (s *Server) appendManagedChatTurnOutcome(ctx context.Context, sessionFK, attemptID uuid.UUID,
	succeeded bool, failureMessage string) error {
	now := time.Now().UTC()
	eventType, sourceKey, role, content := "turn.completed", "turn-completed:"+attemptID.String(), "", ""
	if !succeeded {
		eventType, sourceKey, role, content = "turn.failed", "turn-failed:"+attemptID.String(), "error", failureMessage
	}
	return s.appendSessionEventLocked(ctx, sessionFK, sourceKey, &store.SessionEvent{
		EventType: eventType, Role: role, Content: content, OccurredAt: now,
		FrameworkMeta: mustJSON(map[string]any{"attemptId": attemptID.String()}),
	})
}

func managedChatFailureMessage(report *managedSessionEventReport) string {
	if stopReason, ok := report.Payload["stopReason"].(map[string]any); ok {
		if reason, ok := stopReason["reason"].(string); ok && reason != "" {
			return reason
		}
		if failure, ok := stopReason["error"].(map[string]any); ok {
			if raw, marshalErr := json.Marshal(failure); marshalErr == nil {
				return string(raw)
			}
		}
	}
	return "chat turn failed"
}

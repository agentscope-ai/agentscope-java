// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

const managedExecutionLeaseTTL = 45 * time.Second

type managedSessionEventReport struct {
	ID          string         `json:"id"`
	SessionID   string         `json:"sessionId"`
	Seq         int64          `json:"seq"`
	Type        string         `json:"type"`
	Payload     map[string]any `json:"payload"`
	ProcessedAt *int64         `json:"processedAt,omitempty"`
	CreatedAt   int64          `json:"createdAt"`
	AttemptID   string         `json:"attemptId,omitempty"`
	DispatchGen int64          `json:"dispatchGeneration,omitempty"`
	TurnID      string         `json:"turnId,omitempty"`
}

// reportManagedSessionEvent projects the managed data-plane event log into
// the runtime store used by Chat, Operate, SSE, and attempt lifecycle views.
// The source event id is the idempotency key across retries and replicas.
func (s *Server) reportManagedSessionEvent(c *gin.Context) {
	sessionID := strings.TrimSpace(c.Param("sessionId"))
	var report managedSessionEventReport
	if err := c.ShouldBindJSON(&report); err != nil || sessionID == "" ||
		strings.TrimSpace(report.ID) == "" || strings.TrimSpace(report.Type) == "" || report.Seq <= 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "id, sessionId, positive seq, and type are required"})
		return
	}
	if report.SessionID != "" && report.SessionID != sessionID {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "sessionId does not match request path"})
		return
	}
	sessions, err := s.store.Sessions().List(c.Request.Context(), store.SessionFilter{SessionID: sessionID, Limit: 2})
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	if len(sessions) == 0 {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "runtime session not found"})
		return
	}
	if len(sessions) != 1 {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "runtime session id is ambiguous"})
		return
	}
	session := sessions[0]
	event := managedReportToSessionEvent(&report)
	err = s.store.WithSessionLock(c.Request.Context(), session.ID.String(), func(lockCtx context.Context) error {
		sourceKey := "managed:" + report.ID
		duplicate, err := s.sessionEventSourceExists(lockCtx, session.ID, sourceKey)
		if err != nil || duplicate {
			return err
		}
		if err = s.applyManagedSessionStatus(lockCtx, session, &report, event.OccurredAt); err != nil {
			return err
		}
		return s.appendSessionEventLocked(lockCtx, session.ID, sourceKey, event)
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) sessionEventSourceExists(ctx context.Context, sessionFK uuid.UUID, sourceKey string) (bool, error) {
	events, err := s.store.Events().List(ctx, sessionFK)
	if err != nil {
		return false, err
	}
	for _, event := range events {
		var metadata map[string]any
		if json.Unmarshal(event.FrameworkMeta, &metadata) == nil && metadata["sourceKey"] == sourceKey {
			return true, nil
		}
	}
	return false, nil
}

func managedReportToSessionEvent(report *managedSessionEventReport) *store.SessionEvent {
	payload := report.Payload
	if payload == nil {
		payload = map[string]any{}
	}
	eventType := strings.TrimSpace(report.Type)
	event := &store.SessionEvent{EventType: eventType, OccurredAt: managedEventTime(report.CreatedAt)}
	switch {
	case eventType == "user.message":
		event.Role = "user"
	case eventType == "agent.tool_result":
		event.Role = "tool"
	case strings.HasPrefix(eventType, "agent."):
		event.Role = "assistant"
	case strings.HasPrefix(eventType, "system.") || strings.HasPrefix(eventType, "session."):
		event.Role = "system"
	}
	event.Content = firstPayloadString(payload, "text", "message")
	event.ToolName = firstPayloadString(payload, "toolName", "name")
	event.ToolOutput = firstPayloadString(payload, "output")
	if input, ok := payload["input"]; ok && input != nil {
		event.ToolInput, _ = json.Marshal(input)
	}
	metadata := map[string]any{"managedEventId": report.ID, "managedSeq": report.Seq}
	for _, key := range []string{"toolCallId", "toolUseId", "tool_use_id", "callId"} {
		if value, ok := payload[key]; ok && value != nil {
			metadata[key] = value
		}
	}
	event.FrameworkMeta, _ = json.Marshal(metadata)
	return event
}

func managedEventTime(createdAt int64) time.Time {
	if createdAt <= 0 {
		return time.Now().UTC()
	}
	return time.UnixMilli(createdAt).UTC()
}

func firstPayloadString(payload map[string]any, keys ...string) string {
	for _, key := range keys {
		if value, ok := payload[key]; ok && value != nil {
			if text, ok := value.(string); ok {
				return text
			}
		}
	}
	return ""
}

func (s *Server) applyManagedSessionStatus(ctx context.Context, session *store.Session, report *managedSessionEventReport, at time.Time) error {
	eventType := report.Type
	var task *controlmodel.AgentTask
	if session.AgentTaskID != nil {
		var err error
		task, err = s.store.Collaboration().GetAgentTask(ctx, *session.AgentTaskID)
		if err != nil {
			return err
		}
		matches, matchErr := s.managedReportMatchesAttempt(ctx, task, report)
		if matchErr != nil || !matches {
			return matchErr
		}
	}
	phase := ""
	switch eventType {
	case "session.status_running":
		phase = store.SessionPhaseActive
	case "session.status_idle":
		phase = store.SessionPhaseIdle
	case "session.status_terminated":
		phase = store.SessionPhaseTerminated
	case "session.status_archived":
		phase = store.SessionPhaseArchived
	}
	if phase != "" {
		copy := *session
		busy := phase == store.SessionPhaseActive
		copy.Phase, copy.Busy, copy.LastActiveAt = phase, &busy, &at
		if phase == store.SessionPhaseActive && copy.StartedAt == nil {
			copy.StartedAt = &at
		}
		if phase == store.SessionPhaseTerminated {
			copy.TerminatedAt = &at
		}
		if _, err := s.store.Sessions().Upsert(ctx, &copy); err != nil {
			return err
		}
		if err := s.store.Turns().SyncOnPhase(ctx, session.ID, phase); err != nil {
			return err
		}
	}
	if task == nil {
		return nil
	}
	if eventType == "session.status_running" {
		if task.Status == controlmodel.AgentTaskRunning {
			return nil
		}
		if task.Status != controlmodel.AgentTaskDispatched {
			return fmt.Errorf("managed session started for AgentTask in state %s", task.Status)
		}
		_, err := s.store.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
		return err
	}
	if controlmodel.IsAgentTaskTerminal(task.Status) {
		return nil
	}
	code, message := "", ""
	switch eventType {
	case "session.error":
		code, message = managedTurnError(report.Payload)
	case "session.status_idle":
		code, message = "managed_task_incomplete", "managed Agent turn became idle without completing or failing its AgentTask"
	case "session.status_terminated":
		code, message = "managed_session_terminated", "managed Agent session terminated before its AgentTask reached a terminal state"
	}
	if code == "" {
		return nil
	}
	_, err := (&collaboration.Service{Store: s.store}).FailTask(ctx, task.ID, task.Version, code, message)
	return err
}

func (s *Server) managedReportMatchesAttempt(ctx context.Context, task *controlmodel.AgentTask, report *managedSessionEventReport) (bool, error) {
	if strings.TrimSpace(report.AttemptID) == "" {
		return true, nil
	}
	attemptID, err := uuid.Parse(report.AttemptID)
	if err != nil || task.CurrentAttemptID == nil || *task.CurrentAttemptID != attemptID {
		return false, nil
	}
	attempt, err := s.store.ExecutionAttempts().Get(ctx, attemptID)
	if err != nil {
		return false, err
	}
	if report.DispatchGen > 0 && attempt.DispatchGeneration != report.DispatchGen ||
		report.TurnID != "" && attempt.TurnID != report.TurnID {
		return false, nil
	}
	return true, nil
}

func managedTurnError(payload map[string]any) (string, string) {
	code, message := "managed_turn_failed", "managed Agent turn failed"
	if raw, ok := payload["error"].(map[string]any); ok {
		if value := firstPayloadString(raw, "code"); value != "" {
			code = value
		}
		if value := firstPayloadString(raw, "message", "detail"); value != "" {
			message = value
		}
	}
	if len(message) > 2048 {
		message = message[:2048]
	}
	return code, message
}

type managedSessionHeartbeat struct {
	AttemptID   uuid.UUID `json:"attemptId"`
	DispatchGen int64     `json:"dispatchGeneration"`
	TurnID      string    `json:"turnId"`
}

func (s *Server) heartbeatManagedSession(c *gin.Context) {
	sessionID := strings.TrimSpace(c.Param("sessionId"))
	var heartbeat managedSessionHeartbeat
	if err := c.ShouldBindJSON(&heartbeat); err != nil || sessionID == "" || heartbeat.AttemptID == uuid.Nil || heartbeat.DispatchGen <= 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "sessionId, attemptId, and dispatchGeneration are required"})
		return
	}
	sessions, err := s.store.Sessions().List(c, store.SessionFilter{SessionID: sessionID, Limit: 2})
	if err != nil || len(sessions) != 1 || sessions[0].AgentTaskID == nil {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "managed task session not found"})
		return
	}
	task, err := s.store.Collaboration().GetAgentTask(c, *sessions[0].AgentTaskID)
	if err != nil || task.CurrentAttemptID == nil || *task.CurrentAttemptID != heartbeat.AttemptID {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "managed execution attempt is no longer current"})
		return
	}
	attempt, err := s.store.ExecutionAttempts().Get(c, heartbeat.AttemptID)
	if err != nil || attempt.BackendKind != controlmodel.DataPlaneManaged ||
		attempt.DispatchGeneration != heartbeat.DispatchGen || heartbeat.TurnID != "" && attempt.TurnID != heartbeat.TurnID {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "managed execution attempt scope does not match"})
		return
	}
	if controlmodel.IsExecutionAttemptTerminal(attempt.State) {
		c.Status(http.StatusNoContent)
		return
	}
	if _, err = s.store.ExecutionAttempts().RenewLease(c, attempt.ID, attempt.LeaseToken, attempt.FencingToken, managedExecutionLeaseTTL); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

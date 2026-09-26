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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/orchestration"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

const managedExecutionLeaseTTL = 45 * time.Second

var (
	errManagedAttemptGone = errors.New("managed execution attempt is no longer active")
)

type managedSessionEventReport struct {
	ID          string         `json:"id"`
	SessionID   string         `json:"sessionId"`
	Seq         int64          `json:"seq"`
	Type        string         `json:"type"`
	Payload     map[string]any `json:"payload"`
	ProcessedAt *int64         `json:"processedAt,omitempty"`
	CreatedAt   int64          `json:"createdAt"`
	AgentTaskID string         `json:"agentTaskId,omitempty"`
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
	report.SessionID = sessionID
	normalizeManagedConnectionWarning(&report)
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
		if applyErr := s.applyManagedSessionStatus(lockCtx, session, &report, event.OccurredAt); applyErr != nil {
			return applyErr
		}
		if err := s.projectManagedToolFailure(lockCtx, session, &report, event); err != nil {
			return err
		}
		if err := s.projectManagedEndpointTurn(lockCtx, session, &report, event.OccurredAt); err != nil {
			return err
		}
		return s.appendSessionEventLocked(lockCtx, session.ID, sourceKey, event)
	})
	if err != nil {
		switch {
		case errors.Is(err, errManagedAttemptGone):
			c.JSON(http.StatusGone, ErrorResponse{Error: err.Error()})
		case errors.Is(err, store.ErrConflict):
			c.JSON(http.StatusConflict, ErrorResponse{Error: err.Error()})
		default:
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		}
		return
	}
	c.Status(http.StatusNoContent)
}

// Optional connection callbacks use the MCP-specific error type. Fatal bootstrap
// failures use api_error, also with retry_status=next_turn: retryability alone does
// not mean the current turn can continue. Preserve fatal errors and unknown forms.
func normalizeManagedConnectionWarning(report *managedSessionEventReport) {
	if report.Type != "session.error" {
		return
	}
	detail, _ := report.Payload["error"].(map[string]any)
	if detail["type"] == "mcp_connection_failed_error" && detail["code"] == "mcp_connection_failed_error" && detail["retry_status"] == "next_turn" {
		report.Type = "session.warning"
	}
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
	case strings.HasPrefix(eventType, "user."):
		event.Role = "user"
	case eventType == "agent.tool_result":
		event.Role = "tool"
	case strings.HasPrefix(eventType, "agent."):
		event.Role = "assistant"
	case strings.HasPrefix(eventType, "system.") || strings.HasPrefix(eventType, "session."):
		event.Role = "system"
	}
	event.Content = firstPayloadString(payload, "text", "message")
	if event.Content == "" {
		if detail, ok := payload["error"].(map[string]any); ok {
			event.Content = firstPayloadString(detail, "message", "code")
		}
	}
	event.ToolName = firstPayloadString(payload, "toolName", "name")
	event.ToolOutput = firstPayloadString(payload, "output")
	if input, ok := payload["input"]; ok && input != nil {
		event.ToolInput, _ = json.Marshal(input)
	}
	metadata := map[string]any{
		"managedEventId": report.ID, "managedSeq": report.Seq,
		"agentTaskId": report.AgentTaskID,
		"attemptId":   report.AttemptID, "dispatchGeneration": report.DispatchGen,
		"turnId": report.TurnID,
	}
	for _, key := range []string{"toolCallId", "toolUseId", "tool_use_id", "callId"} {
		if value, ok := payload[key]; ok && value != nil {
			metadata[key] = value
		}
	}
	for _, key := range []string{"approvalId", "decisionVersion", "source", "status", "state", "endpointInvocationId", "endpointTurnId", "truncated", "originalSize", "usage", "error"} {
		if value, ok := payload[key]; ok && value != nil {
			metadata[key] = value
		}
	}
	event.FrameworkMeta, _ = json.Marshal(metadata)
	return event
}

// Endpoint identity travels with the durable user message. Resolve it by source
// sequence, never by the most recently active invocation or wall-clock time:
// delayed completion from turn A must not complete turn B.
func (s *Server) projectManagedEndpointTurn(ctx context.Context, session *store.Session, report *managedSessionEventReport, at time.Time) error {
	if session.AgentTaskID != nil || session.OriginType != "endpoint" {
		return nil
	}
	switch report.Type {
	case "session.status_idle", "session.error", "session.status_terminated":
	default:
		return nil
	}
	events, err := s.store.Events().List(ctx, session.ID)
	if err != nil {
		return err
	}
	var source map[string]any
	var sourceSeq int64
	for _, event := range events {
		var meta map[string]any
		if event.EventType != "user.message" || json.Unmarshal(event.FrameworkMeta, &meta) != nil {
			continue
		}
		seq, _ := meta["managedSeq"].(float64)
		if int64(seq) > sourceSeq && int64(seq) < report.Seq {
			source, sourceSeq = meta, int64(seq)
		}
	}
	id, err := uuid.Parse(firstPayloadString(source, "endpointInvocationId"))
	if err != nil {
		return nil // ordinary chat messages do not create public invocations
	}
	invocation, err := s.store.Endpoints().GetInvocation(ctx, id)
	if err != nil {
		return err
	}
	if invocation.Mode != controlmodel.EndpointConversationMode || invocation.ConversationID == nil ||
		invocation.SessionID != session.SessionID || invocation.TurnID == nil ||
		invocation.TurnID.String() != firstPayloadString(source, "endpointTurnId") {
		return store.ErrConflict
	}
	conversation, err := s.store.Endpoints().GetConversation(ctx, *invocation.ConversationID)
	if err != nil {
		return err
	}
	if conversation.EndpointID.String() != session.OriginRef || conversation.EndpointID != invocation.EndpointID ||
		conversation.AgentID != session.AgentID || conversation.BindingID != session.BindingID {
		return store.ErrConflict
	}
	if endpointInvocationTerminal(invocation.Status) {
		return nil
	}
	invocation.CompletedAt = &at
	switch report.Type {
	case "session.error":
		invocation.Status = controlmodel.EndpointInvocationFailed
		invocation.ErrorCode, invocation.ErrorMessage = managedTurnError(report.Payload)
		if err := s.store.Turns().SyncOnPhase(ctx, session.ID, "failed"); err != nil {
			return err
		}
	case "session.status_terminated":
		invocation.Status = controlmodel.EndpointInvocationCancelled
	default:
		var answer []string
		for _, event := range events {
			var meta map[string]any
			if event.EventType != "agent.message" || json.Unmarshal(event.FrameworkMeta, &meta) != nil {
				continue
			}
			seq, _ := meta["managedSeq"].(float64)
			if int64(seq) > sourceSeq && int64(seq) < report.Seq && event.Content != "" {
				answer = append(answer, event.Content)
			}
		}
		invocation.Status = controlmodel.EndpointInvocationCompleted
		invocation.Result, _ = json.Marshal(strings.Join(answer, "\n\n"))
	}
	if _, err = s.store.Endpoints().UpdateInvocation(ctx, invocation); err != nil {
		return err
	}
	conversation.LastTurnAt = &at
	_, err = s.store.Endpoints().UpdateConversation(ctx, conversation)
	return err
}

// Project both remote MCP errors and errors raised locally before any request
// (for example schema validation). The attempt fence is checked before this
// function, and the shared call ID deduplicates the remote and session paths.
func (s *Server) projectManagedToolFailure(ctx context.Context, session *store.Session,
	report *managedSessionEventReport, event *store.SessionEvent) error {
	state := strings.ToLower(firstPayloadString(report.Payload, "state"))
	if report.Type != "agent.tool_result" || session.AgentTaskID == nil ||
		(state != "error" && state != "denied" && state != "interrupted") {
		return nil
	}
	task, err := s.store.Collaboration().GetAgentTask(ctx, *session.AgentTaskID)
	if err != nil {
		return err
	}
	callID := firstPayloadString(report.Payload, "toolCallId", "toolUseId", "tool_use_id", "callId")
	key := "managed-tool-failed:" + report.ID
	if callID != "" {
		key = fmt.Sprintf("agent-tool-failed:%s:%s", task.ID, callID)
	}
	payload, err := json.Marshal(map[string]any{"toolName": event.ToolName, "toolCallId": callID,
		"state": state, "message": event.ToolOutput, "source": "managed_session",
		"sessionId": session.SessionID, "sessionRef": session.ID, "managedEventId": report.ID})
	if err != nil {
		return err
	}
	_, err = s.store.Orchestration().AppendRunEvent(ctx, &controlmodel.RunEvent{
		RunID: task.OrchestrationRunID, Tenant: task.Tenant, Namespace: task.Namespace,
		NodeID: &task.RunNodeID, AgentTaskID: &task.ID, AttemptID: task.CurrentAttemptID,
		Type: "agent_tool.failed", Actor: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef},
		Payload: payload, CausationID: callID, CorrelationID: task.CorrelationID, IdempotencyKey: key})
	return err
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
	if strings.TrimSpace(report.SessionID) == "" && session != nil {
		report.SessionID = session.SessionID
	}
	eventType := report.Type
	var task *controlmodel.AgentTask
	if session.AgentTaskID != nil {
		var err error
		task, err = s.store.Collaboration().GetAgentTask(ctx, *session.AgentTaskID)
		if err != nil {
			return err
		}
		matches, matchErr := s.managedReportMatchesAttempt(ctx, task, report)
		if !matches {
			if matchErr != nil {
				return matchErr
			}
			return store.ErrConflict
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
	if eventType == "session.requires_action" {
		if controlmodel.IsAgentTaskTerminal(task.Status) {
			return errManagedAttemptGone
		}
		if firstPayloadString(report.Payload, "kind") == "tool_confirmation" {
			return s.requestManagedToolApproval(ctx, session, task, report, at)
		}
		// Other suspension reasons (for example provider TOOL_SUSPENDED) are
		// observability events, not human confirmation requests. Mirror them
		// without inventing an Approval or changing the AgentTask lifecycle.
		return nil
	}
	if eventType == "user.tool_confirmation" {
		return s.resumeManagedTaskFromConfirmationEvent(ctx, session, task, report)
	}
	if eventType == "session.status_running" {
		if task.Status == controlmodel.AgentTaskRunning {
			return nil
		}
		if task.Status == controlmodel.AgentTaskWaiting {
			if strings.HasPrefix(task.WaitReason, "approval:") {
				return s.resumeManagedTaskFromSessionStatus(ctx, session, task, report)
			}
			_, err := s.store.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
			return err
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
	case "session.status_idle":
		// The provider turn completed normally, but an attached AgentTask is a
		// semantic protocol: it must explicitly complete or fail. Convert a
		// text-only return into an immediate durable outcome instead of leaving
		// the task running until its heartbeat lease expires.
		code, message = "managed_turn_incomplete", s.managedIncompleteTurnMessage(ctx, session, report)
	case "session.error":
		code, message = managedTurnError(report.Payload)
	case "session.status_terminated":
		code, message = "managed_session_terminated", "managed Agent session terminated before its AgentTask reached a terminal state"
	}
	if code == "" {
		return nil
	}
	// Give a declared agent step one chance to correct a missing completion call.
	// Requeue the same task/node with a fresh attempt fence; never treat prose as success.
	if code == "managed_turn_incomplete" && report.DispatchGen == 1 && task.TeamID == nil {
		run, err := s.store.Orchestration().GetRun(ctx, task.OrchestrationRunID)
		if err != nil {
			return err
		}
		if run.Mode == controlmodel.RunModeDeclared || run.Mode == controlmodel.RunModeSubrun {
			_, _, err := s.store.Collaboration().RequeueAgentTaskAfterAttemptFailure(ctx, task.ID, store.TaskFailure{
				ExpectedVersion: task.Version, AttemptID: *task.CurrentAttemptID, DispatchGeneration: report.DispatchGen, Code: code, Message: message})
			return err
		}
	}
	failed, err := (&collaboration.Service{Store: s.store}).FailTask(ctx, task.ID, task.Version, code, message)
	if err != nil {
		return err
	}
	return (&orchestration.Engine{Store: s.store}).ReconcileRun(context.WithoutCancel(ctx), failed.OrchestrationRunID)
}

func (s *Server) managedIncompleteTurnMessage(ctx context.Context, session *store.Session,
	report *managedSessionEventReport) string {
	const base = "managed Agent turn ended without task.complete or task.fail"
	if session == nil {
		return base
	}
	events, err := s.store.Events().List(ctx, session.ID)
	if err != nil {
		return base
	}
	for index := len(events) - 1; index >= 0; index-- {
		event := events[index]
		if event.EventType != "agent.message" || strings.TrimSpace(event.Content) == "" {
			continue
		}
		var metadata map[string]any
		if json.Unmarshal(event.FrameworkMeta, &metadata) == nil && strings.TrimSpace(report.AttemptID) != "" {
			if attemptID, _ := metadata["attemptId"].(string); attemptID != report.AttemptID {
				continue
			}
		}
		text := []rune(strings.TrimSpace(event.Content))
		if len(text) > 2000 {
			text = append(text[:2000], []rune("…")...)
		}
		return base + "\nLast response: " + string(text)
	}
	return base
}

func (s *Server) managedReportMatchesAttempt(ctx context.Context, task *controlmodel.AgentTask, report *managedSessionEventReport) (bool, error) {
	if strings.TrimSpace(report.AgentTaskID) == "" || strings.TrimSpace(report.AttemptID) == "" {
		// Managed task events are physical-turn reports. Accepting an unscoped event
		// would let a delayed event from an older turn mutate the current retry.
		return false, store.ErrConflict
	}
	reportedTaskID, err := uuid.Parse(report.AgentTaskID)
	if err != nil || reportedTaskID != task.ID {
		return false, store.ErrConflict
	}
	attemptID, err := uuid.Parse(report.AttemptID)
	if err != nil {
		return false, store.ErrConflict
	}
	if task.CurrentAttemptID == nil || *task.CurrentAttemptID != attemptID ||
		task.Status == controlmodel.AgentTaskFailed || task.Status == controlmodel.AgentTaskCancelled ||
		(task.Status != controlmodel.AgentTaskDispatched && task.Status != controlmodel.AgentTaskRunning &&
			task.Status != controlmodel.AgentTaskWaiting && task.Status != controlmodel.AgentTaskCompleted) {
		return false, errManagedAttemptGone
	}
	attempt, err := s.store.ExecutionAttempts().Get(ctx, attemptID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return false, errManagedAttemptGone
		}
		return false, err
	}
	if attempt.BackendKind != controlmodel.DataPlaneManaged || attempt.AgentTaskID != task.ID ||
		attempt.SessionID != report.SessionID || report.DispatchGen <= 0 || strings.TrimSpace(report.TurnID) == "" ||
		attempt.DispatchGeneration != report.DispatchGen || attempt.TurnID != report.TurnID {
		return false, store.ErrConflict
	}
	if attempt.State == controlmodel.ExecutionSucceeded && task.Status == controlmodel.AgentTaskCompleted {
		// The collaboration tool can commit semantic completion before the agent
		// emits its final assistant text. Preserve that tail without renewing the
		// already-terminal lease.
		return true, nil
	}
	if controlmodel.IsExecutionAttemptTerminal(attempt.State) || attempt.State == controlmodel.ExecutionCancelRequested ||
		task.Status == controlmodel.AgentTaskCompleted {
		return false, errManagedAttemptGone
	}
	// The event stream is an authenticated, fenced proof that this managed turn
	// is alive. Renew here as a second liveness channel in addition to the
	// periodic data-plane heartbeat. This keeps collaboration credentials valid
	// when a deployment temporarily misses the heartbeat loop, while the
	// attempt/generation/turn fence still rejects stale physical turns.
	if _, err = s.store.ExecutionAttempts().RenewLease(ctx, attempt.ID,
		attempt.LeaseToken, attempt.FencingToken, managedExecutionLeaseTTL); err != nil {
		return false, err
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
	if err := c.ShouldBindJSON(&heartbeat); err != nil || sessionID == "" || heartbeat.AttemptID == uuid.Nil ||
		heartbeat.DispatchGen <= 0 || strings.TrimSpace(heartbeat.TurnID) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "sessionId, attemptId, dispatchGeneration, and turnId are required"})
		return
	}
	sessions, err := s.store.Sessions().List(c, store.SessionFilter{SessionID: sessionID, Limit: 2})
	if err != nil || len(sessions) != 1 || sessions[0].AgentTaskID == nil {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "managed task session not found"})
		return
	}
	task, err := s.store.Collaboration().GetAgentTask(c, *sessions[0].AgentTaskID)
	if errors.Is(err, store.ErrNotFound) || err == nil &&
		(task.CurrentAttemptID == nil || *task.CurrentAttemptID != heartbeat.AttemptID ||
			task.Status == controlmodel.AgentTaskFailed || task.Status == controlmodel.AgentTaskCancelled ||
			(task.Status != controlmodel.AgentTaskDispatched && task.Status != controlmodel.AgentTaskRunning &&
				task.Status != controlmodel.AgentTaskWaiting && task.Status != controlmodel.AgentTaskCompleted)) {
		c.JSON(http.StatusGone, ErrorResponse{Error: errManagedAttemptGone.Error()})
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	attempt, err := s.store.ExecutionAttempts().Get(c, heartbeat.AttemptID)
	if errors.Is(err, store.ErrNotFound) {
		c.JSON(http.StatusGone, ErrorResponse{Error: errManagedAttemptGone.Error()})
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	if attempt.BackendKind != controlmodel.DataPlaneManaged || attempt.AgentTaskID != task.ID ||
		attempt.SessionID != sessionID ||
		attempt.DispatchGeneration != heartbeat.DispatchGen || heartbeat.TurnID != "" && attempt.TurnID != heartbeat.TurnID {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "managed execution attempt scope does not match"})
		return
	}
	if attempt.State == controlmodel.ExecutionSucceeded && task.Status == controlmodel.AgentTaskCompleted {
		// A final heartbeat can race the assistant's tail after semantic success.
		// Acknowledge it without renewing a terminal Attempt so the stream can end
		// naturally; failed/cancelled/replaced scopes below are actively stopped.
		c.Status(http.StatusNoContent)
		return
	}
	if controlmodel.IsExecutionAttemptTerminal(attempt.State) || attempt.State == controlmodel.ExecutionCancelRequested ||
		task.Status == controlmodel.AgentTaskCompleted {
		c.JSON(http.StatusGone, ErrorResponse{Error: errManagedAttemptGone.Error()})
		return
	}
	if _, err = s.store.ExecutionAttempts().RenewLease(c, attempt.ID, attempt.LeaseToken, attempt.FencingToken, managedExecutionLeaseTTL); err != nil {
		if errors.Is(err, store.ErrConflict) {
			currentTask, taskErr := s.store.Collaboration().GetAgentTask(c, task.ID)
			currentAttempt, attemptErr := s.store.ExecutionAttempts().Get(c, attempt.ID)
			if taskErr == nil && attemptErr == nil &&
				(controlmodel.IsAgentTaskTerminal(currentTask.Status) ||
					currentTask.CurrentAttemptID == nil || *currentTask.CurrentAttemptID != attempt.ID ||
					controlmodel.IsExecutionAttemptTerminal(currentAttempt.State) ||
					currentAttempt.State == controlmodel.ExecutionCancelRequested) {
				c.JSON(http.StatusGone, ErrorResponse{Error: errManagedAttemptGone.Error()})
				return
			}
		}
		s.writeControlPlaneError(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

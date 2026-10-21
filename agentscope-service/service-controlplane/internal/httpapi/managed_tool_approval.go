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
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
)

const (
	managedToolApprovalTTL      = time.Hour
	runtimeToolApprovalLeaseTTL = 45 * time.Second
)

type managedToolApprovalEnvelope struct {
	Kind               string                     `json:"kind"`
	BackendKind        controlmodel.DataPlaneKind `json:"backendKind,omitempty"`
	SchemaVersion      int32                      `json:"schemaVersion"`
	Tenant             string                     `json:"tenant"`
	Namespace          string                     `json:"namespace"`
	SessionID          string                     `json:"sessionId"`
	ApprovalID         uuid.UUID                  `json:"approvalId"`
	AgentTaskID        uuid.UUID                  `json:"agentTaskId"`
	AttemptID          uuid.UUID                  `json:"attemptId"`
	DispatchGeneration int64                      `json:"dispatchGeneration"`
	TurnID             string                     `json:"turnId"`
	ToolUseID          string                     `json:"toolUseId"`
	ToolName           string                     `json:"toolName"`
	InputPreview       json.RawMessage            `json:"inputPreview,omitempty"`
	InputSHA256        string                     `json:"inputSha256,omitempty"`
	RequestedAt        time.Time                  `json:"requestedAt"`
	ExpiresAt          time.Time                  `json:"expiresAt"`
}

func (e managedToolApprovalEnvelope) fence() store.ManagedToolApprovalFence {
	return store.ManagedToolApprovalFence{BackendKind: e.BackendKind, SessionID: e.SessionID, TaskID: e.AgentTaskID,
		AttemptID: e.AttemptID, ApprovalID: e.ApprovalID, DispatchGeneration: e.DispatchGeneration,
		TurnID: e.TurnID, ToolUseID: e.ToolUseID, ToolName: e.ToolName, InputSHA256: e.InputSHA256}
}

type runtimeToolApprovalRequest struct {
	Kind         string `json:"kind"`
	ToolUseID    string `json:"toolUseId"`
	ToolName     string `json:"toolName"`
	InputPreview any    `json:"inputPreview,omitempty"`
	Input        any    `json:"input,omitempty"`
	InputSHA256  string `json:"inputSha256,omitempty"`
	ExpiresAt    any    `json:"expiresAt,omitempty"`
}

// requestRuntimeToolApproval is the shared pull-based HITL ingress used by
// hosted runtimes and external applications. Identity and execution fencing
// always come from the task token, never from the request body.
func (s *Server) requestRuntimeToolApproval(c *gin.Context) {
	task, ok := taskPrincipal(c)
	if !ok || task.CurrentAttemptID == nil {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "AgentTask has no active execution Attempt"})
		return
	}
	var req runtimeToolApprovalRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	req.ToolUseID, req.ToolName = strings.TrimSpace(req.ToolUseID), strings.TrimSpace(req.ToolName)
	if req.Kind != "" && req.Kind != "tool_confirmation" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "unsupported runtime approval kind"})
		return
	}
	if req.ToolUseID == "" || req.ToolName == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "toolUseId and toolName are required"})
		return
	}
	attempt, err := s.store.ExecutionAttempts().Get(c.Request.Context(), *task.CurrentAttemptID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if attempt.BackendKind != controlmodel.DataPlaneHostedRuntime &&
		attempt.BackendKind != controlmodel.DataPlaneExternalApplication {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "runtime approval endpoint requires a hosted or external execution"})
		return
	}
	if s.portalExecution() && attempt.State != controlmodel.ExecutionWaiting {
		if err = serviceapi.CheckTaskPortalExecution(c.Request.Context(), s.store, task); err != nil {
			c.JSON(http.StatusForbidden, ErrorResponse{Error: "new approval requires execution authorization"})
			return
		}
	}
	payload := map[string]any{"inputPreview": req.InputPreview, "input": req.Input,
		"inputSha256": req.InputSHA256, "expiresAt": req.ExpiresAt}
	inputPreview, inputSHA, err := managedApprovalInput(payload)
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	now := time.Now().UTC()
	expiresAt := managedApprovalExpiry(payload, now)
	approvalID := store.RuntimeToolApprovalID(attempt.BackendKind, task.Tenant, attempt.SessionID,
		attempt.ID.String(), attempt.DispatchGeneration, attempt.TurnID, req.ToolUseID)
	fence := store.ManagedToolApprovalFence{BackendKind: attempt.BackendKind,
		SessionID: attempt.SessionID, TaskID: task.ID, AttemptID: attempt.ID,
		ApprovalID: approvalID, DispatchGeneration: attempt.DispatchGeneration,
		TurnID: attempt.TurnID, ToolUseID: req.ToolUseID, ToolName: req.ToolName, InputSHA256: inputSHA}
	if err = store.ValidateManagedToolApprovalFence(task, attempt, fence); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	approver := strings.TrimSpace(task.AccountableHumanRef)
	if approver == "" {
		if issue, loadErr := s.store.Collaboration().GetIssue(c.Request.Context(), task.IssueID); loadErr == nil &&
			issue.Creator.Type == controlmodel.ActorHuman {
			approver = strings.TrimSpace(issue.Creator.Ref)
		}
	}
	if approver == "" {
		_, _, _ = s.store.Collaboration().FailAgentTaskWithAttempt(c.Request.Context(), task.ID,
			store.TaskFailure{ExpectedVersion: task.Version, AttemptID: attempt.ID,
				DispatchGeneration: attempt.DispatchGeneration, Code: "hitl_approver_unavailable",
				Message: "runtime tool confirmation has no accountable human"})
		c.JSON(http.StatusConflict, ErrorResponse{Error: "runtime tool confirmation has no accountable human"})
		return
	}
	envelope := managedToolApprovalEnvelope{Kind: controlmodel.ApprovalRequestKindRuntimeToolConfirmation,
		BackendKind: attempt.BackendKind, SchemaVersion: 1,
		Tenant: task.Tenant, Namespace: task.Namespace, SessionID: attempt.SessionID,
		ApprovalID: approvalID, AgentTaskID: task.ID, AttemptID: attempt.ID,
		DispatchGeneration: attempt.DispatchGeneration, TurnID: attempt.TurnID,
		ToolUseID: req.ToolUseID, ToolName: req.ToolName, InputPreview: inputPreview,
		InputSHA256: inputSHA, RequestedAt: now, ExpiresAt: expiresAt}
	request, err := json.Marshal(envelope)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	approval := &controlmodel.Approval{ID: approvalID, Tenant: task.Tenant,
		Namespace: task.Namespace, TargetType: controlmodel.ApprovalTargetExecutionAttempt,
		TargetRef: attempt.ID.String(), IssueID: &task.IssueID, RunID: &task.OrchestrationRunID,
		RunNodeID: &task.RunNodeID, RequestedBy: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef},
		ApproverRef: approver, Status: controlmodel.ApprovalPending,
		Reason: "Allow " + string(attempt.BackendKind) + " agent to use tool " + req.ToolName + "?", Request: request}
	approval, _, _, err = s.store.Collaboration().CreateManagedToolApproval(c.Request.Context(),
		store.ManagedToolApprovalRequest{Fence: fence, Approval: approval})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"approval": approval})
}

func (s *Server) getRuntimeToolApprovalDecision(c *gin.Context) {
	task, ok := taskPrincipal(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, ErrorResponse{Error: "task token is required"})
		return
	}
	approvalID, ok := parseUUIDParam(c, "approvalId")
	if !ok {
		return
	}
	approval, err := s.store.Collaboration().GetApproval(c.Request.Context(), approvalID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	envelope, err := parseManagedToolApproval(approval)
	if err != nil || envelope.AgentTaskID != task.ID || envelope.BackendKind == controlmodel.DataPlaneManaged {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "approval is outside the runtime task scope"})
		return
	}
	if approval.Status == controlmodel.ApprovalPending {
		c.JSON(http.StatusAccepted, gin.H{"approvalId": approval.ID, "status": approval.Status})
		return
	}
	if err = s.checkRuntimeApprovalDelivery(c.Request.Context(), approval); err != nil {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "approval delivery requires execution authorization"})
		return
	}
	if _, err = s.validateManagedApprovalCurrent(c.Request.Context(), approval); err != nil {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "runtime approval no longer matches the active execution"})
		return
	}
	if _, _, err = s.store.Collaboration().ResumeManagedToolApproval(c.Request.Context(), approval.ID,
		envelope.fence(), runtimeToolApprovalLeaseTTL); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"approvalId": approval.ID, "decisionVersion": approval.Version,
		"status": approval.Status, "allow": approval.Status == controlmodel.ApprovalApproved,
		"denyMessage": managedApprovalDenyMessage(approval)})
}

func (s *Server) acknowledgeRuntimeToolApprovalDecision(c *gin.Context) {
	task, ok := taskPrincipal(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, ErrorResponse{Error: "task token is required"})
		return
	}
	approvalID, ok := parseUUIDParam(c, "approvalId")
	if !ok {
		return
	}
	var req struct {
		DecisionVersion int64 `json:"decisionVersion"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.DecisionVersion <= 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "positive decisionVersion is required"})
		return
	}
	approval, err := s.store.Collaboration().GetApproval(c.Request.Context(), approvalID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	envelope, err := parseManagedToolApproval(approval)
	if err != nil || envelope.AgentTaskID != task.ID || envelope.BackendKind == controlmodel.DataPlaneManaged ||
		approval.Status == controlmodel.ApprovalPending || approval.Version != req.DecisionVersion {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "runtime approval decision fence does not match"})
		return
	}
	if _, err = s.validateManagedApprovalCurrent(c.Request.Context(), approval); err != nil {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "runtime approval no longer matches the active execution"})
		return
	}
	if err = s.checkRuntimeApprovalDelivery(c.Request.Context(), approval); err != nil {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "approval delivery requires execution authorization"})
		return
	}
	if err = s.recordManagedApprovalDelivered(c.Request.Context(), approval, envelope); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	c.Status(http.StatusNoContent)
}

func managedApprovalInput(payload map[string]any) (json.RawMessage, string, error) {
	value, ok := payload["inputPreview"]
	if !ok || value == nil {
		value = payload["input"]
	}
	if value == nil {
		return nil, firstPayloadString(payload, "inputSha256"), nil
	}
	original, err := json.Marshal(value)
	if err != nil {
		return nil, "", err
	}
	hash := firstPayloadString(payload, "inputSha256")
	if hash == "" {
		sum := sha256.Sum256(original)
		hash = fmt.Sprintf("%x", sum[:])
	}
	preview, err := json.Marshal(redactManagedApprovalInput(value))
	if err != nil {
		return nil, "", err
	}
	if len(preview) > 16*1024 {
		preview, _ = json.Marshal(map[string]any{"redacted": true, "reason": "input_preview_too_large"})
	}
	return preview, hash, nil
}

func redactManagedApprovalInput(value any) any {
	switch typed := value.(type) {
	case map[string]any:
		out := make(map[string]any, len(typed))
		for key, child := range typed {
			lower := strings.ToLower(key)
			normalized := strings.NewReplacer("_", "", "-", "", ".", "", " ", "").Replace(lower)
			if strings.Contains(lower, "token") || strings.Contains(lower, "secret") ||
				strings.Contains(lower, "password") || strings.Contains(lower, "authorization") ||
				strings.Contains(lower, "credential") || strings.Contains(lower, "api_key") ||
				strings.Contains(normalized, "apikey") || strings.Contains(normalized, "accesskey") ||
				strings.Contains(normalized, "privatekey") {
				out[key] = "[REDACTED]"
			} else {
				out[key] = redactManagedApprovalInput(child)
			}
		}
		return out
	case []any:
		out := make([]any, len(typed))
		for i := range typed {
			out[i] = redactManagedApprovalInput(typed[i])
		}
		return out
	default:
		return value
	}
}

func managedApprovalExpiry(payload map[string]any, at time.Time) time.Time {
	if at.IsZero() {
		at = time.Now().UTC()
	}
	if raw := firstPayloadString(payload, "expiresAt"); raw != "" {
		if parsed, err := time.Parse(time.RFC3339Nano, raw); err == nil {
			return parsed.UTC()
		}
	}
	if raw, ok := payload["expiresAt"].(float64); ok && raw > 0 {
		return time.UnixMilli(int64(raw)).UTC()
	}
	return at.Add(managedToolApprovalTTL)
}

func parseManagedToolApproval(approval *controlmodel.Approval) (managedToolApprovalEnvelope, error) {
	var envelope managedToolApprovalEnvelope
	if approval == nil || approval.TargetType != controlmodel.ApprovalTargetExecutionAttempt ||
		json.Unmarshal(approval.Request, &envelope) != nil {
		return envelope, store.ErrConflict
	}
	validKind := envelope.Kind == store.RuntimeToolApprovalRequestKind(envelope.BackendKind)
	validBackend := envelope.BackendKind == controlmodel.DataPlaneHostedRuntime ||
		envelope.BackendKind == controlmodel.DataPlaneExternalApplication
	if !validKind || !validBackend || envelope.SchemaVersion != 1 ||
		envelope.ApprovalID != approval.ID ||
		envelope.Tenant != approval.Tenant || envelope.Namespace != approval.Namespace ||
		approval.TargetRef != envelope.AttemptID.String() {
		return envelope, store.ErrConflict
	}
	return envelope, nil
}

// validateManagedApprovalCurrent performs the same full fence check at human
// decision time and again immediately before durable outbox delivery.
func (s *Server) validateManagedApprovalCurrent(ctx context.Context, approval *controlmodel.Approval) (managedToolApprovalEnvelope, error) {
	envelope, err := parseManagedToolApproval(approval)
	if err != nil {
		return envelope, err
	}
	task, err := s.store.Collaboration().GetAgentTask(ctx, envelope.AgentTaskID)
	if err != nil {
		return envelope, err
	}
	attempt, err := s.store.ExecutionAttempts().Get(ctx, envelope.AttemptID)
	if err != nil {
		return envelope, err
	}
	if err = store.ValidateManagedToolApprovalFence(task, attempt, envelope.fence()); err != nil {
		return envelope, err
	}
	if err = store.ValidateManagedToolApproval(approval, task, envelope.fence()); err != nil {
		return envelope, err
	}
	waitingForThisApproval := task.Status == controlmodel.AgentTaskWaiting &&
		task.WaitReason == "approval:"+approval.ID.String() &&
		attempt.State == controlmodel.ExecutionWaiting
	runningAfterDecision := approval.Status != controlmodel.ApprovalPending &&
		task.Status == controlmodel.AgentTaskRunning && attempt.State == controlmodel.ExecutionRunning
	if !waitingForThisApproval && !runningAfterDecision {
		// Identity fencing alone is insufficient here: cancellation can leave the
		// same Attempt attached to the logical Task. A decision must never release
		// a tool after that Task/Attempt stopped being the active continuation.
		return envelope, store.ErrConflict
	}
	return envelope, nil
}

// DispatchApprovalDecision records whether a pull-based runtime can still read
// its durable human decision. Managed tool confirmations use native Session
// actions and Inputs; they never enter this callback/lease protocol.
func (s *Server) DispatchApprovalDecision(ctx context.Context, approvalID uuid.UUID) error {
	approval, err := s.store.Collaboration().GetApproval(ctx, approvalID)
	if err != nil {
		return err
	}
	if approval.TargetType != controlmodel.ApprovalTargetExecutionAttempt {
		return nil
	}
	envelope, err := parseManagedToolApproval(approval)
	if err != nil {
		return nil
	}
	if approval.Status == controlmodel.ApprovalPending {
		return fmt.Errorf("runtime tool approval is still pending")
	}
	_, err = s.validateManagedApprovalCurrent(ctx, approval)
	if errors.Is(err, store.ErrConflict) || errors.Is(err, store.ErrNotFound) {
		return s.recordManagedApprovalDeliveryStale(ctx, approval, envelope, "stale_execution_fence", err.Error())
	}
	// Hosted/External resume only when the runtime fetches this exact decision.
	return err
}

func firstMetadataInt64(metadata map[string]any, key string) int64 {
	switch value := metadata[key].(type) {
	case float64:
		return int64(value)
	case int64:
		return value
	case json.Number:
		parsed, _ := value.Int64()
		return parsed
	default:
		return 0
	}
}

// DispatchManagedAttemptAbort drains a physical managed turn after its fenced
// Attempt was failed/replaced while waiting for HITL. It is invoked through the
// durable control outbox, so transient data-plane outages cannot leave the old
// waiter blocking a fresh Attempt forever.
func (s *Server) DispatchManagedAttemptAbort(ctx context.Context, attemptID uuid.UUID) error {
	return s.CancelManagedAttempt(ctx, attemptID)
}

// recordManagedApprovalDelivered separates the human decision fact from the
// data-plane delivery fact without requiring mutable delivery columns on the
// Approval row. Replaying the durable outbox/callback is safe and reuses this
// single timeline event.
func (s *Server) recordManagedApprovalDelivered(ctx context.Context,
	approval *controlmodel.Approval, envelope managedToolApprovalEnvelope) error {
	if approval == nil || approval.RunID == nil {
		return nil
	}
	payload, err := json.Marshal(map[string]any{
		"approvalId": approval.ID, "decisionVersion": approval.Version,
		"status": approval.Status, "toolUseId": envelope.ToolUseID,
		"backendKind": envelope.BackendKind,
	})
	if err != nil {
		return err
	}
	taskID, attemptID := envelope.AgentTaskID, envelope.AttemptID
	_, err = s.store.Orchestration().AppendRunEvent(ctx, &controlmodel.RunEvent{
		RunID: *approval.RunID, Tenant: approval.Tenant, Namespace: approval.Namespace,
		NodeID: approval.RunNodeID, AgentTaskID: &taskID, AttemptID: &attemptID,
		Type:    "approval.delivery_delivered",
		Actor:   controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "approval-dispatcher"},
		Payload: payload, IdempotencyKey: fmt.Sprintf("approval-delivery:%s:%d", approval.ID, approval.Version),
	})
	return err
}

// recordManagedApprovalDeliveryStale makes a permanently lost continuation
// visible in the Run timeline. Delivered and stale share one first-write-wins
// idempotency key: event retention or a process crash must never let a later
// replay publish the opposite terminal delivery outcome.
func (s *Server) recordManagedApprovalDeliveryStale(ctx context.Context,
	approval *controlmodel.Approval, envelope managedToolApprovalEnvelope, reason, message string) error {
	if approval == nil || approval.RunID == nil {
		return nil
	}
	payload, err := json.Marshal(map[string]any{
		"approvalId": approval.ID, "decisionVersion": approval.Version,
		"status": approval.Status, "toolUseId": envelope.ToolUseID,
		"backendKind": envelope.BackendKind,
		"reason":      reason, "message": message,
	})
	if err != nil {
		return err
	}
	taskID, attemptID := envelope.AgentTaskID, envelope.AttemptID
	_, err = s.store.Orchestration().AppendRunEvent(ctx, &controlmodel.RunEvent{
		RunID: *approval.RunID, Tenant: approval.Tenant, Namespace: approval.Namespace,
		NodeID: approval.RunNodeID, AgentTaskID: &taskID, AttemptID: &attemptID,
		Type:    "approval.delivery_stale",
		Actor:   controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "approval-dispatcher"},
		Payload: payload, IdempotencyKey: fmt.Sprintf("approval-delivery:%s:%d", approval.ID, approval.Version),
	})
	return err
}

func managedApprovalDenyMessage(approval *controlmodel.Approval) string {
	if approval == nil || approval.Status == controlmodel.ApprovalApproved {
		return ""
	}
	var decision map[string]any
	if json.Unmarshal(approval.Decision, &decision) == nil {
		if message := firstPayloadString(decision, "denyMessage", "message", "reason", "note"); message != "" {
			if len(message) > 2048 {
				return message[:2048]
			}
			return message
		}
	}
	if approval.Status == controlmodel.ApprovalCancelled {
		return "Tool confirmation was cancelled"
	}
	return "Tool use was rejected by the approver"
}

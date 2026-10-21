// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/product"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// AdmitManagedAttempt submits the frozen dispatch intent, never a new Attempt.
// Both the in-process control plane and the separate orchestration runtime use it.
func (s *Server) AdmitManagedAttempt(ctx context.Context, id uuid.UUID) (string, error) {
	if s.runtimeOnly {
		if s.portalClient == nil {
			return "", fmt.Errorf("Managed admission control plane is unavailable")
		}
		return s.portalClient.ManagedAttempt(ctx, id.String(), "admit")
	}
	if s.product == nil {
		return "", fmt.Errorf("Managed resource directory is unavailable")
	}
	var turnID string
	err := s.store.WithSessionLock(ctx, "managed-attempt-admit:"+id.String(), func(ctx context.Context) error {
		attempt, err := s.store.ExecutionAttempts().Get(ctx, id)
		if err != nil {
			return err
		}
		task, err := s.store.Collaboration().GetAgentTask(ctx, attempt.AgentTaskID)
		if err != nil {
			return err
		}
		if attempt.BackendKind != model.DataPlaneManaged || task.CurrentAttemptID == nil ||
			*task.CurrentAttemptID != id || model.IsAgentTaskTerminal(task.Status) ||
			model.IsExecutionAttemptTerminal(attempt.State) || attempt.State == model.ExecutionCancelRequested {
			return store.ErrConflict
		}
		if attempt.TurnID != "" {
			turnID = attempt.TurnID
			return nil
		}
		var snapshot model.RuntimeDispatchSnapshot
		if err = json.Unmarshal(attempt.RuntimeBinding, &snapshot); err != nil {
			return err
		}
		if snapshot.ManagedInput == "" || snapshot.SessionID != attempt.SessionID {
			return fmt.Errorf("Managed Attempt has no frozen admission intent")
		}
		if err = s.authorizeTaskResources(ctx, task, model.RuntimeBindingCandidate{Binding: snapshot.Binding}); err != nil {
			return err
		}
		session, err := s.managedTaskSession(ctx, task, attempt, snapshot)
		if err != nil {
			return err
		}
		contract, err := serviceapi.ReadContract(session.Contract)
		if err != nil {
			return err
		}
		origin, err := s.ensureNativePublicSession(ctx, session, contract, attempt.ManagedOwnerRef, "task-attempt:"+id.String())
		if err != nil {
			return err
		}
		raw, err := s.product.ManagedRuntimeRequest(ctx, attempt.ManagedOwnerRef, product.ManagedRuntimeCall{
			Context: origin, Operation: "submit_turn", Payload: map[string]any{"input": map[string]any{"message": snapshot.ManagedInput}}})
		if err != nil {
			return err
		}
		var receipt struct {
			ID string `json:"id"`
		}
		if err = json.Unmarshal(raw, &receipt); err != nil || receipt.ID == "" {
			return fmt.Errorf("Managed Portal returned an invalid Turn receipt")
		}
		turnID = receipt.ID
		_, err = s.store.ExecutionAttempts().BindManagedTurn(ctx, store.ManagedTurnBinding{AttemptID: attempt.ID,
			DispatchGeneration: attempt.DispatchGeneration, SessionID: attempt.SessionID, TurnID: turnID})
		return err
	})
	return turnID, err
}

// managedTaskSession freezes definitions and the original caller on first admission.
// Task identity remains private; no task token is saved in its public view.
func (s *Server) managedTaskSession(ctx context.Context, task *model.AgentTask, attempt *model.ExecutionAttempt, snapshot model.RuntimeDispatchSnapshot) (*sessionapi.Session, error) {
	id, err := uuid.Parse(attempt.SessionID)
	if err != nil {
		return nil, err
	}
	existing, err := sessionapi.Get(ctx, s.store, task.Tenant, id)
	if err == nil {
		if existing.Namespace != task.Namespace || existing.Target.ID != attempt.AgentID || existing.TaskAttemptID == nil || *existing.TaskAttemptID != attempt.ID {
			return nil, store.ErrConflict
		}
		return existing, nil
	}
	if !errors.Is(err, store.ErrNotFound) {
		return nil, err
	}
	contract, err := serviceapi.ContractForIssue(ctx, s.store, task.IssueID)
	if err != nil {
		return nil, err
	}
	if contract == nil {
		raw, e := s.freezeServiceContract(ctx, &model.Endpoint{ID: id, Tenant: task.Tenant, Namespace: task.Namespace,
			TargetType: model.EndpointTargetAgent, TargetRef: attempt.AgentID, InvocationMode: model.EndpointConversationMode,
			Status: model.EndpointPublished, MaxPayloadBytes: 16 << 20})
		if e != nil {
			return nil, e
		}
		contract, err = serviceapi.ReadContract(raw)
	} else {
		// Never mutate the parent Invocation's immutable contract.
		raw, e := json.Marshal(contract)
		if e != nil {
			return nil, e
		}
		contract, err = serviceapi.ReadContract(raw)
	}
	if err != nil {
		return nil, err
	}
	policy := contract.Policies[task.AgentRef]
	if policy == nil || len(contract.Definitions[task.AgentRef]) == 0 {
		return nil, store.ErrNotFound
	}
	fixed := *policy
	fixed.Candidates = []model.RuntimeBindingCandidate{{Binding: snapshot.Binding, RequiredCapabilities: snapshot.Capabilities,
		SecurityConstraints: snapshot.SecurityConstraints, SelectionSource: snapshot.SelectionSource, CandidateIndex: snapshot.CandidateIndex}}
	contract.Policies[task.AgentRef] = &fixed
	contract.PublicSessionID = id
	contract.Endpoint.TargetType, contract.Endpoint.TargetRef = model.EndpointTargetAgent, attempt.AgentID
	if err = s.freezeManagedEnvironment(ctx, contract, attempt.AgentID); err != nil {
		return nil, err
	}
	actor := task.Originator
	var application, endpoint *uuid.UUID
	inv, err := serviceapi.InvocationForIssue(ctx, s.store, task.IssueID)
	if err != nil {
		return nil, err
	}
	if inv != nil {
		actor, application = inv.Actor, inv.ApplicationID
		endpoint = &inv.EndpointID
	}
	if actor.Type == model.ActorAgent && task.AccountableHumanRef != "" {
		// Resource authorization above validates the delegated Agent dependency.
		// Preserve the delegating Actor on AgentTask rather than substituting the resource owner.
		actor = model.Actor{Type: model.ActorHuman, Ref: task.AccountableHumanRef}
	}
	principal := actor.Ref
	if actor.Type == model.ActorHuman {
		principal = "platform-user:" + actor.Ref
	} else if actor.Type != model.ActorAutomation || application == nil {
		return nil, store.ErrForbidden
	}
	if actor.Ref == "" {
		return nil, store.ErrForbidden
	}
	raw, err := json.Marshal(contract)
	if err != nil {
		return nil, err
	}
	now := time.Now().UTC()
	session := &sessionapi.Session{ID: id, Tenant: task.Tenant, Namespace: task.Namespace,
		Target:    sessionapi.Target{Type: "agent", ID: attempt.AgentID, Version: serviceapi.ContextVersion(serviceapi.WithContract(ctx, contract), task.AgentRef)},
		Principal: principal, ApplicationID: application, SourceEndpointID: endpoint, TaskAttemptID: &attempt.ID,
		Contract: raw, Status: "active", CreatedAt: now, UpdatedAt: now}
	return session, sessionapi.Save(ctx, s.store, session, true)
}

func (s *Server) CancelManagedAttempt(ctx context.Context, id uuid.UUID) error {
	if s.runtimeOnly {
		if s.portalClient == nil {
			return fmt.Errorf("Managed admission control plane is unavailable")
		}
		_, err := s.portalClient.ManagedAttempt(ctx, id.String(), "cancel")
		return err
	}
	return s.store.WithSessionLock(ctx, "managed-attempt-admit:"+id.String(), func(ctx context.Context) error {
		return s.cancelNativeManagedAttempt(ctx, id)
	})
}

func (s *Server) cancelNativeManagedAttempt(ctx context.Context, id uuid.UUID) error {
	attempt, err := s.store.ExecutionAttempts().Get(ctx, id)
	if err != nil {
		return err
	}
	if attempt.BackendKind != model.DataPlaneManaged {
		return store.ErrConflict
	}
	sessionID, err := uuid.Parse(attempt.SessionID)
	if err != nil {
		return err
	}
	session, err := sessionapi.Get(ctx, s.store, attempt.Tenant, sessionID)
	if errors.Is(err, store.ErrNotFound) {
		if attempt.StartedAt != nil || attempt.TurnID != "" {
			return fmt.Errorf("Managed execution metadata is missing; stop has not been proved")
		}
		// The admission lock excludes an in-flight creator, and new admission
		// rejects cancel_requested. No native Session means no execution exists.
		if attempt.State == model.ExecutionCancelRequested {
			_, err = s.store.ExecutionAttempts().ForceCancelled(ctx, attempt.ID, attempt.Version)
			return err
		}
		return nil
	}
	if err != nil {
		return err
	}
	if session.TaskAttemptID == nil || *session.TaskAttemptID != attempt.ID {
		return store.ErrConflict
	}
	if s.product == nil {
		return fmt.Errorf("Managed resource directory is unavailable")
	}
	contract, err := serviceapi.ReadContract(session.Contract)
	if err != nil {
		return err
	}
	origin, err := s.nativePublicSessionOrigin(ctx, session, contract, attempt.ManagedOwnerRef, "task-cancel:"+id.String())
	if err != nil {
		return err
	}
	turnID := attempt.TurnID
	if turnID == "" {
		// A lost receipt still identifies the original Turn through its dedicated
		// Session. The admission lock and cancel_requested fence exclude new admission.
		raw, e := s.product.ManagedRuntimeRequest(ctx, attempt.ManagedOwnerRef,
			product.ManagedRuntimeCall{Context: origin, Operation: "turns", Limit: 2})
		var missing *product.ManagedServiceError
		if errors.As(e, &missing) && missing.StatusCode == 404 {
			if attempt.State == model.ExecutionCancelRequested {
				_, e = s.store.ExecutionAttempts().ForceCancelled(ctx, attempt.ID, attempt.Version)
				return e
			}
			return nil
		}
		if e != nil {
			return e
		}
		var page struct {
			Data []struct {
				ID string `json:"id"`
			} `json:"data"`
		}
		if e = json.Unmarshal(raw, &page); e != nil {
			return e
		}
		if len(page.Data) == 0 {
			if attempt.State == model.ExecutionCancelRequested {
				_, e = s.store.ExecutionAttempts().ForceCancelled(ctx, attempt.ID, attempt.Version)
			}
			return e
		}
		if len(page.Data) != 1 || page.Data[0].ID == "" {
			return fmt.Errorf("invalid Managed Attempt Turn association")
		}
		turnID = page.Data[0].ID
	}
	origin.TurnID = &turnID
	_, err = s.product.ManagedRuntimeRequest(ctx, attempt.ManagedOwnerRef,
		product.ManagedRuntimeCall{Context: origin, Operation: "submit_input", Payload: map[string]any{
			"type": "cancel", "payload": map[string]any{"reason": "task_cancel_requested"}}})
	var closed *product.ManagedServiceError
	if errors.As(err, &closed) && (closed.StatusCode == 409 || closed.StatusCode == 410) {
		raw, e := s.product.ManagedRuntimeRequest(ctx, attempt.ManagedOwnerRef,
			product.ManagedRuntimeCall{Context: origin, Operation: "turn"})
		var turn struct {
			ID     string `json:"id"`
			Status string `json:"status"`
		}
		if e == nil && json.Unmarshal(raw, &turn) == nil && turn.ID == turnID &&
			(turn.Status == "completed" || turn.Status == "failed" || turn.Status == "cancelled") {
			return nil
		}
	}
	return err
}

// managedAttemptSettled serializes absence checks with admission. A missing
// receipt/session cannot prove that a known execution has stopped.
func (s *Server) managedAttemptSettled(ctx context.Context, id uuid.UUID) (bool, error) {
	if s.runtimeOnly {
		if s.portalClient == nil {
			return false, fmt.Errorf("Managed admission control plane is unavailable")
		}
		return s.portalClient.ManagedAttemptSettled(ctx, id.String())
	}
	settled := false
	err := s.store.WithSessionLock(ctx, "managed-attempt-admit:"+id.String(), func(ctx context.Context) error {
		attempt, err := s.store.ExecutionAttempts().Get(ctx, id)
		if err != nil {
			return err
		}
		if attempt.BackendKind != model.DataPlaneManaged {
			return store.ErrConflict
		}
		nativeID, err := uuid.Parse(attempt.SessionID)
		if err != nil {
			return err
		}
		session, err := sessionapi.Get(ctx, s.store, attempt.Tenant, nativeID)
		if errors.Is(err, store.ErrNotFound) && attempt.TurnID == "" && attempt.StartedAt == nil && model.IsExecutionAttemptTerminal(attempt.State) {
			settled = true
			return nil
		}
		if err != nil {
			return err
		}
		if session.TaskAttemptID == nil || *session.TaskAttemptID != id {
			return store.ErrConflict
		}
		if s.product == nil {
			return fmt.Errorf("Managed resource directory is unavailable")
		}
		contract, err := serviceapi.ReadContract(session.Contract)
		if err != nil {
			return err
		}
		origin, err := s.nativePublicSessionOrigin(ctx, session, contract, attempt.ManagedOwnerRef, "task-settle:"+id.String())
		if err != nil {
			return err
		}
		turnID := attempt.TurnID
		if turnID == "" {
			raw, err := s.product.ManagedRuntimeRequest(ctx, attempt.ManagedOwnerRef, product.ManagedRuntimeCall{Context: origin, Operation: "turns", Limit: 2})
			var missing *product.ManagedServiceError
			if errors.As(err, &missing) && missing.StatusCode == 404 && attempt.StartedAt == nil && model.IsExecutionAttemptTerminal(attempt.State) {
				settled = true
				return nil
			}
			if err != nil {
				return err
			}
			var page struct {
				Data []struct {
					ID string `json:"id"`
				} `json:"data"`
			}
			if err = json.Unmarshal(raw, &page); err != nil {
				return err
			}
			if len(page.Data) == 0 && attempt.StartedAt == nil && model.IsExecutionAttemptTerminal(attempt.State) {
				settled = true
				return nil
			}
			if len(page.Data) != 1 || page.Data[0].ID == "" {
				return fmt.Errorf("invalid Managed Attempt Turn association")
			}
			turnID = page.Data[0].ID
		}
		origin.TurnID = &turnID
		raw, err := s.product.ManagedRuntimeRequest(ctx, attempt.ManagedOwnerRef, product.ManagedRuntimeCall{Context: origin, Operation: "turn"})
		if err != nil {
			return err
		}
		var turn struct {
			ID     string `json:"id"`
			Status string `json:"status"`
		}
		if err = json.Unmarshal(raw, &turn); err != nil {
			return err
		}
		if turn.ID != turnID {
			return store.ErrConflict
		}
		settled = turn.Status == "completed" || turn.Status == "failed" || turn.Status == "cancelled"
		return nil
	})
	return settled, err
}

// ReconcileManagedAttempt observes native execution facts. It does not infer
// failure from a control-plane heartbeat, renew a lease, or start another Attempt.
func (s *Server) ReconcileManagedAttempt(ctx context.Context, id uuid.UUID) error {
	if s.runtimeOnly {
		if s.portalClient == nil {
			return fmt.Errorf("Managed admission control plane is unavailable")
		}
		_, err := s.portalClient.ManagedAttempt(ctx, id.String(), "observe")
		return err
	}
	attempt, err := s.store.ExecutionAttempts().Get(ctx, id)
	if err != nil {
		return err
	}
	if attempt.BackendKind != model.DataPlaneManaged || model.IsExecutionAttemptTerminal(attempt.State) {
		return nil
	}
	if attempt.State == model.ExecutionCancelRequested {
		if err = s.CancelManagedAttempt(ctx, id); err != nil {
			return err
		}
		attempt, err = s.store.ExecutionAttempts().Get(ctx, id)
		if err != nil || model.IsExecutionAttemptTerminal(attempt.State) {
			return err
		}
	}
	sid, err := uuid.Parse(attempt.SessionID)
	if err != nil {
		return err
	}
	session, err := sessionapi.Get(ctx, s.store, attempt.Tenant, sid)
	if errors.Is(err, store.ErrNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	if session.TaskAttemptID == nil || *session.TaskAttemptID != id {
		return store.ErrConflict
	}
	if s.product == nil {
		return fmt.Errorf("Managed resource directory is unavailable")
	}
	contract, err := serviceapi.ReadContract(session.Contract)
	if err != nil {
		return err
	}
	origin, err := s.nativePublicSessionOrigin(ctx, session, contract, attempt.ManagedOwnerRef, "task-observe:"+id.String())
	if err != nil {
		return err
	}
	expired := managedAttemptExpired(attempt, contract, time.Now())
	if expired && attempt.State != model.ExecutionCancelRequested {
		task, e := s.store.Collaboration().GetAgentTask(ctx, attempt.AgentTaskID)
		if e != nil {
			return e
		}
		if task.CurrentAttemptID == nil || *task.CurrentAttemptID != id || model.IsAgentTaskTerminal(task.Status) {
			return nil
		}
		if _, e = s.store.ExecutionAttempts().Cancel(ctx, attempt.ID, attempt.Version); e != nil {
			return e
		}
		return s.CancelManagedAttempt(ctx, attempt.ID)
	}
	turnID := attempt.TurnID
	if turnID == "" {
		raw, e := s.product.ManagedRuntimeRequest(ctx, attempt.ManagedOwnerRef,
			product.ManagedRuntimeCall{Context: origin, Operation: "turns", Limit: 2})
		var missing *product.ManagedServiceError
		if errors.As(e, &missing) && missing.StatusCode == 404 {
			return nil
		}
		if e != nil {
			return e
		}
		var page struct {
			Data []struct {
				ID string `json:"id"`
			} `json:"data"`
		}
		if e = json.Unmarshal(raw, &page); e != nil {
			return e
		}
		if len(page.Data) == 0 {
			return nil
		}
		if len(page.Data) != 1 {
			return fmt.Errorf("Managed Attempt Session contains multiple Turns")
		}
		turnID = page.Data[0].ID
	}
	origin.TurnID = &turnID
	raw, err := s.product.ManagedRuntimeRequest(ctx, attempt.ManagedOwnerRef,
		product.ManagedRuntimeCall{Context: origin, Operation: "turn"})
	if err != nil {
		return err
	}
	var turn struct {
		ID     string `json:"id"`
		Status string `json:"status"`
	}
	if err = json.Unmarshal(raw, &turn); err != nil || turn.ID != turnID {
		return fmt.Errorf("invalid Managed Turn observation")
	}
	if attempt.TurnID == "" && attempt.State != model.ExecutionCancelRequested {
		// A Brain's first MCP request may race the admission HTTP response.
		// Bind the observed native identity before task.complete can finish it.
		attempt, err = s.store.ExecutionAttempts().BindManagedTurn(ctx, store.ManagedTurnBinding{AttemptID: id,
			DispatchGeneration: attempt.DispatchGeneration, SessionID: attempt.SessionID, TurnID: turnID})
		if err != nil {
			return err
		}
	}
	if turn.Status == "preparing" || turn.Status == "running" || turn.Status == "waiting" || turn.Status == "waiting_tool" {
		task, e := s.store.Collaboration().GetAgentTask(ctx, attempt.AgentTaskID)
		if e != nil {
			return e
		}
		if task.CurrentAttemptID != nil && *task.CurrentAttemptID == id && task.Status == model.AgentTaskDispatched && attempt.State != model.ExecutionCancelRequested {
			_, e = s.store.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
		}
		return e
	}
	if turn.Status != "completed" && turn.Status != "failed" && turn.Status != "cancelled" {
		return nil
	}
	if attempt.State == model.ExecutionCancelRequested {
		if expired {
			task, e := s.store.Collaboration().GetAgentTask(ctx, attempt.AgentTaskID)
			if e != nil {
				return e
			}
			if !model.IsAgentTaskTerminal(task.Status) {
				_, _, e = s.store.Collaboration().FailAgentTaskWithAttempt(ctx, task.ID, store.TaskFailure{
					ExpectedVersion: task.Version, AttemptID: attempt.ID, DispatchGeneration: attempt.DispatchGeneration,
					Code: "attempt_timeout", Message: "Managed execution stopped after its configured time limit"})
				return e
			}
		}
		_, err = s.store.ExecutionAttempts().ForceCancelled(ctx, attempt.ID, attempt.Version)
		return err
	}
	task, err := s.store.Collaboration().GetAgentTask(ctx, attempt.AgentTaskID)
	if err != nil {
		return err
	}
	if task.CurrentAttemptID == nil || *task.CurrentAttemptID != id || model.IsAgentTaskTerminal(task.Status) {
		return nil
	}
	// AgentTask delivery is explicit through task.complete/task.fail. A final
	// model message alone must not fabricate a successful task deliverable.
	code, message := "managed_turn_failed", "Managed Turn finished without a task result"
	if turn.Status == "completed" {
		code = "task_protocol_incomplete"
	}
	if turn.Status == "cancelled" {
		code, message = "managed_turn_cancelled", "Managed Turn was cancelled"
	}
	_, _, err = s.store.Collaboration().FailAgentTaskWithAttempt(ctx, task.ID, store.TaskFailure{
		ExpectedVersion: task.Version, AttemptID: id, DispatchGeneration: attempt.DispatchGeneration, Code: code, Message: message})
	return err
}

func managedAttemptExpired(attempt *model.ExecutionAttempt, contract *serviceapi.Contract, now time.Time) bool {
	policy := contract.Policies[attempt.AgentID.String()]
	return policy != nil && policy.AttemptTimeoutSeconds > 0 && attempt.StartedAt != nil &&
		!now.Before(attempt.StartedAt.Add(time.Duration(policy.AttemptTimeoutSeconds)*time.Second))
}

// A lost terminal MCP response may be replayed while its original native Turn
// is still acting. This authenticates a read-only receipt, never ordinary work.
func (s *Server) verifyCompletedManagedTaskReceipt(ctx context.Context, token string) (*model.AgentTask, error) {
	claims, err := s.taskTokens.VerifyClaims(token, time.Now())
	if err != nil || claims.ManagedSessionID == uuid.Nil {
		return nil, store.ErrForbidden
	}
	task, err := s.store.Collaboration().GetAgentTask(ctx, claims.TaskID)
	if err != nil {
		return nil, err
	}
	// Leaders retain their separate final-node transition contract.
	if task.LeaderTask || task.CurrentAttemptID == nil || *task.CurrentAttemptID != claims.AttemptID {
		return nil, store.ErrForbidden
	}
	attempt, err := s.store.ExecutionAttempts().Get(ctx, claims.AttemptID)
	if err != nil {
		return nil, err
	}
	if attempt.DispatchGeneration != claims.Generation ||
		!(task.Status == model.AgentTaskCompleted && attempt.State == model.ExecutionSucceeded || task.Status == model.AgentTaskFailed && attempt.State == model.ExecutionFailed) {
		return nil, store.ErrForbidden
	}
	if err = s.validateManagedTaskCredential(ctx, task, attempt, claims.ManagedSessionID); err != nil {
		return nil, err
	}
	if err = s.requireManagedTaskExecution(ctx, task, attempt); err != nil {
		return nil, err
	}
	return task, nil
}

func (s *Server) managedAttemptOperation(c *gin.Context) {
	id, err := uuid.Parse(c.Param("attemptId"))
	if err != nil {
		c.JSON(400, ErrorResponse{Error: "invalid Attempt ID"})
		return
	}
	var turn string
	switch c.Param("operation") {
	case "admit":
		turn, err = s.AdmitManagedAttempt(c.Request.Context(), id)
	case "cancel":
		err = s.CancelManagedAttempt(c.Request.Context(), id)
	case "observe":
		err = s.ReconcileManagedAttempt(c.Request.Context(), id)
	case "settled":
		var settled bool
		settled, err = s.managedAttemptSettled(c.Request.Context(), id)
		if err == nil {
			c.JSON(200, runtimeprotocol.ManagedAttemptReceipt{Version: runtimeprotocol.Version, Settled: &settled})
			return
		}
	case "authorize":
		attempt, e := s.store.ExecutionAttempts().Get(c.Request.Context(), id)
		if e == nil {
			task, load := s.store.Collaboration().GetAgentTask(c.Request.Context(), attempt.AgentTaskID)
			e = load
			if e == nil && (task.CurrentAttemptID == nil || *task.CurrentAttemptID != id) {
				e = store.ErrForbidden
			}
			if e == nil {
				var sid uuid.UUID
				sid, e = uuid.Parse(attempt.SessionID)
				if e == nil {
					e = s.validateManagedTaskCredential(c.Request.Context(), task, attempt, sid)
				}
				if e == nil {
					e = s.requireManagedTaskExecution(c.Request.Context(), task, attempt)
				}
			}
		}
		err = e
	default:
		c.Status(404)
		return
	}
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(200, runtimeprotocol.ManagedAttemptReceipt{Version: runtimeprotocol.Version, TurnID: turn})
}

func (s *Server) validateManagedTaskCredential(ctx context.Context, task *model.AgentTask, attempt *model.ExecutionAttempt, id uuid.UUID) error {
	if attempt.BackendKind != model.DataPlaneManaged || attempt.SessionID != id.String() ||
		attempt.State == model.ExecutionCancelRequested || attempt.State == model.ExecutionCancelled || task.Status == model.AgentTaskCancelled {
		return store.ErrForbidden
	}
	session, err := sessionapi.Get(ctx, s.store, task.Tenant, id)
	if err != nil {
		return err
	}
	if session.TaskAttemptID == nil || *session.TaskAttemptID != attempt.ID || session.Namespace != task.Namespace ||
		attempt.Tenant != task.Tenant || attempt.Namespace != task.Namespace || session.Target.ID != attempt.AgentID {
		return store.ErrForbidden
	}
	if session.Status != "active" {
		return store.ErrForbidden
	}
	agent, err := s.store.AgentCatalog().GetAgent(ctx, attempt.AgentID)
	if err != nil {
		return err
	}
	if agent.Tenant != task.Tenant || agent.Namespace != task.Namespace || agent.Status != model.AgentActive || agent.ArchivedAt != nil {
		return store.ErrForbidden
	}
	binding, err := s.store.AgentCatalog().GetBinding(ctx, attempt.BindingID)
	if err != nil {
		return err
	}
	if binding.AgentID != agent.ID || binding.Kind != model.DataPlaneManaged || !binding.Enabled || binding.ArchivedAt != nil || binding.Tenant != task.Tenant || binding.Namespace != task.Namespace {
		return store.ErrForbidden
	}
	var snapshot model.RuntimeDispatchSnapshot
	if err = json.Unmarshal(attempt.RuntimeBinding, &snapshot); err != nil {
		return err
	}
	return s.authorizeTaskResources(ctx, task, model.RuntimeBindingCandidate{Binding: snapshot.Binding,
		RequiredCapabilities: snapshot.Capabilities, SecurityConstraints: snapshot.SecurityConstraints})
}

func (s *Server) requireManagedTaskExecution(ctx context.Context, task *model.AgentTask, attempt *model.ExecutionAttempt) error {
	if s.runtimeOnly {
		// The resource control plane observes native execution and current grants.
		if s.portalClient == nil {
			return fmt.Errorf("Managed admission control plane is unavailable")
		}
		_, err := s.portalClient.ManagedAttempt(ctx, attempt.ID.String(), "authorize")
		return err
	}
	if s.product == nil || attempt.TurnID == "" {
		return store.ErrForbidden
	}
	id, err := uuid.Parse(attempt.SessionID)
	if err != nil {
		return store.ErrForbidden
	}
	session, err := sessionapi.Get(ctx, s.store, task.Tenant, id)
	if err != nil {
		return err
	}
	contract, err := serviceapi.ReadContract(session.Contract)
	if err != nil {
		return err
	}
	origin, err := s.nativePublicSessionOrigin(ctx, session, contract, attempt.ManagedOwnerRef, "task-authorize:"+attempt.ID.String())
	if err != nil {
		return err
	}
	origin.TurnID = &attempt.TurnID
	raw, err := s.product.ManagedRuntimeRequest(ctx, attempt.ManagedOwnerRef, product.ManagedRuntimeCall{Context: origin, Operation: "turn"})
	if err != nil {
		return err
	}
	var turn struct {
		ID         string `json:"id"`
		Status     string `json:"status"`
		InputsOpen bool   `json:"inputs_open"`
	}
	if err = json.Unmarshal(raw, &turn); err != nil || turn.ID != attempt.TurnID || !turn.InputsOpen {
		return store.ErrForbidden
	}
	switch turn.Status {
	case "preparing", "running", "waiting", "waiting_tool":
		return nil
	default:
		return store.ErrForbidden
	}
}

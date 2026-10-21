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

package controller

import (
	"context"
	"encoding/json"
	"errors"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"time"

	"github.com/google/uuid"
	"sigs.k8s.io/controller-runtime/pkg/log"

	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/conversation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/orchestration"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
)

// RuntimeControlSweeper converges stale registry and execution leases. It is
// safe to run on every control-plane replica because PostgreSQL repositories
// claim expired rows with SKIP LOCKED and all updates are idempotent.
type RuntimeControlSweeper struct {
	PortalOnly              bool
	Store                   store.Store
	ReconcileRun            func(context.Context, uuid.UUID) error
	ReconcileManagedAttempt func(context.Context, uuid.UUID) error
	Interval                time.Duration
	RuntimeTimeout          time.Duration
	StartupTimeout          time.Duration
	HeartbeatTimeout        time.Duration
	CancelGrace             time.Duration
	Batch                   int
}

func (w *RuntimeControlSweeper) Start(ctx context.Context) error {
	if w.Store == nil {
		return errors.New("runtime control sweeper store is required")
	}
	interval := w.Interval
	if interval <= 0 {
		interval = 10 * time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			w.Sweep(ctx, time.Now().UTC())
		}
	}
}

func (w *RuntimeControlSweeper) Sweep(ctx context.Context, now time.Time) {
	logger := log.FromContext(ctx).WithName("runtime-control-sweeper")
	timeout := w.RuntimeTimeout
	if timeout <= 0 {
		timeout = 45 * time.Second
	}
	startupTimeout := w.StartupTimeout
	if startupTimeout <= 0 {
		startupTimeout = 30 * time.Second
	}
	heartbeatTimeout := w.HeartbeatTimeout
	if heartbeatTimeout <= 0 {
		heartbeatTimeout = timeout
	}
	cancelGrace := w.CancelGrace
	if cancelGrace <= 0 {
		cancelGrace = 30 * time.Second
	}
	batch := w.Batch
	if batch <= 0 {
		batch = 100
	}
	if batch > 500 {
		batch = 500
	}
	if _, err := w.Store.RuntimeRegistry().MarkRuntimeHostsOffline(ctx, now.Add(-timeout)); err != nil {
		logger.Error(err, "marking runtime hosts offline")
	}
	if _, err := w.Store.RuntimeRegistry().MarkAgentInstancesOffline(ctx, now.Add(-timeout)); err != nil {
		logger.Error(err, "marking agent instances offline")
	}
	if !w.PortalOnly {
		if err := conversation.Sweep(ctx, w.Store, now); err != nil {
			logger.Error(err, "recovering unresponsive conversation turns")
		}
		if _, err := w.Store.Collaboration().SweepOverdueIssues(ctx, now, batch); err != nil {
			logger.Error(err, "creating overdue Issue attention items")
		}
	}
	if _, err := w.Store.Collaboration().RequeueRetryableInputs(ctx, now, batch, w.PortalOnly); err != nil {
		logger.Error(err, "requeueing retryable AgentTask inputs")
	}
	if _, err := w.Store.Collaboration().SweepTimedOutAgentTasks(ctx, now, batch, w.PortalOnly); err != nil {
		logger.Error(err, "failing AgentTasks that exceeded Team timeout policy")
	}

	queuedAfter, queuedCommit, cursorErr := w.beginPortalSweep(ctx, "portal-queued-task-v1")
	if cursorErr != nil {
		logger.Error(cursorErr, "reading queued Task scan cursor")
		return
	}
	queued, err := w.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{PortalOnly: w.PortalOnly, Status: controlmodel.AgentTaskQueued, Limit: batch, OmitInputs: true, AfterID: queuedAfter})
	if err != nil {
		logger.Error(err, "listing queued AgentTasks for policy timeout")
	} else {
		finished := true
		for _, task := range queued {
			policy, policyErr := serviceapi.RuntimePolicy(ctx, w.Store, task)
			if policyErr != nil || policy.QueueTimeoutSeconds <= 0 || now.Before(task.CreatedAt.Add(time.Duration(policy.QueueTimeoutSeconds)*time.Second)) {
				continue
			}
			if _, failErr := w.Store.Collaboration().FailAgentTask(ctx, task.ID, task.Version,
				"queue_timeout", "AgentTask exceeded its Runtime Policy queue timeout"); failErr != nil && !errors.Is(failErr, store.ErrConflict) {
				finished = false
				logger.Error(failErr, "failing queued AgentTask after policy timeout", "task", task.ID)
			}
		}

		last := uuid.Nil
		if len(queued) > 0 {
			last = queued[len(queued)-1].ID
		}
		if finished {
			if err := queuedCommit(last, len(queued) == batch); err != nil && !errors.Is(err, store.ErrConflict) {
				logger.Error(err, "advancing queued Task scan cursor")
			}
		}
	}
	if w.ReconcileRun != nil {
		runs, err := w.Store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{PortalOnly: w.PortalOnly, ActiveOnly: true, OldestFirst: true, Limit: batch})
		if err != nil {
			logger.Error(err, "listing active orchestration runs")
		} else {
			for _, run := range runs {
				if err := w.ReconcileRun(ctx, run.ID); err != nil && !errors.Is(err, store.ErrConflict) {
					logger.Error(err, "reconciling active orchestration run", "run", run.ID)
				}
			}
		}
	}
	if !w.PortalOnly {
		if err := w.reconcileEndpointJobInvocations(ctx, batch); err != nil {
			logger.Error(err, "reconciling Endpoint Job invocation states")
		}
	}
	// A lease or heartbeat loss never resurrects an Attempt. Fence the old
	// record and put the logical Task back on the durable queue atomically.
	for _, state := range []controlmodel.ExecutionAttemptState{
		controlmodel.ExecutionAssigned, controlmodel.ExecutionPreparing, controlmodel.ExecutionRunning,
		controlmodel.ExecutionWaiting,
	} {

		after, commit, err := w.beginPortalSweep(ctx, "portal-attempt-"+string(state)+"-v1")
		if err != nil {
			logger.Error(err, "reading Attempt scan cursor", "state", state)
			continue
		}
		attempts, err := w.Store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{PortalOnly: w.PortalOnly, State: state, Limit: batch, SweepAfterID: after})
		if err != nil {
			logger.Error(err, "listing stale execution attempts", "state", state)
			continue
		}
		finished := true
		for _, attempt := range attempts {
			// Managed liveness belongs to the native Turn's MQ/Redis guard.
			// A stale control-plane timestamp cannot prove the Brain or its tool stopped.
			if attempt.BackendKind == controlmodel.DataPlaneManaged {
				if w.ReconcileManagedAttempt != nil {
					if err := w.ReconcileManagedAttempt(ctx, attempt.ID); err != nil && !errors.Is(err, store.ErrConflict) {
						finished = false
						logger.Error(err, "reconciling native Managed Attempt", "attempt", attempt.ID)
					}
				}
				continue
			}
			task, getErr := w.Store.Collaboration().GetAgentTask(ctx, attempt.AgentTaskID)
			if getErr != nil || controlmodel.IsAgentTaskTerminal(task.Status) {
				if getErr != nil && !errors.Is(getErr, store.ErrNotFound) {
					finished = false
				}
				continue
			}
			absoluteExpired := false
			if policy, policyErr := serviceapi.RuntimePolicy(ctx, w.Store, task); policyErr == nil &&
				policy.AttemptTimeoutSeconds > 0 && attempt.StartedAt != nil {
				absoluteExpired = !now.Before(attempt.StartedAt.Add(time.Duration(policy.AttemptTimeoutSeconds) * time.Second))
			}
			staleAt := attempt.UpdatedAt
			if attempt.HeartbeatAt != nil {
				staleAt = *attempt.HeartbeatAt
			}
			if attempt.LeaseExpiresAt != nil {
				staleAt = *attempt.LeaseExpiresAt
			}
			stateTimeout := startupTimeout
			if state == controlmodel.ExecutionRunning || state == controlmodel.ExecutionWaiting {
				stateTimeout = heartbeatTimeout
			}
			if !absoluteExpired && (staleAt.After(now) || attempt.LeaseExpiresAt == nil && staleAt.After(now.Add(-stateTimeout))) {
				continue
			}
			code, message := "startup_timeout", "runtime did not start the execution attempt before its deadline"
			if state == controlmodel.ExecutionRunning || state == controlmodel.ExecutionWaiting {
				code, message = "heartbeat_timeout", "runtime heartbeat or host lease expired while execution was running"
			}
			if absoluteExpired {
				code, message = "attempt_timeout", "execution attempt exceeded its Runtime Policy timeout"
			}
			_, failedAttempt, retryErr := w.Store.Collaboration().RequeueAgentTaskAfterAttemptFailure(ctx, task.ID, store.TaskFailure{
				ExpectedVersion: task.Version, AttemptID: attempt.ID, DispatchGeneration: attempt.DispatchGeneration,
				Code: code, Message: message})
			if retryErr != nil && !errors.Is(retryErr, store.ErrConflict) {
				finished = false
				logger.Error(retryErr, "requeueing task after attempt failure", "task", task.ID, "attempt", attempt.ID)
				continue
			}
			if failedAttempt != nil {
				w.emit(ctx, failedAttempt, "execution.failed", code)
			}
		}

		last := uuid.Nil
		if len(attempts) > 0 {
			last = attempts[len(attempts)-1].ID
		}
		if finished {
			if err := commit(last, len(attempts) == batch); err != nil && !errors.Is(err, store.ErrConflict) {
				logger.Error(err, "advancing Attempt scan cursor", "state", state)
			}
		}
	}
	after, commit, cursorErr := w.beginPortalSweep(ctx, "portal-cancelling-attempt-v1")
	if cursorErr != nil {
		logger.Error(cursorErr, "reading cancelling Attempt scan cursor")
		return
	}
	cancelling, err := w.Store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{PortalOnly: w.PortalOnly, State: controlmodel.ExecutionCancelRequested, Limit: batch, SweepAfterID: after})
	if err != nil {
		logger.Error(err, "listing attempts awaiting cancellation acknowledgement")
	} else {
		finished := true
		for _, attempt := range cancelling {
			if attempt.BackendKind == controlmodel.DataPlaneManaged {
				if w.ReconcileManagedAttempt != nil {
					if err := w.ReconcileManagedAttempt(ctx, attempt.ID); err != nil && !errors.Is(err, store.ErrConflict) {
						finished = false
						logger.Error(err, "observing native Managed cancellation", "attempt", attempt.ID)
					}
				}
				continue
			}
			if attempt.UpdatedAt.After(now.Add(-cancelGrace)) {
				continue
			}
			cancelled, cancelErr := w.Store.ExecutionAttempts().ForceCancelled(ctx, attempt.ID, attempt.Version)
			if cancelErr != nil && !errors.Is(cancelErr, store.ErrConflict) {
				finished = false
				logger.Error(cancelErr, "forcing attempt cancellation after grace period", "attempt", attempt.ID)
			} else if cancelled != nil {
				w.emit(ctx, cancelled, "execution.cancelled", "cancel_grace_expired")
			}
		}

		last := uuid.Nil
		if len(cancelling) > 0 {
			last = cancelling[len(cancelling)-1].ID
		}
		if finished {
			if err := commit(last, len(cancelling) == batch); err != nil && !errors.Is(err, store.ErrConflict) {
				logger.Error(err, "advancing cancelling Attempt scan cursor")
			}
		}
	}
	// Run Approval convergence after Task policy and Attempt lease convergence.
	// This closes pending Inbox items in the same sweep that replaced or
	// cancelled their physical continuation instead of waiting for another tick.
	if err := w.sweepManagedToolApprovals(ctx, now, batch); err != nil {
		logger.Error(err, "cancelling expired or stale managed tool approvals")
	}
}

type managedToolApprovalRequest struct {
	Kind               string                     `json:"kind"`
	BackendKind        controlmodel.DataPlaneKind `json:"backendKind"`
	SchemaVersion      int32                      `json:"schemaVersion"`
	Tenant             string                     `json:"tenant"`
	Namespace          string                     `json:"namespace"`
	SessionID          string                     `json:"sessionId"`
	SessionRef         *uuid.UUID                 `json:"sessionRef"`
	ApprovalID         uuid.UUID                  `json:"approvalId"`
	AgentTaskID        uuid.UUID                  `json:"agentTaskId"`
	AttemptID          uuid.UUID                  `json:"attemptId"`
	DispatchGeneration int64                      `json:"dispatchGeneration"`
	TurnID             string                     `json:"turnId"`
	ToolUseID          string                     `json:"toolUseId"`
	ToolName           string                     `json:"toolName"`
	InputSHA256        string                     `json:"inputSha256"`
	ExpiresAt          time.Time                  `json:"expiresAt"`
}

func (w *RuntimeControlSweeper) sweepManagedToolApprovals(ctx context.Context, now time.Time, limit int) error {
	if limit <= 0 {
		limit = 100
	}
	// List a stable page set before mutating it. ListApprovals is newest-first;
	// processing only its first page would permanently starve an old expired
	// Approval whenever enough newer, non-expired Approvals remain pending.
	approvals, commit, err := w.toolApprovalSweepPage(ctx, limit)
	if err != nil {
		return err
	}
	for _, approval := range approvals {
		var request managedToolApprovalRequest
		if json.Unmarshal(approval.Request, &request) != nil || request.SchemaVersion != 1 {
			continue
		}
		if request.BackendKind == "" && request.Kind == controlmodel.ApprovalRequestKindManagedToolConfirmation {
			request.BackendKind = controlmodel.DataPlaneManaged
		}
		if request.Kind != store.RuntimeToolApprovalRequestKind(request.BackendKind) {
			continue
		}
		stale := request.ApprovalID != approval.ID || approval.TargetRef != request.AttemptID.String()
		if !stale {
			task, taskErr := w.Store.Collaboration().GetAgentTask(ctx, request.AgentTaskID)
			attempt, attemptErr := w.Store.ExecutionAttempts().Get(ctx, request.AttemptID)
			fence := store.ManagedToolApprovalFence{BackendKind: request.BackendKind, SessionID: request.SessionID,
				TaskID: request.AgentTaskID, AttemptID: request.AttemptID, ApprovalID: approval.ID,
				DispatchGeneration: request.DispatchGeneration, TurnID: request.TurnID,
				ToolUseID: request.ToolUseID, ToolName: request.ToolName, InputSHA256: request.InputSHA256}
			stale = taskErr != nil || attemptErr != nil ||
				request.Tenant != approval.Tenant || request.Namespace != approval.Namespace ||
				task.Status != controlmodel.AgentTaskWaiting ||
				task.WaitReason != "approval:"+approval.ID.String() ||
				attempt.State != controlmodel.ExecutionWaiting ||
				store.ValidateManagedToolApprovalFence(task, attempt, fence) != nil ||
				store.ValidateManagedToolApproval(approval, task, fence) != nil
			if !stale && request.BackendKind == controlmodel.DataPlaneManaged {
				if request.SessionRef == nil {
					stale = true
				} else {
					session, sessionErr := w.Store.Sessions().GetByID(ctx, *request.SessionRef)
					stale = sessionErr != nil || session.SessionID != request.SessionID ||
						session.AgentTaskID == nil || *session.AgentTaskID != request.AgentTaskID
				}
			}
		}
		if !stale && (request.ExpiresAt.IsZero() || now.Before(request.ExpiresAt)) {
			continue
		}
		reason := "approval_timeout"
		if stale {
			reason = "stale_execution_fence"
		}
		decision, _ := json.Marshal(map[string]any{"reason": reason, "cancelledAt": now})
		if _, decideErr := w.Store.Collaboration().DecideApproval(ctx, approval.ID, approval.Version,
			controlmodel.ApprovalCancelled,
			controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "runtime-control-sweeper"},
			decision); decideErr != nil && !errors.Is(decideErr, store.ErrConflict) {
			return decideErr
		}
	}
	return commit()
}

func (w *RuntimeControlSweeper) reconcileEndpointJobInvocations(ctx context.Context, batch int) error {
	invocations, err := w.Store.Endpoints().ListInvocations(ctx, store.EndpointInvocationFilter{
		Mode: controlmodel.EndpointJobMode, ActiveOnly: true, Limit: batch,
	})
	if err != nil {
		return err
	}
	for _, invocation := range invocations {
		// Published service contracts are reconciled by the durable Invocation worker.
		if len(invocation.Contract) > 0 {
			continue
		}
		if invocation.RunID == nil {
			continue
		}
		run, loadErr := w.Store.Orchestration().GetRun(ctx, *invocation.RunID)
		if loadErr != nil {
			if errors.Is(loadErr, store.ErrNotFound) {
				continue
			}
			return loadErr
		}
		if !controlmodel.IsOrchestrationRunTerminal(run.State) {
			continue
		}
		now := time.Now().UTC()
		invocation.CompletedAt = &now
		nodes, nodesErr := w.Store.Orchestration().ListNodes(ctx, run.ID)
		if nodesErr != nil {
			return nodesErr
		}
		invocation.Result = orchestration.CompletedRunOutput(run, nodes)
		if len(invocation.Result) == 0 {
			invocation.Result, _ = json.Marshal(map[string]any{"runState": run.State})
		}
		switch run.State {
		case controlmodel.RunSucceeded, controlmodel.RunPartialSucceeded:
			invocation.Status = controlmodel.EndpointInvocationCompleted
		case controlmodel.RunCancelled:
			invocation.Status = controlmodel.EndpointInvocationCancelled
		default:
			invocation.Status = controlmodel.EndpointInvocationFailed
			invocation.ErrorCode, invocation.ErrorMessage = run.FailureCode, run.FailureMessage
			if invocation.ErrorCode == "" {
				invocation.ErrorCode = "run_failed"
			}
		}
		if invocation.IssueID != nil {
			issue, issueErr := w.Store.Collaboration().GetIssue(ctx, *invocation.IssueID)
			if issueErr != nil && !errors.Is(issueErr, store.ErrNotFound) {
				return issueErr
			}
			if issue != nil && issue.Kind == controlmodel.IssueKindEndpointJob &&
				issue.CompletionPolicy == controlmodel.IssueCompletionAutomatic &&
				issue.Status != controlmodel.IssueDone && issue.Status != controlmodel.IssueCancelled {
				target, reason := controlmodel.IssueBlocked, "Endpoint Job execution failed: "+run.FailureMessage
				if run.State == controlmodel.RunCancelled {
					target, reason = controlmodel.IssueCancelled, "Endpoint Job execution was cancelled"
				}
				if run.State == controlmodel.RunSucceeded || run.State == controlmodel.RunPartialSucceeded {
					target, reason = controlmodel.IssueDone, "automatic Endpoint Job execution completed"
				}
				if issue.Status != target {
					_, transitionErr := w.Store.Collaboration().TransitionIssue(ctx, issue.ID, issue.Version, target,
						controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "endpoint-invocation:" + invocation.ID.String()}, reason)
					if errors.Is(transitionErr, store.ErrConflict) {
						continue // Leave the invocation active so the next sweep retries projection.
					}
					if transitionErr != nil {
						return transitionErr
					}
				}
			}
		}
		if _, updateErr := w.Store.Endpoints().UpdateInvocation(ctx, invocation); updateErr != nil {
			return updateErr
		}
	}
	return nil
}

func (w *RuntimeControlSweeper) emit(ctx context.Context, execution *controlmodel.ExecutionAttempt, eventType, reason string) {
	payload, _ := json.Marshal(map[string]any{
		"executionId": execution.ID, "agentTaskId": execution.AgentTaskID, "reason": reason,
	})
	_, _ = w.Store.Outbox().Enqueue(ctx, &controlmodel.OutboxEvent{
		Tenant: execution.Tenant, AggregateType: "execution", AggregateID: execution.ID.String(),
		EventType: eventType, Payload: payload,
		DedupeKey:   "execution/" + execution.ID.String() + "/" + eventType + "/" + reason,
		AvailableAt: time.Now().UTC(),
	})
}

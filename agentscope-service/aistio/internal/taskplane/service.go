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

// Package taskplane coordinates AgentTask obligations and ExecutionAttempt
// attempts. Runtime providers never own a second logical task state machine.
package taskplane

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/metrics"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type Service struct {
	ResolveDefinition func(context.Context, uuid.UUID) (json.RawMessage, error)
	Store             store.Store
	CancelBackend     func(context.Context, *controlmodel.ExecutionAttempt) error
	CommentSink       func(context.Context, *controlmodel.Comment) error
}

// DispatchHosted freezes a hosted-runtime binding on a queued AgentTask and
// creates its next immutable physical attempt.
func (s *Service) DispatchHosted(ctx context.Context, taskID uuid.UUID, binding controlmodel.RuntimeBinding, capabilities json.RawMessage) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	return s.DispatchHostedCandidate(ctx, taskID, controlmodel.RuntimeBindingCandidate{Binding: binding, RequiredCapabilities: capabilities})
}

func (s *Service) DispatchHostedCandidate(ctx context.Context, taskID uuid.UUID, candidate controlmodel.RuntimeBindingCandidate) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	if s == nil || s.Store == nil {
		return nil, nil, fmt.Errorf("task plane store is required")
	}
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, nil, err
	}
	var snapshot controlmodel.RuntimeDispatchSnapshot
	if json.Unmarshal(task.RuntimeBinding, &snapshot) == nil && snapshot.SessionID != "" {
		conversation, contextErr := s.hostedConversationRetryContext(ctx, task, snapshot)
		if contextErr != nil {
			return nil, nil, contextErr
		}
		return s.dispatchHostedCandidate(ctx, taskID, candidate, conversation)
	}
	return s.dispatchHostedCandidate(ctx, taskID, candidate, HostedConversationContext{})
}

// HostedConversationContext binds a physical hosted execution to one logical
// conversation turn. ProviderSessionID is the opaque resume token returned by
// the previous attempt; WorkspaceKey identifies the matching host-local
// working directory. The control plane never interprets either value.
type HostedConversationContext struct {
	SessionID         string
	TurnID            string
	ProviderSessionID string
	WorkspaceKey      string
	PreferredHostID   *uuid.UUID
}

// DispatchHostedConversationCandidate dispatches one turn while preserving
// the stable control-plane session and provider-native resume identity.
func (s *Service) DispatchHostedConversationCandidate(ctx context.Context, taskID uuid.UUID,
	candidate controlmodel.RuntimeBindingCandidate, conversation HostedConversationContext) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	if conversation.SessionID == "" || conversation.TurnID == "" {
		return nil, nil, fmt.Errorf("hosted conversation dispatch requires sessionId and turnId")
	}
	return s.dispatchHostedCandidate(ctx, taskID, candidate, conversation)
}

func (s *Service) dispatchHostedCandidate(ctx context.Context, taskID uuid.UUID,
	candidate controlmodel.RuntimeBindingCandidate, conversation HostedConversationContext) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	binding, capabilities := candidate.Binding, candidate.RequiredCapabilities
	if s == nil || s.Store == nil {
		return nil, nil, fmt.Errorf("task plane store is required")
	}
	if binding.Kind != controlmodel.DataPlaneHostedRuntime {
		return nil, nil, fmt.Errorf("hosted dispatch requires a hosted-runtime binding")
	}
	// Generic AgentTasks still need a physical session/turn identity. It is not a
	// product chat session; it is the immutable continuation fence used by HITL,
	// retries, and diagnostics.
	if conversation.SessionID == "" {
		conversation.SessionID = uuid.NewString()
	}
	if conversation.TurnID == "" {
		conversation.TurnID = uuid.NewString()
	}
	if err := binding.Validate(); err != nil {
		return nil, nil, err
	}
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, nil, err
	}
	if task.Status != controlmodel.AgentTaskQueued {
		return nil, nil, store.ErrConflict
	}
	profile, err := s.Store.RuntimeRegistry().GetRuntimeProfileByID(ctx, binding.RuntimeProfileID)
	if err != nil || profile.Tenant != task.Tenant || profile.Namespace != task.Namespace {
		return nil, nil, fmt.Errorf("runtime profile %q: %w", binding.RuntimeProfileID, err)
	}
	pool, err := s.Store.RuntimeRegistry().GetRuntimePoolByID(ctx, binding.RuntimePoolID)
	if err != nil || pool.Tenant != task.Tenant || pool.Namespace != task.Namespace {
		return nil, nil, fmt.Errorf("runtime pool %q: %w", binding.RuntimePoolID, err)
	}
	resolvedConfiguration, err := binding.ExecutionOverrides.ResolveProviderConfiguration(profile.Configuration)
	if err != nil {
		return nil, nil, err
	}
	dispatchPolicyValues := make(map[string]any)
	if conversation.PreferredHostID != nil {
		dispatchPolicyValues["preferredHostId"] = conversation.PreferredHostID
	}
	if conversation.WorkspaceKey != "" {
		dispatchPolicyValues["workspaceKey"] = conversation.WorkspaceKey
	}
	var dispatchPolicy json.RawMessage
	if len(dispatchPolicyValues) > 0 {
		dispatchPolicy, _ = json.Marshal(dispatchPolicyValues)
	}
	var definition json.RawMessage
	if s.ResolveDefinition != nil {
		definition, err = s.ResolveDefinition(ctx, binding.AgentID)
		if err != nil {
			return nil, nil, fmt.Errorf("resolve Agent definition: %w", err)
		}
	}
	snapshot, err := json.Marshal(controlmodel.RuntimeDispatchSnapshot{Binding: binding, Definition: definition,
		RuntimeProfile: profile, RuntimePool: pool, ExecutionOverrides: binding.ExecutionOverrides,
		ResolvedProviderConfiguration: resolvedConfiguration,
		SessionID:                     conversation.SessionID,
		Capabilities:                  capabilities, SecurityConstraints: candidate.SecurityConstraints,
		Policy:          dispatchPolicy,
		SelectionSource: candidate.SelectionSource, CandidateIndex: candidate.CandidateIndex,
		ResolvedAt: time.Now().UTC()})
	if err != nil {
		return nil, nil, err
	}
	dispatched, execution, err := s.Store.Collaboration().ClaimAgentTaskWithAttempt(ctx, store.TaskClaim{
		TaskID: task.ID, ExpectedVersion: task.Version, RuntimeBinding: snapshot,
		SessionID: conversation.SessionID,
	}, &controlmodel.ExecutionAttempt{
		AgentTaskID: task.ID, Tenant: task.Tenant, Namespace: task.Namespace,
		AgentID: binding.AgentID, BindingID: binding.BindingID,
		BackendKind:        controlmodel.DataPlaneHostedRuntime,
		RuntimeProfileName: profile.Name, RuntimePoolName: pool.Name,
		RequiredCapabilities: capabilities,
		SessionID:            conversation.SessionID, TurnID: conversation.TurnID,
		ProviderSessionID: conversation.ProviderSessionID,
		WorkspaceKey:      conversation.WorkspaceKey,
	})
	if err != nil {
		return nil, nil, err
	}
	metrics.RecordAgentTaskTransition(dispatched.Namespace, string(binding.Kind), string(dispatched.Status))
	return dispatched, execution, nil
}

func (s *Service) Claim(ctx context.Context, claim store.ExecutionClaim) (*controlmodel.ExecutionAttempt, error) {
	if s == nil || s.Store == nil {
		return nil, fmt.Errorf("task plane store is required")
	}
	execution, err := s.Store.ExecutionAttempts().Claim(ctx, claim)
	result := "claimed"
	if errors.Is(err, store.ErrNotFound) {
		result = "empty"
	} else if err != nil {
		result = "error"
	}
	metrics.RecordRuntimeClaim(claim.Namespace, claim.RuntimePoolName, result)
	if err == nil {
		s.appendAttemptEvent(ctx, execution, "attempt.assigned", nil)
	}
	return execution, err
}

func (s *Service) MarkPreparing(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64) (*controlmodel.ExecutionAttempt, error) {
	execution, err := s.Store.ExecutionAttempts().MarkPreparing(ctx, id, leaseToken, fencingToken)
	if err == nil {
		s.appendAttemptEvent(ctx, execution, "attempt.preparing", nil)
	}
	return execution, err
}

func (s *Service) MarkRunning(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64, providerSessionID, workspaceKey string) (*controlmodel.ExecutionAttempt, error) {
	execution, err := s.Store.ExecutionAttempts().MarkRunning(ctx, id, leaseToken, fencingToken, providerSessionID, workspaceKey)
	if err != nil {
		return nil, err
	}
	task, err := s.Store.Collaboration().GetAgentTask(ctx, execution.AgentTaskID)
	if err != nil {
		return nil, err
	}
	if task.Status == controlmodel.AgentTaskDispatched {
		var started *controlmodel.AgentTask
		if started, err = s.Store.Collaboration().StartAgentTask(ctx, task.ID, task.Version); err != nil {
			return nil, err
		}
		metrics.RecordAgentTaskTransition(started.Namespace, string(execution.BackendKind), string(started.Status))
	}
	metrics.RecordExecutionAttemptTransition(execution.Namespace, string(execution.BackendKind), string(execution.State), "", 0)
	return execution, nil
}

func (s *Service) Checkpoint(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64, providerSessionID string, checkpoint json.RawMessage) (*controlmodel.ExecutionAttempt, error) {
	return s.Store.ExecutionAttempts().Checkpoint(ctx, id, leaseToken, fencingToken, providerSessionID, checkpoint)
}

func (s *Service) ConfirmCancelled(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64) (*controlmodel.ExecutionAttempt, error) {
	execution, err := s.Store.ExecutionAttempts().Get(ctx, id)
	if err != nil {
		return nil, err
	}
	if execution.State == controlmodel.ExecutionCancelled && execution.LeaseToken == leaseToken && execution.FencingToken == fencingToken {
		return execution, nil
	}
	execution, err = s.Store.ExecutionAttempts().ConfirmCancelled(ctx, id, leaseToken, fencingToken)
	if err == nil {
		s.appendAttemptEvent(ctx, execution, "attempt.cancelled", nil)
	}
	return execution, err
}

func (s *Service) Complete(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64, result, checkpoint json.RawMessage) (*controlmodel.ExecutionAttempt, error) {
	execution, err := s.Store.ExecutionAttempts().Get(ctx, id)
	if err != nil {
		return nil, err
	}
	if execution.State == controlmodel.ExecutionSucceeded && execution.LeaseToken == leaseToken && execution.FencingToken == fencingToken {
		return execution, nil
	}
	if execution.LeaseToken != leaseToken || execution.FencingToken != fencingToken ||
		!controlmodel.CanTransitionExecutionAttempt(execution.State, controlmodel.ExecutionSucceeded) {
		return nil, store.ErrConflict
	}
	task, err := s.Store.Collaboration().GetAgentTask(ctx, execution.AgentTaskID)
	if err != nil {
		return nil, err
	}
	completed, comment, err := (&collaboration.Service{Store: s.Store}).CompleteTask(ctx, task.ID,
		store.TaskCompletion{ExpectedVersion: task.Version, AttemptID: execution.ID,
			DispatchGeneration: execution.DispatchGeneration, LeaseToken: leaseToken,
			FencingToken: fencingToken, Result: result, Checkpoint: checkpoint,
			Usage: store.AttemptUsage(nil, result), Summary: resultSummary(result)},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef})
	if err != nil {
		return nil, err
	}
	if s.CommentSink != nil && comment != nil {
		_ = s.CommentSink(ctx, comment)
	}
	execution, err = s.Store.ExecutionAttempts().Get(ctx, id)
	if err != nil {
		return nil, err
	}
	metrics.RecordAgentTaskTransition(completed.Namespace, string(execution.BackendKind), string(completed.Status))
	metrics.RecordExecutionAttemptTransition(execution.Namespace, string(execution.BackendKind), string(execution.State), "", executionDuration(execution))
	return execution, nil
}

func resultSummary(result json.RawMessage) string {
	var value struct {
		Output string `json:"output"`
	}
	if json.Unmarshal(result, &value) == nil && value.Output != "" {
		return value.Output
	}
	return string(result)
}

func (s *Service) Fail(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64, code, message string, checkpoint json.RawMessage) (*controlmodel.ExecutionAttempt, error) {
	execution, err := s.Store.ExecutionAttempts().Get(ctx, id)
	if err != nil {
		return nil, err
	}
	if execution.State == controlmodel.ExecutionFailed && execution.LeaseToken == leaseToken && execution.FencingToken == fencingToken {
		return execution, nil
	}
	task, err := s.Store.Collaboration().GetAgentTask(ctx, execution.AgentTaskID)
	if err != nil {
		return nil, err
	}
	failed, execution, err := s.Store.Collaboration().FailAgentTaskWithAttempt(ctx, task.ID, store.TaskFailure{
		ExpectedVersion: task.Version, AttemptID: execution.ID, DispatchGeneration: execution.DispatchGeneration,
		LeaseToken: leaseToken, FencingToken: fencingToken, Code: code, Message: message, Checkpoint: checkpoint})
	if err != nil {
		return nil, err
	}
	metrics.RecordAgentTaskTransition(failed.Namespace, string(execution.BackendKind), string(failed.Status))
	metrics.RecordExecutionAttemptTransition(execution.Namespace, string(execution.BackendKind), string(execution.State), code, executionDuration(execution))
	return execution, nil
}

func (s *Service) appendAttemptEvent(ctx context.Context, execution *controlmodel.ExecutionAttempt,
	eventType string, values map[string]any) {
	if s == nil || s.Store == nil || execution == nil || execution.RunID == uuid.Nil {
		return
	}
	payload := map[string]any{
		"state": execution.State, "backendKind": execution.BackendKind,
		"hostId": execution.HostID, "attempt": execution.Attempt,
	}
	for key, value := range values {
		payload[key] = value
	}
	encoded, _ := json.Marshal(payload)
	_, _ = s.Store.Orchestration().AppendRunEvent(ctx, &controlmodel.RunEvent{
		RunID: execution.RunID, Tenant: execution.Tenant, Namespace: execution.Namespace,
		NodeID: &execution.NodeID, AgentTaskID: &execution.AgentTaskID, AttemptID: &execution.ID,
		Type: eventType, Actor: controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "task-plane"},
		Payload: encoded, IdempotencyKey: eventType + ":" + execution.ID.String(),
	})
}

func (s *Service) CancelTask(ctx context.Context, taskID uuid.UUID, expectedVersion int64) (*controlmodel.AgentTask, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	if controlmodel.IsAgentTaskTerminal(task.Status) {
		return task, nil
	}
	executions, err := s.Store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{AgentTaskID: taskID})
	if err != nil {
		return nil, err
	}
	for _, execution := range executions {
		if controlmodel.IsExecutionAttemptTerminal(execution.State) {
			continue
		}
		cancelled, cancelErr := s.Store.ExecutionAttempts().Cancel(ctx, execution.ID, execution.Version)
		if cancelErr != nil && !errors.Is(cancelErr, store.ErrConflict) {
			return nil, cancelErr
		}
		if cancelErr == nil && s.CancelBackend != nil && cancelled.State == controlmodel.ExecutionCancelRequested {
			if err := s.CancelBackend(ctx, cancelled); err != nil {
				payload, _ := json.Marshal(map[string]any{"attemptId": cancelled.ID, "error": err.Error()})
				_, _ = s.Store.Outbox().Enqueue(ctx, &controlmodel.OutboxEvent{Tenant: cancelled.Tenant,
					AggregateType: "execution-attempt", AggregateID: cancelled.ID.String(),
					EventType: "attempt.cancel.delivery_failed.v1", Payload: payload,
					DedupeKey: "attempt-cancel-delivery-failed:" + cancelled.ID.String(), AvailableAt: time.Now().UTC()})
			}
		}
	}
	return s.Store.Collaboration().CancelAgentTask(ctx, taskID, expectedVersion)
}

func (s *Service) RetryTask(ctx context.Context, taskID uuid.UUID) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	failed, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, nil, err
	}
	retry, err := s.Store.Collaboration().RetryAgentTask(ctx, taskID, failed.Originator)
	if err != nil {
		return nil, nil, err
	}
	var snapshot controlmodel.RuntimeDispatchSnapshot
	if err := json.Unmarshal(failed.RuntimeBinding, &snapshot); err != nil || snapshot.Binding.Kind != controlmodel.DataPlaneHostedRuntime {
		return retry, nil, nil
	}
	candidate := controlmodel.RuntimeBindingCandidate{Binding: snapshot.Binding,
		RequiredCapabilities: snapshot.Capabilities, SecurityConstraints: snapshot.SecurityConstraints,
		SelectionSource: snapshot.SelectionSource, CandidateIndex: snapshot.CandidateIndex}
	if snapshot.SessionID == "" {
		return s.DispatchHostedCandidate(ctx, retry.ID, candidate)
	}
	conversation, err := s.hostedConversationRetryContext(ctx, failed, snapshot)
	if err != nil {
		return retry, nil, err
	}
	dispatched, execution, dispatchErr := s.DispatchHostedConversationCandidate(ctx, retry.ID, candidate, conversation)
	if dispatchErr != nil {
		return dispatched, execution, dispatchErr
	}
	sessions, sessionErr := s.Store.Sessions().List(ctx, store.SessionFilter{Tenant: retry.Tenant,
		Namespace: retry.Namespace, AgentID: execution.AgentID, SessionID: snapshot.SessionID, Limit: 1})
	if sessionErr != nil {
		return dispatched, execution, sessionErr
	}
	if len(sessions) > 0 {
		now := time.Now().UTC()
		sessions[0].AgentTaskID, sessions[0].Phase, sessions[0].LastActiveAt =
			&retry.ID, store.SessionPhaseActive, &now
		if _, sessionErr = s.Store.Sessions().Upsert(ctx, sessions[0]); sessionErr != nil {
			return dispatched, execution, sessionErr
		}
	}
	return dispatched, execution, nil
}

func (s *Service) hostedConversationRetryContext(ctx context.Context, failed *controlmodel.AgentTask,
	snapshot controlmodel.RuntimeDispatchSnapshot) (HostedConversationContext, error) {
	attempts, listErr := s.Store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{
		AgentTaskID: failed.ID, NewestFirst: true, Limit: 1,
	})
	if listErr != nil {
		return HostedConversationContext{}, listErr
	}
	if len(attempts) == 0 {
		return HostedConversationContext{}, fmt.Errorf("conversation retry has no previous execution attempt")
	}
	failedAttempt := attempts[0]
	resumeAttempt := failedAttempt
	checkpoints, checkpointErr := s.Store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{
		Tenant: failed.Tenant, Namespace: failed.Namespace, AgentID: snapshot.Binding.AgentID,
		BindingID: snapshot.Binding.BindingID, SessionID: snapshot.SessionID,
		NewestFirst: true, Limit: 100,
	})
	if checkpointErr != nil {
		return HostedConversationContext{}, checkpointErr
	}
	for _, checkpoint := range checkpoints {
		if checkpoint.State == controlmodel.ExecutionSucceeded {
			resumeAttempt = checkpoint
			break
		}
	}
	providerSessionID, workspaceKey, preferredHostID := resumeAttempt.ProviderSessionID,
		resumeAttempt.WorkspaceKey, resumeAttempt.HostID
	if resumeAttempt.State != controlmodel.ExecutionSucceeded {
		providerSessionID = ""
	}
	if preferredHostID == nil {
		providerSessionID, workspaceKey = "", ""
	} else {
		host, hostErr := s.Store.RuntimeRegistry().GetRuntimeHost(ctx, *preferredHostID)
		if hostErr != nil || snapshot.RuntimePool == nil || snapshot.RuntimeProfile == nil ||
			host.State != controlmodel.RuntimeHostOnline ||
			host.PoolName != snapshot.RuntimePool.Name ||
			!controlmodel.RuntimeHostMatchesProfile(host.Capabilities, snapshot.RuntimeProfile) ||
			!controlmodel.RuntimeHostMatchesPool(host.Labels, snapshot.RuntimePool) {
			providerSessionID, workspaceKey, preferredHostID = "", "", nil
		}
	}
	return HostedConversationContext{
		SessionID: snapshot.SessionID, TurnID: failedAttempt.TurnID,
		ProviderSessionID: providerSessionID, WorkspaceKey: workspaceKey,
		PreferredHostID: preferredHostID,
	}, nil
}

func executionDuration(execution *controlmodel.ExecutionAttempt) time.Duration {
	if execution == nil || execution.StartedAt == nil || execution.CompletedAt == nil {
		return 0
	}
	return execution.CompletedAt.Sub(*execution.StartedAt)
}

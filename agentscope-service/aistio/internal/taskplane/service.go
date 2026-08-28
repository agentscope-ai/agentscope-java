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
	Store         store.Store
	CancelBackend func(context.Context, *controlmodel.ExecutionAttempt) error
}

// DispatchHosted freezes a hosted-runtime binding on a queued AgentTask and
// creates its next immutable physical attempt.
func (s *Service) DispatchHosted(ctx context.Context, taskID uuid.UUID, binding controlmodel.RuntimeBinding, capabilities json.RawMessage) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	return s.DispatchHostedCandidate(ctx, taskID, controlmodel.RuntimeBindingCandidate{Binding: binding, RequiredCapabilities: capabilities})
}

func (s *Service) DispatchHostedCandidate(ctx context.Context, taskID uuid.UUID, candidate controlmodel.RuntimeBindingCandidate) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	binding, capabilities := candidate.Binding, candidate.RequiredCapabilities
	if s == nil || s.Store == nil {
		return nil, nil, fmt.Errorf("task plane store is required")
	}
	if binding.Kind != controlmodel.DataPlaneHostedRuntime {
		return nil, nil, fmt.Errorf("hosted dispatch requires a hosted-runtime binding")
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
	snapshot, err := json.Marshal(controlmodel.RuntimeDispatchSnapshot{Binding: binding,
		Capabilities: capabilities, SecurityConstraints: candidate.SecurityConstraints,
		SelectionSource: candidate.SelectionSource, CandidateIndex: candidate.CandidateIndex,
		ResolvedAt: time.Now().UTC()})
	if err != nil {
		return nil, nil, err
	}
	dispatched, execution, err := s.Store.Collaboration().ClaimAgentTaskWithAttempt(ctx, store.TaskClaim{
		TaskID: task.ID, ExpectedVersion: task.Version, RuntimeBinding: snapshot,
	}, &controlmodel.ExecutionAttempt{
		AgentTaskID: task.ID, Tenant: task.Tenant, Namespace: task.Namespace,
		AgentID: binding.AgentID, BindingID: binding.BindingID,
		BackendKind:        controlmodel.DataPlaneHostedRuntime,
		RuntimeProfileName: profile.Name, RuntimePoolName: pool.Name,
		RequiredCapabilities: capabilities,
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
	return execution, err
}

func (s *Service) MarkPreparing(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64) (*controlmodel.ExecutionAttempt, error) {
	return s.Store.ExecutionAttempts().MarkPreparing(ctx, id, leaseToken, fencingToken)
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
	return s.Store.ExecutionAttempts().ConfirmCancelled(ctx, id, leaseToken, fencingToken)
}

func (s *Service) Complete(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64, result, checkpoint json.RawMessage) (*controlmodel.ExecutionAttempt, error) {
	execution, err := s.Store.ExecutionAttempts().Get(ctx, id)
	if err != nil {
		return nil, err
	}
	if execution.LeaseToken != leaseToken || execution.FencingToken != fencingToken ||
		!controlmodel.CanTransitionExecutionAttempt(execution.State, controlmodel.ExecutionSucceeded) {
		return nil, store.ErrConflict
	}
	task, err := s.Store.Collaboration().GetAgentTask(ctx, execution.AgentTaskID)
	if err != nil {
		return nil, err
	}
	completed, _, err := (&collaboration.Service{Store: s.Store}).CompleteTask(ctx, task.ID,
		store.TaskCompletion{ExpectedVersion: task.Version, AttemptID: execution.ID,
			DispatchGeneration: execution.DispatchGeneration, LeaseToken: leaseToken,
			FencingToken: fencingToken, Result: result, Checkpoint: checkpoint,
			Usage: store.AttemptUsage(nil, result), Summary: resultSummary(result)},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef})
	if err != nil {
		return nil, err
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
	return s.DispatchHosted(ctx, retry.ID, snapshot.Binding, snapshot.Capabilities)
}

func executionDuration(execution *controlmodel.ExecutionAttempt) time.Duration {
	if execution == nil || execution.StartedAt == nil || execution.CompletedAt == nil {
		return 0
	}
	return execution.CompletedAt.Sub(*execution.StartedAt)
}

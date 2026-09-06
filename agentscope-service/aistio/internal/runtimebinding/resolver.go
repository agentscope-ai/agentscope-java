// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

// Package runtimebinding resolves stable Agent identities to one immutable
// execution binding while keeping runtime details out of Issue collaboration.
package runtimebinding

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/metrics"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/taskauth"
	"github.com/spring-ai-alibaba/aistio/internal/taskplane"
)

const commandAttemptDispatch = "dispatch"

type ManagedSessionAPI interface {
	FindOrCreateSessionID(ctx context.Context, ownerID, agentID, environmentID, externalKey string) (string, error)
	ClaimManagedRuntimeFence(ctx context.Context, sessionID string, agentTaskID, attemptID uuid.UUID,
		dispatchGeneration int64, turnID string) error
	PostSessionWakeEvent(ctx context.Context, sessionID, ownerID, text string) error
	PostManagedAttemptAbort(ctx context.Context, sessionID, ownerID string, abort product.ManagedAttemptAbort) error
}

// CancelAttempt sends a backend-specific cancellation after the durable
// cancel_requested transition. Hosted Runtime observes it on lease renewal.
func (r *Resolver) CancelAttempt(ctx context.Context, attempt *controlmodel.ExecutionAttempt) error {
	if attempt == nil {
		return fmt.Errorf("execution attempt is required")
	}
	switch attempt.BackendKind {
	case controlmodel.DataPlaneHostedRuntime:
		return nil
	case controlmodel.DataPlaneManaged:
		if r.Managed == nil {
			return fmt.Errorf("managed runtime adapter is not configured")
		}
		return r.Managed.PostManagedAttemptAbort(ctx, attempt.SessionID, attempt.ManagedOwnerRef,
			product.ManagedAttemptAbort{AgentTaskID: attempt.AgentTaskID, AttemptID: attempt.ID,
				DispatchGeneration: attempt.DispatchGeneration, TurnID: attempt.TurnID,
				Reason: "task_cancel_requested"})
	case controlmodel.DataPlaneExternalApplication:
		if r.External == nil || attempt.AgentInstanceID == nil {
			return fmt.Errorf("external runtime target is unavailable")
		}
		instance, err := r.Store.RuntimeRegistry().GetAgentInstance(ctx, *attempt.AgentInstanceID)
		if err != nil {
			return err
		}
		payload, _ := json.Marshal(map[string]any{"attemptId": attempt.ID, "agentTaskId": attempt.AgentTaskID,
			"runId": attempt.RunID, "nodeId": attempt.NodeID, "generation": attempt.DispatchGeneration,
			"attemptToken": r.attemptToken(attempt)})
		return r.External.SendExecutionAttemptCommand(attempt.Tenant, attempt.Namespace, attempt.AgentID.String(), instance.InstanceKey,
			attempt.SessionID, "cancel", payload)
	default:
		return fmt.Errorf("unsupported runtime binding kind %q", attempt.BackendKind)
	}
}

type ExternalCommander interface {
	SendExecutionAttemptCommand(tenant, namespace, agentID, instanceID, sessionID, command string, params []byte) error
}

type Resolver struct {
	Store    store.Store
	Tasks    *taskplane.Service
	Managed  ManagedSessionAPI
	External ExternalCommander
	Tokens   *taskauth.Manager
}

type DispatchResult struct {
	Task            *controlmodel.AgentTask        `json:"task"`
	Execution       *controlmodel.ExecutionAttempt `json:"execution,omitempty"`
	AgentInstanceID *uuid.UUID                     `json:"agentInstanceId,omitempty"`
	SessionID       string                         `json:"sessionId,omitempty"`
	TaskToken       string                         `json:"taskToken,omitempty"`
	AttemptToken    string                         `json:"attemptToken,omitempty"`
}

// LoadRunTeamSnapshot returns the immutable Team roster/policy captured when
// that Team first participated in the Run.
func LoadRunTeamSnapshot(ctx context.Context, st store.Store, runID, teamID uuid.UUID) (*controlmodel.CollaborationTeam, error) {
	snapshots, err := st.Orchestration().ListTeamSnapshots(ctx, runID)
	if err != nil {
		return nil, err
	}
	for _, snapshot := range snapshots {
		if snapshot.TeamID != teamID {
			continue
		}
		var team controlmodel.CollaborationTeam
		if err = json.Unmarshal(snapshot.Snapshot, &team); err != nil {
			return nil, fmt.Errorf("decode Run Team snapshot: %w", err)
		}
		return &team, nil
	}
	return nil, fmt.Errorf("Run %s has no snapshot for Team %s", runID, teamID)
}

func (r *Resolver) Resolve(ctx context.Context, taskID uuid.UUID, requested *controlmodel.RuntimeBinding) (controlmodel.RuntimeBinding, error) {
	var candidate *controlmodel.RuntimeBindingCandidate
	if requested != nil {
		candidate = &controlmodel.RuntimeBindingCandidate{Binding: *requested}
	}
	resolved, err := r.ResolveCandidate(ctx, taskID, candidate)
	return resolved.Binding, err
}

// ResolveCandidate applies the immutable runtime precedence used by every
// backend: node/task override, then Team member override, then Agent policy.
func (r *Resolver) ResolveCandidate(ctx context.Context, taskID uuid.UUID, requested *controlmodel.RuntimeBindingCandidate) (controlmodel.RuntimeBindingCandidate, error) {
	if r == nil || r.Store == nil {
		return controlmodel.RuntimeBindingCandidate{}, fmt.Errorf("runtime binding resolver store is required")
	}
	task, err := r.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return controlmodel.RuntimeBindingCandidate{}, err
	}
	if requested != nil && requested.Binding.Kind != "" {
		if err := requested.Binding.Validate(); err != nil {
			return controlmodel.RuntimeBindingCandidate{}, err
		}
		return *requested, nil
	}
	if len(task.RuntimeBinding) > 0 {
		var existing controlmodel.RuntimeDispatchSnapshot
		if json.Unmarshal(task.RuntimeBinding, &existing) == nil && existing.Binding.Kind != "" && existing.SelectionSource == "node" {
			return controlmodel.RuntimeBindingCandidate{Binding: existing.Binding,
				RequiredCapabilities: existing.Capabilities, SecurityConstraints: existing.SecurityConstraints,
				SelectionSource: existing.SelectionSource, CandidateIndex: existing.CandidateIndex}, existing.Binding.Validate()
		}
	}
	if task.TeamID != nil {
		team, loadErr := LoadRunTeamSnapshot(ctx, r.Store, task.OrchestrationRunID, *task.TeamID)
		if loadErr != nil {
			return controlmodel.RuntimeBindingCandidate{}, loadErr
		}
		for _, member := range team.Members {
			if member.AgentRef != task.AgentRef || task.TeamRole != "" && member.Role != task.TeamRole {
				continue
			}
			var policy controlmodel.RuntimeBindingPolicy
			if json.Unmarshal(member.RuntimeBindingPolicy, &policy) == nil && policy.SelectionMode == "ordered" && len(policy.Candidates) > 0 {
				candidate := policy.Candidates[0]
				candidate.SelectionSource, candidate.CandidateIndex = "team", 0
				return candidate, candidate.Binding.Validate()
			}
		}
	}
	policy, err := r.Store.Orchestration().GetRuntimePolicy(ctx, task.Tenant, task.Namespace, task.AgentRef)
	if err != nil {
		return controlmodel.RuntimeBindingCandidate{}, fmt.Errorf("no runtime policy is configured for agent %q: %w", task.AgentRef, err)
	}
	if policy.SelectionMode != "ordered" || len(policy.Candidates) == 0 {
		return controlmodel.RuntimeBindingCandidate{}, fmt.Errorf("runtime policy for agent %q has no ordered candidates", task.AgentRef)
	}
	candidate := policy.Candidates[0]
	return candidate, candidate.Binding.Validate()
}

func (r *Resolver) Dispatch(ctx context.Context, taskID uuid.UUID, requested *controlmodel.RuntimeBinding) (*DispatchResult, error) {
	var candidate *controlmodel.RuntimeBindingCandidate
	if requested != nil {
		candidate = &controlmodel.RuntimeBindingCandidate{Binding: *requested}
	}
	return r.DispatchCandidate(ctx, taskID, candidate)
}

func (r *Resolver) DispatchCandidate(ctx context.Context, taskID uuid.UUID, requested *controlmodel.RuntimeBindingCandidate) (*DispatchResult, error) {
	candidate, err := r.ResolveCandidate(ctx, taskID, requested)
	if err != nil {
		return nil, err
	}
	binding := candidate.Binding
	task, err := r.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	if err := r.validateCatalogBinding(ctx, task, binding); err != nil {
		return nil, err
	}
	switch binding.Kind {
	case controlmodel.DataPlaneHostedRuntime:
		tasks := r.Tasks
		if tasks == nil {
			tasks = &taskplane.Service{Store: r.Store}
		}
		task, execution, err := tasks.DispatchHostedCandidate(ctx, taskID, candidate)
		return &DispatchResult{Task: task, Execution: execution,
			TaskToken: r.taskTokenForAttempt(taskID, execution), AttemptToken: r.attemptToken(execution)}, err
	case controlmodel.DataPlaneManaged:
		return r.dispatchManaged(ctx, taskID, candidate)
	case controlmodel.DataPlaneExternalApplication:
		return r.dispatchExternal(ctx, taskID, candidate)
	default:
		return nil, fmt.Errorf("unsupported runtime binding kind %q", binding.Kind)
	}
}

func (r *Resolver) validateCatalogBinding(ctx context.Context, task *controlmodel.AgentTask, binding controlmodel.RuntimeBinding) error {
	agentID, err := uuid.Parse(task.AgentRef)
	if err != nil || binding.AgentID != agentID {
		return fmt.Errorf("runtime binding does not match AgentTask agentId")
	}
	agent, err := r.Store.AgentCatalog().GetAgent(ctx, agentID)
	if err != nil {
		return fmt.Errorf("resolve Agent %s: %w", agentID, err)
	}
	if agent.Status != controlmodel.AgentActive || agent.Tenant != task.Tenant || agent.Namespace != task.Namespace {
		return fmt.Errorf("Agent %s is not active in the task scope", agentID)
	}
	stored, err := r.Store.AgentCatalog().GetBinding(ctx, binding.BindingID)
	if err != nil {
		return fmt.Errorf("resolve AgentBinding %s: %w", binding.BindingID, err)
	}
	if stored.AgentID != agentID || stored.Kind != binding.Kind || !stored.Enabled || stored.ArchivedAt != nil {
		return fmt.Errorf("AgentBinding %s is disabled or does not match the task", binding.BindingID)
	}
	return nil
}

func (r *Resolver) dispatchManaged(ctx context.Context, taskID uuid.UUID, candidate controlmodel.RuntimeBindingCandidate) (*DispatchResult, error) {
	binding := candidate.Binding
	if r.Managed == nil {
		return nil, fmt.Errorf("managed runtime adapter is not configured")
	}
	task, err := r.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	sessionID, err := r.Managed.FindOrCreateSessionID(ctx, binding.ManagedOwnerRef,
		binding.ManagedDefinitionRef, "", "agent-task|"+task.ID.String())
	if err != nil {
		return nil, err
	}
	snapshot, _ := json.Marshal(controlmodel.RuntimeDispatchSnapshot{Binding: binding,
		SessionID: sessionID, Capabilities: candidate.RequiredCapabilities,
		SecurityConstraints: candidate.SecurityConstraints, SelectionSource: candidate.SelectionSource,
		CandidateIndex: candidate.CandidateIndex, ResolvedAt: time.Now().UTC()})
	dispatched, attempt, err := r.Store.Collaboration().ClaimAgentTaskWithAttempt(ctx, store.TaskClaim{
		TaskID: task.ID, ExpectedVersion: task.Version, RuntimeBinding: snapshot, SessionID: sessionID,
	}, &controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
		AgentID: binding.AgentID, BindingID: binding.BindingID,
		State: controlmodel.ExecutionAssigned, ManagedOwnerRef: binding.ManagedOwnerRef,
		ManagedAgentRef: binding.ManagedDefinitionRef, SessionID: sessionID, TurnID: uuid.NewString(),
		RequiredCapabilities: candidate.RequiredCapabilities})
	if err != nil {
		return nil, err
	}
	if err := r.persistSession(ctx, dispatched, sessionID, binding, nil); err != nil {
		return nil, err
	}
	if err := r.Managed.ClaimManagedRuntimeFence(ctx, sessionID, dispatched.ID, attempt.ID,
		attempt.DispatchGeneration, attempt.TurnID); err != nil {
		_, _, _ = r.Store.Collaboration().RequeueAgentTaskAfterAttemptFailure(ctx, task.ID, store.TaskFailure{
			ExpectedVersion: dispatched.Version, AttemptID: attempt.ID, DispatchGeneration: attempt.DispatchGeneration,
			Code: "managed_runtime_fence_claim_failed", Message: err.Error()})
		return nil, err
	}
	if err := r.Managed.PostSessionWakeEvent(ctx, sessionID, binding.ManagedOwnerRef,
		managedWakeInstructions(task)); err != nil {
		_, _, _ = r.Store.Collaboration().RequeueAgentTaskAfterAttemptFailure(ctx, task.ID, store.TaskFailure{
			ExpectedVersion: dispatched.Version, AttemptID: attempt.ID, DispatchGeneration: attempt.DispatchGeneration,
			Code: "managed_wake_failed", Message: err.Error()})
		return nil, err
	}
	metrics.RecordAgentTaskTransition(dispatched.Namespace, string(binding.Kind), string(dispatched.Status))
	return &DispatchResult{Task: dispatched, Execution: attempt, SessionID: sessionID,
		TaskToken: r.taskTokenForAttempt(taskID, attempt), AttemptToken: r.attemptToken(attempt)}, nil
}

func managedWakeInstructions(task *controlmodel.AgentTask) string {
	base := "A durable AgentTask is ready. Use the aistio-collaboration tools to read authoritative " +
		"context, perform work, report progress, and finish. Do not merely describe intended actions; " +
		"only report an action after tool success."
	if task == nil || task.TeamID == nil {
		return base + " Call task.complete when the work is done, or task.fail when it cannot be completed."
	}
	if !task.LeaderTask {
		return base + " You are a Team worker. Complete only the assigned child work and call task.complete " +
			"with the result. If required capabilities, credentials, inputs, or tools are unavailable, call " +
			"task.fail with a durable code and explanation; do not merely return explanatory text. Do not " +
			"coordinate or create child Issues."
	}
	if task.ParentTaskID == nil {
		return base + " You are the initial Team leader. Delegate suitable child work once, using the " +
			"member.agentId from team.get as assigneeRef (never the membership id). After every " +
			"issue.child.create succeeds, call task.complete immediately with a delegation summary; do not " +
			"wait inside this turn. A fresh leader follow-up will arrive with each worker result."
	}
	return base + " You are a Team leader follow-up caused by a worker outcome. Read the supplied task " +
		"inputs and comments and validate the outcome. Call issue.accept only for satisfactory completed " +
		"work. For blocked or failed work, retry or reassign only when the new attempt changes the available " +
		"agent, capability, credential, input, or tool; otherwise choose a degraded result, request human " +
		"action, cancel the blocked child, or fail the coordinator. Do not use issue.child.create to bypass " +
		"an unresolved blocked Issue; use the explicit decision actions. Call run.node.complete only when the whole " +
		"coordinator has converged. If sibling work is still active, do not retry run.node.complete in a loop; " +
		"call task.complete with a waiting/decision summary so this follow-up ends and the next worker outcome " +
		"can wake a fresh follow-up. A successful run.node.complete already completes this task."
}

func (r *Resolver) dispatchExternal(ctx context.Context, taskID uuid.UUID, candidate controlmodel.RuntimeBindingCandidate) (*DispatchResult, error) {
	binding := candidate.Binding
	if r.External == nil {
		return nil, fmt.Errorf("external runtime adapter is not configured")
	}
	task, err := r.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	instance, err := r.selectExternal(ctx, task, binding, candidate.RequiredCapabilities,
		candidate.SecurityConstraints)
	if err != nil {
		return nil, err
	}
	sessionID := uuid.NewString()
	instanceID := instance.ID
	snapshot, _ := json.Marshal(controlmodel.RuntimeDispatchSnapshot{Binding: binding,
		AgentInstanceID: &instanceID, SessionID: sessionID, Capabilities: instance.Capabilities,
		SecurityConstraints: candidate.SecurityConstraints,
		SelectionSource:     candidate.SelectionSource, CandidateIndex: candidate.CandidateIndex,
		ResolvedAt: time.Now().UTC()})
	dispatched, attempt, err := r.Store.Collaboration().ClaimAgentTaskWithAttempt(ctx, store.TaskClaim{
		TaskID: task.ID, ExpectedVersion: task.Version, RuntimeBinding: snapshot, SessionID: sessionID,
	}, &controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneExternalApplication,
		AgentID: binding.AgentID, BindingID: binding.BindingID,
		State: controlmodel.ExecutionAssigned, AgentInstanceID: &instanceID, SessionID: sessionID,
		TurnID:               uuid.NewString(),
		RequiredCapabilities: candidate.RequiredCapabilities})
	if err != nil {
		return nil, err
	}
	if err := r.persistSession(ctx, dispatched, sessionID, binding, instance); err != nil {
		return nil, err
	}
	payload, _ := json.Marshal(map[string]any{"attemptId": attempt.ID, "agentTaskId": task.ID,
		"runId": task.OrchestrationRunID, "nodeId": task.RunNodeID,
		"generation": attempt.DispatchGeneration,
		"contextUrl": "/api/v1/agent-tasks/" + task.ID.String() + "/context",
		"taskToken":  r.taskTokenForAttempt(task.ID, attempt), "attemptToken": r.attemptToken(attempt),
		"runtimeBinding": json.RawMessage(snapshot)})
	if err := r.External.SendExecutionAttemptCommand(task.Tenant, task.Namespace, instance.AgentID.String(), instance.InstanceKey,
		sessionID, commandAttemptDispatch, payload); err != nil {
		_, _, _ = r.Store.Collaboration().RequeueAgentTaskAfterAttemptFailure(ctx, task.ID, store.TaskFailure{
			ExpectedVersion: dispatched.Version, AttemptID: attempt.ID, DispatchGeneration: attempt.DispatchGeneration,
			Code: "external_dispatch_failed", Message: err.Error()})
		return nil, err
	}
	metrics.RecordAgentTaskTransition(dispatched.Namespace, string(binding.Kind), string(dispatched.Status))
	return &DispatchResult{Task: dispatched, Execution: attempt, AgentInstanceID: &instanceID, SessionID: sessionID,
		TaskToken: r.taskTokenForAttempt(taskID, attempt), AttemptToken: r.attemptToken(attempt)}, nil
}

func (r *Resolver) selectExternal(ctx context.Context, task *controlmodel.AgentTask, binding controlmodel.RuntimeBinding,
	required, security json.RawMessage) (*controlmodel.AgentInstance, error) {
	instances, err := r.Store.RuntimeRegistry().ListAgentInstances(ctx, task.Tenant, task.Namespace, binding.AgentID)
	if err != nil {
		return nil, err
	}
	for _, instance := range instances {
		if instance.BindingID != binding.BindingID {
			continue
		}
		if binding.InstanceSelector["instance"] != "" && binding.InstanceSelector["instance"] != instance.InstanceKey {
			continue
		}
		if instance.Health == controlmodel.RuntimeHealthHealthy && (instance.Capacity <= 0 || instance.ActiveSessions < instance.Capacity) &&
			capabilitiesMatch(instance.Capabilities, required) &&
			controlmodel.RuntimeSecurityMatches(controlmodel.DataPlaneExternalApplication, instance.Labels, security) {
			return instance, nil
		}
	}
	return nil, fmt.Errorf("no healthy external AgentInstance matches agent %q", binding.AgentID)
}

func capabilitiesMatch(actual, required json.RawMessage) bool {
	return controlmodel.JSONContains(actual, required)
}

func (r *Resolver) persistSession(ctx context.Context, task *controlmodel.AgentTask, sessionID string,
	binding controlmodel.RuntimeBinding, instance *controlmodel.AgentInstance) error {
	envelope, err := (&collaboration.Service{Store: r.Store}).BuildContext(ctx, task.ID)
	if err != nil {
		return err
	}
	contextJSON, err := json.Marshal(envelope)
	if err != nil {
		return err
	}
	agent, err := r.Store.AgentCatalog().GetAgent(ctx, binding.AgentID)
	if err != nil {
		return err
	}
	instanceRef := ""
	agentInstanceID := uuid.Nil
	instanceGeneration := int64(0)
	if instance != nil {
		instanceRef = instance.InstanceKey
		agentInstanceID = instance.ID
		instanceGeneration = instance.Generation
	}
	now := time.Now().UTC()
	_, err = r.Store.Sessions().Upsert(ctx, &store.Session{SessionID: sessionID,
		Tenant: task.Tenant, AgentID: binding.AgentID, BindingID: binding.BindingID,
		AgentInstanceID: agentInstanceID, InstanceGeneration: instanceGeneration,
		AgentName: agent.AgentKey, Namespace: task.Namespace, InstanceRef: instanceRef,
		OriginType: "agent-task", OriginRef: task.ID.String(),
		Phase: store.SessionPhaseActive, AgentTaskID: &task.ID, TaskContext: contextJSON,
		StartedAt: &now, LastActiveAt: &now})
	return err
}

func (r *Resolver) taskToken(taskID uuid.UUID) string {
	if r.Tokens == nil {
		return ""
	}
	token, _ := r.Tokens.Mint(taskID, time.Now().UTC())
	return token
}

func (r *Resolver) taskTokenForAttempt(taskID uuid.UUID, attempt *controlmodel.ExecutionAttempt) string {
	if r.Tokens == nil || attempt == nil {
		return ""
	}
	token, _ := r.Tokens.MintScoped(taskID, attempt.ID, attempt.DispatchGeneration, time.Now().UTC())
	return token
}

func (r *Resolver) attemptToken(attempt *controlmodel.ExecutionAttempt) string {
	if r.Tokens == nil || attempt == nil {
		return ""
	}
	target := attempt.ManagedOwnerRef + "/" + attempt.ManagedAgentRef
	if attempt.AgentInstanceID != nil {
		target = attempt.AgentInstanceID.String()
	} else if attempt.HostID != nil {
		target = attempt.HostID.String()
	}
	token, _ := r.Tokens.MintAttempt(attempt.ID, attempt.DispatchGeneration, string(attempt.BackendKind), target, time.Now().UTC())
	return token
}

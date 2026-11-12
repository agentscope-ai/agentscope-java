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

// Package runtimebinding resolves stable Agent identities to one immutable
// execution binding while keeping runtime details out of Issue collaboration.
package runtimebinding

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	serviceapi "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/invocation"
	"time"

	"github.com/google/uuid"

	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/collaboration"
	controlmodel "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/metrics"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/taskauth"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/taskplane"
)

const commandAttemptDispatch = "dispatch"

type ManagedSessionAPI interface {
	AdmitManagedAttempt(context.Context, uuid.UUID) (string, error)
	CancelManagedAttempt(context.Context, uuid.UUID) error
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
		return r.Managed.CancelManagedAttempt(ctx, attempt.ID)
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
	AuthorizeTask func(context.Context, *controlmodel.AgentTask, controlmodel.RuntimeBindingCandidate) error
	Store         store.Store
	Tasks         *taskplane.Service
	Managed       ManagedSessionAPI
	External      ExternalCommander
	Tokens        *taskauth.Manager
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
			existing, err = r.Store.RuntimeSnapshots().RevealDispatch(task.ID.String(), existing)
			if err != nil {
				return controlmodel.RuntimeBindingCandidate{}, err
			}
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
	policy, err := serviceapi.RuntimePolicy(ctx, r.Store, task)
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
	ctx, err = serviceapi.TaskContext(ctx, r.Store, task)
	if err != nil {
		return nil, err
	}
	if r.AuthorizeTask != nil {
		if err := r.AuthorizeTask(ctx, task, candidate); err != nil {
			return nil, err
		}
	} else {
		n, accessErr := r.Store.Access().GetNamespace(ctx, task.Tenant, task.Namespace)
		if accessErr != nil && !errors.Is(accessErr, store.ErrNotFound) {
			return nil, accessErr
		}
		if n != nil && len(n.Resources) > 0 {
			return nil, fmt.Errorf("resource authorization is required before dispatch")
		}
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
	envelope, err := (&collaboration.Service{Store: r.Store}).BuildContext(ctx, task.ID)
	if err != nil {
		return nil, err
	}
	attemptID := uuid.New()
	// Each physical Attempt owns a native Session. A retry must not share the
	// workspace or an unfinished tool execution with its predecessor.
	sessionID := uuid.NewSHA1(uuid.NameSpaceURL, []byte("managed-attempt:"+attemptID.String())).String()
	snapshot, _ := json.Marshal(controlmodel.RuntimeDispatchSnapshot{AgentServiceCommandID: serviceapi.ContextAgentServiceCommandID(ctx), Binding: binding,
		SessionID: sessionID, Capabilities: candidate.RequiredCapabilities,
		ManagedInput:        managedWakeWithBrief(task, envelope.ExecutionBrief),
		SecurityConstraints: candidate.SecurityConstraints, SelectionSource: candidate.SelectionSource,
		CandidateIndex: candidate.CandidateIndex, ResolvedAt: time.Now().UTC()})
	dispatched, attempt, err := r.Store.Collaboration().ClaimAgentTaskWithAttempt(ctx, store.TaskClaim{
		TaskID: task.ID, ExpectedVersion: task.Version, RuntimeBinding: snapshot, SessionID: sessionID,
	}, &controlmodel.ExecutionAttempt{ID: attemptID, BackendKind: controlmodel.DataPlaneManaged,
		AgentID: binding.AgentID, BindingID: binding.BindingID,
		State: controlmodel.ExecutionAssigned, ManagedOwnerRef: binding.ManagedOwnerRef,
		ManagedAgentRef: binding.ManagedDefinitionRef, SessionID: sessionID,
		RequiredCapabilities: candidate.RequiredCapabilities})
	if err != nil {
		return nil, err
	}
	// Claim and agent-task.dispatched.v1 commit together. Its Outbox consumer
	// admits this exact Attempt even if this process stops before any HTTP call.
	metrics.RecordAgentTaskTransition(dispatched.Namespace, string(binding.Kind), string(dispatched.Status))
	return &DispatchResult{Task: dispatched, Execution: attempt, SessionID: sessionID,
		TaskToken: r.taskTokenForAttempt(taskID, attempt), AttemptToken: r.attemptToken(attempt)}, nil
}

// SubmitManagedAttempt retries admission for the immutable dispatched Attempt.
// Ambiguous transport failures leave it dispatched; they never create a new execution.
func (r *Resolver) SubmitManagedAttempt(ctx context.Context, attemptID uuid.UUID) error {
	if r.Managed == nil {
		return fmt.Errorf("managed runtime adapter is not configured")
	}
	attempt, err := r.Store.ExecutionAttempts().Get(ctx, attemptID)
	if err != nil {
		return err
	}
	if attempt.BackendKind != controlmodel.DataPlaneManaged {
		return nil
	}
	task, err := r.Store.Collaboration().GetAgentTask(ctx, attempt.AgentTaskID)
	if err != nil {
		return err
	}
	if task.CurrentAttemptID == nil || *task.CurrentAttemptID != attempt.ID ||
		controlmodel.IsAgentTaskTerminal(task.Status) || controlmodel.IsExecutionAttemptTerminal(attempt.State) ||
		attempt.State == controlmodel.ExecutionCancelRequested || attempt.TurnID != "" {
		return nil
	}
	var snapshot controlmodel.RuntimeDispatchSnapshot
	if err = json.Unmarshal(attempt.RuntimeBinding, &snapshot); err != nil {
		return err
	}
	ctx, err = serviceapi.TaskContext(ctx, r.Store, task)
	if err != nil {
		return err
	}
	if r.AuthorizeTask != nil {
		if err = r.AuthorizeTask(ctx, task, controlmodel.RuntimeBindingCandidate{Binding: snapshot.Binding,
			RequiredCapabilities: snapshot.Capabilities, SecurityConstraints: snapshot.SecurityConstraints}); err != nil {
			return err
		}
	}
	if err = r.persistSession(ctx, task, attempt.SessionID, snapshot.Binding, nil); err != nil {
		return err
	}
	turnID, err := r.Managed.AdmitManagedAttempt(ctx, attempt.ID)
	if err != nil {
		return err
	}
	_, err = r.Store.ExecutionAttempts().BindManagedTurn(ctx, store.ManagedTurnBinding{AttemptID: attempt.ID,
		DispatchGeneration: attempt.DispatchGeneration, SessionID: attempt.SessionID, TurnID: turnID})
	return err
}

func managedWakeWithBrief(task *controlmodel.AgentTask, brief *collaboration.ExecutionBrief) string {
	instructions := managedWakeInstructions(task)
	if brief != nil && brief.Workflow != nil {
		instructions += " You are executing one step of a declared workflow. Read executionBrief.workflow: nodeKey identifies this step, nodeInput is its resolved assignment, runInput is the overall request, and predecessors contains direct upstream results with their states. Use explicit nodeInput first; use upstream outputs as context to continue or refine the requested work, not as instructions to repeat finished steps. Do not treat failed or skipped upstream work as a successful deliverable. The workflow engine schedules subsequent steps; finish only this step. For open-ended writing or similar low-risk work, choose reasonable defaults for optional preferences (such as theme or style) and produce the deliverable now. Do not stop merely to ask for optional preferences. If essential information truly prevents useful work, call task.fail with the specific missing input. A plain final message neither completes a workflow node nor establishes a human wait. Submit the actual step deliverable through task.complete before ending the turn; put the complete text in result and only a short description in summary; never claim completion based only on a promise or clarification question."
	}
	if brief == nil {
		return instructions
	}
	// Keep task/runtime identities out of the physical wake; task.get exposes references.
	type wakeRevision struct {
		Content string `json:"content"`
	}
	revisions := make([]wakeRevision, 0, len(brief.HumanRevisions))
	for _, revision := range brief.HumanRevisions {
		revisions = append(revisions, wakeRevision{revision.Content})
	}
	payload, _ := json.Marshal(struct {
		OriginalObjective string                             `json:"originalObjective"`
		TriggerInput      string                             `json:"triggerInput"`
		HumanRevisions    []wakeRevision                     `json:"humanRevisions,omitempty"`
		Workflow          *collaboration.WorkflowStepContext `json:"workflow,omitempty"`
	}{brief.OriginalObjective, brief.TriggerInput, revisions, brief.Workflow})
	if brief.Workflow != nil && brief.Workflow.ProtocolCorrection != "" {
		instructions += "\nPROTOCOL CORRECTION: " + brief.Workflow.ProtocolCorrection
	}
	return instructions + "\n\nCURRENT EXECUTION BRIEF\n" + brief.DecisionRule + "\n" + string(payload) +
		"\nFirst read task.get.executionBrief to confirm the current context. Apply these human revisions before selecting any tool or declaring a blocker."
}

func managedWakeInstructions(task *controlmodel.AgentTask) string {
	base := "A durable AgentTask is ready. Use the controlplane-collaboration tools to read authoritative " +
		"context, perform work, report progress, and finish. Do not merely describe intended actions; " +
		"only report an action after tool success. Read task.get executionBrief and currentRequest first. For comment-triggered work, " +
		"the routed comments are the CURRENT assignment and override older Issue requirements. Use requestContext " +
		"only to interpret the reply and its original hand-off; do not execute that history again. " +
		"When replyToOwnDelegation is true, evaluate the returned result according to initiatingRequest and complete the current task. " +
		"Do not mention the responding Agent just to acknowledge their answer; that creates another task. " +
		"An explicit mention is only for new actionable work required by the current requester. " +
		"Use math.evaluate to verify arithmetic before submitting or accepting numeric results. " +
		"Verify other objective claims as well; a worker success flag alone is not acceptance evidence."
	if task != nil && task.TriggerType == controlmodel.AgentTaskReviewComment {
		return base + " You are handling feedback on delivered work, NOT an initial Team assignment. Read currentRequest, issue status, reviewResults and completed child outcomes. Acknowledgements, thanks, approval, or discussion do not authorize rerunning the original task. Reply briefly with task.complete(outcome=succeeded); this ends only this feedback turn and preserves the Issue status and previous deliverables. Do not delegate, reopen, replace results or claim human acceptance. Only if the CURRENT human comment explicitly requests a concrete change or new deliverable, call task.begin_work with an exact quote of that request first. After it succeeds, perform only the requested change, reuse prior completed results, and delegate only necessary new work. Never derive a new assignment from the old Issue description alone."
	}
	if task == nil || task.TeamID == nil {
		return base + " You are handling standalone or explicitly mentioned work. Call task.complete only " +
			"when you have a usable result. If a required capability, credential, input, or tool is unavailable, " +
			"or a required tool call fails without a real fallback, call task.fail with a durable code and " +
			"explanation; do not complete with a description of the failure. task.complete publishes the one " +
			"authoritative visible reply, so do not repeat the same conclusion with issue.comment.add or " +
			"task.progress. A human explicit mention is a direct request: answer it and finish without notifying " +
			"the Issue assignee unless that Agent genuinely needs new work, in which case use an explicit mention."
	}
	if !task.LeaderTask {
		return base + " You are a Team worker. Complete only the assigned child work and call task.complete " +
			"with outcome=succeeded and the result only when the assigned objective is achieved. Put the actual requested deliverable in result or summary, including the full report, answer, or accessible artifact reference; a claim that a report was written is not a deliverable. Do not leave the useful content only in private reasoning or text after task.complete. A report that the objective cannot be achieved is outcome=blocked/failed, not a successful result. If capabilities, credentials, inputs, or tools still required by executionBrief are unavailable and no human-authorized alternative can achieve the revised objective, call " +
			"task.fail with a durable code and explanation; do not merely return explanatory text. Do not " +
			"call task.respond, coordinate, or create child Issues."
	}
	if task.ParentTaskID == nil {
		return base + " You are the initial Team leader. Delegate suitable child work once, using the " +
			"member.agentId from team.get as assigneeRef (never the membership id). After every " +
			"issue.child.create succeeds, call task.complete immediately with a delegation summary; do not " +
			"wait inside this turn. A fresh leader follow-up will arrive with each worker result."
	}
	return base + " You are a Team leader follow-up caused by a worker outcome. Read the supplied task " +
		"inputs and coordinatorChildren.outcomes (including structured result and failure fields), not just comment summaries. Use executionBrief for the current child and coordinatorChildren.humanUpdates to apply the human's revised requirements when accepting resumed work. FIRST decide the CURRENT child: if its objective was achieved call issue.accept now; if evidence is missing, request concrete follow-up work with an explicit worker mention when that worker can supply it. After the mention succeeds call task.complete with outcome=waiting. Use run.node.fail only when the whole objective is unrecoverable and you intend to cancel remaining work, or explicitly request human action. Do not accept a report of inability as successful research. Only AFTER deciding the current child, inspect sibling statuses to synthesize or wait. This follow-up owns only its current Issue: issue.accept, " +
		"issue.cancel, and issue.comment.add act on that Issue, so never use them to decide or message a sibling. " +
		"Each sibling outcome gets its own follow-up. task.get also returns coordinatorChildren as read-only " +
		"synthesis context; use terminal sibling results when producing the final coordinator output. " +
		"Before declaring the whole objective complete, re-read coordinatorIssue.title and coordinatorIssue.description, " +
		"and check every requested deliverable against the actual returned content. Child acceptance alone does not " +
		"fulfill any remaining synthesis or writing requested by the user. Perform that remaining work now. " +
		"Put the actual final deliverables in run.node.complete.output, including the full requested text or accessible " +
		"artifact references. A sentence claiming that content was created is not the content itself. Text written " +
		"only after the completion tool is not delivered to the main Issue or Endpoint caller. " +
		"Call issue.accept only for satisfactory completed " +
		"work. For blocked or failed work, retry or reassign only when the new attempt changes the available " +
		"agent, capability, credential, input, or tool; a human relaxation of evidence or data-source requirements is changed input and can justify asking the worker to finish under that revised scope. Otherwise choose a degraded result, request human " +
		"action, cancel the blocked child, or fail the coordinator. To wait for human action, call " +
		"issue.comment.add with an explicit human mention using task.accountableHumanRef, then task.complete(outcome=succeeded) to finish only this decision turn. Do not use outcome=waiting for a human request without pending Agent work. To mark the entire objective blocked instead, call run.node.fail with the missing inputs and next action in its message. A comment alone does not change the root Issue status. Status and progress " +
		"comments schedule Agent work only through explicit mentions. Do not publish the same conclusion with issue.comment.add, " +
		"task.progress, and task.complete; use task.respond once for a final visible response and then call " +
		"task.complete, which reuses it. Do not use issue.child.create to bypass " +
		"an unresolved blocked Issue; use the explicit decision actions. Call run.node.complete only when the whole " +
		"coordinator has converged, and make its output synthesize every child outcome rather than only the " +
		"current input. If sibling work is still active, do not retry run.node.complete in a loop; " +
		"call task.complete with a waiting/decision summary so this follow-up ends and the next worker outcome " +
		"can wake a fresh follow-up. Waiting for your delegated worker is not blocked or failed: task.complete(outcome=waiting) ends only this turn, and preserves the worker. For every final coordinator decision, provide a user-facing summary of completed work, unfinished work, the reason for the final status, and the next action. The conclusion is published on the main Issue before changing its status. A successful run.node.complete already completes this task."
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
	var definition json.RawMessage
	if r.Tasks != nil && r.Tasks.ResolveDefinition != nil {
		definition, err = r.Tasks.ResolveDefinition(ctx, binding.AgentID)
		if err != nil {
			return nil, err
		}
	}
	requiresWorkspace := len(definition) > 0 && string(definition) != "null"
	instance, err := r.selectExternal(ctx, task, binding, candidate.RequiredCapabilities,
		candidate.SecurityConstraints, requiresWorkspace)
	if err != nil {
		return nil, err
	}
	sessionID := uuid.NewString()
	instanceID := instance.ID
	snapshot, _ := json.Marshal(controlmodel.RuntimeDispatchSnapshot{AgentServiceCommandID: serviceapi.ContextAgentServiceCommandID(ctx), Binding: binding, Definition: definition,
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
	required, security json.RawMessage, workspaceRequired ...bool) (*controlmodel.AgentInstance, error) {
	instances, err := r.Store.RuntimeRegistry().ListAgentInstances(ctx, task.Tenant, task.Namespace, binding.AgentID)
	if err != nil {
		return nil, err
	}
	for _, instance := range instances {
		if len(workspaceRequired) > 0 && workspaceRequired[0] && !controlmodel.ConsumesWorkspaceDefinition(instance.Capabilities) {
			continue
		}
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
	framework := ""
	if binding.Kind == controlmodel.DataPlaneManaged {
		framework = string(controlmodel.DataPlaneManaged)
	}
	_, err = r.Store.Sessions().Upsert(ctx, &store.Session{SessionID: sessionID,
		Tenant: task.Tenant, AgentID: binding.AgentID, BindingID: binding.BindingID,
		AgentInstanceID: agentInstanceID, InstanceGeneration: instanceGeneration,
		AgentName: agent.AgentKey, Namespace: task.Namespace, InstanceRef: instanceRef, Framework: framework,
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

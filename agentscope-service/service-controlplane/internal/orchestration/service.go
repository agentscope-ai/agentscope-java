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

package orchestration

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/collaboration"
	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/taskplane"
)

type Service struct {
	Store     store.Store
	CEL       *CEL
	TaskPlane *taskplane.Service
}

type StartRequest struct {
	RevisionID     *uuid.UUID          `json:"revisionId,omitempty"`
	IdempotencyKey string              `json:"idempotencyKey"`
	Input          json.RawMessage     `json:"input,omitempty"`
	IssueID        *uuid.UUID          `json:"issueId,omitempty"`
	Issue          *controlmodel.Issue `json:"issue,omitempty"`
	TriggerType    string              `json:"triggerType,omitempty"`
	TriggerRef     string              `json:"triggerRef,omitempty"`
	Actor          controlmodel.Actor  `json:"-"`
	RerunOfRunID   *uuid.UUID          `json:"-"`
}

type Graph struct {
	ChildRuns  []*controlmodel.OrchestrationRun      `json:"childRuns,omitempty"`
	Definition *controlmodel.OrchestrationDefinition `json:"definition,omitempty"`
	Revision   *controlmodel.OrchestrationRevision   `json:"revision,omitempty"`
	Run        *controlmodel.OrchestrationRun        `json:"run"`
	Nodes      []*controlmodel.RunNode               `json:"nodes"`
	Edges      []*controlmodel.RunEdge               `json:"edges"`
	Tasks      []*controlmodel.AgentTask             `json:"tasks,omitempty"`
	Attempts   []*controlmodel.ExecutionAttempt      `json:"attempts,omitempty"`
}

func (s *Service) evaluator() (*CEL, error) {
	if s.CEL != nil {
		return s.CEL, nil
	}
	return NewCEL()
}

var ErrInvalidDefinition = errors.New("invalid Workflow definition")

func (s *Service) ValidateDefinition(raw json.RawMessage) (*DefinitionSpec, error) {
	e, err := s.evaluator()
	if err != nil {
		return nil, err
	}
	spec, err := ParseAndValidateSpec(raw, e)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrInvalidDefinition, err)
	}
	return spec, nil
}

func (s *Service) Publish(ctx context.Context, definitionID uuid.UUID, actor controlmodel.Actor, expected ...int64) (*controlmodel.OrchestrationRevision, error) {
	d, err := s.Store.Orchestration().GetDefinition(ctx, definitionID)
	if err != nil {
		return nil, err
	}
	if d.ArchivedAt != nil {
		return nil, fmt.Errorf("%w: archived Workflow cannot be published", store.ErrConflict)
	}
	if len(expected) > 0 && expected[0] != 0 && d.Version != expected[0] {
		return nil, store.ErrConflict
	}
	spec, err := s.ValidateDefinition(d.DraftSpec)
	if err != nil {
		return nil, err
	}
	normalized, err := json.Marshal(spec)
	if err != nil {
		return nil, err
	}
	sum := sha256.Sum256(normalized)
	return s.Store.Orchestration().CreateRevision(ctx, &controlmodel.OrchestrationRevision{ExpectedDefinitionVersion: d.Version, DefinitionID: d.ID, Tenant: d.Tenant, Namespace: d.Namespace, Spec: normalized, Checksum: hex.EncodeToString(sum[:]), PublishedBy: actor})
}

func (s *Service) Start(ctx context.Context, definitionID uuid.UUID, req StartRequest) (*controlmodel.OrchestrationRun, error) {
	definition, err := s.startDefinition(ctx, definitionID, req.RevisionID)
	if err != nil {
		return nil, err
	}
	var result *controlmodel.OrchestrationRun
	key := fmt.Sprintf("workflow-start:%s:%s:%s", definition.Tenant, definition.Namespace, req.IdempotencyKey)
	err = s.Store.WithSessionLock(ctx, key, func(ctx context.Context) error {
		var startErr error
		result, startErr = s.start(ctx, definitionID, req)
		return startErr
	})
	return result, err
}
func (s *Service) start(ctx context.Context, definitionID uuid.UUID, req StartRequest) (*controlmodel.OrchestrationRun, error) {
	if req.IdempotencyKey == "" {
		return nil, fmt.Errorf("idempotencyKey is required")
	}
	if (req.IssueID == nil) == (req.Issue == nil) {
		return nil, fmt.Errorf("exactly one of issueId or issue is required")
	}
	definition, err := s.startDefinition(ctx, definitionID, req.RevisionID)
	if err != nil {
		return nil, err
	}
	if definition.ArchivedAt != nil {
		return nil, fmt.Errorf("%w: archived Workflow cannot start new runs", store.ErrConflict)
	}
	var revision *controlmodel.OrchestrationRevision
	if req.RevisionID != nil {
		revision, err = serviceapi.Revision(ctx, s.Store, uuid.Nil, *req.RevisionID)
	} else {
		var revisions []*controlmodel.OrchestrationRevision
		revisions, err = s.Store.Orchestration().ListRevisions(ctx, definitionID)
		if err == nil && len(revisions) > 0 {
			revision = revisions[0]
		} else if err == nil {
			err = fmt.Errorf("%w: definition has no published revision", store.ErrConflict)
		}
	}
	if err != nil {
		return nil, err
	}
	if revision.DefinitionID != definitionID {
		return nil, store.ErrConflict
	}
	_, err = s.ValidateDefinition(revision.Spec)
	if err != nil {
		return nil, err
	}
	var issue *controlmodel.Issue
	if req.IssueID != nil {
		issue, err = s.Store.Collaboration().GetIssue(ctx, *req.IssueID)
	} else {
		copy := *req.Issue
		copy.ID = uuid.NewSHA1(uuid.NameSpaceOID, []byte(fmt.Sprintf("workflow-issue:%s:%s:%s", definition.Tenant, definition.Namespace, req.IdempotencyKey)))
		copy.Tenant, copy.Namespace = definition.Tenant, definition.Namespace
		copy.AssigneeType, copy.AssigneeRef = "", ""
		copy.Creator = req.Actor
		issue, err = s.Store.Collaboration().GetIssue(ctx, copy.ID)
		if err == store.ErrNotFound {
			issue, err = s.Store.Collaboration().CreateIssue(ctx, &copy)
		}
	}
	if err != nil {
		return nil, err
	}
	if err := store.CheckIssueWorkAccess(ctx, s.Store.Collaboration(), issue.ID, true); err != nil {
		return nil, err
	}
	if issue.Tenant != definition.Tenant || issue.Namespace != definition.Namespace {
		return nil, store.ErrConflict
	}
	trigger := req.TriggerType
	if trigger == "" {
		trigger = "definition"
	}
	run, err := s.Store.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{Tenant: definition.Tenant, Namespace: definition.Namespace, RootIssueID: issue.ID, Mode: controlmodel.RunModeDeclared, DefinitionRevisionID: &revision.ID, RerunOfRunID: req.RerunOfRunID, TriggerType: trigger, TriggerRef: req.TriggerRef, IdempotencyKey: req.IdempotencyKey, Input: req.Input, Variables: json.RawMessage(`{}`), PolicySnapshot: json.RawMessage(`{}`), State: controlmodel.RunPlanned, CreatedBy: req.Actor})
	if err != nil {
		return nil, err
	}
	if run.DefinitionRevisionID == nil {
		return nil, store.ErrConflict
	}
	originalRevision, err := serviceapi.Revision(ctx, s.Store, run.RootIssueID, *run.DefinitionRevisionID)
	if err != nil {
		return nil, err
	}
	if originalRevision.DefinitionID != definitionID || run.RootIssueID != issue.ID {
		return nil, store.ErrConflict
	}
	engine := &Engine{Store: s.Store, CEL: s.CEL}
	if err = engine.ReconcileRun(ctx, run.ID); err != nil {
		return nil, err
	}
	return s.Store.Orchestration().GetRun(ctx, run.ID)
}

// Rerun starts a fresh declared Run pinned to the original immutable revision.
// It never reopens or mutates the terminal source Run.
func (s *Service) Rerun(ctx context.Context, runID uuid.UUID, idempotencyKey string,
	input json.RawMessage, actor controlmodel.Actor) (*controlmodel.OrchestrationRun, error) {
	source, err := s.Store.Orchestration().GetRun(ctx, runID)
	if err != nil {
		return nil, err
	}
	if !controlmodel.IsOrchestrationRunTerminal(source.State) {
		return nil, fmt.Errorf("only a terminal Run can be rerun")
	}
	if source.DefinitionRevisionID == nil {
		return nil, fmt.Errorf("direct/adaptive Runs are rerun through AgentTask retry")
	}
	revision, err := serviceapi.Revision(ctx, s.Store, source.RootIssueID, *source.DefinitionRevisionID)
	if err != nil {
		return nil, err
	}
	if len(input) == 0 {
		input = source.Input
	}
	return s.Start(ctx, revision.DefinitionID, StartRequest{RevisionID: &revision.ID,
		IdempotencyKey: idempotencyKey, Input: input, IssueID: &source.RootIssueID,
		TriggerType: "rerun", TriggerRef: source.ID.String(), Actor: actor, RerunOfRunID: &source.ID})
}

func (s *Service) Graph(ctx context.Context, runID uuid.UUID) (*Graph, error) {
	run, err := s.Store.Orchestration().GetRun(ctx, runID)
	if err != nil {
		return nil, err
	}
	nodes, err := s.Store.Orchestration().ListNodes(ctx, runID)
	if err != nil {
		return nil, err
	}
	edges, err := s.Store.Orchestration().ListEdges(ctx, runID)
	if err != nil {
		return nil, err
	}
	tasks, err := s.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{Tenant: run.Tenant, Namespace: run.Namespace, RunID: runID, Limit: 500})
	if err != nil {
		return nil, err
	}
	attempts := []*controlmodel.ExecutionAttempt{}
	for _, task := range tasks {
		list, _ := s.Store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{AgentTaskID: task.ID, Limit: 100})
		attempts = append(attempts, list...)
	}
	graph := &Graph{Run: run, Nodes: nodes, Edges: edges, Tasks: tasks, Attempts: attempts}
	for _, node := range nodes {
		if node.Type == controlmodel.RunNodeSubrun {
			children, err := s.Store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{Tenant: run.Tenant, Namespace: run.Namespace, ParentNodeID: node.ID, Limit: 100})
			if err != nil {
				return nil, err
			}
			graph.ChildRuns = append(graph.ChildRuns, children...)
		}
	}

	if run.DefinitionRevisionID != nil {
		graph.Revision, _ = serviceapi.Revision(ctx, s.Store, run.RootIssueID, *run.DefinitionRevisionID)
		if graph.Revision != nil {
			graph.Definition, _ = s.Store.Orchestration().GetDefinition(ctx, graph.Revision.DefinitionID)
		}
	}
	return graph, nil
}

func (s *Service) Pause(ctx context.Context, id uuid.UUID) (*controlmodel.OrchestrationRun, error) {
	var result *controlmodel.OrchestrationRun
	err := s.Store.WithSessionLock(ctx, "workflow-run:"+id.String(), func(ctx context.Context) error {
		run, err := s.Store.Orchestration().GetRun(ctx, id)
		if err != nil {
			return err
		}
		result, err = s.Store.Orchestration().TransitionRun(ctx, id, run.Version, controlmodel.RunPaused, nil, "", "")
		return err
	})
	return result, err
}
func (s *Service) Resume(ctx context.Context, id uuid.UUID) (*controlmodel.OrchestrationRun, error) {
	var result *controlmodel.OrchestrationRun
	err := s.Store.WithSessionLock(ctx, "workflow-run:"+id.String(), func(ctx context.Context) error {
		run, err := s.Store.Orchestration().GetRun(ctx, id)
		if err != nil {
			return err
		}
		if run.State != controlmodel.RunPaused {
			return store.ErrConflict
		}
		result, err = s.Store.Orchestration().TransitionRun(ctx, id, run.Version, controlmodel.RunRunning, nil, "", "")
		return err
	})
	if err == nil {
		err = (&Engine{Store: s.Store, CEL: s.CEL}).ReconcileRun(ctx, id)
		if err == nil {
			result, err = s.Store.Orchestration().GetRun(ctx, id)
		}
	}
	return result, err
}
func (s *Service) Cancel(ctx context.Context, id uuid.UUID) (*controlmodel.OrchestrationRun, error) {
	var result *controlmodel.OrchestrationRun
	err := s.Store.WithSessionLock(ctx, "workflow-run:"+id.String(), func(ctx context.Context) error { var err error; result, err = s.cancelRun(ctx, id); return err })
	return result, err
}
func (s *Service) cancelRun(ctx context.Context, id uuid.UUID) (*controlmodel.OrchestrationRun, error) {
	run, err := s.markCancelling(ctx, id)
	if err != nil || controlmodel.IsOrchestrationRunTerminal(run.State) {
		return run, err
	}
	return s.advanceCancellation(ctx, run)
}

func (s *Service) Signal(ctx context.Context, id uuid.UUID, name, key string, payload json.RawMessage, actor controlmodel.Actor) error {
	if name == "" || key == "" {
		return fmt.Errorf("signal name and idempotencyKey are required")
	}
	run, err := s.Store.Orchestration().GetRun(ctx, id)
	if err != nil {
		return err
	}
	contract, err := serviceapi.ContractForIssue(serviceapi.WithContract(ctx, nil), s.Store, run.RootIssueID)
	if err != nil {
		return err
	}
	if contract != nil && contract.PortalCommandID != "" {
		// Portal-owned signals need a targeted durable control proof. A legacy
		// broadcast would be ignored by that engine; never report it as accepted.
		return store.ErrForbidden
	}
	event, err := s.Store.Orchestration().AppendRunEvent(ctx, &controlmodel.RunEvent{RunID: id, Tenant: run.Tenant, Namespace: run.Namespace, Type: "run.signal." + name, Actor: actor, Payload: payload, IdempotencyKey: "signal:" + name + ":" + key})
	if err != nil {
		return err
	}
	_ = event
	return (&Engine{Store: s.Store, CEL: s.CEL}).ReconcileRun(ctx, id)
}

// ValidateCoordinatorNodeCompletion evaluates the Team barrier without changing
// state. The currently executing leader task is intentionally ignored; callers
// use this check before completing that task and then commit the node transition.
func (s *Service) ValidateCoordinatorNodeCompletion(ctx context.Context, taskID uuid.UUID) (*controlmodel.AgentTask, *controlmodel.RunNode, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, nil, err
	}
	if !task.LeaderTask {
		return nil, nil, fmt.Errorf("only a Team leader task can complete a coordinator node")
	}
	node, err := s.Store.Orchestration().GetNode(ctx, task.RunNodeID)
	if err != nil {
		return nil, nil, err
	}
	if node.Type != controlmodel.RunNodeTeam ||
		(node.State != controlmodel.RunNodeRunning && node.State != controlmodel.RunNodeWaiting) {
		return nil, nil, store.ErrConflict
	}
	tasks, err := store.ReadTasksBounded(ctx, s.Store.Collaboration(), store.AgentTaskFilter{Tenant: task.Tenant, Namespace: task.Namespace, RunID: task.OrchestrationRunID})
	if err != nil {
		return nil, nil, err
	}
	run, err := s.Store.Orchestration().GetRun(ctx, task.OrchestrationRunID)
	if err != nil {
		return nil, nil, err
	}
	nodes, err := s.Store.Orchestration().ListNodes(ctx, task.OrchestrationRunID)
	if err != nil {
		return nil, nil, err
	}
	coordinatorIssueID := task.IssueID
	if node.IssueID != nil {
		coordinatorIssueID = *node.IssueID
	}
	children, err := store.ReadChildIssuesBounded(ctx, s.Store.Collaboration(), store.IssueFilter{Tenant: task.Tenant, Namespace: task.Namespace, ParentID: &coordinatorIssueID})
	if err != nil {
		return nil, nil, err
	}
	if err = store.ValidateCoordinatorBarrier(run, node, task, tasks, nodes, children, true); err != nil {
		return nil, nil, err
	}
	return task, node, nil
}

func (s *Service) CompleteCoordinatorNode(ctx context.Context, taskID uuid.UUID, output json.RawMessage, actor controlmodel.Actor) (*controlmodel.RunNode, error) {
	completed, err := s.completeCoordinatorNode(ctx, taskID, output, actor)
	if err != nil {
		return nil, err
	}
	if err = (&Engine{Store: s.Store, CEL: s.CEL}).ReconcileRun(ctx, completed.RunID); err != nil {
		return nil, err
	}
	return completed, nil
}

func (s *Service) completeCoordinatorNode(ctx context.Context, taskID uuid.UUID, output json.RawMessage, actor controlmodel.Actor) (*controlmodel.RunNode, error) {
	current, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	existing, err := s.Store.Orchestration().GetNode(ctx, current.RunNodeID)
	if err != nil {
		return nil, err
	}
	if existing.State == controlmodel.RunNodeSucceeded && existing.Type == controlmodel.RunNodeTeam && current.LeaderTask && current.Status == controlmodel.AgentTaskCompleted {
		intent, readErr := s.coordinatorCompletionIntent(ctx, current)
		if readErr != nil {
			return nil, readErr
		}
		expected, e := canonicalCoordinatorOutput(intent.Output)
		if e != nil {
			return nil, e
		}
		requested, e := canonicalCoordinatorOutput(output)
		if e != nil {
			return nil, e
		}
		committed, e := canonicalCoordinatorOutput(existing.Output)
		if e != nil {
			return nil, e
		}
		if string(expected) != string(requested) || string(committed) != string(requested) {
			return nil, store.ErrConflict
		}
		return existing, nil
	}

	task, node, err := s.ValidateCoordinatorNodeCompletion(ctx, taskID)
	if err != nil {
		return nil, err
	}
	// A Run must never become successful while the leader obligation that made
	// the decision is still active. Public adapters complete the task first and
	// invoke this transition in the same request; a failure between the two
	// leaves a recoverable waiting coordinator instead of a false-success Run.
	if task.Status != controlmodel.AgentTaskCompleted || node.State != controlmodel.RunNodeWaiting {
		return nil, fmt.Errorf("coordinator leader task must be completed before its node")
	}
	// Parallel worker results may create more than one leader follow-up for the
	// same coordinator. They are redundant continuations, not worker barriers.
	// Retire them before exposing the successful node so the Run cannot finish
	// with a live leader task or Attempt left behind.
	tasks, err := store.ReadTasksBounded(ctx, s.Store.Collaboration(), store.AgentTaskFilter{
		Tenant: task.Tenant, Namespace: task.Namespace, RunID: task.OrchestrationRunID,
	})
	if err != nil {
		return nil, err
	}
	taskPlane := s.TaskPlane
	if taskPlane == nil {
		taskPlane = &taskplane.Service{Store: s.Store}
	}
	for _, candidate := range tasks {
		if candidate.ID == task.ID || candidate.RunNodeID != node.ID || !candidate.LeaderTask ||
			controlmodel.IsAgentTaskTerminal(candidate.Status) {
			continue
		}
		if len(candidate.Inputs) > 0 {
			return nil, fmt.Errorf("%w: coordinator has pending leader outcome task %s", store.ErrConflict, candidate.ID)
		}
		if _, cancelErr := taskPlane.CancelTask(ctx, candidate.ID, candidate.Version); cancelErr != nil && cancelErr != store.ErrConflict {
			return nil, cancelErr
		}
	}
	run, err := s.Store.Orchestration().GetRun(ctx, task.OrchestrationRunID)
	if err != nil {
		return nil, err
	}
	if err = s.RequestCoordinatorCompletion(ctx, task, output, actor); err != nil {
		return nil, err
	}
	completed, err := s.Store.Orchestration().FinalizeCoordinatorNode(ctx, task.ID, node.Version, output, actor)
	if err != nil {
		return nil, err
	}
	// Publication follows the durable barrier. A failed publication is recovered
	// by terminal Run reconciliation using its stable summary identity.
	if err = (&collaboration.Service{Store: s.Store}).PublishCoordinatorSummary(ctx, run, task, output, "", ""); err != nil {
		return nil, err
	}
	return completed, nil
}

func (s *Service) FailCoordinatorNode(ctx context.Context, taskID uuid.UUID, code, message string, actor controlmodel.Actor) (*controlmodel.RunNode, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	if !task.LeaderTask {
		return nil, fmt.Errorf("only a Team leader task can fail a coordinator node")
	}
	if task.Status != controlmodel.AgentTaskFailed {
		return nil, fmt.Errorf("coordinator leader task must be failed before its node")
	}
	node, err := s.Store.Orchestration().GetNode(ctx, task.RunNodeID)
	if err != nil {
		return nil, err
	}
	failed, err := s.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeFailed, nil, code, message)
	if err != nil {
		return nil, err
	}
	_, _ = s.Store.Orchestration().AppendRunEvent(ctx, &controlmodel.RunEvent{RunID: task.OrchestrationRunID,
		Tenant: task.Tenant, Namespace: task.Namespace, NodeID: &node.ID, AgentTaskID: &task.ID,
		Type: "node.failed", Actor: actor, IdempotencyKey: "coordinator-fail:" + node.ID.String()})
	if err = (&Engine{Store: s.Store, CEL: s.CEL}).ReconcileRun(ctx, task.OrchestrationRunID); err != nil {
		return nil, err
	}
	return failed, nil
}

func (s *Service) Replan(ctx context.Context, taskID uuid.UUID, definition DefinitionNode, actor controlmodel.Actor) (*controlmodel.RunNode, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	if !task.LeaderTask || (definition.Type != controlmodel.RunNodeAgent && definition.Type != controlmodel.RunNodeTeam) {
		return nil, fmt.Errorf("replan requires a Team leader and an agent or team node")
	}
	run, err := s.Store.Orchestration().GetRun(ctx, task.OrchestrationRunID)
	if err != nil {
		return nil, err
	}
	if run.Mode != controlmodel.RunModeAdaptive || controlmodel.IsOrchestrationRunTerminal(run.State) || run.State == controlmodel.RunCancelling {
		return nil, fmt.Errorf("replan is only available in a non-terminal adaptive Run")
	}
	if definition.Type == controlmodel.RunNodeAgent {
		if _, err := uuid.Parse(definition.AgentID); err != nil {
			return nil, fmt.Errorf("dynamic agent node requires a valid agentId")
		}
	}
	if definition.Type == controlmodel.RunNodeTeam && definition.TeamRef == "" {
		return nil, fmt.Errorf("dynamic team node requires teamRef")
	}
	if definition.Key == "" {
		definition.Key = "dynamic-" + uuid.NewString()
	}
	if _, err = (&collaboration.Service{Store: s.Store}).ReopenBlockedIssueFromTask(ctx, task.ID,
		"Team leader replanned blocked delegated work"); err != nil {
		return nil, err
	}
	config, _ := json.Marshal(definition)
	node, err := s.Store.Orchestration().CreateNode(ctx, &controlmodel.RunNode{RunID: task.OrchestrationRunID,
		Tenant: task.Tenant, Namespace: task.Namespace, NodeKey: definition.Key, Type: definition.Type,
		Role: definition.Role, IssueID: &task.IssueID, State: controlmodel.RunNodeReady,
		Config: config, Iteration: 1})
	if err != nil {
		return nil, err
	}
	_, _ = s.Store.Orchestration().AppendRunEvent(ctx, &controlmodel.RunEvent{RunID: task.OrchestrationRunID,
		Tenant: task.Tenant, Namespace: task.Namespace, NodeID: &node.ID, Type: "run.replanned",
		Actor: actor, Payload: config, IdempotencyKey: "replan:" + node.ID.String()})
	if err = (&Engine{Store: s.Store, CEL: s.CEL}).ReconcileRun(ctx, task.OrchestrationRunID); err != nil {
		return nil, err
	}
	return node, nil
}

var _ = time.Second

// Portal permissions govern whether the frozen revision can run. Runtime
// materialization does not reread a mutable control-plane definition.
func (s *Service) startDefinition(ctx context.Context, id uuid.UUID, revisionID *uuid.UUID) (*controlmodel.OrchestrationDefinition, error) {
	if c := serviceapi.ContextContract(ctx); c != nil && c.PortalCommandID != "" {
		if revisionID == nil {
			return nil, store.ErrForbidden
		}
		revision := c.Revisions[revisionID.String()]
		if revision == nil || revision.DefinitionID != id || revision.Tenant != c.Endpoint.Tenant || revision.Namespace != c.Endpoint.Namespace {
			return nil, store.ErrForbidden
		}
		return &controlmodel.OrchestrationDefinition{ID: id, Tenant: revision.Tenant, Namespace: revision.Namespace}, nil
	}
	return s.Store.Orchestration().GetDefinition(ctx, id)
}

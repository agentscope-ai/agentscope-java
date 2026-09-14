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
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type Engine struct {
	Store store.Store
	CEL   *CEL
}

func (e *Engine) evaluator() (*CEL, error) {
	if e.CEL != nil {
		return e.CEL, nil
	}
	return NewCEL()
}

func (e *Engine) ReconcileRun(ctx context.Context, runID uuid.UUID) error {
	if e == nil || e.Store == nil {
		return fmt.Errorf("orchestration engine store is required")
	}
	return e.Store.WithSessionLock(ctx, "workflow-run:"+runID.String(), func(ctx context.Context) error { return e.reconcileRun(ctx, runID) })
}
func (e *Engine) reconcileRun(ctx context.Context, runID uuid.UUID) error {
	if e == nil || e.Store == nil {
		return fmt.Errorf("orchestration engine store is required")
	}
	evaluator, err := e.evaluator()
	if err != nil {
		return err
	}
	for iteration := 0; iteration < 128; iteration++ {
		run, err := e.Store.Orchestration().GetRun(ctx, runID)
		if err != nil {
			return err
		}
		if run.State == controlmodel.RunPlanned && run.DefinitionRevisionID != nil {
			if err := e.materialize(ctx, run); err != nil {
				return err
			}
			continue
		}
		if controlmodel.IsOrchestrationRunTerminal(run.State) {
			if err = (&collaboration.Service{Store: e.Store}).EnsureTerminalTeamSummary(ctx, run); err != nil {
				return err
			}
			if run.State == controlmodel.RunFailed {
				return e.convergeFailedIssueTree(ctx, run)
			}
			return e.convergeCompletedIssue(ctx, run)
		}
		if run.State == controlmodel.RunCancelling {
			_, err := (&Service{Store: e.Store}).cancelRun(ctx, run.ID)
			return err
		}
		if run.State == controlmodel.RunPaused {
			return nil
		}
		nodes, err := e.Store.Orchestration().ListNodes(ctx, runID)
		if err != nil {
			return err
		}
		edges, err := e.Store.Orchestration().ListEdges(ctx, runID)
		if err != nil {
			return err
		}
		issue, err := e.Store.Collaboration().GetIssue(ctx, run.RootIssueID)
		if err != nil {
			return err
		}
		for _, failedNode := range nodes {
			if failedNode.State != controlmodel.RunNodeFailed {
				continue
			}
			var cfg DefinitionNode
			_ = json.Unmarshal(failedNode.Config, &cfg)
			policy := cfg.FailurePolicy
			if policy == "" {
				policy = "fail_fast"
				// A delegated worker failure is a valid adaptive-Team outcome.
				// Keep the coordinator alive so its leader can reason about the
				// durable blocked result instead of cancelling the whole Run.
				if e.isAdaptiveTeamWorkerNode(ctx, run, failedNode) {
					policy = "continue"
				}
			}
			if policy != "fail_fast" {
				continue
			}
			for _, candidate := range nodes {
				if candidate.ID != failedNode.ID && !controlmodel.IsRunNodeTerminal(candidate.State) {
					if err := (&Service{Store: e.Store}).stopNodeWork(ctx, run, candidate); err != nil {
						return err
					}
					_, _ = e.Store.Orchestration().TransitionNode(ctx, candidate.ID, candidate.Version, controlmodel.RunNodeCancelled, nil, "fail_fast", "cancelled after node failure")
				}
			}
			run, _ = e.Store.Orchestration().GetRun(ctx, runID)
			failedRun, transitionErr := e.Store.Orchestration().TransitionRun(ctx, runID, run.Version,
				controlmodel.RunFailed, nil, failedNode.FailureCode, failedNode.FailureMessage)
			if transitionErr != nil {
				return transitionErr
			}
			return e.convergeFailedIssueTree(ctx, failedRun)
		}
		vars := buildCELVars(run, issue, nodes)
		changed := false
		byID := map[uuid.UUID]*controlmodel.RunNode{}
		for _, n := range nodes {
			byID[n.ID] = n
		}
		for _, node := range nodes {
			if node.State != controlmodel.RunNodePending {
				continue
			}
			incoming := []*controlmodel.RunEdge{}
			for _, edge := range edges {
				if edge.ToNodeID == node.ID {
					incoming = append(incoming, edge)
				}
			}
			if len(incoming) == 0 {
				if _, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeReady, nil, "", ""); err != nil {
					return err
				}
				changed = true
				continue
			}
			allTerminal, selectedCount, terminalCount := true, 0, 0
			for _, edge := range incoming {
				source := byID[edge.FromNodeID]
				if source == nil {
					allTerminal = false
					continue
				}
				if !controlmodel.IsRunNodeTerminal(source.State) {
					allTerminal = false
					continue
				}
				terminalCount++
				matches := false
				for _, state := range edge.OnStates {
					if source.State == state {
						matches = true
						break
					}
				}
				if !matches {
					continue
				}
				if edge.Condition != "" {
					value, evalErr := evaluator.Eval(ctx, edge.Condition, vars)
					if evalErr != nil {
						return e.failNode(ctx, node, evalErr)
					}
					truth, ok := value.(bool)
					if !ok || !truth {
						continue
					}
				}
				selectedCount++
			}
			ready, impossible := false, false
			if node.Type == controlmodel.RunNodeJoin {
				var cfg DefinitionNode
				if err = json.Unmarshal(node.Config, &cfg); err != nil {
					return e.failNode(ctx, node, err)
				}
				mode := cfg.Join.Mode
				if mode == "" {
					mode = "all"
				}
				required := len(incoming)
				if mode == "any" {
					required = 1
				} else if mode == "quorum" {
					required = int(cfg.Join.Quorum)
				}
				ready = selectedCount >= required
				impossible = selectedCount+(len(incoming)-terminalCount) < required
				if !ready && !impossible {
					continue
				}
			} else {
				if !allTerminal {
					continue
				}
				ready = selectedCount > 0
			}
			target := controlmodel.RunNodeSkipped
			if ready {
				target = controlmodel.RunNodeReady
			}
			if _, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, target, nil, "", ""); err != nil {
				return err
			}
			changed = true
		}
		if changed {
			continue
		}
		for _, node := range nodes {
			if node.State != controlmodel.RunNodeReady {
				continue
			}
			var cfg DefinitionNode
			if len(node.Config) == 0 && (node.Type == controlmodel.RunNodeAgent || node.Type == controlmodel.RunNodeTeam) {
				// Direct/adaptive Runs are materialized by Issue routing together
				// with their initial Task. They intentionally have no published
				// Definition config; the engine only owns their node lifecycle.
				existing, listErr := e.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{
					Tenant: run.Tenant, Namespace: run.Namespace, NodeID: node.ID, Limit: 10,
				})
				if listErr != nil {
					return listErr
				}
				if len(existing) == 0 {
					return e.failNode(ctx, node, fmt.Errorf("dynamic node has no AgentTask"))
				}
				waitReason := "agent_task"
				if node.Type == controlmodel.RunNodeTeam {
					waitReason = "team_coordinator"
				}
				if _, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version,
					controlmodel.RunNodeWaiting, nil, waitReason, ""); err != nil {
					return err
				}
				changed = true
				continue
			}
			if err = json.Unmarshal(node.Config, &cfg); err != nil {
				return e.failNode(ctx, node, err)
			}
			if cfg.Condition != "" {
				value, evalErr := evaluator.Eval(ctx, cfg.Condition, vars)
				if evalErr != nil {
					return e.failNode(ctx, node, evalErr)
				}
				truth, ok := value.(bool)
				if !ok {
					return e.failNode(ctx, node, fmt.Errorf("node condition must return bool"))
				}
				if !truth {
					_, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeSkipped, nil, "", "")
					if err != nil {
						return err
					}
					changed = true
					continue
				}
			}
			input := map[string]any{}
			for name, expr := range cfg.Input {
				value, evalErr := evaluator.Eval(ctx, expr, vars)
				if evalErr != nil {
					return e.failNode(ctx, node, evalErr)
				}
				input[name] = value
			}
			inputJSON, _ := json.Marshal(input)
			if !bytes.Equal(node.Input, inputJSON) {
				node, err = e.Store.Orchestration().SetNodeInput(ctx, node.ID, node.Version, inputJSON)
				if err != nil {
					return err
				}
			}
			switch node.Type {
			case controlmodel.RunNodeCondition:
				_, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeRunning, inputJSON, "", "")
				if err == nil {
					node, _ = e.Store.Orchestration().GetNode(ctx, node.ID)
					_, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeSucceeded, inputJSON, "", "")
				}
			case controlmodel.RunNodeAgent:
				existing, listErr := e.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{Tenant: run.Tenant, Namespace: run.Namespace, NodeID: node.ID, Limit: 10})
				if listErr != nil {
					err = listErr
				} else if len(existing) == 0 {
					if cfg.AgentID == "" {
						return e.failNode(ctx, node, fmt.Errorf("agent node requires agentId"))
					}
					_, err = e.Store.Collaboration().CreateRunAgentTask(ctx, store.RunTaskRequest{RunID: run.ID, NodeID: node.ID, IssueID: *node.IssueID, AgentRef: cfg.AgentID, TeamRole: cfg.Role, RuntimeCandidate: cfg.RuntimeCandidate, Originator: run.CreatedBy})
				}
				if err == nil {
					_, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeWaiting, inputJSON, "agent_task", "")
				}
			case controlmodel.RunNodeTeam:
				existing, listErr := e.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{Tenant: run.Tenant, Namespace: run.Namespace, NodeID: node.ID, Limit: 10})
				if listErr != nil {
					err = listErr
					break
				}
				if len(existing) > 0 {
					_, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeWaiting, inputJSON, "team_coordinator", "")
					break
				}
				teamID, parseErr := uuid.Parse(cfg.TeamRef)
				if parseErr != nil {
					return e.failNode(ctx, node, fmt.Errorf("teamRef must be a team UUID: %w", parseErr))
				}
				team, loadErr := e.Store.Collaboration().GetTeam(ctx, teamID)
				if loadErr != nil {
					return e.failNode(ctx, node, loadErr)
				}
				if team.Status != controlmodel.TeamActive {
					return e.failNode(ctx, node, fmt.Errorf("Team %s is not active", team.ID))
				}
				_, _, err = MaterializeTeamCoordinator(ctx, e.Store, MaterializeTeamRequest{
					Run: run, IssueID: *node.IssueID, Team: team, NodeID: node.ID, NodeKey: node.NodeKey,
					RuntimeCandidate: cfg.RuntimeCandidate, Actor: run.CreatedBy,
				})
				if err == nil {
					_, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeWaiting, inputJSON, "team_coordinator", "")
				}
			case controlmodel.RunNodeApproval:
				approver := cfg.Approval.ApproverRef
				if approver == "" {
					return e.failNode(ctx, node, fmt.Errorf("approval node requires approverRef"))
				}
				_, err = e.Store.Collaboration().CreateApproval(ctx, &controlmodel.Approval{Tenant: run.Tenant, Namespace: run.Namespace, TargetType: "run-node", TargetRef: node.ID.String(), IssueID: node.IssueID, RunID: &run.ID, RunNodeID: &node.ID, RequestedBy: run.CreatedBy, ApproverRef: approver, Status: controlmodel.ApprovalPending, Reason: cfg.Approval.Prompt, Request: inputJSON})
				if err == nil {
					_, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeWaiting, nil, "approval", "")
				}
			case controlmodel.RunNodeTimer:
				wake := cfg.Timer.At
				if wake == nil {
					value := time.Now().UTC().Add(time.Duration(cfg.Timer.DurationSeconds) * time.Second)
					wake = &value
				}
				payload, _ := json.Marshal(map[string]any{"wakeAt": wake})
				_, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeWaiting, payload, "timer", "")
			case controlmodel.RunNodeSignal:
				if cfg.SignalName == "" {
					return e.failNode(ctx, node, fmt.Errorf("signal node requires signalName"))
				}
				_, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeWaiting, nil, "signal:"+cfg.SignalName, "")
			case controlmodel.RunNodeJoin:
				_, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeRunning, nil, "", "")
				if err == nil {
					node, _ = e.Store.Orchestration().GetNode(ctx, node.ID)
					_, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeSucceeded, inputJSON, "", "")
				}
			case controlmodel.RunNodeSubrun:
				revisionID, parseErr := uuid.Parse(cfg.DefinitionRevID)
				if parseErr != nil {
					return e.failNode(ctx, node, fmt.Errorf("subrun requires definitionRevisionId"))
				}
				revision, loadErr := e.Store.Orchestration().GetRevision(ctx, revisionID)
				if loadErr != nil {
					return e.failNode(ctx, node, loadErr)
				}
				if revision.Tenant != run.Tenant || revision.Namespace != run.Namespace {
					return e.failNode(ctx, node, fmt.Errorf("subrun revision is outside the Run namespace"))
				}
				sub, createErr := e.createSubrun(ctx, run, node, revision, inputJSON)
				if createErr != nil {
					return e.failNode(ctx, node, createErr)
				}
				payload, _ := json.Marshal(map[string]any{"subrunId": sub.ID})
				_, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeWaiting, payload, "subrun", "")
			default:
				err = fmt.Errorf("unsupported node type %q", node.Type)
			}
			if err != nil {
				return err
			}
			changed = true
		}
		if changed {
			continue
		}
		var swept bool
		if swept, err = e.sweepWaiting(ctx, run, nodes); err != nil {
			return err
		}
		if swept {
			continue
		}
		latest, _ := e.Store.Orchestration().ListNodes(ctx, runID)
		active, waiting, runnable, success, failed := 0, 0, 0, 0, 0
		partialAllowed := false
		coordinatorFailed := false
		for _, n := range latest {
			if !controlmodel.IsRunNodeTerminal(n.State) {
				active++
			}
			if n.State == controlmodel.RunNodeWaiting {
				waiting++
			}
			if n.State == controlmodel.RunNodeReady || n.State == controlmodel.RunNodeRunning {
				runnable++
			}
			if n.State == controlmodel.RunNodeSucceeded {
				success++
			}
			if n.State == controlmodel.RunNodeFailed {
				failed++
				if n.Type == controlmodel.RunNodeTeam {
					coordinatorFailed = true
				}
				var cfg DefinitionNode
				_ = json.Unmarshal(n.Config, &cfg)
				if cfg.FailurePolicy == "partial_success" || e.isAdaptiveTeamWorkerNode(ctx, run, n) {
					partialAllowed = true
				}
			}
		}
		if active == 0 {
			run, _ = e.Store.Orchestration().GetRun(ctx, runID)
			target := controlmodel.RunSucceeded
			if failed > 0 && success > 0 && partialAllowed && !coordinatorFailed {
				target = controlmodel.RunPartialSucceeded
			} else if failed > 0 {
				target = controlmodel.RunFailed
			}
			run, err = e.Store.Orchestration().TransitionRun(ctx, runID, run.Version, target, CompletedRunOutput(run, latest), "", "")
			if err != nil {
				return err
			}
			if target == controlmodel.RunFailed {
				return e.convergeFailedIssueTree(ctx, run)
			}
			return e.convergeCompletedIssue(ctx, run)
		}
		run, _ = e.Store.Orchestration().GetRun(ctx, runID)
		if waiting > 0 && runnable == 0 && run.State == controlmodel.RunRunning {
			_, err = e.Store.Orchestration().TransitionRun(ctx, runID, run.Version, controlmodel.RunWaiting, nil, "node_wait", "")
			return err
		}
		if runnable > 0 && run.State == controlmodel.RunWaiting {
			_, err = e.Store.Orchestration().TransitionRun(ctx, runID, run.Version, controlmodel.RunRunning, nil, "", "")
			return err
		}
		return nil
	}
	return fmt.Errorf("orchestration reconciliation exceeded iteration limit")
}

// convergeCompletedIssue keeps human Work acceptance separate from physical
// execution. Human-facing Work requests review, while an operational
// Endpoint Job with an automatic completion policy closes with its Run.
// Subruns never advance their shared root Issue ahead of the parent Run.
func (e *Engine) convergeCompletedIssue(ctx context.Context, run *controlmodel.OrchestrationRun) error {
	if run == nil || run.TriggerType == controlmodel.AgentTaskReviewComment || run.ParentRunID != nil ||
		(run.State != controlmodel.RunSucceeded && run.State != controlmodel.RunPartialSucceeded) {
		return nil
	}
	issue, err := e.Store.Collaboration().GetIssue(ctx, run.RootIssueID)
	if err != nil {
		return err
	}
	if run.Mode == controlmodel.RunModeDirect && run.TriggerType == "comment" {
		ownsLifecycle := false
		for offset := 0; ; offset += 500 {
			tasks, listErr := e.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{
				Tenant: run.Tenant, Namespace: run.Namespace, RunID: run.ID, Limit: 500, Offset: offset,
			})
			if listErr != nil {
				return listErr
			}
			for _, task := range tasks {
				if store.AgentTaskOwnsIssueLifecycle(issue, task) {
					ownsLifecycle = true
					break
				}
			}
			if ownsLifecycle || len(tasks) < 500 {
				break
			}
		}
		if !ownsLifecycle {
			return nil
		}
	}
	if err = (&collaboration.Service{Store: e.Store}).EnsureTerminalTeamSummary(ctx, run); err != nil {
		return err
	}
	actor := controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "orchestration-run:" + run.ID.String()}
	for attempt := 0; attempt < 3; attempt++ {
		issue, err = e.Store.Collaboration().GetIssue(ctx, run.RootIssueID)
		if err != nil {
			return err
		}
		if issue.Status == controlmodel.IssueBlocked {
			resume, resumeErr := e.completedTeamCanResumeRoot(ctx, run, issue)
			if resumeErr != nil {
				return resumeErr
			}
			if !resume {
				return nil
			}
			if _, err = e.Store.Collaboration().TransitionIssue(ctx, issue.ID, issue.Version,
				controlmodel.IssueInProgress, actor, "Team completed after human input; all child decisions resolved"); err != nil && err != store.ErrConflict {
				return err
			}
			continue
		}
		if issue.Status != controlmodel.IssueInProgress {
			return nil
		}
		target, reason := controlmodel.IssueInReview, "execution completed; awaiting acceptance"
		if issue.CompletionPolicy == controlmodel.IssueCompletionAutomatic {
			target, reason = controlmodel.IssueDone, "automatic Endpoint Job execution completed"
		}
		if _, err = e.Store.Collaboration().TransitionIssue(ctx, issue.ID, issue.Version,
			target, actor, reason); err == nil {
			return nil
		} else if err != store.ErrConflict {
			return err
		}
	}
	return store.ErrConflict
}

// A Team may keep its Run waiting while its root is blocked on human input.
// Once its coordinator and child decisions finish, resume the root lifecycle.
// Historical completion must never advance a newer Run's blocked root.
func (e *Engine) completedTeamCanResumeRoot(ctx context.Context, run *controlmodel.OrchestrationRun, issue *controlmodel.Issue) (bool, error) {
	if run.Mode != controlmodel.RunModeAdaptive || issue.AssigneeType != controlmodel.AssigneeTeam || issue.ArchivedAt != nil {
		return false, nil
	}
	latest, err := e.Store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{Tenant: run.Tenant, Namespace: run.Namespace, RootIssueID: run.RootIssueID, Limit: 1})
	if err != nil || len(latest) == 0 || latest[0].ID != run.ID {
		return false, err
	}
	nodes, err := e.Store.Orchestration().ListNodes(ctx, run.ID)
	if err != nil {
		return false, err
	}
	completedCoordinator := false
	for _, node := range nodes {
		if node.Type == controlmodel.RunNodeTeam && node.IssueID != nil && *node.IssueID == issue.ID && node.State == controlmodel.RunNodeSucceeded {
			completedCoordinator = true
		}
	}
	if !completedCoordinator {
		return false, nil
	}
	for offset := 0; ; offset += 500 {
		children, listErr := e.Store.Collaboration().ListIssues(ctx, store.IssueFilter{Tenant: issue.Tenant, Namespace: issue.Namespace, ParentID: &issue.ID, Limit: 500, Offset: offset})
		if listErr != nil {
			return false, listErr
		}
		for _, child := range children {
			if child.Status != controlmodel.IssueDone && child.Status != controlmodel.IssueCancelled {
				return false, nil
			}
		}
		if len(children) < 500 {
			return true, nil
		}
	}
}

// convergeFailedIssueTree prevents a terminal Run from leaving human-facing
// work permanently in_progress. A failed execution is blocked (recoverable),
// not silently cancelled or accepted; a human or a later leader can still
// reopen the Issue after fixing the missing capability or configuration.
func (e *Engine) convergeFailedIssueTree(ctx context.Context, run *controlmodel.OrchestrationRun) error {
	if run == nil || run.TriggerType == controlmodel.AgentTaskReviewComment || run.ParentRunID != nil || run.State != controlmodel.RunFailed {
		return nil
	}
	latest, err := e.Store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{Tenant: run.Tenant, Namespace: run.Namespace, RootIssueID: run.RootIssueID, Limit: 1})
	if err != nil {
		return err
	}
	// Replaying an old failure must not block a newer recovery run.
	if len(latest) > 0 && latest[0].ID != run.ID && latest[0].CreatedAt.After(run.CreatedAt) {
		return nil
	}
	root, err := e.Store.Collaboration().GetIssue(ctx, run.RootIssueID)
	if err != nil {
		return err
	}
	if err = (&collaboration.Service{Store: e.Store}).EnsureTerminalTeamSummary(ctx, run); err != nil {
		return err
	}
	actor := controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "orchestration-run:" + run.ID.String()}
	queue := []*controlmodel.Issue{root}
	for len(queue) > 0 {
		issue := queue[0]
		queue = queue[1:]
		for offset := 0; ; offset += 500 {
			children, listErr := e.Store.Collaboration().ListIssues(ctx, store.IssueFilter{
				Tenant: issue.Tenant, Namespace: issue.Namespace, ParentID: &issue.ID, Limit: 500, Offset: offset,
			})
			if listErr != nil {
				return listErr
			}
			queue = append(queue, children...)
			if len(children) < 500 {
				break
			}
		}
		if err = e.blockIssueAfterRunFailure(ctx, issue.ID, actor, run.FailureCode); err != nil {
			return err
		}
	}
	return nil
}

func (e *Engine) blockIssueAfterRunFailure(ctx context.Context, issueID uuid.UUID,
	actor controlmodel.Actor, failureCode string) error {
	for attempt := 0; attempt < 4; attempt++ {
		issue, err := e.Store.Collaboration().GetIssue(ctx, issueID)
		if err != nil {
			return err
		}
		switch issue.Status {
		case controlmodel.IssueBlocked, controlmodel.IssueDone, controlmodel.IssueCancelled:
			return nil
		case controlmodel.IssueBacklog:
			if _, err = e.Store.Collaboration().TransitionIssue(ctx, issue.ID, issue.Version,
				controlmodel.IssueTodo, actor, "execution failed before work started"); err != nil && err != store.ErrConflict {
				return err
			}
			continue
		case controlmodel.IssueTodo, controlmodel.IssueInProgress, controlmodel.IssueInReview:
			reason := "orchestration run failed"
			if strings.TrimSpace(failureCode) != "" {
				reason += ": " + failureCode
			}
			if _, err = e.Store.Collaboration().TransitionIssue(ctx, issue.ID, issue.Version,
				controlmodel.IssueBlocked, actor, reason); err == nil {
				return nil
			} else if err != store.ErrConflict {
				return err
			}
		default:
			return nil
		}
	}
	return store.ErrConflict
}

func buildCELVars(run *controlmodel.OrchestrationRun, issue *controlmodel.Issue, nodes []*controlmodel.RunNode) map[string]any {
	var input, variables any
	_ = json.Unmarshal(run.Input, &input)
	_ = json.Unmarshal(run.Variables, &variables)
	nodeMap := map[string]any{}
	for _, n := range nodes {
		var output any
		_ = json.Unmarshal(n.Output, &output)
		nodeMap[n.NodeKey] = map[string]any{"status": string(n.State), "output": output, "artifacts": []any{}}
	}
	issueJSON, _ := json.Marshal(issue)
	var issueValue any
	_ = json.Unmarshal(issueJSON, &issueValue)
	return map[string]any{"run": map[string]any{"input": input, "variables": variables}, "issue": issueValue, "trigger": map[string]any{"type": run.TriggerType, "ref": run.TriggerRef}, "nodes": nodeMap}
}

func (e *Engine) failNode(ctx context.Context, node *controlmodel.RunNode, cause error) error {
	_, err := e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeFailed, nil, "node_error", cause.Error())
	if err != nil {
		return err
	}
	return nil
}

func (e *Engine) sweepWaiting(ctx context.Context, run *controlmodel.OrchestrationRun, nodes []*controlmodel.RunNode) (bool, error) {
	now := time.Now().UTC()
	changed := false
	for _, node := range nodes {
		if node.State != controlmodel.RunNodeWaiting {
			continue
		}
		var cfg DefinitionNode
		_ = json.Unmarshal(node.Config, &cfg)
		if cfg.TimeoutSeconds > 0 && node.StartedAt != nil && now.After(node.StartedAt.Add(time.Duration(cfg.TimeoutSeconds)*time.Second)) {
			if err := (&Service{Store: e.Store}).stopNodeWork(ctx, run, node); err != nil {
				return changed, err
			}
			if _, err := e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeFailed, nil, "timeout", "node execution timed out"); err != nil {
				return changed, err
			}
			changed = true
			continue
		}
		switch node.Type {
		case controlmodel.RunNodeAgent, controlmodel.RunNodeTeam:
			tasks, err := e.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{Tenant: run.Tenant, Namespace: run.Namespace, NodeID: node.ID, Limit: 500})
			if err != nil {
				return changed, err
			}
			if len(tasks) == 0 {
				continue
			}
			latest := tasks[len(tasks)-1]
			if !controlmodel.IsAgentTaskTerminal(latest.Status) {
				continue
			}
			// A Team coordinator is an explicit consistency boundary. Completing the
			// physical leader task is never proof that delegation converged (the model
			// may have returned text without performing the claimed actions).
			if node.Type == controlmodel.RunNodeTeam && latest.Status == controlmodel.AgentTaskCompleted {
				continue
			}
			if latest.Status == controlmodel.AgentTaskCompleted {
				if _, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeSucceeded, latest.Result, "", ""); err != nil && err != store.ErrConflict {
					return changed, err
				}
				changed = err == nil
				continue
			}
			maxAttempts := cfg.Retry.MaxAttempts
			if maxAttempts <= 0 && run.Mode == controlmodel.RunModeAdaptive && latest.TeamID != nil && !latest.LeaderTask {
				if team, teamErr := (&collaboration.Service{Store: e.Store}).TeamForTask(ctx, latest); teamErr == nil {
					maxAttempts = team.Policy.MaxTaskRetries + 1
				}
			}
			if maxAttempts <= 0 {
				maxAttempts = 1
			}
			if int32(len(tasks)) < maxAttempts && retryableTaskFailure(latest.ErrorCode) {
				if latest.CompletedAt != nil && now.Before(latest.CompletedAt.Add(time.Duration(cfg.Retry.BackoffSeconds)*time.Second)) {
					continue
				}
				teamID := latest.TeamID
				_, err = e.Store.Collaboration().CreateRunAgentTask(ctx, store.RunTaskRequest{RunID: run.ID, NodeID: node.ID,
					IssueID: latest.IssueID, AgentRef: latest.AgentRef, TeamID: teamID, TeamRole: latest.TeamRole,
					Leader: latest.LeaderTask, Priority: latest.Priority, RuntimeCandidate: cfg.RuntimeCandidate, Originator: run.CreatedBy})
				if err != nil {
					return changed, err
				}
				changed = true
				continue
			}
			if _, _, err = (&collaboration.Service{Store: e.Store}).ConvergeFailedTask(ctx, latest.ID); err != nil {
				return changed, err
			}
			if _, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeFailed, nil,
				latest.ErrorCode, latest.ErrorMessage); err != nil {
				return changed, err
			}
			changed = true
		case controlmodel.RunNodeApproval:
			approvals, err := e.Store.Collaboration().ListApprovals(ctx, store.ApprovalFilter{Tenant: run.Tenant,
				Namespace: run.Namespace, TargetType: "run-node", TargetRef: node.ID.String(), Limit: 10})
			if err != nil {
				return changed, err
			}
			for _, approval := range approvals {
				decided := true
				switch approval.Status {
				case controlmodel.ApprovalApproved:
					_, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeSucceeded, approval.Decision, "", "")
				case controlmodel.ApprovalRejected, controlmodel.ApprovalCancelled:
					_, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeFailed, approval.Decision, "approval_rejected", approval.Reason)
				default:
					decided = false
				}
				if !decided {
					continue
				}
				if err != nil {
					return changed, err
				}
				changed = true
				break
			}
		case controlmodel.RunNodeSignal:
			for after := int64(0); ; {
				events, err := e.Store.Orchestration().ListRunEvents(ctx, run.ID, after, 100)
				if err != nil {
					return changed, err
				}
				found := false
				for _, event := range events {
					after = event.Sequence
					if event.Type == "run.signal."+cfg.SignalName {
						if _, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeSucceeded, event.Payload, "", ""); err != nil {
							return changed, err
						}
						changed, found = true, true
						break
					}
				}
				if found || len(events) < 100 {
					break
				}
			}
		case controlmodel.RunNodeTimer:
			var output struct {
				WakeAt time.Time `json:"wakeAt"`
			}
			if json.Unmarshal(node.Output, &output) == nil && !output.WakeAt.IsZero() && !now.Before(output.WakeAt) {
				if _, err := e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeSucceeded, node.Output, "", ""); err != nil {
					return changed, err
				}
				changed = true
			}
		case controlmodel.RunNodeSubrun:
			var output struct {
				SubrunID uuid.UUID `json:"subrunId"`
			}
			if json.Unmarshal(node.Output, &output) == nil && output.SubrunID != uuid.Nil {
				sub, err := e.Store.Orchestration().GetRun(ctx, output.SubrunID)
				if err != nil {
					return changed, err
				}
				if controlmodel.IsOrchestrationRunTerminal(sub.State) {
					target := controlmodel.RunNodeSucceeded
					if sub.State == controlmodel.RunFailed || sub.State == controlmodel.RunCancelled {
						target = controlmodel.RunNodeFailed
					}
					if _, err = e.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, target, sub.Output, sub.FailureCode, sub.FailureMessage); err != nil {
						return changed, err
					}
					changed = true
				}
			}
		}
	}
	return changed, nil
}

func retryableTaskFailure(code string) bool {
	normalized := strings.ToLower(strings.TrimSpace(code))
	if normalized == "" {
		return true
	}
	for _, terminal := range []string{
		"api_key_missing", "credential_missing", "configuration_missing", "invalid_configuration",
		"permission_denied", "unauthorized", "forbidden", "unsupported_capability", "invalid_input",
	} {
		if normalized == terminal {
			return false
		}
	}
	return true
}

func (e *Engine) isAdaptiveTeamWorkerNode(ctx context.Context, run *controlmodel.OrchestrationRun,
	node *controlmodel.RunNode) bool {
	if run == nil || node == nil || run.Mode != controlmodel.RunModeAdaptive || node.Type != controlmodel.RunNodeAgent {
		return false
	}
	tasks, err := e.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{
		Tenant: run.Tenant, Namespace: run.Namespace, NodeID: node.ID, Limit: 10,
	})
	if err != nil {
		return false
	}
	for _, task := range tasks {
		if task.TeamID != nil && !task.LeaderTask {
			return true
		}
	}
	return false
}

func (e *Engine) createSubrun(ctx context.Context, parent *controlmodel.OrchestrationRun, node *controlmodel.RunNode, revision *controlmodel.OrchestrationRevision, input json.RawMessage) (*controlmodel.OrchestrationRun, error) {
	run, err := e.Store.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{Tenant: parent.Tenant, Namespace: parent.Namespace, RootIssueID: parent.RootIssueID, Mode: controlmodel.RunModeSubrun, DefinitionRevisionID: &revision.ID, ParentRunID: &parent.ID, ParentNodeID: &node.ID, TriggerType: "subrun", TriggerRef: node.ID.String(), IdempotencyKey: "subrun:" + parent.ID.String() + ":" + node.ID.String(), Input: input, Variables: json.RawMessage(`{}`), PolicySnapshot: parent.PolicySnapshot, State: controlmodel.RunPlanned, CreatedBy: parent.CreatedBy})
	if err != nil {
		return nil, err
	}
	if err = e.ReconcileRun(ctx, run.ID); err != nil {
		return nil, err
	}
	return run, nil
}

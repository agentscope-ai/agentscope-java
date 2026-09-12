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

package collaboration

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// PendingDelegationTasks uses durable lineage, not words such as "waiting" in
// model output. A result already queued for another leader turn is also a wake
// source: the worker can finish between the mention and the leader yielding.
func (s *Service) PendingDelegationTasks(ctx context.Context, task *controlmodel.AgentTask) ([]uuid.UUID, error) {
	if task == nil || !task.LeaderTask || task.TeamID == nil {
		return nil, nil
	}
	tasks, err := s.listAllTasks(ctx, store.AgentTaskFilter{Tenant: task.Tenant, Namespace: task.Namespace, RunID: task.OrchestrationRunID})
	if err != nil {
		return nil, err
	}
	node, err := s.Store.Orchestration().GetNode(ctx, task.RunNodeID)
	if err != nil {
		return nil, err
	}
	issue, err := s.Store.Collaboration().GetIssue(ctx, task.IssueID)
	if err != nil {
		return nil, err
	}
	decided := node.IssueID != nil && (*node.IssueID == task.IssueID || issue.Status == controlmodel.IssueDone || issue.Status == controlmodel.IssueCancelled)
	delegated := map[uuid.UUID]bool{}
	pending := []uuid.UUID{}
	for _, candidate := range tasks {
		if candidate.LeaderTask || candidate.TeamID == nil || *candidate.TeamID != *task.TeamID {
			continue
		}
		ownDelegation := candidate.ParentTaskID != nil && *candidate.ParentTaskID == task.ID || candidate.DelegatedFromTaskID != nil && *candidate.DelegatedFromTaskID == task.ID
		siblingWork := false
		if decided && !controlmodel.IsAgentTaskTerminal(candidate.Status) {
			child, loadErr := s.Store.Collaboration().GetIssue(ctx, candidate.IssueID)
			if loadErr != nil {
				return nil, loadErr
			}
			siblingWork = child.ParentIssueID != nil && node.IssueID != nil && *child.ParentIssueID == *node.IssueID
		}
		if ownDelegation || siblingWork {
			delegated[candidate.ID] = true
			if !controlmodel.IsAgentTaskTerminal(candidate.Status) {
				pending = append(pending, candidate.ID)
			}
		}
	}
	for _, candidate := range tasks {
		if candidate.ID == task.ID || !candidate.LeaderTask || candidate.RunNodeID != task.RunNodeID || controlmodel.IsAgentTaskTerminal(candidate.Status) || candidate.TriggerCommentID == nil {
			continue
		}
		if decided && candidate.TeamID != nil && *candidate.TeamID == *task.TeamID && len(candidate.Inputs) > 0 {
			pending = append(pending, candidate.ID)
			continue
		}
		comment, err := s.Store.Collaboration().GetComment(ctx, *candidate.TriggerCommentID)
		if err != nil {
			return nil, err
		}
		if comment.SourceTaskID != nil && delegated[*comment.SourceTaskID] {
			pending = append(pending, candidate.ID)
		}
	}
	return pending, nil
}

// WaitForDelegatedWork completes only the leader's physical turn. Its shared
// coordinator node remains waiting and the worker outcome owns the next wake.
// The status comment deliberately has no routes, avoiding self-wake loops.
func (s *Service) WaitForDelegatedWork(ctx context.Context, taskID uuid.UUID, summary string, partial ...json.RawMessage) (*controlmodel.AgentTask, *controlmodel.Comment, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, nil, err
	}
	pending, err := s.PendingDelegationTasks(ctx, task)
	if err != nil {
		return nil, nil, err
	}
	if len(pending) == 0 {
		return nil, nil, fmt.Errorf("waiting requires outstanding delegated work or a queued outcome after deciding the current child; do not repeat this call unchanged. Read task.get and decide the current result. For missing human input, call issue.comment.add with mentions=[{type:human,ref:<accountableHumanRef>}] and then task.complete(outcome=succeeded) to end only this decision turn. For recoverable missing input use task.complete(outcome=blocked, result=<partial deliverable>) with the next action in summary; the coordinator stays resumable. Use run.node.fail only to explicitly abort the whole objective. A plain comment or a summary claiming the Issue is blocked does not change its status")
	}
	summary = strings.TrimSpace(summary)
	if summary == "" {
		summary = "Waiting for delegated work; the coordinator will resume when its outcome arrives."
	}
	payload := map[string]any{"outcome": "waiting", "summary": summary, "waitingForTaskIds": pending}
	if len(partial) > 0 && len(partial[0]) > 0 {
		payload["result"] = partial[0]
	}
	result, _ := json.Marshal(payload)
	completion := store.TaskCompletion{ExpectedVersion: task.Version, Summary: summary, Result: result}
	for _, input := range task.Inputs {
		completion.ProcessedInputIDs = append(completion.ProcessedInputIDs, input.ID)
	}
	if task.CurrentAttemptID != nil {
		attempt, err := s.Store.ExecutionAttempts().Get(ctx, *task.CurrentAttemptID)
		if err != nil {
			return nil, nil, err
		}
		completion.AttemptID = attempt.ID
		completion.DispatchGeneration = attempt.DispatchGeneration
	}
	actor := controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
	completed, comment, err := s.Store.Collaboration().CompleteAgentTaskWithComment(ctx, task.ID, completion, &controlmodel.Comment{IssueID: task.IssueID, Author: actor, Type: controlmodel.CommentStatus, Content: summary, SourceTaskID: &task.ID}, nil)
	if err != nil {
		return completed, comment, err
	}
	_, err = s.Store.Orchestration().AppendRunEvent(context.WithoutCancel(ctx), &controlmodel.RunEvent{RunID: task.OrchestrationRunID, Tenant: task.Tenant, Namespace: task.Namespace, NodeID: &task.RunNodeID, AgentTaskID: &task.ID, AttemptID: task.CurrentAttemptID, Type: "coordinator.waiting", Actor: actor, Payload: result, IdempotencyKey: "coordinator-waiting:" + task.ID.String()})
	return completed, comment, err
}

// BlockCoordinatorForHuman ends the physical decision turn without aborting the
// coordinator. Partial work is durable and visible on the root; a human follow-up
// can use the existing blocked-Issue resume path.
func (s *Service) BlockCoordinatorForHuman(ctx context.Context, taskID uuid.UUID, reason string, partial json.RawMessage, codes ...string) (*controlmodel.AgentTask, *controlmodel.Comment, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, nil, err
	}
	if !task.LeaderTask || task.TeamID == nil || task.TriggerType == controlmodel.AgentTaskReviewComment || controlmodel.IsAgentTaskTerminal(task.Status) {
		return nil, nil, fmt.Errorf("blocked coordinator requires an active Team leader")
	}
	if strings.TrimSpace(reason) == "" {
		return nil, nil, fmt.Errorf("blocked requires a concrete reason and next action")
	}
	run, err := s.Store.Orchestration().GetRun(ctx, task.OrchestrationRunID)
	if err != nil {
		return nil, nil, err
	}
	actor := controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
	// Mark the affected Issue before ending the attempt, so recovery never treats
	// the partial result as an accepted deliverable.
	for _, id := range []uuid.UUID{task.IssueID, run.RootIssueID} {
		issue, loadErr := s.Store.Collaboration().GetIssue(ctx, id)
		if loadErr != nil {
			return nil, nil, loadErr
		}
		if issue.Status != controlmodel.IssueBlocked && issue.Status != controlmodel.IssueDone && issue.Status != controlmodel.IssueCancelled {
			if _, err = s.Store.Collaboration().TransitionIssue(ctx, id, issue.Version, controlmodel.IssueBlocked, actor, reason); err != nil {
				return nil, nil, err
			}
		}
	}
	code := "objective_blocked"
	if len(codes) > 0 && strings.TrimSpace(codes[0]) != "" {
		code = codes[0]
	}
	result, err := json.Marshal(map[string]any{"outcome": "blocked", "code": code, "reason": reason, "result": partial})
	if err != nil {
		return nil, nil, err
	}
	completion := store.TaskCompletion{ExpectedVersion: task.Version, Summary: reason, Result: result}
	for _, input := range task.Inputs {
		completion.ProcessedInputIDs = append(completion.ProcessedInputIDs, input.ID)
	}
	if task.CurrentAttemptID != nil {
		attempt, err := s.Store.ExecutionAttempts().Get(ctx, *task.CurrentAttemptID)
		if err != nil {
			return nil, nil, err
		}
		completion.AttemptID, completion.DispatchGeneration = attempt.ID, attempt.DispatchGeneration
	}
	content := "处理状态：blocked（等待补充条件，协调器保留）\n原因：" + reason + "\n错误代码：" + code
	if text := CoordinatorOutcomeText(partial); text != "" {
		content += "\n\n已保存的部分交付（尚未完成验收）：\n" + text
	}
	// Unique per decision turn, separate from the final coordinator summary ID.
	rootComment := &controlmodel.Comment{ID: uuid.NewSHA1(task.ID, []byte("coordinator-blocked")), IssueID: run.RootIssueID, Author: actor, Type: controlmodel.CommentStatus, Content: content, SourceTaskID: &task.ID, SourceAttemptID: task.CurrentAttemptID}
	if _, err = s.Store.Collaboration().CreateComment(ctx, store.CreateCommentRequest{Comment: rootComment}); err != nil {
		if _, readErr := s.Store.Collaboration().GetComment(ctx, rootComment.ID); readErr != nil {
			return nil, nil, err
		}
	}
	completed, err := s.Store.Collaboration().CompleteAgentTask(ctx, task.ID, completion)
	if err != nil {
		return completed, rootComment, err
	}
	_, err = s.Store.Orchestration().AppendRunEvent(context.WithoutCancel(ctx), &controlmodel.RunEvent{RunID: run.ID, Tenant: task.Tenant, Namespace: task.Namespace, NodeID: &task.RunNodeID, AgentTaskID: &task.ID, AttemptID: task.CurrentAttemptID, Type: "coordinator.blocked", Actor: actor, Payload: result, IdempotencyKey: "coordinator-blocked:" + task.ID.String()})
	return completed, rootComment, err
}

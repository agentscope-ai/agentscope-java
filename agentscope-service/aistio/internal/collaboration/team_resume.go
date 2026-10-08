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
	"fmt"
	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func (s *Service) failedTeamAssigneeContext(ctx context.Context, issue *model.Issue, agentRef string) (*uuid.UUID, string, *uuid.UUID, error) {
	if issue.ParentIssueID == nil {
		return nil, "", nil, nil
	}
	root, err := s.Store.Collaboration().GetIssue(ctx, *issue.ParentIssueID)
	if err != nil {
		return nil, "", nil, err
	}
	if root.AssigneeType != model.AssigneeTeam || root.ArchivedAt != nil || (root.Status != model.IssueBlocked && root.Status != model.IssueInProgress) {
		return nil, "", nil, nil
	}
	tasks, err := s.listAllTasks(ctx, store.AgentTaskFilter{Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: issue.ID, AgentRef: agentRef})
	if err != nil {
		return nil, "", nil, err
	}
	for i := len(tasks) - 1; i >= 0; i-- {
		task := tasks[i]
		if task.TeamID == nil || task.LeaderTask || task.TeamID.String() != root.AssigneeRef || task.ParentTaskID == nil {
			continue
		}
		run, loadErr := s.Store.Orchestration().GetRun(ctx, task.OrchestrationRunID)
		if loadErr != nil {
			return nil, "", nil, loadErr
		}
		if run.State != model.RunFailed || run.RootIssueID != root.ID {
			continue
		}
		parent, loadErr := s.Store.Collaboration().GetAgentTask(ctx, *task.ParentTaskID)
		if loadErr != nil {
			return nil, "", nil, loadErr
		}
		if !parent.LeaderTask {
			continue
		}
		team, loadErr := s.TeamForTask(ctx, task)
		if loadErr != nil {
			return nil, "", nil, loadErr
		}
		if _, member := teamAgentRole(team, agentRef); !member {
			continue
		}
		active, loadErr := s.Store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{Tenant: root.Tenant, Namespace: root.Namespace, RootIssueID: root.ID, ActiveOnly: true, Limit: 3})
		if loadErr != nil {
			return nil, "", nil, loadErr
		}
		for _, candidate := range active {
			if candidate.RerunOfRunID == nil || *candidate.RerunOfRunID != parent.OrchestrationRunID {
				return nil, "", nil, fmt.Errorf("root Issue already has a different active Run")
			}
		}
		return task.TeamID, task.TeamRole, &parent.ID, nil
	}
	return nil, "", nil, nil
}

// resumedChildCoordinatorTarget also handles tasks dispatched before this fix,
// which have already lost TeamID but still belong to a human-resumed child.
func (s *Service) resumedChildCoordinatorTarget(ctx context.Context, task *model.AgentTask, issue *model.Issue) (*store.CommentTarget, error) {
	if task.TeamID != nil || issue.ParentIssueID == nil || issue.AssigneeType != model.AssigneeAgent || issue.AssigneeRef != task.AgentRef {
		return nil, nil
	}
	human := task.Originator.Type == model.ActorHuman
	if task.TriggerCommentID != nil {
		trigger, err := s.Store.Collaboration().GetComment(ctx, *task.TriggerCommentID)
		if err != nil {
			return nil, err
		}
		human = human || trigger.Author.Type == model.ActorHuman
	}
	if !human {
		return nil, nil
	}
	teamID, _, parentID, err := s.failedTeamAssigneeContext(ctx, issue, task.AgentRef)
	if err != nil || teamID == nil || parentID == nil {
		return nil, err
	}
	parent, err := s.Store.Collaboration().GetAgentTask(ctx, *parentID)
	if err != nil {
		return nil, err
	}
	return &store.CommentTarget{TargetType: model.AssigneeAgent, TargetRef: parent.AgentRef, AgentRef: parent.AgentRef, TeamID: teamID, TeamRole: parent.TeamRole, ParentTaskID: parentID, RouteType: model.RouteTeamLeader}, nil
}

// ResumeCompletedDelegatedTask repairs only the missing return route. Existing
// worker output and terminal Run/Task/Attempt records are retained unchanged.
func (s *Service) ResumeCompletedDelegatedTask(ctx context.Context, taskID uuid.UUID) (*model.Comment, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	if task.Status != model.AgentTaskCompleted {
		return nil, fmt.Errorf("only completed child results can be recovered")
	}
	id := uuid.NewSHA1(task.ID, []byte("resumed-team-result-v1"))
	if existing, err := s.Store.Collaboration().GetComment(ctx, id); err == nil {
		return existing, nil
	} else if err != store.ErrNotFound {
		return nil, err
	}
	issue, err := s.Store.Collaboration().GetIssue(ctx, task.IssueID)
	if err != nil {
		return nil, err
	}
	target, err := s.resumedChildCoordinatorTarget(ctx, task, issue)
	if err != nil {
		return nil, err
	}
	if target == nil {
		return nil, fmt.Errorf("no failed parent Team delegation to resume")
	}
	content := "系统恢复结果回流：人类已补充要求，当前子任务完成后未唤醒 Team Lead。请结合最新人类要求验收实际结果，必要时要求补全交付内容，再继续主 Issue 的整体汇总。\n\n已保存的任务结果：\n" + string(task.Result)
	if task.TriggerCommentID != nil {
		trigger, err := s.Store.Collaboration().GetComment(ctx, *task.TriggerCommentID)
		if err != nil {
			return nil, err
		}
		content += "\n\n人类本次要求：\n" + trigger.Content
	}
	result, err := s.Store.Collaboration().CreateComment(ctx, store.CreateCommentRequest{Comment: &model.Comment{ID: id, IssueID: issue.ID, Author: model.Actor{Type: model.ActorSystem, Ref: "repair:team-continuation"}, Type: model.CommentStatus, SourceTaskID: &task.ID, Content: content}, Targets: []store.CommentTarget{*target}})
	if err != nil {
		if existing, loadErr := s.Store.Collaboration().GetComment(ctx, id); loadErr == nil {
			return existing, nil
		}
		return nil, err
	}
	return result.Comment, nil
}

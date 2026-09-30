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
package memory

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"time"
)

func (r *collaborationRepo) BeginReviewWork(ctx context.Context, id uuid.UUID, version int64, quote string) (*model.AgentTask, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	task := r.s.agentTasks[id]
	if task == nil {
		return nil, store.ErrNotFound
	}
	if task.Version != version {
		return nil, store.ErrConflict
	}
	issue := r.s.issues[task.IssueID]
	if issue == nil {
		return nil, store.ErrNotFound
	}
	comments := []*model.Comment{}
	for _, input := range r.s.taskInputs[id] {
		if c := r.s.comments[input.CommentID]; c != nil {
			comments = append(comments, c)
		}
	}
	if err := store.ValidateBeginReviewWork(issue, task, quote, comments); err != nil {
		return nil, err
	}
	for _, run := range r.s.runs {
		if run.RootIssueID == issue.ID && run.ID != task.OrchestrationRunID && run.TriggerType != model.AgentTaskReviewComment && !model.IsOrchestrationRunTerminal(run.State) {
			return nil, fmt.Errorf("another work Run is already active for this Issue")
		}
	}
	run := r.s.runs[task.OrchestrationRunID]
	if run == nil {
		return nil, store.ErrNotFound
	}
	now := time.Now().UTC()
	task.TriggerType = "comment"
	task.Version++
	run.TriggerType = "comment"
	run.Input, _ = json.Marshal(map[string]string{"reviewRequest": quote})
	run.Version++
	run.UpdatedAt = now
	previous := issue.Status
	issue.Status = model.IssueInProgress
	issue.ResolvedAt = nil
	issue.Version++
	issue.UpdatedAt = now
	actor := model.Actor{Type: model.ActorAgent, Ref: task.AgentRef}
	reason := "New work requested in review: " + quote
	payload, _ := json.Marshal(map[string]string{"from": string(previous), "to": string(issue.Status), "reason": reason})
	r.appendActivityLocked(&model.Activity{Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: &issue.ID, Actor: actor, Action: "issue.status_changed", ObjectType: "issue", ObjectRef: issue.ID.String(), Details: payload})
	r.notifyIssueInboxLocked(issue, previous, actor, reason, task)
	r.enqueueEventLocked(issue.Tenant, "issue", issue.ID, "issue.status-changed.v1", map[string]any{"issue": issue, "previousStatus": previous}, fmt.Sprintf("issue-status:%s:%d", issue.ID, issue.Version))
	r.s.runEvents[run.ID] = append(r.s.runEvents[run.ID], &model.RunEvent{ID: uuid.New(), RunID: run.ID, Tenant: run.Tenant, Namespace: run.Namespace, Sequence: int64(len(r.s.runEvents[run.ID]) + 1), NodeID: &task.RunNodeID, AgentTaskID: &task.ID, Type: "review.work_requested", Actor: actor, Payload: payload, OccurredAt: now, IdempotencyKey: "review-work:" + task.ID.String()})
	return cloneAgentTask(task), nil
}

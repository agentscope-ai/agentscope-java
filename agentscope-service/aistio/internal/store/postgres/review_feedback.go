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
package postgres

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func (r *collaborationRepo) BeginReviewWork(ctx context.Context, id uuid.UUID, version int64, quote string) (*model.AgentTask, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	task, err := scanAgentTask(tx.QueryRow(ctx, `SELECT `+agentTaskColumns+` FROM agent_tasks WHERE id=$1 FOR UPDATE`, id))
	if err != nil {
		return nil, err
	}
	if task.Version != version {
		return nil, store.ErrConflict
	}
	issue, err := scanIssue(tx.QueryRow(ctx, `SELECT `+issueColumns+` FROM issues WHERE id=$1 FOR UPDATE`, task.IssueID))
	if err != nil {
		return nil, err
	}
	rows, err := tx.Query(ctx, `SELECT c.id FROM comments c JOIN agent_task_inputs i ON i.comment_id=c.id WHERE i.task_id=$1`, id)
	if err != nil {
		return nil, err
	}
	ids := []uuid.UUID{}
	for rows.Next() {
		var cid uuid.UUID
		if err = rows.Scan(&cid); err != nil {
			rows.Close()
			return nil, err
		}
		ids = append(ids, cid)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, err
	}
	comments := []*model.Comment{}
	for _, cid := range ids {
		c, e := scanComment(tx.QueryRow(ctx, `SELECT `+commentColumns+` FROM comments WHERE id=$1`, cid))
		if e != nil {
			return nil, e
		}
		comments = append(comments, c)
	}
	if err = store.ValidateBeginReviewWork(issue, task, quote, comments); err != nil {
		return nil, err
	}
	var active bool
	if err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration_runs WHERE root_issue_id=$1 AND id<>$2 AND trigger_type<>$3 AND state NOT IN ('succeeded','partial_succeeded','failed','cancelled'))`, issue.ID, task.OrchestrationRunID, model.AgentTaskReviewComment).Scan(&active); err != nil {
		return nil, err
	}
	if active {
		return nil, fmt.Errorf("another work Run is already active for this Issue")
	}
	task, err = scanAgentTask(tx.QueryRow(ctx, `UPDATE agent_tasks SET trigger_type='comment',version=version+1 WHERE id=$1 RETURNING `+agentTaskColumns, id))
	if err != nil {
		return nil, err
	}
	input, _ := json.Marshal(map[string]string{"reviewRequest": quote})
	if _, err = tx.Exec(ctx, `UPDATE orchestration_runs SET trigger_type='comment',input=$2,version=version+1,updated_at=now() WHERE id=$1`, task.OrchestrationRunID, input); err != nil {
		return nil, err
	}
	previous := issue.Status
	issue, err = scanIssue(tx.QueryRow(ctx, `UPDATE issues SET status='in_progress',resolved_at=NULL,version=version+1,updated_at=now() WHERE id=$1 RETURNING `+issueColumns, issue.ID))
	if err != nil {
		return nil, err
	}
	actor := model.Actor{Type: model.ActorAgent, Ref: task.AgentRef}
	reason := "New work requested in review: " + quote
	payload, _ := json.Marshal(map[string]string{"from": string(previous), "to": string(issue.Status), "reason": reason})
	if err = insertActivityTx(ctx, tx, &model.Activity{Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: &issue.ID, Actor: actor, Action: "issue.status_changed", ObjectType: "issue", ObjectRef: issue.ID.String(), Details: payload}); err != nil {
		return nil, err
	}
	if err = notifyIssueInboxTx(ctx, tx, issue, previous, actor, reason, task); err != nil {
		return nil, err
	}
	if err = enqueueCollaborationEventTx(ctx, tx, issue.Tenant, "issue", issue.ID, "issue.status-changed.v1", map[string]any{"issue": issue, "previousStatus": previous}, fmt.Sprintf("issue-status:%s:%d", issue.ID, issue.Version)); err != nil {
		return nil, err
	}
	if err = appendRunEventTx(ctx, tx, &model.RunEvent{RunID: task.OrchestrationRunID, Tenant: task.Tenant, Namespace: task.Namespace, NodeID: &task.RunNodeID, AgentTaskID: &task.ID, Type: "review.work_requested", Actor: actor, Payload: payload, IdempotencyKey: "review-work:" + task.ID.String()}); err != nil {
		return nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	return task, nil
}

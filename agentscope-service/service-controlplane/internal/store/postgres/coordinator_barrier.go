// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"sort"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// Writers keep the Run row lock until commit, including reused Task nodes and
// coalesced inputs. Recheck closure after locking, not before waiting for it.
func lockCoordinatorRunTx(ctx context.Context, tx pgx.Tx, runID uuid.UUID) (model.OrchestrationRunState, error) {
	var state model.OrchestrationRunState
	var mode model.OrchestrationRunMode
	var root uuid.UUID
	if err := tx.QueryRow(ctx, `SELECT state,mode,root_issue_id FROM orchestration_runs WHERE id=$1 FOR UPDATE`, runID).Scan(&state, &mode, &root); err != nil {
		return state, orchNotFound(err)
	}
	if state == model.RunCancelling {
		return state, store.ErrConflict
	}
	if !model.IsOrchestrationRunTerminal(state) && mode == model.RunModeAdaptive {
		var closed bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration_run_nodes WHERE run_id=$1 AND issue_id=$2 AND type='team' AND state IN('succeeded','skipped','failed','cancelled'))`, runID, root).Scan(&closed); err != nil {
			return state, err
		}
		if closed {
			return state, store.ErrConflict
		}
	}
	return state, nil
}

func guardTaskCoordinatorTx(ctx context.Context, tx pgx.Tx, taskID uuid.UUID, allowContinuation bool) (model.OrchestrationRunState, error) {
	var runID uuid.UUID
	if err := tx.QueryRow(ctx, `SELECT orchestration_run_id FROM agent_tasks WHERE id=$1`, taskID).Scan(&runID); err != nil {
		return "", orchNotFound(err)
	}
	state, err := lockCoordinatorRunTx(ctx, tx, runID)
	if err != nil {
		return state, err
	}
	if model.IsOrchestrationRunTerminal(state) {
		if !allowContinuation {
			return state, store.ErrConflict
		}
		return state, nil
	}
	var closed bool
	if err = tx.QueryRow(ctx, `WITH RECURSIVE lineage AS (
 SELECT id,parent_task_id,run_node_id,ARRAY[id] AS path,false AS cycle,1 AS depth FROM agent_tasks WHERE id=$1
 UNION ALL SELECT p.id,p.parent_task_id,p.run_node_id,l.path||p.id,p.id=ANY(l.path),l.depth+1
 FROM agent_tasks p JOIN lineage l ON p.id=l.parent_task_id
 WHERE p.orchestration_run_id=$2 AND l.depth<256 AND NOT l.cycle)
 SELECT EXISTS(SELECT 1 FROM lineage l LEFT JOIN orchestration_run_nodes n ON n.id=l.run_node_id
 WHERE l.cycle OR (l.depth=256 AND l.parent_task_id IS NOT NULL)
 OR (n.type='team' AND n.state IN('succeeded','skipped','failed','cancelled')))`, taskID, runID).Scan(&closed); err != nil {
		return state, err
	}
	if closed {
		return state, store.ErrConflict
	}
	return state, nil
}

// Unassigned children also participate in an active coordinator barrier.
// A later human request on a terminal Issue is separate work; an old AgentTask
// cannot delegate after its Run has closed (guardTaskCoordinatorTx).
func guardCoordinatorChildTx(ctx context.Context, tx pgx.Tx, issue *model.Issue) error {
	return guardCoordinatorChildWriteTx(ctx, tx, issue, false)
}

func guardCoordinatorChildWriteTx(ctx context.Context, tx pgx.Tx, issue *model.Issue, allowTerminalSource bool) error {
	var sourceID *uuid.UUID
	var ids []uuid.UUID
	if issue.SourceType == "agent-task" {
		id, err := uuid.Parse(issue.SourceRef)
		if err != nil {
			return store.ErrNotFound
		}
		var runID uuid.UUID
		if err = tx.QueryRow(ctx, `SELECT orchestration_run_id FROM agent_tasks WHERE id=$1 AND tenant=$2 AND namespace=$3`, id, issue.Tenant, issue.Namespace).Scan(&runID); err != nil {
			return orchNotFound(err)
		}
		sourceID = &id
		ids = append(ids, runID)
	}
	if issue.ParentIssueID != nil {
		rows, err := tx.Query(ctx, `SELECT DISTINCT n.run_id FROM orchestration_run_nodes n JOIN orchestration_runs r ON r.id=n.run_id
	 WHERE n.issue_id=$1 AND n.type='team' AND r.mode='adaptive' AND r.state NOT IN('succeeded','partial_succeeded','failed','cancelled')
	 ORDER BY n.run_id LIMIT $2`, *issue.ParentIssueID, store.MaxRunNodes+1)
		if err != nil {
			return err
		}
		for rows.Next() {
			var id uuid.UUID
			if err = rows.Scan(&id); err != nil {
				rows.Close()
				return err
			}
			ids = append(ids, id)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return err
		}
	}
	if len(ids) > store.MaxRunNodes+1 {
		return store.ErrResourceLimit
	}
	sort.Slice(ids, func(i, j int) bool { return ids[i].String() < ids[j].String() })
	for _, id := range ids {
		state, err := lockCoordinatorRunTx(ctx, tx, id)
		if err != nil {
			return err
		}
		if model.IsOrchestrationRunTerminal(state) {
			if allowTerminalSource {
				continue
			}
			return store.ErrConflict
		}
		if issue.ParentIssueID == nil {
			continue
		}
		var closed bool
		if err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration_run_nodes n JOIN orchestration_runs r ON r.id=n.run_id WHERE n.run_id=$1 AND n.issue_id=$2 AND n.type='team' AND r.mode='adaptive' AND n.state IN('succeeded','skipped','failed','cancelled'))`, id, *issue.ParentIssueID).Scan(&closed); err != nil {
			return err
		}
		if closed {
			return store.ErrConflict
		}
	}
	if sourceID != nil {
		_, err := guardTaskCoordinatorTx(ctx, tx, *sourceID, allowTerminalSource)
		return err
	}
	return nil
}

func coordinatorCommitError(err error) error {
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && (pgErr.Code == "55P03" || pgErr.Code == "40P01") {
		return store.ErrConflict
	}
	return orchNotFound(err)
}

func (r *orchestrationRepo) FinalizeCoordinatorNode(ctx context.Context, taskID uuid.UUID, expected int64, output json.RawMessage, actor model.Actor) (*model.RunNode, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	task := &model.AgentTask{ID: taskID}
	if err = tx.QueryRow(ctx, `SELECT orchestration_run_id,run_node_id,issue_id,is_leader_task,status,tenant,namespace FROM agent_tasks WHERE id=$1`, taskID).Scan(&task.OrchestrationRunID, &task.RunNodeID, &task.IssueID, &task.LeaderTask, &task.Status, &task.Tenant, &task.Namespace); err != nil {
		return nil, orchNotFound(err)
	}
	run := &model.OrchestrationRun{ID: task.OrchestrationRunID}
	// NOWAIT avoids inversion with Task/Issue writers that already hold node
	// locks. Contention leaves a recoverable waiting node for the next attempt.
	if err = tx.QueryRow(ctx, `SELECT mode,state FROM orchestration_runs WHERE id=$1 FOR UPDATE NOWAIT`, run.ID).Scan(&run.Mode, &run.State); err != nil {
		return nil, coordinatorCommitError(err)
	}
	node, err := scanNode(tx.QueryRow(ctx, `SELECT `+nodeCols+` FROM orchestration_run_nodes WHERE id=$1 FOR UPDATE NOWAIT`, task.RunNodeID))
	if err != nil {
		return nil, coordinatorCommitError(err)
	}
	if !task.LeaderTask || task.Status != model.AgentTaskCompleted || node.Type != model.RunNodeTeam || node.State != model.RunNodeWaiting || node.Version != expected || model.IsOrchestrationRunTerminal(run.State) || run.State == model.RunCancelling {
		return nil, store.ErrConflict
	}
	// Only bounded barrier columns are read; result payloads and all input bodies
	// are unnecessary. EXISTS preserves the pending duplicate-leader rule.
	rows, err := tx.Query(ctx, `SELECT id,run_node_id,parent_task_id,is_leader_task,status,EXISTS(SELECT 1 FROM agent_task_inputs i WHERE i.task_id=t.id) FROM agent_tasks t WHERE orchestration_run_id=$1 ORDER BY id LIMIT $2`, run.ID, store.MaxRunTaskView+1)
	if err != nil {
		return nil, err
	}
	var tasks []*model.AgentTask
	for rows.Next() {
		v := &model.AgentTask{}
		var inputs bool
		if err = rows.Scan(&v.ID, &v.RunNodeID, &v.ParentTaskID, &v.LeaderTask, &v.Status, &inputs); err != nil {
			rows.Close()
			return nil, err
		}
		if inputs {
			v.Inputs = []model.AgentTaskInput{{}}
		}
		tasks = append(tasks, v)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, err
	}
	if len(tasks) > store.MaxRunTaskView {
		return nil, store.ErrResourceLimit
	}
	rows, err = tx.Query(ctx, `SELECT id,state,left(node_key,256) FROM orchestration_run_nodes WHERE run_id=$1 ORDER BY id LIMIT $2`, run.ID, store.MaxRunNodes+1)
	if err != nil {
		return nil, err
	}
	var nodes []*model.RunNode
	for rows.Next() {
		v := &model.RunNode{}
		if err = rows.Scan(&v.ID, &v.State, &v.NodeKey); err != nil {
			rows.Close()
			return nil, err
		}
		nodes = append(nodes, v)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, err
	}
	if len(nodes) > store.MaxRunNodes {
		return nil, store.ErrResourceLimit
	}
	issueID := task.IssueID
	if node.IssueID != nil {
		issueID = *node.IssueID
	}
	rows, err = tx.Query(ctx, `SELECT id,status,CASE WHEN source_type='agent-task' THEN 'agent-task' ELSE '' END,CASE WHEN source_type='agent-task' THEN left(source_ref,37) ELSE '' END FROM issues WHERE parent_issue_id=$1 ORDER BY id LIMIT $2`, issueID, store.MaxRunNodes+1)
	if err != nil {
		return nil, err
	}
	var children []*model.Issue
	for rows.Next() {
		v := &model.Issue{}
		if err = rows.Scan(&v.ID, &v.Status, &v.SourceType, &v.SourceRef); err != nil {
			rows.Close()
			return nil, err
		}
		children = append(children, v)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, err
	}
	if err = store.ValidateCoordinatorBarrier(run, node, task, tasks, nodes, children, false); err != nil {
		return nil, err
	}
	completed, err := scanNode(tx.QueryRow(ctx, `UPDATE orchestration_run_nodes SET state='succeeded',output=COALESCE($3,output),wait_reason=NULL,failure_code=NULL,failure_message=NULL,version=version+1,updated_at=now(),completed_at=now() WHERE id=$1 AND version=$2 RETURNING `+nodeCols, node.ID, expected, nullJSON(output)))
	if err != nil {
		return nil, coordinatorCommitError(err)
	}
	if err = appendRunEventTx(ctx, tx, &model.RunEvent{RunID: run.ID, Tenant: task.Tenant, Namespace: task.Namespace, NodeID: &node.ID, AgentTaskID: &task.ID, Type: "node.succeeded", Actor: actor, Payload: output, IdempotencyKey: "coordinator-complete:" + node.ID.String()}); err != nil {
		return nil, err
	}
	if _, err = tx.Exec(ctx, "SELECT pg_notify('"+runEventNotifyChannel+"', $1)", run.ID.String()); err != nil {
		return nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, coordinatorCommitError(err)
	}
	return completed, nil
}

func (r *orchestrationRepo) SaveCoordinatorCompletionIntent(ctx context.Context, taskID uuid.UUID, expected int64, raw json.RawMessage) (json.RawMessage, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var tenant, namespace string
	var leader bool
	var status model.AgentTaskStatus
	var version int64
	if err = tx.QueryRow(ctx, `SELECT tenant,namespace,is_leader_task,status,version FROM agent_tasks WHERE id=$1 FOR UPDATE`, taskID).Scan(&tenant, &namespace, &leader, &status, &version); err != nil {
		return nil, orchNotFound(err)
	}
	if !leader || version != expected || model.IsAgentTaskTerminal(status) && status != model.AgentTaskCompleted {
		return nil, store.ErrConflict
	}
	path := store.CoordinatorCompletionIntentPath(namespace)
	var previous json.RawMessage
	err = tx.QueryRow(ctx, `SELECT value FROM dp_kv WHERE tenant=$1 AND ns_path=$2 AND item_key=$3`, tenant, path, taskID.String()).Scan(&previous)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return nil, err
	}
	if status == model.AgentTaskCompleted && len(previous) > 0 {
		accepted, e := store.CoordinatorIntentAccepted(previous)
		if e != nil {
			return nil, e
		}
		if accepted {
			return previous, nil
		}
	}
	raw, err = store.SetCoordinatorIntentAccepted(raw, status == model.AgentTaskCompleted)
	if err != nil {
		return nil, err
	}
	if _, err = tx.Exec(ctx, `INSERT INTO dp_kv(tenant,ns_path,item_key,value,version,updated_at) VALUES($1,$2,$3,$4,1,now()) ON CONFLICT(tenant,ns_path,item_key) DO UPDATE SET value=EXCLUDED.value,version=dp_kv.version+1,updated_at=now()`, tenant, path, taskID.String(), raw); err != nil {
		return nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	return raw, nil
}

// The caller holds the Task row lock. Only a semantic conclusion accepts its
// pending decision, and its physical result must match that decision exactly.
func acceptCoordinatorIntentTx(ctx context.Context, tx pgx.Tx, task *model.AgentTask, completion store.TaskCompletion) error {
	if !completion.CoordinatorConclusion {
		return nil
	}
	if !task.LeaderTask {
		return store.ErrConflict
	}
	path := store.CoordinatorCompletionIntentPath(task.Namespace)
	var raw json.RawMessage
	if err := tx.QueryRow(ctx, `SELECT value FROM dp_kv WHERE tenant=$1 AND ns_path=$2 AND item_key=$3`, task.Tenant, path, task.ID.String()).Scan(&raw); err != nil {
		return orchNotFound(err)
	}
	matches, err := store.CoordinatorIntentMatchesOutput(raw, completion.Result)
	if err != nil {
		return err
	}
	if !matches {
		return store.ErrConflict
	}
	accepted, err := store.SetCoordinatorIntentAccepted(raw, true)
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `UPDATE dp_kv SET value=$4,version=version+1,updated_at=now() WHERE tenant=$1 AND ns_path=$2 AND item_key=$3`, task.Tenant, path, task.ID.String(), accepted)
	return err
}

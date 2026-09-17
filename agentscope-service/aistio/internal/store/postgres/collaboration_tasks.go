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

package postgres

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func createAgentTaskTx(ctx context.Context, tx pgx.Tx, issue *controlmodel.Issue,
	agentRef, triggerType string, triggerCommentID, teamID *uuid.UUID, teamRole string,
	leader bool, originator controlmodel.Actor, parentTaskID, retryOfTaskID *uuid.UUID) (*controlmodel.AgentTask, error) {
	if issue == nil || agentRef == "" {
		return nil, fmt.Errorf("issue and agentRef are required")
	}
	if retryOfTaskID != nil {
		var previousTrigger string
		if err := tx.QueryRow(ctx, `SELECT trigger_type FROM agent_tasks WHERE id=$1`, *retryOfTaskID).Scan(&previousTrigger); err != nil {
			return nil, err
		}
		if previousTrigger == controlmodel.AgentTaskReviewComment {
			triggerType = previousTrigger
		}
	}
	triggerType = store.ReviewCommentTrigger(issue, triggerType, originator)
	task := &controlmodel.AgentTask{ID: uuid.New(), Tenant: issue.Tenant,
		Namespace: issue.Namespace, IssueID: issue.ID, AgentRef: agentRef,
		Status: controlmodel.AgentTaskQueued, Priority: issuePriority(issue.Priority),
		TriggerType: triggerType, TriggerCommentID: triggerCommentID, TeamID: teamID,
		TeamRole: teamRole, LeaderTask: leader, ParentTaskID: parentTaskID,
		DelegatedFromTaskID: parentTaskID,
		RetryOfTaskID:       retryOfTaskID, Originator: originator, Version: 1,
		CorrelationID: issue.ID.String()}
	if originator.Type == controlmodel.ActorHuman {
		task.AccountableHumanRef = originator.Ref
	}
	if issue.SourceType == "automation" && task.AccountableHumanRef == "" {
		runID, parseErr := uuid.Parse(issue.SourceRef)
		if parseErr == nil {
			var owner *string
			err := tx.QueryRow(ctx, `SELECT runtime->'details'->'snapshot'->'createdBy'->>'ref' FROM automation_runs WHERE id=$1 AND tenant=$2 AND namespace=$3 AND runtime->'details'->'snapshot'->'createdBy'->>'type'='human'`, runID, issue.Tenant, issue.Namespace).Scan(&owner)
			if err != nil && err != pgx.ErrNoRows {
				return nil, err
			}
			task.AccountableHumanRef = deref(owner)
		}
	}
	var sourceTaskID = parentTaskID
	if triggerCommentID != nil {
		task.CausationID = triggerCommentID.String()
		var commentSourceTaskID *uuid.UUID
		if err := tx.QueryRow(ctx, `SELECT source_task_id FROM comments WHERE id=$1`, *triggerCommentID).Scan(&commentSourceTaskID); err != nil && err != pgx.ErrNoRows {
			return nil, err
		}
		// A human Comment has no source_task_id. Preserve the compatible active
		// task selected by the router so its follow-up remains in the same Run.
		if sourceTaskID == nil && commentSourceTaskID != nil {
			sourceTaskID = commentSourceTaskID
			task.ParentTaskID = commentSourceTaskID
			task.DelegatedFromTaskID = commentSourceTaskID
		}
	} else {
		if parentTaskID != nil {
			task.CausationID = parentTaskID.String()
		} else {
			task.CausationID = issue.ID.String()
		}
		if originator.Type == controlmodel.ActorHuman {
			task.AccountableHumanRef = originator.Ref
		}
	}
	if task.TriggerType == controlmodel.AgentTaskReviewComment {
		sourceTaskID = nil // Feedback owns its physical turn, not a prior coordinator.
	}
	if sourceTaskID != nil {
		var source controlmodel.AgentTask
		var correlation *string
		var accountableHumanRef *string
		var sourceTeamID *uuid.UUID
		var sourceRole *string
		if err := tx.QueryRow(ctx, `SELECT issue_id,correlation_id,hop_count,team_depth,accountable_human_ref,team_id,
			orchestration_run_id,run_node_id,agent_id,team_role,is_leader_task FROM agent_tasks WHERE id=$1`, *sourceTaskID).
			Scan(&source.IssueID, &correlation, &source.HopCount, &source.TeamDepth, &accountableHumanRef,
				&sourceTeamID, &source.OrchestrationRunID, &source.RunNodeID, &source.AgentRef, &sourceRole,
				&source.LeaderTask); err != nil {
			return nil, err
		}
		source.AccountableHumanRef = deref(accountableHumanRef)
		task.CorrelationID, task.HopCount, task.TeamDepth = deref(correlation), source.HopCount+1, source.TeamDepth
		task.AccountableHumanRef = source.AccountableHumanRef
		if task.TeamID != nil && (sourceTeamID == nil || *task.TeamID != *sourceTeamID) {
			task.TeamDepth = source.TeamDepth + 1
		}
		var runState controlmodel.OrchestrationRunState
		if err := tx.QueryRow(ctx, `SELECT state FROM orchestration_runs WHERE id=$1`, source.OrchestrationRunID).Scan(&runState); err != nil {
			return nil, err
		}
		if !controlmodel.IsOrchestrationRunTerminal(runState) {
			task.OrchestrationRunID = source.OrchestrationRunID
			reuseSourceNode := retryOfTaskID != nil || source.LeaderTask && leader &&
				source.AgentRef == agentRef && deref(sourceRole) == teamRole
			if reuseSourceNode {
				var nodeState controlmodel.RunNodeState
				if err := tx.QueryRow(ctx, `SELECT state FROM orchestration_run_nodes WHERE id=$1`, source.RunNodeID).
					Scan(&nodeState); err != nil {
					return nil, err
				}
				if !controlmodel.IsRunNodeTerminal(nodeState) {
					task.RunNodeID = source.RunNodeID
				}
			}
		} else {
			attached, attachErr := attachTeamContinuationTx(ctx, tx, issue, task, &source)
			if attachErr != nil {
				return nil, attachErr
			}
			if !attached {
				if err := createTaskRunNodeTx(ctx, tx, issue, task, &source.OrchestrationRunID); err != nil {
					return nil, err
				}
			}
		}
	}
	if task.OrchestrationRunID == uuid.Nil {
		if err := createTaskRunNodeTx(ctx, tx, issue, task, nil); err != nil {
			return nil, err
		}
	} else if task.RunNodeID == uuid.Nil {
		if err := createTaskNodeTx(ctx, tx, task); err != nil {
			return nil, err
		}
	}
	if task.TeamID != nil {
		if err := snapshotTaskTeamTx(ctx, tx, task.OrchestrationRunID, *task.TeamID); err != nil {
			return nil, err
		}
	}
	created, err := scanAgentTask(tx.QueryRow(ctx, `INSERT INTO agent_tasks
		(id,tenant,namespace,issue_id,orchestration_run_id,run_node_id,agent_id,status,priority,trigger_type,
			trigger_comment_id,team_id,team_role,is_leader_task,parent_task_id,
			delegated_from_task_id,retry_of_task_id,originator_type,originator_ref,accountable_human_ref,
			causation_id,correlation_id,hop_count,team_depth,version)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23,$24,1)
		RETURNING `+agentTaskColumns, task.ID, task.Tenant, task.Namespace, task.IssueID,
		task.OrchestrationRunID, task.RunNodeID, task.AgentRef, task.Status, task.Priority, task.TriggerType, task.TriggerCommentID,
		task.TeamID, nullStr(task.TeamRole), task.LeaderTask, task.ParentTaskID,
		task.DelegatedFromTaskID, task.RetryOfTaskID, task.Originator.Type, nullStr(task.Originator.Ref),
		nullStr(task.AccountableHumanRef), nullStr(task.CausationID), nullStr(task.CorrelationID),
		task.HopCount, task.TeamDepth))
	if err != nil {
		return nil, err
	}
	if err := appendRunEventTx(ctx, tx, &controlmodel.RunEvent{RunID: created.OrchestrationRunID,
		Tenant: created.Tenant, Namespace: created.Namespace, NodeID: &created.RunNodeID,
		AgentTaskID: &created.ID, Type: "agent-task.queued", Actor: originator,
		CausationID: created.CausationID, CorrelationID: created.CorrelationID,
		IdempotencyKey: "agent-task-queued:" + created.ID.String()}); err != nil {
		return nil, err
	}
	if err := enqueueCollaborationEventTx(ctx, tx, created.Tenant, "agent-task", created.ID,
		"agent-task.queued.v1", created, "agent-task-queued:"+created.ID.String()); err != nil {
		return nil, err
	}
	return created, nil
}

func snapshotTaskTeamTx(ctx context.Context, tx pgx.Tx, runID, teamID uuid.UUID) error {
	var exists bool
	if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration_run_team_snapshots
		WHERE run_id=$1 AND team_id=$2)`, runID, teamID).Scan(&exists); err != nil || exists {
		return err
	}
	team, err := scanCollaborationTeam(tx.QueryRow(ctx, `SELECT `+collaborationTeamColumns+`
		FROM teams WHERE id=$1`, teamID))
	if err != nil {
		return err
	}
	rows, err := tx.Query(ctx, `SELECT `+collaborationMemberColumns+`
		FROM team_members WHERE team_id=$1 AND archived_at IS NULL ORDER BY role,id`, teamID)
	if err != nil {
		return err
	}
	for rows.Next() {
		member, scanErr := scanCollaborationMember(rows)
		if scanErr != nil {
			rows.Close()
			return scanErr
		}
		team.Members = append(team.Members, *member)
	}
	if err = rows.Err(); err != nil {
		rows.Close()
		return err
	}
	rows.Close()
	snapshot, err := json.Marshal(team)
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `INSERT INTO orchestration_run_team_snapshots
		(run_id,team_id,tenant,namespace,snapshot) VALUES($1,$2,$3,$4,$5)
		ON CONFLICT(run_id,team_id) DO NOTHING`, runID, team.ID, team.Tenant, team.Namespace, snapshot)
	return err
}

func createTaskRunNodeTx(ctx context.Context, tx pgx.Tx, issue *controlmodel.Issue,
	task *controlmodel.AgentTask, rerunOf *uuid.UUID) error {
	task.OrchestrationRunID = uuid.New()
	mode := controlmodel.RunModeDirect
	if task.TeamID != nil || task.LeaderTask {
		mode = controlmodel.RunModeAdaptive
	}
	_, err := tx.Exec(ctx, `INSERT INTO orchestration_runs
		(id,tenant,namespace,root_issue_id,mode,rerun_of_run_id,trigger_type,trigger_ref,state,
		 version,created_by_type,created_by_ref,started_at)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,1,$10,$11,now())`, task.OrchestrationRunID,
		issue.Tenant, issue.Namespace, issue.ID, mode, rerunOf, task.TriggerType,
		task.CausationID, controlmodel.RunRunning, task.Originator.Type, nullStr(task.Originator.Ref))
	if err != nil {
		return err
	}
	return createTaskNodeTx(ctx, tx, task)
}

func createTaskNodeTx(ctx context.Context, tx pgx.Tx, task *controlmodel.AgentTask) error {
	task.RunNodeID = uuid.New()
	nodeType := controlmodel.RunNodeAgent
	// TeamID records the roster/policy context for both coordinators and workers.
	// Only a leader task owns a Team coordinator barrier; delegated members are
	// ordinary agent nodes inside the same adaptive Run.
	if task.LeaderTask {
		nodeType = controlmodel.RunNodeTeam
	}
	_, err := tx.Exec(ctx, `INSERT INTO orchestration_run_nodes
		(id,run_id,tenant,namespace,node_key,type,role,issue_id,state,iteration,version)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,1,1)`, task.RunNodeID,
		task.OrchestrationRunID, task.Tenant, task.Namespace, "task:"+task.ID.String(),
		nodeType, nullStr(task.TeamRole), task.IssueID, controlmodel.RunNodeReady)
	return err
}

func appendRunEventTx(ctx context.Context, tx pgx.Tx, event *controlmodel.RunEvent) error {
	if event.ID == uuid.Nil {
		event.ID = uuid.New()
	}
	if _, err := tx.Exec(ctx, `SELECT id FROM orchestration_runs WHERE id=$1 FOR UPDATE`, event.RunID); err != nil {
		return err
	}
	if event.IdempotencyKey != "" {
		var existing uuid.UUID
		err := tx.QueryRow(ctx, `SELECT id FROM orchestration_run_events WHERE run_id=$1 AND idempotency_key=$2`, event.RunID, event.IdempotencyKey).Scan(&existing)
		if err == nil {
			return nil
		}
		if err != pgx.ErrNoRows {
			return err
		}
	}
	if err := tx.QueryRow(ctx, `SELECT COALESCE(MAX(sequence),0)+1 FROM orchestration_run_events WHERE run_id=$1`, event.RunID).Scan(&event.Sequence); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `INSERT INTO orchestration_run_events
		(id,run_id,tenant,namespace,sequence,node_id,agent_task_id,attempt_id,type,actor_type,
		 actor_ref,payload,causation_id,correlation_id,idempotency_key)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15)`, event.ID,
		event.RunID, event.Tenant, event.Namespace, event.Sequence, event.NodeID,
		event.AgentTaskID, event.AttemptID, event.Type, event.Actor.Type, nullStr(event.Actor.Ref),
		nullJSON(event.Payload), nullStr(event.CausationID), nullStr(event.CorrelationID),
		nullStr(event.IdempotencyKey))
	return err
}

func routeAgentTaskInputTx(ctx context.Context, tx pgx.Tx, issue *controlmodel.Issue,
	comment *controlmodel.Comment, target store.CommentTarget) (*controlmodel.AgentTask, *controlmodel.AgentTaskInput, bool, error) {
	// PostgreSQL text values cannot contain NUL. Length-prefix both variable
	// components so different Agent/role pairs cannot alias the advisory lock.
	lockKey := fmt.Sprintf("%s|%d:%s|%d:%s", issue.ID, len(target.AgentRef), target.AgentRef, len(target.TeamRole), target.TeamRole)
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1,0))`, lockKey); err != nil {
		return nil, nil, false, fmt.Errorf("lock AgentTask route: %w", err)
	}
	var task *controlmodel.AgentTask
	coalesced := false
	task, err := scanAgentTask(tx.QueryRow(ctx, `SELECT `+agentTaskColumns+`
		FROM agent_tasks WHERE issue_id=$1 AND agent_id=$2
		AND COALESCE(team_role,'')=$3 AND status=$4 ORDER BY created_at LIMIT 1 FOR UPDATE`,
		issue.ID, target.AgentRef, target.TeamRole, controlmodel.AgentTaskQueued))
	if err == nil {
		coalesced = true
	} else if err != store.ErrNotFound {
		return nil, nil, false, fmt.Errorf("find queued AgentTask: %w", err)
	} else {
		parentID := target.ParentTaskID
		if parentID == nil {
			var activeID uuid.UUID
			err = tx.QueryRow(ctx, `SELECT id FROM agent_tasks WHERE issue_id=$1 AND agent_id=$2
				AND COALESCE(team_role,'')=$3 AND status IN ($4,$5,$6)
				ORDER BY created_at DESC LIMIT 1`, issue.ID, target.AgentRef, target.TeamRole,
				controlmodel.AgentTaskDispatched, controlmodel.AgentTaskRunning,
				controlmodel.AgentTaskWaiting).Scan(&activeID)
			if err == nil {
				parentID = &activeID
			} else if err != pgx.ErrNoRows {
				return nil, nil, false, fmt.Errorf("find active AgentTask: %w", err)
			}
		}
		task, err = createAgentTaskTx(ctx, tx, issue, target.AgentRef, "comment",
			&comment.ID, target.TeamID, target.TeamRole,
			target.RouteType == controlmodel.RouteTeamLeader, comment.Author, parentID, nil)
		if err != nil {
			return nil, nil, false, fmt.Errorf("create routed AgentTask: %w", err)
		}
	}
	var sequence int64
	if err := tx.QueryRow(ctx, `SELECT COALESCE(MAX(sequence),0)+1 FROM agent_task_inputs
		WHERE task_id=$1`, task.ID).Scan(&sequence); err != nil {
		return nil, nil, false, fmt.Errorf("allocate AgentTask input sequence: %w", err)
	}
	input, err := scanTaskInput(tx.QueryRow(ctx, `INSERT INTO agent_task_inputs
		(id,tenant,namespace,task_id,comment_id,comment_version,sequence,state)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
		ON CONFLICT (task_id,comment_id,comment_version) DO UPDATE SET task_id=EXCLUDED.task_id
		RETURNING `+taskInputColumns, uuid.New(), task.Tenant, task.Namespace, task.ID,
		comment.ID, comment.Version, sequence, controlmodel.TaskInputPlanned))
	if err != nil {
		return nil, nil, false, fmt.Errorf("create AgentTask input: %w", err)
	}
	return task, input, coalesced, nil
}

func (r *collaborationRepo) GetAgentTask(ctx context.Context, id uuid.UUID) (*controlmodel.AgentTask, error) {
	task, err := scanAgentTask(r.pool.QueryRow(ctx, `SELECT `+agentTaskColumns+` FROM agent_tasks WHERE id=$1`, id))
	if err != nil {
		return nil, err
	}
	inputs, err := listTaskInputs(ctx, r.pool, id)
	if err != nil {
		return nil, err
	}
	task.Inputs = inputs
	return task, nil
}

func (r *collaborationRepo) CreateRunAgentTask(ctx context.Context, req store.RunTaskRequest) (*controlmodel.AgentTask, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var tenant, namespace string
	var nodeRunID uuid.UUID
	var nodeIssueID *uuid.UUID
	if err := tx.QueryRow(ctx, `SELECT n.run_id,n.issue_id,r.tenant,r.namespace FROM orchestration_run_nodes n
		JOIN orchestration_runs r ON r.id=n.run_id WHERE n.id=$1 AND r.id=$2 FOR UPDATE OF n,r`,
		req.NodeID, req.RunID).Scan(&nodeRunID, &nodeIssueID, &tenant, &namespace); err != nil {
		return nil, collaborationScanError(err)
	}
	if nodeIssueID == nil || *nodeIssueID != req.IssueID || req.AgentRef == "" {
		return nil, store.ErrConflict
	}
	var issuePriorityValue string
	if err := tx.QueryRow(ctx, `SELECT priority FROM issues WHERE id=$1 AND tenant=$2 AND namespace=$3`,
		req.IssueID, tenant, namespace).Scan(&issuePriorityValue); err != nil {
		return nil, collaborationScanError(err)
	}
	priority := req.Priority
	if priority == 0 {
		priority = issuePriority(issuePriorityValue)
	}
	taskID := uuid.New()
	var runtimeBinding json.RawMessage
	if req.RuntimeCandidate != nil {
		runtimeBinding, _ = json.Marshal(controlmodel.RuntimeDispatchSnapshot{
			Binding: req.RuntimeCandidate.Binding, Capabilities: req.RuntimeCandidate.RequiredCapabilities,
			SecurityConstraints: req.RuntimeCandidate.SecurityConstraints, SelectionSource: "node"})
	}
	accountable := ""
	if req.Originator.Type == controlmodel.ActorHuman {
		accountable = req.Originator.Ref
	}
	task, err := scanAgentTask(tx.QueryRow(ctx, `INSERT INTO agent_tasks
		(id,tenant,namespace,issue_id,orchestration_run_id,run_node_id,agent_id,status,priority,
		 trigger_type,team_id,team_role,is_leader_task,originator_type,originator_ref,
		 accountable_human_ref,causation_id,correlation_id,runtime_binding,version)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,'orchestration_node',$10,$11,$12,$13,$14,$15,$16,$17,$18,1)
		RETURNING `+agentTaskColumns, taskID, tenant, namespace, req.IssueID, req.RunID, req.NodeID,
		req.AgentRef, controlmodel.AgentTaskQueued, priority, req.TeamID, nullStr(req.TeamRole), req.Leader,
		req.Originator.Type, nullStr(req.Originator.Ref), nullStr(accountable), req.NodeID.String(), req.RunID.String(), nullJSON(runtimeBinding)))
	if err != nil {
		return nil, err
	}
	if err := appendRunEventTx(ctx, tx, &controlmodel.RunEvent{RunID: req.RunID, Tenant: tenant,
		Namespace: namespace, NodeID: &req.NodeID, AgentTaskID: &task.ID, Type: "agent-task.queued",
		Actor: req.Originator, CausationID: req.NodeID.String(), CorrelationID: req.RunID.String(),
		IdempotencyKey: "agent-task-queued:" + task.ID.String()}); err != nil {
		return nil, err
	}
	if err := enqueueCollaborationEventTx(ctx, tx, tenant, "agent-task", task.ID,
		"agent-task.queued.v1", task, "agent-task-queued:"+task.ID.String()); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return task, nil
}

func (r *collaborationRepo) ListAgentTasks(ctx context.Context, filter store.AgentTaskFilter) ([]*controlmodel.AgentTask, error) {
	limit := filter.Limit
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `SELECT `+agentTaskColumns+` FROM agent_tasks WHERE
		($1='' OR tenant=$1) AND ($2='' OR namespace=$2)
		AND ($3::uuid=$11::uuid OR issue_id=$3) AND ($4='' OR agent_id=$4)
		AND ($5::uuid=$11::uuid OR team_id=$5) AND ($6='' OR status=$6)
		AND ($9::uuid=$11::uuid OR orchestration_run_id=$9)
		AND ($10::uuid=$11::uuid OR run_node_id=$10)
		AND (NOT $12 OR issue_access_allowed(issue_id,$13::text[]))
		ORDER BY priority DESC,created_at LIMIT $7 OFFSET $8`, filter.Tenant,
		filter.Namespace, filter.IssueID, filter.AgentRef, filter.TeamID, filter.Status,
		limit, maxInt(filter.Offset, 0), filter.RunID, filter.NodeID, uuid.Nil, store.WorkAccessFrom(ctx).Restricted, store.WorkAccessFrom(ctx).Refs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.AgentTask, 0)
	for rows.Next() {
		task, err := scanAgentTask(rows)
		if err != nil {
			return nil, err
		}
		inputs, err := listTaskInputs(ctx, r.pool, task.ID)
		if err != nil {
			return nil, err
		}
		task.Inputs = inputs
		out = append(out, task)
	}
	return out, rows.Err()
}

func (r *collaborationRepo) ClaimAgentTask(ctx context.Context, claim store.TaskClaim) (*controlmodel.AgentTask, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	task, err := scanAgentTask(tx.QueryRow(ctx, `SELECT `+agentTaskColumns+`
		FROM agent_tasks WHERE id=$1 FOR UPDATE`, claim.TaskID))
	if err != nil {
		return nil, err
	}
	if task.Status != controlmodel.AgentTaskQueued || claim.ExpectedVersion > 0 && task.Version != claim.ExpectedVersion {
		return nil, store.ErrConflict
	}
	task, err = scanAgentTask(tx.QueryRow(ctx, `UPDATE agent_tasks SET status=$2,
		runtime_binding=$3,session_id=$4,error_code=NULL,error_message=NULL,
		version=version+1,dispatched_at=now()
		WHERE id=$1 RETURNING `+agentTaskColumns, task.ID, controlmodel.AgentTaskDispatched,
		nullJSON(claim.RuntimeBinding), nullStr(claim.SessionID)))
	if err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `UPDATE agent_task_inputs SET state=$2,delivered_at=now()
		WHERE task_id=$1 AND state=$3`, task.ID, controlmodel.TaskInputDelivered,
		controlmodel.TaskInputPlanned); err != nil {
		return nil, err
	}
	inputs, err := listTaskInputs(ctx, tx, task.ID)
	if err != nil {
		return nil, err
	}
	task.Inputs = inputs
	if err := enqueueCollaborationEventTx(ctx, tx, task.Tenant, "agent-task", task.ID,
		"agent-task.dispatched.v1", task, fmt.Sprintf("agent-task-dispatched:%s:%d", task.ID, task.Version)); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return task, nil
}

func (r *collaborationRepo) AcknowledgeTaskInputs(ctx context.Context, taskID uuid.UUID, inputIDs []uuid.UUID) ([]controlmodel.AgentTaskInput, error) {
	if len(inputIDs) > 0 {
		if _, err := r.pool.Exec(ctx, `UPDATE agent_task_inputs SET state=$2,
			acknowledged_at=now() WHERE task_id=$1 AND id=ANY($3::uuid[])
			AND state IN ($4,$2)`, taskID, controlmodel.TaskInputAcknowledged,
			inputIDs, controlmodel.TaskInputDelivered); err != nil {
			return nil, err
		}
	}
	return listTaskInputs(ctx, r.pool, taskID)
}

func (r *collaborationRepo) FailTaskInputDelivery(ctx context.Context, taskID uuid.UUID, inputIDs []uuid.UUID, message string, maxAttempts int) ([]controlmodel.AgentTaskInput, error) {
	if maxAttempts <= 0 {
		maxAttempts = 5
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	task, err := scanAgentTask(tx.QueryRow(ctx, `SELECT `+agentTaskColumns+` FROM agent_tasks WHERE id=$1 FOR UPDATE`, taskID))
	if err != nil {
		return nil, err
	}
	rows, err := tx.Query(ctx, `SELECT `+taskInputColumns+` FROM agent_task_inputs
		WHERE task_id=$1 AND id=ANY($2::uuid[]) FOR UPDATE`, taskID, inputIDs)
	if err != nil {
		return nil, err
	}
	selected := make([]*controlmodel.AgentTaskInput, 0)
	for rows.Next() {
		input, scanErr := scanTaskInput(rows)
		if scanErr != nil {
			rows.Close()
			return nil, scanErr
		}
		selected = append(selected, input)
	}
	rows.Close()
	for _, input := range selected {
		if input.State == controlmodel.TaskInputProcessed || input.State == controlmodel.TaskInputDeferred {
			continue
		}
		input.Attempts++
		state := controlmodel.TaskInputRetrying
		var next *time.Time
		if int(input.Attempts) >= maxAttempts {
			state = controlmodel.TaskInputDeadLetter
		} else {
			value := time.Now().UTC().Add(time.Duration(1<<minIntPG(int(input.Attempts), 8)) * time.Second)
			next = &value
		}
		if _, err := tx.Exec(ctx, `UPDATE agent_task_inputs SET state=$2,attempts=$3,
			last_error=$4,next_attempt_at=$5 WHERE id=$1`, input.ID, state, input.Attempts, message, next); err != nil {
			return nil, err
		}
		if state == controlmodel.TaskInputDeadLetter && task.AccountableHumanRef != "" {
			if _, err := tx.Exec(ctx, `INSERT INTO inbox_items
				(id,tenant,namespace,recipient_type,recipient_ref,type,severity,issue_id,
				 actor_type,title,body,dedupe_key)
				VALUES ($1,$2,$3,'human',$4,'dead_letter','error',$5,'system',
				 'Agent input delivery failed',$6,$7)
				ON CONFLICT (tenant,dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING`,
				uuid.New(), task.Tenant, task.Namespace, task.AccountableHumanRef, task.IssueID,
				message, "dead-letter:"+input.ID.String()); err != nil {
				return nil, err
			}
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return listTaskInputs(ctx, r.pool, taskID)
}

func (r *collaborationRepo) ReplayDeadLetterInputs(ctx context.Context, taskID uuid.UUID, inputIDs []uuid.UUID, actor controlmodel.Actor) (*controlmodel.AgentTask, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	source, err := scanAgentTask(tx.QueryRow(ctx, `SELECT `+agentTaskColumns+` FROM agent_tasks WHERE id=$1 FOR UPDATE`, taskID))
	if err != nil {
		return nil, err
	}
	var count int
	if err := tx.QueryRow(ctx, `SELECT count(*) FROM agent_task_inputs WHERE task_id=$1
		AND id=ANY($2::uuid[]) AND state=$3`, taskID, inputIDs, controlmodel.TaskInputDeadLetter).Scan(&count); err != nil {
		return nil, err
	}
	if count == 0 {
		return nil, store.ErrConflict
	}
	issue, err := scanIssue(tx.QueryRow(ctx, `SELECT `+issueColumns+` FROM issues WHERE id=$1`, source.IssueID))
	if err != nil {
		return nil, err
	}
	retryOf := source.ID
	replay, err := createAgentTaskTx(ctx, tx, issue, source.AgentRef, "manual_replay",
		source.TriggerCommentID, source.TeamID, source.TeamRole, source.LeaderTask,
		actor, &source.ID, &retryOf)
	if err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO agent_task_inputs
		(id,tenant,namespace,task_id,comment_id,comment_version,sequence,state)
		SELECT gen_random_uuid(),tenant,namespace,$3,comment_id,comment_version,
		row_number() OVER (ORDER BY sequence),$4 FROM agent_task_inputs
		WHERE task_id=$1 AND id=ANY($2::uuid[]) AND state=$5`, taskID, inputIDs,
		replay.ID, controlmodel.TaskInputPlanned, controlmodel.TaskInputDeadLetter); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return replay, nil
}

func minIntPG(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func (r *collaborationRepo) RequeueRetryableInputs(ctx context.Context, now time.Time, limit int) ([]uuid.UUID, error) {
	if limit <= 0 {
		limit = 100
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	rows, err := tx.Query(ctx, `SELECT task_id FROM agent_task_inputs
		WHERE state=$1 AND next_attempt_at<=$2 ORDER BY next_attempt_at,id LIMIT $3 FOR UPDATE SKIP LOCKED`,
		controlmodel.TaskInputRetrying, now, limit)
	if err != nil {
		return nil, err
	}
	ids := make([]uuid.UUID, 0)
	seen := map[uuid.UUID]bool{}
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		if !seen[id] {
			seen[id] = true
			ids = append(ids, id)
		}
	}
	rows.Close()
	if len(ids) > 0 {
		if _, err := tx.Exec(ctx, `UPDATE agent_task_inputs SET state=$1,next_attempt_at=NULL
			WHERE task_id=ANY($2::uuid[]) AND state=$3 AND next_attempt_at<=$4`,
			controlmodel.TaskInputPlanned, ids, controlmodel.TaskInputRetrying, now); err != nil {
			return nil, err
		}
		if _, err := tx.Exec(ctx, `UPDATE agent_tasks SET status=$1,version=version+1,dispatched_at=NULL
			WHERE id=ANY($2::uuid[]) AND status=$3`, controlmodel.AgentTaskQueued, ids,
			controlmodel.AgentTaskDispatched); err != nil {
			return nil, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return ids, nil
}

func (r *collaborationRepo) StartAgentTask(ctx context.Context, id uuid.UUID, expectedVersion int64) (*controlmodel.AgentTask, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	task, err := scanAgentTask(tx.QueryRow(ctx, `SELECT `+agentTaskColumns+` FROM agent_tasks WHERE id=$1 FOR UPDATE`, id))
	if err != nil {
		return nil, err
	}
	if expectedVersion > 0 && task.Version != expectedVersion || !controlmodel.CanTransitionAgentTask(task.Status, controlmodel.AgentTaskRunning) {
		return nil, store.ErrConflict
	}
	if task.CurrentAttemptID != nil {
		attempt, loadErr := scanExecutionAttempt(tx.QueryRow(ctx, `SELECT `+executionAttemptColumns+` FROM execution_attempts WHERE id=$1 FOR UPDATE`, *task.CurrentAttemptID))
		if loadErr != nil {
			return nil, loadErr
		}
		if attempt.State == controlmodel.ExecutionAssigned {
			if _, err = tx.Exec(ctx, `UPDATE execution_attempts SET state=$2,version=version+1,updated_at=now() WHERE id=$1`, attempt.ID, controlmodel.ExecutionPreparing); err != nil {
				return nil, err
			}
			attempt.State = controlmodel.ExecutionPreparing
		}
		if attempt.State == controlmodel.ExecutionWaiting {
			attempt.State = controlmodel.ExecutionPreparing
		}
		if !controlmodel.CanTransitionExecutionAttempt(attempt.State, controlmodel.ExecutionRunning) {
			return nil, store.ErrConflict
		}
		if _, err = tx.Exec(ctx, `UPDATE execution_attempts SET state=$2,version=version+1,updated_at=now(),
			started_at=COALESCE(started_at,now()),heartbeat_at=now() WHERE id=$1`, attempt.ID, controlmodel.ExecutionRunning); err != nil {
			return nil, err
		}
		if err = appendRunEventTx(ctx, tx, &controlmodel.RunEvent{RunID: task.OrchestrationRunID,
			Tenant: task.Tenant, Namespace: task.Namespace, NodeID: &task.RunNodeID,
			AgentTaskID: &task.ID, AttemptID: &attempt.ID, Type: "attempt.running",
			Actor:          controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef},
			IdempotencyKey: "attempt-running:" + attempt.ID.String()}); err != nil {
			return nil, err
		}
	}
	if _, err = tx.Exec(ctx, `UPDATE orchestration_run_nodes SET state=$2,version=version+1,
		updated_at=now(),started_at=COALESCE(started_at,now()) WHERE id=$1 AND state=$3`,
		task.RunNodeID, controlmodel.RunNodeRunning, controlmodel.RunNodeReady); err != nil {
		return nil, err
	}
	task, err = scanAgentTask(tx.QueryRow(ctx, `UPDATE agent_tasks SET status=$2,version=version+1,
		started_at=COALESCE(started_at,now()) WHERE id=$1 RETURNING `+agentTaskColumns, id, controlmodel.AgentTaskRunning))
	if err != nil {
		return nil, err
	}
	if err = startIssueForAgentTaskTx(ctx, tx, task); err != nil {
		return nil, err
	}
	if _, err = tx.Exec(ctx, `UPDATE orchestration_runs SET state=$2,wait_reason=NULL,version=version+1,
		updated_at=now() WHERE id=$1 AND state=$3`, task.OrchestrationRunID, controlmodel.RunRunning,
		controlmodel.RunWaiting); err != nil {
		return nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	return task, nil
}

func startIssueForAgentTaskTx(ctx context.Context, tx pgx.Tx, task *controlmodel.AgentTask) error {
	issue, err := scanIssue(tx.QueryRow(ctx, `SELECT `+issueColumns+` FROM issues WHERE id=$1 FOR UPDATE`, task.IssueID))
	if err != nil {
		return err
	}
	if !store.AgentTaskMayAdvanceIssueLifecycle(issue, task) {
		return nil
	}
	if issue.Status != controlmodel.IssueBacklog && issue.Status != controlmodel.IssueTodo && !store.AgentTaskReopensReview(issue, task) &&
		(issue.Status != controlmodel.IssueBlocked || task.LeaderTask && issue.ParentIssueID != nil) {
		return nil
	}
	previousStatus := issue.Status
	issue, err = scanIssue(tx.QueryRow(ctx, `UPDATE issues SET status=$2,version=version+1,
		updated_at=now() WHERE id=$1 RETURNING `+issueColumns, issue.ID, controlmodel.IssueInProgress))
	if err != nil {
		return err
	}
	actor := controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
	details, _ := json.Marshal(map[string]string{"from": string(previousStatus),
		"to": string(controlmodel.IssueInProgress), "reason": "agent task started"})
	if err = insertActivityTx(ctx, tx, &controlmodel.Activity{Tenant: issue.Tenant,
		Namespace: issue.Namespace, IssueID: &issue.ID, Actor: actor,
		Action: "issue.status_changed", ObjectType: "issue", ObjectRef: issue.ID.String(),
		CausationID: task.ID.String(), CorrelationID: task.CorrelationID, Details: details}); err != nil {
		return err
	}
	if err := notifyIssueInboxTx(ctx, tx, issue, previousStatus, actor, "Agent task started", nil); err != nil {
		return err
	}
	return enqueueCollaborationEventTx(ctx, tx, issue.Tenant, "issue", issue.ID,
		"issue.status-changed.v1", map[string]any{"issue": issue, "previousStatus": previousStatus},
		fmt.Sprintf("issue-status:%s:%d", issue.ID, issue.Version))
}

func (r *collaborationRepo) CompleteAgentTask(ctx context.Context, id uuid.UUID, completion store.TaskCompletion) (*controlmodel.AgentTask, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	task, err := scanAgentTask(tx.QueryRow(ctx, `SELECT `+agentTaskColumns+`
		FROM agent_tasks WHERE id=$1 FOR UPDATE`, id))
	if err != nil {
		return nil, err
	}
	if completion.ExpectedVersion > 0 && task.Version != completion.ExpectedVersion || !controlmodel.CanTransitionAgentTask(task.Status, controlmodel.AgentTaskCompleted) {
		return nil, store.ErrConflict
	}
	var attempt *controlmodel.ExecutionAttempt
	if task.CurrentAttemptID != nil {
		attempt, err = scanExecutionAttempt(tx.QueryRow(ctx, `SELECT `+executionAttemptColumns+`
			FROM execution_attempts WHERE id=$1 FOR UPDATE`, *task.CurrentAttemptID))
		if err != nil {
			return nil, err
		}
		if completion.AttemptID != uuid.Nil && completion.AttemptID != attempt.ID ||
			completion.DispatchGeneration > 0 && completion.DispatchGeneration != attempt.DispatchGeneration ||
			completion.LeaseToken != "" && (completion.LeaseToken != attempt.LeaseToken || completion.FencingToken != attempt.FencingToken) ||
			!controlmodel.CanTransitionExecutionAttempt(attempt.State, controlmodel.ExecutionSucceeded) {
			return nil, store.ErrConflict
		}
	}
	actor := controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
	if completion.ResponseCommentID != nil {
		comment, loadErr := scanComment(tx.QueryRow(ctx, `SELECT `+commentColumns+`
			FROM comments WHERE id=$1`, *completion.ResponseCommentID))
		if loadErr != nil {
			return nil, loadErr
		}
		if comment.IssueID != task.IssueID || comment.SourceTaskID == nil ||
			*comment.SourceTaskID != task.ID || comment.Type != controlmodel.CommentResult {
			return nil, store.ErrConflict
		}
		actor = comment.Author
	}
	if len(completion.ProcessedInputIDs) > 0 {
		if _, err := tx.Exec(ctx, `UPDATE agent_task_inputs SET state=$2,processed_at=now(),
			response_comment_id=COALESCE($3,response_comment_id)
			WHERE task_id=$1 AND id=ANY($4::uuid[])`, id, controlmodel.TaskInputProcessed,
			completion.ResponseCommentID, completion.ProcessedInputIDs); err != nil {
			return nil, err
		}
	}
	if len(completion.DeferredInputIDs) > 0 {
		if _, err := tx.Exec(ctx, `UPDATE agent_task_inputs SET state=$2
			WHERE task_id=$1 AND id=ANY($3::uuid[])`, id, controlmodel.TaskInputDeferred,
			completion.DeferredInputIDs); err != nil {
			return nil, err
		}
	}
	var uncovered int
	if err := tx.QueryRow(ctx, `SELECT count(*) FROM agent_task_inputs WHERE task_id=$1
		AND state IN ($2,$3,$4)`, id, controlmodel.TaskInputPlanned,
		controlmodel.TaskInputDelivered, controlmodel.TaskInputAcknowledged).Scan(&uncovered); err != nil {
		return nil, err
	}
	if uncovered > 0 {
		return nil, store.ErrConflict
	}
	if attempt != nil {
		usage := store.AttemptUsage(completion.Usage, completion.Result)
		attempt, err = scanExecutionAttempt(tx.QueryRow(ctx, `UPDATE execution_attempts SET state=$2,
			result=$3,checkpoint=COALESCE($4,checkpoint),usage=COALESCE($5,usage),lease_expires_at=NULL,
			version=version+1,updated_at=now(),completed_at=now() WHERE id=$1 AND version=$6
			RETURNING `+executionAttemptColumns, attempt.ID, controlmodel.ExecutionSucceeded,
			nullJSON(completion.Result), nullJSON(completion.Checkpoint), nullJSON(usage), attempt.Version))
		if err != nil {
			return nil, err
		}
		if err = mergeRunUsageTx(ctx, tx, task.OrchestrationRunID, usage); err != nil {
			return nil, err
		}
	}
	task, err = scanAgentTask(tx.QueryRow(ctx, `UPDATE agent_tasks SET status=$2,result=$3,
		error_code=NULL,error_message=NULL,version=version+1,completed_at=now()
		WHERE id=$1 RETURNING `+agentTaskColumns,
		id, controlmodel.AgentTaskCompleted, nullJSON(completion.Result)))
	if err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `UPDATE issues SET version=version+1,updated_at=now() WHERE id=$1`, task.IssueID); err != nil {
		return nil, err
	}
	if err := reconcileCompletedTaskTx(ctx, tx, task, attempt, completion.Result, actor); err != nil {
		return nil, err
	}
	if err := insertActivityTx(ctx, tx, &controlmodel.Activity{Tenant: task.Tenant, Namespace: task.Namespace,
		IssueID: &task.IssueID, Actor: actor, Action: "agent_task.completed", ObjectType: "agent_task",
		ObjectRef: task.ID.String(), CausationID: task.CausationID, CorrelationID: task.CorrelationID}); err != nil {
		return nil, err
	}
	if err := enqueueCollaborationEventTx(ctx, tx, task.Tenant, "agent-task", task.ID,
		"agent-task.completed.v1", task, fmt.Sprintf("agent-task-completed:%s:%d", task.ID, task.Version)); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return task, nil
}

func (r *collaborationRepo) FailAgentTask(ctx context.Context, id uuid.UUID, expectedVersion int64, code, message string, result ...json.RawMessage) (*controlmodel.AgentTask, error) {
	var partial json.RawMessage
	if len(result) > 0 {
		partial = result[0]
	}
	return r.transitionAgentTask(ctx, id, expectedVersion, controlmodel.AgentTaskFailed, partial, code, message)
}

func (r *collaborationRepo) CancelAgentTask(ctx context.Context, id uuid.UUID, expectedVersion int64) (*controlmodel.AgentTask, error) {
	return r.transitionAgentTask(ctx, id, expectedVersion, controlmodel.AgentTaskCancelled, nil, "", "")
}

func (r *collaborationRepo) RetryAgentTask(ctx context.Context, id uuid.UUID, actor controlmodel.Actor) (*controlmodel.AgentTask, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	failed, err := scanAgentTask(tx.QueryRow(ctx, `SELECT `+agentTaskColumns+`
		FROM agent_tasks WHERE id=$1 FOR UPDATE`, id))
	if err != nil {
		return nil, err
	}
	if failed.Status != controlmodel.AgentTaskFailed {
		return nil, store.ErrConflict
	}
	issue, err := scanIssue(tx.QueryRow(ctx, `SELECT `+issueColumns+` FROM issues WHERE id=$1`, failed.IssueID))
	if err != nil {
		return nil, err
	}
	retryID := failed.ID
	retry, err := createAgentTaskTx(ctx, tx, issue, failed.AgentRef, "retry",
		failed.TriggerCommentID, failed.TeamID, failed.TeamRole, failed.LeaderTask,
		actor, nil, &retryID)
	if err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO agent_task_inputs
		(id,tenant,namespace,task_id,comment_id,comment_version,sequence,state)
		SELECT gen_random_uuid(),tenant,namespace,$2,comment_id,comment_version,sequence,$3
		FROM agent_task_inputs WHERE task_id=$1 ORDER BY sequence`, id, retry.ID,
		controlmodel.TaskInputPlanned); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return retry, nil
}

func (r *collaborationRepo) ReconcileRunningInputs(ctx context.Context, taskID uuid.UUID, _ time.Time) error {
	var exists bool
	if err := r.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM agent_tasks WHERE id=$1)`, taskID).Scan(&exists); err != nil {
		return err
	}
	if !exists {
		return store.ErrNotFound
	}
	return nil
}

func (r *collaborationRepo) transitionAgentTask(ctx context.Context, id uuid.UUID,
	expectedVersion int64, target controlmodel.AgentTaskStatus, result json.RawMessage,
	code, message string) (*controlmodel.AgentTask, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	task, err := scanAgentTask(tx.QueryRow(ctx, `SELECT `+agentTaskColumns+`
		FROM agent_tasks WHERE id=$1 FOR UPDATE`, id))
	if err != nil {
		return nil, err
	}
	if expectedVersion > 0 && task.Version != expectedVersion || !controlmodel.CanTransitionAgentTask(task.Status, target) {
		return nil, store.ErrConflict
	}
	var startedAt, completedAt *time.Time
	if target == controlmodel.AgentTaskRunning {
		now := time.Now().UTC()
		startedAt = &now
	}
	if controlmodel.IsAgentTaskTerminal(target) {
		now := time.Now().UTC()
		completedAt = &now
	}
	task, err = scanAgentTask(tx.QueryRow(ctx, `UPDATE agent_tasks SET status=$2,
		result=$3,error_code=$4,error_message=$5,version=version+1,
		started_at=COALESCE(started_at,$6),completed_at=$7 WHERE id=$1
		RETURNING `+agentTaskColumns, id, target, nullJSON(result), nullStr(code),
		nullStr(message), startedAt, completedAt))
	if err != nil {
		return nil, err
	}
	if target == controlmodel.AgentTaskFailed {
		if err := notifyTaskFailureInboxTx(ctx, tx, task); err != nil {
			return nil, err
		}
	}

	eventType := "agent-task." + string(target) + ".v1"
	if err := enqueueCollaborationEventTx(ctx, tx, task.Tenant, "agent-task", task.ID,
		eventType, task, fmt.Sprintf("agent-task-%s:%s:%d", target, task.ID, task.Version)); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return task, nil
}

type taskInputQuerier interface {
	Query(context.Context, string, ...any) (pgx.Rows, error)
}

func listTaskInputs(ctx context.Context, q taskInputQuerier, taskID uuid.UUID) ([]controlmodel.AgentTaskInput, error) {
	rows, err := q.Query(ctx, `SELECT `+taskInputColumns+` FROM agent_task_inputs
		WHERE task_id=$1 ORDER BY sequence,id`, taskID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]controlmodel.AgentTaskInput, 0)
	for rows.Next() {
		input, err := scanTaskInput(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, *input)
	}
	return out, rows.Err()
}

func issuePriority(priority string) int32 {
	switch priority {
	case "urgent":
		return 100
	case "high":
		return 75
	case "medium":
		return 50
	case "low":
		return 25
	default:
		return 0
	}
}

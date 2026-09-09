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
	"strings"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// CompleteAgentTaskWithComment commits the externally visible result and the
// terminal task/input state in one transaction. This is the only completion
// path used when the caller has not already persisted a response Comment.
func (r *collaborationRepo) CompleteAgentTaskWithComment(ctx context.Context, id uuid.UUID, completion store.TaskCompletion, comment *controlmodel.Comment, targets []store.CommentTarget) (*controlmodel.AgentTask, *controlmodel.Comment, error) {
	if comment == nil || strings.TrimSpace(comment.Content) == "" {
		return nil, nil, fmt.Errorf("result comment content is required")
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	task, err := scanAgentTask(tx.QueryRow(ctx, `SELECT `+agentTaskColumns+` FROM agent_tasks WHERE id=$1 FOR UPDATE`, id))
	if err != nil {
		return nil, nil, err
	}
	if completion.ExpectedVersion > 0 && task.Version != completion.ExpectedVersion || !controlmodel.CanTransitionAgentTask(task.Status, controlmodel.AgentTaskCompleted) {
		return nil, nil, store.ErrConflict
	}
	var attempt *controlmodel.ExecutionAttempt
	if task.CurrentAttemptID != nil {
		attempt, err = scanExecutionAttempt(tx.QueryRow(ctx, `SELECT `+executionAttemptColumns+` FROM execution_attempts WHERE id=$1 FOR UPDATE`, *task.CurrentAttemptID))
		if err != nil {
			return nil, nil, err
		}
		if completion.AttemptID != uuid.Nil && completion.AttemptID != attempt.ID ||
			completion.DispatchGeneration > 0 && completion.DispatchGeneration != attempt.DispatchGeneration ||
			completion.LeaseToken != "" && (completion.LeaseToken != attempt.LeaseToken || completion.FencingToken != attempt.FencingToken) ||
			!controlmodel.CanTransitionExecutionAttempt(attempt.State, controlmodel.ExecutionSucceeded) {
			return nil, nil, store.ErrConflict
		}
	}
	inputs, err := listTaskInputs(ctx, tx, id)
	if err != nil {
		return nil, nil, err
	}
	processed, deferred := uuidSetPG(completion.ProcessedInputIDs), uuidSetPG(completion.DeferredInputIDs)
	for _, input := range inputs {
		if !processed[input.ID] && !deferred[input.ID] && input.State != controlmodel.TaskInputProcessed && input.State != controlmodel.TaskInputDeferred {
			return nil, nil, store.ErrConflict
		}
	}
	issue, err := scanIssue(tx.QueryRow(ctx, `SELECT `+issueColumns+` FROM issues WHERE id=$1 FOR UPDATE`, task.IssueID))
	if err != nil {
		return nil, nil, err
	}
	created := comment
	created.ID = nonNilUUIDPG(created.ID)
	created.Tenant, created.Namespace, created.IssueID = issue.Tenant, issue.Namespace, issue.ID
	created.SourceTaskID = &task.ID
	// A leader may finish a decision turn by yielding to delegated work.
	// Keep that informational comment distinct from the worker's deliverable.
	if task.TriggerType == controlmodel.AgentTaskReviewComment {
		created.Type = controlmodel.CommentGeneral
	} else if !task.LeaderTask || created.Type != controlmodel.CommentStatus {
		created.Type = controlmodel.CommentResult
	}
	if attempt != nil {
		created.SourceAttemptID = &attempt.ID
	}
	if created.ParentID != nil {
		parent, loadErr := scanComment(tx.QueryRow(ctx, `SELECT `+commentColumns+` FROM comments WHERE id=$1`, *created.ParentID))
		if loadErr != nil || parent.IssueID != issue.ID {
			return nil, nil, store.ErrNotFound
		}
		created.ThreadRootID = parent.ThreadRootID
	} else {
		created.ThreadRootID = created.ID
	}
	created, err = scanComment(tx.QueryRow(ctx, `INSERT INTO comments
		(id,tenant,namespace,issue_id,parent_id,thread_root_id,author_type,author_ref,
		 content,type,source_task_id,source_attempt_id,version)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,1) RETURNING `+commentColumns,
		created.ID, created.Tenant, created.Namespace, created.IssueID, created.ParentID,
		created.ThreadRootID, created.Author.Type, nullStr(created.Author.Ref), created.Content,
		created.Type, created.SourceTaskID, created.SourceAttemptID))
	if err != nil {
		return nil, nil, err
	}
	routes := make([]controlmodel.CommentRoute, 0, len(targets))
	seen := map[string]bool{}
	for _, target := range targets {
		key := string(target.TargetType) + "\x00" + target.TargetRef + "\x00" + target.TeamRole
		if seen[key] {
			continue
		}
		seen[key] = true
		route := controlmodel.CommentRoute{ID: uuid.New(), Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: issue.ID, CommentID: created.ID, CommentVersion: created.Version, TargetType: target.TargetType, TargetRef: target.TargetRef, RouteType: target.RouteType}
		switch {
		case target.Blocked:
			route.Outcome, route.ReasonCode = controlmodel.RouteBlocked, target.ReasonCode
			if task.AccountableHumanRef != "" {
				if _, err := tx.Exec(ctx, `INSERT INTO inbox_items
					(id,tenant,namespace,recipient_type,recipient_ref,type,severity,issue_id,
					 comment_id,actor_type,actor_ref,title,body,dedupe_key)
					VALUES ($1,$2,$3,$4,$5,'routing_blocked','warning',$6,$7,$8,$9,$10,$11,$12)
					ON CONFLICT (tenant,dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING`,
					uuid.New(), issue.Tenant, issue.Namespace, controlmodel.AssigneeHuman,
					task.AccountableHumanRef, issue.ID, created.ID, created.Author.Type,
					nullStr(created.Author.Ref), "Agent collaboration blocked", target.ReasonCode,
					"route-blocked:"+created.ID.String()+":"+target.TargetRef); err != nil {
					return nil, nil, err
				}
			}
		case target.TargetType == controlmodel.AssigneeHuman:
			route.Outcome = controlmodel.RouteQueued
			itemType, title := store.CommentInboxType(target.RouteType, true), issue.Title
			if target.RouteType == controlmodel.RouteReviewRequest {
				itemType, title = "review_request", "Review requested: "+issue.Title
			}
			if _, err := tx.Exec(ctx, `INSERT INTO inbox_items
				(id,tenant,namespace,recipient_type,recipient_ref,type,severity,issue_id,
				 comment_id,actor_type,actor_ref,title,body,dedupe_key)
				VALUES ($1,$2,$3,$4,$5,$6,'attention',$7,$8,$9,$10,$11,$12,$13)
				ON CONFLICT (tenant,dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING`,
				uuid.New(), issue.Tenant, issue.Namespace, controlmodel.AssigneeHuman,
				target.TargetRef, itemType, issue.ID, created.ID, created.Author.Type,
				nullStr(created.Author.Ref), title, created.Content,
				"comment:"+created.ID.String()+":"+target.TargetRef); err != nil {
				return nil, nil, err
			}
		case target.AgentRef == task.AgentRef:
			route.Outcome, route.ReasonCode = controlmodel.RouteSuppressed, "self_trigger"
		default:
			routed, _, coalesced, routeErr := routeAgentTaskInputTx(ctx, tx, issue, created, target)
			if routeErr != nil {
				return nil, nil, routeErr
			}
			route.TaskID = &routed.ID
			if coalesced {
				route.Outcome = controlmodel.RouteCoalesced
			} else {
				route.Outcome = controlmodel.RouteQueued
			}
		}
		if err := tx.QueryRow(ctx, `INSERT INTO comment_routes
			(id,tenant,namespace,issue_id,comment_id,comment_version,target_type,target_ref,route_type,
			 outcome,task_id,reason_code) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
			 RETURNING created_at`, route.ID, route.Tenant, route.Namespace, route.IssueID,
			route.CommentID, route.CommentVersion, route.TargetType, route.TargetRef, route.RouteType,
			route.Outcome, route.TaskID, nullStr(route.ReasonCode)).Scan(&route.CreatedAt); err != nil {
			return nil, nil, err
		}
		routes = append(routes, route)
	}
	if len(completion.ProcessedInputIDs) > 0 {
		if _, err := tx.Exec(ctx, `UPDATE agent_task_inputs SET state=$2,processed_at=now(),response_comment_id=$3
			WHERE task_id=$1 AND id=ANY($4::uuid[])`, id, controlmodel.TaskInputProcessed, created.ID, completion.ProcessedInputIDs); err != nil {
			return nil, nil, err
		}
	}
	if len(completion.DeferredInputIDs) > 0 {
		if _, err := tx.Exec(ctx, `UPDATE agent_task_inputs SET state=$2 WHERE task_id=$1 AND id=ANY($3::uuid[])`, id, controlmodel.TaskInputDeferred, completion.DeferredInputIDs); err != nil {
			return nil, nil, err
		}
	}
	if attempt != nil {
		usage := store.AttemptUsage(completion.Usage, completion.Result)
		attempt, err = scanExecutionAttempt(tx.QueryRow(ctx, `UPDATE execution_attempts SET state=$2,
			result=$3,checkpoint=COALESCE($4,checkpoint),usage=COALESCE($5,usage),lease_expires_at=NULL,version=version+1,
			updated_at=now(),completed_at=now() WHERE id=$1 AND version=$6 RETURNING `+executionAttemptColumns,
			attempt.ID, controlmodel.ExecutionSucceeded, nullJSON(completion.Result),
			nullJSON(completion.Checkpoint), nullJSON(usage), attempt.Version))
		if err != nil {
			return nil, nil, err
		}
		if err := mergeRunUsageTx(ctx, tx, task.OrchestrationRunID, usage); err != nil {
			return nil, nil, err
		}
	}
	task, err = scanAgentTask(tx.QueryRow(ctx, `UPDATE agent_tasks SET status=$2,result=$3,
		error_code=NULL,error_message=NULL,version=version+1,completed_at=now()
		WHERE id=$1 RETURNING `+agentTaskColumns,
		id, controlmodel.AgentTaskCompleted, nullJSON(completion.Result)))
	if err != nil {
		return nil, nil, err
	}
	if _, err := tx.Exec(ctx, `UPDATE issues SET version=version+1,updated_at=now() WHERE id=$1`, issue.ID); err != nil {
		return nil, nil, err
	}
	if err := reconcileCompletedTaskTx(ctx, tx, task, attempt, completion.Result, created.Author); err != nil {
		return nil, nil, err
	}
	if err := insertActivityTx(ctx, tx, &controlmodel.Activity{Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: &issue.ID, Actor: created.Author, Action: "agent_task.completed", ObjectType: "agent_task", ObjectRef: task.ID.String(), CausationID: task.CausationID, CorrelationID: task.CorrelationID}); err != nil {
		return nil, nil, err
	}
	if err := enqueueCollaborationEventTx(ctx, tx, task.Tenant, "comment", created.ID, "comment.created.v1", map[string]any{"comment": created, "routes": routes}, "comment-created:"+created.ID.String()); err != nil {
		return nil, nil, err
	}
	if err := enqueueCollaborationEventTx(ctx, tx, task.Tenant, "agent-task", task.ID, "agent-task.completed.v1", task, fmt.Sprintf("agent-task-completed:%s:%d", task.ID, task.Version)); err != nil {
		return nil, nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, nil, err
	}
	created.Routes = routes
	return task, created, nil
}

func mergeRunUsageTx(ctx context.Context, tx pgx.Tx, runID uuid.UUID, incoming json.RawMessage) error {
	if len(incoming) == 0 {
		return nil
	}
	var current json.RawMessage
	if err := tx.QueryRow(ctx, `SELECT usage FROM orchestration_runs WHERE id=$1 FOR UPDATE`, runID).Scan(&current); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `UPDATE orchestration_runs SET usage=$2,updated_at=now() WHERE id=$1`,
		runID, store.MergeUsage(current, incoming))
	return err
}

func reconcileCompletedTaskTx(ctx context.Context, tx pgx.Tx, task *controlmodel.AgentTask,
	attempt *controlmodel.ExecutionAttempt, output json.RawMessage, actor controlmodel.Actor) error {
	node, err := scanNode(tx.QueryRow(ctx, `SELECT `+nodeCols+` FROM orchestration_run_nodes WHERE id=$1 FOR UPDATE`, task.RunNodeID))
	if err != nil {
		return err
	}
	next := controlmodel.RunNodeSucceeded
	if node.Type == controlmodel.RunNodeTeam && task.LeaderTask && task.TriggerType != controlmodel.AgentTaskReviewComment {
		next = controlmodel.RunNodeWaiting
	}
	coordinatorAlreadyTerminal := node.Type == controlmodel.RunNodeTeam && task.LeaderTask &&
		controlmodel.IsRunNodeTerminal(node.State)
	if !coordinatorAlreadyTerminal {
		if node.State == controlmodel.RunNodeReady {
			if _, err := tx.Exec(ctx, `UPDATE orchestration_run_nodes SET state=$2,started_at=COALESCE(started_at,now()),
				version=version+1,updated_at=now() WHERE id=$1`, node.ID, controlmodel.RunNodeRunning); err != nil {
				return err
			}
			node.State = controlmodel.RunNodeRunning
			node.Version++
		}
		if !controlmodel.CanTransitionRunNode(node.State, next) {
			return store.ErrConflict
		}
		node, err = scanNode(tx.QueryRow(ctx, `UPDATE orchestration_run_nodes SET state=$2,output=$3,wait_reason=NULL,
			version=version+1,updated_at=now(),started_at=COALESCE(started_at,now()),
			completed_at=CASE WHEN $2='succeeded' THEN now() ELSE completed_at END
			WHERE id=$1 RETURNING `+nodeCols, node.ID, next, nullJSON(output)))
		if err != nil {
			return err
		}
	}
	if attempt != nil {
		if err := appendRunEventTx(ctx, tx, &controlmodel.RunEvent{RunID: task.OrchestrationRunID,
			Tenant: task.Tenant, Namespace: task.Namespace, NodeID: &task.RunNodeID,
			AgentTaskID: &task.ID, AttemptID: &attempt.ID, Type: "attempt.succeeded", Actor: actor,
			CausationID: task.CausationID, CorrelationID: task.CorrelationID,
			IdempotencyKey: "attempt-succeeded:" + attempt.ID.String()}); err != nil {
			return err
		}
	}
	if !coordinatorAlreadyTerminal {
		payload, _ := json.Marshal(map[string]any{"output": output})
		if err := appendRunEventTx(ctx, tx, &controlmodel.RunEvent{RunID: task.OrchestrationRunID,
			Tenant: task.Tenant, Namespace: task.Namespace, NodeID: &task.RunNodeID,
			AgentTaskID: &task.ID, Type: "node." + string(next), Actor: actor,
			Payload:     payload,
			CausationID: task.CausationID, CorrelationID: task.CorrelationID,
			IdempotencyKey: "node-" + string(next) + ":" + node.ID.String()}); err != nil {
			return err
		}
	}
	// The leader may explicitly converge the coordinator while its provider
	// process is still running. Preserve that terminal decision when the
	// Runtime Host subsequently records physical task completion.
	if coordinatorAlreadyTerminal {
		return nil
	}
	if next != controlmodel.RunNodeSucceeded {
		_, err = tx.Exec(ctx, `UPDATE orchestration_runs SET state=$2,wait_reason='team_coordinator',
			version=version+1,updated_at=now() WHERE id=$1 AND state=$3`, task.OrchestrationRunID,
			controlmodel.RunWaiting, controlmodel.RunRunning)
		return err
	}
	var active int
	if err := tx.QueryRow(ctx, `SELECT COUNT(*) FROM orchestration_run_nodes WHERE run_id=$1
		AND state NOT IN('succeeded','skipped','failed','cancelled')`, task.OrchestrationRunID).Scan(&active); err != nil {
		return err
	}
	if active == 0 {
		if _, err := tx.Exec(ctx, `UPDATE orchestration_runs SET state=$2,output=$3,wait_reason=NULL,version=version+1,
			updated_at=now(),completed_at=now() WHERE id=$1 AND state IN($4,$5)`, task.OrchestrationRunID,
			controlmodel.RunSucceeded, nullJSON(output), controlmodel.RunRunning, controlmodel.RunWaiting); err != nil {
			return err
		}
		if err := appendRunEventTx(ctx, tx, &controlmodel.RunEvent{RunID: task.OrchestrationRunID,
			Tenant: task.Tenant, Namespace: task.Namespace, Type: "run.succeeded", Actor: actor,
			CausationID: task.CausationID, CorrelationID: task.CorrelationID,
			IdempotencyKey: "run-succeeded:" + task.OrchestrationRunID.String()}); err != nil {
			return err
		}
		return requestIssueReviewForCompletedRunTx(ctx, tx, task, actor)
	}
	return nil
}

func requestIssueReviewForCompletedRunTx(ctx context.Context, tx pgx.Tx, task *controlmodel.AgentTask, actor controlmodel.Actor) error {
	var rootIssueID uuid.UUID
	var parentRunID *uuid.UUID
	if err := tx.QueryRow(ctx, `SELECT root_issue_id,parent_run_id FROM orchestration_runs WHERE id=$1`, task.OrchestrationRunID).
		Scan(&rootIssueID, &parentRunID); err != nil {
		return err
	}
	if parentRunID != nil {
		return nil
	}
	current, err := scanIssue(tx.QueryRow(ctx, `SELECT `+issueColumns+` FROM issues WHERE id=$1`, rootIssueID))
	if err != nil {
		return err
	}
	if !store.AgentTaskMayAdvanceIssueLifecycle(current, task) {
		return nil
	}
	issue, err := scanIssue(tx.QueryRow(ctx, `UPDATE issues SET
		status=CASE WHEN completion_policy=$2 THEN $3 ELSE $4 END,
		resolved_at=CASE WHEN completion_policy=$2 THEN now() ELSE resolved_at END,
		version=version+1,updated_at=now()
		WHERE id=$1 AND status=$5 RETURNING `+issueColumns, rootIssueID,
		controlmodel.IssueCompletionAutomatic, controlmodel.IssueDone,
		controlmodel.IssueInReview, controlmodel.IssueInProgress))
	if err == store.ErrNotFound {
		return nil
	}
	if err != nil {
		return err
	}
	reason := "execution completed; awaiting acceptance"
	if issue.Status == controlmodel.IssueDone {
		reason = "automatic Endpoint Job execution completed"
	}
	details, _ := json.Marshal(map[string]string{"from": string(controlmodel.IssueInProgress),
		"to": string(issue.Status), "reason": reason})
	if err = insertActivityTx(ctx, tx, &controlmodel.Activity{Tenant: issue.Tenant,
		Namespace: issue.Namespace, IssueID: &issue.ID, Actor: actor,
		Action: "issue.status_changed", ObjectType: "issue", ObjectRef: issue.ID.String(),
		Details: details}); err != nil {
		return err
	}
	if err := notifyIssueInboxTx(ctx, tx, issue, controlmodel.IssueInProgress, actor, reason, task); err != nil {
		return err
	}
	return enqueueCollaborationEventTx(ctx, tx, issue.Tenant, "issue", issue.ID,
		"issue.status-changed.v1", map[string]any{"issue": issue, "previousStatus": controlmodel.IssueInProgress},
		fmt.Sprintf("issue-status:%s:%d", issue.ID, issue.Version))
}

func (r *collaborationRepo) ClaimAgentTaskWithAttempt(ctx context.Context, claim store.TaskClaim, execution *controlmodel.ExecutionAttempt) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	if execution == nil || execution.BackendKind == "" {
		return nil, nil, store.ErrConflict
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	task, err := scanAgentTask(tx.QueryRow(ctx, `SELECT `+agentTaskColumns+` FROM agent_tasks WHERE id=$1 FOR UPDATE`, claim.TaskID))
	if err != nil {
		return nil, nil, err
	}
	if task.Status != controlmodel.AgentTaskQueued || claim.ExpectedVersion > 0 && task.Version != claim.ExpectedVersion {
		return nil, nil, store.ErrConflict
	}
	execution.ID = nonNilUUIDPG(execution.ID)
	task, err = scanAgentTask(tx.QueryRow(ctx, `UPDATE agent_tasks SET status=$2,
		runtime_binding=$3,session_id=$4,current_attempt_id=$5,error_code=NULL,error_message=NULL,
		version=version+1,dispatched_at=now()
		WHERE id=$1 RETURNING `+agentTaskColumns, task.ID, controlmodel.AgentTaskDispatched,
		nullJSON(claim.RuntimeBinding), nullStr(claim.SessionID), execution.ID))
	if err != nil {
		return nil, nil, err
	}
	if _, err := tx.Exec(ctx, `UPDATE agent_task_inputs SET state=$2,delivered_at=now()
		WHERE task_id=$1 AND state=$3`, task.ID, controlmodel.TaskInputDelivered, controlmodel.TaskInputPlanned); err != nil {
		return nil, nil, err
	}
	if execution.Attempt <= 0 || execution.DispatchGeneration <= 0 {
		var nextAttempt int32
		var nextDispatchGeneration int64
		if err := tx.QueryRow(ctx, `SELECT COALESCE(MAX(attempt),0)+1,
			COALESCE(MAX(dispatch_generation),0)+1 FROM execution_attempts WHERE agent_task_id=$1`, task.ID).
			Scan(&nextAttempt, &nextDispatchGeneration); err != nil {
			return nil, nil, err
		}
		if execution.Attempt <= 0 {
			execution.Attempt = nextAttempt
		}
		if execution.DispatchGeneration <= 0 {
			execution.DispatchGeneration = nextDispatchGeneration
		}
	}
	execution.AgentTaskID, execution.Tenant, execution.Namespace = task.ID, task.Tenant, task.Namespace
	execution.RunID, execution.NodeID = task.OrchestrationRunID, task.RunNodeID
	execution.RuntimeBinding = append(json.RawMessage(nil), claim.RuntimeBinding...)
	var dispatch controlmodel.RuntimeDispatchSnapshot
	if json.Unmarshal(claim.RuntimeBinding, &dispatch) == nil {
		execution.AgentID, execution.BindingID = dispatch.Binding.AgentID, dispatch.Binding.BindingID
	}
	if execution.State == "" {
		execution.State = controlmodel.ExecutionQueued
	}
	created, err := scanExecutionAttempt(tx.QueryRow(ctx, `INSERT INTO execution_attempts (
		id,agent_task_id,tenant,namespace,run_id,node_id,attempt,dispatch_generation,backend_kind,
		runtime_binding,runtime_profile_name,runtime_pool_name,required_capabilities,host_id,
		agent_instance_id,managed_owner_ref,managed_agent_ref,session_id,turn_id,provider_session_id,
		workspace_key,state,lease_owner,lease_token,fencing_token,lease_expires_at,heartbeat_at,
		cancel_requested_at,checkpoint,result,failure_code,failure_message,usage,agent_id,binding_id,version,started_at,completed_at)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23,$24,$25,$26,$27,$28,$29,$30,$31,$32,$33,$34,$35,1,$36,$37)
		RETURNING `+executionAttemptColumns, execution.ID, execution.AgentTaskID,
		execution.Tenant, execution.Namespace, execution.RunID, execution.NodeID, execution.Attempt,
		execution.DispatchGeneration, execution.BackendKind, execution.RuntimeBinding,
		nullStr(execution.RuntimeProfileName), nullStr(execution.RuntimePoolName),
		nullJSON(execution.RequiredCapabilities), execution.HostID, execution.AgentInstanceID,
		nullStr(execution.ManagedOwnerRef), nullStr(execution.ManagedAgentRef), nullStr(execution.SessionID),
		nullStr(execution.TurnID), nullStr(execution.ProviderSessionID), nullStr(execution.WorkspaceKey), execution.State,
		nullStr(execution.LeaseOwner), nullStr(execution.LeaseToken), execution.FencingToken,
		execution.LeaseExpiresAt, execution.HeartbeatAt, execution.CancelRequestedAt,
		nullJSON(execution.Checkpoint), nullJSON(execution.Result), nullStr(execution.FailureCode),
		nullStr(execution.FailureMessage), nullJSON(execution.Usage), nullableUUID(execution.AgentID), nullableUUID(execution.BindingID), execution.StartedAt,
		execution.CompletedAt))
	if err != nil {
		return nil, nil, err
	}
	if err := appendRunEventTx(ctx, tx, &controlmodel.RunEvent{RunID: task.OrchestrationRunID,
		Tenant: task.Tenant, Namespace: task.Namespace, NodeID: &task.RunNodeID,
		AgentTaskID: &task.ID, AttemptID: &created.ID, Type: "attempt.queued",
		Actor: controlmodel.Actor{Type: controlmodel.ActorSystem}, CausationID: task.CausationID,
		CorrelationID: task.CorrelationID, IdempotencyKey: "attempt-queued:" + created.ID.String()}); err != nil {
		return nil, nil, err
	}
	if err := enqueueCollaborationEventTx(ctx, tx, task.Tenant, "agent-task", task.ID,
		"agent-task.dispatched.v1", task, fmt.Sprintf("agent-task-dispatched:%s:%d", task.ID, task.Version)); err != nil {
		return nil, nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, nil, err
	}
	task.Inputs, err = listTaskInputs(ctx, r.pool, task.ID)
	if err != nil {
		return nil, nil, err
	}
	return task, created, nil
}

func uuidSetPG(ids []uuid.UUID) map[uuid.UUID]bool {
	out := make(map[uuid.UUID]bool, len(ids))
	for _, id := range ids {
		out[id] = true
	}
	return out
}

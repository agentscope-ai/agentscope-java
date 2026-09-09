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
	"fmt"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// FailAgentTaskWithAttempt fences and commits the physical and logical failure
// in one transaction. A failed Attempt is immutable; retry creates a new Task
// or Attempt according to the orchestration policy.
func (r *collaborationRepo) FailAgentTaskWithAttempt(ctx context.Context, id uuid.UUID, failure store.TaskFailure) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	task, err := scanAgentTask(tx.QueryRow(ctx, `SELECT `+agentTaskColumns+` FROM agent_tasks WHERE id=$1 FOR UPDATE`, id))
	if err != nil {
		return nil, nil, err
	}
	if task.CurrentAttemptID == nil || failure.AttemptID == uuid.Nil || *task.CurrentAttemptID != failure.AttemptID {
		return nil, nil, store.ErrConflict
	}
	attempt, err := scanExecutionAttempt(tx.QueryRow(ctx, `SELECT `+executionAttemptColumns+` FROM execution_attempts WHERE id=$1 FOR UPDATE`, failure.AttemptID))
	if err != nil {
		return nil, nil, err
	}
	if attempt.State == controlmodel.ExecutionFailed && task.Status == controlmodel.AgentTaskFailed &&
		attempt.FailureCode == failure.Code && attempt.FailureMessage == failure.Message {
		return task, attempt, nil
	}
	if failure.ExpectedVersion > 0 && task.Version != failure.ExpectedVersion ||
		failure.DispatchGeneration > 0 && attempt.DispatchGeneration != failure.DispatchGeneration ||
		failure.LeaseToken != "" && (attempt.LeaseToken != failure.LeaseToken || attempt.FencingToken != failure.FencingToken) ||
		!controlmodel.CanTransitionExecutionAttempt(attempt.State, controlmodel.ExecutionFailed) ||
		!controlmodel.CanTransitionAgentTask(task.Status, controlmodel.AgentTaskFailed) {
		return nil, nil, store.ErrConflict
	}
	abortManaged := store.ManagedAttemptNeedsAbort(task, attempt, failure.Code)
	attempt, err = scanExecutionAttempt(tx.QueryRow(ctx, `UPDATE execution_attempts SET state=$2,
		checkpoint=COALESCE($3,checkpoint),usage=COALESCE($4,usage),failure_code=$5,failure_message=$6,
		result=COALESCE($7,result),lease_expires_at=NULL,version=version+1,updated_at=now(),completed_at=now()
		WHERE id=$1 RETURNING `+executionAttemptColumns, attempt.ID, controlmodel.ExecutionFailed,
		nullJSON(failure.Checkpoint), nullJSON(failure.Usage), nullStr(failure.Code), nullStr(failure.Message), nullJSON(failure.Result)))
	if err != nil {
		return nil, nil, err
	}
	if err = mergeRunUsageTx(ctx, tx, task.OrchestrationRunID, failure.Usage); err != nil {
		return nil, nil, err
	}
	task, err = scanAgentTask(tx.QueryRow(ctx, `UPDATE agent_tasks SET status=$2,error_code=$3,
		error_message=$4,result=COALESCE($5,result),version=version+1,completed_at=now() WHERE id=$1 RETURNING `+agentTaskColumns,
		task.ID, controlmodel.AgentTaskFailed, nullStr(failure.Code), nullStr(failure.Message), nullJSON(failure.Result)))
	if err != nil {
		return nil, nil, err
	}
	// A terminal failure must not leave delivery work waiting on an immutable
	// Task. Keep successful/deferred dispositions, and retain why the rest failed.
	if _, err = tx.Exec(ctx, `UPDATE agent_task_inputs SET state=$2,last_error=$3,next_attempt_at=NULL
		WHERE task_id=$1 AND state NOT IN ('processed','deferred','dead_letter','blocked')`,
		task.ID, controlmodel.TaskInputBlocked, failure.Message); err != nil {
		return nil, nil, err
	}
	actor := controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
	if err = appendRunEventTx(ctx, tx, &controlmodel.RunEvent{RunID: task.OrchestrationRunID,
		Tenant: task.Tenant, Namespace: task.Namespace, NodeID: &task.RunNodeID, AgentTaskID: &task.ID,
		AttemptID: &attempt.ID, Type: "attempt.failed", Actor: actor,
		Payload:     []byte(fmt.Sprintf(`{"code":%q,"message":%q}`, failure.Code, failure.Message)),
		CausationID: task.CausationID, CorrelationID: task.CorrelationID,
		IdempotencyKey: "attempt-failed:" + attempt.ID.String()}); err != nil {
		return nil, nil, err
	}
	if err = notifyTaskFailureInboxTx(ctx, tx, task); err != nil {
		return nil, nil, err
	}
	if err = enqueueCollaborationEventTx(ctx, tx, task.Tenant, "agent-task", task.ID,
		"agent-task.failed.v1", task, fmt.Sprintf("agent-task-failed:%s:%d", task.ID, task.Version)); err != nil {
		return nil, nil, err
	}
	if abortManaged {
		if err = enqueueCollaborationEventTx(ctx, tx, task.Tenant, "execution-attempt", attempt.ID,
			"execution-attempt.abort-managed.v1", attempt, "abort-managed-attempt:"+attempt.ID.String()); err != nil {
			return nil, nil, err
		}
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, nil, err
	}
	return task, attempt, nil
}

// RequeueAgentTaskAfterAttemptFailure atomically fences a retryable physical
// failure and returns the logical task to its durable queue. The failed
// Attempt remains immutable; the next dispatch always creates a new Attempt.
func (r *collaborationRepo) RequeueAgentTaskAfterAttemptFailure(ctx context.Context, id uuid.UUID, failure store.TaskFailure) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	task, err := scanAgentTask(tx.QueryRow(ctx, `SELECT `+agentTaskColumns+` FROM agent_tasks WHERE id=$1 FOR UPDATE`, id))
	if err != nil {
		return nil, nil, err
	}
	if task.CurrentAttemptID == nil || failure.AttemptID == uuid.Nil || *task.CurrentAttemptID != failure.AttemptID {
		return nil, nil, store.ErrConflict
	}
	attempt, err := scanExecutionAttempt(tx.QueryRow(ctx, `SELECT `+executionAttemptColumns+` FROM execution_attempts WHERE id=$1 FOR UPDATE`, failure.AttemptID))
	if err != nil {
		return nil, nil, err
	}
	if failure.ExpectedVersion > 0 && task.Version != failure.ExpectedVersion ||
		failure.DispatchGeneration > 0 && attempt.DispatchGeneration != failure.DispatchGeneration ||
		failure.LeaseToken != "" && (attempt.LeaseToken != failure.LeaseToken || attempt.FencingToken != failure.FencingToken) {
		return nil, nil, store.ErrConflict
	}
	if attempt.State == controlmodel.ExecutionFailed && task.Status == controlmodel.AgentTaskQueued {
		return task, attempt, nil
	}
	if controlmodel.IsExecutionAttemptTerminal(attempt.State) || controlmodel.IsAgentTaskTerminal(task.Status) ||
		!controlmodel.CanTransitionExecutionAttempt(attempt.State, controlmodel.ExecutionFailed) ||
		!controlmodel.CanTransitionAgentTask(task.Status, controlmodel.AgentTaskQueued) {
		return nil, nil, store.ErrConflict
	}
	abortManaged := store.ManagedAttemptNeedsAbort(task, attempt, failure.Code)
	attempt, err = scanExecutionAttempt(tx.QueryRow(ctx, `UPDATE execution_attempts SET state=$2,
		checkpoint=COALESCE($3,checkpoint),usage=COALESCE($4,usage),failure_code=$5,failure_message=$6,
		lease_expires_at=NULL,version=version+1,updated_at=now(),completed_at=now()
		WHERE id=$1 RETURNING `+executionAttemptColumns, attempt.ID, controlmodel.ExecutionFailed,
		nullJSON(failure.Checkpoint), nullJSON(failure.Usage), nullStr(failure.Code), nullStr(failure.Message)))
	if err != nil {
		return nil, nil, err
	}
	if err = mergeRunUsageTx(ctx, tx, task.OrchestrationRunID, failure.Usage); err != nil {
		return nil, nil, err
	}
	task, err = scanAgentTask(tx.QueryRow(ctx, `UPDATE agent_tasks SET status=$2,error_code=$3,
		error_message=$4,version=version+1,completed_at=NULL WHERE id=$1 RETURNING `+agentTaskColumns,
		task.ID, controlmodel.AgentTaskQueued, nullStr(failure.Code), nullStr(failure.Message)))
	if err != nil {
		return nil, nil, err
	}
	actor := controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "runtime-scheduler"}
	if err = appendRunEventTx(ctx, tx, &controlmodel.RunEvent{RunID: task.OrchestrationRunID,
		Tenant: task.Tenant, Namespace: task.Namespace, NodeID: &task.RunNodeID, AgentTaskID: &task.ID,
		AttemptID: &attempt.ID, Type: "attempt.retry_queued", Actor: actor,
		Payload:     []byte(fmt.Sprintf(`{"code":%q,"message":%q}`, failure.Code, failure.Message)),
		CausationID: task.CausationID, CorrelationID: task.CorrelationID,
		IdempotencyKey: "attempt-retry-queued:" + attempt.ID.String()}); err != nil {
		return nil, nil, err
	}
	if err = enqueueCollaborationEventTx(ctx, tx, task.Tenant, "agent-task", task.ID,
		"agent-task.queued.v1", task, "agent-task-retry-queued:"+attempt.ID.String()); err != nil {
		return nil, nil, err
	}
	if abortManaged {
		if err = enqueueCollaborationEventTx(ctx, tx, task.Tenant, "execution-attempt", attempt.ID,
			"execution-attempt.abort-managed.v1", attempt, "abort-managed-attempt:"+attempt.ID.String()); err != nil {
			return nil, nil, err
		}
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, nil, err
	}
	return task, attempt, nil
}

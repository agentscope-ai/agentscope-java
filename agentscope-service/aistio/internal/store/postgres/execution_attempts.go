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
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type executionAttemptRepo struct{ pool *pgxpool.Pool }

const executionAttemptColumns = `id,agent_task_id,tenant,namespace,run_id,node_id,attempt,
	dispatch_generation,backend_kind,runtime_binding,runtime_profile_name,runtime_pool_name,
	required_capabilities,host_id,agent_instance_id,managed_owner_ref,managed_agent_ref,session_id,
	turn_id,provider_session_id,workspace_key,state,lease_owner,lease_token,fencing_token,
	lease_expires_at,heartbeat_at,cancel_requested_at,checkpoint,result,failure_code,failure_message,
	usage,agent_id,binding_id,version,created_at,updated_at,started_at,completed_at`

func scanExecutionAttempt(row scannable) (*controlmodel.ExecutionAttempt, error) {
	execution := &controlmodel.ExecutionAttempt{}
	var profile, poolName, managedOwner, managedAgent, sessionID, turnID *string
	var providerSessionID, workspaceKey, leaseOwner, leaseToken *string
	var failureCode, failureMessage *string
	var agentID, bindingID *uuid.UUID
	var runtimeBinding, requiredCapabilities, checkpoint, result, usage []byte
	err := row.Scan(&execution.ID, &execution.AgentTaskID, &execution.Tenant, &execution.Namespace,
		&execution.RunID, &execution.NodeID, &execution.Attempt, &execution.DispatchGeneration,
		&execution.BackendKind, &runtimeBinding, &profile, &poolName, &requiredCapabilities,
		&execution.HostID, &execution.AgentInstanceID, &managedOwner, &managedAgent, &sessionID,
		&turnID, &providerSessionID, &workspaceKey,
		&execution.State, &leaseOwner, &leaseToken, &execution.FencingToken,
		&execution.LeaseExpiresAt, &execution.HeartbeatAt, &execution.CancelRequestedAt,
		&checkpoint, &result, &failureCode, &failureMessage, &usage, &agentID, &bindingID,
		&execution.Version, &execution.CreatedAt, &execution.UpdatedAt, &execution.StartedAt,
		&execution.CompletedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	execution.RuntimeProfileName = deref(profile)
	execution.RuntimePoolName = deref(poolName)
	execution.RuntimeBinding = runtimeBinding
	execution.RequiredCapabilities = requiredCapabilities
	execution.ManagedOwnerRef = deref(managedOwner)
	execution.ManagedAgentRef = deref(managedAgent)
	execution.SessionID = deref(sessionID)
	execution.TurnID = deref(turnID)
	execution.ProviderSessionID = deref(providerSessionID)
	execution.WorkspaceKey = deref(workspaceKey)
	execution.LeaseOwner = deref(leaseOwner)
	execution.LeaseToken = deref(leaseToken)
	execution.Checkpoint = checkpoint
	execution.Result = result
	execution.Usage = usage
	if agentID != nil {
		execution.AgentID = *agentID
	}
	if bindingID != nil {
		execution.BindingID = *bindingID
	}
	execution.FailureCode = deref(failureCode)
	execution.FailureMessage = deref(failureMessage)
	return execution, nil
}

func (r *executionAttemptRepo) Create(ctx context.Context, execution *controlmodel.ExecutionAttempt) (*controlmodel.ExecutionAttempt, error) {
	if execution == nil || execution.AgentTaskID == uuid.Nil || execution.BackendKind == "" {
		return nil, fmt.Errorf("execution attempt requires agentTaskId and backendKind")
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var taskRunID, taskNodeID uuid.UUID
	if err := tx.QueryRow(ctx, `SELECT orchestration_run_id,run_node_id FROM agent_tasks WHERE id=$1 FOR UPDATE`, execution.AgentTaskID).Scan(&taskRunID, &taskNodeID); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	if execution.RunID == uuid.Nil {
		execution.RunID = taskRunID
	}
	if execution.NodeID == uuid.Nil {
		execution.NodeID = taskNodeID
	}
	if taskRunID != execution.RunID || taskNodeID != execution.NodeID {
		return nil, store.ErrConflict
	}
	if execution.Attempt <= 0 {
		if err := tx.QueryRow(ctx, `SELECT COALESCE(MAX(attempt),0)+1 FROM execution_attempts WHERE agent_task_id=$1`, execution.AgentTaskID).Scan(&execution.Attempt); err != nil {
			return nil, err
		}
	}
	if execution.ID == uuid.Nil {
		execution.ID = uuid.New()
	}
	if execution.State == "" {
		execution.State = controlmodel.ExecutionQueued
	}
	if execution.DispatchGeneration <= 0 {
		execution.DispatchGeneration = 1
	}
	if len(execution.RuntimeBinding) == 0 {
		execution.RuntimeBinding = json.RawMessage(`{}`)
	}
	created, err := scanExecutionAttempt(tx.QueryRow(ctx, `INSERT INTO execution_attempts (
		id,agent_task_id,tenant,namespace,run_id,node_id,attempt,dispatch_generation,backend_kind,
		runtime_binding,runtime_profile_name,runtime_pool_name,required_capabilities,host_id,
		agent_instance_id,managed_owner_ref,managed_agent_ref,session_id,turn_id,provider_session_id,
		workspace_key,state,lease_owner,lease_token,fencing_token,lease_expires_at,heartbeat_at,
		cancel_requested_at,checkpoint,result,failure_code,failure_message,usage,agent_id,binding_id,version,started_at,completed_at)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23,$24,$25,$26,$27,$28,$29,$30,$31,$32,$33,$34,$35,1,$36,$37)
		RETURNING `+executionAttemptColumns,
		execution.ID, execution.AgentTaskID, execution.Tenant, execution.Namespace, execution.RunID,
		execution.NodeID, execution.Attempt, execution.DispatchGeneration, execution.BackendKind,
		execution.RuntimeBinding, nullStr(execution.RuntimeProfileName), nullStr(execution.RuntimePoolName),
		nullJSON(execution.RequiredCapabilities), execution.HostID, execution.AgentInstanceID,
		nullStr(execution.ManagedOwnerRef), nullStr(execution.ManagedAgentRef), nullStr(execution.SessionID),
		nullStr(execution.TurnID), nullStr(execution.ProviderSessionID), nullStr(execution.WorkspaceKey), execution.State,
		nullStr(execution.LeaseOwner), nullStr(execution.LeaseToken), execution.FencingToken,
		execution.LeaseExpiresAt, execution.HeartbeatAt, execution.CancelRequestedAt,
		nullJSON(execution.Checkpoint), nullJSON(execution.Result), nullStr(execution.FailureCode),
		nullStr(execution.FailureMessage), nullJSON(execution.Usage), nullableUUID(execution.AgentID), nullableUUID(execution.BindingID), execution.StartedAt,
		execution.CompletedAt))
	if err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `UPDATE agent_tasks SET current_attempt_id=$2,version=version+1 WHERE id=$1`, execution.AgentTaskID, created.ID); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return created, nil
}

func nullableUUID(id uuid.UUID) any {
	if id == uuid.Nil {
		return nil
	}
	return id
}

func (r *executionAttemptRepo) Get(ctx context.Context, id uuid.UUID) (*controlmodel.ExecutionAttempt, error) {
	return scanExecutionAttempt(r.pool.QueryRow(ctx, `SELECT `+executionAttemptColumns+` FROM execution_attempts WHERE id=$1`, id))
}

func (r *executionAttemptRepo) List(ctx context.Context, filter store.ExecutionAttemptFilter) ([]*controlmodel.ExecutionAttempt, error) {
	limit := filter.Limit
	if limit <= 0 {
		limit = 100
	}
	order := "ASC"
	if filter.NewestFirst {
		order = "DESC"
	}
	rows, err := r.pool.Query(ctx, `SELECT `+executionAttemptColumns+` FROM execution_attempts WHERE
		($1::uuid='00000000-0000-0000-0000-000000000000' OR agent_task_id=$1)
		AND ($2::uuid='00000000-0000-0000-0000-000000000000' OR agent_id=$2)
		AND ($3::uuid='00000000-0000-0000-0000-000000000000' OR binding_id=$3)
		AND ($4='' OR tenant=$4) AND ($5='' OR namespace=$5)
		AND ($6='' OR session_id=$6) AND ($7='' OR runtime_pool_name=$7)
		AND ($8::uuid='00000000-0000-0000-0000-000000000000' OR host_id=$8)
		AND ($9='' OR state=$9) AND (NOT $11 OR EXISTS(SELECT 1 FROM agent_tasks t WHERE t.id=execution_attempts.agent_task_id AND issue_access_allowed(t.issue_id,$12::text[]))) ORDER BY created_at `+order+` LIMIT $10`, filter.AgentTaskID,
		filter.AgentID, filter.BindingID, filter.Tenant, filter.Namespace, filter.SessionID,
		filter.RuntimePoolName, filter.HostID, filter.State, limit, store.WorkAccessFrom(ctx).Restricted, store.WorkAccessFrom(ctx).Refs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanExecutionAttemptRows(rows)
}

func scanExecutionAttemptRows(rows pgx.Rows) ([]*controlmodel.ExecutionAttempt, error) {
	out := make([]*controlmodel.ExecutionAttempt, 0)
	for rows.Next() {
		execution, err := scanExecutionAttempt(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, execution)
	}
	return out, rows.Err()
}

func (r *executionAttemptRepo) Claim(ctx context.Context, claim store.ExecutionClaim) (*controlmodel.ExecutionAttempt, error) {
	if claim.HostID == uuid.Nil || claim.LeaseOwner == "" || claim.LeaseToken == "" || claim.LeaseTTL <= 0 {
		return nil, fmt.Errorf("execution claim requires hostId, leaseOwner, leaseToken, and positive leaseTTL")
	}
	execution, err := scanExecutionAttempt(r.pool.QueryRow(ctx, `WITH candidate AS (
		SELECT e.id AS execution_id FROM execution_attempts e
		JOIN agent_tasks t ON t.id=e.agent_task_id
		JOIN issues i ON i.id=t.issue_id
		JOIN runtime_hosts h ON h.id=$4
		WHERE e.state=$8 AND e.backend_kind=$9
			AND ($1='' OR e.tenant=$1) AND ($2='' OR e.namespace=$2)
			AND ($3='' OR e.runtime_pool_name=$3)
			AND (COALESCE(e.runtime_binding #>> '{policy,preferredHostId}','')='' OR
				e.runtime_binding #>> '{policy,preferredHostId}'=$4::text)
			AND e.runtime_pool_name=h.pool_name
			AND h.tenant=e.tenant AND h.namespace=e.namespace
			AND h.state=$10 AND h.lease_generation=$12
			AND (h.capacity=0 OR h.active<h.capacity)
			AND COALESCE(e.required_capabilities,'{}'::jsonb) <@ COALESCE(h.capabilities,'{}'::jsonb)
			AND (NOT (COALESCE(e.runtime_binding,'{}'::jsonb) ? 'runtimeProfile') OR (
				COALESCE(h.capabilities->'providers','{}'::jsonb) ?
					(e.runtime_binding #>> '{runtimeProfile,provider}')
				AND COALESCE(e.runtime_binding->'runtimeProfile'->'requirements','{}'::jsonb) <@
					COALESCE(h.capabilities,'{}'::jsonb)))
			AND (NOT (COALESCE(e.runtime_binding,'{}'::jsonb) ? 'runtimePool') OR
				COALESCE(e.runtime_binding->'runtimePool'->'hostSelector','{}'::jsonb) <@
					COALESCE(h.labels,'{}'::jsonb))
			AND COALESCE(e.runtime_binding->'securityConstraints','{}'::jsonb) <@
				(jsonb_build_object('backendKind',e.backend_kind) || COALESCE(h.labels,'{}'::jsonb))
		ORDER BY (SELECT COUNT(*) FROM execution_attempts active WHERE active.tenant=e.tenant
				AND active.namespace=e.namespace AND active.state NOT IN('succeeded','failed','cancelled')) ASC,
			t.priority + LEAST(50,FLOOR(EXTRACT(EPOCH FROM (now()-e.created_at))/600)) DESC,
			i.due_at NULLS LAST,e.created_at
		FOR UPDATE OF e SKIP LOCKED LIMIT 1
	)
	UPDATE execution_attempts e SET host_id=$4,lease_owner=$5,lease_token=$6,
		fencing_token=nextval('execution_attempt_fencing_seq'),
		lease_expires_at=now()+$7::interval,state=$11,version=e.version+1,updated_at=now()
	FROM candidate c WHERE e.id=c.execution_id RETURNING `+executionAttemptColumns,
		claim.Tenant, claim.Namespace, claim.RuntimePoolName, claim.HostID, claim.LeaseOwner,
		claim.LeaseToken, intervalSeconds(claim.LeaseTTL), controlmodel.ExecutionQueued,
		controlmodel.DataPlaneHostedRuntime, controlmodel.RuntimeHostOnline,
		controlmodel.ExecutionAssigned, claim.HostGeneration))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrNotFound
	}
	return execution, err
}

func (r *executionAttemptRepo) RenewLease(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64, ttl time.Duration) (*controlmodel.ExecutionAttempt, error) {
	if ttl <= 0 {
		return nil, fmt.Errorf("lease ttl must be positive")
	}
	current, currentErr := r.Get(ctx, id)
	if currentErr == nil && current.State == controlmodel.ExecutionCancelRequested {
		if current.LeaseToken != leaseToken || current.FencingToken != fencingToken {
			return nil, store.ErrConflict
		}
		return current, nil
	}
	execution, err := scanExecutionAttempt(r.pool.QueryRow(ctx, `UPDATE execution_attempts SET
		lease_expires_at=now()+$4::interval,heartbeat_at=now(),version=version+1,updated_at=now()
		WHERE id=$1 AND ((lease_token=$2 AND fencing_token=$3 AND lease_expires_at>now()) OR
			(backend_kind=$8 AND COALESCE(lease_token,'')='' AND fencing_token=0))
		AND state IN ($5,$6,$7,$9) RETURNING `+executionAttemptColumns,
		id, leaseToken, fencingToken, intervalSeconds(ttl), controlmodel.ExecutionAssigned,
		controlmodel.ExecutionPreparing, controlmodel.ExecutionRunning, controlmodel.DataPlaneManaged,
		controlmodel.ExecutionWaiting))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return execution, err
}

func (r *executionAttemptRepo) MarkPreparing(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64) (*controlmodel.ExecutionAttempt, error) {
	return r.transitionLeased(ctx, id, leaseToken, fencingToken, controlmodel.ExecutionPreparing,
		[]controlmodel.ExecutionAttemptState{controlmodel.ExecutionAssigned}, "", "", nil, nil)
}

func (r *executionAttemptRepo) MarkRunning(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64, providerSessionID, workspaceKey string) (*controlmodel.ExecutionAttempt, error) {
	return r.transitionLeased(ctx, id, leaseToken, fencingToken, controlmodel.ExecutionRunning,
		[]controlmodel.ExecutionAttemptState{controlmodel.ExecutionPreparing}, providerSessionID,
		workspaceKey, nil, nil)
}

func (r *executionAttemptRepo) Checkpoint(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64, providerSessionID string, checkpoint json.RawMessage) (*controlmodel.ExecutionAttempt, error) {
	execution, err := scanExecutionAttempt(r.pool.QueryRow(ctx, `UPDATE execution_attempts SET
		provider_session_id=CASE WHEN $4='' THEN provider_session_id ELSE $4 END,
		checkpoint=COALESCE($5,checkpoint),version=version+1,updated_at=now()
		WHERE id=$1 AND lease_token=$2 AND fencing_token=$3 AND lease_expires_at>now()
		AND state=$6 RETURNING `+executionAttemptColumns, id, leaseToken, fencingToken,
		providerSessionID, nullJSON(checkpoint), controlmodel.ExecutionRunning))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return execution, err
}

func (r *executionAttemptRepo) Complete(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64, result, checkpoint json.RawMessage) (*controlmodel.ExecutionAttempt, error) {
	return r.transitionLeased(ctx, id, leaseToken, fencingToken, controlmodel.ExecutionSucceeded,
		[]controlmodel.ExecutionAttemptState{controlmodel.ExecutionRunning}, "", "", result, checkpoint)
}

func (r *executionAttemptRepo) Fail(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64, failureCode, failureMessage string, checkpoint json.RawMessage) (*controlmodel.ExecutionAttempt, error) {
	execution, err := scanExecutionAttempt(r.pool.QueryRow(ctx, `UPDATE execution_attempts SET
		state=$4,failure_code=$5,failure_message=$6,checkpoint=$7,lease_expires_at=NULL,
		completed_at=now(),version=version+1,updated_at=now()
		WHERE id=$1 AND lease_token=$2 AND fencing_token=$3 AND lease_expires_at>now()
		AND state IN ($8,$9,$10) RETURNING `+executionAttemptColumns,
		id, leaseToken, fencingToken, controlmodel.ExecutionFailed, nullStr(failureCode),
		nullStr(failureMessage), nullJSON(checkpoint), controlmodel.ExecutionAssigned,
		controlmodel.ExecutionPreparing, controlmodel.ExecutionRunning))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return execution, err
}

func (r *executionAttemptRepo) transitionLeased(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64, to controlmodel.ExecutionAttemptState, from []controlmodel.ExecutionAttemptState, providerSessionID, workspaceKey string, result, checkpoint json.RawMessage) (*controlmodel.ExecutionAttempt, error) {
	if len(from) != 1 {
		return nil, fmt.Errorf("transitionLeased requires one source state")
	}
	terminal := controlmodel.IsExecutionAttemptTerminal(to)
	execution, err := scanExecutionAttempt(r.pool.QueryRow(ctx, `UPDATE execution_attempts SET
		state=$4,
		provider_session_id=CASE WHEN $5='' THEN provider_session_id ELSE $5 END,
		workspace_key=CASE WHEN $6='' THEN workspace_key ELSE $6 END,
		result=COALESCE($7,result),checkpoint=COALESCE($8,checkpoint),
		started_at=CASE WHEN $4=$9 THEN COALESCE(started_at,now()) ELSE started_at END,
		completed_at=CASE WHEN $10 THEN now() ELSE completed_at END,
		lease_expires_at=CASE WHEN $10 THEN NULL ELSE lease_expires_at END,
		version=version+1,updated_at=now()
		WHERE id=$1 AND lease_token=$2 AND fencing_token=$3 AND lease_expires_at>now()
		AND state=$11 RETURNING `+executionAttemptColumns,
		id, leaseToken, fencingToken, to, providerSessionID, workspaceKey, nullJSON(result),
		nullJSON(checkpoint), controlmodel.ExecutionRunning, terminal, from[0]))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return execution, err
}

func (r *executionAttemptRepo) Cancel(ctx context.Context, id uuid.UUID, expectedVersion int64) (*controlmodel.ExecutionAttempt, error) {
	execution, err := scanExecutionAttempt(r.pool.QueryRow(ctx, `UPDATE execution_attempts SET
		state=CASE WHEN state=$6 THEN $3 ELSE $7 END,
		cancel_requested_at=CASE WHEN state=$6 THEN cancel_requested_at ELSE now() END,
		lease_expires_at=CASE WHEN state=$6 THEN NULL ELSE lease_expires_at END,
		completed_at=CASE WHEN state=$6 THEN now() ELSE completed_at END,
		version=version+1,updated_at=now()
		WHERE id=$1 AND ($2<=0 OR version=$2) AND state NOT IN ($3,$4,$5)
		RETURNING `+executionAttemptColumns, id, expectedVersion, controlmodel.ExecutionCancelled,
		controlmodel.ExecutionSucceeded, controlmodel.ExecutionFailed, controlmodel.ExecutionQueued,
		controlmodel.ExecutionCancelRequested))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return execution, err
}

func (r *executionAttemptRepo) ConfirmCancelled(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64) (*controlmodel.ExecutionAttempt, error) {
	execution, err := scanExecutionAttempt(r.pool.QueryRow(ctx, `UPDATE execution_attempts SET
		state=$4,lease_expires_at=NULL,completed_at=now(),version=version+1,updated_at=now()
		WHERE id=$1 AND lease_token=$2 AND fencing_token=$3 AND state=$5
		RETURNING `+executionAttemptColumns, id, leaseToken, fencingToken, controlmodel.ExecutionCancelled,
		controlmodel.ExecutionCancelRequested))
	if errors.Is(err, store.ErrNotFound) {
		current, getErr := r.Get(ctx, id)
		if getErr == nil && current.State == controlmodel.ExecutionCancelled {
			return current, nil
		}
		return nil, store.ErrConflict
	}
	return execution, err
}

func (r *executionAttemptRepo) ForceCancelled(ctx context.Context, id uuid.UUID, expectedVersion int64) (*controlmodel.ExecutionAttempt, error) {
	attempt, err := scanExecutionAttempt(r.pool.QueryRow(ctx, `UPDATE execution_attempts SET
		state=$3,lease_expires_at=NULL,completed_at=now(),version=version+1,updated_at=now()
		WHERE id=$1 AND ($2<=0 OR version=$2) AND state=$4 RETURNING `+executionAttemptColumns,
		id, expectedVersion, controlmodel.ExecutionCancelled, controlmodel.ExecutionCancelRequested))
	if errors.Is(err, store.ErrNotFound) {
		current, getErr := r.Get(ctx, id)
		if getErr == nil && current.State == controlmodel.ExecutionCancelled {
			return current, nil
		}
		return nil, store.ErrConflict
	}
	return attempt, err
}

func (r *executionAttemptRepo) Report(ctx context.Context, report store.ExecutionAttemptReport) (*controlmodel.ExecutionAttempt, error) {
	current, err := r.Get(ctx, report.AttemptID)
	if err != nil {
		return nil, err
	}
	if current.DispatchGeneration != report.DispatchGeneration || current.BackendKind != report.BackendKind ||
		report.BackendKind == controlmodel.DataPlaneExternalApplication && (current.AgentInstanceID == nil || *current.AgentInstanceID != report.AgentInstanceID) {
		return nil, store.ErrConflict
	}
	if controlmodel.IsExecutionAttemptTerminal(current.State) {
		if current.State == report.State {
			return current, nil
		}
		return nil, store.ErrConflict
	}
	if report.State != "" && !controlmodel.CanTransitionExecutionAttempt(current.State, report.State) {
		return nil, store.ErrConflict
	}
	attempt, err := scanExecutionAttempt(r.pool.QueryRow(ctx, `UPDATE execution_attempts SET
		state=CASE WHEN $3='' THEN state ELSE $3 END,heartbeat_at=now(),checkpoint=COALESCE($4,checkpoint),
		result=COALESCE($5,result),usage=COALESCE($6,usage),failure_code=$7,failure_message=$8,
		version=version+1,updated_at=now(),started_at=CASE WHEN $3='running' THEN COALESCE(started_at,now()) ELSE started_at END,
		completed_at=CASE WHEN $3 IN('succeeded','failed','cancelled') THEN now() ELSE completed_at END
		WHERE id=$1 AND version=$2 RETURNING `+executionAttemptColumns, current.ID, current.Version, report.State,
		nullJSON(report.Checkpoint), nullJSON(report.Result), nullJSON(report.Usage), nullStr(report.FailureCode), nullStr(report.FailureMessage)))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return attempt, err
}

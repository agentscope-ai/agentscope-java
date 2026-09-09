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
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

const automationColumns = `id,tenant,namespace,name,description,enabled,trigger_type,trigger_config,
	action_type,action_config,webhook_secret_hash,next_run_at,last_run_at,version,created_by_type,
	created_by_ref,created_at,updated_at,archived_at,execution,triggers`

func scanAutomation(row scannable) (*controlmodel.Automation, error) {
	item := &controlmodel.Automation{}
	var description, secret, createdRef *string
	var triggerConfig, actionConfig, execution, triggers []byte
	err := row.Scan(&item.ID, &item.Tenant, &item.Namespace, &item.Name, &description, &item.Enabled, &item.TriggerType,
		&triggerConfig, &item.ActionType, &actionConfig, &secret, &item.NextRunAt, &item.LastRunAt, &item.Version,
		&item.CreatedBy.Type, &createdRef, &item.CreatedAt, &item.UpdatedAt, &item.ArchivedAt, &execution, &triggers)
	if err != nil {
		return nil, collaborationScanError(err)
	}
	item.Description, item.WebhookSecretHash, item.CreatedBy.Ref = deref(description), deref(secret), deref(createdRef)
	item.TriggerConfig, item.ActionConfig = triggerConfig, actionConfig
	if len(execution) > 0 {
		if err = json.Unmarshal(execution, &item.Execution); err != nil {
			return nil, err
		}
	}
	if len(triggers) > 0 {
		if err = json.Unmarshal(triggers, &item.Triggers); err != nil {
			return nil, err
		}
	}
	item.WebhookConfigured = item.WebhookSecretHash != ""
	return item, nil
}

const automationRunColumns = `id,automation_id,tenant,namespace,trigger_type,trigger_ref,idempotency_key,status,
	issue_id,agent_task_id,orchestration_run_id,input,output,error_code,error_message,created_at,completed_at,runtime`

func scanAutomationRun(row scannable) (*controlmodel.AutomationRun, error) {
	item := &controlmodel.AutomationRun{}
	var triggerRef, errorCode, errorMessage *string
	var input, output, runtime []byte
	err := row.Scan(&item.ID, &item.AutomationID, &item.Tenant, &item.Namespace, &item.TriggerType, &triggerRef, &item.IdempotencyKey, &item.Status, &item.IssueID, &item.AgentTaskID, &item.OrchestrationRunID, &input, &output, &errorCode, &errorMessage, &item.CreatedAt, &item.CompletedAt, &runtime)
	if err != nil {
		return nil, collaborationScanError(err)
	}
	item.TriggerRef, item.ErrorCode, item.ErrorMessage = deref(triggerRef), deref(errorCode), deref(errorMessage)
	item.Input, item.Output = input, output
	if len(runtime) > 0 {
		var m automationRunMetadata
		if err = json.Unmarshal(runtime, &m); err != nil {
			return nil, err
		}
		item.AutomationRunDetails = m.Details
		item.LeaseToken = m.LeaseToken
		item.LeaseUntil = m.LeaseUntil
	}
	return item, nil
}

func (r *collaborationRepo) CreateAutomation(ctx context.Context, in *controlmodel.Automation) (*controlmodel.Automation, error) {
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	if in.ActionConfig == nil {
		in.ActionConfig = []byte(`{}`)
	}
	return scanAutomation(r.pool.QueryRow(ctx, `INSERT INTO automations(id,tenant,namespace,name,description,enabled,trigger_type,trigger_config,action_type,action_config,webhook_secret_hash,next_run_at,created_by_type,created_by_ref,execution,triggers) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16) RETURNING `+automationColumns, in.ID, in.Tenant, in.Namespace, in.Name, nullStr(in.Description), in.Enabled, in.TriggerType, nullJSON(in.TriggerConfig), in.ActionType, nullJSON(in.ActionConfig), nullStr(in.WebhookSecretHash), in.NextRunAt, in.CreatedBy.Type, nullStr(in.CreatedBy.Ref), automationJSON(in.Execution), automationJSON(in.Triggers)))
}

func (r *collaborationRepo) GetAutomation(ctx context.Context, id uuid.UUID) (*controlmodel.Automation, error) {
	return scanAutomation(r.pool.QueryRow(ctx, `SELECT `+automationColumns+` FROM automations WHERE id=$1`, id))
}

func (r *collaborationRepo) ListAutomations(ctx context.Context, filter store.AutomationFilter) ([]*controlmodel.Automation, error) {
	limit := filter.Limit
	if limit <= 0 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `SELECT `+automationColumns+` FROM automations WHERE archived_at IS NULL AND ($1='' OR tenant=$1) AND ($2='' OR namespace=$2) AND ($3::boolean IS NULL OR enabled=$3) AND ($4::timestamptz IS NULL OR next_run_at<=$4) AND (NOT $7 OR (created_by_type='human' AND created_by_ref=ANY($8::text[]))) ORDER BY created_at,id LIMIT $5 OFFSET $6`, filter.Tenant, filter.Namespace, filter.Enabled, filter.DueBefore, limit, filter.Offset, store.WorkAccessFrom(ctx).Restricted, store.WorkAccessFrom(ctx).Refs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.Automation, 0)
	for rows.Next() {
		item, scanErr := scanAutomation(rows)
		if scanErr != nil {
			return nil, scanErr
		}
		out = append(out, item)
	}
	return out, rows.Err()
}

func (r *collaborationRepo) UpdateAutomation(ctx context.Context, in *controlmodel.Automation, expectedVersion int64) (*controlmodel.Automation, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	current, err := scanAutomation(tx.QueryRow(ctx, `SELECT `+automationColumns+` FROM automations WHERE id=$1 FOR UPDATE`, in.ID))
	if err != nil {
		return nil, err
	}
	copy := *in
	copy.Triggers = append([]controlmodel.AutomationTrigger(nil), in.Triggers...)
	if in.Triggers != nil && copy.Triggers == nil {
		copy.Triggers = []controlmodel.AutomationTrigger{}
	}
	store.MergeAutomationScheduleOnEdit(current, &copy)
	in = &copy
	item, err := scanAutomation(tx.QueryRow(ctx, `UPDATE automations SET name=$2,description=$3,enabled=$4,trigger_type=$5,trigger_config=$6,action_type=$7,action_config=$8,webhook_secret_hash=$9,next_run_at=$10,version=version+1,updated_at=now(),execution=$12,triggers=$13 WHERE id=$1 AND archived_at IS NULL AND ($11<=0 OR version=$11) RETURNING `+automationColumns, in.ID, in.Name, nullStr(in.Description), in.Enabled, in.TriggerType, nullJSON(in.TriggerConfig), in.ActionType, nullJSON(in.ActionConfig), nullStr(in.WebhookSecretHash), in.NextRunAt, expectedVersion, automationJSON(in.Execution), automationJSON(in.Triggers)))
	if err == store.ErrNotFound {
		return nil, store.ErrConflict
	}
	if err != nil {
		return nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	return item, nil
}

func (r *collaborationRepo) ArchiveAutomation(ctx context.Context, id uuid.UUID, expectedVersion int64) (*controlmodel.Automation, error) {
	item, err := scanAutomation(r.pool.QueryRow(ctx, `UPDATE automations SET archived_at=now(),enabled=false,next_run_at=NULL,version=version+1,updated_at=now() WHERE id=$1 AND ($2<=0 OR version=$2) RETURNING `+automationColumns, id, expectedVersion))
	if err == store.ErrNotFound {
		return nil, store.ErrConflict
	}
	return item, err
}

func (r *collaborationRepo) BeginAutomationRun(ctx context.Context, in *controlmodel.AutomationRun) (*controlmodel.AutomationRun, bool, error) {
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	item, err := scanAutomationRun(r.pool.QueryRow(ctx, `INSERT INTO automation_runs(id,automation_id,tenant,namespace,trigger_type,trigger_ref,idempotency_key,status,input) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9) ON CONFLICT(automation_id,idempotency_key) DO NOTHING RETURNING `+automationRunColumns, in.ID, in.AutomationID, in.Tenant, in.Namespace, in.TriggerType, nullStr(in.TriggerRef), in.IdempotencyKey, controlmodel.AutomationRunRunning, nullJSON(in.Input)))
	if err == nil {
		return item, true, nil
	}
	if !errors.Is(err, store.ErrNotFound) {
		return nil, false, err
	}
	item, err = scanAutomationRun(r.pool.QueryRow(ctx, `SELECT `+automationRunColumns+` FROM automation_runs WHERE automation_id=$1 AND idempotency_key=$2`, in.AutomationID, in.IdempotencyKey))
	return item, false, err
}

func (r *collaborationRepo) ListAutomationRuns(ctx context.Context, automationID uuid.UUID, limit, offset int) ([]*controlmodel.AutomationRun, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `SELECT `+automationRunColumns+` FROM automation_runs WHERE automation_id=$1 ORDER BY created_at DESC,id DESC LIMIT $2 OFFSET $3`, automationID, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.AutomationRun, 0)
	for rows.Next() {
		item, scanErr := scanAutomationRun(rows)
		if scanErr != nil {
			return nil, scanErr
		}
		out = append(out, item)
	}
	return out, rows.Err()
}

var _ pgx.Row
var _ = time.Now

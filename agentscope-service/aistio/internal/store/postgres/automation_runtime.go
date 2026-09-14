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
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"time"
)

type automationRunMetadata struct {
	Details    controlmodel.AutomationRunDetails `json:"details"`
	LeaseUntil *time.Time                        `json:"leaseUntil,omitempty"`
	LeaseToken uuid.UUID                         `json:"leaseToken,omitempty"`
}

func automationJSON(v any) []byte { b, _ := json.Marshal(v); return b }
func automationRuntime(v *controlmodel.AutomationRun) []byte {
	return automationJSON(automationRunMetadata{v.AutomationRunDetails, v.LeaseUntil, v.LeaseToken})
}

func (r *collaborationRepo) AdmitAutomationRun(ctx context.Context, req store.AutomationAdmission) (*controlmodel.AutomationRun, bool, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, false, err
	}
	defer tx.Rollback(ctx)
	rule, err := scanAutomation(tx.QueryRow(ctx, `SELECT `+automationColumns+` FROM automations WHERE id=$1 FOR UPDATE`, req.Run.AutomationID))
	if err != nil {
		return nil, false, err
	}
	existing, err := scanAutomationRun(tx.QueryRow(ctx, `SELECT `+automationRunColumns+` FROM automation_runs WHERE automation_id=$1 AND idempotency_key=$2`, rule.ID, req.Run.IdempotencyKey))
	if err == nil {
		if !store.SameAutomationInput(existing.Input, req.Run.Input) {
			return nil, false, store.ErrConflict
		}
		return existing, false, nil
	}
	if err != store.ErrNotFound {
		return nil, false, err
	}
	var active bool
	err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM automation_runs WHERE automation_id=$1 AND status IN ('queued','dispatching','running','waiting') AND COALESCE(runtime->'details'->>'executionCompletedAt','')='')`, rule.ID).Scan(&active)
	if err != nil {
		return nil, false, err
	}
	if req.Run.ID == uuid.Nil {
		req.Run.ID = uuid.New()
	}
	if err = store.PrepareAutomationAdmission(rule, req, active, time.Now().UTC()); err != nil {
		return nil, false, err
	}
	in := req.Run
	run, err := scanAutomationRun(tx.QueryRow(ctx, `INSERT INTO automation_runs(id,automation_id,tenant,namespace,trigger_type,trigger_ref,idempotency_key,status,input,error_code,error_message,completed_at,runtime) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13) RETURNING `+automationRunColumns, in.ID, in.AutomationID, in.Tenant, in.Namespace, in.TriggerType, nullStr(in.TriggerRef), in.IdempotencyKey, in.Status, nullJSON(in.Input), nullStr(in.ErrorCode), nullStr(in.ErrorMessage), in.CompletedAt, automationRuntime(in)))
	if err != nil {
		return nil, false, err
	}
	_, err = tx.Exec(ctx, `UPDATE automations SET next_run_at=$2,triggers=$3,last_run_at=$4 WHERE id=$1`, rule.ID, rule.NextRunAt, automationJSON(rule.Triggers), rule.LastRunAt)
	if err != nil {
		return nil, false, err
	}
	if err = enqueueCollaborationEventTx(ctx, tx, run.Tenant, "automation-run", run.ID, "automation-run.accepted.v1", run, "automation-run-accepted:"+run.ID.String()); err != nil {
		return nil, false, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, false, err
	}
	return run, true, nil
}

func (r *collaborationRepo) GetAutomationRun(ctx context.Context, id uuid.UUID) (*controlmodel.AutomationRun, error) {
	return scanAutomationRun(r.pool.QueryRow(ctx, `SELECT `+automationRunColumns+` FROM automation_runs WHERE id=$1`, id))
}
func (r *collaborationRepo) ListPendingAutomationRuns(ctx context.Context, limit int) ([]*controlmodel.AutomationRun, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `SELECT `+automationRunColumns+` FROM automation_runs WHERE status IN ('queued','dispatching','running','waiting') AND runtime IS NOT NULL ORDER BY runtime->'details'->>'updatedAt',id LIMIT $1`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*controlmodel.AutomationRun{}
	for rows.Next() {
		v, e := scanAutomationRun(rows)
		if e != nil {
			return nil, e
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

func (r *collaborationRepo) ClaimAutomationRun(ctx context.Context, id uuid.UUID, now time.Time, ttl time.Duration) (*controlmodel.AutomationRun, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	var ruleID uuid.UUID
	if err = tx.QueryRow(ctx, `SELECT automation_id FROM automation_runs WHERE id=$1`, id).Scan(&ruleID); err != nil {
		return nil, collaborationScanError(err)
	}
	if _, err = tx.Exec(ctx, `SELECT id FROM automations WHERE id=$1 FOR UPDATE`, ruleID); err != nil {
		return nil, err
	}
	v, err := scanAutomationRun(tx.QueryRow(ctx, `SELECT `+automationRunColumns+` FROM automation_runs WHERE id=$1 FOR UPDATE`, id))
	if err != nil {
		return nil, err
	}
	if v.Status != controlmodel.AutomationRunQueued && v.Status != controlmodel.AutomationRunDispatching {
		return nil, store.ErrConflict
	}
	if v.LeaseUntil != nil && v.LeaseUntil.After(now) {
		return nil, store.ErrConflict
	}
	if v.Snapshot != nil && v.Snapshot.Execution != nil {
		var busy bool
		err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM automation_runs WHERE automation_id=$1 AND id<>$2 AND status IN ('queued','dispatching','running','waiting') AND COALESCE(runtime->'details'->>'executionCompletedAt','')='' AND (status<>'queued' OR issue_id IS NOT NULL OR (created_at,id)<($3,$2)))`, v.AutomationID, v.ID, v.CreatedAt).Scan(&busy)
		if err != nil {
			return nil, err
		}
		if busy {
			v.UpdatedAt = now
			v.Version++
			if _, err = tx.Exec(ctx, `UPDATE automation_runs SET runtime=$2 WHERE id=$1`, v.ID, automationRuntime(v)); err != nil {
				return nil, err
			}
			if err = tx.Commit(ctx); err != nil {
				return nil, err
			}
			return nil, store.ErrConflict
		}
	}
	until := now.Add(ttl)
	v.LeaseUntil = &until
	v.LeaseToken = uuid.New()
	v.Status = controlmodel.AutomationRunDispatching
	v.UpdatedAt = now
	v.Version++
	_, err = tx.Exec(ctx, `UPDATE automation_runs SET status=$2,runtime=$3 WHERE id=$1`, v.ID, v.Status, automationRuntime(v))
	if err != nil {
		return nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	return v, nil
}

func (r *collaborationRepo) FinishAutomationRun(ctx context.Context, in *controlmodel.AutomationRun) (*controlmodel.AutomationRun, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	old, err := scanAutomationRun(tx.QueryRow(ctx, `SELECT `+automationRunColumns+` FROM automation_runs WHERE id=$1 FOR UPDATE`, in.ID))
	if err != nil {
		return nil, err
	}
	if controlmodel.IsAutomationRunTerminal(old.Status) || (in.Version > 0 && old.Version != in.Version) {
		return nil, store.ErrConflict
	}
	v := *in
	now := time.Now().UTC()
	v.UpdatedAt = now
	v.Version = old.Version + 1
	v.LeaseUntil = nil
	v.LeaseToken = uuid.Nil
	if controlmodel.IsAutomationRunTerminal(v.Status) {
		v.CompletedAt = &now
	} else {
		v.CompletedAt = nil
	}
	updated, err := scanAutomationRun(tx.QueryRow(ctx, `UPDATE automation_runs SET status=$2,issue_id=$3,agent_task_id=$4,orchestration_run_id=$5,output=$6,error_code=$7,error_message=$8,completed_at=$9,runtime=$10 WHERE id=$1 RETURNING `+automationRunColumns, v.ID, v.Status, v.IssueID, v.AgentTaskID, v.OrchestrationRunID, nullJSON(v.Output), nullStr(v.ErrorCode), nullStr(v.ErrorMessage), v.CompletedAt, automationRuntime(&v)))
	if err != nil {
		return nil, err
	}
	if item := store.AutomationFailureInbox(updated); item != nil {
		_, err = tx.Exec(ctx, `INSERT INTO inbox_items (id,tenant,namespace,recipient_type,recipient_ref,type,severity,actor_type,actor_ref,title,body,details,needs_action,dedupe_key,created_at)
        VALUES ($1,$2,$3,'human',$4,$5,$6,$7,$8,$9,$10,$11,true,$12,$13)
        ON CONFLICT (tenant,dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING`, item.ID, item.Tenant, item.Namespace, item.RecipientRef, item.Type, item.Severity, item.Actor.Type, item.Actor.Ref, item.Title, item.Body, item.Details, item.DedupeKey, item.CreatedAt)
		if err != nil {
			return nil, err
		}
	}
	if controlmodel.IsAutomationRunTerminal(v.Status) {
		if err = enqueueCollaborationEventTx(ctx, tx, v.Tenant, "automation-run", v.ID, "automation-run."+string(v.Status)+".v1", updated, "automation-run-finished:"+v.ID.String()); err != nil {
			return nil, err
		}
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	return updated, nil
}

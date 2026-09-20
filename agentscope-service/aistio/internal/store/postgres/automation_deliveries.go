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
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

const automationDeliveryColumns = `id,automation_id,trigger_id,idempotency_key,input,event,status,reason,run_id,replayed_from,created_at`

func scanAutomationDelivery(row scannable) (*controlmodel.AutomationDelivery, error) {
	v := &controlmodel.AutomationDelivery{}
	err := row.Scan(&v.ID, &v.AutomationID, &v.TriggerID, &v.IdempotencyKey, &v.Input, &v.Event, &v.Status, &v.Reason, &v.RunID, &v.ReplayedFrom, &v.CreatedAt)
	return v, collaborationScanError(err)
}
func (r *collaborationRepo) SaveAutomationDelivery(ctx context.Context, in *controlmodel.AutomationDelivery) (*controlmodel.AutomationDelivery, bool, error) {
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	if in.Status != "queued" {
		v, e := scanAutomationDelivery(r.pool.QueryRow(ctx, `UPDATE automation_deliveries SET status=$2,reason=$3,run_id=$4 WHERE id=$1 AND status='queued' RETURNING `+automationDeliveryColumns, in.ID, in.Status, in.Reason, in.RunID))
		if e == nil {
			return v, false, nil
		}
		if e != store.ErrNotFound {
			return nil, false, e
		}
	}
	v, e := scanAutomationDelivery(r.pool.QueryRow(ctx, `INSERT INTO automation_deliveries(id,automation_id,trigger_id,idempotency_key,input,event,status,reason,run_id,replayed_from) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) ON CONFLICT DO NOTHING RETURNING `+automationDeliveryColumns, in.ID, in.AutomationID, in.TriggerID, in.IdempotencyKey, nullJSON(in.Input), in.Event, in.Status, in.Reason, in.RunID, in.ReplayedFrom))
	if e == nil {
		return v, true, nil
	}
	if e != store.ErrNotFound {
		return nil, false, e
	}
	v, e = scanAutomationDelivery(r.pool.QueryRow(ctx, `SELECT `+automationDeliveryColumns+` FROM automation_deliveries WHERE automation_id=$1 AND trigger_id=$2 AND idempotency_key=$3`, in.AutomationID, in.TriggerID, in.IdempotencyKey))
	if e == nil && (v.Event != in.Event || !store.SameAutomationInput(v.Input, in.Input)) {
		return nil, false, store.ErrConflict
	}
	return v, false, e
}
func (r *collaborationRepo) GetAutomationDelivery(ctx context.Context, id uuid.UUID) (*controlmodel.AutomationDelivery, error) {
	return scanAutomationDelivery(r.pool.QueryRow(ctx, `SELECT `+automationDeliveryColumns+` FROM automation_deliveries WHERE id=$1`, id))
}
func (r *collaborationRepo) ListAutomationDeliveries(ctx context.Context, id uuid.UUID, limit, offset int) ([]*controlmodel.AutomationDelivery, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `SELECT `+automationDeliveryColumns+` FROM automation_deliveries WHERE ($1='00000000-0000-0000-0000-000000000000'::uuid AND status='queued') OR automation_id=$1 ORDER BY created_at DESC,id DESC LIMIT $2 OFFSET $3`, id, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*controlmodel.AutomationDelivery{}
	for rows.Next() {
		v, e := scanAutomationDelivery(rows)
		if e != nil {
			return nil, e
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

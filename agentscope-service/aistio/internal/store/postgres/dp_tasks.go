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
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type dpTaskRepo struct {
	pool *pgxpool.Pool
}

const dpTaskSelectCols = `
	tenant, parent_agent_id, parent_session_id, task_id,
	sub_agent_id, sub_session_id, status, terminal,
	result, error_message, cancel_requested,
	transport_type, remote_base_url, remote_headers, user_id,
	created_at, last_checked_at, last_updated_at, delivered_at, version`

func scanDPTask(row pgx.Row) (*store.DPTask, error) {
	t := &store.DPTask{}
	var subAgentID, subSessionID, result, errMsg, transportType, remoteBaseURL, userID *string
	var remoteHeaders []byte
	var lastCheckedAt, deliveredAt *time.Time
	err := row.Scan(
		&t.Tenant, &t.ParentAgentID, &t.ParentSessionID, &t.TaskID,
		&subAgentID, &subSessionID, &t.Status, &t.Terminal,
		&result, &errMsg, &t.CancelRequested,
		&transportType, &remoteBaseURL, &remoteHeaders, &userID,
		&t.CreatedAt, &lastCheckedAt, &t.LastUpdatedAt, &deliveredAt, &t.Version,
	)
	if err != nil {
		return nil, err
	}
	t.SubAgentID = deref(subAgentID)
	t.SubSessionID = deref(subSessionID)
	t.Result = deref(result)
	t.ErrorMessage = deref(errMsg)
	t.TransportType = deref(transportType)
	t.RemoteBaseURL = deref(remoteBaseURL)
	t.UserID = deref(userID)
	t.LastCheckedAt = lastCheckedAt
	t.DeliveredAt = deliveredAt
	if len(remoteHeaders) > 0 {
		t.RemoteHeaders = json.RawMessage(remoteHeaders)
	}
	return t, nil
}

func scanDPTaskRows(rows pgx.Rows) ([]*store.DPTask, error) {
	var out []*store.DPTask
	for rows.Next() {
		t, err := scanDPTask(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	if out == nil {
		out = []*store.DPTask{}
	}
	return out, rows.Err()
}

func (r *dpTaskRepo) Upsert(ctx context.Context, task *store.DPTask) (*store.DPTask, error) {
	if task == nil {
		return nil, store.ErrNotFound
	}
	now := time.Now().UTC()
	if task.CreatedAt.IsZero() {
		task.CreatedAt = now
	}
	if task.Status == "" {
		task.Status = store.DPTaskStatusPending
	}
	terminal := store.IsTerminalTaskStatus(task.Status)
	return scanDPTask(r.pool.QueryRow(ctx, `
		INSERT INTO dp_tasks (
			tenant, parent_agent_id, parent_session_id, task_id,
			sub_agent_id, sub_session_id, status, terminal,
			result, error_message, cancel_requested,
			transport_type, remote_base_url, remote_headers, user_id,
			created_at, last_checked_at, last_updated_at, delivered_at, version
		) VALUES (
			$1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,now(),$18,1
		)
		ON CONFLICT (tenant, parent_agent_id, parent_session_id, task_id) DO UPDATE SET
			sub_agent_id = EXCLUDED.sub_agent_id,
			sub_session_id = EXCLUDED.sub_session_id,
			status = EXCLUDED.status,
			terminal = EXCLUDED.terminal,
			result = EXCLUDED.result,
			error_message = EXCLUDED.error_message,
			cancel_requested = EXCLUDED.cancel_requested,
			transport_type = EXCLUDED.transport_type,
			remote_base_url = EXCLUDED.remote_base_url,
			remote_headers = EXCLUDED.remote_headers,
			user_id = EXCLUDED.user_id,
			last_checked_at = EXCLUDED.last_checked_at,
			last_updated_at = now(),
			delivered_at = COALESCE(dp_tasks.delivered_at, EXCLUDED.delivered_at),
			version = dp_tasks.version + 1
		RETURNING `+dpTaskSelectCols,
		task.Tenant, task.ParentAgentID, task.ParentSessionID, task.TaskID,
		nullStr(task.SubAgentID), nullStr(task.SubSessionID), task.Status, terminal,
		nullStr(task.Result), nullStr(task.ErrorMessage), task.CancelRequested,
		nullStr(task.TransportType), nullStr(task.RemoteBaseURL), nullJSON(task.RemoteHeaders), nullStr(task.UserID),
		task.CreatedAt, task.LastCheckedAt, task.DeliveredAt,
	))
}

func (r *dpTaskRepo) Get(ctx context.Context, tenant, parentAgentID, parentSessionID, taskID string) (*store.DPTask, error) {
	t, err := scanDPTask(r.pool.QueryRow(ctx, `
		SELECT `+dpTaskSelectCols+`
		FROM dp_tasks
		WHERE tenant=$1 AND parent_agent_id=$2 AND parent_session_id=$3 AND task_id=$4`,
		tenant, parentAgentID, parentSessionID, taskID))
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, store.ErrNotFound
	}
	return t, err
}

func (r *dpTaskRepo) List(ctx context.Context, tenant, parentAgentID, parentSessionID, status string) ([]*store.DPTask, error) {
	var rows pgx.Rows
	var err error
	if status != "" {
		rows, err = r.pool.Query(ctx, `
			SELECT `+dpTaskSelectCols+`
			FROM dp_tasks
			WHERE tenant=$1 AND parent_agent_id=$2 AND parent_session_id=$3 AND status=$4
			ORDER BY created_at ASC`,
			tenant, parentAgentID, parentSessionID, status)
	} else {
		rows, err = r.pool.Query(ctx, `
			SELECT `+dpTaskSelectCols+`
			FROM dp_tasks
			WHERE tenant=$1 AND parent_agent_id=$2 AND parent_session_id=$3
			ORDER BY created_at ASC`,
			tenant, parentAgentID, parentSessionID)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanDPTaskRows(rows)
}

func (r *dpTaskRepo) Heartbeat(ctx context.Context, tenant, parentAgentID string, refs []store.DPTaskRef) error {
	now := time.Now().UTC()
	for _, ref := range refs {
		_, err := r.pool.Exec(ctx, `
			UPDATE dp_tasks
			SET last_updated_at=$5, version=version+1
			WHERE tenant=$1 AND parent_agent_id=$2 AND parent_session_id=$3 AND task_id=$4
			  AND NOT terminal`,
			tenant, parentAgentID, ref.ParentSessionID, ref.TaskID, now)
		if err != nil {
			return err
		}
	}
	return nil
}

func (r *dpTaskRepo) RequestCancel(ctx context.Context, tenant, parentAgentID, parentSessionID, taskID string) error {
	tag, err := r.pool.Exec(ctx, `
		UPDATE dp_tasks
		SET cancel_requested=true, last_updated_at=now(), version=version+1
		WHERE tenant=$1 AND parent_agent_id=$2 AND parent_session_id=$3 AND task_id=$4`,
		tenant, parentAgentID, parentSessionID, taskID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	return nil
}

func (r *dpTaskRepo) MarkDelivered(ctx context.Context, tenant, parentAgentID, parentSessionID, taskID string) (bool, error) {
	tag, err := r.pool.Exec(ctx, `
		UPDATE dp_tasks
		SET delivered_at=now(), last_updated_at=now(), version=version+1
		WHERE tenant=$1 AND parent_agent_id=$2 AND parent_session_id=$3 AND task_id=$4
		  AND delivered_at IS NULL`,
		tenant, parentAgentID, parentSessionID, taskID)
	if err != nil {
		return false, err
	}
	if tag.RowsAffected() == 0 {
		_, err := r.Get(ctx, tenant, parentAgentID, parentSessionID, taskID)
		if err != nil {
			return false, err
		}
		return false, nil
	}
	return true, nil
}

func (r *dpTaskRepo) ListPendingDeliveries(ctx context.Context, tenant, parentAgentID, parentSessionID string) ([]*store.DPTask, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT `+dpTaskSelectCols+`
		FROM dp_tasks
		WHERE tenant=$1 AND parent_agent_id=$2 AND parent_session_id=$3
		  AND terminal AND delivered_at IS NULL
		ORDER BY last_updated_at ASC`,
		tenant, parentAgentID, parentSessionID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanDPTaskRows(rows)
}

func (r *dpTaskRepo) Delete(ctx context.Context, tenant, parentAgentID, parentSessionID, taskID string) error {
	tag, err := r.pool.Exec(ctx, `
		DELETE FROM dp_tasks
		WHERE tenant=$1 AND parent_agent_id=$2 AND parent_session_id=$3 AND task_id=$4`,
		tenant, parentAgentID, parentSessionID, taskID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	return nil
}

func (r *dpTaskRepo) SweepOrphaned(ctx context.Context, orphanTimeout time.Duration, errMsg string) ([]*store.DPTask, error) {
	rows, err := r.pool.Query(ctx, `
		UPDATE dp_tasks SET status='FAILED', terminal=true, error_message=$2,
		       last_updated_at=now(), version=version+1
		WHERE NOT terminal AND transport_type IS DISTINCT FROM 'agent-protocol'
		  AND last_updated_at < now() - $1::interval
		RETURNING `+dpTaskSelectCols,
		intervalSeconds(orphanTimeout), errMsg)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanDPTaskRows(rows)
}

func (r *dpTaskRepo) PurgeTerminalOlderThan(ctx context.Context, olderThan time.Duration) (int64, error) {
	tag, err := r.pool.Exec(ctx, `
		DELETE FROM dp_tasks
		WHERE terminal AND last_updated_at < now() - $1::interval`,
		intervalSeconds(olderThan))
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}

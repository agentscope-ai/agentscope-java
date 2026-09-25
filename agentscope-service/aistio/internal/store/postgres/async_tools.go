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
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type asyncToolRepo struct {
	pool *pgxpool.Pool
}

func (r *asyncToolRepo) Register(ctx context.Context, rec *store.AsyncToolRecord) error {
	if rec == nil {
		return store.ErrNotFound
	}
	now := time.Now().UTC()
	if rec.CreatedAt.IsZero() {
		rec.CreatedAt = now
	}
	rec.UpdatedAt = now
	if rec.Status == "" {
		rec.Status = store.AsyncToolRunning
	}
	_, err := r.pool.Exec(ctx, `
		INSERT INTO dp_async_tools (
			tenant, record_id, session_id, tool_name, tool_call_id,
			status, result, error, created_at, updated_at
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)
		ON CONFLICT (tenant, record_id) DO UPDATE
		   SET session_id = EXCLUDED.session_id,
		       tool_name = EXCLUDED.tool_name,
		       tool_call_id = EXCLUDED.tool_call_id,
		       status = EXCLUDED.status,
		       result = EXCLUDED.result,
		       error = EXCLUDED.error,
		       updated_at = EXCLUDED.updated_at`,
		rec.Tenant, rec.ID, rec.SessionID,
		nullStr(rec.ToolName), nullStr(rec.ToolCallID),
		rec.Status, nullStr(rec.Result), nullStr(rec.Error),
		rec.CreatedAt, rec.UpdatedAt,
	)
	return err
}

func (r *asyncToolRepo) Complete(ctx context.Context, tenant, recordID, result string) error {
	return r.setStatus(ctx, tenant, recordID, store.AsyncToolCompleted, result, "")
}

func (r *asyncToolRepo) Fail(ctx context.Context, tenant, recordID, errMsg string) error {
	return r.setStatus(ctx, tenant, recordID, store.AsyncToolFailed, "", errMsg)
}

func (r *asyncToolRepo) MarkTimeout(ctx context.Context, tenant, recordID string) error {
	return r.setStatus(ctx, tenant, recordID, store.AsyncToolTimeout, "", "")
}

func (r *asyncToolRepo) setStatus(ctx context.Context, tenant, recordID, status, result, errMsg string) error {
	tag, err := r.pool.Exec(ctx, `
		UPDATE dp_async_tools
		SET status=$3,
		    result=COALESCE(NULLIF($4, ''), result),
		    error=COALESCE(NULLIF($5, ''), error),
		    updated_at=now()
		WHERE tenant=$1 AND record_id=$2`,
		tenant, recordID, status, result, errMsg)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	return nil
}

func (r *asyncToolRepo) FindStale(ctx context.Context, tenant, sessionID string, ttl time.Duration) ([]*store.AsyncToolRecord, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT tenant, record_id, session_id, tool_name, tool_call_id,
		       status, result, error, created_at, updated_at
		FROM dp_async_tools
		WHERE tenant=$1 AND session_id=$2 AND status=$3
		  AND created_at < now() - $4::interval`,
		tenant, sessionID, store.AsyncToolRunning, intervalSeconds(ttl))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*store.AsyncToolRecord
	for rows.Next() {
		rec := &store.AsyncToolRecord{}
		var toolName, toolCallID, result, errMsg *string
		if err := rows.Scan(
			&rec.Tenant, &rec.ID, &rec.SessionID, &toolName, &toolCallID,
			&rec.Status, &result, &errMsg, &rec.CreatedAt, &rec.UpdatedAt,
		); err != nil {
			return nil, err
		}
		rec.ToolName = deref(toolName)
		rec.ToolCallID = deref(toolCallID)
		rec.Result = deref(result)
		rec.Error = deref(errMsg)
		out = append(out, rec)
	}
	if out == nil {
		out = []*store.AsyncToolRecord{}
	}
	return out, rows.Err()
}

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
	"strconv"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type busRepo struct {
	pool *pgxpool.Pool
}

func (r *busRepo) QueuePush(ctx context.Context, tenant, key string, payload json.RawMessage) (string, error) {
	var id int64
	err := r.pool.QueryRow(ctx, `
		INSERT INTO dp_bus_entries (tenant, bus_key, kind, payload)
		VALUES ($1, $2, $3, $4)
		RETURNING id`,
		tenant, key, store.BusKindQueue, payload,
	).Scan(&id)
	if err != nil {
		return "", err
	}
	return strconv.FormatInt(id, 10), nil
}

func (r *busRepo) QueueDrain(ctx context.Context, tenant, key string, maxCount int) ([]*store.BusEntry, error) {
	if maxCount <= 0 {
		maxCount = 1
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	rows, err := tx.Query(ctx, `
		DELETE FROM dp_bus_entries
		 WHERE id IN (
		   SELECT id FROM dp_bus_entries
		    WHERE tenant = $1 AND bus_key = $2 AND kind = $3
		    ORDER BY id LIMIT $4 FOR UPDATE SKIP LOCKED)
		RETURNING id, payload`,
		tenant, key, store.BusKindQueue, maxCount)
	if err != nil {
		return nil, err
	}
	var out []*store.BusEntry
	for rows.Next() {
		var id int64
		e := &store.BusEntry{}
		if err := rows.Scan(&id, &e.Payload); err != nil {
			rows.Close()
			return nil, err
		}
		e.EntryID = strconv.FormatInt(id, 10)
		out = append(out, e)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	if out == nil {
		out = []*store.BusEntry{}
	}
	return out, nil
}

func (r *busRepo) QueueDelete(ctx context.Context, tenant, key string) error {
	_, err := r.pool.Exec(ctx, `
		DELETE FROM dp_bus_entries WHERE tenant=$1 AND bus_key=$2 AND kind=$3`,
		tenant, key, store.BusKindQueue)
	return err
}

func (r *busRepo) QueuePeek(ctx context.Context, tenant, key string) (bool, error) {
	var exists bool
	err := r.pool.QueryRow(ctx, `
		SELECT EXISTS(
		  SELECT 1 FROM dp_bus_entries
		  WHERE tenant=$1 AND bus_key=$2 AND kind=$3)`,
		tenant, key, store.BusKindQueue,
	).Scan(&exists)
	return exists, err
}

func (r *busRepo) LogAppend(ctx context.Context, tenant, key string, payload json.RawMessage, maxLen int) (string, error) {
	var id int64
	err := r.pool.QueryRow(ctx, `
		INSERT INTO dp_bus_entries (tenant, bus_key, kind, payload)
		VALUES ($1, $2, $3, $4)
		RETURNING id`,
		tenant, key, store.BusKindLog, payload,
	).Scan(&id)
	if err != nil {
		return "", err
	}
	if maxLen > 0 {
		_, err = r.pool.Exec(ctx, `
			DELETE FROM dp_bus_entries
			 WHERE id IN (
			   SELECT id FROM (
			     SELECT id, ROW_NUMBER() OVER (ORDER BY id DESC) AS rn
			     FROM dp_bus_entries
			     WHERE tenant=$1 AND bus_key=$2 AND kind=$3
			   ) t WHERE rn > $4
			 )`,
			tenant, key, store.BusKindLog, maxLen)
		if err != nil {
			return "", err
		}
	}
	return strconv.FormatInt(id, 10), nil
}

func (r *busRepo) LogRead(ctx context.Context, tenant, key, since string, maxCount int) ([]*store.BusEntry, error) {
	if maxCount <= 0 {
		maxCount = 100
	}
	var sinceID int64
	if since != "" {
		sinceID, _ = strconv.ParseInt(since, 10, 64)
	}
	rows, err := r.pool.Query(ctx, `
		SELECT id, payload FROM dp_bus_entries
		WHERE tenant=$1 AND bus_key=$2 AND kind=$3 AND id > $4
		ORDER BY id ASC
		LIMIT $5`,
		tenant, key, store.BusKindLog, sinceID, maxCount)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*store.BusEntry
	for rows.Next() {
		var id int64
		e := &store.BusEntry{}
		if err := rows.Scan(&id, &e.Payload); err != nil {
			return nil, err
		}
		e.EntryID = strconv.FormatInt(id, 10)
		out = append(out, e)
	}
	if out == nil {
		out = []*store.BusEntry{}
	}
	return out, rows.Err()
}

func (r *busRepo) LogTrim(ctx context.Context, tenant, key string) error {
	_, err := r.pool.Exec(ctx, `
		DELETE FROM dp_bus_entries WHERE tenant=$1 AND bus_key=$2 AND kind=$3`,
		tenant, key, store.BusKindLog)
	return err
}

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
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type kvRepo struct {
	pool *pgxpool.Pool
}

func escapeLike(s string) string {
	s = strings.ReplaceAll(s, `\`, `\\`)
	s = strings.ReplaceAll(s, `%`, `\%`)
	s = strings.ReplaceAll(s, `_`, `\_`)
	return s
}

func (r *kvRepo) Get(ctx context.Context, tenant, nsPath, key string) (*store.KVItem, error) {
	item := &store.KVItem{}
	err := r.pool.QueryRow(ctx, `
		SELECT item_key, value, version, ns_path
		FROM dp_kv WHERE tenant=$1 AND ns_path=$2 AND item_key=$3`,
		tenant, nsPath, key,
	).Scan(&item.Key, &item.Value, &item.Version, &item.NsPath)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	return item, nil
}

func (r *kvRepo) Put(ctx context.Context, tenant, nsPath, key string, value json.RawMessage) (int64, error) {
	var ver int64
	err := r.pool.QueryRow(ctx, `
		INSERT INTO dp_kv (tenant, ns_path, item_key, value, version, updated_at)
		VALUES ($1, $2, $3, $4, 1, now())
		ON CONFLICT (tenant, ns_path, item_key) DO UPDATE
		   SET value = EXCLUDED.value,
		       version = dp_kv.version + 1,
		       updated_at = now()
		RETURNING version`,
		tenant, nsPath, key, value,
	).Scan(&ver)
	return ver, err
}

func (r *kvRepo) PutIfVersion(ctx context.Context, tenant, nsPath, key string, value json.RawMessage, expectedVersion int64) (int64, bool, error) {
	if expectedVersion == 0 {
		var ver int64
		err := r.pool.QueryRow(ctx, `
			INSERT INTO dp_kv (tenant, ns_path, item_key, value, version, updated_at)
			VALUES ($1, $2, $3, $4, 1, now())
			ON CONFLICT (tenant, ns_path, item_key) DO NOTHING
			RETURNING version`,
			tenant, nsPath, key, value,
		).Scan(&ver)
		if err == nil {
			return ver, true, nil
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return 0, false, err
		}
		cur, gerr := r.Get(ctx, tenant, nsPath, key)
		if gerr != nil {
			return 0, false, gerr
		}
		return cur.Version, false, nil
	}

	var ver int64
	err := r.pool.QueryRow(ctx, `
		UPDATE dp_kv
		SET value=$4, version=version+1, updated_at=now()
		WHERE tenant=$1 AND ns_path=$2 AND item_key=$3 AND version=$5
		RETURNING version`,
		tenant, nsPath, key, value, expectedVersion,
	).Scan(&ver)
	if err == nil {
		return ver, true, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return 0, false, err
	}
	cur, gerr := r.Get(ctx, tenant, nsPath, key)
	if gerr != nil {
		return 0, false, gerr
	}
	return cur.Version, false, nil
}

func (r *kvRepo) Delete(ctx context.Context, tenant, nsPath, key string) error {
	_, err := r.pool.Exec(ctx, `
		DELETE FROM dp_kv WHERE tenant=$1 AND ns_path=$2 AND item_key=$3`,
		tenant, nsPath, key)
	return err
}

func (r *kvRepo) Search(ctx context.Context, tenant, nsPath string, limit, offset int) ([]*store.KVItem, error) {
	if limit <= 0 {
		limit = 100
	}
	if offset < 0 {
		offset = 0
	}
	// Exact ns_path OR child namespaces (ns_path + \x1f + ...). Escape LIKE wildcards.
	likePat := escapeLike(nsPath) + string([]byte{0x1f}) + "%"
	rows, err := r.pool.Query(ctx, `
		SELECT item_key, value, version, ns_path
		FROM dp_kv
		WHERE tenant=$1 AND (ns_path = $2 OR ns_path LIKE $3 ESCAPE E'\\')
		ORDER BY item_key, ns_path
		LIMIT $4 OFFSET $5`,
		tenant, nsPath, likePat, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*store.KVItem
	for rows.Next() {
		item := &store.KVItem{}
		if err := rows.Scan(&item.Key, &item.Value, &item.Version, &item.NsPath); err != nil {
			return nil, err
		}
		out = append(out, item)
	}
	if out == nil {
		out = []*store.KVItem{}
	}
	return out, rows.Err()
}

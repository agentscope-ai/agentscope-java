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
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"slices"
)

type accessRepo struct{ s *Store }

func (s *Store) Access() store.AccessRepository { return &accessRepo{s: s} }
func scanNamespace(row scannable) (*controlmodel.Namespace, error) {
	var data []byte
	var version int64
	if err := row.Scan(&data, &version); err != nil {
		return nil, collaborationScanError(err)
	}
	var n controlmodel.Namespace
	if err := json.Unmarshal(data, &n); err != nil {
		return nil, err
	}
	n.Version = version
	return &n, nil
}
func (r *accessRepo) GetNamespace(ctx context.Context, tenant, name string) (*controlmodel.Namespace, error) {
	return scanNamespace(r.s.pool.QueryRow(ctx, `SELECT payload,version FROM access_namespaces WHERE tenant=$1 AND name=$2`, tenant, name))
}
func (r *accessRepo) ListNamespaces(ctx context.Context, tenant, user string, limit, offset int) ([]*controlmodel.Namespace, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := r.s.pool.Query(ctx, `SELECT payload,version FROM access_namespaces WHERE tenant=$1 AND ($2='' OR (COALESCE((payload->>'archived')::boolean,false)=false AND (payload->>'kind'='global' OR payload->>'owner'=$2 OR payload->'members' ? $2 OR EXISTS (SELECT 1 FROM jsonb_each(COALESCE(payload->'groups','{}'::jsonb)) g WHERE g.value->'members' ? $2)))) ORDER BY name LIMIT $3 OFFSET $4`, tenant, user, limit, maxInt(offset, 0))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*controlmodel.Namespace{}
	for rows.Next() {
		n, err := scanNamespace(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	return out, rows.Err()
}
func (r *accessRepo) PutNamespace(ctx context.Context, n *controlmodel.Namespace, version int64, actor string) (*controlmodel.Namespace, error) {
	if err := n.Validate(); err != nil {
		return nil, err
	}
	data, err := json.Marshal(n)
	if err != nil {
		return nil, err
	}
	tx, err := r.s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	var updated *controlmodel.Namespace
	if version == 0 {
		updated, err = scanNamespace(tx.QueryRow(ctx, `INSERT INTO access_namespaces(tenant,name,payload) VALUES($1,$2,$3) ON CONFLICT DO NOTHING RETURNING payload,version`, n.Tenant, n.Name, data))
	} else {
		updated, err = scanNamespace(tx.QueryRow(ctx, `UPDATE access_namespaces SET payload=$3,version=version+1 WHERE tenant=$1 AND name=$2 AND version=$4 AND payload->>'owner'=$5 AND payload->>'kind'=$6 RETURNING payload,version`, n.Tenant, n.Name, data, version, n.Owner, n.Kind))
	}
	if err == store.ErrNotFound {
		return nil, store.ErrConflict
	}
	if err != nil {
		return nil, err
	}
	_, err = tx.Exec(ctx, `INSERT INTO access_namespace_audit(tenant,name,actor,payload,version) VALUES($1,$2,$3,$4,$5)`, n.Tenant, n.Name, actor, data, updated.Version)
	if err != nil {
		return nil, err
	}
	return updated, tx.Commit(ctx)
}

func (r *accessRepo) TransferNamespace(ctx context.Context, tenant, name, owner string, version int64, actor string) (*controlmodel.Namespace, error) {
	tx, err := r.s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	n, err := scanNamespace(tx.QueryRow(ctx, `SELECT payload,version FROM access_namespaces WHERE tenant=$1 AND name=$2 FOR UPDATE`, tenant, name))
	if err != nil {
		return nil, err
	}
	if n.Version != version || n.Kind != "shared" || n.Archived || owner == "" {
		return nil, store.ErrConflict
	}
	if n.Members == nil {
		n.Members = map[string][]string{}
	}
	if !slices.Contains(n.Members[n.Owner], "admin") {
		n.Members[n.Owner] = append(n.Members[n.Owner], "admin")
	}
	n.Owner = owner
	data, err := json.Marshal(n)
	if err != nil {
		return nil, err
	}
	updated, err := scanNamespace(tx.QueryRow(ctx, `UPDATE access_namespaces SET payload=$3,version=version+1 WHERE tenant=$1 AND name=$2 AND version=$4 RETURNING payload,version`, tenant, name, data, version))
	if err != nil {
		return nil, err
	}
	_, err = tx.Exec(ctx, `INSERT INTO access_namespace_audit(tenant,name,actor,payload,version) VALUES($1,$2,$3,$4,$5)`, tenant, name, actor, data, updated.Version)
	if err != nil {
		return nil, err
	}
	return updated, tx.Commit(ctx)
}

func (r *accessRepo) ListNamespaceAudit(ctx context.Context, tenant, name string, limit, offset int) ([]*controlmodel.NamespaceAudit, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	rows, err := r.s.pool.Query(ctx, `SELECT id,tenant,name,actor,payload,version,created_at FROM access_namespace_audit WHERE tenant=$1 AND ($2='' OR name=$2) ORDER BY id DESC LIMIT $3 OFFSET $4`, tenant, name, limit, maxInt(offset, 0))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []*controlmodel.NamespaceAudit{}
	for rows.Next() {
		a := &controlmodel.NamespaceAudit{}
		var data []byte
		if err := rows.Scan(&a.ID, &a.Tenant, &a.Name, &a.Actor, &data, &a.Version, &a.CreatedAt); err != nil {
			return nil, err
		}
		if err := json.Unmarshal(data, &a.Namespace); err != nil {
			return nil, err
		}
		a.Namespace.Version = a.Version
		items = append(items, a)
	}
	return items, rows.Err()
}

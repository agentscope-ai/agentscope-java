/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package postgres

import (
	"context"
	"encoding/json"
	"errors"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type applicationRepo struct{ pool *pgxpool.Pool }

const applicationCols = `id,tenant,namespace,name,description,owner_user_id,members,status,version,created_at,updated_at,max_concurrent,token_budget,tokens_used`

func scanApplication(row scannable) (*model.Application, error) {
	v := &model.Application{}
	var members json.RawMessage
	if err := row.Scan(&v.ID, &v.Tenant, &v.Namespace, &v.Name, &v.Description, &v.OwnerUserID, &members, &v.Status, &v.Version, &v.CreatedAt, &v.UpdatedAt, &v.MaxConcurrent, &v.TokenBudget, &v.TokensUsed); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	if err := json.Unmarshal(members, &v.Members); err != nil {
		return nil, err
	}
	return v, nil
}
func (r *applicationRepo) Create(ctx context.Context, in *model.Application) (*model.Application, error) {
	members, _ := json.Marshal(in.Members)
	if in.Members == nil {
		members = []byte(`[]`)
	}
	v, err := scanApplication(r.pool.QueryRow(ctx, `INSERT INTO applications(id,tenant,namespace,name,description,owner_user_id,members,status,max_concurrent,token_budget) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) RETURNING `+applicationCols, uuid.New(), in.Tenant, in.Namespace, in.Name, in.Description, in.OwnerUserID, members, in.Status, in.MaxConcurrent, in.TokenBudget))
	return v, catalogConflict(err)
}
func (r *applicationRepo) Get(ctx context.Context, id uuid.UUID) (*model.Application, error) {
	return scanApplication(r.pool.QueryRow(ctx, `SELECT `+applicationCols+` FROM applications WHERE id=$1`, id))
}
func (r *applicationRepo) List(ctx context.Context, tenant, namespace string) ([]*model.Application, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+applicationCols+` FROM applications WHERE tenant=$1 AND namespace=$2 ORDER BY created_at DESC`, tenant, namespace)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*model.Application{}
	for rows.Next() {
		v, e := scanApplication(rows)
		if e != nil {
			return nil, e
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
func (r *applicationRepo) Update(ctx context.Context, in *model.Application, version int64) (*model.Application, error) {
	members, _ := json.Marshal(in.Members)
	if in.Members == nil {
		members = []byte(`[]`)
	}
	v, err := scanApplication(r.pool.QueryRow(ctx, `UPDATE applications SET name=$3,description=$4,members=$5,status=$6,max_concurrent=$7,token_budget=$8,version=version+1,updated_at=now() WHERE id=$1 AND version=$2 RETURNING `+applicationCols, in.ID, version, in.Name, in.Description, members, in.Status, in.MaxConcurrent, in.TokenBudget))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return v, catalogConflict(err)
}

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

package policychanges

import (
	"context"
	_ "embed"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed schema.sql
var SchemaSQL string

type Postgres struct{ Pool *pgxpool.Pool }

func (r *Postgres) Claim(ctx context.Context, now time.Time, ttl time.Duration, limit int) ([]Change, error) {
	if limit < 1 || limit > 100 || ttl <= 0 || ttl > time.Minute {
		return nil, fmt.Errorf("invalid policy change claim limits")
	}
	token := uuid.NewString()
	rows, err := r.Pool.Query(ctx, `WITH candidates AS (
 SELECT id FROM policy_change_outbox WHERE delivered_at IS NULL
 AND (claim_until IS NULL OR claim_until<=$1) ORDER BY created_at,id
 FOR UPDATE SKIP LOCKED LIMIT $2)
 UPDATE policy_change_outbox o SET claim_token=$3,claim_until=$1+$4::interval
 FROM candidates c WHERE o.id=c.id RETURNING o.id,o.tenant,o.namespace,o.application_id,o.claim_token,o.created_at`, now, limit, token, fmt.Sprintf("%f seconds", ttl.Seconds()))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Change{}
	for rows.Next() {
		var v Change
		if err = rows.Scan(&v.ID, &v.Tenant, &v.Namespace, &v.ApplicationID, &v.ClaimToken, &v.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
func (r *Postgres) Acknowledge(ctx context.Context, id uuid.UUID, token string) error {
	tag, err := r.Pool.Exec(ctx, `UPDATE policy_change_outbox SET delivered_at=coalesce(delivered_at,now()),claim_until=NULL
 WHERE id=$1 AND claim_token=$2 AND (delivered_at IS NOT NULL OR claim_until>now())`, id, token)
	if err != nil {
		return err
	}
	if tag.RowsAffected() != 1 {
		return ErrClaim
	}
	return nil
}

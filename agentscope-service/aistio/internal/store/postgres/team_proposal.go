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
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type teamProposalRepo struct{ pool *pgxpool.Pool }

func scanTeamProposal(row scannable) (*controlmodel.TeamProposal, error) {
	v := &controlmodel.TeamProposal{}
	var members []byte
	if err := row.Scan(&v.ID, &v.IssueID, &v.Tenant, &v.Namespace, &v.Requirements, &members, &v.Status, &v.RunID, &v.Version, &v.CreatedAt, &v.UpdatedAt); err != nil {
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
func (r *teamProposalRepo) Create(ctx context.Context, in *controlmodel.TeamProposal) (*controlmodel.TeamProposal, error) {
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	members, err := json.Marshal(in.Members)
	if err != nil {
		return nil, err
	}
	return scanTeamProposal(r.pool.QueryRow(ctx, `INSERT INTO team_proposals(id,issue_id,tenant,namespace,requirements,members,status) VALUES($1,$2,$3,$4,$5,$6,'proposed') RETURNING id,issue_id,tenant,namespace,requirements,members,status,run_id,version,created_at,updated_at`, in.ID, in.IssueID, in.Tenant, in.Namespace, in.Requirements, members))
}
func (r *teamProposalRepo) Get(ctx context.Context, id uuid.UUID) (*controlmodel.TeamProposal, error) {
	return scanTeamProposal(r.pool.QueryRow(ctx, `SELECT id,issue_id,tenant,namespace,requirements,members,status,run_id,version,created_at,updated_at FROM team_proposals WHERE id=$1`, id))
}
func (r *teamProposalRepo) Confirm(ctx context.Context, id uuid.UUID, expected int64, runID uuid.UUID) (*controlmodel.TeamProposal, error) {
	v, err := scanTeamProposal(r.pool.QueryRow(ctx, `UPDATE team_proposals SET status='confirmed',run_id=$3,version=version+1,updated_at=now() WHERE id=$1 AND version=$2 AND status='proposed' RETURNING id,issue_id,tenant,namespace,requirements,members,status,run_id,version,created_at,updated_at`, id, expected, runID))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return v, err
}

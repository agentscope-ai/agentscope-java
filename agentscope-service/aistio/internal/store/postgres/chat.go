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
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type chatRepo struct{ pool *pgxpool.Pool }

const chatColumns = `id, tenant, namespace, creator_ref, agent_id, agent_name,
		session_fk, runtime_session_id, title, status, pinned, last_read_seq,
		version, created_at, updated_at`

func (r *chatRepo) Create(ctx context.Context, in *controlmodel.Chat) (*controlmodel.Chat, error) {
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	if in.Status == "" {
		in.Status = controlmodel.ChatActive
	}
	now := time.Now().UTC()
	row := r.pool.QueryRow(ctx, `INSERT INTO chat_conversations (
		id, tenant, namespace, creator_ref, agent_id, agent_name, session_fk,
		runtime_session_id, title, status, pinned, last_read_seq, version, created_at, updated_at
	) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,1,$13,$13)
	RETURNING `+chatColumns, in.ID, in.Tenant, in.Namespace, in.CreatorRef, in.AgentID,
		in.AgentName, in.SessionID, in.RuntimeSession, in.Title, in.Status, in.Pinned,
		in.LastReadSeq, now)
	out := &controlmodel.Chat{}
	if err := scanChat(row, out); err != nil {
		if isUniqueViolation(err) {
			return nil, store.ErrConflict
		}
		return nil, fmt.Errorf("postgres chat create: %w", err)
	}
	return out, nil
}

func (r *chatRepo) Get(ctx context.Context, id uuid.UUID) (*controlmodel.Chat, error) {
	out := &controlmodel.Chat{}
	if err := scanChat(r.pool.QueryRow(ctx, `SELECT `+chatColumns+` FROM chat_conversations WHERE id=$1`, id), out); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	return out, nil
}

func (r *chatRepo) List(ctx context.Context, filter store.ChatFilter) ([]*controlmodel.Chat, error) {
	conditions := []string{"status = $1"}
	args := []any{controlmodel.ChatActive}
	if filter.Archived {
		args[0] = controlmodel.ChatArchived
	}
	if filter.Deleted {
		args[0] = controlmodel.ChatDeleted
	}
	add := func(column string, value any) {
		args = append(args, value)
		conditions = append(conditions, fmt.Sprintf("%s = $%d", column, len(args)))
	}
	if filter.Tenant != "" {
		add("tenant", filter.Tenant)
	}
	if filter.Namespace != "" {
		add("namespace", filter.Namespace)
	}
	if filter.CreatorRef != "" {
		add("creator_ref", filter.CreatorRef)
	}
	query := `SELECT ` + chatColumns + ` FROM chat_conversations WHERE ` +
		strings.Join(conditions, " AND ") + ` ORDER BY pinned DESC, updated_at DESC`
	if filter.Limit > 0 {
		args = append(args, filter.Limit)
		query += fmt.Sprintf(" LIMIT $%d", len(args))
	}
	if filter.Offset > 0 {
		args = append(args, filter.Offset)
		query += fmt.Sprintf(" OFFSET $%d", len(args))
	}
	rows, err := r.pool.Query(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.Chat, 0)
	for rows.Next() {
		value := &controlmodel.Chat{}
		if err := scanChat(rows, value); err != nil {
			return nil, err
		}
		out = append(out, value)
	}
	return out, rows.Err()
}

func (r *chatRepo) Update(ctx context.Context, in *controlmodel.Chat, expectedVersion int64) (*controlmodel.Chat, error) {
	out := &controlmodel.Chat{}
	err := scanChat(r.pool.QueryRow(ctx, `UPDATE chat_conversations SET
		title=$2, status=$3, pinned=$4, last_read_seq=$5,
		version=version+1, updated_at=now()
		WHERE id=$1 AND version=$6 RETURNING `+chatColumns,
		in.ID, in.Title, in.Status, in.Pinned, in.LastReadSeq, expectedVersion), out)
	if errors.Is(err, pgx.ErrNoRows) {
		if _, getErr := r.Get(ctx, in.ID); errors.Is(getErr, store.ErrNotFound) {
			return nil, store.ErrNotFound
		}
		return nil, store.ErrConflict
	}
	if err != nil {
		return nil, err
	}
	return out, nil
}

func (r *chatRepo) Touch(ctx context.Context, id uuid.UUID) error {
	tag, err := r.pool.Exec(ctx, `UPDATE chat_conversations SET updated_at=now() WHERE id=$1`, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	return nil
}

func scanChat(row scannable, out *controlmodel.Chat) error {
	return row.Scan(&out.ID, &out.Tenant, &out.Namespace, &out.CreatorRef, &out.AgentID,
		&out.AgentName, &out.SessionID, &out.RuntimeSession, &out.Title, &out.Status,
		&out.Pinned, &out.LastReadSeq, &out.Version, &out.CreatedAt, &out.UpdatedAt)
}

func isUniqueViolation(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "23505"
}

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
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type commandRepo struct {
	pool *pgxpool.Pool
}

func (r *commandRepo) Insert(ctx context.Context, cmd *store.SessionCommand) error {
	if cmd.ID == uuid.Nil {
		cmd.ID = uuid.New()
	}
	if cmd.RequestedAt.IsZero() {
		cmd.RequestedAt = time.Now().UTC()
	}
	if cmd.Status == "" {
		cmd.Status = store.CommandStatusAccepted
	}
	return r.pool.QueryRow(ctx, `
		INSERT INTO session_commands (
			id, session_fk, agent_name, namespace, session_id, command, operator, source,
			instance_ref, status, code, error, forced, command_id, requested_at, completed_at, duration_ms
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17)
		RETURNING id`,
		cmd.ID, cmd.SessionFK, cmd.AgentName, cmd.Namespace, cmd.SessionID, cmd.Command,
		nullStr(cmd.Operator), nullStr(cmd.Source), nullStr(cmd.InstanceRef),
		cmd.Status, nullStr(cmd.Code), nullStr(cmd.Error), cmd.Forced, nullStr(cmd.CommandID),
		cmd.RequestedAt, cmd.CompletedAt, cmd.DurationMs,
	).Scan(&cmd.ID)
}

func (r *commandRepo) Update(ctx context.Context, cmd *store.SessionCommand) error {
	tag, err := r.pool.Exec(ctx, `
		UPDATE session_commands SET
			status=$2, code=$3, error=$4, completed_at=$5, duration_ms=$6
		WHERE id=$1`,
		cmd.ID, cmd.Status, nullStr(cmd.Code), nullStr(cmd.Error), cmd.CompletedAt, cmd.DurationMs)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	return nil
}

func (r *commandRepo) GetByCommandID(ctx context.Context, commandID string) (*store.SessionCommand, error) {
	if commandID == "" {
		return nil, store.ErrNotFound
	}
	row := r.pool.QueryRow(ctx, `
		SELECT id, session_fk, agent_name, namespace, session_id, command, operator, source,
			instance_ref, status, code, error, forced, command_id, requested_at, completed_at, duration_ms
		FROM session_commands WHERE command_id=$1`, commandID)
	return scanCommand(row)
}

func (r *commandRepo) List(ctx context.Context, f store.SessionCommandFilter) ([]*store.SessionCommand, error) {
	var (
		conds []string
		args  []any
	)
	add := func(cond string, v any) {
		args = append(args, v)
		conds = append(conds, fmt.Sprintf(cond, len(args)))
	}
	if f.SessionFK != uuid.Nil {
		add("session_fk=$%d", f.SessionFK)
	}
	if f.AgentName != "" {
		add("agent_name=$%d", f.AgentName)
	}
	if f.Namespace != "" {
		add("namespace=$%d", f.Namespace)
	}
	if f.Status != "" {
		add("status=$%d", f.Status)
	}
	if f.Since != nil {
		add("requested_at>=$%d", *f.Since)
	}
	q := `SELECT id, session_fk, agent_name, namespace, session_id, command, operator, source,
		instance_ref, status, code, error, forced, command_id, requested_at, completed_at, duration_ms
		FROM session_commands`
	if len(conds) > 0 {
		q += " WHERE " + strings.Join(conds, " AND ")
	}
	q += " ORDER BY requested_at DESC"
	limit := f.Limit
	if limit <= 0 {
		limit = 50
	}
	args = append(args, limit)
	q += fmt.Sprintf(" LIMIT $%d", len(args))
	rows, err := r.pool.Query(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*store.SessionCommand
	for rows.Next() {
		cmd, err := scanCommand(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, cmd)
	}
	return out, rows.Err()
}

func scanCommand(row scannable) (*store.SessionCommand, error) {
	cmd := &store.SessionCommand{}
	var op, src, iref, code, errMsg, cmdID *string
	err := row.Scan(
		&cmd.ID, &cmd.SessionFK, &cmd.AgentName, &cmd.Namespace, &cmd.SessionID, &cmd.Command,
		&op, &src, &iref, &cmd.Status, &code, &errMsg, &cmd.Forced, &cmdID,
		&cmd.RequestedAt, &cmd.CompletedAt, &cmd.DurationMs,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	cmd.Operator = deref(op)
	cmd.Source = deref(src)
	cmd.InstanceRef = deref(iref)
	cmd.Code = deref(code)
	cmd.Error = deref(errMsg)
	cmd.CommandID = deref(cmdID)
	return cmd, nil
}

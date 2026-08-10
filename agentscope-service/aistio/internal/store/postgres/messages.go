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

type messageRepo struct {
	pool *pgxpool.Pool
}

func (r *messageRepo) Send(ctx context.Context, msg *store.TeamMessage) error {
	if msg.Kind == "" {
		msg.Kind = "message"
	}
	if msg.CreatedAt.IsZero() {
		msg.CreatedAt = time.Now().UTC()
	}
	return r.pool.QueryRow(ctx, `
		INSERT INTO team_messages (
			team_name, namespace, from_member, to_member, content, kind, nonce,
			delivered, delivered_at, attempts, created_at
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11) RETURNING id`,
		msg.TeamName, msg.Namespace, msg.FromMember, nullStr(msg.ToMember), msg.Content,
		msg.Kind, nullStr(msg.Nonce), msg.Delivered, msg.DeliveredAt, msg.Attempts, msg.CreatedAt,
	).Scan(&msg.ID)
}

func (r *messageRepo) ListPending(ctx context.Context, teamName, namespace string) ([]*store.TeamMessage, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT id, team_name, namespace, from_member, to_member, content, kind, nonce,
			delivered, delivered_at, attempts, created_at
		FROM team_messages
		WHERE team_name=$1 AND namespace=$2 AND delivered=false
		ORDER BY created_at ASC`, teamName, namespace)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanMessages(rows)
}

func (r *messageRepo) ListPendingAll(ctx context.Context, limit int) ([]*store.TeamMessage, error) {
	if limit <= 0 {
		limit = 100
	}
	// FOR UPDATE SKIP LOCKED requires a transaction; we claim rows briefly.
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	rows, err := tx.Query(ctx, `
		SELECT id, team_name, namespace, from_member, to_member, content, kind, nonce,
			delivered, delivered_at, attempts, created_at
		FROM team_messages
		WHERE delivered=false
		ORDER BY created_at ASC
		LIMIT $1
		FOR UPDATE SKIP LOCKED`, limit)
	if err != nil {
		return nil, err
	}
	msgs, err := scanMessages(rows)
	rows.Close()
	if err != nil {
		return nil, err
	}
	// Commit without changes — SKIP LOCKED just reserved rows during the tx.
	// Callers then MarkDelivered / IncrementAttempts on their own.
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return msgs, nil
}

func (r *messageRepo) MarkDelivered(ctx context.Context, id int64) error {
	now := time.Now().UTC()
	tag, err := r.pool.Exec(ctx, `
		UPDATE team_messages
		SET delivered=true, delivered_at=$2, attempts=attempts+1
		WHERE id=$1`, id, now)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	return nil
}

func (r *messageRepo) IncrementAttempts(ctx context.Context, id int64) error {
	tag, err := r.pool.Exec(ctx, `
		UPDATE team_messages SET attempts=attempts+1 WHERE id=$1`, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	return nil
}

func (r *messageRepo) History(ctx context.Context, teamName, namespace string, limit int) ([]*store.TeamMessage, error) {
	if limit <= 0 {
		limit = 50
	}
	rows, err := r.pool.Query(ctx, `
		SELECT id, team_name, namespace, from_member, to_member, content, kind, nonce,
			delivered, delivered_at, attempts, created_at
		FROM team_messages
		WHERE team_name=$1 AND namespace=$2
		ORDER BY created_at DESC
		LIMIT $3`, teamName, namespace, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanMessages(rows)
}

func (r *messageRepo) DeleteByTeam(ctx context.Context, teamName, namespace string) error {
	_, err := r.pool.Exec(ctx, `DELETE FROM team_messages WHERE team_name=$1 AND namespace=$2`, teamName, namespace)
	return err
}

type rowsScanner interface {
	Next() bool
	Scan(dest ...any) error
	Err() error
}

func scanMessages(rows rowsScanner) ([]*store.TeamMessage, error) {
	var out []*store.TeamMessage
	for rows.Next() {
		m := &store.TeamMessage{}
		var to, kind, nonce *string
		if err := rows.Scan(
			&m.ID, &m.TeamName, &m.Namespace, &m.FromMember, &to, &m.Content, &kind, &nonce,
			&m.Delivered, &m.DeliveredAt, &m.Attempts, &m.CreatedAt,
		); err != nil {
			return nil, err
		}
		m.ToMember = deref(to)
		m.Kind = deref(kind)
		m.Nonce = deref(nonce)
		out = append(out, m)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return out, nil
}

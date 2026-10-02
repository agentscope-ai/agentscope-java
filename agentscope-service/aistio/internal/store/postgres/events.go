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

type eventRepo struct {
	pool *pgxpool.Pool
}

const sessionEventNotifyChannel = "aistio_session_events"

func (r *eventRepo) Append(ctx context.Context, event *store.SessionEvent) error {
	if event.OccurredAt.IsZero() {
		event.OccurredAt = time.Now().UTC()
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("postgres events begin append: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	err = tx.QueryRow(ctx, `
		INSERT INTO session_events (
			session_fk, seq, event_type, role, content, tool_name, tool_input,
			tool_output, tokens_in, tokens_out, duration_ms, framework_meta, occurred_at
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)
		ON CONFLICT (session_fk, seq) DO NOTHING
		RETURNING id`,
		event.SessionFK, event.Seq, event.EventType, nullStr(event.Role), nullStr(event.Content),
		nullStr(event.ToolName), nullJSON(event.ToolInput), nullStr(event.ToolOutput),
		nullInt(event.TokensIn), nullInt(event.TokensOut), nullInt(event.DurationMs),
		nullJSON(event.FrameworkMeta), event.OccurredAt,
	).Scan(&event.ID)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			// (session_fk, seq) already exists — idempotent success, aligned
			// with the memory driver.
			return store.ErrConflict
		}
		return fmt.Errorf("postgres events append: %w", err)
	}
	if _, err := tx.Exec(ctx, "SELECT pg_notify('"+sessionEventNotifyChannel+"', $1)", event.SessionFK.String()); err != nil {
		return fmt.Errorf("postgres events notify: %w", err)
	}
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("postgres events commit: %w", err)
	}
	return nil
}

func (r *eventRepo) WaitForNew(ctx context.Context, sessionFK uuid.UUID, afterSeq int) error {
	conn, err := r.pool.Acquire(ctx)
	if err != nil {
		return fmt.Errorf("postgres events acquire listener: %w", err)
	}
	defer conn.Release()
	if _, err := conn.Exec(ctx, "LISTEN "+sessionEventNotifyChannel); err != nil {
		return fmt.Errorf("postgres events listen: %w", err)
	}
	defer func() {
		cleanupCtx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		_, _ = conn.Exec(cleanupCtx, "UNLISTEN "+sessionEventNotifyChannel)
	}()

	// LISTEN is active before this check, closing the race between the last SSE
	// page read and starting the wait.
	var available bool
	if err := conn.QueryRow(ctx,
		"SELECT EXISTS (SELECT 1 FROM session_events WHERE session_fk=$1 AND seq>$2)",
		sessionFK, afterSeq).Scan(&available); err != nil {
		return fmt.Errorf("postgres events check listener cursor: %w", err)
	}
	if available {
		return nil
	}
	for {
		notification, err := conn.Conn().WaitForNotification(ctx)
		if err != nil {
			return err
		}
		if notification.Payload == sessionFK.String() {
			return nil
		}
	}
}

func (r *eventRepo) List(ctx context.Context, sessionFK uuid.UUID, opts ...store.EventOption) ([]*store.SessionEvent, error) {
	o := store.ResolveEventOptions(opts)
	var (
		conds = []string{"session_fk=$1"}
		args  = []any{sessionFK}
	)
	if o.EventType != "" {
		args = append(args, o.EventType)
		conds = append(conds, fmt.Sprintf("event_type=$%d", len(args)))
	}
	if o.Since != nil {
		args = append(args, *o.Since)
		conds = append(conds, fmt.Sprintf("occurred_at>=$%d", len(args)))
	}
	if o.Until != nil {
		args = append(args, *o.Until)
		conds = append(conds, fmt.Sprintf("occurred_at<=$%d", len(args)))
	}
	if o.Before != nil {
		args = append(args, *o.Before)
		conds = append(conds, fmt.Sprintf("occurred_at<$%d", len(args)))
	}
	if o.BeforeSeq != nil {
		args = append(args, *o.BeforeSeq)
		conds = append(conds, fmt.Sprintf("seq<$%d", len(args)))
	}
	if o.AfterSeq != nil {
		args = append(args, *o.AfterSeq)
		conds = append(conds, fmt.Sprintf("seq>$%d", len(args)))
	}

	where := strings.Join(conds, " AND ")
	order := "ORDER BY seq ASC"
	if o.NewestFirst && o.Limit > 0 {
		// Newest page: DESC + LIMIT, then reverse to chronological ASC for callers.
		q := `SELECT id, session_fk, seq, event_type, role, content, tool_name, tool_input,
			tool_output, tokens_in, tokens_out, duration_ms, framework_meta, occurred_at
			FROM session_events WHERE ` + where + ` ORDER BY seq DESC`
		args = append(args, o.Limit)
		q += fmt.Sprintf(" LIMIT $%d", len(args))
		out, err := r.scanEvents(ctx, q, args)
		if err != nil {
			return nil, err
		}
		for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
			out[i], out[j] = out[j], out[i]
		}
		return out, nil
	}

	q := `SELECT id, session_fk, seq, event_type, role, content, tool_name, tool_input,
		tool_output, tokens_in, tokens_out, duration_ms, framework_meta, occurred_at
		FROM session_events WHERE ` + where + ` ` + order
	if o.Limit > 0 {
		args = append(args, o.Limit)
		q += fmt.Sprintf(" LIMIT $%d", len(args))
	}
	if o.Offset > 0 {
		args = append(args, o.Offset)
		q += fmt.Sprintf(" OFFSET $%d", len(args))
	}
	return r.scanEvents(ctx, q, args)
}

func (r *eventRepo) scanEvents(ctx context.Context, q string, args []any) ([]*store.SessionEvent, error) {
	rows, err := r.pool.Query(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*store.SessionEvent
	for rows.Next() {
		e := &store.SessionEvent{}
		var role, content, toolName, toolOutput *string
		var toolInput, meta []byte
		var tokensIn, tokensOut, durationMs *int
		if err := rows.Scan(
			&e.ID, &e.SessionFK, &e.Seq, &e.EventType, &role, &content, &toolName, &toolInput,
			&toolOutput, &tokensIn, &tokensOut, &durationMs, &meta, &e.OccurredAt,
		); err != nil {
			return nil, err
		}
		e.Role = deref(role)
		e.Content = deref(content)
		e.ToolName = deref(toolName)
		e.ToolOutput = deref(toolOutput)
		e.ToolInput = toolInput
		e.FrameworkMeta = meta
		if tokensIn != nil {
			e.TokensIn = *tokensIn
		}
		if tokensOut != nil {
			e.TokensOut = *tokensOut
		}
		if durationMs != nil {
			e.DurationMs = *durationMs
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

func nullInt(n int) any {
	if n == 0 {
		return nil
	}
	return n
}

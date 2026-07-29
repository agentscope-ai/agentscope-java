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

func (r *eventRepo) Append(ctx context.Context, event *store.SessionEvent) error {
	if event.OccurredAt.IsZero() {
		event.OccurredAt = time.Now().UTC()
	}
	err := r.pool.QueryRow(ctx, `
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
	return nil
}

func (r *eventRepo) List(ctx context.Context, sessionFK uuid.UUID, opts ...store.EventOption) ([]*store.SessionEvent, error) {
	eventType, since, until, limit, offset := store.ApplyEventOptions(opts)
	var (
		conds = []string{"session_fk=$1"}
		args  = []any{sessionFK}
	)
	if eventType != "" {
		args = append(args, eventType)
		conds = append(conds, fmt.Sprintf("event_type=$%d", len(args)))
	}
	if since != nil {
		args = append(args, *since)
		conds = append(conds, fmt.Sprintf("occurred_at>=$%d", len(args)))
	}
	if until != nil {
		args = append(args, *until)
		conds = append(conds, fmt.Sprintf("occurred_at<=$%d", len(args)))
	}
	q := `SELECT id, session_fk, seq, event_type, role, content, tool_name, tool_input,
		tool_output, tokens_in, tokens_out, duration_ms, framework_meta, occurred_at
		FROM session_events WHERE ` + strings.Join(conds, " AND ") + ` ORDER BY seq ASC`
	if limit > 0 {
		args = append(args, limit)
		q += fmt.Sprintf(" LIMIT $%d", len(args))
	}
	if offset > 0 {
		args = append(args, offset)
		q += fmt.Sprintf(" OFFSET $%d", len(args))
	}
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

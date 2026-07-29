package postgres

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type metricsRepo struct {
	pool *pgxpool.Pool
}

func (r *metricsRepo) RecordTokenUsage(ctx context.Context, m *store.TokenUsageMetric) error {
	if m.RecordedAt.IsZero() {
		m.RecordedAt = time.Now().UTC()
	}
	return r.pool.QueryRow(ctx, `
		INSERT INTO token_usage_metrics (
			session_fk, agent_name, namespace, model, provider,
			prompt_tokens, completion_tokens, total_tokens, recorded_at
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) RETURNING id`,
		m.SessionFK, m.AgentName, m.Namespace, nullStr(m.Model), nullStr(m.Provider),
		m.PromptTokens, m.CompletionTokens, m.TotalTokens, m.RecordedAt,
	).Scan(&m.ID)
}

func (r *metricsRepo) RecordSnapshot(ctx context.Context, s *store.SessionSnapshot) error {
	if s.CapturedAt.IsZero() {
		s.CapturedAt = time.Now().UTC()
	}
	return r.pool.QueryRow(ctx, `
		INSERT INTO session_snapshots (
			session_fk, captured_at, message_count, prompt_tokens, completion_tokens,
			total_tokens, context_pressure, is_compacted, effective_message_count,
			context_hash, task_summary
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11) RETURNING id`,
		s.SessionFK, s.CapturedAt, s.MessageCount, s.PromptTokens, s.CompletionTokens,
		s.TotalTokens, s.ContextPressure, s.IsCompacted, s.EffectiveMessageCount,
		nullStr(s.ContextHash), nullJSON(s.TaskSummary),
	).Scan(&s.ID)
}

func (r *metricsRepo) RecordAgentMetric(ctx context.Context, m *store.AgentMetric) error {
	if m.RecordedAt.IsZero() {
		m.RecordedAt = time.Now().UTC()
	}
	return r.pool.QueryRow(ctx, `
		INSERT INTO agent_metrics (
			agent_name, namespace, recorded_at, active_sessions, total_messages,
			total_tokens, avg_context_pressure, error_count, uptime_seconds
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) RETURNING id`,
		m.AgentName, m.Namespace, m.RecordedAt, m.ActiveSessions, m.TotalMessages,
		m.TotalTokens, m.AvgContextPressure, m.ErrorCount, m.UptimeSeconds,
	).Scan(&m.ID)
}

func (r *metricsRepo) LatestSnapshot(ctx context.Context, sessionFK uuid.UUID) (*store.SessionSnapshot, error) {
	row := r.pool.QueryRow(ctx, `
		SELECT id, session_fk, captured_at, message_count, prompt_tokens, completion_tokens,
			total_tokens, context_pressure, is_compacted, effective_message_count,
			context_hash, task_summary
		FROM session_snapshots
		WHERE session_fk=$1
		ORDER BY captured_at DESC
		LIMIT 1`, sessionFK)
	s := &store.SessionSnapshot{}
	var hash *string
	var summary []byte
	if err := row.Scan(
		&s.ID, &s.SessionFK, &s.CapturedAt, &s.MessageCount, &s.PromptTokens, &s.CompletionTokens,
		&s.TotalTokens, &s.ContextPressure, &s.IsCompacted, &s.EffectiveMessageCount,
		&hash, &summary,
	); err != nil {
		if err == pgx.ErrNoRows {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	s.ContextHash = deref(hash)
	s.TaskSummary = summary
	return s, nil
}

func (r *metricsRepo) LatestSnapshots(ctx context.Context, sessionFKs []uuid.UUID) (map[uuid.UUID]*store.SessionSnapshot, error) {
	out := make(map[uuid.UUID]*store.SessionSnapshot)
	if len(sessionFKs) == 0 {
		return out, nil
	}
	rows, err := r.pool.Query(ctx, `
		SELECT DISTINCT ON (session_fk)
			id, session_fk, captured_at, message_count, prompt_tokens, completion_tokens,
			total_tokens, context_pressure, is_compacted, effective_message_count,
			context_hash, task_summary
		FROM session_snapshots
		WHERE session_fk = ANY($1)
		ORDER BY session_fk, captured_at DESC`, sessionFKs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		s := &store.SessionSnapshot{}
		var hash *string
		var summary []byte
		if err := rows.Scan(
			&s.ID, &s.SessionFK, &s.CapturedAt, &s.MessageCount, &s.PromptTokens, &s.CompletionTokens,
			&s.TotalTokens, &s.ContextPressure, &s.IsCompacted, &s.EffectiveMessageCount,
			&hash, &summary,
		); err != nil {
			return nil, err
		}
		s.ContextHash = deref(hash)
		s.TaskSummary = summary
		out[s.SessionFK] = s
	}
	return out, rows.Err()
}

func (r *metricsRepo) QueryTokenUsage(ctx context.Context, f store.TokenFilter) ([]*store.TokenUsageMetric, error) {
	var (
		conds []string
		args  []any
	)
	add := func(cond string, v any) {
		args = append(args, v)
		conds = append(conds, fmt.Sprintf(cond, len(args)))
	}
	if f.AgentName != "" {
		add("agent_name=$%d", f.AgentName)
	}
	if f.Namespace != "" {
		add("namespace=$%d", f.Namespace)
	}
	if f.Model != "" {
		add("model=$%d", f.Model)
	}
	if f.Since != nil {
		add("recorded_at>=$%d", *f.Since)
	}
	if f.Until != nil {
		add("recorded_at<=$%d", *f.Until)
	}
	q := `SELECT id, session_fk, agent_name, namespace, model, provider,
		prompt_tokens, completion_tokens, total_tokens, recorded_at FROM token_usage_metrics`
	if len(conds) > 0 {
		q += " WHERE " + strings.Join(conds, " AND ")
	}
	q += " ORDER BY recorded_at DESC"
	if f.Limit > 0 {
		args = append(args, f.Limit)
		q += fmt.Sprintf(" LIMIT $%d", len(args))
	}
	rows, err := r.pool.Query(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*store.TokenUsageMetric
	for rows.Next() {
		m := &store.TokenUsageMetric{}
		var model, provider *string
		if err := rows.Scan(
			&m.ID, &m.SessionFK, &m.AgentName, &m.Namespace, &model, &provider,
			&m.PromptTokens, &m.CompletionTokens, &m.TotalTokens, &m.RecordedAt,
		); err != nil {
			return nil, err
		}
		m.Model = deref(model)
		m.Provider = deref(provider)
		out = append(out, m)
	}
	return out, rows.Err()
}

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
	conds, args := tokenFilterConds(f)
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

func (r *metricsRepo) QueryAgentMetrics(ctx context.Context, f store.AgentMetricFilter) ([]*store.AgentMetric, error) {
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
	if f.Since != nil {
		add("recorded_at>=$%d", *f.Since)
	}
	if f.Until != nil {
		add("recorded_at<=$%d", *f.Until)
	}
	q := `SELECT id, agent_name, namespace, recorded_at, active_sessions, total_messages,
		total_tokens, avg_context_pressure, error_count, uptime_seconds FROM agent_metrics`
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
	var out []*store.AgentMetric
	for rows.Next() {
		m := &store.AgentMetric{}
		if err := rows.Scan(
			&m.ID, &m.AgentName, &m.Namespace, &m.RecordedAt, &m.ActiveSessions, &m.TotalMessages,
			&m.TotalTokens, &m.AvgContextPressure, &m.ErrorCount, &m.UptimeSeconds,
		); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func (r *metricsRepo) AggregateTokens(ctx context.Context, f store.TokenFilter, bucket time.Duration) ([]store.TokenBucket, error) {
	if bucket <= 0 {
		bucket = time.Hour
	}
	trunc := "hour"
	switch {
	case bucket >= 24*time.Hour:
		trunc = "day"
	case bucket >= time.Hour:
		trunc = "hour"
	default:
		trunc = "minute"
	}
	conds, args := tokenFilterConds(f)
	q := fmt.Sprintf(`
		SELECT date_trunc('%s', recorded_at) AS bucket_start,
			COALESCE(SUM(prompt_tokens),0), COALESCE(SUM(completion_tokens),0),
			COALESCE(SUM(total_tokens),0), COUNT(*)
		FROM token_usage_metrics`, trunc)
	if len(conds) > 0 {
		q += " WHERE " + strings.Join(conds, " AND ")
	}
	q += " GROUP BY bucket_start ORDER BY bucket_start ASC"
	rows, err := r.pool.Query(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []store.TokenBucket
	for rows.Next() {
		var b store.TokenBucket
		if err := rows.Scan(&b.BucketStart, &b.PromptTokens, &b.CompletionTokens, &b.TotalTokens, &b.SampleCount); err != nil {
			return nil, err
		}
		out = append(out, b)
	}
	return out, rows.Err()
}

func (r *metricsRepo) TopAgents(ctx context.Context, since time.Time, limit int) ([]store.AgentUsage, error) {
	if limit <= 0 {
		limit = 10
	}
	rows, err := r.pool.Query(ctx, `
		SELECT t.agent_name, t.namespace, t.total_tokens,
			COALESCE(a.active_sessions, 0), COALESCE(a.avg_pressure, 0), COALESCE(a.error_count, 0)
		FROM (
			SELECT agent_name, namespace, SUM(total_tokens)::bigint AS total_tokens
			FROM token_usage_metrics
			WHERE recorded_at >= $1
			GROUP BY agent_name, namespace
		) t
		LEFT JOIN LATERAL (
			SELECT active_sessions, avg_context_pressure AS avg_pressure, error_count
			FROM agent_metrics am
			WHERE am.agent_name = t.agent_name AND am.namespace = t.namespace
			ORDER BY recorded_at DESC
			LIMIT 1
		) a ON true
		ORDER BY t.total_tokens DESC
		LIMIT $2`, since, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []store.AgentUsage
	for rows.Next() {
		var u store.AgentUsage
		if err := rows.Scan(&u.AgentName, &u.Namespace, &u.TotalTokens, &u.ActiveSessions, &u.AvgPressure, &u.ErrorCount); err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

func (r *metricsRepo) TopSessionsByTokens(ctx context.Context, since time.Time, limit int) ([]store.SessionUsage, error) {
	if limit <= 0 {
		limit = 10
	}
	rows, err := r.pool.Query(ctx, `
		SELECT s.id, s.session_id, s.agent_name, s.namespace, s.phase, SUM(t.total_tokens)::bigint
		FROM token_usage_metrics t
		INNER JOIN sessions s ON s.id = t.session_fk
		WHERE t.recorded_at >= $1 AND t.session_fk IS NOT NULL
		GROUP BY s.id, s.session_id, s.agent_name, s.namespace, s.phase
		ORDER BY SUM(t.total_tokens) DESC
		LIMIT $2`, since, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []store.SessionUsage
	for rows.Next() {
		var u store.SessionUsage
		if err := rows.Scan(&u.SessionFK, &u.SessionID, &u.AgentName, &u.Namespace, &u.Phase, &u.TotalTokens); err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

func (r *metricsRepo) TopSessionsByDuration(ctx context.Context, since time.Time, limit int) ([]store.SessionDuration, error) {
	if limit <= 0 {
		limit = 10
	}
	// Active sessions only, ranked by current running turn elapsed.
	_ = since // activity window unused: live ranking is point-in-time for running turns
	rows, err := r.pool.Query(ctx, `
		SELECT s.id, s.session_id, s.agent_name, s.namespace, s.phase, t.started_at,
			now() AT TIME ZONE 'utc' AS ended_at,
			(EXTRACT(EPOCH FROM (now() AT TIME ZONE 'utc' - t.started_at)) * 1000)::bigint AS duration_ms,
			t.turn_index
		FROM sessions s
		INNER JOIN session_turns t ON t.session_fk = s.id AND t.status = $1
		WHERE lower(s.phase) = $2
		ORDER BY duration_ms DESC
		LIMIT $3`, store.TurnStatusRunning, store.SessionPhaseActive, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []store.SessionDuration
	for rows.Next() {
		var d store.SessionDuration
		if err := rows.Scan(
			&d.SessionFK, &d.SessionID, &d.AgentName, &d.Namespace, &d.Phase,
			&d.StartedAt, &d.EndedAt, &d.DurationMs, &d.TurnIndex,
		); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

func (r *metricsRepo) TopAgentsByActiveSessions(ctx context.Context, since time.Time, limit int) ([]store.AgentUsage, error) {
	if limit <= 0 {
		limit = 10
	}
	rows, err := r.pool.Query(ctx, `
		SELECT agent_name, namespace, MAX(active_sessions)::int
		FROM agent_metrics
		WHERE recorded_at >= $1
		GROUP BY agent_name, namespace
		ORDER BY MAX(active_sessions) DESC
		LIMIT $2`, since, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []store.AgentUsage
	for rows.Next() {
		var u store.AgentUsage
		if err := rows.Scan(&u.AgentName, &u.Namespace, &u.ActiveSessions); err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

func (r *metricsRepo) PressureStats(ctx context.Context, f store.SessionFilter) (avg, p95 float64, err error) {
	conds, args := sessionFilterCondsPrefixed(f, "s")
	where := ""
	if len(conds) > 0 {
		where = " WHERE " + strings.Join(conds, " AND ")
	}
	q := `
		SELECT COALESCE(AVG(snap.context_pressure), 0),
			COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY snap.context_pressure), 0)
		FROM sessions s
		INNER JOIN LATERAL (
			SELECT context_pressure FROM session_snapshots ss
			WHERE ss.session_fk = s.id
			ORDER BY ss.captured_at DESC
			LIMIT 1
		) snap ON true` + where
	err = r.pool.QueryRow(ctx, q, args...).Scan(&avg, &p95)
	return avg, p95, err
}

func (r *metricsRepo) SumTokenUsage(ctx context.Context, f store.TokenFilter) (int64, error) {
	conds, args := tokenFilterConds(f)
	q := `SELECT COALESCE(SUM(total_tokens), 0) FROM token_usage_metrics`
	if len(conds) > 0 {
		q += " WHERE " + strings.Join(conds, " AND ")
	}
	var total int64
	err := r.pool.QueryRow(ctx, q, args...).Scan(&total)
	return total, err
}

func (r *metricsRepo) SumErrorCount(ctx context.Context, f store.AgentMetricFilter) (int32, error) {
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
	if f.Since != nil {
		add("recorded_at>=$%d", *f.Since)
	}
	if f.Until != nil {
		add("recorded_at<=$%d", *f.Until)
	}
	q := `SELECT COALESCE(SUM(error_count), 0) FROM agent_metrics`
	if len(conds) > 0 {
		q += " WHERE " + strings.Join(conds, " AND ")
	}
	var total int32
	err := r.pool.QueryRow(ctx, q, args...).Scan(&total)
	return total, err
}

func tokenFilterConds(f store.TokenFilter) (conds []string, args []any) {
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
	return conds, args
}

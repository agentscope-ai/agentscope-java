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

type sessionRepo struct {
	pool *pgxpool.Pool
}

const sessionColumns = `id, tenant, session_id, agent_id, binding_id, agent_instance_id, instance_generation,
			agent_name, namespace, framework, framework_version,
			phase, busy, instance_ref, instance_ip, agent_task_id, origin_type, origin_ref, task_context,
			started_at, last_active_at, terminated_at, created_at, updated_at`

func (r *sessionRepo) Upsert(ctx context.Context, s *store.Session) (*store.Session, error) {
	if s.Tenant == "" {
		s.Tenant = "default"
	}
	if s.Phase == "" {
		s.Phase = store.SessionPhaseActive
	}
	now := time.Now().UTC()
	conflictTarget := `(tenant, agent_name, namespace, session_id) WHERE agent_id IS NULL`
	if s.AgentID != uuid.Nil {
		conflictTarget = `(tenant, agent_id, session_id) WHERE agent_id IS NOT NULL`
	}
	row := r.pool.QueryRow(ctx, `
		INSERT INTO sessions (
			tenant, session_id, agent_id, binding_id, agent_instance_id, instance_generation,
			agent_name, namespace, framework, framework_version,
			phase, busy, instance_ref, instance_ip, agent_task_id, origin_type, origin_ref, task_context,
			started_at, last_active_at, terminated_at, created_at, updated_at
		) VALUES (
			$1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23
		)
		ON CONFLICT `+conflictTarget+` DO UPDATE SET
			agent_id = COALESCE(EXCLUDED.agent_id, sessions.agent_id),
			binding_id = COALESCE(EXCLUDED.binding_id, sessions.binding_id),
			agent_instance_id = COALESCE(EXCLUDED.agent_instance_id, sessions.agent_instance_id),
			instance_generation = GREATEST(EXCLUDED.instance_generation, sessions.instance_generation),
			agent_name = COALESCE(NULLIF(EXCLUDED.agent_name, ''), sessions.agent_name),
			framework = COALESCE(NULLIF(EXCLUDED.framework, ''), sessions.framework),
			framework_version = COALESCE(EXCLUDED.framework_version, sessions.framework_version),
			phase = EXCLUDED.phase,
			busy = EXCLUDED.busy,
			instance_ref = COALESCE(EXCLUDED.instance_ref, sessions.instance_ref),
			instance_ip = COALESCE(EXCLUDED.instance_ip, sessions.instance_ip),
			agent_task_id = COALESCE(EXCLUDED.agent_task_id, sessions.agent_task_id),
			origin_type = CASE
				WHEN EXCLUDED.origin_type = 'runtime'
					AND sessions.origin_type IS NOT NULL
					AND sessions.origin_type <> 'runtime'
				THEN sessions.origin_type
				ELSE COALESCE(EXCLUDED.origin_type, sessions.origin_type)
			END,
			origin_ref = CASE
				WHEN EXCLUDED.origin_type = 'runtime'
					AND sessions.origin_type IS NOT NULL
					AND sessions.origin_type <> 'runtime'
				THEN sessions.origin_ref
				ELSE COALESCE(EXCLUDED.origin_ref, sessions.origin_ref)
			END,
			task_context = COALESCE(EXCLUDED.task_context, sessions.task_context),
			started_at = COALESCE(EXCLUDED.started_at, sessions.started_at),
			last_active_at = COALESCE(EXCLUDED.last_active_at, sessions.last_active_at),
			terminated_at = EXCLUDED.terminated_at,
			updated_at = EXCLUDED.updated_at
		RETURNING `+sessionColumns,
		s.Tenant, s.SessionID, nullUUID(s.AgentID), nullUUID(s.BindingID), nullUUID(s.AgentInstanceID), s.InstanceGeneration,
		s.AgentName, s.Namespace, s.Framework, nullStr(s.FrameworkVersion),
		s.Phase, s.Busy, nullStr(s.InstanceRef), nullStr(s.InstanceIP), s.AgentTaskID,
		nullStr(s.OriginType), nullStr(s.OriginRef), nullJSON(s.TaskContext),
		s.StartedAt, s.LastActiveAt, s.TerminatedAt, now, now,
	)
	out := &store.Session{}
	if err := scanSession(row, out); err != nil {
		return nil, fmt.Errorf("postgres sessions upsert: %w", err)
	}
	return out, nil
}

func (r *sessionRepo) Get(ctx context.Context, tenant, agentName, namespace, sessionID string) (*store.Session, error) {
	row := r.pool.QueryRow(ctx, `
		SELECT `+sessionColumns+`
		FROM sessions WHERE tenant=$1 AND agent_name=$2 AND namespace=$3 AND session_id=$4`,
		tenant, agentName, namespace, sessionID)
	out := &store.Session{}
	if err := scanSession(row, out); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	return out, nil
}

func (r *sessionRepo) GetByID(ctx context.Context, id uuid.UUID) (*store.Session, error) {
	row := r.pool.QueryRow(ctx, `
		SELECT `+sessionColumns+`
		FROM sessions WHERE id=$1`, id)
	out := &store.Session{}
	if err := scanSession(row, out); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	return out, nil
}

func (r *sessionRepo) List(ctx context.Context, f store.SessionFilter) ([]*store.Session, error) {
	conds, args := sessionFilterConds(f)
	if access := store.WorkAccessFrom(ctx); access.Restricted {
		args = append(args, access.Refs)
		n := len(args)
		conds = append(conds, fmt.Sprintf(`((agent_task_id IS NOT NULL AND EXISTS(SELECT 1 FROM agent_tasks t WHERE t.id=sessions.agent_task_id AND issue_access_allowed(t.issue_id,$%d::text[]))) OR (agent_task_id IS NULL AND EXISTS(SELECT 1 FROM chat_conversations ch WHERE ch.session_fk=sessions.id AND ch.creator_ref=ANY($%d::text[]))))`, n, n))
	}
	q := `SELECT ` + sessionColumns + ` FROM sessions`
	if len(conds) > 0 {
		q += " WHERE " + strings.Join(conds, " AND ")
	}
	q += " ORDER BY created_at DESC"
	if f.Limit > 0 {
		args = append(args, f.Limit)
		q += fmt.Sprintf(" LIMIT $%d", len(args))
	}
	if f.Offset > 0 {
		args = append(args, f.Offset)
		q += fmt.Sprintf(" OFFSET $%d", len(args))
	}
	rows, err := r.pool.Query(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*store.Session
	for rows.Next() {
		s := &store.Session{}
		if err := scanSession(rows, s); err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

func (r *sessionRepo) UpdatePhase(ctx context.Context, id uuid.UUID, phase string) error {
	now := time.Now().UTC()
	var terminatedAt any
	if phase == store.SessionPhaseTerminated {
		terminatedAt = now
	}
	tag, err := r.pool.Exec(ctx, `
		UPDATE sessions SET phase=$2, terminated_at=COALESCE($3, terminated_at), updated_at=$4
		WHERE id=$1`, id, phase, terminatedAt, now)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	return nil
}

func (r *sessionRepo) ArchiveMissing(ctx context.Context, tenant, agentName, namespace string, keepSessionIDs []string, olderThan time.Duration) (int, error) {
	cutoff := time.Now().UTC().Add(-olderThan)
	keep := keepSessionIDs
	if keep == nil {
		keep = []string{}
	}
	tag, err := r.pool.Exec(ctx, `
		UPDATE sessions
		SET phase=$5, updated_at=now(), busy=false
		WHERE tenant=$1 AND agent_name=$2 AND namespace=$3
		  AND phase NOT IN ($5, $7)
		  AND created_at < $4
		  AND NOT (session_id = ANY($6))`,
		tenant, agentName, namespace, cutoff, store.SessionPhaseArchived, keep, store.SessionPhaseTerminated)
	if err != nil {
		return 0, err
	}
	return int(tag.RowsAffected()), nil
}

func (r *sessionRepo) ArchiveIdleOlderThan(ctx context.Context, olderThan time.Duration) (int, error) {
	if olderThan <= 0 {
		return 0, nil
	}
	cutoff := time.Now().UTC().Add(-olderThan)
	tag, err := r.pool.Exec(ctx, `
		UPDATE sessions
		SET phase=$1, updated_at=now(), busy=false
		WHERE phase = $2
		  AND COALESCE(last_active_at, updated_at, created_at) < $3`,
		store.SessionPhaseArchived, store.SessionPhaseIdle, cutoff)
	if err != nil {
		return 0, err
	}
	return int(tag.RowsAffected()), nil
}

func (r *sessionRepo) CountActive(ctx context.Context, tenant, agentName, namespace string) (int32, error) {
	var n int32
	err := r.pool.QueryRow(ctx, `
		SELECT COUNT(*) FROM sessions
		WHERE tenant=$1 AND agent_name=$2 AND namespace=$3 AND phase NOT IN ($4, $5)`,
		tenant, agentName, namespace, store.SessionPhaseTerminated, store.SessionPhaseArchived).Scan(&n)
	return n, err
}

func (r *sessionRepo) CountByPhase(ctx context.Context, f store.SessionFilter) (map[string]int, error) {
	conds, args := sessionFilterConds(f)
	q := `SELECT lower(phase), COUNT(*) FROM sessions`
	if len(conds) > 0 {
		q += " WHERE " + strings.Join(conds, " AND ")
	}
	q += " GROUP BY lower(phase)"
	rows, err := r.pool.Query(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]int{}
	for rows.Next() {
		var phase string
		var n int
		if err := rows.Scan(&phase, &n); err != nil {
			return nil, err
		}
		out[phase] = n
	}
	return out, rows.Err()
}

func (r *sessionRepo) ListByPressure(ctx context.Context, f store.SessionFilter, minPressure float64, limit int) ([]*store.SessionWithSnapshot, error) {
	if limit <= 0 {
		limit = 10
	}
	conds, args := sessionFilterCondsPrefixed(f, "s")
	args = append(args, minPressure, limit)
	pressureIdx := len(args) - 1
	limitIdx := len(args)
	whereParts := append([]string{}, conds...)
	whereParts = append(whereParts, fmt.Sprintf("snap.context_pressure >= $%d", pressureIdx))
	where := " WHERE " + strings.Join(whereParts, " AND ")
	prefixed := make([]string, 0, 19)
	for _, c := range []string{
		"id", "tenant", "session_id", "agent_id", "binding_id", "agent_instance_id", "instance_generation",
		"agent_name", "namespace", "framework", "framework_version",
		"phase", "busy", "instance_ref", "instance_ip", "agent_task_id", "origin_type", "origin_ref", "task_context",
		"started_at", "last_active_at", "terminated_at", "created_at", "updated_at",
	} {
		prefixed = append(prefixed, "s."+c)
	}
	q := fmt.Sprintf(`
		SELECT %s,
			snap.id, snap.session_fk, snap.captured_at, snap.message_count, snap.prompt_tokens,
			snap.completion_tokens, snap.total_tokens, snap.context_pressure, snap.is_compacted,
			snap.effective_message_count, snap.context_hash, snap.task_summary, snap.token_usage_reported, snap.context_pressure_reported
		FROM sessions s
		INNER JOIN LATERAL (
			SELECT * FROM session_snapshots ss
			WHERE ss.session_fk = s.id
			ORDER BY ss.captured_at DESC
			LIMIT 1
		) snap ON true
		%s
		ORDER BY snap.context_pressure DESC
		LIMIT $%d`, strings.Join(prefixed, ", "), where, limitIdx)
	rows, err := r.pool.Query(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*store.SessionWithSnapshot
	for rows.Next() {
		sess := &store.Session{}
		snap := &store.SessionSnapshot{}
		var hash *string
		var summary []byte
		var fwVer, instRef, instIP, originType, originRef *string
		var agentID, bindingID, agentInstanceID *uuid.UUID
		var taskCtx []byte
		if err := rows.Scan(
			&sess.ID, &sess.Tenant, &sess.SessionID, &agentID, &bindingID, &agentInstanceID, &sess.InstanceGeneration,
			&sess.AgentName, &sess.Namespace, &sess.Framework, &fwVer,
			&sess.Phase, &sess.Busy, &instRef, &instIP, &sess.AgentTaskID, &originType, &originRef, &taskCtx,
			&sess.StartedAt, &sess.LastActiveAt, &sess.TerminatedAt, &sess.CreatedAt, &sess.UpdatedAt,
			&snap.ID, &snap.SessionFK, &snap.CapturedAt, &snap.MessageCount, &snap.PromptTokens,
			&snap.CompletionTokens, &snap.TotalTokens, &snap.ContextPressure, &snap.IsCompacted,
			&snap.EffectiveMessageCount, &hash, &summary, &snap.TokenUsageReported, &snap.ContextPressureReported,
		); err != nil {
			return nil, err
		}
		sess.FrameworkVersion = deref(fwVer)
		sess.AgentID = derefUUID(agentID)
		sess.BindingID = derefUUID(bindingID)
		sess.AgentInstanceID = derefUUID(agentInstanceID)
		sess.InstanceRef = deref(instRef)
		sess.InstanceIP = deref(instIP)
		sess.OriginType = deref(originType)
		sess.OriginRef = deref(originRef)
		sess.TaskContext = taskCtx
		snap.ContextHash = deref(hash)
		snap.TaskSummary = summary
		out = append(out, &store.SessionWithSnapshot{Session: sess, Snapshot: snap})
	}
	return out, rows.Err()
}

func (r *sessionRepo) DeleteByAgent(ctx context.Context, tenant, agentName, namespace string) error {
	_, err := r.pool.Exec(ctx, `DELETE FROM sessions WHERE tenant=$1 AND agent_name=$2 AND namespace=$3`, tenant, agentName, namespace)
	return err
}

func sessionFilterConds(f store.SessionFilter) (conds []string, args []any) {
	return sessionFilterCondsPrefixed(f, "")
}

func sessionFilterCondsPrefixed(f store.SessionFilter, alias string) (conds []string, args []any) {
	col := func(name string) string {
		if alias == "" {
			return name
		}
		return alias + "." + name
	}
	add := func(name string, v any) {
		args = append(args, v)
		conds = append(conds, fmt.Sprintf("%s=$%d", col(name), len(args)))
	}
	if f.Tenant != "" {
		add("tenant", f.Tenant)
	}
	if f.AgentName != "" {
		add("agent_name", f.AgentName)
	}
	if f.AgentID != uuid.Nil {
		add("agent_id", f.AgentID)
	}
	if f.Namespace != "" {
		add("namespace", f.Namespace)
	}
	if f.SessionID != "" {
		add("session_id", f.SessionID)
	}
	if f.Phase != "" {
		add("phase", f.Phase)
	}
	if f.Framework != "" {
		add("framework", f.Framework)
	}
	if f.PendingConversation {
		conds = append(conds, col("task_context")+"->'conversationTurn'->>'state' IN ('dispatching','running')")
	}
	if f.AgentTaskID != uuid.Nil {
		add("agent_task_id", f.AgentTaskID)
	}
	return conds, args
}

type scannable interface {
	Scan(dest ...any) error
}

func scanSession(row scannable, s *store.Session) error {
	var fwVer, instRef, instIP, originType, originRef *string
	var agentID, bindingID, agentInstanceID *uuid.UUID
	var taskCtx []byte
	err := row.Scan(
		&s.ID, &s.Tenant, &s.SessionID, &agentID, &bindingID, &agentInstanceID, &s.InstanceGeneration,
		&s.AgentName, &s.Namespace, &s.Framework, &fwVer,
		&s.Phase, &s.Busy, &instRef, &instIP, &s.AgentTaskID, &originType, &originRef, &taskCtx,
		&s.StartedAt, &s.LastActiveAt, &s.TerminatedAt, &s.CreatedAt, &s.UpdatedAt,
	)
	if err != nil {
		return err
	}
	s.FrameworkVersion = deref(fwVer)
	s.AgentID = derefUUID(agentID)
	s.BindingID = derefUUID(bindingID)
	s.AgentInstanceID = derefUUID(agentInstanceID)
	s.InstanceRef = deref(instRef)
	s.InstanceIP = deref(instIP)
	s.OriginType = deref(originType)
	s.OriginRef = deref(originRef)
	s.TaskContext = taskCtx
	return nil
}

func nullStr(s string) any {
	if s == "" {
		return nil
	}
	return s
}

func nullJSON(b []byte) any {
	if len(b) == 0 {
		return nil
	}
	return b
}

func nullUUID(id uuid.UUID) any {
	if id == uuid.Nil {
		return nil
	}
	return id
}

func derefUUID(id *uuid.UUID) uuid.UUID {
	if id == nil {
		return uuid.Nil
	}
	return *id
}

func deref(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}

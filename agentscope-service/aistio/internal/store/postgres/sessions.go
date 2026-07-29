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

func (r *sessionRepo) Upsert(ctx context.Context, s *store.Session) (*store.Session, error) {
	if s.Phase == "" {
		s.Phase = store.SessionPhaseActive
	}
	now := time.Now().UTC()
	row := r.pool.QueryRow(ctx, `
		INSERT INTO sessions (
			session_id, agent_name, namespace, framework, framework_version,
			phase, instance_ref, instance_ip, team_id, team_role, team_context,
			started_at, last_active_at, terminated_at, created_at, updated_at
		) VALUES (
			$1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16
		)
		ON CONFLICT (agent_name, namespace, session_id) DO UPDATE SET
			framework = COALESCE(NULLIF(EXCLUDED.framework, ''), sessions.framework),
			framework_version = COALESCE(EXCLUDED.framework_version, sessions.framework_version),
			phase = EXCLUDED.phase,
			instance_ref = COALESCE(EXCLUDED.instance_ref, sessions.instance_ref),
			instance_ip = COALESCE(EXCLUDED.instance_ip, sessions.instance_ip),
			team_id = COALESCE(EXCLUDED.team_id, sessions.team_id),
			team_role = COALESCE(EXCLUDED.team_role, sessions.team_role),
			team_context = COALESCE(EXCLUDED.team_context, sessions.team_context),
			started_at = COALESCE(EXCLUDED.started_at, sessions.started_at),
			last_active_at = COALESCE(EXCLUDED.last_active_at, sessions.last_active_at),
			terminated_at = EXCLUDED.terminated_at,
			updated_at = EXCLUDED.updated_at
		RETURNING id, session_id, agent_name, namespace, framework, framework_version,
			phase, instance_ref, instance_ip, team_id, team_role, team_context,
			started_at, last_active_at, terminated_at, created_at, updated_at`,
		s.SessionID, s.AgentName, s.Namespace, s.Framework, nullStr(s.FrameworkVersion),
		s.Phase, nullStr(s.InstanceRef), nullStr(s.InstanceIP), nullStr(s.TeamID), nullStr(s.TeamRole),
		nullJSON(s.TeamContext), s.StartedAt, s.LastActiveAt, s.TerminatedAt, now, now,
	)
	out := &store.Session{}
	if err := scanSession(row, out); err != nil {
		return nil, fmt.Errorf("postgres sessions upsert: %w", err)
	}
	return out, nil
}

func (r *sessionRepo) Get(ctx context.Context, agentName, namespace, sessionID string) (*store.Session, error) {
	row := r.pool.QueryRow(ctx, `
		SELECT id, session_id, agent_name, namespace, framework, framework_version,
			phase, instance_ref, instance_ip, team_id, team_role, team_context,
			started_at, last_active_at, terminated_at, created_at, updated_at
		FROM sessions WHERE agent_name=$1 AND namespace=$2 AND session_id=$3`,
		agentName, namespace, sessionID)
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
		SELECT id, session_id, agent_name, namespace, framework, framework_version,
			phase, instance_ref, instance_ip, team_id, team_role, team_context,
			started_at, last_active_at, terminated_at, created_at, updated_at
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
	if f.SessionID != "" {
		add("session_id=$%d", f.SessionID)
	}
	if f.Phase != "" {
		add("phase=$%d", f.Phase)
	}
	if f.Framework != "" {
		add("framework=$%d", f.Framework)
	}
	if f.TeamID != "" {
		add("team_id=$%d", f.TeamID)
	}
	if f.TeamRole != "" {
		add("team_role=$%d", f.TeamRole)
	}
	q := `SELECT id, session_id, agent_name, namespace, framework, framework_version,
		phase, instance_ref, instance_ip, team_id, team_role, team_context,
		started_at, last_active_at, terminated_at, created_at, updated_at FROM sessions`
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

func (r *sessionRepo) TerminateMissing(ctx context.Context, agentName, namespace string, keepSessionIDs []string, olderThan time.Duration) (int, error) {
	cutoff := time.Now().UTC().Add(-olderThan)
	keep := keepSessionIDs
	if keep == nil {
		keep = []string{}
	}
	tag, err := r.pool.Exec(ctx, `
		UPDATE sessions
		SET phase=$4, terminated_at=now(), updated_at=now()
		WHERE agent_name=$1 AND namespace=$2
		  AND phase != $4
		  AND created_at < $3
		  AND NOT (session_id = ANY($5))`,
		agentName, namespace, cutoff, store.SessionPhaseTerminated, keep)
	if err != nil {
		return 0, err
	}
	return int(tag.RowsAffected()), nil
}

func (r *sessionRepo) CountActive(ctx context.Context, agentName, namespace string) (int32, error) {
	var n int32
	err := r.pool.QueryRow(ctx, `
		SELECT COUNT(*) FROM sessions
		WHERE agent_name=$1 AND namespace=$2 AND phase != $3`,
		agentName, namespace, store.SessionPhaseTerminated).Scan(&n)
	return n, err
}

func (r *sessionRepo) DeleteByAgent(ctx context.Context, agentName, namespace string) error {
	_, err := r.pool.Exec(ctx, `DELETE FROM sessions WHERE agent_name=$1 AND namespace=$2`, agentName, namespace)
	return err
}

func (r *sessionRepo) DeleteByTeam(ctx context.Context, teamName, namespace string) error {
	_, err := r.pool.Exec(ctx, `DELETE FROM sessions WHERE team_id=$1 AND namespace=$2`, teamName, namespace)
	return err
}

type scannable interface {
	Scan(dest ...any) error
}

func scanSession(row scannable, s *store.Session) error {
	var fwVer, instRef, instIP, teamID, teamRole *string
	var teamCtx []byte
	err := row.Scan(
		&s.ID, &s.SessionID, &s.AgentName, &s.Namespace, &s.Framework, &fwVer,
		&s.Phase, &instRef, &instIP, &teamID, &teamRole, &teamCtx,
		&s.StartedAt, &s.LastActiveAt, &s.TerminatedAt, &s.CreatedAt, &s.UpdatedAt,
	)
	if err != nil {
		return err
	}
	s.FrameworkVersion = deref(fwVer)
	s.InstanceRef = deref(instRef)
	s.InstanceIP = deref(instIP)
	s.TeamID = deref(teamID)
	s.TeamRole = deref(teamRole)
	s.TeamContext = teamCtx
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

func deref(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}

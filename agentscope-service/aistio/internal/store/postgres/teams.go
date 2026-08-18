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
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type teamRepo struct {
	pool *pgxpool.Pool
}

func (r *teamRepo) Create(ctx context.Context, team *store.Team) (*store.Team, error) {
	now := time.Now().UTC()
	phase := team.Phase
	if phase == "" {
		phase = store.TeamPhasePending
	}
	t, err := r.scanTeam(ctx, `
		INSERT INTO teams (
			name, namespace, objective, phase, lead_ref, lead_prompt, config, spec_extra,
			started_at, created_at, updated_at
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$10)
		RETURNING id, name, namespace, objective, phase, lead_ref, lead_prompt, config, spec_extra,
			started_at, created_at, updated_at`,
		team.Name, team.Namespace, team.Objective, phase, team.LeadRef,
		nullStr(team.LeadPrompt), nullJSON(team.Config), nullJSON(team.SpecExtra),
		team.StartedAt, now)
	if err != nil {
		if isUniqueViolation(err) {
			return nil, store.ErrConflict
		}
		return nil, fmt.Errorf("postgres teams create: %w", err)
	}
	return t, nil
}

func (r *teamRepo) Get(ctx context.Context, namespace, name string) (*store.Team, error) {
	return r.scanTeam(ctx, `
		SELECT id, name, namespace, objective, phase, lead_ref, lead_prompt, config, spec_extra,
			started_at, created_at, updated_at
		FROM teams WHERE namespace=$1 AND name=$2`, namespace, name)
}

func (r *teamRepo) List(ctx context.Context, namespace string) ([]*store.Team, error) {
	var (
		rows pgx.Rows
		err  error
	)
	if namespace == "" {
		rows, err = r.pool.Query(ctx, `
			SELECT id, name, namespace, objective, phase, lead_ref, lead_prompt, config, spec_extra,
				started_at, created_at, updated_at
			FROM teams ORDER BY created_at ASC`)
	} else {
		rows, err = r.pool.Query(ctx, `
			SELECT id, name, namespace, objective, phase, lead_ref, lead_prompt, config, spec_extra,
				started_at, created_at, updated_at
			FROM teams WHERE namespace=$1 ORDER BY created_at ASC`, namespace)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanTeams(rows)
}

func (r *teamRepo) UpdatePhase(ctx context.Context, namespace, name, phase string) error {
	now := time.Now().UTC()
	tag, err := r.pool.Exec(ctx, `
		UPDATE teams
		SET phase=$3, updated_at=$4,
			started_at = CASE
				WHEN $3 = $5 AND started_at IS NULL THEN $4
				ELSE started_at
			END
		WHERE namespace=$1 AND name=$2`,
		namespace, name, phase, now, store.TeamPhaseRunning)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	return nil
}

func (r *teamRepo) Update(ctx context.Context, team *store.Team) (*store.Team, error) {
	now := time.Now().UTC()
	return r.scanTeam(ctx, `
		UPDATE teams SET
			objective=$3, phase=$4, lead_ref=$5, lead_prompt=$6,
			config=$7, spec_extra=$8, started_at=$9, updated_at=$10
		WHERE namespace=$1 AND name=$2
		RETURNING id, name, namespace, objective, phase, lead_ref, lead_prompt, config, spec_extra,
			started_at, created_at, updated_at`,
		team.Namespace, team.Name, team.Objective, team.Phase, team.LeadRef,
		nullStr(team.LeadPrompt), nullJSON(team.Config), nullJSON(team.SpecExtra),
		team.StartedAt, now)
}

func (r *teamRepo) Delete(ctx context.Context, namespace, name string) error {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `DELETE FROM team_members WHERE namespace=$1 AND team_name=$2`, namespace, name); err != nil {
		return err
	}
	tag, err := tx.Exec(ctx, `DELETE FROM teams WHERE namespace=$1 AND name=$2`, namespace, name)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	return tx.Commit(ctx)
}

func (r *teamRepo) UpsertMember(ctx context.Context, m *store.TeamMember) (*store.TeamMember, error) {
	now := time.Now().UTC()
	phase := m.Phase
	if phase == "" {
		phase = store.MemberPhaseJoining
	}
	origin := m.Origin
	if origin == "" {
		origin = store.MemberOriginStatic
	}
	deploy := m.DeployMode
	if deploy == "" {
		deploy = store.MemberDeployBYO
	}
	return r.scanMember(ctx, `
		INSERT INTO team_members (
			team_name, namespace, member_name, agent_ref, prompt, plan_approval,
			plan_text, plan_status,
			origin, deploy_mode, managed_agent_id, owner_id, phase,
			session_id, managed_session_id, instance_ref, current_task,
			restart_count, last_restart_at, last_restart_reason, created_at, updated_at
		) VALUES (
			$1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$21
		)
		ON CONFLICT (namespace, team_name, member_name) DO UPDATE SET
			agent_ref=EXCLUDED.agent_ref,
			prompt=EXCLUDED.prompt,
			plan_approval=EXCLUDED.plan_approval,
			plan_text=EXCLUDED.plan_text,
			plan_status=EXCLUDED.plan_status,
			origin=EXCLUDED.origin,
			deploy_mode=EXCLUDED.deploy_mode,
			managed_agent_id=EXCLUDED.managed_agent_id,
			owner_id=EXCLUDED.owner_id,
			phase=EXCLUDED.phase,
			session_id=EXCLUDED.session_id,
			managed_session_id=EXCLUDED.managed_session_id,
			instance_ref=EXCLUDED.instance_ref,
			current_task=EXCLUDED.current_task,
			restart_count=EXCLUDED.restart_count,
			last_restart_at=EXCLUDED.last_restart_at,
			last_restart_reason=EXCLUDED.last_restart_reason,
			updated_at=EXCLUDED.updated_at
		RETURNING `+memberColumns,
		m.TeamName, m.Namespace, m.MemberName, m.AgentRef, nullStr(m.Prompt), m.PlanApproval,
		nullStr(m.PlanText), nullStr(m.PlanStatus),
		origin, deploy, nullStr(m.ManagedAgentID), nullStr(m.OwnerID), phase,
		nullStr(m.SessionID), nullStr(m.ManagedSessionID), nullStr(m.InstanceRef), nullStr(m.CurrentTask),
		m.RestartCount, m.LastRestartAt, nullStr(m.LastRestartReason), now)
}

func (r *teamRepo) GetMember(ctx context.Context, namespace, teamName, memberName string) (*store.TeamMember, error) {
	return r.scanMember(ctx, `
		SELECT `+memberColumns+`
		FROM team_members WHERE namespace=$1 AND team_name=$2 AND member_name=$3`,
		namespace, teamName, memberName)
}

func (r *teamRepo) ListMembers(ctx context.Context, namespace, teamName string) ([]*store.TeamMember, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT `+memberColumns+`
		FROM team_members WHERE namespace=$1 AND team_name=$2
		ORDER BY created_at ASC`, namespace, teamName)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanMembers(rows)
}

func (r *teamRepo) RemoveMember(ctx context.Context, namespace, teamName, memberName string) error {
	tag, err := r.pool.Exec(ctx,
		`DELETE FROM team_members WHERE namespace=$1 AND team_name=$2 AND member_name=$3`,
		namespace, teamName, memberName)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	return nil
}

func (r *teamRepo) BindMemberSession(ctx context.Context, namespace, teamName, memberName, sessionID, managedSessionID, instanceRef string) error {
	tag, err := r.pool.Exec(ctx, `
		UPDATE team_members
		SET session_id=$4, managed_session_id=$5, instance_ref=$6, updated_at=$7
		WHERE namespace=$1 AND team_name=$2 AND member_name=$3`,
		namespace, teamName, memberName, nullStr(sessionID), nullStr(managedSessionID),
		nullStr(instanceRef), time.Now().UTC())
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	return nil
}

func (r *teamRepo) UpdateMemberPhase(ctx context.Context, namespace, teamName, memberName, phase string) error {
	tag, err := r.pool.Exec(ctx, `
		UPDATE team_members SET phase=$4, updated_at=$5
		WHERE namespace=$1 AND team_name=$2 AND member_name=$3`,
		namespace, teamName, memberName, phase, time.Now().UTC())
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	return nil
}

func (r *teamRepo) FindMemberBySessionID(ctx context.Context, sessionID string) (*store.TeamMember, error) {
	if sessionID == "" {
		return nil, store.ErrNotFound
	}
	return r.scanMember(ctx, `
		SELECT `+memberColumns+`
		FROM team_members
		WHERE session_id=$1 OR managed_session_id=$1
		ORDER BY updated_at DESC
		LIMIT 1`, sessionID)
}

func (r *teamRepo) scanTeam(ctx context.Context, q string, args ...any) (*store.Team, error) {
	t := &store.Team{}
	var leadPrompt *string
	err := r.pool.QueryRow(ctx, q, args...).Scan(
		&t.ID, &t.Name, &t.Namespace, &t.Objective, &t.Phase, &t.LeadRef, &leadPrompt,
		&t.Config, &t.SpecExtra, &t.StartedAt, &t.CreatedAt, &t.UpdatedAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	t.LeadPrompt = deref(leadPrompt)
	return t, nil
}

func scanTeams(rows rowsScanner) ([]*store.Team, error) {
	var out []*store.Team
	for rows.Next() {
		t := &store.Team{}
		var leadPrompt *string
		if err := rows.Scan(
			&t.ID, &t.Name, &t.Namespace, &t.Objective, &t.Phase, &t.LeadRef, &leadPrompt,
			&t.Config, &t.SpecExtra, &t.StartedAt, &t.CreatedAt, &t.UpdatedAt,
		); err != nil {
			return nil, err
		}
		t.LeadPrompt = deref(leadPrompt)
		out = append(out, t)
	}
	return out, rows.Err()
}

// memberColumns is the shared team_members projection. scanMember/scanMembers
// read positionally, so every query must select exactly this list.
const memberColumns = `id, team_name, namespace, member_name, agent_ref, prompt, plan_approval,
			plan_text, plan_status,
			origin, deploy_mode, managed_agent_id, owner_id, phase,
			session_id, managed_session_id, instance_ref, current_task,
			restart_count, last_restart_at, last_restart_reason, created_at, updated_at`

// memberNullables holds the nullable TEXT columns of one team_members row.
type memberNullables struct {
	prompt           *string
	planText         *string
	planStatus       *string
	managedAgentID   *string
	ownerID          *string
	sessionID        *string
	managedSessionID *string
	instanceRef      *string
	currentTask      *string
	lastReason       *string
}

func (n *memberNullables) targets(m *store.TeamMember) []any {
	return []any{
		&m.ID, &m.TeamName, &m.Namespace, &m.MemberName, &m.AgentRef, &n.prompt, &m.PlanApproval,
		&n.planText, &n.planStatus,
		&m.Origin, &m.DeployMode, &n.managedAgentID, &n.ownerID, &m.Phase,
		&n.sessionID, &n.managedSessionID, &n.instanceRef, &n.currentTask,
		&m.RestartCount, &m.LastRestartAt, &n.lastReason, &m.CreatedAt, &m.UpdatedAt,
	}
}

func (n *memberNullables) apply(m *store.TeamMember) {
	m.Prompt = deref(n.prompt)
	m.PlanText = deref(n.planText)
	m.PlanStatus = deref(n.planStatus)
	m.ManagedAgentID = deref(n.managedAgentID)
	m.OwnerID = deref(n.ownerID)
	m.SessionID = deref(n.sessionID)
	m.ManagedSessionID = deref(n.managedSessionID)
	m.InstanceRef = deref(n.instanceRef)
	m.CurrentTask = deref(n.currentTask)
	m.LastRestartReason = deref(n.lastReason)
}

func (r *teamRepo) scanMember(ctx context.Context, q string, args ...any) (*store.TeamMember, error) {
	m := &store.TeamMember{}
	var n memberNullables
	if err := r.pool.QueryRow(ctx, q, args...).Scan(n.targets(m)...); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	n.apply(m)
	return m, nil
}

func scanMembers(rows rowsScanner) ([]*store.TeamMember, error) {
	var out []*store.TeamMember
	for rows.Next() {
		m := &store.TeamMember{}
		var n memberNullables
		if err := rows.Scan(n.targets(m)...); err != nil {
			return nil, err
		}
		n.apply(m)
		out = append(out, m)
	}
	return out, rows.Err()
}

func isUniqueViolation(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "23505"
}

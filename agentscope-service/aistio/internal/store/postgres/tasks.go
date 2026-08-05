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
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type taskRepo struct {
	pool *pgxpool.Pool
}

func (r *taskRepo) Create(ctx context.Context, namespace, teamName, subject, description string, blockedBy []string, owner string) (*store.TeamTask, error) {
	now := time.Now().UTC()
	var blockedJSON []byte
	if len(blockedBy) > 0 {
		var err error
		blockedJSON, err = json.Marshal(blockedBy)
		if err != nil {
			return nil, err
		}
	}
	// Allocate next task_id sequence within the team.
	var next int64
	err := r.pool.QueryRow(ctx, `
		SELECT COALESCE(MAX(
			CASE WHEN task_id ~ '^task-[0-9]+$'
				THEN CAST(substring(task_id from 6) AS BIGINT)
				ELSE 0 END
		), 0) + 1
		FROM team_tasks WHERE namespace=$1 AND team_name=$2`, namespace, teamName).Scan(&next)
	if err != nil {
		return nil, err
	}
	taskID := fmt.Sprintf("task-%d", next)
	t, err := r.scanOne(ctx, `
		INSERT INTO team_tasks (
			task_id, team_name, namespace, subject, description, state, owner, blocked_by,
			version, created_at, updated_at
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,1,$9,$9)
		RETURNING id, task_id, team_name, namespace, subject, description, state, owner,
			blocked_by, result, version, created_at, updated_at, completed_at`,
		taskID, teamName, namespace, subject, nullStr(description), store.TaskStatePending,
		nullStr(owner), nullJSON(blockedJSON), now)
	if err != nil {
		return nil, fmt.Errorf("postgres tasks create: %w", err)
	}
	_, _ = r.pool.Exec(ctx, `
		INSERT INTO team_task_history (task_fk, team_name, namespace, from_state, to_state, owner)
		VALUES ($1,$2,$3,$4,$5,$6)`,
		t.ID, teamName, namespace, "", store.TaskStatePending, owner)
	return t, nil
}

func (r *taskRepo) Get(ctx context.Context, namespace, teamName, taskID string) (*store.TeamTask, error) {
	return r.scanOne(ctx, `
		SELECT id, task_id, team_name, namespace, subject, description, state, owner,
			blocked_by, result, version, created_at, updated_at, completed_at
		FROM team_tasks WHERE namespace=$1 AND team_name=$2 AND task_id=$3`,
		namespace, teamName, taskID)
}

func (r *taskRepo) List(ctx context.Context, namespace, teamName string) ([]*store.TeamTask, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT id, task_id, team_name, namespace, subject, description, state, owner,
			blocked_by, result, version, created_at, updated_at, completed_at
		FROM team_tasks WHERE namespace=$1 AND team_name=$2
		ORDER BY created_at ASC`, namespace, teamName)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanTasks(rows)
}

func (r *taskRepo) Assign(ctx context.Context, namespace, teamName, taskID, owner string, expectedVersion int64) (*store.TeamTask, error) {
	if owner == "" {
		return nil, fmt.Errorf("postgres tasks assign: owner required")
	}
	now := time.Now().UTC()
	t := &store.TeamTask{}
	var prevOwner, desc, result *string
	err := r.pool.QueryRow(ctx, `
		UPDATE team_tasks
		SET owner=$5, version=version+1, updated_at=$6
		WHERE namespace=$1 AND team_name=$2 AND task_id=$3 AND version=$4 AND state=$7
		RETURNING id, task_id, team_name, namespace, subject, description, state, owner,
			blocked_by, result, version, created_at, updated_at, completed_at`,
		namespace, teamName, taskID, expectedVersion, owner, now, store.TaskStatePending,
	).Scan(
		&t.ID, &t.TaskID, &t.TeamName, &t.Namespace, &t.Subject, &desc, &t.State, &prevOwner,
		&t.BlockedBy, &result, &t.Version, &t.CreatedAt, &t.UpdatedAt, &t.CompletedAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrConflict
		}
		return nil, err
	}
	t.Owner = deref(prevOwner)
	t.Description = deref(desc)
	t.Result = deref(result)
	_, _ = r.pool.Exec(ctx, `
		INSERT INTO team_task_history (task_fk, team_name, namespace, from_state, to_state, owner)
		VALUES ($1,$2,$3,$4,$5,$6)`,
		t.ID, teamName, namespace, store.TaskStatePending, store.TaskStatePending, owner)
	return t, nil
}

func (r *taskRepo) Claim(ctx context.Context, namespace, teamName, taskID, claimedBy string, expectedVersion int64) (*store.TeamTask, error) {
	cur, err := r.Get(ctx, namespace, teamName, taskID)
	if err != nil {
		return nil, err
	}
	if cur.State == store.TaskStateInProgress && cur.Owner == claimedBy {
		return cur, nil
	}
	version := expectedVersion
	if version <= 0 {
		version = cur.Version
	}
	if cur.Version != version || cur.State != store.TaskStatePending {
		return nil, store.ErrConflict
	}
	if cur.Owner != "" && cur.Owner != claimedBy {
		return nil, store.ErrConflict
	}
	if blocked, err := r.isBlocked(ctx, namespace, teamName, cur); err != nil {
		return nil, err
	} else if blocked {
		return nil, store.ErrConflict
	}

	now := time.Now().UTC()
	t := &store.TeamTask{}
	var owner, desc, result *string
	err = r.pool.QueryRow(ctx, `
		UPDATE team_tasks
		SET state=$5, owner=$6, version=version+1, updated_at=$7
		WHERE namespace=$1 AND team_name=$2 AND task_id=$3 AND version=$4 AND state=$8
			AND (owner IS NULL OR owner='' OR owner=$6)
		RETURNING id, task_id, team_name, namespace, subject, description, state, owner,
			blocked_by, result, version, created_at, updated_at, completed_at`,
		namespace, teamName, taskID, version, store.TaskStateInProgress, claimedBy, now,
		store.TaskStatePending,
	).Scan(
		&t.ID, &t.TaskID, &t.TeamName, &t.Namespace, &t.Subject, &desc, &t.State, &owner,
		&t.BlockedBy, &result, &t.Version, &t.CreatedAt, &t.UpdatedAt, &t.CompletedAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrConflict
		}
		return nil, err
	}
	t.Owner = deref(owner)
	t.Description = deref(desc)
	t.Result = deref(result)
	_, _ = r.pool.Exec(ctx, `
		INSERT INTO team_task_history (task_fk, team_name, namespace, from_state, to_state, owner)
		VALUES ($1,$2,$3,$4,$5,$6)`,
		t.ID, teamName, namespace, store.TaskStatePending, store.TaskStateInProgress, claimedBy)
	return t, nil
}

func (r *taskRepo) Complete(ctx context.Context, namespace, teamName, taskID, result string) (*store.TeamTask, error) {
	now := time.Now().UTC()
	t := &store.TeamTask{}
	var owner, desc, res *string
	err := r.pool.QueryRow(ctx, `
		UPDATE team_tasks
		SET state=$4, result=$5, version=version+1, updated_at=$6, completed_at=$6
		WHERE namespace=$1 AND team_name=$2 AND task_id=$3 AND state=$7
		RETURNING id, task_id, team_name, namespace, subject, description, state, owner,
			blocked_by, result, version, created_at, updated_at, completed_at`,
		namespace, teamName, taskID, store.TaskStateCompleted, result, now, store.TaskStateInProgress,
	).Scan(
		&t.ID, &t.TaskID, &t.TeamName, &t.Namespace, &t.Subject, &desc, &t.State, &owner,
		&t.BlockedBy, &res, &t.Version, &t.CreatedAt, &t.UpdatedAt, &t.CompletedAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	t.Owner = deref(owner)
	t.Description = deref(desc)
	t.Result = deref(res)
	_, _ = r.pool.Exec(ctx, `
		INSERT INTO team_task_history (task_fk, team_name, namespace, from_state, to_state, owner)
		VALUES ($1,$2,$3,$4,$5,$6)`,
		t.ID, teamName, namespace, store.TaskStateInProgress, store.TaskStateCompleted, t.Owner)
	return t, nil
}

func (r *taskRepo) Fail(ctx context.Context, namespace, teamName, taskID, reason string) (*store.TeamTask, error) {
	now := time.Now().UTC()
	t := &store.TeamTask{}
	var owner, desc, res *string
	err := r.pool.QueryRow(ctx, `
		UPDATE team_tasks
		SET state=$4, result=$5, version=version+1, updated_at=$6, completed_at=$6
		WHERE namespace=$1 AND team_name=$2 AND task_id=$3 AND state IN ($7,$8)
		RETURNING id, task_id, team_name, namespace, subject, description, state, owner,
			blocked_by, result, version, created_at, updated_at, completed_at`,
		namespace, teamName, taskID, store.TaskStateFailed, reason, now,
		store.TaskStatePending, store.TaskStateInProgress,
	).Scan(
		&t.ID, &t.TaskID, &t.TeamName, &t.Namespace, &t.Subject, &desc, &t.State, &owner,
		&t.BlockedBy, &res, &t.Version, &t.CreatedAt, &t.UpdatedAt, &t.CompletedAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			if _, getErr := r.Get(ctx, namespace, teamName, taskID); getErr != nil {
				return nil, getErr
			}
			return nil, store.ErrConflict
		}
		return nil, err
	}
	t.Owner = deref(owner)
	t.Description = deref(desc)
	t.Result = deref(res)
	_, _ = r.pool.Exec(ctx, `
		INSERT INTO team_task_history (task_fk, team_name, namespace, from_state, to_state, owner)
		VALUES ($1,$2,$3,$4,$5,$6)`,
		t.ID, teamName, namespace, store.TaskStateInProgress, store.TaskStateFailed, t.Owner)
	return t, nil
}

func (r *taskRepo) Unclaim(ctx context.Context, namespace, teamName, taskID string) (*store.TeamTask, error) {
	now := time.Now().UTC()
	t := &store.TeamTask{}
	var owner, desc, res *string
	err := r.pool.QueryRow(ctx, `
		UPDATE team_tasks
		SET state=$4, owner=NULL, version=version+1, updated_at=$5
		WHERE namespace=$1 AND team_name=$2 AND task_id=$3 AND state=$6
		RETURNING id, task_id, team_name, namespace, subject, description, state, owner,
			blocked_by, result, version, created_at, updated_at, completed_at`,
		namespace, teamName, taskID, store.TaskStatePending, now, store.TaskStateInProgress,
	).Scan(
		&t.ID, &t.TaskID, &t.TeamName, &t.Namespace, &t.Subject, &desc, &t.State, &owner,
		&t.BlockedBy, &res, &t.Version, &t.CreatedAt, &t.UpdatedAt, &t.CompletedAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	t.Owner = deref(owner)
	t.Description = deref(desc)
	t.Result = deref(res)
	_, _ = r.pool.Exec(ctx, `
		INSERT INTO team_task_history (task_fk, team_name, namespace, from_state, to_state, owner)
		VALUES ($1,$2,$3,$4,$5,$6)`,
		t.ID, teamName, namespace, store.TaskStateInProgress, store.TaskStatePending, "")
	return t, nil
}

func (r *taskRepo) GetUnblockedPending(ctx context.Context, namespace, teamName string) ([]*store.TeamTask, error) {
	tasks, err := r.List(ctx, namespace, teamName)
	if err != nil {
		return nil, err
	}
	completed := map[string]bool{}
	for _, t := range tasks {
		if t.State == store.TaskStateCompleted {
			completed[t.TaskID] = true
		}
	}
	var out []*store.TeamTask
	for _, t := range tasks {
		if t.State != store.TaskStatePending || t.Owner != "" {
			continue
		}
		var blocked []string
		if len(t.BlockedBy) > 0 {
			_ = json.Unmarshal(t.BlockedBy, &blocked)
		}
		ok := true
		for _, b := range blocked {
			if !completed[b] {
				ok = false
				break
			}
		}
		if ok {
			out = append(out, t)
		}
	}
	return out, nil
}

func (r *taskRepo) isBlocked(ctx context.Context, namespace, teamName string, task *store.TeamTask) (bool, error) {
	var blocked []string
	if len(task.BlockedBy) > 0 {
		if err := json.Unmarshal(task.BlockedBy, &blocked); err != nil {
			return false, err
		}
	}
	if len(blocked) == 0 {
		return false, nil
	}
	tasks, err := r.List(ctx, namespace, teamName)
	if err != nil {
		return false, err
	}
	completed := map[string]bool{}
	for _, t := range tasks {
		if t.State == store.TaskStateCompleted {
			completed[t.TaskID] = true
		}
	}
	for _, b := range blocked {
		if !completed[b] {
			return true, nil
		}
	}
	return false, nil
}

func (r *taskRepo) GetSummary(ctx context.Context, namespace, teamName string) (total, pending, inProgress, completed int32, err error) {
	err = r.pool.QueryRow(ctx, `
		SELECT
			COUNT(*)::int,
			COUNT(*) FILTER (WHERE state='pending')::int,
			COUNT(*) FILTER (WHERE state='in_progress')::int,
			COUNT(*) FILTER (WHERE state='completed')::int
		FROM team_tasks WHERE namespace=$1 AND team_name=$2`,
		namespace, teamName).Scan(&total, &pending, &inProgress, &completed)
	return
}

func (r *taskRepo) DeleteByTeam(ctx context.Context, namespace, teamName string) error {
	_, err := r.pool.Exec(ctx, `DELETE FROM team_tasks WHERE namespace=$1 AND team_name=$2`, namespace, teamName)
	return err
}

func (r *taskRepo) scanOne(ctx context.Context, q string, args ...any) (*store.TeamTask, error) {
	t := &store.TeamTask{}
	var owner, desc, result *string
	err := r.pool.QueryRow(ctx, q, args...).Scan(
		&t.ID, &t.TaskID, &t.TeamName, &t.Namespace, &t.Subject, &desc, &t.State, &owner,
		&t.BlockedBy, &result, &t.Version, &t.CreatedAt, &t.UpdatedAt, &t.CompletedAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	t.Owner = deref(owner)
	t.Description = deref(desc)
	t.Result = deref(result)
	return t, nil
}

func scanTasks(rows rowsScanner) ([]*store.TeamTask, error) {
	var out []*store.TeamTask
	for rows.Next() {
		t := &store.TeamTask{}
		var owner, desc, result *string
		if err := rows.Scan(
			&t.ID, &t.TaskID, &t.TeamName, &t.Namespace, &t.Subject, &desc, &t.State, &owner,
			&t.BlockedBy, &result, &t.Version, &t.CreatedAt, &t.UpdatedAt, &t.CompletedAt,
		); err != nil {
			return nil, err
		}
		t.Owner = deref(owner)
		t.Description = deref(desc)
		t.Result = deref(result)
		out = append(out, t)
	}
	return out, rows.Err()
}

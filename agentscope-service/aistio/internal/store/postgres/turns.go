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
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type turnRepo struct {
	pool *pgxpool.Pool
}

func (r *turnRepo) SyncOnPhase(ctx context.Context, sessionFK uuid.UUID, phase string) error {
	phase = strings.ToLower(strings.TrimSpace(phase))
	running, err := r.CurrentRunning(ctx, sessionFK)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		return err
	}
	now := time.Now().UTC()

	if phase == store.SessionPhaseActive {
		if running != nil {
			return nil
		}
		var nextIdx int
		err := r.pool.QueryRow(ctx, `
			SELECT COALESCE(MAX(turn_index), 0) + 1 FROM session_turns WHERE session_fk=$1`, sessionFK,
		).Scan(&nextIdx)
		if err != nil {
			return err
		}
		_, err = r.pool.Exec(ctx, `
			INSERT INTO session_turns (session_fk, turn_index, status, started_at, created_at)
			VALUES ($1, $2, $3, $4, $4)`,
			sessionFK, nextIdx, store.TurnStatusRunning, now)
		return err
	}

	if running == nil {
		return nil
	}
	status := store.TurnStatusCompleted
	if phase == store.SessionPhaseTerminated {
		status = store.TurnStatusAborted
	}
	dur := now.Sub(running.StartedAt).Milliseconds()
	if dur < 0 {
		dur = 0
	}
	_, err = r.pool.Exec(ctx, `
		UPDATE session_turns
		SET status=$2, ended_at=$3, duration_ms=$4
		WHERE id=$1 AND status=$5`,
		running.ID, status, now, dur, store.TurnStatusRunning)
	return err
}

func (r *turnRepo) List(ctx context.Context, sessionFK uuid.UUID, limit int) ([]*store.SessionTurn, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `
		SELECT id, session_fk, turn_index, status, started_at, ended_at, COALESCE(duration_ms, 0),
			COALESCE(user_preview, ''), prompt_tokens, completion_tokens, created_at
		FROM session_turns
		WHERE session_fk=$1
		ORDER BY turn_index DESC
		LIMIT $2`, sessionFK, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*store.SessionTurn
	for rows.Next() {
		t := &store.SessionTurn{}
		if err := rows.Scan(
			&t.ID, &t.SessionFK, &t.TurnIndex, &t.Status, &t.StartedAt, &t.EndedAt, &t.DurationMs,
			&t.UserPreview, &t.PromptTokens, &t.CompletionTokens, &t.CreatedAt,
		); err != nil {
			return nil, err
		}
		if t.Status == store.TurnStatusRunning && t.DurationMs == 0 {
			t.DurationMs = time.Since(t.StartedAt).Milliseconds()
			if t.DurationMs < 0 {
				t.DurationMs = 0
			}
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

func (r *turnRepo) CurrentRunning(ctx context.Context, sessionFK uuid.UUID) (*store.SessionTurn, error) {
	row := r.pool.QueryRow(ctx, `
		SELECT id, session_fk, turn_index, status, started_at, ended_at, COALESCE(duration_ms, 0),
			COALESCE(user_preview, ''), prompt_tokens, completion_tokens, created_at
		FROM session_turns
		WHERE session_fk=$1 AND status=$2
		ORDER BY turn_index DESC
		LIMIT 1`, sessionFK, store.TurnStatusRunning)
	t := &store.SessionTurn{}
	if err := row.Scan(
		&t.ID, &t.SessionFK, &t.TurnIndex, &t.Status, &t.StartedAt, &t.EndedAt, &t.DurationMs,
		&t.UserPreview, &t.PromptTokens, &t.CompletionTokens, &t.CreatedAt,
	); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	return t, nil
}

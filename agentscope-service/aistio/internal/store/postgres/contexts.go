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

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type contextRepo struct {
	pool *pgxpool.Pool
}

func (r *contextRepo) PutIfChanged(ctx context.Context, snapshot *store.ContextSnapshot) (bool, error) {
	if snapshot.CapturedAt.IsZero() {
		snapshot.CapturedAt = time.Now().UTC()
	}
	var id int64
	err := r.pool.QueryRow(ctx, `
		INSERT INTO context_snapshots (
			session_fk, captured_at, context_hash, system_prompt, messages, tools,
			is_compacted, compaction_summary, original_message_count, compacted_at,
			total_tokens, max_tokens, framework, framework_state
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)
		ON CONFLICT (session_fk, context_hash) DO NOTHING
		RETURNING id`,
		snapshot.SessionFK, snapshot.CapturedAt, snapshot.ContextHash,
		nullStr(snapshot.SystemPrompt), snapshot.Messages, nullJSON(snapshot.Tools),
		snapshot.IsCompacted, nullStr(snapshot.CompactionSummary),
		nullInt(snapshot.OriginalMessageCount), snapshot.CompactedAt,
		nullInt(snapshot.TotalTokens), nullInt(snapshot.MaxTokens),
		snapshot.Framework, nullJSON(snapshot.FrameworkState),
	).Scan(&id)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return false, nil // conflict = unchanged
		}
		return false, fmt.Errorf("postgres context put: %w", err)
	}
	snapshot.ID = id
	return true, nil
}

func (r *contextRepo) Latest(ctx context.Context, sessionFK uuid.UUID) (*store.ContextSnapshot, error) {
	row := r.pool.QueryRow(ctx, `
		SELECT id, session_fk, captured_at, context_hash, system_prompt, messages, tools,
			is_compacted, compaction_summary, original_message_count, compacted_at,
			total_tokens, max_tokens, framework, framework_state
		FROM context_snapshots WHERE session_fk=$1
		ORDER BY captured_at DESC LIMIT 1`, sessionFK)
	s := &store.ContextSnapshot{}
	var sysPrompt, compSum *string
	var tools, fwState []byte
	var origCount, totalTok, maxTok *int
	err := row.Scan(
		&s.ID, &s.SessionFK, &s.CapturedAt, &s.ContextHash, &sysPrompt, &s.Messages, &tools,
		&s.IsCompacted, &compSum, &origCount, &s.CompactedAt,
		&totalTok, &maxTok, &s.Framework, &fwState,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	s.SystemPrompt = deref(sysPrompt)
	s.CompactionSummary = deref(compSum)
	s.Tools = tools
	s.FrameworkState = fwState
	if origCount != nil {
		s.OriginalMessageCount = *origCount
	}
	if totalTok != nil {
		s.TotalTokens = *totalTok
	}
	if maxTok != nil {
		s.MaxTokens = *maxTok
	}
	return s, nil
}

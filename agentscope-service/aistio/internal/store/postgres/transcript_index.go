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

type transcriptIndexRepo struct {
	pool *pgxpool.Pool
}

func (r *transcriptIndexRepo) Upsert(ctx context.Context, idx *store.SessionTranscriptIndex) error {
	if idx == nil {
		return fmt.Errorf("postgres transcript index upsert: nil index")
	}
	if idx.UpdatedAt.IsZero() {
		idx.UpdatedAt = time.Now().UTC()
	}
	_, err := r.pool.Exec(ctx, `
		INSERT INTO session_transcript_index (
			session_fk, entry_count, prompt_tokens, completion_tokens, object_prefix, updated_at
		) VALUES ($1,$2,$3,$4,NULLIF($5,''),$6)
		ON CONFLICT (session_fk) DO UPDATE SET
			entry_count = EXCLUDED.entry_count,
			prompt_tokens = EXCLUDED.prompt_tokens,
			completion_tokens = EXCLUDED.completion_tokens,
			object_prefix = COALESCE(EXCLUDED.object_prefix, session_transcript_index.object_prefix),
			updated_at = EXCLUDED.updated_at`,
		idx.SessionFK, idx.EntryCount, idx.PromptTokens, idx.CompletionTokens,
		idx.ObjectPrefix, idx.UpdatedAt,
	)
	if err != nil {
		return fmt.Errorf("postgres transcript index upsert: %w", err)
	}
	return nil
}

func (r *transcriptIndexRepo) Get(ctx context.Context, sessionFK uuid.UUID) (*store.SessionTranscriptIndex, error) {
	row := r.pool.QueryRow(ctx, `
		SELECT session_fk, entry_count, prompt_tokens, completion_tokens, COALESCE(object_prefix,''), updated_at
		FROM session_transcript_index WHERE session_fk=$1`, sessionFK)
	idx := &store.SessionTranscriptIndex{}
	if err := row.Scan(&idx.SessionFK, &idx.EntryCount, &idx.PromptTokens, &idx.CompletionTokens, &idx.ObjectPrefix, &idx.UpdatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, fmt.Errorf("postgres transcript index get: %w", err)
	}
	return idx, nil
}

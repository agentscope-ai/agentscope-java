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

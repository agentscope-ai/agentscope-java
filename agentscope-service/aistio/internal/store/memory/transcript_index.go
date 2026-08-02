package memory

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type transcriptIndexRepo struct{ s *Store }

func (r *transcriptIndexRepo) Upsert(_ context.Context, idx *store.SessionTranscriptIndex) error {
	if idx == nil {
		return nil
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if r.s.transcriptIndex == nil {
		r.s.transcriptIndex = make(map[uuid.UUID]store.SessionTranscriptIndex)
	}
	cp := *idx
	if cp.UpdatedAt.IsZero() {
		cp.UpdatedAt = time.Now().UTC()
	}
	r.s.transcriptIndex[cp.SessionFK] = cp
	return nil
}

func (r *transcriptIndexRepo) Get(_ context.Context, sessionFK uuid.UUID) (*store.SessionTranscriptIndex, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	if r.s.transcriptIndex == nil {
		return nil, store.ErrNotFound
	}
	idx, ok := r.s.transcriptIndex[sessionFK]
	if !ok {
		return nil, store.ErrNotFound
	}
	cp := idx
	return &cp, nil
}

package memory

import (
	"context"
	"time"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type asyncToolRepo struct{ s *Store }

func asyncToolKey(tenant, recordID string) string {
	return tenant + "\x00" + recordID
}

func (r *asyncToolRepo) Register(_ context.Context, rec *store.AsyncToolRecord) error {
	if rec == nil {
		return store.ErrNotFound
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	now := time.Now().UTC()
	if rec.CreatedAt.IsZero() {
		rec.CreatedAt = now
	}
	rec.UpdatedAt = now
	if rec.Status == "" {
		rec.Status = store.AsyncToolRunning
	}
	cp := *rec
	r.s.asyncTools[asyncToolKey(rec.Tenant, rec.ID)] = &cp
	return nil
}

func (r *asyncToolRepo) Complete(_ context.Context, tenant, recordID, result string) error {
	return r.setStatus(tenant, recordID, store.AsyncToolCompleted, result, "")
}

func (r *asyncToolRepo) Fail(_ context.Context, tenant, recordID, errMsg string) error {
	return r.setStatus(tenant, recordID, store.AsyncToolFailed, "", errMsg)
}

func (r *asyncToolRepo) MarkTimeout(_ context.Context, tenant, recordID string) error {
	return r.setStatus(tenant, recordID, store.AsyncToolTimeout, "", "")
}

func (r *asyncToolRepo) setStatus(tenant, recordID, status, result, errMsg string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	rec, ok := r.s.asyncTools[asyncToolKey(tenant, recordID)]
	if !ok {
		return store.ErrNotFound
	}
	rec.Status = status
	if result != "" {
		rec.Result = result
	}
	if errMsg != "" {
		rec.Error = errMsg
	}
	rec.UpdatedAt = time.Now().UTC()
	return nil
}

func (r *asyncToolRepo) FindStale(_ context.Context, tenant, sessionID string, ttl time.Duration) ([]*store.AsyncToolRecord, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	cut := time.Now().UTC().Add(-ttl)
	var out []*store.AsyncToolRecord
	for _, rec := range r.s.asyncTools {
		if rec.Tenant != tenant || rec.SessionID != sessionID {
			continue
		}
		if rec.Status != store.AsyncToolRunning {
			continue
		}
		if rec.CreatedAt.Before(cut) {
			cp := *rec
			out = append(out, &cp)
		}
	}
	return out, nil
}

package memory

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type eventRepo struct{ s *Store }

func (r *eventRepo) Append(_ context.Context, event *store.SessionEvent) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for _, e := range r.s.events {
		if e.SessionFK == event.SessionFK && e.Seq == event.Seq {
			return store.ErrConflict
		}
	}
	if event.OccurredAt.IsZero() {
		event.OccurredAt = time.Now().UTC()
	}
	event.ID = nextID(&r.s.nextEvtID)
	cp := *event
	if event.ToolInput != nil {
		cp.ToolInput = append([]byte(nil), event.ToolInput...)
	}
	if event.FrameworkMeta != nil {
		cp.FrameworkMeta = append([]byte(nil), event.FrameworkMeta...)
	}
	r.s.events = append(r.s.events, cp)
	return nil
}

func (r *eventRepo) List(_ context.Context, sessionFK uuid.UUID, opts ...store.EventOption) ([]*store.SessionEvent, error) {
	eventType, since, until, limit, offset := store.ApplyEventOptions(opts)
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var out []*store.SessionEvent
	for i := range r.s.events {
		e := r.s.events[i]
		if e.SessionFK != sessionFK {
			continue
		}
		if eventType != "" && e.EventType != eventType {
			continue
		}
		if since != nil && e.OccurredAt.Before(*since) {
			continue
		}
		if until != nil && e.OccurredAt.After(*until) {
			continue
		}
		cp := e
		out = append(out, &cp)
	}
	// Sort by seq ascending (insertion order is usually fine but enforce).
	for i := 0; i < len(out); i++ {
		for j := i + 1; j < len(out); j++ {
			if out[j].Seq < out[i].Seq {
				out[i], out[j] = out[j], out[i]
			}
		}
	}
	if offset > 0 {
		if offset >= len(out) {
			return nil, nil
		}
		out = out[offset:]
	}
	if limit > 0 && len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

type contextRepo struct{ s *Store }

func (r *contextRepo) PutIfChanged(_ context.Context, snapshot *store.ContextSnapshot) (bool, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for _, c := range r.s.contexts {
		if c.SessionFK == snapshot.SessionFK && c.ContextHash == snapshot.ContextHash {
			return false, nil
		}
	}
	if snapshot.CapturedAt.IsZero() {
		snapshot.CapturedAt = time.Now().UTC()
	}
	snapshot.ID = nextID(&r.s.nextCtxID)
	cp := *snapshot
	if snapshot.Messages != nil {
		cp.Messages = append([]byte(nil), snapshot.Messages...)
	}
	if snapshot.Tools != nil {
		cp.Tools = append([]byte(nil), snapshot.Tools...)
	}
	if snapshot.FrameworkState != nil {
		cp.FrameworkState = append([]byte(nil), snapshot.FrameworkState...)
	}
	r.s.contexts = append(r.s.contexts, cp)
	return true, nil
}

func (r *contextRepo) Latest(_ context.Context, sessionFK uuid.UUID) (*store.ContextSnapshot, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var latest *store.ContextSnapshot
	for i := range r.s.contexts {
		c := &r.s.contexts[i]
		if c.SessionFK != sessionFK {
			continue
		}
		if latest == nil || c.CapturedAt.After(latest.CapturedAt) {
			cp := *c
			latest = &cp
		}
	}
	if latest == nil {
		return nil, store.ErrNotFound
	}
	return latest, nil
}

type metricsRepo struct{ s *Store }

func (r *metricsRepo) RecordTokenUsage(_ context.Context, m *store.TokenUsageMetric) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if m.RecordedAt.IsZero() {
		m.RecordedAt = time.Now().UTC()
	}
	m.ID = nextID(&r.s.nextTokID)
	r.s.tokens = append(r.s.tokens, *m)
	return nil
}

func (r *metricsRepo) RecordSnapshot(_ context.Context, snap *store.SessionSnapshot) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if snap.CapturedAt.IsZero() {
		snap.CapturedAt = time.Now().UTC()
	}
	snap.ID = nextID(&r.s.nextSnapID)
	cp := *snap
	if snap.TaskSummary != nil {
		cp.TaskSummary = append([]byte(nil), snap.TaskSummary...)
	}
	r.s.snapshots = append(r.s.snapshots, cp)
	return nil
}

func (r *metricsRepo) RecordAgentMetric(_ context.Context, m *store.AgentMetric) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if m.RecordedAt.IsZero() {
		m.RecordedAt = time.Now().UTC()
	}
	m.ID = nextID(&r.s.nextAgID)
	r.s.agents = append(r.s.agents, *m)
	return nil
}

func (r *metricsRepo) QueryTokenUsage(_ context.Context, f store.TokenFilter) ([]*store.TokenUsageMetric, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var out []*store.TokenUsageMetric
	for i := range r.s.tokens {
		m := r.s.tokens[i]
		if f.AgentName != "" && m.AgentName != f.AgentName {
			continue
		}
		if f.Namespace != "" && m.Namespace != f.Namespace {
			continue
		}
		if f.Model != "" && m.Model != f.Model {
			continue
		}
		if f.Since != nil && m.RecordedAt.Before(*f.Since) {
			continue
		}
		if f.Until != nil && m.RecordedAt.After(*f.Until) {
			continue
		}
		cp := m
		out = append(out, &cp)
	}
	if f.Limit > 0 && len(out) > f.Limit {
		out = out[:f.Limit]
	}
	return out, nil
}

func (r *metricsRepo) LatestSnapshot(_ context.Context, sessionFK uuid.UUID) (*store.SessionSnapshot, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var latest *store.SessionSnapshot
	for i := range r.s.snapshots {
		snap := &r.s.snapshots[i]
		if snap.SessionFK != sessionFK {
			continue
		}
		if latest == nil || snap.CapturedAt.After(latest.CapturedAt) {
			cp := *snap
			latest = &cp
		}
	}
	if latest == nil {
		return nil, store.ErrNotFound
	}
	return latest, nil
}

func (r *metricsRepo) LatestSnapshots(_ context.Context, sessionFKs []uuid.UUID) (map[uuid.UUID]*store.SessionSnapshot, error) {
	want := make(map[uuid.UUID]struct{}, len(sessionFKs))
	for _, id := range sessionFKs {
		want[id] = struct{}{}
	}
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make(map[uuid.UUID]*store.SessionSnapshot)
	for i := range r.s.snapshots {
		snap := &r.s.snapshots[i]
		if _, ok := want[snap.SessionFK]; !ok {
			continue
		}
		if prev, ok := out[snap.SessionFK]; ok && !snap.CapturedAt.After(prev.CapturedAt) {
			continue
		}
		cp := *snap
		out[snap.SessionFK] = &cp
	}
	return out, nil
}

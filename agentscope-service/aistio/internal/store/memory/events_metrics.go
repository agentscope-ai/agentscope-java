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

package memory

import (
	"context"
	"strings"
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
	o := store.ResolveEventOptions(opts)
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var out []*store.SessionEvent
	for i := range r.s.events {
		e := r.s.events[i]
		if e.SessionFK != sessionFK {
			continue
		}
		if o.EventType != "" && e.EventType != o.EventType {
			continue
		}
		if o.Since != nil && e.OccurredAt.Before(*o.Since) {
			continue
		}
		if o.Until != nil && e.OccurredAt.After(*o.Until) {
			continue
		}
		if o.Before != nil && !e.OccurredAt.Before(*o.Before) {
			continue
		}
		if o.BeforeSeq != nil && e.Seq >= *o.BeforeSeq {
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
	if o.NewestFirst && o.Limit > 0 {
		if len(out) > o.Limit {
			out = out[len(out)-o.Limit:]
		}
		return out, nil
	}
	if o.Offset > 0 {
		if o.Offset >= len(out) {
			return nil, nil
		}
		out = out[o.Offset:]
	}
	if o.Limit > 0 && len(out) > o.Limit {
		out = out[:o.Limit]
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

func (r *metricsRepo) QueryAgentMetrics(_ context.Context, f store.AgentMetricFilter) ([]*store.AgentMetric, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var out []*store.AgentMetric
	for i := range r.s.agents {
		m := r.s.agents[i]
		if f.AgentName != "" && m.AgentName != f.AgentName {
			continue
		}
		if f.Namespace != "" && m.Namespace != f.Namespace {
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
	for i := 0; i < len(out); i++ {
		for j := i + 1; j < len(out); j++ {
			if out[j].RecordedAt.After(out[i].RecordedAt) {
				out[i], out[j] = out[j], out[i]
			}
		}
	}
	if f.Limit > 0 && len(out) > f.Limit {
		out = out[:f.Limit]
	}
	return out, nil
}

func (r *metricsRepo) AggregateTokens(_ context.Context, f store.TokenFilter, bucket time.Duration) ([]store.TokenBucket, error) {
	if bucket <= 0 {
		bucket = time.Hour
	}
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	agg := map[time.Time]*store.TokenBucket{}
	var order []time.Time
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
		start := truncBucket(m.RecordedAt, bucket)
		b, ok := agg[start]
		if !ok {
			b = &store.TokenBucket{BucketStart: start}
			agg[start] = b
			order = append(order, start)
		}
		b.PromptTokens += m.PromptTokens
		b.CompletionTokens += m.CompletionTokens
		b.TotalTokens += m.TotalTokens
		b.SampleCount++
	}
	for i := 0; i < len(order); i++ {
		for j := i + 1; j < len(order); j++ {
			if order[j].Before(order[i]) {
				order[i], order[j] = order[j], order[i]
			}
		}
	}
	out := make([]store.TokenBucket, 0, len(order))
	for _, t := range order {
		out = append(out, *agg[t])
	}
	return out, nil
}

func truncBucket(t time.Time, bucket time.Duration) time.Time {
	t = t.UTC()
	switch {
	case bucket >= 24*time.Hour:
		return time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC)
	case bucket >= time.Hour:
		return time.Date(t.Year(), t.Month(), t.Day(), t.Hour(), 0, 0, 0, time.UTC)
	default:
		return time.Date(t.Year(), t.Month(), t.Day(), t.Hour(), t.Minute(), 0, 0, time.UTC)
	}
}

func (r *metricsRepo) TopAgents(_ context.Context, since time.Time, limit int) ([]store.AgentUsage, error) {
	if limit <= 0 {
		limit = 10
	}
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()

	type agentKey struct{ agent, ns string }
	totals := map[agentKey]int64{}
	for i := range r.s.tokens {
		m := r.s.tokens[i]
		if m.RecordedAt.Before(since) {
			continue
		}
		k := agentKey{m.AgentName, m.Namespace}
		totals[k] += m.TotalTokens
	}

	latestAgent := map[agentKey]*store.AgentMetric{}
	for i := range r.s.agents {
		m := &r.s.agents[i]
		k := agentKey{m.AgentName, m.Namespace}
		if prev, ok := latestAgent[k]; ok && !m.RecordedAt.After(prev.RecordedAt) {
			continue
		}
		cp := *m
		latestAgent[k] = &cp
	}

	out := make([]store.AgentUsage, 0, len(totals))
	for k, total := range totals {
		u := store.AgentUsage{
			AgentName:   k.agent,
			Namespace:   k.ns,
			TotalTokens: total,
		}
		if am, ok := latestAgent[k]; ok {
			u.ActiveSessions = am.ActiveSessions
			u.AvgPressure = am.AvgContextPressure
			u.ErrorCount = am.ErrorCount
		}
		out = append(out, u)
	}
	for i := 0; i < len(out); i++ {
		for j := i + 1; j < len(out); j++ {
			if out[j].TotalTokens > out[i].TotalTokens {
				out[i], out[j] = out[j], out[i]
			}
		}
	}
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

func (r *metricsRepo) TopSessionsByTokens(_ context.Context, since time.Time, limit int) ([]store.SessionUsage, error) {
	if limit <= 0 {
		limit = 10
	}
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()

	totals := map[uuid.UUID]int64{}
	for i := range r.s.tokens {
		m := r.s.tokens[i]
		if m.SessionFK == nil || m.RecordedAt.Before(since) {
			continue
		}
		totals[*m.SessionFK] += m.TotalTokens
	}
	out := make([]store.SessionUsage, 0, len(totals))
	for fk, total := range totals {
		s, ok := r.s.sessions[fk]
		if !ok || s == nil {
			continue
		}
		out = append(out, store.SessionUsage{
			SessionFK:   fk,
			SessionID:   s.SessionID,
			AgentName:   s.AgentName,
			Namespace:   s.Namespace,
			Phase:       s.Phase,
			TotalTokens: total,
		})
	}
	for i := 0; i < len(out); i++ {
		for j := i + 1; j < len(out); j++ {
			if out[j].TotalTokens > out[i].TotalTokens {
				out[i], out[j] = out[j], out[i]
			}
		}
	}
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

func (r *metricsRepo) TopSessionsByDuration(_ context.Context, since time.Time, limit int) ([]store.SessionDuration, error) {
	if limit <= 0 {
		limit = 10
	}
	_ = since
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()

	now := time.Now().UTC()
	out := make([]store.SessionDuration, 0)
	for _, s := range r.s.sessions {
		if s == nil || strings.ToLower(s.Phase) != store.SessionPhaseActive {
			continue
		}
		var running *store.SessionTurn
		for i := range r.s.turns {
			t := &r.s.turns[i]
			if t.SessionFK == s.ID && t.Status == store.TurnStatusRunning {
				if running == nil || t.TurnIndex > running.TurnIndex {
					running = t
				}
			}
		}
		if running == nil {
			continue
		}
		dur := now.Sub(running.StartedAt)
		if dur < 0 {
			dur = 0
		}
		started := running.StartedAt
		ended := now
		out = append(out, store.SessionDuration{
			SessionFK:  s.ID,
			SessionID:  s.SessionID,
			AgentName:  s.AgentName,
			Namespace:  s.Namespace,
			Phase:      s.Phase,
			DurationMs: dur.Milliseconds(),
			StartedAt:  &started,
			EndedAt:    &ended,
			TurnIndex:  running.TurnIndex,
		})
	}
	for i := 0; i < len(out); i++ {
		for j := i + 1; j < len(out); j++ {
			if out[j].DurationMs > out[i].DurationMs {
				out[i], out[j] = out[j], out[i]
			}
		}
	}
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

func (r *metricsRepo) TopAgentsByActiveSessions(_ context.Context, since time.Time, limit int) ([]store.AgentUsage, error) {
	if limit <= 0 {
		limit = 10
	}
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()

	type agentKey struct{ agent, ns string }
	peaks := map[agentKey]int32{}
	for i := range r.s.agents {
		m := r.s.agents[i]
		if m.RecordedAt.Before(since) {
			continue
		}
		k := agentKey{m.AgentName, m.Namespace}
		if m.ActiveSessions > peaks[k] {
			peaks[k] = m.ActiveSessions
		}
	}
	out := make([]store.AgentUsage, 0, len(peaks))
	for k, peak := range peaks {
		out = append(out, store.AgentUsage{
			AgentName:      k.agent,
			Namespace:      k.ns,
			ActiveSessions: peak,
		})
	}
	for i := 0; i < len(out); i++ {
		for j := i + 1; j < len(out); j++ {
			if out[j].ActiveSessions > out[i].ActiveSessions {
				out[i], out[j] = out[j], out[i]
			}
		}
	}
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

func (r *metricsRepo) PressureStats(_ context.Context, f store.SessionFilter) (avg, p95 float64, err error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()

	latest := map[uuid.UUID]*store.SessionSnapshot{}
	for i := range r.s.snapshots {
		snap := &r.s.snapshots[i]
		if prev, ok := latest[snap.SessionFK]; ok && !snap.CapturedAt.After(prev.CapturedAt) {
			continue
		}
		cp := *snap
		latest[snap.SessionFK] = &cp
	}

	var pressures []float64
	for _, s := range r.s.sessions {
		if !sessionMatchesFilter(s, f) {
			continue
		}
		snap, ok := latest[s.ID]
		if !ok {
			continue
		}
		pressures = append(pressures, snap.ContextPressure)
	}
	if len(pressures) == 0 {
		return 0, 0, nil
	}
	var sum float64
	for _, p := range pressures {
		sum += p
	}
	avg = sum / float64(len(pressures))
	for i := 0; i < len(pressures); i++ {
		for j := i + 1; j < len(pressures); j++ {
			if pressures[j] < pressures[i] {
				pressures[i], pressures[j] = pressures[j], pressures[i]
			}
		}
	}
	// percentile_cont(0.95): linear interpolation between closest ranks.
	n := len(pressures)
	if n == 1 {
		return avg, pressures[0], nil
	}
	pos := 0.95 * float64(n-1)
	lo := int(pos)
	hi := lo + 1
	if hi >= n {
		return avg, pressures[n-1], nil
	}
	frac := pos - float64(lo)
	p95 = pressures[lo]*(1-frac) + pressures[hi]*frac
	return avg, p95, nil
}

func (r *metricsRepo) SumTokenUsage(_ context.Context, f store.TokenFilter) (int64, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var total int64
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
		total += m.TotalTokens
	}
	return total, nil
}

func (r *metricsRepo) SumErrorCount(_ context.Context, f store.AgentMetricFilter) (int32, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var total int32
	for i := range r.s.agents {
		m := r.s.agents[i]
		if f.AgentName != "" && m.AgentName != f.AgentName {
			continue
		}
		if f.Namespace != "" && m.Namespace != f.Namespace {
			continue
		}
		if f.Since != nil && m.RecordedAt.Before(*f.Since) {
			continue
		}
		if f.Until != nil && m.RecordedAt.After(*f.Until) {
			continue
		}
		total += m.ErrorCount
	}
	return total, nil
}

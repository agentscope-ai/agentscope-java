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

type turnRepo struct{ s *Store }

func (r *turnRepo) SyncOnPhase(_ context.Context, sessionFK uuid.UUID, phase string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	phase = strings.ToLower(strings.TrimSpace(phase))
	now := time.Now().UTC()

	maxIdx := 0
	var runningPtr *store.SessionTurn
	for i := range r.s.turns {
		t := &r.s.turns[i]
		if t.SessionFK != sessionFK {
			continue
		}
		if t.TurnIndex > maxIdx {
			maxIdx = t.TurnIndex
		}
		if t.Status == store.TurnStatusRunning {
			runningPtr = t
		}
	}

	if phase == store.SessionPhaseActive {
		if runningPtr != nil {
			return nil
		}
		r.s.turns = append(r.s.turns, store.SessionTurn{
			ID:        uuid.New(),
			SessionFK: sessionFK,
			TurnIndex: maxIdx + 1,
			Status:    store.TurnStatusRunning,
			StartedAt: now,
			CreatedAt: now,
		})
		return nil
	}

	if runningPtr == nil {
		return nil
	}
	status := store.TurnStatusCompleted
	if phase == store.SessionPhaseTerminated {
		status = store.TurnStatusAborted
	}
	dur := now.Sub(runningPtr.StartedAt).Milliseconds()
	if dur < 0 {
		dur = 0
	}
	runningPtr.Status = status
	runningPtr.EndedAt = &now
	runningPtr.DurationMs = dur
	return nil
}

func (r *turnRepo) List(_ context.Context, sessionFK uuid.UUID, limit int) ([]*store.SessionTurn, error) {
	if limit <= 0 {
		limit = 100
	}
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var matched []store.SessionTurn
	for i := range r.s.turns {
		if r.s.turns[i].SessionFK == sessionFK {
			matched = append(matched, r.s.turns[i])
		}
	}
	// sort by turn_index desc
	for i := 0; i < len(matched); i++ {
		for j := i + 1; j < len(matched); j++ {
			if matched[j].TurnIndex > matched[i].TurnIndex {
				matched[i], matched[j] = matched[j], matched[i]
			}
		}
	}
	if len(matched) > limit {
		matched = matched[:limit]
	}
	out := make([]*store.SessionTurn, 0, len(matched))
	now := time.Now().UTC()
	for i := range matched {
		cp := matched[i]
		if cp.Status == store.TurnStatusRunning && cp.DurationMs == 0 {
			cp.DurationMs = now.Sub(cp.StartedAt).Milliseconds()
			if cp.DurationMs < 0 {
				cp.DurationMs = 0
			}
		}
		out = append(out, &cp)
	}
	return out, nil
}

func (r *turnRepo) CurrentRunning(_ context.Context, sessionFK uuid.UUID) (*store.SessionTurn, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var best *store.SessionTurn
	for i := range r.s.turns {
		t := &r.s.turns[i]
		if t.SessionFK != sessionFK || t.Status != store.TurnStatusRunning {
			continue
		}
		if best == nil || t.TurnIndex > best.TurnIndex {
			cp := *t
			best = &cp
		}
	}
	if best == nil {
		return nil, store.ErrNotFound
	}
	return best, nil
}

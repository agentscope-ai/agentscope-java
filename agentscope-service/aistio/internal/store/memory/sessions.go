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

type sessionRepo struct{ s *Store }

func (r *sessionRepo) Upsert(_ context.Context, in *store.Session) (*store.Session, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	now := time.Now().UTC()
	key := sessCompositeKey(in.AgentName, in.Namespace, in.SessionID)
	if id, ok := r.s.sessKey[key]; ok {
		existing := r.s.sessions[id]
		if in.Framework != "" {
			existing.Framework = in.Framework
		}
		if in.FrameworkVersion != "" {
			existing.FrameworkVersion = in.FrameworkVersion
		}
		existing.Phase = in.Phase
		if in.Phase == "" {
			existing.Phase = store.SessionPhaseActive
		}
		if in.Busy != nil {
			b := *in.Busy
			existing.Busy = &b
		} else {
			existing.Busy = nil
		}
		if in.InstanceRef != "" {
			existing.InstanceRef = in.InstanceRef
		}
		if in.InstanceIP != "" {
			existing.InstanceIP = in.InstanceIP
		}
		if in.TeamID != "" {
			existing.TeamID = in.TeamID
		}
		if in.TeamRole != "" {
			existing.TeamRole = in.TeamRole
		}
		if len(in.TeamContext) > 0 {
			existing.TeamContext = append([]byte(nil), in.TeamContext...)
		}
		if in.StartedAt != nil {
			existing.StartedAt = in.StartedAt
		}
		if in.LastActiveAt != nil {
			existing.LastActiveAt = in.LastActiveAt
		}
		existing.TerminatedAt = in.TerminatedAt
		existing.UpdatedAt = now
		return cloneSession(existing), nil
	}
	id := uuid.New()
	phase := in.Phase
	if phase == "" {
		phase = store.SessionPhaseActive
	}
	var busy *bool
	if in.Busy != nil {
		b := *in.Busy
		busy = &b
	}
	s := &store.Session{
		ID:               id,
		SessionID:        in.SessionID,
		AgentName:        in.AgentName,
		Namespace:        in.Namespace,
		Framework:        in.Framework,
		FrameworkVersion: in.FrameworkVersion,
		Phase:            phase,
		Busy:             busy,
		InstanceRef:      in.InstanceRef,
		InstanceIP:       in.InstanceIP,
		TeamID:           in.TeamID,
		TeamRole:         in.TeamRole,
		TeamContext:      append([]byte(nil), in.TeamContext...),
		StartedAt:        in.StartedAt,
		LastActiveAt:     in.LastActiveAt,
		TerminatedAt:     in.TerminatedAt,
		CreatedAt:        now,
		UpdatedAt:        now,
	}
	r.s.sessions[id] = s
	r.s.sessKey[key] = id
	return cloneSession(s), nil
}

func (r *sessionRepo) Get(_ context.Context, agentName, namespace, sessionID string) (*store.Session, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	id, ok := r.s.sessKey[sessCompositeKey(agentName, namespace, sessionID)]
	if !ok {
		return nil, store.ErrNotFound
	}
	return cloneSession(r.s.sessions[id]), nil
}

func (r *sessionRepo) GetByID(_ context.Context, id uuid.UUID) (*store.Session, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	s, ok := r.s.sessions[id]
	if !ok {
		return nil, store.ErrNotFound
	}
	return cloneSession(s), nil
}

func (r *sessionRepo) List(_ context.Context, f store.SessionFilter) ([]*store.Session, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var out []*store.Session
	for _, s := range r.s.sessions {
		if f.AgentName != "" && s.AgentName != f.AgentName {
			continue
		}
		if f.Namespace != "" && s.Namespace != f.Namespace {
			continue
		}
		if f.SessionID != "" && s.SessionID != f.SessionID {
			continue
		}
		if f.Phase != "" && s.Phase != f.Phase {
			continue
		}
		if f.Framework != "" && s.Framework != f.Framework {
			continue
		}
		if f.TeamID != "" && s.TeamID != f.TeamID {
			continue
		}
		if f.TeamRole != "" && s.TeamRole != f.TeamRole {
			continue
		}
		out = append(out, cloneSession(s))
	}
	// Stable-ish order by CreatedAt desc.
	for i := 0; i < len(out); i++ {
		for j := i + 1; j < len(out); j++ {
			if out[j].CreatedAt.After(out[i].CreatedAt) {
				out[i], out[j] = out[j], out[i]
			}
		}
	}
	if f.Offset > 0 {
		if f.Offset >= len(out) {
			return nil, nil
		}
		out = out[f.Offset:]
	}
	if f.Limit > 0 && len(out) > f.Limit {
		out = out[:f.Limit]
	}
	return out, nil
}

func (r *sessionRepo) UpdatePhase(_ context.Context, id uuid.UUID, phase string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	s, ok := r.s.sessions[id]
	if !ok {
		return store.ErrNotFound
	}
	s.Phase = phase
	s.UpdatedAt = time.Now().UTC()
	if phase == store.SessionPhaseTerminated {
		now := time.Now().UTC()
		s.TerminatedAt = &now
	}
	return nil
}

func (r *sessionRepo) ArchiveMissing(_ context.Context, agentName, namespace string, keepSessionIDs []string, olderThan time.Duration) (int, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	keep := map[string]bool{}
	for _, id := range keepSessionIDs {
		keep[id] = true
	}
	cutoff := time.Now().UTC().Add(-olderThan)
	n := 0
	now := time.Now().UTC()
	for _, s := range r.s.sessions {
		if s.AgentName != agentName || s.Namespace != namespace {
			continue
		}
		if s.Phase == store.SessionPhaseTerminated || s.Phase == store.SessionPhaseArchived {
			continue
		}
		if keep[s.SessionID] {
			continue
		}
		if !s.CreatedAt.Before(cutoff) {
			continue
		}
		s.Phase = store.SessionPhaseArchived
		falseBusy := false
		s.Busy = &falseBusy
		s.UpdatedAt = now
		n++
	}
	return n, nil
}

func (r *sessionRepo) ArchiveIdleOlderThan(_ context.Context, olderThan time.Duration) (int, error) {
	if olderThan <= 0 {
		return 0, nil
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	cutoff := time.Now().UTC().Add(-olderThan)
	n := 0
	now := time.Now().UTC()
	for _, s := range r.s.sessions {
		if s.Phase != store.SessionPhaseIdle {
			continue
		}
		activity := s.CreatedAt
		if !s.UpdatedAt.IsZero() {
			activity = s.UpdatedAt
		}
		if s.LastActiveAt != nil {
			activity = *s.LastActiveAt
		}
		if !activity.Before(cutoff) {
			continue
		}
		s.Phase = store.SessionPhaseArchived
		falseBusy := false
		s.Busy = &falseBusy
		s.UpdatedAt = now
		n++
	}
	return n, nil
}

func (r *sessionRepo) CountActive(_ context.Context, agentName, namespace string) (int32, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var n int32
	for _, s := range r.s.sessions {
		if s.AgentName == agentName && s.Namespace == namespace &&
			s.Phase != store.SessionPhaseTerminated && s.Phase != store.SessionPhaseArchived {
			n++
		}
	}
	return n, nil
}

func (r *sessionRepo) CountByPhase(_ context.Context, f store.SessionFilter) (map[string]int, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := map[string]int{}
	for _, s := range r.s.sessions {
		if !sessionMatchesFilter(s, f) {
			continue
		}
		out[strings.ToLower(s.Phase)]++
	}
	return out, nil
}

func (r *sessionRepo) ListByPressure(_ context.Context, f store.SessionFilter, minPressure float64, limit int) ([]*store.SessionWithSnapshot, error) {
	if limit <= 0 {
		limit = 10
	}
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()

	latest := map[uuid.UUID]*store.SessionSnapshot{}
	for i := range r.s.snapshots {
		snap := &r.s.snapshots[i]
		if prev, ok := latest[snap.SessionFK]; ok && !snap.CapturedAt.After(prev.CapturedAt) {
			continue
		}
		cp := *snap
		if snap.TaskSummary != nil {
			cp.TaskSummary = append([]byte(nil), snap.TaskSummary...)
		}
		latest[snap.SessionFK] = &cp
	}

	var out []*store.SessionWithSnapshot
	for _, s := range r.s.sessions {
		if !sessionMatchesFilter(s, f) {
			continue
		}
		snap, ok := latest[s.ID]
		if !ok || snap.ContextPressure < minPressure {
			continue
		}
		out = append(out, &store.SessionWithSnapshot{
			Session:  cloneSession(s),
			Snapshot: snap,
		})
	}
	for i := 0; i < len(out); i++ {
		for j := i + 1; j < len(out); j++ {
			if out[j].Snapshot.ContextPressure > out[i].Snapshot.ContextPressure {
				out[i], out[j] = out[j], out[i]
			}
		}
	}
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

func sessionMatchesFilter(s *store.Session, f store.SessionFilter) bool {
	if f.AgentName != "" && s.AgentName != f.AgentName {
		return false
	}
	if f.Namespace != "" && s.Namespace != f.Namespace {
		return false
	}
	if f.SessionID != "" && s.SessionID != f.SessionID {
		return false
	}
	if f.Phase != "" && s.Phase != f.Phase {
		return false
	}
	if f.Framework != "" && s.Framework != f.Framework {
		return false
	}
	if f.TeamID != "" && s.TeamID != f.TeamID {
		return false
	}
	if f.TeamRole != "" && s.TeamRole != f.TeamRole {
		return false
	}
	return true
}

func (r *sessionRepo) DeleteByAgent(_ context.Context, agentName, namespace string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for id, s := range r.s.sessions {
		if s.AgentName == agentName && s.Namespace == namespace {
			delete(r.s.sessKey, sessCompositeKey(s.AgentName, s.Namespace, s.SessionID))
			delete(r.s.sessions, id)
		}
	}
	return nil
}

func (r *sessionRepo) DeleteByTeam(_ context.Context, teamName, namespace string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for id, s := range r.s.sessions {
		if s.TeamID == teamName && s.Namespace == namespace {
			delete(r.s.sessKey, sessCompositeKey(s.AgentName, s.Namespace, s.SessionID))
			delete(r.s.sessions, id)
		}
	}
	return nil
}

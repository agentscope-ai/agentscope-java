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
	"encoding/json"
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
	tenant := in.Tenant
	if tenant == "" {
		tenant = "default"
	}
	key := sessCompositeKey(tenant, in.AgentName, in.Namespace, in.SessionID)
	if id, ok := r.s.sessKey[key]; ok {
		existing := r.s.sessions[id]
		if in.AgentID != uuid.Nil {
			existing.AgentID = in.AgentID
		}
		if in.BindingID != uuid.Nil {
			existing.BindingID = in.BindingID
		}
		if in.AgentInstanceID != uuid.Nil {
			existing.AgentInstanceID = in.AgentInstanceID
		}
		if in.InstanceGeneration > 0 {
			existing.InstanceGeneration = in.InstanceGeneration
		}
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
		if in.AgentTaskID != nil {
			id := *in.AgentTaskID
			existing.AgentTaskID = &id
		}
		// Runtime inventory is an observation of an existing session, not the
		// authority for how that session entered the control plane. Preserve a
		// more specific origin assigned by Endpoint or AgentTask.
		preserveControlPlaneOrigin := in.OriginType == "runtime" &&
			existing.OriginType != "" && existing.OriginType != "runtime"
		if in.OriginType != "" && !preserveControlPlaneOrigin {
			existing.OriginType = in.OriginType
		}
		if in.OriginRef != "" && !preserveControlPlaneOrigin {
			existing.OriginRef = in.OriginRef
		}
		if len(in.TaskContext) > 0 {
			existing.TaskContext = append([]byte(nil), in.TaskContext...)
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
		ID:                 id,
		Tenant:             tenant,
		SessionID:          in.SessionID,
		AgentID:            in.AgentID,
		BindingID:          in.BindingID,
		AgentInstanceID:    in.AgentInstanceID,
		InstanceGeneration: in.InstanceGeneration,
		AgentName:          in.AgentName,
		Namespace:          in.Namespace,
		Framework:          in.Framework,
		FrameworkVersion:   in.FrameworkVersion,
		Phase:              phase,
		Busy:               busy,
		InstanceRef:        in.InstanceRef,
		InstanceIP:         in.InstanceIP,
		AgentTaskID:        in.AgentTaskID,
		OriginType:         in.OriginType,
		OriginRef:          in.OriginRef,
		TaskContext:        append([]byte(nil), in.TaskContext...),
		StartedAt:          in.StartedAt,
		LastActiveAt:       in.LastActiveAt,
		TerminatedAt:       in.TerminatedAt,
		CreatedAt:          now,
		UpdatedAt:          now,
	}
	r.s.sessions[id] = s
	r.s.sessKey[key] = id
	return cloneSession(s), nil
}

func (r *sessionRepo) Get(_ context.Context, tenant, agentName, namespace, sessionID string) (*store.Session, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	id, ok := r.s.sessKey[sessCompositeKey(tenant, agentName, namespace, sessionID)]
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

func (r *sessionRepo) List(ctx context.Context, f store.SessionFilter) ([]*store.Session, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var out []*store.Session
	for _, s := range r.s.sessions {
		if !r.s.canReadSessionLocked(ctx, s) {
			continue
		}
		if f.Tenant != "" && s.Tenant != f.Tenant {
			continue
		}
		if f.AgentName != "" && s.AgentName != f.AgentName {
			continue
		}
		if f.AgentID != uuid.Nil && s.AgentID != f.AgentID {
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
		if f.PendingConversation {
			var data struct {
				Turn struct {
					State string `json:"state"`
				} `json:"conversationTurn"`
			}
			if json.Unmarshal(s.TaskContext, &data) != nil || (data.Turn.State != "dispatching" && data.Turn.State != "running") {
				continue
			}
		}
		if f.AgentTaskID != uuid.Nil && (s.AgentTaskID == nil || *s.AgentTaskID != f.AgentTaskID) {
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

func (r *sessionRepo) ArchiveMissing(_ context.Context, tenant, agentName, namespace string, keepSessionIDs []string, olderThan time.Duration) (int, error) {
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
		if s.Tenant != tenant || s.AgentName != agentName || s.Namespace != namespace {
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

func (r *sessionRepo) CountActive(_ context.Context, tenant, agentName, namespace string) (int32, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var n int32
	for _, s := range r.s.sessions {
		if s.Tenant == tenant && s.AgentName == agentName && s.Namespace == namespace &&
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
	if f.Tenant != "" && s.Tenant != f.Tenant {
		return false
	}
	if f.AgentName != "" && s.AgentName != f.AgentName {
		return false
	}
	if f.AgentID != uuid.Nil && s.AgentID != f.AgentID {
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
	if f.AgentTaskID != uuid.Nil && (s.AgentTaskID == nil || *s.AgentTaskID != f.AgentTaskID) {
		return false
	}
	return true
}

func (r *sessionRepo) DeleteByAgent(_ context.Context, tenant, agentName, namespace string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for id, s := range r.s.sessions {
		if s.Tenant == tenant && s.AgentName == agentName && s.Namespace == namespace {
			delete(r.s.sessKey, sessCompositeKey(s.Tenant, s.AgentName, s.Namespace, s.SessionID))
			delete(r.s.sessions, id)
		}
	}
	return nil
}

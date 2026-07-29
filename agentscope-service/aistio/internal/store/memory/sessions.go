package memory

import (
	"context"
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
	s := &store.Session{
		ID:               id,
		SessionID:        in.SessionID,
		AgentName:        in.AgentName,
		Namespace:        in.Namespace,
		Framework:        in.Framework,
		FrameworkVersion: in.FrameworkVersion,
		Phase:            phase,
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

func (r *sessionRepo) TerminateMissing(_ context.Context, agentName, namespace string, keepSessionIDs []string, olderThan time.Duration) (int, error) {
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
		if s.Phase == store.SessionPhaseTerminated {
			continue
		}
		if keep[s.SessionID] {
			continue
		}
		if !s.CreatedAt.Before(cutoff) {
			continue
		}
		s.Phase = store.SessionPhaseTerminated
		s.TerminatedAt = &now
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
		if s.AgentName == agentName && s.Namespace == namespace && s.Phase != store.SessionPhaseTerminated {
			n++
		}
	}
	return n, nil
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

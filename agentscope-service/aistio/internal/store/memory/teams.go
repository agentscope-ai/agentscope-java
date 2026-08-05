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
	"time"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type teamRepo struct{ s *Store }

func (r *teamRepo) Create(_ context.Context, team *store.Team) (*store.Team, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	key := teamKey(team.Namespace, team.Name)
	if _, ok := r.s.teams[key]; ok {
		return nil, store.ErrConflict
	}
	now := time.Now().UTC()
	cp := *team
	cp.ID = nextID(&r.s.nextTeamID)
	if cp.Phase == "" {
		cp.Phase = store.TeamPhasePending
	}
	cp.CreatedAt = now
	cp.UpdatedAt = now
	r.s.teams[key] = &cp
	out := cp
	return &out, nil
}

func (r *teamRepo) Get(_ context.Context, namespace, name string) (*store.Team, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	t, ok := r.s.teams[teamKey(namespace, name)]
	if !ok {
		return nil, store.ErrNotFound
	}
	cp := *t
	return &cp, nil
}

func (r *teamRepo) List(_ context.Context, namespace string) ([]*store.Team, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var out []*store.Team
	for _, t := range r.s.teams {
		if namespace != "" && t.Namespace != namespace {
			continue
		}
		cp := *t
		out = append(out, &cp)
	}
	return out, nil
}

func (r *teamRepo) UpdatePhase(_ context.Context, namespace, name, phase string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	t, ok := r.s.teams[teamKey(namespace, name)]
	if !ok {
		return store.ErrNotFound
	}
	t.Phase = phase
	t.UpdatedAt = time.Now().UTC()
	if phase == store.TeamPhaseRunning && t.StartedAt == nil {
		now := time.Now().UTC()
		t.StartedAt = &now
	}
	return nil
}

func (r *teamRepo) Update(_ context.Context, team *store.Team) (*store.Team, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	key := teamKey(team.Namespace, team.Name)
	cur, ok := r.s.teams[key]
	if !ok {
		return nil, store.ErrNotFound
	}
	cp := *team
	cp.ID = cur.ID
	cp.CreatedAt = cur.CreatedAt
	cp.UpdatedAt = time.Now().UTC()
	r.s.teams[key] = &cp
	out := cp
	return &out, nil
}

func (r *teamRepo) Delete(_ context.Context, namespace, name string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	key := teamKey(namespace, name)
	if _, ok := r.s.teams[key]; !ok {
		return store.ErrNotFound
	}
	delete(r.s.teams, key)
	kept := r.s.teamMembers[:0]
	for _, m := range r.s.teamMembers {
		if m.Namespace == namespace && m.TeamName == name {
			continue
		}
		kept = append(kept, m)
	}
	r.s.teamMembers = kept
	return nil
}

func (r *teamRepo) UpsertMember(_ context.Context, m *store.TeamMember) (*store.TeamMember, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	now := time.Now().UTC()
	for i := range r.s.teamMembers {
		cur := &r.s.teamMembers[i]
		if cur.Namespace == m.Namespace && cur.TeamName == m.TeamName && cur.MemberName == m.MemberName {
			id, created := cur.ID, cur.CreatedAt
			*cur = *m
			cur.ID = id
			cur.CreatedAt = created
			cur.UpdatedAt = now
			if cur.Phase == "" {
				cur.Phase = store.MemberPhaseJoining
			}
			if cur.Origin == "" {
				cur.Origin = store.MemberOriginStatic
			}
			if cur.DeployMode == "" {
				cur.DeployMode = store.MemberDeployBYO
			}
			cp := *cur
			return &cp, nil
		}
	}
	cp := *m
	cp.ID = nextID(&r.s.nextMemberID)
	if cp.Phase == "" {
		cp.Phase = store.MemberPhaseJoining
	}
	if cp.Origin == "" {
		cp.Origin = store.MemberOriginStatic
	}
	if cp.DeployMode == "" {
		cp.DeployMode = store.MemberDeployBYO
	}
	cp.CreatedAt = now
	cp.UpdatedAt = now
	r.s.teamMembers = append(r.s.teamMembers, cp)
	out := cp
	return &out, nil
}

func (r *teamRepo) GetMember(_ context.Context, namespace, teamName, memberName string) (*store.TeamMember, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	for i := range r.s.teamMembers {
		m := r.s.teamMembers[i]
		if m.Namespace == namespace && m.TeamName == teamName && m.MemberName == memberName {
			cp := m
			return &cp, nil
		}
	}
	return nil, store.ErrNotFound
}

func (r *teamRepo) ListMembers(_ context.Context, namespace, teamName string) ([]*store.TeamMember, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var out []*store.TeamMember
	for i := range r.s.teamMembers {
		m := r.s.teamMembers[i]
		if m.Namespace == namespace && m.TeamName == teamName {
			cp := m
			out = append(out, &cp)
		}
	}
	return out, nil
}

func (r *teamRepo) RemoveMember(_ context.Context, namespace, teamName, memberName string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	kept := r.s.teamMembers[:0]
	found := false
	for _, m := range r.s.teamMembers {
		if m.Namespace == namespace && m.TeamName == teamName && m.MemberName == memberName {
			found = true
			continue
		}
		kept = append(kept, m)
	}
	r.s.teamMembers = kept
	if !found {
		return store.ErrNotFound
	}
	return nil
}

func (r *teamRepo) BindMemberSession(_ context.Context, namespace, teamName, memberName, sessionID, managedSessionID, instanceRef string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for i := range r.s.teamMembers {
		m := &r.s.teamMembers[i]
		if m.Namespace == namespace && m.TeamName == teamName && m.MemberName == memberName {
			m.SessionID = sessionID
			m.ManagedSessionID = managedSessionID
			m.InstanceRef = instanceRef
			m.UpdatedAt = time.Now().UTC()
			return nil
		}
	}
	return store.ErrNotFound
}

func (r *teamRepo) UpdateMemberPhase(_ context.Context, namespace, teamName, memberName, phase string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for i := range r.s.teamMembers {
		m := &r.s.teamMembers[i]
		if m.Namespace == namespace && m.TeamName == teamName && m.MemberName == memberName {
			m.Phase = phase
			m.UpdatedAt = time.Now().UTC()
			return nil
		}
	}
	return store.ErrNotFound
}

func (r *teamRepo) FindMemberBySessionID(_ context.Context, sessionID string) (*store.TeamMember, error) {
	if sessionID == "" {
		return nil, store.ErrNotFound
	}
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var best *store.TeamMember
	for i := range r.s.teamMembers {
		m := &r.s.teamMembers[i]
		if m.SessionID == sessionID || m.ManagedSessionID == sessionID {
			cp := *m
			if best == nil || cp.UpdatedAt.After(best.UpdatedAt) {
				best = &cp
			}
		}
	}
	if best == nil {
		return nil, store.ErrNotFound
	}
	return best, nil
}

func teamKey(namespace, name string) string {
	return namespace + "/" + name
}

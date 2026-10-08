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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package memory

import (
	"context"
	"encoding/json"
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"slices"
	"sort"
	"time"
)

type accessRepo struct{ s *Store }

func (s *Store) canReadTargetLocked(ctx context.Context, kind, ref string) bool {
	if !store.WorkAccessFrom(ctx).Restricted {
		return true
	}
	id, err := uuid.Parse(ref)
	if err != nil {
		return false
	}
	switch kind {
	case "issue":
		return s.canReadIssueLocked(ctx, id)
	case "agent-task", "agent_task":
		t := s.agentTasks[id]
		return t != nil && s.canReadIssueLocked(ctx, t.IssueID)
	case "execution-attempt", "execution_attempt":
		e := s.executions[id]
		if e == nil {
			return false
		}
		t := s.agentTasks[e.AgentTaskID]
		return t != nil && s.canReadIssueLocked(ctx, t.IssueID)
	case "run-node", "run_node":
		n := s.runNodes[id]
		if n == nil {
			return false
		}
		r := s.runs[n.RunID]
		return r != nil && s.canReadIssueLocked(ctx, r.RootIssueID)
	}
	return false
}

func (s *Store) canReadSessionLocked(ctx context.Context, session *store.Session) bool {
	access := store.WorkAccessFrom(ctx)
	if !access.Restricted {
		return true
	}
	if session.AgentTaskID != nil {
		task := s.agentTasks[*session.AgentTaskID]
		return task != nil && s.canReadIssueLocked(ctx, task.IssueID)
	}
	for _, chat := range s.chats {
		if chat.SessionID == session.ID && slices.Contains(access.Refs, chat.CreatorRef) {
			return true
		}
	}
	return false
}

func (s *Store) canReadIssueLocked(ctx context.Context, id uuid.UUID) bool {
	access := store.WorkAccessFrom(ctx)
	if !access.Restricted {
		return true
	}
	seen := map[uuid.UUID]bool{}
	for !seen[id] {
		seen[id] = true
		issue := s.issues[id]
		if issue == nil {
			return false
		}
		if issue.ParentIssueID == nil {
			return issue.Access.Allows(issue.Creator, access.Refs, false)
		}
		id = *issue.ParentIssueID
	}
	return false
}
func cloneNamespace(n *controlmodel.Namespace) *controlmodel.Namespace {
	data, _ := json.Marshal(n)
	var copy controlmodel.Namespace
	_ = json.Unmarshal(data, &copy)
	return &copy
}
func (s *Store) Access() store.AccessRepository { return &accessRepo{s: s} }
func (r *accessRepo) GetNamespace(_ context.Context, tenant, name string) (*controlmodel.Namespace, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	n := r.s.accessNamespaces[tenant+"/"+name]
	if n == nil {
		return nil, store.ErrNotFound
	}
	return cloneNamespace(n), nil
}
func (r *accessRepo) ListNamespaces(_ context.Context, tenant, user string, limit, offset int) ([]*controlmodel.Namespace, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*controlmodel.Namespace{}
	for _, n := range r.s.accessNamespaces {
		if n.Tenant == tenant && (user == "" || len(n.Roles(user)) > 0) {
			out = append(out, cloneNamespace(n))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return page(out, offset, limit), nil
}
func (r *accessRepo) PutNamespace(_ context.Context, n *controlmodel.Namespace, version int64, actor string) (*controlmodel.Namespace, error) {
	if err := n.Validate(); err != nil {
		return nil, err
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if r.s.accessNamespaces == nil {
		r.s.accessNamespaces = map[string]*controlmodel.Namespace{}
	}
	key := n.Tenant + "/" + n.Name
	old := r.s.accessNamespaces[key]
	if old == nil && version != 0 || old != nil && old.Version != version {
		return nil, store.ErrConflict
	}
	if old != nil && (old.Owner != n.Owner || old.Kind != n.Kind) {
		return nil, store.ErrConflict
	}
	copy := cloneNamespace(n)
	copy.Version = version + 1
	r.s.accessNamespaces[key] = copy
	r.recordAuditLocked(copy, actor)
	return cloneNamespace(copy), nil
}

func (r *accessRepo) recordAuditLocked(n *controlmodel.Namespace, actor string) {
	r.s.accessNamespaceAudit = append(r.s.accessNamespaceAudit, &controlmodel.NamespaceAudit{ID: int64(len(r.s.accessNamespaceAudit) + 1), Tenant: n.Tenant, Name: n.Name, Actor: actor, Namespace: *cloneNamespace(n), Version: n.Version, CreatedAt: time.Now().UTC()})
}

func (r *accessRepo) TransferNamespace(_ context.Context, tenant, name, owner string, version int64, actor string) (*controlmodel.Namespace, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	n := r.s.accessNamespaces[tenant+"/"+name]
	if n == nil {
		return nil, store.ErrNotFound
	}
	if n.Version != version || n.Kind != "shared" || n.Archived || owner == "" {
		return nil, store.ErrConflict
	}
	copy := cloneNamespace(n)
	if copy.Members == nil {
		copy.Members = map[string][]string{}
	}
	if !slices.Contains(copy.Members[n.Owner], "admin") {
		copy.Members[n.Owner] = append(copy.Members[n.Owner], "admin")
	}
	copy.Owner = owner
	copy.Version++
	r.s.accessNamespaces[tenant+"/"+name] = copy
	r.recordAuditLocked(copy, actor)
	return cloneNamespace(copy), nil
}

func (r *accessRepo) ListNamespaceAudit(_ context.Context, tenant, name string, limit, offset int) ([]*controlmodel.NamespaceAudit, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	items := []*controlmodel.NamespaceAudit{}
	for i := len(r.s.accessNamespaceAudit) - 1; i >= 0; i-- {
		a := r.s.accessNamespaceAudit[i]
		if a.Tenant != tenant || name != "" && a.Name != name {
			continue
		}
		copy := *a
		copy.Namespace = *cloneNamespace(&a.Namespace)
		items = append(items, &copy)
	}
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	return page(items, offset, limit), nil
}

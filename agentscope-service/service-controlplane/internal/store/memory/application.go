/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package memory

import (
	"context"
	"sort"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

type applicationRepo struct{ s *Store }

func cloneApplication(in *model.Application) *model.Application {
	if in == nil {
		return nil
	}
	out := *in
	out.Members = make([]model.ApplicationMember, len(in.Members))
	for i, m := range in.Members {
		out.Members[i] = m
		out.Members[i].Roles = append([]string(nil), m.Roles...)
	}
	return &out
}
func (r *applicationRepo) Create(_ context.Context, in *model.Application) (*model.Application, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for _, v := range r.s.applications {
		if v.Tenant == in.Tenant && v.Namespace == in.Namespace && v.Name == in.Name {
			return nil, store.ErrConflict
		}
	}
	v := cloneApplication(in)
	v.ID = uuid.New()
	v.Version = 1
	v.TokensUsed = 0
	v.CreatedAt = time.Now().UTC()
	v.UpdatedAt = v.CreatedAt
	if v.Status == "" {
		v.Status = "active"
	}
	r.s.applications[v.ID] = v
	return cloneApplication(v), nil
}
func (r *applicationRepo) Get(_ context.Context, id uuid.UUID) (*model.Application, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	v := r.s.applications[id]
	if v == nil {
		return nil, store.ErrNotFound
	}
	return cloneApplication(v), nil
}
func (r *applicationRepo) List(_ context.Context, tenant, namespace string) ([]*model.Application, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*model.Application{}
	for _, v := range r.s.applications {
		if v.Tenant == tenant && v.Namespace == namespace {
			out = append(out, cloneApplication(v))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.After(out[j].CreatedAt) })
	return out, nil
}
func (r *applicationRepo) Update(_ context.Context, in *model.Application, version int64) (*model.Application, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	current := r.s.applications[in.ID]
	if current == nil {
		return nil, store.ErrNotFound
	}
	if current.Version != version {
		return nil, store.ErrConflict
	}
	for id, v := range r.s.applications {
		if id != in.ID && v.Tenant == in.Tenant && v.Namespace == in.Namespace && v.Name == in.Name {
			return nil, store.ErrConflict
		}
	}
	v := cloneApplication(in)
	v.Tenant = current.Tenant
	v.Namespace = current.Namespace
	v.OwnerUserID = current.OwnerUserID
	v.CreatedAt = current.CreatedAt
	v.TokensUsed = current.TokensUsed
	v.Version = version + 1
	v.UpdatedAt = time.Now().UTC()
	r.s.applications[v.ID] = v
	return cloneApplication(v), nil
}

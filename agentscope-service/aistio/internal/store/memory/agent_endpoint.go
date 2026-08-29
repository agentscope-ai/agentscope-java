// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package memory

import (
	"context"
	"sort"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type agentEndpointRepo struct{ s *Store }

func cloneEndpoint(v *controlmodel.AgentEndpoint) *controlmodel.AgentEndpoint {
	c := *v
	c.AuthPolicy, c.RateLimit = cloneJSON(v.AuthPolicy), cloneJSON(v.RateLimit)
	c.CredentialHash = append([]byte(nil), v.CredentialHash...)
	return &c
}
func (r *agentEndpointRepo) Create(_ context.Context, in *controlmodel.AgentEndpoint) (*controlmodel.AgentEndpoint, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for _, v := range r.s.agentEndpoints {
		if v.Slug == in.Slug {
			return nil, store.ErrConflict
		}
	}
	c := cloneEndpoint(in)
	if c.ID == uuid.Nil {
		c.ID = uuid.New()
	}
	now := time.Now().UTC()
	c.Version, c.CreatedAt, c.UpdatedAt = 1, now, now
	r.s.agentEndpoints[c.ID] = c
	return cloneEndpoint(c), nil
}
func (r *agentEndpointRepo) Get(_ context.Context, id uuid.UUID) (*controlmodel.AgentEndpoint, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	v := r.s.agentEndpoints[id]
	if v == nil {
		return nil, store.ErrNotFound
	}
	return cloneEndpoint(v), nil
}
func (r *agentEndpointRepo) GetBySlug(_ context.Context, slug string) (*controlmodel.AgentEndpoint, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	for _, v := range r.s.agentEndpoints {
		if v.Slug == slug {
			return cloneEndpoint(v), nil
		}
	}
	return nil, store.ErrNotFound
}
func (r *agentEndpointRepo) List(_ context.Context, tenant, namespace string) ([]*controlmodel.AgentEndpoint, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*controlmodel.AgentEndpoint{}
	for _, v := range r.s.agentEndpoints {
		if (tenant == "" || v.Tenant == tenant) && (namespace == "" || v.Namespace == namespace) {
			out = append(out, cloneEndpoint(v))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UpdatedAt.After(out[j].UpdatedAt) })
	return out, nil
}
func (r *agentEndpointRepo) Update(_ context.Context, in *controlmodel.AgentEndpoint, expected int64) (*controlmodel.AgentEndpoint, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	v := r.s.agentEndpoints[in.ID]
	if v == nil {
		return nil, store.ErrNotFound
	}
	if v.Version != expected {
		return nil, store.ErrConflict
	}
	c := cloneEndpoint(in)
	c.CreatedAt = v.CreatedAt
	c.Version = v.Version + 1
	c.UpdatedAt = time.Now().UTC()
	r.s.agentEndpoints[c.ID] = c
	return cloneEndpoint(c), nil
}
func (r *agentEndpointRepo) ReserveJob(_ context.Context, endpointID uuid.UUID, key string) (*controlmodel.EndpointJob, bool, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for _, v := range r.s.endpointJobs {
		if v.EndpointID == endpointID && v.IdempotencyKey == key {
			c := *v
			return &c, false, nil
		}
	}
	now := time.Now().UTC()
	v := &controlmodel.EndpointJob{ID: uuid.New(), EndpointID: endpointID, IdempotencyKey: key, CreatedAt: now, UpdatedAt: now}
	r.s.endpointJobs[v.ID] = v
	c := *v
	return &c, true, nil
}
func (r *agentEndpointRepo) CompleteJob(_ context.Context, id, issueID, runID uuid.UUID) (*controlmodel.EndpointJob, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	v := r.s.endpointJobs[id]
	if v == nil {
		return nil, store.ErrNotFound
	}
	v.IssueID = &issueID
	v.RunID = &runID
	v.UpdatedAt = time.Now().UTC()
	c := *v
	return &c, nil
}
func (r *agentEndpointRepo) GetJobByIssue(_ context.Context, issueID uuid.UUID) (*controlmodel.EndpointJob, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	for _, v := range r.s.endpointJobs {
		if v.IssueID != nil && *v.IssueID == issueID {
			c := *v
			return &c, nil
		}
	}
	return nil, store.ErrNotFound
}

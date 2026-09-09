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
	"sort"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type endpointRepo struct{ s *Store }

type endpointRateWindow struct {
	started time.Time
	count   int
}

func cloneEndpoint(v *controlmodel.Endpoint) *controlmodel.Endpoint {
	if v == nil {
		return nil
	}
	c := *v
	c.InputSchema, c.OutputSchema = cloneJSON(v.InputSchema), cloneJSON(v.OutputSchema)
	c.AuthPolicy, c.RateLimit = cloneJSON(v.AuthPolicy), cloneJSON(v.RateLimit)
	if v.ActiveReleaseID != nil {
		id := *v.ActiveReleaseID
		c.ActiveReleaseID = &id
	}
	return &c
}

func cloneEndpointCredential(v *controlmodel.EndpointCredential) *controlmodel.EndpointCredential {
	if v == nil {
		return nil
	}
	c := *v
	c.SecretHash = append([]byte(nil), v.SecretHash...)
	c.SecretCiphertext = append([]byte(nil), v.SecretCiphertext...)
	c.Recoverable = len(c.SecretCiphertext) > 0
	c.Scopes = cloneJSON(v.Scopes)
	return &c
}

func cloneEndpointRelease(v *controlmodel.EndpointRelease) *controlmodel.EndpointRelease {
	if v == nil {
		return nil
	}
	c := *v
	return &c
}

func cloneEndpointInvocation(v *controlmodel.EndpointInvocation) *controlmodel.EndpointInvocation {
	if v == nil {
		return nil
	}
	c := *v
	c.Input, c.Result = cloneJSON(v.Input), cloneJSON(v.Result)
	return &c
}

func cloneEndpointConversation(v *controlmodel.EndpointConversation) *controlmodel.EndpointConversation {
	if v == nil {
		return nil
	}
	c := *v
	return &c
}

func (r *endpointRepo) Create(_ context.Context, in *controlmodel.Endpoint) (*controlmodel.Endpoint, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for _, v := range r.s.endpoints {
		if v.Slug == in.Slug || (v.Tenant == in.Tenant && v.Namespace == in.Namespace && v.Name == in.Name) {
			return nil, store.ErrConflict
		}
	}
	c := cloneEndpoint(in)
	if c.ID == uuid.Nil {
		c.ID = uuid.New()
	}
	if c.Status == "" {
		c.Status = controlmodel.EndpointDraft
	}
	if c.EventSchemaVersion == "" {
		c.EventSchemaVersion = "v1"
	}
	if c.TimeoutSeconds <= 0 {
		c.TimeoutSeconds = 300
	}
	if c.MaxPayloadBytes <= 0 {
		c.MaxPayloadBytes = 1 << 20
	}
	now := time.Now().UTC()
	c.Version, c.CreatedAt, c.UpdatedAt = 1, now, now
	r.s.endpoints[c.ID] = c
	return cloneEndpoint(c), nil
}

func (r *endpointRepo) Get(_ context.Context, id uuid.UUID) (*controlmodel.Endpoint, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	v := r.s.endpoints[id]
	if v == nil {
		return nil, store.ErrNotFound
	}
	return cloneEndpoint(v), nil
}

func (r *endpointRepo) GetBySlug(_ context.Context, slug string) (*controlmodel.Endpoint, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	for _, v := range r.s.endpoints {
		if v.Slug == slug {
			return cloneEndpoint(v), nil
		}
	}
	return nil, store.ErrNotFound
}

func (r *endpointRepo) List(_ context.Context, tenant, namespace string) ([]*controlmodel.Endpoint, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*controlmodel.Endpoint{}
	for _, v := range r.s.endpoints {
		if (tenant == "" || v.Tenant == tenant) && (namespace == "" || v.Namespace == namespace) {
			out = append(out, cloneEndpoint(v))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UpdatedAt.After(out[j].UpdatedAt) })
	return out, nil
}

func (r *endpointRepo) Update(_ context.Context, in *controlmodel.Endpoint, expected int64) (*controlmodel.Endpoint, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	v := r.s.endpoints[in.ID]
	if v == nil {
		return nil, store.ErrNotFound
	}
	if v.Version != expected {
		return nil, store.ErrConflict
	}
	for id, other := range r.s.endpoints {
		if id != in.ID && (other.Slug == in.Slug ||
			(other.Tenant == in.Tenant && other.Namespace == in.Namespace && other.Name == in.Name)) {
			return nil, store.ErrConflict
		}
	}
	c := cloneEndpoint(in)
	c.CreatedAt = v.CreatedAt
	c.Version = v.Version + 1
	c.UpdatedAt = time.Now().UTC()
	r.s.endpoints[c.ID] = c
	return cloneEndpoint(c), nil
}

func (r *endpointRepo) DeployRelease(_ context.Context, endpointID uuid.UUID,
	targetType controlmodel.EndpointTargetType, targetRef uuid.UUID, expected int64,
	actor controlmodel.Actor, reason string) (*controlmodel.Endpoint, *controlmodel.EndpointRelease, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	endpoint := r.s.endpoints[endpointID]
	if endpoint == nil {
		return nil, nil, store.ErrNotFound
	}
	if endpoint.Version != expected || endpoint.TargetType != targetType {
		return nil, nil, store.ErrConflict
	}
	number := 1
	for _, release := range r.s.endpointReleases {
		if release.EndpointID == endpointID && release.Number >= number {
			number = release.Number + 1
		}
	}
	now := time.Now().UTC()
	release := &controlmodel.EndpointRelease{ID: uuid.New(), EndpointID: endpointID, Number: number,
		TargetType: targetType, TargetRef: targetRef, CreatedBy: actor, Reason: reason,
		CreatedAt: now, ActivatedAt: now}
	r.s.endpointReleases[release.ID] = release
	updated := cloneEndpoint(endpoint)
	updated.TargetRef = targetRef
	updated.ActiveReleaseID = &release.ID
	updated.ActiveRelease = number
	updated.Version++
	updated.UpdatedAt = now
	r.s.endpoints[endpointID] = updated
	return cloneEndpoint(updated), cloneEndpointRelease(release), nil
}

func (r *endpointRepo) GetRelease(_ context.Context, endpointID, releaseID uuid.UUID) (*controlmodel.EndpointRelease, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	release := r.s.endpointReleases[releaseID]
	if release == nil || release.EndpointID != endpointID {
		return nil, store.ErrNotFound
	}
	return cloneEndpointRelease(release), nil
}

func (r *endpointRepo) ListReleases(_ context.Context, endpointID uuid.UUID) ([]*controlmodel.EndpointRelease, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*controlmodel.EndpointRelease{}
	for _, release := range r.s.endpointReleases {
		if release.EndpointID == endpointID {
			out = append(out, cloneEndpointRelease(release))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Number > out[j].Number })
	return out, nil
}

func (r *endpointRepo) CreateCredential(_ context.Context, in *controlmodel.EndpointCredential) (*controlmodel.EndpointCredential, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if r.s.endpoints[in.EndpointID] == nil {
		return nil, store.ErrNotFound
	}
	for _, v := range r.s.endpointCredentials {
		if v.EndpointID == in.EndpointID && (v.Name == in.Name || v.KeyPrefix == in.KeyPrefix) {
			return nil, store.ErrConflict
		}
	}
	c := cloneEndpointCredential(in)
	if c.ID == uuid.Nil {
		c.ID = uuid.New()
	}
	if c.Status == "" {
		c.Status = controlmodel.EndpointCredentialActive
	}
	c.CreatedAt = time.Now().UTC()
	r.s.endpointCredentials[c.ID] = c
	return cloneEndpointCredential(c), nil
}

func (r *endpointRepo) ListCredentials(_ context.Context, endpointID uuid.UUID) ([]*controlmodel.EndpointCredential, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*controlmodel.EndpointCredential{}
	for _, v := range r.s.endpointCredentials {
		if v.EndpointID == endpointID {
			out = append(out, cloneEndpointCredential(v))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.After(out[j].CreatedAt) })
	return out, nil
}

func (r *endpointRepo) GetCredentialByPrefix(_ context.Context, endpointID uuid.UUID, prefix string) (*controlmodel.EndpointCredential, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	for _, v := range r.s.endpointCredentials {
		if v.EndpointID == endpointID && v.KeyPrefix == prefix {
			return cloneEndpointCredential(v), nil
		}
	}
	return nil, store.ErrNotFound
}

func (r *endpointRepo) UpdateCredential(_ context.Context, in *controlmodel.EndpointCredential) (*controlmodel.EndpointCredential, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if r.s.endpointCredentials[in.ID] == nil {
		return nil, store.ErrNotFound
	}
	c := cloneEndpointCredential(in)
	r.s.endpointCredentials[c.ID] = c
	return cloneEndpointCredential(c), nil
}

func (r *endpointRepo) ConsumeRateLimit(_ context.Context, endpointID uuid.UUID, principal string,
	limit, windowSeconds int, now time.Time) (bool, time.Duration, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if r.s.endpoints[endpointID] == nil {
		return false, 0, store.ErrNotFound
	}
	if windowSeconds <= 0 {
		windowSeconds = 60
	}
	duration := time.Duration(windowSeconds) * time.Second
	started := now.Truncate(duration)
	key := endpointID.String() + "\x00" + principal + "\x00" + started.Format(time.RFC3339Nano)
	window := r.s.endpointRateWindows[key]
	if window == nil {
		window = &endpointRateWindow{started: started}
		r.s.endpointRateWindows[key] = window
	}
	window.count++
	retryAfter := duration - now.Sub(window.started)
	return window.count <= limit, retryAfter, nil
}

func (r *endpointRepo) ReserveInvocation(_ context.Context, in *controlmodel.EndpointInvocation) (*controlmodel.EndpointInvocation, bool, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if r.s.endpoints[in.EndpointID] == nil {
		return nil, false, store.ErrNotFound
	}
	if in.IdempotencyKey != "" {
		for _, v := range r.s.endpointInvocations {
			if v.EndpointID == in.EndpointID && v.Mode == in.Mode && v.PrincipalRef == in.PrincipalRef &&
				v.IdempotencyKey == in.IdempotencyKey {
				return cloneEndpointInvocation(v), false, nil
			}
		}
	}
	c := cloneEndpointInvocation(in)
	if c.ID == uuid.Nil {
		c.ID = uuid.New()
	}
	if c.Status == "" {
		c.Status = controlmodel.EndpointInvocationAccepted
	}
	now := time.Now().UTC()
	c.CreatedAt, c.UpdatedAt = now, now
	r.s.endpointInvocations[c.ID] = c
	return cloneEndpointInvocation(c), true, nil
}

func (r *endpointRepo) GetInvocation(_ context.Context, id uuid.UUID) (*controlmodel.EndpointInvocation, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	v := r.s.endpointInvocations[id]
	if v == nil {
		return nil, store.ErrNotFound
	}
	return cloneEndpointInvocation(v), nil
}

func (r *endpointRepo) ListInvocations(_ context.Context, filter store.EndpointInvocationFilter) ([]*controlmodel.EndpointInvocation, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*controlmodel.EndpointInvocation{}
	for _, v := range r.s.endpointInvocations {
		if filter.EndpointID != uuid.Nil && v.EndpointID != filter.EndpointID {
			continue
		}
		if filter.RunID != uuid.Nil && (v.RunID == nil || *v.RunID != filter.RunID) {
			continue
		}
		if filter.Mode != "" && v.Mode != filter.Mode {
			continue
		}
		if filter.Status != "" && v.Status != filter.Status {
			continue
		}
		if filter.ActiveOnly && endpointInvocationTerminal(v.Status) {
			continue
		}
		out = append(out, cloneEndpointInvocation(v))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.After(out[j].CreatedAt) })
	if filter.Limit > 0 && len(out) > filter.Limit {
		out = out[:filter.Limit]
	}
	return out, nil
}

func endpointInvocationTerminal(status controlmodel.EndpointInvocationStatus) bool {
	return status == controlmodel.EndpointInvocationCompleted || status == controlmodel.EndpointInvocationFailed ||
		status == controlmodel.EndpointInvocationCancelled || status == controlmodel.EndpointInvocationTimedOut
}

func (r *endpointRepo) UpdateInvocation(_ context.Context, in *controlmodel.EndpointInvocation) (*controlmodel.EndpointInvocation, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if r.s.endpointInvocations[in.ID] == nil {
		return nil, store.ErrNotFound
	}
	c := cloneEndpointInvocation(in)
	c.UpdatedAt = time.Now().UTC()
	r.s.endpointInvocations[c.ID] = c
	return cloneEndpointInvocation(c), nil
}

func (r *endpointRepo) CreateConversation(_ context.Context, in *controlmodel.EndpointConversation) (*controlmodel.EndpointConversation, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for _, v := range r.s.endpointConversations {
		if v.EndpointID == in.EndpointID && v.SessionID == in.SessionID {
			return nil, store.ErrConflict
		}
	}
	c := cloneEndpointConversation(in)
	if c.ID == uuid.Nil {
		c.ID = uuid.New()
	}
	if c.Status == "" {
		c.Status = controlmodel.EndpointConversationActive
	}
	now := time.Now().UTC()
	c.CreatedAt, c.UpdatedAt = now, now
	r.s.endpointConversations[c.ID] = c
	return cloneEndpointConversation(c), nil
}

func (r *endpointRepo) GetConversation(_ context.Context, id uuid.UUID) (*controlmodel.EndpointConversation, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	v := r.s.endpointConversations[id]
	if v == nil {
		return nil, store.ErrNotFound
	}
	return cloneEndpointConversation(v), nil
}

func (r *endpointRepo) UpdateConversation(_ context.Context, in *controlmodel.EndpointConversation) (*controlmodel.EndpointConversation, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if r.s.endpointConversations[in.ID] == nil {
		return nil, store.ErrNotFound
	}
	c := cloneEndpointConversation(in)
	c.UpdatedAt = time.Now().UTC()
	r.s.endpointConversations[c.ID] = c
	return cloneEndpointConversation(c), nil
}

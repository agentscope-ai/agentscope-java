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
	"bytes"
	"context"
	"encoding/json"
	"slices"
	"sort"
	"strings"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type agentCatalogRepo struct{ s *Store }

func cloneAgent(in *controlmodel.Agent) *controlmodel.Agent {
	cp := *in
	cp.Capabilities = cloneJSON(in.Capabilities)
	cp.Labels = cloneJSON(in.Labels)
	cp.Metadata = cloneJSON(in.Metadata)
	return &cp
}

func cloneAgentBinding(in *controlmodel.AgentBinding) *controlmodel.AgentBinding {
	cp := *in
	cp.Configuration = cloneJSON(in.Configuration)
	return &cp
}

func cloneAgentCredential(in *controlmodel.AgentRegistrationCredential) *controlmodel.AgentRegistrationCredential {
	cp := *in
	cp.TokenHash = append([]byte(nil), in.TokenHash...)
	return &cp
}

func normalizeAgent(in *controlmodel.Agent, now time.Time) (*controlmodel.Agent, error) {
	if in == nil || strings.TrimSpace(in.Tenant) == "" || strings.TrimSpace(in.Namespace) == "" || strings.TrimSpace(in.AgentKey) == "" {
		return nil, store.ErrConflict
	}
	cp := cloneAgent(in)
	if cp.ID == uuid.Nil {
		cp.ID = uuid.New()
	}
	if cp.DisplayName == "" {
		cp.DisplayName = cp.AgentKey
	}
	if cp.Status == "" {
		cp.Status = controlmodel.AgentProvisioning
	}
	cp.Version = 1
	cp.CreatedAt = now
	cp.UpdatedAt = now
	return cp, nil
}

func (r *agentCatalogRepo) CreateAgent(_ context.Context, in *controlmodel.Agent) (*controlmodel.Agent, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for _, existing := range r.s.logicalAgents {
		if existing.Tenant == in.Tenant && existing.Namespace == in.Namespace && existing.AgentKey == in.AgentKey && existing.ArchivedAt == nil {
			return nil, store.ErrConflict
		}
	}
	cp, err := normalizeAgent(in, time.Now().UTC())
	if err != nil {
		return nil, err
	}
	r.s.logicalAgents[cp.ID] = cloneAgent(cp)
	return cp, nil
}

func (r *agentCatalogRepo) GetAgent(_ context.Context, id uuid.UUID) (*controlmodel.Agent, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	in := r.s.logicalAgents[id]
	if in == nil {
		return nil, store.ErrNotFound
	}
	return cloneAgent(in), nil
}

func (r *agentCatalogRepo) GetAgentByKey(_ context.Context, tenant, namespace, key string) (*controlmodel.Agent, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	for _, in := range r.s.logicalAgents {
		if in.Tenant == tenant && in.Namespace == namespace && in.AgentKey == key && in.ArchivedAt == nil {
			return cloneAgent(in), nil
		}
	}
	return nil, store.ErrNotFound
}

func (r *agentCatalogRepo) ListAgents(_ context.Context, filter store.AgentFilter) ([]*controlmodel.Agent, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*controlmodel.Agent, 0)
	for _, in := range r.s.logicalAgents {
		if filter.Tenant != "" && in.Tenant != filter.Tenant || filter.Namespace != "" && in.Namespace != filter.Namespace || filter.Status != "" && in.Status != filter.Status || !filter.IncludeArchived && in.ArchivedAt != nil {
			continue
		}
		if slices.Contains(filter.ExcludedIDs, in.ID) {
			continue
		}
		out = append(out, cloneAgent(in))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].AgentKey < out[j].AgentKey })
	if filter.Offset > 0 {
		if filter.Offset >= len(out) {
			return []*controlmodel.Agent{}, nil
		}
		out = out[filter.Offset:]
	}
	if filter.Limit > 0 && len(out) > filter.Limit {
		out = out[:filter.Limit]
	}
	return out, nil
}

func (r *agentCatalogRepo) UpdateAgent(_ context.Context, in *controlmodel.Agent, expected int64) (*controlmodel.Agent, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	old := r.s.logicalAgents[in.ID]
	if old == nil {
		return nil, store.ErrNotFound
	}
	if expected != old.Version {
		return nil, store.ErrConflict
	}
	cp := cloneAgent(in)
	cp.Tenant, cp.Namespace, cp.AgentKey = old.Tenant, old.Namespace, old.AgentKey
	cp.CreatedAt, cp.Version, cp.UpdatedAt = old.CreatedAt, old.Version+1, time.Now().UTC()
	r.s.logicalAgents[cp.ID] = cloneAgent(cp)
	return cp, nil
}

func prepareBinding(in *controlmodel.AgentBinding, now time.Time) (*controlmodel.AgentBinding, error) {
	if err := in.Validate(); err != nil {
		return nil, err
	}
	cp := cloneAgentBinding(in)
	if cp.ID == uuid.Nil {
		cp.ID = uuid.New()
	}
	cp.Version, cp.CreatedAt, cp.UpdatedAt = 1, now, now
	return cp, nil
}

func (r *agentCatalogRepo) CreateBinding(_ context.Context, in *controlmodel.AgentBinding) (*controlmodel.AgentBinding, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	agent := r.s.logicalAgents[in.AgentID]
	if agent == nil || agent.Tenant != in.Tenant || agent.Namespace != in.Namespace {
		return nil, store.ErrNotFound
	}
	cp, err := prepareBinding(in, time.Now().UTC())
	if err != nil {
		return nil, err
	}
	r.s.agentBindings[cp.ID] = cloneAgentBinding(cp)
	return cp, nil
}

func (r *agentCatalogRepo) GetBinding(_ context.Context, id uuid.UUID) (*controlmodel.AgentBinding, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	in := r.s.agentBindings[id]
	if in == nil {
		return nil, store.ErrNotFound
	}
	return cloneAgentBinding(in), nil
}

func (r *agentCatalogRepo) ListBindings(_ context.Context, agentID uuid.UUID, includeDisabled bool) ([]*controlmodel.AgentBinding, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*controlmodel.AgentBinding, 0)
	for _, in := range r.s.agentBindings {
		if in.AgentID != agentID || in.ArchivedAt != nil || !includeDisabled && !in.Enabled {
			continue
		}
		out = append(out, cloneAgentBinding(in))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Priority > out[j].Priority })
	return out, nil
}

func (r *agentCatalogRepo) UpdateBinding(_ context.Context, in *controlmodel.AgentBinding, expected int64) (*controlmodel.AgentBinding, error) {
	if err := in.Validate(); err != nil {
		return nil, err
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	old := r.s.agentBindings[in.ID]
	if old == nil {
		return nil, store.ErrNotFound
	}
	if old.Version != expected || old.AgentID != in.AgentID {
		return nil, store.ErrConflict
	}
	cp := cloneAgentBinding(in)
	cp.Tenant, cp.Namespace, cp.Kind = old.Tenant, old.Namespace, old.Kind
	cp.CreatedAt, cp.UpdatedAt, cp.Version = old.CreatedAt, time.Now().UTC(), old.Version+1
	r.s.agentBindings[cp.ID] = cloneAgentBinding(cp)
	return cp, nil
}

func (r *agentCatalogRepo) RegisterExternal(_ context.Context, req store.ExternalAgentRegistration) (*store.ExternalAgentRegistrationResult, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	now := time.Now().UTC()
	var agent *controlmodel.Agent
	for _, candidate := range r.s.logicalAgents {
		if candidate.Tenant == req.Tenant && candidate.Namespace == req.Namespace && candidate.AgentKey == req.AgentKey && candidate.ArchivedAt == nil {
			agent = candidate
			break
		}
	}
	createdCredential := false
	var credential *controlmodel.AgentRegistrationCredential
	var binding *controlmodel.AgentBinding
	if agent == nil {
		if len(req.NewCredentialHash) == 0 {
			return nil, store.ErrForbidden
		}
		agent, _ = normalizeAgent(&controlmodel.Agent{Tenant: req.Tenant, Namespace: req.Namespace,
			AgentKey: req.AgentKey, DisplayName: req.DisplayName, Description: req.Description,
			OwnerType: req.OwnerType, OwnerRef: req.OwnerRef, Status: controlmodel.AgentActive,
			Capabilities: req.Capabilities, Labels: req.Labels}, now)
		r.s.logicalAgents[agent.ID] = cloneAgent(agent)
		cfg, _ := json.Marshal(controlmodel.ExternalBindingConfiguration{})
		binding, _ = prepareBinding(&controlmodel.AgentBinding{AgentID: agent.ID, Tenant: agent.Tenant,
			Namespace: agent.Namespace, Kind: controlmodel.DataPlaneExternalApplication,
			Configuration: cfg, Priority: 100, Enabled: true}, now)
		r.s.agentBindings[binding.ID] = cloneAgentBinding(binding)
		runtimeBinding, _ := binding.RuntimeBinding()
		policy := &controlmodel.AgentRuntimePolicy{ID: uuid.New(), Tenant: agent.Tenant, Namespace: agent.Namespace,
			AgentRef: agent.ID.String(), SelectionMode: "ordered", FallbackMode: "disabled", Version: 1,
			Candidates: []controlmodel.RuntimeBindingCandidate{{Binding: runtimeBinding}},
			CreatedAt:  now, UpdatedAt: now}
		r.s.runtimePolicies[orchPolicyKey(agent.Tenant, agent.Namespace, agent.ID.String())] = policy
	} else {
		if agent.Status != controlmodel.AgentActive {
			return nil, store.ErrForbidden
		}
		for _, candidate := range r.s.agentBindings {
			if candidate.AgentID == agent.ID && candidate.Kind == controlmodel.DataPlaneExternalApplication && candidate.Enabled && candidate.ArchivedAt == nil {
				binding = candidate
				break
			}
		}
		if binding == nil {
			return nil, store.ErrConflict
		}
	}
	if len(req.NewCredentialHash) == 0 {
		return nil, store.ErrForbidden
	}
	credential = &controlmodel.AgentRegistrationCredential{ID: uuid.New(), AgentID: agent.ID,
		TokenHash: append([]byte(nil), req.NewCredentialHash...), Status: controlmodel.RegistrationCredentialActive,
		ExpiresAt: req.CredentialExpiresAt, CreatedAt: now, UpdatedAt: now}
	r.s.agentCredentials[credential.ID] = cloneAgentCredential(credential)
	createdCredential = true
	var current *controlmodel.AgentInstance
	for _, candidate := range r.s.agentInstances {
		if candidate.AgentID == agent.ID && candidate.BindingID == binding.ID && candidate.InstanceKey == req.InstanceKey {
			current = candidate
			break
		}
	}
	instance := &controlmodel.AgentInstance{Tenant: req.Tenant, Namespace: req.Namespace,
		AgentID: agent.ID, BindingID: binding.ID, BackendKind: controlmodel.DataPlaneExternalApplication,
		InstanceKey: req.InstanceKey, Framework: req.Framework, FrameworkVersion: req.FrameworkVersion,
		SDKVersion: req.SDKVersion, Capabilities: cloneJSON(req.Capabilities), Labels: cloneJSON(req.Labels),
		RoutingKey: req.RoutingKey, Health: controlmodel.RuntimeHealthHealthy, Capacity: req.Capacity,
		LastSeenAt: now, CreatedAt: now, UpdatedAt: now, Generation: 1}
	if current != nil {
		instance.ID, instance.CreatedAt, instance.Generation = current.ID, current.CreatedAt, current.Generation+1
	} else {
		instance.ID = uuid.New()
	}
	r.s.agentInstances[instance.ID] = cloneAgentInstance(instance)
	var credentialCopy *controlmodel.AgentRegistrationCredential
	if credential != nil {
		credentialCopy = cloneAgentCredential(credential)
	}
	return &store.ExternalAgentRegistrationResult{Agent: cloneAgent(agent), Binding: cloneAgentBinding(binding),
		Instance: cloneAgentInstance(instance), Credential: credentialCopy, CredentialCreated: createdCredential}, nil
}

func (r *agentCatalogRepo) RotateRegistrationCredential(_ context.Context, agentID uuid.UUID, hash []byte, expires *time.Time) (*controlmodel.AgentRegistrationCredential, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if r.s.logicalAgents[agentID] == nil || len(hash) == 0 {
		return nil, store.ErrNotFound
	}
	now := time.Now().UTC()
	for _, old := range r.s.agentCredentials {
		if old.AgentID == agentID && old.Status == controlmodel.RegistrationCredentialActive {
			old.Status, old.RotatedAt, old.UpdatedAt = controlmodel.RegistrationCredentialRevoked, &now, now
		}
	}
	credential := &controlmodel.AgentRegistrationCredential{ID: uuid.New(), AgentID: agentID,
		TokenHash: append([]byte(nil), hash...), Status: controlmodel.RegistrationCredentialActive,
		ExpiresAt: expires, CreatedAt: now, UpdatedAt: now}
	r.s.agentCredentials[credential.ID] = cloneAgentCredential(credential)
	return cloneAgentCredential(credential), nil
}

func (r *agentCatalogRepo) RevokeRegistrationCredential(_ context.Context, agentID, credentialID uuid.UUID) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	credential := r.s.agentCredentials[credentialID]
	if credential == nil || credential.AgentID != agentID {
		return store.ErrNotFound
	}
	now := time.Now().UTC()
	credential.Status, credential.RotatedAt, credential.UpdatedAt = controlmodel.RegistrationCredentialRevoked, &now, now
	return nil
}

func (r *agentCatalogRepo) ValidateInstanceClaim(_ context.Context, claim store.AgentInstanceClaim) (*controlmodel.AgentInstance, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	agent := r.s.logicalAgents[claim.AgentID]
	if agent == nil || agent.Status != controlmodel.AgentActive || agent.Tenant != claim.Tenant ||
		agent.Namespace != claim.Namespace || agent.AgentKey != claim.AgentKey {
		return nil, store.ErrForbidden
	}
	binding := r.s.agentBindings[claim.BindingID]
	if binding == nil || binding.AgentID != agent.ID || binding.Kind != controlmodel.DataPlaneExternalApplication ||
		!binding.Enabled || binding.ArchivedAt != nil {
		return nil, store.ErrForbidden
	}
	var instance *controlmodel.AgentInstance
	for _, candidate := range r.s.agentInstances {
		if candidate.AgentID == claim.AgentID && candidate.BindingID == claim.BindingID &&
			candidate.InstanceKey == claim.InstanceKey && candidate.Generation == claim.Generation {
			instance = candidate
			break
		}
	}
	if instance == nil {
		return nil, store.ErrForbidden
	}
	if !claim.TrustedWorkloadIdentity {
		now := time.Now().UTC()
		valid := false
		for _, credential := range r.s.agentCredentials {
			if credential.AgentID == claim.AgentID && credential.Status == controlmodel.RegistrationCredentialActive &&
				(credential.ExpiresAt == nil || credential.ExpiresAt.After(now)) && bytes.Equal(credential.TokenHash, claim.CredentialHash) {
				valid = true
				break
			}
		}
		if !valid {
			return nil, store.ErrForbidden
		}
	}
	return cloneAgentInstance(instance), nil
}

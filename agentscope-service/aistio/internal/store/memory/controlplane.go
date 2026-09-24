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
	"fmt"
	"sort"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type controlPlaneRepo struct{ s *Store }

func registryKey(tenant, namespace, name string) string {
	return tenant + "\x00" + namespace + "\x00" + name
}

func cloneJSON(in json.RawMessage) json.RawMessage {
	if in == nil {
		return nil
	}
	return append(json.RawMessage(nil), in...)
}

func cloneAgentInstance(in *controlmodel.AgentInstance) *controlmodel.AgentInstance {
	cp := *in
	cp.Capabilities = cloneJSON(in.Capabilities)
	cp.Labels = cloneJSON(in.Labels)
	return &cp
}

func cloneRuntimeProfile(in *controlmodel.RuntimeProfile) *controlmodel.RuntimeProfile {
	cp := *in
	cp.Configuration = cloneJSON(in.Configuration)
	cp.Requirements = cloneJSON(in.Requirements)
	return &cp
}

func cloneRuntimePool(in *controlmodel.RuntimePool) *controlmodel.RuntimePool {
	cp := *in
	cp.HostSelector = cloneJSON(in.HostSelector)
	cp.Configuration = cloneJSON(in.Configuration)
	return &cp
}

func cloneRuntimeHost(in *controlmodel.RuntimeHost) *controlmodel.RuntimeHost {
	cp := *in
	cp.Labels = cloneJSON(in.Labels)
	cp.Capabilities = cloneJSON(in.Capabilities)
	return &cp
}

func cloneExecution(in *controlmodel.ExecutionAttempt) *controlmodel.ExecutionAttempt {
	cp := *in
	cp.RuntimeBinding = cloneJSON(in.RuntimeBinding)
	cp.RequiredCapabilities = cloneJSON(in.RequiredCapabilities)
	cp.Checkpoint = cloneJSON(in.Checkpoint)
	cp.Result = cloneJSON(in.Result)
	cp.Usage = cloneJSON(in.Usage)
	if in.AgentInstanceID != nil {
		v := *in.AgentInstanceID
		cp.AgentInstanceID = &v
	}
	if in.HostID != nil {
		v := *in.HostID
		cp.HostID = &v
	}
	if in.LeaseExpiresAt != nil {
		v := *in.LeaseExpiresAt
		cp.LeaseExpiresAt = &v
	}
	if in.HeartbeatAt != nil {
		v := *in.HeartbeatAt
		cp.HeartbeatAt = &v
	}
	if in.CancelRequestedAt != nil {
		v := *in.CancelRequestedAt
		cp.CancelRequestedAt = &v
	}
	if in.StartedAt != nil {
		v := *in.StartedAt
		cp.StartedAt = &v
	}
	if in.CompletedAt != nil {
		v := *in.CompletedAt
		cp.CompletedAt = &v
	}
	return &cp
}

func cloneOutboxEvent(in *controlmodel.OutboxEvent) *controlmodel.OutboxEvent {
	cp := *in
	cp.Payload = cloneJSON(in.Payload)
	if in.ClaimedUntil != nil {
		v := *in.ClaimedUntil
		cp.ClaimedUntil = &v
	}
	if in.DeliveredAt != nil {
		v := *in.DeliveredAt
		cp.DeliveredAt = &v
	}
	return &cp
}

func (r *controlPlaneRepo) UpsertAgentInstance(_ context.Context, in *controlmodel.AgentInstance) (*controlmodel.AgentInstance, error) {
	if in == nil || in.AgentID == uuid.Nil || in.BindingID == uuid.Nil || in.InstanceKey == "" || in.BackendKind == "" {
		return nil, fmt.Errorf("agent instance requires agentId, bindingId, instanceKey, and backendKind")
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	now := time.Now().UTC()
	var current *controlmodel.AgentInstance
	for _, candidate := range r.s.agentInstances {
		if candidate.AgentID == in.AgentID && candidate.BindingID == in.BindingID && candidate.InstanceKey == in.InstanceKey {
			current = candidate
			break
		}
	}
	cp := cloneAgentInstance(in)
	if current != nil {
		cp.ID = current.ID
		cp.CreatedAt = current.CreatedAt
		cp.Generation = current.Generation + 1
	} else {
		if cp.ID == uuid.Nil {
			cp.ID = uuid.New()
		}
		cp.CreatedAt = now
		cp.Generation = 1
	}
	if cp.Health == "" {
		cp.Health = controlmodel.RuntimeHealthHealthy
	}
	if cp.LastSeenAt.IsZero() {
		cp.LastSeenAt = now
	}
	cp.UpdatedAt = now
	r.s.agentInstances[cp.ID] = cloneAgentInstance(cp)
	return cp, nil
}

func (r *controlPlaneRepo) GetAgentInstance(_ context.Context, id uuid.UUID) (*controlmodel.AgentInstance, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	in, ok := r.s.agentInstances[id]
	if !ok {
		return nil, store.ErrNotFound
	}
	return cloneAgentInstance(in), nil
}

func (r *controlPlaneRepo) ListAgentInstances(_ context.Context, tenant, namespace string, agentID uuid.UUID) ([]*controlmodel.AgentInstance, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*controlmodel.AgentInstance, 0)
	for _, in := range r.s.agentInstances {
		if tenant != "" && in.Tenant != tenant || namespace != "" && in.Namespace != namespace || agentID != uuid.Nil && in.AgentID != agentID {
			continue
		}
		out = append(out, cloneAgentInstance(in))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UpdatedAt.After(out[j].UpdatedAt) })
	return out, nil
}

func (r *controlPlaneRepo) HeartbeatAgentInstance(_ context.Context, id uuid.UUID, generation int64, activeSessions int32, capabilities json.RawMessage) (*controlmodel.AgentInstance, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	in, ok := r.s.agentInstances[id]
	if !ok {
		return nil, store.ErrNotFound
	}
	if generation > 0 && in.Generation != generation {
		return nil, store.ErrConflict
	}
	in.ActiveSessions = activeSessions
	if capabilities != nil {
		in.Capabilities = cloneJSON(capabilities)
	}
	in.Health = controlmodel.RuntimeHealthHealthy
	in.LastSeenAt = time.Now().UTC()
	in.UpdatedAt = in.LastSeenAt
	return cloneAgentInstance(in), nil
}

func (r *controlPlaneRepo) SetAgentInstanceHealth(_ context.Context, id uuid.UUID, generation int64, health string) (*controlmodel.AgentInstance, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	in, ok := r.s.agentInstances[id]
	if !ok {
		return nil, store.ErrNotFound
	}
	if generation > 0 && in.Generation != generation {
		return nil, store.ErrConflict
	}
	in.Health = health
	in.UpdatedAt = time.Now().UTC()
	return cloneAgentInstance(in), nil
}

func (r *controlPlaneRepo) MarkAgentInstancesOffline(_ context.Context, before time.Time) (int64, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	var count int64
	for _, in := range r.s.agentInstances {
		if in.LastSeenAt.Before(before) && in.Health != controlmodel.RuntimeHealthUnhealthy {
			in.Health = controlmodel.RuntimeHealthUnhealthy
			in.UpdatedAt = time.Now().UTC()
			count++
		}
	}
	return count, nil
}

func (r *controlPlaneRepo) UpsertRuntimeProfile(_ context.Context, in *controlmodel.RuntimeProfile) (*controlmodel.RuntimeProfile, error) {
	if in == nil || in.Name == "" || in.Provider == "" {
		return nil, fmt.Errorf("runtime profile requires name and provider")
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	key := registryKey(in.Tenant, in.Namespace, in.Name)
	now := time.Now().UTC()
	cp := cloneRuntimeProfile(in)
	if current, ok := r.s.runtimeProfiles[key]; ok {
		cp.ID = current.ID
		cp.CreatedAt = current.CreatedAt
		cp.Version = current.Version + 1
	} else {
		if cp.ID == uuid.Nil {
			cp.ID = uuid.New()
		}
		cp.CreatedAt = now
		cp.Version = 1
	}
	cp.UpdatedAt = now
	r.s.runtimeProfiles[key] = cloneRuntimeProfile(cp)
	return cp, nil
}

func (r *controlPlaneRepo) GetRuntimeProfile(_ context.Context, tenant, namespace, name string) (*controlmodel.RuntimeProfile, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	in, ok := r.s.runtimeProfiles[registryKey(tenant, namespace, name)]
	if !ok {
		return nil, store.ErrNotFound
	}
	return cloneRuntimeProfile(in), nil
}

func (r *controlPlaneRepo) GetRuntimeProfileByID(_ context.Context, id uuid.UUID) (*controlmodel.RuntimeProfile, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	for _, profile := range r.s.runtimeProfiles {
		if profile.ID == id {
			return cloneRuntimeProfile(profile), nil
		}
	}
	return nil, store.ErrNotFound
}

func (r *controlPlaneRepo) ListRuntimeProfiles(_ context.Context, tenant, namespace string) ([]*controlmodel.RuntimeProfile, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*controlmodel.RuntimeProfile, 0)
	for _, in := range r.s.runtimeProfiles {
		if tenant != "" && in.Tenant != tenant || namespace != "" && in.Namespace != namespace {
			continue
		}
		out = append(out, cloneRuntimeProfile(in))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out, nil
}

func (r *controlPlaneRepo) UpsertRuntimePool(_ context.Context, in *controlmodel.RuntimePool) (*controlmodel.RuntimePool, error) {
	if in == nil || in.Name == "" {
		return nil, fmt.Errorf("runtime pool requires name")
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	key := registryKey(in.Tenant, in.Namespace, in.Name)
	now := time.Now().UTC()
	cp := cloneRuntimePool(in)
	if current, ok := r.s.runtimePools[key]; ok {
		cp.ID = current.ID
		cp.CreatedAt = current.CreatedAt
		cp.Version = current.Version + 1
	} else {
		if cp.ID == uuid.Nil {
			cp.ID = uuid.New()
		}
		cp.CreatedAt = now
		cp.Version = 1
	}
	cp.UpdatedAt = now
	r.s.runtimePools[key] = cloneRuntimePool(cp)
	return cp, nil
}

func (r *controlPlaneRepo) GetRuntimePool(_ context.Context, tenant, namespace, name string) (*controlmodel.RuntimePool, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	in, ok := r.s.runtimePools[registryKey(tenant, namespace, name)]
	if !ok {
		return nil, store.ErrNotFound
	}
	return cloneRuntimePool(in), nil
}

func (r *controlPlaneRepo) GetRuntimePoolByID(_ context.Context, id uuid.UUID) (*controlmodel.RuntimePool, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	for _, pool := range r.s.runtimePools {
		if pool.ID == id {
			return cloneRuntimePool(pool), nil
		}
	}
	return nil, store.ErrNotFound
}

func (r *controlPlaneRepo) ListRuntimePools(_ context.Context, tenant, namespace string) ([]*controlmodel.RuntimePool, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*controlmodel.RuntimePool, 0)
	for _, in := range r.s.runtimePools {
		if tenant != "" && in.Tenant != tenant || namespace != "" && in.Namespace != namespace {
			continue
		}
		out = append(out, cloneRuntimePool(in))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out, nil
}

func (r *controlPlaneRepo) UpsertRuntimeHost(_ context.Context, in *controlmodel.RuntimeHost) (*controlmodel.RuntimeHost, error) {
	if in == nil || in.HostKey == "" || in.PoolName == "" {
		return nil, fmt.Errorf("runtime host requires hostKey and poolName")
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	now := time.Now().UTC()
	var current *controlmodel.RuntimeHost
	for _, candidate := range r.s.runtimeHosts {
		if candidate.Tenant == in.Tenant && candidate.Namespace == in.Namespace && candidate.HostKey == in.HostKey {
			current = candidate
			break
		}
	}
	cp := cloneRuntimeHost(in)
	cp.CapacityManaged = false
	if current != nil {
		cp.ID = current.ID
		cp.CreatedAt = current.CreatedAt
		cp.LeaseGeneration = current.LeaseGeneration + 1
		cp.CapacityManaged = current.CapacityManaged
		if current.CapacityManaged {
			cp.Capacity = current.Capacity
		}
	} else {
		if cp.ID == uuid.Nil {
			cp.ID = uuid.New()
		}
		cp.CreatedAt = now
		cp.LeaseGeneration = 1
	}
	if cp.State == "" {
		cp.State = controlmodel.RuntimeHostOnline
	}
	if cp.LastSeenAt.IsZero() {
		cp.LastSeenAt = now
	}
	cp.UpdatedAt = now
	r.s.runtimeHosts[cp.ID] = cloneRuntimeHost(cp)
	return cp, nil
}

func (r *controlPlaneRepo) GetRuntimeHost(_ context.Context, id uuid.UUID) (*controlmodel.RuntimeHost, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	host, ok := r.s.runtimeHosts[id]
	if !ok {
		return nil, store.ErrNotFound
	}
	return cloneRuntimeHost(host), nil
}

func (r *controlPlaneRepo) GetRuntimeHostByKey(_ context.Context, tenant, namespace, hostKey string) (*controlmodel.RuntimeHost, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	for _, host := range r.s.runtimeHosts {
		if host.Tenant == tenant && host.Namespace == namespace && host.HostKey == hostKey {
			return cloneRuntimeHost(host), nil
		}
	}
	return nil, store.ErrNotFound
}

func (r *controlPlaneRepo) ListRuntimeHosts(_ context.Context, tenant, namespace, poolName, state string) ([]*controlmodel.RuntimeHost, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*controlmodel.RuntimeHost, 0)
	for _, host := range r.s.runtimeHosts {
		if tenant != "" && host.Tenant != tenant || namespace != "" && host.Namespace != namespace || poolName != "" && host.PoolName != poolName || state != "" && host.State != state {
			continue
		}
		out = append(out, cloneRuntimeHost(host))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].LastSeenAt.After(out[j].LastSeenAt) })
	return out, nil
}

func (r *controlPlaneRepo) HeartbeatRuntimeHost(_ context.Context, id uuid.UUID, generation int64, active int32, capabilities json.RawMessage) (*controlmodel.RuntimeHost, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	host, ok := r.s.runtimeHosts[id]
	if !ok {
		return nil, store.ErrNotFound
	}
	if generation != host.LeaseGeneration {
		return nil, store.ErrConflict
	}
	host.Active = active
	if capabilities != nil {
		host.Capabilities = cloneJSON(capabilities)
	}
	if host.State == controlmodel.RuntimeHostOffline {
		host.State = controlmodel.RuntimeHostOnline
	}
	host.LastSeenAt = time.Now().UTC()
	host.UpdatedAt = host.LastSeenAt
	return cloneRuntimeHost(host), nil
}

func (r *controlPlaneRepo) SetRuntimeHostCapacity(_ context.Context, id uuid.UUID, expectedCapacity, capacity int32) (*controlmodel.RuntimeHost, error) {
	if capacity < 1 || capacity > controlmodel.MaxRuntimeHostCapacity {
		return nil, fmt.Errorf("capacity must be between 1 and %d", controlmodel.MaxRuntimeHostCapacity)
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	host, ok := r.s.runtimeHosts[id]
	if !ok {
		return nil, store.ErrNotFound
	}
	if host.Capacity != expectedCapacity {
		return nil, store.ErrConflict
	}
	host.Capacity, host.CapacityManaged = capacity, true
	host.UpdatedAt = time.Now().UTC()
	return cloneRuntimeHost(host), nil
}

func (r *controlPlaneRepo) SetRuntimeHostState(_ context.Context, id uuid.UUID, generation int64, state string) (*controlmodel.RuntimeHost, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	host, ok := r.s.runtimeHosts[id]
	if !ok {
		return nil, store.ErrNotFound
	}
	if generation != host.LeaseGeneration {
		return nil, store.ErrConflict
	}
	host.State = state
	host.UpdatedAt = time.Now().UTC()
	return cloneRuntimeHost(host), nil
}

func (r *controlPlaneRepo) MarkRuntimeHostsOffline(_ context.Context, before time.Time) (int64, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	var count int64
	for _, host := range r.s.runtimeHosts {
		if host.LastSeenAt.Before(before) && host.State != controlmodel.RuntimeHostOffline {
			host.State = controlmodel.RuntimeHostOffline
			host.UpdatedAt = time.Now().UTC()
			count++
		}
	}
	return count, nil
}

type executionRepo struct{ *controlPlaneRepo }

func (r *executionRepo) Create(_ context.Context, in *controlmodel.ExecutionAttempt) (*controlmodel.ExecutionAttempt, error) {
	if in == nil || in.AgentTaskID == uuid.Nil || in.BackendKind == "" {
		return nil, fmt.Errorf("execution attempt requires agentTaskId and backendKind")
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	task, ok := r.s.agentTasks[in.AgentTaskID]
	if !ok {
		return nil, store.ErrNotFound
	}
	if in.RunID == uuid.Nil {
		in.RunID = task.OrchestrationRunID
	}
	if in.NodeID == uuid.Nil {
		in.NodeID = task.RunNodeID
	}
	if task.OrchestrationRunID != in.RunID || task.RunNodeID != in.NodeID {
		return nil, store.ErrConflict
	}
	cp := cloneExecution(in)
	if cp.ID == uuid.Nil {
		cp.ID = uuid.New()
	}
	if _, exists := r.s.executions[cp.ID]; exists {
		return nil, store.ErrConflict
	}
	if cp.Attempt <= 0 {
		for _, candidate := range r.s.executions {
			if candidate.AgentTaskID == cp.AgentTaskID && candidate.Attempt >= cp.Attempt {
				cp.Attempt = candidate.Attempt + 1
			}
		}
		if cp.Attempt <= 0 {
			cp.Attempt = 1
		}
	}
	for _, candidate := range r.s.executions {
		if candidate.AgentTaskID == cp.AgentTaskID && candidate.Attempt == cp.Attempt {
			return nil, store.ErrConflict
		}
	}
	if cp.State == "" {
		cp.State = controlmodel.ExecutionQueued
	}
	if cp.DispatchGeneration <= 0 {
		cp.DispatchGeneration = 1
	}
	if len(cp.RuntimeBinding) == 0 {
		cp.RuntimeBinding = json.RawMessage(`{}`)
	}
	now := time.Now().UTC()
	cp.Version = 1
	cp.CreatedAt = now
	cp.UpdatedAt = now
	r.s.executions[cp.ID] = cloneExecution(cp)
	task.CurrentAttemptID = &cp.ID
	task.Version++
	return cp, nil
}

func (r *executionRepo) Get(_ context.Context, id uuid.UUID) (*controlmodel.ExecutionAttempt, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	execution, ok := r.s.executions[id]
	if !ok {
		return nil, store.ErrNotFound
	}
	return cloneExecution(execution), nil
}

func (r *executionRepo) List(ctx context.Context, filter store.ExecutionAttemptFilter) ([]*controlmodel.ExecutionAttempt, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*controlmodel.ExecutionAttempt, 0)
	for _, execution := range r.s.executions {
		if store.WorkAccessFrom(ctx).Restricted {
			task := r.s.agentTasks[execution.AgentTaskID]
			if task == nil || !r.s.canReadIssueLocked(ctx, task.IssueID) {
				continue
			}
		}
		if filter.AgentTaskID != uuid.Nil && execution.AgentTaskID != filter.AgentTaskID ||
			filter.AgentID != uuid.Nil && execution.AgentID != filter.AgentID ||
			filter.BindingID != uuid.Nil && execution.BindingID != filter.BindingID ||
			filter.Tenant != "" && execution.Tenant != filter.Tenant ||
			filter.Namespace != "" && execution.Namespace != filter.Namespace ||
			filter.SessionID != "" && execution.SessionID != filter.SessionID ||
			filter.RuntimePoolName != "" && execution.RuntimePoolName != filter.RuntimePoolName ||
			filter.HostID != uuid.Nil && (execution.HostID == nil || *execution.HostID != filter.HostID) ||
			filter.State != "" && execution.State != filter.State {
			continue
		}
		out = append(out, cloneExecution(execution))
	}
	sort.Slice(out, func(i, j int) bool {
		if filter.NewestFirst {
			return out[i].CreatedAt.After(out[j].CreatedAt)
		}
		return out[i].CreatedAt.Before(out[j].CreatedAt)
	})
	limit := filter.Limit
	if limit <= 0 {
		limit = 100
	}
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

func (r *executionRepo) Claim(_ context.Context, claim store.ExecutionClaim) (*controlmodel.ExecutionAttempt, error) {
	if claim.HostID == uuid.Nil || claim.LeaseOwner == "" || claim.LeaseToken == "" || claim.LeaseTTL <= 0 {
		return nil, fmt.Errorf("execution claim requires hostId, leaseOwner, leaseToken, and positive leaseTTL")
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	host, ok := r.s.runtimeHosts[claim.HostID]
	if !ok {
		return nil, store.ErrNotFound
	}
	if host.State != controlmodel.RuntimeHostOnline || host.Active >= host.Capacity && host.Capacity > 0 {
		return nil, store.ErrConflict
	}
	if claim.HostGeneration != host.LeaseGeneration {
		return nil, store.ErrConflict
	}
	var candidates []*controlmodel.ExecutionAttempt
	for _, execution := range r.s.executions {
		if execution.State != controlmodel.ExecutionQueued || execution.BackendKind != controlmodel.DataPlaneHostedRuntime {
			continue
		}
		if claim.Tenant != "" && execution.Tenant != claim.Tenant || claim.Namespace != "" && execution.Namespace != claim.Namespace || claim.RuntimePoolName != "" && execution.RuntimePoolName != claim.RuntimePoolName || execution.RuntimePoolName != host.PoolName {
			continue
		}
		if !memoryCapabilitiesMatch(host.Capabilities, execution.RequiredCapabilities) {
			continue
		}
		var snapshot controlmodel.RuntimeDispatchSnapshot
		if len(execution.RuntimeBinding) > 0 && json.Unmarshal(execution.RuntimeBinding, &snapshot) != nil {
			continue
		}
		var dispatchPolicy struct {
			PreferredHostID uuid.UUID `json:"preferredHostId"`
		}
		_ = json.Unmarshal(snapshot.Policy, &dispatchPolicy)
		if dispatchPolicy.PreferredHostID != uuid.Nil && dispatchPolicy.PreferredHostID != claim.HostID {
			continue
		}
		if snapshot.RuntimeProfile != nil && !controlmodel.RuntimeHostMatchesProfile(host.Capabilities, snapshot.RuntimeProfile) {
			continue
		}
		if snapshot.RuntimePool != nil && !controlmodel.RuntimeHostMatchesPool(host.Labels, snapshot.RuntimePool) {
			continue
		}
		if !controlmodel.RuntimeSecurityMatches(controlmodel.DataPlaneHostedRuntime, host.Labels, snapshot.SecurityConstraints) {
			continue
		}
		candidates = append(candidates, execution)
	}
	if len(candidates) == 0 {
		return nil, store.ErrNotFound
	}
	sort.Slice(candidates, func(i, j int) bool {
		left, right := r.s.agentTasks[candidates[i].AgentTaskID], r.s.agentTasks[candidates[j].AgentTaskID]
		if left != nil && right != nil && left.Priority != right.Priority {
			return left.Priority > right.Priority
		}
		return candidates[i].CreatedAt.Before(candidates[j].CreatedAt)
	})
	execution := candidates[0]
	now := time.Now().UTC()
	expires := now.Add(claim.LeaseTTL)
	hostID := claim.HostID
	execution.HostID = &hostID
	execution.LeaseOwner = claim.LeaseOwner
	execution.LeaseToken = claim.LeaseToken
	r.s.nextFencing++
	execution.FencingToken = r.s.nextFencing
	execution.LeaseExpiresAt = &expires
	execution.State = controlmodel.ExecutionAssigned
	execution.Version++
	execution.UpdatedAt = now
	return cloneExecution(execution), nil
}

func memoryCapabilitiesMatch(actual, required json.RawMessage) bool {
	return controlmodel.JSONContains(actual, required)
}

func (r *executionRepo) mutateLeased(id uuid.UUID, leaseToken string, fencingToken int64, to controlmodel.ExecutionAttemptState, mutate func(*controlmodel.ExecutionAttempt, time.Time)) (*controlmodel.ExecutionAttempt, error) {
	execution, ok := r.s.executions[id]
	if !ok {
		return nil, store.ErrNotFound
	}
	if execution.LeaseToken != leaseToken || execution.FencingToken != fencingToken || execution.LeaseExpiresAt == nil || execution.LeaseExpiresAt.Before(time.Now().UTC()) || !controlmodel.CanTransitionExecutionAttempt(execution.State, to) {
		return nil, store.ErrConflict
	}
	now := time.Now().UTC()
	execution.State = to
	execution.Version++
	execution.UpdatedAt = now
	if mutate != nil {
		mutate(execution, now)
	}
	return cloneExecution(execution), nil
}

func (r *executionRepo) RenewLease(_ context.Context, id uuid.UUID, leaseToken string, fencingToken int64, ttl time.Duration) (*controlmodel.ExecutionAttempt, error) {
	if ttl <= 0 {
		return nil, fmt.Errorf("lease ttl must be positive")
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	execution, ok := r.s.executions[id]
	if !ok {
		return nil, store.ErrNotFound
	}
	if execution.LeaseToken != leaseToken || execution.FencingToken != fencingToken || controlmodel.IsExecutionAttemptTerminal(execution.State) {
		return nil, store.ErrConflict
	}
	if execution.State == controlmodel.ExecutionCancelRequested {
		return cloneExecution(execution), nil
	}
	now := time.Now().UTC()
	expires := now.Add(ttl)
	execution.LeaseExpiresAt = &expires
	execution.HeartbeatAt = &now
	execution.Version++
	execution.UpdatedAt = now
	return cloneExecution(execution), nil
}

func (r *executionRepo) MarkPreparing(_ context.Context, id uuid.UUID, leaseToken string, fencingToken int64) (*controlmodel.ExecutionAttempt, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	return r.mutateLeased(id, leaseToken, fencingToken, controlmodel.ExecutionPreparing, nil)
}

func (r *executionRepo) MarkRunning(_ context.Context, id uuid.UUID, leaseToken string, fencingToken int64, providerSessionID, workspaceKey string) (*controlmodel.ExecutionAttempt, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	return r.mutateLeased(id, leaseToken, fencingToken, controlmodel.ExecutionRunning, func(execution *controlmodel.ExecutionAttempt, now time.Time) {
		execution.ProviderSessionID = providerSessionID
		execution.WorkspaceKey = workspaceKey
		if execution.StartedAt == nil {
			execution.StartedAt = &now
		}
	})
}

func (r *executionRepo) Checkpoint(_ context.Context, id uuid.UUID, leaseToken string, fencingToken int64, providerSessionID string, checkpoint json.RawMessage) (*controlmodel.ExecutionAttempt, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	execution, ok := r.s.executions[id]
	if !ok {
		return nil, store.ErrNotFound
	}
	if execution.LeaseToken != leaseToken || execution.FencingToken != fencingToken || execution.LeaseExpiresAt == nil || execution.LeaseExpiresAt.Before(time.Now().UTC()) || execution.State != controlmodel.ExecutionRunning {
		return nil, store.ErrConflict
	}
	if providerSessionID != "" {
		execution.ProviderSessionID = providerSessionID
	}
	if checkpoint != nil {
		execution.Checkpoint = cloneJSON(checkpoint)
	}
	execution.Version++
	execution.UpdatedAt = time.Now().UTC()
	return cloneExecution(execution), nil
}

func (r *executionRepo) Complete(_ context.Context, id uuid.UUID, leaseToken string, fencingToken int64, result, checkpoint json.RawMessage) (*controlmodel.ExecutionAttempt, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	return r.mutateLeased(id, leaseToken, fencingToken, controlmodel.ExecutionSucceeded, func(execution *controlmodel.ExecutionAttempt, now time.Time) {
		execution.Result = cloneJSON(result)
		execution.Checkpoint = cloneJSON(checkpoint)
		execution.CompletedAt = &now
		execution.LeaseExpiresAt = nil
	})
}

func (r *executionRepo) Fail(_ context.Context, id uuid.UUID, leaseToken string, fencingToken int64, failureCode, failureMessage string, checkpoint json.RawMessage) (*controlmodel.ExecutionAttempt, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	return r.mutateLeased(id, leaseToken, fencingToken, controlmodel.ExecutionFailed, func(execution *controlmodel.ExecutionAttempt, now time.Time) {
		execution.FailureCode = failureCode
		execution.FailureMessage = failureMessage
		execution.Checkpoint = cloneJSON(checkpoint)
		execution.CompletedAt = &now
		execution.LeaseExpiresAt = nil
	})
}

func (r *executionRepo) Cancel(_ context.Context, id uuid.UUID, expectedVersion int64) (*controlmodel.ExecutionAttempt, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	execution, ok := r.s.executions[id]
	if !ok {
		return nil, store.ErrNotFound
	}
	if expectedVersion > 0 && execution.Version != expectedVersion || controlmodel.IsExecutionAttemptTerminal(execution.State) {
		return nil, store.ErrConflict
	}
	now := time.Now().UTC()
	if execution.State == controlmodel.ExecutionQueued {
		execution.State, execution.CompletedAt, execution.LeaseExpiresAt = controlmodel.ExecutionCancelled, &now, nil
	} else if controlmodel.CanTransitionExecutionAttempt(execution.State, controlmodel.ExecutionCancelRequested) {
		execution.State, execution.CancelRequestedAt = controlmodel.ExecutionCancelRequested, &now
	} else {
		return nil, store.ErrConflict
	}
	execution.Version++
	execution.UpdatedAt = now
	return cloneExecution(execution), nil
}

func (r *executionRepo) ConfirmCancelled(_ context.Context, id uuid.UUID, leaseToken string, fencingToken int64) (*controlmodel.ExecutionAttempt, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	execution := r.s.executions[id]
	if execution == nil {
		return nil, store.ErrNotFound
	}
	if execution.State == controlmodel.ExecutionCancelled {
		return cloneExecution(execution), nil
	}
	if execution.State != controlmodel.ExecutionCancelRequested || execution.LeaseToken != leaseToken || execution.FencingToken != fencingToken {
		return nil, store.ErrConflict
	}
	now := time.Now().UTC()
	execution.State, execution.Version, execution.UpdatedAt = controlmodel.ExecutionCancelled, execution.Version+1, now
	execution.CompletedAt, execution.LeaseExpiresAt = &now, nil
	return cloneExecution(execution), nil
}

func (r *executionRepo) ForceCancelled(_ context.Context, id uuid.UUID, expectedVersion int64) (*controlmodel.ExecutionAttempt, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	attempt := r.s.executions[id]
	if attempt == nil {
		return nil, store.ErrNotFound
	}
	if attempt.State == controlmodel.ExecutionCancelled {
		return cloneExecution(attempt), nil
	}
	if expectedVersion > 0 && attempt.Version != expectedVersion || attempt.State != controlmodel.ExecutionCancelRequested {
		return nil, store.ErrConflict
	}
	now := time.Now().UTC()
	attempt.State, attempt.LeaseExpiresAt, attempt.CompletedAt = controlmodel.ExecutionCancelled, nil, &now
	attempt.Version, attempt.UpdatedAt = attempt.Version+1, now
	return cloneExecution(attempt), nil
}

func (r *executionRepo) Report(_ context.Context, report store.ExecutionAttemptReport) (*controlmodel.ExecutionAttempt, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	attempt := r.s.executions[report.AttemptID]
	if attempt == nil {
		return nil, store.ErrNotFound
	}
	if attempt.DispatchGeneration != report.DispatchGeneration || attempt.BackendKind != report.BackendKind ||
		report.BackendKind == controlmodel.DataPlaneExternalApplication && (attempt.AgentInstanceID == nil || *attempt.AgentInstanceID != report.AgentInstanceID) {
		return nil, store.ErrConflict
	}
	if controlmodel.IsExecutionAttemptTerminal(attempt.State) {
		if attempt.State == report.State {
			return cloneExecution(attempt), nil
		}
		return nil, store.ErrConflict
	}
	if report.State != "" && !controlmodel.CanTransitionExecutionAttempt(attempt.State, report.State) {
		return nil, store.ErrConflict
	}
	now := time.Now().UTC()
	if report.State != "" {
		attempt.State = report.State
	}
	attempt.HeartbeatAt, attempt.UpdatedAt = &now, now
	attempt.Version++
	if report.Checkpoint != nil {
		attempt.Checkpoint = cloneJSON(report.Checkpoint)
	}
	if report.Result != nil {
		attempt.Result = cloneJSON(report.Result)
	}
	if report.Usage != nil {
		attempt.Usage = cloneJSON(report.Usage)
	}
	attempt.FailureCode, attempt.FailureMessage = report.FailureCode, report.FailureMessage
	if report.State == controlmodel.ExecutionRunning && attempt.StartedAt == nil {
		attempt.StartedAt = &now
	}
	if controlmodel.IsExecutionAttemptTerminal(report.State) {
		attempt.CompletedAt = &now
	}
	return cloneExecution(attempt), nil
}

type outboxRepo struct{ *controlPlaneRepo }

func (r *outboxRepo) Enqueue(_ context.Context, in *controlmodel.OutboxEvent) (*controlmodel.OutboxEvent, error) {
	if in == nil || in.AggregateType == "" || in.AggregateID == "" || in.EventType == "" {
		return nil, fmt.Errorf("outbox event requires aggregateType, aggregateId, and eventType")
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if in.DedupeKey != "" {
		for _, existing := range r.s.outboxEvents {
			if existing.Tenant == in.Tenant && existing.DedupeKey == in.DedupeKey {
				return cloneOutboxEvent(existing), nil
			}
		}
	}
	cp := cloneOutboxEvent(in)
	if cp.ID == uuid.Nil {
		cp.ID = uuid.New()
	}
	now := time.Now().UTC()
	if cp.AvailableAt.IsZero() {
		cp.AvailableAt = now
	}
	if cp.OccurredAt.IsZero() {
		cp.OccurredAt = now
	}
	if cp.SchemaVersion <= 0 {
		cp.SchemaVersion = 1
	}
	if cp.Actor.Type == "" {
		cp.Actor.Type = controlmodel.ActorSystem
	}
	cp.CreatedAt = now
	r.s.outboxEvents[cp.ID] = cloneOutboxEvent(cp)
	return cp, nil
}

func (r *outboxRepo) Claim(_ context.Context, worker string, now time.Time, ttl time.Duration, limit int) ([]*controlmodel.OutboxEvent, error) {
	if worker == "" || ttl <= 0 {
		return nil, fmt.Errorf("outbox claim requires worker and positive ttl")
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if limit <= 0 {
		limit = 100
	}
	var candidates []*controlmodel.OutboxEvent
	for _, event := range r.s.outboxEvents {
		if event.DeliveredAt != nil || event.DeadLetteredAt != nil || event.AvailableAt.After(now) || event.ClaimedUntil != nil && event.ClaimedUntil.After(now) {
			continue
		}
		candidates = append(candidates, event)
	}
	sort.Slice(candidates, func(i, j int) bool { return candidates[i].CreatedAt.Before(candidates[j].CreatedAt) })
	if len(candidates) > limit {
		candidates = candidates[:limit]
	}
	until := now.Add(ttl)
	out := make([]*controlmodel.OutboxEvent, 0, len(candidates))
	for _, event := range candidates {
		event.ClaimedBy = worker
		event.ClaimedUntil = &until
		event.Attempts++
		out = append(out, cloneOutboxEvent(event))
	}
	return out, nil
}

func (r *outboxRepo) MarkDelivered(_ context.Context, id uuid.UUID, worker string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	event, ok := r.s.outboxEvents[id]
	if !ok {
		return store.ErrNotFound
	}
	if event.ClaimedBy != worker {
		return store.ErrConflict
	}
	now := time.Now().UTC()
	event.DeliveredAt = &now
	event.ClaimedUntil = nil
	return nil
}

func (r *outboxRepo) MarkFailed(_ context.Context, id uuid.UUID, worker, lastError string, retryAt time.Time) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	event, ok := r.s.outboxEvents[id]
	if !ok {
		return store.ErrNotFound
	}
	if event.ClaimedBy != worker {
		return store.ErrConflict
	}
	event.LastError = lastError
	if event.Attempts >= 12 {
		now := time.Now().UTC()
		event.DeadLetteredAt = &now
		recipient := "admin"
		if event.AggregateType == "agent-task" {
			if taskID, err := uuid.Parse(event.AggregateID); err == nil {
				if task := r.s.agentTasks[taskID]; task != nil && task.AccountableHumanRef != "" {
					recipient = task.AccountableHumanRef
				}
			}
		} else if event.Actor.Type == controlmodel.ActorHuman && event.Actor.Ref != "" {
			recipient = event.Actor.Ref
		}
		item := &controlmodel.InboxItem{ID: uuid.New(), Tenant: event.Tenant, Namespace: event.Namespace,
			RecipientType: controlmodel.AssigneeHuman, RecipientRef: recipient, Type: "outbox_dead_letter",
			Severity: "error", Actor: controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "outbox"},
			Title: "Delivery requires attention", Body: event.EventType + ": " + lastError,
			DedupeKey: "outbox-dead-letter:" + event.ID.String(), CreatedAt: now}
		r.s.inboxItems[item.ID] = item
	}
	event.AvailableAt = retryAt
	event.ClaimedBy = ""
	event.ClaimedUntil = nil
	return nil
}

func (r *outboxRepo) MarkDeferred(_ context.Context, id uuid.UUID, worker, lastError string, retryAt time.Time) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	event, ok := r.s.outboxEvents[id]
	if !ok {
		return store.ErrNotFound
	}
	if event.ClaimedBy != worker || event.DeliveredAt != nil || event.DeadLetteredAt != nil {
		return store.ErrConflict
	}
	event.LastError, event.AvailableAt = lastError, retryAt
	event.ClaimedBy, event.ClaimedUntil = "", nil
	return nil
}

func (r *outboxRepo) ListDeadLetters(_ context.Context, tenant, namespace string, limit int) ([]*controlmodel.OutboxEvent, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*controlmodel.OutboxEvent, 0)
	for _, event := range r.s.outboxEvents {
		if event.DeadLetteredAt == nil || tenant != "" && event.Tenant != tenant || namespace != "" && event.Namespace != namespace {
			continue
		}
		out = append(out, cloneOutboxEvent(event))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].DeadLetteredAt.After(*out[j].DeadLetteredAt) })
	if limit > 0 && len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

func (r *outboxRepo) ReplayDeadLetter(_ context.Context, id uuid.UUID) (*controlmodel.OutboxEvent, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	event := r.s.outboxEvents[id]
	if event == nil {
		return nil, store.ErrNotFound
	}
	if event.DeadLetteredAt == nil || event.DeliveredAt != nil {
		return nil, store.ErrConflict
	}
	event.DeadLetteredAt, event.ClaimedUntil = nil, nil
	event.ClaimedBy, event.LastError, event.Attempts = "", "", 0
	event.AvailableAt = time.Now().UTC()
	return cloneOutboxEvent(event), nil
}

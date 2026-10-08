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

package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

type AgentFilter struct {
	ExcludedIDs       []uuid.UUID
	Offset            int
	Tenant, Namespace string
	Status            controlmodel.AgentStatus
	IncludeArchived   bool
	Limit             int
}

type ExternalAgentRegistration struct {
	Tenant, Namespace, AgentKey string
	DisplayName, Description    string
	OwnerType, OwnerRef         string
	InstanceKey                 string
	Framework, FrameworkVersion string
	SDKVersion, RoutingKey      string
	Capabilities, Labels        json.RawMessage
	Capacity                    int32
	TrustedBootstrap            bool
	ClaimCredentialHash         []byte
	NewCredentialHash           []byte
	CredentialExpiresAt         *time.Time
}

type ExternalAgentRegistrationResult struct {
	Agent             *controlmodel.Agent
	Binding           *controlmodel.AgentBinding
	Instance          *controlmodel.AgentInstance
	Credential        *controlmodel.AgentRegistrationCredential
	CredentialCreated bool
}

// AgentInstanceClaim is the authenticated v5 identity carried by an ASDP
// stream. A trusted workload identity may omit a registration credential, but
// all stable catalog and generation fields are still required.
type AgentInstanceClaim struct {
	Tenant, Namespace       string
	AgentID, BindingID      uuid.UUID
	AgentKey, InstanceKey   string
	Generation              int64
	CredentialHash          []byte
	TrustedWorkloadIdentity bool
}

// AgentCatalogRepository is the runtime-store authority for logical Agent
// identity, runtime bindings, and registration credentials.
type AgentCatalogRepository interface {
	CreateAgent(context.Context, *controlmodel.Agent) (*controlmodel.Agent, error)
	GetAgent(context.Context, uuid.UUID) (*controlmodel.Agent, error)
	GetAgentByKey(context.Context, string, string, string) (*controlmodel.Agent, error)
	ListAgents(context.Context, AgentFilter) ([]*controlmodel.Agent, error)
	UpdateAgent(context.Context, *controlmodel.Agent, int64) (*controlmodel.Agent, error)

	CreateBinding(context.Context, *controlmodel.AgentBinding) (*controlmodel.AgentBinding, error)
	GetBinding(context.Context, uuid.UUID) (*controlmodel.AgentBinding, error)
	ListBindings(context.Context, uuid.UUID, bool) ([]*controlmodel.AgentBinding, error)
	UpdateBinding(context.Context, *controlmodel.AgentBinding, int64) (*controlmodel.AgentBinding, error)

	RegisterExternal(context.Context, ExternalAgentRegistration) (*ExternalAgentRegistrationResult, error)
	RotateRegistrationCredential(context.Context, uuid.UUID, []byte, *time.Time) (*controlmodel.AgentRegistrationCredential, error)
	RevokeRegistrationCredential(context.Context, uuid.UUID, uuid.UUID) error
	ValidateInstanceClaim(context.Context, AgentInstanceClaim) (*controlmodel.AgentInstance, error)
}

// RuntimeRegistryRepository persists observed Agent applications, reusable
// runtime configuration, and user-operated Runtime Hosts.
type RuntimeRegistryRepository interface {
	UpsertAgentInstance(ctx context.Context, instance *controlmodel.AgentInstance) (*controlmodel.AgentInstance, error)
	GetAgentInstance(ctx context.Context, id uuid.UUID) (*controlmodel.AgentInstance, error)
	ListAgentInstances(ctx context.Context, tenant, namespace string, agentID uuid.UUID) ([]*controlmodel.AgentInstance, error)
	HeartbeatAgentInstance(ctx context.Context, id uuid.UUID, generation int64, activeSessions int32, capabilities json.RawMessage) (*controlmodel.AgentInstance, error)
	SetAgentInstanceHealth(ctx context.Context, id uuid.UUID, generation int64, health string) (*controlmodel.AgentInstance, error)
	MarkAgentInstancesOffline(ctx context.Context, before time.Time) (int64, error)

	UpsertRuntimeProfile(ctx context.Context, profile *controlmodel.RuntimeProfile) (*controlmodel.RuntimeProfile, error)
	GetRuntimeProfile(ctx context.Context, tenant, namespace, name string) (*controlmodel.RuntimeProfile, error)
	GetRuntimeProfileByID(ctx context.Context, id uuid.UUID) (*controlmodel.RuntimeProfile, error)
	ListRuntimeProfiles(ctx context.Context, tenant, namespace string) ([]*controlmodel.RuntimeProfile, error)

	UpsertRuntimePool(ctx context.Context, pool *controlmodel.RuntimePool) (*controlmodel.RuntimePool, error)
	GetRuntimePool(ctx context.Context, tenant, namespace, name string) (*controlmodel.RuntimePool, error)
	GetRuntimePoolByID(ctx context.Context, id uuid.UUID) (*controlmodel.RuntimePool, error)
	ListRuntimePools(ctx context.Context, tenant, namespace string) ([]*controlmodel.RuntimePool, error)

	UpsertRuntimeHost(ctx context.Context, host *controlmodel.RuntimeHost) (*controlmodel.RuntimeHost, error)
	GetRuntimeHost(ctx context.Context, id uuid.UUID) (*controlmodel.RuntimeHost, error)
	GetRuntimeHostByKey(ctx context.Context, tenant, namespace, hostKey string) (*controlmodel.RuntimeHost, error)
	ListRuntimeHosts(ctx context.Context, tenant, namespace, poolName, state string) ([]*controlmodel.RuntimeHost, error)
	HeartbeatRuntimeHost(ctx context.Context, id uuid.UUID, generation int64, active int32, capabilities json.RawMessage) (*controlmodel.RuntimeHost, error)
	SetRuntimeHostCapacity(ctx context.Context, id uuid.UUID, expectedCapacity, capacity int32) (*controlmodel.RuntimeHost, error)
	SetRuntimeHostState(ctx context.Context, id uuid.UUID, generation int64, state string) (*controlmodel.RuntimeHost, error)
	MarkRuntimeHostsOffline(ctx context.Context, before time.Time) (int64, error)
}

// ExecutionAttemptFilter limits physical attempt queries.
type ExecutionAttemptFilter struct {
	AgentTaskID     uuid.UUID
	AgentID         uuid.UUID
	BindingID       uuid.UUID
	Tenant          string
	Namespace       string
	SessionID       string
	RuntimePoolName string
	HostID          uuid.UUID
	State           controlmodel.ExecutionAttemptState
	NewestFirst     bool
	Limit           int
}

// ExecutionClaim asks the store to atomically claim the oldest compatible
// queued execution. Capability matching is performed by the scheduler before
// claim; pool/profile constraints are enforced again by the store.
type ExecutionClaim struct {
	Tenant          string
	Namespace       string
	RuntimePoolName string
	HostID          uuid.UUID
	HostGeneration  int64
	LeaseOwner      string
	LeaseToken      string
	LeaseTTL        time.Duration
}

type ExecutionAttemptReport struct {
	AttemptID, AgentInstanceID  uuid.UUID
	DispatchGeneration          int64
	BackendKind                 controlmodel.DataPlaneKind
	State                       controlmodel.ExecutionAttemptState
	Checkpoint, Result, Usage   json.RawMessage
	FailureCode, FailureMessage string
}

// ExecutionAttemptRepository manages physical execution attempts and leases.
type ExecutionAttemptRepository interface {
	Create(ctx context.Context, execution *controlmodel.ExecutionAttempt) (*controlmodel.ExecutionAttempt, error)
	Get(ctx context.Context, id uuid.UUID) (*controlmodel.ExecutionAttempt, error)
	List(ctx context.Context, filter ExecutionAttemptFilter) ([]*controlmodel.ExecutionAttempt, error)
	Claim(ctx context.Context, claim ExecutionClaim) (*controlmodel.ExecutionAttempt, error)
	RenewLease(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64, ttl time.Duration) (*controlmodel.ExecutionAttempt, error)
	MarkPreparing(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64) (*controlmodel.ExecutionAttempt, error)
	MarkRunning(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64, providerSessionID, workspaceKey string) (*controlmodel.ExecutionAttempt, error)
	Checkpoint(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64, providerSessionID string, checkpoint json.RawMessage) (*controlmodel.ExecutionAttempt, error)
	Complete(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64, result, checkpoint json.RawMessage) (*controlmodel.ExecutionAttempt, error)
	Fail(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64, failureCode, failureMessage string, checkpoint json.RawMessage) (*controlmodel.ExecutionAttempt, error)
	Cancel(ctx context.Context, id uuid.UUID, expectedVersion int64) (*controlmodel.ExecutionAttempt, error)
	ConfirmCancelled(ctx context.Context, id uuid.UUID, leaseToken string, fencingToken int64) (*controlmodel.ExecutionAttempt, error)
	ForceCancelled(ctx context.Context, id uuid.UUID, expectedVersion int64) (*controlmodel.ExecutionAttempt, error)
	Report(ctx context.Context, report ExecutionAttemptReport) (*controlmodel.ExecutionAttempt, error)
}

// OutboxRepository manages durable at-least-once domain event delivery.
type OutboxRepository interface {
	Enqueue(ctx context.Context, event *controlmodel.OutboxEvent) (*controlmodel.OutboxEvent, error)
	Claim(ctx context.Context, worker string, now time.Time, ttl time.Duration, limit int) ([]*controlmodel.OutboxEvent, error)
	MarkDelivered(ctx context.Context, id uuid.UUID, worker string) error
	MarkFailed(ctx context.Context, id uuid.UUID, worker, lastError string, retryAt time.Time) error
	MarkDeferred(ctx context.Context, id uuid.UUID, worker, lastError string, retryAt time.Time) error
	ListDeadLetters(ctx context.Context, tenant, namespace string, limit int) ([]*controlmodel.OutboxEvent, error)
	ReplayDeadLetter(ctx context.Context, id uuid.UUID) (*controlmodel.OutboxEvent, error)
}

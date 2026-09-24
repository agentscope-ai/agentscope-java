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

// Package model contains the stable control-plane resource vocabulary.
//
// It deliberately has no dependencies on transport, Kubernetes, or storage so
// every data-plane adapter can share these identities without sharing an
// implementation.
package model

import (
	"bytes"
	"encoding/json"
	"fmt"
	"reflect"
	"time"

	"github.com/google/uuid"
)

// JSONContains reports whether actual contains every recursively specified
// key/value in required. It is shared by capability and security admission so
// memory and PostgreSQL stores enforce the same JSON containment semantics.
func JSONContains(actual, required json.RawMessage) bool {
	if len(required) == 0 || string(required) == "{}" || string(required) == "null" {
		return true
	}
	decode := func(raw json.RawMessage) (map[string]any, bool) {
		decoder := json.NewDecoder(bytes.NewReader(raw))
		decoder.UseNumber()
		var value map[string]any
		if decoder.Decode(&value) != nil || value == nil {
			return nil, false
		}
		return value, true
	}
	have, ok := decode(actual)
	if !ok {
		return false
	}
	want, ok := decode(required)
	return ok && jsonMapContains(have, want)
}

func jsonMapContains(actual, required map[string]any) bool {
	for key, wanted := range required {
		have, ok := actual[key]
		if !ok {
			return false
		}
		if nested, ok := wanted.(map[string]any); ok {
			actualNested, nestedOK := have.(map[string]any)
			if !nestedOK || !jsonMapContains(actualNested, nested) {
				return false
			}
			continue
		}
		if !reflect.DeepEqual(have, wanted) {
			return false
		}
	}
	return true
}

// RuntimeSecurityMatches treats securityConstraints as required target labels.
// backendKind is injected as a virtual immutable label for all three runtimes.
func RuntimeSecurityMatches(kind DataPlaneKind, labels, constraints json.RawMessage) bool {
	actual := map[string]any{"backendKind": string(kind)}
	if len(labels) > 0 && string(labels) != "null" {
		var target map[string]any
		if json.Unmarshal(labels, &target) != nil {
			return false
		}
		for key, value := range target {
			actual[key] = value
		}
	}
	raw, _ := json.Marshal(actual)
	return JSONContains(raw, constraints)
}

func ValidateJSONObject(raw json.RawMessage, field string) error {
	if len(raw) == 0 || string(raw) == "null" {
		return nil
	}
	var value map[string]any
	if json.Unmarshal(raw, &value) != nil || value == nil {
		return fmt.Errorf("%s must be a JSON object", field)
	}
	return nil
}

// DataPlaneKind identifies who owns an Agent runtime lifecycle.
type DataPlaneKind string

const (
	DataPlaneManaged             DataPlaneKind = "managed"
	DataPlaneExternalApplication DataPlaneKind = "external-application"
	DataPlaneHostedRuntime       DataPlaneKind = "hosted-runtime"
)

// RuntimeBinding chooses how an AgentDefinition is materialized.
type RuntimeBinding struct {
	AgentID              uuid.UUID                 `json:"agentId"`
	BindingID            uuid.UUID                 `json:"bindingId"`
	Kind                 DataPlaneKind             `json:"kind"`
	ManagedOwnerRef      string                    `json:"managedOwnerRef,omitempty"`
	ManagedDefinitionRef string                    `json:"managedDefinitionRef,omitempty"`
	InstanceSelector     map[string]string         `json:"instanceSelector,omitempty"`
	RuntimeProfileID     uuid.UUID                 `json:"runtimeProfileId,omitempty"`
	RuntimePoolID        uuid.UUID                 `json:"runtimePoolId,omitempty"`
	ExecutionOverrides   *HostedExecutionOverrides `json:"executionOverrides,omitempty"`
}

// RuntimeDispatchSnapshot freezes the selected backend for one AgentTask
// dispatch. Registry or Team configuration changes never mutate this record.
type RuntimeDispatchSnapshot struct {
	Definition                    json.RawMessage           `json:"definition,omitempty"`
	Binding                       RuntimeBinding            `json:"binding"`
	RuntimeProfile                *RuntimeProfile           `json:"runtimeProfile,omitempty"`
	RuntimePool                   *RuntimePool              `json:"runtimePool,omitempty"`
	ExecutionOverrides            *HostedExecutionOverrides `json:"executionOverrides,omitempty"`
	ResolvedProviderConfiguration json.RawMessage           `json:"resolvedProviderConfiguration,omitempty"`
	SelectionSource               string                    `json:"selectionSource,omitempty"`
	CandidateIndex                int32                     `json:"candidateIndex,omitempty"`
	AgentInstanceID               *uuid.UUID                `json:"agentInstanceId,omitempty"`
	SessionID                     string                    `json:"sessionId,omitempty"`
	Capabilities                  json.RawMessage           `json:"capabilities,omitempty"`
	SecurityConstraints           json.RawMessage           `json:"securityConstraints,omitempty"`
	Policy                        json.RawMessage           `json:"policy,omitempty"`
	ResolvedAt                    time.Time                 `json:"resolvedAt"`
}

// RuntimeHostMatchesProfile verifies both the provider installation advertised
// by a Host and the immutable capability requirements captured at dispatch.
func RuntimeHostMatchesProfile(capabilities json.RawMessage, profile *RuntimeProfile) bool {
	if profile == nil || profile.Provider == "" {
		return false
	}
	if !JSONContains(capabilities, profile.Requirements) {
		return false
	}
	var advertised struct {
		Providers map[string]any `json:"providers"`
	}
	if json.Unmarshal(capabilities, &advertised) != nil {
		return false
	}
	_, ok := advertised.Providers[profile.Provider]
	return ok
}

// RuntimeHostMatchesPool applies the immutable Host selector captured with the
// pool. Pool membership by name alone is not sufficient admission.
func RuntimeHostMatchesPool(labels json.RawMessage, pool *RuntimePool) bool {
	return pool != nil && JSONContains(labels, pool.HostSelector)
}

// Validate checks that a binding contains only the reference required by its kind.
func (b RuntimeBinding) Validate() error {
	if b.AgentID == uuid.Nil || b.BindingID == uuid.Nil {
		return fmt.Errorf("runtime binding requires agentId and bindingId")
	}
	switch b.Kind {
	case DataPlaneManaged:
		if b.ManagedOwnerRef == "" || b.ManagedDefinitionRef == "" {
			return fmt.Errorf("managed runtime binding requires managedOwnerRef and managedDefinitionRef")
		}
	case DataPlaneExternalApplication:
		// An empty selector means any healthy instance of this binding.
	case DataPlaneHostedRuntime:
		if b.RuntimeProfileID == uuid.Nil || b.RuntimePoolID == uuid.Nil {
			return fmt.Errorf("hosted runtime binding requires runtimeProfileId and runtimePoolId")
		}
		if err := b.ExecutionOverrides.Validate(); err != nil {
			return err
		}
	default:
		return fmt.Errorf("unsupported runtime binding kind %q", b.Kind)
	}
	return nil
}

// Runtime health and lifecycle states.
const (
	RuntimeHealthUnknown   = "unknown"
	RuntimeHealthHealthy   = "healthy"
	RuntimeHealthUnhealthy = "unhealthy"

	RuntimeHostOnline      = "online"
	RuntimeHostDraining    = "draining"
	RuntimeHostOffline     = "offline"
	RuntimeHostQuarantined = "quarantined"
)

// AgentInstance is one observed external or managed Agent application process.
type AgentInstance struct {
	ID               uuid.UUID       `json:"id"`
	Tenant           string          `json:"tenant"`
	Namespace        string          `json:"namespace"`
	AgentID          uuid.UUID       `json:"agentId"`
	BindingID        uuid.UUID       `json:"bindingId"`
	BackendKind      DataPlaneKind   `json:"backendKind"`
	InstanceKey      string          `json:"instanceKey"`
	Framework        string          `json:"framework,omitempty"`
	FrameworkVersion string          `json:"frameworkVersion,omitempty"`
	SDKVersion       string          `json:"sdkVersion,omitempty"`
	Capabilities     json.RawMessage `json:"capabilities,omitempty"`
	Labels           json.RawMessage `json:"labels,omitempty"`
	RoutingKey       string          `json:"routingKey,omitempty"`
	Health           string          `json:"health"`
	Capacity         int32           `json:"capacity"`
	ActiveSessions   int32           `json:"activeSessions"`
	LastSeenAt       time.Time       `json:"lastSeenAt"`
	Generation       int64           `json:"generation"`
	CreatedAt        time.Time       `json:"createdAt"`
	UpdatedAt        time.Time       `json:"updatedAt"`
}

// RuntimeProfile is a reusable provider configuration advertised to Hosts.
type RuntimeProfile struct {
	ID            uuid.UUID       `json:"id"`
	Tenant        string          `json:"tenant"`
	Namespace     string          `json:"namespace"`
	Name          string          `json:"name"`
	Provider      string          `json:"provider"`
	Runtime       string          `json:"runtime"`
	Configuration json.RawMessage `json:"configuration,omitempty"`
	Requirements  json.RawMessage `json:"requirements,omitempty"`
	Version       int64           `json:"version"`
	CreatedAt     time.Time       `json:"createdAt"`
	UpdatedAt     time.Time       `json:"updatedAt"`
}

// RuntimePool groups compatible Runtime Hosts for scheduling and policy.
type RuntimePool struct {
	ID            uuid.UUID       `json:"id"`
	Tenant        string          `json:"tenant"`
	Namespace     string          `json:"namespace"`
	Name          string          `json:"name"`
	HostSelector  json.RawMessage `json:"hostSelector,omitempty"`
	Configuration json.RawMessage `json:"configuration,omitempty"`
	Version       int64           `json:"version"`
	CreatedAt     time.Time       `json:"createdAt"`
	UpdatedAt     time.Time       `json:"updatedAt"`
}

// RuntimeHost is a user-operated execution node. It manages provider
// processes and workspaces, not arbitrary application deployments.
// MaxRuntimeHostCapacity bounds capacity configured through the console.
const MaxRuntimeHostCapacity int32 = 50

type RuntimeHost struct {
	ID              uuid.UUID       `json:"id"`
	Tenant          string          `json:"tenant"`
	Namespace       string          `json:"namespace"`
	HostKey         string          `json:"hostKey"`
	PoolName        string          `json:"poolName"`
	DaemonVersion   string          `json:"daemonVersion,omitempty"`
	OS              string          `json:"os,omitempty"`
	Arch            string          `json:"arch,omitempty"`
	Labels          json.RawMessage `json:"labels,omitempty"`
	Capabilities    json.RawMessage `json:"capabilities,omitempty"`
	State           string          `json:"state"`
	Capacity        int32           `json:"capacity"`
	CapacityManaged bool            `json:"capacityManaged"`
	Active          int32           `json:"active"`
	LastSeenAt      time.Time       `json:"lastSeenAt"`
	LeaseGeneration int64           `json:"leaseGeneration"`
	CreatedAt       time.Time       `json:"createdAt"`
	UpdatedAt       time.Time       `json:"updatedAt"`
}

// ExecutionAttempt states describe one physical attempt.
type ExecutionAttemptState string

const (
	ExecutionQueued          ExecutionAttemptState = "queued"
	ExecutionAssigned        ExecutionAttemptState = "assigned"
	ExecutionPreparing       ExecutionAttemptState = "preparing"
	ExecutionRunning         ExecutionAttemptState = "running"
	ExecutionWaiting         ExecutionAttemptState = "waiting"
	ExecutionCancelRequested ExecutionAttemptState = "cancel_requested"
	ExecutionSucceeded       ExecutionAttemptState = "succeeded"
	ExecutionFailed          ExecutionAttemptState = "failed"
	ExecutionCancelled       ExecutionAttemptState = "cancelled"
)

func IsExecutionAttemptTerminal(state ExecutionAttemptState) bool {
	return state == ExecutionSucceeded || state == ExecutionFailed || state == ExecutionCancelled
}

// CanTransitionExecutionAttempt centralizes the attempt state machine.
func CanTransitionExecutionAttempt(from, to ExecutionAttemptState) bool {
	if from == to {
		return true
	}
	switch from {
	case ExecutionQueued:
		return to == ExecutionAssigned || to == ExecutionCancelRequested || to == ExecutionCancelled || to == ExecutionFailed
	case ExecutionAssigned:
		return to == ExecutionPreparing || to == ExecutionCancelRequested || to == ExecutionFailed
	case ExecutionPreparing:
		return to == ExecutionRunning || to == ExecutionWaiting || to == ExecutionCancelRequested || to == ExecutionFailed
	case ExecutionRunning:
		return to == ExecutionWaiting || to == ExecutionSucceeded || to == ExecutionFailed || to == ExecutionCancelRequested
	case ExecutionWaiting:
		return to == ExecutionPreparing || to == ExecutionRunning || to == ExecutionSucceeded || to == ExecutionFailed || to == ExecutionCancelRequested
	case ExecutionCancelRequested:
		return to == ExecutionCancelled || to == ExecutionFailed
	default:
		return false
	}
}

// ExecutionAttempt is one immutable attempt identity plus mutable lease/state.
// SessionRef is a read-time control-plane Session primary key for API/UI
// diagnostics; unlike SessionID, it is not persisted with the attempt.
type ExecutionAttempt struct {
	ID                   uuid.UUID             `json:"id"`
	AgentTaskID          uuid.UUID             `json:"agentTaskId"`
	AgentID              uuid.UUID             `json:"agentId"`
	BindingID            uuid.UUID             `json:"bindingId"`
	RunID                uuid.UUID             `json:"runId"`
	NodeID               uuid.UUID             `json:"nodeId"`
	Tenant               string                `json:"tenant"`
	Namespace            string                `json:"namespace"`
	Attempt              int32                 `json:"attempt"`
	DispatchGeneration   int64                 `json:"dispatchGeneration"`
	BackendKind          DataPlaneKind         `json:"backendKind"`
	RuntimeBinding       json.RawMessage       `json:"runtimeBinding"`
	RuntimeProfileName   string                `json:"runtimeProfileName,omitempty"`
	RuntimePoolName      string                `json:"runtimePoolName,omitempty"`
	RequiredCapabilities json.RawMessage       `json:"requiredCapabilities,omitempty"`
	HostID               *uuid.UUID            `json:"hostId,omitempty"`
	AgentInstanceID      *uuid.UUID            `json:"agentInstanceId,omitempty"`
	ManagedOwnerRef      string                `json:"managedOwnerRef,omitempty"`
	ManagedAgentRef      string                `json:"managedAgentRef,omitempty"`
	SessionID            string                `json:"sessionId,omitempty"`
	SessionRef           *uuid.UUID            `json:"sessionRef,omitempty"`
	TurnID               string                `json:"turnId,omitempty"`
	ProviderSessionID    string                `json:"providerSessionId,omitempty"`
	WorkspaceKey         string                `json:"workspaceKey,omitempty"`
	State                ExecutionAttemptState `json:"state"`
	LeaseOwner           string                `json:"leaseOwner,omitempty"`
	LeaseToken           string                `json:"leaseToken,omitempty"`
	FencingToken         int64                 `json:"fencingToken"`
	LeaseExpiresAt       *time.Time            `json:"leaseExpiresAt,omitempty"`
	HeartbeatAt          *time.Time            `json:"heartbeatAt,omitempty"`
	CancelRequestedAt    *time.Time            `json:"cancelRequestedAt,omitempty"`
	Checkpoint           json.RawMessage       `json:"checkpoint,omitempty"`
	Result               json.RawMessage       `json:"result,omitempty"`
	FailureCode          string                `json:"failureCode,omitempty"`
	FailureMessage       string                `json:"failureMessage,omitempty"`
	Usage                json.RawMessage       `json:"usage,omitempty"`
	Version              int64                 `json:"version"`
	CreatedAt            time.Time             `json:"createdAt"`
	UpdatedAt            time.Time             `json:"updatedAt"`
	StartedAt            *time.Time            `json:"startedAt,omitempty"`
	CompletedAt          *time.Time            `json:"completedAt,omitempty"`
}

// OutboxEvent is a durable notification. Delivery is at least once; consumers
// must deduplicate by ID or DedupeKey.
type OutboxEvent struct {
	ID             uuid.UUID       `json:"id"`
	Tenant         string          `json:"tenant"`
	Namespace      string          `json:"namespace"`
	AggregateType  string          `json:"aggregateType"`
	AggregateID    string          `json:"aggregateId"`
	EventType      string          `json:"eventType"`
	SchemaVersion  int32           `json:"schemaVersion"`
	Actor          Actor           `json:"actor"`
	CausationID    string          `json:"causationId,omitempty"`
	CorrelationID  string          `json:"correlationId,omitempty"`
	OccurredAt     time.Time       `json:"occurredAt"`
	Payload        json.RawMessage `json:"payload"`
	DedupeKey      string          `json:"dedupeKey,omitempty"`
	Attempts       int32           `json:"attempts"`
	AvailableAt    time.Time       `json:"availableAt"`
	ClaimedBy      string          `json:"claimedBy,omitempty"`
	ClaimedUntil   *time.Time      `json:"claimedUntil,omitempty"`
	DeliveredAt    *time.Time      `json:"deliveredAt,omitempty"`
	DeadLetteredAt *time.Time      `json:"deadLetteredAt,omitempty"`
	LastError      string          `json:"lastError,omitempty"`
	CreatedAt      time.Time       `json:"createdAt"`
}

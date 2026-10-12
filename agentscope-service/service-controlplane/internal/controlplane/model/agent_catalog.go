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

package model

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
)

// AgentStatus is the administrator-owned lifecycle of one logical Agent. Runtime
// health is deliberately kept on AgentInstance and never mutates this value.
type AgentStatus string

const (
	AgentProvisioning       AgentStatus = "provisioning"
	AgentActive             AgentStatus = "active"
	AgentDisabled           AgentStatus = "disabled"
	AgentProvisioningFailed AgentStatus = "provisioning_failed"
	AgentArchived           AgentStatus = "archived"
)

// Agent is the stable logical identity used by work assignment, teams,
// orchestration definitions, comments, sessions, and execution attempts.
type Agent struct {
	ID           uuid.UUID       `json:"id"`
	Tenant       string          `json:"tenant"`
	Namespace    string          `json:"namespace"`
	AgentKey     string          `json:"agentKey"`
	DisplayName  string          `json:"displayName"`
	Description  string          `json:"description,omitempty"`
	OwnerType    string          `json:"ownerType,omitempty"`
	OwnerRef     string          `json:"ownerRef,omitempty"`
	Status       AgentStatus     `json:"status"`
	Capabilities json.RawMessage `json:"capabilities,omitempty"`
	Labels       json.RawMessage `json:"labels,omitempty"`
	Metadata     json.RawMessage `json:"metadata,omitempty"`
	Version      int64           `json:"version"`
	CreatedAt    time.Time       `json:"createdAt"`
	UpdatedAt    time.Time       `json:"updatedAt"`
	ArchivedAt   *time.Time      `json:"archivedAt,omitempty"`
}

// AgentBinding is a selectable runtime attachment for a logical Agent.
// Configuration is discriminated by Kind and validated before persistence.
type AgentBinding struct {
	ID            uuid.UUID       `json:"id"`
	AgentID       uuid.UUID       `json:"agentId"`
	Tenant        string          `json:"tenant"`
	Namespace     string          `json:"namespace"`
	Kind          DataPlaneKind   `json:"kind"`
	Configuration json.RawMessage `json:"configuration"`
	Priority      int32           `json:"priority"`
	Enabled       bool            `json:"enabled"`
	Version       int64           `json:"version"`
	CreatedAt     time.Time       `json:"createdAt"`
	UpdatedAt     time.Time       `json:"updatedAt"`
	ArchivedAt    *time.Time      `json:"archivedAt,omitempty"`
}

type ManagedBindingConfiguration struct {
	OwnerRef             string `json:"ownerRef"`
	ManagedDefinitionRef string `json:"managedDefinitionRef"`
}

type ExternalBindingConfiguration struct {
	InstanceSelector map[string]string `json:"instanceSelector,omitempty"`
}

type HostedBindingConfiguration struct {
	RuntimeProfileID   uuid.UUID                 `json:"runtimeProfileId"`
	RuntimePoolID      uuid.UUID                 `json:"runtimePoolId"`
	ExecutionOverrides *HostedExecutionOverrides `json:"executionOverrides,omitempty"`
}

// HostedExecutionOverrides contains per-Agent execution preferences layered on
// top of a reusable RuntimeProfile. Keeping these values on the binding avoids
// mutating a shared profile when one Agent changes its native CLI behavior.
type HostedExecutionOverrides struct {
	ReasoningEffort       string          `json:"reasoningEffort,omitempty"`
	ServiceTier           string          `json:"serviceTier,omitempty"`
	ProviderConfiguration json.RawMessage `json:"providerConfiguration,omitempty"`
	CustomArgs            []string        `json:"customArgs,omitempty"`
}

func (o *HostedExecutionOverrides) Validate() error {
	if o == nil {
		return nil
	}
	if len(o.ProviderConfiguration) > 0 && string(o.ProviderConfiguration) != "null" {
		var value map[string]any
		if err := json.Unmarshal(o.ProviderConfiguration, &value); err != nil || value == nil {
			return fmt.Errorf("providerConfiguration must be a JSON object")
		}
	}
	if len(o.CustomArgs) > 64 {
		return fmt.Errorf("customArgs cannot contain more than 64 arguments")
	}
	for _, arg := range o.CustomArgs {
		if strings.TrimSpace(arg) == "" || len(arg) > 1024 || strings.ContainsRune(arg, '\x00') {
			return fmt.Errorf("customArgs contains an invalid argument")
		}
	}
	return nil
}

// ResolveProviderConfiguration applies per-Agent provider values without
// changing the reusable profile. The result is frozen on each dispatch.
func (o *HostedExecutionOverrides) ResolveProviderConfiguration(base json.RawMessage) (json.RawMessage, error) {
	resolved := map[string]any{}
	if len(base) > 0 && string(base) != "null" {
		if err := json.Unmarshal(base, &resolved); err != nil || resolved == nil {
			return nil, fmt.Errorf("runtime profile configuration must be a JSON object")
		}
	}
	if o != nil && len(o.ProviderConfiguration) > 0 && string(o.ProviderConfiguration) != "null" {
		var overrides map[string]any
		if err := json.Unmarshal(o.ProviderConfiguration, &overrides); err != nil || overrides == nil {
			return nil, fmt.Errorf("providerConfiguration must be a JSON object")
		}
		for key, value := range overrides {
			resolved[key] = value
		}
	}
	if o != nil && o.ReasoningEffort != "" {
		resolved["reasoningEffort"] = o.ReasoningEffort
	}
	if o != nil && o.ServiceTier != "" {
		resolved["serviceTier"] = o.ServiceTier
	}
	return json.Marshal(resolved)
}

func (b AgentBinding) Validate() error {
	if b.AgentID == uuid.Nil {
		return fmt.Errorf("agentId is required")
	}
	switch b.Kind {
	case DataPlaneManaged:
		var cfg ManagedBindingConfiguration
		if json.Unmarshal(b.Configuration, &cfg) != nil || cfg.OwnerRef == "" || cfg.ManagedDefinitionRef == "" {
			return fmt.Errorf("managed binding requires ownerRef and managedDefinitionRef")
		}
	case DataPlaneExternalApplication:
		var cfg ExternalBindingConfiguration
		if len(b.Configuration) > 0 && json.Unmarshal(b.Configuration, &cfg) != nil {
			return fmt.Errorf("external binding configuration is invalid")
		}
	case DataPlaneHostedRuntime:
		var cfg HostedBindingConfiguration
		if json.Unmarshal(b.Configuration, &cfg) != nil || cfg.RuntimeProfileID == uuid.Nil || cfg.RuntimePoolID == uuid.Nil {
			return fmt.Errorf("hosted binding requires runtimeProfileId and runtimePoolId")
		}
		if err := cfg.ExecutionOverrides.Validate(); err != nil {
			return err
		}
	default:
		return fmt.Errorf("unsupported binding kind %q", b.Kind)
	}
	return nil
}

// RuntimeBinding materializes the immutable dispatch form of this catalog
// binding. Policies store binding IDs; dispatch snapshots store this result.
func (b AgentBinding) RuntimeBinding() (RuntimeBinding, error) {
	if err := b.Validate(); err != nil {
		return RuntimeBinding{}, err
	}
	result := RuntimeBinding{AgentID: b.AgentID, BindingID: b.ID, Kind: b.Kind}
	switch b.Kind {
	case DataPlaneManaged:
		var cfg ManagedBindingConfiguration
		_ = json.Unmarshal(b.Configuration, &cfg)
		result.ManagedOwnerRef = cfg.OwnerRef
		result.ManagedDefinitionRef = cfg.ManagedDefinitionRef
	case DataPlaneExternalApplication:
		var cfg ExternalBindingConfiguration
		_ = json.Unmarshal(b.Configuration, &cfg)
		result.InstanceSelector = cfg.InstanceSelector
	case DataPlaneHostedRuntime:
		var cfg HostedBindingConfiguration
		_ = json.Unmarshal(b.Configuration, &cfg)
		result.RuntimeProfileID, result.RuntimePoolID = cfg.RuntimeProfileID, cfg.RuntimePoolID
		result.ExecutionOverrides = cfg.ExecutionOverrides
	}
	return result, result.Validate()
}

type RegistrationCredentialStatus string

const (
	RegistrationCredentialActive  RegistrationCredentialStatus = "active"
	RegistrationCredentialRevoked RegistrationCredentialStatus = "revoked"
	RegistrationCredentialExpired RegistrationCredentialStatus = "expired"
)

// AgentRegistrationCredential exposes credential metadata only. TokenHash is
// a storage field and is never serialized or returned by an API.
type AgentRegistrationCredential struct {
	ID        uuid.UUID                    `json:"id"`
	AgentID   uuid.UUID                    `json:"agentId"`
	TokenHash []byte                       `json:"-"`
	Status    RegistrationCredentialStatus `json:"status"`
	ExpiresAt *time.Time                   `json:"expiresAt,omitempty"`
	RotatedAt *time.Time                   `json:"rotatedAt,omitempty"`
	CreatedAt time.Time                    `json:"createdAt"`
	UpdatedAt time.Time                    `json:"updatedAt"`
}

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
	"time"

	"github.com/google/uuid"
)

type EndpointTargetType string

const (
	EndpointTargetAgent                 EndpointTargetType = "agent"
	EndpointTargetTeam                  EndpointTargetType = "team"
	EndpointTargetOrchestrationRevision EndpointTargetType = "orchestration_revision"
)

type EndpointInvocationMode string

const (
	EndpointConversationMode EndpointInvocationMode = "conversation"
	EndpointJobMode          EndpointInvocationMode = "job"
)

type EndpointStatus string

const (
	EndpointDraft     EndpointStatus = "draft"
	EndpointPublished EndpointStatus = "published"
	EndpointDisabled  EndpointStatus = "disabled"
	EndpointArchived  EndpointStatus = "archived"
)

// Endpoint is the stable public API contract for one logical AgentScope
// target. Runtime bindings and physical instances are deliberately resolved at
// invocation time and never become part of the public address.
type Endpoint struct {
	ID                 uuid.UUID              `json:"id"`
	Tenant             string                 `json:"tenant"`
	Namespace          string                 `json:"namespace"`
	Name               string                 `json:"name"`
	Slug               string                 `json:"slug"`
	Description        string                 `json:"description,omitempty"`
	TargetType         EndpointTargetType     `json:"targetType"`
	TargetRef          uuid.UUID              `json:"targetRef"`
	InvocationMode     EndpointInvocationMode `json:"invocationMode"`
	InputSchema        json.RawMessage        `json:"inputSchema,omitempty"`
	OutputSchema       json.RawMessage        `json:"outputSchema,omitempty"`
	EventSchemaVersion string                 `json:"eventSchemaVersion"`
	TimeoutSeconds     int                    `json:"timeoutSeconds"`
	MaxPayloadBytes    int64                  `json:"maxPayloadBytes"`
	AuthPolicy         json.RawMessage        `json:"authPolicy"`
	RateLimit          json.RawMessage        `json:"rateLimit,omitempty"`
	ActiveReleaseID    *uuid.UUID             `json:"activeReleaseId,omitempty"`
	ActiveRelease      int                    `json:"activeRelease,omitempty"`
	Status             EndpointStatus         `json:"status"`
	Version            int64                  `json:"version"`
	CreatedAt          time.Time              `json:"createdAt"`
	UpdatedAt          time.Time              `json:"updatedAt"`
	ArchivedAt         *time.Time             `json:"archivedAt,omitempty"`
}

// EndpointRelease is an immutable deployment record behind a stable public
// Endpoint. Deploying or rolling back always appends a release, which keeps
// the public slug and contract stable while preserving target history.
type EndpointRelease struct {
	ID          uuid.UUID          `json:"id"`
	EndpointID  uuid.UUID          `json:"endpointId"`
	Number      int                `json:"number"`
	TargetType  EndpointTargetType `json:"targetType"`
	TargetRef   uuid.UUID          `json:"targetRef"`
	CreatedBy   Actor              `json:"createdBy"`
	Reason      string             `json:"reason,omitempty"`
	CreatedAt   time.Time          `json:"createdAt"`
	ActivatedAt time.Time          `json:"activatedAt"`
}

type EndpointCredentialStatus string

const (
	EndpointCredentialActive  EndpointCredentialStatus = "active"
	EndpointCredentialRevoked EndpointCredentialStatus = "revoked"
	EndpointCredentialExpired EndpointCredentialStatus = "expired"
)

type EndpointCredential struct {
	ID         uuid.UUID `json:"id"`
	EndpointID uuid.UUID `json:"endpointId"`
	Name       string    `json:"name"`
	KeyPrefix  string    `json:"keyPrefix"`
	SecretHash []byte    `json:"-"`
	// SecretCiphertext is AES-GCM encrypted at rest and is never serialized in
	// normal credential responses. It exists solely for authorized reveal.
	SecretCiphertext []byte                   `json:"-"`
	Recoverable      bool                     `json:"recoverable"`
	Status           EndpointCredentialStatus `json:"status"`
	Scopes           json.RawMessage          `json:"scopes,omitempty"`
	ExpiresAt        *time.Time               `json:"expiresAt,omitempty"`
	LastUsedAt       *time.Time               `json:"lastUsedAt,omitempty"`
	RotatedFrom      *uuid.UUID               `json:"rotatedFrom,omitempty"`
	CreatedAt        time.Time                `json:"createdAt"`
	RevokedAt        *time.Time               `json:"revokedAt,omitempty"`
}

type EndpointInvocationStatus string

const (
	EndpointInvocationAccepted    EndpointInvocationStatus = "accepted"
	EndpointInvocationDispatching EndpointInvocationStatus = "dispatching"
	EndpointInvocationRunning     EndpointInvocationStatus = "running"
	EndpointInvocationWaiting     EndpointInvocationStatus = "waiting"
	EndpointInvocationCompleted   EndpointInvocationStatus = "completed"
	EndpointInvocationFailed      EndpointInvocationStatus = "failed"
	EndpointInvocationCancelled   EndpointInvocationStatus = "cancelled"
	EndpointInvocationTimedOut    EndpointInvocationStatus = "timed_out"
)

// EndpointInvocation is the public execution identity. It prevents callers
// from having to treat Issue IDs, Run IDs, or runtime Session IDs as public Job
// identifiers.
type EndpointInvocation struct {
	ID             uuid.UUID                `json:"id"`
	EndpointID     uuid.UUID                `json:"endpointId"`
	Mode           EndpointInvocationMode   `json:"mode"`
	PrincipalType  string                   `json:"principalType"`
	PrincipalRef   string                   `json:"principalRef"`
	IdempotencyKey string                   `json:"idempotencyKey,omitempty"`
	Status         EndpointInvocationStatus `json:"status"`
	ConversationID *uuid.UUID               `json:"conversationId,omitempty"`
	TurnID         *uuid.UUID               `json:"turnId,omitempty"`
	SessionID      string                   `json:"sessionId,omitempty"`
	IssueID        *uuid.UUID               `json:"issueId,omitempty"`
	RunID          *uuid.UUID               `json:"runId,omitempty"`
	Input          json.RawMessage          `json:"input,omitempty"`
	Result         json.RawMessage          `json:"result,omitempty"`
	ErrorCode      string                   `json:"errorCode,omitempty"`
	ErrorMessage   string                   `json:"errorMessage,omitempty"`
	CorrelationID  string                   `json:"correlationId"`
	CreatedAt      time.Time                `json:"createdAt"`
	StartedAt      *time.Time               `json:"startedAt,omitempty"`
	CompletedAt    *time.Time               `json:"completedAt,omitempty"`
	UpdatedAt      time.Time                `json:"updatedAt"`
}

type EndpointConversationStatus string

const (
	EndpointConversationActive      EndpointConversationStatus = "active"
	EndpointConversationUnavailable EndpointConversationStatus = "unavailable"
	EndpointConversationClosed      EndpointConversationStatus = "closed"
)

type EndpointConversation struct {
	ID                 uuid.UUID                  `json:"id"`
	EndpointID         uuid.UUID                  `json:"endpointId"`
	AgentID            uuid.UUID                  `json:"agentId"`
	SessionID          string                     `json:"sessionId"`
	BindingID          uuid.UUID                  `json:"bindingId"`
	AgentInstanceID    uuid.UUID                  `json:"agentInstanceId,omitempty"`
	InstanceGeneration int64                      `json:"instanceGeneration,omitempty"`
	ExternalSessionRef string                     `json:"externalSessionRef,omitempty"`
	Status             EndpointConversationStatus `json:"status"`
	PrincipalRef       string                     `json:"principalRef"`
	LastTurnAt         *time.Time                 `json:"lastTurnAt,omitempty"`
	CreatedAt          time.Time                  `json:"createdAt"`
	UpdatedAt          time.Time                  `json:"updatedAt"`
}

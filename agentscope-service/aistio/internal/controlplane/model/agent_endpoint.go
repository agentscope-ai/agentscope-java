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
	EndpointConversation EndpointInvocationMode = "conversation"
	EndpointJobMode      EndpointInvocationMode = "job"
)

type AgentEndpoint struct {
	ID               uuid.UUID              `json:"id"`
	Tenant           string                 `json:"tenant"`
	Namespace        string                 `json:"namespace"`
	Name             string                 `json:"name"`
	Slug             string                 `json:"slug"`
	TargetType       EndpointTargetType     `json:"targetType"`
	TargetRef        uuid.UUID              `json:"targetRef"`
	InvocationMode   EndpointInvocationMode `json:"invocationMode"`
	AuthPolicy       json.RawMessage        `json:"authPolicy"`
	RateLimit        json.RawMessage        `json:"rateLimit,omitempty"`
	RuntimePolicyRef *uuid.UUID             `json:"runtimePolicyRef,omitempty"`
	CredentialHash   []byte                 `json:"-"`
	Enabled          bool                   `json:"enabled"`
	Version          int64                  `json:"version"`
	CreatedAt        time.Time              `json:"createdAt"`
	UpdatedAt        time.Time              `json:"updatedAt"`
}

type EndpointJob struct {
	ID             uuid.UUID
	EndpointID     uuid.UUID
	IdempotencyKey string
	IssueID        *uuid.UUID
	RunID          *uuid.UUID
	CreatedAt      time.Time
	UpdatedAt      time.Time
}

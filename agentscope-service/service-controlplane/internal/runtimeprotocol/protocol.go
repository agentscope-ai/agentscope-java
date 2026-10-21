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
package runtimeprotocol

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"unicode/utf16"
)

const Version = 1
const MaxMessageBytes = 2 << 20

type Context struct {
	Version             int            `json:"version"`
	Tenant              string         `json:"tenant"`
	Namespace           string         `json:"namespace"`
	ApplicationID       *string        `json:"applicationId"`
	SessionID           string         `json:"sessionId"`
	TurnID              *string        `json:"turnId"`
	CommandID           string         `json:"commandId"`
	TraceID             string         `json:"traceId"`
	Actor               map[string]any `json:"actor"`
	PolicyVersion       *string        `json:"policyVersion"`
	PermissionExpiresAt int64          `json:"permissionExpiresAt"`
}

func (c Context) Validate() error {
	if c.Version != Version {
		return fmt.Errorf("unsupported runtime protocol version: %d", c.Version)
	}
	for field, value := range map[string]string{
		"tenant": c.Tenant, "namespace": c.Namespace, "sessionId": c.SessionID,
		"commandId": c.CommandID, "traceId": c.TraceID,
	} {
		if strings.TrimSpace(value) == "" || len(utf16.Encode([]rune(value))) > 256 {
			return fmt.Errorf("%s must contain 1..256 characters", field)
		}
	}
	return nil
}

type RuntimeSessionBootstrap struct {
	Context               Context         `json:"context"`
	RuntimeKind           string          `json:"runtimeKind"`
	ConfigurationRevision string          `json:"configurationRevision"`
	Configuration         json.RawMessage `json:"configuration"`
}

type RuntimeSessionAdmission struct {
	Bootstrap  RuntimeSessionBootstrap `json:"bootstrap"`
	Policy     map[string]any          `json:"policy"`
	PublicView map[string]any          `json:"publicView"`
}

type RuntimeSessionLifecycle struct {
	Context           Context `json:"context"`
	LifecycleRevision int64   `json:"lifecycleRevision"`
	Lifecycle         string  `json:"lifecycle"`
}
type RuntimeLifecycleReceipt struct {
	Version           int    `json:"version"`
	SessionID         string `json:"sessionId"`
	CommandID         string `json:"commandId"`
	LifecycleRevision int64  `json:"lifecycleRevision"`
	Lifecycle         string `json:"lifecycle"`
	PayloadDigest     string `json:"payloadDigest"`
	Status            string `json:"status"`
}

type RuntimeSessionReady struct {
	Version               int    `json:"version"`
	SessionID             string `json:"sessionId"`
	CommandID             string `json:"commandId"`
	ConfigurationRevision string `json:"configurationRevision"`
	ConfigurationDigest   string `json:"configurationDigest"`
}

type TurnCommand struct {
	Context           Context        `json:"context"`
	IdempotencyKey    string         `json:"idempotencyKey"`
	InputDigest       string         `json:"inputDigest"`
	AdmissionSequence int64          `json:"admissionSequence"`
	Input             map[string]any `json:"input"`
}

type RuntimeCommandReceipt struct {
	Version           int    `json:"version"`
	SessionID         string `json:"sessionId"`
	TurnID            string `json:"turnId"`
	CommandID         string `json:"commandId"`
	InputDigest       string `json:"inputDigest"`
	AdmissionSequence int64  `json:"admissionSequence"`
	Status            string `json:"status"`
}

type RuntimeExecutionRequest struct {
	ActivationCommandID string  `json:"activationCommandId"`
	Context             Context `json:"context"`
	InputDigest         string  `json:"inputDigest"`
	AdmissionSequence   int64   `json:"admissionSequence"`
	ExecutionGeneration int64   `json:"executionGeneration"`
	ExecutionFence      string  `json:"executionFence"`
	Renewal             bool    `json:"renewal"`
}

type RuntimeExecutionGrant struct {
	Context              Context                      `json:"context"`
	ExecutionGeneration  int64                        `json:"executionGeneration"`
	ExecutionFence       string                       `json:"executionFence"`
	ValidUntil           int64                        `json:"validUntil"`
	ConfigurationOverlay *RuntimeConfigurationOverlay `json:"configurationOverlay,omitempty"`
	BudgetSettings       *RuntimeBudgetSettings       `json:"budgetSettings,omitempty"`
}

type RuntimeBudgetSettings struct {
	Revision int64          `json:"revision"`
	Limits   map[string]any `json:"limits"`
}

type RuntimeConfigurationOverlay struct {
	ScopeID       string         `json:"scopeId"`
	Revision      int64          `json:"revision"`
	Configuration map[string]any `json:"configuration"`
}

type InteractionCommand struct {
	Context Context        `json:"context"`
	Kind    string         `json:"kind"`
	Payload map[string]any `json:"payload"`
}

type RuntimeInteractionReceipt struct {
	Version       int     `json:"version"`
	SessionID     string  `json:"sessionId"`
	TurnID        *string `json:"turnId"`
	CommandID     string  `json:"commandId"`
	Kind          string  `json:"kind"`
	PayloadDigest string  `json:"payloadDigest"`
	Status        string  `json:"status"`
	InputStatus   *string `json:"inputStatus"`
}

type RuntimeFact struct {
	Context        Context        `json:"context"`
	Source         string         `json:"source"`
	EventID        string         `json:"eventId"`
	SourceSequence int64          `json:"sourceSequence"`
	ExecutionID    *string        `json:"executionId"`
	ExecutionFence *string        `json:"executionFence"`
	Type           string         `json:"type"`
	Payload        map[string]any `json:"payload"`
}

type RuntimeFactReceipt struct {
	Version        int    `json:"version"`
	SessionID      string `json:"sessionId"`
	Source         string `json:"source"`
	EventID        string `json:"eventId"`
	SourceSequence int64  `json:"sourceSequence"`
	PublicSequence int64  `json:"publicSequence"`
}

type ToolTask struct {
	Context                 Context        `json:"context"`
	TaskID                  string         `json:"taskId"`
	ToolCallID              string         `json:"toolCallId"`
	EnvironmentID           string         `json:"environmentId"`
	SandboxScope            string         `json:"sandboxScope"`
	WorkspaceRef            string         `json:"workspaceRef"`
	AuthorizationGeneration string         `json:"authorizationGeneration"`
	Name                    string         `json:"name"`
	Input                   map[string]any `json:"input"`
	TimeoutMs               int64          `json:"timeoutMs"`
	RetryKind               string         `json:"retryKind"`
}

type ToolResult struct {
	Context                 Context        `json:"context"`
	TaskID                  string         `json:"taskId"`
	AuthorizationGeneration string         `json:"authorizationGeneration"`
	ClaimToken              string         `json:"claimToken"`
	Status                  string         `json:"status"`
	Output                  map[string]any `json:"output"`
	ArtifactRef             *string        `json:"artifactRef"`
}

type UsageReceipt struct {
	Context          Context      `json:"context"`
	ReceiptID        string       `json:"receiptId"`
	SourceEventID    string       `json:"sourceEventId"`
	AccountingDomain string       `json:"accountingDomain"`
	InputTokens      int64        `json:"inputTokens"`
	OutputTokens     int64        `json:"outputTokens"`
	ReportedCost     *json.Number `json:"reportedCost"`
	Currency         *string      `json:"currency"`
}

// Decode bounds one wire message and rejects unsupported versions before a handler can mutate state.
// Authentication, permission expiry and operation-specific validation remain the handler's responsibility.
func Decode(reader io.Reader, destination any) error {
	data, err := io.ReadAll(io.LimitReader(reader, MaxMessageBytes+1))
	if err != nil {
		return err
	}
	if len(data) > MaxMessageBytes {
		return fmt.Errorf("runtime message exceeds %d bytes", MaxMessageBytes)
	}
	var envelope struct {
		Context   Context                 `json:"context"`
		Bootstrap RuntimeSessionBootstrap `json:"bootstrap"`
		Version   int                     `json:"version"`
	}
	if err := json.Unmarshal(data, &envelope); err != nil {
		return err
	}
	switch destination.(type) {
	case *RuntimeSessionAdmission:
		if err := envelope.Bootstrap.Context.Validate(); err != nil {
			return err
		}
	case *ManagedAttemptReceipt, *RuntimeAgentSignal, *RuntimeAgentSignalReceipt, *RuntimeLifecycleReceipt, *RuntimeSessionReady, *RuntimeCommandReceipt, *RuntimeInteractionReceipt, *RuntimeFactReceipt:
		if envelope.Version != Version {
			return fmt.Errorf("unsupported runtime protocol version: %d", envelope.Version)
		}
	default:
		if err := envelope.Context.Validate(); err != nil {
			return err
		}
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	decoder.DisallowUnknownFields()
	return decoder.Decode(destination)
}

// RuntimeAgentSignal is accepted only from the private fenced-task courier.
type RuntimeAgentSignal struct {
	Version             int             `json:"version"`
	SessionID           string          `json:"sessionId"`
	TurnID              string          `json:"turnId"`
	ExecutionGeneration int64           `json:"executionGeneration"`
	ExecutionFence      string          `json:"executionFence"`
	TaskID              string          `json:"taskId"`
	AttemptID           string          `json:"attemptId"`
	TaskFence           int64           `json:"taskFence"`
	AgentRef            string          `json:"agentRef"`
	RequestID           string          `json:"requestId"`
	IdempotencyKey      string          `json:"idempotencyKey"`
	Value               json.RawMessage `json:"value"`
}

// AgentSignalProof parses only task attribution. It does not authenticate the courier.
type AgentSignalProof struct {
	TaskID    string `json:"agent_task"`
	AttemptID string `json:"agent_attempt"`
	Fence     int64  `json:"agent_fence"`
	AgentRef  string `json:"agent_ref"`
}

func ParseAgentSignalProof(actor map[string]any) (*AgentSignalProof, error) {
	present := false
	for _, key := range []string{"agent_task", "agent_attempt", "agent_fence", "agent_ref"} {
		_, ok := actor[key]
		present = present || ok
	}
	if !present {
		return nil, nil
	}
	raw, err := json.Marshal(actor)
	if err != nil {
		return nil, err
	}
	var proof AgentSignalProof
	if err = json.Unmarshal(raw, &proof); err != nil {
		return nil, err
	}
	if proof.TaskID == "" || proof.AttemptID == "" || proof.Fence < 1 || strings.TrimSpace(proof.AgentRef) == "" || len(proof.AgentRef) > 256 {
		return nil, fmt.Errorf("invalid agent signal proof")
	}
	return &proof, nil
}

// RuntimeAgentSignalReceipt is the durable Portal command ACK, not proof of native application.
type RuntimeAgentSignalReceipt struct {
	Version       int    `json:"version"`
	CommandID     string `json:"command_id"`
	SessionID     string `json:"session_id"`
	TurnID        string `json:"turn_id"`
	Kind          string `json:"kind"`
	Status        string `json:"status"`
	CommandStatus string `json:"command_status"`
	CreatedAt     int64  `json:"created_at"`
}

// ManagedAttemptReceipt is returned only by the private control-plane courier.
// It carries admission or durable-settlement facts, never caller configuration.
type ManagedAttemptReceipt struct {
	Version int    `json:"version"`
	TurnID  string `json:"turnId,omitempty"`
	Settled *bool  `json:"settled,omitempty"`
}

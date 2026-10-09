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

type OrchestrationRunMode string

const (
	RunModeDirect   OrchestrationRunMode = "direct"
	RunModeAdaptive OrchestrationRunMode = "adaptive"
	RunModeDeclared OrchestrationRunMode = "declared"
	RunModeSubrun   OrchestrationRunMode = "subrun"
)

type OrchestrationRunState string

const (
	RunPlanned          OrchestrationRunState = "planned"
	RunRunning          OrchestrationRunState = "running"
	RunWaiting          OrchestrationRunState = "waiting"
	RunPaused           OrchestrationRunState = "paused"
	RunCancelling       OrchestrationRunState = "cancelling"
	RunSucceeded        OrchestrationRunState = "succeeded"
	RunPartialSucceeded OrchestrationRunState = "partial_succeeded"
	RunFailed           OrchestrationRunState = "failed"
	RunCancelled        OrchestrationRunState = "cancelled"
)

func IsOrchestrationRunTerminal(state OrchestrationRunState) bool {
	return state == RunSucceeded || state == RunPartialSucceeded || state == RunFailed || state == RunCancelled
}

func CanTransitionOrchestrationRun(from, to OrchestrationRunState) bool {
	if from == to {
		return true
	}
	switch from {
	case RunPlanned:
		return to == RunRunning || to == RunCancelling || to == RunCancelled || to == RunFailed
	case RunRunning:
		return to == RunWaiting || to == RunPaused || to == RunCancelling || to == RunSucceeded || to == RunPartialSucceeded || to == RunFailed
	case RunWaiting:
		return to == RunRunning || to == RunPaused || to == RunCancelling || to == RunSucceeded || to == RunPartialSucceeded || to == RunFailed
	case RunPaused:
		return to == RunRunning || to == RunWaiting || to == RunCancelling
	case RunCancelling:
		return to == RunCancelled
	default:
		return false
	}
}

type RunNodeType string

const (
	RunNodeAgent     RunNodeType = "agent"
	RunNodeTeam      RunNodeType = "team"
	RunNodeApproval  RunNodeType = "approval"
	RunNodeCondition RunNodeType = "condition"
	RunNodeJoin      RunNodeType = "join"
	RunNodeTimer     RunNodeType = "timer"
	RunNodeSignal    RunNodeType = "signal"
	RunNodeSubrun    RunNodeType = "subrun"
)

type RunNodeState string

const (
	RunNodePending   RunNodeState = "pending"
	RunNodeReady     RunNodeState = "ready"
	RunNodeRunning   RunNodeState = "running"
	RunNodeWaiting   RunNodeState = "waiting"
	RunNodeSucceeded RunNodeState = "succeeded"
	RunNodeSkipped   RunNodeState = "skipped"
	RunNodeFailed    RunNodeState = "failed"
	RunNodeCancelled RunNodeState = "cancelled"
)

func IsRunNodeTerminal(state RunNodeState) bool {
	return state == RunNodeSucceeded || state == RunNodeSkipped || state == RunNodeFailed || state == RunNodeCancelled
}

func CanTransitionRunNode(from, to RunNodeState) bool {
	if from == to {
		return true
	}
	switch from {
	case RunNodePending:
		return to == RunNodeReady || to == RunNodeSkipped || to == RunNodeCancelled
	case RunNodeReady:
		return to == RunNodeRunning || to == RunNodeWaiting || to == RunNodeSkipped || to == RunNodeFailed || to == RunNodeCancelled
	case RunNodeRunning:
		return to == RunNodeWaiting || to == RunNodeSucceeded || to == RunNodeFailed || to == RunNodeCancelled
	case RunNodeWaiting:
		return to == RunNodeReady || to == RunNodeRunning || to == RunNodeSucceeded || to == RunNodeFailed || to == RunNodeCancelled
	default:
		return false
	}
}

type OrchestrationDefinition struct {
	ID           uuid.UUID       `json:"id"`
	Tenant       string          `json:"tenant"`
	Namespace    string          `json:"namespace"`
	Name         string          `json:"name"`
	Description  string          `json:"description,omitempty"`
	DraftSpec    json.RawMessage `json:"draftSpec"`
	DraftVersion int64           `json:"draftVersion"`
	Version      int64           `json:"version"`
	CreatedBy    Actor           `json:"createdBy"`
	CreatedAt    time.Time       `json:"createdAt"`
	UpdatedAt    time.Time       `json:"updatedAt"`
	ArchivedAt   *time.Time      `json:"archivedAt,omitempty"`
}

type OrchestrationRevision struct {
	ExpectedDefinitionVersion int64           `json:"-"`
	ID                        uuid.UUID       `json:"id"`
	DefinitionID              uuid.UUID       `json:"definitionId"`
	Tenant                    string          `json:"tenant"`
	Namespace                 string          `json:"namespace"`
	Revision                  int64           `json:"revision"`
	Spec                      json.RawMessage `json:"spec"`
	Checksum                  string          `json:"checksum"`
	PublishedBy               Actor           `json:"publishedBy"`
	PublishedAt               time.Time       `json:"publishedAt"`
}

type OrchestrationRun struct {
	ID                   uuid.UUID             `json:"id"`
	Tenant               string                `json:"tenant"`
	Namespace            string                `json:"namespace"`
	RootIssueID          uuid.UUID             `json:"rootIssueId"`
	Mode                 OrchestrationRunMode  `json:"mode"`
	DefinitionRevisionID *uuid.UUID            `json:"definitionRevisionId,omitempty"`
	ParentRunID          *uuid.UUID            `json:"parentRunId,omitempty"`
	ParentNodeID         *uuid.UUID            `json:"parentNodeId,omitempty"`
	RerunOfRunID         *uuid.UUID            `json:"rerunOfRunId,omitempty"`
	TriggerType          string                `json:"triggerType"`
	TriggerRef           string                `json:"triggerRef,omitempty"`
	IdempotencyKey       string                `json:"idempotencyKey,omitempty"`
	Input                json.RawMessage       `json:"input,omitempty"`
	Variables            json.RawMessage       `json:"variables,omitempty"`
	Output               json.RawMessage       `json:"output,omitempty"`
	PolicySnapshot       json.RawMessage       `json:"policySnapshot,omitempty"`
	Usage                json.RawMessage       `json:"usage,omitempty"`
	State                OrchestrationRunState `json:"state"`
	WaitReason           string                `json:"waitReason,omitempty"`
	FailureCode          string                `json:"failureCode,omitempty"`
	FailureMessage       string                `json:"failureMessage,omitempty"`
	Version              int64                 `json:"version"`
	CreatedBy            Actor                 `json:"createdBy"`
	CreatedAt            time.Time             `json:"createdAt"`
	UpdatedAt            time.Time             `json:"updatedAt"`
	StartedAt            *time.Time            `json:"startedAt,omitempty"`
	CompletedAt          *time.Time            `json:"completedAt,omitempty"`
}

type RunNode struct {
	ID                uuid.UUID       `json:"id"`
	RunID             uuid.UUID       `json:"runId"`
	Tenant            string          `json:"tenant"`
	Namespace         string          `json:"namespace"`
	NodeKey           string          `json:"nodeKey"`
	DefinitionNodeKey string          `json:"definitionNodeKey,omitempty"`
	Type              RunNodeType     `json:"type"`
	Role              string          `json:"role,omitempty"`
	IssueID           *uuid.UUID      `json:"issueId,omitempty"`
	State             RunNodeState    `json:"state"`
	Config            json.RawMessage `json:"config,omitempty"`
	Input             json.RawMessage `json:"input,omitempty"`
	Output            json.RawMessage `json:"output,omitempty"`
	Iteration         int32           `json:"iteration"`
	WaitReason        string          `json:"waitReason,omitempty"`
	FailureCode       string          `json:"failureCode,omitempty"`
	FailureMessage    string          `json:"failureMessage,omitempty"`
	Version           int64           `json:"version"`
	CreatedAt         time.Time       `json:"createdAt"`
	UpdatedAt         time.Time       `json:"updatedAt"`
	StartedAt         *time.Time      `json:"startedAt,omitempty"`
	CompletedAt       *time.Time      `json:"completedAt,omitempty"`
}

type RunEdge struct {
	ID         uuid.UUID       `json:"id"`
	RunID      uuid.UUID       `json:"runId"`
	Tenant     string          `json:"tenant"`
	Namespace  string          `json:"namespace"`
	FromNodeID uuid.UUID       `json:"fromNodeId"`
	ToNodeID   uuid.UUID       `json:"toNodeId"`
	OnStates   []RunNodeState  `json:"onStates"`
	Condition  string          `json:"condition,omitempty"`
	Ordinal    int32           `json:"ordinal"`
	Metadata   json.RawMessage `json:"metadata,omitempty"`
	CreatedAt  time.Time       `json:"createdAt"`
}

type RunTeamSnapshot struct {
	RunID     uuid.UUID       `json:"runId"`
	TeamID    uuid.UUID       `json:"teamId"`
	Tenant    string          `json:"tenant"`
	Namespace string          `json:"namespace"`
	Snapshot  json.RawMessage `json:"snapshot"`
	CreatedAt time.Time       `json:"createdAt"`
}

type RunEvent struct {
	ID             uuid.UUID       `json:"id"`
	RunID          uuid.UUID       `json:"runId"`
	Tenant         string          `json:"tenant"`
	Namespace      string          `json:"namespace"`
	Sequence       int64           `json:"sequence"`
	NodeID         *uuid.UUID      `json:"nodeId,omitempty"`
	AgentTaskID    *uuid.UUID      `json:"agentTaskId,omitempty"`
	AttemptID      *uuid.UUID      `json:"attemptId,omitempty"`
	Type           string          `json:"type"`
	Actor          Actor           `json:"actor"`
	Payload        json.RawMessage `json:"payload,omitempty"`
	CausationID    string          `json:"causationId,omitempty"`
	CorrelationID  string          `json:"correlationId,omitempty"`
	IdempotencyKey string          `json:"idempotencyKey,omitempty"`
	OccurredAt     time.Time       `json:"occurredAt"`
}

type RuntimeBindingCandidate struct {
	Binding              RuntimeBinding  `json:"binding"`
	RequiredCapabilities json.RawMessage `json:"requiredCapabilities,omitempty"`
	SecurityConstraints  json.RawMessage `json:"securityConstraints,omitempty"`
	// Dispatch metadata is assigned by the scheduler and frozen into the task.
	SelectionSource string `json:"selectionSource,omitempty"`
	CandidateIndex  int32  `json:"candidateIndex,omitempty"`
}

// RuntimeBindingPolicy is the reusable ordered candidate policy embedded in a
// Team member snapshot. AgentRuntimePolicy adds persistence identity,
// concurrency, and timeout controls around the same selection semantics.
type RuntimeBindingPolicy struct {
	Candidates    []RuntimeBindingCandidate `json:"candidates"`
	SelectionMode string                    `json:"selectionMode"`
	FallbackMode  string                    `json:"fallbackMode"`
	RetryPolicy   json.RawMessage           `json:"retryPolicy,omitempty"`
}

type AgentRuntimePolicy struct {
	ID                    uuid.UUID                 `json:"id"`
	Tenant                string                    `json:"tenant"`
	Namespace             string                    `json:"namespace"`
	AgentRef              string                    `json:"agentId"`
	Candidates            []RuntimeBindingCandidate `json:"candidates"`
	SelectionMode         string                    `json:"selectionMode"`
	FallbackMode          string                    `json:"fallbackMode"`
	MaxConcurrency        int32                     `json:"maxConcurrency,omitempty"`
	QueueTimeoutSeconds   int64                     `json:"queueTimeoutSeconds,omitempty"`
	AttemptTimeoutSeconds int64                     `json:"attemptTimeoutSeconds,omitempty"`
	RetryPolicy           json.RawMessage           `json:"retryPolicy,omitempty"`
	Version               int64                     `json:"version"`
	CreatedAt             time.Time                 `json:"createdAt"`
	UpdatedAt             time.Time                 `json:"updatedAt"`
	ArchivedAt            *time.Time                `json:"archivedAt,omitempty"`
}

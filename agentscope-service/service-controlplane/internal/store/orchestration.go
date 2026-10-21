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

package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
)

// These per-Run limits bound graph evaluation; they are not instance execution
// slots or Application admission quotas. Dynamic delegation shares the limit.
const (
	MaxRunNodes = 8192
	MaxRunEdges = 16384
	// Aggregate persisted payload bytes per complete node/edge view.
	MaxRunGraphBytes = 16 << 20
)

func RunNodeGraphBytes(node *controlmodel.RunNode) int {
	return len(node.Config) + len(node.Input) + len(node.Output) + len(node.NodeKey) + len(node.DefinitionNodeKey) + len(node.Role) + len(node.WaitReason) + len(node.FailureCode) + len(node.FailureMessage) + len(node.Tenant) + len(node.Namespace)
}

func RunEdgeGraphBytes(edge *controlmodel.RunEdge) int {
	bytes := len(edge.Condition) + len(edge.Metadata) + len(edge.Tenant) + len(edge.Namespace)
	for _, state := range edge.OnStates {
		bytes += len(state)
	}
	return bytes
}

type OrchestrationDefinitionFilter struct {
	ExcludedIDs             []uuid.UUID
	Tenant, Namespace, Name string
	IncludeArchived         bool
	Offset                  int
	Limit                   int
}

type OrchestrationRunFilter struct {
	Tenant, Namespace string
	RootIssueID       uuid.UUID
	// IssueID matches either the root Issue or any AgentTask Issue in the Run.
	DefinitionID           uuid.UUID
	ParentNodeID           uuid.UUID
	ExcludeCancelling      bool
	Offset                 int
	IssueID                uuid.UUID
	State                  controlmodel.OrchestrationRunState
	ActiveOnly             bool
	PortalOnly             bool
	IncludeTerminalCleanup bool
	OldestFirst            bool
	Limit                  int
	// Keyset position for bounded ascending runtime reconciliation.
	AfterCreatedAt *time.Time
	AfterID        uuid.UUID
}

type RunNodeFilter struct {
	AfterID    uuid.UUID
	ActiveOnly bool
	// AutonomousOnly finds work that can advance without a human answer.
	AutonomousOnly bool
	HumanOnly      bool
	// AnsweredOnly finds waiting human nodes whose durable answer is ready.
	AnsweredOnly bool
	Limit        int
}

// OrchestrationRepository is the durable authority for definitions, immutable
// revisions, materialized run graphs, event streams, and runtime policies.
type OrchestrationRepository interface {
	CreateDefinition(context.Context, *controlmodel.OrchestrationDefinition) (*controlmodel.OrchestrationDefinition, error)
	GetDefinition(context.Context, uuid.UUID) (*controlmodel.OrchestrationDefinition, error)
	ListDefinitions(context.Context, OrchestrationDefinitionFilter) ([]*controlmodel.OrchestrationDefinition, error)
	UpdateDefinition(context.Context, *controlmodel.OrchestrationDefinition, int64) (*controlmodel.OrchestrationDefinition, error)
	CreateRevision(context.Context, *controlmodel.OrchestrationRevision) (*controlmodel.OrchestrationRevision, error)
	GetRevision(context.Context, uuid.UUID) (*controlmodel.OrchestrationRevision, error)
	ListRevisions(context.Context, uuid.UUID) ([]*controlmodel.OrchestrationRevision, error)

	CreateRun(context.Context, *controlmodel.OrchestrationRun) (*controlmodel.OrchestrationRun, error)
	GetRun(context.Context, uuid.UUID) (*controlmodel.OrchestrationRun, error)
	ListRuns(context.Context, OrchestrationRunFilter) ([]*controlmodel.OrchestrationRun, error)
	TransitionRun(context.Context, uuid.UUID, int64, controlmodel.OrchestrationRunState, json.RawMessage, string, string) (*controlmodel.OrchestrationRun, error)
	CompleteTerminalCleanup(context.Context, uuid.UUID, int64) error
	CreateNode(context.Context, *controlmodel.RunNode) (*controlmodel.RunNode, error)
	GetNode(context.Context, uuid.UUID) (*controlmodel.RunNode, error)
	ListNodes(context.Context, uuid.UUID) ([]*controlmodel.RunNode, error)
	ListNodesPage(context.Context, uuid.UUID, RunNodeFilter) ([]*controlmodel.RunNode, error)
	SetNodeInput(context.Context, uuid.UUID, int64, json.RawMessage) (*controlmodel.RunNode, error)
	TransitionNode(context.Context, uuid.UUID, int64, controlmodel.RunNodeState, json.RawMessage, string, string) (*controlmodel.RunNode, error)
	// SaveCoordinatorCompletionIntent serializes decision updates with physical Task completion.
	SaveCoordinatorCompletionIntent(context.Context, uuid.UUID, int64, json.RawMessage) (json.RawMessage, error)
	// FinalizeCoordinatorNode checks the complete Team barrier and persists success plus its event atomically.
	FinalizeCoordinatorNode(context.Context, uuid.UUID, int64, json.RawMessage, controlmodel.Actor) (*controlmodel.RunNode, error)
	CreateEdges(context.Context, []*controlmodel.RunEdge) error
	ListEdges(context.Context, uuid.UUID) ([]*controlmodel.RunEdge, error)
	PutTeamSnapshot(context.Context, *controlmodel.RunTeamSnapshot) (*controlmodel.RunTeamSnapshot, error)
	ListTeamSnapshots(context.Context, uuid.UUID) ([]*controlmodel.RunTeamSnapshot, error)
	AppendRunEvent(context.Context, *controlmodel.RunEvent) (*controlmodel.RunEvent, error)
	ListRunEvents(context.Context, uuid.UUID, int64, int) ([]*controlmodel.RunEvent, error)
	FirstRunSignal(context.Context, uuid.UUID, uuid.UUID, string, bool) (*controlmodel.RunEvent, error)
	// WaitForRunEvent blocks until a durable event exists after the supplied
	// per-run sequence. Implementations must close the read/wait race.
	WaitForRunEvent(context.Context, uuid.UUID, int64) error

	PutRuntimePolicy(context.Context, *controlmodel.AgentRuntimePolicy) (*controlmodel.AgentRuntimePolicy, error)
	GetRuntimePolicy(context.Context, string, string, string) (*controlmodel.AgentRuntimePolicy, error)
	ListRuntimePolicies(context.Context, string, string, int) ([]*controlmodel.AgentRuntimePolicy, error)
}

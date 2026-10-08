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

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

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
	DefinitionID uuid.UUID
	ParentNodeID uuid.UUID
	Offset       int
	IssueID      uuid.UUID
	State        controlmodel.OrchestrationRunState
	ActiveOnly   bool
	OldestFirst  bool
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
	CreateNode(context.Context, *controlmodel.RunNode) (*controlmodel.RunNode, error)
	GetNode(context.Context, uuid.UUID) (*controlmodel.RunNode, error)
	ListNodes(context.Context, uuid.UUID) ([]*controlmodel.RunNode, error)
	SetNodeInput(context.Context, uuid.UUID, int64, json.RawMessage) (*controlmodel.RunNode, error)
	TransitionNode(context.Context, uuid.UUID, int64, controlmodel.RunNodeState, json.RawMessage, string, string) (*controlmodel.RunNode, error)
	CreateEdges(context.Context, []*controlmodel.RunEdge) error
	ListEdges(context.Context, uuid.UUID) ([]*controlmodel.RunEdge, error)
	PutTeamSnapshot(context.Context, *controlmodel.RunTeamSnapshot) (*controlmodel.RunTeamSnapshot, error)
	ListTeamSnapshots(context.Context, uuid.UUID) ([]*controlmodel.RunTeamSnapshot, error)
	AppendRunEvent(context.Context, *controlmodel.RunEvent) (*controlmodel.RunEvent, error)
	ListRunEvents(context.Context, uuid.UUID, int64, int) ([]*controlmodel.RunEvent, error)
	// WaitForRunEvent blocks until a durable event exists after the supplied
	// per-run sequence. Implementations must close the read/wait race.
	WaitForRunEvent(context.Context, uuid.UUID, int64) error

	PutRuntimePolicy(context.Context, *controlmodel.AgentRuntimePolicy) (*controlmodel.AgentRuntimePolicy, error)
	GetRuntimePolicy(context.Context, string, string, string) (*controlmodel.AgentRuntimePolicy, error)
	ListRuntimePolicies(context.Context, string, string, int) ([]*controlmodel.AgentRuntimePolicy, error)
}

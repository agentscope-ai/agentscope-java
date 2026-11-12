// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package memory

import (
	"errors"
	"fmt"
	"testing"

	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

func TestDynamicDelegationCapacityAndExistingTaskCoalescing(t *testing.T) {
	facade, err := Open(t.Context(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	st := facade.(*Store)
	defer st.Close()
	actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
	issue, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "graph", Creator: actor, AssigneeType: model.AssigneeAgent, AssigneeRef: "original"})
	if err != nil {
		t.Fatal(err)
	}
	tasks, _ := st.Collaboration().ListAgentTasks(t.Context(), store.AgentTaskFilter{IssueID: issue.ID, Limit: 10})
	task := tasks[0]
	st.mu.Lock()
	for i := 1; i < store.MaxRunNodes; i++ {
		id := uuid.New()
		st.runNodes[id] = &model.RunNode{ID: id, RunID: task.OrchestrationRunID, NodeKey: fmt.Sprint(i), Type: model.RunNodeCondition}
	}
	st.mu.Unlock()
	childID := uuid.New()
	_, err = st.Collaboration().CreateIssue(t.Context(), &model.Issue{ID: childID, Tenant: "t", Namespace: "n", Title: "delegation", Creator: actor, AssigneeType: model.AssigneeAgent, AssigneeRef: "other", ParentIssueID: &issue.ID, SourceType: "agent-task", SourceRef: task.ID.String()})
	if !errors.Is(err, store.ErrResourceLimit) {
		t.Fatal("dynamic capacity bypass", err)
	}
	if _, err = st.Collaboration().GetIssue(t.Context(), childID); !errors.Is(err, store.ErrNotFound) {
		t.Fatal("orphan rejected Issue", err)
	}
	result, err := st.Collaboration().CreateComment(t.Context(), store.CreateCommentRequest{Comment: &model.Comment{IssueID: issue.ID, Author: model.Actor{Type: model.ActorAgent, Ref: "producer"}, Content: "route", SourceTaskID: &task.ID}, Targets: []store.CommentTarget{{TargetType: model.AssigneeAgent, TargetRef: "other", AgentRef: "other"}, {TargetType: model.AssigneeAgent, TargetRef: "original", AgentRef: "original"}}})
	if err != nil || len(result.Routes) != 2 || result.Routes[0].Outcome != model.RouteBlocked || result.Routes[0].ReasonCode != "run_graph_capacity" || result.Routes[1].Outcome != model.RouteCoalesced {
		t.Fatalf("route result: %+v %v", result, err)
	}
	nodes, err := st.Orchestration().ListNodes(t.Context(), task.OrchestrationRunID)
	if err != nil || len(nodes) != store.MaxRunNodes {
		t.Fatalf("node capacity grew: %d %v", len(nodes), err)
	}
}

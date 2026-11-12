// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package postgres

import (
	"errors"
	"fmt"
	"sync"
	"testing"

	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

func TestGraphCapacityIsSharedByReplicasAndDynamicDelegation(t *testing.T) {
	cfg := serviceMigrationConfig(t)
	first, err := store.Open(t.Context(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	second, err := store.Open(t.Context(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
	issue, err := first.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "graph", Creator: actor, AssigneeType: model.AssigneeAgent, AssigneeRef: "original"})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := first.Collaboration().ListAgentTasks(t.Context(), store.AgentTaskFilter{IssueID: issue.ID, Limit: 10})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("initial task: %v %v", tasks, err)
	}
	task := tasks[0]
	runID := task.OrchestrationRunID
	// Populate only this isolated test schema efficiently; application writes below
	// use separate pools and the real transaction guard, not a test counter.
	_, err = first.(*Store).pool.Exec(t.Context(), `INSERT INTO orchestration_run_nodes(id,run_id,tenant,namespace,node_key,type,state)
 SELECT md5($1::text || ':' || g::text)::uuid,$1::uuid,'t','n','seed-' || g::text,'condition','succeeded' FROM generate_series(1,$2) g`, runID, store.MaxRunNodes-2)
	if err != nil {
		t.Fatal(err)
	}
	start := make(chan struct{})
	results := make(chan error, 8)
	var group sync.WaitGroup
	for i := 0; i < 8; i++ {
		group.Add(1)
		go func(i int) {
			defer group.Done()
			<-start
			st := first
			if i%2 == 1 {
				st = second
			}
			_, err := st.Orchestration().CreateNode(t.Context(), &model.RunNode{RunID: runID, Tenant: "t", Namespace: "n", NodeKey: fmt.Sprintf("race-%d", i), Type: model.RunNodeCondition})
			results <- err
		}(i)
	}
	close(start)
	group.Wait()
	close(results)
	accepted := 0
	for err := range results {
		if err == nil {
			accepted++
		} else if !errors.Is(err, store.ErrResourceLimit) {
			t.Fatal(err)
		}
	}
	nodes, err := first.Orchestration().ListNodes(t.Context(), runID)
	if err != nil || accepted != 1 || len(nodes) != store.MaxRunNodes {
		t.Fatalf("oversubscribed replica capacity: accepted=%d nodes=%d error=%v", accepted, len(nodes), err)
	}
	childID := uuid.New()
	_, err = first.Collaboration().CreateIssue(t.Context(), &model.Issue{ID: childID, Tenant: "t", Namespace: "n", Title: "delegation", Creator: actor, AssigneeType: model.AssigneeAgent, AssigneeRef: "other", ParentIssueID: &issue.ID, SourceType: "agent-task", SourceRef: task.ID.String()})
	if !errors.Is(err, store.ErrResourceLimit) {
		t.Fatal("dynamic delegation bypassed limit", err)
	}
	if _, err = first.Collaboration().GetIssue(t.Context(), childID); !errors.Is(err, store.ErrNotFound) {
		t.Fatal("capacity rejection left orphan Issue", err)
	}
	result, err := first.Collaboration().CreateComment(t.Context(), store.CreateCommentRequest{Comment: &model.Comment{IssueID: issue.ID, Author: model.Actor{Type: model.ActorAgent, Ref: "producer"}, Content: "route", SourceTaskID: &task.ID}, Targets: []store.CommentTarget{{TargetType: model.AssigneeAgent, TargetRef: "other", AgentRef: "other"}, {TargetType: model.AssigneeAgent, TargetRef: "original", AgentRef: "original"}}})
	if err != nil || len(result.Routes) != 2 || result.Routes[0].Outcome != model.RouteBlocked || result.Routes[0].ReasonCode != "run_graph_capacity" || result.Routes[0].TaskID != nil || result.Routes[1].Outcome != model.RouteCoalesced {
		t.Fatalf("capacity did not persist blocked/coalesced routes: %+v %v", result, err)
	}
	_, err = first.(*Store).pool.Exec(t.Context(), `INSERT INTO orchestration_run_edges(id,run_id,tenant,namespace,from_node_id,to_node_id,on_states)
 SELECT md5($1::text || ':edge:' || g::text)::uuid,$1::uuid,'t','n',$2,$3,'["succeeded"]'::jsonb FROM generate_series(1,$4) g`, runID, nodes[0].ID, nodes[1].ID, store.MaxRunEdges-1)
	if err != nil {
		t.Fatal(err)
	}
	edge := func() *model.RunEdge {
		return &model.RunEdge{RunID: runID, Tenant: "t", Namespace: "n", FromNodeID: nodes[0].ID, ToNodeID: nodes[1].ID, OnStates: []model.RunNodeState{model.RunNodeSucceeded}}
	}
	if err = second.Orchestration().CreateEdges(t.Context(), []*model.RunEdge{edge(), edge()}); !errors.Is(err, store.ErrResourceLimit) {
		t.Fatal("oversized edge batch accepted", err)
	}
	edges, err := first.Orchestration().ListEdges(t.Context(), runID)
	if err != nil || len(edges) != store.MaxRunEdges-1 {
		t.Fatalf("partial edge batch committed: %d %v", len(edges), err)
	}
	if err = second.Orchestration().CreateEdges(t.Context(), []*model.RunEdge{edge()}); err != nil {
		t.Fatal("rejection consumed last slot", err)
	}
}

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package orchestration

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

func TestMaterializationAndExecutionContinueInFiniteBatches(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			st := cancellationStore(t, driver)
			actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
			spec := DefinitionSpec{}
			for i := 0; i < 40; i++ {
				key := fmt.Sprintf("node-%03d", i)
				spec.Nodes = append(spec.Nodes, DefinitionNode{Key: key, Type: model.RunNodeCondition})
				for distance := 1; distance <= 3 && i >= distance; distance++ {
					spec.Edges = append(spec.Edges, DefinitionEdge{From: fmt.Sprintf("node-%03d", i-distance), To: key})
				}
			}
			raw, _ := json.Marshal(spec)
			definition, err := st.Orchestration().CreateDefinition(t.Context(), &model.OrchestrationDefinition{Tenant: "t", Namespace: "n", Name: "batch", DraftSpec: raw, CreatedBy: actor})
			if err != nil {
				t.Fatal(err)
			}
			service := &Service{Store: st}
			if _, err = service.Publish(t.Context(), definition.ID, actor); err != nil {
				t.Fatal(err)
			}
			run, err := service.Start(t.Context(), definition.ID, StartRequest{IdempotencyKey: "batches", Issue: &model.Issue{Title: "batch graph"}, Actor: actor})
			if err != nil {
				t.Fatal(err)
			}
			nodes, err := st.Orchestration().ListNodes(t.Context(), run.ID)
			if err != nil || len(nodes) != materializeNodeBatch || run.State != model.RunPlanned {
				t.Fatalf("first graph batch: nodes=%d state=%s error=%v", len(nodes), run.State, err)
			}
			// Replace the Engine after each call; no local cursor is carried forward.
			for pass := 0; pass < 20; pass++ {
				beforeNodes, _ := st.Orchestration().ListNodes(t.Context(), run.ID)
				beforeEdges, _ := st.Orchestration().ListEdges(t.Context(), run.ID)
				if err = (&Engine{Store: st}).ReconcileRun(t.Context(), run.ID); err != nil {
					t.Fatal(err)
				}
				nodes, err = st.Orchestration().ListNodes(t.Context(), run.ID)
				if err != nil {
					t.Fatal(err)
				}
				edges, err := st.Orchestration().ListEdges(t.Context(), run.ID)
				if err != nil {
					t.Fatal(err)
				}
				if len(nodes)-len(beforeNodes) > materializeNodeBatch || len(edges)-len(beforeEdges) > materializeEdgeBatch {
					t.Fatal("graph construction exceeded one batch")
				}
				run, err = st.Orchestration().GetRun(t.Context(), run.ID)
				if err != nil {
					t.Fatal(err)
				}
				if run.State == model.RunPlanned {
					for _, node := range nodes {
						if model.IsRunNodeTerminal(node.State) {
							t.Fatal("executed before complete graph commit")
						}
					}
				}
				if model.IsOrchestrationRunTerminal(run.State) {
					break
				}
			}
			edges, err := st.Orchestration().ListEdges(t.Context(), run.ID)
			if err != nil || len(edges) != len(spec.Edges) || len(nodes) != 40 || run.State != model.RunSucceeded {
				t.Fatalf("incomplete graph/output: edges=%d nodes=%d state=%s error=%v", len(edges), len(nodes), run.State, err)
			}
			var output map[string]json.RawMessage
			if json.Unmarshal(run.Output, &output) != nil || len(output) != 40 {
				t.Fatalf("outputs truncated: %s", run.Output)
			}
		})
	}
}

func TestWaitingSweepRotatesPastLongLivedHumanWaits(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			st := cancellationStore(t, driver)
			actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
			issue, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "wait", Creator: actor})
			if err != nil {
				t.Fatal(err)
			}
			run, err := st.Orchestration().CreateRun(t.Context(), &model.OrchestrationRun{Tenant: "t", Namespace: "n", RootIssueID: issue.ID, Mode: model.RunModeDeclared, State: model.RunRunning, CreatedBy: actor})
			if err != nil {
				t.Fatal(err)
			}
			var timer *model.RunNode
			for i := 1; i <= 65; i++ {
				node := &model.RunNode{ID: uuid.MustParse(fmt.Sprintf("00000000-0000-0000-0000-%012x", i)), RunID: run.ID, Tenant: "t", Namespace: "n", NodeKey: fmt.Sprintf("wait-%03d", i), Type: model.RunNodeApproval, State: model.RunNodeWaiting, IssueID: &issue.ID, Config: json.RawMessage(`{"type":"approval"}`)}
				if i == 65 {
					node.Type = model.RunNodeTimer
					node.Config = json.RawMessage(`{"type":"timer"}`)
					node.Output, _ = json.Marshal(map[string]any{"wakeAt": time.Now().Add(-time.Minute)})
				}
				timer, err = st.Orchestration().CreateNode(t.Context(), node)
				if err != nil {
					t.Fatal(err)
				}
			}
			for pass := 0; pass < 3; pass++ {
				if err = (&Engine{Store: st}).ReconcileRun(t.Context(), run.ID); err != nil {
					t.Fatal(err)
				}
			}
			timer, err = st.Orchestration().GetNode(t.Context(), timer.ID)
			if err != nil || timer.State != model.RunNodeSucceeded {
				t.Fatalf("human waits starved a timer across Engine replacement: %+v %v", timer, err)
			}
			if _, err = st.KV().Get(t.Context(), run.Tenant, waitingSweepCursorPath, run.ID.String()); err != nil {
				t.Fatal("missing shared sweep cursor", err)
			}
		})
	}
}

func TestOversizedGraphFailsAndUsesDurableCancellation(t *testing.T) {
	st := cancellationStore(t, store.DriverMemory)
	actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
	issue, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "large", Creator: actor})
	if err != nil {
		t.Fatal(err)
	}
	run, err := st.Orchestration().CreateRun(t.Context(), &model.OrchestrationRun{Tenant: "t", Namespace: "n", RootIssueID: issue.ID, Mode: model.RunModeDeclared, State: model.RunRunning, CreatedBy: actor})
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		if _, err = st.Orchestration().CreateNode(t.Context(), &model.RunNode{RunID: run.ID, Tenant: "t", Namespace: "n", NodeKey: fmt.Sprint(i), Type: model.RunNodeSignal, State: model.RunNodeWaiting, Output: json.RawMessage(`"` + strings.Repeat("a", store.MaxRunGraphBytes/2) + `"`)}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err = st.Orchestration().ListNodes(t.Context(), run.ID); !errors.Is(err, store.ErrResourceLimit) {
		t.Fatal("oversized graph was accepted", err)
	}
	for pass := 0; pass < 8; pass++ {
		if err = (&Engine{Store: st}).ReconcileRun(t.Context(), run.ID); err != nil {
			t.Fatal(err)
		}
		run, _ = st.Orchestration().GetRun(t.Context(), run.ID)
		if model.IsOrchestrationRunTerminal(run.State) {
			break
		}
	}
	if run.State != model.RunFailed || run.FailureCode != "resource_limit" {
		t.Fatalf("capacity failure stranded graph: %+v", run)
	}
}

func TestDefinitionCapacityIsCheckedBeforeCELOrMaterialization(t *testing.T) {
	for _, spec := range []DefinitionSpec{{Nodes: make([]DefinitionNode, maxDefinitionNodes+1)}, {Nodes: []DefinitionNode{{Key: "a", Type: model.RunNodeCondition}}, Edges: make([]DefinitionEdge, maxDefinitionEdges+1)}} {
		raw, _ := json.Marshal(spec)
		if _, err := ParseAndValidateSpec(raw, nil); !errors.Is(err, store.ErrResourceLimit) {
			t.Fatal("oversized definition accepted", err)
		}
	}
	if _, err := ParseAndValidateSpec(json.RawMessage(strings.Repeat(" ", maxDefinitionBytes+1)), nil); !errors.Is(err, store.ErrResourceLimit) {
		t.Fatal("oversized bytes decoded before limit", err)
	}
}

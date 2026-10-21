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
	"fmt"
	"strings"
	"testing"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
)

func TestCoordinatorBarrierChecksTasksAndChildrenBeyondFirstPage(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			st := cancellationStore(t, driver)
			actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
			root, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "large Team", Creator: actor})
			if err != nil {
				t.Fatal(err)
			}
			run, err := st.Orchestration().CreateRun(t.Context(), &model.OrchestrationRun{Tenant: "t", Namespace: "n", RootIssueID: root.ID, Mode: model.RunModeAdaptive, State: model.RunRunning, CreatedBy: actor})
			if err != nil {
				t.Fatal(err)
			}
			node, err := st.Orchestration().CreateNode(t.Context(), &model.RunNode{RunID: run.ID, Tenant: "t", Namespace: "n", IssueID: &root.ID, NodeKey: "coordinator", Type: model.RunNodeTeam, State: model.RunNodeWaiting})
			if err != nil {
				t.Fatal(err)
			}
			var leader, worker *model.AgentTask
			for i := 0; i < 521; i++ {
				leader, err = st.Collaboration().CreateRunAgentTask(t.Context(), store.RunTaskRequest{RunID: run.ID, NodeID: node.ID, IssueID: root.ID, AgentRef: fmt.Sprintf("task-%03d", i), Leader: i < 520, Originator: actor})
				if err != nil {
					t.Fatal(err)
				}
				if i == 520 {
					worker = leader
				}
			}
			all, err := store.ReadTasksBounded(t.Context(), st.Collaboration(), store.AgentTaskFilter{RunID: run.ID})
			if err != nil || len(all) != 521 {
				t.Fatalf("incomplete task view: %d %v", len(all), err)
			}
			leader = all[0]
			service := &Service{Store: st}
			if _, _, err = service.ValidateCoordinatorNodeCompletion(t.Context(), leader.ID); err == nil || !strings.Contains(err.Error(), "active worker task") {
				t.Fatalf("501+ task ignored: %v", err)
			}
			if _, err = st.Collaboration().CancelAgentTask(t.Context(), worker.ID, worker.Version); err != nil {
				t.Fatal(err)
			}
			for i := 0; i < 521; i++ {
				status := model.IssueDone
				if i == 520 {
					status = model.IssueInProgress
				}
				if _, err = st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: fmt.Sprintf("child-%03d", i), Creator: actor, ParentIssueID: &root.ID, Status: status}); err != nil {
					t.Fatal(err)
				}
			}
			if _, _, err = service.ValidateCoordinatorNodeCompletion(t.Context(), leader.ID); err == nil || !strings.Contains(err.Error(), "active child issue") {
				t.Fatalf("501+ child ignored: %v", err)
			}
		})
	}
}

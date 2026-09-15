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

package orchestration

import (
	"context"
	"testing"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func TestFailFastCancelsActiveTasksOnSiblingNodes(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })

	runID, failedNodeID, activeNodeID, issueID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	nowActor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	if _, err = st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		ID: issueID, Tenant: "tenant", Namespace: "default", Title: "fail fast",
		Creator: nowActor,
	}); err != nil {
		t.Fatal(err)
	}
	if _, err = st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{
		ID: runID, Tenant: "tenant", Namespace: "default", RootIssueID: issueID,
		Mode: controlmodel.RunModeAdaptive, State: controlmodel.RunRunning, CreatedBy: nowActor,
	}); err != nil {
		t.Fatal(err)
	}
	failedNode, err := st.Orchestration().CreateNode(ctx, &controlmodel.RunNode{
		ID: failedNodeID, RunID: runID, Tenant: "tenant", Namespace: "default",
		NodeKey: "failed", Type: controlmodel.RunNodeAgent, IssueID: &issueID,
		State: controlmodel.RunNodeReady,
	})
	if err != nil {
		t.Fatal(err)
	}
	activeNode, err := st.Orchestration().CreateNode(ctx, &controlmodel.RunNode{
		ID: activeNodeID, RunID: runID, Tenant: "tenant", Namespace: "default",
		NodeKey: "active", Type: controlmodel.RunNodeAgent, IssueID: &issueID,
		State: controlmodel.RunNodeReady,
	})
	if err != nil {
		t.Fatal(err)
	}
	failedNode, err = st.Orchestration().TransitionNode(ctx, failedNode.ID, failedNode.Version,
		controlmodel.RunNodeFailed, nil, "worker_failed", "worker failed")
	if err != nil {
		t.Fatal(err)
	}
	activeNode, err = st.Orchestration().TransitionNode(ctx, activeNode.ID, activeNode.Version,
		controlmodel.RunNodeWaiting, nil, "agent_task", "")
	if err != nil {
		t.Fatal(err)
	}
	task, err := st.Collaboration().CreateRunAgentTask(ctx, store.RunTaskRequest{
		RunID: runID, NodeID: activeNode.ID, IssueID: issueID, AgentRef: "leader", Originator: nowActor,
	})
	if err != nil {
		t.Fatal(err)
	}
	task, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{
		TaskID: task.ID, ExpectedVersion: task.Version,
	})
	if err == nil {
		task, err = st.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
	}
	if err != nil {
		t.Fatal(err)
	}

	if err = (&Engine{Store: st}).ReconcileRun(ctx, runID); err != nil {
		t.Fatal(err)
	}
	activeNode, err = st.Orchestration().GetNode(ctx, activeNode.ID)
	if err != nil {
		t.Fatal(err)
	}
	if activeNode.State != controlmodel.RunNodeCancelled {
		t.Fatalf("active node state=%s", activeNode.State)
	}
	task, err = st.Collaboration().GetAgentTask(ctx, task.ID)
	if err != nil {
		t.Fatal(err)
	}
	if task.Status != controlmodel.AgentTaskCancelled {
		t.Fatalf("active sibling task state=%s", task.Status)
	}
	run, err := st.Orchestration().GetRun(ctx, runID)
	if err != nil {
		t.Fatal(err)
	}
	if run.State != controlmodel.RunFailed || run.FailureCode != failedNode.FailureCode {
		t.Fatalf("run=%+v", run)
	}
}

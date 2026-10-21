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
	"encoding/json"
	"fmt"
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
)

func TestTimedOutNodeWaitsUntilEveryTaskBatchIsDrained(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			st := cancellationStore(t, driver)
			ctx := t.Context()
			actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
			issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
				Tenant: "t", Namespace: "n", Title: "timeout batches", Creator: actor})
			if err != nil {
				t.Fatal(err)
			}
			run, err := st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{
				Tenant: "t", Namespace: "n", RootIssueID: issue.ID, Mode: controlmodel.RunModeDirect,
				State: controlmodel.RunRunning, CreatedBy: actor})
			if err != nil {
				t.Fatal(err)
			}
			node, err := st.Orchestration().CreateNode(ctx, &controlmodel.RunNode{
				RunID: run.ID, Tenant: "t", Namespace: "n", NodeKey: "timed-out", Type: controlmodel.RunNodeAgent,
				State: controlmodel.RunNodeWaiting, IssueID: &issue.ID, Config: json.RawMessage(`{"timeoutSeconds":1}`)})
			if err != nil {
				t.Fatal(err)
			}
			for i := 0; i < 40; i++ {
				if _, err = st.Collaboration().CreateRunAgentTask(ctx, store.RunTaskRequest{
					RunID: run.ID, NodeID: node.ID, IssueID: issue.ID,
					AgentRef: fmt.Sprintf("worker-%d", i), Originator: actor}); err != nil {
					t.Fatal(err)
				}
			}
			past := time.Now().Add(-time.Minute)
			for i := 0; i < 3; i++ {
				node.StartedAt = &past
				changed, err := (&Engine{Store: st}).sweepWaiting(ctx, run, []*controlmodel.RunNode{node})
				if err != nil || changed != (i == 2) {
					t.Fatalf("timeout pass %d: changed=%v error=%v", i, changed, err)
				}
				remaining, err := st.Collaboration().ListAgentTasks(ctx,
					store.AgentTaskFilter{NodeID: node.ID, ActiveOnly: true, Limit: 100})
				expected := 40 - (i+1)*cancellationWorkBatch
				if expected < 0 {
					expected = 0
				}
				if err != nil || len(remaining) != expected {
					t.Fatalf("pass %d remaining=%d error=%v", i, len(remaining), err)
				}
				node, err = st.Orchestration().GetNode(ctx, node.ID)
				if err != nil || i < 2 && node.State != controlmodel.RunNodeWaiting {
					t.Fatalf("timeout became terminal before drain=%v %v", node, err)
				}
			}
			if node.State != controlmodel.RunNodeFailed || node.FailureCode != "timeout" {
				t.Fatalf("timeout did not conclude after drain=%v", node)
			}
		})
	}
}

func TestFailFastDrainsFiniteBatchesAcrossCoordinatorReplacement(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			st := cancellationStore(t, driver)
			ctx := t.Context()
			actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
			issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
				Tenant: "t", Namespace: "n", Title: "fail-fast batches", Creator: actor})
			if err != nil {
				t.Fatal(err)
			}
			run, err := st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{
				Tenant: "t", Namespace: "n", RootIssueID: issue.ID, Mode: controlmodel.RunModeDirect,
				State: controlmodel.RunRunning, CreatedBy: actor})
			if err != nil {
				t.Fatal(err)
			}
			failed, err := st.Orchestration().CreateNode(ctx, &controlmodel.RunNode{
				RunID: run.ID, Tenant: "t", Namespace: "n", NodeKey: "failed", Type: controlmodel.RunNodeAgent,
				State: controlmodel.RunNodeFailed, FailureCode: "worker_failed", FailureMessage: "original failure"})
			if err != nil {
				t.Fatal(err)
			}
			nodes := []*controlmodel.RunNode{}
			for i := 1; i <= 25; i++ {
				node, err := st.Orchestration().CreateNode(ctx, &controlmodel.RunNode{
					ID:    uuid.MustParse(fmt.Sprintf("00000000-0000-0000-0000-%012x", i)),
					RunID: run.ID, Tenant: "t", Namespace: "n", NodeKey: fmt.Sprintf("waiting-%d", i),
					Type: controlmodel.RunNodeSignal, State: controlmodel.RunNodeWaiting})
				if err != nil {
					t.Fatal(err)
				}
				nodes = append(nodes, node)
			}
			child, err := st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{
				Tenant: "t", Namespace: "n", RootIssueID: issue.ID, ParentRunID: &run.ID,
				ParentNodeID: &nodes[0].ID, Mode: controlmodel.RunModeSubrun,
				State: controlmodel.RunRunning, CreatedBy: actor})
			if err != nil {
				t.Fatal(err)
			}
			if _, err = st.Orchestration().CreateNode(ctx, &controlmodel.RunNode{
				RunID: child.ID, Tenant: "t", Namespace: "n", NodeKey: "child-wait",
				Type: controlmodel.RunNodeSignal, State: controlmodel.RunNodeWaiting}); err != nil {
				t.Fatal(err)
			}
			if err = (&Engine{Store: st}).ReconcileRun(ctx, run.ID); err != nil {
				t.Fatal(err)
			}
			run, err = st.Orchestration().GetRun(ctx, run.ID)
			if err != nil || run.State != controlmodel.RunCancelling || run.FailureCode != "worker_failed" {
				t.Fatalf("failure drain intent=%v %v", run, err)
			}
			cancelled := 0
			for _, node := range nodes {
				current, err := st.Orchestration().GetNode(ctx, node.ID)
				if err != nil {
					t.Fatal(err)
				}
				if current.State == controlmodel.RunNodeCancelled {
					cancelled++
				}
			}
			if cancelled != cancellationNodeBatch-1 {
				t.Fatalf("first failure pass cancelled %d nodes", cancelled)
			}
			// A fresh coordinator advances the saved cursor past a still-waiting
			// child; it does not restart cleanup at the first sibling.
			if err = (&Engine{Store: st}).ReconcileRun(ctx, run.ID); err != nil {
				t.Fatal(err)
			}
			ninth, _ := st.Orchestration().GetNode(ctx, nodes[8].ID)
			if ninth.State != controlmodel.RunNodeCancelled {
				t.Fatal("a child waiting for its own worker starved later siblings")
			}
			for i := 0; i < 12; i++ {
				if err = (&Engine{Store: st}).ReconcileRun(ctx, child.ID); err != nil {
					t.Fatal(err)
				}
				if err = (&Engine{Store: st}).ReconcileRun(ctx, run.ID); err != nil {
					t.Fatal(err)
				}
			}
			run, err = st.Orchestration().GetRun(ctx, run.ID)
			if err != nil || run.State != controlmodel.RunFailed || run.FailureCode != "worker_failed" || run.FailureMessage != "original failure" {
				t.Fatalf("failure lost across drain=%v %v", run, err)
			}
			child, _ = st.Orchestration().GetRun(ctx, child.ID)
			failed, _ = st.Orchestration().GetNode(ctx, failed.ID)
			if child.State != controlmodel.RunCancelled || failed.State != controlmodel.RunNodeFailed {
				t.Fatalf("cleanup overwrote the failure or did not stop the child: %s %s", failed.State, child.State)
			}
			events, err := st.Orchestration().ListRunEvents(ctx, run.ID, 0, 100)
			if err != nil {
				t.Fatal(err)
			}
			failures := 0
			for _, event := range events {
				if event.Type == "run.failed" {
					failures++
				}
			}
			if failures != 1 {
				t.Fatalf("terminal failure event count=%d", failures)
			}
		})
	}
}

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

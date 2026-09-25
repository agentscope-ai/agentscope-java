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
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestDeclaredSignalRunMaterializesChildIssueAndConverges(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	spec := json.RawMessage(`{"nodes":[{"key":"prepare","type":"condition","issueMode":"child"},{"key":"release","type":"signal","signalName":"ship"},{"key":"done","type":"join","join":{"mode":"all"}}],"edges":[{"from":"prepare","to":"release"},{"from":"release","to":"done"}]}`)
	definition, err := st.Orchestration().CreateDefinition(ctx, &controlmodel.OrchestrationDefinition{
		Tenant: "tenant-a", Namespace: "default", Name: "release", DraftSpec: spec, CreatedBy: actor})
	if err != nil {
		t.Fatal(err)
	}
	service := &Service{Store: st}
	if _, err = service.Publish(ctx, definition.ID, actor); err != nil {
		t.Fatal(err)
	}
	run, err := service.Start(ctx, definition.ID, StartRequest{IdempotencyKey: "release-1", Input: json.RawMessage(`{"enabled":true}`),
		Issue: &controlmodel.Issue{Title: "Release v4", Priority: "high", Creator: actor}, Actor: actor})
	if err != nil {
		t.Fatal(err)
	}
	graph, err := service.Graph(ctx, run.ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(graph.Nodes) != 3 {
		t.Fatalf("nodes=%d", len(graph.Nodes))
	}
	var childID string
	for _, node := range graph.Nodes {
		if node.NodeKey == "prepare" && node.IssueID != nil {
			childID = node.IssueID.String()
		}
	}
	if childID == "" || childID == run.RootIssueID.String() {
		t.Fatal("issueMode=child did not materialize an isolated child Issue")
	}
	if err = service.Signal(ctx, run.ID, "ship", "signal-1", json.RawMessage(`{"approved":true}`), actor); err != nil {
		t.Fatal(err)
	}
	finished, err := st.Orchestration().GetRun(ctx, run.ID)
	if err != nil || finished.State != controlmodel.RunSucceeded {
		t.Fatalf("run did not converge: %+v err=%v", finished, err)
	}
	root, _ := st.Collaboration().GetIssue(ctx, run.RootIssueID)
	if root.Status == controlmodel.IssueDone {
		t.Fatal("terminal Run completed its root Issue")
	}
	// Duplicate signal is idempotent and cannot append a second event.
	if err = service.Signal(ctx, run.ID, "ship", "signal-1", json.RawMessage(`{"approved":true}`), actor); err != nil {
		t.Fatal(err)
	}
	rerun, err := service.Rerun(ctx, run.ID, "release-rerun-1", nil, actor)
	if err != nil {
		t.Fatal(err)
	}
	if rerun.ID == run.ID || rerun.RerunOfRunID == nil || *rerun.RerunOfRunID != run.ID ||
		rerun.DefinitionRevisionID == nil || *rerun.DefinitionRevisionID != *run.DefinitionRevisionID ||
		rerun.State != controlmodel.RunWaiting {
		t.Fatalf("rerun did not preserve lineage/revision: source=%+v rerun=%+v", run, rerun)
	}
}

func TestDeclaredApprovalTimerSubrunAndJoinConverge(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	service := &Service{Store: st}
	childSpec, _ := json.Marshal(DefinitionSpec{Nodes: []DefinitionNode{{Key: "child-done", Type: controlmodel.RunNodeCondition}}})
	child, err := st.Orchestration().CreateDefinition(ctx, &controlmodel.OrchestrationDefinition{
		Tenant: "gates", Namespace: "default", Name: "child", DraftSpec: childSpec, CreatedBy: actor})
	if err != nil {
		t.Fatal(err)
	}
	childRevision, err := service.Publish(ctx, child.ID, actor)
	if err != nil {
		t.Fatal(err)
	}
	past := time.Now().UTC().Add(-time.Second)
	parentSpec, _ := json.Marshal(DefinitionSpec{Nodes: []DefinitionNode{
		{Key: "timer", Type: controlmodel.RunNodeTimer, Timer: TimerSpec{At: &past}},
		{Key: "approval", Type: controlmodel.RunNodeApproval, Approval: ApprovalSpec{ApproverRef: "reviewer", Prompt: "ship?"}},
		{Key: "subrun", Type: controlmodel.RunNodeSubrun, DefinitionRevID: childRevision.ID.String()},
		{Key: "join", Type: controlmodel.RunNodeJoin, Join: JoinSpec{Mode: "all"}},
	}, Edges: []DefinitionEdge{{From: "timer", To: "approval"}, {From: "approval", To: "subrun"}, {From: "subrun", To: "join"}}})
	parent, err := st.Orchestration().CreateDefinition(ctx, &controlmodel.OrchestrationDefinition{
		Tenant: "gates", Namespace: "default", Name: "parent", DraftSpec: parentSpec, CreatedBy: actor})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = service.Publish(ctx, parent.ID, actor); err != nil {
		t.Fatal(err)
	}
	run, err := service.Start(ctx, parent.ID, StartRequest{IdempotencyKey: "gates-1",
		Issue: &controlmodel.Issue{Title: "gated release", Creator: actor}, Actor: actor})
	if err != nil {
		t.Fatal(err)
	}
	approvals, err := st.Collaboration().ListApprovals(ctx, store.ApprovalFilter{Tenant: "gates",
		Namespace: "default", ApproverRef: "reviewer", Limit: 10})
	if err != nil || len(approvals) != 1 || approvals[0].Status != controlmodel.ApprovalPending {
		t.Fatalf("approval gate not materialized: approvals=%+v err=%v", approvals, err)
	}
	if _, err = st.Collaboration().DecideApproval(ctx, approvals[0].ID, approvals[0].Version,
		controlmodel.ApprovalApproved, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "reviewer"},
		json.RawMessage(`{"approved":true}`)); err != nil {
		t.Fatal(err)
	}
	if err = (&Engine{Store: st}).ReconcileRun(ctx, run.ID); err != nil {
		t.Fatal(err)
	}
	finished, err := st.Orchestration().GetRun(ctx, run.ID)
	if err != nil || finished.State != controlmodel.RunSucceeded {
		t.Fatalf("gated parent did not converge: run=%+v err=%v", finished, err)
	}
	runs, err := st.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{Tenant: "gates", Namespace: "default", Limit: 10})
	if err != nil {
		t.Fatal(err)
	}
	foundSubrun := false
	for _, candidate := range runs {
		if candidate.ParentRunID != nil && *candidate.ParentRunID == run.ID && candidate.Mode == controlmodel.RunModeSubrun {
			foundSubrun = candidate.State == controlmodel.RunSucceeded
		}
	}
	if !foundSubrun {
		t.Fatal("terminal subrun lineage was not persisted")
	}
}

func TestFailurePoliciesFailFastContinueAndPartialSuccess(t *testing.T) {
	for _, test := range []struct {
		name, policy string
		want         controlmodel.OrchestrationRunState
	}{
		{name: "default-fail-fast", want: controlmodel.RunFailed},
		{name: "continue", policy: "continue", want: controlmodel.RunFailed},
		{name: "partial", policy: "partial_success", want: controlmodel.RunPartialSucceeded},
	} {
		t.Run(test.name, func(t *testing.T) {
			ctx := context.Background()
			st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = st.Close() })
			actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
			spec, _ := json.Marshal(DefinitionSpec{Nodes: []DefinitionNode{
				{Key: "success", Type: controlmodel.RunNodeCondition},
				{Key: "failure", Type: controlmodel.RunNodeAgent, AgentID: uuid.NewString(), FailurePolicy: test.policy},
			}})
			definition, err := st.Orchestration().CreateDefinition(ctx, &controlmodel.OrchestrationDefinition{
				Tenant: "failures", Namespace: "default", Name: test.name, DraftSpec: spec, CreatedBy: actor})
			if err != nil {
				t.Fatal(err)
			}
			service := &Service{Store: st}
			if _, err = service.Publish(ctx, definition.ID, actor); err != nil {
				t.Fatal(err)
			}
			run, err := service.Start(ctx, definition.ID, StartRequest{IdempotencyKey: "run-" + test.name,
				Issue: &controlmodel.Issue{Title: test.name, Creator: actor}, Actor: actor})
			if err != nil {
				t.Fatal(err)
			}
			tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{RunID: run.ID, Limit: 10})
			if err != nil || len(tasks) != 1 {
				t.Fatalf("agent node task: %+v %v", tasks, err)
			}
			if _, err = st.Collaboration().FailAgentTask(ctx, tasks[0].ID, tasks[0].Version, "business", "rejected"); err != nil {
				t.Fatal(err)
			}
			if err = (&Engine{Store: st}).ReconcileRun(ctx, run.ID); err != nil {
				t.Fatal(err)
			}
			finished, err := st.Orchestration().GetRun(ctx, run.ID)
			if err != nil || finished.State != test.want {
				t.Fatalf("failure policy %q: run=%+v err=%v", test.policy, finished, err)
			}
		})
	}
}

func TestPauseResumeAndCancelPreserveIssueState(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	spec := json.RawMessage(`{"nodes":[{"key":"gate","type":"signal","signalName":"continue"}]}`)
	definition, err := st.Orchestration().CreateDefinition(ctx, &controlmodel.OrchestrationDefinition{
		Tenant: "controls", Namespace: "default", Name: "controls", DraftSpec: spec, CreatedBy: actor})
	if err != nil {
		t.Fatal(err)
	}
	service := &Service{Store: st}
	if _, err = service.Publish(ctx, definition.ID, actor); err != nil {
		t.Fatal(err)
	}
	pausedRun, err := service.Start(ctx, definition.ID, StartRequest{IdempotencyKey: "paused",
		Issue: &controlmodel.Issue{Title: "pause", Creator: actor}, Actor: actor})
	if err != nil {
		t.Fatal(err)
	}
	pausedRun, err = service.Pause(ctx, pausedRun.ID)
	if err != nil || pausedRun.State != controlmodel.RunPaused {
		t.Fatalf("pause: run=%+v err=%v", pausedRun, err)
	}
	if err = service.Signal(ctx, pausedRun.ID, "continue", "while-paused", json.RawMessage(`{"saved":true}`), actor); err != nil {
		t.Fatal(err)
	}
	stillPaused, _ := st.Orchestration().GetRun(ctx, pausedRun.ID)
	if stillPaused.State != controlmodel.RunPaused {
		t.Fatalf("signal should save output without resuming Run: %+v", stillPaused)
	}
	if _, err = service.Resume(ctx, pausedRun.ID); err != nil {
		t.Fatal(err)
	}
	resumed, _ := st.Orchestration().GetRun(ctx, pausedRun.ID)
	if resumed.State != controlmodel.RunSucceeded {
		t.Fatalf("resumed Run did not converge: %+v", resumed)
	}

	cancelledRun, err := service.Start(ctx, definition.ID, StartRequest{IdempotencyKey: "cancelled",
		Issue: &controlmodel.Issue{Title: "cancel", Creator: actor}, Actor: actor})
	if err != nil {
		t.Fatal(err)
	}
	cancelledRun, err = service.Cancel(ctx, cancelledRun.ID)
	if err != nil || cancelledRun.State != controlmodel.RunCancelled {
		t.Fatalf("cancel: run=%+v err=%v", cancelledRun, err)
	}
	nodes, _ := st.Orchestration().ListNodes(ctx, cancelledRun.ID)
	if len(nodes) != 1 || nodes[0].State != controlmodel.RunNodeCancelled {
		t.Fatalf("cancel did not cascade to node: %+v", nodes)
	}
	issue, _ := st.Collaboration().GetIssue(ctx, cancelledRun.RootIssueID)
	if issue.Status == controlmodel.IssueCancelled || issue.Status == controlmodel.IssueDone {
		t.Fatalf("Run cancellation changed Issue truth: %+v", issue)
	}
}

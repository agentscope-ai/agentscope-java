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

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func TestAdaptiveWorkerCapabilityFailureBlocksChildAndWakesLeader(t *testing.T) {
	ctx := context.Background()
	st, svc, engine, root, leader, child, worker := setupTeamFailureRun(t, 3)

	worker = startTask(t, st, worker)
	if _, err := svc.FailTask(ctx, worker.ID, worker.Version, "API_KEY_MISSING",
		"TAVILY_API_KEY is not configured"); err != nil {
		t.Fatal(err)
	}
	if err := engine.ReconcileRun(ctx, leader.OrchestrationRunID); err != nil {
		t.Fatal(err)
	}

	child, err := st.Collaboration().GetIssue(ctx, child.ID)
	if err != nil || child.Status != controlmodel.IssueBlocked {
		t.Fatalf("failed worker did not block child Issue: issue=%+v err=%v", child, err)
	}
	workerNode, err := st.Orchestration().GetNode(ctx, worker.RunNodeID)
	if err != nil || workerNode.State != controlmodel.RunNodeFailed {
		t.Fatalf("worker node did not retain physical failure: node=%+v err=%v", workerNode, err)
	}
	run, err := st.Orchestration().GetRun(ctx, leader.OrchestrationRunID)
	if err != nil || controlmodel.IsOrchestrationRunTerminal(run.State) {
		t.Fatalf("adaptive Run terminated before leader decision: run=%+v err=%v", run, err)
	}
	comments, err := st.Collaboration().ListComments(ctx, child.ID, store.CommentListOptions{Limit: 20})
	if err != nil || len(comments) != 1 || comments[0].Type != controlmodel.CommentStatus ||
		comments[0].SourceTaskID == nil || *comments[0].SourceTaskID != worker.ID {
		t.Fatalf("worker failure was not recorded as a leader-readable outcome: comments=%+v err=%v", comments, err)
	}
	followUps, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{
		Tenant: root.Tenant, Namespace: root.Namespace, RunID: run.ID, AgentRef: "leader", Limit: 20,
	})
	if err != nil {
		t.Fatal(err)
	}
	var decisionTask *controlmodel.AgentTask
	for _, followUp := range followUps {
		if followUp.TriggerCommentID != nil && *followUp.TriggerCommentID == comments[0].ID &&
			followUp.LeaderTask && followUp.Status == controlmodel.AgentTaskQueued {
			decisionTask = followUp
		}
	}
	if decisionTask == nil {
		t.Fatalf("blocked worker outcome did not wake Team leader: tasks=%+v", followUps)
	}

	decisionTask = startTask(t, st, decisionTask)
	if _, err = svc.CancelBlockedIssueFromTask(ctx, decisionTask.ID,
		"continue with the available partial result"); err != nil {
		t.Fatal(err)
	}
	decisionTask, _, err = svc.CompleteTask(ctx, decisionTask.ID, store.TaskCompletion{
		ExpectedVersion: decisionTask.Version, Summary: "partial result accepted",
	}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"})
	if err != nil {
		t.Fatal(err)
	}
	root, err = st.Collaboration().GetIssue(ctx, root.ID)
	if err == nil {
		root, err = st.Collaboration().TransitionIssue(ctx, root.ID, root.Version, controlmodel.IssueBlocked, root.Creator, "waiting for a human decision")
	}
	if err != nil {
		t.Fatal(err)
	}
	if _, err = (&Service{Store: st}).CompleteCoordinatorNode(ctx, decisionTask.ID,
		[]byte(`{"summary":"partial result"}`), controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	run, err = st.Orchestration().GetRun(ctx, run.ID)
	if err != nil || run.State != controlmodel.RunPartialSucceeded {
		t.Fatalf("leader partial-success decision did not converge Run: run=%+v err=%v", run, err)
	}
	root, err = st.Collaboration().GetIssue(ctx, root.ID)
	if err != nil || root.Status != controlmodel.IssueInReview {
		t.Fatalf("resolved child left root blocked: root=%+v err=%v", root, err)
	}
}

func TestAdaptiveWorkerRetriesTransientFailureBeforeLeaderHandoff(t *testing.T) {
	ctx := context.Background()
	st, svc, engine, _, leader, child, worker := setupTeamFailureRun(t, 1)

	worker = startTask(t, st, worker)
	if _, err := svc.FailTask(ctx, worker.ID, worker.Version, "provider_unavailable", "temporary outage"); err != nil {
		t.Fatal(err)
	}
	if err := engine.ReconcileRun(ctx, leader.OrchestrationRunID); err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{NodeID: worker.RunNodeID, Limit: 20})
	if err != nil || len(tasks) != 2 || tasks[1].Status != controlmodel.AgentTaskQueued {
		t.Fatalf("transient worker failure did not consume configured retry: tasks=%+v err=%v", tasks, err)
	}
	currentChild, err := st.Collaboration().GetIssue(ctx, child.ID)
	if err != nil || currentChild.Status != controlmodel.IssueInProgress {
		t.Fatalf("child was blocked before retry budget exhausted: issue=%+v err=%v", currentChild, err)
	}

	retry := startTask(t, st, tasks[1])
	if _, err = svc.FailTask(ctx, retry.ID, retry.Version, "provider_unavailable", "temporary outage"); err != nil {
		t.Fatal(err)
	}
	if err = engine.ReconcileRun(ctx, leader.OrchestrationRunID); err != nil {
		t.Fatal(err)
	}
	currentChild, err = st.Collaboration().GetIssue(ctx, child.ID)
	if err != nil || currentChild.Status != controlmodel.IssueBlocked {
		t.Fatalf("exhausted worker failure did not reach leader as blocked: issue=%+v err=%v", currentChild, err)
	}
	followUps, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{
		IssueID: child.ID, AgentRef: "leader", Status: controlmodel.AgentTaskQueued, Limit: 20,
	})
	if err != nil || len(followUps) == 0 {
		t.Fatalf("exhausted retry did not create leader decision task: tasks=%+v err=%v", followUps, err)
	}
	decisionTask := startTask(t, st, followUps[len(followUps)-1])
	if _, err = (&Service{Store: st}).Replan(ctx, decisionTask.ID, DefinitionNode{
		Type: controlmodel.RunNodeAgent, AgentID: uuid.NewString(), Role: "fallback",
	}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	currentChild, err = st.Collaboration().GetIssue(ctx, child.ID)
	if err != nil || currentChild.Status != controlmodel.IssueInProgress {
		t.Fatalf("leader replan did not reopen blocked child: issue=%+v err=%v", currentChild, err)
	}
}

func TestFailedRunBlocksOpenRootAndChildIssues(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	svc := &collaboration.Service{Store: st}
	root, task, err := svc.CreateIssue(ctx, collaboration.CreateIssueRequest{
		Tenant: "tenant", Namespace: "default", Title: "work that cannot run",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker",
	})
	if err != nil {
		t.Fatal(err)
	}
	child, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: root.Tenant, Namespace: root.Namespace, Title: "unfinished child",
		Status: controlmodel.IssueInProgress, ParentIssueID: &root.ID,
		Creator: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "worker"},
	})
	if err != nil {
		t.Fatal(err)
	}
	engine := &Engine{Store: st}
	if err = engine.ReconcileRun(ctx, task.OrchestrationRunID); err != nil {
		t.Fatal(err)
	}
	task = startTask(t, st, task)
	if _, err = svc.FailTask(ctx, task.ID, task.Version, "invalid_configuration", "missing capability"); err != nil {
		t.Fatal(err)
	}
	if err = engine.ReconcileRun(ctx, task.OrchestrationRunID); err != nil {
		t.Fatal(err)
	}
	run, _ := st.Orchestration().GetRun(ctx, task.OrchestrationRunID)
	root, _ = st.Collaboration().GetIssue(ctx, root.ID)
	child, _ = st.Collaboration().GetIssue(ctx, child.ID)
	if run.State != controlmodel.RunFailed || root.Status != controlmodel.IssueBlocked ||
		child.Status != controlmodel.IssueBlocked {
		t.Fatalf("failed Run left Issue tree active: run=%+v root=%+v child=%+v", run, root, child)
	}
}

func setupTeamFailureRun(t *testing.T, maxRetries int32) (store.Store, *collaboration.Service,
	*Engine, *controlmodel.Issue, *controlmodel.AgentTask, *controlmodel.Issue, *controlmodel.AgentTask) {
	t.Helper()
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant", Namespace: "default", Name: "research", LeaderAgentRef: "leader",
		Policy: controlmodel.TeamPolicy{MaxTaskRetries: maxRetries, MaxChildDepth: 4, MaxChildIssues: 4},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.Collaboration().AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{
		TeamID: team.ID, AgentRef: "worker", Role: "researcher",
	}); err != nil {
		t.Fatal(err)
	}
	svc := &collaboration.Service{Store: st}
	root, leader, err := svc.CreateIssue(ctx, collaboration.CreateIssueRequest{
		Tenant: "tenant", Namespace: "default", Title: "research two markets",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String(),
	})
	if err != nil {
		t.Fatal(err)
	}
	engine := &Engine{Store: st}
	if err = engine.ReconcileRun(ctx, leader.OrchestrationRunID); err != nil {
		t.Fatal(err)
	}
	leader = startTask(t, st, leader)
	child, worker, err := svc.CreateChildFromTask(ctx, leader.ID, collaboration.CreateIssueRequest{
		Title: "research energy", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker",
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = svc.CompleteTask(ctx, leader.ID, store.TaskCompletion{
		ExpectedVersion: leader.Version, Summary: "delegated",
	}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	if err = engine.ReconcileRun(ctx, leader.OrchestrationRunID); err != nil {
		t.Fatal(err)
	}
	return st, svc, engine, root, leader, child, worker
}

func startTask(t *testing.T, st store.Store, task *controlmodel.AgentTask) *controlmodel.AgentTask {
	t.Helper()
	claimed, err := st.Collaboration().ClaimAgentTask(context.Background(), store.TaskClaim{
		TaskID: task.ID, ExpectedVersion: task.Version, SessionID: "session-" + uuid.NewString(),
	})
	if err != nil {
		t.Fatal(err)
	}
	running, err := st.Collaboration().StartAgentTask(context.Background(), claimed.ID, claimed.Version)
	if err != nil {
		t.Fatal(err)
	}
	return running
}

package orchestration

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestEngineKeepsDynamicAdaptiveNodeWaiting(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant-a", Namespace: "ns-a", Name: "operators", LeaderAgentRef: "leader",
	})
	if err != nil {
		t.Fatal(err)
	}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "ns-a", Title: "dynamic work",
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String(),
		Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
	})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 10})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("dynamic task: tasks=%+v err=%v", tasks, err)
	}
	if err = (&Engine{Store: st}).ReconcileRun(ctx, tasks[0].OrchestrationRunID); err != nil {
		t.Fatal(err)
	}
	run, err := st.Orchestration().GetRun(ctx, tasks[0].OrchestrationRunID)
	if err != nil || run.State != controlmodel.RunWaiting {
		t.Fatalf("adaptive Run should wait for its coordinator: run=%+v err=%v", run, err)
	}
	node, err := st.Orchestration().GetNode(ctx, tasks[0].RunNodeID)
	if err != nil || node.State != controlmodel.RunNodeWaiting || node.WaitReason != "team_coordinator" {
		t.Fatalf("dynamic coordinator node should wait: node=%+v err=%v", node, err)
	}
}

func TestSuccessfulTeamLeaderRequiresExplicitCoordinatorCompletion(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant-a", Namespace: "ns-a", Name: "operators", LeaderAgentRef: "leader",
	})
	if err != nil {
		t.Fatal(err)
	}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "ns-a", Title: "simple team answer",
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String(),
		Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
	})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 10})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("leader task: tasks=%+v err=%v", tasks, err)
	}
	task, err := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version})
	if err == nil {
		task, err = st.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	result := json.RawMessage(`{"output":"hello"}`)
	if _, _, err = (&collaboration.Service{Store: st}).CompleteTask(ctx, task.ID,
		store.TaskCompletion{ExpectedVersion: task.Version, Result: result, Summary: "hello"},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	engine := &Engine{Store: st}
	if err = engine.ReconcileRun(ctx, task.OrchestrationRunID); err != nil {
		t.Fatal(err)
	}
	node, err := st.Orchestration().GetNode(ctx, task.RunNodeID)
	if err != nil || node.State != controlmodel.RunNodeWaiting {
		t.Fatalf("coordinator should require explicit completion: node=%+v err=%v", node, err)
	}
	if _, err = (&Service{Store: st}).CompleteCoordinatorNode(ctx, task.ID, result,
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	run, err := st.Orchestration().GetRun(ctx, task.OrchestrationRunID)
	if err != nil || run.State != controlmodel.RunSucceeded {
		t.Fatalf("Team Run did not complete: run=%+v err=%v", run, err)
	}
}

func TestSuccessfulTeamLeaderKeepsCoordinatorWaitingForOpenChild(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant-a", Namespace: "ns-a", Name: "delegators", LeaderAgentRef: "leader",
	})
	if err != nil {
		t.Fatal(err)
	}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "ns-a", Title: "delegated team work",
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String(),
		Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "ns-a", Title: "unfinished child", ParentIssueID: &issue.ID,
		Creator: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"},
	}); err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 10})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("leader task: tasks=%+v err=%v", tasks, err)
	}
	task, err := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version})
	if err == nil {
		task, err = st.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = (&collaboration.Service{Store: st}).CompleteTask(ctx, task.ID,
		store.TaskCompletion{ExpectedVersion: task.Version, Summary: "delegated"},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	if err = (&Engine{Store: st}).ReconcileRun(ctx, task.OrchestrationRunID); err != nil {
		t.Fatal(err)
	}
	node, err := st.Orchestration().GetNode(ctx, task.RunNodeID)
	if err != nil || node.State != controlmodel.RunNodeWaiting {
		t.Fatalf("coordinator should wait for open child: node=%+v err=%v", node, err)
	}
}

func TestTaskCompletionPreservesExplicitCoordinatorCompletion(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant-a", Namespace: "ns-a", Name: "explicit", LeaderAgentRef: "leader",
	})
	if err != nil {
		t.Fatal(err)
	}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "ns-a", Title: "explicit completion",
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String(),
		Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
	})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 10})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("leader task: tasks=%+v err=%v", tasks, err)
	}
	engine := &Engine{Store: st}
	if err = engine.ReconcileRun(ctx, tasks[0].OrchestrationRunID); err != nil {
		t.Fatal(err)
	}
	task, err := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version})
	if err == nil {
		task, err = st.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	result := json.RawMessage(`{"output":"explicit"}`)
	if _, err = (&Service{Store: st}).CompleteCoordinatorNode(ctx, task.ID, result,
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	if _, _, err = (&collaboration.Service{Store: st}).CompleteTask(ctx, task.ID,
		store.TaskCompletion{ExpectedVersion: task.Version, Result: result, Summary: "explicit"},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatalf("physical task completion overwrote explicit coordinator state: %v", err)
	}
	node, err := st.Orchestration().GetNode(ctx, task.RunNodeID)
	if err != nil || node.State != controlmodel.RunNodeSucceeded {
		t.Fatalf("explicit coordinator state was lost: node=%+v err=%v", node, err)
	}
}

func TestSuccessfulTopLevelRunMovesInProgressIssueToReview(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	actor := controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "endpoint:test"}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "ns-a", Title: "endpoint job",
		Status: controlmodel.IssueInProgress, Creator: actor,
	})
	if err != nil {
		t.Fatal(err)
	}
	run, err := st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{
		Tenant: "tenant-a", Namespace: "ns-a", RootIssueID: issue.ID,
		Mode: controlmodel.RunModeDirect, State: controlmodel.RunRunning, CreatedBy: actor,
	})
	if err != nil {
		t.Fatal(err)
	}
	issueID := issue.ID
	if _, err = st.Orchestration().CreateNode(ctx, &controlmodel.RunNode{
		ID: uuid.New(), RunID: run.ID, Tenant: run.Tenant, Namespace: run.Namespace,
		NodeKey: "agent", Type: controlmodel.RunNodeAgent, IssueID: &issueID,
		State: controlmodel.RunNodeSucceeded,
	}); err != nil {
		t.Fatal(err)
	}
	engine := &Engine{Store: st}
	if err = engine.ReconcileRun(ctx, run.ID); err != nil {
		t.Fatal(err)
	}
	finished, err := st.Orchestration().GetRun(ctx, run.ID)
	if err != nil || finished.State != controlmodel.RunSucceeded {
		t.Fatalf("Run did not succeed: run=%+v err=%v", finished, err)
	}
	review, err := st.Collaboration().GetIssue(ctx, issue.ID)
	if err != nil || review.Status != controlmodel.IssueInReview {
		t.Fatalf("successful Run did not request Issue review: issue=%+v err=%v", review, err)
	}
	if err = engine.ReconcileRun(ctx, run.ID); err != nil {
		t.Fatalf("terminal reconciliation was not idempotent: %v", err)
	}
}

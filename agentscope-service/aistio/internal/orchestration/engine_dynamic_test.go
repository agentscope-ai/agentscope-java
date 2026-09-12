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
	run, err := st.Orchestration().GetRun(ctx, task.OrchestrationRunID)
	if err != nil || run.State != controlmodel.RunWaiting {
		t.Fatalf("Team Run completed without explicit action: run=%+v err=%v", run, err)
	}
	if _, err = (&Service{Store: st}).CompleteCoordinatorNode(ctx, task.ID, result,
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	run, err = st.Orchestration().GetRun(ctx, task.OrchestrationRunID)
	if err != nil || run.State != controlmodel.RunSucceeded {
		t.Fatalf("Team Run did not complete after explicit action: run=%+v err=%v", run, err)
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
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err == nil {
		t.Fatal("active leader task was allowed to expose a successful coordinator")
	}
	task, _, err = (&collaboration.Service{Store: st}).CompleteTask(ctx, task.ID,
		store.TaskCompletion{ExpectedVersion: task.Version, Result: result, Summary: "explicit"},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"})
	if err != nil {
		t.Fatalf("complete leader task: %v", err)
	}
	if _, err = (&Service{Store: st}).CompleteCoordinatorNode(ctx, task.ID, result,
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatalf("completed leader could not converge coordinator: %v", err)
	}
	node, err := st.Orchestration().GetNode(ctx, task.RunNodeID)
	if err != nil || node.State != controlmodel.RunNodeSucceeded {
		t.Fatalf("explicit coordinator state was lost: node=%+v err=%v", node, err)
	}
}

func TestLeaderFollowUpConvergesOriginalCoordinatorAfterDelegation(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	collaborationService := &collaboration.Service{Store: st}
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant", Namespace: "default", Name: "delivery", LeaderAgentRef: "leader",
		Policy: controlmodel.TeamPolicy{MaxChildDepth: 4, MaxChildIssues: 4},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.Collaboration().AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{
		TeamID: team.ID, AgentRef: "worker", Role: "worker",
	}); err != nil {
		t.Fatal(err)
	}
	_, leader, err := collaborationService.CreateIssue(ctx, collaboration.CreateIssueRequest{
		Tenant: "tenant", Namespace: "default", Title: "deliver",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String(),
	})
	if err != nil {
		t.Fatal(err)
	}
	leader, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: leader.ID, ExpectedVersion: leader.Version})
	if err == nil {
		leader, err = st.Collaboration().StartAgentTask(ctx, leader.ID, leader.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	child, worker, err := collaborationService.CreateChildFromTask(ctx, leader.ID,
		collaboration.CreateIssueRequest{Title: "implement", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker"})
	if err != nil {
		t.Fatal(err)
	}
	workerNode, err := st.Orchestration().GetNode(ctx, worker.RunNodeID)
	if err != nil || workerNode.Type != controlmodel.RunNodeAgent {
		t.Fatalf("delegated Team worker must use an agent node: node=%+v err=%v", workerNode, err)
	}
	childRuns, err := st.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{
		Tenant: "tenant", Namespace: "default", IssueID: child.ID, Limit: 10,
	})
	if err != nil || len(childRuns) != 1 || childRuns[0].ID != leader.OrchestrationRunID {
		t.Fatalf("child Issue did not resolve its shared Run: runs=%+v err=%v", childRuns, err)
	}
	leader, _, err = collaborationService.CompleteTask(ctx, leader.ID,
		store.TaskCompletion{ExpectedVersion: leader.Version, Summary: "delegated"},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"})
	if err != nil {
		t.Fatal(err)
	}
	worker, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: worker.ID, ExpectedVersion: worker.Version})
	if err == nil {
		worker, err = st.Collaboration().StartAgentTask(ctx, worker.ID, worker.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	_, result, err := collaborationService.CompleteTask(ctx, worker.ID,
		store.TaskCompletion{ExpectedVersion: worker.Version, Summary: "implemented"},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "worker"})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{
		IssueID: child.ID, AgentRef: "leader", Limit: 10,
	})
	if err != nil {
		t.Fatal(err)
	}
	var followUp *controlmodel.AgentTask
	for _, task := range tasks {
		if task.TriggerCommentID != nil && *task.TriggerCommentID == result.ID {
			followUp = task
		}
	}
	if followUp == nil || !followUp.LeaderTask || followUp.RunNodeID != leader.RunNodeID {
		t.Fatalf("invalid coordinator follow-up: %+v", tasks)
	}
	followUp, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: followUp.ID, ExpectedVersion: followUp.Version})
	if err == nil {
		followUp, err = st.Collaboration().StartAgentTask(ctx, followUp.ID, followUp.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	if _, err = collaborationService.AcceptIssueFromTask(ctx, followUp.ID, "verified"); err != nil {
		t.Fatal(err)
	}
	output := json.RawMessage(`{"summary":"converged"}`)
	followUp, _, err = collaborationService.CompleteTask(ctx, followUp.ID,
		store.TaskCompletion{ExpectedVersion: followUp.Version, Result: output, Summary: "converged"},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = (&Service{Store: st}).CompleteCoordinatorNode(ctx, followUp.ID, output,
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	node, err := st.Orchestration().GetNode(ctx, leader.RunNodeID)
	if err != nil || node.State != controlmodel.RunNodeSucceeded || node.WaitReason != "" {
		t.Fatalf("coordinator did not finish cleanly: node=%+v err=%v", node, err)
	}
	run, err := st.Orchestration().GetRun(ctx, leader.OrchestrationRunID)
	if err != nil || run.State != controlmodel.RunSucceeded || run.WaitReason != "" {
		t.Fatalf("adaptive Run did not converge: run=%+v err=%v", run, err)
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

func TestSuccessfulMentionConsultationDoesNotAdvanceAssignedIssue(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	svc := &collaboration.Service{Store: st}
	issue, ownerTask, err := svc.CreateIssue(ctx, collaboration.CreateIssueRequest{
		Tenant: "tenant-a", Namespace: "ns-a", Title: "consult another agent",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "assigned-agent",
	})
	if err != nil {
		t.Fatal(err)
	}
	ownerTask, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{
		TaskID: ownerTask.ID, ExpectedVersion: ownerTask.Version,
	})
	if err == nil {
		ownerTask, err = st.Collaboration().StartAgentTask(ctx, ownerTask.ID, ownerTask.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	request, err := svc.AddComment(ctx, collaboration.AddCommentRequest{
		IssueID: issue.ID, Author: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		Content: "Please give me a second opinion.", Mentions: []collaboration.MentionTarget{{
			Type: controlmodel.AssigneeAgent, Ref: "consultant",
		}},
	})
	if err != nil || len(request.Tasks) != 1 {
		t.Fatalf("mention consultant: result=%+v err=%v", request, err)
	}
	consultant, err := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{
		TaskID: request.Tasks[0].ID, ExpectedVersion: request.Tasks[0].Version,
	})
	if err == nil {
		consultant, err = st.Collaboration().StartAgentTask(ctx, consultant.ID, consultant.Version)
	}
	if err == nil {
		consultant, _, err = svc.CompleteTask(ctx, consultant.ID, store.TaskCompletion{
			ExpectedVersion: consultant.Version, Summary: "second opinion",
		}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "consultant"})
	}
	if err != nil {
		t.Fatal(err)
	}
	if err = (&Engine{Store: st}).ReconcileRun(ctx, consultant.OrchestrationRunID); err != nil {
		t.Fatal(err)
	}
	current, err := st.Collaboration().GetIssue(ctx, issue.ID)
	if err != nil || current.Status != controlmodel.IssueInProgress {
		t.Fatalf("consultation Run advanced assigned Issue: issue=%+v err=%v", current, err)
	}
}

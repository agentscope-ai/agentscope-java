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

package collaboration

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func openTestStore(t *testing.T) store.Store {
	t.Helper()
	st, err := store.Open(context.Background(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	return st
}

func TestAgentTaskStartMovesAssignedIssueIntoProgress(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	issue, task, err := svc.CreateIssue(ctx, CreateIssueRequest{
		Tenant: "tenant-a", Namespace: "ns-a", Title: "external task",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "external-worker",
	})
	if err != nil || task == nil {
		t.Fatalf("create issue/task: issue=%+v task=%+v err=%v", issue, task, err)
	}
	claimed, err := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{
		TaskID: task.ID, ExpectedVersion: task.Version, SessionID: "external-session",
	})
	if err != nil {
		t.Fatal(err)
	}
	claimedIssue, err := st.Collaboration().GetIssue(ctx, issue.ID)
	if err != nil || claimedIssue.Status != controlmodel.IssueBacklog {
		t.Fatalf("claim should not start issue: issue=%+v err=%v", claimedIssue, err)
	}
	running, err := st.Collaboration().StartAgentTask(ctx, claimed.ID, claimed.Version)
	if err != nil {
		t.Fatal(err)
	}
	runningIssue, err := st.Collaboration().GetIssue(ctx, issue.ID)
	if err != nil || runningIssue.Status != controlmodel.IssueInProgress {
		t.Fatalf("start did not move issue in progress: issue=%+v err=%v", runningIssue, err)
	}
	activities, err := st.Collaboration().ListActivities(ctx, issue.ID, 10, 0)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, activity := range activities {
		if activity.Action == "issue.status_changed" && activity.Actor.Ref == "external-worker" {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("missing issue status activity: %+v", activities)
	}
	if _, _, err = svc.CompleteTask(ctx, running.ID, store.TaskCompletion{
		ExpectedVersion: running.Version, Summary: "done",
	}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "external-worker"}); err != nil {
		t.Fatal(err)
	}
	completedIssue, err := st.Collaboration().GetIssue(ctx, issue.ID)
	if err != nil || completedIssue.Status != controlmodel.IssueInReview {
		t.Fatalf("completion did not request review: issue=%+v err=%v", completedIssue, err)
	}
}

func TestHumanFollowUpInheritsUniqueActiveAdaptiveRun(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant-a", Namespace: "ns-a", Name: "operators", LeaderAgentRef: "leader",
	})
	if err != nil {
		t.Fatal(err)
	}
	issue, initial, err := svc.CreateIssue(ctx, CreateIssueRequest{Tenant: "tenant-a", Namespace: "ns-a",
		Title: "continue this run", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String()})
	if err != nil || initial == nil {
		t.Fatalf("create adaptive task: task=%+v err=%v", initial, err)
	}
	initial, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: initial.ID,
		ExpectedVersion: initial.Version, SessionID: "leader-session"})
	if err != nil {
		t.Fatal(err)
	}
	result, err := svc.AddComment(ctx, AddCommentRequest{IssueID: issue.ID,
		Author: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, Content: "new input"})
	if err != nil || len(result.Tasks) != 1 {
		t.Fatalf("route follow-up: result=%+v err=%v", result, err)
	}
	followUp := result.Tasks[0]
	if followUp.OrchestrationRunID != initial.OrchestrationRunID || followUp.RunNodeID != initial.RunNodeID {
		t.Fatalf("follow-up escaped active Run/Node: initial=%+v followUp=%+v", initial, followUp)
	}
	loadedFollowUp, err := st.Collaboration().GetAgentTask(ctx, followUp.ID)
	if err != nil || loadedFollowUp.ParentTaskID == nil || *loadedFollowUp.ParentTaskID != initial.ID || len(loadedFollowUp.Inputs) != 1 {
		t.Fatalf("follow-up lineage/input missing: %+v", loadedFollowUp)
	}
}

func TestTeamPolicyGuardsDelegationContentSLAAndTimeout(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant-a", Namespace: "ns-a", Name: "reviewers", LeaderAgentRef: "leader",
		Policy: controlmodel.TeamPolicy{MaxChildDepth: 4, MaxChildIssues: 1, MaxFanout: 1,
			MaxTaskRetries: 1, IssueSLASeconds: 60, TaskTimeoutSeconds: 1,
			SecretPolicy: "block", PIIPolicy: "block"},
	})
	if err != nil {
		t.Fatal(err)
	}
	issue, task, err := svc.CreateIssue(ctx, CreateIssueRequest{Tenant: "tenant-a", Namespace: "ns-a",
		Title: "Review release", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String()})
	if err != nil || task == nil {
		t.Fatalf("create issue/task: %v %#v", err, task)
	}
	if issue.DueAt == nil || issue.DueAt.Before(time.Now()) {
		t.Fatal("Team SLA did not set an Issue due time")
	}
	all, err := svc.AddComment(ctx, AddCommentRequest{IssueID: issue.ID,
		Author: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, Content: "notify everyone",
		Mentions: []MentionTarget{{Type: controlmodel.AssigneeAgent, Ref: "all"}}})
	if err != nil || len(all.Routes) != 1 || all.Routes[0].Outcome != controlmodel.RouteBlocked || all.Routes[0].ReasonCode != "mention_all_not_allowed" {
		t.Fatalf("unexpected @all policy result: %#v err=%v", all, err)
	}
	if _, _, err = svc.CreateChildFromTask(ctx, task.ID, CreateIssueRequest{Title: "first child"}); err != nil {
		t.Fatalf("first child: %v", err)
	}
	if _, _, err = svc.CreateChildFromTask(ctx, task.ID, CreateIssueRequest{Title: "second child"}); err == nil || !strings.Contains(err.Error(), "count budget") {
		t.Fatalf("expected child budget error, got %v", err)
	}
	if _, err = svc.AddComment(ctx, AddCommentRequest{IssueID: issue.ID,
		Author: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, Content: "api_key=123456789-secret"}); err == nil {
		t.Fatal("secret policy accepted a credential")
	}
	if _, err = svc.AddComment(ctx, AddCommentRequest{IssueID: issue.ID,
		Author: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, Content: "contact user@example.com"}); err == nil {
		t.Fatal("PII policy accepted an email address")
	}
	claimed, err := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version})
	if err != nil {
		t.Fatal(err)
	}
	running, err := st.Collaboration().StartAgentTask(ctx, task.ID, claimed.Version)
	if err != nil {
		t.Fatal(err)
	}
	count, err := st.Collaboration().SweepTimedOutAgentTasks(ctx, running.StartedAt.Add(2*time.Second), 10)
	if err != nil || count != 1 {
		t.Fatalf("timeout sweep: count=%d err=%v", count, err)
	}
	failed, _ := st.Collaboration().GetAgentTask(ctx, task.ID)
	if failed.Status != controlmodel.AgentTaskFailed || failed.ErrorCode != "task_timeout" {
		t.Fatalf("unexpected timed out task: %#v", failed)
	}
	if _, err = svc.RetryTask(ctx, task.ID, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}); err != nil {
		t.Fatalf("first retry: %v", err)
	}
}

func TestAcceptanceRequiresChildrenAndVisibleResult(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	issue, _, err := svc.CreateIssue(ctx, CreateIssueRequest{Tenant: "t", Namespace: "n", Title: "parent",
		Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "h"}, AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "agent-a"})
	if err != nil {
		t.Fatal(err)
	}
	child, _, err := svc.CreateIssue(ctx, CreateIssueRequest{Tenant: "t", Namespace: "n", Title: "child",
		Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "h"}, ParentIssueID: &issue.ID})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.Collaboration().TransitionIssue(ctx, issue.ID, issue.Version, controlmodel.IssueInProgress, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "h"}, ""); err != nil {
		t.Fatal(err)
	}
	current, _ := st.Collaboration().GetIssue(ctx, issue.ID)
	if _, err = svc.TransitionIssue(ctx, issue.ID, current.Version, controlmodel.IssueDone, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "h"}, ""); err == nil || !strings.Contains(err.Error(), "child issue") {
		t.Fatalf("expected child acceptance guard, got %v", err)
	}
	// Keep the UUID referenced so the test also asserts child creation returned a real record.
	if child.ID == uuid.Nil {
		t.Fatal("child ID is nil")
	}
}

func TestAcceptanceCriteriaAndRequiredHumanReview(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "t", Namespace: "n", Name: "reviewed", LeaderAgentRef: "leader",
		Policy: controlmodel.TeamPolicy{RequireReview: true},
	})
	if err != nil {
		t.Fatal(err)
	}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "t", Namespace: "n", Title: "review me", AssigneeType: controlmodel.AssigneeTeam,
		AssigneeRef: team.ID.String(), Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AcceptanceCriteria: json.RawMessage(`{"checklist":[{"id":"tests","satisfied":false}]}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	issue, err = st.Collaboration().TransitionIssue(ctx, issue.ID, issue.Version, controlmodel.IssueInProgress, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, "")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = svc.TransitionIssue(ctx, issue.ID, issue.Version, controlmodel.IssueDone, controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "runtime"}, ""); err == nil || !strings.Contains(err.Error(), "human review") {
		t.Fatalf("expected human review guard, got %v", err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 10})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("expected the Team leader task, tasks=%d err=%v", len(tasks), err)
	}
	claimed, err := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version})
	if err != nil {
		t.Fatal(err)
	}
	running, err := st.Collaboration().StartAgentTask(ctx, claimed.ID, claimed.Version)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = svc.CompleteTask(ctx, running.ID, store.TaskCompletion{ExpectedVersion: running.Version, Summary: "done"}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	// A leader turn is not the entire team's completion. Notify when the Issue
	// actually enters review, independent of whether a new result is published.
	current, _ := st.Collaboration().GetIssue(ctx, issue.ID)
	if _, err = st.Collaboration().TransitionIssue(ctx, issue.ID, current.Version, controlmodel.IssueInReview,
		controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "test"}, "ready for acceptance"); err != nil {
		t.Fatal(err)
	}
	inbox, err := st.Collaboration().ListInbox(ctx, store.InboxFilter{Tenant: "t", Namespace: "n", RecipientRef: "owner", Limit: 10})
	if err != nil || len(inbox) != 1 || inbox[0].Type != "review_request" {
		t.Fatalf("expected an explicit human review request, inbox=%#v err=%v", inbox, err)
	}
	if _, err = svc.TransitionIssue(ctx, issue.ID, issue.Version, controlmodel.IssueDone, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, ""); err == nil || !strings.Contains(err.Error(), "checklist tests") {
		t.Fatalf("expected checklist guard, got %v", err)
	}
}

func TestCompletionBudgetFailsTaskAndNotifiesHuman(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "t", Namespace: "n", Name: "budgeted", LeaderAgentRef: "leader",
		Policy: controlmodel.TeamPolicy{MaxIssueTokens: 10, MaxIssueCostMicros: 100},
	})
	if err != nil {
		t.Fatal(err)
	}
	_, task, err := svc.CreateIssue(ctx, CreateIssueRequest{
		Tenant: "t", Namespace: "n", Title: "bounded", AssigneeType: controlmodel.AssigneeTeam,
		AssigneeRef: team.ID.String(), Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
	})
	if err != nil {
		t.Fatal(err)
	}
	task, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version})
	if err != nil {
		t.Fatal(err)
	}
	task, err = st.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
	if err != nil {
		t.Fatal(err)
	}
	failed, _, err := svc.CompleteTask(ctx, task.ID, store.TaskCompletion{ExpectedVersion: task.Version,
		Result: json.RawMessage(`{"usage":{"totalTokens":11,"costMicros":20}}`)}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"})
	if err == nil || !strings.Contains(err.Error(), "token budget exceeded") || failed == nil || failed.Status != controlmodel.AgentTaskFailed {
		t.Fatalf("expected an explicit budget failure, task=%#v err=%v", failed, err)
	}
	inbox, err := st.Collaboration().ListInbox(ctx, store.InboxFilter{Tenant: "t", Namespace: "n", RecipientRef: "owner", Limit: 10})
	if err != nil || len(inbox) != 1 || inbox[0].Type != "agent_task_failed" {
		t.Fatalf("expected human budget notification, inbox=%#v err=%v", inbox, err)
	}
}

func TestCompletionAtomicallyAggregatesAttemptAndRunUsage(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	_, task, err := svc.CreateIssue(ctx, CreateIssueRequest{Tenant: "usage", Namespace: "default",
		Title: "account usage", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker",
		Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}})
	if err != nil {
		t.Fatal(err)
	}
	task, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneExternalApplication,
			State: controlmodel.ExecutionAssigned, DispatchGeneration: 3})
	if err != nil {
		t.Fatal(err)
	}
	task, err = st.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
	if err != nil {
		t.Fatal(err)
	}
	usage := json.RawMessage(`{"totalTokens":13,"costMicros":21,"tokens":{"input":8,"output":5}}`)
	completed, _, err := svc.CompleteTask(ctx, task.ID, store.TaskCompletion{ExpectedVersion: task.Version,
		AttemptID: attempt.ID, DispatchGeneration: attempt.DispatchGeneration, Usage: usage,
		Result: json.RawMessage(`{"output":"done"}`), Summary: "done"},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "worker"})
	if err != nil || completed.Status != controlmodel.AgentTaskCompleted {
		t.Fatalf("complete: task=%+v err=%v", completed, err)
	}
	storedAttempt, err := st.ExecutionAttempts().Get(ctx, attempt.ID)
	if err != nil || string(storedAttempt.Usage) != string(usage) {
		t.Fatalf("attempt usage missing: attempt=%+v err=%v", storedAttempt, err)
	}
	run, err := st.Orchestration().GetRun(ctx, task.OrchestrationRunID)
	var aggregate struct {
		TotalTokens int64 `json:"totalTokens"`
		CostMicros  int64 `json:"costMicros"`
	}
	if err == nil {
		err = json.Unmarshal(run.Usage, &aggregate)
	}
	if err != nil || aggregate.TotalTokens != 13 || aggregate.CostMicros != 21 {
		t.Fatalf("run usage missing: run=%+v err=%v", run, err)
	}
}

func TestRespondThenCompleteAtomicallyFinishesAttemptAndRun(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	issue, task, err := svc.CreateIssue(ctx, CreateIssueRequest{Tenant: "respond-complete", Namespace: "default",
		Title: "preserve response", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker",
		Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}})
	if err != nil {
		t.Fatal(err)
	}
	task, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
			State: controlmodel.ExecutionAssigned, DispatchGeneration: 7})
	if err != nil {
		t.Fatal(err)
	}
	task, err = st.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
	if err != nil {
		t.Fatal(err)
	}
	response, err := svc.AddComment(ctx, AddCommentRequest{IssueID: issue.ID,
		Author:  controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "worker"},
		Content: "WORKER_RESULT", Type: controlmodel.CommentResult, SourceTaskID: &task.ID})
	if err != nil || response.Comment == nil {
		t.Fatalf("respond: result=%+v err=%v", response, err)
	}
	completed, reused, err := svc.CompleteTask(ctx, task.ID, store.TaskCompletion{
		ExpectedVersion: task.Version, Summary: "WORKER_RESULT", Result: json.RawMessage(`{"ok":true}`)},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "worker"})
	if err != nil || completed.Status != controlmodel.AgentTaskCompleted || reused == nil || reused.ID != response.Comment.ID {
		t.Fatalf("complete: task=%+v comment=%+v err=%v", completed, reused, err)
	}
	storedAttempt, err := st.ExecutionAttempts().Get(ctx, attempt.ID)
	if err != nil || storedAttempt.State != controlmodel.ExecutionSucceeded || storedAttempt.CompletedAt == nil {
		t.Fatalf("attempt not terminal: attempt=%+v err=%v", storedAttempt, err)
	}
	run, err := st.Orchestration().GetRun(ctx, task.OrchestrationRunID)
	if err != nil || run.State != controlmodel.RunSucceeded || run.CompletedAt == nil {
		t.Fatalf("run not terminal: run=%+v err=%v", run, err)
	}
}

func TestChildDelegationUsesFrozenTeamRoster(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	human := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant", Namespace: "default", Name: "frozen-roster",
		LeaderAgentRef: "leader-v1", Policy: controlmodel.TeamPolicy{MaxChildDepth: 4, MaxChildIssues: 4},
	})
	if err != nil {
		t.Fatal(err)
	}
	member, err := st.Collaboration().AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{
		TeamID: team.ID, AgentRef: "specialist-v1", Role: "specialist",
	})
	if err != nil {
		t.Fatal(err)
	}
	team, err = st.Collaboration().GetTeam(ctx, team.ID)
	if err != nil {
		t.Fatal(err)
	}
	root, leaderTask, err := svc.CreateIssue(ctx, CreateIssueRequest{
		Tenant: "tenant", Namespace: "default", Title: "frozen collaboration",
		Creator: human, AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String(),
	})
	if err != nil || leaderTask == nil {
		t.Fatalf("create root: issue=%+v task=%+v err=%v", root, leaderTask, err)
	}

	team.LeaderAgentRef = "leader-v2"
	team.Policy.AllowExternalDelegation = true
	team, err = st.Collaboration().UpdateTeam(ctx, team, team.Version)
	if err != nil {
		t.Fatal(err)
	}
	if err = st.Collaboration().RemoveTeamMember(ctx, team.ID, member.ID); err != nil {
		t.Fatal(err)
	}
	frozen, err := svc.TeamForTask(ctx, leaderTask)
	if err != nil {
		t.Fatal(err)
	}
	if frozen.LeaderAgentRef != "leader-v1" || frozen.Policy.AllowExternalDelegation || len(frozen.Members) != 1 {
		t.Fatalf("unexpected frozen snapshot: %+v", frozen)
	}

	_, childTask, err := svc.CreateChildFromTask(ctx, leaderTask.ID, CreateIssueRequest{
		Title: "delegate to frozen specialist", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "specialist-v1",
	})
	if err != nil {
		t.Fatal(err)
	}
	if childTask == nil || childTask.TeamID == nil || *childTask.TeamID != team.ID || childTask.TeamRole != "specialist" || childTask.LeaderTask {
		t.Fatalf("child task lost frozen Team lineage: %+v", childTask)
	}
	if _, _, err = svc.CreateChildFromTask(ctx, leaderTask.ID, CreateIssueRequest{
		Title: "live leader is not in snapshot", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "leader-v2",
	}); err == nil || !strings.Contains(err.Error(), "not in the Team snapshot") {
		t.Fatalf("live roster leaked into running Team: %v", err)
	}
}

func TestTeamProgressDoesNotDispatchAndImplicitAssigneeFollowUpKeepsLineage(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant", Namespace: "default", Name: "routing", LeaderAgentRef: "leader",
		Policy: controlmodel.TeamPolicy{MaxChildDepth: 4, MaxChildIssues: 4},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.Collaboration().AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{
		TeamID: team.ID, AgentRef: "worker", Role: "researcher",
	}); err != nil {
		t.Fatal(err)
	}
	_, leader, err := svc.CreateIssue(ctx, CreateIssueRequest{
		Tenant: "tenant", Namespace: "default", Title: "coordinate",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String(),
	})
	if err != nil {
		t.Fatal(err)
	}
	child, _, err := svc.CreateChildFromTask(ctx, leader.ID, CreateIssueRequest{
		Title: "research", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker",
	})
	if err != nil {
		t.Fatal(err)
	}
	progress, err := svc.AddComment(ctx, AddCommentRequest{
		IssueID: child.ID, Author: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"},
		Content: "waiting for a feasible fallback", Type: controlmodel.CommentProgress,
		SourceTaskID: &leader.ID,
	})
	if err != nil || len(progress.Routes) != 0 || len(progress.Tasks) != 0 {
		t.Fatalf("progress comment dispatched work: result=%+v err=%v", progress, err)
	}
	status, err := svc.AddComment(ctx, AddCommentRequest{
		IssueID: child.ID, Author: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"},
		Content: "waiting for a credential", Type: controlmodel.CommentStatus,
		SourceTaskID: &leader.ID,
	})
	if err != nil || len(status.Routes) != 0 || len(status.Tasks) != 0 {
		t.Fatalf("status comment dispatched work: result=%+v err=%v", status, err)
	}
	followUp, err := svc.AddComment(ctx, AddCommentRequest{
		IssueID: child.ID, Author: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"},
		Content: "retry using the supplied local material", SourceTaskID: &leader.ID,
	})
	if err != nil || len(followUp.Tasks) != 1 {
		t.Fatalf("implicit assignee follow-up was not dispatched: result=%+v err=%v", followUp, err)
	}
	task := followUp.Tasks[0]
	if task.TeamID == nil || *task.TeamID != team.ID || task.TeamRole != "researcher" ||
		task.ParentTaskID == nil || *task.ParentTaskID != leader.ID {
		t.Fatalf("implicit assignee follow-up lost Team lineage: %+v", task)
	}
}

func TestHumanExplicitMentionCompletesWithoutAssigneePingPong(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	issue, assigneeTask, err := svc.CreateIssue(ctx, CreateIssueRequest{
		Tenant: "tenant", Namespace: "default", Title: "ask another agent",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "assignee",
	})
	if err != nil {
		t.Fatal(err)
	}
	assigneeTask, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{
		TaskID: assigneeTask.ID, ExpectedVersion: assigneeTask.Version,
	})
	if err == nil {
		assigneeTask, err = st.Collaboration().StartAgentTask(ctx, assigneeTask.ID, assigneeTask.Version)
	}
	if err == nil {
		assigneeTask, err = svc.FailTask(ctx, assigneeTask.ID, assigneeTask.Version,
			"missing_capability", "required search tool unavailable")
	}
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = svc.ConvergeFailedTask(ctx, assigneeTask.ID); err != nil {
		t.Fatal(err)
	}
	request, err := svc.AddComment(ctx, AddCommentRequest{
		IssueID: issue.ID, Author: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		Content: "Can you solve it?", Mentions: []MentionTarget{{
			Type: controlmodel.AssigneeAgent, Ref: "consultant",
		}},
	})
	if err != nil || len(request.Tasks) != 1 {
		t.Fatalf("explicit mention: result=%+v err=%v", request, err)
	}
	consultant := &request.Tasks[0]
	consultant, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{
		TaskID: consultant.ID, ExpectedVersion: consultant.Version,
	})
	if err == nil {
		consultant, err = st.Collaboration().StartAgentTask(ctx, consultant.ID, consultant.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	currentIssue, err := st.Collaboration().GetIssue(ctx, issue.ID)
	if err != nil || currentIssue.Status != controlmodel.IssueBlocked {
		t.Fatalf("mentioned consultant took ownership while starting: issue=%+v err=%v", currentIssue, err)
	}
	_, answer, err := svc.CompleteTask(ctx, consultant.ID, store.TaskCompletion{
		ExpectedVersion: consultant.Version, Summary: "A usable direct answer",
	}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "consultant"})
	if err != nil {
		t.Fatal(err)
	}
	if answer == nil || answer.ParentID == nil || *answer.ParentID != request.Comment.ID ||
		len(answer.Routes) != 1 || answer.Routes[0].TargetType != controlmodel.AssigneeHuman ||
		answer.Routes[0].TargetRef != "owner" {
		t.Fatalf("explicit mention result was implicitly rerouted: %+v", answer)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{
		Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: issue.ID, Limit: 20,
	})
	if err != nil || len(tasks) != 2 {
		t.Fatalf("mention created an assignee ping-pong task: tasks=%+v err=%v", tasks, err)
	}
	currentIssue, err = st.Collaboration().GetIssue(ctx, issue.ID)
	if err != nil || currentIssue.Status != controlmodel.IssueBlocked {
		t.Fatalf("consultation completion advanced assigned Issue: issue=%+v err=%v", currentIssue, err)
	}
}

func TestAssigneeCompletionTerminatesOneWayAgentHandoff(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	issue, initial, err := svc.CreateIssue(ctx, CreateIssueRequest{
		Tenant: "tenant", Namespace: "default", Title: "consume consultation",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "assignee",
	})
	if err != nil {
		t.Fatal(err)
	}
	initial, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{
		TaskID: initial.ID, ExpectedVersion: initial.Version,
	})
	if err == nil {
		initial, err = st.Collaboration().StartAgentTask(ctx, initial.ID, initial.Version)
	}
	if err == nil {
		initial, err = svc.FailTask(ctx, initial.ID, initial.Version, "unavailable", "needs consultation")
	}
	if err != nil {
		t.Fatal(err)
	}
	request, err := svc.AddComment(ctx, AddCommentRequest{
		IssueID: issue.ID, Author: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		Content: "consult", Mentions: []MentionTarget{{Type: controlmodel.AssigneeAgent, Ref: "consultant"}},
	})
	if err != nil || len(request.Tasks) != 1 {
		t.Fatalf("create consultant: result=%+v err=%v", request, err)
	}
	consultant, err := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{
		TaskID: request.Tasks[0].ID, ExpectedVersion: request.Tasks[0].Version,
	})
	if err == nil {
		consultant, err = st.Collaboration().StartAgentTask(ctx, consultant.ID, consultant.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	consultant, err = st.Collaboration().GetAgentTask(ctx, consultant.ID)
	if err != nil {
		t.Fatal(err)
	}
	inputIDs := make([]uuid.UUID, 0, len(consultant.Inputs))
	for _, input := range consultant.Inputs {
		inputIDs = append(inputIDs, input.ID)
	}
	_, consultation, err := st.Collaboration().CompleteAgentTaskWithComment(ctx, consultant.ID,
		store.TaskCompletion{ExpectedVersion: consultant.Version, ProcessedInputIDs: inputIDs},
		&controlmodel.Comment{IssueID: issue.ID, ParentID: &request.Comment.ID,
			Author:  controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "consultant"},
			Content: "consultation result", Type: controlmodel.CommentResult}, []store.CommentTarget{{
			TargetType: controlmodel.AssigneeAgent, TargetRef: "assignee", AgentRef: "assignee",
			RouteType: controlmodel.RouteAssignee,
		}})
	if err != nil || consultation == nil || len(consultation.Routes) != 1 || consultation.Routes[0].TaskID == nil {
		t.Fatalf("route consultation to assignee: comment=%+v err=%v", consultation, err)
	}
	consumer, err := st.Collaboration().GetAgentTask(ctx, *consultation.Routes[0].TaskID)
	if err == nil {
		consumer, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{
			TaskID: consumer.ID, ExpectedVersion: consumer.Version,
		})
	}
	if err == nil {
		consumer, err = st.Collaboration().StartAgentTask(ctx, consumer.ID, consumer.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	_, final, err := svc.CompleteTask(ctx, consumer.ID, store.TaskCompletion{
		ExpectedVersion: consumer.Version, Summary: "final synthesis",
	}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "assignee"})
	if err != nil || final == nil || len(final.Routes) != 0 {
		t.Fatalf("assignee completion bounced to consultant: comment=%+v err=%v", final, err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{
		Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: issue.ID, AgentRef: "consultant", Limit: 20,
	})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("assignee result created consultant follow-up: tasks=%+v err=%v", tasks, err)
	}
}

func TestQuiescentBlockedTeamWorkBlocksAndHumanFollowUpReopensRoot(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant", Namespace: "default", Name: "blocked-root", LeaderAgentRef: "leader",
		Policy: controlmodel.TeamPolicy{MaxChildDepth: 4, MaxChildIssues: 4},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.Collaboration().AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{
		TeamID: team.ID, AgentRef: "worker", Role: "researcher",
	}); err != nil {
		t.Fatal(err)
	}
	root, leader, err := svc.CreateIssue(ctx, CreateIssueRequest{
		Tenant: "tenant", Namespace: "default", Title: "needs credential",
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
	child, worker, err := svc.CreateChildFromTask(ctx, leader.ID, CreateIssueRequest{
		Title: "web research", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker",
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = svc.CompleteTask(ctx, leader.ID, store.TaskCompletion{ExpectedVersion: leader.Version,
		Summary: "delegated"}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	worker, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: worker.ID, ExpectedVersion: worker.Version})
	if err == nil {
		worker, err = st.Collaboration().StartAgentTask(ctx, worker.ID, worker.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	worker, err = svc.FailTask(ctx, worker.ID, worker.Version, "credential_missing", "TAVILY_API_KEY missing")
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = svc.ConvergeFailedWorker(ctx, worker.ID); err != nil {
		t.Fatal(err)
	}
	followUps, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{
		IssueID: child.ID, AgentRef: "leader", Status: controlmodel.AgentTaskQueued, Limit: 10,
	})
	if err != nil || len(followUps) != 1 {
		t.Fatalf("leader follow-up missing: tasks=%+v err=%v", followUps, err)
	}
	followUp, err := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{
		TaskID: followUps[0].ID, ExpectedVersion: followUps[0].Version,
	})
	if err == nil {
		followUp, err = st.Collaboration().StartAgentTask(ctx, followUp.ID, followUp.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = svc.CompleteTask(ctx, followUp.ID, store.TaskCompletion{ExpectedVersion: followUp.Version,
		Summary: "waiting for a credential"}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	root, _ = st.Collaboration().GetIssue(ctx, root.ID)
	if root.Status != controlmodel.IssueBlocked {
		t.Fatalf("quiescent blocked children did not block root: %+v", root)
	}
	humanInput, err := svc.AddComment(ctx, AddCommentRequest{IssueID: root.ID,
		Author: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, Content: "credential is now available"})
	if err != nil || len(humanInput.Tasks) != 1 {
		t.Fatalf("human follow-up was not queued: result=%+v err=%v", humanInput, err)
	}
	if humanInput.Tasks[0].OrchestrationRunID != leader.OrchestrationRunID {
		t.Fatalf("human follow-up started a duplicate adaptive Run: task=%+v", humanInput.Tasks[0])
	}
	resumed, err := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{
		TaskID: humanInput.Tasks[0].ID, ExpectedVersion: humanInput.Tasks[0].Version,
	})
	if err == nil {
		resumed, err = st.Collaboration().StartAgentTask(ctx, resumed.ID, resumed.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	root, _ = st.Collaboration().GetIssue(ctx, root.ID)
	if root.Status != controlmodel.IssueInProgress {
		t.Fatalf("new human input did not reopen blocked root: %+v", root)
	}
	if _, _, err = svc.CreateChildFromTask(ctx, resumed.ID, CreateIssueRequest{
		Title: "blind duplicate", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker",
	}); err == nil || !strings.Contains(err.Error(), "blocked child Issue") {
		t.Fatalf("leader bypassed blocked work with a new child: %v", err)
	}
	if _, _, err = svc.CompleteTask(ctx, resumed.ID, store.TaskCompletion{ExpectedVersion: resumed.Version,
		Summary: "still waiting for a credential"}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	root, _ = st.Collaboration().GetIssue(ctx, root.ID)
	if root.Status != controlmodel.IssueBlocked {
		t.Fatalf("waiting human follow-up did not restore blocked root: %+v", root)
	}
}

func TestHumanFollowUpOnBlockedWorkerIssueKeepsActiveTeamLineage(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant", Namespace: "default", Name: "child-resume", LeaderAgentRef: "leader",
		Policy: controlmodel.TeamPolicy{MaxChildDepth: 4, MaxChildIssues: 4},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.Collaboration().AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{
		TeamID: team.ID, AgentRef: "worker", Role: "researcher",
	}); err != nil {
		t.Fatal(err)
	}
	_, leader, err := svc.CreateIssue(ctx, CreateIssueRequest{
		Tenant: "tenant", Namespace: "default", Title: "coordinate",
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
	child, worker, err := svc.CreateChildFromTask(ctx, leader.ID, CreateIssueRequest{
		Title: "research", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker",
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = svc.CompleteTask(ctx, leader.ID, store.TaskCompletion{ExpectedVersion: leader.Version,
		Summary: "delegated"}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	worker, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: worker.ID, ExpectedVersion: worker.Version})
	if err == nil {
		worker, err = st.Collaboration().StartAgentTask(ctx, worker.ID, worker.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	worker, err = svc.FailTask(ctx, worker.ID, worker.Version, "credential_missing", "missing key")
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = svc.ConvergeFailedWorker(ctx, worker.ID); err != nil {
		t.Fatal(err)
	}
	leaderFollowUps, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{
		IssueID: child.ID, AgentRef: "leader", Status: controlmodel.AgentTaskQueued, Limit: 10,
	})
	if err != nil || len(leaderFollowUps) != 1 {
		t.Fatalf("leader follow-up missing: tasks=%+v err=%v", leaderFollowUps, err)
	}
	followUp, err := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{
		TaskID: leaderFollowUps[0].ID, ExpectedVersion: leaderFollowUps[0].Version,
	})
	if err == nil {
		followUp, err = st.Collaboration().StartAgentTask(ctx, followUp.ID, followUp.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = svc.CompleteTask(ctx, followUp.ID, store.TaskCompletion{ExpectedVersion: followUp.Version,
		Summary: "waiting"}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	humanInput, err := svc.AddComment(ctx, AddCommentRequest{IssueID: child.ID,
		Author: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, Content: "continue"})
	if err != nil || len(humanInput.Tasks) != 1 {
		t.Fatalf("child follow-up was not queued: result=%+v err=%v", humanInput, err)
	}
	resumed := humanInput.Tasks[0]
	if resumed.OrchestrationRunID != leader.OrchestrationRunID || resumed.TeamID == nil ||
		*resumed.TeamID != team.ID || resumed.TeamRole != "researcher" || resumed.ParentTaskID == nil ||
		*resumed.ParentTaskID != followUp.ID {
		t.Fatalf("child follow-up lost active Team lineage: %+v", resumed)
	}
}

func TestCrossTeamChildIssueResultWakesParentLeaderAndCanBeAccepted(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	svc := &Service{Store: st}
	human := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	teamA, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{Tenant: "tenant", Namespace: "default", Name: "team-a", LeaderAgentRef: "lead-a",
		Policy: controlmodel.TeamPolicy{AllowExternalDelegation: true}})
	if err != nil {
		t.Fatal(err)
	}
	teamB, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{Tenant: "tenant", Namespace: "default", Name: "team-b", LeaderAgentRef: "lead-b"})
	if err != nil {
		t.Fatal(err)
	}
	root, rootTask, err := svc.CreateIssue(ctx, CreateIssueRequest{Tenant: "tenant", Namespace: "default", Title: "ship release",
		Creator: human, AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: teamA.ID.String()})
	if err != nil || rootTask == nil || !rootTask.LeaderTask {
		t.Fatalf("root setup: issue=%+v task=%+v err=%v", root, rootTask, err)
	}
	rootTask, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: rootTask.ID, ExpectedVersion: rootTask.Version, SessionID: "lead-a-1"})
	if err != nil {
		t.Fatal(err)
	}
	rootTask, err = st.Collaboration().StartAgentTask(ctx, rootTask.ID, rootTask.Version)
	if err != nil {
		t.Fatal(err)
	}
	child, childTask, err := svc.CreateChildFromTask(ctx, rootTask.ID, CreateIssueRequest{Title: "independent review",
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: teamB.ID.String()})
	if err != nil || childTask == nil || child.ParentIssueID == nil || *child.ParentIssueID != root.ID || childTask.ParentTaskID == nil || *childTask.ParentTaskID != rootTask.ID {
		t.Fatalf("cross-Team delegation: child=%+v task=%+v err=%v", child, childTask, err)
	}
	childTask, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: childTask.ID, ExpectedVersion: childTask.Version, SessionID: "lead-b-1"})
	if err != nil {
		t.Fatal(err)
	}
	childTask, err = st.Collaboration().StartAgentTask(ctx, childTask.ID, childTask.Version)
	if err != nil {
		t.Fatal(err)
	}
	childTask, result, err := svc.CompleteTask(ctx, childTask.ID, store.TaskCompletion{ExpectedVersion: childTask.Version, Summary: "review approved"},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "lead-b"})
	if err != nil || result == nil || result.SourceTaskID == nil || *result.SourceTaskID != childTask.ID {
		t.Fatalf("Team B result: task=%+v comment=%+v err=%v", childTask, result, err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: child.ID, AgentRef: "lead-a", Limit: 10})
	if err != nil {
		t.Fatal(err)
	}
	var successor *controlmodel.AgentTask
	for _, candidate := range tasks {
		if candidate.ParentTaskID != nil && *candidate.ParentTaskID == rootTask.ID && candidate.Status == controlmodel.AgentTaskQueued {
			successor = candidate
		}
	}
	if successor == nil || len(successor.Inputs) != 1 || successor.Inputs[0].CommentID != result.ID {
		t.Fatalf("Team B result did not wake Team A leader: %+v", tasks)
	}
	successor, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: successor.ID, ExpectedVersion: successor.Version, SessionID: "lead-a-2"})
	if err != nil {
		t.Fatal(err)
	}
	inputIDs := []uuid.UUID{successor.Inputs[0].ID}
	if _, err = st.Collaboration().AcknowledgeTaskInputs(ctx, successor.ID, inputIDs); err != nil {
		t.Fatal(err)
	}
	successor, err = st.Collaboration().StartAgentTask(ctx, successor.ID, successor.Version)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = svc.CompleteTask(ctx, successor.ID, store.TaskCompletion{ExpectedVersion: successor.Version,
		Summary: "child accepted", ProcessedInputIDs: inputIDs}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "lead-a"}); err != nil {
		t.Fatal(err)
	}
	child, err = st.Collaboration().GetIssue(ctx, child.ID)
	if err == nil {
		child, err = svc.TransitionIssue(ctx, child.ID, child.Version, controlmodel.IssueInProgress, human, "reviewed")
	}
	if err == nil {
		child, err = svc.TransitionIssue(ctx, child.ID, child.Version, controlmodel.IssueDone, human, "accepted")
	}
	if err != nil || child.Status != controlmodel.IssueDone {
		t.Fatalf("accept child: %+v %v", child, err)
	}
	rootTask, _, err = svc.CompleteTask(ctx, rootTask.ID, store.TaskCompletion{ExpectedVersion: rootTask.Version, Summary: "parent conclusion"},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "lead-a"})
	if err != nil {
		t.Fatal(err)
	}
	node, err := st.Orchestration().GetNode(ctx, rootTask.RunNodeID)
	if err == nil {
		node, err = st.Orchestration().TransitionNode(ctx, node.ID, node.Version, controlmodel.RunNodeSucceeded, nil, "", "")
	}
	run, runErr := st.Orchestration().GetRun(ctx, rootTask.OrchestrationRunID)
	if err == nil && runErr == nil {
		_, err = st.Orchestration().TransitionRun(ctx, run.ID, run.Version, controlmodel.RunSucceeded, nil, "", "")
	}
	if err != nil || runErr != nil || node.State != controlmodel.RunNodeSucceeded {
		t.Fatalf("explicit coordinator completion: node=%+v err=%v runErr=%v", node, err, runErr)
	}
	root, err = st.Collaboration().GetIssue(ctx, root.ID)
	if err != nil {
		t.Fatal(err)
	}
	root, err = svc.TransitionIssue(ctx, root.ID, root.Version, controlmodel.IssueInProgress, human, "reviewed")
	if err == nil {
		root, err = svc.TransitionIssue(ctx, root.ID, root.Version, controlmodel.IssueDone, human, "accepted")
	}
	if err != nil || root.Status != controlmodel.IssueDone {
		t.Fatalf("accept parent: %+v %v", root, err)
	}
}

func TestWorkerResultWakesOriginalCoordinatorAndLeaderCanAccept(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant", Namespace: "default", Name: "delivery", LeaderAgentRef: "leader",
		Policy: controlmodel.TeamPolicy{MaxChildDepth: 4, MaxChildIssues: 4},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.Collaboration().AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{
		TeamID: team.ID, AgentRef: "worker", Role: "implementer",
	}); err != nil {
		t.Fatal(err)
	}
	_, leader, err := svc.CreateIssue(ctx, CreateIssueRequest{Tenant: "tenant", Namespace: "default",
		Title: "deliver", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String()})
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
	child, worker, err := svc.CreateChildFromTask(ctx, leader.ID, CreateIssueRequest{Title: "implement",
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker"})
	if err != nil {
		t.Fatal(err)
	}
	leader, _, err = svc.CompleteTask(ctx, leader.ID, store.TaskCompletion{ExpectedVersion: leader.Version,
		Summary: "delegated"}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"})
	if err != nil {
		t.Fatal(err)
	}
	coordinatorID := leader.RunNodeID
	worker, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: worker.ID, ExpectedVersion: worker.Version})
	if err == nil {
		worker, err = st.Collaboration().StartAgentTask(ctx, worker.ID, worker.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	_, result, err := svc.CompleteTask(ctx, worker.ID, store.TaskCompletion{ExpectedVersion: worker.Version,
		Summary: "implemented"}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "worker"})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: child.ID, AgentRef: "leader", Limit: 10})
	if err != nil {
		t.Fatal(err)
	}
	var followUp *controlmodel.AgentTask
	for _, candidate := range tasks {
		if candidate.TriggerCommentID != nil && *candidate.TriggerCommentID == result.ID {
			followUp = candidate
			break
		}
	}
	if followUp == nil || !followUp.LeaderTask || followUp.RunNodeID != coordinatorID {
		t.Fatalf("worker result did not resume original coordinator: tasks=%+v", tasks)
	}
	followUp, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: followUp.ID, ExpectedVersion: followUp.Version})
	if err == nil {
		followUp, err = st.Collaboration().StartAgentTask(ctx, followUp.ID, followUp.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	accepted, err := svc.AcceptIssueFromTask(ctx, followUp.ID, "worker result verified")
	if err != nil || accepted.Status != controlmodel.IssueDone {
		t.Fatalf("accept delegated Issue: issue=%+v err=%v", accepted, err)
	}
	node, err := st.Orchestration().GetNode(ctx, coordinatorID)
	if err != nil || node.State != controlmodel.RunNodeWaiting {
		t.Fatalf("original coordinator is not ready for convergence: node=%+v err=%v", node, err)
	}
}

func TestLeaderFollowUpContextIncludesEverySiblingResult(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant", Namespace: "default", Name: "aggregate-results", LeaderAgentRef: "leader",
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
	root, leader, err := svc.CreateIssue(ctx, CreateIssueRequest{
		Tenant: "tenant", Namespace: "default", Title: "list files and write poem",
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
	children := make([]*controlmodel.Issue, 0, 2)
	workers := make([]*controlmodel.AgentTask, 0, 2)
	for _, title := range []string{"list files", "write poem"} {
		child, worker, createErr := svc.CreateChildFromTask(ctx, leader.ID, CreateIssueRequest{
			Title: title, AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker",
		})
		if createErr != nil {
			t.Fatal(createErr)
		}
		children, workers = append(children, child), append(workers, worker)
	}
	if _, _, err = svc.CompleteTask(ctx, leader.ID, store.TaskCompletion{
		ExpectedVersion: leader.Version, Summary: "delegated",
	}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	results := []string{"file-a\nfile-b", "a complete poem"}
	for index, worker := range workers {
		worker, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: worker.ID, ExpectedVersion: worker.Version})
		if err == nil {
			worker, err = st.Collaboration().StartAgentTask(ctx, worker.ID, worker.Version)
		}
		if err == nil {
			_, _, err = svc.CompleteTask(ctx, worker.ID, store.TaskCompletion{
				ExpectedVersion: worker.Version, Summary: results[index],
			}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "worker"})
		}
		if err != nil {
			t.Fatal(err)
		}
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{
		IssueID: children[1].ID, AgentRef: "leader", Limit: 10,
	})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("second follow-up: tasks=%+v err=%v", tasks, err)
	}
	activeEnvelope, err := svc.BuildContext(ctx, tasks[0].ID)
	if err != nil {
		t.Fatal(err)
	}
	activeResults := map[string]bool{}
	for _, child := range activeEnvelope.CoordinatorChildren {
		for _, result := range child.Results {
			activeResults[result.Content] = true
		}
	}
	if activeResults[results[0]] || !activeResults[results[1]] {
		t.Fatalf("active sibling result leaked into current follow-up: results=%+v context=%+v",
			activeResults, activeEnvelope.CoordinatorChildren)
	}
	firstFollowUps, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{
		IssueID: children[0].ID, AgentRef: "leader", Limit: 10,
	})
	if err != nil || len(firstFollowUps) != 1 {
		t.Fatalf("first follow-up: tasks=%+v err=%v", firstFollowUps, err)
	}
	firstFollowUp, err := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{
		TaskID: firstFollowUps[0].ID, ExpectedVersion: firstFollowUps[0].Version,
	})
	if err == nil {
		firstFollowUp, err = st.Collaboration().StartAgentTask(ctx, firstFollowUp.ID, firstFollowUp.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	firstChild, err := svc.AcceptIssueFromTask(ctx, firstFollowUp.ID, "accepted")
	if err != nil || firstChild.Status != controlmodel.IssueDone {
		t.Fatalf("accept first sibling: issue=%+v err=%v", firstChild, err)
	}
	envelope, err := svc.BuildContext(ctx, tasks[0].ID)
	if err != nil {
		t.Fatal(err)
	}
	if envelope.CoordinatorIssue == nil || envelope.CoordinatorIssue.ID != root.ID || len(envelope.CoordinatorChildren) != 2 {
		t.Fatalf("coordinator context incomplete: %+v", envelope)
	}
	seen := map[string]bool{}
	for _, child := range envelope.CoordinatorChildren {
		for _, result := range child.Results {
			seen[result.Content] = true
		}
	}
	for _, result := range results {
		if !seen[result] {
			t.Fatalf("missing sibling result %q from coordinator context: %+v", result, envelope.CoordinatorChildren)
		}
	}
}

func TestTaskRespondThenCompleteReusesSingleResultComment(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	issue, task, err := svc.CreateIssue(ctx, CreateIssueRequest{Tenant: "tenant", Namespace: "default",
		Title: "answer", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker"})
	if err != nil {
		t.Fatal(err)
	}
	task, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version})
	if err == nil {
		task, err = st.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	responded, err := svc.AddComment(ctx, AddCommentRequest{IssueID: issue.ID,
		Author: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "worker"}, Content: "final answer",
		Type: controlmodel.CommentResult, SourceTaskID: &task.ID})
	if err != nil {
		t.Fatal(err)
	}
	completed, reused, err := svc.CompleteTask(ctx, task.ID, store.TaskCompletion{ExpectedVersion: task.Version,
		Summary: "must not duplicate"}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "worker"})
	if err != nil || reused == nil || reused.ID != responded.Comment.ID || completed.Status != controlmodel.AgentTaskCompleted {
		t.Fatalf("result was not reused: task=%+v comment=%+v err=%v", completed, reused, err)
	}
	comments, err := st.Collaboration().ListComments(ctx, issue.ID, store.CommentListOptions{Limit: 20})
	if err != nil {
		t.Fatal(err)
	}
	results := 0
	for _, comment := range comments {
		if comment.Type == controlmodel.CommentResult && comment.SourceTaskID != nil && *comment.SourceTaskID == task.ID {
			results++
		}
	}
	if results != 1 {
		t.Fatalf("result comments=%d, want 1: %+v", results, comments)
	}
	run, err := st.Orchestration().GetRun(ctx, task.OrchestrationRunID)
	if err != nil || run.State != controlmodel.RunSucceeded || run.WaitReason != "" {
		t.Fatalf("completion with an existing response did not reconcile the run: %+v err=%v", run, err)
	}
	if string(completed.Result) != `"final answer"` || string(run.Output) != `"final answer"` {
		t.Fatalf("response disappeared from durable result: task=%s run=%s", completed.Result, run.Output)
	}
	events, err := st.Orchestration().ListRunEvents(ctx, run.ID, 0, 100)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, event := range events {
		if event.Type == "node.succeeded" && strings.Contains(string(event.Payload), "final answer") {
			found = true
		}
	}
	if !found {
		t.Fatalf("result missing from public job event source: %+v", events)
	}
}

func TestFailedDirectTaskPublishesOneVisibleStatus(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	issue, task, err := svc.CreateIssue(ctx, CreateIssueRequest{Tenant: "tenant", Namespace: "default",
		Title: "direct failure", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker"})
	if err != nil {
		t.Fatal(err)
	}
	task, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version})
	if err == nil {
		task, err = st.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	task, err = svc.FailTask(ctx, task.ID, task.Version, "managed_turn_incomplete", "last response: need a key")
	if err != nil {
		t.Fatal(err)
	}
	blocked, status, err := svc.ConvergeFailedTask(ctx, task.ID)
	if err != nil || blocked.Status != controlmodel.IssueBlocked || status == nil ||
		!strings.Contains(status.Content, "last response: need a key") || len(status.Routes) != 0 {
		t.Fatalf("direct failure was not projected: issue=%+v status=%+v err=%v", blocked, status, err)
	}
	_, same, err := svc.ConvergeFailedTask(ctx, task.ID)
	if err != nil || same == nil || same.ID != status.ID {
		t.Fatalf("direct failure projection was not idempotent: first=%+v second=%+v err=%v", status, same, err)
	}
	comments, err := st.Collaboration().ListComments(ctx, issue.ID, store.CommentListOptions{Limit: 10})
	if err != nil || len(comments) != 1 {
		t.Fatalf("direct failure comments=%+v err=%v", comments, err)
	}
}

func TestSelfTriggerGuardIgnoresMismatchedTeamContext(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant", Namespace: "default", Name: "guarded", LeaderAgentRef: "same-agent",
	})
	if err != nil {
		t.Fatal(err)
	}
	issue, task, err := svc.CreateIssue(ctx, CreateIssueRequest{Tenant: "tenant", Namespace: "default",
		Title: "guard", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String()})
	if err != nil {
		t.Fatal(err)
	}
	target := svc.guardTarget(ctx, issue, &task.ID, store.CommentTarget{
		TargetType: controlmodel.AssigneeAgent, TargetRef: "same-agent", AgentRef: "same-agent",
	})
	if !target.Blocked || target.ReasonCode != "self_trigger" {
		t.Fatalf("same Agent bypassed guard through a missing Team ID: %+v", target)
	}
}

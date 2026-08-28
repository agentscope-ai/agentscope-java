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

func TestCrossTeamChildIssueResultWakesParentLeaderAndCanBeAccepted(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	svc := &Service{Store: st}
	human := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	teamA, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{Tenant: "tenant", Namespace: "default", Name: "team-a", LeaderAgentRef: "lead-a"})
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

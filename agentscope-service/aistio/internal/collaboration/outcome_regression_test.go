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

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func outcomeLeader(t *testing.T) (*Service, *controlmodel.Issue, *controlmodel.AgentTask) {
	t.Helper()
	ctx := context.Background()
	svc := &Service{Store: openTestStore(t)}
	team, err := svc.Store.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{Tenant: "tenant", Namespace: "default", Name: "research", LeaderAgentRef: "lead", Policy: controlmodel.TeamPolicy{MaxChildDepth: 4, MaxChildIssues: 4}})
	if err != nil {
		t.Fatal(err)
	}
	_, err = svc.Store.Collaboration().AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{TeamID: team.ID, AgentRef: "worker", Role: "research"})
	if err != nil {
		t.Fatal(err)
	}
	issue, task, err := svc.CreateIssue(ctx, CreateIssueRequest{Tenant: team.Tenant, Namespace: team.Namespace, Title: "EV research", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String()})
	if err != nil {
		t.Fatal(err)
	}
	task, _, err = svc.Store.Collaboration().ClaimAgentTaskWithAttempt(ctx, store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version}, &controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged, State: controlmodel.ExecutionAssigned})
	if err == nil {
		task, err = svc.Store.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	return svc, issue, task
}

func TestBlockedCoordinatorPreservesPartialAndRemainsResumable(t *testing.T) {
	ctx := context.Background()
	svc, root, task := outcomeLeader(t)
	completed, comment, err := svc.BlockCoordinatorForHuman(ctx, task.ID, "provide acceptance permission", json.RawMessage(`"EV findings and sources"`))
	if err != nil {
		t.Fatal(err)
	}
	node, _ := svc.Store.Orchestration().GetNode(ctx, task.RunNodeID)
	run, _ := svc.Store.Orchestration().GetRun(ctx, task.OrchestrationRunID)
	root, _ = svc.Store.Collaboration().GetIssue(ctx, root.ID)
	if completed.Status != controlmodel.AgentTaskCompleted || node.State != controlmodel.RunNodeWaiting || controlmodel.IsOrchestrationRunTerminal(run.State) || root.Status != controlmodel.IssueBlocked {
		t.Fatalf("blocked must preserve coordinator: task=%s node=%s run=%s root=%s", completed.Status, node.State, run.State, root.Status)
	}
	if !strings.Contains(string(completed.Result), "EV findings") || !strings.Contains(comment.Content, "EV findings") || comment.Type != controlmodel.CommentStatus {
		t.Fatalf("partial lost: %s %s", completed.Result, comment.Content)
	}
	// A later final summary must have its own ID and must not be suppressed by the partial report.
	if err = svc.PublishCoordinatorSummary(ctx, run, completed, json.RawMessage(`"final verified report"`), "", ""); err != nil {
		t.Fatal(err)
	}
	comments, err := svc.Store.Collaboration().ListComments(ctx, root.ID, store.CommentListOptions{Limit: 20})
	if err != nil || len(comments) != 2 {
		t.Fatalf("final summary hidden by blocked status: %v %v", comments, err)
	}
	input, err := svc.AddComment(ctx, AddCommentRequest{IssueID: root.ID, Author: root.Creator, Content: "acceptance permission is available; continue"})
	if err != nil || len(input.Tasks) != 1 || input.Tasks[0].OrchestrationRunID != run.ID {
		t.Fatalf("blocked coordinator did not resume on its original run: %+v %v", input, err)
	}

}

func TestFailedAttemptKeepsPartialResult(t *testing.T) {
	ctx := context.Background()
	svc, _, task := outcomeLeader(t)
	failed, err := svc.FailTask(ctx, task.ID, task.Version, "research_failed", "network unavailable", json.RawMessage(`"saved evidence"`))
	if err != nil {
		t.Fatal(err)
	}
	attempt, err := svc.Store.ExecutionAttempts().Get(ctx, *task.CurrentAttemptID)
	if err != nil || string(failed.Result) != `"saved evidence"` || string(attempt.Result) != `"saved evidence"` {
		t.Fatalf("failed task/attempt lost result: %s %+v %v", failed.Result, attempt, err)
	}
}

func TestChecklistUpdateUsesLeaderScopeAndPreservesRequirements(t *testing.T) {
	ctx := context.Background()
	svc, _, leader := outcomeLeader(t)
	child, worker, err := svc.CreateChildFromTask(ctx, leader.ID, CreateIssueRequest{Title: "research", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker", AcceptanceCriteria: json.RawMessage(`{"checklist":[{"id":"sources","text":"include sources","required":true,"satisfied":false}],"minimumArtifacts":0}`)})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = svc.UpdateAcceptanceFromTask(ctx, worker.ID, "sources", true, "sources provided"); err == nil {
		t.Fatal("worker self-validated checklist")
	}
	// Create a real worker outcome to obtain the coordinator's child-scoped follow-up.
	worker, err = svc.Store.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: worker.ID, ExpectedVersion: worker.Version})
	if err == nil {
		worker, err = svc.Store.Collaboration().StartAgentTask(ctx, worker.ID, worker.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = svc.CompleteTask(ctx, worker.ID, store.TaskCompletion{ExpectedVersion: worker.Version, Result: json.RawMessage(`"worker report"`)}, controlmodel.Actor{}); err != nil {
		t.Fatal(err)
	}
	tasks, _ := svc.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: child.ID, AgentRef: "lead", Limit: 10})
	if len(tasks) != 1 {
		t.Fatalf("expected child leader: %v", tasks)
	}
	follow := tasks[0]
	follow, err = svc.Store.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: follow.ID, ExpectedVersion: follow.Version})
	if err == nil {
		follow, err = svc.Store.Collaboration().StartAgentTask(ctx, follow.ID, follow.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	if _, err = svc.UpdateAcceptanceFromTask(ctx, follow.ID, "unknown", true, "evidence"); err == nil {
		t.Fatal("unknown item accepted")
	}
	if _, err = svc.UpdateAcceptanceFromTask(ctx, follow.ID, "sources", true, ""); err == nil {
		t.Fatal("empty evidence accepted")
	}
	updated, err := svc.UpdateAcceptanceFromTask(ctx, follow.ID, "sources", true, "https://example.org/source, checked claim")
	if err != nil {
		t.Fatal(err)
	}
	var criteria struct {
		Checklist []struct {
			Required, Satisfied bool
			Text, Evidence      string
		}
	}
	if err = json.Unmarshal(updated.AcceptanceCriteria, &criteria); err != nil {
		t.Fatal(err)
	}
	if !criteria.Checklist[0].Required || !criteria.Checklist[0].Satisfied || criteria.Checklist[0].Text != "include sources" || criteria.Checklist[0].Evidence == "" {
		t.Fatalf("criteria incorrectly changed: %s", updated.AcceptanceCriteria)
	}
	// A lead replacement report saved via task.respond belongs in the root summary.
	_, err = svc.Store.Collaboration().CreateComment(ctx, store.CreateCommentRequest{Comment: &controlmodel.Comment{IssueID: child.ID, Author: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "lead"}, Type: controlmodel.CommentResult, Content: "full replacement EV report", SourceTaskID: &follow.ID}})
	if err != nil {
		t.Fatal(err)
	}
	run, _ := svc.Store.Orchestration().GetRun(ctx, leader.OrchestrationRunID)
	if err = svc.PublishCoordinatorSummary(ctx, run, follow, json.RawMessage(`"partial synthesis"`), "acceptance_blocked", "manual review needed"); err != nil {
		t.Fatal(err)
	}
	comments, _ := svc.Store.Collaboration().ListComments(ctx, run.RootIssueID, store.CommentListOptions{Limit: 20})
	found := false
	for _, comment := range comments {
		if strings.Contains(comment.Content, "full replacement EV report") && strings.Contains(comment.Content, "partial synthesis") {
			found = true
		}
	}
	if !found {
		t.Fatal("root summary omitted lead replacement or partial result")
	}
}

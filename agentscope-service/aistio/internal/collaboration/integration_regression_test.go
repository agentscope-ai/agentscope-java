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

package collaboration

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func startRegressionTask(t *testing.T, st store.Store, task *controlmodel.AgentTask) *controlmodel.AgentTask {
	t.Helper()
	ctx := context.Background()
	current, err := st.Collaboration().GetAgentTask(ctx, task.ID)
	if err == nil {
		current, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: current.ID, ExpectedVersion: current.Version})
	}
	if err == nil {
		current, err = st.Collaboration().StartAgentTask(ctx, current.ID, current.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	return current
}

func completeRegressionTask(t *testing.T, svc *Service, task *controlmodel.AgentTask, summary string, result json.RawMessage) *controlmodel.Comment {
	t.Helper()
	current, err := svc.Store.Collaboration().GetAgentTask(context.Background(), task.ID)
	if err != nil {
		t.Fatal(err)
	}
	_, comment, err := svc.CompleteTask(context.Background(), current.ID, store.TaskCompletion{ExpectedVersion: current.Version, Summary: summary, Result: result}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: current.AgentRef})
	if err != nil {
		t.Fatal(err)
	}
	return comment
}

// Reproduces the real explicit A -> B -> A route, including coalescing of B's
// explicit reply with its completion. The final ACK must not run B a second time.
func TestExplicitAgentRoundTripStopsAfterOriginatorAcknowledges(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	issue, initial, err := svc.CreateIssue(ctx, CreateIssueRequest{Tenant: "t", Namespace: "n", Title: "round trip", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "A"})
	if err != nil {
		t.Fatal(err)
	}
	initial = startRegressionTask(t, st, initial)
	ask, err := svc.AddComment(ctx, AddCommentRequest{IssueID: issue.ID, Author: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "A"}, SourceTaskID: &initial.ID, Content: "calculate 12*12", Mentions: []MentionTarget{{Type: controlmodel.AssigneeAgent, Ref: "B"}}})
	if err != nil || len(ask.Tasks) != 1 {
		t.Fatalf("ask: %+v %v", ask, err)
	}
	completeRegressionTask(t, svc, initial, "delegated", nil)
	worker := startRegressionTask(t, st, &ask.Tasks[0])
	reply, err := svc.AddComment(ctx, AddCommentRequest{IssueID: issue.ID, Author: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "B"}, SourceTaskID: &worker.ID, Content: "144", Mentions: []MentionTarget{{Type: controlmodel.AssigneeAgent, Ref: "A"}}})
	if err != nil || len(reply.Tasks) != 1 {
		t.Fatalf("reply: %+v %v", reply, err)
	}
	ack := startRegressionTask(t, st, &reply.Tasks[0])
	envelope, contextErr := svc.BuildContext(ctx, ack.ID)
	if contextErr != nil || !envelope.ReplyToOwnDelegation || envelope.InitiatingRequest != issue.Title+"\n\n"+issue.Description {
		t.Fatalf("returning answer lacks initiating context: %+v %v", envelope, contextErr)
	}
	// The recipient may already be running before B publishes its completion.
	// Coalescing only queued tasks cannot prevent this duplicate delivery.
	completeRegressionTask(t, svc, worker, "144", nil)
	responded, err := svc.AddComment(ctx, AddCommentRequest{IssueID: issue.ID,
		Author: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "A"}, SourceTaskID: &ack.ID,
		ParentID: &reply.Comment.ID, Type: controlmodel.CommentResult, Content: "CROSS_ACK"})
	if err != nil || len(responded.Tasks) != 0 {
		t.Fatalf("ACK response routed again: %+v %v", responded, err)
	}
	comment := completeRegressionTask(t, svc, ack, "CROSS_ACK", nil)
	if len(comment.Routes) != 0 {
		t.Fatalf("ACK automatically dispatched again: %+v", comment.Routes)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 20})
	if err != nil || len(tasks) != 3 {
		t.Fatalf("expected A,B,A only: %+v %v", tasks, err)
	}
	for _, task := range tasks {
		if task.Status != controlmodel.AgentTaskCompleted {
			t.Fatalf("unfinished task: %+v", task)
		}
	}
}

func TestCommentContextMakesNewRequestAuthoritative(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	issue, initial, err := svc.CreateIssue(ctx, CreateIssueRequest{Tenant: "t", Namespace: "n", Title: "old request", Description: "original work", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "A"})
	if err != nil {
		t.Fatal(err)
	}
	initial = startRegressionTask(t, st, initial)
	completeRegressionTask(t, svc, initial, "old answer", nil)
	updated, err := svc.AddComment(ctx, AddCommentRequest{IssueID: issue.ID, Author: issue.Creator, Content: "Use the corrected input and answer the new question", Mentions: []MentionTarget{{Type: controlmodel.AssigneeAgent, Ref: "A"}}})
	if err != nil || len(updated.Tasks) != 1 {
		t.Fatalf("updated: %+v %v", updated, err)
	}
	envelope, err := svc.BuildContext(ctx, updated.Tasks[0].ID)
	if err != nil || envelope.CurrentRequest != updated.Comment.Content || envelope.Issue.Description != "original work" {
		t.Fatalf("context lost request precedence/history: %+v %v", envelope, err)
	}
	feedback := startRegressionTask(t, st, &updated.Tasks[0])
	if _, err = st.Collaboration().BeginReviewWork(ctx, feedback.ID, feedback.Version, updated.Comment.Content); err != nil {
		t.Fatal(err)
	}
	current, err := st.Collaboration().GetIssue(ctx, issue.ID)
	if err != nil || current.Status != controlmodel.IssueInProgress {
		t.Fatalf("new assignee request did not resume review: %+v %v", current, err)
	}
}

func TestWorkerResultSurvivesSummaryAndQuiescentUnreviewedWorkIsVisible(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{Tenant: "t", Namespace: "n", Name: "results", LeaderAgentRef: "leader", Policy: controlmodel.TeamPolicy{MaxChildDepth: 4, MaxChildIssues: 4}})
	if err != nil {
		t.Fatal(err)
	}
	_, err = st.Collaboration().AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{TeamID: team.ID, AgentRef: "worker", Role: "analyst"})
	if err != nil {
		t.Fatal(err)
	}
	root, lead, err := svc.CreateIssue(ctx, CreateIssueRequest{Tenant: "t", Namespace: "n", Title: "sort", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String()})
	if err != nil {
		t.Fatal(err)
	}
	lead = startRegressionTask(t, st, lead)
	child, worker, err := svc.CreateChildFromTask(ctx, lead.ID, CreateIssueRequest{Title: "sort strings", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker"})
	if err != nil {
		t.Fatal(err)
	}
	completeRegressionTask(t, svc, lead, "delegated", nil)
	worker = startRegressionTask(t, st, worker)
	result := json.RawMessage(`{"sorted":["apple","banana","cherry"]}`)
	comment := completeRegressionTask(t, svc, worker, "Sorted the strings.", result)
	if !strings.Contains(comment.Content, "Sorted the strings.") || !strings.Contains(comment.Content, string(result)) {
		t.Fatalf("lost summary or result: %s", comment.Content)
	}
	follows, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: child.ID, AgentRef: "leader", Limit: 10})
	if err != nil || len(follows) != 1 {
		t.Fatalf("follow-up: %+v %v", follows, err)
	}
	follow := startRegressionTask(t, st, follows[0])
	envelope, err := svc.BuildContext(ctx, follow.ID)
	if err != nil || len(envelope.CoordinatorChildren) != 1 {
		t.Fatalf("context: %+v %v", envelope, err)
	}
	outcomes := envelope.CoordinatorChildren[0].Outcomes
	if len(outcomes) != 1 || string(outcomes[0].Result) != string(result) || outcomes[0].Status != controlmodel.AgentTaskCompleted {
		t.Fatalf("structured outcome lost: %+v", outcomes)
	}
	// Older clients can still finish a turn without deciding the child. Surface
	// this as blocked work rather than a root in_progress with no executing task.
	completeRegressionTask(t, svc, follow, "waiting for a worker that already finished", nil)
	current, err := st.Collaboration().GetIssue(ctx, root.ID)
	if err != nil || current.Status != controlmodel.IssueBlocked {
		t.Fatalf("quiescent unresolved root invisible: %+v %v", current, err)
	}
}

func TestIndependentRequestToAssigneeStillReceivesAutomaticAnswer(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	issue, initial, err := svc.CreateIssue(ctx, CreateIssueRequest{Tenant: "t", Namespace: "n", Title: "requests", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "A"})
	if err != nil {
		t.Fatal(err)
	}
	initial = startRegressionTask(t, st, initial)
	completeRegressionTask(t, svc, initial, "initial work finished", nil)
	request, err := svc.AddComment(ctx, AddCommentRequest{IssueID: issue.ID, Author: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, Content: "B: request help from A", Mentions: []MentionTarget{{Type: controlmodel.AssigneeAgent, Ref: "B"}}})
	if err != nil || len(request.Tasks) != 1 {
		t.Fatalf("independent request: %+v %v", request, err)
	}
	b := startRegressionTask(t, st, &request.Tasks[0])
	question, err := svc.AddComment(ctx, AddCommentRequest{IssueID: issue.ID, Author: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "B"}, SourceTaskID: &b.ID, Content: "please calculate 2+2", Mentions: []MentionTarget{{Type: controlmodel.AssigneeAgent, Ref: "A"}}})
	if err != nil || len(question.Tasks) != 1 {
		t.Fatalf("question: %+v %v", question, err)
	}
	completeRegressionTask(t, svc, b, "asked A", nil)
	a := startRegressionTask(t, st, &question.Tasks[0])
	answer := completeRegressionTask(t, svc, a, "4", nil)
	if len(answer.Routes) != 1 || answer.Routes[0].TargetRef != "B" {
		t.Fatalf("independent request lost its answer: %+v", answer.Routes)
	}
}

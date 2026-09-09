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

package storetest

import (
	"context"
	"testing"

	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func testInbox(t *testing.T, ctx context.Context, s store.Store) {
	r := s.Collaboration()
	owner := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "inbox-owner"}
	agent := controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "inbox-worker"}
	newIssue := func(t *testing.T) *controlmodel.Issue {
		t.Helper()
		issue, err := r.CreateIssue(ctx, &controlmodel.Issue{Tenant: "inbox-" + uuid.NewString(), Namespace: "n", Title: "Test work", Creator: owner})
		if err != nil {
			t.Fatal(err)
		}
		return issue
	}
	transition := func(t *testing.T, issue *controlmodel.Issue, status controlmodel.IssueStatus) *controlmodel.Issue {
		t.Helper()
		current, err := r.GetIssue(ctx, issue.ID)
		if err != nil {
			t.Fatal(err)
		}
		next, err := r.TransitionIssue(ctx, issue.ID, current.Version, status, agent, "test lifecycle reason")
		if err != nil {
			t.Fatal(err)
		}
		return next
	}
	list := func(t *testing.T, issue *controlmodel.Issue, kind string, archived bool) []*controlmodel.InboxItem {
		t.Helper()
		items, err := r.ListInbox(ctx, store.InboxFilter{Tenant: issue.Tenant, Namespace: "n", RecipientRef: owner.Ref, Type: kind, Archived: archived, Limit: 500})
		if err != nil {
			t.Fatal(err)
		}
		return items
	}
	t.Run("LifecycleEpisodes", func(t *testing.T) {
		issue := newIssue(t)
		issue = transition(t, issue, controlmodel.IssueInProgress)
		issue = transition(t, issue, controlmodel.IssueBlocked)
		items := list(t, issue, "issue_blocked", false)
		if len(items) != 1 || !items[0].NeedsAction {
			t.Fatalf("blocker=%+v", items)
		}
		first := items[0]
		yes := true
		read, err := r.UpdateInbox(ctx, first.ID, owner.Ref, &yes, nil)
		if err != nil || !read.Read || read.ReadAt == nil || !read.NeedsAction {
			t.Fatalf("read=%+v err=%v", read, err)
		}
		again, err := r.UpdateInbox(ctx, first.ID, owner.Ref, &yes, nil)
		if err != nil || !again.ReadAt.Equal(*read.ReadAt) {
			t.Fatal("read is not idempotent")
		}
		if _, err = r.UpdateInbox(ctx, first.ID, owner.Ref, nil, &yes); err != store.ErrConflict {
			t.Fatalf("archived unresolved blocker: %v", err)
		}
		if _, err = r.GetInbox(ctx, first.ID, "other"); err != store.ErrNotFound {
			t.Fatalf("cross-user get=%v", err)
		}
		issue = transition(t, issue, controlmodel.IssueBlocked)
		if len(list(t, issue, "issue_blocked", false)) != 1 {
			t.Fatal("same-state transition duplicated blocker")
		}
		issue = transition(t, issue, controlmodel.IssueInProgress)
		closed, err := r.GetInbox(ctx, first.ID, owner.Ref)
		if err != nil || closed.NeedsAction || !closed.Archived || closed.ResolvedAt == nil || !closed.Read {
			t.Fatalf("closed=%+v %v", closed, err)
		}
		issue = transition(t, issue, controlmodel.IssueBlocked)
		items = list(t, issue, "issue_blocked", false)
		if len(items) != 1 || items[0].ID == first.ID || items[0].Read {
			t.Fatalf("second episode=%+v", items)
		}
		issue = transition(t, issue, controlmodel.IssueInProgress)
		issue = transition(t, issue, controlmodel.IssueInReview)
		review := list(t, issue, "review_request", false)
		if len(review) != 1 || !review[0].NeedsAction {
			t.Fatalf("review=%+v", review)
		}
		issue = transition(t, issue, controlmodel.IssueDone)
		resolved, err := r.GetInbox(ctx, review[0].ID, owner.Ref)
		if err != nil || resolved.Read || !resolved.Archived || resolved.NeedsAction {
			t.Fatalf("auto completion forged read state: %+v %v", resolved, err)
		}
	})
	t.Run("ApprovalAndGlobalPagination", func(t *testing.T) {
		issue := newIssue(t)
		var first *controlmodel.Approval
		for i := 0; i < 105; i++ {
			approval, err := r.CreateApproval(ctx, &controlmodel.Approval{Tenant: issue.Tenant, Namespace: "n", IssueID: &issue.ID, TargetType: "issue", TargetRef: issue.ID.String(), ApproverRef: owner.Ref, RequestedBy: agent})
			if err != nil {
				t.Fatal(err)
			}
			if first == nil {
				first = approval
			}
		}
		filter := store.InboxFilter{Tenant: issue.Tenant, Namespace: "n", RecipientRef: owner.Ref, View: "attention", Limit: 25}
		summary, err := r.InboxSummary(ctx, filter)
		if err != nil || summary.PendingApprovals != 105 || summary.AttentionTotal != 105 || summary.Unread != 105 {
			t.Fatalf("summary=%+v %v", summary, err)
		}
		seen := map[uuid.UUID]bool{}
		var original *controlmodel.InboxItem
		for {
			page, err := r.ListInbox(ctx, filter)
			if err != nil {
				t.Fatal(err)
			}
			if len(page) == 0 {
				break
			}
			for _, item := range page {
				if seen[item.ID] {
					t.Fatal("cursor duplicated message")
				}
				seen[item.ID] = true
				if *item.ApprovalID == first.ID {
					original = item
				}
			}
			filter.Cursor = store.EncodeInboxCursor(page[len(page)-1])
			if len(page) < filter.Limit {
				break
			}
		}
		if len(seen) != 105 || original == nil {
			t.Fatalf("pagination lost old approval: %d", len(seen))
		}
		yes := true
		read, err := r.UpdateInbox(ctx, original.ID, owner.Ref, &yes, nil)
		if err != nil || !read.NeedsAction {
			t.Fatalf("read approval=%+v %v", read, err)
		}
		summary, err = r.InboxSummary(ctx, filter)
		if err != nil || summary.PendingApprovals != 105 || summary.Unread != 104 || summary.AttentionTotal != 105 {
			t.Fatalf("read changed pending count: %+v %v", summary, err)
		}
		if _, err = r.UpdateInbox(ctx, original.ID, owner.Ref, nil, &yes); err != store.ErrConflict {
			t.Fatalf("pending archive=%v", err)
		}
		if _, err = r.DecideApproval(ctx, first.ID, first.Version, controlmodel.ApprovalApproved, owner, nil); err != nil {
			t.Fatal(err)
		}
		closed, err := r.GetInbox(ctx, original.ID, owner.Ref)
		if err != nil || closed.NeedsAction || !closed.Archived {
			t.Fatalf("decision=%+v %v", closed, err)
		}
		filter.Cursor = ""
		filter.View = "unread"
		filter.Type = "approval"
		page, err := r.ListInbox(ctx, filter)
		if err != nil || len(page) != 25 {
			t.Fatalf("server filtered page=%d %v", len(page), err)
		}
		filter.Namespace = "other"
		summary, err = r.InboxSummary(ctx, filter)
		if err != nil || summary.AttentionTotal != 0 {
			t.Fatalf("scope leaked: %+v %v", summary, err)
		}
	})
	t.Run("MentionSubscriptionDeduplication", func(t *testing.T) {
		issue := newIssue(t)
		if _, err := r.SubscribeIssue(ctx, &controlmodel.IssueSubscriber{IssueID: issue.ID, Tenant: issue.Tenant, Namespace: issue.Namespace, SubscriberType: controlmodel.AssigneeHuman, SubscriberRef: owner.Ref}); err != nil {
			t.Fatal(err)
		}
		result, err := r.CreateComment(ctx, store.CreateCommentRequest{Comment: &controlmodel.Comment{IssueID: issue.ID, Author: agent, Content: "Please check this result", Type: controlmodel.CommentGeneral}, Targets: []store.CommentTarget{{TargetType: controlmodel.AssigneeHuman, TargetRef: owner.Ref, RouteType: controlmodel.RouteExplicit}}})
		if err != nil {
			t.Fatal(err)
		}
		items := list(t, issue, "", false)
		if len(items) != 1 || items[0].CommentID == nil || *items[0].CommentID != result.Comment.ID || items[0].Type != "mention" {
			t.Fatalf("duplicate mention=%+v", items)
		}
		if _, err = r.CreateComment(ctx, store.CreateCommentRequest{Comment: &controlmodel.Comment{IssueID: issue.ID, Author: agent, Content: "still running", Type: controlmodel.CommentProgress}}); err != nil {
			t.Fatal(err)
		}
		if len(list(t, issue, "", false)) != 1 {
			t.Fatal("progress spammed subscriber")
		}
	})
	t.Run("DirectCompletionAndReusedResult", func(t *testing.T) {
		issue, err := r.CreateIssue(ctx, &controlmodel.Issue{Tenant: "inbox-result-" + uuid.NewString(), Namespace: "n", Title: "Direct result", Creator: owner, AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: agent.Ref})
		if err != nil {
			t.Fatal(err)
		}
		tasks, err := r.ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 10})
		if err != nil || len(tasks) != 1 {
			t.Fatalf("tasks=%+v %v", tasks, err)
		}
		task, err := r.ClaimAgentTask(ctx, store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version})
		if err != nil {
			t.Fatal(err)
		}
		task, err = r.StartAgentTask(ctx, task.ID, task.Version)
		if err != nil {
			t.Fatal(err)
		}
		result, err := r.CreateComment(ctx, store.CreateCommentRequest{Comment: &controlmodel.Comment{IssueID: issue.ID, Author: agent, Content: "The final answer", Type: controlmodel.CommentResult, SourceTaskID: &task.ID}, Targets: []store.CommentTarget{{TargetType: controlmodel.AssigneeHuman, TargetRef: owner.Ref, RouteType: controlmodel.RouteThreadParent}}})
		if err != nil {
			t.Fatal(err)
		}
		_, err = r.CompleteAgentTask(ctx, task.ID, store.TaskCompletion{ExpectedVersion: task.Version, ResponseCommentID: &result.Comment.ID})
		if err != nil {
			t.Fatal(err)
		}
		current, err := r.GetIssue(ctx, issue.ID)
		if err != nil || current.Status != controlmodel.IssueInReview {
			t.Fatalf("issue=%+v %v", current, err)
		}
		items := list(t, issue, "review_request", false)
		if len(items) != 1 || !items[0].NeedsAction || items[0].CommentID == nil || *items[0].CommentID != result.Comment.ID {
			t.Fatalf("reused result review=%+v", items)
		}
	})
}

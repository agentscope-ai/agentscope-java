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

package store

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

// InboxCursor preserves ordering while earlier unread messages leave the list.
type InboxCursor struct {
	CreatedAt time.Time `json:"at"`
	ID        uuid.UUID `json:"id"`
	Action    bool      `json:"action"`
}

func EncodeInboxCursor(item *controlmodel.InboxItem) string {
	b, _ := json.Marshal(InboxCursor{item.CreatedAt, item.ID, item.NeedsAction})
	return base64.RawURLEncoding.EncodeToString(b)
}

func DecodeInboxCursor(value string) (*InboxCursor, error) {
	if value == "" {
		return nil, nil
	}
	b, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil {
		return nil, fmt.Errorf("invalid inbox cursor")
	}
	var cursor InboxCursor
	if json.Unmarshal(b, &cursor) != nil || cursor.ID == uuid.Nil || cursor.CreatedAt.IsZero() {
		return nil, fmt.Errorf("invalid inbox cursor")
	}
	return &cursor, nil
}

func InboxMatches(item *controlmodel.InboxItem, filter InboxFilter) bool {
	return (filter.Tenant == "" || filter.Tenant == item.Tenant) &&
		(filter.Namespace == "" || filter.Namespace == item.Namespace) && item.RecipientRef == filter.RecipientRef &&
		item.Archived == filter.Archived && (filter.Type == "" || filter.Type == item.Type) &&
		(filter.View != "unread" || !item.Read) && (filter.View != "attention" || !item.Read || item.NeedsAction) &&
		(filter.View != "action" || item.NeedsAction)
}

func InboxBefore(a, b *controlmodel.InboxItem, attention bool) bool {
	if attention && a.NeedsAction != b.NeedsAction {
		return a.NeedsAction
	}
	if !a.CreatedAt.Equal(b.CreatedAt) {
		return a.CreatedAt.After(b.CreatedAt)
	}
	return a.ID.String() < b.ID.String()
}

func InboxAfterCursor(item *controlmodel.InboxItem, cursor *InboxCursor, attention bool) bool {
	if cursor == nil {
		return true
	}
	return InboxBefore(&controlmodel.InboxItem{ID: cursor.ID, CreatedAt: cursor.CreatedAt, NeedsAction: cursor.Action}, item, attention)
}

func AddInboxSummary(summary *controlmodel.InboxSummary, item *controlmodel.InboxItem) {
	if item.Archived {
		return
	}
	if !item.Read {
		summary.Unread++
	}
	if item.NeedsAction {
		summary.ActionRequired++
		if item.ApprovalID != nil {
			summary.PendingApprovals++
		}
	}
	if !item.Read || item.NeedsAction {
		summary.AttentionTotal++
	}
	summary.ByType[item.Type]++
}

// WorkInboxIssue excludes operational jobs and internal delegated work, unless
// that child has explicitly been assigned to a human.
func WorkInboxIssue(issue *controlmodel.Issue) bool {
	return issue != nil && issue.ArchivedAt == nil &&
		(issue.Kind == "" || issue.Kind == controlmodel.IssueKindUserWork) &&
		(issue.Visibility == "" || issue.Visibility == controlmodel.IssueVisibilityWorkHub) &&
		(issue.ParentIssueID == nil || issue.AssigneeType == controlmodel.AssigneeHuman)
}

func IssueInboxOwner(issue *controlmodel.Issue, accountable string) string {
	if issue.AssigneeType == controlmodel.AssigneeHuman && issue.AssigneeRef != "" {
		return issue.AssigneeRef
	}
	if accountable != "" {
		return accountable
	}
	if issue.Creator.Type == controlmodel.ActorHuman {
		return issue.Creator.Ref
	}
	return ""
}

// IssueInboxItems is the shared notification policy. Stores call it in the same
// transaction/lock that commits a lifecycle transition, including run completion.
func IssueInboxItems(issue *controlmodel.Issue, previous controlmodel.IssueStatus, actor controlmodel.Actor,
	reason, accountable string, subscribers []string) []*controlmodel.InboxItem {
	// A child explicitly entering human review needs an owner notification,
	// even though ordinary internal delegation updates stay out of the Inbox.
	childReview := issue != nil && issue.ParentIssueID != nil && issue.ArchivedAt == nil &&
		issue.Status == controlmodel.IssueInReview && accountable != "" &&
		(issue.Kind == "" || issue.Kind == controlmodel.IssueKindUserWork) &&
		(issue.Visibility == "" || issue.Visibility == controlmodel.IssueVisibilityWorkHub)
	if (!WorkInboxIssue(issue) && !childReview) || previous == issue.Status {
		return nil
	}
	kind, prefix, severity, action := "", "", "info", false
	switch issue.Status {
	case controlmodel.IssueBlocked:
		kind, prefix, severity, action = "issue_blocked", "Blocked: ", "warning", true
	case controlmodel.IssueInReview:
		if issue.CompletionPolicy == controlmodel.IssueCompletionAutomatic || issue.CompletionPolicy == controlmodel.IssueCompletionExternal {
			return nil
		}
		kind, prefix, severity, action = "review_request", "Review requested: ", "attention", true
	case controlmodel.IssueDone:
		kind, prefix = "issue_completed", "Completed: "
	case controlmodel.IssueCancelled:
		kind, prefix = "issue_cancelled", "Cancelled: "
	case controlmodel.IssueInProgress:
		if previous == controlmodel.IssueDone || previous == controlmodel.IssueCancelled || previous == controlmodel.IssueBlocked {
			kind, prefix = "issue_reopened", "Work resumed: "
		}
	}
	if kind == "" {
		return nil
	}
	owner := IssueInboxOwner(issue, accountable)
	recipients := append([]string{owner}, subscribers...)
	seen := map[string]bool{}
	items := make([]*controlmodel.InboxItem, 0)
	for _, recipient := range recipients {
		if recipient == "" || seen[recipient] {
			continue
		}
		seen[recipient] = true
		needsAction := action && recipient == owner
		if !needsAction && actor.Type == controlmodel.ActorHuman && actor.Ref == recipient {
			continue
		}
		details, _ := json.Marshal(map[string]any{"previousStatus": previous, "status": issue.Status, "statusVersion": issue.Version, "reason": reason})
		items = append(items, &controlmodel.InboxItem{ID: uuid.New(), Tenant: issue.Tenant, Namespace: issue.Namespace,
			RecipientType: controlmodel.AssigneeHuman, RecipientRef: recipient, Type: kind, Severity: severity,
			IssueID: &issue.ID, Actor: actor, Title: prefix + issue.Title, Body: reason, Details: details,
			NeedsAction: needsAction, CreatedAt: issue.UpdatedAt,
			DedupeKey: fmt.Sprintf("issue-status:%s:%d:%s:%s", issue.ID, issue.Version, issue.Namespace, recipient)})
	}
	return items
}

func CommentInboxType(route controlmodel.CommentRouteType, result bool) string {
	if route == controlmodel.RouteReviewRequest {
		return "review_request"
	}
	if route == controlmodel.RouteThreadParent {
		return "reply"
	}
	if result {
		return "result"
	}
	return "mention"
}

func NotifyCommentSubscribers(comment *controlmodel.Comment) bool {
	return comment.Type == "" || comment.Type == controlmodel.CommentGeneral || comment.Type == controlmodel.CommentResult
}

// An exhausted worker reports to its leader. Only the root/standalone failure
// needs a human fallback while the run converges to an Issue blocker.
func TaskFailureInbox(task *controlmodel.AgentTask, issue *controlmodel.Issue) *controlmodel.InboxItem {
	if task.TeamID != nil && !task.LeaderTask {
		return nil
	}
	if issue == nil || WorkInboxIssue(issue) && issue.Status == controlmodel.IssueBlocked {
		return nil
	}
	owner := IssueInboxOwner(issue, task.AccountableHumanRef)
	if owner == "" {
		return nil
	}
	details, _ := json.Marshal(map[string]any{"taskId": task.ID, "runId": task.OrchestrationRunID, "errorCode": task.ErrorCode})
	return &controlmodel.InboxItem{ID: uuid.New(), Tenant: task.Tenant, Namespace: task.Namespace,
		RecipientType: controlmodel.AssigneeHuman, RecipientRef: owner, Type: "agent_task_failed", Severity: "error",
		IssueID: &task.IssueID, Actor: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef},
		Title: "Execution failed: " + issue.Title, Body: task.ErrorCode + ": " + task.ErrorMessage, Details: details,
		NeedsAction: true, CreatedAt: time.Now().UTC(), DedupeKey: "agent-task-failed:" + task.ID.String() + ":" + owner}
}

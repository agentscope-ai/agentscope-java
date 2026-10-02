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

package memory

import (
	"context"
	"sort"
	"time"

	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func cloneInbox(item *controlmodel.InboxItem) *controlmodel.InboxItem {
	copy := *item
	copy.Details = cloneJSON(item.Details)
	return &copy
}

func (r *collaborationRepo) ListInbox(ctx context.Context, filter store.InboxFilter) ([]*controlmodel.InboxItem, error) {
	cursor, err := store.DecodeInboxCursor(filter.Cursor)
	if err != nil {
		return nil, err
	}
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	items := make([]*controlmodel.InboxItem, 0)
	for _, item := range r.s.inboxItems {
		if item.IssueID != nil && !r.s.canReadIssueLocked(ctx, *item.IssueID) {
			continue
		}
		if store.InboxMatches(item, filter) && store.InboxAfterCursor(item, cursor, filter.View == "attention") {
			items = append(items, cloneInbox(item))
		}
	}
	sort.Slice(items, func(i, j int) bool { return store.InboxBefore(items[i], items[j], filter.View == "attention") })
	limit := filter.Limit
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	return page(items, filter.Offset, limit), nil
}

func (r *collaborationRepo) GetInbox(_ context.Context, id uuid.UUID, recipient string) (*controlmodel.InboxItem, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	item := r.s.inboxItems[id]
	if item == nil || item.RecipientRef != recipient {
		return nil, store.ErrNotFound
	}
	return cloneInbox(item), nil
}

func (r *collaborationRepo) InboxSummary(ctx context.Context, filter store.InboxFilter) (*controlmodel.InboxSummary, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	result := &controlmodel.InboxSummary{ByType: map[string]int{}}
	filter.Archived = false
	filter.Type = ""
	filter.View = ""
	for _, item := range r.s.inboxItems {
		if item.IssueID != nil && !r.s.canReadIssueLocked(ctx, *item.IssueID) {
			continue
		}
		if store.InboxMatches(item, filter) {
			store.AddInboxSummary(result, item)
		}
	}
	return result, nil
}

func (r *collaborationRepo) UpdateInbox(_ context.Context, id uuid.UUID, recipient string, read, archived *bool) (*controlmodel.InboxItem, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	item := r.s.inboxItems[id]
	if item == nil || item.RecipientRef != recipient {
		return nil, store.ErrNotFound
	}
	if archived != nil && *archived && item.NeedsAction {
		return nil, store.ErrConflict
	}
	if read != nil {
		item.Read = *read
		if *read && item.ReadAt == nil {
			now := time.Now().UTC()
			item.ReadAt = &now
		} else if !*read {
			item.ReadAt = nil
		}
	}
	if archived != nil {
		item.Archived = *archived
	}
	return cloneInbox(item), nil
}

func (r *collaborationRepo) notifyIssueInboxLocked(issue *controlmodel.Issue, previous controlmodel.IssueStatus, actor controlmodel.Actor, reason string, task *controlmodel.AgentTask) {
	now := time.Now().UTC()
	if previous != issue.Status {
		for _, item := range r.s.inboxItems {
			if item.IssueID != nil && *item.IssueID == issue.ID && item.ApprovalID == nil && !item.Archived && (item.Type == "issue_blocked" || item.Type == "review_request" || item.Type == "agent_task_failed" || item.Type == "routing_blocked" || item.Type == "dead_letter") {
				item.NeedsAction, item.Archived, item.ResolvedAt = false, true, &now
			}
		}
	}
	accountable := ""
	if task != nil {
		accountable = task.AccountableHumanRef
	}
	if accountable == "" {
		var latest *controlmodel.AgentTask
		for _, candidate := range r.s.agentTasks {
			if candidate.IssueID == issue.ID && candidate.ParentTaskID == nil && candidate.AccountableHumanRef != "" && (latest == nil || candidate.CreatedAt.After(latest.CreatedAt)) {
				latest = candidate
			}
		}
		if latest != nil {
			accountable = latest.AccountableHumanRef
		}
	}
	subscribers := []string{}
	for _, sub := range r.s.subscribers[issue.ID] {
		if sub.SubscriberType == controlmodel.AssigneeHuman {
			subscribers = append(subscribers, sub.SubscriberRef)
		}
	}
	for _, item := range store.IssueInboxItems(issue, previous, actor, reason, accountable, subscribers) {
		if task != nil {
			var result *controlmodel.Comment
			for _, comment := range r.s.comments {
				if comment.SourceTaskID != nil && *comment.SourceTaskID == task.ID && comment.Type == controlmodel.CommentResult && (result == nil || comment.CreatedAt.After(result.CreatedAt)) {
					result = comment
				}
			}
			if result != nil {
				item.CommentID = &result.ID
				for _, old := range r.s.inboxItems {
					if old.CommentID != nil && *old.CommentID == result.ID && old.RecipientRef == item.RecipientRef && (old.Type == "result" || old.Type == "reply" || old.Type == "mention" || old.Type == "review_request" || old.Type == "issue_update") {
						old.Archived, old.NeedsAction, old.ResolvedAt = true, false, &now
					}
				}
			}
		}
		duplicate := false
		for _, old := range r.s.inboxItems {
			if old.Tenant == item.Tenant && old.DedupeKey == item.DedupeKey {
				duplicate = true
				break
			}
		}
		if !duplicate {
			r.s.inboxItems[item.ID] = item
		}
	}
}

func (r *collaborationRepo) notifyTaskFailureInboxLocked(task *controlmodel.AgentTask) {
	item := store.TaskFailureInbox(task, r.s.issues[task.IssueID])
	if item == nil {
		return
	}
	for _, old := range r.s.inboxItems {
		if old.Tenant == item.Tenant && old.DedupeKey == item.DedupeKey {
			return
		}
	}
	r.s.inboxItems[item.ID] = item
}

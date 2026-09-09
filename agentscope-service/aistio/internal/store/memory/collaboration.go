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

package memory

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type collaborationRepo struct{ s *Store }

func (r *collaborationRepo) CreateIssue(_ context.Context, issue *controlmodel.Issue) (*controlmodel.Issue, error) {
	if issue == nil || strings.TrimSpace(issue.Title) == "" {
		return nil, store.ErrConflict
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if issue.Access.Mode == "" {
		issue.Access.Mode = "private"
	}
	if err := issue.Access.Validate(); err != nil {
		return nil, err
	}
	if issue.Tenant == "" {
		issue.Tenant = "default"
	}
	if issue.Namespace == "" {
		issue.Namespace = "default"
	}
	if issue.ID == uuid.Nil {
		issue.ID = uuid.New()
	}
	if _, exists := r.s.issues[issue.ID]; exists {
		return nil, store.ErrConflict
	}
	if issue.ParentIssueID != nil {
		parent := r.s.issues[*issue.ParentIssueID]
		if parent == nil || parent.Tenant != issue.Tenant || parent.Namespace != issue.Namespace {
			return nil, store.ErrNotFound
		}
		for current := parent; current != nil && current.ParentIssueID != nil; current = r.s.issues[*current.ParentIssueID] {
			if *current.ParentIssueID == issue.ID {
				return nil, store.ErrConflict
			}
		}
	}
	now := time.Now().UTC()
	copy := cloneIssue(issue)
	if copy.Status == "" {
		copy.Status = controlmodel.IssueBacklog
	}
	if copy.Priority == "" {
		copy.Priority = "none"
	}
	if copy.Kind == "" {
		copy.Kind = controlmodel.IssueKindUserWork
	}
	if copy.Visibility == "" {
		copy.Visibility = controlmodel.IssueVisibilityWorkHub
	}
	if copy.CompletionPolicy == "" {
		copy.CompletionPolicy = controlmodel.IssueCompletionReview
	}
	if len(copy.AcceptanceCriteria) == 0 {
		copy.AcceptanceCriteria = json.RawMessage(`[]`)
	}
	if len(copy.ContextRefs) == 0 {
		copy.ContextRefs = json.RawMessage(`[]`)
	}
	copy.Version = 1
	copy.CreatedAt, copy.UpdatedAt = now, now
	r.s.issues[copy.ID] = copy
	var parentTaskID *uuid.UUID
	var sourceTeam *controlmodel.CollaborationTeam
	if copy.SourceType == "agent-task" {
		sourceID, err := uuid.Parse(copy.SourceRef)
		source := r.s.agentTasks[sourceID]
		if err != nil || source == nil || source.Tenant != copy.Tenant || source.Namespace != copy.Namespace {
			delete(r.s.issues, copy.ID)
			return nil, store.ErrNotFound
		}
		parentTaskID = &sourceID
		if source.TeamID != nil {
			snapshot := r.s.runSnapshots[source.OrchestrationRunID.String()+"\x00"+source.TeamID.String()]
			if snapshot == nil || json.Unmarshal(snapshot.Snapshot, &sourceTeam) != nil || sourceTeam == nil {
				delete(r.s.issues, copy.ID)
				return nil, store.ErrConflict
			}
		}
	}
	if copy.AssigneeType == controlmodel.AssigneeTeam {
		teamID, err := uuid.Parse(copy.AssigneeRef)
		team := r.s.collabTeams[teamID]
		if sourceTeam != nil && sourceTeam.ID == teamID {
			team = sourceTeam
		}
		if err != nil || team == nil || team.Tenant != copy.Tenant || team.Namespace != copy.Namespace || team.Status != controlmodel.TeamActive || team.ArchivedAt != nil {
			delete(r.s.issues, copy.ID)
			return nil, store.ErrNotFound
		}
		r.newTaskLocked(copy, team.LeaderAgentRef, "assignment", nil, &teamID, "leader", true, copy.Creator, parentTaskID, nil)
	} else if copy.AssigneeType == controlmodel.AssigneeAgent {
		if copy.AssigneeRef == "" {
			delete(r.s.issues, copy.ID)
			return nil, store.ErrConflict
		}
		var teamID *uuid.UUID
		teamRole := ""
		leader := false
		if sourceTeam != nil {
			role, member := snapshotTeamAgentRole(sourceTeam, copy.AssigneeRef)
			if !member && !sourceTeam.Policy.AllowExternalDelegation {
				delete(r.s.issues, copy.ID)
				return nil, store.ErrConflict
			}
			if !member {
				role = "external"
			}
			teamID, teamRole, leader = &sourceTeam.ID, role, role == "leader"
		}
		r.newTaskLocked(copy, copy.AssigneeRef, "assignment", nil, teamID, teamRole, leader, copy.Creator, parentTaskID, nil)
	}
	r.appendActivityLocked(&controlmodel.Activity{
		Tenant: copy.Tenant, Namespace: copy.Namespace, IssueID: &copy.ID,
		Actor: copy.Creator, Action: "issue.created", ObjectType: "issue", ObjectRef: copy.ID.String(),
	})
	r.enqueueEventLocked(copy.Tenant, "issue", copy.ID, "issue.created.v1", copy, "issue-created:"+copy.ID.String())
	return cloneIssue(copy), nil
}

func (r *collaborationRepo) GetIssue(_ context.Context, id uuid.UUID) (*controlmodel.Issue, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	issue := r.s.issues[id]
	if issue == nil {
		return nil, store.ErrNotFound
	}
	return cloneIssue(issue), nil
}

func (r *collaborationRepo) ListIssues(ctx context.Context, filter store.IssueFilter) ([]*controlmodel.Issue, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*controlmodel.Issue, 0)
	for _, issue := range r.s.issues {
		if !r.s.canReadIssueLocked(ctx, issue.ID) {
			continue
		}
		if (issue.ArchivedAt != nil) != filter.Archived {
			continue
		}
		if filter.Tenant != "" && issue.Tenant != filter.Tenant || filter.Namespace != "" && issue.Namespace != filter.Namespace {
			continue
		}
		if filter.Status != "" && issue.Status != filter.Status || filter.AssigneeType != "" && issue.AssigneeType != filter.AssigneeType || filter.AssigneeRef != "" && issue.AssigneeRef != filter.AssigneeRef {
			continue
		}
		if filter.Kind != "" && issue.Kind != filter.Kind || filter.Visibility != "" && issue.Visibility != filter.Visibility {
			continue
		}
		// Conversation turns are an internal execution carrier for Chat, not
		// user-facing Issues. They are only available through an explicit kind query.
		if filter.Kind == "" && issue.Kind == controlmodel.IssueKindConversationTurn {
			continue
		}
		if filter.ParentID != nil && (issue.ParentIssueID == nil || *issue.ParentIssueID != *filter.ParentID) {
			continue
		}
		if search := strings.ToLower(strings.TrimSpace(filter.Search)); search != "" &&
			!strings.Contains(strings.ToLower(issue.Title), search) &&
			!strings.Contains(strings.ToLower(issue.Description), search) &&
			!strings.Contains(strings.ToLower(issue.Identifier), search) {
			continue
		}
		if filter.CursorTime != nil && (issue.UpdatedAt.After(*filter.CursorTime) || issue.UpdatedAt.Equal(*filter.CursorTime) && issue.ID.String() >= filter.CursorID.String()) {
			continue
		}
		out = append(out, cloneIssue(issue))
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].UpdatedAt.Equal(out[j].UpdatedAt) {
			return out[i].ID.String() > out[j].ID.String()
		}
		return out[i].UpdatedAt.After(out[j].UpdatedAt)
	})
	return page(out, filter.Offset, filter.Limit), nil
}

func (r *collaborationRepo) UpdateIssue(_ context.Context, issue *controlmodel.Issue, expectedVersion int64, actor controlmodel.Actor) (*controlmodel.Issue, error) {
	if issue == nil {
		return nil, store.ErrNotFound
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	current := r.s.issues[issue.ID]
	if current == nil {
		return nil, store.ErrNotFound
	}
	if expectedVersion > 0 && current.Version != expectedVersion {
		return nil, store.ErrConflict
	}
	next := cloneIssue(issue)
	next.Tenant, next.Namespace, next.CreatedAt = current.Tenant, current.Namespace, current.CreatedAt
	next.Creator, next.ParentIssueID = current.Creator, current.ParentIssueID
	next.Version = current.Version + 1
	next.UpdatedAt = time.Now().UTC()
	r.s.issues[next.ID] = next
	r.appendActivityLocked(&controlmodel.Activity{Tenant: next.Tenant, Namespace: next.Namespace,
		IssueID: &next.ID, Actor: actor, Action: "issue.updated", ObjectType: "issue", ObjectRef: next.ID.String(), Details: mustMarshalIssueAccess(next.Access)})
	r.enqueueEventLocked(next.Tenant, "issue", next.ID, "issue.updated.v1", next,
		fmt.Sprintf("issue-updated:%s:%d", next.ID, next.Version))
	return cloneIssue(next), nil
}

func (r *collaborationRepo) TransitionIssue(_ context.Context, id uuid.UUID, expectedVersion int64, status controlmodel.IssueStatus, actor controlmodel.Actor, reason string) (*controlmodel.Issue, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	issue := r.s.issues[id]
	if issue == nil {
		return nil, store.ErrNotFound
	}
	if expectedVersion > 0 && issue.Version != expectedVersion || !controlmodel.CanTransitionIssue(issue.Status, status) {
		return nil, store.ErrConflict
	}
	next := cloneIssue(issue)
	next.Status = status
	next.Version++
	next.UpdatedAt = time.Now().UTC()
	if status == controlmodel.IssueDone {
		now := next.UpdatedAt
		next.ResolvedAt = &now
	} else {
		next.ResolvedAt = nil
	}
	r.s.issues[id] = next
	details, _ := json.Marshal(map[string]string{"from": string(issue.Status), "to": string(status), "reason": reason})
	r.appendActivityLocked(&controlmodel.Activity{Tenant: next.Tenant, Namespace: next.Namespace, IssueID: &id, Actor: actor, Action: "issue.status_changed", ObjectType: "issue", ObjectRef: id.String(), Details: details})
	r.notifyIssueInboxLocked(next, issue.Status, actor, reason, nil)
	r.enqueueEventLocked(next.Tenant, "issue", id, "issue.status-changed.v1", map[string]any{"issue": cloneIssue(next), "previousStatus": issue.Status}, fmt.Sprintf("issue-status:%s:%d", id, next.Version))
	return cloneIssue(next), nil
}

func (r *collaborationRepo) ArchiveIssue(_ context.Context, id uuid.UUID, expectedVersion int64, actor controlmodel.Actor) (*controlmodel.Issue, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	issue := r.s.issues[id]
	if issue == nil {
		return nil, store.ErrNotFound
	}
	if issue.ArchivedAt != nil || expectedVersion > 0 && issue.Version != expectedVersion ||
		issue.Status != controlmodel.IssueDone && issue.Status != controlmodel.IssueCancelled {
		return nil, store.ErrConflict
	}
	now := time.Now().UTC()
	issue.ArchivedAt, issue.UpdatedAt, issue.Version = &now, now, issue.Version+1
	r.appendActivityLocked(&controlmodel.Activity{Tenant: issue.Tenant, Namespace: issue.Namespace,
		IssueID: &issue.ID, Actor: actor, Action: "issue.archived", ObjectType: "issue", ObjectRef: issue.ID.String()})
	r.enqueueEventLocked(issue.Tenant, "issue", issue.ID, "issue.archived.v1", issue,
		fmt.Sprintf("issue-archived:%s:%d", issue.ID, issue.Version))
	return cloneIssue(issue), nil
}

func (r *collaborationRepo) AssignIssue(_ context.Context, id uuid.UUID, expectedVersion int64, assigneeType controlmodel.AssigneeType, assigneeRef string, actor controlmodel.Actor) (*controlmodel.Issue, *controlmodel.AgentTask, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	issue := r.s.issues[id]
	if issue == nil {
		return nil, nil, store.ErrNotFound
	}
	if expectedVersion > 0 && issue.Version != expectedVersion {
		return nil, nil, store.ErrConflict
	}
	if issue.ArchivedAt != nil ||
		((assigneeType == controlmodel.AssigneeAgent || assigneeType == controlmodel.AssigneeTeam) &&
			(issue.Status == controlmodel.IssueDone || issue.Status == controlmodel.IssueCancelled)) {
		return nil, nil, store.ErrConflict
	}

	agentRef, teamID, leader := assigneeRef, (*uuid.UUID)(nil), false
	if assigneeType == controlmodel.AssigneeTeam {
		tid, err := uuid.Parse(assigneeRef)
		if err != nil || r.s.collabTeams[tid] == nil {
			return nil, nil, store.ErrNotFound
		}
		team := r.s.collabTeams[tid]
		if team.Tenant != issue.Tenant || team.Namespace != issue.Namespace || team.Status != controlmodel.TeamActive || team.ArchivedAt != nil {
			return nil, nil, store.ErrNotFound
		}
		agentRef, teamID, leader = team.LeaderAgentRef, &tid, true
	}
	next := cloneIssue(issue)
	next.AssigneeType, next.AssigneeRef = assigneeType, assigneeRef
	next.Version++
	next.UpdatedAt = time.Now().UTC()
	r.s.issues[id] = next
	var task *controlmodel.AgentTask
	if assigneeType == controlmodel.AssigneeAgent || assigneeType == controlmodel.AssigneeTeam {
		task = r.newTaskLocked(next, agentRef, "assignment", nil, teamID, "leader", leader, actor, nil, nil)
	}
	r.appendActivityLocked(&controlmodel.Activity{Tenant: next.Tenant, Namespace: next.Namespace, IssueID: &id, Actor: actor, Action: "issue.assigned", ObjectType: string(assigneeType), ObjectRef: assigneeRef})
	r.enqueueEventLocked(next.Tenant, "issue", id, "issue.assigned.v1", map[string]any{"issue": next, "task": task}, fmt.Sprintf("issue-assigned:%s:%d", id, next.Version))
	return cloneIssue(next), cloneAgentTask(task), nil
}

func (r *collaborationRepo) CreateComment(_ context.Context, req store.CreateCommentRequest) (*store.CreateCommentResult, error) {
	if req.Comment == nil || strings.TrimSpace(req.Comment.Content) == "" {
		return nil, store.ErrConflict
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	issue := r.s.issues[req.Comment.IssueID]
	if issue == nil {
		return nil, store.ErrNotFound
	}
	comment := cloneComment(req.Comment)
	comment.Tenant, comment.Namespace = issue.Tenant, issue.Namespace
	if comment.ID == uuid.Nil {
		comment.ID = uuid.New()
	}
	if _, exists := r.s.comments[comment.ID]; exists {
		return nil, store.ErrConflict
	}
	if comment.ParentID != nil {
		parent := r.s.comments[*comment.ParentID]
		if parent == nil || parent.IssueID != comment.IssueID {
			return nil, store.ErrNotFound
		}
		comment.ThreadRootID = parent.ThreadRootID
	} else {
		comment.ThreadRootID = comment.ID
	}
	if comment.Type == "" {
		comment.Type = controlmodel.CommentGeneral
	}
	now := time.Now().UTC()
	comment.Version, comment.CreatedAt, comment.UpdatedAt = 1, now, now
	r.s.comments[comment.ID] = comment

	mentions := make([]controlmodel.Mention, 0, len(req.Mentions))
	for _, mention := range req.Mentions {
		mention.ID = nonNilUUID(mention.ID)
		mention.Tenant, mention.Namespace = issue.Tenant, issue.Namespace
		mention.IssueID, mention.CommentID, mention.CreatedAt = issue.ID, comment.ID, now
		mentions = append(mentions, mention)
	}
	r.s.commentMentions[comment.ID] = mentions

	routes := make([]controlmodel.CommentRoute, 0, len(req.Targets))
	tasks := make([]controlmodel.AgentTask, 0, len(req.Targets))
	seen := map[string]bool{}
	for _, target := range req.Targets {
		key := string(target.TargetType) + "\x00" + target.TargetRef + "\x00" + target.TeamRole
		if seen[key] {
			continue
		}
		seen[key] = true
		route := controlmodel.CommentRoute{ID: uuid.New(), Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: issue.ID, CommentID: comment.ID, CommentVersion: comment.Version, TargetType: target.TargetType, TargetRef: target.TargetRef, RouteType: target.RouteType, CreatedAt: now}
		if target.Blocked {
			route.Outcome, route.ReasonCode = controlmodel.RouteBlocked, target.ReasonCode
			if recipient := r.attentionRecipientLocked(issue, comment); recipient != "" {
				item := &controlmodel.InboxItem{ID: uuid.New(), Tenant: issue.Tenant, Namespace: issue.Namespace,
					RecipientType: controlmodel.AssigneeHuman, RecipientRef: recipient,
					Type: "routing_blocked", Severity: "warning", IssueID: &issue.ID, CommentID: &comment.ID,
					Actor: comment.Author, Title: "Agent collaboration blocked", Body: target.ReasonCode,
					DedupeKey: "route-blocked:" + comment.ID.String() + ":" + target.TargetRef, CreatedAt: now}
				r.s.inboxItems[item.ID] = item
			}
		} else if target.TargetType == controlmodel.AssigneeHuman {
			route.Outcome = controlmodel.RouteQueued
			itemType, title := store.CommentInboxType(target.RouteType, false), issue.Title
			if target.RouteType == controlmodel.RouteReviewRequest {
				itemType, title = "review_request", "Review requested: "+issue.Title
			}
			item := &controlmodel.InboxItem{ID: uuid.New(), Tenant: issue.Tenant, Namespace: issue.Namespace,
				RecipientType: controlmodel.AssigneeHuman, RecipientRef: target.TargetRef,
				Type: itemType, Severity: "attention", IssueID: &issue.ID, CommentID: &comment.ID,
				Actor: comment.Author, Title: title, Body: comment.Content,
				DedupeKey: "comment:" + comment.ID.String() + ":" + target.TargetRef, CreatedAt: now}
			r.s.inboxItems[item.ID] = item
		} else if target.AgentRef == comment.Author.Ref && comment.Author.Type == controlmodel.ActorAgent {
			route.Outcome, route.ReasonCode = controlmodel.RouteSuppressed, "self_trigger"
		} else {
			task, coalesced := r.routeTaskLocked(issue, comment, target)
			route.TaskID = &task.ID
			if coalesced {
				route.Outcome = controlmodel.RouteCoalesced
			} else {
				route.Outcome = controlmodel.RouteQueued
			}
			tasks = append(tasks, *cloneAgentTask(task))
		}
		routes = append(routes, route)
	}
	r.s.commentRoutes[comment.ID] = routes
	for _, subscriber := range r.s.subscribers[issue.ID] {
		if !store.NotifyCommentSubscribers(comment) || subscriber.SubscriberType != controlmodel.AssigneeHuman || comment.Author.Type == controlmodel.ActorHuman && comment.Author.Ref == subscriber.SubscriberRef || seen[string(controlmodel.AssigneeHuman)+"\x00"+subscriber.SubscriberRef+"\x00"] {
			continue
		}
		item := &controlmodel.InboxItem{ID: uuid.New(), Tenant: issue.Tenant, Namespace: issue.Namespace,
			RecipientType: controlmodel.AssigneeHuman, RecipientRef: subscriber.SubscriberRef,
			Type: "issue_update", Severity: "info", IssueID: &issue.ID, CommentID: &comment.ID,
			Actor: comment.Author, Title: issue.Title, Body: comment.Content,
			DedupeKey: "comment:" + comment.ID.String() + ":" + subscriber.SubscriberRef, CreatedAt: now}
		r.s.inboxItems[item.ID] = item
	}
	issue.UpdatedAt = now
	issue.Version++
	if req.Activity != nil {
		activity := *req.Activity
		activity.Tenant, activity.Namespace, activity.IssueID = issue.Tenant, issue.Namespace, &issue.ID
		activity.ObjectType, activity.ObjectRef = "comment", comment.ID.String()
		r.appendActivityLocked(&activity)
	} else {
		r.appendActivityLocked(&controlmodel.Activity{Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: &issue.ID, Actor: comment.Author, Action: "comment.created", ObjectType: "comment", ObjectRef: comment.ID.String()})
	}
	for _, event := range req.Outbox {
		if event == nil {
			continue
		}
		copy := *event
		copy.ID = nonNilUUID(copy.ID)
		copy.Tenant = issue.Tenant
		if copy.CreatedAt.IsZero() {
			copy.CreatedAt = now
		}
		if copy.AvailableAt.IsZero() {
			copy.AvailableAt = now
		}
		r.s.outboxEvents[copy.ID] = &copy
	}
	r.enqueueEventLocked(issue.Tenant, "comment", comment.ID, "comment.created.v1", map[string]any{"comment": comment, "routes": routes}, "comment-created:"+comment.ID.String())
	resultComment := cloneComment(comment)
	resultComment.Mentions, resultComment.Routes = mentions, routes
	return &store.CreateCommentResult{Comment: resultComment, Routes: routes, Tasks: tasks}, nil
}

func (r *collaborationRepo) attentionRecipientLocked(issue *controlmodel.Issue, comment *controlmodel.Comment) string {
	if comment.SourceTaskID != nil {
		if task := r.s.agentTasks[*comment.SourceTaskID]; task != nil && task.AccountableHumanRef != "" {
			return task.AccountableHumanRef
		}
	}
	if issue.Creator.Type == controlmodel.ActorHuman {
		return issue.Creator.Ref
	}
	return ""
}

func (r *collaborationRepo) GetComment(_ context.Context, id uuid.UUID) (*controlmodel.Comment, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	comment := r.s.comments[id]
	if comment == nil {
		return nil, store.ErrNotFound
	}
	out := cloneComment(comment)
	out.Mentions = append([]controlmodel.Mention(nil), r.s.commentMentions[id]...)
	out.Routes = append([]controlmodel.CommentRoute(nil), r.s.commentRoutes[id]...)
	return out, nil
}

func (r *collaborationRepo) ListComments(_ context.Context, issueID uuid.UUID, opts store.CommentListOptions) ([]*controlmodel.Comment, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	if r.s.issues[issueID] == nil {
		return nil, store.ErrNotFound
	}
	out := make([]*controlmodel.Comment, 0)
	for _, comment := range r.s.comments {
		if comment.IssueID != issueID || opts.RootsOnly && comment.ParentID != nil || opts.ThreadID != nil && comment.ThreadRootID != *opts.ThreadID && comment.ID != *opts.ThreadID {
			continue
		}
		if opts.CursorTime != nil && (comment.CreatedAt.Before(*opts.CursorTime) || comment.CreatedAt.Equal(*opts.CursorTime) && comment.ID.String() <= opts.CursorID.String()) {
			continue
		}
		copy := cloneComment(comment)
		copy.Mentions = append([]controlmodel.Mention(nil), r.s.commentMentions[comment.ID]...)
		copy.Routes = append([]controlmodel.CommentRoute(nil), r.s.commentRoutes[comment.ID]...)
		out = append(out, copy)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].CreatedAt.Equal(out[j].CreatedAt) {
			return out[i].ID.String() < out[j].ID.String()
		}
		return out[i].CreatedAt.Before(out[j].CreatedAt)
	})
	if opts.Tail > 0 && len(out) > opts.Tail {
		out = out[len(out)-opts.Tail:]
	}
	return page(out, opts.Offset, opts.Limit), nil
}

func (r *collaborationRepo) UpdateComment(_ context.Context, comment *controlmodel.Comment, expectedVersion int64) (*controlmodel.Comment, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	current := r.s.comments[comment.ID]
	if current == nil {
		return nil, store.ErrNotFound
	}
	if expectedVersion > 0 && current.Version != expectedVersion {
		return nil, store.ErrConflict
	}
	next := cloneComment(current)
	next.Content, next.Type = comment.Content, comment.Type
	next.Version++
	next.UpdatedAt = time.Now().UTC()
	r.s.comments[next.ID] = next
	seen := map[string]bool{}
	for taskID, inputs := range r.s.taskInputs {
		matched := false
		for _, input := range inputs {
			if input.CommentID == current.ID && input.CommentVersion == current.Version {
				matched = true
				break
			}
		}
		if !matched {
			continue
		}
		task := r.s.agentTasks[taskID]
		key := task.AgentRef + "\x00" + task.TeamRole
		if seen[key] {
			continue
		}
		seen[key] = true
		target := store.CommentTarget{TargetType: controlmodel.AssigneeAgent, TargetRef: task.AgentRef,
			AgentRef: task.AgentRef, TeamID: task.TeamID, TeamRole: task.TeamRole,
			RouteType: controlmodel.RouteFollowUp}
		if task.TeamID != nil {
			target.TargetType, target.TargetRef = controlmodel.AssigneeTeam, task.TeamID.String()
		}
		routed, coalesced := r.routeTaskLocked(r.s.issues[next.IssueID], next, target)
		route := controlmodel.CommentRoute{ID: uuid.New(), Tenant: next.Tenant, Namespace: next.Namespace,
			IssueID: next.IssueID, CommentID: next.ID, CommentVersion: next.Version,
			TargetType: target.TargetType, TargetRef: target.TargetRef, RouteType: controlmodel.RouteFollowUp,
			TaskID: &routed.ID, Outcome: controlmodel.RouteQueued, ReasonCode: "comment_correction", CreatedAt: next.UpdatedAt}
		if coalesced {
			route.Outcome = controlmodel.RouteCoalesced
		}
		r.s.commentRoutes[next.ID] = append(r.s.commentRoutes[next.ID], route)
	}
	if issue := r.s.issues[next.IssueID]; issue != nil {
		issue.Version, issue.UpdatedAt = issue.Version+1, next.UpdatedAt
		r.appendActivityLocked(&controlmodel.Activity{Tenant: issue.Tenant, Namespace: issue.Namespace,
			IssueID: &issue.ID, Actor: next.Author, Action: "comment.updated", ObjectType: "comment", ObjectRef: next.ID.String()})
	}
	r.enqueueEventLocked(next.Tenant, "comment", next.ID, "comment.updated.v1", next, fmt.Sprintf("comment-updated:%s:%d", next.ID, next.Version))
	out := cloneComment(next)
	out.Mentions = append([]controlmodel.Mention(nil), r.s.commentMentions[next.ID]...)
	out.Routes = append([]controlmodel.CommentRoute(nil), r.s.commentRoutes[next.ID]...)
	return out, nil
}

func (r *collaborationRepo) DeleteComment(_ context.Context, id uuid.UUID, expectedVersion int64, actor controlmodel.Actor) (*controlmodel.Comment, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	current := r.s.comments[id]
	if current == nil {
		return nil, store.ErrNotFound
	}
	if expectedVersion > 0 && current.Version != expectedVersion {
		return nil, store.ErrConflict
	}
	if current.DeletedAt != nil {
		return cloneComment(current), nil
	}
	next := cloneComment(current)
	now := time.Now().UTC()
	next.Content = "[deleted]"
	next.DeletedAt, next.UpdatedAt = &now, now
	next.Version++
	r.s.comments[id] = next
	if issue := r.s.issues[next.IssueID]; issue != nil {
		issue.Version, issue.UpdatedAt = issue.Version+1, now
		r.appendActivityLocked(&controlmodel.Activity{Tenant: issue.Tenant, Namespace: issue.Namespace,
			IssueID: &issue.ID, Actor: actor, Action: "comment.deleted", ObjectType: "comment", ObjectRef: id.String()})
		r.enqueueEventLocked(issue.Tenant, "comment", id, "comment.deleted.v1", next, fmt.Sprintf("comment-deleted:%s:%d", id, next.Version))
	}
	return cloneComment(next), nil
}

func (r *collaborationRepo) ResolveComment(_ context.Context, id uuid.UUID, expectedVersion int64, actor controlmodel.Actor, resolved bool) (*controlmodel.Comment, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	current := r.s.comments[id]
	if current == nil {
		return nil, store.ErrNotFound
	}
	if expectedVersion > 0 && current.Version != expectedVersion {
		return nil, store.ErrConflict
	}
	next := cloneComment(current)
	next.Version++
	next.UpdatedAt = time.Now().UTC()
	if resolved {
		now := next.UpdatedAt
		next.ResolvedAt, next.ResolvedBy = &now, &actor
	} else {
		next.ResolvedAt, next.ResolvedBy = nil, nil
	}
	r.s.comments[id] = next
	if issue := r.s.issues[next.IssueID]; issue != nil {
		action := "comment.unresolved"
		if resolved {
			action = "comment.resolved"
		}
		r.appendActivityLocked(&controlmodel.Activity{Tenant: issue.Tenant, Namespace: issue.Namespace,
			IssueID: &issue.ID, Actor: actor, Action: action, ObjectType: "comment", ObjectRef: next.ID.String()})
		r.enqueueEventLocked(issue.Tenant, "comment", next.ID, "comment.resolved.v1", next, fmt.Sprintf("comment-resolved:%s:%d", next.ID, next.Version))
	}
	return cloneComment(next), nil
}

func (r *collaborationRepo) GetAgentTask(_ context.Context, id uuid.UUID) (*controlmodel.AgentTask, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	task := r.s.agentTasks[id]
	if task == nil {
		return nil, store.ErrNotFound
	}
	out := cloneAgentTask(task)
	out.Inputs = append([]controlmodel.AgentTaskInput(nil), r.s.taskInputs[id]...)
	return out, nil
}

func (r *collaborationRepo) CreateRunAgentTask(_ context.Context, req store.RunTaskRequest) (*controlmodel.AgentTask, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	run, node, issue := r.s.runs[req.RunID], r.s.runNodes[req.NodeID], r.s.issues[req.IssueID]
	if run == nil || node == nil || issue == nil || node.RunID != run.ID || node.IssueID == nil || *node.IssueID != issue.ID || req.AgentRef == "" {
		return nil, store.ErrNotFound
	}
	now := time.Now().UTC()
	task := &controlmodel.AgentTask{ID: uuid.New(), Tenant: run.Tenant, Namespace: run.Namespace,
		IssueID: issue.ID, OrchestrationRunID: run.ID, RunNodeID: node.ID, AgentRef: req.AgentRef,
		Status: controlmodel.AgentTaskQueued, Priority: req.Priority, TriggerType: "orchestration_node",
		TeamID: req.TeamID, TeamRole: req.TeamRole, LeaderTask: req.Leader, Originator: req.Originator,
		CausationID: node.ID.String(), CorrelationID: run.ID.String(), Version: 1, CreatedAt: now}
	if req.RuntimeCandidate != nil {
		task.RuntimeBinding, _ = json.Marshal(controlmodel.RuntimeDispatchSnapshot{
			Binding: req.RuntimeCandidate.Binding, Capabilities: req.RuntimeCandidate.RequiredCapabilities,
			SecurityConstraints: req.RuntimeCandidate.SecurityConstraints, SelectionSource: "node"})
	}
	if task.Priority == 0 {
		task.Priority = memoryIssuePriority(issue.Priority)
	}
	if req.Originator.Type == controlmodel.ActorHuman {
		task.AccountableHumanRef = req.Originator.Ref
	}
	r.s.agentTasks[task.ID] = task
	event := &controlmodel.RunEvent{ID: uuid.New(), RunID: run.ID, Tenant: run.Tenant, Namespace: run.Namespace,
		Sequence: int64(len(r.s.runEvents[run.ID]) + 1), NodeID: &node.ID, AgentTaskID: &task.ID,
		Type: "agent-task.queued", Actor: req.Originator, CausationID: node.ID.String(),
		CorrelationID: run.ID.String(), IdempotencyKey: "agent-task-queued:" + task.ID.String(), OccurredAt: now}
	r.s.runEvents[run.ID] = append(r.s.runEvents[run.ID], event)
	r.enqueueEventLocked(task.Tenant, "agent-task", task.ID, "agent-task.queued.v1", task, "agent-task-queued:"+task.ID.String())
	return cloneAgentTask(task), nil
}

func memoryIssuePriority(priority string) int32 {
	switch priority {
	case "urgent":
		return 100
	case "high":
		return 75
	case "low":
		return 25
	default:
		return 50
	}
}

func (r *collaborationRepo) ListAgentTasks(ctx context.Context, filter store.AgentTaskFilter) ([]*controlmodel.AgentTask, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*controlmodel.AgentTask, 0)
	for _, task := range r.s.agentTasks {
		if !r.s.canReadIssueLocked(ctx, task.IssueID) {
			continue
		}
		if filter.Tenant != "" && task.Tenant != filter.Tenant || filter.Namespace != "" && task.Namespace != filter.Namespace || filter.IssueID != uuid.Nil && task.IssueID != filter.IssueID || filter.RunID != uuid.Nil && task.OrchestrationRunID != filter.RunID || filter.NodeID != uuid.Nil && task.RunNodeID != filter.NodeID || filter.AgentRef != "" && task.AgentRef != filter.AgentRef || filter.TeamID != uuid.Nil && (task.TeamID == nil || *task.TeamID != filter.TeamID) || filter.Status != "" && task.Status != filter.Status {
			continue
		}
		copy := cloneAgentTask(task)
		copy.Inputs = append([]controlmodel.AgentTaskInput(nil), r.s.taskInputs[task.ID]...)
		out = append(out, copy)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.Before(out[j].CreatedAt) })
	return page(out, filter.Offset, filter.Limit), nil
}

func (r *collaborationRepo) ClaimAgentTask(_ context.Context, claim store.TaskClaim) (*controlmodel.AgentTask, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	task := r.s.agentTasks[claim.TaskID]
	if task == nil {
		return nil, store.ErrNotFound
	}
	if task.Status != controlmodel.AgentTaskQueued || claim.ExpectedVersion > 0 && task.Version != claim.ExpectedVersion {
		return nil, store.ErrConflict
	}
	now := time.Now().UTC()
	task.Status, task.RuntimeBinding, task.SessionID = controlmodel.AgentTaskDispatched, cloneJSON(claim.RuntimeBinding), claim.SessionID
	task.ErrorCode, task.ErrorMessage = "", ""
	task.Version++
	task.DispatchedAt = &now
	inputs := r.s.taskInputs[task.ID]
	for i := range inputs {
		if inputs[i].State == controlmodel.TaskInputPlanned {
			inputs[i].State, inputs[i].DeliveredAt = controlmodel.TaskInputDelivered, &now
		}
	}
	r.s.taskInputs[task.ID] = inputs
	r.enqueueEventLocked(task.Tenant, "agent-task", task.ID, "agent-task.dispatched.v1", task, fmt.Sprintf("agent-task-dispatched:%s:%d", task.ID, task.Version))
	out := cloneAgentTask(task)
	out.Inputs = append([]controlmodel.AgentTaskInput(nil), inputs...)
	return out, nil
}

func (r *collaborationRepo) ClaimAgentTaskWithAttempt(_ context.Context, claim store.TaskClaim, execution *controlmodel.ExecutionAttempt) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	if execution == nil || execution.BackendKind == "" {
		return nil, nil, store.ErrConflict
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	task := r.s.agentTasks[claim.TaskID]
	if task == nil {
		return nil, nil, store.ErrNotFound
	}
	if task.Status != controlmodel.AgentTaskQueued || claim.ExpectedVersion > 0 && task.Version != claim.ExpectedVersion {
		return nil, nil, store.ErrConflict
	}
	now := time.Now().UTC()
	execution.ID = nonNilUUID(execution.ID)
	task.Status, task.RuntimeBinding, task.SessionID = controlmodel.AgentTaskDispatched, cloneJSON(claim.RuntimeBinding), claim.SessionID
	task.ErrorCode, task.ErrorMessage = "", ""
	task.CurrentAttemptID = &execution.ID
	task.Version++
	task.DispatchedAt = &now
	inputs := r.s.taskInputs[task.ID]
	for i := range inputs {
		if inputs[i].State == controlmodel.TaskInputPlanned {
			inputs[i].State, inputs[i].DeliveredAt = controlmodel.TaskInputDelivered, &now
		}
	}
	r.s.taskInputs[task.ID] = inputs
	created := cloneExecution(execution)
	created.ID, created.AgentTaskID = nonNilUUID(created.ID), task.ID
	created.Tenant, created.Namespace = task.Tenant, task.Namespace
	created.RunID, created.NodeID = task.OrchestrationRunID, task.RunNodeID
	created.RuntimeBinding = cloneJSON(claim.RuntimeBinding)
	var dispatch controlmodel.RuntimeDispatchSnapshot
	if json.Unmarshal(claim.RuntimeBinding, &dispatch) == nil {
		created.AgentID, created.BindingID = dispatch.Binding.AgentID, dispatch.Binding.BindingID
	}
	nextAttempt, nextDispatchGeneration := int32(1), int64(1)
	for _, candidate := range r.s.executions {
		if candidate.AgentTaskID != task.ID {
			continue
		}
		if candidate.Attempt >= nextAttempt {
			nextAttempt = candidate.Attempt + 1
		}
		if candidate.DispatchGeneration >= nextDispatchGeneration {
			nextDispatchGeneration = candidate.DispatchGeneration + 1
		}
	}
	if created.Attempt <= 0 {
		created.Attempt = nextAttempt
	}
	if created.DispatchGeneration <= 0 {
		created.DispatchGeneration = nextDispatchGeneration
	}
	if created.State == "" {
		created.State = controlmodel.ExecutionQueued
	}
	created.Version = 1
	created.CreatedAt, created.UpdatedAt = now, now
	r.s.executions[created.ID] = cloneExecution(created)
	event := &controlmodel.RunEvent{ID: uuid.New(), RunID: task.OrchestrationRunID, Tenant: task.Tenant,
		Namespace: task.Namespace, Sequence: int64(len(r.s.runEvents[task.OrchestrationRunID]) + 1),
		NodeID: &task.RunNodeID, AgentTaskID: &task.ID, AttemptID: &created.ID,
		Type: "attempt.queued", Actor: controlmodel.Actor{Type: controlmodel.ActorSystem},
		CausationID: task.CausationID, CorrelationID: task.CorrelationID,
		IdempotencyKey: "attempt-queued:" + created.ID.String(), OccurredAt: now}
	r.s.runEvents[task.OrchestrationRunID] = append(r.s.runEvents[task.OrchestrationRunID], event)
	r.enqueueEventLocked(task.Tenant, "agent-task", task.ID, "agent-task.dispatched.v1", task, fmt.Sprintf("agent-task-dispatched:%s:%d", task.ID, task.Version))
	out := cloneAgentTask(task)
	out.Inputs = append([]controlmodel.AgentTaskInput(nil), inputs...)
	return out, created, nil
}

func (r *collaborationRepo) AcknowledgeTaskInputs(_ context.Context, taskID uuid.UUID, inputIDs []uuid.UUID) ([]controlmodel.AgentTaskInput, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if r.s.agentTasks[taskID] == nil {
		return nil, store.ErrNotFound
	}
	set := uuidSet(inputIDs)
	now := time.Now().UTC()
	inputs := r.s.taskInputs[taskID]
	for i := range inputs {
		if set[inputs[i].ID] && (inputs[i].State == controlmodel.TaskInputDelivered || inputs[i].State == controlmodel.TaskInputAcknowledged) {
			inputs[i].State, inputs[i].AcknowledgedAt = controlmodel.TaskInputAcknowledged, &now
		}
	}
	r.s.taskInputs[taskID] = inputs
	return append([]controlmodel.AgentTaskInput(nil), inputs...), nil
}

func (r *collaborationRepo) FailTaskInputDelivery(_ context.Context, taskID uuid.UUID, inputIDs []uuid.UUID, message string, maxAttempts int) ([]controlmodel.AgentTaskInput, error) {
	if maxAttempts <= 0 {
		maxAttempts = 5
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	task := r.s.agentTasks[taskID]
	if task == nil {
		return nil, store.ErrNotFound
	}
	set := uuidSet(inputIDs)
	now := time.Now().UTC()
	inputs := r.s.taskInputs[taskID]
	for i := range inputs {
		if !set[inputs[i].ID] || inputs[i].State == controlmodel.TaskInputProcessed || inputs[i].State == controlmodel.TaskInputDeferred {
			continue
		}
		inputs[i].Attempts++
		inputs[i].LastError = message
		if int(inputs[i].Attempts) >= maxAttempts {
			inputs[i].State, inputs[i].NextAttemptAt = controlmodel.TaskInputDeadLetter, nil
			if task.AccountableHumanRef != "" {
				item := &controlmodel.InboxItem{ID: uuid.New(), Tenant: task.Tenant, Namespace: task.Namespace,
					RecipientType: controlmodel.AssigneeHuman, RecipientRef: task.AccountableHumanRef,
					Type: "dead_letter", Severity: "error", IssueID: &task.IssueID,
					Actor: controlmodel.Actor{Type: controlmodel.ActorSystem}, Title: "Agent input delivery failed",
					Body: message, DedupeKey: "dead-letter:" + inputs[i].ID.String(), CreatedAt: now}
				r.s.inboxItems[item.ID] = item
			}
		} else {
			next := now.Add(time.Duration(1<<minInt(int(inputs[i].Attempts), 8)) * time.Second)
			inputs[i].State, inputs[i].NextAttemptAt = controlmodel.TaskInputRetrying, &next
		}
	}
	r.s.taskInputs[taskID] = inputs
	return append([]controlmodel.AgentTaskInput(nil), inputs...), nil
}

func (r *collaborationRepo) ReplayDeadLetterInputs(_ context.Context, taskID uuid.UUID, inputIDs []uuid.UUID, actor controlmodel.Actor) (*controlmodel.AgentTask, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	source := r.s.agentTasks[taskID]
	if source == nil {
		return nil, store.ErrNotFound
	}
	set := uuidSet(inputIDs)
	selected := make([]controlmodel.AgentTaskInput, 0)
	for _, input := range r.s.taskInputs[taskID] {
		if set[input.ID] && input.State == controlmodel.TaskInputDeadLetter {
			selected = append(selected, input)
		}
	}
	if len(selected) == 0 {
		return nil, store.ErrConflict
	}
	retryOf := source.ID
	replay := r.newTaskLocked(r.s.issues[source.IssueID], source.AgentRef, "manual_replay", source.TriggerCommentID, source.TeamID, source.TeamRole, source.LeaderTask, actor, nil, &retryOf)
	replay.ParentTaskID = &source.ID
	for _, old := range selected {
		input := old
		input.ID, input.TaskID, input.State = uuid.New(), replay.ID, controlmodel.TaskInputPlanned
		input.Attempts, input.LastError, input.NextAttemptAt = 0, "", nil
		input.DeliveredAt, input.AcknowledgedAt, input.ProcessedAt, input.ResponseCommentID = nil, nil, nil, nil
		input.CreatedAt = time.Now().UTC()
		r.s.taskInputs[replay.ID] = append(r.s.taskInputs[replay.ID], input)
	}
	return cloneAgentTask(replay), nil
}

func (r *collaborationRepo) RequeueRetryableInputs(_ context.Context, now time.Time, limit int) ([]uuid.UUID, error) {
	if limit <= 0 {
		limit = 100
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	seen := map[uuid.UUID]bool{}
	out := make([]uuid.UUID, 0)
	for taskID, inputs := range r.s.taskInputs {
		for i := range inputs {
			if inputs[i].State != controlmodel.TaskInputRetrying || inputs[i].NextAttemptAt == nil || inputs[i].NextAttemptAt.After(now) {
				continue
			}
			inputs[i].State, inputs[i].NextAttemptAt = controlmodel.TaskInputPlanned, nil
			if !seen[taskID] && len(out) < limit {
				seen[taskID] = true
				out = append(out, taskID)
				if task := r.s.agentTasks[taskID]; task != nil && task.Status == controlmodel.AgentTaskDispatched {
					task.Status, task.Version, task.DispatchedAt = controlmodel.AgentTaskQueued, task.Version+1, nil
				}
			}
		}
		r.s.taskInputs[taskID] = inputs
		if len(out) >= limit {
			break
		}
	}
	return out, nil
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func (r *collaborationRepo) StartAgentTask(_ context.Context, id uuid.UUID, expectedVersion int64) (*controlmodel.AgentTask, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	task := r.s.agentTasks[id]
	if task == nil {
		return nil, store.ErrNotFound
	}
	if expectedVersion > 0 && task.Version != expectedVersion || !controlmodel.CanTransitionAgentTask(task.Status, controlmodel.AgentTaskRunning) {
		return nil, store.ErrConflict
	}
	now := time.Now().UTC()
	if task.CurrentAttemptID != nil {
		attempt := r.s.executions[*task.CurrentAttemptID]
		if attempt == nil {
			return nil, store.ErrNotFound
		}
		if attempt.State == controlmodel.ExecutionAssigned {
			attempt.State, attempt.Version = controlmodel.ExecutionPreparing, attempt.Version+1
		}
		if attempt.State == controlmodel.ExecutionWaiting {
			attempt.State = controlmodel.ExecutionPreparing
		}
		if !controlmodel.CanTransitionExecutionAttempt(attempt.State, controlmodel.ExecutionRunning) {
			return nil, store.ErrConflict
		}
		attempt.State, attempt.Version, attempt.UpdatedAt, attempt.HeartbeatAt = controlmodel.ExecutionRunning, attempt.Version+1, now, &now
		if attempt.StartedAt == nil {
			attempt.StartedAt = &now
		}
	}
	if node := r.s.runNodes[task.RunNodeID]; node != nil && node.State == controlmodel.RunNodeReady {
		node.State, node.Version, node.UpdatedAt, node.StartedAt = controlmodel.RunNodeRunning, node.Version+1, now, &now
	}
	if run := r.s.runs[task.OrchestrationRunID]; run != nil && run.State == controlmodel.RunWaiting {
		run.State, run.WaitReason, run.Version, run.UpdatedAt = controlmodel.RunRunning, "", run.Version+1, now
	}
	task.Status, task.Version, task.StartedAt = controlmodel.AgentTaskRunning, task.Version+1, &now
	if issue := r.s.issues[task.IssueID]; issue != nil && store.AgentTaskMayAdvanceIssueLifecycle(issue, task) &&
		(issue.Status == controlmodel.IssueBacklog || issue.Status == controlmodel.IssueTodo ||
			store.AgentTaskReopensReview(issue, task) ||
			issue.Status == controlmodel.IssueBlocked && (!task.LeaderTask || issue.ParentIssueID == nil)) {
		previousStatus := issue.Status
		issue.Status, issue.Version, issue.UpdatedAt = controlmodel.IssueInProgress, issue.Version+1, now
		actor := controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
		details, _ := json.Marshal(map[string]string{"from": string(previousStatus),
			"to": string(controlmodel.IssueInProgress), "reason": "agent task started"})
		r.appendActivityLocked(&controlmodel.Activity{Tenant: issue.Tenant,
			Namespace: issue.Namespace, IssueID: &issue.ID, Actor: actor,
			Action: "issue.status_changed", ObjectType: "issue", ObjectRef: issue.ID.String(),
			CausationID: task.ID.String(), CorrelationID: task.CorrelationID, Details: details})
		r.notifyIssueInboxLocked(issue, previousStatus, actor, "Agent task started", nil)
		r.enqueueEventLocked(issue.Tenant, "issue", issue.ID, "issue.status-changed.v1",
			map[string]any{"issue": cloneIssue(issue), "previousStatus": previousStatus},
			fmt.Sprintf("issue-status:%s:%d", issue.ID, issue.Version))
	}
	return cloneAgentTask(task), nil
}

func (r *collaborationRepo) CompleteAgentTask(_ context.Context, id uuid.UUID, completion store.TaskCompletion) (*controlmodel.AgentTask, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	task := r.s.agentTasks[id]
	if task == nil {
		return nil, store.ErrNotFound
	}
	if completion.ExpectedVersion > 0 && task.Version != completion.ExpectedVersion || !controlmodel.CanTransitionAgentTask(task.Status, controlmodel.AgentTaskCompleted) {
		return nil, store.ErrConflict
	}
	var attempt *controlmodel.ExecutionAttempt
	if task.CurrentAttemptID != nil {
		attempt = r.s.executions[*task.CurrentAttemptID]
		if attempt == nil || completion.AttemptID != uuid.Nil && completion.AttemptID != attempt.ID ||
			completion.DispatchGeneration > 0 && completion.DispatchGeneration != attempt.DispatchGeneration ||
			completion.LeaseToken != "" && (completion.LeaseToken != attempt.LeaseToken || completion.FencingToken != attempt.FencingToken) ||
			!controlmodel.CanTransitionExecutionAttempt(attempt.State, controlmodel.ExecutionSucceeded) {
			return nil, store.ErrConflict
		}
	}
	actor := controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
	if completion.ResponseCommentID != nil {
		comment := r.s.comments[*completion.ResponseCommentID]
		if comment == nil || comment.IssueID != task.IssueID || comment.SourceTaskID == nil ||
			*comment.SourceTaskID != task.ID || comment.Type != controlmodel.CommentResult {
			return nil, store.ErrConflict
		}
		actor = comment.Author
	}
	processed, deferred := uuidSet(completion.ProcessedInputIDs), uuidSet(completion.DeferredInputIDs)
	now := time.Now().UTC()
	inputs := r.s.taskInputs[id]
	for i := range inputs {
		switch {
		case processed[inputs[i].ID]:
			inputs[i].State, inputs[i].ProcessedAt, inputs[i].ResponseCommentID = controlmodel.TaskInputProcessed, &now, completion.ResponseCommentID
		case deferred[inputs[i].ID]:
			inputs[i].State = controlmodel.TaskInputDeferred
		case inputs[i].State != controlmodel.TaskInputProcessed && inputs[i].State != controlmodel.TaskInputDeferred:
			return nil, store.ErrConflict
		}
	}
	r.s.taskInputs[id] = inputs
	if attempt != nil {
		usage := store.AttemptUsage(completion.Usage, completion.Result)
		attempt.State, attempt.Result, attempt.Checkpoint = controlmodel.ExecutionSucceeded,
			cloneJSON(completion.Result), cloneJSON(completion.Checkpoint)
		attempt.Usage = cloneJSON(usage)
		attempt.Version++
		attempt.UpdatedAt, attempt.CompletedAt, attempt.LeaseExpiresAt = now, &now, nil
		if run := r.s.runs[task.OrchestrationRunID]; run != nil {
			run.Usage = store.MergeUsage(run.Usage, usage)
		}
	}
	task.Status, task.Result, task.Version, task.CompletedAt = controlmodel.AgentTaskCompleted, cloneJSON(completion.Result), task.Version+1, &now
	task.ErrorCode, task.ErrorMessage = "", ""
	if issue := r.s.issues[task.IssueID]; issue != nil {
		issue.Version, issue.UpdatedAt = issue.Version+1, now
	}
	if err := r.reconcileCompletedTaskLocked(task, attempt, completion.Result, actor, now); err != nil {
		return nil, err
	}
	r.appendActivityLocked(&controlmodel.Activity{Tenant: task.Tenant, Namespace: task.Namespace,
		IssueID: &task.IssueID, Actor: actor, Action: "agent_task.completed", ObjectType: "agent_task",
		ObjectRef: task.ID.String(), CausationID: task.CausationID, CorrelationID: task.CorrelationID})
	r.enqueueEventLocked(task.Tenant, "agent-task", task.ID, "agent-task.completed.v1", task, fmt.Sprintf("agent-task-completed:%s:%d", task.ID, task.Version))
	return cloneAgentTask(task), nil
}

func (r *collaborationRepo) CompleteAgentTaskWithComment(_ context.Context, id uuid.UUID, completion store.TaskCompletion, comment *controlmodel.Comment, targets []store.CommentTarget) (*controlmodel.AgentTask, *controlmodel.Comment, error) {
	if comment == nil || strings.TrimSpace(comment.Content) == "" {
		return nil, nil, store.ErrConflict
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	task := r.s.agentTasks[id]
	if task == nil {
		return nil, nil, store.ErrNotFound
	}
	if completion.ExpectedVersion > 0 && task.Version != completion.ExpectedVersion || !controlmodel.CanTransitionAgentTask(task.Status, controlmodel.AgentTaskCompleted) {
		return nil, nil, store.ErrConflict
	}
	var attempt *controlmodel.ExecutionAttempt
	if task.CurrentAttemptID != nil {
		attempt = r.s.executions[*task.CurrentAttemptID]
		if attempt == nil || completion.AttemptID != uuid.Nil && completion.AttemptID != attempt.ID ||
			completion.DispatchGeneration > 0 && completion.DispatchGeneration != attempt.DispatchGeneration ||
			completion.LeaseToken != "" && (completion.LeaseToken != attempt.LeaseToken || completion.FencingToken != attempt.FencingToken) ||
			!controlmodel.CanTransitionExecutionAttempt(attempt.State, controlmodel.ExecutionSucceeded) {
			return nil, nil, store.ErrConflict
		}
	}
	issue := r.s.issues[task.IssueID]
	if issue == nil {
		return nil, nil, store.ErrNotFound
	}
	processed, deferred := uuidSet(completion.ProcessedInputIDs), uuidSet(completion.DeferredInputIDs)
	inputs := r.s.taskInputs[id]
	for _, input := range inputs {
		if !processed[input.ID] && !deferred[input.ID] && input.State != controlmodel.TaskInputProcessed && input.State != controlmodel.TaskInputDeferred {
			return nil, nil, store.ErrConflict
		}
	}
	now := time.Now().UTC()
	created := cloneComment(comment)
	created.ID = nonNilUUID(created.ID)
	created.Tenant, created.Namespace, created.IssueID = issue.Tenant, issue.Namespace, issue.ID
	created.SourceTaskID = &task.ID
	// A leader may finish a decision turn by yielding to delegated work.
	// Keep that informational comment distinct from the worker's deliverable.
	if task.TriggerType == controlmodel.AgentTaskReviewComment {
		created.Type = controlmodel.CommentGeneral
	} else if !task.LeaderTask || created.Type != controlmodel.CommentStatus {
		created.Type = controlmodel.CommentResult
	}
	if attempt != nil {
		created.SourceAttemptID = &attempt.ID
	}
	if created.ParentID != nil {
		parent := r.s.comments[*created.ParentID]
		if parent == nil || parent.IssueID != issue.ID {
			return nil, nil, store.ErrNotFound
		}
		created.ThreadRootID = parent.ThreadRootID
	} else {
		created.ThreadRootID = created.ID
	}
	created.Version, created.CreatedAt, created.UpdatedAt = 1, now, now
	r.s.comments[created.ID] = created
	routes := make([]controlmodel.CommentRoute, 0, len(targets))
	for _, target := range targets {
		route := controlmodel.CommentRoute{ID: uuid.New(), Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: issue.ID, CommentID: created.ID, CommentVersion: created.Version, TargetType: target.TargetType, TargetRef: target.TargetRef, RouteType: target.RouteType, CreatedAt: now}
		if target.Blocked {
			route.Outcome, route.ReasonCode = controlmodel.RouteBlocked, target.ReasonCode
			if task.AccountableHumanRef != "" {
				item := &controlmodel.InboxItem{ID: uuid.New(), Tenant: issue.Tenant, Namespace: issue.Namespace,
					RecipientType: controlmodel.AssigneeHuman, RecipientRef: task.AccountableHumanRef,
					Type: "routing_blocked", Severity: "warning", IssueID: &issue.ID, CommentID: &created.ID,
					Actor: created.Author, Title: "Agent collaboration blocked", Body: target.ReasonCode,
					DedupeKey: "route-blocked:" + created.ID.String() + ":" + target.TargetRef, CreatedAt: now}
				r.s.inboxItems[item.ID] = item
			}
		} else if target.TargetType == controlmodel.AssigneeHuman {
			route.Outcome = controlmodel.RouteQueued
			itemType, title := store.CommentInboxType(target.RouteType, true), issue.Title
			if target.RouteType == controlmodel.RouteReviewRequest {
				itemType, title = "review_request", "Review requested: "+issue.Title
			}
			item := &controlmodel.InboxItem{ID: uuid.New(), Tenant: issue.Tenant, Namespace: issue.Namespace,
				RecipientType: controlmodel.AssigneeHuman, RecipientRef: target.TargetRef,
				Type: itemType, Severity: "attention", IssueID: &issue.ID, CommentID: &created.ID,
				Actor: created.Author, Title: title, Body: created.Content,
				DedupeKey: "comment:" + created.ID.String() + ":" + target.TargetRef, CreatedAt: now}
			r.s.inboxItems[item.ID] = item
		} else if target.AgentRef == task.AgentRef {
			route.Outcome, route.ReasonCode = controlmodel.RouteSuppressed, "self_trigger"
		} else {
			routedTask, coalesced := r.routeTaskLocked(issue, created, target)
			route.TaskID = &routedTask.ID
			if coalesced {
				route.Outcome = controlmodel.RouteCoalesced
			} else {
				route.Outcome = controlmodel.RouteQueued
			}
		}
		routes = append(routes, route)
	}
	r.s.commentRoutes[created.ID] = routes
	for i := range inputs {
		switch {
		case processed[inputs[i].ID]:
			inputs[i].State, inputs[i].ProcessedAt, inputs[i].ResponseCommentID = controlmodel.TaskInputProcessed, &now, &created.ID
		case deferred[inputs[i].ID]:
			inputs[i].State = controlmodel.TaskInputDeferred
		}
	}
	r.s.taskInputs[id] = inputs
	if attempt != nil {
		usage := store.AttemptUsage(completion.Usage, completion.Result)
		attempt.State, attempt.Result, attempt.Checkpoint = controlmodel.ExecutionSucceeded, cloneJSON(completion.Result), cloneJSON(completion.Checkpoint)
		attempt.Usage = cloneJSON(usage)
		attempt.Version++
		attempt.UpdatedAt, attempt.CompletedAt, attempt.LeaseExpiresAt = now, &now, nil
		if run := r.s.runs[task.OrchestrationRunID]; run != nil {
			run.Usage = store.MergeUsage(run.Usage, usage)
		}
	}
	task.Status, task.Result, task.Version, task.CompletedAt = controlmodel.AgentTaskCompleted, cloneJSON(completion.Result), task.Version+1, &now
	task.ErrorCode, task.ErrorMessage = "", ""
	issue.Version, issue.UpdatedAt = issue.Version+1, now
	if err := r.reconcileCompletedTaskLocked(task, attempt, completion.Result, created.Author, now); err != nil {
		return nil, nil, err
	}
	r.appendActivityLocked(&controlmodel.Activity{Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: &issue.ID, Actor: created.Author, Action: "agent_task.completed", ObjectType: "agent_task", ObjectRef: task.ID.String(), CausationID: task.CausationID, CorrelationID: task.CorrelationID})
	r.enqueueEventLocked(issue.Tenant, "comment", created.ID, "comment.created.v1", map[string]any{"comment": created, "routes": routes}, "comment-created:"+created.ID.String())
	r.enqueueEventLocked(task.Tenant, "agent-task", task.ID, "agent-task.completed.v1", task, fmt.Sprintf("agent-task-completed:%s:%d", task.ID, task.Version))
	out := cloneComment(created)
	out.Routes = routes
	return cloneAgentTask(task), out, nil
}

func (r *collaborationRepo) reconcileCompletedTaskLocked(task *controlmodel.AgentTask,
	attempt *controlmodel.ExecutionAttempt, output json.RawMessage, actor controlmodel.Actor, now time.Time) error {
	node := r.s.runNodes[task.RunNodeID]
	run := r.s.runs[task.OrchestrationRunID]
	if node == nil || run == nil {
		return store.ErrNotFound
	}
	next := controlmodel.RunNodeSucceeded
	if node.Type == controlmodel.RunNodeTeam && task.LeaderTask && task.TriggerType != controlmodel.AgentTaskReviewComment {
		next = controlmodel.RunNodeWaiting
	}
	coordinatorAlreadyTerminal := node.Type == controlmodel.RunNodeTeam && task.LeaderTask &&
		controlmodel.IsRunNodeTerminal(node.State)
	if !coordinatorAlreadyTerminal {
		if node.State == controlmodel.RunNodeReady {
			node.State, node.Version, node.UpdatedAt = controlmodel.RunNodeRunning, node.Version+1, now
			node.StartedAt = &now
		}
		if !controlmodel.CanTransitionRunNode(node.State, next) {
			return store.ErrConflict
		}
		node.State, node.Output, node.WaitReason, node.Version, node.UpdatedAt = next, cloneJSON(output), "", node.Version+1, now
		if next == controlmodel.RunNodeSucceeded {
			node.CompletedAt = &now
		}
	}
	appendEvent := func(event *controlmodel.RunEvent) {
		event.ID, event.RunID, event.Tenant, event.Namespace = uuid.New(), run.ID, run.Tenant, run.Namespace
		event.Sequence, event.Actor, event.OccurredAt = int64(len(r.s.runEvents[run.ID])+1), actor, now
		r.s.runEvents[run.ID] = append(r.s.runEvents[run.ID], event)
	}
	if attempt != nil {
		appendEvent(&controlmodel.RunEvent{NodeID: &node.ID, AgentTaskID: &task.ID, AttemptID: &attempt.ID,
			Type: "attempt.succeeded", IdempotencyKey: "attempt-succeeded:" + attempt.ID.String(),
			CausationID: task.CausationID, CorrelationID: task.CorrelationID})
	}
	if !coordinatorAlreadyTerminal {
		payload, _ := json.Marshal(map[string]any{"output": output})
		appendEvent(&controlmodel.RunEvent{NodeID: &node.ID, AgentTaskID: &task.ID, Type: "node." + string(next),
			Payload:        payload,
			IdempotencyKey: "node-" + string(next) + ":" + node.ID.String(), CausationID: task.CausationID,
			CorrelationID: task.CorrelationID})
	}
	if coordinatorAlreadyTerminal {
		return nil
	}
	if next == controlmodel.RunNodeWaiting {
		if run.State == controlmodel.RunRunning {
			run.State, run.WaitReason, run.Version, run.UpdatedAt = controlmodel.RunWaiting, "team_coordinator", run.Version+1, now
		}
		return nil
	}
	for _, candidate := range r.s.runNodes {
		if candidate.RunID == run.ID && !controlmodel.IsRunNodeTerminal(candidate.State) {
			return nil
		}
	}
	if run.State == controlmodel.RunRunning || run.State == controlmodel.RunWaiting {
		run.State, run.Output, run.WaitReason, run.Version, run.UpdatedAt, run.CompletedAt = controlmodel.RunSucceeded,
			cloneJSON(output), "", run.Version+1, now, &now
		appendEvent(&controlmodel.RunEvent{Type: "run.succeeded",
			IdempotencyKey: "run-succeeded:" + run.ID.String(), CausationID: task.CausationID,
			CorrelationID: task.CorrelationID})
		if run.ParentRunID == nil {
			if issue := r.s.issues[run.RootIssueID]; issue != nil && issue.Status == controlmodel.IssueInProgress &&
				store.AgentTaskMayAdvanceIssueLifecycle(issue, task) {
				target, reason := controlmodel.IssueInReview, "execution completed; awaiting acceptance"
				if issue.CompletionPolicy == controlmodel.IssueCompletionAutomatic {
					target, reason = controlmodel.IssueDone, "automatic Endpoint Job execution completed"
					issue.ResolvedAt = &now
				}
				issue.Status, issue.Version, issue.UpdatedAt = target, issue.Version+1, now
				details, _ := json.Marshal(map[string]string{"from": string(controlmodel.IssueInProgress),
					"to": string(target), "reason": reason})
				r.appendActivityLocked(&controlmodel.Activity{Tenant: issue.Tenant, Namespace: issue.Namespace,
					IssueID: &issue.ID, Actor: actor, Action: "issue.status_changed", ObjectType: "issue",
					ObjectRef: issue.ID.String(), Details: details})
				r.notifyIssueInboxLocked(issue, controlmodel.IssueInProgress, actor, reason, task)
				r.enqueueEventLocked(issue.Tenant, "issue", issue.ID, "issue.status-changed.v1",
					map[string]any{"issue": cloneIssue(issue), "previousStatus": controlmodel.IssueInProgress},
					fmt.Sprintf("issue-status:%s:%d", issue.ID, issue.Version))
			}
		}
	}
	return nil
}

func (r *collaborationRepo) FailAgentTask(_ context.Context, id uuid.UUID, expectedVersion int64, code, message string, result ...json.RawMessage) (*controlmodel.AgentTask, error) {
	var partial json.RawMessage
	if len(result) > 0 {
		partial = result[0]
	}
	return r.transitionTask(id, expectedVersion, controlmodel.AgentTaskFailed, partial, code, message)
}

func (r *collaborationRepo) FailAgentTaskWithAttempt(_ context.Context, id uuid.UUID, failure store.TaskFailure) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	task := r.s.agentTasks[id]
	if task == nil || task.CurrentAttemptID == nil || failure.AttemptID == uuid.Nil || *task.CurrentAttemptID != failure.AttemptID {
		return nil, nil, store.ErrConflict
	}
	attempt := r.s.executions[failure.AttemptID]
	if attempt == nil {
		return nil, nil, store.ErrNotFound
	}
	if attempt.State == controlmodel.ExecutionFailed && task.Status == controlmodel.AgentTaskFailed && attempt.FailureCode == failure.Code && attempt.FailureMessage == failure.Message {
		return cloneAgentTask(task), cloneExecution(attempt), nil
	}
	if failure.ExpectedVersion > 0 && task.Version != failure.ExpectedVersion ||
		failure.DispatchGeneration > 0 && attempt.DispatchGeneration != failure.DispatchGeneration ||
		failure.LeaseToken != "" && (attempt.LeaseToken != failure.LeaseToken || attempt.FencingToken != failure.FencingToken) ||
		!controlmodel.CanTransitionExecutionAttempt(attempt.State, controlmodel.ExecutionFailed) ||
		!controlmodel.CanTransitionAgentTask(task.Status, controlmodel.AgentTaskFailed) {
		return nil, nil, store.ErrConflict
	}
	abortManaged := store.ManagedAttemptNeedsAbort(task, attempt, failure.Code)
	now := time.Now().UTC()
	attempt.State, attempt.FailureCode, attempt.FailureMessage = controlmodel.ExecutionFailed, failure.Code, failure.Message
	attempt.Checkpoint, attempt.Usage = cloneJSON(failure.Checkpoint), cloneJSON(failure.Usage)
	if len(failure.Result) > 0 {
		task.Result, attempt.Result = cloneJSON(failure.Result), cloneJSON(failure.Result)
	}
	if run := r.s.runs[task.OrchestrationRunID]; run != nil {
		run.Usage = store.MergeUsage(run.Usage, failure.Usage)
	}
	attempt.LeaseExpiresAt, attempt.CompletedAt = nil, &now
	attempt.Version, attempt.UpdatedAt = attempt.Version+1, now
	task.Status, task.ErrorCode, task.ErrorMessage = controlmodel.AgentTaskFailed, failure.Code, failure.Message
	task.Version, task.CompletedAt = task.Version+1, &now
	for i := range r.s.taskInputs[id] {
		input := &r.s.taskInputs[id][i]
		switch input.State {
		case controlmodel.TaskInputProcessed, controlmodel.TaskInputDeferred, controlmodel.TaskInputDeadLetter, controlmodel.TaskInputBlocked:
		default:
			input.State, input.LastError, input.NextAttemptAt = controlmodel.TaskInputBlocked, failure.Message, nil
		}
	}
	failurePayload, _ := json.Marshal(map[string]any{"code": failure.Code, "message": failure.Message})
	event := &controlmodel.RunEvent{ID: uuid.New(), RunID: task.OrchestrationRunID, Tenant: task.Tenant,
		Namespace: task.Namespace, Sequence: int64(len(r.s.runEvents[task.OrchestrationRunID]) + 1),
		NodeID: &task.RunNodeID, AgentTaskID: &task.ID, AttemptID: &attempt.ID,
		Type: "attempt.failed", Actor: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef},
		Payload:     failurePayload,
		CausationID: task.CausationID, CorrelationID: task.CorrelationID,
		IdempotencyKey: "attempt-failed:" + attempt.ID.String(), OccurredAt: now}
	r.s.runEvents[task.OrchestrationRunID] = append(r.s.runEvents[task.OrchestrationRunID], event)
	r.notifyTaskFailureInboxLocked(task)
	r.enqueueEventLocked(task.Tenant, "agent-task", task.ID, "agent-task.failed.v1", task, fmt.Sprintf("agent-task-failed:%s:%d", task.ID, task.Version))
	if abortManaged {
		r.enqueueEventLocked(task.Tenant, "execution-attempt", attempt.ID,
			"execution-attempt.abort-managed.v1", attempt, "abort-managed-attempt:"+attempt.ID.String())
	}
	return cloneAgentTask(task), cloneExecution(attempt), nil
}

func (r *collaborationRepo) RequeueAgentTaskAfterAttemptFailure(_ context.Context, id uuid.UUID, failure store.TaskFailure) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	task := r.s.agentTasks[id]
	if task == nil || task.CurrentAttemptID == nil || failure.AttemptID == uuid.Nil || *task.CurrentAttemptID != failure.AttemptID {
		return nil, nil, store.ErrConflict
	}
	attempt := r.s.executions[failure.AttemptID]
	if attempt == nil {
		return nil, nil, store.ErrNotFound
	}
	if failure.ExpectedVersion > 0 && task.Version != failure.ExpectedVersion ||
		failure.DispatchGeneration > 0 && attempt.DispatchGeneration != failure.DispatchGeneration ||
		failure.LeaseToken != "" && (attempt.LeaseToken != failure.LeaseToken || attempt.FencingToken != failure.FencingToken) {
		return nil, nil, store.ErrConflict
	}
	if attempt.State == controlmodel.ExecutionFailed && task.Status == controlmodel.AgentTaskQueued {
		return cloneAgentTask(task), cloneExecution(attempt), nil
	}
	if controlmodel.IsExecutionAttemptTerminal(attempt.State) || controlmodel.IsAgentTaskTerminal(task.Status) ||
		!controlmodel.CanTransitionExecutionAttempt(attempt.State, controlmodel.ExecutionFailed) ||
		!controlmodel.CanTransitionAgentTask(task.Status, controlmodel.AgentTaskQueued) {
		return nil, nil, store.ErrConflict
	}
	abortManaged := store.ManagedAttemptNeedsAbort(task, attempt, failure.Code)
	now := time.Now().UTC()
	attempt.State, attempt.FailureCode, attempt.FailureMessage = controlmodel.ExecutionFailed, failure.Code, failure.Message
	attempt.Checkpoint, attempt.Usage = cloneJSON(failure.Checkpoint), cloneJSON(failure.Usage)
	if run := r.s.runs[task.OrchestrationRunID]; run != nil {
		run.Usage = store.MergeUsage(run.Usage, failure.Usage)
	}
	attempt.LeaseExpiresAt, attempt.CompletedAt = nil, &now
	attempt.Version, attempt.UpdatedAt = attempt.Version+1, now
	task.Status, task.ErrorCode, task.ErrorMessage = controlmodel.AgentTaskQueued, failure.Code, failure.Message
	task.Version, task.CompletedAt = task.Version+1, nil
	failurePayload, _ := json.Marshal(map[string]any{"code": failure.Code, "message": failure.Message})
	event := &controlmodel.RunEvent{ID: uuid.New(), RunID: task.OrchestrationRunID, Tenant: task.Tenant,
		Namespace: task.Namespace, Sequence: int64(len(r.s.runEvents[task.OrchestrationRunID]) + 1),
		NodeID: &task.RunNodeID, AgentTaskID: &task.ID, AttemptID: &attempt.ID,
		Type: "attempt.retry_queued", Actor: controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "runtime-scheduler"},
		Payload:     failurePayload,
		CausationID: task.CausationID, CorrelationID: task.CorrelationID,
		IdempotencyKey: "attempt-retry-queued:" + attempt.ID.String(), OccurredAt: now}
	r.s.runEvents[task.OrchestrationRunID] = append(r.s.runEvents[task.OrchestrationRunID], event)
	r.enqueueEventLocked(task.Tenant, "agent-task", task.ID, "agent-task.queued.v1", task, "agent-task-retry-queued:"+attempt.ID.String())
	if abortManaged {
		r.enqueueEventLocked(task.Tenant, "execution-attempt", attempt.ID,
			"execution-attempt.abort-managed.v1", attempt, "abort-managed-attempt:"+attempt.ID.String())
	}
	return cloneAgentTask(task), cloneExecution(attempt), nil
}

func (r *collaborationRepo) CancelAgentTask(_ context.Context, id uuid.UUID, expectedVersion int64) (*controlmodel.AgentTask, error) {
	return r.transitionTask(id, expectedVersion, controlmodel.AgentTaskCancelled, nil, "", "")
}

func (r *collaborationRepo) RetryAgentTask(_ context.Context, id uuid.UUID, actor controlmodel.Actor) (*controlmodel.AgentTask, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	failed := r.s.agentTasks[id]
	if failed == nil {
		return nil, store.ErrNotFound
	}
	if failed.Status != controlmodel.AgentTaskFailed {
		return nil, store.ErrConflict
	}
	retryID := failed.ID
	retry := r.newTaskLocked(r.s.issues[failed.IssueID], failed.AgentRef, "retry", failed.TriggerCommentID, failed.TeamID, failed.TeamRole, failed.LeaderTask, actor, nil, &retryID)
	for _, old := range r.s.taskInputs[failed.ID] {
		input := old
		input.ID, input.TaskID, input.State = uuid.New(), retry.ID, controlmodel.TaskInputPlanned
		input.DeliveredAt, input.AcknowledgedAt, input.ProcessedAt, input.ResponseCommentID = nil, nil, nil, nil
		input.CreatedAt = time.Now().UTC()
		r.s.taskInputs[retry.ID] = append(r.s.taskInputs[retry.ID], input)
	}
	return cloneAgentTask(retry), nil
}

func (r *collaborationRepo) CreateTeam(_ context.Context, team *controlmodel.CollaborationTeam) (*controlmodel.CollaborationTeam, error) {
	if team == nil || team.Name == "" || team.LeaderAgentRef == "" {
		return nil, store.ErrConflict
	}
	memberAgents := make(map[string]struct{}, len(team.Members))
	memberRoles := make(map[string]struct{}, len(team.Members))
	for _, member := range team.Members {
		role := strings.TrimSpace(member.Role)
		if member.AgentRef == "" || role == "" || member.AgentRef == team.LeaderAgentRef {
			return nil, store.ErrConflict
		}
		if _, exists := memberAgents[member.AgentRef]; exists {
			return nil, store.ErrConflict
		}
		if _, exists := memberRoles[role]; exists {
			return nil, store.ErrConflict
		}
		memberAgents[member.AgentRef] = struct{}{}
		memberRoles[role] = struct{}{}
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	copy := cloneTeam(team)
	copy.ID = nonNilUUID(copy.ID)
	if copy.Status == "" {
		copy.Status = controlmodel.TeamActive
	}
	for _, existing := range r.s.collabTeams {
		if existing.Tenant == copy.Tenant && existing.Namespace == copy.Namespace && existing.Name == copy.Name && existing.ArchivedAt == nil {
			return nil, store.ErrConflict
		}
	}
	now := time.Now().UTC()
	copy.Version, copy.CreatedAt, copy.UpdatedAt = 1, now, now
	members := make([]controlmodel.CollaborationTeamMember, 0, len(copy.Members))
	for _, member := range copy.Members {
		member.ID = nonNilUUID(member.ID)
		member.TeamID = copy.ID
		member.Tenant, member.Namespace, member.CreatedAt = copy.Tenant, copy.Namespace, now
		members = append(members, member)
	}
	copy.Members = nil
	r.s.collabTeams[copy.ID] = copy
	r.s.collabMembers[copy.ID] = members
	out := cloneTeam(copy)
	out.Members = cloneTeamMembers(members)
	return out, nil
}

func (r *collaborationRepo) GetTeam(_ context.Context, id uuid.UUID) (*controlmodel.CollaborationTeam, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	team := r.s.collabTeams[id]
	if team == nil {
		return nil, store.ErrNotFound
	}
	out := cloneTeam(team)
	out.Members = activeTeamMembers(r.s.collabMembers[id])
	return out, nil
}

func (r *collaborationRepo) ListTeams(_ context.Context, tenant, namespace string) ([]*controlmodel.CollaborationTeam, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*controlmodel.CollaborationTeam, 0)
	for _, team := range r.s.collabTeams {
		if tenant != "" && team.Tenant != tenant || namespace != "" && team.Namespace != namespace || team.ArchivedAt != nil {
			continue
		}
		copy := cloneTeam(team)
		copy.Members = activeTeamMembers(r.s.collabMembers[team.ID])
		out = append(out, copy)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out, nil
}

func (r *collaborationRepo) UpdateTeam(_ context.Context, team *controlmodel.CollaborationTeam, expectedVersion int64) (*controlmodel.CollaborationTeam, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	current := r.s.collabTeams[team.ID]
	if current == nil {
		return nil, store.ErrNotFound
	}
	if expectedVersion > 0 && current.Version != expectedVersion {
		return nil, store.ErrConflict
	}
	for _, member := range r.s.collabMembers[team.ID] {
		if member.ArchivedAt == nil && member.AgentRef == team.LeaderAgentRef {
			return nil, store.ErrConflict
		}
	}
	next := cloneTeam(team)
	next.Tenant, next.Namespace, next.CreatedAt = current.Tenant, current.Namespace, current.CreatedAt
	next.Version, next.UpdatedAt = current.Version+1, time.Now().UTC()
	r.s.collabTeams[next.ID] = next
	out := cloneTeam(next)
	out.Members = activeTeamMembers(r.s.collabMembers[next.ID])
	return out, nil
}

func (r *collaborationRepo) AddTeamMember(_ context.Context, member *controlmodel.CollaborationTeamMember) (*controlmodel.CollaborationTeamMember, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	team := r.s.collabTeams[member.TeamID]
	if team == nil {
		return nil, store.ErrNotFound
	}
	if team.LeaderAgentRef == member.AgentRef {
		return nil, store.ErrConflict
	}
	for _, existing := range r.s.collabMembers[member.TeamID] {
		if existing.AgentRef == member.AgentRef && existing.ArchivedAt == nil || existing.Role == member.Role && existing.ArchivedAt == nil {
			return nil, store.ErrConflict
		}
	}
	copy := *member
	copy.ID = nonNilUUID(copy.ID)
	copy.Tenant, copy.Namespace, copy.CreatedAt = team.Tenant, team.Namespace, time.Now().UTC()
	r.s.collabMembers[copy.TeamID] = append(r.s.collabMembers[copy.TeamID], copy)
	team.Version++
	team.UpdatedAt = copy.CreatedAt
	return &copy, nil
}

func (r *collaborationRepo) UpdateTeamMember(_ context.Context, member *controlmodel.CollaborationTeamMember, expectedTeamVersion int64) (*controlmodel.CollaborationTeamMember, error) {
	if member == nil || member.TeamID == uuid.Nil || member.ID == uuid.Nil || strings.TrimSpace(member.Role) == "" {
		return nil, store.ErrConflict
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	team := r.s.collabTeams[member.TeamID]
	if team == nil {
		return nil, store.ErrNotFound
	}
	if expectedTeamVersion > 0 && team.Version != expectedTeamVersion {
		return nil, store.ErrConflict
	}
	members := r.s.collabMembers[member.TeamID]
	index := -1
	for i := range members {
		if members[i].ArchivedAt != nil {
			continue
		}
		if members[i].ID == member.ID {
			index = i
			continue
		}
		if members[i].Role == member.Role {
			return nil, store.ErrConflict
		}
	}
	if index < 0 {
		return nil, store.ErrNotFound
	}
	next := members[index]
	next.Role = member.Role
	next.Instructions = member.Instructions
	next.CapabilityRequirements = cloneJSON(member.CapabilityRequirements)
	next.RuntimeBindingPolicy = cloneJSON(member.RuntimeBindingPolicy)
	members[index] = next
	r.s.collabMembers[member.TeamID] = members
	team.Version++
	team.UpdatedAt = time.Now().UTC()
	return cloneTeamMember(&next), nil
}

func (r *collaborationRepo) RemoveTeamMember(_ context.Context, teamID, memberID uuid.UUID) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	members := r.s.collabMembers[teamID]
	for i := range members {
		if members[i].ID == memberID && members[i].ArchivedAt == nil {
			now := time.Now().UTC()
			members[i].ArchivedAt = &now
			r.s.collabMembers[teamID] = members
			if team := r.s.collabTeams[teamID]; team != nil {
				team.Version++
				team.UpdatedAt = now
			}
			return nil
		}
	}
	return store.ErrNotFound
}

func (r *collaborationRepo) CreateArtifact(_ context.Context, artifact *controlmodel.Artifact, links []controlmodel.ArtifactLink) (*controlmodel.Artifact, error) {
	if artifact == nil || artifact.StorageProvider == "" || artifact.StorageKey == "" || artifact.Filename == "" || artifact.SizeBytes < 0 || artifact.Checksum == "" {
		return nil, store.ErrConflict
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	copy := *artifact
	if copy.SourceTaskID != nil {
		task := r.s.agentTasks[*copy.SourceTaskID]
		if task == nil || task.Tenant != copy.Tenant || task.Namespace != copy.Namespace {
			return nil, store.ErrNotFound
		}
	}
	for _, link := range links {
		ref, err := uuid.Parse(link.TargetRef)
		if err != nil || !r.artifactTargetMatchesScopeLocked(link.TargetType, ref, copy.Tenant, copy.Namespace) {
			return nil, store.ErrNotFound
		}
	}
	copy.ID = nonNilUUID(copy.ID)
	if _, exists := r.s.artifacts[copy.ID]; exists {
		return nil, store.ErrConflict
	}
	copy.CreatedAt = time.Now().UTC()
	copy.Metadata = cloneJSON(copy.Metadata)
	r.s.artifacts[copy.ID] = &copy
	for i := range links {
		links[i].ArtifactID, links[i].CreatedAt = copy.ID, copy.CreatedAt
	}
	r.s.artifactLinks[copy.ID] = append([]controlmodel.ArtifactLink(nil), links...)
	return cloneArtifact(&copy), nil
}

func (r *collaborationRepo) artifactTargetMatchesScopeLocked(targetType string, ref uuid.UUID, tenant, namespace string) bool {
	switch targetType {
	case "issue":
		item := r.s.issues[ref]
		return item != nil && item.Tenant == tenant && item.Namespace == namespace
	case "comment":
		item := r.s.comments[ref]
		return item != nil && item.Tenant == tenant && item.Namespace == namespace
	case "agent-task":
		item := r.s.agentTasks[ref]
		return item != nil && item.Tenant == tenant && item.Namespace == namespace
	case "execution":
		item := r.s.executions[ref]
		return item != nil && item.Tenant == tenant && item.Namespace == namespace
	default:
		return false
	}
}

func (r *collaborationRepo) GetArtifact(_ context.Context, id uuid.UUID) (*controlmodel.Artifact, []controlmodel.ArtifactLink, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	artifact := r.s.artifacts[id]
	if artifact == nil {
		return nil, nil, store.ErrNotFound
	}
	return cloneArtifact(artifact), append([]controlmodel.ArtifactLink(nil), r.s.artifactLinks[id]...), nil
}

func (r *collaborationRepo) ListArtifacts(_ context.Context, tenant, namespace, targetType, targetRef string) ([]*controlmodel.Artifact, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*controlmodel.Artifact, 0)
	for id, artifact := range r.s.artifacts {
		if tenant != "" && artifact.Tenant != tenant || namespace != "" && artifact.Namespace != namespace {
			continue
		}
		matched := targetType == "" && targetRef == ""
		for _, link := range r.s.artifactLinks[id] {
			if (targetType == "" || link.TargetType == targetType) && (targetRef == "" || link.TargetRef == targetRef) {
				matched = true
				break
			}
		}
		if matched {
			out = append(out, cloneArtifact(artifact))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.Before(out[j].CreatedAt) })
	return out, nil
}

func (r *collaborationRepo) SubscribeIssue(_ context.Context, subscriber *controlmodel.IssueSubscriber) (*controlmodel.IssueSubscriber, error) {
	if subscriber == nil || subscriber.IssueID == uuid.Nil || subscriber.SubscriberType == "" || subscriber.SubscriberRef == "" {
		return nil, store.ErrConflict
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	issue := r.s.issues[subscriber.IssueID]
	if issue == nil {
		return nil, store.ErrNotFound
	}
	for _, current := range r.s.subscribers[subscriber.IssueID] {
		if current.SubscriberType == subscriber.SubscriberType && current.SubscriberRef == subscriber.SubscriberRef {
			copy := current
			return &copy, nil
		}
	}
	copy := *subscriber
	copy.Tenant, copy.Namespace, copy.CreatedAt = issue.Tenant, issue.Namespace, time.Now().UTC()
	r.s.subscribers[copy.IssueID] = append(r.s.subscribers[copy.IssueID], copy)
	return &copy, nil
}

func (r *collaborationRepo) UnsubscribeIssue(_ context.Context, issueID uuid.UUID, subscriberType controlmodel.AssigneeType, subscriberRef string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	items := r.s.subscribers[issueID]
	for i, item := range items {
		if item.SubscriberType == subscriberType && item.SubscriberRef == subscriberRef {
			r.s.subscribers[issueID] = append(items[:i], items[i+1:]...)
			return nil
		}
	}
	return store.ErrNotFound
}

func (r *collaborationRepo) ListIssueSubscribers(_ context.Context, issueID uuid.UUID) ([]*controlmodel.IssueSubscriber, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	if r.s.issues[issueID] == nil {
		return nil, store.ErrNotFound
	}
	out := make([]*controlmodel.IssueSubscriber, 0, len(r.s.subscribers[issueID]))
	for _, item := range r.s.subscribers[issueID] {
		copy := item
		out = append(out, &copy)
	}
	return out, nil
}

func (r *collaborationRepo) CreateApproval(_ context.Context, approval *controlmodel.Approval) (*controlmodel.Approval, error) {
	if approval == nil || approval.TargetType == "" || approval.TargetRef == "" || approval.ApproverRef == "" {
		return nil, store.ErrConflict
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	copy := cloneApproval(approval)
	copy.ID = nonNilUUID(copy.ID)
	if _, exists := r.s.approvals[copy.ID]; exists {
		return nil, store.ErrConflict
	}
	now := time.Now().UTC()
	if copy.Status == "" {
		copy.Status = controlmodel.ApprovalPending
	}
	copy.Version, copy.CreatedAt, copy.UpdatedAt = 1, now, now
	r.s.approvals[copy.ID] = copy
	item := &controlmodel.InboxItem{ID: uuid.New(), Tenant: copy.Tenant, Namespace: copy.Namespace,
		RecipientType: controlmodel.AssigneeHuman, RecipientRef: copy.ApproverRef,
		Type: "approval", NeedsAction: true, Severity: "attention", IssueID: copy.IssueID, ApprovalID: &copy.ID,
		Actor: copy.RequestedBy, Title: "Approval requested", Body: copy.Reason,
		DedupeKey: "approval:" + copy.ID.String(), CreatedAt: now}
	r.s.inboxItems[item.ID] = item
	r.enqueueEventLocked(copy.Tenant, "approval", copy.ID, "approval.requested.v1", copy, "approval-requested:"+copy.ID.String())
	return cloneApproval(copy), nil
}

func (r *collaborationRepo) CreateManagedToolApproval(_ context.Context, req store.ManagedToolApprovalRequest) (*controlmodel.Approval, *controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	if req.Approval == nil {
		return nil, nil, nil, store.ErrConflict
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	task := r.s.agentTasks[req.Fence.TaskID]
	attempt := r.s.executions[req.Fence.AttemptID]
	if task == nil || attempt == nil {
		return nil, nil, nil, store.ErrNotFound
	}
	if err := store.ValidateManagedToolApprovalFence(task, attempt, req.Fence); err != nil {
		return nil, nil, nil, err
	}
	if err := store.ValidateManagedToolApproval(req.Approval, task, req.Fence); err != nil {
		return nil, nil, nil, err
	}
	for _, existing := range r.s.approvals {
		if store.ManagedToolApprovalMatchesFence(existing, req.Fence) {
			if existing.ID != req.Approval.ID {
				return nil, nil, nil, store.ErrConflict
			}
			if err := store.ValidateManagedToolApproval(existing, task, req.Fence); err != nil {
				return nil, nil, nil, err
			}
			return cloneApproval(existing), cloneAgentTask(task), cloneExecution(attempt), nil
		}
	}
	if current := r.s.approvals[req.Approval.ID]; current != nil {
		if current.TargetType != req.Approval.TargetType || current.TargetRef != req.Approval.TargetRef ||
			current.Tenant != req.Approval.Tenant || current.Namespace != req.Approval.Namespace {
			return nil, nil, nil, store.ErrConflict
		}
		if err := store.ValidateManagedToolApproval(current, task, req.Fence); err != nil {
			return nil, nil, nil, err
		}
		return cloneApproval(current), cloneAgentTask(task), cloneExecution(attempt), nil
	}
	if task.Status != controlmodel.AgentTaskRunning || attempt.State != controlmodel.ExecutionRunning {
		return nil, nil, nil, store.ErrConflict
	}
	now := time.Now().UTC()
	approval := cloneApproval(req.Approval)
	approval.Status, approval.Version = controlmodel.ApprovalPending, 1
	approval.CreatedAt, approval.UpdatedAt = now, now
	r.s.approvals[approval.ID] = approval
	item := &controlmodel.InboxItem{ID: uuid.New(), Tenant: approval.Tenant, Namespace: approval.Namespace,
		RecipientType: controlmodel.AssigneeHuman, RecipientRef: approval.ApproverRef,
		Type: "approval", NeedsAction: true, Severity: "attention", IssueID: approval.IssueID, ApprovalID: &approval.ID,
		Actor: approval.RequestedBy, Title: "Tool approval requested", Body: approval.Reason,
		Details: cloneJSON(approval.Request), DedupeKey: "approval:" + approval.ID.String(), CreatedAt: now}
	r.s.inboxItems[item.ID] = item
	task.Status, task.WaitReason, task.Version = controlmodel.AgentTaskWaiting, "approval:"+approval.ID.String(), task.Version+1
	attempt.State, attempt.Version = controlmodel.ExecutionWaiting, attempt.Version+1
	attempt.UpdatedAt = now
	payload, _ := json.Marshal(map[string]any{"approvalId": approval.ID, "toolUseId": req.Fence.ToolUseID})
	event := &controlmodel.RunEvent{ID: uuid.New(), RunID: task.OrchestrationRunID,
		Tenant: task.Tenant, Namespace: task.Namespace,
		Sequence: int64(len(r.s.runEvents[task.OrchestrationRunID]) + 1), NodeID: &task.RunNodeID,
		AgentTaskID: &task.ID, AttemptID: &attempt.ID, Type: "attempt.waiting_for_approval",
		Actor: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}, Payload: payload,
		IdempotencyKey: "attempt-waiting-approval:" + approval.ID.String(), OccurredAt: now}
	r.s.runEvents[task.OrchestrationRunID] = append(r.s.runEvents[task.OrchestrationRunID], event)
	r.enqueueEventLocked(approval.Tenant, "approval", approval.ID, "approval.requested.v1", approval,
		"approval-requested:"+approval.ID.String())
	return cloneApproval(approval), cloneAgentTask(task), cloneExecution(attempt), nil
}

func (r *collaborationRepo) ResumeManagedToolApproval(_ context.Context, approvalID uuid.UUID, fence store.ManagedToolApprovalFence, leaseTTL time.Duration) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	if leaseTTL <= 0 {
		return nil, nil, store.ErrConflict
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	approval := r.s.approvals[approvalID]
	task := r.s.agentTasks[fence.TaskID]
	attempt := r.s.executions[fence.AttemptID]
	if approval == nil || task == nil || attempt == nil {
		return nil, nil, store.ErrNotFound
	}
	if approval.Status == controlmodel.ApprovalPending {
		return nil, nil, store.ErrConflict
	}
	if err := store.ValidateManagedToolApprovalFence(task, attempt, fence); err != nil {
		return nil, nil, err
	}
	if err := store.ValidateManagedToolApproval(approval, task, fence); err != nil {
		return nil, nil, err
	}
	if task.Status == controlmodel.AgentTaskRunning && attempt.State == controlmodel.ExecutionRunning {
		return cloneAgentTask(task), cloneExecution(attempt), nil
	}
	if task.Status != controlmodel.AgentTaskWaiting || attempt.State != controlmodel.ExecutionWaiting ||
		task.WaitReason != "approval:"+approval.ID.String() {
		return nil, nil, store.ErrConflict
	}
	now, expires := time.Now().UTC(), time.Now().UTC().Add(leaseTTL)
	task.Status, task.WaitReason, task.Version = controlmodel.AgentTaskRunning, "", task.Version+1
	attempt.State, attempt.Version, attempt.UpdatedAt = controlmodel.ExecutionRunning, attempt.Version+1, now
	attempt.HeartbeatAt, attempt.LeaseExpiresAt = &now, &expires
	payload, _ := json.Marshal(map[string]any{"approvalId": approval.ID, "status": approval.Status})
	event := &controlmodel.RunEvent{ID: uuid.New(), RunID: task.OrchestrationRunID,
		Tenant: task.Tenant, Namespace: task.Namespace,
		Sequence: int64(len(r.s.runEvents[task.OrchestrationRunID]) + 1), NodeID: &task.RunNodeID,
		AgentTaskID: &task.ID, AttemptID: &attempt.ID, Type: "attempt.resumed_after_approval",
		Actor: controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "approval-dispatcher"}, Payload: payload,
		IdempotencyKey: "attempt-resumed-approval:" + approval.ID.String(), OccurredAt: now}
	r.s.runEvents[task.OrchestrationRunID] = append(r.s.runEvents[task.OrchestrationRunID], event)
	return cloneAgentTask(task), cloneExecution(attempt), nil
}

func (r *collaborationRepo) GetApproval(_ context.Context, id uuid.UUID) (*controlmodel.Approval, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	approval := r.s.approvals[id]
	if approval == nil {
		return nil, store.ErrNotFound
	}
	return cloneApproval(approval), nil
}

func (r *collaborationRepo) ListApprovals(ctx context.Context, filter store.ApprovalFilter) ([]*controlmodel.Approval, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*controlmodel.Approval, 0)
	for _, approval := range r.s.approvals {
		if !r.s.canReadTargetLocked(ctx, approval.TargetType, approval.TargetRef) {
			continue
		}
		if filter.Tenant != "" && approval.Tenant != filter.Tenant || filter.Namespace != "" && approval.Namespace != filter.Namespace || filter.ApproverRef != "" && approval.ApproverRef != filter.ApproverRef || filter.TargetType != "" && approval.TargetType != filter.TargetType || filter.TargetRef != "" && approval.TargetRef != filter.TargetRef || filter.Status != "" && approval.Status != filter.Status {
			continue
		}
		out = append(out, cloneApproval(approval))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.After(out[j].CreatedAt) })
	return page(out, filter.Offset, filter.Limit), nil
}

func (r *collaborationRepo) DecideApproval(_ context.Context, id uuid.UUID, expectedVersion int64, status controlmodel.ApprovalStatus, actor controlmodel.Actor, decision json.RawMessage) (*controlmodel.Approval, error) {
	if status != controlmodel.ApprovalApproved && status != controlmodel.ApprovalRejected && status != controlmodel.ApprovalCancelled {
		return nil, store.ErrConflict
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	approval := r.s.approvals[id]
	if approval == nil {
		return nil, store.ErrNotFound
	}
	actorAllowed := actor.Type == controlmodel.ActorHuman && actor.Ref == approval.ApproverRef ||
		status == controlmodel.ApprovalCancelled && actor.Type == controlmodel.ActorSystem
	if approval.Status != controlmodel.ApprovalPending || expectedVersion > 0 && approval.Version != expectedVersion || !actorAllowed {
		return nil, store.ErrConflict
	}
	now := time.Now().UTC()
	approval.Status, approval.Decision, approval.DecidedBy = status, cloneJSON(decision), &actor
	approval.Version, approval.UpdatedAt, approval.DecidedAt = approval.Version+1, now, &now
	for _, item := range r.s.inboxItems {
		if item.ApprovalID != nil && *item.ApprovalID == id {
			item.Archived, item.NeedsAction, item.ResolvedAt = true, false, &now
		}
	}
	r.enqueueEventLocked(approval.Tenant, "approval", approval.ID, "approval.decided.v1", approval, fmt.Sprintf("approval-decided:%s:%d", approval.ID, approval.Version))
	return cloneApproval(approval), nil
}

func (r *collaborationRepo) ListActivities(_ context.Context, issueID uuid.UUID, limit, offset int) ([]*controlmodel.Activity, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*controlmodel.Activity, 0)
	for i := range r.s.activities {
		activity := r.s.activities[i]
		if activity.IssueID != nil && *activity.IssueID == issueID {
			copy := activity
			copy.Details = cloneJSON(copy.Details)
			out = append(out, &copy)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.Before(out[j].CreatedAt) })
	return page(out, offset, limit), nil
}

func (r *collaborationRepo) SweepOverdueIssues(_ context.Context, now time.Time, limit int) (int, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if limit <= 0 {
		limit = 100
	}
	created := 0
	for _, issue := range r.s.issues {
		if created >= limit || issue.DueAt == nil || issue.DueAt.After(now) || issue.ArchivedAt != nil ||
			issue.Status == controlmodel.IssueDone || issue.Status == controlmodel.IssueCancelled {
			continue
		}
		dedupe := "issue-sla-breached:" + issue.ID.String()
		seen := false
		for _, item := range r.s.inboxItems {
			if item.Tenant == issue.Tenant && item.DedupeKey == dedupe {
				seen = true
				break
			}
		}
		if seen {
			continue
		}
		recipient := "admin"
		if issue.Creator.Type == controlmodel.ActorHuman && issue.Creator.Ref != "" {
			recipient = issue.Creator.Ref
		} else if issue.AssigneeType == controlmodel.AssigneeHuman && issue.AssigneeRef != "" {
			recipient = issue.AssigneeRef
		}
		issueID := issue.ID
		item := &controlmodel.InboxItem{ID: uuid.New(), Tenant: issue.Tenant, Namespace: issue.Namespace,
			RecipientType: controlmodel.AssigneeHuman, RecipientRef: recipient, Type: "issue_sla_breached",
			Severity: "warning", IssueID: &issueID, Actor: controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "governance"},
			Title: "Issue SLA breached", Body: issue.Title, DedupeKey: dedupe, CreatedAt: now}
		r.s.inboxItems[item.ID] = item
		created++
	}
	return created, nil
}

func (r *collaborationRepo) SweepTimedOutAgentTasks(ctx context.Context, now time.Time, limit int) (int, error) {
	if limit <= 0 {
		limit = 100
	}
	type candidate struct {
		id      uuid.UUID
		version int64
	}
	items := make([]candidate, 0, limit)
	r.s.mu.RLock()
	for _, task := range r.s.agentTasks {
		if len(items) >= limit || task.TeamID == nil || task.Status != controlmodel.AgentTaskRunning && task.Status != controlmodel.AgentTaskDispatched {
			continue
		}
		snapshot := r.s.runSnapshots[task.OrchestrationRunID.String()+"\x00"+task.TeamID.String()]
		var team controlmodel.CollaborationTeam
		if snapshot == nil || json.Unmarshal(snapshot.Snapshot, &team) != nil || team.Policy.TaskTimeoutSeconds <= 0 {
			continue
		}
		started := task.CreatedAt
		if task.DispatchedAt != nil {
			started = *task.DispatchedAt
		}
		if task.StartedAt != nil {
			started = *task.StartedAt
		}
		if !started.Add(time.Duration(team.Policy.TaskTimeoutSeconds) * time.Second).After(now) {
			items = append(items, candidate{id: task.ID, version: task.Version})
		}
	}
	r.s.mu.RUnlock()
	count := 0
	for _, item := range items {
		if _, err := r.FailAgentTask(ctx, item.id, item.version, "task_timeout", "Team AgentTask timeout exceeded"); err == nil {
			count++
		} else if err != store.ErrConflict {
			return count, err
		}
	}
	return count, nil
}

func (r *collaborationRepo) ReconcileRunningInputs(_ context.Context, taskID uuid.UUID, _ time.Time) error {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	if r.s.agentTasks[taskID] == nil {
		return store.ErrNotFound
	}
	return nil
}

func (r *collaborationRepo) transitionTask(id uuid.UUID, expectedVersion int64, target controlmodel.AgentTaskStatus, result json.RawMessage, code, message string) (*controlmodel.AgentTask, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	task := r.s.agentTasks[id]
	if task == nil {
		return nil, store.ErrNotFound
	}
	if expectedVersion > 0 && task.Version != expectedVersion || !controlmodel.CanTransitionAgentTask(task.Status, target) {
		return nil, store.ErrConflict
	}
	now := time.Now().UTC()
	task.Status, task.Version = target, task.Version+1
	if target == controlmodel.AgentTaskRunning && task.StartedAt == nil {
		task.StartedAt = &now
	}
	if controlmodel.IsAgentTaskTerminal(target) {
		task.CompletedAt = &now
	}
	task.Result, task.ErrorCode, task.ErrorMessage = cloneJSON(result), code, message
	if target == controlmodel.AgentTaskFailed {
		r.notifyTaskFailureInboxLocked(task)
	}

	r.enqueueEventLocked(task.Tenant, "agent-task", task.ID, "agent-task."+string(target)+".v1", task, fmt.Sprintf("agent-task-%s:%s:%d", target, task.ID, task.Version))
	return cloneAgentTask(task), nil
}

func (r *collaborationRepo) routeTaskLocked(issue *controlmodel.Issue, comment *controlmodel.Comment, target store.CommentTarget) (*controlmodel.AgentTask, bool) {
	for _, task := range r.s.agentTasks {
		if task.IssueID == issue.ID && task.AgentRef == target.AgentRef && task.TeamRole == target.TeamRole && task.Status == controlmodel.AgentTaskQueued {
			r.addInputLocked(task, comment)
			return task, true
		}
	}
	parent := target.ParentTaskID
	if parent == nil {
		for _, task := range r.s.agentTasks {
			if task.IssueID == issue.ID && task.AgentRef == target.AgentRef && task.TeamRole == target.TeamRole && (task.Status == controlmodel.AgentTaskDispatched || task.Status == controlmodel.AgentTaskRunning || task.Status == controlmodel.AgentTaskWaiting) {
				id := task.ID
				parent = &id
				break
			}
		}
	}
	teamID := target.TeamID
	task := r.newTaskLocked(issue, target.AgentRef, "comment", &comment.ID, teamID, target.TeamRole, target.RouteType == controlmodel.RouteTeamLeader, comment.Author, parent, nil)
	r.addInputLocked(task, comment)
	return task, false
}

func (r *collaborationRepo) newTaskLocked(issue *controlmodel.Issue, agentRef, triggerType string, triggerCommentID *uuid.UUID, teamID *uuid.UUID, teamRole string, leader bool, originator controlmodel.Actor, parentTaskID, retryOf *uuid.UUID) *controlmodel.AgentTask {
	now := time.Now().UTC()
	if retryOf != nil {
		if previous := r.s.agentTasks[*retryOf]; previous != nil && previous.TriggerType == controlmodel.AgentTaskReviewComment {
			triggerType = previous.TriggerType
		}
	}
	triggerType = store.ReviewCommentTrigger(issue, triggerType, originator)
	task := &controlmodel.AgentTask{ID: uuid.New(), Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: issue.ID, AgentRef: agentRef, Status: controlmodel.AgentTaskQueued, TriggerType: triggerType, TriggerCommentID: triggerCommentID, TeamID: teamID, TeamRole: teamRole, LeaderTask: leader, ParentTaskID: parentTaskID, DelegatedFromTaskID: parentTaskID, Originator: originator, RetryOfTaskID: retryOf, Version: 1, CreatedAt: now, CausationID: issue.ID.String(), CorrelationID: issue.ID.String()}
	var source *controlmodel.AgentTask
	if parentTaskID != nil {
		source = r.s.agentTasks[*parentTaskID]
	}
	if originator.Type == controlmodel.ActorHuman {
		task.AccountableHumanRef = originator.Ref
	}
	if issue.SourceType == "automation" && task.AccountableHumanRef == "" {
		if runID, err := uuid.Parse(issue.SourceRef); err == nil {
			if run := r.s.automationRuns[runID]; run != nil && run.Snapshot != nil && run.Snapshot.CreatedBy.Type == controlmodel.ActorHuman {
				task.AccountableHumanRef = run.Snapshot.CreatedBy.Ref
			}
		}
	}
	if source == nil && issue.SourceType == "agent-task" {
		if sourceID, err := uuid.Parse(issue.SourceRef); err == nil {
			if sourceTask := r.s.agentTasks[sourceID]; sourceTask != nil && sourceTask.Tenant == issue.Tenant && sourceTask.Namespace == issue.Namespace {
				source = sourceTask
				task.ParentTaskID, task.DelegatedFromTaskID = &sourceID, &sourceID
				task.CausationID, task.CorrelationID = sourceID.String(), sourceTask.CorrelationID
				task.HopCount, task.TeamDepth, task.AccountableHumanRef = sourceTask.HopCount+1, sourceTask.TeamDepth, sourceTask.AccountableHumanRef
				if task.TeamID != nil && (sourceTask.TeamID == nil || *task.TeamID != *sourceTask.TeamID) {
					task.TeamDepth++
				}
			}
		}
	}
	if triggerCommentID != nil {
		task.CausationID = triggerCommentID.String()
		if comment := r.s.comments[*triggerCommentID]; parentTaskID == nil && comment != nil && comment.SourceTaskID != nil {
			if sourceTask := r.s.agentTasks[*comment.SourceTaskID]; sourceTask != nil {
				source = sourceTask
				task.ParentTaskID, task.DelegatedFromTaskID = comment.SourceTaskID, comment.SourceTaskID
				task.CorrelationID, task.HopCount, task.TeamDepth = sourceTask.CorrelationID, sourceTask.HopCount+1, sourceTask.TeamDepth
				task.AccountableHumanRef = sourceTask.AccountableHumanRef
				if teamID != nil {
					task.TeamDepth++
				}
			}
		}
	}
	if retryOf != nil && r.s.agentTasks[*retryOf] != nil {
		source = r.s.agentTasks[*retryOf]
	}
	if triggerType == controlmodel.AgentTaskReviewComment {
		source = nil
	}
	var rerunOf *uuid.UUID
	if source != nil {
		if run := r.s.runs[source.OrchestrationRunID]; run != nil && !controlmodel.IsOrchestrationRunTerminal(run.State) {
			task.OrchestrationRunID = source.OrchestrationRunID
			reuseSourceNode := retryOf != nil || source.LeaderTask && leader &&
				source.AgentRef == agentRef && source.TeamRole == teamRole
			sourceNode := r.s.runNodes[source.RunNodeID]
			if reuseSourceNode && sourceNode != nil && !controlmodel.IsRunNodeTerminal(sourceNode.State) {
				task.RunNodeID = source.RunNodeID
			}
		} else if source.OrchestrationRunID != uuid.Nil && !r.attachTeamContinuationLocked(issue, task, source) {
			id := source.OrchestrationRunID
			rerunOf = &id
		}
	}
	if task.OrchestrationRunID == uuid.Nil {
		task.OrchestrationRunID = uuid.New()
		mode := controlmodel.RunModeDirect
		if teamID != nil || leader {
			mode = controlmodel.RunModeAdaptive
		}
		r.s.runs[task.OrchestrationRunID] = &controlmodel.OrchestrationRun{ID: task.OrchestrationRunID,
			Tenant: task.Tenant, Namespace: task.Namespace, RootIssueID: task.IssueID, Mode: mode,
			RerunOfRunID: rerunOf, TriggerType: triggerType, TriggerRef: task.CausationID,
			State: controlmodel.RunRunning, Version: 1, CreatedBy: originator,
			CreatedAt: now, UpdatedAt: now, StartedAt: &now}
	}
	if task.RunNodeID == uuid.Nil {
		task.RunNodeID = uuid.New()
		nodeType := controlmodel.RunNodeAgent
		if leader {
			nodeType = controlmodel.RunNodeTeam
		}
		r.s.runNodes[task.RunNodeID] = &controlmodel.RunNode{ID: task.RunNodeID,
			RunID: task.OrchestrationRunID, Tenant: task.Tenant, Namespace: task.Namespace,
			NodeKey: "task:" + task.ID.String(), Type: nodeType, Role: teamRole, IssueID: &task.IssueID,
			State: controlmodel.RunNodeReady, Iteration: 1, Version: 1, CreatedAt: now, UpdatedAt: now}
	}
	if task.TeamID != nil {
		key := task.OrchestrationRunID.String() + "\x00" + task.TeamID.String()
		if r.s.runSnapshots[key] == nil {
			team := cloneTeam(r.s.collabTeams[*task.TeamID])
			if team != nil {
				team.Members = activeTeamMembers(r.s.collabMembers[*task.TeamID])
				snapshot, _ := json.Marshal(team)
				r.s.runSnapshots[key] = &controlmodel.RunTeamSnapshot{RunID: task.OrchestrationRunID,
					TeamID: *task.TeamID, Tenant: task.Tenant, Namespace: task.Namespace,
					Snapshot: snapshot, CreatedAt: now}
			}
		}
	}
	r.s.agentTasks[task.ID] = task
	event := &controlmodel.RunEvent{ID: uuid.New(), RunID: task.OrchestrationRunID, Tenant: task.Tenant,
		Namespace: task.Namespace, Sequence: int64(len(r.s.runEvents[task.OrchestrationRunID]) + 1),
		NodeID: &task.RunNodeID, AgentTaskID: &task.ID, Type: "agent-task.queued", Actor: originator,
		CausationID: task.CausationID, CorrelationID: task.CorrelationID,
		IdempotencyKey: "agent-task-queued:" + task.ID.String(), OccurredAt: now}
	r.s.runEvents[task.OrchestrationRunID] = append(r.s.runEvents[task.OrchestrationRunID], event)
	r.enqueueEventLocked(task.Tenant, "agent-task", task.ID, "agent-task.queued.v1", task, "agent-task-queued:"+task.ID.String())
	return task
}

func (r *collaborationRepo) addInputLocked(task *controlmodel.AgentTask, comment *controlmodel.Comment) {
	inputs := r.s.taskInputs[task.ID]
	for _, input := range inputs {
		if input.CommentID == comment.ID && input.CommentVersion == comment.Version {
			return
		}
	}
	input := controlmodel.AgentTaskInput{ID: uuid.New(), Tenant: task.Tenant, Namespace: task.Namespace, TaskID: task.ID, CommentID: comment.ID, CommentVersion: comment.Version, Sequence: int64(len(inputs) + 1), State: controlmodel.TaskInputPlanned, CreatedAt: time.Now().UTC()}
	r.s.taskInputs[task.ID] = append(inputs, input)
}

func (r *collaborationRepo) appendActivityLocked(activity *controlmodel.Activity) {
	if activity == nil {
		return
	}
	copy := *activity
	copy.ID = nonNilUUID(copy.ID)
	if copy.CreatedAt.IsZero() {
		copy.CreatedAt = time.Now().UTC()
	}
	copy.Details = cloneJSON(copy.Details)
	r.s.activities = append(r.s.activities, copy)
}

func (r *collaborationRepo) enqueueEventLocked(tenant, aggregateType string, aggregateID uuid.UUID, eventType string, payload any, dedupe string) {
	for _, event := range r.s.outboxEvents {
		if event.Tenant == tenant && dedupe != "" && event.DedupeKey == dedupe {
			return
		}
	}
	data, _ := json.Marshal(payload)
	now := time.Now().UTC()
	event := &controlmodel.OutboxEvent{ID: uuid.New(), Tenant: tenant, AggregateType: aggregateType,
		AggregateID: aggregateID.String(), EventType: eventType, SchemaVersion: 1,
		Actor: controlmodel.Actor{Type: controlmodel.ActorSystem}, OccurredAt: now, Payload: data,
		DedupeKey: dedupe, AvailableAt: now, CreatedAt: now}
	meta := inferMemoryEventMetadata(data)
	event.Namespace, event.Actor, event.CausationID, event.CorrelationID = meta.Namespace, meta.Actor, meta.CausationID, meta.CorrelationID
	r.s.outboxEvents[event.ID] = event
}

type memoryEventMetadata struct {
	Namespace, CausationID, CorrelationID string
	Actor                                 controlmodel.Actor
}

func inferMemoryEventMetadata(data []byte) memoryEventMetadata {
	meta := memoryEventMetadata{Actor: controlmodel.Actor{Type: controlmodel.ActorSystem}}
	var root map[string]any
	if json.Unmarshal(data, &root) != nil {
		return meta
	}
	for _, key := range []string{"comment", "task", "issue", "artifact", "approval"} {
		if nested, ok := root[key].(map[string]any); ok {
			for k, v := range nested {
				if _, exists := root[k]; !exists {
					root[k] = v
				}
			}
			break
		}
	}
	meta.Namespace, _ = root["namespace"].(string)
	meta.CausationID, _ = root["causationId"].(string)
	meta.CorrelationID, _ = root["correlationId"].(string)
	for _, key := range []string{"actor", "author", "creator", "originator", "requestedBy"} {
		if actor, ok := root[key].(map[string]any); ok {
			if value, ok := actor["type"].(string); ok && value != "" {
				meta.Actor.Type = controlmodel.ActorType(value)
			}
			meta.Actor.Ref, _ = actor["ref"].(string)
			break
		}
	}
	return meta
}

func cloneIssue(in *controlmodel.Issue) *controlmodel.Issue {
	if in == nil {
		return nil
	}
	out := *in
	out.Access.Members = make(map[string]string, len(in.Access.Members))
	for k, v := range in.Access.Members {
		out.Access.Members[k] = v
	}
	out.AcceptanceCriteria, out.ContextRefs = cloneJSON(in.AcceptanceCriteria), cloneJSON(in.ContextRefs)
	return &out
}

func cloneComment(in *controlmodel.Comment) *controlmodel.Comment {
	if in == nil {
		return nil
	}
	out := *in
	out.Mentions = append([]controlmodel.Mention(nil), in.Mentions...)
	out.Routes = append([]controlmodel.CommentRoute(nil), in.Routes...)
	return &out
}

func cloneAgentTask(in *controlmodel.AgentTask) *controlmodel.AgentTask {
	if in == nil {
		return nil
	}
	out := *in
	out.RuntimeBinding, out.Result = cloneJSON(in.RuntimeBinding), cloneJSON(in.Result)
	out.Inputs = append([]controlmodel.AgentTaskInput(nil), in.Inputs...)
	return &out
}

func cloneTeam(in *controlmodel.CollaborationTeam) *controlmodel.CollaborationTeam {
	if in == nil {
		return nil
	}
	out := *in
	out.Members = cloneTeamMembers(in.Members)
	return &out
}

func cloneTeamMembers(in []controlmodel.CollaborationTeamMember) []controlmodel.CollaborationTeamMember {
	out := append([]controlmodel.CollaborationTeamMember(nil), in...)
	for i := range out {
		out[i].CapabilityRequirements = cloneJSON(in[i].CapabilityRequirements)
		out[i].RuntimeBindingPolicy = cloneJSON(in[i].RuntimeBindingPolicy)
	}
	return out
}

func cloneTeamMember(in *controlmodel.CollaborationTeamMember) *controlmodel.CollaborationTeamMember {
	if in == nil {
		return nil
	}
	out := *in
	out.CapabilityRequirements = cloneJSON(in.CapabilityRequirements)
	out.RuntimeBindingPolicy = cloneJSON(in.RuntimeBindingPolicy)
	return &out
}

func activeTeamMembers(in []controlmodel.CollaborationTeamMember) []controlmodel.CollaborationTeamMember {
	active := make([]controlmodel.CollaborationTeamMember, 0, len(in))
	for _, member := range in {
		if member.ArchivedAt == nil {
			active = append(active, member)
		}
	}
	return cloneTeamMembers(active)
}

func snapshotTeamAgentRole(team *controlmodel.CollaborationTeam, agentRef string) (string, bool) {
	if team == nil {
		return "", false
	}
	if team.LeaderAgentRef == agentRef {
		return "leader", true
	}
	for _, member := range team.Members {
		if member.ArchivedAt == nil && member.AgentRef == agentRef {
			return member.Role, true
		}
	}
	return "", false
}

func cloneArtifact(in *controlmodel.Artifact) *controlmodel.Artifact {
	if in == nil {
		return nil
	}
	out := *in
	out.Metadata = cloneJSON(in.Metadata)
	return &out
}

func cloneApproval(in *controlmodel.Approval) *controlmodel.Approval {
	if in == nil {
		return nil
	}
	out := *in
	out.Request, out.Decision = cloneJSON(in.Request), cloneJSON(in.Decision)
	if in.DecidedBy != nil {
		actor := *in.DecidedBy
		out.DecidedBy = &actor
	}
	return &out
}

func nonNilUUID(id uuid.UUID) uuid.UUID {
	if id == uuid.Nil {
		return uuid.New()
	}
	return id
}

func uuidSet(ids []uuid.UUID) map[uuid.UUID]bool {
	out := make(map[uuid.UUID]bool, len(ids))
	for _, id := range ids {
		out[id] = true
	}
	return out
}

func page[T any](in []T, offset, limit int) []T {
	if offset < 0 {
		offset = 0
	}
	if offset >= len(in) {
		return []T{}
	}
	in = in[offset:]
	if limit > 0 && len(in) > limit {
		in = in[:limit]
	}
	return in
}

func mustMarshalIssueAccess(access controlmodel.IssueAccess) json.RawMessage {
	data, _ := json.Marshal(map[string]any{"access": access})
	return data
}

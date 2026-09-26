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
	"fmt"
	"regexp"
	"strings"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/metrics"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type Service struct {
	Store store.Store
}

var (
	secretPattern = regexp.MustCompile(`(?i)(-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\bAKIA[0-9A-Z]{16}\b|(?:api[_-]?key|secret|password|bearer)\s*[:=]\s*[^\s]{8,})`)
	piiPattern    = regexp.MustCompile(`(?i)(\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b|\b(?:\+?[0-9][ -]?){10,15}\b)`)
)

// ValidateTeamPolicy keeps governance configuration typed and bounded rather
// than accepting arbitrary JSON that different runtimes interpret differently.
func ValidateTeamPolicy(policy controlmodel.TeamPolicy) error {
	if policy.MaxActiveTasks < 0 || policy.MaxHops < 0 || policy.MaxChildDepth < 0 ||
		policy.MaxChildIssues < 0 || policy.MaxFanout < 0 || policy.MaxTaskRetries < 0 ||
		policy.MaxArtifactBytes < 0 || policy.MaxIssueTokens < 0 || policy.MaxIssueCostMicros < 0 ||
		policy.IssueSLASeconds < 0 || policy.TaskTimeoutSeconds < 0 {
		return fmt.Errorf("Team policy limits cannot be negative")
	}
	if policy.MaxHops > 64 || policy.MaxChildDepth > 32 || policy.MaxFanout > 256 {
		return fmt.Errorf("Team policy exceeds platform safety bounds")
	}
	for name, value := range map[string]string{"secretPolicy": policy.SecretPolicy, "piiPolicy": policy.PIIPolicy} {
		if value != "" && value != "allow" && value != "block" {
			return fmt.Errorf("%s must be allow or block", name)
		}
	}
	for _, mediaType := range policy.AllowedArtifactMediaTypes {
		if strings.TrimSpace(mediaType) == "" || !strings.Contains(mediaType, "/") {
			return fmt.Errorf("allowedArtifactMediaTypes entries must be MIME types")
		}
	}
	return nil
}

// ValidateRuntimeBindingPolicy rejects malformed Team configuration before a
// durable AgentTask reaches the scheduler. An empty policy delegates to the
// AgentRuntimePolicy; it never implies an arbitrary External instance.
func ValidateRuntimeBindingPolicy(raw json.RawMessage) error {
	if len(raw) == 0 || string(raw) == "null" {
		return nil
	}
	var policy controlmodel.RuntimeBindingPolicy
	if err := json.Unmarshal(raw, &policy); err != nil {
		return fmt.Errorf("runtimeBindingPolicy must be a valid ordered policy: %w", err)
	}
	if policy.SelectionMode != "ordered" || len(policy.Candidates) == 0 {
		return fmt.Errorf("runtimeBindingPolicy requires selectionMode=ordered and at least one candidate")
	}
	if policy.FallbackMode != "disabled" && policy.FallbackMode != "fresh" {
		return fmt.Errorf("runtimeBindingPolicy fallbackMode must be disabled or fresh")
	}
	for i, candidate := range policy.Candidates {
		if err := candidate.Binding.Validate(); err != nil {
			return fmt.Errorf("runtimeBindingPolicy candidate %d: %w", i, err)
		}
		if err := controlmodel.ValidateJSONObject(candidate.RequiredCapabilities, "requiredCapabilities"); err != nil {
			return fmt.Errorf("runtimeBindingPolicy candidate %d: %w", i, err)
		}
		if err := controlmodel.ValidateJSONObject(candidate.SecurityConstraints, "securityConstraints"); err != nil {
			return fmt.Errorf("runtimeBindingPolicy candidate %d: %w", i, err)
		}
	}
	return nil
}

func ValidateContentPolicy(policy controlmodel.TeamPolicy, content string) error {
	if policy.SecretPolicy == "block" && secretPattern.MatchString(content) {
		return fmt.Errorf("content blocked by Team secret policy")
	}
	if policy.PIIPolicy == "block" && piiPattern.MatchString(content) {
		return fmt.Errorf("content blocked by Team PII policy")
	}
	return nil
}

// TransitionIssue applies the product-level acceptance guard before the
// repository records a state transition. A physical runtime/task finishing is
// deliberately insufficient to close an Issue.
func (s *Service) TransitionIssue(ctx context.Context, id uuid.UUID, expectedVersion int64, status controlmodel.IssueStatus, actor controlmodel.Actor, reason string) (*controlmodel.Issue, error) {
	if _, err := s.ValidateIssueTransition(ctx, id, expectedVersion, status, actor); err != nil {
		return nil, err
	}
	return s.Store.Collaboration().TransitionIssue(ctx, id, expectedVersion, status, actor, reason)
}

// ValidateIssueTransition performs acceptance and optimistic concurrency checks
// without mutating the local projection. External Work Sources use it before
// sending the authoritative command.
func (s *Service) ValidateIssueTransition(ctx context.Context, id uuid.UUID, expectedVersion int64, status controlmodel.IssueStatus, actor controlmodel.Actor) (*controlmodel.Issue, error) {
	issue, err := s.Store.Collaboration().GetIssue(ctx, id)
	if err != nil {
		return nil, err
	}
	if status == controlmodel.IssueDone {
		if err := s.validateAcceptance(ctx, issue, actor); err != nil {
			return nil, err
		}
	}
	if expectedVersion > 0 && issue.Version != expectedVersion || !controlmodel.CanTransitionIssue(issue.Status, status) {
		return nil, store.ErrConflict
	}
	return issue, nil
}

func (s *Service) validateAcceptance(ctx context.Context, issue *controlmodel.Issue, actor controlmodel.Actor) error {
	return s.validateAcceptanceIgnoringTask(ctx, issue, actor, nil)
}

func (s *Service) validateAcceptanceIgnoringTask(ctx context.Context, issue *controlmodel.Issue, actor controlmodel.Actor, ignoredTaskID *uuid.UUID) error {
	if team := s.teamForIssueOrTask(ctx, issue, nil); team != nil && team.Policy.RequireReview && actor.Type != controlmodel.ActorHuman {
		return fmt.Errorf("acceptance blocked: Team policy requires human review")
	}
	children, err := s.listAllIssues(ctx, store.IssueFilter{
		Tenant: issue.Tenant, Namespace: issue.Namespace, ParentID: &issue.ID,
	})
	if err != nil {
		return err
	}
	for _, child := range children {
		if child.Status != controlmodel.IssueDone && child.Status != controlmodel.IssueCancelled {
			return fmt.Errorf("acceptance blocked: child issue %s is %s", child.ID, child.Status)
		}
	}
	approvals, err := s.Store.Collaboration().ListApprovals(ctx, store.ApprovalFilter{
		Tenant: issue.Tenant, Namespace: issue.Namespace, TargetType: "issue", TargetRef: issue.ID.String(),
		Status: controlmodel.ApprovalPending, Limit: 1,
	})
	if err != nil {
		return err
	}
	if len(approvals) > 0 {
		return fmt.Errorf("acceptance blocked: %d approval(s) are pending", len(approvals))
	}
	tasks, err := s.listAllTasks(ctx, store.AgentTaskFilter{Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: issue.ID})
	if err != nil {
		return err
	}
	for _, task := range tasks {
		if ignoredTaskID != nil && task.ID == *ignoredTaskID {
			continue
		}
		if !controlmodel.IsAgentTaskTerminal(task.Status) {
			return fmt.Errorf("acceptance blocked: agent task %s is %s", task.ID, task.Status)
		}
		for _, input := range task.Inputs {
			switch input.State {
			case controlmodel.TaskInputProcessed, controlmodel.TaskInputDeferred, controlmodel.TaskInputDeadLetter, controlmodel.TaskInputBlocked:
			default:
				return fmt.Errorf("acceptance blocked: task input %s is %s", input.ID, input.State)
			}
		}
	}
	if issue.AssigneeType == controlmodel.AssigneeAgent || issue.AssigneeType == controlmodel.AssigneeTeam {
		comments, err := s.listAllComments(ctx, issue.ID)
		if err != nil {
			return err
		}
		hasResult := false
		for _, comment := range comments {
			if comment.Type == controlmodel.CommentResult && comment.DeletedAt == nil {
				hasResult = true
				break
			}
		}
		if !hasResult {
			return fmt.Errorf("acceptance blocked: a visible result comment is required")
		}
	}
	if err := s.evaluateAcceptanceCriteria(ctx, issue); err != nil {
		return err
	}
	runs, err := s.Store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{
		Tenant: issue.Tenant, Namespace: issue.Namespace, RootIssueID: issue.ID, ActiveOnly: true, Limit: 1,
	})
	if err != nil {
		return err
	}
	if len(runs) > 0 {
		return fmt.Errorf("acceptance blocked: orchestration run %s is %s", runs[0].ID, runs[0].State)
	}
	return nil
}

// AcceptIssueFromTask lets a Team leader follow-up accept the delegated child
// Issue represented by that task. The current task is intentionally ignored by
// the acceptance guard because accepting the work is part of completing it.
func (s *Service) AcceptIssueFromTask(ctx context.Context, taskID uuid.UUID, reason string) (*controlmodel.Issue, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	if task.TriggerType == controlmodel.AgentTaskReviewComment || !task.LeaderTask || task.TeamID == nil || controlmodel.IsAgentTaskTerminal(task.Status) {
		return nil, fmt.Errorf("only an active Team leader task can accept a delegated Issue")
	}
	issue, err := s.Store.Collaboration().GetIssue(ctx, task.IssueID)
	if err != nil {
		return nil, err
	}
	if issue.ParentIssueID == nil {
		return nil, fmt.Errorf("only a delegated child Issue can be accepted from a task")
	}
	team, err := s.TeamForTask(ctx, task)
	if err != nil {
		return nil, err
	}
	actor := controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
	if team.Policy.RequireReview {
		return nil, fmt.Errorf("acceptance blocked: Team policy requires human review")
	}
	if issue.Status == controlmodel.IssueDone {
		return issue, nil
	}
	if err = s.validateAcceptanceIgnoringTask(ctx, issue, actor, &task.ID); err != nil {
		return nil, err
	}
	if issue.Status != controlmodel.IssueInProgress && issue.Status != controlmodel.IssueInReview {
		issue, err = s.Store.Collaboration().TransitionIssue(ctx, issue.ID, issue.Version,
			controlmodel.IssueInProgress, actor, "leader reviewing delegated result")
		if err != nil {
			return nil, err
		}
	}
	if strings.TrimSpace(reason) == "" {
		reason = "accepted by Team leader"
	}
	return s.Store.Collaboration().TransitionIssue(ctx, issue.ID, issue.Version,
		controlmodel.IssueDone, actor, reason)
}

// CancelBlockedIssueFromTask lets a Team leader explicitly abandon a blocked
// delegated obligation after deciding that a degraded/partial result is still
// useful. A worker failure alone never implies cancellation.
func (s *Service) CancelBlockedIssueFromTask(ctx context.Context, taskID uuid.UUID, reason string) (*controlmodel.Issue, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	if task.TriggerType == controlmodel.AgentTaskReviewComment || !task.LeaderTask || task.TeamID == nil || controlmodel.IsAgentTaskTerminal(task.Status) {
		return nil, fmt.Errorf("only an active Team leader task can cancel a blocked delegated Issue")
	}
	issue, err := s.Store.Collaboration().GetIssue(ctx, task.IssueID)
	if err != nil {
		return nil, err
	}
	if issue.ParentIssueID == nil || issue.Status != controlmodel.IssueBlocked {
		return nil, fmt.Errorf("only a blocked delegated child Issue can be cancelled from a task")
	}
	tasks, err := s.listAllTasks(ctx, store.AgentTaskFilter{
		Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: issue.ID,
	})
	if err != nil {
		return nil, err
	}
	for _, candidate := range tasks {
		if candidate.ID != task.ID && !candidate.LeaderTask && !controlmodel.IsAgentTaskTerminal(candidate.Status) {
			return nil, fmt.Errorf("delegated Issue still has active worker task %s", candidate.ID)
		}
	}
	if strings.TrimSpace(reason) == "" {
		reason = "Team leader accepted a degraded result and skipped blocked work"
	}
	return s.Store.Collaboration().TransitionIssue(ctx, issue.ID, issue.Version,
		controlmodel.IssueCancelled, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}, reason)
}

// ReopenBlockedIssueFromTask prepares the current delegated Issue for a
// leader-directed retry or replan. It deliberately does not choose an Agent;
// the orchestration command remains the authoritative scheduling decision.
func (s *Service) ReopenBlockedIssueFromTask(ctx context.Context, taskID uuid.UUID, reason string) (*controlmodel.Issue, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	if task.TriggerType == controlmodel.AgentTaskReviewComment || !task.LeaderTask || task.TeamID == nil || controlmodel.IsAgentTaskTerminal(task.Status) {
		return nil, fmt.Errorf("only an active Team leader task can reopen a blocked delegated Issue")
	}
	issue, err := s.Store.Collaboration().GetIssue(ctx, task.IssueID)
	if err != nil || issue.Status != controlmodel.IssueBlocked {
		return issue, err
	}
	if strings.TrimSpace(reason) == "" {
		reason = "Team leader scheduled another attempt"
	}
	return s.Store.Collaboration().TransitionIssue(ctx, issue.ID, issue.Version,
		controlmodel.IssueInProgress, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}, reason)
}

// acceptanceCriteria is intentionally small and portable across runtimes.
// Unknown JSON fields are preserved by Issue storage but do not acquire magic
// execution semantics. A checklist entry is required unless required=false.
type acceptanceCriteria struct {
	RequiredResult   bool                  `json:"requiredResult,omitempty"`
	MinimumArtifacts int                   `json:"minimumArtifacts,omitempty"`
	MinimumApprovals int                   `json:"minimumApprovals,omitempty"`
	Checklist        []acceptanceChecklist `json:"checklist,omitempty"`
}

type acceptanceChecklist struct {
	ID        string `json:"id,omitempty"`
	Text      string `json:"text,omitempty"`
	Required  *bool  `json:"required,omitempty"`
	Satisfied bool   `json:"satisfied"`
}

func (s *Service) evaluateAcceptanceCriteria(ctx context.Context, issue *controlmodel.Issue) error {
	raw := strings.TrimSpace(string(issue.AcceptanceCriteria))
	if raw == "" || raw == "null" || raw == "[]" || raw == "{}" {
		return nil
	}
	var criteria acceptanceCriteria
	if err := json.Unmarshal(issue.AcceptanceCriteria, &criteria); err != nil {
		return fmt.Errorf("acceptance blocked: invalid acceptance criteria: %w", err)
	}
	if criteria.MinimumArtifacts < 0 || criteria.MinimumApprovals < 0 {
		return fmt.Errorf("acceptance blocked: acceptance criteria counts cannot be negative")
	}
	for i, item := range criteria.Checklist {
		required := item.Required == nil || *item.Required
		if required && !item.Satisfied {
			label := strings.TrimSpace(item.Text)
			if label == "" {
				label = strings.TrimSpace(item.ID)
			}
			if label == "" {
				label = fmt.Sprintf("item %d", i+1)
			}
			return fmt.Errorf("acceptance blocked: checklist %s is not satisfied", label)
		}
	}
	if criteria.MinimumArtifacts > 0 {
		artifacts, err := s.Store.Collaboration().ListArtifacts(ctx, issue.Tenant, issue.Namespace, "issue", issue.ID.String())
		if err != nil {
			return err
		}
		if len(artifacts) < criteria.MinimumArtifacts {
			return fmt.Errorf("acceptance blocked: requires at least %d issue artifact(s)", criteria.MinimumArtifacts)
		}
	}
	if criteria.MinimumApprovals > 0 {
		approvals, err := s.listAllApprovals(ctx, store.ApprovalFilter{
			Tenant: issue.Tenant, Namespace: issue.Namespace, TargetType: "issue", TargetRef: issue.ID.String(),
			Status: controlmodel.ApprovalApproved,
		})
		if err != nil {
			return err
		}
		if len(approvals) < criteria.MinimumApprovals {
			return fmt.Errorf("acceptance blocked: requires at least %d approved approval(s)", criteria.MinimumApprovals)
		}
	}
	// Agent/Team Issues already require a result. Explicit criteria also apply
	// to human-owned and unassigned Issues.
	if criteria.RequiredResult {
		comments, err := s.listAllComments(ctx, issue.ID)
		if err != nil {
			return err
		}
		for _, comment := range comments {
			if comment.Type == controlmodel.CommentResult && comment.DeletedAt == nil {
				return nil
			}
		}
		return fmt.Errorf("acceptance blocked: a visible result comment is required")
	}
	return nil
}

const collaborationPageSize = 500

func (s *Service) listAllIssues(ctx context.Context, filter store.IssueFilter) ([]*controlmodel.Issue, error) {
	var out []*controlmodel.Issue
	for offset := 0; ; offset += collaborationPageSize {
		filter.Limit, filter.Offset = collaborationPageSize, offset
		page, err := s.Store.Collaboration().ListIssues(ctx, filter)
		if err != nil {
			return nil, err
		}
		out = append(out, page...)
		if len(page) < collaborationPageSize {
			return out, nil
		}
	}
}

func (s *Service) listAllTasks(ctx context.Context, filter store.AgentTaskFilter) ([]*controlmodel.AgentTask, error) {
	var out []*controlmodel.AgentTask
	for offset := 0; ; offset += collaborationPageSize {
		filter.Limit, filter.Offset = collaborationPageSize, offset
		page, err := s.Store.Collaboration().ListAgentTasks(ctx, filter)
		if err != nil {
			return nil, err
		}
		out = append(out, page...)
		if len(page) < collaborationPageSize {
			return out, nil
		}
	}
}

func (s *Service) listAllApprovals(ctx context.Context, filter store.ApprovalFilter) ([]*controlmodel.Approval, error) {
	var out []*controlmodel.Approval
	for offset := 0; ; offset += collaborationPageSize {
		filter.Limit, filter.Offset = collaborationPageSize, offset
		page, err := s.Store.Collaboration().ListApprovals(ctx, filter)
		if err != nil {
			return nil, err
		}
		out = append(out, page...)
		if len(page) < collaborationPageSize {
			return out, nil
		}
	}
}

func (s *Service) listAllComments(ctx context.Context, issueID uuid.UUID) ([]*controlmodel.Comment, error) {
	var out []*controlmodel.Comment
	for offset := 0; ; offset += collaborationPageSize {
		page, err := s.Store.Collaboration().ListComments(ctx, issueID, store.CommentListOptions{Limit: collaborationPageSize, Offset: offset})
		if err != nil {
			return nil, err
		}
		out = append(out, page...)
		if len(page) < collaborationPageSize {
			return out, nil
		}
	}
}

type CreateIssueRequest struct {
	// ID is an optional durable intake identity; callers must authorize before reuse.
	ID                  uuid.UUID
	Access              controlmodel.IssueAccess
	Tenant              string
	Namespace           string
	Title               string
	Description         string
	Priority            string
	Kind                controlmodel.IssueKind
	Visibility          controlmodel.IssueVisibility
	CompletionPolicy    controlmodel.IssueCompletionPolicy
	Creator             controlmodel.Actor
	AssigneeType        controlmodel.AssigneeType
	AssigneeRef         string
	ExecutionTargetType string
	ExecutionTargetRef  string
	ParentIssueID       *uuid.UUID
	AcceptanceCriteria  json.RawMessage
	ContextRefs         json.RawMessage
	SourceType          string
	SourceRef           string
	DueAt               *time.Time
}

func (s *Service) CreateIssue(ctx context.Context, req CreateIssueRequest) (*controlmodel.Issue, *controlmodel.AgentTask, error) {
	if s == nil || s.Store == nil {
		return nil, nil, fmt.Errorf("collaboration store is unavailable")
	}
	if strings.TrimSpace(req.Title) == "" {
		return nil, nil, fmt.Errorf("title is required")
	}
	if req.ParentIssueID != nil {
		depth := int32(1)
		currentID := req.ParentIssueID
		for currentID != nil {
			parent, err := s.Store.Collaboration().GetIssue(ctx, *currentID)
			if err != nil {
				return nil, nil, err
			}
			if parent.Tenant != req.Tenant && req.Tenant != "" || parent.Namespace != req.Namespace && req.Namespace != "" {
				return nil, nil, store.ErrNotFound
			}
			depth++
			if depth > 8 {
				return nil, nil, fmt.Errorf("maximum child issue depth exceeded")
			}
			currentID = parent.ParentIssueID
		}
	}
	if req.DueAt == nil && req.AssigneeType == controlmodel.AssigneeTeam {
		if teamID, parseErr := uuid.Parse(req.AssigneeRef); parseErr == nil {
			if team, loadErr := s.Store.Collaboration().GetTeam(ctx, teamID); loadErr == nil && team.Policy.IssueSLASeconds > 0 {
				due := time.Now().UTC().Add(time.Duration(team.Policy.IssueSLASeconds) * time.Second)
				req.DueAt = &due
			}
		}
	}
	issue, err := s.Store.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		ID:     req.ID,
		Tenant: req.Tenant, Namespace: req.Namespace, Title: strings.TrimSpace(req.Title),
		Description: req.Description, Status: controlmodel.IssueBacklog,
		Priority: req.Priority, Kind: req.Kind, Visibility: req.Visibility,
		Access: req.Access, CompletionPolicy: req.CompletionPolicy, Creator: req.Creator, ParentIssueID: req.ParentIssueID,
		AssigneeType: req.AssigneeType, AssigneeRef: req.AssigneeRef,
		ExecutionTargetType: req.ExecutionTargetType, ExecutionTargetRef: req.ExecutionTargetRef,
		AcceptanceCriteria: req.AcceptanceCriteria, ContextRefs: req.ContextRefs,
		SourceType: req.SourceType, SourceRef: req.SourceRef, DueAt: req.DueAt,
	})
	if err != nil {
		return nil, nil, err
	}
	if req.AssigneeType == "" || req.AssigneeRef == "" {
		return issue, nil, nil
	}
	tasks, err := s.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 1})
	if err != nil {
		return nil, nil, err
	}
	if len(tasks) == 0 {
		return issue, nil, nil
	}
	return issue, tasks[0], nil
}

// CreateChildFromTask is the agent-authorized delegation path. Only a Team
// leader task may create child work, and the Team policy bounds nesting.
func (s *Service) CreateChildFromTask(ctx context.Context, taskID uuid.UUID, req CreateIssueRequest) (*controlmodel.Issue, *controlmodel.AgentTask, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, nil, err
	}
	if task.TriggerType == controlmodel.AgentTaskReviewComment || !task.LeaderTask || task.TeamID == nil || controlmodel.IsAgentTaskTerminal(task.Status) {
		return nil, nil, fmt.Errorf("only an active Team leader AgentTask may create child Issues")
	}
	parent, err := s.Store.Collaboration().GetIssue(ctx, task.IssueID)
	if err != nil {
		return nil, nil, err
	}
	if task.ParentTaskID != nil {
		if parent.Status == controlmodel.IssueBlocked {
			return nil, nil, fmt.Errorf("blocked Issue must be explicitly replanned, cancelled, or escalated before creating more child work")
		}
		children, listErr := s.listAllIssues(ctx, store.IssueFilter{
			Tenant: parent.Tenant, Namespace: parent.Namespace, ParentID: &parent.ID,
		})
		if listErr != nil {
			return nil, nil, listErr
		}
		for _, child := range children {
			if child.Status == controlmodel.IssueBlocked {
				return nil, nil, fmt.Errorf("blocked child Issue %s must be explicitly replanned, cancelled, or escalated before creating more child work", child.ID)
			}
		}
	}
	team, err := s.TeamForTask(ctx, task)
	if err != nil {
		return nil, nil, err
	}
	switch req.AssigneeType {
	case controlmodel.AssigneeAgent:
		if _, member := teamAgentRole(team, req.AssigneeRef); !member && !team.Policy.AllowExternalDelegation {
			return nil, nil, fmt.Errorf("child Issue assignee is not in the Team snapshot")
		}
	case controlmodel.AssigneeTeam:
		if req.AssigneeRef != task.TeamID.String() && !team.Policy.AllowExternalDelegation {
			return nil, nil, fmt.Errorf("child Issue Team assignee is outside the Team snapshot")
		}
	}
	maxDepth := team.Policy.MaxChildDepth
	if maxDepth <= 0 {
		maxDepth = 4
	}
	depth := int32(1)
	for current := parent; current.ParentIssueID != nil; {
		depth++
		current, err = s.Store.Collaboration().GetIssue(ctx, *current.ParentIssueID)
		if err != nil {
			return nil, nil, err
		}
	}
	if depth >= maxDepth {
		return nil, nil, fmt.Errorf("Team child Issue budget exceeded")
	}
	if team.Policy.MaxChildIssues > 0 {
		children, listErr := s.Store.Collaboration().ListIssues(ctx, store.IssueFilter{
			Tenant: parent.Tenant, Namespace: parent.Namespace, ParentID: &parent.ID, Limit: int(team.Policy.MaxChildIssues) + 1,
		})
		if listErr != nil {
			return nil, nil, listErr
		}
		if len(children) >= int(team.Policy.MaxChildIssues) {
			return nil, nil, fmt.Errorf("Team child Issue count budget exceeded")
		}
	}
	req.Tenant, req.Namespace, req.ParentIssueID = parent.Tenant, parent.Namespace, &parent.ID
	req.Kind, req.Visibility, req.CompletionPolicy = parent.Kind, parent.Visibility, parent.CompletionPolicy
	req.Creator = controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
	req.SourceType, req.SourceRef = "agent-task", task.ID.String()
	if req.DueAt == nil && team.Policy.IssueSLASeconds > 0 {
		due := time.Now().UTC().Add(time.Duration(team.Policy.IssueSLASeconds) * time.Second)
		req.DueAt = &due
	}
	return s.CreateIssue(ctx, req)
}

type MentionTarget struct {
	Type controlmodel.AssigneeType `json:"type"`
	Ref  string                    `json:"ref"`
}

type AddCommentRequest struct {
	ID              uuid.UUID
	IssueID         uuid.UUID
	ParentID        *uuid.UUID
	Author          controlmodel.Actor
	Content         string
	Type            controlmodel.CommentType
	Mentions        []MentionTarget
	SourceTaskID    *uuid.UUID
	SourceAttemptID *uuid.UUID
	// SuppressImplicitRouting records the comment without treating it as new
	// work for the Issue assignee. Explicit mentions still route normally.
	SuppressImplicitRouting bool
}

func (s *Service) AddComment(ctx context.Context, req AddCommentRequest) (*store.CreateCommentResult, error) {
	if s == nil || s.Store == nil {
		return nil, fmt.Errorf("collaboration store is unavailable")
	}
	if req.IssueID == uuid.Nil || strings.TrimSpace(req.Content) == "" {
		return nil, fmt.Errorf("issueId and content are required")
	}
	issue, err := s.Store.Collaboration().GetIssue(ctx, req.IssueID)
	if err != nil {
		return nil, err
	}
	policy := s.policyForIssueOrTask(ctx, issue, req.SourceTaskID)
	if err := ValidateContentPolicy(policy, req.Content); err != nil {
		return nil, err
	}
	commentType := req.Type
	if commentType == "" {
		commentType = controlmodel.CommentGeneral
	}
	suppressImplicitRouting := req.SuppressImplicitRouting ||
		commentType == controlmodel.CommentProgress || commentType == controlmodel.CommentStatus
	if req.SourceTaskID != nil {
		source, loadErr := s.Store.Collaboration().GetAgentTask(ctx, *req.SourceTaskID)
		if loadErr != nil {
			return nil, loadErr
		}
		if source.TeamID != nil && source.LeaderTask && source.ParentTaskID != nil {
			comments, listErr := s.listAllComments(ctx, issue.ID)
			if listErr != nil {
				return nil, listErr
			}
			for index := len(comments) - 1; index >= 0; index-- {
				comment := comments[index]
				if comment.SourceTaskID == nil || *comment.SourceTaskID != source.ID ||
					(comment.Type != controlmodel.CommentProgress && comment.Type != controlmodel.CommentStatus) {
					continue
				}
				if len(req.Mentions) == 0 &&
					(commentType == controlmodel.CommentProgress || commentType == controlmodel.CommentStatus) {
					return &store.CreateCommentResult{Comment: comment, Routes: comment.Routes}, nil
				}
				if commentType == controlmodel.CommentResult {
					for _, route := range comment.Routes {
						if route.TargetType == controlmodel.AssigneeHuman && route.Outcome == controlmodel.RouteQueued {
							return &store.CreateCommentResult{Comment: comment, Routes: comment.Routes}, nil
						}
					}
				}
			}
		}
	}
	mentions := make([]controlmodel.Mention, 0, len(req.Mentions))
	targets := make([]store.CommentTarget, 0, len(req.Mentions)+1)
	for _, target := range req.Mentions {
		mention := controlmodel.Mention{TargetType: target.Type, TargetRef: strings.TrimSpace(target.Ref)}
		if mention.TargetRef == "" {
			return nil, fmt.Errorf("mention target ref is required")
		}
		mentions = append(mentions, mention)
		if mention.TargetRef == "all" {
			switch mention.TargetType {
			case controlmodel.AssigneeHuman:
				subscribers, listErr := s.Store.Collaboration().ListIssueSubscribers(ctx, issue.ID)
				if listErr != nil {
					return nil, listErr
				}
				humanTargets := 0
				for _, subscriber := range subscribers {
					if subscriber.SubscriberType == controlmodel.AssigneeHuman {
						targets = append(targets, store.CommentTarget{TargetType: controlmodel.AssigneeHuman,
							TargetRef: subscriber.SubscriberRef, RouteType: controlmodel.RouteExplicit})
						humanTargets++
					}
				}
				if humanTargets == 0 {
					targets = append(targets, store.CommentTarget{TargetType: controlmodel.AssigneeHuman,
						TargetRef: "all", RouteType: controlmodel.RouteExplicit, Blocked: true, ReasonCode: "no_human_subscribers"})
				}
			case controlmodel.AssigneeAgent:
				team := s.teamForIssueOrTask(ctx, issue, req.SourceTaskID)
				if team == nil || !team.Policy.AllowMentionAll {
					targets = append(targets, store.CommentTarget{TargetType: controlmodel.AssigneeAgent,
						TargetRef: "all", RouteType: controlmodel.RouteExplicit, Blocked: true, ReasonCode: "mention_all_not_allowed"})
					continue
				}
				seen := map[string]bool{}
				for _, member := range append([]controlmodel.CollaborationTeamMember{{AgentRef: team.LeaderAgentRef, Role: "leader"}}, team.Members...) {
					key := member.AgentRef + "\x00" + member.Role
					if member.AgentRef == "" || seen[key] {
						continue
					}
					seen[key] = true
					targets = append(targets, s.guardTarget(ctx, issue, req.SourceTaskID, store.CommentTarget{
						TargetType: controlmodel.AssigneeAgent, TargetRef: member.AgentRef, AgentRef: member.AgentRef,
						TeamID: &team.ID, TeamRole: member.Role, RouteType: controlmodel.RouteExplicit}))
				}
			default:
				targets = append(targets, store.CommentTarget{TargetType: mention.TargetType,
					TargetRef: "all", RouteType: controlmodel.RouteExplicit, Blocked: true, ReasonCode: "mention_all_not_supported"})
			}
			continue
		}
		if mention.TargetType == controlmodel.AssigneeAgent && req.SourceTaskID != nil {
			if source, taskErr := s.Store.Collaboration().GetAgentTask(ctx, *req.SourceTaskID); taskErr == nil && source.TeamID != nil {
				if team, teamErr := s.TeamForTask(ctx, source); teamErr == nil {
					role, member := teamAgentRole(team, mention.TargetRef)
					if !member && !team.Policy.AllowExternalDelegation {
						targets = append(targets, store.CommentTarget{TargetType: mention.TargetType,
							TargetRef: mention.TargetRef, RouteType: controlmodel.RouteExplicit,
							Blocked: true, ReasonCode: "target_not_in_team_snapshot"})
						continue
					}
					if !member {
						role = "external"
					}
					resolved := store.CommentTarget{TargetType: controlmodel.AssigneeAgent,
						TargetRef: mention.TargetRef, AgentRef: mention.TargetRef,
						TeamID: source.TeamID, TeamRole: role, RouteType: controlmodel.RouteExplicit}
					targets = append(targets, s.guardTarget(ctx, issue, req.SourceTaskID, resolved))
					continue
				}
			}
		}
		resolved, err := s.resolveTarget(ctx, issue, target, controlmodel.RouteExplicit)
		if err != nil {
			targets = append(targets, store.CommentTarget{TargetType: target.Type,
				TargetRef: target.Ref, RouteType: controlmodel.RouteExplicit,
				Blocked: true, ReasonCode: "target_unavailable"})
			continue
		}
		targets = append(targets, s.guardTarget(ctx, issue, req.SourceTaskID, resolved))
	}
	if policy.MaxFanout > 0 && len(targets) > int(policy.MaxFanout) {
		return nil, fmt.Errorf("Team mention fanout budget exceeded")
	}
	if !suppressImplicitRouting && len(targets) == 0 && commentType == controlmodel.CommentResult && req.SourceTaskID != nil {
		source, loadErr := s.Store.Collaboration().GetAgentTask(ctx, *req.SourceTaskID)
		if loadErr != nil {
			return nil, loadErr
		}
		targets, err = s.completionTargets(ctx, source, req.ParentID)
		if err != nil {
			return nil, err
		}
	}
	if !suppressImplicitRouting && len(targets) == 0 && !(commentType == controlmodel.CommentResult && req.SourceTaskID != nil) {
		if req.ParentID != nil {
			parent, loadErr := s.Store.Collaboration().GetComment(ctx, *req.ParentID)
			if loadErr != nil {
				return nil, loadErr
			}
			if parent.Author.Type == controlmodel.ActorAgent {
				target := store.CommentTarget{TargetType: controlmodel.AssigneeAgent,
					TargetRef: parent.Author.Ref, AgentRef: parent.Author.Ref,
					RouteType: controlmodel.RouteThreadParent}
				if parent.SourceTaskID != nil {
					if sourceTask, taskErr := s.Store.Collaboration().GetAgentTask(ctx, *parent.SourceTaskID); taskErr == nil {
						target.TeamID, target.TeamRole = sourceTask.TeamID, sourceTask.TeamRole
						if req.Author.Type == controlmodel.ActorHuman && sourceTask.LeaderTask {
							target.ParentTaskID = &sourceTask.ID
							target.RouteType = controlmodel.RouteTeamLeader
						}
					}
				}
				targets = append(targets, s.guardTarget(ctx, issue, req.SourceTaskID, target))
			} else if parent.Author.Type == controlmodel.ActorHuman && req.Author.Type == controlmodel.ActorAgent {
				targets = append(targets, store.CommentTarget{TargetType: controlmodel.AssigneeHuman,
					TargetRef: parent.Author.Ref, RouteType: controlmodel.RouteThreadParent})
			}
		}
		if len(targets) == 0 && issue.AssigneeType != "" && issue.AssigneeRef != "" {
			resolved, resolveErr := s.resolveTarget(ctx, issue, MentionTarget{Type: issue.AssigneeType, Ref: issue.AssigneeRef}, controlmodel.RouteAssignee)
			if resolveErr == nil {
				if req.SourceTaskID == nil && req.Author.Type == controlmodel.ActorHuman &&
					resolved.TargetType == controlmodel.AssigneeAgent && resolved.TeamID == nil {
					teamID, role, parentTaskID, contextErr := s.activeTeamAssigneeContext(ctx, issue, resolved.AgentRef)
					if contextErr != nil {
						return nil, contextErr
					}
					resolved.TeamID, resolved.TeamRole, resolved.ParentTaskID = teamID, role, parentTaskID
				}
				if req.SourceTaskID == nil && req.Author.Type == controlmodel.ActorHuman && resolved.TeamID != nil {
					if resolved.ParentTaskID == nil {
						parentTaskID, parentErr := s.activeTeamRunParent(ctx, issue, *resolved.TeamID)
						if parentErr != nil {
							return nil, parentErr
						}
						resolved.ParentTaskID = parentTaskID
					}
				}
				// An implicit assignee route created by a Team task is still Team
				// work. Preserve the snapshot lineage so retries and exhausted
				// failures return to the coordinator instead of becoming a
				// fail-fast standalone node.
				if resolved.TargetType == controlmodel.AssigneeAgent && req.SourceTaskID != nil {
					if source, taskErr := s.Store.Collaboration().GetAgentTask(ctx, *req.SourceTaskID); taskErr == nil && source.TeamID != nil {
						if team, teamErr := s.TeamForTask(ctx, source); teamErr == nil {
							role, member := teamAgentRole(team, resolved.TargetRef)
							if member || team.Policy.AllowExternalDelegation {
								if !member {
									role = "external"
								}
								resolved.TeamID, resolved.TeamRole = source.TeamID, role
							}
						}
					}
				}
				targets = append(targets, s.guardTarget(ctx, issue, req.SourceTaskID, resolved))
			}
		}
	}
	if req.SourceTaskID == nil && req.Author.Type == controlmodel.ActorHuman && issue.ParentIssueID != nil {
		for index := range targets {
			target := &targets[index]
			if target.Blocked || target.AgentRef != issue.AssigneeRef || issue.AssigneeType != controlmodel.AssigneeAgent {
				continue
			}
			teamID, role, parent, contextErr := s.activeTeamAssigneeContext(ctx, issue, target.AgentRef)
			if contextErr != nil {
				return nil, contextErr
			}
			target.TeamID, target.TeamRole, target.ParentTaskID = teamID, role, parent
		}
	}
	result, err := s.Store.Collaboration().CreateComment(ctx, store.CreateCommentRequest{
		Comment: &controlmodel.Comment{ID: req.ID, IssueID: issue.ID, ParentID: req.ParentID,
			Author: req.Author, Content: strings.TrimSpace(req.Content), Type: commentType,
			SourceTaskID: req.SourceTaskID, SourceAttemptID: req.SourceAttemptID},
		Mentions: mentions, Targets: targets,
	})
	if err != nil {
		return nil, err
	}
	for _, route := range result.Routes {
		metrics.RecordCommentRoute(route.Namespace, string(route.TargetType), string(route.Outcome))
	}
	return result, nil
}

// activeTeamAssigneeContext recovers the immutable Team lineage for a human
// follow-up posted directly on a child Issue assigned to a Team worker. The
// mutable Issue assignee only identifies the Agent, so without this lookup the
// reply would incorrectly start an unrelated direct Run.
func (s *Service) activeTeamAssigneeContext(ctx context.Context, issue *controlmodel.Issue,
	agentRef string) (*uuid.UUID, string, *uuid.UUID, error) {
	runs, err := s.Store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{
		Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: issue.ID, ActiveOnly: true, Limit: 3,
	})
	if err != nil {
		return nil, "", nil, err
	}
	if len(runs) == 0 {
		return s.failedTeamAssigneeContext(ctx, issue, agentRef)
	}
	if len(runs) > 1 {
		return nil, "", nil, fmt.Errorf("Issue %s has multiple active orchestration Runs", issue.ID)
	}
	tasks, err := s.listAllTasks(ctx, store.AgentTaskFilter{
		Tenant: issue.Tenant, Namespace: issue.Namespace, RunID: runs[0].ID,
	})
	if err != nil {
		return nil, "", nil, err
	}
	var worker, leader *controlmodel.AgentTask
	for _, task := range tasks {
		if task.TeamID == nil {
			continue
		}
		if task.IssueID == issue.ID && task.AgentRef == agentRef && !task.LeaderTask &&
			(worker == nil || task.CreatedAt.After(worker.CreatedAt)) {
			worker = task
		}
	}
	if worker == nil {
		return nil, "", nil, nil
	}
	for _, task := range tasks {
		if task.TeamID == nil || *task.TeamID != *worker.TeamID || !task.LeaderTask ||
			leader != nil && !task.CreatedAt.After(leader.CreatedAt) {
			continue
		}
		leader = task
	}
	var parentTaskID *uuid.UUID
	if leader != nil {
		id := leader.ID
		parentTaskID = &id
	} else {
		parentTaskID = worker.ParentTaskID
	}
	teamID := *worker.TeamID
	return &teamID, worker.TeamRole, parentTaskID, nil
}

// activeTeamRunParent lets human input resume a quiescent waiting coordinator.
// Looking only for a currently running AgentTask incorrectly starts a second
// adaptive Run once every worker and leader follow-up has reached a terminal
// task state.
func (s *Service) activeTeamRunParent(ctx context.Context, issue *controlmodel.Issue, teamID uuid.UUID) (*uuid.UUID, error) {
	runs, err := s.Store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{
		Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: issue.ID, ActiveOnly: true, Limit: 3,
	})
	if err != nil || len(runs) == 0 {
		return nil, err
	}
	if len(runs) > 1 {
		return nil, fmt.Errorf("Issue %s has multiple active orchestration Runs", issue.ID)
	}
	tasks, err := s.listAllTasks(ctx, store.AgentTaskFilter{
		Tenant: issue.Tenant, Namespace: issue.Namespace, RunID: runs[0].ID, TeamID: teamID,
	})
	if err != nil {
		return nil, err
	}
	var latest *controlmodel.AgentTask
	for _, task := range tasks {
		if !task.LeaderTask || latest != nil && !task.CreatedAt.After(latest.CreatedAt) {
			continue
		}
		latest = task
	}
	if latest == nil {
		return nil, nil
	}
	id := latest.ID
	return &id, nil
}

func teamAgentRole(team *controlmodel.CollaborationTeam, agentRef string) (string, bool) {
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

func (s *Service) policyForIssueOrTask(ctx context.Context, issue *controlmodel.Issue, taskID *uuid.UUID) controlmodel.TeamPolicy {
	if team := s.teamForIssueOrTask(ctx, issue, taskID); team != nil {
		return team.Policy
	}
	return controlmodel.TeamPolicy{}
}

func (s *Service) ValidateIssueContent(ctx context.Context, issueID uuid.UUID, taskID *uuid.UUID, content string) error {
	issue, err := s.Store.Collaboration().GetIssue(ctx, issueID)
	if err != nil {
		return err
	}
	return ValidateContentPolicy(s.policyForIssueOrTask(ctx, issue, taskID), content)
}

func (s *Service) teamForIssueOrTask(ctx context.Context, issue *controlmodel.Issue, taskID *uuid.UUID) *controlmodel.CollaborationTeam {
	var teamID *uuid.UUID
	if taskID != nil {
		if task, err := s.Store.Collaboration().GetAgentTask(ctx, *taskID); err == nil {
			teamID = task.TeamID
			if teamID != nil {
				if team, loadErr := s.TeamForTask(ctx, task); loadErr == nil {
					return team
				}
			}
		}
	}
	if teamID == nil && issue != nil && issue.AssigneeType == controlmodel.AssigneeTeam {
		if parsed, err := uuid.Parse(issue.AssigneeRef); err == nil {
			teamID = &parsed
		}
	}
	if teamID != nil {
		if team, err := s.Store.Collaboration().GetTeam(ctx, *teamID); err == nil {
			return team
		}
	}
	return nil
}

// TeamForTask returns the immutable Team definition captured for this Run.
// The mutable catalog Team controls future Runs only.
func (s *Service) TeamForTask(ctx context.Context, task *controlmodel.AgentTask) (*controlmodel.CollaborationTeam, error) {
	if task == nil || task.TeamID == nil {
		return nil, store.ErrNotFound
	}
	snapshots, err := s.Store.Orchestration().ListTeamSnapshots(ctx, task.OrchestrationRunID)
	if err != nil {
		return nil, err
	}
	for _, snapshot := range snapshots {
		if snapshot.TeamID != *task.TeamID {
			continue
		}
		var team controlmodel.CollaborationTeam
		if err = json.Unmarshal(snapshot.Snapshot, &team); err != nil {
			return nil, fmt.Errorf("decode Run Team snapshot: %w", err)
		}
		return &team, nil
	}
	return nil, fmt.Errorf("Run %s has no snapshot for Team %s", task.OrchestrationRunID, *task.TeamID)
}

func (s *Service) RetryTask(ctx context.Context, taskID uuid.UUID, actor controlmodel.Actor) (*controlmodel.AgentTask, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	if task.TeamID != nil {
		team, loadErr := s.TeamForTask(ctx, task)
		if loadErr != nil {
			return nil, loadErr
		}
		if team.Policy.MaxTaskRetries > 0 {
			count := int32(0)
			current := task
			for current.RetryOfTaskID != nil {
				count++
				current, err = s.Store.Collaboration().GetAgentTask(ctx, *current.RetryOfTaskID)
				if err != nil {
					return nil, err
				}
			}
			if count >= team.Policy.MaxTaskRetries {
				return nil, fmt.Errorf("Team AgentTask retry budget exceeded")
			}
		}
	}
	return s.Store.Collaboration().RetryAgentTask(ctx, taskID, actor)
}

func (s *Service) guardTarget(ctx context.Context, issue *controlmodel.Issue, sourceTaskID *uuid.UUID, target store.CommentTarget) store.CommentTarget {
	if target.Blocked || target.TargetType == controlmodel.AssigneeHuman {
		return target
	}
	const defaultMaxHops int32 = 8
	const defaultMaxActiveTasks = 32
	maxHops, maxActive := defaultMaxHops, int32(defaultMaxActiveTasks)
	if target.TeamID != nil {
		if team := s.teamForIssueOrTask(ctx, issue, sourceTaskID); team != nil {
			if team.Policy.MaxHops > 0 {
				maxHops = team.Policy.MaxHops
			}
			if team.Policy.MaxActiveTasks > 0 {
				maxActive = team.Policy.MaxActiveTasks
			}
		}
	}
	if sourceTaskID != nil {
		if source, err := s.Store.Collaboration().GetAgentTask(ctx, *sourceTaskID); err == nil {
			if source.HopCount+1 > maxHops {
				target.Blocked, target.ReasonCode = true, "max_hops_exceeded"
				return target
			}
			if source.AgentRef == target.AgentRef {
				target.Blocked, target.ReasonCode = true, "self_trigger"
				return target
			}
		}
	}
	if target.TeamID != nil {
		tasks, err := s.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, TeamID: *target.TeamID, Limit: 500})
		if err == nil {
			var active int32
			for _, task := range tasks {
				if !controlmodel.IsAgentTaskTerminal(task.Status) {
					active++
				}
			}
			if active >= maxActive {
				target.Blocked, target.ReasonCode = true, "active_task_budget_exceeded"
			}
		}
	}
	return target
}

func (s *Service) PreviewCommentRoutes(ctx context.Context, issueID uuid.UUID, parentID *uuid.UUID, author controlmodel.Actor, mentions []MentionTarget) ([]store.CommentTarget, error) {
	issue, err := s.Store.Collaboration().GetIssue(ctx, issueID)
	if err != nil {
		return nil, err
	}
	targets := make([]store.CommentTarget, 0, len(mentions)+1)
	for _, mention := range mentions {
		target, resolveErr := s.resolveTarget(ctx, issue, mention, controlmodel.RouteExplicit)
		if resolveErr != nil {
			target = store.CommentTarget{TargetType: mention.Type, TargetRef: mention.Ref,
				RouteType: controlmodel.RouteExplicit, Blocked: true, ReasonCode: "target_unavailable"}
		}
		if target.AgentRef == author.Ref && author.Type == controlmodel.ActorAgent {
			target.Blocked, target.ReasonCode = true, "self_trigger"
		}
		targets = append(targets, target)
	}
	if len(targets) == 0 && parentID != nil {
		parent, loadErr := s.Store.Collaboration().GetComment(ctx, *parentID)
		if loadErr != nil {
			return nil, loadErr
		}
		if parent.Author.Type == controlmodel.ActorAgent {
			targets = append(targets, store.CommentTarget{TargetType: controlmodel.AssigneeAgent,
				TargetRef: parent.Author.Ref, AgentRef: parent.Author.Ref,
				RouteType: controlmodel.RouteThreadParent})
		} else if parent.Author.Type == controlmodel.ActorHuman && author.Type == controlmodel.ActorAgent {
			targets = append(targets, store.CommentTarget{TargetType: controlmodel.AssigneeHuman,
				TargetRef: parent.Author.Ref, RouteType: controlmodel.RouteThreadParent})
		}
	}
	if len(targets) == 0 && issue.AssigneeType != "" {
		target, resolveErr := s.resolveTarget(ctx, issue, MentionTarget{Type: issue.AssigneeType, Ref: issue.AssigneeRef}, controlmodel.RouteAssignee)
		if resolveErr == nil {
			targets = append(targets, target)
		}
	}
	return targets, nil
}

func (s *Service) resolveTarget(ctx context.Context, issue *controlmodel.Issue, mention MentionTarget, routeType controlmodel.CommentRouteType) (store.CommentTarget, error) {
	target := store.CommentTarget{TargetType: mention.Type, TargetRef: mention.Ref, RouteType: routeType}
	switch mention.Type {
	case controlmodel.AssigneeHuman:
		return target, nil
	case controlmodel.AssigneeAgent:
		target.AgentRef = mention.Ref
		return target, nil
	case controlmodel.AssigneeTeam:
		teamID, err := uuid.Parse(mention.Ref)
		if err != nil {
			return target, store.ErrNotFound
		}
		team, err := s.Store.Collaboration().GetTeam(ctx, teamID)
		if err != nil || team.Tenant != issue.Tenant || team.Namespace != issue.Namespace || team.Status != controlmodel.TeamActive || team.ArchivedAt != nil {
			return target, store.ErrNotFound
		}
		target.AgentRef, target.TeamID, target.TeamRole = team.LeaderAgentRef, &team.ID, "leader"
		if routeType != controlmodel.RouteExplicit {
			target.RouteType = controlmodel.RouteTeamLeader
		}
		return target, nil
	default:
		return target, fmt.Errorf("unsupported mention target type %q", mention.Type)
	}
}

type ContextInput struct {
	Input   controlmodel.AgentTaskInput `json:"input"`
	Comment *controlmodel.Comment       `json:"comment"`
}

// CoordinatorChildContext gives a Team leader a durable view of every delegated
// branch. Follow-up tasks are routed from one child Issue at a time, so their
// direct Inputs alone are not sufficient to synthesize the coordinator result.
type CoordinatorChildContext struct {
	Issue        *controlmodel.Issue     `json:"issue"`
	Results      []*controlmodel.Comment `json:"results,omitempty"`
	HumanUpdates []*controlmodel.Comment `json:"humanUpdates,omitempty"`
	// Outcomes preserve structured worker results independently of discussion summaries.
	Outcomes []CoordinatorWorkerOutcome `json:"outcomes,omitempty"`
}

// CoordinatorWorkerOutcome exposes delivery evidence without sibling runtime
// bindings, session configuration, or unrelated task inputs.
type CoordinatorWorkerOutcome struct {
	TaskID       uuid.UUID                    `json:"taskId"`
	AgentRef     string                       `json:"agentId"`
	Status       controlmodel.AgentTaskStatus `json:"status"`
	Result       json.RawMessage              `json:"result,omitempty"`
	ErrorCode    string                       `json:"errorCode,omitempty"`
	ErrorMessage string                       `json:"errorMessage,omitempty"`
}

type ContextEnvelope struct {
	ExecutionBrief       *ExecutionBrief                 `json:"executionBrief"`
	Node                 *controlmodel.RunNode           `json:"node,omitempty"`
	ReviewResults        []*controlmodel.Comment         `json:"reviewResults,omitempty"`
	Task                 *controlmodel.AgentTask         `json:"task"`
	Issue                *controlmodel.Issue             `json:"issue"`
	Run                  *controlmodel.OrchestrationRun  `json:"run"`
	Inputs               []ContextInput                  `json:"inputs"`
	ReplyToOwnDelegation bool                            `json:"replyToOwnDelegation,omitempty"`
	InitiatingRequest    string                          `json:"initiatingRequest,omitempty"`
	CurrentRequest       string                          `json:"currentRequest"`
	RequestContext       []ContextInput                  `json:"requestContext,omitempty"`
	CoordinatorIssue     *controlmodel.Issue             `json:"coordinatorIssue,omitempty"`
	CoordinatorChildren  []CoordinatorChildContext       `json:"coordinatorChildren,omitempty"`
	Team                 *controlmodel.CollaborationTeam `json:"team,omitempty"`
	Artifacts            []*controlmodel.Artifact        `json:"artifacts,omitempty"`
	AvailableActions     []string                        `json:"availableActions"`
	TaskToken            string                          `json:"taskToken,omitempty"`
}

func (s *Service) BuildContext(ctx context.Context, taskID uuid.UUID) (*ContextEnvelope, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	issue, err := s.Store.Collaboration().GetIssue(ctx, task.IssueID)
	if err != nil {
		return nil, err
	}
	run, err := s.Store.Orchestration().GetRun(ctx, task.OrchestrationRunID)
	if err != nil {
		return nil, err
	}
	envelope := &ContextEnvelope{Task: task, Issue: issue, Run: run,
		AvailableActions: []string{"math.evaluate", "issue.get", "issue.comment.list", "issue.comment.add", "artifact.upload", "artifact.download",
			"task.get", "task.start", "task.progress", "task.respond", "task.complete", "task.fail", "approval.request",
			"run.get", "run.graph", "run.signal", "run.artifacts"}}
	for _, input := range task.Inputs {
		comment, loadErr := s.Store.Collaboration().GetComment(ctx, input.CommentID)
		if loadErr != nil {
			return nil, loadErr
		}
		envelope.Inputs = append(envelope.Inputs, ContextInput{Input: input, Comment: comment})
		if envelope.CurrentRequest != "" {
			envelope.CurrentRequest += "\n\n"
		}
		envelope.CurrentRequest += comment.Content
	}
	if task.TriggerType == controlmodel.AgentTaskReviewComment {
		envelope.AvailableActions = append(envelope.AvailableActions, "task.begin_work")
		comments, listErr := s.Store.Collaboration().ListComments(ctx, issue.ID, store.CommentListOptions{Limit: 50, Tail: 50})
		if listErr != nil {
			return nil, listErr
		}
		for _, comment := range comments {
			if comment.Type == controlmodel.CommentResult && comment.DeletedAt == nil {
				envelope.ReviewResults = append(envelope.ReviewResults, comment)
			}
		}
	}
	if run.TriggerType == "endpoint" && issue.ID == run.RootIssueID {
		copy := *issue
		copy.Description = EndpointIssueDescription(issue.Description, run.Input)
		issue = &copy
		envelope.Issue = issue
	}
	if envelope.CurrentRequest == "" {
		envelope.CurrentRequest = issue.Title + "\n\n" + issue.Description
	}
	if run.Mode == controlmodel.RunModeDeclared || run.Mode == controlmodel.RunModeSubrun {
		node, nodeErr := s.Store.Orchestration().GetNode(ctx, task.RunNodeID)
		if nodeErr != nil {
			return nil, nodeErr
		}
		envelope.Node = node
		if len(node.Input) > 0 && string(node.Input) != "{}" {
			envelope.CurrentRequest += "\n\nWorkflow node input (use these values for this step):\n" + string(node.Input)
		}
	}
	// Preserve the initiating instructions when a reply returns through A→B→A.
	// These are background requests, not new inputs to execute or acknowledge.
	if task.TeamID == nil {
		parentID := task.ParentTaskID
		seen := map[uuid.UUID]bool{task.ID: true}
		for parentID != nil && !seen[*parentID] && len(seen) <= 32 {
			seen[*parentID] = true
			parent, loadErr := s.Store.Collaboration().GetAgentTask(ctx, *parentID)
			if loadErr != nil {
				return nil, loadErr
			}
			if parent.IssueID != task.IssueID {
				break
			}
			ownRequest := task.Originator.Type == controlmodel.ActorAgent && parent.ID != *task.ParentTaskID &&
				parent.AgentRef == task.AgentRef && !envelope.ReplyToOwnDelegation
			if ownRequest {
				envelope.ReplyToOwnDelegation = true
			}
			for _, input := range parent.Inputs {
				comment, loadErr := s.Store.Collaboration().GetComment(ctx, input.CommentID)
				if loadErr != nil {
					return nil, loadErr
				}
				envelope.RequestContext = append(envelope.RequestContext, ContextInput{Input: input, Comment: comment})
				if ownRequest {
					if envelope.InitiatingRequest != "" {
						envelope.InitiatingRequest += "\n\n"
					}
					envelope.InitiatingRequest += comment.Content
				}
			}
			if ownRequest && envelope.InitiatingRequest == "" {
				envelope.InitiatingRequest = issue.Title + "\n\n" + issue.Description
			}
			parentID = parent.ParentTaskID
		}
	}
	if task.TeamID != nil {
		envelope.Team, err = s.TeamForTask(ctx, task)
		if err != nil {
			return nil, err
		}
		envelope.AvailableActions = append(envelope.AvailableActions, "team.get")
		if task.LeaderTask {
			if err = s.addCoordinatorContext(ctx, envelope); err != nil {
				return nil, err
			}
			envelope.AvailableActions = append(envelope.AvailableActions, "issue.child.create", "issue.accept", "issue.acceptance.update", "issue.cancel", "run.node.complete", "run.node.fail", "run.replan")
		}
	}
	envelope.Artifacts, err = s.Store.Collaboration().ListArtifacts(ctx, task.Tenant, task.Namespace, "issue", task.IssueID.String())
	if err != nil {
		return nil, err
	}
	var reviewInput struct {
		ReviewRequest string `json:"reviewRequest"`
	}
	_ = json.Unmarshal(run.Input, &reviewInput)
	if reviewInput.ReviewRequest != "" && envelope.CoordinatorIssue != nil {
		copy := *envelope.CoordinatorIssue
		copy.Description = "Current follow-up request: " + reviewInput.ReviewRequest + "\n\nOriginal delivered request (context only; do not repeat completed work):\n" + copy.Description
		envelope.CoordinatorIssue = &copy
	}

	envelope.ExecutionBrief, err = s.buildExecutionBrief(ctx, envelope)
	if err != nil {
		return nil, err
	}
	return envelope, nil
}

func (s *Service) addCoordinatorContext(ctx context.Context, envelope *ContextEnvelope) error {
	if envelope == nil || envelope.Task == nil || !envelope.Task.LeaderTask {
		return nil
	}
	node, err := s.Store.Orchestration().GetNode(ctx, envelope.Task.RunNodeID)
	if err != nil {
		return err
	}
	coordinatorIssueID := envelope.Task.IssueID
	if node.IssueID != nil {
		coordinatorIssueID = *node.IssueID
	}
	coordinatorIssue, err := s.Store.Collaboration().GetIssue(ctx, coordinatorIssueID)
	if err != nil {
		return err
	}
	envelope.CoordinatorIssue = coordinatorIssue
	children, err := s.listAllIssues(ctx, store.IssueFilter{
		Tenant: envelope.Task.Tenant, Namespace: envelope.Task.Namespace, ParentID: &coordinatorIssueID,
	})
	if err != nil {
		return err
	}
	for _, child := range children {
		childContext := CoordinatorChildContext{Issue: child}
		// The current child result is needed for its decision. Sibling result
		// bodies become synthesis context only after that sibling has reached a
		// terminal Issue state; exposing active/blocked sibling bodies here caused
		// leaders to act on them through tools scoped to the current child.
		if child.ID == envelope.Task.IssueID || child.Status == controlmodel.IssueDone ||
			child.Status == controlmodel.IssueCancelled {
			tasks, listErr := s.listAllTasks(ctx, store.AgentTaskFilter{IssueID: child.ID,
				Tenant: child.Tenant, Namespace: child.Namespace})
			if listErr != nil {
				return listErr
			}
			for _, worker := range tasks {
				if !worker.LeaderTask && controlmodel.IsAgentTaskTerminal(worker.Status) {
					childContext.Outcomes = append(childContext.Outcomes, CoordinatorWorkerOutcome{
						TaskID: worker.ID, AgentRef: worker.AgentRef, Status: worker.Status,
						Result: worker.Result, ErrorCode: worker.ErrorCode, ErrorMessage: worker.ErrorMessage})
				}
			}
			comments, listErr := s.listAllComments(ctx, child.ID)
			if listErr != nil {
				return listErr
			}
			for _, comment := range comments {
				if comment.DeletedAt != nil {
					continue
				}
				if comment.Type == controlmodel.CommentResult {
					childContext.Results = append(childContext.Results, comment)
				}
				if comment.Author.Type == controlmodel.ActorHuman {
					childContext.HumanUpdates = append(childContext.HumanUpdates, comment)
				}
			}
		}
		envelope.CoordinatorChildren = append(envelope.CoordinatorChildren, childContext)
	}
	return nil
}

func (s *Service) CompleteTask(ctx context.Context, taskID uuid.UUID, completion store.TaskCompletion, actor controlmodel.Actor) (*controlmodel.AgentTask, *controlmodel.Comment, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, nil, err
	}
	if actor.Type == "" {
		actor = controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
	}
	if task.CurrentAttemptID != nil && completion.AttemptID == uuid.Nil {
		attempt, loadErr := s.Store.ExecutionAttempts().Get(ctx, *task.CurrentAttemptID)
		if loadErr != nil {
			return nil, nil, loadErr
		}
		completion.AttemptID = attempt.ID
		completion.DispatchGeneration = attempt.DispatchGeneration
	}
	if budgetErr := s.validateCompletionBudget(ctx, task, completion.Result); budgetErr != nil {
		failed, failErr := s.FailTask(ctx, task.ID, completion.ExpectedVersion, "budget_exceeded", budgetErr.Error())
		if failErr != nil {
			return nil, nil, failErr
		}
		return failed, nil, budgetErr
	}
	if len(completion.ProcessedInputIDs) == 0 && len(completion.DeferredInputIDs) == 0 {
		for _, input := range task.Inputs {
			completion.ProcessedInputIDs = append(completion.ProcessedInputIDs, input.ID)
		}
	}
	if completion.ResponseCommentID == nil && task.TeamID != nil && task.LeaderTask && task.ParentTaskID != nil {
		issue, loadErr := s.Store.Collaboration().GetIssue(ctx, task.IssueID)
		if loadErr != nil {
			return nil, nil, loadErr
		}
		if issue.Status == controlmodel.IssueBlocked {
			comments, listErr := s.listAllComments(ctx, task.IssueID)
			if listErr != nil {
				return nil, nil, listErr
			}
			var notification *controlmodel.Comment
			for _, comment := range comments {
				if comment.SourceTaskID == nil || *comment.SourceTaskID != task.ID {
					continue
				}
				for _, route := range comment.Routes {
					if route.TargetType == controlmodel.AssigneeHuman && route.Outcome == controlmodel.RouteQueued &&
						(notification == nil || comment.CreatedAt.After(notification.CreatedAt)) {
						notification = comment
					}
				}
			}
			if notification != nil {
				completed, completeErr := s.Store.Collaboration().CompleteAgentTask(ctx, taskID, completion)
				if completeErr != nil {
					return nil, notification, completeErr
				}
				if convergeErr := s.convergeQuiescentBlockedTeamRoot(ctx, completed); convergeErr != nil {
					return completed, notification, convergeErr
				}
				return completed, notification, nil
			}
		}
	}
	var output *controlmodel.Comment
	if completion.ResponseCommentID == nil {
		comments, listErr := s.listAllComments(ctx, task.IssueID)
		if listErr != nil {
			return nil, nil, listErr
		}
		for _, comment := range comments {
			if comment.DeletedAt == nil && comment.Type == controlmodel.CommentResult &&
				comment.SourceTaskID != nil && *comment.SourceTaskID == task.ID &&
				(output == nil || comment.CreatedAt.After(output.CreatedAt)) {
				output = comment
			}
		}
		if output != nil {
			id := output.ID
			completion.ResponseCommentID = &id
		}
	}
	if len(completion.Result) == 0 || string(completion.Result) == "null" {
		text := strings.TrimSpace(completion.Summary)
		if output != nil {
			text = output.Content
		}
		if text != "" {
			completion.Result, _ = json.Marshal(text)
		}
	}
	if completion.ResponseCommentID == nil {
		content := strings.TrimSpace(completion.Summary)
		result := strings.TrimSpace(string(completion.Result))
		if result != "" && result != "null" {
			var plain string
			if json.Unmarshal(completion.Result, &plain) == nil {
				result = strings.TrimSpace(plain)
			}
			if content == "" {
				content = result
			} else if result != "" && !strings.Contains(content, result) {
				content += "\n\n" + result
			}
		}
		if content == "" {
			content = "Task completed."
		}
		var parentID *uuid.UUID
		if task.TriggerCommentID != nil {
			parentID = task.TriggerCommentID
		}
		targets, targetErr := s.completionTargets(ctx, task, parentID)
		if targetErr != nil {
			return nil, nil, targetErr
		}
		commentType := controlmodel.CommentResult
		if task.TriggerType == controlmodel.AgentTaskReviewComment {
			commentType = controlmodel.CommentGeneral
		}
		completed, resultComment, completeErr := s.Store.Collaboration().CompleteAgentTaskWithComment(ctx, taskID, completion,
			&controlmodel.Comment{IssueID: task.IssueID, ParentID: parentID, Author: actor,
				Content: content, Type: commentType, SourceTaskID: &task.ID}, targets)
		if completeErr != nil {
			return nil, nil, completeErr
		}
		if convergeErr := s.convergeQuiescentBlockedTeamRoot(ctx, completed); convergeErr != nil {
			return completed, resultComment, convergeErr
		}
		return completed, resultComment, nil
	}
	completed, err := s.Store.Collaboration().CompleteAgentTask(ctx, taskID, completion)
	if err != nil {
		return nil, output, err
	}
	if convergeErr := s.convergeQuiescentBlockedTeamRoot(ctx, completed); convergeErr != nil {
		return completed, output, convergeErr
	}
	return completed, output, nil
}

// convergeQuiescentBlockedTeamRoot propagates an exhausted child dependency to
// the root only after the leader has finished its decision turn and no other
// task can still make progress. The adaptive Run remains waiting and the root
// can be reopened when new human input or capabilities arrive.
func (s *Service) convergeQuiescentBlockedTeamRoot(ctx context.Context, completed *controlmodel.AgentTask) error {
	if completed == nil || completed.TeamID == nil || !completed.LeaderTask || completed.ParentTaskID == nil {
		return nil
	}
	run, err := s.Store.Orchestration().GetRun(ctx, completed.OrchestrationRunID)
	if err != nil || controlmodel.IsOrchestrationRunTerminal(run.State) {
		return err
	}
	tasks, err := s.listAllTasks(ctx, store.AgentTaskFilter{
		Tenant: completed.Tenant, Namespace: completed.Namespace, RunID: completed.OrchestrationRunID,
	})
	if err != nil {
		return err
	}
	for _, task := range tasks {
		if !controlmodel.IsAgentTaskTerminal(task.Status) {
			return nil
		}
	}
	children, err := s.listAllIssues(ctx, store.IssueFilter{
		Tenant: completed.Tenant, Namespace: completed.Namespace, ParentID: &run.RootIssueID,
	})
	if err != nil {
		return err
	}
	hasBlocked := false
	for _, child := range children {
		switch child.Status {
		case controlmodel.IssueDone, controlmodel.IssueCancelled:
		case controlmodel.IssueBlocked:
			hasBlocked = true
		default:
			// No task remains to advance this unreviewed child. Make the missing
			// coordinator decision visible instead of leaving work in_progress.
			hasBlocked = true
		}
	}
	if !hasBlocked {
		return nil
	}
	reason := "Team is waiting on blocked or unreviewed delegated work"
	for attempt := 0; attempt < 3; attempt++ {
		root, loadErr := s.Store.Collaboration().GetIssue(ctx, run.RootIssueID)
		if loadErr != nil {
			return loadErr
		}
		if root.Status == controlmodel.IssueBlocked {
			return nil
		}
		if root.Status != controlmodel.IssueInProgress && root.Status != controlmodel.IssueInReview && root.Status != controlmodel.IssueTodo {
			return nil
		}
		_, transitionErr := s.Store.Collaboration().TransitionIssue(ctx, root.ID, root.Version,
			controlmodel.IssueBlocked, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: completed.AgentRef},
			reason)
		if transitionErr == nil {
			return nil
		}
		if transitionErr != store.ErrConflict {
			return transitionErr
		}
	}
	return store.ErrConflict
}

// FailTask is the only logical failure entry point. When a physical Attempt
// exists it fences and commits both records atomically; queued tasks without
// an Attempt fail only at the logical layer.
func (s *Service) FailTask(ctx context.Context, taskID uuid.UUID, expectedVersion int64, code, message string, result ...json.RawMessage) (*controlmodel.AgentTask, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	if task.CurrentAttemptID == nil {
		return s.Store.Collaboration().FailAgentTask(ctx, taskID, expectedVersion, code, message, result...)
	}
	attempt, err := s.Store.ExecutionAttempts().Get(ctx, *task.CurrentAttemptID)
	if err != nil {
		return nil, err
	}
	var partial json.RawMessage
	if len(result) > 0 {
		partial = result[0]
	}
	failed, _, err := s.Store.Collaboration().FailAgentTaskWithAttempt(ctx, taskID, store.TaskFailure{
		ExpectedVersion: expectedVersion, AttemptID: attempt.ID, DispatchGeneration: attempt.DispatchGeneration,
		Code: code, Message: message, Result: partial})
	return failed, err
}

// ConvergeFailedTask projects every failed conversational task into a visible,
// idempotent Issue outcome and delegates Team worker handling to its richer
// coordinator-aware path. Declared workflows retain their own failure policy.
func (s *Service) ConvergeFailedTask(ctx context.Context, taskID uuid.UUID) (*controlmodel.Issue, *controlmodel.Comment, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, nil, err
	}
	if task.Status != controlmodel.AgentTaskFailed || task.TriggerType == controlmodel.AgentTaskReviewComment {
		return nil, nil, nil
	}
	if task.TeamID != nil && !task.LeaderTask {
		return s.ConvergeFailedWorker(ctx, taskID)
	}
	run, err := s.Store.Orchestration().GetRun(ctx, task.OrchestrationRunID)
	if err != nil {
		return nil, nil, err
	}
	// Declared workflows own their root Issue lifecycle through their failure
	// policy. This projection is for direct/adaptive conversational work.
	if run.DefinitionRevisionID != nil {
		return nil, nil, nil
	}
	if task.LeaderTask && task.IssueID == run.RootIssueID {
		if err = s.PublishCoordinatorSummary(ctx, run, task, nil, task.ErrorCode, task.ErrorMessage); err != nil {
			return nil, nil, err
		}
	}
	issue, err := s.Store.Collaboration().GetIssue(ctx, task.IssueID)
	if err != nil {
		return nil, nil, err
	}
	if issue.Status == controlmodel.IssueDone || issue.Status == controlmodel.IssueCancelled {
		return issue, nil, nil
	}
	actor := controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
	if issue.Status != controlmodel.IssueBlocked {
		issue, err = s.Store.Collaboration().TransitionIssue(ctx, issue.ID, issue.Version,
			controlmodel.IssueBlocked, actor, "AgentTask failed: "+task.ErrorCode)
		if err != nil {
			return nil, nil, err
		}
	}
	comments, err := s.listAllComments(ctx, issue.ID)
	if err != nil {
		return nil, nil, err
	}
	for _, comment := range comments {
		if comment.Type == controlmodel.CommentStatus && comment.SourceTaskID != nil && *comment.SourceTaskID == task.ID {
			return issue, comment, nil
		}
	}
	code := strings.TrimSpace(task.ErrorCode)
	if code == "" {
		code = "agent_task_failed"
	}
	message := strings.TrimSpace(task.ErrorMessage)
	if message == "" {
		message = "The Agent could not complete the task."
	}
	result, err := s.Store.Collaboration().CreateComment(ctx, store.CreateCommentRequest{
		Comment: &controlmodel.Comment{IssueID: issue.ID, Author: actor,
			Content: "Agent could not complete this Issue.\nFailure code: " + code +
				"\nFailure: " + message + "\nIssue status: blocked.",
			Type: controlmodel.CommentStatus, SourceTaskID: &task.ID, SourceAttemptID: task.CurrentAttemptID},
	})
	if err != nil {
		return nil, nil, err
	}
	return issue, result.Comment, nil
}

// ConvergeFailedWorker turns an exhausted Team worker failure into a durable
// collaboration outcome. The physical Attempt and logical AgentTask remain
// failed for observability, while the child Issue becomes blocked and the
// coordinator leader receives a normal follow-up input on which it can reason.
// Reconciliation may call this repeatedly; the source Task identifies the
// unique status Comment.
func (s *Service) ConvergeFailedWorker(ctx context.Context, taskID uuid.UUID) (*controlmodel.Issue, *controlmodel.Comment, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, nil, err
	}
	if task.Status != controlmodel.AgentTaskFailed || task.TeamID == nil || task.LeaderTask {
		return nil, nil, nil
	}
	issue, err := s.Store.Collaboration().GetIssue(ctx, task.IssueID)
	if err != nil {
		return nil, nil, err
	}
	if issue.ParentIssueID == nil || issue.Status == controlmodel.IssueDone || issue.Status == controlmodel.IssueCancelled {
		return issue, nil, nil
	}

	actor := controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
	if issue.Status == controlmodel.IssueBacklog {
		issue, err = s.Store.Collaboration().TransitionIssue(ctx, issue.ID, issue.Version,
			controlmodel.IssueTodo, actor, "worker could not start delegated work")
		if err != nil {
			return nil, nil, err
		}
	}
	if issue.Status != controlmodel.IssueBlocked {
		issue, err = s.Store.Collaboration().TransitionIssue(ctx, issue.ID, issue.Version,
			controlmodel.IssueBlocked, actor, "worker reported an unresolved failure: "+task.ErrorCode)
		if err != nil {
			return nil, nil, err
		}
	}

	comments, err := s.listAllComments(ctx, issue.ID)
	if err != nil {
		return nil, nil, err
	}
	for _, comment := range comments {
		if comment.Type == controlmodel.CommentStatus && comment.SourceTaskID != nil && *comment.SourceTaskID == task.ID {
			return issue, comment, nil
		}
	}

	code := strings.TrimSpace(task.ErrorCode)
	if code == "" {
		code = "agent_reported_failure"
	}
	message := strings.TrimSpace(task.ErrorMessage)
	if message == "" {
		message = "The delegated worker could not complete the task."
	}
	nodeTasks, err := s.listAllTasks(ctx, store.AgentTaskFilter{
		Tenant: task.Tenant, Namespace: task.Namespace, NodeID: task.RunNodeID,
	})
	if err != nil {
		return nil, nil, err
	}
	attemptCount := 0
	for _, nodeTask := range nodeTasks {
		attempts, listErr := s.Store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{
			Tenant: task.Tenant, Namespace: task.Namespace, AgentTaskID: nodeTask.ID, Limit: collaborationPageSize,
		})
		if listErr != nil {
			return nil, nil, listErr
		}
		attemptCount += len(attempts)
	}
	if attemptCount == 0 {
		attemptCount = len(nodeTasks)
	}
	content := "Delegated worker could not complete this Issue.\n" +
		"Failure code: " + code + "\n" +
		"Failure: " + message + "\n" +
		fmt.Sprintf("Attempts consumed: %d\n", attemptCount) +
		"Issue status: blocked. The Team leader must decide whether to retry or reassign, continue with a degraded result, request human action, or fail the coordinator."
	if err = ValidateContentPolicy(s.policyForIssueOrTask(ctx, issue, &task.ID), content); err != nil {
		return nil, nil, err
	}
	targets, err := s.completionTargets(ctx, task, task.TriggerCommentID)
	if err != nil {
		return nil, nil, err
	}
	result, err := s.Store.Collaboration().CreateComment(ctx, store.CreateCommentRequest{
		Comment: &controlmodel.Comment{IssueID: issue.ID, Author: actor, Content: content,
			Type: controlmodel.CommentStatus, SourceTaskID: &task.ID, SourceAttemptID: task.CurrentAttemptID},
		Targets: targets,
	})
	if err != nil {
		return nil, nil, err
	}
	return issue, result.Comment, nil
}

// CancelTask requests cancellation of every active Attempt before closing the
// logical Task. Backend acknowledgement later makes each Attempt cancelled.
func (s *Service) CancelTask(ctx context.Context, taskID uuid.UUID, expectedVersion int64) (*controlmodel.AgentTask, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	if controlmodel.IsAgentTaskTerminal(task.Status) {
		return task, nil
	}
	attempts, err := s.Store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{AgentTaskID: taskID, Limit: 100})
	if err != nil {
		return nil, err
	}
	for _, attempt := range attempts {
		if controlmodel.IsExecutionAttemptTerminal(attempt.State) {
			continue
		}
		if _, cancelErr := s.Store.ExecutionAttempts().Cancel(ctx, attempt.ID, attempt.Version); cancelErr != nil && cancelErr != store.ErrConflict {
			return nil, cancelErr
		}
	}
	return s.Store.Collaboration().CancelAgentTask(ctx, taskID, expectedVersion)
}

type taskUsage struct {
	TotalTokens int64
	CostMicros  int64
}

func resultUsage(raw json.RawMessage) taskUsage {
	var document map[string]any
	if len(raw) == 0 || json.Unmarshal(raw, &document) != nil {
		return taskUsage{}
	}
	usage, _ := document["usage"].(map[string]any)
	if usage == nil {
		return taskUsage{}
	}
	number := func(keys ...string) int64 {
		for _, key := range keys {
			if value, ok := usage[key].(float64); ok && value > 0 {
				return int64(value)
			}
		}
		return 0
	}
	total := number("totalTokens", "total_tokens")
	if total == 0 {
		total = number("inputTokens", "input_tokens", "promptTokens", "prompt_tokens") +
			number("outputTokens", "output_tokens", "completionTokens", "completion_tokens")
	}
	return taskUsage{TotalTokens: total, CostMicros: number("costMicros", "cost_micros")}
}

func (s *Service) validateCompletionBudget(ctx context.Context, task *controlmodel.AgentTask, result json.RawMessage) error {
	policy := s.policyForIssueOrTask(ctx, nil, &task.ID)
	if policy.MaxIssueTokens <= 0 && policy.MaxIssueCostMicros <= 0 {
		return nil
	}
	usage := resultUsage(result)
	tasks, err := s.listAllTasks(ctx, store.AgentTaskFilter{
		Tenant: task.Tenant, Namespace: task.Namespace, IssueID: task.IssueID,
	})
	if err != nil {
		return err
	}
	for _, previous := range tasks {
		if previous.ID == task.ID || previous.Status != controlmodel.AgentTaskCompleted {
			continue
		}
		prior := resultUsage(previous.Result)
		usage.TotalTokens += prior.TotalTokens
		usage.CostMicros += prior.CostMicros
	}
	if policy.MaxIssueTokens > 0 && usage.TotalTokens > policy.MaxIssueTokens {
		return fmt.Errorf("Issue token budget exceeded: used %d, limit %d", usage.TotalTokens, policy.MaxIssueTokens)
	}
	if policy.MaxIssueCostMicros > 0 && usage.CostMicros > policy.MaxIssueCostMicros {
		return fmt.Errorf("Issue cost budget exceeded: used %d micros, limit %d", usage.CostMicros, policy.MaxIssueCostMicros)
	}
	return nil
}

func (s *Service) completionTargets(ctx context.Context, task *controlmodel.AgentTask, parentID *uuid.UUID) ([]store.CommentTarget, error) {
	if task.TriggerType == controlmodel.AgentTaskReviewComment {
		return []store.CommentTarget{{TargetType: controlmodel.AssigneeHuman, TargetRef: task.Originator.Ref, RouteType: controlmodel.RouteThreadParent}}, nil
	}
	issue, err := s.Store.Collaboration().GetIssue(ctx, task.IssueID)
	if err != nil {
		return nil, err
	}
	recovered, recoverErr := s.resumedChildCoordinatorTarget(ctx, task, issue)
	if recoverErr != nil {
		return nil, recoverErr
	}
	if recovered != nil {
		return []store.CommentTarget{*recovered}, nil
	}
	if task.TeamID == nil && task.TriggerCommentID != nil {
		trigger, loadErr := s.Store.Collaboration().GetComment(ctx, *task.TriggerCommentID)
		if loadErr != nil {
			return nil, loadErr
		}
		for _, route := range trigger.Routes {
			if route.TaskID == nil || *route.TaskID != task.ID {
				continue
			}
			// A human explicitly mentioned this Agent to obtain a direct answer.
			// Its result is already visible in that thread and must not implicitly
			// wake the Issue assignee. If another Agent needs to act, the responder
			// must address it with an explicit mention.
			if trigger.Author.Type == controlmodel.ActorHuman && route.RouteType == controlmodel.RouteExplicit {
				return []store.CommentTarget{{TargetType: controlmodel.AssigneeHuman,
					TargetRef: trigger.Author.Ref, RouteType: controlmodel.RouteThreadParent}}, nil
			}
			// An assignee consuming another Agent's result is the terminal side of
			// that hand-off. Returning its completion to ParentTaskID would create
			// an automatic responder -> assignee -> responder ping-pong.
			if issue.AssigneeType == controlmodel.AssigneeAgent && issue.AssigneeRef == task.AgentRef {
				if route.RouteType == controlmodel.RouteAssignee {
					return nil, nil
				}
				if (route.RouteType == controlmodel.RouteExplicit || route.RouteType == controlmodel.RouteFollowUp) && task.ParentTaskID != nil {
					// Only stop a reply returning to its originator. An independent
					// Agent request to the assignee must still receive an answer.
					parent, err := s.Store.Collaboration().GetAgentTask(ctx, *task.ParentTaskID)
					if err != nil {
						return nil, err
					}
					if parent.ParentTaskID != nil {
						origin, err := s.Store.Collaboration().GetAgentTask(ctx, *parent.ParentTaskID)
						if err != nil {
							return nil, err
						}
						if origin.AgentRef == task.AgentRef {
							return nil, nil
						}
					}
				}
			}
		}
	}
	if task.ParentTaskID != nil {
		parent, err := s.Store.Collaboration().GetAgentTask(ctx, *task.ParentTaskID)
		if err == nil {
			if task.TeamID == nil {
				// An explicit reply already notified the requester. Publishing the
				// completion must not schedule the same recipient a second time.
				comments, listErr := s.listAllComments(ctx, task.IssueID)
				if listErr != nil {
					return nil, listErr
				}
				for _, comment := range comments {
					if comment.DeletedAt != nil || comment.SourceTaskID == nil || *comment.SourceTaskID != task.ID {
						continue
					}
					for _, route := range comment.Routes {
						if route.RouteType == controlmodel.RouteExplicit && route.TargetRef == parent.AgentRef && route.Outcome == controlmodel.RouteQueued {
							return nil, nil
						}
					}
				}
			}
			routeType := controlmodel.RouteFollowUp
			if parent.LeaderTask {
				routeType = controlmodel.RouteTeamLeader
			}
			return []store.CommentTarget{{TargetType: controlmodel.AssigneeAgent, TargetRef: parent.AgentRef,
				AgentRef: parent.AgentRef, TeamID: parent.TeamID, TeamRole: parent.TeamRole,
				ParentTaskID: task.ParentTaskID,
				RouteType:    routeType}}, nil
		}
	}
	if task.TeamID != nil && !task.LeaderTask {
		team, err := s.TeamForTask(ctx, task)
		if err != nil {
			return nil, err
		}
		return []store.CommentTarget{{TargetType: controlmodel.AssigneeTeam, TargetRef: team.ID.String(),
			AgentRef: team.LeaderAgentRef, TeamID: &team.ID, TeamRole: "leader",
			RouteType: controlmodel.RouteTeamLeader}}, nil
	}
	if issue.ParentIssueID != nil {
		parents, listErr := s.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: *issue.ParentIssueID, Limit: 100})
		if listErr != nil {
			return nil, listErr
		}
		for i := len(parents) - 1; i >= 0; i-- {
			candidate := parents[i]
			if candidate.LeaderTask || !controlmodel.IsAgentTaskTerminal(candidate.Status) {
				return []store.CommentTarget{{TargetType: controlmodel.AssigneeAgent, TargetRef: candidate.AgentRef,
					AgentRef: candidate.AgentRef, TeamID: candidate.TeamID, TeamRole: candidate.TeamRole,
					RouteType: controlmodel.RouteFollowUp}}, nil
			}
		}
	}
	return s.PreviewCommentRoutes(ctx, task.IssueID, parentID,
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}, nil)
}

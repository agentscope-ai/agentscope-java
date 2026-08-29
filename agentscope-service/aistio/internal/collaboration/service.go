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
			label := strings.TrimSpace(item.ID)
			if label == "" {
				label = strings.TrimSpace(item.Text)
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
	// Agent/Team Issues already require a result. This flag lets a human-owned
	// Issue opt into the same objective completion condition.
	if criteria.RequiredResult && issue.AssigneeType == controlmodel.AssigneeHuman {
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
	Tenant             string
	Namespace          string
	Title              string
	Description        string
	Priority           string
	Creator            controlmodel.Actor
	AssigneeType       controlmodel.AssigneeType
	AssigneeRef        string
	ParentIssueID      *uuid.UUID
	AcceptanceCriteria json.RawMessage
	ContextRefs        json.RawMessage
	SourceType         string
	SourceRef          string
	DueAt              *time.Time
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
		Tenant: req.Tenant, Namespace: req.Namespace, Title: strings.TrimSpace(req.Title),
		Description: req.Description, Status: controlmodel.IssueBacklog,
		Priority: req.Priority, Creator: req.Creator, ParentIssueID: req.ParentIssueID,
		AssigneeType: req.AssigneeType, AssigneeRef: req.AssigneeRef,
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
	if !task.LeaderTask || task.TeamID == nil || controlmodel.IsAgentTaskTerminal(task.Status) {
		return nil, nil, fmt.Errorf("only an active Team leader AgentTask may create child Issues")
	}
	parent, err := s.Store.Collaboration().GetIssue(ctx, task.IssueID)
	if err != nil {
		return nil, nil, err
	}
	team, err := s.Store.Collaboration().GetTeam(ctx, *task.TeamID)
	if err != nil {
		return nil, nil, err
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
	req.Creator = controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
	req.SourceType, req.SourceRef = "agent-task", task.ID.String()
	return s.CreateIssue(ctx, req)
}

type MentionTarget struct {
	Type controlmodel.AssigneeType `json:"type"`
	Ref  string                    `json:"ref"`
}

type AddCommentRequest struct {
	IssueID         uuid.UUID
	ParentID        *uuid.UUID
	Author          controlmodel.Actor
	Content         string
	Type            controlmodel.CommentType
	Mentions        []MentionTarget
	SourceTaskID    *uuid.UUID
	SourceAttemptID *uuid.UUID
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
	if len(targets) == 0 {
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
					}
				}
				targets = append(targets, s.guardTarget(ctx, issue, req.SourceTaskID, target))
			}
		}
		if len(targets) == 0 && issue.AssigneeType != "" && issue.AssigneeRef != "" {
			resolved, resolveErr := s.resolveTarget(ctx, issue, MentionTarget{Type: issue.AssigneeType, Ref: issue.AssigneeRef}, controlmodel.RouteAssignee)
			if resolveErr == nil {
				targets = append(targets, s.guardTarget(ctx, issue, req.SourceTaskID, resolved))
			}
		}
	}
	commentType := req.Type
	if commentType == "" {
		commentType = controlmodel.CommentGeneral
	}
	result, err := s.Store.Collaboration().CreateComment(ctx, store.CreateCommentRequest{
		Comment: &controlmodel.Comment{IssueID: issue.ID, ParentID: req.ParentID,
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

func (s *Service) RetryTask(ctx context.Context, taskID uuid.UUID, actor controlmodel.Actor) (*controlmodel.AgentTask, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	if task.TeamID != nil {
		team, loadErr := s.Store.Collaboration().GetTeam(ctx, *task.TeamID)
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
		if team, err := s.Store.Collaboration().GetTeam(ctx, *target.TeamID); err == nil {
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
			if source.AgentRef == target.AgentRef && sameCollaborationRole(source, target) {
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

func sameCollaborationRole(task *controlmodel.AgentTask, target store.CommentTarget) bool {
	if task.TeamID == nil && target.TeamID == nil {
		return task.TeamRole == target.TeamRole
	}
	return task.TeamID != nil && target.TeamID != nil && *task.TeamID == *target.TeamID && task.TeamRole == target.TeamRole
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
		if err != nil || team.Tenant != issue.Tenant || team.Namespace != issue.Namespace || team.ArchivedAt != nil {
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

type ContextEnvelope struct {
	Task             *controlmodel.AgentTask         `json:"task"`
	Issue            *controlmodel.Issue             `json:"issue"`
	Inputs           []ContextInput                  `json:"inputs"`
	Team             *controlmodel.CollaborationTeam `json:"team,omitempty"`
	Artifacts        []*controlmodel.Artifact        `json:"artifacts,omitempty"`
	AvailableActions []string                        `json:"availableActions"`
	TaskToken        string                          `json:"taskToken,omitempty"`
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
	envelope := &ContextEnvelope{Task: task, Issue: issue,
		AvailableActions: []string{"issue.get", "issue.comment.list", "issue.comment.add", "artifact.upload", "artifact.download", "task.respond", "task.complete", "task.fail"}}
	for _, input := range task.Inputs {
		comment, loadErr := s.Store.Collaboration().GetComment(ctx, input.CommentID)
		if loadErr != nil {
			return nil, loadErr
		}
		envelope.Inputs = append(envelope.Inputs, ContextInput{Input: input, Comment: comment})
	}
	if task.TeamID != nil {
		envelope.Team, err = s.Store.Collaboration().GetTeam(ctx, *task.TeamID)
		if err != nil {
			return nil, err
		}
		if task.LeaderTask {
			envelope.AvailableActions = append(envelope.AvailableActions, "issue.child.create", "issue.assign", "team.get")
		}
	}
	envelope.Artifacts, err = s.Store.Collaboration().ListArtifacts(ctx, task.Tenant, task.Namespace, "issue", task.IssueID.String())
	if err != nil {
		return nil, err
	}
	return envelope, nil
}

func (s *Service) CompleteTask(ctx context.Context, taskID uuid.UUID, completion store.TaskCompletion, actor controlmodel.Actor) (*controlmodel.AgentTask, *controlmodel.Comment, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, nil, err
	}
	if actor.Type == "" {
		actor = controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
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
	var output *controlmodel.Comment
	if completion.ResponseCommentID == nil {
		content := strings.TrimSpace(completion.Summary)
		if content == "" && len(completion.Result) > 0 {
			content = string(completion.Result)
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
		if task.TeamID != nil && task.LeaderTask && task.AccountableHumanRef != "" {
			if team, teamErr := s.Store.Collaboration().GetTeam(ctx, *task.TeamID); teamErr == nil && team.Policy.RequireReview {
				targets = append(targets, store.CommentTarget{TargetType: controlmodel.AssigneeHuman,
					TargetRef: task.AccountableHumanRef, RouteType: controlmodel.RouteReviewRequest})
			}
		}
		completed, resultComment, completeErr := s.Store.Collaboration().CompleteAgentTaskWithComment(ctx, taskID, completion,
			&controlmodel.Comment{IssueID: task.IssueID, ParentID: parentID, Author: actor,
				Content: content, Type: controlmodel.CommentResult, SourceTaskID: &task.ID}, targets)
		if completeErr != nil {
			return nil, nil, completeErr
		}
		return completed, resultComment, nil
	}
	completed, err := s.Store.Collaboration().CompleteAgentTask(ctx, taskID, completion)
	if err != nil {
		return nil, output, err
	}
	return completed, output, nil
}

// FailTask is the only logical failure entry point. When a physical Attempt
// exists it fences and commits both records atomically; queued tasks without
// an Attempt fail only at the logical layer.
func (s *Service) FailTask(ctx context.Context, taskID uuid.UUID, expectedVersion int64, code, message string) (*controlmodel.AgentTask, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	if task.CurrentAttemptID == nil {
		return s.Store.Collaboration().FailAgentTask(ctx, taskID, expectedVersion, code, message)
	}
	attempt, err := s.Store.ExecutionAttempts().Get(ctx, *task.CurrentAttemptID)
	if err != nil {
		return nil, err
	}
	failed, _, err := s.Store.Collaboration().FailAgentTaskWithAttempt(ctx, taskID, store.TaskFailure{
		ExpectedVersion: expectedVersion, AttemptID: attempt.ID, DispatchGeneration: attempt.DispatchGeneration,
		Code: code, Message: message})
	return failed, err
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
	if task.ParentTaskID != nil {
		parent, err := s.Store.Collaboration().GetAgentTask(ctx, *task.ParentTaskID)
		if err == nil {
			return []store.CommentTarget{{TargetType: controlmodel.AssigneeAgent, TargetRef: parent.AgentRef,
				AgentRef: parent.AgentRef, TeamID: parent.TeamID, TeamRole: parent.TeamRole,
				ParentTaskID: task.ParentTaskID,
				RouteType:    controlmodel.RouteFollowUp}}, nil
		}
	}
	if task.TeamID != nil && !task.LeaderTask {
		team, err := s.Store.Collaboration().GetTeam(ctx, *task.TeamID)
		if err != nil {
			return nil, err
		}
		return []store.CommentTarget{{TargetType: controlmodel.AssigneeTeam, TargetRef: team.ID.String(),
			AgentRef: team.LeaderAgentRef, TeamID: &team.ID, TeamRole: "leader",
			RouteType: controlmodel.RouteTeamLeader}}, nil
	}
	issue, err := s.Store.Collaboration().GetIssue(ctx, task.IssueID)
	if err != nil {
		return nil, err
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

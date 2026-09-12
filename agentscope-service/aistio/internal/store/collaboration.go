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

package store

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

type IssueFilter struct {
	Tenant       string
	Namespace    string
	Status       controlmodel.IssueStatus
	AssigneeType controlmodel.AssigneeType
	AssigneeRef  string
	Kind         controlmodel.IssueKind
	Visibility   controlmodel.IssueVisibility
	ParentID     *uuid.UUID
	Limit        int
	Offset       int
	CursorTime   *time.Time
	CursorID     uuid.UUID
	Search       string
	Archived     bool
}

type CommentListOptions struct {
	RootsOnly  bool
	ThreadID   *uuid.UUID
	Limit      int
	Offset     int
	Tail       int
	CursorTime *time.Time
	CursorID   uuid.UUID
}

type CommentTarget struct {
	TargetType   controlmodel.AssigneeType
	TargetRef    string
	AgentRef     string
	TeamID       *uuid.UUID
	TeamRole     string
	ParentTaskID *uuid.UUID
	RouteType    controlmodel.CommentRouteType
	Blocked      bool
	ReasonCode   string
}

type CreateCommentRequest struct {
	Comment  *controlmodel.Comment
	Mentions []controlmodel.Mention
	Targets  []CommentTarget
	Outbox   []*controlmodel.OutboxEvent
	Activity *controlmodel.Activity
}

// AgentTaskOwnsIssueLifecycle reports whether a Task is allowed to advance the
// Issue's workflow status. Explicitly mentioned consultants can contribute to
// the discussion, but only the assigned Agent or Team leader owns the Issue.
func AgentTaskOwnsIssueLifecycle(issue *controlmodel.Issue, task *controlmodel.AgentTask) bool {
	if issue == nil || task == nil {
		return false
	}
	switch issue.AssigneeType {
	case "":
		return true
	case controlmodel.AssigneeAgent:
		return issue.AssigneeRef == task.AgentRef
	case controlmodel.AssigneeTeam:
		return task.LeaderTask && task.TeamID != nil && issue.AssigneeRef == task.TeamID.String()
	default:
		return false
	}
}

// AgentTaskMayAdvanceIssueLifecycle limits ownership checks to direct comment
// consultations. Workflow/endpoint tasks and Team tasks keep their existing
// lifecycle semantics, while an explicitly mentioned standalone consultant
// cannot move an Issue assigned to somebody else.
func AgentTaskMayAdvanceIssueLifecycle(issue *controlmodel.Issue, task *controlmodel.AgentTask) bool {
	if task == nil || task.TriggerType == controlmodel.AgentTaskReviewComment {
		return false
	}
	return task.TriggerType != "comment" || task.TeamID != nil || AgentTaskOwnsIssueLifecycle(issue, task)
}

// A new human request to the accountable assignee resumes work under review.
// A consultant's reply or a leader reviewing a child's result does not reopen it.
func AgentTaskReopensReview(issue *controlmodel.Issue, task *controlmodel.AgentTask) bool {
	return issue != nil && task != nil && issue.Status == controlmodel.IssueInReview &&
		task.TriggerType == "comment" && task.Originator.Type == controlmodel.ActorHuman && AgentTaskOwnsIssueLifecycle(issue, task)
}

type CreateCommentResult struct {
	Comment *controlmodel.Comment
	Routes  []controlmodel.CommentRoute
	Tasks   []controlmodel.AgentTask
}

type AgentTaskFilter struct {
	Tenant    string
	Namespace string
	IssueID   uuid.UUID
	RunID     uuid.UUID
	NodeID    uuid.UUID
	AgentRef  string
	TeamID    uuid.UUID
	Status    controlmodel.AgentTaskStatus
	Limit     int
	Offset    int
}

type TaskClaim struct {
	TaskID          uuid.UUID
	ExpectedVersion int64
	RuntimeBinding  json.RawMessage
	SessionID       string
}

type RunTaskRequest struct {
	RunID, NodeID, IssueID uuid.UUID
	AgentRef               string
	RuntimeCandidate       *controlmodel.RuntimeBindingCandidate
	TeamID                 *uuid.UUID
	TeamRole               string
	Leader                 bool
	Priority               int32
	Originator             controlmodel.Actor
}

type TaskCompletion struct {
	ExpectedVersion    int64           `json:"expectedVersion"`
	AttemptID          uuid.UUID       `json:"attemptId,omitempty"`
	DispatchGeneration int64           `json:"dispatchGeneration,omitempty"`
	LeaseToken         string          `json:"-"`
	FencingToken       int64           `json:"-"`
	Checkpoint         json.RawMessage `json:"checkpoint,omitempty"`
	Result             json.RawMessage `json:"result,omitempty"`
	Usage              json.RawMessage `json:"usage,omitempty"`
	Summary            string          `json:"summary,omitempty"`
	ProcessedInputIDs  []uuid.UUID     `json:"processedInputIds,omitempty"`
	DeferredInputIDs   []uuid.UUID     `json:"deferredInputIds,omitempty"`
	ResponseCommentID  *uuid.UUID      `json:"responseCommentId,omitempty"`
}

type TaskFailure struct {
	ExpectedVersion    int64
	AttemptID          uuid.UUID
	DispatchGeneration int64
	LeaseToken         string
	FencingToken       int64
	Code               string
	Message            string
	Result             json.RawMessage
	Checkpoint         json.RawMessage
	Usage              json.RawMessage
}

type InboxFilter struct {
	Tenant       string
	Namespace    string
	RecipientRef string
	Archived     bool
	Type         string
	View         string
	Cursor       string
	Limit        int
	Offset       int
}

type ApprovalFilter struct {
	Tenant      string
	Namespace   string
	ApproverRef string
	TargetType  string
	TargetRef   string
	Status      controlmodel.ApprovalStatus
	Limit       int
	Offset      int
}

// ManagedToolApprovalFence freezes the physical managed turn that requested a
// tool confirmation. Every transition must match all fields so a delayed event
// or approval decision can never resume a newer retry of the logical Task.
type ManagedToolApprovalFence struct {
	BackendKind        controlmodel.DataPlaneKind
	SessionID          string
	TaskID             uuid.UUID
	AttemptID          uuid.UUID
	ApprovalID         uuid.UUID
	DispatchGeneration int64
	TurnID             string
	ToolUseID          string
	ToolName           string
	InputSHA256        string
}

func (f ManagedToolApprovalFence) RuntimeKind() controlmodel.DataPlaneKind {
	if f.BackendKind == "" {
		return controlmodel.DataPlaneManaged
	}
	return f.BackendKind
}

func RuntimeToolApprovalRequestKind(backend controlmodel.DataPlaneKind) string {
	if backend == controlmodel.DataPlaneManaged || backend == "" {
		return controlmodel.ApprovalRequestKindManagedToolConfirmation
	}
	return controlmodel.ApprovalRequestKindRuntimeToolConfirmation
}

func RuntimeToolApprovalID(backend controlmodel.DataPlaneKind, tenant, sessionID, attemptID string,
	dispatchGeneration int64, turnID, toolUseID string) uuid.UUID {
	if backend == controlmodel.DataPlaneManaged || backend == "" {
		return ManagedToolApprovalID(tenant, sessionID, attemptID, dispatchGeneration, turnID, toolUseID)
	}
	name := fmt.Sprintf("aistio:runtime-hitl:v1:%s:%s:%s:%s:%d:%s:%s",
		backend, tenant, sessionID, attemptID, dispatchGeneration, turnID, toolUseID)
	return uuid.NewSHA1(uuid.NameSpaceURL, []byte(name))
}

// ManagedToolApprovalRequest atomically creates the human Approval/Inbox item
// and suspends the requesting Task and Attempt.
type ManagedToolApprovalRequest struct {
	Fence    ManagedToolApprovalFence
	Approval *controlmodel.Approval
}

// ManagedToolApprovalID mirrors the managed data-plane UUIDv5 contract. The
// ID is stable across event-upload retries and process restarts, while the
// complete tuple prevents one physical tool use from aliasing another turn.
func ManagedToolApprovalID(tenant, sessionID, attemptID string, dispatchGeneration int64,
	turnID, toolUseID string) uuid.UUID {
	name := fmt.Sprintf("aistio:managed-hitl:v1:%s:%s:%s:%d:%s:%s",
		tenant, sessionID, attemptID, dispatchGeneration, turnID, toolUseID)
	return uuid.NewSHA1(uuid.NameSpaceURL, []byte(name))
}

// ManagedAttemptNeedsAbort identifies failures that can otherwise leave a
// managed data-plane HITL waiter holding the session after the logical Attempt
// was fenced. Callers evaluate it before mutating Task/Attempt state.
func ManagedAttemptNeedsAbort(task *controlmodel.AgentTask, attempt *controlmodel.ExecutionAttempt, failureCode string) bool {
	if task == nil || attempt == nil || attempt.BackendKind != controlmodel.DataPlaneManaged {
		return false
	}
	return failureCode == "hitl_approver_unavailable" || failureCode == "hitl_continuation_lost" ||
		task.Status == controlmodel.AgentTaskWaiting && strings.HasPrefix(task.WaitReason, "approval:")
}

// ValidateManagedToolApprovalFence is shared by durable and in-memory stores.
// ToolUseID is deliberately part of the fence even though it is not stored on
// ExecutionAttempt: Approval.TargetRef anchors the Attempt, while Approval.Request
// freezes the individual confirmation within that turn.
func ValidateManagedToolApprovalFence(task *controlmodel.AgentTask, attempt *controlmodel.ExecutionAttempt, fence ManagedToolApprovalFence) error {
	if task == nil || attempt == nil || fence.SessionID == "" || fence.TaskID == uuid.Nil ||
		fence.AttemptID == uuid.Nil || fence.ApprovalID == uuid.Nil || fence.DispatchGeneration <= 0 ||
		fence.TurnID == "" || fence.ToolUseID == "" || fence.ToolName == "" {
		return fmt.Errorf("managed tool approval requires session, task, attempt, generation, turn, and tool-use fence")
	}
	if task.ID != fence.TaskID || task.CurrentAttemptID == nil || *task.CurrentAttemptID != fence.AttemptID ||
		attempt.ID != fence.AttemptID || attempt.AgentTaskID != task.ID ||
		task.Tenant != attempt.Tenant || task.Namespace != attempt.Namespace ||
		attempt.BackendKind != fence.RuntimeKind() || attempt.SessionID != fence.SessionID ||
		attempt.DispatchGeneration != fence.DispatchGeneration || attempt.TurnID != fence.TurnID {
		return ErrConflict
	}
	return nil
}

// ValidateManagedToolApproval binds the user-facing Approval to the same
// collaboration scope and stable tool-use target as its physical turn.
func ValidateManagedToolApproval(approval *controlmodel.Approval, task *controlmodel.AgentTask, fence ManagedToolApprovalFence) error {
	if approval == nil || task == nil || approval.ID != fence.ApprovalID || approval.ApproverRef == "" ||
		approval.Tenant != task.Tenant || approval.Namespace != task.Namespace ||
		approval.TargetType != controlmodel.ApprovalTargetExecutionAttempt ||
		approval.TargetRef != fence.AttemptID.String() ||
		approval.IssueID == nil || *approval.IssueID != task.IssueID ||
		approval.RunID == nil || *approval.RunID != task.OrchestrationRunID ||
		approval.RunNodeID == nil || *approval.RunNodeID != task.RunNodeID {
		return ErrConflict
	}
	var request struct {
		Kind               string                     `json:"kind"`
		BackendKind        controlmodel.DataPlaneKind `json:"backendKind"`
		SchemaVersion      int32                      `json:"schemaVersion"`
		Tenant             string                     `json:"tenant"`
		Namespace          string                     `json:"namespace"`
		SessionID          string                     `json:"sessionId"`
		ApprovalID         uuid.UUID                  `json:"approvalId"`
		AgentTaskID        uuid.UUID                  `json:"agentTaskId"`
		AttemptID          uuid.UUID                  `json:"attemptId"`
		DispatchGeneration int64                      `json:"dispatchGeneration"`
		TurnID             string                     `json:"turnId"`
		ToolUseID          string                     `json:"toolUseId"`
		ToolName           string                     `json:"toolName"`
		InputSHA256        string                     `json:"inputSha256"`
	}
	if json.Unmarshal(approval.Request, &request) != nil ||
		request.Kind != RuntimeToolApprovalRequestKind(fence.RuntimeKind()) ||
		(request.BackendKind != "" && request.BackendKind != fence.RuntimeKind()) ||
		request.SchemaVersion != 1 ||
		request.Tenant != task.Tenant || request.Namespace != task.Namespace ||
		request.SessionID != fence.SessionID || request.ApprovalID != fence.ApprovalID ||
		request.AgentTaskID != fence.TaskID || request.AttemptID != fence.AttemptID ||
		request.DispatchGeneration != fence.DispatchGeneration || request.TurnID != fence.TurnID ||
		request.ToolUseID != fence.ToolUseID || request.ToolName != fence.ToolName ||
		request.InputSHA256 != fence.InputSHA256 {
		return ErrConflict
	}
	return nil
}

// ManagedToolApprovalMatchesFence compares the immutable physical tool-use
// identity while deliberately ignoring ApprovalID and SourceEventID. It is
// used under the Task row/lock to prevent a restarted or faulty reporter from
// creating a second Approval for the same continuation with a different ID.
func ManagedToolApprovalMatchesFence(approval *controlmodel.Approval, fence ManagedToolApprovalFence) bool {
	if approval == nil || approval.TargetType != controlmodel.ApprovalTargetExecutionAttempt ||
		approval.TargetRef != fence.AttemptID.String() {
		return false
	}
	var request struct {
		Kind               string                     `json:"kind"`
		BackendKind        controlmodel.DataPlaneKind `json:"backendKind"`
		SchemaVersion      int32                      `json:"schemaVersion"`
		SessionID          string                     `json:"sessionId"`
		AgentTaskID        uuid.UUID                  `json:"agentTaskId"`
		AttemptID          uuid.UUID                  `json:"attemptId"`
		DispatchGeneration int64                      `json:"dispatchGeneration"`
		TurnID             string                     `json:"turnId"`
		ToolUseID          string                     `json:"toolUseId"`
	}
	return json.Unmarshal(approval.Request, &request) == nil &&
		request.Kind == RuntimeToolApprovalRequestKind(fence.RuntimeKind()) &&
		(request.BackendKind == "" || request.BackendKind == fence.RuntimeKind()) &&
		request.SchemaVersion == 1 &&
		request.SessionID == fence.SessionID && request.AgentTaskID == fence.TaskID &&
		request.AttemptID == fence.AttemptID && request.DispatchGeneration == fence.DispatchGeneration &&
		request.TurnID == fence.TurnID && request.ToolUseID == fence.ToolUseID
}

type AutomationFilter struct {
	Tenant    string
	Namespace string
	Enabled   *bool
	DueBefore *time.Time
	Limit     int
	Offset    int
}

type CollaborationRepository interface {
	CreateIssue(ctx context.Context, issue *controlmodel.Issue) (*controlmodel.Issue, error)
	GetIssue(ctx context.Context, id uuid.UUID) (*controlmodel.Issue, error)
	ListIssues(ctx context.Context, filter IssueFilter) ([]*controlmodel.Issue, error)
	UpdateIssue(ctx context.Context, issue *controlmodel.Issue, expectedVersion int64, actor controlmodel.Actor) (*controlmodel.Issue, error)
	TransitionIssue(ctx context.Context, id uuid.UUID, expectedVersion int64, status controlmodel.IssueStatus, actor controlmodel.Actor, reason string) (*controlmodel.Issue, error)
	ArchiveIssue(ctx context.Context, id uuid.UUID, expectedVersion int64, actor controlmodel.Actor) (*controlmodel.Issue, error)
	AssignIssue(ctx context.Context, id uuid.UUID, expectedVersion int64, assigneeType controlmodel.AssigneeType, assigneeRef string, actor controlmodel.Actor) (*controlmodel.Issue, *controlmodel.AgentTask, error)

	CreateComment(ctx context.Context, req CreateCommentRequest) (*CreateCommentResult, error)
	GetComment(ctx context.Context, id uuid.UUID) (*controlmodel.Comment, error)
	ListComments(ctx context.Context, issueID uuid.UUID, opts CommentListOptions) ([]*controlmodel.Comment, error)
	UpdateComment(ctx context.Context, comment *controlmodel.Comment, expectedVersion int64) (*controlmodel.Comment, error)
	DeleteComment(ctx context.Context, id uuid.UUID, expectedVersion int64, actor controlmodel.Actor) (*controlmodel.Comment, error)
	ResolveComment(ctx context.Context, id uuid.UUID, expectedVersion int64, actor controlmodel.Actor, resolved bool) (*controlmodel.Comment, error)

	GetAgentTask(ctx context.Context, id uuid.UUID) (*controlmodel.AgentTask, error)
	CreateRunAgentTask(ctx context.Context, req RunTaskRequest) (*controlmodel.AgentTask, error)
	ListAgentTasks(ctx context.Context, filter AgentTaskFilter) ([]*controlmodel.AgentTask, error)
	ClaimAgentTask(ctx context.Context, claim TaskClaim) (*controlmodel.AgentTask, error)
	ClaimAgentTaskWithAttempt(ctx context.Context, claim TaskClaim, execution *controlmodel.ExecutionAttempt) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error)
	AcknowledgeTaskInputs(ctx context.Context, taskID uuid.UUID, inputIDs []uuid.UUID) ([]controlmodel.AgentTaskInput, error)
	FailTaskInputDelivery(ctx context.Context, taskID uuid.UUID, inputIDs []uuid.UUID, message string, maxAttempts int) ([]controlmodel.AgentTaskInput, error)
	ReplayDeadLetterInputs(ctx context.Context, taskID uuid.UUID, inputIDs []uuid.UUID, actor controlmodel.Actor) (*controlmodel.AgentTask, error)
	RequeueRetryableInputs(ctx context.Context, now time.Time, limit int) ([]uuid.UUID, error)
	StartAgentTask(ctx context.Context, id uuid.UUID, expectedVersion int64) (*controlmodel.AgentTask, error)
	BeginReviewWork(ctx context.Context, id uuid.UUID, expectedVersion int64, requestQuote string) (*controlmodel.AgentTask, error)
	CompleteAgentTask(ctx context.Context, id uuid.UUID, completion TaskCompletion) (*controlmodel.AgentTask, error)
	CompleteAgentTaskWithComment(ctx context.Context, id uuid.UUID, completion TaskCompletion, comment *controlmodel.Comment, targets []CommentTarget) (*controlmodel.AgentTask, *controlmodel.Comment, error)
	FailAgentTaskWithAttempt(ctx context.Context, id uuid.UUID, failure TaskFailure) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error)
	RequeueAgentTaskAfterAttemptFailure(ctx context.Context, id uuid.UUID, failure TaskFailure) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error)
	FailAgentTask(ctx context.Context, id uuid.UUID, expectedVersion int64, code, message string, result ...json.RawMessage) (*controlmodel.AgentTask, error)
	CancelAgentTask(ctx context.Context, id uuid.UUID, expectedVersion int64) (*controlmodel.AgentTask, error)
	RetryAgentTask(ctx context.Context, id uuid.UUID, actor controlmodel.Actor) (*controlmodel.AgentTask, error)

	CreateTeam(ctx context.Context, team *controlmodel.CollaborationTeam) (*controlmodel.CollaborationTeam, error)
	GetTeam(ctx context.Context, id uuid.UUID) (*controlmodel.CollaborationTeam, error)
	ListTeams(ctx context.Context, tenant, namespace string) ([]*controlmodel.CollaborationTeam, error)
	UpdateTeam(ctx context.Context, team *controlmodel.CollaborationTeam, expectedVersion int64) (*controlmodel.CollaborationTeam, error)
	AddTeamMember(ctx context.Context, member *controlmodel.CollaborationTeamMember) (*controlmodel.CollaborationTeamMember, error)
	UpdateTeamMember(ctx context.Context, member *controlmodel.CollaborationTeamMember, expectedTeamVersion int64) (*controlmodel.CollaborationTeamMember, error)
	RemoveTeamMember(ctx context.Context, teamID, memberID uuid.UUID) error

	CreateArtifact(ctx context.Context, artifact *controlmodel.Artifact, links []controlmodel.ArtifactLink) (*controlmodel.Artifact, error)
	GetArtifact(ctx context.Context, id uuid.UUID) (*controlmodel.Artifact, []controlmodel.ArtifactLink, error)
	ListArtifacts(ctx context.Context, tenant, namespace, targetType, targetRef string) ([]*controlmodel.Artifact, error)
	SubscribeIssue(ctx context.Context, subscriber *controlmodel.IssueSubscriber) (*controlmodel.IssueSubscriber, error)
	UnsubscribeIssue(ctx context.Context, issueID uuid.UUID, subscriberType controlmodel.AssigneeType, subscriberRef string) error
	ListIssueSubscribers(ctx context.Context, issueID uuid.UUID) ([]*controlmodel.IssueSubscriber, error)

	CreateApproval(ctx context.Context, approval *controlmodel.Approval) (*controlmodel.Approval, error)
	CreateManagedToolApproval(ctx context.Context, req ManagedToolApprovalRequest) (*controlmodel.Approval, *controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error)
	ResumeManagedToolApproval(ctx context.Context, approvalID uuid.UUID, fence ManagedToolApprovalFence, leaseTTL time.Duration) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error)
	GetApproval(ctx context.Context, id uuid.UUID) (*controlmodel.Approval, error)
	ListApprovals(ctx context.Context, filter ApprovalFilter) ([]*controlmodel.Approval, error)
	DecideApproval(ctx context.Context, id uuid.UUID, expectedVersion int64, status controlmodel.ApprovalStatus, actor controlmodel.Actor, decision json.RawMessage) (*controlmodel.Approval, error)

	ListInbox(ctx context.Context, filter InboxFilter) ([]*controlmodel.InboxItem, error)
	GetInbox(ctx context.Context, id uuid.UUID, recipientRef string) (*controlmodel.InboxItem, error)
	InboxSummary(ctx context.Context, filter InboxFilter) (*controlmodel.InboxSummary, error)
	UpdateInbox(ctx context.Context, id uuid.UUID, recipientRef string, read, archived *bool) (*controlmodel.InboxItem, error)
	ListActivities(ctx context.Context, issueID uuid.UUID, limit, offset int) ([]*controlmodel.Activity, error)
	SweepOverdueIssues(ctx context.Context, now time.Time, limit int) (int, error)
	SweepTimedOutAgentTasks(ctx context.Context, now time.Time, limit int) (int, error)

	CreateAutomation(ctx context.Context, automation *controlmodel.Automation) (*controlmodel.Automation, error)
	GetAutomation(ctx context.Context, id uuid.UUID) (*controlmodel.Automation, error)
	ListAutomations(ctx context.Context, filter AutomationFilter) ([]*controlmodel.Automation, error)
	UpdateAutomation(ctx context.Context, automation *controlmodel.Automation, expectedVersion int64) (*controlmodel.Automation, error)
	ArchiveAutomation(ctx context.Context, id uuid.UUID, expectedVersion int64) (*controlmodel.Automation, error)
	AdmitAutomationRun(ctx context.Context, req AutomationAdmission) (*controlmodel.AutomationRun, bool, error)
	GetAutomationRun(ctx context.Context, id uuid.UUID) (*controlmodel.AutomationRun, error)
	ListPendingAutomationRuns(ctx context.Context, limit int) ([]*controlmodel.AutomationRun, error)
	ClaimAutomationRun(ctx context.Context, id uuid.UUID, now time.Time, ttl time.Duration) (*controlmodel.AutomationRun, error)
	SaveAutomationDelivery(ctx context.Context, delivery *controlmodel.AutomationDelivery) (*controlmodel.AutomationDelivery, bool, error)
	GetAutomationDelivery(ctx context.Context, id uuid.UUID) (*controlmodel.AutomationDelivery, error)
	ListAutomationDeliveries(ctx context.Context, automationID uuid.UUID, limit, offset int) ([]*controlmodel.AutomationDelivery, error)

	BeginAutomationRun(ctx context.Context, run *controlmodel.AutomationRun) (*controlmodel.AutomationRun, bool, error)
	FinishAutomationRun(ctx context.Context, run *controlmodel.AutomationRun) (*controlmodel.AutomationRun, error)
	ListAutomationRuns(ctx context.Context, automationID uuid.UUID, limit, offset int) ([]*controlmodel.AutomationRun, error)

	// ReconcileRunningInputs guarantees that comments routed while a task was
	// running remain attached to a queued successor instead of being lost.
	ReconcileRunningInputs(ctx context.Context, taskID uuid.UUID, now time.Time) error
}

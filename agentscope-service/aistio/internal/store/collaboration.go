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
	Checkpoint         json.RawMessage
	Usage              json.RawMessage
}

type InboxFilter struct {
	Tenant       string
	Namespace    string
	RecipientRef string
	Archived     bool
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
	CompleteAgentTask(ctx context.Context, id uuid.UUID, completion TaskCompletion) (*controlmodel.AgentTask, error)
	CompleteAgentTaskWithComment(ctx context.Context, id uuid.UUID, completion TaskCompletion, comment *controlmodel.Comment, targets []CommentTarget) (*controlmodel.AgentTask, *controlmodel.Comment, error)
	FailAgentTaskWithAttempt(ctx context.Context, id uuid.UUID, failure TaskFailure) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error)
	RequeueAgentTaskAfterAttemptFailure(ctx context.Context, id uuid.UUID, failure TaskFailure) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error)
	FailAgentTask(ctx context.Context, id uuid.UUID, expectedVersion int64, code, message string) (*controlmodel.AgentTask, error)
	CancelAgentTask(ctx context.Context, id uuid.UUID, expectedVersion int64) (*controlmodel.AgentTask, error)
	RetryAgentTask(ctx context.Context, id uuid.UUID, actor controlmodel.Actor) (*controlmodel.AgentTask, error)

	CreateTeam(ctx context.Context, team *controlmodel.CollaborationTeam) (*controlmodel.CollaborationTeam, error)
	GetTeam(ctx context.Context, id uuid.UUID) (*controlmodel.CollaborationTeam, error)
	ListTeams(ctx context.Context, tenant, namespace string) ([]*controlmodel.CollaborationTeam, error)
	UpdateTeam(ctx context.Context, team *controlmodel.CollaborationTeam, expectedVersion int64) (*controlmodel.CollaborationTeam, error)
	AddTeamMember(ctx context.Context, member *controlmodel.CollaborationTeamMember) (*controlmodel.CollaborationTeamMember, error)
	RemoveTeamMember(ctx context.Context, teamID, memberID uuid.UUID) error

	CreateArtifact(ctx context.Context, artifact *controlmodel.Artifact, links []controlmodel.ArtifactLink) (*controlmodel.Artifact, error)
	GetArtifact(ctx context.Context, id uuid.UUID) (*controlmodel.Artifact, []controlmodel.ArtifactLink, error)
	ListArtifacts(ctx context.Context, tenant, namespace, targetType, targetRef string) ([]*controlmodel.Artifact, error)
	SubscribeIssue(ctx context.Context, subscriber *controlmodel.IssueSubscriber) (*controlmodel.IssueSubscriber, error)
	UnsubscribeIssue(ctx context.Context, issueID uuid.UUID, subscriberType controlmodel.AssigneeType, subscriberRef string) error
	ListIssueSubscribers(ctx context.Context, issueID uuid.UUID) ([]*controlmodel.IssueSubscriber, error)

	CreateApproval(ctx context.Context, approval *controlmodel.Approval) (*controlmodel.Approval, error)
	GetApproval(ctx context.Context, id uuid.UUID) (*controlmodel.Approval, error)
	ListApprovals(ctx context.Context, filter ApprovalFilter) ([]*controlmodel.Approval, error)
	DecideApproval(ctx context.Context, id uuid.UUID, expectedVersion int64, status controlmodel.ApprovalStatus, actor controlmodel.Actor, decision json.RawMessage) (*controlmodel.Approval, error)

	ListInbox(ctx context.Context, filter InboxFilter) ([]*controlmodel.InboxItem, error)
	UpdateInbox(ctx context.Context, id uuid.UUID, recipientRef string, read, archived *bool) (*controlmodel.InboxItem, error)
	ListActivities(ctx context.Context, issueID uuid.UUID, limit, offset int) ([]*controlmodel.Activity, error)
	SweepOverdueIssues(ctx context.Context, now time.Time, limit int) (int, error)
	SweepTimedOutAgentTasks(ctx context.Context, now time.Time, limit int) (int, error)

	CreateAutomation(ctx context.Context, automation *controlmodel.Automation) (*controlmodel.Automation, error)
	GetAutomation(ctx context.Context, id uuid.UUID) (*controlmodel.Automation, error)
	ListAutomations(ctx context.Context, filter AutomationFilter) ([]*controlmodel.Automation, error)
	UpdateAutomation(ctx context.Context, automation *controlmodel.Automation, expectedVersion int64) (*controlmodel.Automation, error)
	ArchiveAutomation(ctx context.Context, id uuid.UUID, expectedVersion int64) (*controlmodel.Automation, error)
	BeginAutomationRun(ctx context.Context, run *controlmodel.AutomationRun) (*controlmodel.AutomationRun, bool, error)
	FinishAutomationRun(ctx context.Context, run *controlmodel.AutomationRun) (*controlmodel.AutomationRun, error)
	ListAutomationRuns(ctx context.Context, automationID uuid.UUID, limit, offset int) ([]*controlmodel.AutomationRun, error)

	// ReconcileRunningInputs guarantees that comments routed while a task was
	// running remain attached to a queued successor instead of being lost.
	ReconcileRunningInputs(ctx context.Context, taskID uuid.UUID, now time.Time) error
}

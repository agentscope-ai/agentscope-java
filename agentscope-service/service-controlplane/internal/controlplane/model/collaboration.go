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

package model

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

// ActorType identifies who performed a collaboration action. ActorRef is an
// opaque stable product identifier and is always resolved server-side.
type ActorType string

const (
	ActorHuman      ActorType = "human"
	ActorAgent      ActorType = "agent"
	ActorSystem     ActorType = "system"
	ActorAutomation ActorType = "automation"
)

type Actor struct {
	Type ActorType `json:"type"`
	Ref  string    `json:"ref,omitempty"`
}

type IssueStatus string

const (
	IssueBacklog    IssueStatus = "backlog"
	IssueTodo       IssueStatus = "todo"
	IssueInProgress IssueStatus = "in_progress"
	IssueInReview   IssueStatus = "in_review"
	IssueBlocked    IssueStatus = "blocked"
	IssueDone       IssueStatus = "done"
	IssueCancelled  IssueStatus = "cancelled"
)

func CanTransitionIssue(from, to IssueStatus) bool {
	if from == to {
		return true
	}
	switch from {
	case IssueBacklog:
		return to == IssueTodo || to == IssueInProgress || to == IssueCancelled
	case IssueTodo:
		return to == IssueBacklog || to == IssueInProgress || to == IssueBlocked || to == IssueCancelled
	case IssueInProgress:
		return to == IssueInReview || to == IssueBlocked || to == IssueDone || to == IssueCancelled
	case IssueInReview:
		return to == IssueInProgress || to == IssueBlocked || to == IssueDone || to == IssueCancelled
	case IssueBlocked:
		return to == IssueTodo || to == IssueInProgress || to == IssueCancelled
	case IssueDone:
		return to == IssueInProgress
	default:
		return false
	}
}

type AssigneeType string

const (
	AssigneeHuman AssigneeType = "human"
	AssigneeAgent AssigneeType = "agent"
	AssigneeTeam  AssigneeType = "team"
)

// IssueKind separates human-facing Work from durable execution records.  Team
// and Workflow Endpoint jobs deliberately keep using the collaboration plane,
// but are not ordinary Work Hub Issues.
type IssueKind string

const (
	IssueKindUserWork         IssueKind = "user_work"
	IssueKindEndpointJob      IssueKind = "endpoint_job"
	IssueKindAutomationJob    IssueKind = "automation_job"
	IssueKindConversationTurn IssueKind = "conversation_turn"
)

type IssueVisibility string

const (
	IssueVisibilityWorkHub     IssueVisibility = "work_hub"
	IssueVisibilityOperational IssueVisibility = "operational"
)

type IssueCompletionPolicy string

const (
	IssueCompletionReview    IssueCompletionPolicy = "review"
	IssueCompletionAutomatic IssueCompletionPolicy = "automatic"
	IssueCompletionExternal  IssueCompletionPolicy = "external"
)

type Issue struct {
	Access              IssueAccess           `json:"access"`
	ID                  uuid.UUID             `json:"id"`
	Tenant              string                `json:"tenant"`
	Namespace           string                `json:"namespace"`
	Identifier          string                `json:"identifier,omitempty"`
	Title               string                `json:"title"`
	Description         string                `json:"description,omitempty"`
	Status              IssueStatus           `json:"status"`
	Priority            string                `json:"priority"`
	Kind                IssueKind             `json:"kind"`
	Visibility          IssueVisibility       `json:"visibility"`
	CompletionPolicy    IssueCompletionPolicy `json:"completionPolicy"`
	AssigneeType        AssigneeType          `json:"assigneeType,omitempty"`
	AssigneeRef         string                `json:"assigneeRef,omitempty"`
	ExecutionTargetType string                `json:"executionTargetType,omitempty"`
	ExecutionTargetRef  string                `json:"executionTargetRef,omitempty"`
	Creator             Actor                 `json:"creator"`
	ParentIssueID       *uuid.UUID            `json:"parentIssueId,omitempty"`
	AcceptanceCriteria  json.RawMessage       `json:"acceptanceCriteria,omitempty"`
	ContextRefs         json.RawMessage       `json:"contextRefs,omitempty"`
	SourceType          string                `json:"sourceType,omitempty"`
	SourceRef           string                `json:"sourceRef,omitempty"`
	DueAt               *time.Time            `json:"dueAt,omitempty"`
	Version             int64                 `json:"version"`
	CreatedAt           time.Time             `json:"createdAt"`
	UpdatedAt           time.Time             `json:"updatedAt"`
	ResolvedAt          *time.Time            `json:"resolvedAt,omitempty"`
	ArchivedAt          *time.Time            `json:"archivedAt,omitempty"`
}

type CommentType string

const (
	CommentGeneral  CommentType = "comment"
	CommentProgress CommentType = "progress"
	CommentResult   CommentType = "result"
	CommentStatus   CommentType = "status"
	CommentSystem   CommentType = "system"
)

type Comment struct {
	ID              uuid.UUID      `json:"id"`
	Tenant          string         `json:"tenant"`
	Namespace       string         `json:"namespace"`
	IssueID         uuid.UUID      `json:"issueId"`
	ParentID        *uuid.UUID     `json:"parentId,omitempty"`
	ThreadRootID    uuid.UUID      `json:"threadRootId"`
	Author          Actor          `json:"author"`
	Content         string         `json:"content"`
	Type            CommentType    `json:"type"`
	SourceTaskID    *uuid.UUID     `json:"sourceTaskId,omitempty"`
	SourceAttemptID *uuid.UUID     `json:"sourceAttemptId,omitempty"`
	Version         int64          `json:"version"`
	CreatedAt       time.Time      `json:"createdAt"`
	UpdatedAt       time.Time      `json:"updatedAt"`
	ResolvedAt      *time.Time     `json:"resolvedAt,omitempty"`
	ResolvedBy      *Actor         `json:"resolvedBy,omitempty"`
	DeletedAt       *time.Time     `json:"deletedAt,omitempty"`
	Mentions        []Mention      `json:"mentions,omitempty"`
	Routes          []CommentRoute `json:"routes,omitempty"`
}

type Mention struct {
	ID         uuid.UUID    `json:"id"`
	Tenant     string       `json:"tenant"`
	Namespace  string       `json:"namespace"`
	IssueID    uuid.UUID    `json:"issueId"`
	CommentID  uuid.UUID    `json:"commentId"`
	TargetType AssigneeType `json:"targetType"`
	TargetRef  string       `json:"targetRef"`
	CreatedAt  time.Time    `json:"createdAt"`
}

type CommentRouteType string

const (
	RouteExplicit      CommentRouteType = "explicit"
	RouteThreadParent  CommentRouteType = "thread_parent"
	RouteAssignee      CommentRouteType = "assignee"
	RouteTeamLeader    CommentRouteType = "team_leader"
	RouteFollowUp      CommentRouteType = "follow_up"
	RouteReviewRequest CommentRouteType = "review_request"
)

type CommentRouteOutcome string

const (
	RouteQueued     CommentRouteOutcome = "queued"
	RouteCoalesced  CommentRouteOutcome = "coalesced"
	RouteDeferred   CommentRouteOutcome = "deferred"
	RouteSuppressed CommentRouteOutcome = "suppressed"
	RouteBlocked    CommentRouteOutcome = "blocked"
)

type CommentRoute struct {
	ID             uuid.UUID           `json:"id"`
	Tenant         string              `json:"tenant"`
	Namespace      string              `json:"namespace"`
	IssueID        uuid.UUID           `json:"issueId"`
	CommentID      uuid.UUID           `json:"commentId"`
	CommentVersion int64               `json:"commentVersion"`
	TargetType     AssigneeType        `json:"targetType"`
	TargetRef      string              `json:"targetRef"`
	RouteType      CommentRouteType    `json:"routeType"`
	Outcome        CommentRouteOutcome `json:"outcome"`
	TaskID         *uuid.UUID          `json:"taskId,omitempty"`
	ReasonCode     string              `json:"reasonCode,omitempty"`
	CreatedAt      time.Time           `json:"createdAt"`
}

type AgentTaskStatus string

const (
	AgentTaskQueued     AgentTaskStatus = "queued"
	AgentTaskDispatched AgentTaskStatus = "dispatched"
	AgentTaskRunning    AgentTaskStatus = "running"
	AgentTaskWaiting    AgentTaskStatus = "waiting"
	AgentTaskCompleted  AgentTaskStatus = "completed"
	AgentTaskFailed     AgentTaskStatus = "failed"
	AgentTaskCancelled  AgentTaskStatus = "cancelled"
)

func IsAgentTaskTerminal(status AgentTaskStatus) bool {
	return status == AgentTaskCompleted || status == AgentTaskFailed || status == AgentTaskCancelled
}

func CanTransitionAgentTask(from, to AgentTaskStatus) bool {
	if from == to {
		return true
	}
	switch from {
	case AgentTaskQueued:
		return to == AgentTaskDispatched || to == AgentTaskCancelled || to == AgentTaskFailed
	case AgentTaskDispatched:
		return to == AgentTaskQueued || to == AgentTaskRunning || to == AgentTaskWaiting || to == AgentTaskFailed || to == AgentTaskCancelled
	case AgentTaskRunning:
		return to == AgentTaskQueued || to == AgentTaskWaiting || to == AgentTaskCompleted || to == AgentTaskFailed || to == AgentTaskCancelled
	case AgentTaskWaiting:
		return to == AgentTaskQueued || to == AgentTaskRunning || to == AgentTaskFailed || to == AgentTaskCancelled
	default:
		return false
	}
}

// AgentTaskReviewComment handles feedback without reopening the original work.
const AgentTaskReviewComment = "review_comment"

type AgentTask struct {
	ID                  uuid.UUID        `json:"id"`
	Tenant              string           `json:"tenant"`
	Namespace           string           `json:"namespace"`
	IssueID             uuid.UUID        `json:"issueId"`
	OrchestrationRunID  uuid.UUID        `json:"orchestrationRunId"`
	RunNodeID           uuid.UUID        `json:"runNodeId"`
	CurrentAttemptID    *uuid.UUID       `json:"currentAttemptId,omitempty"`
	AgentRef            string           `json:"agentId"`
	Status              AgentTaskStatus  `json:"status"`
	Priority            int32            `json:"priority"`
	TriggerType         string           `json:"triggerType"`
	TriggerCommentID    *uuid.UUID       `json:"triggerCommentId,omitempty"`
	TeamID              *uuid.UUID       `json:"teamId,omitempty"`
	TeamRole            string           `json:"teamRole,omitempty"`
	LeaderTask          bool             `json:"leaderTask,omitempty"`
	ParentTaskID        *uuid.UUID       `json:"parentTaskId,omitempty"`
	DelegatedFromTaskID *uuid.UUID       `json:"delegatedFromTaskId,omitempty"`
	RetryOfTaskID       *uuid.UUID       `json:"retryOfTaskId,omitempty"`
	RerunOfTaskID       *uuid.UUID       `json:"rerunOfTaskId,omitempty"`
	Originator          Actor            `json:"originator"`
	AccountableHumanRef string           `json:"accountableHumanRef,omitempty"`
	CausationID         string           `json:"causationId,omitempty"`
	CorrelationID       string           `json:"correlationId,omitempty"`
	HopCount            int32            `json:"hopCount"`
	TeamDepth           int32            `json:"teamDepth"`
	RuntimeBinding      json.RawMessage  `json:"runtimeBinding,omitempty"`
	SessionID           string           `json:"sessionId,omitempty"`
	Result              json.RawMessage  `json:"result,omitempty"`
	ErrorCode           string           `json:"errorCode,omitempty"`
	ErrorMessage        string           `json:"errorMessage,omitempty"`
	WaitReason          string           `json:"waitReason,omitempty"`
	Version             int64            `json:"version"`
	CreatedAt           time.Time        `json:"createdAt"`
	DispatchedAt        *time.Time       `json:"dispatchedAt,omitempty"`
	StartedAt           *time.Time       `json:"startedAt,omitempty"`
	CompletedAt         *time.Time       `json:"completedAt,omitempty"`
	Inputs              []AgentTaskInput `json:"inputs,omitempty"`
}

type AgentTaskInputState string

const (
	TaskInputPlanned      AgentTaskInputState = "planned"
	TaskInputDelivered    AgentTaskInputState = "delivered"
	TaskInputAcknowledged AgentTaskInputState = "acknowledged"
	TaskInputProcessed    AgentTaskInputState = "processed"
	TaskInputDeferred     AgentTaskInputState = "deferred"
	TaskInputRetrying     AgentTaskInputState = "retrying"
	TaskInputDeadLetter   AgentTaskInputState = "dead_letter"
	TaskInputBlocked      AgentTaskInputState = "blocked"
)

type AgentTaskInput struct {
	ID                uuid.UUID           `json:"id"`
	Tenant            string              `json:"tenant"`
	Namespace         string              `json:"namespace"`
	TaskID            uuid.UUID           `json:"taskId"`
	CommentID         uuid.UUID           `json:"commentId"`
	CommentVersion    int64               `json:"commentVersion"`
	Sequence          int64               `json:"sequence"`
	State             AgentTaskInputState `json:"state"`
	DeliveredAt       *time.Time          `json:"deliveredAt,omitempty"`
	AcknowledgedAt    *time.Time          `json:"acknowledgedAt,omitempty"`
	ProcessedAt       *time.Time          `json:"processedAt,omitempty"`
	ResponseCommentID *uuid.UUID          `json:"responseCommentId,omitempty"`
	Attempts          int32               `json:"attempts"`
	LastError         string              `json:"lastError,omitempty"`
	NextAttemptAt     *time.Time          `json:"nextAttemptAt,omitempty"`
	CreatedAt         time.Time           `json:"createdAt"`
}

type TeamPolicy struct {
	MaxActiveTasks            int32    `json:"maxActiveTasks,omitempty"`
	MaxHops                   int32    `json:"maxHops,omitempty"`
	MaxChildDepth             int32    `json:"maxChildDepth,omitempty"`
	MaxChildIssues            int32    `json:"maxChildIssues,omitempty"`
	MaxFanout                 int32    `json:"maxFanout,omitempty"`
	MaxTaskRetries            int32    `json:"maxTaskRetries,omitempty"`
	MaxArtifactBytes          int64    `json:"maxArtifactBytes,omitempty"`
	MaxIssueTokens            int64    `json:"maxIssueTokens,omitempty"`
	MaxIssueCostMicros        int64    `json:"maxIssueCostMicros,omitempty"`
	IssueSLASeconds           int64    `json:"issueSlaSeconds,omitempty"`
	TaskTimeoutSeconds        int64    `json:"taskTimeoutSeconds,omitempty"`
	RequireReview             bool     `json:"requireReview,omitempty"`
	AllowMentionAll           bool     `json:"allowMentionAll,omitempty"`
	AllowExternalDelegation   bool     `json:"allowExternalDelegation,omitempty"`
	SecretPolicy              string   `json:"secretPolicy,omitempty"` // allow/block
	PIIPolicy                 string   `json:"piiPolicy,omitempty"`    // allow/block
	AllowedArtifactMediaTypes []string `json:"allowedArtifactMediaTypes,omitempty"`
}

type TeamStatus string

const (
	TeamActive   TeamStatus = "active"
	TeamDisabled TeamStatus = "disabled"
)

type CollaborationTeam struct {
	ID             uuid.UUID                 `json:"id"`
	Tenant         string                    `json:"tenant"`
	Namespace      string                    `json:"namespace"`
	Name           string                    `json:"name"`
	Description    string                    `json:"description,omitempty"`
	Instructions   string                    `json:"instructions,omitempty"`
	Status         TeamStatus                `json:"status"`
	LeaderAgentRef string                    `json:"leaderAgentId"`
	Policy         TeamPolicy                `json:"policy"`
	Version        int64                     `json:"version"`
	CreatedAt      time.Time                 `json:"createdAt"`
	UpdatedAt      time.Time                 `json:"updatedAt"`
	ArchivedAt     *time.Time                `json:"archivedAt,omitempty"`
	Members        []CollaborationTeamMember `json:"members,omitempty"`
}

type CollaborationTeamMember struct {
	ID                     uuid.UUID       `json:"id"`
	Tenant                 string          `json:"tenant"`
	Namespace              string          `json:"namespace"`
	TeamID                 uuid.UUID       `json:"teamId"`
	AgentRef               string          `json:"agentId"`
	Role                   string          `json:"role"`
	Instructions           string          `json:"instructions,omitempty"`
	CapabilityRequirements json.RawMessage `json:"capabilityRequirements,omitempty"`
	RuntimeBindingPolicy   json.RawMessage `json:"runtimeBindingPolicy,omitempty"`
	CreatedAt              time.Time       `json:"createdAt"`
	ArchivedAt             *time.Time      `json:"archivedAt,omitempty"`
}

type Artifact struct {
	ID              uuid.UUID       `json:"id"`
	Tenant          string          `json:"tenant"`
	Namespace       string          `json:"namespace"`
	StorageProvider string          `json:"storageProvider"`
	StorageKey      string          `json:"storageKey"`
	Filename        string          `json:"filename"`
	ContentType     string          `json:"contentType"`
	SizeBytes       int64           `json:"sizeBytes"`
	Checksum        string          `json:"checksum"`
	Uploader        Actor           `json:"uploader"`
	SourceTaskID    *uuid.UUID      `json:"sourceTaskId,omitempty"`
	SourceAttemptID *uuid.UUID      `json:"sourceAttemptId,omitempty"`
	Metadata        json.RawMessage `json:"metadata,omitempty"`
	CreatedAt       time.Time       `json:"createdAt"`
	ExpiresAt       *time.Time      `json:"expiresAt,omitempty"`
}

type ArtifactLink struct {
	ArtifactID uuid.UUID `json:"artifactId"`
	TargetType string    `json:"targetType"`
	TargetRef  string    `json:"targetRef"`
	Relation   string    `json:"relation"`
	CreatedAt  time.Time `json:"createdAt"`
}

type IssueSubscriber struct {
	IssueID        uuid.UUID    `json:"issueId"`
	Tenant         string       `json:"tenant"`
	Namespace      string       `json:"namespace"`
	SubscriberType AssigneeType `json:"subscriberType"`
	SubscriberRef  string       `json:"subscriberRef"`
	CreatedAt      time.Time    `json:"createdAt"`
}

type ApprovalStatus string

const (
	ApprovalPending   ApprovalStatus = "pending"
	ApprovalApproved  ApprovalStatus = "approved"
	ApprovalRejected  ApprovalStatus = "rejected"
	ApprovalCancelled ApprovalStatus = "cancelled"

	ApprovalTargetExecutionAttempt             = "execution_attempt"
	ApprovalRequestKindManagedToolConfirmation = "managed_tool_confirmation"
	ApprovalRequestKindRuntimeToolConfirmation = "runtime_tool_confirmation"
)

type Approval struct {
	ID          uuid.UUID       `json:"id"`
	Tenant      string          `json:"tenant"`
	Namespace   string          `json:"namespace"`
	TargetType  string          `json:"targetType"`
	TargetRef   string          `json:"targetRef"`
	IssueID     *uuid.UUID      `json:"issueId,omitempty"`
	RunID       *uuid.UUID      `json:"runId,omitempty"`
	RunNodeID   *uuid.UUID      `json:"runNodeId,omitempty"`
	RequestedBy Actor           `json:"requestedBy"`
	ApproverRef string          `json:"approverRef"`
	Status      ApprovalStatus  `json:"status"`
	Reason      string          `json:"reason,omitempty"`
	Request     json.RawMessage `json:"request,omitempty"`
	Decision    json.RawMessage `json:"decision,omitempty"`
	DecidedBy   *Actor          `json:"decidedBy,omitempty"`
	Version     int64           `json:"version"`
	CreatedAt   time.Time       `json:"createdAt"`
	UpdatedAt   time.Time       `json:"updatedAt"`
	DecidedAt   *time.Time      `json:"decidedAt,omitempty"`
}

type InboxItem struct {
	ID            uuid.UUID       `json:"id"`
	Tenant        string          `json:"tenant"`
	Namespace     string          `json:"namespace"`
	RecipientType AssigneeType    `json:"recipientType"`
	RecipientRef  string          `json:"recipientRef"`
	Type          string          `json:"type"`
	Severity      string          `json:"severity"`
	IssueID       *uuid.UUID      `json:"issueId,omitempty"`
	CommentID     *uuid.UUID      `json:"commentId,omitempty"`
	ApprovalID    *uuid.UUID      `json:"approvalId,omitempty"`
	Actor         Actor           `json:"actor"`
	Title         string          `json:"title"`
	Body          string          `json:"body,omitempty"`
	Details       json.RawMessage `json:"details,omitempty"`
	Read          bool            `json:"read"`
	Archived      bool            `json:"archived"`
	NeedsAction   bool            `json:"needsAction"`
	ReadAt        *time.Time      `json:"readAt,omitempty"`
	ResolvedAt    *time.Time      `json:"resolvedAt,omitempty"`
	DedupeKey     string          `json:"dedupeKey,omitempty"`
	CreatedAt     time.Time       `json:"createdAt"`
}

type InboxSummary struct {
	Unread           int            `json:"unread"`
	ActionRequired   int            `json:"actionRequired"`
	PendingApprovals int            `json:"pendingApprovals"`
	AttentionTotal   int            `json:"attentionTotal"`
	ByType           map[string]int `json:"byType"`
}

type Activity struct {
	ID            uuid.UUID       `json:"id"`
	Tenant        string          `json:"tenant"`
	Namespace     string          `json:"namespace"`
	IssueID       *uuid.UUID      `json:"issueId,omitempty"`
	Actor         Actor           `json:"actor"`
	Action        string          `json:"action"`
	ObjectType    string          `json:"objectType"`
	ObjectRef     string          `json:"objectRef"`
	CausationID   string          `json:"causationId,omitempty"`
	CorrelationID string          `json:"correlationId,omitempty"`
	Details       json.RawMessage `json:"details,omitempty"`
	CreatedAt     time.Time       `json:"createdAt"`
}

type AutomationTriggerType string

const (
	AutomationTriggerCron    AutomationTriggerType = "cron"
	AutomationTriggerWebhook AutomationTriggerType = "webhook"
	AutomationTriggerChannel AutomationTriggerType = "channel"
)

type AutomationActionType string

const (
	AutomationCreateIssue AutomationActionType = "create_issue"
	AutomationAddComment  AutomationActionType = "add_comment"
	AutomationStartRun    AutomationActionType = "start_orchestration"
	AutomationSignalRun   AutomationActionType = "signal_orchestration"
)

// Automation is a durable ingress rule. TriggerConfig and ActionConfig are
// versioned snapshots owned by the control plane, never by a runtime.
type Automation struct {
	Execution         *AutomationExecution  `json:"execution,omitempty"`
	Triggers          []AutomationTrigger   `json:"triggers,omitempty"`
	WebhookConfigured bool                  `json:"webhookConfigured"`
	ID                uuid.UUID             `json:"id"`
	Tenant            string                `json:"tenant"`
	Namespace         string                `json:"namespace"`
	Name              string                `json:"name"`
	Description       string                `json:"description,omitempty"`
	Enabled           bool                  `json:"enabled"`
	TriggerType       AutomationTriggerType `json:"triggerType"`
	TriggerConfig     json.RawMessage       `json:"triggerConfig,omitempty"`
	ActionType        AutomationActionType  `json:"actionType"`
	ActionConfig      json.RawMessage       `json:"actionConfig"`
	WebhookSecretHash string                `json:"-"`
	NextRunAt         *time.Time            `json:"nextRunAt,omitempty"`
	LastRunAt         *time.Time            `json:"lastRunAt,omitempty"`
	Version           int64                 `json:"version"`
	CreatedBy         Actor                 `json:"createdBy"`
	CreatedAt         time.Time             `json:"createdAt"`
	UpdatedAt         time.Time             `json:"updatedAt"`
	ArchivedAt        *time.Time            `json:"archivedAt,omitempty"`
}

type AutomationRunStatus string

const (
	AutomationRunRunning   AutomationRunStatus = "running"
	AutomationRunCompleted AutomationRunStatus = "completed"
	AutomationRunFailed    AutomationRunStatus = "failed"
	AutomationRunDuplicate AutomationRunStatus = "duplicate"
)

type AutomationRun struct {
	AutomationRunDetails
	ID                 uuid.UUID             `json:"id"`
	AutomationID       uuid.UUID             `json:"automationId"`
	Tenant             string                `json:"tenant"`
	Namespace          string                `json:"namespace"`
	TriggerType        AutomationTriggerType `json:"triggerType"`
	TriggerRef         string                `json:"triggerRef,omitempty"`
	IdempotencyKey     string                `json:"idempotencyKey"`
	Status             AutomationRunStatus   `json:"status"`
	IssueID            *uuid.UUID            `json:"issueId,omitempty"`
	AgentTaskID        *uuid.UUID            `json:"agentTaskId,omitempty"`
	OrchestrationRunID *uuid.UUID            `json:"orchestrationRunId,omitempty"`
	Input              json.RawMessage       `json:"input,omitempty"`
	Output             json.RawMessage       `json:"output,omitempty"`
	ErrorCode          string                `json:"errorCode,omitempty"`
	ErrorMessage       string                `json:"errorMessage,omitempty"`
	CreatedAt          time.Time             `json:"createdAt"`
	CompletedAt        *time.Time            `json:"completedAt,omitempty"`
}

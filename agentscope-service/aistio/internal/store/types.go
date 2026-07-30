package store

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

// Session phases.
const (
	SessionPhaseActive      = "active"
	SessionPhaseIdle        = "idle"
	SessionPhaseCompressing = "compressing"
	SessionPhaseTerminated  = "terminated"
)

// Team task states.
const (
	TaskStatePending    = "pending"
	TaskStateInProgress = "in_progress"
	TaskStateCompleted  = "completed"
)

// Session is a runtime session on an agent.
type Session struct {
	ID               uuid.UUID       `json:"id"`
	SessionID        string          `json:"sessionId"`
	AgentName        string          `json:"agentName"`
	Namespace        string          `json:"namespace"`
	Framework        string          `json:"framework"`
	FrameworkVersion string          `json:"frameworkVersion,omitempty"`
	Phase            string          `json:"phase"`
	// Busy is true when a turn/inference is in progress. nil means the data
	// plane did not report busy (unknown). Independent of Phase.
	Busy             *bool           `json:"busy,omitempty"`
	InstanceRef      string          `json:"instanceRef,omitempty"`
	InstanceIP       string          `json:"instanceIP,omitempty"`
	TeamID           string          `json:"teamId,omitempty"`
	TeamRole         string          `json:"teamRole,omitempty"`
	TeamContext      json.RawMessage `json:"teamContext,omitempty"`
	StartedAt        *time.Time      `json:"startedAt,omitempty"`
	LastActiveAt     *time.Time      `json:"lastActiveAt,omitempty"`
	TerminatedAt     *time.Time      `json:"terminatedAt,omitempty"`
	CreatedAt        time.Time       `json:"createdAt"`
	UpdatedAt        time.Time       `json:"updatedAt"`
}

// SessionWithSnapshot pairs a session with its latest Level-1 snapshot.
type SessionWithSnapshot struct {
	Session  *Session         `json:"session"`
	Snapshot *SessionSnapshot `json:"snapshot,omitempty"`
}

// TokenBucket is a time-bucketed token usage aggregate.
type TokenBucket struct {
	BucketStart      time.Time `json:"bucketStart"`
	PromptTokens     int64     `json:"promptTokens"`
	CompletionTokens int64     `json:"completionTokens"`
	TotalTokens      int64     `json:"totalTokens"`
	SampleCount      int64     `json:"sampleCount"`
}

// AgentUsage is a per-agent usage aggregate for TopAgents.
type AgentUsage struct {
	AgentName      string  `json:"agentName"`
	Namespace      string  `json:"namespace"`
	TotalTokens    int64   `json:"totalTokens"`
	ActiveSessions int32   `json:"activeSessions"`
	AvgPressure    float64 `json:"avgPressure,omitempty"`
	ErrorCount     int32   `json:"errorCount,omitempty"`
}

// SessionCommandStatus values for the session_commands audit table.
const (
	CommandStatusAccepted  = "accepted"
	CommandStatusQueued    = "queued"
	CommandStatusSucceeded = "succeeded"
	CommandStatusFailed    = "failed"
	CommandStatusRejected  = "rejected"
)

// SessionCommand is an audit row for a control-plane session operation.
type SessionCommand struct {
	ID           uuid.UUID  `json:"id"`
	SessionFK    *uuid.UUID `json:"sessionFk,omitempty"`
	AgentName    string     `json:"agentName"`
	Namespace    string     `json:"namespace"`
	SessionID    string     `json:"sessionId"`
	Command      string     `json:"command"`
	Operator     string     `json:"operator,omitempty"`
	Source       string     `json:"source,omitempty"`
	InstanceRef  string     `json:"instanceRef,omitempty"`
	Status       string     `json:"status"`
	Code         string     `json:"code,omitempty"`
	Error        string     `json:"error,omitempty"`
	Forced       bool       `json:"forced,omitempty"`
	CommandID    string     `json:"commandId,omitempty"`
	RequestedAt  time.Time  `json:"requestedAt"`
	CompletedAt  *time.Time `json:"completedAt,omitempty"`
	DurationMs   int64      `json:"durationMs,omitempty"`
}

// SessionSnapshot is a Level-1 summary captured on each poll / report.
type SessionSnapshot struct {
	ID                    int64           `json:"id"`
	SessionFK             uuid.UUID       `json:"sessionFk"`
	CapturedAt            time.Time       `json:"capturedAt"`
	MessageCount          int32           `json:"messageCount,omitempty"`
	PromptTokens          int64           `json:"promptTokens,omitempty"`
	CompletionTokens      int64           `json:"completionTokens,omitempty"`
	TotalTokens           int64           `json:"totalTokens,omitempty"`
	ContextPressure       float64         `json:"contextPressure,omitempty"`
	IsCompacted           bool            `json:"isCompacted,omitempty"`
	EffectiveMessageCount int32           `json:"effectiveMessageCount,omitempty"`
	ContextHash           string          `json:"contextHash,omitempty"`
	TaskSummary           json.RawMessage `json:"taskSummary,omitempty"`
}

// SessionEvent is a Level-2 event-stream entry.
type SessionEvent struct {
	ID            int64           `json:"id"`
	SessionFK     uuid.UUID       `json:"sessionFk"`
	Seq           int             `json:"seq"`
	EventType     string          `json:"eventType"`
	Role          string          `json:"role,omitempty"`
	Content       string          `json:"content,omitempty"`
	ToolName      string          `json:"toolName,omitempty"`
	ToolInput     json.RawMessage `json:"toolInput,omitempty"`
	ToolOutput    string          `json:"toolOutput,omitempty"`
	TokensIn      int             `json:"tokensIn,omitempty"`
	TokensOut     int             `json:"tokensOut,omitempty"`
	DurationMs    int             `json:"durationMs,omitempty"`
	FrameworkMeta json.RawMessage `json:"frameworkMeta,omitempty"`
	OccurredAt    time.Time       `json:"occurredAt"`
}

// ContextSnapshot is a Level-4 full effective-context snapshot.
type ContextSnapshot struct {
	ID                   int64           `json:"id"`
	SessionFK            uuid.UUID       `json:"sessionFk"`
	CapturedAt           time.Time       `json:"capturedAt"`
	ContextHash          string          `json:"contextHash"`
	SystemPrompt         string          `json:"systemPrompt,omitempty"`
	Messages             json.RawMessage `json:"messages"`
	Tools                json.RawMessage `json:"tools,omitempty"`
	IsCompacted          bool            `json:"isCompacted,omitempty"`
	CompactionSummary    string          `json:"compactionSummary,omitempty"`
	OriginalMessageCount int             `json:"originalMessageCount,omitempty"`
	CompactedAt          *time.Time      `json:"compactedAt,omitempty"`
	TotalTokens          int             `json:"totalTokens,omitempty"`
	MaxTokens            int             `json:"maxTokens,omitempty"`
	Framework            string          `json:"framework"`
	FrameworkState       json.RawMessage `json:"frameworkState,omitempty"`
}

// TokenUsageMetric is a time-series token-usage sample.
type TokenUsageMetric struct {
	ID               int64      `json:"id"`
	SessionFK        *uuid.UUID `json:"sessionFk,omitempty"`
	AgentName        string     `json:"agentName"`
	Namespace        string     `json:"namespace"`
	Model            string     `json:"model,omitempty"`
	Provider         string     `json:"provider,omitempty"`
	PromptTokens     int64      `json:"promptTokens,omitempty"`
	CompletionTokens int64      `json:"completionTokens,omitempty"`
	TotalTokens      int64      `json:"totalTokens,omitempty"`
	RecordedAt       time.Time  `json:"recordedAt"`
}

// AgentMetric is an agent-level aggregate sample.
type AgentMetric struct {
	ID                 int64     `json:"id"`
	AgentName          string    `json:"agentName"`
	Namespace          string    `json:"namespace"`
	RecordedAt         time.Time `json:"recordedAt"`
	ActiveSessions     int32     `json:"activeSessions"`
	TotalMessages      int64     `json:"totalMessages,omitempty"`
	TotalTokens        int64     `json:"totalTokens,omitempty"`
	AvgContextPressure float64   `json:"avgContextPressure,omitempty"`
	ErrorCount         int32     `json:"errorCount,omitempty"`
	UptimeSeconds      int64     `json:"uptimeSeconds,omitempty"`
}

// TeamMessage is a team collaboration message (outbox).
type TeamMessage struct {
	ID          int64      `json:"id"`
	TeamName    string     `json:"teamName"`
	Namespace   string     `json:"namespace"`
	FromMember  string     `json:"fromMember"`
	ToMember    string     `json:"toMember,omitempty"` // empty = broadcast recipient already resolved
	Content     string     `json:"content"`
	Kind        string     `json:"kind,omitempty"`
	Nonce       string     `json:"nonce,omitempty"`
	Delivered   bool       `json:"delivered"`
	DeliveredAt *time.Time `json:"deliveredAt,omitempty"`
	Attempts    int32      `json:"attempts"`
	CreatedAt   time.Time  `json:"createdAt"`
}

// TeamTask is a dynamic team work item.
type TeamTask struct {
	ID          int64           `json:"id"`
	TaskID      string          `json:"taskId"` // logical id exposed to callers (e.g. "task-1")
	TeamName    string          `json:"teamName"`
	Namespace   string          `json:"namespace"`
	Subject     string          `json:"subject"`
	Description string          `json:"description,omitempty"`
	State       string          `json:"state"`
	Owner       string          `json:"owner,omitempty"`
	BlockedBy   json.RawMessage `json:"blockedBy,omitempty"`
	Result      string          `json:"result,omitempty"`
	Version     int64           `json:"version"`
	CreatedAt   time.Time       `json:"createdAt"`
	UpdatedAt   time.Time       `json:"updatedAt"`
	CompletedAt *time.Time      `json:"completedAt,omitempty"`
}

// TeamTaskHistory is an audit row for task state transitions.
type TeamTaskHistory struct {
	ID             int64     `json:"id"`
	TaskFK         int64     `json:"taskFk"`
	TeamName       string    `json:"teamName"`
	Namespace      string    `json:"namespace"`
	FromState      string    `json:"fromState,omitempty"`
	ToState        string    `json:"toState"`
	Owner          string    `json:"owner,omitempty"`
	TransitionedAt time.Time `json:"transitionedAt"`
}

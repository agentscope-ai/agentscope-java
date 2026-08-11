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
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

// Session phases (operational state machine).
//
//	active      — turn/inference in progress; hard-bound to instance; no compress
//	idle        — turn finished; soft affinity on instanceRef; compress allowed
//	compressing — compress in flight on a bound instance; no new turn
//	archived    — History: operator archive, idle TTL, or DP stopped listing the session
//	terminated  — hard destroy only (explicit terminate / DELETE / team teardown / DP reports terminated); not restorable
const (
	SessionPhaseActive      = "active"
	SessionPhaseIdle        = "idle"
	SessionPhaseCompressing = "compressing"
	SessionPhaseArchived    = "archived"
	SessionPhaseTerminated  = "terminated"
)

// Team task states. Completed and Failed are terminal.
const (
	TaskStatePending    = "pending"
	TaskStateInProgress = "in_progress"
	TaskStateCompleted  = "completed"
	TaskStateFailed     = "failed"
)

// IsTaskTerminal reports whether a task state accepts no further transitions.
func IsTaskTerminal(state string) bool {
	return state == TaskStateCompleted || state == TaskStateFailed
}

// Team / member lifecycle phases (store-backed; CRD enums map 1:1).
const (
	TeamPhasePending   = "Pending"
	TeamPhaseRunning   = "Running"
	TeamPhaseIdle      = "Idle" // alive; all active members idle (not Completed)
	TeamPhaseCompleted = "Completed"
	TeamPhaseFailed    = "Failed"

	MemberPhaseJoining  = "Joining"
	MemberPhaseWorking  = "Working"
	MemberPhaseIdle     = "Idle"
	MemberPhaseLost     = "Lost"
	MemberPhaseFailed   = "Failed"
	MemberPhaseShutdown = "Shutdown"

	MemberOriginStatic  = "static"
	MemberOriginDynamic = "dynamic"

	MemberDeployManaged = "managed"
	MemberDeployBYO     = "byo"

	// PlanStatusPending marks a submitted member plan awaiting lead review.
	PlanStatusPending = "pending"
	// PlanStatusApproved marks a member plan the lead accepted.
	PlanStatusApproved = "approved"
	// PlanStatusRejected marks a member plan the lead sent back.
	PlanStatusRejected = "rejected"
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
	// Busy is derived from phase when reported by modern data planes
	// (busy := phase == "active"). Kept for backward compatibility; prefer Phase.
	// nil means the data plane did not report busy (unknown).
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

// SessionUsage is a per-session token aggregate for TopSessionsByTokens.
type SessionUsage struct {
	SessionFK   uuid.UUID `json:"sessionFk"`
	SessionID   string    `json:"sessionId"`
	AgentName   string    `json:"agentName"`
	Namespace   string    `json:"namespace"`
	Phase       string    `json:"phase,omitempty"`
	TotalTokens int64     `json:"totalTokens"`
}

// SessionDuration ranks active sessions by current running turn elapsed.
type SessionDuration struct {
	SessionFK  uuid.UUID  `json:"sessionFk"`
	SessionID  string     `json:"sessionId"`
	AgentName  string     `json:"agentName"`
	Namespace  string     `json:"namespace"`
	Phase      string     `json:"phase,omitempty"`
	DurationMs int64      `json:"durationMs"`
	StartedAt  *time.Time `json:"startedAt,omitempty"`
	EndedAt    *time.Time `json:"endedAt,omitempty"`
	TurnIndex  int        `json:"turnIndex,omitempty"`
}

// Session turn status values.
const (
	TurnStatusRunning   = "running"
	TurnStatusCompleted = "completed"
	TurnStatusAborted   = "aborted"
	TurnStatusFailed    = "failed"
)

// SessionTurn is one inference cycle within a session (user request → response).
type SessionTurn struct {
	ID               uuid.UUID  `json:"id"`
	SessionFK        uuid.UUID  `json:"sessionFk"`
	TurnIndex        int        `json:"turnIndex"`
	Status           string     `json:"status"`
	StartedAt        time.Time  `json:"startedAt"`
	EndedAt          *time.Time `json:"endedAt,omitempty"`
	DurationMs       int64      `json:"durationMs,omitempty"`
	UserPreview      string     `json:"userPreview,omitempty"`
	PromptTokens     int64      `json:"promptTokens,omitempty"`
	CompletionTokens int64      `json:"completionTokens,omitempty"`
	CreatedAt        time.Time  `json:"createdAt"`
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

// SessionTranscriptIndex is a narrow one-row-per-session aggregate for Operate
// list/sort without scanning session_snapshots history or replaying events.
// Write-time maintenance uses DP Level-1 snapshot fields (messageCount /
// tokenUsage), not event recomputation.
type SessionTranscriptIndex struct {
	SessionFK        uuid.UUID `json:"sessionFk"`
	EntryCount       int32     `json:"entryCount"`
	PromptTokens     int64     `json:"promptTokens"`
	CompletionTokens int64     `json:"completionTokens"`
	ObjectPrefix     string    `json:"objectPrefix,omitempty"`
	UpdatedAt        time.Time `json:"updatedAt"`
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

// Team is the store-backed team runtime resource (authoritative; CRD is optional projection).
type Team struct {
	ID         int64           `json:"id"`
	Name       string          `json:"name"`
	Namespace  string          `json:"namespace"`
	Objective  string          `json:"objective"`
	Phase      string          `json:"phase"`
	LeadRef    string          `json:"leadRef"` // registry agentName of the lead
	LeadPrompt string          `json:"leadPrompt,omitempty"`
	Config     json.RawMessage `json:"config,omitempty"` // TeamConfig JSON
	SpecExtra  json.RawMessage `json:"specExtra,omitempty"` // dynamicMembers/sharedContext/recovery/lifecycle
	StartedAt  *time.Time      `json:"startedAt,omitempty"`
	CreatedAt  time.Time       `json:"createdAt"`
	UpdatedAt  time.Time       `json:"updatedAt"`
}

// TeamMember is one role slot on a team (lead or worker).
type TeamMember struct {
	ID               int64      `json:"id"`
	TeamName         string     `json:"teamName"`
	Namespace        string     `json:"namespace"`
	MemberName       string     `json:"memberName"` // "lead" for the lead role
	AgentRef         string     `json:"agentRef"`   // registry agentName
	Prompt           string     `json:"prompt,omitempty"`
	PlanApproval     bool       `json:"planApproval,omitempty"`
	PlanText         string     `json:"planText,omitempty"`
	PlanStatus       string     `json:"planStatus,omitempty"` // pending | approved | rejected | ""
	Origin           string     `json:"origin,omitempty"`     // static | dynamic
	DeployMode       string     `json:"deployMode,omitempty"` // managed | byo
	ManagedAgentID   string     `json:"managedAgentId,omitempty"`
	OwnerID          string     `json:"ownerId,omitempty"` // product owner for managed find-or-create
	Phase            string     `json:"phase"`
	SessionID        string     `json:"sessionId,omitempty"`
	ManagedSessionID string     `json:"managedSessionId,omitempty"`
	InstanceRef      string     `json:"instanceRef,omitempty"`
	CurrentTask      string     `json:"currentTask,omitempty"`
	RestartCount     int32      `json:"restartCount,omitempty"`
	LastRestartAt    *time.Time `json:"lastRestartAt,omitempty"`
	LastRestartReason string    `json:"lastRestartReason,omitempty"`
	CreatedAt        time.Time  `json:"createdAt"`
	UpdatedAt        time.Time  `json:"updatedAt"`
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

// NamespacePathSeparator joins BaseStore namespace segments for Postgres TEXT
// storage. NUL (\x00) cannot be stored in Postgres TEXT; unit separator is
// valid and unused in normal path components.
const NamespacePathSeparator = "\x1f"

// JoinNamespacePath joins namespace segments with NamespacePathSeparator.
func JoinNamespacePath(segments []string) string {
	if len(segments) == 0 {
		return ""
	}
	out := segments[0]
	for i := 1; i < len(segments); i++ {
		out += NamespacePathSeparator + segments[i]
	}
	return out
}

// SplitNamespacePath splits a ns_path produced by JoinNamespacePath.
func SplitNamespacePath(nsPath string) []string {
	if nsPath == "" {
		return nil
	}
	parts := make([]string, 0)
	start := 0
	for i := 0; i < len(nsPath); i++ {
		if nsPath[i] == NamespacePathSeparator[0] {
			parts = append(parts, nsPath[start:i])
			start = i + 1
		}
	}
	parts = append(parts, nsPath[start:])
	return parts
}

// KVItem is one hosted BaseStore entry.
type KVItem struct {
	Key     string          `json:"key"`
	Value   json.RawMessage `json:"value"`
	Version int64           `json:"version"`
	NsPath  string          `json:"nsPath,omitempty"`
}

// Lock is a hosted TTL lease for SandboxExecutionGuard.
type Lock struct {
	Name         string    `json:"name"`
	OwnerToken   string    `json:"ownerToken"`
	FencingToken int64     `json:"fencingToken"`
	Holder       string    `json:"holder,omitempty"`
	ExpiresAt    time.Time `json:"expiresAt"`
}

// Snapshot storage modes.
const (
	SnapshotModeInline   = "inline"
	SnapshotModeExternal = "external"
)

// SnapshotMeta describes a hosted sandbox snapshot (without payload bytes).
type SnapshotMeta struct {
	SnapshotID  string    `json:"snapshotId"`
	SizeBytes   int64     `json:"sizeBytes"`
	StorageMode string    `json:"storageMode"`
	ExternalURL string    `json:"externalUrl,omitempty"`
	CreatedAt   time.Time `json:"createdAt"`
	AccessedAt  time.Time `json:"accessedAt"`
}

// BusEntry is one queue or log entry in the hosted MessageBus.
type BusEntry struct {
	EntryID string          `json:"entryId"`
	Payload json.RawMessage `json:"payload"`
}

// Async tool statuses (mirror Java AsyncToolRecord).
const (
	AsyncToolRunning   = "RUNNING"
	AsyncToolCompleted = "COMPLETED"
	AsyncToolFailed    = "FAILED"
	AsyncToolTimeout   = "TIMEOUT"
)

// Hosted subagent task statuses (mirror Java TaskStatus).
const (
	DPTaskStatusPending   = "PENDING"
	DPTaskStatusRunning   = "RUNNING"
	DPTaskStatusCompleted = "COMPLETED"
	DPTaskStatusFailed    = "FAILED"
	DPTaskStatusCancelled = "CANCELLED"
)

// IsTerminalTaskStatus reports whether status is a final task state.
func IsTerminalTaskStatus(status string) bool {
	switch status {
	case DPTaskStatusCompleted, DPTaskStatusFailed, DPTaskStatusCancelled:
		return true
	default:
		return false
	}
}

// DPTask is a hosted subagent background task record.
type DPTask struct {
	Tenant           string          `json:"tenant,omitempty"`
	ParentAgentID    string          `json:"parentAgentId"`
	ParentSessionID  string          `json:"parentSessionId"`
	TaskID           string          `json:"taskId"`
	SubAgentID       string          `json:"subAgentId,omitempty"`
	SubSessionID     string          `json:"subSessionId,omitempty"`
	Status           string          `json:"status"`
	Terminal         bool            `json:"terminal"`
	Result           string          `json:"result,omitempty"`
	ErrorMessage     string          `json:"errorMessage,omitempty"`
	CancelRequested  bool            `json:"cancelRequested"`
	TransportType    string          `json:"transportType,omitempty"`
	RemoteBaseURL    string          `json:"remoteBaseUrl,omitempty"`
	RemoteHeaders    json.RawMessage `json:"remoteHeaders,omitempty"`
	UserID           string          `json:"userId,omitempty"`
	CreatedAt        time.Time       `json:"createdAt"`
	LastCheckedAt    *time.Time      `json:"lastCheckedAt,omitempty"`
	LastUpdatedAt    time.Time       `json:"lastUpdatedAt"`
	DeliveredAt      *time.Time      `json:"deliveredAt,omitempty"`
	Version          int64           `json:"version"`
}

// DPTaskRef identifies a task for heartbeat batch updates.
type DPTaskRef struct {
	ParentSessionID string `json:"parentSessionId"`
	TaskID          string `json:"taskId"`
}

// AsyncToolRecord tracks an async tool execution.
type AsyncToolRecord struct {
	ID         string    `json:"id"`
	Tenant     string    `json:"tenant,omitempty"`
	SessionID  string    `json:"sessionId"`
	ToolName   string    `json:"toolName,omitempty"`
	ToolCallID string    `json:"toolCallId,omitempty"`
	Status     string    `json:"status"`
	Result     string    `json:"result,omitempty"`
	Error      string    `json:"error,omitempty"`
	CreatedAt  time.Time `json:"createdAt"`
	UpdatedAt  time.Time `json:"updatedAt"`
}

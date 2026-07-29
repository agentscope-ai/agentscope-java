package prober

import "encoding/json"

// DataPlaneInfo holds metadata returned by GET /agentscope/info.
type DataPlaneInfo struct {
	Name            string            `json:"name"`
	DisplayName     string            `json:"displayName,omitempty"`
	Description     string            `json:"description,omitempty"`
	Runtime         string            `json:"runtime"`
	Version         string            `json:"version,omitempty"`
	SDKVersion      string            `json:"sdkVersion,omitempty"`
	ContractLevel   int32             `json:"contractLevel"`
	Capabilities    []string          `json:"capabilities,omitempty"`
	Port            int32             `json:"port,omitempty"`
	SessionAffinity string            `json:"sessionAffinity,omitempty"`
	AgentConfig     *ProbeAgentConfig `json:"agentConfig,omitempty"`
}

// ProbeAgentConfig holds agent configuration reported by the data plane.
type ProbeAgentConfig struct {
	ModelProvider string   `json:"modelProvider,omitempty"`
	Model         string   `json:"model,omitempty"`
	Tools         []string `json:"tools,omitempty"`
	MaxTurns      int32    `json:"maxTurns,omitempty"`
}

// SessionSnapshot represents a session as reported by the data plane.
type SessionSnapshot struct {
	ID              string       `json:"id"`
	Phase           string       `json:"phase"`
	StartedAt       string       `json:"startedAt,omitempty"`
	LastActiveAt    string       `json:"lastActiveAt,omitempty"`
	MessageCount    int32        `json:"messageCount,omitempty"`
	TokenUsage      *TokenUsage  `json:"tokenUsage,omitempty"`
	ContextPressure float64      `json:"contextPressure,omitempty"`
	TaskSummary     *TaskSummary `json:"taskSummary,omitempty"`

	// Level-1 extensions (see sdk-design.md §3.1).
	Framework             string `json:"framework,omitempty"`
	FrameworkVersion      string `json:"frameworkVersion,omitempty"`
	ContextHash           string `json:"contextHash,omitempty"`
	IsCompacted           bool   `json:"isCompacted,omitempty"`
	EffectiveMessageCount int32  `json:"effectiveMessageCount,omitempty"`
}

// TokenUsage tracks token counts.
type TokenUsage struct {
	PromptTokens     int64 `json:"promptTokens"`
	CompletionTokens int64 `json:"completionTokens"`
}

// TaskSummary holds aggregate task counts.
type TaskSummary struct {
	Total      int32 `json:"total"`
	Pending    int32 `json:"pending"`
	InProgress int32 `json:"inProgress"`
	Completed  int32 `json:"completed"`
}

// SessionState holds detailed session state returned by GET /agentscope/sessions/{id}/state.
type SessionState struct {
	SessionID       string               `json:"sessionId"`
	Summary         string               `json:"summary,omitempty"`
	CurrentIter     int32                `json:"currentIter,omitempty"`
	ContextPressure *ContextPressureInfo `json:"contextPressure,omitempty"`
	Tasks           []TaskInfo           `json:"tasks,omitempty"`
}

// ContextPressureInfo holds context window pressure metrics.
type ContextPressureInfo struct {
	UsedTokens int64   `json:"usedTokens"`
	MaxTokens  int64   `json:"maxTokens"`
	Ratio      float64 `json:"ratio"`
}

// TaskInfo represents a task within a session state response.
type TaskInfo struct {
	ID      string `json:"id"`
	Subject string `json:"subject"`
	State   string `json:"state"`
}

// ═══════════ Level 3 / Level 4 contract types (sdk-design.md §4) ═══════════

// ContextMessage is one effective-context message returned by
// GET /agentscope/sessions/{id}/context.
type ContextMessage struct {
	Role         string `json:"role"`
	Content      string `json:"content"`
	IsCompaction bool   `json:"isCompaction,omitempty"`
}

// ToolInfo describes one tool currently available to the agent.
type ToolInfo struct {
	Name        string          `json:"name"`
	Description string          `json:"description,omitempty"`
	Parameters  json.RawMessage `json:"parameters,omitempty"`
}

// ContextSnapshot is the Level-4 effective context returned by
// GET /agentscope/sessions/{id}/context (mirrors the ASDP ContextReport).
type ContextSnapshot struct {
	SessionID            string          `json:"sessionId"`
	CapturedAt           string          `json:"capturedAt,omitempty"`
	ContextHash          string          `json:"contextHash"`
	SystemPrompt         string          `json:"systemPrompt,omitempty"`
	Messages             []ContextMessage `json:"messages"`
	Tools                []ToolInfo      `json:"tools,omitempty"`
	IsCompacted          bool            `json:"isCompacted,omitempty"`
	CompactionSummary    string          `json:"compactionSummary,omitempty"`
	OriginalMessageCount int32           `json:"originalMessageCount,omitempty"`
	CompactedAt          string          `json:"compactedAt,omitempty"`
	TotalTokens          int32           `json:"totalTokens,omitempty"`
	MaxTokens            int32           `json:"maxTokens,omitempty"`
	Framework            string          `json:"framework,omitempty"`
	FrameworkState       json.RawMessage `json:"frameworkState,omitempty"`
}

// MessageItem is one full-content history entry (Level 3).
type MessageItem struct {
	Seq        int32  `json:"seq"`
	Role       string `json:"role"`
	Content    string `json:"content"`
	ToolName   string `json:"toolName,omitempty"`
	ToolInput  json.RawMessage `json:"toolInput,omitempty"`
	ToolOutput string `json:"toolOutput,omitempty"`
	OccurredAt string `json:"occurredAt,omitempty"`
}

// MessagePage is a paginated Level-3 full-history response from
// GET /agentscope/sessions/{id}/messages.
type MessagePage struct {
	SessionID string        `json:"sessionId"`
	Offset    int           `json:"offset"`
	Limit     int           `json:"limit"`
	Total     int           `json:"total"`
	Messages  []MessageItem `json:"messages"`
}

// SubagentInfo describes one subagent known to the data plane instance.
type SubagentInfo struct {
	Name          string   `json:"name"`
	Description   string   `json:"description,omitempty"`
	Tools         []string `json:"tools,omitempty"`
	WorkspaceMode string   `json:"workspaceMode,omitempty"`
	URL           string   `json:"url,omitempty"`
	InvokeCount   int64    `json:"invokeCount,omitempty"`
	LastInvokedAt string   `json:"lastInvokedAt,omitempty"`
}

// WorkspaceInfo describes one workspace known to the data plane instance.
type WorkspaceInfo struct {
	Path      string `json:"path"`
	Mode      string `json:"mode,omitempty"`
	SizeBytes int64  `json:"sizeBytes,omitempty"`
	OwnerRef  string `json:"ownerRef,omitempty"`
}

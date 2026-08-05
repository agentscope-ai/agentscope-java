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
// Tools may be a string array (legacy) or [{name,description},...] — see ToolNames().
type ProbeAgentConfig struct {
	Name          string          `json:"name,omitempty"`
	Description   string          `json:"description,omitempty"`
	SystemPrompt  string          `json:"systemPrompt,omitempty"`
	ModelProvider string          `json:"modelProvider,omitempty"`
	Model         string          `json:"model,omitempty"`
	Tools         json.RawMessage `json:"tools,omitempty"`
	MaxTurns      int32           `json:"maxTurns,omitempty"`
	MaxIters      int32           `json:"maxIters,omitempty"`
	Sources       []string        `json:"sources,omitempty"`
	Skills        json.RawMessage `json:"skills,omitempty"`
	Subagents     json.RawMessage `json:"subagents,omitempty"`
	Workspace     json.RawMessage `json:"workspace,omitempty"`
	ToolsPolicy   json.RawMessage `json:"toolsPolicy,omitempty"`
}

// ToolNames extracts tool name strings from Tools whether encoded as []string or [{name}].
func (c *ProbeAgentConfig) ToolNames() []string {
	if c == nil || len(c.Tools) == 0 {
		return nil
	}
	var names []string
	if err := json.Unmarshal(c.Tools, &names); err == nil {
		return names
	}
	var objs []struct {
		Name string `json:"name"`
	}
	if err := json.Unmarshal(c.Tools, &objs); err == nil {
		out := make([]string, 0, len(objs))
		for _, o := range objs {
			if o.Name != "" {
				out = append(out, o.Name)
			}
		}
		return out
	}
	return nil
}

// SessionSnapshot represents a session as reported by the data plane.
type SessionSnapshot struct {
	ID              string       `json:"id"`
	Phase           string       `json:"phase"`
	Busy            *bool        `json:"busy,omitempty"`
	Model           string       `json:"model,omitempty"`
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
	TotalTokens      int64 `json:"totalTokens,omitempty"`
	MaxTokens        int64 `json:"maxTokens,omitempty"`
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
	ID              string               `json:"id,omitempty"` // frozen schema alias for sessionId
	Phase           string               `json:"phase,omitempty"`
	Busy            *bool                `json:"busy,omitempty"`
	Model           string               `json:"model,omitempty"`
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

// TaskInfo represents a task within a session (todolist or state summary).
type TaskInfo struct {
	ID            string          `json:"id"`
	Subject       string          `json:"subject"`
	State         string          `json:"state"`
	Description   string          `json:"description,omitempty"`
	Owner         string          `json:"owner,omitempty"`
	BlockedBy     json.RawMessage `json:"blockedBy,omitempty"`
	UpdatedAt     string          `json:"updatedAt,omitempty"`
	FrameworkMeta json.RawMessage `json:"frameworkMeta,omitempty"`
}

// SubagentTaskInfo is a background subagent task (not todolist).
type SubagentTaskInfo struct {
	ID            string `json:"id,omitempty"`
	TaskID        string `json:"taskId,omitempty"`
	SubagentID    string `json:"subagentId,omitempty"`
	Status        string `json:"status,omitempty"`
	Subject       string `json:"subject,omitempty"`
	CreatedAt     string `json:"createdAt,omitempty"`
	LastCheckedAt string `json:"lastCheckedAt,omitempty"`
	Completed     bool   `json:"completed,omitempty"`
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
	Model                string          `json:"model,omitempty"`
	FrameworkState       json.RawMessage `json:"frameworkState,omitempty"`
}

// MessageItem is one full-content history entry (Level 3).
type MessageItem struct {
	Seq          int32           `json:"seq"`
	Role         string          `json:"role"`
	Content      string          `json:"content"`
	ToolName     string          `json:"toolName,omitempty"`
	ToolCallID   string          `json:"toolCallId,omitempty"`
	ToolInput    json.RawMessage `json:"toolInput,omitempty"`
	ToolOutput   string          `json:"toolOutput,omitempty"`
	Truncated    bool            `json:"truncated,omitempty"`
	OriginalSize int             `json:"originalSize,omitempty"`
	OccurredAt   string          `json:"occurredAt,omitempty"`
}

// MessagePage is a paginated Level-3 full-history response from
// GET /agentscope/sessions/{id}/messages.
type MessagePage struct {
	SessionID string        `json:"sessionId"`
	Offset    int           `json:"offset"`
	Limit     int           `json:"limit"`
	Total     int           `json:"total"`
	Messages  []MessageItem `json:"messages"`
	// Source is "transcript" when served from CP transcript storage, or
	// "dataplane" when proxied to a live instance.
	Source string `json:"source,omitempty"`
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

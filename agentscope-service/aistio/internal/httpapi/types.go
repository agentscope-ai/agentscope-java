package httpapi

import "encoding/json"

// PushAgentRequest is the request body for POST /api/v1/agents/{name}/push.
type PushAgentRequest struct {
	DisplayName   string              `json:"displayName,omitempty"`
	Description   string              `json:"description,omitempty"`
	Runtime       string              `json:"runtime,omitempty"`
	SystemPrompt  string              `json:"systemPrompt,omitempty"`
	Model         *ModelSpec          `json:"model,omitempty"`
	Tools         *ToolsSpec          `json:"tools,omitempty"`
	Skills        []SkillSpec         `json:"skills,omitempty"`
	Subagents     []SubagentEntry     `json:"subagents,omitempty"`
	TeamTemplates []TeamTemplateEntry `json:"teamTemplates,omitempty"`
	Deployment    *DeploymentSpec     `json:"deployment,omitempty"`
	Extras        map[string]string   `json:"extras,omitempty"`

	// Image, when set, makes push create a BYO (image) agent instead of a
	// Declarative one. Mutually exclusive with model/systemPrompt semantics.
	Image   string   `json:"image,omitempty"`
	Command []string `json:"command,omitempty"`
	Args    []string `json:"args,omitempty"`
}

// ModelSpec defines model configuration in push request.
type ModelSpec struct {
	Provider string            `json:"provider"`
	ModelID  string            `json:"modelId"`
	APIKey   string            `json:"apiKey,omitempty"`
	Options  map[string]string `json:"options,omitempty"`
}

// ToolsSpec defines tools configuration.
type ToolsSpec struct {
	Tools           []ToolEntry     `json:"tools,omitempty"`
	InterruptConfig map[string]bool `json:"interruptConfig,omitempty"`
}

// ToolEntry represents a single tool.
type ToolEntry struct {
	Name            string            `json:"name"`
	MCPServerURL    string            `json:"mcpServerUrl,omitempty"`
	MCPServerName   string            `json:"mcpServerName,omitempty"`
	Protocol        string            `json:"protocol,omitempty"`        // STREAMABLE_HTTP or SSE
	Timeout         string            `json:"timeout,omitempty"`         // e.g. "30s"
	RequireApproval bool              `json:"requireApproval,omitempty"` // per-tool approval
	HeadersFrom     []HeaderFromEntry `json:"headersFrom,omitempty"`     // auth headers sourced from Secrets
}

// HeaderFromEntry references an HTTP header value stored in a Secret, used when
// building the MCPServer so tool discovery can authenticate to the server.
type HeaderFromEntry struct {
	Kind   string `json:"kind,omitempty"`
	Name   string `json:"name"`
	Key    string `json:"key"`
	Header string `json:"header"`
}

// SubagentEntry defines an in-process sub-agent in a push request.
type SubagentEntry struct {
	Name          string   `json:"name"`
	Description   string   `json:"description,omitempty"`
	Model         string   `json:"model,omitempty"`
	Instructions  string   `json:"instructions,omitempty"`
	Tools         []string `json:"tools,omitempty"`
	Steps         int32    `json:"steps,omitempty"`
	WorkspaceMode string   `json:"workspaceMode,omitempty"`
	URL           string   `json:"url,omitempty"`
}

// TeamTemplateEntry defines a team template in a push request.
type TeamTemplateEntry struct {
	Name        string                    `json:"name"`
	Description string                    `json:"description,omitempty"`
	Members     []TeamTemplateMemberEntry `json:"members,omitempty"`
}

// TeamTemplateMemberEntry defines a member within a team template.
type TeamTemplateMemberEntry struct {
	Role     string `json:"role"`
	AgentRef string `json:"agentRef"`
	Prompt   string `json:"prompt,omitempty"`
}

// SkillSpec defines a skill.
type SkillSpec struct {
	Type         string `json:"type"` // inline | oci
	Name         string `json:"name,omitempty"`
	Description  string `json:"description,omitempty"`
	Instructions string `json:"instructions,omitempty"`
	Ref          string `json:"ref,omitempty"`
}

// DeploymentSpec defines deployment settings.
type DeploymentSpec struct {
	Replicas  int32         `json:"replicas,omitempty"`
	Resources *ResourceSpec `json:"resources,omitempty"`
}

// ResourceSpec defines resource requests/limits.
type ResourceSpec struct {
	Requests map[string]string `json:"requests,omitempty"`
	Limits   map[string]string `json:"limits,omitempty"`
}

// PushAgentResponse is returned after a successful push.
type PushAgentResponse struct {
	Name             string            `json:"name"`
	Namespace        string            `json:"namespace"`
	Type             string            `json:"type"`
	Revision         string            `json:"revision"`
	CreatedAt        string            `json:"createdAt,omitempty"`
	UpdatedAt        string            `json:"updatedAt,omitempty"`
	Status           *AgentStatusBrief `json:"status,omitempty"`
	CreatedResources []CreatedResource `json:"createdResources,omitempty"`
}

// AgentStatusBrief is a summary of agent status.
type AgentStatusBrief struct {
	Phase    string      `json:"phase"`
	Replicas *ReplicaRef `json:"replicas,omitempty"`
}

// ReplicaRef holds replica counts.
type ReplicaRef struct {
	Desired int32 `json:"desired"`
	Ready   int32 `json:"ready"`
}

// CreatedResource represents a K8s resource created by the push operation.
type CreatedResource struct {
	Kind string `json:"kind"`
	Name string `json:"name"`
}

// RevisionEntry is one entry in an agent's revision history.
type RevisionEntry struct {
	Revision     string          `json:"revision"`
	CreatedAt    string          `json:"createdAt"`
	Message      string          `json:"message,omitempty"`
	SpecSnapshot json.RawMessage `json:"specSnapshot,omitempty"`
}

// RevisionSummary is a lightweight view of a revision without the spec snapshot.
type RevisionSummary struct {
	Revision  string `json:"revision"`
	CreatedAt string `json:"createdAt"`
	Message   string `json:"message,omitempty"`
}

// RollbackRequest is the request body for POST /api/v1/agents/{name}/rollback.
type RollbackRequest struct {
	Revision string `json:"revision" binding:"required"`
}

// ListMetadata carries pagination state in list responses.
type ListMetadata struct {
	Continue string `json:"continue,omitempty"`
}

// RevisionListResponse wraps an agent's revision history.
type RevisionListResponse struct {
	Revisions []RevisionSummary `json:"revisions"`
}

// AgentListResponse wraps a list of agents.
type AgentListResponse struct {
	Items    []AgentSummary `json:"items"`
	Metadata *ListMetadata  `json:"metadata,omitempty"`
}

// AgentSummary is a brief representation of an agent.
type AgentSummary struct {
	Name           string `json:"name"`
	Namespace      string `json:"namespace"`
	Type           string `json:"type"`
	Runtime        string `json:"runtime"`
	DisplayName    string `json:"displayName,omitempty"`
	Replicas       string `json:"replicas,omitempty"`
	ActiveSessions int32  `json:"activeSessions"`
	Revision       string `json:"revision,omitempty"`
}

// SessionListResponse wraps a list of sessions.
type SessionListResponse struct {
	Sessions []SessionSummary `json:"sessions"`
	Metadata *ListMetadata    `json:"metadata,omitempty"`
}

// SessionSummary represents a session.
type SessionSummary struct {
	ID           string `json:"id"`
	AgentName    string `json:"agentName"`
	Phase        string `json:"phase"`
	StartedAt    string `json:"startedAt,omitempty"`
	LastActiveAt string `json:"lastActiveAt,omitempty"`
	MessageCount int32  `json:"messageCount"`
}

// TeamCreateRequest is the request body for POST /api/v1/teams.
type TeamCreateRequest struct {
	Name      string              `json:"name"`
	Namespace string              `json:"namespace,omitempty"`
	Objective string              `json:"objective"`
	Lead      TeamLeadRequest     `json:"lead"`
	Members   []TeamMemberRequest `json:"members,omitempty"`
}

// TeamLeadRequest defines team lead in create request.
type TeamLeadRequest struct {
	AgentRef string `json:"agentRef"`
	Prompt   string `json:"prompt,omitempty"`
}

// TeamMemberRequest defines a team member.
type TeamMemberRequest struct {
	Name     string `json:"name"`
	AgentRef string `json:"agentRef"`
	Prompt   string `json:"prompt,omitempty"`
}

// TeamTaskRequest is the request body for creating a task.
type TeamTaskRequest struct {
	Subject     string   `json:"subject"`
	Description string   `json:"description,omitempty"`
	BlockedBy   []string `json:"blockedBy,omitempty"`
}

// TeamTaskClaimRequest is the body for claiming a task.
type TeamTaskClaimRequest struct {
	ClaimedBy       string `json:"claimedBy"`
	ResourceVersion string `json:"resourceVersion,omitempty"`
}

// TeamMessageRequest is the body for sending a message.
type TeamMessageRequest struct {
	From    string `json:"from"`
	To      string `json:"to"`
	Content string `json:"content"`
}

// ErrorResponse represents an error.
type ErrorResponse struct {
	Error   string `json:"error"`
	Message string `json:"message,omitempty"`
}

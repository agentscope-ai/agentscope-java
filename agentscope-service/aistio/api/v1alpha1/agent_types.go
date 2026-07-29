package v1alpha1

import (
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// AgentType defines the management mode of an Agent.
// +kubebuilder:validation:Enum=Declarative;BYO
type AgentType string

const (
	AgentTypeDeclarative AgentType = "Declarative"
	AgentTypeBYO         AgentType = "BYO"
)

// ManagementMode describes how the control plane manages this agent.
type ManagementMode string

const (
	ManagementModeCPManaged ManagementMode = "CP-Managed"
	ManagementModeAdopted   ManagementMode = "Adopted"
)

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:shortName=ag
// +kubebuilder:printcolumn:name="Type",type=string,JSONPath=`.spec.type`
// +kubebuilder:printcolumn:name="Runtime",type=string,JSONPath=`.spec.runtime`
// +kubebuilder:printcolumn:name="Ready",type=string,JSONPath=`.status.conditions[?(@.type=="Ready")].status`
// +kubebuilder:printcolumn:name="Replicas",type=integer,JSONPath=`.status.replicas.ready`
// +kubebuilder:printcolumn:name="Sessions",type=integer,JSONPath=`.status.activeSessions`
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`

// Agent represents a managed AI agent in the cluster.
type Agent struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   AgentSpec   `json:"spec,omitempty"`
	Status AgentStatus `json:"status,omitempty"`
}

// +kubebuilder:object:root=true

// AgentList contains a list of Agent.
type AgentList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []Agent `json:"items"`
}

// AgentSpec defines the desired state of an Agent.
// +kubebuilder:validation:XValidation:rule="self.type != 'Declarative' || (has(self.declarative) && !has(self.byo))",message="Declarative agents must set spec.declarative and must not set spec.byo"
// +kubebuilder:validation:XValidation:rule="self.type != 'BYO' || (has(self.byo) && !has(self.declarative))",message="BYO agents must set spec.byo and must not set spec.declarative"
type AgentSpec struct {
	// Type specifies the management mode: Declarative or BYO.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:Enum=Declarative;BYO
	Type AgentType `json:"type"`

	// Runtime identifies the data plane runtime (e.g. agentscope-java, agentscope-go, custom).
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Runtime string `json:"runtime"`

	// +kubebuilder:validation:MaxLength=256
	DisplayName string `json:"displayName,omitempty"`
	// +kubebuilder:validation:MaxLength=1024
	Description string `json:"description,omitempty"`

	// Declarative mode configuration (mutually exclusive with BYO).
	// +optional
	Declarative *DeclarativeSpec `json:"declarative,omitempty"`

	// BYO mode configuration (mutually exclusive with Declarative).
	// +optional
	BYO *BYOSpec `json:"byo,omitempty"`

	// +optional
	Sandbox *SandboxSpec `json:"sandbox,omitempty"`
	// +optional
	AllowedNamespaces *AllowedNamespaces `json:"allowedNamespaces,omitempty"`
}

// DeclarativeSpec defines configuration for Declarative mode agents.
type DeclarativeSpec struct {
	AgentConfig AgentConfig   `json:"agentConfig"`
	Tools       []ToolBinding `json:"tools,omitempty"`
	// +optional
	Skills *SkillsSpec `json:"skills,omitempty"`
	// +optional
	Subagents []SubagentSpec `json:"subagents,omitempty"`
	// +optional
	TeamTemplates []TeamTemplateRef `json:"teamTemplates,omitempty"`

	// +kubebuilder:validation:Minimum=1
	// +kubebuilder:validation:Maximum=100
	// +kubebuilder:default=1
	// +optional
	Replicas *int32 `json:"replicas,omitempty"`
	// +optional
	Resources *ResourceRequirements `json:"resources,omitempty"`
	// +optional
	Env []EnvVar `json:"env,omitempty"`
	// +optional
	Advanced *AdvancedConfig `json:"advanced,omitempty"`
}

// AgentConfig holds the agent's runtime configuration.
type AgentConfig struct {
	// +kubebuilder:validation:MaxLength=65536
	SystemMessage string `json:"systemMessage,omitempty"`
	// +optional
	SystemMessageFrom *ConfigMapKeyRef `json:"systemMessageFrom,omitempty"`
	// +optional
	ModelConfigRef string `json:"modelConfigRef,omitempty"`
	// +kubebuilder:default=true
	Stream bool `json:"stream,omitempty"`
	// +kubebuilder:validation:Minimum=1
	// +kubebuilder:validation:Maximum=1000
	// +kubebuilder:default=50
	MaxTurns int32 `json:"maxTurns,omitempty"`
	// +kubebuilder:validation:Enum=none;instance
	SessionAffinity string `json:"sessionAffinity,omitempty"`
}

// ToolBinding defines a tool attached to an agent.
type ToolBinding struct {
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:Enum=McpServer;Skill
	Type string `json:"type"`
	// +optional
	MCPServer *MCPServerRef `json:"mcpServer,omitempty"`
	// +optional
	Skill *SkillRef `json:"skill,omitempty"`
}

// MCPServerRef references an MCPServer resource.
type MCPServerRef struct {
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Name            string   `json:"name"`
	ToolNames       []string `json:"toolNames,omitempty"`
	RequireApproval []string `json:"requireApproval,omitempty"`
}

// SkillRef references a skill.
type SkillRef struct {
	// +kubebuilder:validation:Required
	Name    string `json:"name"`
	Version string `json:"version,omitempty"`
}

// SkillsSpec defines skill references.
type SkillsSpec struct {
	Refs     []string       `json:"refs,omitempty"`
	Bindings []SkillBinding `json:"bindings,omitempty"`
}

// SkillBinding defines a skill attached to an agent with inline or OCI configuration.
type SkillBinding struct {
	Name         string `json:"name"`
	Description  string `json:"description,omitempty"`
	Instructions string `json:"instructions,omitempty"`
	Ref          string `json:"ref,omitempty"` // OCI reference
}

// SubagentSpec defines an in-process sub-agent.
type SubagentSpec struct {
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Name         string   `json:"name"`
	Description  string   `json:"description,omitempty"`
	Model        string   `json:"model,omitempty"`
	Instructions string   `json:"instructions,omitempty"`
	Tools        []string `json:"tools,omitempty"`
	// +kubebuilder:validation:Minimum=1
	Steps int32 `json:"steps,omitempty"`
	// +kubebuilder:validation:Enum=isolated;shared
	WorkspaceMode string `json:"workspaceMode,omitempty"`
	URL           string `json:"url,omitempty"`
}

// TeamTemplateRef defines a team template for cross-agent collaboration.
type TeamTemplateRef struct {
	// +kubebuilder:validation:Required
	Name           string               `json:"name"`
	Description    string               `json:"description,omitempty"`
	Members        []TeamTemplateMember `json:"members,omitempty"`
	DynamicMembers *DynamicMembersSpec  `json:"dynamicMembers,omitempty"`
	Config         *TeamConfig          `json:"config,omitempty"`
}

// TeamTemplateMember defines a member in a team template.
type TeamTemplateMember struct {
	Role     string `json:"role"`
	AgentRef string `json:"agentRef"`
	Prompt   string `json:"prompt,omitempty"`
}

// AdvancedConfig holds advanced agent configuration.
type AdvancedConfig struct {
	ContextStrategy *ContextStrategyConfig `json:"contextStrategy,omitempty"`
	ReactConfig     *ReactConfig           `json:"reactConfig,omitempty"`
	Labels          map[string]string      `json:"labels,omitempty"`
	NodeSelector    map[string]string      `json:"nodeSelector,omitempty"`
	// +optional
	Tolerations []corev1.Toleration `json:"tolerations,omitempty"`
}

// ContextStrategyConfig defines context management strategy.
type ContextStrategyConfig struct {
	// TriggerRatio is the context pressure ratio that triggers compression (0.0-1.0).
	// +kubebuilder:validation:Pattern=`^(0(\.\d+)?|1(\.0+)?)$`
	TriggerRatio string `json:"triggerRatio,omitempty"`
	// ReserveRatio is the ratio of context to reserve after compression (0.0-1.0).
	// +kubebuilder:validation:Pattern=`^(0(\.\d+)?|1(\.0+)?)$`
	ReserveRatio     string `json:"reserveRatio,omitempty"`
	CompressionModel string `json:"compressionModel,omitempty"`
}

// ReactConfig defines ReAct loop configuration.
type ReactConfig struct {
	// +kubebuilder:validation:Minimum=1
	// +kubebuilder:validation:Maximum=100
	MaxIters     int32 `json:"maxIters,omitempty"`
	StopOnReject bool  `json:"stopOnReject,omitempty"`
}

// BYOSpec defines configuration for BYO mode agents.
// +kubebuilder:validation:XValidation:rule="has(self.image) != has(self.workloadRef)",message="exactly one of spec.byo.image or spec.byo.workloadRef must be set"
type BYOSpec struct {
	// Image mode: control plane creates Deployment (mutually exclusive with WorkloadRef).
	// +optional
	Image   string   `json:"image,omitempty"`
	Command []string `json:"command,omitempty"`
	Args    []string `json:"args,omitempty"`

	// WorkloadRef mode: control plane adopts existing Deployment (mutually exclusive with Image).
	// +optional
	WorkloadRef *ObjectReference `json:"workloadRef,omitempty"`

	// +kubebuilder:validation:Minimum=1
	// +kubebuilder:validation:Maximum=100
	// +optional
	Replicas *int32 `json:"replicas,omitempty"`
	// +optional
	Resources *ResourceRequirements `json:"resources,omitempty"`
	// +optional
	Env []EnvVar `json:"env,omitempty"`
	// +kubebuilder:validation:Minimum=1
	// +kubebuilder:validation:Maximum=65535
	// +kubebuilder:default=8080
	AgentPort int32 `json:"agentPort,omitempty"`
	// +kubebuilder:default="/agentscope"
	ContractPath string `json:"contractPath,omitempty"`

	// +optional
	Overrides *BYOOverrides `json:"overrides,omitempty"`
	// +optional
	HealthProbe *HealthProbeRef `json:"healthProbe,omitempty"`
	// +optional
	Advanced *AdvancedConfig `json:"advanced,omitempty"`
}

// BYOOverrides defines additional configuration the control plane appends to BYO agents.
type BYOOverrides struct {
	Tools []ToolBinding `json:"tools,omitempty"`
}

// HealthProbeRef defines a custom health check probe.
type HealthProbeRef struct {
	HTTPGet *HTTPGetAction `json:"httpGet,omitempty"`
}

// HTTPGetAction describes an HTTP GET health check.
type HTTPGetAction struct {
	Path string `json:"path"`
	// +kubebuilder:validation:Minimum=1
	// +kubebuilder:validation:Maximum=65535
	Port int32 `json:"port"`
}

// SandboxSpec defines sandbox configuration for an agent.
type SandboxSpec struct {
	Enabled     bool              `json:"enabled,omitempty"`
	TemplateRef string            `json:"templateRef,omitempty"`
	Network     *NetworkPolicy    `json:"network,omitempty"`
	Lifecycle   *SandboxLifecycle `json:"lifecycle,omitempty"`
}

// NetworkPolicy defines network access rules.
type NetworkPolicy struct {
	AllowedDomains []string `json:"allowedDomains,omitempty"`
}

// SandboxLifecycle defines sandbox lifecycle behavior.
type SandboxLifecycle struct {
	// +kubebuilder:validation:Enum=Delete;Retain
	ShutdownPolicy string `json:"shutdownPolicy,omitempty"`
	IdleTimeout    string `json:"idleTimeout,omitempty"`
}

// AgentStatus defines the observed state of an Agent.
type AgentStatus struct {
	ObservedGeneration int64          `json:"observedGeneration,omitempty"`
	Revision           string         `json:"revision,omitempty"`
	ManagementMode     ManagementMode `json:"managementMode,omitempty"`
	Conditions         []Condition    `json:"conditions,omitempty"`
	Replicas           ReplicaStatus  `json:"replicas,omitempty"`
	ActiveSessions     int32          `json:"activeSessions,omitempty"`
	DataPlaneInfo      *DataPlaneInfo `json:"dataPlaneInfo,omitempty"`
	Endpoints          []Endpoint     `json:"endpoints,omitempty"`
}

package v1alpha1

import metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

// MCPServerType defines the transport type.
// +kubebuilder:validation:Enum=Remote;Stdio
type MCPServerType string

const (
	MCPServerTypeRemote MCPServerType = "Remote"
	MCPServerTypeStdio  MCPServerType = "Stdio"
)

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:shortName=mcp
// +kubebuilder:printcolumn:name="Type",type=string,JSONPath=`.spec.type`
// +kubebuilder:printcolumn:name="URL",type=string,JSONPath=`.spec.remote.url`,priority=1
// +kubebuilder:printcolumn:name="Tools",type=integer,JSONPath=`.status.discoveredTools`,priority=1
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`

// MCPServer defines an MCP service registration.
type MCPServer struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   MCPServerSpec   `json:"spec,omitempty"`
	Status MCPServerStatus `json:"status,omitempty"`
}

// +kubebuilder:object:root=true

// MCPServerList contains a list of MCPServer.
type MCPServerList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []MCPServer `json:"items"`
}

// MCPServerSpec defines the desired state of MCPServer.
type MCPServerSpec struct {
	// +kubebuilder:validation:MaxLength=512
	Description string `json:"description,omitempty"`

	// +kubebuilder:validation:Required
	// +kubebuilder:validation:Enum=Remote;Stdio
	Type MCPServerType `json:"type"`

	// Remote transport configuration. Required when type=Remote.
	// +optional
	Remote *RemoteMCPConfig `json:"remote,omitempty"`

	// Stdio transport configuration. Required when type=Stdio.
	// +optional
	Stdio *StdioMCPConfig `json:"stdio,omitempty"`

	// +optional
	AllowedNamespaces *AllowedNamespaces `json:"allowedNamespaces,omitempty"`
}

// RemoteMCPConfig defines remote MCP transport.
type RemoteMCPConfig struct {
	// +kubebuilder:validation:Enum=STREAMABLE_HTTP;SSE
	// +kubebuilder:default="STREAMABLE_HTTP"
	Protocol string `json:"protocol,omitempty"`

	// +kubebuilder:validation:Required
	// +kubebuilder:validation:Pattern=`^https?://`
	URL string `json:"url"`

	// +optional
	HeadersFrom []HeaderFromRef `json:"headersFrom,omitempty"`

	// +kubebuilder:default="30s"
	Timeout string `json:"timeout,omitempty"`
}

// HeaderFromRef references a header value from a Secret.
type HeaderFromRef struct {
	Kind   string `json:"kind"`
	Name   string `json:"name"`
	Key    string `json:"key"`
	Header string `json:"header"`
}

// StdioMCPConfig defines stdio MCP transport.
type StdioMCPConfig struct {
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Command string            `json:"command"`
	Args    []string          `json:"args,omitempty"`
	Env     map[string]string `json:"env,omitempty"`
}

// MCPServerStatus defines the observed state of MCPServer.
type MCPServerStatus struct {
	Conditions      []Condition      `json:"conditions,omitempty"`
	DiscoveredTools []DiscoveredTool `json:"discoveredTools,omitempty"`
}

// DiscoveredTool represents a tool discovered from the MCP server.
type DiscoveredTool struct {
	Name        string `json:"name"`
	Description string `json:"description,omitempty"`
}

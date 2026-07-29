package v1alpha1

import metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

// ConditionType defines the type of condition.
type ConditionType string

const (
	ConditionAccepted           ConditionType = "Accepted"
	ConditionReady              ConditionType = "Ready"
	ConditionDataPlaneConnected ConditionType = "DataPlaneConnected"
	ConditionProvisioned        ConditionType = "Provisioned"
	ConditionDiscovered         ConditionType = "Discovered"
)

// Condition represents a status condition.
type Condition struct {
	Type               ConditionType          `json:"type"`
	Status             metav1.ConditionStatus `json:"status"`
	LastTransitionTime metav1.Time            `json:"lastTransitionTime,omitempty"`
	Reason             string                 `json:"reason,omitempty"`
	Message            string                 `json:"message,omitempty"`
}

// ResourceRequirements defines resource requests and limits.
type ResourceRequirements struct {
	Requests ResourceList `json:"requests,omitempty"`
	Limits   ResourceList `json:"limits,omitempty"`
}

// ResourceList is a set of resource quantities.
type ResourceList struct {
	CPU    string `json:"cpu,omitempty"`
	Memory string `json:"memory,omitempty"`
}

// EnvVar represents an environment variable.
type EnvVar struct {
	Name      string `json:"name"`
	Value     string `json:"value,omitempty"`
	ValueFrom string `json:"valueFrom,omitempty"`
}

// AllowedNamespaces defines namespace access policy.
type AllowedNamespaces struct {
	From string `json:"from,omitempty"` // Same | All | Selector
}

// ObjectReference refers to a Kubernetes object.
type ObjectReference struct {
	Kind      string `json:"kind,omitempty"`
	Name      string `json:"name"`
	Namespace string `json:"namespace,omitempty"`
}

// SecretKeyRef refers to a key in a Secret.
type SecretKeyRef struct {
	Name string `json:"name"`
	Key  string `json:"key"`
}

// ConfigMapKeyRef refers to a key in a ConfigMap.
type ConfigMapKeyRef struct {
	Kind string `json:"kind,omitempty"`
	Name string `json:"name"`
	Key  string `json:"key"`
}

// ReplicaStatus tracks desired/ready/available replica counts.
type ReplicaStatus struct {
	Desired   int32 `json:"desired"`
	Ready     int32 `json:"ready"`
	Available int32 `json:"available,omitempty"`
}

// Endpoint represents a pod endpoint.
type Endpoint struct {
	IP   string `json:"ip"`
	Port int32  `json:"port"`
}

// DataPlaneInfo holds information probed from the data plane.
type DataPlaneInfo struct {
	ContractLevel   int32    `json:"contractLevel"`
	Model           string   `json:"model,omitempty"`
	ModelProvider   string   `json:"modelProvider,omitempty"`
	Tools           []string `json:"tools,omitempty"`
	SDKVersion      string   `json:"sdkVersion,omitempty"`
	Version         string   `json:"version,omitempty"`
	SessionAffinity string   `json:"sessionAffinity,omitempty"`
	Capabilities    []string `json:"capabilities,omitempty"`
	LastProbeAt     string   `json:"lastProbeAt,omitempty"`
}

// Data-plane capability names advertised via DataPlaneInfo.Capabilities
// (see docs/zh/controlplane/sdk-design.md §2.4).
const (
	// CapabilitySessionReporting: Level-1 session summary snapshots.
	CapabilitySessionReporting = "session-reporting"
	// CapabilityEventReporting: Level-2 event stream (push via ASDP).
	CapabilityEventReporting = "event-reporting"
	// CapabilityContextReporting: Level-4 context snapshots (push via ASDP).
	CapabilityContextReporting = "context-reporting"
	// CapabilityContextQuery: GET /agentscope/sessions/{id}/context.
	CapabilityContextQuery = "context-query"
	// CapabilityMessageQuery: GET /agentscope/sessions/{id}/messages.
	CapabilityMessageQuery = "message-query"
	// CapabilitySessionCommand: compress/terminate commands.
	CapabilitySessionCommand = "session-command"
	// CapabilitySubagentInventory: subagent inventory report/query.
	CapabilitySubagentInventory = "subagent-inventory"
	// CapabilityWorkspaceInventory: workspace inventory report/query.
	CapabilityWorkspaceInventory = "workspace-inventory"
)

// HasCapability reports whether the data plane advertised the given
// capability via DataPlaneInfo.Capabilities.
func (d *DataPlaneInfo) HasCapability(capability string) bool {
	if d == nil {
		return false
	}
	for _, c := range d.Capabilities {
		if c == capability {
			return true
		}
	}
	return false
}

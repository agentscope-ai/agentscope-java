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

package v1alpha1

import metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

// SandboxPhase represents the lifecycle phase of a sandbox claim.
// +kubebuilder:validation:Enum=Pending;Bound;Released
type SandboxPhase string

const (
	SandboxPhasePending  SandboxPhase = "Pending"
	SandboxPhaseBound    SandboxPhase = "Bound"
	SandboxPhaseReleased SandboxPhase = "Released"
)

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:shortName=sbc
// +kubebuilder:printcolumn:name="Agent",type=string,JSONPath=`.spec.agentRef.name`
// +kubebuilder:printcolumn:name="Phase",type=string,JSONPath=`.status.phase`
// +kubebuilder:printcolumn:name="FQDN",type=string,JSONPath=`.status.serviceFQDN`,priority=1
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`

// SandboxClaim represents a request for a sandboxed execution environment.
//
// EXPERIMENTAL: Sandbox provisioning is not yet implemented. Creating a
// SandboxClaim will be accepted and set to Pending phase, but will not
// provision an actual sandbox. This feature requires the agent-sandbox
// project (agent-sandbox.sigs.k8s.io) which is under active development.
// See the project roadmap for planned integration timeline.
type SandboxClaim struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   SandboxClaimSpec   `json:"spec,omitempty"`
	Status SandboxClaimStatus `json:"status,omitempty"`
}

// +kubebuilder:object:root=true

// SandboxClaimList contains a list of SandboxClaim.
type SandboxClaimList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []SandboxClaim `json:"items"`
}

// SandboxClaimSpec defines the desired state of SandboxClaim.
type SandboxClaimSpec struct {
	// +kubebuilder:validation:Required
	AgentRef   ObjectReference `json:"agentRef"`
	SessionRef ObjectReference `json:"sessionRef,omitempty"`
	// +kubebuilder:validation:Required
	SandboxTemplate SandboxTemplateSpec `json:"sandboxTemplate"`
}

// SandboxTemplateSpec defines the sandbox template.
type SandboxTemplateSpec struct {
	PodTemplate *PodTemplateSpec  `json:"podTemplate,omitempty"`
	Lifecycle   *SandboxLifecycle `json:"lifecycle,omitempty"`
	Network     *NetworkPolicy    `json:"network,omitempty"`
}

// PodTemplateSpec holds a simplified pod template.
type PodTemplateSpec struct {
	Containers []ContainerSpec `json:"containers,omitempty"`
}

// ContainerSpec defines a container in the sandbox.
type ContainerSpec struct {
	// +kubebuilder:validation:Required
	Name string `json:"name"`
	// +kubebuilder:validation:Required
	Image     string                `json:"image"`
	Resources *ResourceRequirements `json:"resources,omitempty"`
}

// SandboxClaimStatus defines the observed state of SandboxClaim.
type SandboxClaimStatus struct {
	Phase       SandboxPhase     `json:"phase,omitempty"`
	SandboxRef  *ObjectReference `json:"sandboxRef,omitempty"`
	ServiceFQDN string           `json:"serviceFQDN,omitempty"`
	Conditions  []Condition      `json:"conditions,omitempty"`
}

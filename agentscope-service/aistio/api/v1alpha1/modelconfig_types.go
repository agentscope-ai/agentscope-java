package v1alpha1

import metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:shortName=mc
// +kubebuilder:printcolumn:name="Provider",type=string,JSONPath=`.spec.provider`
// +kubebuilder:printcolumn:name="Model",type=string,JSONPath=`.spec.model`
// +kubebuilder:printcolumn:name="Accepted",type=string,JSONPath=`.status.conditions[?(@.type=="Accepted")].status`
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`

// ModelConfig defines model provider configuration.
type ModelConfig struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   ModelConfigSpec   `json:"spec,omitempty"`
	Status ModelConfigStatus `json:"status,omitempty"`
}

// +kubebuilder:object:root=true

// ModelConfigList contains a list of ModelConfig.
type ModelConfigList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []ModelConfig `json:"items"`
}

// ModelConfigSpec defines the desired state of ModelConfig.
type ModelConfigSpec struct {
	// Provider identifies the model vendor.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:Enum=DashScope;OpenAI;Anthropic;Gemini;Ollama;Moonshot;Custom
	Provider string `json:"provider"`

	// Model is the model identifier.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Model string `json:"model"`

	// APIKeySecret references the Secret containing the API key.
	// +optional
	APIKeySecret string `json:"apiKeySecret,omitempty"`

	// APIKeySecretKey is the key within the Secret.
	// +kubebuilder:default="api-key"
	APIKeySecretKey string `json:"apiKeySecretKey,omitempty"`

	// Options holds provider-specific configuration (temperature, maxTokens, etc).
	// +optional
	Options map[string]string `json:"options,omitempty"`

	// TLS configuration for provider endpoint.
	// +optional
	TLS *TLSConfig `json:"tls,omitempty"`
}

// TLSConfig defines TLS settings.
type TLSConfig struct {
	DisableVerify   bool   `json:"disableVerify,omitempty"`
	CACertSecretRef string `json:"caCertSecretRef,omitempty"`
}

// ModelConfigStatus defines the observed state of ModelConfig.
type ModelConfigStatus struct {
	Conditions []Condition `json:"conditions,omitempty"`
	// SecretHash tracks changes to the referenced Secret.
	SecretHash string `json:"secretHash,omitempty"`
}

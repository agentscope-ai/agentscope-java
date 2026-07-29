package adapter

import (
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

// ToolConfig holds resolved tool configuration for building ConfigMaps.
type ToolConfig struct {
	Name            string
	MCPServer       string
	URL             string
	ToolNames       []string
	RequireApproval []string
}

// DataPlaneAdapter builds Kubernetes resources for a specific data plane runtime.
// Different runtimes (agentscope-java, agentscope-go, langchain) have different
// images, ports, config formats, and probes.
type DataPlaneAdapter interface {
	// RuntimeName returns the runtime identifier (e.g. "agentscope-java").
	RuntimeName() string

	// BuildDeployment constructs a Kubernetes Deployment from the Agent CRD spec.
	BuildDeployment(agent *v1alpha1.Agent) (*appsv1.Deployment, error)

	// BuildConfigMap translates Agent spec into a data plane consumable config format.
	BuildConfigMap(agent *v1alpha1.Agent, tools []ToolConfig) (*corev1.ConfigMap, error)

	// BuildService constructs the Kubernetes Service for the agent.
	BuildService(agent *v1alpha1.Agent) (*corev1.Service, error)

	// HealthProbe returns the data plane health check probe configuration.
	HealthProbe() *corev1.Probe

	// DefaultPort returns the default container port for this runtime.
	DefaultPort() int32

	// SupportsFeature queries whether the runtime supports a specific feature.
	SupportsFeature(feature string) bool
}

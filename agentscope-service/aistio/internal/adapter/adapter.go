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

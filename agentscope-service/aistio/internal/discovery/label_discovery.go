package discovery

import (
	"strconv"

	appsv1 "k8s.io/api/apps/v1"
)

const (
	// Labels
	LabelManaged   = "agentscope.io/managed"
	LabelAgentName = "agentscope.io/agent-name"

	// Annotations
	AnnoRuntime      = "agentscope.io/runtime"
	AnnoAgentPort    = "agentscope.io/agent-port"
	AnnoContractPath = "agentscope.io/contract-path"

	// Namespace-level discovery label
	LabelNamespaceDiscovery = "agentscope.io/discovery"

	// Defaults
	DefaultPort         = int32(8080)
	DefaultContractPath = "/agentscope"
	DefaultRuntime      = "custom"
)

// DeploymentMetadata holds metadata extracted from a Deployment's labels/annotations.
type DeploymentMetadata struct {
	AgentName    string
	Runtime      string
	AgentPort    int32
	ContractPath string
	IsManaged    bool
}

// ExtractMetadata reads agentscope labels/annotations from a Deployment.
func ExtractMetadata(dep *appsv1.Deployment) DeploymentMetadata {
	meta := DeploymentMetadata{
		AgentPort:    DefaultPort,
		ContractPath: DefaultContractPath,
		Runtime:      DefaultRuntime,
	}

	if dep.Labels == nil {
		return meta
	}

	meta.IsManaged = dep.Labels[LabelManaged] == "true"

	if name := dep.Labels[LabelAgentName]; name != "" {
		meta.AgentName = name
	} else {
		meta.AgentName = dep.Name
	}

	if dep.Annotations != nil {
		if rt := dep.Annotations[AnnoRuntime]; rt != "" {
			meta.Runtime = rt
		}
		if portStr := dep.Annotations[AnnoAgentPort]; portStr != "" {
			if port, err := strconv.ParseInt(portStr, 10, 32); err == nil {
				meta.AgentPort = int32(port)
			}
		}
		if cp := dep.Annotations[AnnoContractPath]; cp != "" {
			meta.ContractPath = cp
		}
	}

	return meta
}

// ShouldDiscover returns true if the Deployment should be discovered.
func ShouldDiscover(dep *appsv1.Deployment) bool {
	if dep.Labels == nil {
		return false
	}
	return dep.Labels[LabelManaged] == "true"
}

// BuildEndpoint constructs the contract API endpoint from metadata and pod IP.
func BuildEndpoint(podIP string, meta DeploymentMetadata) string {
	return "http://" + podIP + ":" + strconv.Itoa(int(meta.AgentPort)) + meta.ContractPath
}

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

package controller

import (
	"context"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/predicate"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/endpoints"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
)

// agentWorkloadRefPredicate selects Agents based on whether they are BYO
// workloadRef agents. AgentReconciler wants the non-workloadRef agents
// (Declarative + BYO image); BYOWorkloadReconciler wants workloadRef agents.
func agentWorkloadRefPredicate(wantWorkloadRef bool) predicate.Predicate {
	return predicate.NewPredicateFuncs(func(o client.Object) bool {
		a, ok := o.(*v1alpha1.Agent)
		if !ok {
			return false
		}
		isWorkloadRef := a.Spec.Type == v1alpha1.AgentTypeBYO &&
			a.Spec.BYO != nil && a.Spec.BYO.WorkloadRef != nil
		return isWorkloadRef == wantWorkloadRef
	})
}

// managedDeploymentPredicate selects only Deployments labeled as managed,
// so the DiscoveryController is not enqueued for every Deployment in the cluster.
func managedDeploymentPredicate() predicate.Predicate {
	return predicate.NewPredicateFuncs(func(o client.Object) bool {
		return o.GetLabels()[labelManaged] == "true"
	})
}

// toDataPlaneInfo converts a probe result into the CRD status representation.
func toDataPlaneInfo(info *prober.DataPlaneInfo) *v1alpha1.DataPlaneInfo {
	dpi := &v1alpha1.DataPlaneInfo{
		ContractLevel:   info.ContractLevel,
		SDKVersion:      info.SDKVersion,
		Version:         info.Version,
		SessionAffinity: info.SessionAffinity,
		Capabilities:    info.Capabilities,
		LastProbeAt:     time.Now().Format(time.RFC3339),
	}
	if info.AgentConfig != nil {
		dpi.Model = info.AgentConfig.Model
		dpi.ModelProvider = info.AgentConfig.ModelProvider
		dpi.Tools = info.AgentConfig.ToolNames()
	}
	return dpi
}

// findReadyPodIPForDeployment delegates to the shared endpoints package.
func findReadyPodIPForDeployment(ctx context.Context, c client.Client, dep *appsv1.Deployment) (string, error) {
	return endpoints.ReadyPodIP(ctx, c, dep)
}

// agentHasCapability reports whether the agent's data plane advertised the
// given capability via status.dataPlaneInfo.capabilities.
func agentHasCapability(agent *v1alpha1.Agent, capability string) bool {
	if agent == nil {
		return false
	}
	return agent.Status.DataPlaneInfo.HasCapability(capability)
}

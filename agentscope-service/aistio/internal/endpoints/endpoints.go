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

package endpoints

import (
	"context"
	"fmt"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

// ResolveAgentHTTP returns an HTTP base URL for a ready agent pod
// (e.g. "http://10.0.0.5:8080"). Deployment is assumed to be named after the Agent.
func ResolveAgentHTTP(ctx context.Context, c client.Client, agent *v1alpha1.Agent) (string, error) {
	var dep appsv1.Deployment
	if err := c.Get(ctx, types.NamespacedName{Name: agent.Name, Namespace: agent.Namespace}, &dep); err != nil {
		return "", fmt.Errorf("looking up deployment: %w", err)
	}
	podIP, err := ReadyPodIP(ctx, c, &dep)
	if err != nil {
		return "", err
	}
	port := int32(8080)
	if agent.Spec.BYO != nil && agent.Spec.BYO.AgentPort > 0 {
		port = agent.Spec.BYO.AgentPort
	}
	return fmt.Sprintf("http://%s:%d", podIP, port), nil
}

// ReadyPodIP finds a ready pod IP for the given Deployment.
func ReadyPodIP(ctx context.Context, c client.Client, dep *appsv1.Deployment) (string, error) {
	var podList corev1.PodList
	if err := c.List(ctx, &podList,
		client.InNamespace(dep.Namespace),
		client.MatchingLabels(dep.Spec.Selector.MatchLabels),
	); err != nil {
		return "", err
	}
	for _, pod := range podList.Items {
		if pod.Status.Phase == corev1.PodRunning && pod.Status.PodIP != "" {
			for _, cond := range pod.Status.Conditions {
				if cond.Type == corev1.PodReady && cond.Status == corev1.ConditionTrue {
					return pod.Status.PodIP, nil
				}
			}
		}
	}
	return "", fmt.Errorf("no ready pods found for deployment %s/%s", dep.Namespace, dep.Name)
}

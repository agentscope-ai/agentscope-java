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
	"fmt"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/metrics"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// BYOWorkloadReconciler observes adopted external Deployments,
// periodically probes the contract API, and syncs status.
type BYOWorkloadReconciler struct {
	client.Client
	Scheme   *runtime.Scheme
	Prober   prober.DataPlaneProber
	Store    store.Store
	Recorder record.EventRecorder
}

// +kubebuilder:rbac:groups="",resources=events,verbs=create;patch

func (r *BYOWorkloadReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	logger := log.FromContext(ctx)

	var agent v1alpha1.Agent
	if err := r.Get(ctx, req.NamespacedName, &agent); err != nil {
		if errors.IsNotFound(err) {
			metrics.ForgetAgent(req.Namespace, req.Name)
			return ctrl.Result{}, nil
		}
		return ctrl.Result{}, err
	}

	logger.Info("reconciling BYO workload", "agent", agent.Name)

	// Get the referenced Deployment
	depName := agent.Spec.BYO.WorkloadRef.Name
	var dep appsv1.Deployment
	if err := r.Get(ctx, types.NamespacedName{Name: depName, Namespace: agent.Namespace}, &dep); err != nil {
		if errors.IsNotFound(err) {
			cond := v1alpha1.Condition{
				Type:               v1alpha1.ConditionReady,
				Status:             metav1.ConditionFalse,
				LastTransitionTime: metav1.Now(),
				Reason:             "WorkloadDeleted",
				Message:            fmt.Sprintf("Deployment %s not found", depName),
			}
			setConditionInList(&agent.Status.Conditions, cond)
			return ctrl.Result{}, r.Status().Update(ctx, &agent)
		}
		return ctrl.Result{}, err
	}

	// Update replica status (read-only, never modify the Deployment)
	agent.Status.Replicas = v1alpha1.ReplicaStatus{
		Desired:   *dep.Spec.Replicas,
		Ready:     dep.Status.ReadyReplicas,
		Available: dep.Status.AvailableReplicas,
	}
	agent.Status.ObservedGeneration = agent.Generation
	agent.Status.ManagementMode = v1alpha1.ManagementModeAdopted
	if r.Store != nil {
		agent.Status.ActiveSessions, _ = r.Store.Sessions().CountActive(ctx, agent.Name, agent.Namespace)
	}
	metrics.RecordAgent(agent.Namespace, agent.Name, string(agent.Spec.Type), agent.Spec.Runtime,
		string(v1alpha1.ManagementModeAdopted), agent.Status.Replicas.Desired, agent.Status.Replicas.Ready, agent.Status.Replicas.Available)
	setConditionInList(&agent.Status.Conditions, v1alpha1.Condition{
		Type:               v1alpha1.ConditionAccepted,
		Status:             metav1.ConditionTrue,
		LastTransitionTime: metav1.Now(),
		Reason:             "Adopted",
		Message:            "BYO workload adopted and observed",
	})

	// Probe health if pods are ready
	if dep.Status.ReadyReplicas > 0 {
		r.probeAndUpdateStatus(ctx, &agent, &dep)
	} else {
		cond := v1alpha1.Condition{
			Type:               v1alpha1.ConditionReady,
			Status:             metav1.ConditionFalse,
			LastTransitionTime: metav1.Now(),
			Reason:             "NoReadyPods",
		}
		setConditionInList(&agent.Status.Conditions, cond)
	}

	if err := r.Status().Update(ctx, &agent); err != nil {
		return ctrl.Result{}, err
	}

	r.Recorder.Eventf(&agent, corev1.EventTypeNormal, "StatusUpdated", "BYO workload status updated for agent %s", agent.Name)
	return ctrl.Result{RequeueAfter: 30 * time.Second}, nil
}

func (r *BYOWorkloadReconciler) probeAndUpdateStatus(ctx context.Context, agent *v1alpha1.Agent, dep *appsv1.Deployment) {
	port := agent.Spec.BYO.AgentPort
	if port == 0 {
		port = 8080
	}

	// Find a ready pod for probing
	podIP, err := findReadyPodIPForDeployment(ctx, r.Client, dep)
	if err != nil {
		return
	}

	// The prober appends the contract path (/agentscope/...) itself,
	// so callers must pass only the base scheme://host:port URL.
	endpoint := fmt.Sprintf("http://%s:%d", podIP, port)

	// Health check
	healthy, _ := r.Prober.ProbeHealth(ctx, endpoint)
	healthCond := v1alpha1.Condition{
		Type:               v1alpha1.ConditionReady,
		LastTransitionTime: metav1.Now(),
	}
	if healthy {
		healthCond.Status = metav1.ConditionTrue
		healthCond.Reason = "HealthCheckPassed"
		r.Recorder.Eventf(agent, corev1.EventTypeNormal, "HealthProbeSucceeded", "health probe passed for agent %s", agent.Name)
	} else {
		healthCond.Status = metav1.ConditionFalse
		healthCond.Reason = "HealthCheckFailed"
		r.Recorder.Eventf(agent, corev1.EventTypeWarning, "HealthProbeFailed", "health probe failed for agent %s", agent.Name)
	}
	setConditionInList(&agent.Status.Conditions, healthCond)

	// Full info probe
	info, err := r.Prober.ProbeInfo(ctx, endpoint)
	if err != nil {
		return
	}

	agent.Status.DataPlaneInfo = toDataPlaneInfo(info)

	dpCond := v1alpha1.Condition{
		Type:               v1alpha1.ConditionDataPlaneConnected,
		Status:             metav1.ConditionTrue,
		LastTransitionTime: metav1.Now(),
		Reason:             fmt.Sprintf("ContractLevel%dVerified", info.ContractLevel),
	}
	setConditionInList(&agent.Status.Conditions, dpCond)
}

func (r *BYOWorkloadReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		Named("byoworkload").
		For(&v1alpha1.Agent{}, builder.WithPredicates(agentWorkloadRefPredicate(true))).
		WithOptions(controller.Options{MaxConcurrentReconciles: 2}).
		Complete(r)
}

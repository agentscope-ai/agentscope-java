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
	"k8s.io/client-go/tools/record"
	"k8s.io/client-go/util/retry"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/adapter"
	"github.com/spring-ai-alibaba/aistio/internal/metrics"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

const agentFinalizer = "agentscope.io/agent-finalizer"

// AgentReconciler reconciles Agent objects (Declarative and BYO image mode).
type AgentReconciler struct {
	client.Client
	Scheme   *runtime.Scheme
	Prober   prober.DataPlaneProber
	Store    store.Store
	Recorder record.EventRecorder
}

// +kubebuilder:rbac:groups=agentscope.io,resources=agents,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=agentscope.io,resources=agents/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=agentscope.io,resources=agents/finalizers,verbs=update
// +kubebuilder:rbac:groups=apps,resources=deployments,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups="",resources=services,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups="",resources=configmaps,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups="",resources=events,verbs=create;patch
// +kubebuilder:rbac:groups=coordination.k8s.io,resources=leases,verbs=get;list;watch;create;update;patch;delete

func (r *AgentReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	logger := log.FromContext(ctx)

	var agent v1alpha1.Agent
	if err := r.Get(ctx, req.NamespacedName, &agent); err != nil {
		if errors.IsNotFound(err) {
			metrics.ForgetAgent(req.Namespace, req.Name)
			return ctrl.Result{}, nil
		}
		return ctrl.Result{}, err
	}

	// Handle deletion with finalizer
	if !agent.DeletionTimestamp.IsZero() {
		if controllerutil.ContainsFinalizer(&agent, agentFinalizer) {
			if r.Store != nil {
				if err := r.Store.Sessions().DeleteByAgent(ctx, agent.Name, agent.Namespace); err != nil {
					logger.Error(err, "failed to cleanup owned sessions")
					return ctrl.Result{}, err
				}
			}
			r.Recorder.Eventf(&agent, corev1.EventTypeNormal, "CleanupComplete", "cascading cleanup of owned sessions finished")
			controllerutil.RemoveFinalizer(&agent, agentFinalizer)
			if err := r.Update(ctx, &agent); err != nil {
				return ctrl.Result{}, err
			}
		}
		return ctrl.Result{}, nil
	}

	// Ensure finalizer is present
	if !controllerutil.ContainsFinalizer(&agent, agentFinalizer) {
		controllerutil.AddFinalizer(&agent, agentFinalizer)
		if err := r.Update(ctx, &agent); err != nil {
			return ctrl.Result{}, err
		}
	}

	logger.Info("reconciling agent", "name", agent.Name, "type", agent.Spec.Type)

	adp, err := adapter.Get(agent.Spec.Runtime)
	if err != nil {
		r.Recorder.Eventf(&agent, corev1.EventTypeWarning, "UnsupportedRuntime", "runtime %q is not supported: %v", agent.Spec.Runtime, err)
		return ctrl.Result{}, r.setCondition(ctx, &agent, v1alpha1.ConditionAccepted, metav1.ConditionFalse, "UnsupportedRuntime", err.Error())
	}

	// Reconcile ConfigMap (Declarative only)
	if agent.Spec.Type == v1alpha1.AgentTypeDeclarative {
		if err := r.reconcileConfigMap(ctx, &agent, adp); err != nil {
			logger.Error(err, "failed to reconcile ConfigMap")
			return ctrl.Result{}, err
		}
		r.Recorder.Eventf(&agent, corev1.EventTypeNormal, "ConfigMapUpdated", "ConfigMap reconciled for agent %s", agent.Name)
	}

	// Reconcile Deployment
	if err := r.reconcileDeployment(ctx, &agent, adp); err != nil {
		logger.Error(err, "failed to reconcile Deployment")
		return ctrl.Result{}, err
	}
	r.Recorder.Eventf(&agent, corev1.EventTypeNormal, "DeploymentCreated", "Deployment reconciled for agent %s", agent.Name)

	// Reconcile Service
	if err := r.reconcileService(ctx, &agent, adp); err != nil {
		logger.Error(err, "failed to reconcile Service")
		return ctrl.Result{}, err
	}

	// Update status from Deployment (+ data plane probe)
	if err := r.updateStatus(ctx, &agent, adp); err != nil {
		logger.Error(err, "failed to update status")
		return ctrl.Result{}, err
	}

	// ASDP config push to connected data plane instances is handled by
	// ConfigPushWatcher, which runs on all replicas (not only the leader).

	r.Recorder.Eventf(&agent, corev1.EventTypeNormal, "ReconcileSuccess", "agent %s reconciled successfully", agent.Name)
	return ctrl.Result{RequeueAfter: 30 * time.Second}, nil
}

func (r *AgentReconciler) reconcileConfigMap(ctx context.Context, agent *v1alpha1.Agent, adp adapter.DataPlaneAdapter) error {
	desired, err := adp.BuildConfigMap(agent, nil)
	if err != nil {
		return fmt.Errorf("building ConfigMap: %w", err)
	}

	var existing corev1.ConfigMap
	err = r.Get(ctx, client.ObjectKeyFromObject(desired), &existing)
	if errors.IsNotFound(err) {
		return r.Create(ctx, desired)
	}
	if err != nil {
		return err
	}

	existing.Data = desired.Data
	return r.Update(ctx, &existing)
}

func (r *AgentReconciler) reconcileDeployment(ctx context.Context, agent *v1alpha1.Agent, adp adapter.DataPlaneAdapter) error {
	desired, err := adp.BuildDeployment(agent)
	if err != nil {
		return fmt.Errorf("building Deployment: %w", err)
	}

	var existing appsv1.Deployment
	err = r.Get(ctx, client.ObjectKeyFromObject(desired), &existing)
	if errors.IsNotFound(err) {
		return r.Create(ctx, desired)
	}
	if err != nil {
		return err
	}

	existing.Spec.Replicas = desired.Spec.Replicas
	existing.Spec.Template = desired.Spec.Template
	return r.Update(ctx, &existing)
}

func (r *AgentReconciler) reconcileService(ctx context.Context, agent *v1alpha1.Agent, adp adapter.DataPlaneAdapter) error {
	desired, err := adp.BuildService(agent)
	if err != nil {
		return fmt.Errorf("building Service: %w", err)
	}

	var existing corev1.Service
	err = r.Get(ctx, client.ObjectKeyFromObject(desired), &existing)
	if errors.IsNotFound(err) {
		return r.Create(ctx, desired)
	}
	if err != nil {
		return err
	}

	existing.Spec.Ports = desired.Spec.Ports
	existing.Spec.Selector = desired.Spec.Selector
	return r.Update(ctx, &existing)
}

func (r *AgentReconciler) updateStatus(ctx context.Context, agent *v1alpha1.Agent, adp adapter.DataPlaneAdapter) error {
	var dep appsv1.Deployment
	if err := r.Get(ctx, client.ObjectKey{Name: agent.Name, Namespace: agent.Namespace}, &dep); err != nil {
		if errors.IsNotFound(err) {
			return nil
		}
		return err
	}

	replicas := v1alpha1.ReplicaStatus{
		Desired:   *dep.Spec.Replicas,
		Ready:     dep.Status.ReadyReplicas,
		Available: dep.Status.AvailableReplicas,
	}

	readyCond := v1alpha1.Condition{
		Type:               v1alpha1.ConditionReady,
		LastTransitionTime: metav1.Now(),
	}
	if dep.Status.ReadyReplicas == *dep.Spec.Replicas {
		readyCond.Status = metav1.ConditionTrue
		readyCond.Reason = "DeploymentReady"
		readyCond.Message = fmt.Sprintf("%d/%d replicas available", dep.Status.ReadyReplicas, *dep.Spec.Replicas)
	} else {
		readyCond.Status = metav1.ConditionFalse
		readyCond.Reason = "DeploymentNotReady"
		readyCond.Message = fmt.Sprintf("%d/%d replicas ready", dep.Status.ReadyReplicas, *dep.Spec.Replicas)
	}

	// Probe the data plane contract API once pods are ready.
	var dataPlaneInfo *v1alpha1.DataPlaneInfo
	var dpCond *v1alpha1.Condition
	if dep.Status.ReadyReplicas > 0 && r.Prober != nil {
		if podIP, err := findReadyPodIPForDeployment(ctx, r.Client, &dep); err == nil {
			// The prober appends the contract path (/agentscope/...) itself,
			// so callers must pass only the base scheme://host:port URL.
			endpoint := fmt.Sprintf("http://%s:%d", podIP, adp.DefaultPort())
			if info, err := r.Prober.ProbeInfo(ctx, endpoint); err == nil {
				dataPlaneInfo = toDataPlaneInfo(info)
				dpCond = &v1alpha1.Condition{
					Type:               v1alpha1.ConditionDataPlaneConnected,
					Status:             metav1.ConditionTrue,
					LastTransitionTime: metav1.Now(),
					Reason:             fmt.Sprintf("ContractLevel%dVerified", info.ContractLevel),
				}
				r.Recorder.Eventf(agent, corev1.EventTypeNormal, "ProbeSucceeded", "data plane probe succeeded at %s", endpoint)
			} else {
				r.Recorder.Eventf(agent, corev1.EventTypeWarning, "ProbeFailed", "data plane probe failed at %s: %v", endpoint, err)
			}
		}
	}

	var activeSessions int32
	if r.Store != nil {
		activeSessions, _ = r.Store.Sessions().CountActive(ctx, agent.Name, agent.Namespace)
	}

	metrics.RecordAgent(agent.Namespace, agent.Name, string(agent.Spec.Type), agent.Spec.Runtime,
		string(v1alpha1.ManagementModeCPManaged), replicas.Desired, replicas.Ready, replicas.Available)

	return retry.RetryOnConflict(retry.DefaultRetry, func() error {
		var fresh v1alpha1.Agent
		if err := r.Get(ctx, client.ObjectKeyFromObject(agent), &fresh); err != nil {
			return err
		}
		fresh.Status.ObservedGeneration = fresh.Generation
		fresh.Status.ManagementMode = v1alpha1.ManagementModeCPManaged
		fresh.Status.Replicas = replicas
		fresh.Status.ActiveSessions = activeSessions
		setConditionInList(&fresh.Status.Conditions, v1alpha1.Condition{
			Type:               v1alpha1.ConditionAccepted,
			Status:             metav1.ConditionTrue,
			LastTransitionTime: metav1.Now(),
			Reason:             "Reconciled",
			Message:            "Agent spec accepted and resources reconciled",
		})
		setConditionInList(&fresh.Status.Conditions, readyCond)
		if dataPlaneInfo != nil {
			fresh.Status.DataPlaneInfo = dataPlaneInfo
		}
		if dpCond != nil {
			setConditionInList(&fresh.Status.Conditions, *dpCond)
		}
		return r.Status().Update(ctx, &fresh)
	})
}

func (r *AgentReconciler) setCondition(ctx context.Context, agent *v1alpha1.Agent, condType v1alpha1.ConditionType, status metav1.ConditionStatus, reason, message string) error {
	return retry.RetryOnConflict(retry.DefaultRetry, func() error {
		var fresh v1alpha1.Agent
		if err := r.Get(ctx, client.ObjectKeyFromObject(agent), &fresh); err != nil {
			return err
		}
		setConditionInList(&fresh.Status.Conditions, v1alpha1.Condition{
			Type:               condType,
			Status:             status,
			LastTransitionTime: metav1.Now(),
			Reason:             reason,
			Message:            message,
		})
		return r.Status().Update(ctx, &fresh)
	})
}

func (r *AgentReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		Named("agent").
		For(&v1alpha1.Agent{}, builder.WithPredicates(agentWorkloadRefPredicate(false))).
		Owns(&appsv1.Deployment{}).
		Owns(&corev1.Service{}).
		Owns(&corev1.ConfigMap{}).
		WithOptions(controller.Options{MaxConcurrentReconciles: 2}).
		Complete(r)
}

// setConditionInList updates or appends a condition in the list.
func setConditionInList(conditions *[]v1alpha1.Condition, cond v1alpha1.Condition) {
	for i, c := range *conditions {
		if c.Type == cond.Type {
			(*conditions)[i] = cond
			return
		}
	}
	*conditions = append(*conditions, cond)
}

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
	"k8s.io/client-go/util/retry"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
)

const (
	labelManaged     = "agentscope.io/managed"
	labelAgentName   = "agentscope.io/agent-name"
	annoRuntime      = "agentscope.io/runtime"
	annoAgentPort    = "agentscope.io/agent-port"
	annoContractPath = "agentscope.io/contract-path"

	defaultContractPath = "/agentscope"
	defaultAgentPort    = "8080"
)

// DiscoveryReconciler watches Deployments with agentscope.io/managed=true
// and automatically creates BYO Agent CRDs (workloadRef mode).
type DiscoveryReconciler struct {
	client.Client
	Scheme   *runtime.Scheme
	Prober   prober.DataPlaneProber
	Recorder record.EventRecorder
}

// +kubebuilder:rbac:groups=apps,resources=deployments,verbs=get;list;watch
// +kubebuilder:rbac:groups="",resources=pods,verbs=get;list;watch
// +kubebuilder:rbac:groups="",resources=events,verbs=create;patch
// +kubebuilder:rbac:groups=agentscope.io,resources=agents,verbs=get;list;watch;create;update;patch

func (r *DiscoveryReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	logger := log.FromContext(ctx)

	var dep appsv1.Deployment
	if err := r.Get(ctx, req.NamespacedName, &dep); err != nil {
		if errors.IsNotFound(err) {
			return ctrl.Result{}, nil
		}
		return ctrl.Result{}, err
	}

	// Only process deployments with agentscope.io/managed=true
	if dep.Labels[labelManaged] != "true" {
		return ctrl.Result{}, nil
	}

	logger.Info("discovered managed deployment", "name", dep.Name, "namespace", dep.Namespace)
	r.Recorder.Eventf(&dep, corev1.EventTypeNormal, "AgentDiscovered", "managed deployment %s discovered", dep.Name)

	// Determine agent name
	agentName := dep.Labels[labelAgentName]
	if agentName == "" {
		agentName = dep.Name
	}

	// Check if Agent CRD already exists
	var existingAgent v1alpha1.Agent
	err := r.Get(ctx, types.NamespacedName{Name: agentName, Namespace: dep.Namespace}, &existingAgent)
	if err == nil {
		// Agent already exists — update status if needed
		return r.reconcileExistingAgent(ctx, &existingAgent, &dep)
	}
	if !errors.IsNotFound(err) {
		return ctrl.Result{}, err
	}

	// Wait for at least one pod to be ready
	if dep.Status.ReadyReplicas == 0 {
		logger.Info("waiting for ready pods", "deployment", dep.Name)
		return ctrl.Result{RequeueAfter: 10 * time.Second}, nil
	}

	// Probe the data plane contract API
	info, err := r.probeDeployment(ctx, &dep)
	if err != nil {
		logger.Info("probe failed, creating with minimal info", "error", err)
		r.Recorder.Eventf(&dep, corev1.EventTypeWarning, "ProbeFailed", "data plane probe failed for deployment %s: %v", dep.Name, err)
	}

	// Create BYO Agent CRD (workloadRef)
	if err := r.createBYOAgent(ctx, &dep, agentName, info); err != nil {
		logger.Error(err, "failed to create Agent CRD")
		return ctrl.Result{}, err
	}

	r.Recorder.Eventf(&dep, corev1.EventTypeNormal, "AgentCreated", "Agent CRD %s created for discovered deployment", agentName)
	logger.Info("created Agent CRD for discovered deployment", "agent", agentName)
	return ctrl.Result{}, nil
}

func (r *DiscoveryReconciler) reconcileExistingAgent(ctx context.Context, agent *v1alpha1.Agent, dep *appsv1.Deployment) (ctrl.Result, error) {
	readyCond := v1alpha1.Condition{
		Type:               v1alpha1.ConditionReady,
		LastTransitionTime: metav1.Now(),
	}
	if dep.Status.ReadyReplicas > 0 {
		readyCond.Status = metav1.ConditionTrue
		readyCond.Reason = "WorkloadReady"
	} else {
		readyCond.Status = metav1.ConditionFalse
		readyCond.Reason = "WorkloadNotReady"
	}

	err := retry.RetryOnConflict(retry.DefaultRetry, func() error {
		var fresh v1alpha1.Agent
		if err := r.Get(ctx, client.ObjectKeyFromObject(agent), &fresh); err != nil {
			return err
		}
		fresh.Status.Replicas = v1alpha1.ReplicaStatus{
			Desired:   *dep.Spec.Replicas,
			Ready:     dep.Status.ReadyReplicas,
			Available: dep.Status.AvailableReplicas,
		}
		setConditionInList(&fresh.Status.Conditions, readyCond)
		return r.Status().Update(ctx, &fresh)
	})

	return ctrl.Result{RequeueAfter: 30 * time.Second}, err
}

func (r *DiscoveryReconciler) probeDeployment(ctx context.Context, dep *appsv1.Deployment) (*prober.DataPlaneInfo, error) {
	// Find a ready pod IP
	podIP, err := r.findReadyPodIP(ctx, dep)
	if err != nil {
		return nil, err
	}

	port := dep.Annotations[annoAgentPort]
	if port == "" {
		port = defaultAgentPort
	}

	// The prober appends the contract path (/agentscope/...) itself,
	// so callers must pass only the base scheme://host:port URL.
	endpoint := fmt.Sprintf("http://%s:%s", podIP, port)
	return r.Prober.ProbeInfo(ctx, endpoint)
}

func (r *DiscoveryReconciler) findReadyPodIP(ctx context.Context, dep *appsv1.Deployment) (string, error) {
	var podList corev1.PodList
	if err := r.List(ctx, &podList,
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
	return "", fmt.Errorf("no ready pods found for deployment %s", dep.Name)
}

func (r *DiscoveryReconciler) createBYOAgent(ctx context.Context, dep *appsv1.Deployment, agentName string, info *prober.DataPlaneInfo) error {
	runtimeName := dep.Annotations[annoRuntime]
	if runtimeName == "" && info != nil {
		runtimeName = info.Runtime
	}
	if runtimeName == "" {
		runtimeName = "custom"
	}

	port := int32(8080)
	if info != nil && info.Port > 0 {
		port = info.Port
	}

	agent := &v1alpha1.Agent{
		ObjectMeta: metav1.ObjectMeta{
			Name:      agentName,
			Namespace: dep.Namespace,
			Labels: map[string]string{
				labelManaged:   "true",
				labelAgentName: agentName,
			},
			Annotations: map[string]string{
				"agentscope.io/discovered-at": time.Now().Format(time.RFC3339),
				"agentscope.io/discovered-by": "DiscoveryController",
			},
		},
		Spec: v1alpha1.AgentSpec{
			Type:    v1alpha1.AgentTypeBYO,
			Runtime: runtimeName,
			BYO: &v1alpha1.BYOSpec{
				WorkloadRef: &v1alpha1.ObjectReference{
					Kind: "Deployment",
					Name: dep.Name,
				},
				AgentPort:    port,
				ContractPath: defaultContractPath,
			},
		},
	}

	if info != nil {
		agent.Spec.DisplayName = info.DisplayName
		agent.Spec.Description = info.Description
	}

	if err := r.Create(ctx, agent); err != nil {
		return err
	}

	// Re-fetch after Create to obtain the latest resourceVersion before the
	// status subresource update (avoids stale-object conflicts).
	return retry.RetryOnConflict(retry.DefaultRetry, func() error {
		var fresh v1alpha1.Agent
		if err := r.Get(ctx, client.ObjectKeyFromObject(agent), &fresh); err != nil {
			return err
		}
		fresh.Status.ManagementMode = v1alpha1.ManagementModeAdopted
		fresh.Status.Replicas = v1alpha1.ReplicaStatus{
			Desired: *dep.Spec.Replicas,
			Ready:   dep.Status.ReadyReplicas,
		}
		if info != nil {
			fresh.Status.DataPlaneInfo = toDataPlaneInfo(info)
			setConditionInList(&fresh.Status.Conditions, v1alpha1.Condition{
				Type:               v1alpha1.ConditionDataPlaneConnected,
				Status:             metav1.ConditionTrue,
				LastTransitionTime: metav1.Now(),
				Reason:             fmt.Sprintf("ContractLevel%dVerified", info.ContractLevel),
			})
		}
		setConditionInList(&fresh.Status.Conditions, v1alpha1.Condition{
			Type:               v1alpha1.ConditionReady,
			Status:             metav1.ConditionTrue,
			LastTransitionTime: metav1.Now(),
			Reason:             "WorkloadDiscovered",
		})
		return r.Status().Update(ctx, &fresh)
	})
}

func (r *DiscoveryReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&appsv1.Deployment{}, builder.WithPredicates(managedDeploymentPredicate())).
		WithOptions(controller.Options{MaxConcurrentReconciles: 2}).
		Complete(r)
}

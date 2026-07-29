package controller

import (
	"context"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

// SandboxBrokerReconciler translates SandboxClaim to agent-sandbox Sandbox CRDs.
type SandboxBrokerReconciler struct {
	client.Client
	Scheme   *runtime.Scheme
	Recorder record.EventRecorder
}

// +kubebuilder:rbac:groups=agentscope.io,resources=sandboxclaims,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=agentscope.io,resources=sandboxclaims/status,verbs=get;update;patch

func (r *SandboxBrokerReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	logger := log.FromContext(ctx)

	var claim v1alpha1.SandboxClaim
	if err := r.Get(ctx, req.NamespacedName, &claim); err != nil {
		if errors.IsNotFound(err) {
			return ctrl.Result{}, nil
		}
		return ctrl.Result{}, err
	}

	logger.Info("reconciling SandboxClaim", "name", claim.Name)

	// If already bound, nothing to do
	if claim.Status.Phase == v1alpha1.SandboxPhaseBound {
		return ctrl.Result{}, nil
	}

	// Validate agent ref
	if claim.Spec.AgentRef.Name == "" {
		cond := v1alpha1.Condition{
			Type:               v1alpha1.ConditionAccepted,
			Status:             metav1.ConditionFalse,
			LastTransitionTime: metav1.Now(),
			Reason:             "InvalidAgentRef",
			Message:            "agentRef.name is required",
		}
		setConditionInList(&claim.Status.Conditions, cond)
		return ctrl.Result{}, r.Status().Update(ctx, &claim)
	}

	// Set to pending — actual sandbox creation will integrate with agent-sandbox project
	claim.Status.Phase = v1alpha1.SandboxPhasePending
	cond := v1alpha1.Condition{
		Type:               v1alpha1.ConditionAccepted,
		Status:             metav1.ConditionTrue,
		LastTransitionTime: metav1.Now(),
		Reason:             "ClaimAccepted",
		Message:            "SandboxClaim accepted, awaiting sandbox provisioning",
	}
	setConditionInList(&claim.Status.Conditions, cond)

	provisionedCond := v1alpha1.Condition{
		Type:               v1alpha1.ConditionProvisioned,
		Status:             metav1.ConditionFalse,
		LastTransitionTime: metav1.Now(),
		Reason:             "NotImplemented",
		Message:            "Sandbox provisioning is not yet implemented. The SandboxClaim feature is experimental.",
	}
	setConditionInList(&claim.Status.Conditions, provisionedCond)

	r.Recorder.Event(&claim, corev1.EventTypeWarning, "SandboxNotImplemented",
		"Sandbox provisioning is experimental and not yet implemented; claim will remain in Pending phase")

	return ctrl.Result{}, r.Status().Update(ctx, &claim)
}

func (r *SandboxBrokerReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&v1alpha1.SandboxClaim{}).
		Complete(r)
}

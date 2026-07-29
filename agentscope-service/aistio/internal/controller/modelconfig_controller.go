package controller

import (
	"context"
	"crypto/sha256"
	"fmt"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

// ModelConfigReconciler validates provider configuration and tracks secret changes.
type ModelConfigReconciler struct {
	client.Client
	Scheme   *runtime.Scheme
	Recorder record.EventRecorder
}

// +kubebuilder:rbac:groups=agentscope.io,resources=modelconfigs,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=agentscope.io,resources=modelconfigs/status,verbs=get;update;patch
// +kubebuilder:rbac:groups="",resources=secrets,verbs=get;list;watch;create;update;patch
// +kubebuilder:rbac:groups="",resources=events,verbs=create;patch

func (r *ModelConfigReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	logger := log.FromContext(ctx)

	var mc v1alpha1.ModelConfig
	if err := r.Get(ctx, req.NamespacedName, &mc); err != nil {
		if errors.IsNotFound(err) {
			return ctrl.Result{}, nil
		}
		return ctrl.Result{}, err
	}

	logger.Info("reconciling ModelConfig", "name", mc.Name)

	// Validate provider
	if mc.Spec.Provider == "" || mc.Spec.Model == "" {
		cond := v1alpha1.Condition{
			Type:               v1alpha1.ConditionAccepted,
			Status:             metav1.ConditionFalse,
			LastTransitionTime: metav1.Now(),
			Reason:             "InvalidConfig",
			Message:            "provider and model are required",
		}
		setConditionInList(&mc.Status.Conditions, cond)
		return ctrl.Result{}, r.Status().Update(ctx, &mc)
	}

	// Check secret exists and compute hash
	if mc.Spec.APIKeySecret != "" {
		secretHash, err := r.computeSecretHash(ctx, mc.Namespace, mc.Spec.APIKeySecret, mc.Spec.APIKeySecretKey)
		if err != nil {
			cond := v1alpha1.Condition{
				Type:               v1alpha1.ConditionAccepted,
				Status:             metav1.ConditionFalse,
				LastTransitionTime: metav1.Now(),
				Reason:             "SecretNotFound",
				Message:            err.Error(),
			}
			setConditionInList(&mc.Status.Conditions, cond)
			r.Recorder.Eventf(&mc, corev1.EventTypeWarning, "SecretNotFound", "secret %s not found: %v", mc.Spec.APIKeySecret, err)
			return ctrl.Result{}, r.Status().Update(ctx, &mc)
		}
		mc.Status.SecretHash = secretHash
		r.Recorder.Eventf(&mc, corev1.EventTypeNormal, "SecretHashUpdated", "secret hash updated for ModelConfig %s", mc.Name)
	}

	cond := v1alpha1.Condition{
		Type:               v1alpha1.ConditionAccepted,
		Status:             metav1.ConditionTrue,
		LastTransitionTime: metav1.Now(),
		Reason:             "ConfigValid",
	}
	setConditionInList(&mc.Status.Conditions, cond)

	r.Recorder.Eventf(&mc, corev1.EventTypeNormal, "ConfigValidated", "ModelConfig %s validated successfully", mc.Name)

	// ASDP config push to referencing agents is handled by ConfigPushWatcher,
	// which runs on all replicas (not only the leader).

	return ctrl.Result{}, r.Status().Update(ctx, &mc)
}

func (r *ModelConfigReconciler) computeSecretHash(ctx context.Context, namespace, secretName, key string) (string, error) {
	var secret corev1.Secret
	if err := r.Get(ctx, types.NamespacedName{Name: secretName, Namespace: namespace}, &secret); err != nil {
		return "", fmt.Errorf("secret %s not found: %w", secretName, err)
	}

	data, ok := secret.Data[key]
	if !ok {
		return "", fmt.Errorf("key %s not found in secret %s", key, secretName)
	}

	hash := sha256.Sum256(data)
	return fmt.Sprintf("%x", hash[:8]), nil
}

func (r *ModelConfigReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&v1alpha1.ModelConfig{}).
		Complete(r)
}

package controller

import (
	"context"
	"fmt"
	"time"

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
	"github.com/spring-ai-alibaba/aistio/internal/mcp"
	"github.com/spring-ai-alibaba/aistio/internal/metrics"
)

const discoveryTimeout = 15 * time.Second

// MCPServerReconciler validates MCPServer connectivity and discovers tools.
type MCPServerReconciler struct {
	client.Client
	Scheme   *runtime.Scheme
	Recorder record.EventRecorder
}

// +kubebuilder:rbac:groups=agentscope.io,resources=mcpservers,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=agentscope.io,resources=mcpservers/status,verbs=get;update;patch
// +kubebuilder:rbac:groups="",resources=secrets,verbs=get;list;watch

func (r *MCPServerReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	logger := log.FromContext(ctx)

	var mcpServer v1alpha1.MCPServer
	if err := r.Get(ctx, req.NamespacedName, &mcpServer); err != nil {
		if errors.IsNotFound(err) {
			return ctrl.Result{}, nil
		}
		return ctrl.Result{}, err
	}

	logger.Info("reconciling MCPServer", "name", mcpServer.Name)

	// Validate configuration
	if mcpServer.Spec.Type == v1alpha1.MCPServerTypeRemote && mcpServer.Spec.Remote == nil {
		cond := v1alpha1.Condition{
			Type:               v1alpha1.ConditionAccepted,
			Status:             metav1.ConditionFalse,
			LastTransitionTime: metav1.Now(),
			Reason:             "InvalidConfig",
			Message:            "remote config required for Remote type",
		}
		setConditionInList(&mcpServer.Status.Conditions, cond)
		return ctrl.Result{}, r.Status().Update(ctx, &mcpServer)
	}

	if mcpServer.Spec.Type == v1alpha1.MCPServerTypeStdio && mcpServer.Spec.Stdio == nil {
		cond := v1alpha1.Condition{
			Type:               v1alpha1.ConditionAccepted,
			Status:             metav1.ConditionFalse,
			LastTransitionTime: metav1.Now(),
			Reason:             "InvalidConfig",
			Message:            "stdio config required for Stdio type",
		}
		setConditionInList(&mcpServer.Status.Conditions, cond)
		return ctrl.Result{}, r.Status().Update(ctx, &mcpServer)
	}

	// Mark as accepted
	setConditionInList(&mcpServer.Status.Conditions, v1alpha1.Condition{
		Type:               v1alpha1.ConditionAccepted,
		Status:             metav1.ConditionTrue,
		LastTransitionTime: metav1.Now(),
		Reason:             "ConfigValid",
	})

	// Tool discovery
	if mcpServer.Spec.Type == v1alpha1.MCPServerTypeStdio {
		logger.Info("skipping tool discovery for Stdio server", "name", mcpServer.Name)
		return ctrl.Result{RequeueAfter: 5 * time.Minute}, r.Status().Update(ctx, &mcpServer)
	}

	// Resolve headers from Secret references
	headers, err := r.resolveHeaders(ctx, mcpServer.Namespace, mcpServer.Spec.Remote.HeadersFrom)
	if err != nil {
		metrics.RecordReconcileError("mcpserver", "resolve_headers_failed")
		setConditionInList(&mcpServer.Status.Conditions, v1alpha1.Condition{
			Type:               v1alpha1.ConditionDiscovered,
			Status:             metav1.ConditionFalse,
			LastTransitionTime: metav1.Now(),
			Reason:             "DiscoveryFailed",
			Message:            fmt.Sprintf("failed to resolve headers: %v", err),
		})
		r.Recorder.Eventf(&mcpServer, corev1.EventTypeWarning, "DiscoveryFailed", "failed to resolve headers: %v", err)
		return ctrl.Result{RequeueAfter: 5 * time.Minute}, r.Status().Update(ctx, &mcpServer)
	}

	// Discover tools via MCP protocol
	tools, err := mcp.DiscoverTools(ctx, string(mcpServer.Spec.Type), mcpServer.Spec.Remote.URL, headers, discoveryTimeout)
	if err != nil {
		metrics.RecordReconcileError("mcpserver", "discovery_failed")
		setConditionInList(&mcpServer.Status.Conditions, v1alpha1.Condition{
			Type:               v1alpha1.ConditionDiscovered,
			Status:             metav1.ConditionFalse,
			LastTransitionTime: metav1.Now(),
			Reason:             "DiscoveryFailed",
			Message:            fmt.Sprintf("tool discovery failed: %v", err),
		})
		r.Recorder.Eventf(&mcpServer, corev1.EventTypeWarning, "DiscoveryFailed", "tool discovery failed: %v", err)
		return ctrl.Result{RequeueAfter: 5 * time.Minute}, r.Status().Update(ctx, &mcpServer)
	}

	// Populate status.discoveredTools
	mcpServer.Status.DiscoveredTools = make([]v1alpha1.DiscoveredTool, len(tools))
	for i, t := range tools {
		mcpServer.Status.DiscoveredTools[i] = v1alpha1.DiscoveredTool{
			Name:        t.Name,
			Description: t.Description,
		}
	}

	setConditionInList(&mcpServer.Status.Conditions, v1alpha1.Condition{
		Type:               v1alpha1.ConditionDiscovered,
		Status:             metav1.ConditionTrue,
		LastTransitionTime: metav1.Now(),
		Reason:             "ToolsDiscovered",
		Message:            fmt.Sprintf("discovered %d tools", len(tools)),
	})

	logger.Info("tool discovery complete", "name", mcpServer.Name, "toolCount", len(tools))

	// ASDP config push to referencing agents is handled by ConfigPushWatcher,
	// which runs on all replicas (not only the leader).

	return ctrl.Result{RequeueAfter: 5 * time.Minute}, r.Status().Update(ctx, &mcpServer)
}

// resolveHeaders reads Secret references from headersFrom and returns a map of
// header name to header value.
func (r *MCPServerReconciler) resolveHeaders(ctx context.Context, namespace string, refs []v1alpha1.HeaderFromRef) (map[string]string, error) {
	if len(refs) == 0 {
		return nil, nil
	}

	headers := make(map[string]string, len(refs))
	for _, ref := range refs {
		var secret corev1.Secret
		if err := r.Get(ctx, types.NamespacedName{
			Namespace: namespace,
			Name:      ref.Name,
		}, &secret); err != nil {
			return nil, fmt.Errorf("reading secret %q: %w", ref.Name, err)
		}

		val, ok := secret.Data[ref.Key]
		if !ok {
			return nil, fmt.Errorf("key %q not found in secret %q", ref.Key, ref.Name)
		}
		headers[ref.Header] = string(val)
	}
	return headers, nil
}

// referencesServer checks whether an Agent references the named MCPServer.
func referencesServer(agent v1alpha1.Agent, mcpName string) bool {
	if agent.Spec.Declarative != nil {
		for _, t := range agent.Spec.Declarative.Tools {
			if t.MCPServer != nil && t.MCPServer.Name == mcpName {
				return true
			}
		}
	}
	if agent.Spec.BYO != nil && agent.Spec.BYO.Overrides != nil {
		for _, t := range agent.Spec.BYO.Overrides.Tools {
			if t.MCPServer != nil && t.MCPServer.Name == mcpName {
				return true
			}
		}
	}
	return false
}

func (r *MCPServerReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&v1alpha1.MCPServer{}).
		Complete(r)
}

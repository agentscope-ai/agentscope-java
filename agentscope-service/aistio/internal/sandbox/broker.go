package sandbox

import (
	"context"
	"fmt"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

var sandboxGVR = schema.GroupVersionResource{
	Group:    "agent-sandbox.sigs.k8s.io",
	Version:  "v1alpha1",
	Resource: "sandboxes",
}

// EXPERIMENTAL: sandbox provisioning is NOT wired into v0.1. The Broker is a
// reference implementation; SandboxBrokerReconciler does not call it yet.
// Enabled only via --enable-experimental.

// Broker translates SandboxClaim CRDs into agent-sandbox Sandbox resources.
type Broker struct {
	client           client.Client
	sandboxNamespace string
}

// NewBroker creates a new sandbox Broker.
func NewBroker(c client.Client, sandboxNamespace string) *Broker {
	if sandboxNamespace == "" {
		sandboxNamespace = "agent-sandboxes"
	}
	return &Broker{
		client:           c,
		sandboxNamespace: sandboxNamespace,
	}
}

// Provision creates an agent-sandbox Sandbox resource from a SandboxClaim.
func (b *Broker) Provision(ctx context.Context, claim *v1alpha1.SandboxClaim) error {
	logger := log.FromContext(ctx)
	logger.Info("provisioning sandbox", "claim", claim.Name, "agent", claim.Spec.AgentRef.Name)

	sandboxName := fmt.Sprintf("%s-%s", claim.Spec.AgentRef.Name, claim.Name)

	// Build unstructured Sandbox resource (agent-sandbox CRD)
	sandbox := &unstructured.Unstructured{}
	sandbox.SetGroupVersionKind(schema.GroupVersionKind{
		Group:   "agent-sandbox.sigs.k8s.io",
		Version: "v1alpha1",
		Kind:    "Sandbox",
	})
	sandbox.SetName(sandboxName)
	sandbox.SetNamespace(b.sandboxNamespace)
	sandbox.SetLabels(map[string]string{
		"agentscope.io/agent":   claim.Spec.AgentRef.Name,
		"agentscope.io/session": claim.Spec.SessionRef.Name,
		"agentscope.io/claim":   claim.Name,
	})

	// Set ownerReference pointing to the SandboxClaim (cascade delete)
	sandbox.SetOwnerReferences([]metav1.OwnerReference{
		{
			APIVersion: v1alpha1.GroupVersion.String(),
			Kind:       "SandboxClaim",
			Name:       claim.Name,
			UID:        claim.UID,
		},
	})

	// Build podTemplate spec from claim
	spec := map[string]interface{}{}

	if claim.Spec.SandboxTemplate.PodTemplate != nil {
		containers := make([]interface{}, 0)
		for _, c := range claim.Spec.SandboxTemplate.PodTemplate.Containers {
			container := map[string]interface{}{
				"name":  c.Name,
				"image": c.Image,
			}
			if c.Resources != nil {
				resources := map[string]interface{}{}
				if c.Resources.Limits.CPU != "" || c.Resources.Limits.Memory != "" {
					limits := map[string]interface{}{}
					if c.Resources.Limits.CPU != "" {
						limits["cpu"] = c.Resources.Limits.CPU
					}
					if c.Resources.Limits.Memory != "" {
						limits["memory"] = c.Resources.Limits.Memory
					}
					resources["limits"] = limits
				}
				container["resources"] = resources
			}
			containers = append(containers, container)
		}
		spec["podTemplate"] = map[string]interface{}{
			"spec": map[string]interface{}{
				"containers": containers,
			},
		}
	}

	if claim.Spec.SandboxTemplate.Lifecycle != nil {
		lifecycle := map[string]interface{}{}
		if claim.Spec.SandboxTemplate.Lifecycle.ShutdownPolicy != "" {
			lifecycle["shutdownPolicy"] = claim.Spec.SandboxTemplate.Lifecycle.ShutdownPolicy
		}
		if claim.Spec.SandboxTemplate.Lifecycle.IdleTimeout != "" {
			lifecycle["idleTimeout"] = claim.Spec.SandboxTemplate.Lifecycle.IdleTimeout
		}
		spec["lifecycle"] = lifecycle
	}

	if claim.Spec.SandboxTemplate.Network != nil && len(claim.Spec.SandboxTemplate.Network.AllowedDomains) > 0 {
		domains := make([]interface{}, len(claim.Spec.SandboxTemplate.Network.AllowedDomains))
		for i, d := range claim.Spec.SandboxTemplate.Network.AllowedDomains {
			domains[i] = d
		}
		spec["network"] = map[string]interface{}{
			"allowedDomains": domains,
		}
	}

	sandbox.Object["spec"] = spec

	if err := b.client.Create(ctx, sandbox); err != nil {
		return fmt.Errorf("creating sandbox resource: %w", err)
	}

	// Update SandboxClaim status
	claim.Status.Phase = v1alpha1.SandboxPhaseBound
	claim.Status.SandboxRef = &v1alpha1.ObjectReference{
		Name:      sandboxName,
		Namespace: b.sandboxNamespace,
	}
	claim.Status.ServiceFQDN = fmt.Sprintf("%s.%s.svc.cluster.local", sandboxName, b.sandboxNamespace)

	return b.client.Status().Update(ctx, claim)
}

// Release deletes the Sandbox resource and marks the claim as released.
func (b *Broker) Release(ctx context.Context, claim *v1alpha1.SandboxClaim) error {
	logger := log.FromContext(ctx)

	if claim.Status.SandboxRef == nil {
		return nil
	}

	logger.Info("releasing sandbox", "sandbox", claim.Status.SandboxRef.Name)

	// Delete the sandbox (will cascade via ownerReference anyway, but explicit is clearer)
	sandbox := &unstructured.Unstructured{}
	sandbox.SetGroupVersionKind(schema.GroupVersionKind{
		Group:   "agent-sandbox.sigs.k8s.io",
		Version: "v1alpha1",
		Kind:    "Sandbox",
	})
	sandbox.SetName(claim.Status.SandboxRef.Name)
	sandbox.SetNamespace(claim.Status.SandboxRef.Namespace)

	if err := b.client.Delete(ctx, sandbox); err != nil {
		logger.Error(err, "failed to delete sandbox resource")
	}

	claim.Status.Phase = v1alpha1.SandboxPhaseReleased
	return b.client.Status().Update(ctx, claim)
}

package sandbox

import (
	"context"
	"time"

	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

// LifecycleManager handles sandbox idle timeout detection and session-termination cleanup.
type LifecycleManager struct {
	client client.Client
	broker *Broker
}

// NewLifecycleManager creates a new LifecycleManager.
func NewLifecycleManager(c client.Client, broker *Broker) *LifecycleManager {
	return &LifecycleManager{
		client: c,
		broker: broker,
	}
}

// CheckIdleTimeout checks all bound sandbox claims for idle timeout expiration.
// Called periodically by the SandboxBrokerController reconcile loop.
func (m *LifecycleManager) CheckIdleTimeout(ctx context.Context, claim *v1alpha1.SandboxClaim) bool {
	if claim.Status.Phase != v1alpha1.SandboxPhaseBound {
		return false
	}

	if claim.Spec.SandboxTemplate.Lifecycle == nil || claim.Spec.SandboxTemplate.Lifecycle.IdleTimeout == "" {
		return false
	}

	timeout, err := time.ParseDuration(claim.Spec.SandboxTemplate.Lifecycle.IdleTimeout)
	if err != nil {
		return false
	}

	// Use the claim's creation time as baseline (in production, would track last activity)
	created := claim.CreationTimestamp.Time
	return time.Since(created) > timeout
}

// HandleSessionTermination releases sandboxes when their associated session terminates.
func (m *LifecycleManager) HandleSessionTermination(ctx context.Context, sessionName, namespace string) error {
	logger := log.FromContext(ctx)

	// Find sandbox claims referencing this session
	var claims v1alpha1.SandboxClaimList
	if err := m.client.List(ctx, &claims, client.InNamespace(namespace)); err != nil {
		return err
	}

	for i := range claims.Items {
		claim := &claims.Items[i]
		if claim.Spec.SessionRef.Name == sessionName && claim.Status.Phase == v1alpha1.SandboxPhaseBound {
			logger.Info("releasing sandbox due to session termination",
				"claim", claim.Name, "session", sessionName)
			if err := m.broker.Release(ctx, claim); err != nil {
				logger.Error(err, "failed to release sandbox", "claim", claim.Name)
			}
		}
	}

	return nil
}

// ReleaseExpired releases all sandboxes that have exceeded their idle timeout.
func (m *LifecycleManager) ReleaseExpired(ctx context.Context, namespace string) error {
	logger := log.FromContext(ctx)

	var claims v1alpha1.SandboxClaimList
	if err := m.client.List(ctx, &claims, client.InNamespace(namespace)); err != nil {
		return err
	}

	for i := range claims.Items {
		claim := &claims.Items[i]
		if m.CheckIdleTimeout(ctx, claim) {
			logger.Info("releasing expired sandbox", "claim", claim.Name)
			if err := m.broker.Release(ctx, claim); err != nil {
				logger.Error(err, "failed to release expired sandbox", "claim", claim.Name)
			}
		}
	}

	return nil
}

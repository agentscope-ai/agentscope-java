package discovery

import (
	"context"
	"fmt"
	"net/http"

	"sigs.k8s.io/controller-runtime/pkg/webhook/admission"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/adapter"
)

// +kubebuilder:webhook:path=/validate-agentscope-io-v1alpha1-agent,mutating=false,failurePolicy=fail,sideEffects=None,groups=agentscope.io,resources=agents,verbs=create;update,versions=v1alpha1,name=vagent.agentscope.io,admissionReviewVersions=v1

// AgentValidator implements a ValidatingWebhook for Agent CRD admission.
type AgentValidator struct {
	decoder admission.Decoder
}

// NewAgentValidator creates the webhook handler.
func NewAgentValidator(decoder admission.Decoder) *AgentValidator {
	return &AgentValidator{decoder: decoder}
}

// Handle validates Agent CRD create/update requests.
func (v *AgentValidator) Handle(ctx context.Context, req admission.Request) admission.Response {
	agent := &v1alpha1.Agent{}
	if err := v.decoder.Decode(req, agent); err != nil {
		return admission.Errored(http.StatusBadRequest, err)
	}

	if err := v.validate(agent); err != nil {
		return admission.Denied(err.Error())
	}

	return admission.Allowed("")
}

func (v *AgentValidator) validate(agent *v1alpha1.Agent) error {
	// Type-specific mutual exclusivity validation
	switch agent.Spec.Type {
	case v1alpha1.AgentTypeDeclarative:
		if agent.Spec.Declarative == nil {
			return fmt.Errorf("spec.declarative is required when type=Declarative")
		}
		if agent.Spec.BYO != nil {
			return fmt.Errorf("spec.byo must be empty when type=Declarative")
		}
		// Declarative agents are built by an adapter, so the runtime must be registered.
		if !adapter.IsRegistered(agent.Spec.Runtime) {
			return fmt.Errorf("unsupported runtime %q: no data plane adapter registered", agent.Spec.Runtime)
		}

	case v1alpha1.AgentTypeBYO:
		if agent.Spec.BYO == nil {
			return fmt.Errorf("spec.byo is required when type=BYO")
		}
		if agent.Spec.Declarative != nil {
			return fmt.Errorf("spec.declarative must be empty when type=BYO")
		}

		// BYO: image and workloadRef are mutually exclusive
		hasImage := agent.Spec.BYO.Image != ""
		hasWorkloadRef := agent.Spec.BYO.WorkloadRef != nil
		if !hasImage && !hasWorkloadRef {
			return fmt.Errorf("spec.byo must specify either image or workloadRef")
		}
		if hasImage && hasWorkloadRef {
			return fmt.Errorf("spec.byo.image and spec.byo.workloadRef are mutually exclusive")
		}

		// workloadRef mode: replicas and resources don't apply
		if hasWorkloadRef {
			if agent.Spec.BYO.Replicas != nil {
				return fmt.Errorf("spec.byo.replicas is not applicable in workloadRef mode")
			}
		}
		// image mode: the control plane builds the Deployment via an adapter.
		if hasImage && !adapter.IsRegistered(agent.Spec.Runtime) {
			return fmt.Errorf("unsupported runtime %q: no data plane adapter registered", agent.Spec.Runtime)
		}

	default:
		return fmt.Errorf("spec.type must be Declarative or BYO, got %q", agent.Spec.Type)
	}

	return nil
}

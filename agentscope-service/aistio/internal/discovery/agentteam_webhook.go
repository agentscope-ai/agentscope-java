package discovery

import (
	"context"
	"fmt"
	"net/http"
	"time"

	"sigs.k8s.io/controller-runtime/pkg/webhook/admission"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

// +kubebuilder:webhook:path=/validate-agentscope-io-v1alpha1-agentteam,mutating=false,failurePolicy=fail,sideEffects=None,groups=agentscope.io,resources=agentteams,verbs=create;update,versions=v1alpha1,name=vagentteam.agentscope.io,admissionReviewVersions=v1

// AgentTeamValidator implements a ValidatingWebhook for AgentTeam CRD admission.
type AgentTeamValidator struct {
	decoder admission.Decoder
}

// NewAgentTeamValidator creates the webhook handler.
func NewAgentTeamValidator(decoder admission.Decoder) *AgentTeamValidator {
	return &AgentTeamValidator{decoder: decoder}
}

// Handle validates AgentTeam CRD create/update requests.
func (v *AgentTeamValidator) Handle(ctx context.Context, req admission.Request) admission.Response {
	team := &v1alpha1.AgentTeam{}
	if err := v.decoder.Decode(req, team); err != nil {
		return admission.Errored(http.StatusBadRequest, err)
	}

	if err := v.validate(team); err != nil {
		return admission.Denied(err.Error())
	}

	return admission.Allowed("")
}

func (v *AgentTeamValidator) validate(team *v1alpha1.AgentTeam) error {
	// Lead agentRef.name must be non-empty.
	if team.Spec.Lead.AgentRef.Name == "" {
		return fmt.Errorf("spec.lead.agentRef.name is required")
	}

	// Member names must be unique and each agentRef.name must be non-empty.
	seen := make(map[string]struct{}, len(team.Spec.Members))
	for i, m := range team.Spec.Members {
		if m.Name == "" {
			return fmt.Errorf("spec.members[%d].name is required", i)
		}
		if _, dup := seen[m.Name]; dup {
			return fmt.Errorf("spec.members[%d].name %q is duplicated", i, m.Name)
		}
		seen[m.Name] = struct{}{}

		if m.AgentRef.Name == "" {
			return fmt.Errorf("spec.members[%d].agentRef.name is required", i)
		}
	}

	// DynamicMembers: maxTotal >= static members + 1 (lead).
	if team.Spec.DynamicMembers != nil && team.Spec.DynamicMembers.Enabled {
		minRequired := int32(len(team.Spec.Members)) + 1 // +1 for lead
		if team.Spec.DynamicMembers.MaxTotal < minRequired {
			return fmt.Errorf("spec.dynamicMembers.maxTotal (%d) must be >= number of static members + 1 (lead) (%d)",
				team.Spec.DynamicMembers.MaxTotal, minRequired)
		}
	}

	// ShutdownPolicy validation.
	if team.Spec.Config != nil && team.Spec.Config.ShutdownPolicy != "" {
		switch team.Spec.Config.ShutdownPolicy {
		case "all-complete", "lead-decides", "manual", "timeout":
			// valid
		default:
			return fmt.Errorf("spec.config.shutdownPolicy must be one of: all-complete, lead-decides, manual, timeout; got %q",
				team.Spec.Config.ShutdownPolicy)
		}
	}

	// Lifecycle maxDuration must be a valid Go duration.
	if team.Spec.Lifecycle != nil && team.Spec.Lifecycle.MaxDuration != "" {
		if _, err := time.ParseDuration(team.Spec.Lifecycle.MaxDuration); err != nil {
			return fmt.Errorf("spec.lifecycle.maxDuration %q is not a valid duration: %v",
				team.Spec.Lifecycle.MaxDuration, err)
		}
	}

	return nil
}

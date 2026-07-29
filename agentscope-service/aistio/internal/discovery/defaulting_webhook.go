package discovery

import (
	"context"
	"encoding/json"
	"net/http"

	"sigs.k8s.io/controller-runtime/pkg/webhook/admission"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

// +kubebuilder:webhook:path=/mutate-agentscope-io-v1alpha1-agent,mutating=true,failurePolicy=fail,sideEffects=None,groups=agentscope.io,resources=agents,verbs=create;update,versions=v1alpha1,name=magent.agentscope.io,admissionReviewVersions=v1

// AgentDefaulter implements a MutatingWebhook that sets defaults on Agent CRD
// creation and update.
type AgentDefaulter struct {
	decoder admission.Decoder
}

// NewAgentDefaulter creates the defaulting webhook handler.
func NewAgentDefaulter(decoder admission.Decoder) *AgentDefaulter {
	return &AgentDefaulter{decoder: decoder}
}

// Handle applies defaults to incoming Agent resources.
func (d *AgentDefaulter) Handle(ctx context.Context, req admission.Request) admission.Response {
	agent := &v1alpha1.Agent{}
	if err := d.decoder.Decode(req, agent); err != nil {
		return admission.Errored(http.StatusBadRequest, err)
	}

	d.setDefaults(agent)

	marshaled, err := json.Marshal(agent)
	if err != nil {
		return admission.Errored(http.StatusInternalServerError, err)
	}
	return admission.PatchResponseFromRaw(req.Object.Raw, marshaled)
}

func (d *AgentDefaulter) setDefaults(agent *v1alpha1.Agent) {
	if agent.Spec.Type == v1alpha1.AgentTypeDeclarative && agent.Spec.Declarative != nil {
		decl := agent.Spec.Declarative
		if decl.Replicas == nil {
			one := int32(1)
			decl.Replicas = &one
		}
		if decl.AgentConfig.MaxTurns == 0 {
			decl.AgentConfig.MaxTurns = 50
		}
		if decl.AgentConfig.SessionAffinity == "" {
			decl.AgentConfig.SessionAffinity = "none"
		}
	}
	if agent.Spec.Type == v1alpha1.AgentTypeBYO && agent.Spec.BYO != nil {
		if agent.Spec.BYO.AgentPort == 0 {
			agent.Spec.BYO.AgentPort = 8080
		}
		if agent.Spec.BYO.ContractPath == "" {
			agent.Spec.BYO.ContractPath = "/agentscope"
		}
	}
}

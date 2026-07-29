package discovery

import (
	"context"
	"fmt"
	"net/http"

	"sigs.k8s.io/controller-runtime/pkg/webhook/admission"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

// +kubebuilder:webhook:path=/validate-agentscope-io-v1alpha1-modelconfig,mutating=false,failurePolicy=fail,sideEffects=None,groups=agentscope.io,resources=modelconfigs,verbs=create;update,versions=v1alpha1,name=vmodelconfig.agentscope.io,admissionReviewVersions=v1

// ModelConfigValidator implements a ValidatingWebhook for ModelConfig CRD admission.
type ModelConfigValidator struct {
	decoder admission.Decoder
}

// NewModelConfigValidator creates the webhook handler.
func NewModelConfigValidator(decoder admission.Decoder) *ModelConfigValidator {
	return &ModelConfigValidator{decoder: decoder}
}

// Handle validates ModelConfig CRD create/update requests.
func (v *ModelConfigValidator) Handle(ctx context.Context, req admission.Request) admission.Response {
	mc := &v1alpha1.ModelConfig{}
	if err := v.decoder.Decode(req, mc); err != nil {
		return admission.Errored(http.StatusBadRequest, err)
	}

	if err := v.validate(mc); err != nil {
		return admission.Denied(err.Error())
	}

	return admission.Allowed("")
}

func (v *ModelConfigValidator) validate(mc *v1alpha1.ModelConfig) error {
	if mc.Spec.Provider == "" {
		return fmt.Errorf("spec.provider is required")
	}
	if mc.Spec.Model == "" {
		return fmt.Errorf("spec.model is required")
	}

	// If apiKeySecret is set, apiKeySecretKey must also be non-empty.
	if mc.Spec.APIKeySecret != "" && mc.Spec.APIKeySecretKey == "" {
		return fmt.Errorf("spec.apiKeySecretKey is required when spec.apiKeySecret is set")
	}

	return nil
}

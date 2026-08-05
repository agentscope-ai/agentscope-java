// Copyright 2024-2026 the original author or authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

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

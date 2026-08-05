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

// +kubebuilder:webhook:path=/validate-agentscope-io-v1alpha1-mcpserver,mutating=false,failurePolicy=fail,sideEffects=None,groups=agentscope.io,resources=mcpservers,verbs=create;update,versions=v1alpha1,name=vmcpserver.agentscope.io,admissionReviewVersions=v1

// MCPServerValidator implements a ValidatingWebhook for MCPServer CRD admission.
type MCPServerValidator struct {
	decoder admission.Decoder
}

// NewMCPServerValidator creates the webhook handler.
func NewMCPServerValidator(decoder admission.Decoder) *MCPServerValidator {
	return &MCPServerValidator{decoder: decoder}
}

// Handle validates MCPServer CRD create/update requests.
func (v *MCPServerValidator) Handle(ctx context.Context, req admission.Request) admission.Response {
	server := &v1alpha1.MCPServer{}
	if err := v.decoder.Decode(req, server); err != nil {
		return admission.Errored(http.StatusBadRequest, err)
	}

	if err := v.validate(server); err != nil {
		return admission.Denied(err.Error())
	}

	return admission.Allowed("")
}

func (v *MCPServerValidator) validate(server *v1alpha1.MCPServer) error {
	switch server.Spec.Type {
	case v1alpha1.MCPServerTypeRemote:
		if server.Spec.Remote == nil {
			return fmt.Errorf("spec.remote is required when type=Remote")
		}
		if server.Spec.Remote.URL == "" {
			return fmt.Errorf("spec.remote.url is required when type=Remote")
		}
	case v1alpha1.MCPServerTypeStdio:
		if server.Spec.Stdio == nil {
			return fmt.Errorf("spec.stdio is required when type=Stdio")
		}
		if server.Spec.Stdio.Command == "" {
			return fmt.Errorf("spec.stdio.command is required when type=Stdio")
		}
	default:
		return fmt.Errorf("spec.type must be Remote or Stdio, got %q", server.Spec.Type)
	}

	return nil
}

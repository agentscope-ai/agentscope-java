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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package collaboration

import (
	"encoding/json"
	"testing"
)

func TestValidateRuntimeBindingPolicy(t *testing.T) {
	valid := []json.RawMessage{
		nil,
		json.RawMessage(`{"selectionMode":"ordered","fallbackMode":"disabled","candidates":[{"binding":{"agentId":"11111111-1111-1111-1111-111111111111","bindingId":"21111111-1111-1111-1111-111111111111","kind":"managed","managedOwnerRef":"owner","managedDefinitionRef":"agent"}}]}`),
		json.RawMessage(`{"selectionMode":"ordered","fallbackMode":"fresh","candidates":[{"binding":{"agentId":"11111111-1111-1111-1111-111111111111","bindingId":"21111111-1111-1111-1111-111111111111","kind":"external-application","instanceSelector":{"instance":"worker-1"}}},{"binding":{"agentId":"11111111-1111-1111-1111-111111111111","bindingId":"31111111-1111-1111-1111-111111111111","kind":"hosted-runtime","runtimeProfileId":"41111111-1111-1111-1111-111111111111","runtimePoolId":"51111111-1111-1111-1111-111111111111"}}]}`),
	}
	for _, raw := range valid {
		if err := ValidateRuntimeBindingPolicy(raw); err != nil {
			t.Fatalf("valid policy %s rejected: %v", raw, err)
		}
	}
	for _, raw := range []json.RawMessage{
		json.RawMessage(`{"selectionMode":"random","fallbackMode":"disabled","candidates":[{"binding":{"kind":"managed","managedOwnerRef":"owner","managedAgentRef":"agent"}}]}`),
		json.RawMessage(`{"selectionMode":"ordered","fallbackMode":"disabled","candidates":[{"binding":{"kind":"managed"}}]}`),
		json.RawMessage(`{"selectionMode":"ordered","fallbackMode":"automatic","candidates":[{"binding":{"kind":"unsupported-runtime"}}]}`),
		json.RawMessage(`{"selectionMode":"ordered","fallbackMode":"disabled","candidates":[{"binding":{"kind":"managed","managedOwnerRef":"owner","managedAgentRef":"agent"},"securityConstraints":[]}]}`),
	} {
		if err := ValidateRuntimeBindingPolicy(raw); err == nil {
			t.Fatalf("invalid policy %s accepted", raw)
		}
	}
}

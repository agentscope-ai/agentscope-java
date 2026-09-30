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

package httpapi

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"testing"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

func TestQoderFullAccessPermissionCanBeSavedPerAgent(t *testing.T) {
	s, st := accessTestServer(t)
	ctx := context.Background()
	profile, err := st.RuntimeRegistry().UpsertRuntimeProfile(ctx, &controlmodel.RuntimeProfile{Tenant: "default", Namespace: "engineering", Name: "qoder", Provider: "qoder", Runtime: "qodercli", Configuration: json.RawMessage(`{"permissionMode":"default"}`)})
	if err != nil {
		t.Fatal(err)
	}
	pool, err := st.RuntimeRegistry().UpsertRuntimePool(ctx, &controlmodel.RuntimePool{Tenant: "default", Namespace: "engineering", Name: "coding"})
	if err != nil {
		t.Fatal(err)
	}
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "default", Namespace: "engineering", AgentKey: "qoder", DisplayName: "Qoder", OwnerType: "user", OwnerRef: "alice", Status: controlmodel.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	config, _ := json.Marshal(controlmodel.HostedBindingConfiguration{RuntimeProfileID: profile.ID, RuntimePoolID: pool.ID})
	binding, err := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{AgentID: agent.ID, Tenant: agent.Tenant, Namespace: agent.Namespace, Kind: controlmodel.DataPlaneHostedRuntime, Configuration: config, Enabled: true, Priority: 100})
	if err != nil {
		t.Fatal(err)
	}
	path := "/api/v1/agents/" + agent.ID.String() + "/hosted-settings"
	body := fmt.Sprintf(`{"bindingVersion":%d,"executionOverrides":{"providerConfiguration":{"permissionMode":"bypass_permissions"}}}`, binding.Version)
	if w := accessRequest(s, "carol", "PATCH", path, body); w.Code < 400 {
		t.Fatal("viewer changed execution permissions")
	}
	if w := accessRequest(s, "alice", "PATCH", path, body); w.Code != 200 {
		t.Fatalf("save: %d %s", w.Code, w.Body.String())
	}
	if w := accessRequest(s, "alice", "GET", path, ""); w.Code != 200 || !strings.Contains(w.Body.String(), `"permissionMode":"bypass_permissions"`) {
		t.Fatalf("read saved permission: %d %s", w.Code, w.Body.String())
	}
	policy, err := st.Orchestration().GetRuntimePolicy(ctx, agent.Tenant, agent.Namespace, agent.ID.String())
	if err != nil || len(policy.Candidates) != 1 {
		t.Fatalf("policy: %+v %v", policy, err)
	}
	resolved, err := policy.Candidates[0].Binding.ExecutionOverrides.ResolveProviderConfiguration(profile.Configuration)
	if err != nil || !strings.Contains(string(resolved), `"permissionMode":"bypass_permissions"`) {
		t.Fatalf("dispatch configuration: %s %v", resolved, err)
	}
	stored, err := st.RuntimeRegistry().GetRuntimeProfileByID(ctx, profile.ID)
	if err != nil || string(stored.Configuration) != `{"permissionMode":"default"}` {
		t.Fatalf("shared profile changed: %+v %v", stored, err)
	}
}

func TestQoderPermissionModesValidateExplicitValues(t *testing.T) {
	profile := &controlmodel.RuntimeProfile{Provider: "qoder", Configuration: json.RawMessage(`{"permissionMode":"default"}`)}
	for _, mode := range []string{"default", "auto", "accept_edits", "dont_ask", "bypass_permissions"} {
		raw, _ := json.Marshal(map[string]string{"permissionMode": mode})
		if err := validateHostedExecutionOverrides(profile, &controlmodel.HostedExecutionOverrides{ProviderConfiguration: raw}); err != nil {
			t.Errorf("%s: %v", mode, err)
		}
	}
	if err := validateHostedExecutionOverrides(profile, &controlmodel.HostedExecutionOverrides{ProviderConfiguration: json.RawMessage(`{"permissionMode":"bypass_typo"}`)}); err == nil {
		t.Fatal("unknown Qoder mode accepted")
	}
}

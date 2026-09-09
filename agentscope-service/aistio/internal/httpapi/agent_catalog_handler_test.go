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

package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/features"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestExternalAgentRegistrationIsOpenAndKeepsStableIdentity(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	server := NewServer(ServerOptions{Store: st, InternalToken: "bootstrap-secret", AuthToken: "console-secret"})

	request := func(body string, headers map[string]string) *httptest.ResponseRecorder {
		t.Helper()
		req := httptest.NewRequest(http.MethodPost, "/api/v1/agent-registrations", bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
		for key, value := range headers {
			req.Header.Set(key, value)
		}
		response := httptest.NewRecorder()
		server.router.ServeHTTP(response, req)
		return response
	}

	first := request(`{"tenant":"acme","namespace":"engineering","agentKey":"reviewer","instanceKey":"pod-1","capacity":2}`,
		map[string]string{"X-Builder-Internal-Token": "bootstrap-secret"})
	if first.Code != http.StatusCreated {
		t.Fatalf("first registration status=%d body=%s", first.Code, first.Body.String())
	}
	var created struct {
		Agent                  controlmodel.Agent         `json:"agent"`
		Binding                controlmodel.AgentBinding  `json:"binding"`
		Instance               controlmodel.AgentInstance `json:"instance"`
		RegistrationCredential string                     `json:"registrationCredential"`
	}
	if err := json.Unmarshal(first.Body.Bytes(), &created); err != nil {
		t.Fatal(err)
	}
	if created.RegistrationCredential == "" || created.Agent.ID != created.Instance.AgentID || created.Binding.ID != created.Instance.BindingID {
		t.Fatalf("registration did not return one stable identity: %+v", created)
	}

	second := request(`{"tenant":"acme","namespace":"engineering","agentKey":"reviewer","instanceKey":"pod-2","capacity":2}`, nil)
	if second.Code != http.StatusCreated {
		t.Fatalf("anonymous registration status=%d body=%s", second.Code, second.Body.String())
	}
	var scaled struct {
		Agent    controlmodel.Agent         `json:"agent"`
		Binding  controlmodel.AgentBinding  `json:"binding"`
		Instance controlmodel.AgentInstance `json:"instance"`
	}
	if err := json.Unmarshal(second.Body.Bytes(), &scaled); err != nil {
		t.Fatal(err)
	}
	if scaled.Agent.ID != created.Agent.ID || scaled.Binding.ID != created.Binding.ID || scaled.Instance.ID == created.Instance.ID {
		t.Fatalf("scale-out created a second logical identity: first=%+v second=%+v", created, scaled)
	}

	forged := request(`{"tenant":"acme","namespace":"engineering","agentKey":"reviewer","instanceKey":"pod-forged"}`,
		map[string]string{"X-Agent-Registration-Credential": "asreg_forged"})
	if forged.Code != http.StatusCreated {
		t.Fatalf("ignored legacy credential status=%d body=%s", forged.Code, forged.Body.String())
	}
	bootstrapClaim := request(`{"tenant":"acme","namespace":"engineering","agentKey":"reviewer","instanceKey":"pod-bootstrap"}`,
		map[string]string{"X-Builder-Internal-Token": "bootstrap-secret"})
	if bootstrapClaim.Code != http.StatusCreated {
		t.Fatalf("ignored bootstrap token status=%d body=%s", bootstrapClaim.Code, bootstrapClaim.Body.String())
	}

	instances, err := st.RuntimeRegistry().ListAgentInstances(ctx, "acme", "engineering", created.Agent.ID)
	if err != nil || len(instances) != 4 {
		t.Fatalf("logical Agent should have four instances: instances=%+v err=%v", instances, err)
	}
}

func TestRuntimeHostRegistrationCreatesFlattenedAgentRuntime(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	server := NewServer(ServerOptions{
		Store: st, InternalToken: "runtime-secret", AuthToken: "console-secret",
		Features: features.Gates{RuntimeHost: true},
	})

	registration := `{
		"tenant":"acme","namespace":"engineering","hostKey":"macbook.local","poolName":"coding-default","capacity":2,
		"capabilities":{"providers":{"codex":"codex-cli 1.2.3"},"providerCapabilities":{"codex":{"displayName":"Codex","runtime":"codex","workspace":{"supported":true,"mode":"cwd"}}}}
	}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/runtime-hosts/register", bytes.NewBufferString(registration))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Builder-Internal-Token", "runtime-secret")
	response := httptest.NewRecorder()
	server.router.ServeHTTP(response, req)
	if response.Code != http.StatusOK {
		t.Fatalf("registration status=%d body=%s", response.Code, response.Body.String())
	}

	req = httptest.NewRequest(http.MethodGet,
		"/api/v1/agents/runtime-options?tenant=acme&namespace=engineering", nil)
	req.Header.Set("Authorization", "Bearer console-secret")
	response = httptest.NewRecorder()
	server.router.ServeHTTP(response, req)
	if response.Code != http.StatusOK {
		t.Fatalf("runtime options status=%d body=%s", response.Code, response.Body.String())
	}
	var body struct {
		Runtimes []struct {
			Name             string    `json:"name"`
			Provider         string    `json:"provider"`
			RuntimeProfileID uuid.UUID `json:"runtimeProfileId"`
			RuntimePoolID    uuid.UUID `json:"runtimePoolId"`
			HostCount        int       `json:"hostCount"`
		} `json:"runtimes"`
	}
	if err = json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Runtimes) != 1 || body.Runtimes[0].Provider != "codex" ||
		body.Runtimes[0].Name != "Codex (macbook.local)" || body.Runtimes[0].HostCount != 1 ||
		body.Runtimes[0].RuntimeProfileID == uuid.Nil || body.Runtimes[0].RuntimePoolID == uuid.Nil {
		t.Fatalf("unexpected flattened runtimes: %+v", body.Runtimes)
	}
	profile, err := st.RuntimeRegistry().GetRuntimeProfile(ctx, "acme", "engineering", "auto-codex")
	if err != nil || profile.Runtime != "codex" {
		t.Fatalf("automatic profile=%+v err=%v", profile, err)
	}
	if string(profile.Configuration) != `{"sandbox":"workspace-write"}` {
		t.Fatalf("automatic Codex configuration=%s", profile.Configuration)
	}
}

func TestAutomaticRuntimeProfilesHaveUsableHeadlessDefaults(t *testing.T) {
	tests := map[string]map[string]any{
		"codex":       {"sandbox": "workspace-write"},
		"claude-code": {"permissionMode": "default"},
		"qoder":       {"permissionMode": "default", "strictMCPConfig": true},
		"qwenpaw":     {"permissionMode": "default"},
		"openclaw":    {"codeMode": "auto", "timeoutSeconds": float64(600)},
	}
	for provider, expected := range tests {
		var configuration map[string]any
		if err := json.Unmarshal(automaticRuntimeProfileConfiguration(provider), &configuration); err != nil {
			t.Fatalf("%s configuration: %v", provider, err)
		}
		for key, want := range expected {
			if got := configuration[key]; got != want {
				t.Errorf("%s %s=%v, want %v", provider, key, got, want)
			}
		}
		if provider == "qoder" || provider == "claude-code" {
			allowed, ok := configuration["allowedTools"].([]any)
			if !ok || len(allowed) != 1 || allowed[0] != "mcp__agentscope-collaboration__*" {
				t.Errorf("%s collaboration allowlist=%v", provider, configuration["allowedTools"])
			}
		}
	}
}

func TestRuntimeHostRegistrationUpgradesOnlyLegacyEmptyAutomaticProfile(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	if _, err = st.RuntimeRegistry().UpsertRuntimeProfile(ctx, &controlmodel.RuntimeProfile{
		Tenant: "acme", Namespace: "engineering", Name: "auto-qoder", Provider: "qoder",
		Runtime: "qodercli", Configuration: json.RawMessage(`{}`), Requirements: json.RawMessage(`{}`),
	}); err != nil {
		t.Fatal(err)
	}
	server := NewServer(ServerOptions{Store: st, InternalToken: "runtime-secret", AuthToken: "console-secret",
		Features: features.Gates{RuntimeHost: true}})
	register := func() {
		t.Helper()
		body := `{"tenant":"acme","namespace":"engineering","hostKey":"macbook.local","poolName":"coding-default","capacity":1,"capabilities":{"providers":{"qoder":"1.0.37"},"providerCapabilities":{"qoder":{"runtime":"qodercli"}}}}`
		req := httptest.NewRequest(http.MethodPost, "/api/v1/runtime-hosts/register", bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Builder-Internal-Token", "runtime-secret")
		response := httptest.NewRecorder()
		server.router.ServeHTTP(response, req)
		if response.Code != http.StatusOK {
			t.Fatalf("registration status=%d body=%s", response.Code, response.Body.String())
		}
	}
	register()
	profile, err := st.RuntimeRegistry().GetRuntimeProfile(ctx, "acme", "engineering", "auto-qoder")
	if err != nil || string(profile.Configuration) != `{"permissionMode":"default","allowedTools":["mcp__agentscope-collaboration__*"],"strictMCPConfig":true}` {
		t.Fatalf("upgraded profile=%+v err=%v", profile, err)
	}
	profile.Configuration = json.RawMessage(`{"permissionMode":"auto"}`)
	if _, err = st.RuntimeRegistry().UpsertRuntimeProfile(ctx, profile); err != nil {
		t.Fatal(err)
	}
	register()
	profile, err = st.RuntimeRegistry().GetRuntimeProfile(ctx, "acme", "engineering", "auto-qoder")
	if err != nil || string(profile.Configuration) != `{"permissionMode":"auto"}` {
		t.Fatalf("custom profile was overwritten: profile=%+v err=%v", profile, err)
	}
}

func TestHostedQoderSettingsAcceptStructuredKnownFields(t *testing.T) {
	profile := &controlmodel.RuntimeProfile{Provider: "qoder", Configuration: automaticRuntimeProfileConfiguration("qoder")}
	overrides := &controlmodel.HostedExecutionOverrides{ProviderConfiguration: json.RawMessage(`{
		"permissionMode":"auto",
		"allowedTools":["Read","mcp__agentscope-collaboration__*"],
		"disallowedTools":["Bash(rm -rf:*)"],
		"maxTurns":24,
		"maxOutputTokens":8000,
		"contextWindow":120000,
		"strictMCPConfig":true,
		"agent":"reviewer"
	}`)}
	if err := validateHostedExecutionOverrides(profile, overrides); err != nil {
		t.Fatalf("valid Qoder settings: %v", err)
	}
	overrides.ProviderConfiguration = json.RawMessage(`{"allowedTools":"Read"}`)
	if err := validateHostedExecutionOverrides(profile, overrides); err == nil {
		t.Fatal("expected a type error for allowedTools")
	}
}

func TestHostedAgentSettingsArePerAgentAndUpdateRuntimePolicy(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	profile, _ := st.RuntimeRegistry().UpsertRuntimeProfile(ctx, &controlmodel.RuntimeProfile{
		Tenant: "acme", Namespace: "engineering", Name: "auto-codex", Provider: "codex", Runtime: "codex",
		Configuration: json.RawMessage(`{"sandbox":"workspace-write"}`),
	})
	pool, _ := st.RuntimeRegistry().UpsertRuntimePool(ctx, &controlmodel.RuntimePool{
		Tenant: "acme", Namespace: "engineering", Name: "coding-default",
	})
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "acme", Namespace: "engineering",
		AgentKey: "reviewer", DisplayName: "Reviewer", OwnerType: "user", OwnerRef: "ken", Status: controlmodel.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	bindingConfiguration, _ := json.Marshal(controlmodel.HostedBindingConfiguration{RuntimeProfileID: profile.ID, RuntimePoolID: pool.ID})
	binding, err := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{AgentID: agent.ID,
		Tenant: agent.Tenant, Namespace: agent.Namespace, Kind: controlmodel.DataPlaneHostedRuntime,
		Configuration: bindingConfiguration, Priority: 100, Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	runtimeBinding, _ := binding.RuntimeBinding()
	policy, err := st.Orchestration().PutRuntimePolicy(ctx, &controlmodel.AgentRuntimePolicy{Tenant: agent.Tenant,
		Namespace: agent.Namespace, AgentRef: agent.ID.String(), SelectionMode: "ordered", FallbackMode: "disabled",
		Candidates: []controlmodel.RuntimeBindingCandidate{{Binding: runtimeBinding}}})
	if err != nil {
		t.Fatal(err)
	}
	server := NewServer(ServerOptions{Store: st, AuthToken: "console-secret"})

	body := fmt.Sprintf(`{"runtimeProfileId":%q,"runtimePoolId":%q,"bindingVersion":%d,"policyVersion":%d,"maxConcurrency":3,"executionOverrides":{"reasoningEffort":"high","serviceTier":"priority","providerConfiguration":{"sandbox":"read-only"},"customArgs":["--profile","work"]}}`,
		profile.ID, pool.ID, binding.Version, policy.Version)
	req := httptest.NewRequest(http.MethodPatch, "/api/v1/agents/"+agent.ID.String()+"/hosted-settings", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer console-secret")
	response := httptest.NewRecorder()
	server.router.ServeHTTP(response, req)
	if response.Code != http.StatusOK {
		t.Fatalf("patch hosted settings status=%d body=%s", response.Code, response.Body.String())
	}

	storedProfile, _ := st.RuntimeRegistry().GetRuntimeProfileByID(ctx, profile.ID)
	if string(storedProfile.Configuration) != `{"sandbox":"workspace-write"}` {
		t.Fatalf("shared profile was mutated: %s", storedProfile.Configuration)
	}
	updatedBinding, _ := st.AgentCatalog().GetBinding(ctx, binding.ID)
	var hosted controlmodel.HostedBindingConfiguration
	if err = json.Unmarshal(updatedBinding.Configuration, &hosted); err != nil || hosted.ExecutionOverrides == nil ||
		hosted.ExecutionOverrides.ReasoningEffort != "high" || len(hosted.ExecutionOverrides.CustomArgs) != 2 {
		t.Fatalf("binding overrides=%+v err=%v", hosted.ExecutionOverrides, err)
	}
	updatedPolicy, _ := st.Orchestration().GetRuntimePolicy(ctx, agent.Tenant, agent.Namespace, agent.ID.String())
	if updatedPolicy.MaxConcurrency != 3 || updatedPolicy.Candidates[0].Binding.ExecutionOverrides == nil ||
		updatedPolicy.Candidates[0].Binding.ExecutionOverrides.ServiceTier != "priority" {
		t.Fatalf("runtime policy was not synchronized: %+v", updatedPolicy)
	}

	badBody := fmt.Sprintf(`{"runtimeProfileId":%q,"runtimePoolId":%q,"bindingVersion":%d,"policyVersion":%d,"executionOverrides":{"customArgs":["--cd","/tmp/escape"]}}`,
		profile.ID, pool.ID, updatedBinding.Version, updatedPolicy.Version)
	req = httptest.NewRequest(http.MethodPatch, "/api/v1/agents/"+agent.ID.String()+"/hosted-settings", bytes.NewBufferString(badBody))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer console-secret")
	response = httptest.NewRecorder()
	server.router.ServeHTTP(response, req)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("reserved custom args status=%d body=%s", response.Code, response.Body.String())
	}
}

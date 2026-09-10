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
	"context"
	"encoding/json"
	"testing"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func setupConversationAgent(t *testing.T, configs ...store.Config) (store.Store, *controlmodel.Agent, *controlmodel.AgentBinding, *controlmodel.AgentInstance) {
	t.Helper()
	ctx := context.Background()
	cfg := store.Config{Driver: store.DriverMemory}
	if len(configs) > 0 {
		cfg = configs[0]
	}
	st, err := store.Open(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "t", Namespace: "n",
		AgentKey: "conversation-agent", DisplayName: "Conversation Agent", Status: controlmodel.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	binding, err := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{AgentID: agent.ID,
		Tenant: "t", Namespace: "n", Kind: controlmodel.DataPlaneExternalApplication,
		Configuration: json.RawMessage(`{"instanceSelector":{}}`), Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	instance, err := st.RuntimeRegistry().UpsertAgentInstance(ctx, &controlmodel.AgentInstance{Tenant: "t", Namespace: "n",
		AgentID: agent.ID, BindingID: binding.ID, BackendKind: binding.Kind, InstanceKey: "external-1",
		Health: controlmodel.RuntimeHealthHealthy, Capacity: 2, Capabilities: json.RawMessage(`["conversation-inbound"]`)})
	if err != nil {
		t.Fatal(err)
	}
	runtimeBinding, err := binding.RuntimeBinding()
	if err != nil {
		t.Fatal(err)
	}
	_, err = st.Orchestration().PutRuntimePolicy(ctx, &controlmodel.AgentRuntimePolicy{Tenant: "t", Namespace: "n",
		AgentRef: agent.ID.String(), SelectionMode: "ordered", FallbackMode: "disabled",
		Candidates: []controlmodel.RuntimeBindingCandidate{{Binding: runtimeBinding}}})
	if err != nil {
		t.Fatal(err)
	}
	return st, agent, binding, instance
}

func TestHostProviderResumeUsesAdvertisedAdapterCapability(t *testing.T) {
	capabilities := json.RawMessage(`{"providerCapabilities":{"codex":{"resume":true},"openclaw":{"resume":false}}}`)
	if !hostProviderSupportsResume(capabilities, "codex") {
		t.Fatal("Codex resume capability was not recognized")
	}
	if hostProviderSupportsResume(capabilities, "openclaw") ||
		hostProviderSupportsResume(json.RawMessage(`{"providers":{"codex":"1"}}`), "codex") {
		t.Fatal("provider resume must not be inferred from an opaque session ID or version")
	}
}

func setupHostedConversationAgent(t *testing.T, configs ...store.Config) (store.Store, *controlmodel.Agent,
	*controlmodel.AgentBinding, *controlmodel.RuntimeHost) {
	t.Helper()
	ctx := context.Background()
	cfg := store.Config{Driver: store.DriverMemory}
	if len(configs) > 0 {
		cfg = configs[0]
	}
	st, err := store.Open(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "t", Namespace: "n",
		AgentKey: "hosted-chat", DisplayName: "Hosted Chat", Status: controlmodel.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	profile, err := st.RuntimeRegistry().UpsertRuntimeProfile(ctx, &controlmodel.RuntimeProfile{
		Tenant: "t", Namespace: "n", Name: "codex", Provider: "codex",
		Requirements: json.RawMessage(`{"sandbox":{"network":false}}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	pool, err := st.RuntimeRegistry().UpsertRuntimePool(ctx, &controlmodel.RuntimePool{
		Tenant: "t", Namespace: "n", Name: "coding", HostSelector: json.RawMessage(`{"region":"cn"}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	configuration, _ := json.Marshal(controlmodel.HostedBindingConfiguration{
		RuntimeProfileID: profile.ID, RuntimePoolID: pool.ID,
	})
	binding, err := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{
		AgentID: agent.ID, Tenant: "t", Namespace: "n", Kind: controlmodel.DataPlaneHostedRuntime,
		Configuration: configuration, Enabled: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	runtimeBinding, _ := binding.RuntimeBinding()
	_, err = st.Orchestration().PutRuntimePolicy(ctx, &controlmodel.AgentRuntimePolicy{
		Tenant: "t", Namespace: "n", AgentRef: agent.ID.String(), SelectionMode: "ordered",
		Candidates: []controlmodel.RuntimeBindingCandidate{{Binding: runtimeBinding}},
	})
	if err != nil {
		t.Fatal(err)
	}
	host, err := st.RuntimeRegistry().UpsertRuntimeHost(ctx, &controlmodel.RuntimeHost{
		Tenant: "t", Namespace: "n", HostKey: "hosted-chat-host", PoolName: pool.Name,
		State: controlmodel.RuntimeHostOnline, Capacity: 2, Labels: json.RawMessage(`{"region":"cn"}`),
		Capabilities: json.RawMessage(`{"providers":{"codex":"test"},"providerCapabilities":{"codex":{"resume":true}},"sandbox":{"network":false}}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	return st, agent, binding, host
}

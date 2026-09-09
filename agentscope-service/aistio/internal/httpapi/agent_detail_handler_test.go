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
	"net/http"
	"net/http/httptest"
	"testing"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestAgentDetailOverviewUsesStableAgentIdentity(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "t", Namespace: "n",
		AgentKey: "paw", DisplayName: "Paw", Status: controlmodel.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	binding, err := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{AgentID: agent.ID,
		Tenant: agent.Tenant, Namespace: agent.Namespace, Kind: controlmodel.DataPlaneExternalApplication,
		Configuration: json.RawMessage(`{"instanceSelector":{}}`), Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	runtimeBinding, _ := binding.RuntimeBinding()
	if _, err = st.Orchestration().PutRuntimePolicy(ctx, &controlmodel.AgentRuntimePolicy{Tenant: agent.Tenant,
		Namespace: agent.Namespace, AgentRef: agent.ID.String(), SelectionMode: "ordered", FallbackMode: "disabled",
		Candidates: []controlmodel.RuntimeBindingCandidate{{Binding: runtimeBinding}}}); err != nil {
		t.Fatal(err)
	}
	instance, err := st.RuntimeRegistry().UpsertAgentInstance(ctx, &controlmodel.AgentInstance{Tenant: agent.Tenant,
		Namespace: agent.Namespace, AgentID: agent.ID, BindingID: binding.ID,
		BackendKind: controlmodel.DataPlaneExternalApplication, InstanceKey: "paw-1",
		Health: controlmodel.RuntimeHealthHealthy, Capacity: 4, ActiveSessions: 1,
		Capabilities: json.RawMessage(`["session-reporting","agent-task"]`)})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.Sessions().Upsert(ctx, &store.Session{Tenant: agent.Tenant, Namespace: agent.Namespace,
		AgentID: agent.ID, BindingID: binding.ID, AgentInstanceID: instance.ID,
		InstanceGeneration: instance.Generation, AgentName: agent.AgentKey, SessionID: "online-1",
		OriginType: "runtime", Phase: store.SessionPhaseActive}); err != nil {
		t.Fatal(err)
	}

	server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	req := httptest.NewRequest(http.MethodGet, "/api/v1/agents/"+agent.ID.String()+"/overview", nil)
	req.Header.Set("Authorization", "Bearer console")
	w := httptest.NewRecorder()
	server.router.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("overview status=%d body=%s", w.Code, w.Body.String())
	}
	var overview agentDetailOverview
	if err = json.Unmarshal(w.Body.Bytes(), &overview); err != nil {
		t.Fatal(err)
	}
	if overview.Readiness.State != "ready" || overview.Instances.Healthy != 1 || overview.Sessions.Active != 1 {
		t.Fatalf("unexpected overview: %+v", overview)
	}
	if overview.Usage.TotalTokens != nil || overview.Usage.Status != telemetryNotReporting {
		t.Fatalf("missing telemetry must not be rendered as zero: %+v", overview.Usage)
	}
}

func TestAgentDetailOverviewFailsClosedWithoutCapacity(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agent, _ := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "t", Namespace: "n", AgentKey: "offline", Status: controlmodel.AgentActive})
	binding, _ := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{AgentID: agent.ID, Tenant: "t", Namespace: "n",
		Kind: controlmodel.DataPlaneExternalApplication, Configuration: json.RawMessage(`{"instanceSelector":{}}`), Enabled: true})
	runtimeBinding, _ := binding.RuntimeBinding()
	_, _ = st.Orchestration().PutRuntimePolicy(ctx, &controlmodel.AgentRuntimePolicy{Tenant: "t", Namespace: "n",
		AgentRef: agent.ID.String(), SelectionMode: "ordered", Candidates: []controlmodel.RuntimeBindingCandidate{{Binding: runtimeBinding}}})

	server := NewServer(ServerOptions{Store: st})
	w := httptest.NewRecorder()
	server.router.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/v1/agents/"+agent.ID.String()+"/overview", nil))
	var overview agentDetailOverview
	_ = json.Unmarshal(w.Body.Bytes(), &overview)
	if overview.Readiness.State != "unavailable" {
		t.Fatalf("expected unavailable, got %+v", overview.Readiness)
	}
}

func TestHostedAgentDetailUsesOnDemandHostCapacity(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agent, _ := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "t", Namespace: "n", AgentKey: "hosted", Status: controlmodel.AgentActive})
	profile, _ := st.RuntimeRegistry().UpsertRuntimeProfile(ctx, &controlmodel.RuntimeProfile{Tenant: "t", Namespace: "n", Name: "codex", Provider: "codex"})
	pool, _ := st.RuntimeRegistry().UpsertRuntimePool(ctx, &controlmodel.RuntimePool{Tenant: "t", Namespace: "n", Name: "coding"})
	configuration, _ := json.Marshal(controlmodel.HostedBindingConfiguration{RuntimeProfileID: profile.ID, RuntimePoolID: pool.ID})
	binding, _ := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{AgentID: agent.ID, Tenant: "t", Namespace: "n",
		Kind: controlmodel.DataPlaneHostedRuntime, Configuration: configuration, Enabled: true})
	runtimeBinding, _ := binding.RuntimeBinding()
	_, _ = st.Orchestration().PutRuntimePolicy(ctx, &controlmodel.AgentRuntimePolicy{Tenant: "t", Namespace: "n",
		AgentRef: agent.ID.String(), SelectionMode: "ordered", Candidates: []controlmodel.RuntimeBindingCandidate{{Binding: runtimeBinding}}})
	_, _ = st.RuntimeRegistry().UpsertRuntimeHost(ctx, &controlmodel.RuntimeHost{Tenant: "t", Namespace: "n", HostKey: "host-1",
		PoolName: pool.Name, State: controlmodel.RuntimeHostOnline, Capacity: 2})

	server := NewServer(ServerOptions{Store: st})
	w := httptest.NewRecorder()
	server.router.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/v1/agents/"+agent.ID.String()+"/overview", nil))
	var overview agentDetailOverview
	_ = json.Unmarshal(w.Body.Bytes(), &overview)
	if overview.Readiness.State != "ready" || overview.Readiness.Mode != "on-demand" || overview.Sessions.Status != telemetryNotApplicable {
		t.Fatalf("unexpected hosted readiness: %+v sessions=%+v", overview.Readiness, overview.Sessions)
	}
}

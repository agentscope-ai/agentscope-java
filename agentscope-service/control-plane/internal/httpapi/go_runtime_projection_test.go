// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package httpapi

import (
	"encoding/json"
	"net/http/httptest"
	"slices"
	"testing"
	"time"

	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	serviceapi "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/invocation"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/sessionapi"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

func TestGoRuntimeProjectionUsesFrozenTeamGraphAndCommonAuthorization(t *testing.T) {
	ctx := t.Context()
	st, err := store.Open(ctx, store.DefaultConfig())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	agent, err := st.AgentCatalog().CreateAgent(ctx, &model.Agent{Tenant: "t", Namespace: "n", AgentKey: "hosted", DisplayName: "Hosted", Status: model.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	binding, err := st.AgentCatalog().CreateBinding(ctx, &model.AgentBinding{AgentID: agent.ID, Tenant: "t", Namespace: "n", Kind: model.DataPlaneExternalApplication, Enabled: true, Configuration: json.RawMessage(`{"instanceSelector":{}}`)})
	if err != nil {
		t.Fatal(err)
	}
	team, err := st.Collaboration().CreateTeam(ctx, &model.CollaborationTeam{Tenant: "t", Namespace: "n", Name: "frozen", Status: model.TeamActive, LeaderAgentRef: agent.ID.String()})
	if err != nil {
		t.Fatal(err)
	}
	contract := &serviceapi.Contract{PublicSessionID: uuid.New(), Endpoint: model.Endpoint{ID: uuid.New(), Tenant: "t", Namespace: "n", TargetType: model.EndpointTargetTeam, TargetRef: team.ID}, Agents: []string{agent.ID.String()}, Teams: map[string]*model.CollaborationTeam{team.ID.String(): team}, Policies: map[string]*model.AgentRuntimePolicy{agent.ID.String(): {Candidates: []model.RuntimeBindingCandidate{{Binding: model.RuntimeBinding{AgentID: agent.ID, BindingID: binding.ID, Kind: binding.Kind}}}}}}
	session := &sessionapi.Session{ID: contract.PublicSessionID, Tenant: "t", Namespace: "n", Target: sessionapi.Target{Type: "team", ID: team.ID}, Principal: "platform-user:owner", Status: "active", CreatedAt: time.Now(), UpdatedAt: time.Now()}
	contract.Endpoint.ID = session.ID
	session.Contract, err = json.Marshal(contract)
	if err != nil {
		t.Fatal(err)
	}
	if err = sessionapi.Save(ctx, st, session, true); err != nil {
		t.Fatal(err)
	}
	s := NewServer(ServerOptions{Store: st, InternalToken: "projection-secret", LocalDev: true, DefaultTenant: "t", DefaultNamespace: "n"})
	// Editing live membership cannot expand the frozen Session's permission graph.
	newAgent := uuid.NewString()
	if _, err = st.Collaboration().AddTeamMember(ctx, &model.CollaborationTeamMember{Tenant: "t", Namespace: "n", TeamID: team.ID, AgentRef: newAgent, Role: "new"}); err != nil {
		t.Fatal(err)
	}
	graph, err := s.goRuntimeResources(ctx, &model.Namespace{Tenant: "t", Name: "n"}, session, contract)
	if err != nil || !slices.Equal(graph["team:"+team.ID.String()].Dependencies, []string{"agent:" + agent.ID.String()}) {
		t.Fatalf("frozen graph=%+v %v", graph, err)
	}
	path := "/api/internal/runtime-sessions/" + session.ID.String() + "/projection?tenant=t"
	req := httptest.NewRequest("GET", path, nil)
	req.Header.Set("X-Managed-Agents-Internal-Token", "projection-secret")
	out := httptest.NewRecorder()
	s.router.ServeHTTP(out, req)
	if out.Code != 200 {
		t.Fatalf("projection=%d %s", out.Code, out.Body.String())
	}
	var projected struct {
		Managed     bool   `json:"managed"`
		RuntimeKind string `json:"runtimeKind"`
		Resolved    struct {
			Session struct {
				ID      string `json:"id"`
				OwnerID string `json:"ownerId"`
			} `json:"session"`
			Contract serviceapi.Contract `json:"contract"`
		} `json:"resolved"`
		Policy struct {
			Scope  string              `json:"scope_id"`
			Humans map[string][]string `json:"humans"`
			Expiry int64               `json:"valid_until"`
		} `json:"policy"`
	}
	if err = json.Unmarshal(out.Body.Bytes(), &projected); err != nil {
		t.Fatal(err)
	}
	if projected.Managed || projected.RuntimeKind != "team" || projected.Resolved.Session.ID != session.ID.String() || projected.Resolved.Session.OwnerID != "owner" || len(projected.Policy.Scope) != 64 || projected.Policy.Expiry <= time.Now().UnixMilli() || !slices.Contains(projected.Policy.Humans["owner"], "invoke") {
		t.Fatalf("generic projection=%+v", projected)
	}
	if len(projected.Resolved.Contract.Teams[team.ID.String()].Members) != 0 {
		t.Fatal("live Team changes entered the frozen execution contract")
	}
	// Binding revocation affects new invocation eligibility without mutating the snapshot.
	binding.Enabled = false
	if _, err = st.AgentCatalog().UpdateBinding(ctx, binding, binding.Version); err != nil {
		t.Fatal(err)
	}
	graph, err = s.goRuntimeResources(ctx, &model.Namespace{Tenant: "t", Name: "n"}, session, contract)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := graph["agent:"+agent.ID.String()]; ok {
		t.Fatal("revoked binding retained invocation eligibility")
	}
}

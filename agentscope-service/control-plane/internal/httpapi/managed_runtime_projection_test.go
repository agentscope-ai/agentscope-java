/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package httpapi

import (
	"bytes"
	"encoding/json"
	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	serviceapi "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/invocation"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/sessionapi"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
	"net/http/httptest"
	"slices"
	"testing"
	"time"
)

func TestManagedRuntimeSummaryIsMonotonicAndProtectsTurnOwnership(t *testing.T) {
	st, err := store.Open(t.Context(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	app, err := st.Applications().Create(t.Context(), &model.Application{Tenant: "t", Namespace: "n", Name: "app", OwnerUserID: "owner", Status: "active"})
	if err != nil {
		t.Fatal(err)
	}
	a, err := st.AgentCatalog().CreateAgent(t.Context(), &model.Agent{Tenant: "t", Namespace: "n", AgentKey: "fixture-agent", DisplayName: "Managed", Status: model.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	agent := a.ID
	contract := &serviceapi.Contract{Endpoint: model.Endpoint{ID: uuid.New(), TargetRef: agent}, Policies: map[string]*model.AgentRuntimePolicy{agent.String(): {Candidates: []model.RuntimeBindingCandidate{{Binding: model.RuntimeBinding{Kind: model.DataPlaneManaged}}}}}}
	raw, _ := json.Marshal(contract)
	session := &sessionapi.Session{ID: uuid.New(), Tenant: "t", Namespace: "n", ApplicationID: &app.ID, Target: sessionapi.Target{Type: "agent", ID: agent}, Principal: "platform-user:owner", Status: "active", Contract: raw, CreatedAt: time.Now()}
	if err = sessionapi.Save(t.Context(), st, session, true); err != nil {
		t.Fatal(err)
	}
	s := NewServer(ServerOptions{Store: st, InternalToken: "runtime-fixture", LocalDev: false, DefaultTenant: "t", DefaultNamespace: "n"})
	path := "/api/internal/runtime-sessions/" + session.ID.String() + "/summary"
	send := func(revision int, tokens int64, status string, token string) *httptest.ResponseRecorder {
		body, _ := json.Marshal(map[string]any{"tenant": "t", "revision": revision, "applicationTokens": tokens, "status": status, "runtimeStatus": "idle", "updatedAt": 1234})
		r := httptest.NewRequest("POST", path, bytes.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("X-Managed-Agents-Internal-Token", token)
		w := httptest.NewRecorder()
		s.router.ServeHTTP(w, r)
		return w
	}
	if w := send(2, 60, "archived", "wrong"); w.Code != 401 {
		t.Fatalf("unauthorized summary accepted: %d", w.Code)
	}
	s.localDev = true
	for _, v := range []struct {
		revision int
		tokens   int64
		status   string
	}{{2, 60, "archived"}, {2, 60, "archived"}, {1, 40, "active"}, {3, 100, "active"}} {
		if w := send(v.revision, v.tokens, v.status, "runtime-fixture"); w.Code != 204 {
			t.Fatalf("summary: %d %s", w.Code, w.Body)
		}
	}
	saved, _ := sessionapi.Get(t.Context(), st, "t", session.ID)
	if saved.RuntimeOwner != "dataplane" || saved.RuntimeRevision != 3 || saved.Status != "active" {
		t.Fatalf("stale summary overwrote ownership: %+v", saved)
	}
	usage, _ := st.Applications().Get(t.Context(), app.ID)
	if usage.TokensUsed != 100 || usage.RuntimeTokensUsed != 100 {
		t.Fatalf("replay double counted runtime usage: %+v", usage)
	}
	credential := sessionCredential{ID: uuid.New(), ApplicationID: app.ID, Name: "retained-cancel", Hash: "fixture-hash", Status: "active", Scopes: []string{"invoke", "read", "cancel", "interact"}, Targets: []sessionapi.Target{{Type: "agent", ID: agent}}}
	credentialJSON, _ := json.Marshal(credential)
	if _, err = st.KV().Put(t.Context(), "t", applicationCredentialPath(app.ID), credential.ID.String(), credentialJSON); err != nil {
		t.Fatal(err)
	}
	app.Status = "disabled"
	if _, err = st.Applications().Update(t.Context(), app, app.Version); err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest("GET", "/api/internal/runtime-sessions/"+session.ID.String()+"/projection?tenant=t&configuration=false", nil)
	request.Header.Set("X-Managed-Agents-Internal-Token", "runtime-fixture")
	projected := httptest.NewRecorder()
	s.router.ServeHTTP(projected, request)
	if projected.Code != 200 {
		t.Fatalf("disabled app projection: %d %s", projected.Code, projected.Body)
	}
	var body struct {
		Policy struct {
			Credentials []sessionCredential `json:"credentials"`
			Humans      map[string][]string `json:"humans"`
		} `json:"policy"`
	}
	if err = json.Unmarshal(projected.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Policy.Credentials) != 1 || !slices.Contains(body.Policy.Credentials[0].Scopes, "cancel") || slices.Contains(body.Policy.Credentials[0].Scopes, "invoke") || slices.Contains(body.Policy.Credentials[0].Scopes, "interact") {
		t.Fatalf("disabled Application key retained execution or lost cancellation: %+v", body.Policy.Credentials)
	}
	if !slices.Contains(body.Policy.Humans["owner"], "cancel") || slices.Contains(body.Policy.Humans["owner"], "invoke") || slices.Contains(body.Policy.Humans["owner"], "interact") || slices.Contains(body.Policy.Humans["owner"], "approve") {
		t.Fatalf("disabled Application retained execution scopes: %+v", body.Policy.Humans)
	}
	if w := sessionTestRequest(s, "POST", "/api/v1/agent-sessions/"+session.ID.String()+"/turns", `{"message":"test"}`, "turn", ""); w.Code != 409 {
		t.Fatalf("control accepted data-owned Turn: %d %s", w.Code, w.Body)
	}
}

func TestManagedRuntimePostgresApplicationUsageIsSharedAndMonotonic(t *testing.T) {
	replicas := servicePostgresReplicas(t)
	app, err := replicas[0].Applications().Create(t.Context(), &model.Application{Tenant: "t", Namespace: "n", Name: "runtime-app", OwnerUserID: "owner", Status: "active"})
	if err != nil {
		t.Fatal(err)
	}
	errors := make(chan error, 8)
	for i, n := range []int64{60, 60, 40, 100, 80, 100, 20, 70} {
		go func() { errors <- replicas[i%2].Applications().RecordRuntimeTokens(t.Context(), app.ID, n) }()
	}
	for range 8 {
		if err = <-errors; err != nil {
			t.Fatal(err)
		}
	}
	saved, err := replicas[1].Applications().Get(t.Context(), app.ID)
	if err != nil || saved.TokensUsed != 100 || saved.RuntimeTokensUsed != 100 {
		t.Fatalf("runtime usage double counted: %+v %v", saved, err)
	}
}

func TestManagedRuntimePolicyScopeSharesSessionsAndSeparatesAuthorizationInputs(t *testing.T) {
	app := uuid.New()
	session := &sessionapi.Session{ID: uuid.New(), Tenant: "tenant", Namespace: "namespace", Principal: "platform-user:owner", ApplicationID: &app, Target: sessionapi.Target{Type: "agent", ID: uuid.New(), Version: 2}, Status: "active"}
	contract := &serviceapi.Contract{Endpoint: model.Endpoint{ID: uuid.New(), InputSchema: json.RawMessage(`{"type":"object"}`)}}
	first, err := managedRuntimePolicyScope(session, contract)
	if err != nil || len(first) != 64 {
		t.Fatalf("policy scope: %q %v", first, err)
	}
	another := *session
	another.ID = uuid.New()
	another.CreatedAt = time.Now()
	another.Status = "archived"
	contract.Endpoint.ID = uuid.New()
	second, err := managedRuntimePolicyScope(&another, contract)
	if err != nil || first != second {
		t.Fatalf("same policy was fragmented by Session metadata: %q %q %v", first, second, err)
	}
	for _, change := range []func(*sessionapi.Session){
		func(v *sessionapi.Session) { v.Tenant = "other" },
		func(v *sessionapi.Session) { v.Namespace = "other" },
		func(v *sessionapi.Session) { v.Principal = "platform-user:other" },
		func(v *sessionapi.Session) { other := uuid.New(); v.ApplicationID = &other },
		func(v *sessionapi.Session) { v.Target.Version++ },
		func(v *sessionapi.Session) { v.Status = "deleted" },
	} {
		value := *session
		change(&value)
		scope, err := managedRuntimePolicyScope(&value, contract)
		if err != nil || scope == first {
			t.Fatalf("authorization input did not separate policy scopes: %+v %v", value, err)
		}
	}
	contract.SessionOptions = json.RawMessage(`{"resources":[{"file_id":"input-a"}]}`)
	resources, err := managedRuntimePolicyScope(session, contract)
	if err != nil || resources != first {
		t.Fatalf("input files fragmented authorization: %q %v", resources, err)
	}
	contract.SessionOptions = json.RawMessage(`{"vaultIds":["private-vault"]}`)
	mounted, err := managedRuntimePolicyScope(session, contract)
	if err != nil || mounted == first {
		t.Fatalf("selected mounts shared authorization: %q %v", mounted, err)
	}
	contract.SessionOptions = nil
	contract.EnvironmentID = "another-environment"
	changed, err := managedRuntimePolicyScope(session, contract)
	if err != nil || changed == first {
		t.Fatalf("environment scope: %q %v", changed, err)
	}
}

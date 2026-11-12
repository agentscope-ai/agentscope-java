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
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	serviceapi "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/invocation"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/product"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/sessionapi"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

func TestLocalDevelopmentAPIUsesSingleScopeWithoutCredentials(t *testing.T) {
	st, err := store.Open(context.Background(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	server := NewServer(ServerOptions{LocalDev: true, Store: st, AuthToken: "production-token"})
	for _, request := range []struct{ method, path, body string }{
		{"GET", "/api/v1/me/scope", ""},
		{"GET", "/api/v1/me/namespaces", ""},
		{"POST", "/api/v1/issues?tenant=other&namespace=other", `{"tenant":"other","namespace":"other","title":"local work"}`},
		{"GET", "/api/v1/issues", ""},
	} {
		response := httptest.NewRecorder()
		req := httptest.NewRequest(request.method, request.path, strings.NewReader(request.body))
		req.Header.Set("Content-Type", "application/json")
		server.router.ServeHTTP(response, req)
		if response.Code < 200 || response.Code >= 300 {
			t.Fatalf("%s %s: %d %s", request.method, request.path, response.Code, response.Body.String())
		}
		if strings.Contains(response.Body.String(), `"tenant":"other"`) || strings.Contains(response.Body.String(), `"namespace":"other"`) {
			t.Fatalf("local scope was overridden: %s", response.Body.String())
		}
		if request.path == "/api/v1/me/scope" {
			var scope struct {
				Mode            string `json:"mode"`
				SelectorVisible bool   `json:"selectorVisible"`
			}
			if json.Unmarshal(response.Body.Bytes(), &scope) != nil || scope.Mode != ScopeModeSingle || scope.SelectorVisible {
				t.Fatalf("unexpected local scope: %s", response.Body.String())
			}
		}
	}
	production := NewServer(ServerOptions{Store: st, AuthToken: "production-token"})
	response := httptest.NewRecorder()
	production.router.ServeHTTP(response, httptest.NewRequest("GET", "/api/v1/issues", nil))
	if response.Code != 401 {
		t.Fatalf("production API accepted no credentials: %d", response.Code)
	}
}

func TestLocalDevelopmentIgnoresPoliciesWithoutMutatingThem(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	server := NewServer(ServerOptions{LocalDev: true, Store: st})
	namespace, err := server.ensureGlobalDefaultNamespace(ctx)
	if err != nil {
		t.Fatal(err)
	}
	namespace.Resources = map[string]model.ResourcePolicy{"agent:" + uuid.NewString(): {Mode: "restricted"}}
	if _, err := st.Access().PutNamespace(ctx, namespace, namespace.Version, "system"); err != nil {
		t.Fatal(err)
	}
	view, err := server.localDevelopmentNamespace(ctx)
	if err != nil || len(view.Resources) != 0 || !model.NamespaceAllows(view.Roles(product.LocalDeveloperID), "work.audit") {
		t.Fatalf("local permissions: %+v %v", view, err)
	}
	stored, err := st.Access().GetNamespace(ctx, "default", "default")
	if err != nil || len(stored.Resources) != 1 || stored.Owner != "system" {
		t.Fatalf("stored policy changed: %+v %v", stored, err)
	}
	issue, err := st.Collaboration().CreateIssue(ctx, &model.Issue{Tenant: "default", Namespace: "default", Title: "private", Creator: model.Actor{Type: model.ActorHuman, Ref: "other-user"}, Access: model.IssueAccess{Mode: "private"}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := server.canAccessIssue(ctx, &namespaceAccess{User: product.LocalDeveloperID, Namespace: view}, issue.ID, true); err != nil {
		t.Fatal("local developer cannot update private work", err)
	}
}

func TestLocalDevelopmentEndpointAndSessionBypass(t *testing.T) {
	for _, local := range []bool{false, true} {
		server := &Server{localDev: local, defaultTenant: "default", defaultNamespace: "default"}
		c, _ := gin.CreateTestContext(httptest.NewRecorder())
		c.Request = httptest.NewRequest("GET", "/api/v1/invocations/test", nil)
		endpoint := &model.Endpoint{Status: model.EndpointPublished, AuthPolicy: json.RawMessage(`{"type":"api_key"}`)}
		// Unsupported policy is rejected without reaching the credential store in normal mode.
		if !local {
			endpoint.AuthPolicy = json.RawMessage(`{"type":"platform"}`)
		}
		if server.authenticateEndpoint(c, endpoint, true) != local {
			t.Fatalf("endpoint bypass local=%v", local)
		}
		c, _ = gin.CreateTestContext(httptest.NewRecorder())
		c.Request = httptest.NewRequest("GET", "/api/v1/sessions/test", nil)
		session := &sessionapi.Session{Tenant: "default", Namespace: "default", Principal: "other-user"}
		if server.sessionAccess(c, session, "read") != local {
			t.Fatalf("session bypass local=%v", local)
		}
		endpoint.Status = model.EndpointArchived
		if server.authenticateEndpoint(c, endpoint, true) {
			t.Fatal("local mode made an archived endpoint callable")
		}
	}
}

func TestLocalDevelopmentManagedQuickstart(t *testing.T) {
	dsn := os.Getenv("CONTROL_PLANE_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("CONTROL_PLANE_TEST_POSTGRES_DSN not set")
	}
	submitted := false
	runtime := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.Header.Get("X-Managed-Agents-Internal-User") != "namespace:default:default" {
			t.Error("missing local runtime owner")
		}
		if r.Method != http.MethodPost || !strings.HasSuffix(r.URL.Path, "/request") {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		var call product.ManagedRuntimeCall
		if err := json.NewDecoder(r.Body).Decode(&call); err != nil || call.Context.Actor["user"] != product.LocalDeveloperID || call.Context.Actor["internal"] != false {
			t.Errorf("invalid native runtime call: %+v %v", call, err)
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		switch call.Operation {
		case "session":
			_ = json.NewEncoder(w).Encode(map[string]any{"id": call.Context.SessionID, "runtime_kind": "managed", "status": "idle"})
		case "submit_turn":
			submitted = true
			w.WriteHeader(http.StatusAccepted)
			io.WriteString(w, `{"id":"native-turn","status":"accepted"}`)
		case "snapshot":
			io.WriteString(w, `{"items":[],"turns":[],"runs":[],"tools":[],"required_actions":[],"artifacts":[],"usage":{}}`)
		default:
			w.WriteHeader(404)
		}
	}))
	defer runtime.Close()
	cfg := product.DefaultConfig()
	cfg.LocalDev, cfg.AllowLocalEnvironment = true, true
	cfg.DSN, cfg.WorkspaceRoot, cfg.DataURL = dsn, t.TempDir(), runtime.URL
	cfg.AgentServiceURL = runtime.URL
	p, err := product.Open(t.Context(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer p.Close()
	st, err := store.Open(t.Context(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	server := NewServer(ServerOptions{LocalDev: true, Store: st, Product: p})
	call := func(method, path, body string, expected int) map[string]any {
		req := httptest.NewRequest(method, path, strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Idempotency-Key", uuid.NewString())
		response := httptest.NewRecorder()
		server.router.ServeHTTP(response, req)
		return decodeSessionTest(t, response, expected)
	}
	env := call("POST", "/api/environments", `{"name":"Quickstart local","type":"local","config":{}}`, 200)
	agent := call("POST", "/api/v1/agents", `{"agentKey":"local-`+uuid.NewString()+`","displayName":"Notes","binding":{"kind":"managed"},"definition":{"name":"Notes","system":"Organize notes","defaultEnvironmentId":"`+env["id"].(string)+`"}}`, 201)
	session := call("POST", "/api/v1/agent-sessions", `{"target":{"type":"agent","id":"`+agent["agent"].(map[string]any)["id"].(string)+`"}}`, 201)
	path := "/api/v1/agent-sessions/" + session["id"].(string)
	call("POST", path+"/turns", `{"message":"Managed must use Agent Service"}`, 409)
	resource, err := sessionapi.Get(t.Context(), st, "default", uuid.MustParse(session["id"].(string)))
	if err != nil {
		t.Fatal(err)
	}
	if resource.RuntimeOwner != "" {
		t.Fatal("test must cover a Managed configuration Session without a runtime-owner marker")
	}
	contract, err := serviceapi.ReadContract(resource.Contract)
	if err != nil {
		t.Fatal(err)
	}
	owner, _, err := server.publicNativeSession(t.Context(), resource, contract)
	if err != nil {
		t.Fatal(err)
	}
	origin, err := server.nativePublicSessionOrigin(t.Context(), resource, contract, owner, "local-native-turn")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = p.ManagedRuntimeRequest(t.Context(), owner, product.ManagedRuntimeCall{Context: origin, Operation: "submit_turn", Payload: map[string]any{"input": map[string]any{"message": "Organize notes"}}}); err != nil {
		t.Fatal(err)
	}
	call("GET", path+"/snapshot", "", 200)
	if !submitted {
		t.Fatal("local Turn did not reach the native Agent Service")
	}
}

func TestLocalDevelopmentApprovalSkipsIdentityButKeepsVersion(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(string(driver), func(t *testing.T) {
			cfg := store.Config{Driver: driver}
			if driver == store.DriverPostgres {
				cfg = acceptancePostgresConfig(t)
			}
			st, err := store.Open(t.Context(), cfg)
			if err != nil {
				t.Fatal(err)
			}
			defer st.Close()
			approval, err := st.Collaboration().CreateApproval(t.Context(), &model.Approval{
				Tenant: "default", Namespace: "default", ApproverRef: "designated-user", RequestedBy: model.Actor{Type: model.ActorHuman, Ref: "another-user"},
				TargetType: "issue", TargetRef: uuid.NewString(), Reason: "Review",
			})
			if err != nil {
				t.Fatal(err)
			}
			server := NewServer(ServerOptions{LocalDev: true, Store: st})
			path := "/api/v1/approvals/" + approval.ID.String()
			response := httptest.NewRecorder()
			server.router.ServeHTTP(response, httptest.NewRequest("GET", path, nil))
			if response.Code != 200 {
				t.Fatalf("local approval read: %d %s", response.Code, response.Body.String())
			}
			for _, step := range []struct {
				version int
				status  int
			}{{99, 409}, {1, 200}, {1, 409}} {
				req := httptest.NewRequest("POST", path+"/decide", strings.NewReader(fmt.Sprintf(`{"status":"approved","expectedVersion":%d}`, step.version)))
				req.Header.Set("Content-Type", "application/json")
				response := httptest.NewRecorder()
				server.router.ServeHTTP(response, req)
				if response.Code != step.status {
					t.Fatalf("local approval version %d: %d %s", step.version, response.Code, response.Body.String())
				}
			}
			saved, err := st.Collaboration().GetApproval(t.Context(), approval.ID)
			if err != nil || saved.DecidedBy == nil || saved.DecidedBy.Ref != product.LocalDeveloperID {
				t.Fatalf("decision identity: %+v %v", saved, err)
			}
		})
	}
}

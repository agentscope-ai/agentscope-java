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
	"fmt"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	serviceapi "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/invocation"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/product"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/sessionapi"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
	"golang.org/x/crypto/bcrypt"
)

// Exercise real accounts / CP PostgreSQL and the live Java Agent Service fixture.
// Runtime execution uses the fixture's explicit MQ / Redis fakes; no paid model calls.
func TestPublicManagedSessionAccountFilesAndNativeBridge(t *testing.T) {
	dsn, agentService := os.Getenv("CONTROL_PLANE_TEST_POSTGRES_DSN"), os.Getenv("MANAGED_AGENTS_AGENT_SERVICE_TEST_URL")
	if dsn == "" || agentService == "" {
		t.Skip("explicit test database and live Agent Service fixture are required")
	}
	user := "session-user-" + uuid.NewString()[:8]
	cfg := product.DefaultConfig()
	cfg.DSN = dsn
	cfg.SeedUsers = false
	cfg.WorkspaceRoot = t.TempDir()
	cfg.AgentServiceURL = agentService
	cfg.InternalToken = os.Getenv("MANAGED_AGENTS_AGENT_SERVICE_TEST_TOKEN")
	p, err := product.Open(t.Context(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer p.Close()
	db, err := pgxpool.New(t.Context(), dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	hash, _ := bcrypt.GenerateFromPassword([]byte("test-password"), bcrypt.MinCost)
	if _, err = db.Exec(t.Context(), `INSERT INTO cp.users(user_id,username,password_hash,roles_csv,created_at) VALUES($1,$1,$2,'user',0)`, user, string(hash)); err != nil {
		t.Fatal(err)
	}
	st, err := store.Open(t.Context(), acceptancePostgresConfig(t))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	s := NewServer(ServerOptions{Store: st, Product: p, AuthToken: "console", InternalToken: cfg.InternalToken})
	token, apiKey := "", ""
	call := func(method, path, body, key, filename string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, bytes.NewBufferString(body))
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Idempotency-Key", key)
		if apiKey != "" {
			r.Header.Set("X-API-Key", apiKey)
		}
		if token != "" {
			r.Header.Set("Authorization", "Bearer "+token)
		}
		if filename != "" {
			r.Header.Set("X-File-Name", filename)
		}
		w := httptest.NewRecorder()
		s.router.ServeHTTP(w, r)
		return w
	}
	login := decodeSessionTest(t, call("POST", "/api/auth/login", `{"username":"`+user+`","password":"test-password"}`, "", ""), 200)
	token = login["token"].(string)
	a := decodeSessionTest(t, call("POST", "/api/v1/agents", `{"agentKey":"worker","displayName":"Worker","binding":{"kind":"managed"},"definition":{"name":"Worker","system":"Read the input"}}`, "", ""), 201)
	agentID := a["agent"].(map[string]any)["id"].(string)
	v := decodeSessionTest(t, call("POST", "/api/v1/agent-sessions", `{"target":{"type":"agent","id":"`+agentID+`"}}`, "session", ""), 201)
	base := "/api/v1/agent-sessions/" + v["id"].(string)
	// A definition edit before the first Turn must not change this Session.
	decodeSessionTest(t, call("PATCH", "/api/v1/agents/"+agentID+"/definition", `{"name":"Worker","version":1,"system":"Changed after Session creation"}`, "", ""), 200)
	pinned := decodeSessionTest(t, call("POST", "/api/v1/agent-sessions", `{"target":{"type":"agent","id":"`+agentID+`","version":1}}`, "pinned", ""), 201)
	if pinned["target"].(map[string]any)["version"] != float64(1) {
		t.Fatal("explicit historical version was not selected", pinned)
	}
	// Native Fork must register both fixed definitions, preserve the original Actor and reach
	// Agent Service's checkpoint validation. These empty Sessions deliberately have no checkpoint.
	forkTarget := decodeSessionTest(t, call("POST", "/api/v1/agent-sessions", `{"target":{"type":"agent","id":"`+agentID+`","version":1}}`, "fork-target", ""), 201)
	forkBase := "/api/v1/agent-sessions/" + pinned["id"].(string)
	forkBody := `{"target_session_id":"` + forkTarget["id"].(string) + `","checkpoint_id":"checkpoint_missing"}`
	if response := call("POST", forkBase+"/fork", forkBody, "missing-checkpoint", ""); response.Code != 404 || !strings.Contains(response.Body.String(), "checkpoint_not_found") {
		t.Fatalf("Fork did not reach native checkpoint validation: %d %s", response.Code, response.Body)
	}
	decodeSessionTest(t, call("GET", forkBase+"/checkpoints", "", "", ""), 200)
	if response := call("GET", forkBase+"/checkpoint-operations/checkpoint_op_missing", "", "", ""); response.Code != 404 {
		t.Fatalf("native operation route differs: %d %s", response.Code, response.Body)
	}

	file := decodeSessionTest(t, call("POST", base+"/files", `{"business":"unchanged"}`, "file", "input.json"), 201)
	fileID, ok := file["file_id"].(string)
	if !ok || !strings.HasPrefix(fileID, "file_") {
		t.Fatalf("invalid file metadata: %v", file)
	}
	if response := call("POST", base+"/turns", `{"message":"use native Agent Service"}`, "turn", ""); response.Code != 409 {
		t.Fatalf("Go accepted a Agent Service-owned Turn: %d %s", response.Code, response.Body)
	}
	resource, err := sessionapi.Get(t.Context(), st, v["tenant"].(string), uuid.MustParse(v["id"].(string)))
	if err != nil {
		t.Fatal(err)
	}
	contract, err := serviceapi.ReadContract(resource.Contract)
	if err != nil {
		t.Fatal(err)
	}
	owner, _, err := s.publicNativeSession(t.Context(), resource, contract)
	if err != nil {
		t.Fatal(err)
	}
	origin, err := s.nativePublicSessionOrigin(t.Context(), resource, contract, owner, "account-turn")
	if err != nil {
		t.Fatal(err)
	}
	if origin.Actor["user"] != user || origin.Actor["internal"] != false {
		t.Fatalf("original account Actor changed: %v", origin.Actor)
	}
	input := map[string]any{"input": []any{map[string]any{"role": "user", "content": []any{map[string]any{"type": "file", "file_id": fileID}}}}}
	raw, err := p.ManagedRuntimeRequest(t.Context(), owner, product.ManagedRuntimeCall{Context: origin, Operation: "submit_turn", Payload: map[string]any{"input": input}})
	if err != nil {
		t.Fatal(err)
	}
	var turn map[string]any
	if json.Unmarshal(raw, &turn) != nil || turn["status"] != "accepted" {
		t.Fatalf("invalid Turn receipt: %s", raw)
	}
	snapshot := decodeSessionTest(t, call("GET", base+"/snapshot", "", "", ""), 200)
	if snapshot["snapshot_id"] == nil || snapshot["as_of"] == nil || snapshot["resources"] == nil {
		t.Fatalf("invalid native snapshot: %v", snapshot)
	}
	raw, err = p.ManagedRuntimeRequest(t.Context(), owner, product.ManagedRuntimeCall{Context: origin, Operation: "session"})
	if err != nil {
		t.Fatal(err)
	}
	var nativeView map[string]any
	if json.Unmarshal(raw, &nativeView) != nil || nativeView["agent"].(map[string]any)["version"] != float64(1) {
		t.Fatalf("runtime used the edited head: %s", raw)
	}
	page := decodeSessionTest(t, call("GET", base+"/events", "", "", ""), 200)
	if len(page["data"].([]any)) == 0 {
		t.Fatal("committed native facts are missing")
	}
	for _, rawEvent := range page["data"].([]any) {
		event := rawEvent.(map[string]any)
		if event["session_id"] != resource.ID.String() || event["cursor"] == nil {
			t.Fatalf("native event coordinate changed: %v", event)
		}
	}
	download := call("GET", base+"/files/"+fileID+"/content", "", "", "")
	if download.Code != 200 || download.Body.String() != `{"business":"unchanged"}` {
		t.Fatalf("JSON file bytes changed: %d %s", download.Code, download.Body)
	}
	if v["target"].(map[string]any)["version"] != float64(1) {
		t.Fatal("definition version not frozen", v)
	}
	app := decodeSessionTest(t, call("POST", "/api/v1/applications", `{"name":"Account integration"}`, "", ""), 201)["application"].(map[string]any)
	credential := decodeSessionTest(t, call("POST", "/api/v1/applications/"+app["id"].(string)+"/credentials", `{"name":"backend","scopes":["invoke","read"],"targets":[{"type":"agent","id":"`+agentID+`"}]}`, "", ""), 201)
	apiKey = credential["apiKey"].(string)
	applicationSession := decodeSessionTest(t, call("POST", "/api/v1/agent-sessions", `{"target":{"type":"agent","id":"`+agentID+`"}}`, "application-session", ""), 201)
	apiKey = ""
	listed := decodeSessionTest(t, call("GET", "/api/v1/agent-sessions", "", "", ""), 200)
	visible := false
	for _, item := range listed["items"].([]any) {
		if item.(map[string]any)["id"] == applicationSession["id"] {
			visible = true
		}
	}
	if !visible {
		t.Fatal("Application owner cannot list its Session")
	}

	for index, option := range []string{`"timeoutSeconds":30`, `"outputSchema":{"type":"object"}`, `"resultMapping":{"result":"/answer"}`} {
		body := `{"target":{"type":"agent","id":"` + agentID + `"},` + option + `}`
		response := call("POST", "/api/v1/agent-sessions", body, fmt.Sprintf("unsupported-%d", index), "")
		if response.Code != 400 {
			t.Fatalf("Managed accepted unsupported contract: %d %s", response.Code, response.Body)
		}
	}

	// A new independent Managed Session is admitted to the data plane without a
	// control-plane Turn, with one public/Harness identity and hashed credentials.
	projectionPath := "/api/internal/runtime-sessions/" + applicationSession["id"].(string) + "/projection?tenant=" + applicationSession["tenant"].(string)
	unauthorized := httptest.NewRecorder()
	s.router.ServeHTTP(unauthorized, httptest.NewRequest("GET", projectionPath, nil))
	if unauthorized.Code != 401 {
		t.Fatalf("untrusted runtime projection: %d", unauthorized.Code)
	}
	request := httptest.NewRequest("GET", projectionPath, nil)
	request.Header.Set("X-Managed-Agents-Internal-Token", cfg.InternalToken)
	response := httptest.NewRecorder()
	s.router.ServeHTTP(response, request)
	projection := decodeSessionTest(t, response, 200)
	resolved := projection["resolved"].(map[string]any)
	if resolved["session"].(map[string]any)["id"] != applicationSession["id"] {
		t.Fatal("runtime projection kept a second Session identity", resolved)
	}
	policy := projection["policy"].(map[string]any)
	if len(policy["credentials"].([]any)) != 1 || strings.Contains(response.Body.String(), credential["apiKey"].(string)) {
		t.Fatal("runtime credential projection is incomplete or contains a raw secret")
	}
	traceAllowed := func() bool {
		r := httptest.NewRequest("GET", projectionPath, nil)
		r.Header.Set("X-Managed-Agents-Internal-Token", cfg.InternalToken)
		w := httptest.NewRecorder()
		s.router.ServeHTTP(w, r)
		view := decodeSessionTest(t, w, 200)
		humans := view["policy"].(map[string]any)["humans"].(map[string]any)
		for _, scope := range humans[user].([]any) {
			if scope == "trace" {
				return true
			}
		}
		return false
	}
	if traceAllowed() {
		t.Fatal("ordinary account received native trace scope")
	}
	if _, err = db.Exec(t.Context(), `UPDATE cp.users SET roles_csv='admin',version=version+1 WHERE user_id=$1`, user); err != nil {
		t.Fatal(err)
	}
	if !traceAllowed() {
		t.Fatal("current administrator could not read an owned Session trace")
	}
	if _, err = db.Exec(t.Context(), `UPDATE cp.users SET roles_csv='user',version=version+1 WHERE user_id=$1`, user); err != nil {
		t.Fatal(err)
	}
	if traceAllowed() {
		t.Fatal("revoked administrator retained trace scope")
	}
	ownedPath := "/api/v1/agent-sessions/" + applicationSession["id"].(string) + "/turns"
	if response := call("POST", ownedPath, `{"message":"must use dataplane"}`, "owned-turn", ""); response.Code != 409 {
		t.Fatalf("control plane accepted data-owned Turn: %d %s", response.Code, response.Body)
	}
}

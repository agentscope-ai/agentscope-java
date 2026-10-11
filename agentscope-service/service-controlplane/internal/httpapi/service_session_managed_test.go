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
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/artifact"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/product"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/crypto/bcrypt"
)

// Exercise real account, catalog, version and Session stores with a deterministic
// dataplane fixture. This deliberately does not make paid model calls.
func TestPublicManagedSessionAccountFilesAndNativeBridge(t *testing.T) {
	dsn := os.Getenv("CONTROL_PLANE_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("CONTROL_PLANE_TEST_POSTGRES_DSN not set")
	}
	user := "session-user-" + uuid.NewString()[:8]
	submitted := false
	var uploaded []byte
	runtime := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Builder-Internal-User") != user || r.Header.Get("X-Builder-Internal-Token") != "session-internal" {
			t.Error("missing authenticated runtime owner")
			w.WriteHeader(401)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == "POST" && strings.HasSuffix(r.URL.Path, "/files"):
			uploaded, _ = io.ReadAll(r.Body)
			io.WriteString(w, `{"file_id":"native-file"}`)
		case r.Method == "POST" && strings.HasSuffix(r.URL.Path, "/turns"):
			raw, _ := io.ReadAll(r.Body)
			if !strings.Contains(string(raw), `"file_id":"native-file"`) {
				t.Error("public file was not materialized", string(raw))
			}
			submitted = true
			w.WriteHeader(202)
			io.WriteString(w, `{"id":"native-turn","status":"queued"}`)
		case strings.HasSuffix(r.URL.Path, "/turns/native-turn"):
			io.WriteString(w, `{"id":"native-turn","status":"completed"}`)
		case strings.HasSuffix(r.URL.Path, "/events"):
			io.WriteString(w, `{"data":[{"id":"native-item-event","type":"item.completed","data":{"turn_id":"native-turn","item_id":"answer","item":{"role":"ASSISTANT","content":[{"type":"text","text":"done"}]}}},{"id":"native-end-event","type":"turn.completed","data":{"turn_id":"native-turn","status":"completed"}}],"next_cursor":"native-cursor","has_more":false}`)
		case strings.HasSuffix(r.URL.Path, "/snapshot"):
			io.WriteString(w, `{"items":[{"type":"item.completed","data":{"turn_id":"native-turn","item_id":"answer","item":{"role":"ASSISTANT","content":[{"type":"text","text":"done"}]}}}],"turns":[{"type":"turn.completed","data":{"turn_id":"native-turn","status":"completed"}}],"runs":[],"tools":[],"required_actions":[],"artifacts":[],"usage":{}}`)
		default:
			w.WriteHeader(404)
		}
	}))
	defer runtime.Close()
	cfg := product.DefaultConfig()
	cfg.DSN = dsn
	cfg.SeedUsers = false
	cfg.WorkspaceRoot = t.TempDir()
	cfg.DataURL = runtime.URL
	cfg.InternalToken = "session-internal"
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
	s := NewServer(ServerOptions{Store: st, Product: p, AuthToken: "console", ArtifactProvider: &artifact.LocalProvider{Root: t.TempDir()}})
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

	file := decodeSessionTest(t, call("POST", base+"/files", `{"business":"unchanged"}`, "file", "input.json"), 201)
	input := map[string]any{"input": []any{map[string]any{"role": "user", "content": []any{map[string]any{"type": "file", "file_id": file["id"]}}}}}
	raw, _ := json.Marshal(input)
	turn := decodeSessionTest(t, call("POST", base+"/turns", string(raw), "turn", ""), 202)
	if !submitted || string(uploaded) != `{"business":"unchanged"}` {
		t.Fatalf("native dispatch=%v; JSON file changed to %s", submitted, uploaded)
	}
	snapshot := decodeSessionTest(t, call("GET", base+"/snapshot", "", "", ""), 200)
	conversation, err := st.Endpoints().GetConversation(t.Context(), uuid.MustParse(v["id"].(string)))
	if err != nil {
		t.Fatal(err)
	}
	nativeSession, err := p.ManagedSessionView(t.Context(), user, conversation.SessionID)
	if err != nil {
		t.Fatal(err)
	}
	nativeJSON, _ := json.Marshal(nativeSession)
	var nativeView map[string]any
	_ = json.Unmarshal(nativeJSON, &nativeView)
	if nativeView["agentVersion"] != float64(1) {
		t.Fatal("runtime used the edited head", nativeView)
	}

	encoded, _ := json.Marshal(snapshot)
	if strings.Contains(string(encoded), `"turn_id":"native-turn"`) || !strings.Contains(string(encoded), turn["id"].(string)) {
		t.Fatal("native identity leaked", string(encoded))
	}
	page := decodeSessionTest(t, call("GET", base+"/events", "", "", ""), 200)
	itemIndex, terminalIndex := -1, -1
	for index, rawEvent := range page["data"].([]any) {
		event := rawEvent.(map[string]any)
		if event["type"] == "item.completed" {
			itemIndex = index
		}
		if event["type"] == "turn.completed" {
			terminalIndex = index
		}
		if event["turn_id"] != nil && event["turn_id"] != turn["id"] {
			t.Fatal("private Turn ID leaked", event)
		}
	}
	if itemIndex < 0 || terminalIndex < itemIndex {
		t.Fatal("completion overtook committed output", page)
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

}

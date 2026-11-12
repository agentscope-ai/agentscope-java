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
	"sync"
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

// A failed managed turn is terminal in the public API but still blocks later
// turns in the dataplane, so cancelling it must reach the dataplane.
func TestPublicManagedSessionCancelDiscardsFailedTurn(t *testing.T) {
	dsn := os.Getenv("CONTROL_PLANE_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("CONTROL_PLANE_TEST_POSTGRES_DSN not set")
	}
	user := "failed-turn-user-" + uuid.NewString()[:8]
	var mu sync.Mutex
	nativeStatus, nativeCancels := "failed", 0
	runtime := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == "POST" && strings.HasSuffix(r.URL.Path, "/turns"):
			w.WriteHeader(202)
			io.WriteString(w, `{"id":"native-turn","status":"queued"}`)
		case r.Method == "POST" && strings.HasSuffix(r.URL.Path, "/turns/native-turn/cancel"):
			nativeCancels++
			nativeStatus = "cancelled"
			io.WriteString(w, `{"id":"native-turn","status":"cancelled"}`)
		case r.Method == "GET" && strings.HasSuffix(r.URL.Path, "/turns/native-turn"):
			io.WriteString(w, `{"id":"native-turn","status":"`+nativeStatus+`","errorCode":"model_error"}`)
		case strings.HasSuffix(r.URL.Path, "/events"):
			io.WriteString(w, `{"data":[],"next_cursor":"native-cursor","has_more":false}`)
		case strings.HasSuffix(r.URL.Path, "/snapshot"):
			io.WriteString(w, `{"items":[],"turns":[],"runs":[],"tools":[],"required_actions":[],"artifacts":[],"usage":{}}`)
		default:
			t.Log("unexpected dataplane request", r.Method, r.URL.Path)
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
	token := ""
	call := func(method, path, body, key string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, bytes.NewBufferString(body))
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Idempotency-Key", key)
		if token != "" {
			r.Header.Set("Authorization", "Bearer "+token)
		}
		w := httptest.NewRecorder()
		s.router.ServeHTTP(w, r)
		return w
	}
	commands := func(turnBase string) []any {
		v := decodeSessionTest(t, call("GET", turnBase+"/capabilities", "", ""), 200)
		return v["available_commands"].([]any)
	}
	token = decodeSessionTest(t, call("POST", "/api/auth/login", `{"username":"`+user+`","password":"test-password"}`, ""), 200)["token"].(string)
	a := decodeSessionTest(t, call("POST", "/api/v1/agents", `{"agentKey":"worker","displayName":"Worker","binding":{"kind":"managed"},"definition":{"name":"Worker","system":"Read the input"}}`, ""), 201)
	agentID := a["agent"].(map[string]any)["id"].(string)
	v := decodeSessionTest(t, call("POST", "/api/v1/agent-sessions", `{"target":{"type":"agent","id":"`+agentID+`"}}`, "session"), 201)
	base := "/api/v1/agent-sessions/" + v["id"].(string)
	turn := decodeSessionTest(t, call("POST", base+"/turns", `{"message":"hello"}`, "turn"), 202)
	turnBase := base + "/turns/" + turn["id"].(string)
	if state := decodeSessionTest(t, call("GET", turnBase, "", ""), 200); state["status"] != "failed" {
		t.Fatal("native failure was not projected", state)
	}
	if got := commands(turnBase); len(got) != 1 || got[0] != "cancel" {
		t.Fatal("failed turn must offer only cancel", got)
	}
	if w := call("POST", turnBase+"/resume", `{}`, "resume"); w.Code != 409 || !strings.Contains(w.Body.String(), "cancel it to continue") {
		t.Fatal("resume of a failed turn must point to cancellation", w.Code, w.Body)
	}
	accepted := decodeSessionTest(t, call("POST", turnBase+"/cancel", `{}`, "discard"), 202)
	inv, err := st.Endpoints().GetInvocation(t.Context(), uuid.MustParse(turn["id"].(string)))
	if err != nil {
		t.Fatal(err)
	}
	if err = s.sweepServiceInvocation(t.Context(), inv); err != nil {
		t.Fatal(err)
	}
	commandID := accepted["command"].(map[string]any)["id"].(string)
	if command := decodeSessionTest(t, call("GET", turnBase+"/commands/"+commandID, "", ""), 200)["command"].(map[string]any); command["status"] != "completed" {
		t.Fatal("discard command did not complete", command)
	}
	mu.Lock()
	cancels := nativeCancels
	mu.Unlock()
	if cancels != 1 {
		t.Fatalf("dataplane cancel calls = %d, want 1", cancels)
	}
	if state := decodeSessionTest(t, call("GET", turnBase, "", ""), 200); state["status"] != "failed" {
		t.Fatal("discarding changed the public outcome", state)
	}
	if got := commands(turnBase); len(got) != 0 {
		t.Fatal("discarded turn still offers commands", got)
	}
	if w := call("POST", turnBase+"/cancel", `{}`, "again"); w.Code != 409 {
		t.Fatal("discarded turn accepted another command", w.Code, w.Body)
	}
}

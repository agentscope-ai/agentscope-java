/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	serviceapi "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/invocation"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/product"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/sessionapi"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

// Real CP PostgreSQL and resource resolution; Agent Service is an explicit protocol fake, not a Tool Worker test.
func TestPublicSessionForkRegistersTargetsAndRetainsNativeOperations(t *testing.T) {
	dsn := os.Getenv("CONTROL_PLANE_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("explicit test database is required")
	}
	registered := map[string]bool{}
	forkCalls := 0
	var sourceID, targetID, expectedOwner string
	var mu sync.Mutex
	agentService := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		if r.Header.Get("X-Managed-Agents-Internal-User") != expectedOwner {
			t.Error("native call replaced the runtime owner")
			w.WriteHeader(403)
			return
		}
		if r.URL.Path == "/api/internal/runtime/sessions/provision" {
			var admission runtimeprotocol.RuntimeSessionAdmission
			if json.NewDecoder(r.Body).Decode(&admission) != nil {
				t.Error("invalid admission")
				w.WriteHeader(400)
				return
			}
			origin := admission.Bootstrap.Context
			if origin.Actor["user"] != product.LocalDeveloperID || origin.Actor["internal"] != false || origin.PermissionExpiresAt <= time.Now().UnixMilli() {
				t.Error("resource registration lost original Actor")
				w.WriteHeader(403)
				return
			}
			registered[origin.SessionID] = true
			_ = json.NewEncoder(w).Encode(map[string]any{"id": origin.SessionID, "state": "created"})
			return
		}
		var call product.ManagedRuntimeCall
		if json.NewDecoder(r.Body).Decode(&call) != nil {
			t.Error("invalid native call")
			w.WriteHeader(400)
			return
		}
		if call.Context.Actor["user"] != product.LocalDeveloperID || call.Context.Actor["internal"] != false {
			t.Error("operation lost original Actor")
			w.WriteHeader(403)
			return
		}
		switch call.Operation {
		case "session":
			if !registered[call.Context.SessionID] {
				w.WriteHeader(404)
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"id": call.Context.SessionID})
			return
		case "fork_checkpoint":
			payload := call.Payload.(map[string]any)
			if call.Context.SessionID != sourceID || call.Context.CommandID != "fork-key" || payload["target_session_id"] != targetID || payload["checkpoint_id"] != "checkpoint_saved" || len(payload) != 2 || !registered[sourceID] || !registered[targetID] {
				t.Error("Fork lost its immutable origin, target registration or body")
				w.WriteHeader(400)
				return
			}
			forkCalls++
		case "restore_checkpoint":
			if call.Context.SessionID != sourceID {
				t.Error("Restore source differs")
			}
		case "checkpoint_operation", "cancel_checkpoint_operation":
			if call.ReceiptID != "checkpoint_op_saved" || call.Context.SessionID != sourceID {
				t.Error("operation identity differs")
			}
		default:
			t.Error("unexpected operation", call.Operation)
			w.WriteHeader(400)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"operation_id": "checkpoint_op_saved", "command_id": "checkpoint_op_saved", "source_session_id": sourceID, "session_id": targetID, "status": "validating"})
	}))
	defer agentService.Close()
	cfg := product.DefaultConfig()
	cfg.DSN, cfg.LocalDev, cfg.WorkspaceRoot = dsn, true, t.TempDir()
	cfg.AgentServiceURL, cfg.DataURL, cfg.InternalToken = agentService.URL, "http://127.0.0.1:1", "test-internal-token-at-least-32-characters"
	p, err := product.Open(t.Context(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer p.Close()
	st, err := store.Open(t.Context(), acceptancePostgresConfig(t))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	s := NewServer(ServerOptions{Store: st, Product: p, LocalDev: true})
	call := func(method, path, body, key string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Idempotency-Key", key)
		w := httptest.NewRecorder()
		s.router.ServeHTTP(w, r)
		return w
	}
	a := decodeSessionTest(t, call("POST", "/api/v1/agents", `{"agentKey":"fork-`+uuid.NewString()+`","displayName":"Fork","binding":{"kind":"managed"},"definition":{"name":"Fork","system":"Fixed definition"}}`, ""), 201)
	agent := a["agent"].(map[string]any)["id"].(string)
	definition := `{"target":{"type":"agent","id":"` + agent + `","version":1}}`
	createdSource := decodeSessionTest(t, call("POST", "/api/v1/agent-sessions", definition, "source"), 201)["id"].(string)
	createdTarget := decodeSessionTest(t, call("POST", "/api/v1/agent-sessions", definition, "target"), 201)["id"].(string)
	resource, err := sessionapi.Get(t.Context(), st, "default", uuid.MustParse(createdSource))
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
	mu.Lock()
	sourceID, targetID, expectedOwner = createdSource, createdTarget, owner
	mu.Unlock()
	base := "/api/v1/agent-sessions/" + sourceID
	body := `{"target_session_id":"` + targetID + `","checkpoint_id":"checkpoint_saved"}`
	for range 2 {
		receipt := decodeSessionTest(t, call("POST", base+"/fork", body, "fork-key"), 202)
		if receipt["source_session_id"] != sourceID || receipt["session_id"] != targetID || receipt["operation_id"] != "checkpoint_op_saved" {
			t.Fatal("native receipt changed")
		}
	}
	mu.Lock()
	countsCorrect := len(registered) == 2 && forkCalls == 2
	mu.Unlock()
	if !countsCorrect {
		t.Fatal("unexpected registration or Fork count")
	}
	decodeSessionTest(t, call("GET", base+"/checkpoint-operations/checkpoint_op_saved", "", ""), 200)
	decodeSessionTest(t, call("POST", base+"/checkpoint-operations/checkpoint_op_saved/cancel", "", ""), 200)
	decodeSessionTest(t, call("POST", base+"/checkpoints/restore", `{"checkpoint_id":"checkpoint_saved"}`, "restore-key"), 202)
	for _, invalid := range []struct{ body, key string }{{body, ""}, {`{"target_session_id":"` + sourceID + `","checkpoint_id":"checkpoint_saved"}`, "key"}, {strings.TrimSuffix(body, "}") + `,"reason":"obsolete"}`, "key"}} {
		if w := call("POST", base+"/fork", invalid.body, invalid.key); w.Code != 400 {
			t.Fatalf("invalid Fork accepted: %d", w.Code)
		}
	}
}

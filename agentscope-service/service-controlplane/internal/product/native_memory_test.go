// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//     http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package product

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestNativeMemoryWithoutControlPlaneSession(t *testing.T) {
	s := resourceTestServer(t)
	s.cfg.InternalToken = "native-memory-fixture-internal-token"
	owner, store, env := shortID("owner_"), shortID("store_"), shortID("env_")
	resourceSQL(t, s, `INSERT INTO memory_stores(store_id,owner_id,name,created_at,updated_at) VALUES($1,$2,'native notes',1,1)`, store, owner)
	resourceSQL(t, s, `INSERT INTO environments(environment_id,owner_id,name,type,created_at,updated_at) VALUES($1,$2,'native env','cloud',1,1)`, env, owner)
	var sessionsBefore int
	if err := s.db.Pool.QueryRow(t.Context(), `SELECT count(*) FROM sessions`).Scan(&sessionsBefore); err != nil {
		t.Fatal(err)
	}
	router := gin.New()
	router.Use(s.Middlewares()...)
	s.registerInternal(router)
	call := func(method, suffix, body, caller, environment, token string) *httptest.ResponseRecorder {
		t.Helper()
		req := httptest.NewRequest(method, "/api/internal/memory-stores/"+store+"/memories"+suffix, bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Builder-Internal-Token", token)
		req.Header.Set("X-Builder-Internal-User", caller)
		req.Header.Set("X-Builder-Internal-Environment", environment)
		out := httptest.NewRecorder()
		router.ServeHTTP(out, req)
		return out
	}
	expect := func(out *httptest.ResponseRecorder, code int) {
		t.Helper()
		if out.Code != code {
			t.Fatalf("expected %d, got %d: %s", code, out.Code, out.Body.String())
		}
	}
	token := s.cfg.InternalToken
	expect(call(http.MethodPut, "/nested/note.md", `{"content":"v1","expectedVersion":0}`, owner, env, ""), http.StatusUnauthorized)
	expect(call(http.MethodPut, "/nested/note.md", `{"content":"v1","expectedVersion":0}`, "", env, token), http.StatusForbidden)
	expect(call(http.MethodPut, "/nested/note.md", `{"content":"v1","expectedVersion":0}`, "other-owner", env, token), http.StatusNotFound)
	expect(call(http.MethodPut, "/nested/note.md", `{"content":"v1","expectedVersion":0}`, owner, env, token), http.StatusOK)
	expect(call(http.MethodPut, "/nested/note.md", `{"content":"lost","expectedVersion":0}`, owner, env, token), http.StatusConflict)
	expect(call(http.MethodPut, "/nested/note.md", `{"content":"v2","expectedVersion":1}`, owner, env, token), http.StatusOK)
	read := call(http.MethodGet, "/nested/note.md", "", owner, env, token)
	expect(read, http.StatusOK)
	if !bytes.Contains(read.Body.Bytes(), []byte(`"content":"v2"`)) {
		t.Fatalf("updated memory missing: %s", read.Body.String())
	}
	expect(call(http.MethodGet, "", "", owner, env, token), http.StatusOK)
	resourceSQL(t, s, `UPDATE environments SET config_json=$1 WHERE environment_id=$2`, mustJSON(gin.H{"memoryAccess": gin.H{store: "read_only"}}), env)
	expect(call(http.MethodPut, "/nested/note.md", `{"content":"v3","expectedVersion":2}`, owner, env, token), http.StatusForbidden)
	expect(call(http.MethodDelete, "/nested/note.md", "", owner, env, token), http.StatusForbidden)
	expect(call(http.MethodGet, "/nested/note.md", "", owner, env, token), http.StatusOK)
	expect(call(http.MethodGet, "/nested/note.md", "", owner, "missing-environment", token), http.StatusConflict)
	resourceSQL(t, s, `UPDATE environments SET archived_at=2 WHERE environment_id=$1`, env)
	expect(call(http.MethodGet, "/nested/note.md", "", owner, env, token), http.StatusConflict)
	resourceSQL(t, s, `UPDATE environments SET archived_at=NULL WHERE environment_id=$1`, env)
	resourceSQL(t, s, `UPDATE environments SET owner_id='other-owner' WHERE environment_id=$1`, env)
	expect(call(http.MethodGet, "/nested/note.md", "", owner, env, token), http.StatusConflict)
	resourceSQL(t, s, `UPDATE environments SET owner_id=$1,config_json='{}' WHERE environment_id=$2`, owner, env)
	expect(call(http.MethodDelete, "/nested/note.md", "", owner, env, token), http.StatusNoContent)
	expect(call(http.MethodGet, "/nested/note.md", "", owner, env, token), http.StatusNotFound)
	resourceSQL(t, s, `UPDATE memory_stores SET archived_at=2 WHERE store_id=$1`, store)
	expect(call(http.MethodGet, "", "", owner, env, token), http.StatusNotFound)
	var sessionsAfter int
	if err := s.db.Pool.QueryRow(t.Context(), `SELECT count(*) FROM sessions`).Scan(&sessionsAfter); err != nil || sessionsAfter != sessionsBefore {
		t.Fatalf("native resource access changed legacy Session rows: before=%d after=%d err=%v", sessionsBefore, sessionsAfter, err)
	}
}

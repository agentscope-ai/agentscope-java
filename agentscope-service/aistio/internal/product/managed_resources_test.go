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

package product

import (
	"bytes"
	"context"
	"encoding/json"
	"github.com/gin-gonic/gin"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
)

func resourceTestServer(t *testing.T) *Server {
	t.Helper()
	dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set")
	}
	db, err := openDB(context.Background(), dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	if err = migrate(context.Background(), db); err != nil {
		t.Fatal(err)
	}
	return &Server{db: db, vaultKey: make([]byte, 32)}
}

func resourceSQL(t *testing.T, s *Server, sql string, args ...any) {
	t.Helper()
	if _, err := s.db.Pool.Exec(context.Background(), sql, args...); err != nil {
		t.Fatal(err)
	}
}

func TestManagedMemorySessionAuthorizationAndVersionConflict(t *testing.T) {
	s := resourceTestServer(t)
	owner, store, sess, env := shortID("owner_"), shortID("store_"), shortID("sess_"), shortID("env_")
	resourceSQL(t, s, `INSERT INTO memory_stores(store_id,owner_id,name,created_at,updated_at) VALUES($1,$2,'notes',1,1)`, store, owner)
	resourceSQL(t, s, `INSERT INTO environments(environment_id,owner_id,name,type,created_at,updated_at) VALUES($1,$2,'test','self_hosted',1,1)`, env, owner)
	resourceSQL(t, s, `INSERT INTO sessions(session_id,owner_id,agent_id,environment_id,status,version,memory_store_ids_json,created_at,updated_at) VALUES($1,$2,'agent',$3,'idle',1,$4,1,1)`, sess, owner, env, mustJSON([]string{store}))
	gin.SetMode(gin.TestMode)
	router := gin.New()
	s.registerInternal(router)
	call := func(method, path, body string) *httptest.ResponseRecorder {
		t.Helper()
		req := httptest.NewRequest(method, path, bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
		out := httptest.NewRecorder()
		router.ServeHTTP(out, req)
		return out
	}
	path := "/api/internal/sessions/" + sess + "/memory-stores/" + store + "/memories/nested/note.md"
	if out := call(http.MethodPut, path, `{"content":"v1","expectedVersion":0}`); out.Code != 200 {
		t.Fatalf("create: %d %s", out.Code, out.Body.String())
	}
	if out := call(http.MethodPut, path, `{"content":"lost","expectedVersion":0}`); out.Code != 409 {
		t.Fatalf("expected conflict: %d %s", out.Code, out.Body.String())
	}
	if out := call(http.MethodPut, path, `{"content":"v2","expectedVersion":1}`); out.Code != 200 {
		t.Fatalf("edit: %d %s", out.Code, out.Body.String())
	}
	var versions int
	if err := s.db.Pool.QueryRow(context.Background(), `SELECT COUNT(*) FROM memory_versions WHERE memory_id=(SELECT memory_id FROM memories WHERE store_id=$1)`, store).Scan(&versions); err != nil || versions != 2 {
		t.Fatalf("history count=%d err=%v", versions, err)
	}
	cfg := mustJSON(gin.H{"memoryAccess": gin.H{store: "read_only"}})
	resourceSQL(t, s, `UPDATE environments SET config_json=$1 WHERE environment_id=$2`, cfg, env)
	if out := call(http.MethodDelete, path, ""); out.Code != 403 {
		t.Fatalf("read-only delete: %d", out.Code)
	}
	resourceSQL(t, s, `UPDATE sessions SET memory_store_ids_json='[]' WHERE session_id=$1`, sess)
	if out := call(http.MethodGet, path, ""); out.Code != 403 {
		t.Fatalf("revoked mount read: %d", out.Code)
	}
	if _, err := s.buildMemoryMount(context.Background(), store, "another-owner"); err == nil {
		t.Fatal("cross-owner mount accepted")
	}
}

func TestManagedSnapshotPinsFilesAndVaultRevisions(t *testing.T) {
	s := resourceTestServer(t)
	ctx := context.Background()
	owner, agent, vault, credential := shortID("owner_"), shortID("agent_"), shortID("vault_"), shortID("cred_")
	resourceSQL(t, s, `INSERT INTO workspace_files(owner_id,scope_type,scope_id,path,content,updated_at) VALUES($1,'agent',$2,'AGENTS.md','old',1)`, owner, agent)
	snapshot, err := s.agentSnapshot(ctx, owner, agent, "agent", "", "", "", 20, nil, nil, nil, nil, "/tmp/workspace", "", "", nil, nil, 1, 1, 1)
	if err != nil {
		t.Fatal(err)
	}
	resourceSQL(t, s, `UPDATE workspace_files SET content='new' WHERE owner_id=$1`, owner)
	files := snapshot["definitionFiles"].(map[string]string)
	if files["AGENTS.md"] != "old" {
		t.Fatalf("snapshot not pinned: %#v", files)
	}
	resourceSQL(t, s, `INSERT INTO vaults(vault_id,owner_id,display_name,created_at,updated_at) VALUES($1,$2,'test',1,1)`, vault, owner)
	ciphertext, err := encryptAESGCM(s.vaultKey, "first")
	if err != nil {
		t.Fatal(err)
	}
	resourceSQL(t, s, `INSERT INTO vault_credentials(credential_id,vault_id,type,label,target,ciphertext,created_at) VALUES($1,$2,'static_bearer','token','https://example.test/mcp',$3,1)`, credential, vault, ciphertext)
	creds, err := s.resolveVaultCredentials(ctx, []string{vault}, owner)
	if err != nil || len(creds) != 1 || creds[0]["secret"] != "first" {
		t.Fatalf("resolve err=%v count=%d", err, len(creds))
	}
	if _, err = s.resolveVaultCredentials(ctx, []string{vault}, "another-owner"); err == nil {
		t.Fatal("cross-owner vault accepted")
	}
	oldRevision := creds[0]["revision"]
	ciphertext, _ = encryptAESGCM(s.vaultKey, "second")
	resourceSQL(t, s, `UPDATE vault_credentials SET ciphertext=$1, revision=revision+1 WHERE credential_id=$2`, ciphertext, credential)
	creds, err = s.resolveVaultCredentials(ctx, []string{vault}, owner)
	if err != nil || creds[0]["revision"] == oldRevision {
		t.Fatal("rotation did not change revision")
	}
	encoded, _ := json.Marshal(snapshot)
	if bytes.Contains(encoded, []byte("second")) {
		t.Fatal("credential leaked into agent definition")
	}
}

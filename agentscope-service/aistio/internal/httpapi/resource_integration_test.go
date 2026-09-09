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
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"golang.org/x/crypto/bcrypt"
)

func TestProductResourcePolicyAcrossHTTPAndSessionCreation(t *testing.T) {
	dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set")
	}
	cfg := product.DefaultConfig()
	cfg.DSN = dsn
	cfg.SeedUsers = false
	cfg.WorkspaceRoot = t.TempDir()
	p, err := product.Open(t.Context(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(p.Close)
	db, err := pgxpool.New(t.Context(), dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	st, err := store.Open(t.Context(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	user := "resource-http-" + uuid.NewString()[:8]
	hash, _ := bcrypt.GenerateFromPassword([]byte("test-password"), bcrypt.MinCost)
	if _, err = db.Exec(t.Context(), `INSERT INTO cp.users(user_id,username,password_hash,roles_csv,created_at) VALUES($1,$1,$2,'user',0)`, user, string(hash)); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		for _, table := range []string{"account_login_sessions", "account_access_audit", "users"} {
			db.Exec(context.Background(), `DELETE FROM cp.`+table+` WHERE user_id=$1`, user)
		}
		db.Exec(context.Background(), `DELETE FROM cp.agents WHERE owner_id=$1`, user)
	})
	s := NewServer(ServerOptions{Store: st, Product: p, AuthToken: "test-service"})
	token := ""
	call := func(method, path, body string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		if token != "" {
			r.Header.Set("Authorization", "Bearer "+token)
		}
		out := httptest.NewRecorder()
		s.router.ServeHTTP(out, r)
		return out
	}
	response := call("POST", "/api/auth/login", `{"username":"`+user+`","password":"test-password"}`)
	var login struct {
		Token string `json:"token"`
	}
	if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &login) != nil {
		t.Fatal(response.Code, response.Body.String())
	}
	token = login.Token
	if _, err = p.EnsureManagedDefinition(t.Context(), user, "worker", product.ManagedDefinitionInput{Name: "Worker", System: "private instructions"}); err != nil {
		t.Fatal(err)
	}
	n, err := s.ensurePersonalNamespace(t.Context(), user)
	if err != nil {
		t.Fatal(err)
	}
	n.Resources = map[string]model.ResourcePolicy{"managed-agent:worker": {Mode: "restricted"}}
	if _, err = st.Access().PutNamespace(t.Context(), n, n.Version, user); err != nil {
		t.Fatal(err)
	}
	for _, request := range []struct{ method, path, body string }{{"GET", "/api/agents/worker/workspace", ""}, {"PUT", "/api/agents/worker/workspace/file?path=note.md", `{"content":"Bypass"}`}, {"POST", "/api/sessions", `{"agent":"worker"}`}} {
		response = call(request.method, request.path, request.body)
		if response.Code != 403 {
			t.Fatal("resource policy bypass", request.path, response.Code, response.Body.String())
		}
	}
	response = call("GET", "/api/v1/namespaces/"+n.Name+"/resources", "")
	if response.Code != 200 || !strings.Contains(response.Body.String(), "Worker") || strings.Contains(response.Body.String(), "private instructions") {
		t.Fatal("manager recovery catalog", response.Code, response.Body.String())
	}
}

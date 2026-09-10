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
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/postgres"
	"golang.org/x/crypto/bcrypt"
)

// Exercise both API surfaces through the production middleware and real account
// directory, including preferences and the namespace ownership disable guard.
func TestAccountNamespaceIntegration(t *testing.T) {
	dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set")
	}
	cfg := product.DefaultConfig()
	cfg.DSN, cfg.SeedUsers, cfg.WorkspaceRoot = dsn, false, t.TempDir()
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
	st, err := store.Open(t.Context(), store.Config{Driver: store.DriverPostgres, PostgresDSN: dsn})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	suffix := uuid.NewString()[:8]
	admin, owner := "mgmt-admin-"+suffix, "mgmt-owner-"+suffix
	hash, err := bcrypt.GenerateFromPassword([]byte("test-password"), bcrypt.MinCost)
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{admin, owner} {
		roles := "user"
		if id == admin {
			roles = "user,admin"
		}
		if _, err = db.Exec(t.Context(), `INSERT INTO cp.users(user_id,username,password_hash,roles_csv,created_at) VALUES($1,$1,$2,$3,0)`, id, string(hash), roles); err != nil {
			t.Fatal(err)
		}
	}
	t.Cleanup(func() {
		for _, table := range []string{"account_login_sessions", "account_access_audit", "users"} {
			_, _ = db.Exec(context.Background(), `DELETE FROM cp.`+table+` WHERE user_id=ANY($1::text[])`, []string{admin, owner})
		}
	})
	s := NewServer(ServerOptions{Store: st, Product: p, AuthToken: "test-service", DefaultTenant: "mgmt-" + suffix})
	request := func(token, method, path, body string, status int) string {
		t.Helper()
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		if token != "" {
			r.Header.Set("Authorization", "Bearer "+token)
		}
		w := httptest.NewRecorder()
		s.router.ServeHTTP(w, r)
		if w.Code != status {
			t.Fatalf("%s %s: %d want %d: %s", method, path, w.Code, status, w.Body.String())
		}
		return w.Body.String()
	}
	login := func(id string) string {
		var response struct {
			Token string `json:"token"`
		}
		body := request("", "POST", "/api/auth/login", `{"username":"`+id+`","password":"test-password"}`, 200)
		if err := json.Unmarshal([]byte(body), &response); err != nil || response.Token == "" {
			t.Fatal(body, err)
		}
		return response.Token
	}
	a, o := login(admin), login(owner)
	request(a, "POST", "/api/v1/namespaces", `{"name":"engineering","displayName":"Engineering","owner":"`+owner+`"}`, 201)
	var scope struct {
		Namespace string `json:"namespace"`
	}
	body := request(o, "GET", "/api/v1/me/scope", "", 200)
	if err := json.Unmarshal([]byte(body), &scope); err != nil || scope.Namespace != "default" {
		t.Fatal(body, err)
	}
	directory := request(o, "GET", "/api/v1/namespaces/engineering/accounts?q="+admin, "", 200)
	if !strings.Contains(directory, admin) || strings.Contains(directory, "password") {
		t.Fatal(directory)
	}
	request(o, "PUT", "/api/v1/me/preferences", `{"defaultNamespace":"foreign"}`, 400)
	request(o, "PUT", "/api/v1/me/preferences", `{"defaultNamespace":"engineering"}`, 200)
	body = request(o, "GET", "/api/v1/me/scope", "", 200)
	if err := json.Unmarshal([]byte(body), &scope); err != nil || scope.Namespace != "engineering" {
		t.Fatal(body, err)
	}
	request(a, "PATCH", "/api/admin/users/"+owner+"/status", `{"disabled":true,"version":1}`, 409)
	request(o, "POST", "/api/v1/namespaces/engineering/transfer", `{"owner":"`+admin+`","version":1}`, 200)
	request(a, "PUT", "/api/v1/namespaces/engineering", `{"members":{},"version":2}`, 200)
	body = request(o, "GET", "/api/v1/me/scope", "", 200)
	if err := json.Unmarshal([]byte(body), &scope); err != nil || scope.Namespace != "default" {
		t.Fatal("revoked default remained selected", body, err)
	}
	request(a, "PATCH", "/api/admin/users/"+owner+"/status", `{"disabled":true,"version":1}`, 200)
	request(o, "GET", "/api/v1/me/scope", "", 401)
	request(o, "GET", "/api/user/profile", "", 401)
}

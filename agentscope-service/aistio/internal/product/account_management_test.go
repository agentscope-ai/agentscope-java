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
package product

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/gin-gonic/gin"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

type accountFixture struct {
	s           *Server
	router      *gin.Engine
	admin, user string
}

func accountManagementFixture(t *testing.T) accountFixture {
	t.Helper()
	dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set")
	}
	ctx := context.Background()
	db, err := openDB(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	if err = migrate(ctx, db); err != nil {
		t.Fatal(err)
	}
	f := accountFixture{s: &Server{db: db, cfg: DefaultConfig()}, admin: shortID("access-admin-"), user: shortID("access-user-")}
	hash, _ := hashPassword("test-password")
	for _, id := range []string{f.admin, f.user} {
		roles := "user"
		if id == f.admin {
			roles = "user,admin"
		}
		_, err = db.Pool.Exec(ctx, `INSERT INTO users(user_id,username,password_hash,roles_csv,created_at) VALUES($1,$1,$2,$3,$4)`, id, hash, roles, nowMillis())
		if err != nil {
			t.Fatal(err)
		}
	}
	t.Cleanup(func() {
		for _, table := range []string{"account_login_sessions", "account_access_audit", "users"} {
			_, _ = db.Pool.Exec(context.Background(), `DELETE FROM `+table+` WHERE user_id=ANY($1::text[])`, []string{f.admin, f.user})
		}
	})
	gin.SetMode(gin.TestMode)
	f.router = gin.New()
	f.router.Use(f.s.jwtMiddleware())
	f.s.registerAuth(f.router)
	f.s.registerAdmin(f.router)
	return f
}
func (f accountFixture) request(token, method, path, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("User-Agent", "Access acceptance browser")
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	f.router.ServeHTTP(w, r)
	return w
}
func (f accountFixture) login(t *testing.T, id string) string {
	t.Helper()
	w := f.request("", "POST", "/api/auth/login", `{"username":"`+id+`","password":"test-password"}`)
	if w.Code != 200 {
		t.Fatalf("login: %d %s", w.Code, w.Body.String())
	}
	var v struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &v); err != nil {
		t.Fatal(err)
	}
	return v.Token
}

func TestAccountManagementSessionRevocationAndIdentity(t *testing.T) {
	f := accountManagementFixture(t)
	admin := f.login(t, f.admin)
	first := f.login(t, f.user)
	second := f.login(t, f.user)
	unseen, err := issueToken(f.s.cfg.JWTSecret, f.user, f.user, []string{"user"})
	if err != nil {
		t.Fatal(err)
	}
	if first == second {
		t.Fatal("logins issued the same session")
	}
	if w := f.request(first, "DELETE", "/api/user/login-sessions/"+sessionFingerprint(admin), ""); w.Code != 404 {
		t.Fatal("foreign login session revoked")
	}
	w := f.request(first, "POST", "/api/user/login-sessions/revoke-others", "")
	if w.Code != 204 {
		t.Fatal(w.Code)
	}
	if _, err := f.s.VerifyAccountToken(context.Background(), unseen); err == nil {
		t.Fatal("previously unused login survived revocation")
	}
	if _, err := f.s.VerifyAccountToken(context.Background(), second); err == nil {
		t.Fatal("revoked session accepted")
	}
	if _, err := f.s.VerifyAccountToken(context.Background(), first); err != nil {
		t.Fatal("current session was revoked", err)
	}
	w = f.request(first, "PUT", "/api/user/profile", `{"displayName":"Alice","roles":["admin"]}`)
	if w.Code != 200 {
		t.Fatalf("profile: %d %s", w.Code, w.Body.String())
	}
	claims, err := f.s.VerifyAccountToken(context.Background(), first)
	if err != nil || hasRole(claims.Roles, "admin") {
		t.Fatal("profile escalated platform access")
	}
	if w = f.request(first, "GET", "/api/admin/users", ""); w.Code != 403 {
		t.Fatal("non-admin enumerated accounts")
	}
	if w = f.request(admin, "PATCH", "/api/admin/users/"+f.user+"/roles", `{"roles":["invalid"],"version":2}`); w.Code != 400 {
		t.Fatal("unknown role accepted")
	}
	w = f.request(admin, "PATCH", "/api/admin/users/"+f.user+"/roles", `{"roles":["user","operator"],"version":2}`)
	if w.Code != 200 {
		t.Fatalf("roles: %d %s", w.Code, w.Body.String())
	}
	claims, err = f.s.VerifyAccountToken(context.Background(), first)
	if err != nil || !hasRole(claims.Roles, "operator") {
		t.Fatal("role changes not live")
	}
	w = f.request(admin, "PATCH", "/api/admin/users/"+f.user+"/roles", `{"roles":["user"],"version":2}`)
	if w.Code != 409 {
		t.Fatal("stale account change accepted")
	}
	w = f.request(admin, "PATCH", "/api/admin/users/"+f.user+"/status", `{"disabled":true,"version":3}`)
	if w.Code != 200 {
		t.Fatalf("disable: %d %s", w.Code, w.Body.String())
	}
	if _, err = f.s.VerifyAccountToken(context.Background(), first); err == nil {
		t.Fatal("disabled account accepted")
	}
	if w = f.request("", "POST", "/api/auth/login", `{"username":"`+f.user+`","password":"test-password"}`); w.Code != 401 {
		t.Fatal("disabled account logged in")
	}
	if w = f.request(admin, "PATCH", "/api/admin/users/"+f.user+"/status", `{"disabled":false,"version":4}`); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	if _, err = f.s.VerifyAccountToken(context.Background(), first); err == nil {
		t.Fatal("old login revived after enabling")
	}
	_ = f.login(t, f.user)
	w = f.request(admin, "GET", "/api/admin/access-audit?userId="+f.user, "")
	if w.Code != 200 || !strings.Contains(w.Body.String(), "account.disabled") || strings.Contains(w.Body.String(), "test-password") {
		t.Fatalf("audit: %d %s", w.Code, w.Body.String())
	}
}

func TestAccountManagementPasswordAndLifecycleGuards(t *testing.T) {
	f := accountManagementFixture(t)
	admin := f.login(t, f.admin)
	first := f.login(t, f.user)
	second := f.login(t, f.user)
	unseen, err := issueToken(f.s.cfg.JWTSecret, f.user, f.user, []string{"user"})
	if err != nil {
		t.Fatal(err)
	}
	f.s.SetAccountDisableGuard(func(context.Context, string) error {
		return fmt.Errorf("Transfer ownership of namespace engineering first")
	})
	w := f.request(admin, "PATCH", "/api/admin/users/"+f.user+"/status", `{"disabled":true,"version":1}`)
	if w.Code != 409 {
		t.Fatal("owned namespace was orphaned")
	}
	w = f.request(admin, "PATCH", "/api/admin/users/"+f.admin+"/status", `{"disabled":true,"version":1}`)
	if w.Code != 400 {
		t.Fatal("self-disable allowed")
	}
	// Fixture databases contain only this test's active platform administrator.
	var count int
	_ = f.s.db.Pool.QueryRow(context.Background(), `SELECT count(*) FROM users WHERE disabled=false AND 'admin'=ANY(string_to_array(lower(roles_csv),','))`).Scan(&count)
	if count == 1 {
		w = f.request(admin, "PATCH", "/api/admin/users/"+f.admin+"/roles", `{"roles":["user"],"version":1}`)
		if w.Code != 409 {
			t.Fatal("last administrator removed")
		}
	}
	w = f.request(first, "POST", "/api/user/change-password", `{"currentPassword":"test-password","newPassword":"new-password"}`)
	if w.Code != 204 {
		t.Fatalf("change password: %d %s", w.Code, w.Body.String())
	}
	if _, err := f.s.VerifyAccountToken(context.Background(), first); err != nil {
		t.Fatal(err)
	}
	if _, err := f.s.VerifyAccountToken(context.Background(), unseen); err == nil {
		t.Fatal("previously unused login survived revocation")
	}
	if _, err := f.s.VerifyAccountToken(context.Background(), second); err == nil {
		t.Fatal("other session survived password change")
	}
	w = f.request(admin, "PATCH", "/api/admin/users/"+f.user+"/password", `{"newPassword":"reset-password"}`)
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	if _, err := f.s.VerifyAccountToken(context.Background(), first); err == nil {
		t.Fatal("session survived administrator reset")
	}
	if w = f.request(first, "GET", "/api/user/profile", ""); w.Code != 401 {
		t.Fatal("revoked credential reached handler")
	}
}

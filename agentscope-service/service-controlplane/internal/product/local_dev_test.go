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
	"context"
	"encoding/json"
	"net/http/httptest"
	"os"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestLocalDevelopmentAuthenticationIsExplicit(t *testing.T) {
	cfg := DefaultConfig()
	server := &Server{cfg: cfg}
	if _, err := server.VerifyAccountToken(context.Background(), ""); err == nil {
		t.Fatal("default configuration accepted missing authentication")
	}
	router := gin.New()
	router.Use(server.Middlewares()...)
	router.GET("/api/auth/dev-session", server.localDevSession)
	response := httptest.NewRecorder()
	router.ServeHTTP(response, httptest.NewRequest("GET", "/api/auth/dev-session", nil))
	if response.Code != 404 {
		t.Fatalf("development session available in normal mode: %d", response.Code)
	}
	server.cfg.LocalDev = true
	claims, err := server.VerifyAccountToken(context.Background(), "invalid")
	if err != nil || claims.Subject != LocalDeveloperID {
		t.Fatalf("local authentication: %+v %v", claims, err)
	}
	response = httptest.NewRecorder()
	router.ServeHTTP(response, httptest.NewRequest("GET", "/api/auth/dev-session", nil))
	if response.Code != 200 || response.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("local session: %d %s", response.Code, response.Body.String())
	}
}

func TestLocalDeveloperCannotLogInAfterDisablingMode(t *testing.T) {
	dsn := os.Getenv("CONTROL_PLANE_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("CONTROL_PLANE_TEST_POSTGRES_DSN not set")
	}
	cfg := DefaultConfig()
	cfg.LocalDev, cfg.DSN, cfg.WorkspaceRoot = true, dsn, t.TempDir()
	server, err := Open(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	// Starting again neither duplicates the developer nor changes its credentials.
	if err := seedLocalDeveloper(context.Background(), server.db); err != nil {
		t.Fatal(err)
	}
	router := gin.New()
	router.Use(server.Middlewares()...)
	server.registerAuth(router)
	response := httptest.NewRecorder()
	router.ServeHTTP(response, httptest.NewRequest("GET", "/api/auth/dev-session", nil))
	var session struct {
		Token string `json:"token"`
	}
	if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &session) != nil || session.Token == "" {
		t.Fatalf("development session: %d %s", response.Code, response.Body.String())
	}
	server.cfg.LocalDev = false
	if _, err := server.VerifyAccountToken(context.Background(), session.Token); err == nil {
		t.Fatal("development token became a production login")
	}
	var hash string
	if err := server.db.Pool.QueryRow(context.Background(), "SELECT password_hash FROM users WHERE user_id=$1", LocalDeveloperID).Scan(&hash); err != nil || hash != "" {
		t.Fatalf("development account has a usable password: %v", err)
	}
}

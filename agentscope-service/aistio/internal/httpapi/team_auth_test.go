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

package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestTeamsAuthAcceptsInternalToken(t *testing.T) {
	gin.SetMode(gin.TestMode)
	st, err := store.Open(context.Background(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	defer st.Close()

	const token = "local-dev-internal-token-at-least-32chars"
	srv := NewServer(ServerOptions{
		Store:         st,
		InternalToken: token,
		// Require bearer unless internal token matches — simulates product JWT bar.
		AuthToken: "console-static-token",
	})

	body, _ := json.Marshal(map[string]any{
		"name":      "auth-team",
		"objective": "verify internal token",
		"lead":      map[string]string{"agentRef": "lead-agent"},
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/teams", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Builder-Internal-Token", token)
	w := httptest.NewRecorder()
	srv.router.ServeHTTP(w, req)
	if w.Code == http.StatusUnauthorized || w.Code == http.StatusForbidden {
		t.Fatalf("expected internal token to authenticate teams, got %d: %s", w.Code, w.Body.String())
	}
}

func TestTeamsAuthRejectsMissingCredentials(t *testing.T) {
	gin.SetMode(gin.TestMode)
	st, err := store.Open(context.Background(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	defer st.Close()

	srv := NewServer(ServerOptions{
		Store:         st,
		InternalToken: "local-dev-internal-token-at-least-32chars",
		AuthToken:     "console-static-token",
	})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/teams", nil)
	w := httptest.NewRecorder()
	srv.router.ServeHTTP(w, req)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 without credentials, got %d: %s", w.Code, w.Body.String())
	}
}

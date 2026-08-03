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

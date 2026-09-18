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
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestCollaborationRejectsUnscopedInternalToken(t *testing.T) {
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

	req := httptest.NewRequest(http.MethodGet, "/api/v1/teams?tenant=t&namespace=n", nil)
	req.Header.Set("X-Builder-Internal-Token", token)
	w := httptest.NewRecorder()
	srv.router.ServeHTTP(w, req)
	if w.Code != http.StatusForbidden {
		t.Fatalf("expected unscoped internal token to be forbidden, got %d: %s", w.Code, w.Body.String())
	}
}

func TestTaskTokenCanOnlyReadItsIssue(t *testing.T) {
	gin.SetMode(gin.TestMode)
	st, err := store.Open(context.Background(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	creator := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	owned, err := st.Collaboration().CreateIssue(context.Background(), &controlmodel.Issue{Tenant: "t", Namespace: "n", Title: "owned", Creator: creator, AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "agent-a"})
	if err != nil {
		t.Fatal(err)
	}
	other, err := st.Collaboration().CreateIssue(context.Background(), &controlmodel.Issue{Tenant: "t", Namespace: "n", Title: "other", Creator: creator})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(context.Background(), store.AgentTaskFilter{IssueID: owned.ID, Limit: 10})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("task setup: %v", err)
	}
	srv := NewServer(ServerOptions{Store: st, InternalToken: "local-dev-internal-token-at-least-32chars", AuthToken: "console-static-token", TaskTokenSecret: "0123456789abcdef0123456789abcdef"})
	token, err := srv.taskTokens.Mint(tasks[0].ID, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	request := func(issueID string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodGet, "/api/v1/issues/"+issueID, nil)
		req.Header.Set("X-Agent-Task-Token", token)
		w := httptest.NewRecorder()
		srv.router.ServeHTTP(w, req)
		return w
	}
	if w := request(owned.ID.String()); w.Code != http.StatusOK {
		t.Fatalf("owned issue: %d %s", w.Code, w.Body.String())
	}
	if w := request(other.ID.String()); w.Code != http.StatusForbidden {
		t.Fatalf("cross-issue read: %d %s", w.Code, w.Body.String())
	}
	runReq := httptest.NewRequest(http.MethodGet, "/api/v1/agent-tasks/"+tasks[0].ID.String()+"/run", nil)
	runReq.Header.Set("X-Agent-Task-Token", token)
	runResponse := httptest.NewRecorder()
	srv.router.ServeHTTP(runResponse, runReq)
	if runResponse.Code != http.StatusOK {
		t.Fatalf("task-scoped Run read: %d %s", runResponse.Code, runResponse.Body.String())
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

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
	"github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func newPlanTestServer(t *testing.T) (*Server, store.Store) {
	t.Helper()
	gin.SetMode(gin.TestMode)
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatalf("memory.Open: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })

	ctx := context.Background()
	if _, err := st.Teams().Create(ctx, &store.Team{
		Name: "research", Namespace: "default", Objective: "ship it", Phase: store.TeamPhaseRunning,
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := st.Teams().UpsertMember(ctx, &store.TeamMember{
		TeamName: "research", Namespace: "default", MemberName: "worker-1",
		AgentRef: "worker-agent", PlanApproval: true, Phase: store.MemberPhaseWorking,
	}); err != nil {
		t.Fatal(err)
	}
	return NewServer(ServerOptions{Store: st}), st
}

func postPlanJSON(t *testing.T, srv *Server, path string, body any) *httptest.ResponseRecorder {
	t.Helper()
	var reader *bytes.Reader
	if body == nil {
		reader = bytes.NewReader(nil)
	} else {
		b, err := json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
		reader = bytes.NewReader(b)
	}
	req := httptest.NewRequest(http.MethodPost, path, reader)
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	srv.router.ServeHTTP(w, req)
	return w
}

func TestMemberPlanSubmitApproveReject(t *testing.T) {
	srv, st := newPlanTestServer(t)
	const base = "/api/v1/teams/research/members/worker-1/plan"

	w := postPlanJSON(t, srv, base, map[string]string{"planText": "1. read docs\n2. write code"})
	if w.Code != http.StatusOK {
		t.Fatalf("submit status=%d body=%s", w.Code, w.Body.String())
	}
	m, err := st.Teams().GetMember(context.Background(), "default", "research", "worker-1")
	if err != nil {
		t.Fatal(err)
	}
	if m.PlanStatus != store.PlanStatusPending || m.PlanText == "" {
		t.Fatalf("after submit: status=%q text=%q", m.PlanStatus, m.PlanText)
	}

	w = postPlanJSON(t, srv, base+"/approve", map[string]string{"note": "looks good"})
	if w.Code != http.StatusOK {
		t.Fatalf("approve status=%d body=%s", w.Code, w.Body.String())
	}
	m, _ = st.Teams().GetMember(context.Background(), "default", "research", "worker-1")
	if m.PlanStatus != store.PlanStatusApproved {
		t.Fatalf("after approve: status=%q", m.PlanStatus)
	}
	if m.PlanText == "" {
		t.Fatal("approve must not clear the submitted plan text")
	}

	w = postPlanJSON(t, srv, base+"/reject", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("reject status=%d body=%s", w.Code, w.Body.String())
	}
	m, _ = st.Teams().GetMember(context.Background(), "default", "research", "worker-1")
	if m.PlanStatus != store.PlanStatusRejected {
		t.Fatalf("after reject: status=%q", m.PlanStatus)
	}

	// The lead is notified on submit, and the member on each decision.
	msgs := srv.messageRouter.GetMessageHistory("default", "research", 50)
	if len(msgs) != 3 {
		t.Fatalf("expected 3 outbox messages, got %d", len(msgs))
	}
}

func TestMemberPlanRequiresText(t *testing.T) {
	srv, _ := newPlanTestServer(t)
	w := postPlanJSON(t, srv, "/api/v1/teams/research/members/worker-1/plan", map[string]string{})
	if w.Code != http.StatusBadRequest {
		t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
	}
}

func TestMemberPlanUnknownMember(t *testing.T) {
	srv, _ := newPlanTestServer(t)
	w := postPlanJSON(t, srv, "/api/v1/teams/research/members/ghost/plan",
		map[string]string{"planText": "x"})
	if w.Code != http.StatusNotFound {
		t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
	}
}

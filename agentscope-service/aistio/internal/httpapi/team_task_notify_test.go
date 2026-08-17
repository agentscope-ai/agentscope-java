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
	"strings"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

const notifyTestToken = "local-dev-internal-token-at-least-32chars"

func newTaskNotifyServer(t *testing.T) (*Server, store.Store) {
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
		AgentRef: "worker-agent", Phase: store.MemberPhaseWorking,
	}); err != nil {
		t.Fatal(err)
	}
	return NewServer(ServerOptions{
		Store:         st,
		InternalToken: notifyTestToken,
		AuthToken:     "console-static-token",
	}), st
}

// postAs issues a team API call either as the data plane (an agent acting for
// itself) or as a console operator driving the board by hand.
func postAs(t *testing.T, srv *Server, path string, body any, asAgent bool) *httptest.ResponseRecorder {
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
	if asAgent {
		req.Header.Set("X-Builder-Internal-Token", notifyTestToken)
	} else {
		req.Header.Set("Authorization", "Bearer console-static-token")
	}
	w := httptest.NewRecorder()
	srv.router.ServeHTTP(w, req)
	return w
}

func messagesTo(srv *Server, member string) []*store.TeamMessage {
	var out []*store.TeamMessage
	for _, m := range srv.messageRouter.GetMessageHistory("default", "research", 50) {
		if m.ToMember == member {
			out = append(out, m)
		}
	}
	return out
}

func TestCreateTaskWithOwnerNotifiesOwner(t *testing.T) {
	srv, _ := newTaskNotifyServer(t)

	w := postAs(t, srv, "/api/v1/teams/research/tasks", map[string]any{
		"subject": "collect docs", "description": "read the SDK guide", "owner": "worker-1",
	}, true)
	if w.Code != http.StatusCreated {
		t.Fatalf("create status=%d body=%s", w.Code, w.Body.String())
	}

	msgs := messagesTo(srv, "worker-1")
	if len(msgs) != 1 {
		t.Fatalf("expected 1 notice to worker-1, got %d", len(msgs))
	}
	if !strings.Contains(msgs[0].Content, "task-1") {
		t.Fatalf("notice must name the task: %q", msgs[0].Content)
	}
	if !strings.Contains(msgs[0].Content, "read the SDK guide") {
		t.Fatalf("notice must carry the description: %q", msgs[0].Content)
	}
	if !strings.Contains(msgs[0].Content, "claimTask") {
		t.Fatalf("notice must tell the worker to claim: %q", msgs[0].Content)
	}
}

func TestAssignTaskNotifiesNewOwner(t *testing.T) {
	srv, _ := newTaskNotifyServer(t)

	w := postAs(t, srv, "/api/v1/teams/research/tasks",
		map[string]any{"subject": "collect docs"}, true)
	if w.Code != http.StatusCreated {
		t.Fatalf("create status=%d body=%s", w.Code, w.Body.String())
	}
	if got := messagesTo(srv, "worker-1"); len(got) != 0 {
		t.Fatalf("unowned task must not notify anyone, got %d", len(got))
	}

	w = postAs(t, srv, "/api/v1/teams/research/tasks/task-1/assign",
		map[string]any{"owner": "worker-1", "resourceVersion": "1"}, true)
	if w.Code != http.StatusOK {
		t.Fatalf("assign status=%d body=%s", w.Code, w.Body.String())
	}

	msgs := messagesTo(srv, "worker-1")
	if len(msgs) != 1 {
		t.Fatalf("expected 1 notice to worker-1, got %d", len(msgs))
	}
	if !strings.Contains(msgs[0].Content, "assigned to you") {
		t.Fatalf("unexpected notice: %q", msgs[0].Content)
	}
}

// A lead that passes the agent name instead of the roster name would otherwise
// create a task owned by nobody: invisible to every claimable list and to the
// unassigned pool.
func TestOwnerMustBeARosterMember(t *testing.T) {
	srv, _ := newTaskNotifyServer(t)

	w := postAs(t, srv, "/api/v1/teams/research/tasks",
		map[string]any{"subject": "collect docs", "owner": "worker-agent"}, true)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("create status=%d body=%s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "worker-1") {
		t.Fatalf("error must name the valid members: %s", w.Body.String())
	}
	if tasks := srv.taskStore.List("default", "research"); len(tasks) != 0 {
		t.Fatalf("rejected task must not reach the board, got %d", len(tasks))
	}

	postAs(t, srv, "/api/v1/teams/research/tasks", map[string]any{"subject": "ok"}, true)
	w = postAs(t, srv, "/api/v1/teams/research/tasks/task-1/assign",
		map[string]any{"owner": "worker-agent", "resourceVersion": "1"}, true)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("assign status=%d body=%s", w.Code, w.Body.String())
	}

	w = postAs(t, srv, "/api/v1/teams/research/tasks/task-1/claim",
		map[string]any{"claimedBy": "ghost", "resourceVersion": "1"}, true)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("claim status=%d body=%s", w.Code, w.Body.String())
	}
}

// A lead replying to its own name would wake itself instead of the teammate, so
// the reply the worker is waiting for never arrives.
func TestSelfAddressedMessageIsRejected(t *testing.T) {
	srv, _ := newTaskNotifyServer(t)

	w := postAs(t, srv, "/api/v1/teams/research/messages",
		map[string]any{"from": "lead", "to": "lead", "content": "here are the docs"}, true)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "yourself") {
		t.Fatalf("error must explain the rule: %s", w.Body.String())
	}
	if got := messagesTo(srv, "lead"); len(got) != 0 {
		t.Fatalf("self message must not be stored, got %d", len(got))
	}
}

func TestMessageRecipientMustBeARosterMember(t *testing.T) {
	srv, _ := newTaskNotifyServer(t)

	w := postAs(t, srv, "/api/v1/teams/research/messages",
		map[string]any{"from": "lead", "to": "worker-agent", "content": "status?"}, true)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "worker-1") {
		t.Fatalf("error must name the valid members: %s", w.Body.String())
	}
}

func TestClaimByAgentItselfDoesNotNotify(t *testing.T) {
	srv, _ := newTaskNotifyServer(t)

	postAs(t, srv, "/api/v1/teams/research/tasks",
		map[string]any{"subject": "collect docs"}, true)
	w := postAs(t, srv, "/api/v1/teams/research/tasks/task-1/claim",
		map[string]any{"claimedBy": "worker-1", "resourceVersion": "1"}, true)
	if w.Code != http.StatusOK {
		t.Fatalf("claim status=%d body=%s", w.Code, w.Body.String())
	}

	if got := messagesTo(srv, "worker-1"); len(got) != 0 {
		t.Fatalf("a member claiming its own task needs no notice, got %d", len(got))
	}
}

func TestConsoleStartNotifiesOwner(t *testing.T) {
	srv, _ := newTaskNotifyServer(t)

	postAs(t, srv, "/api/v1/teams/research/tasks",
		map[string]any{"subject": "collect docs", "owner": "worker-1"}, true)
	before := len(messagesTo(srv, "worker-1"))

	w := postAs(t, srv, "/api/v1/teams/research/tasks/task-1/claim",
		map[string]any{"claimedBy": "worker-1", "resourceVersion": "1"}, false)
	if w.Code != http.StatusOK {
		t.Fatalf("claim status=%d body=%s", w.Code, w.Body.String())
	}

	msgs := messagesTo(srv, "worker-1")
	if len(msgs) != before+1 {
		t.Fatalf("expected one more notice after console start, got %d (was %d)", len(msgs), before)
	}
	found := false
	for _, m := range msgs {
		if strings.Contains(m.Content, "in progress") {
			found = true
		}
	}
	if !found {
		t.Fatalf("console start must tell the owner the task is in progress: %+v", msgs)
	}
}

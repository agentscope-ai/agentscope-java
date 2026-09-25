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
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/spring-ai-alibaba/aistio/internal/conversation"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func chatRequest(server *Server, method, path, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, bytes.NewBufferString(body))
	req.Header.Set("Authorization", "Bearer console")
	req.Header.Set("Content-Type", "application/json")
	out := httptest.NewRecorder()
	server.router.ServeHTTP(out, req)
	return out
}

func TestChatOwnsDurableSessionAndDispatchesTurns(t *testing.T) {
	st, agent, _, _ := setupConversationAgent(t)
	commands := &endpointCommandCapture{}
	server := NewServer(ServerOptions{Store: st, AuthToken: "console", ASDPCommands: commands})

	created := chatRequest(server, http.MethodPost, "/api/v1/chats",
		`{"tenant":"t","namespace":"n","agentId":"`+agent.ID.String()+`","title":"Design review"}`)
	if created.Code != http.StatusCreated {
		t.Fatalf("create Chat: %d %s", created.Code, created.Body)
	}
	var response struct {
		Chat controlmodel.Chat `json:"chat"`
	}
	if err := json.Unmarshal(created.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.Chat.ID == uuid.Nil || response.Chat.SessionID == uuid.Nil ||
		response.Chat.CreatorRef != "system" || response.Chat.Title != "Design review" {
		t.Fatalf("unexpected Chat: %+v", response.Chat)
	}
	session, err := st.Sessions().GetByID(context.Background(), response.Chat.SessionID)
	if err != nil || session.OriginType != "chat" || session.OriginRef != response.Chat.ID.String() {
		t.Fatalf("Chat session provenance: session=%+v err=%v", session, err)
	}

	turn := chatRequest(server, http.MethodPost, "/api/v1/chats/"+response.Chat.ID.String()+
		"/turns?tenant=t&namespace=n", `{"message":"hello"}`)
	if turn.Code != http.StatusAccepted || len(commands.turns) != 1 {
		t.Fatalf("send turn: %d %s commands=%d", turn.Code, turn.Body, len(commands.turns))
	}

	listed := chatRequest(server, http.MethodGet, "/api/v1/chats?tenant=t&namespace=n", "")
	if listed.Code != http.StatusOK {
		t.Fatalf("list Chat: %d %s", listed.Code, listed.Body)
	}
	var list struct {
		Items []controlmodel.Chat `json:"items"`
	}
	if json.Unmarshal(listed.Body.Bytes(), &list) != nil || len(list.Items) != 1 || list.Items[0].ID != response.Chat.ID {
		t.Fatalf("unexpected Chat list: %s", listed.Body)
	}

	archived := chatRequest(server, http.MethodPatch, "/api/v1/chats/"+response.Chat.ID.String()+
		"?tenant=t&namespace=n", `{"status":"archived","version":1}`)
	if archived.Code != http.StatusOK {
		t.Fatalf("archive Chat: %d %s", archived.Code, archived.Body)
	}
	rejected := chatRequest(server, http.MethodPost, "/api/v1/chats/"+response.Chat.ID.String()+
		"/turns?tenant=t&namespace=n", `{"message":"again"}`)
	if rejected.Code != http.StatusConflict {
		t.Fatalf("archived Chat accepted a turn: %d %s", rejected.Code, rejected.Body)
	}
}

func TestConversationTurnIssuesRequireExplicitKind(t *testing.T) {
	st, _, _, _ := setupConversationAgent(t)
	ctx := context.Background()
	_, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "t", Namespace: "n", Title: "Conversation turn", Status: controlmodel.IssueInProgress,
		Kind: controlmodel.IssueKindConversationTurn, Visibility: controlmodel.IssueVisibilityOperational,
		CompletionPolicy: controlmodel.IssueCompletionAutomatic,
	})
	if err != nil {
		t.Fatal(err)
	}
	items, err := st.Collaboration().ListIssues(ctx, store.IssueFilter{Tenant: "t", Namespace: "n"})
	if err != nil || len(items) != 0 {
		t.Fatalf("internal Chat turn leaked into Issues: items=%+v err=%v", items, err)
	}
	items, err = st.Collaboration().ListIssues(ctx, store.IssueFilter{Tenant: "t", Namespace: "n",
		Kind: controlmodel.IssueKindConversationTurn})
	if err != nil || len(items) != 1 {
		t.Fatalf("explicit diagnostics cannot find Chat turn: items=%+v err=%v", items, err)
	}
}

func TestChatMapsOverlappingHostedTurnToConflict(t *testing.T) {
	st, agent, _, _ := setupHostedConversationAgent(t)
	server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	created := chatRequest(server, http.MethodPost, "/api/v1/chats",
		`{"tenant":"t","namespace":"n","agentId":"`+agent.ID.String()+`"}`)
	if created.Code != http.StatusCreated {
		t.Fatalf("create Chat: %d %s", created.Code, created.Body)
	}
	var response struct {
		Chat controlmodel.Chat `json:"chat"`
	}
	if err := json.Unmarshal(created.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	path := "/api/v1/chats/" + response.Chat.ID.String() + "/turns?tenant=t&namespace=n"
	first := chatRequest(server, http.MethodPost, path, `{"message":"first"}`)
	if first.Code != http.StatusAccepted {
		t.Fatalf("first turn: %d %s", first.Code, first.Body)
	}
	overlap := chatRequest(server, http.MethodPost, path, `{"message":"overlap"}`)
	if overlap.Code != http.StatusConflict {
		t.Fatalf("overlapping turn status=%d, want 409: %s", overlap.Code, overlap.Body)
	}
	var conflict ErrorResponse
	if json.Unmarshal(overlap.Body.Bytes(), &conflict) != nil || conflict.Code != "conversation_turn_conflict" {
		t.Fatalf("overlapping turn has no stable error code: %s", overlap.Body)
	}
}

func TestHostedChatTurnKeepsItsOwnersPrivateWorkAccess(t *testing.T) {
	testHostedChatTurnPrivateAccess(t, store.Config{Driver: store.DriverMemory})
}

func TestHostedChatTurnPrivateAccessPostgres(t *testing.T) {
	testHostedChatTurnPrivateAccess(t, acceptancePostgresConfig(t))
}

func testHostedChatTurnPrivateAccess(t *testing.T, cfg store.Config) {
	st, agent, binding, _ := setupHostedConversationAgent(t, cfg)
	server := NewServer(ServerOptions{Store: st})
	ctx := context.Background()
	chatID := uuid.New()
	session, err := server.resolveAgentConversation(ctx, agent, "", "chat", chatID.String())
	if err != nil {
		t.Fatal(err)
	}
	_, err = st.Chats().Create(ctx, &controlmodel.Chat{ID: chatID, Tenant: agent.Tenant,
		Namespace: agent.Namespace, CreatorRef: "alice", AgentID: agent.ID, AgentName: agent.DisplayName,
		SessionID: session.ID, RuntimeSession: session.SessionID, Status: controlmodel.ChatActive})
	if err != nil {
		t.Fatal(err)
	}
	owner := store.WithWorkAccess(ctx, store.WorkAccess{Refs: []string{"alice"}, Restricted: true})
	turn, err := server.dispatchHostedConversationTurn(owner, session, binding, "private message", uuid.NewString(), "chat", chatID.String())
	if err != nil {
		t.Fatalf("owner cannot dispatch private Chat: %v", err)
	}
	if _, err = st.Collaboration().GetAgentTask(owner, turn.AgentTaskID); err != nil {
		t.Fatalf("owner cannot inspect their turn: %v", err)
	}
	other := store.WithWorkAccess(ctx, store.WorkAccess{Refs: []string{"bob"}, Restricted: true})
	for _, reader := range []context.Context{owner, other} {
		tasks, err := st.Collaboration().ListAgentTasks(reader, store.AgentTaskFilter{RunID: turn.RunID})
		want := 0
		if reader == owner {
			want = 1
		}
		if err != nil || len(tasks) != want {
			t.Fatalf("private turn visibility: got %d, want %d, err=%v", len(tasks), want, err)
		}
	}
}

func TestChatDeleteAndRestore(t *testing.T) {
	testChatDeleteAndRestore(t, store.Config{Driver: store.DriverMemory})
}

func TestChatDeleteAndRestorePostgres(t *testing.T) {
	testChatDeleteAndRestore(t, acceptancePostgresConfig(t))
}

func testChatDeleteAndRestore(t *testing.T, cfg store.Config) {
	st, agent, _, _ := setupConversationAgent(t, cfg)
	ctx := context.Background()
	server := NewServer(ServerOptions{Store: st, AuthToken: "console", ASDPCommands: &endpointCommandCapture{}})
	created := chatRequest(server, http.MethodPost, "/api/v1/chats", `{"tenant":"t","namespace":"n","agentId":"`+agent.ID.String()+`"}`)
	var response struct {
		Chat controlmodel.Chat `json:"chat"`
	}
	if created.Code != http.StatusCreated || json.Unmarshal(created.Body.Bytes(), &response) != nil {
		t.Fatalf("create: %d %s", created.Code, created.Body)
	}
	chat := response.Chat
	path := "/api/v1/chats/" + chat.ID.String() + "?tenant=t&namespace=n"
	turnPath := "/api/v1/chats/" + chat.ID.String() + "/turns?tenant=t&namespace=n"
	if out := chatRequest(server, http.MethodPost, turnPath, `{"message":"hello"}`); out.Code != http.StatusAccepted {
		t.Fatal(out.Body)
	}
	if out := chatRequest(server, http.MethodDelete, path, ""); out.Code != http.StatusConflict {
		t.Fatalf("deleted running Chat: %d %s", out.Code, out.Body)
	}
	session, _ := st.Sessions().GetByID(ctx, chat.SessionID)
	turn := conversation.Read(session)
	if err := conversation.Fail(ctx, st, session, turn.ID, "test_failure", "Test failure", time.Now()); err != nil {
		t.Fatal(err)
	}
	before, _ := st.Events().List(ctx, session.ID)
	if out := chatRequest(server, http.MethodDelete, path, ""); out.Code != http.StatusOK {
		t.Fatalf("delete: %d %s", out.Code, out.Body)
	}
	// Deletion is idempotent and preserves the original Session and diagnostics.
	if out := chatRequest(server, http.MethodDelete, path, ""); out.Code != http.StatusOK {
		t.Fatal(out.Body)
	}
	for _, view := range []string{"", "&archived=true", "&deleted=true"} {
		out := chatRequest(server, http.MethodGet, "/api/v1/chats?tenant=t&namespace=n"+view, "")
		var list struct {
			Items []controlmodel.Chat `json:"items"`
		}
		if json.Unmarshal(out.Body.Bytes(), &list) != nil {
			t.Fatal(out.Body)
		}
		want := 0
		if view == "&deleted=true" {
			want = 1
		}
		if len(list.Items) != want {
			t.Fatalf("view %q: %s", view, out.Body)
		}
	}
	if _, err := st.Sessions().GetByID(ctx, session.ID); err != nil {
		t.Fatal(err)
	}
	after, _ := st.Events().List(ctx, session.ID)
	if len(before) != len(after) {
		t.Fatal("deletion removed execution events")
	}
	if out := chatRequest(server, http.MethodPost, turnPath, `{"message":"again"}`); out.Code != http.StatusConflict {
		t.Fatal("deleted Chat accepted a turn")
	}
	if err := server.sendAgentConversationTurn(ctx, session, "bypass", "chat", chat.ID.String()); !errors.Is(err, store.ErrConflict) {
		t.Fatalf("Session endpoint bypass: %v", err)
	}
	deleted, _ := st.Chats().Get(ctx, chat.ID)
	if out := chatRequest(server, http.MethodPatch, path, fmt.Sprintf(`{"version":%d,"status":"active"}`, deleted.Version)); out.Code != http.StatusOK {
		t.Fatal(out.Body)
	}
	if out := chatRequest(server, http.MethodPost, turnPath, `{"message":"retry"}`); out.Code != http.StatusAccepted {
		t.Fatalf("restored Chat: %d %s", out.Code, out.Body)
	}
	if out := chatRequest(server, http.MethodDelete, "/api/v1/chats/"+chat.ID.String()+"?tenant=t&namespace=elsewhere", ""); out.Code != http.StatusNotFound {
		t.Fatal("cross-scope deletion allowed")
	}
	other := *deleted
	other.ID = uuid.New()
	other.CreatorRef = "bob"
	otherSession, err := server.resolveAgentConversation(ctx, agent, "", "chat", other.ID.String())
	if err != nil {
		t.Fatal(err)
	}
	other.SessionID = otherSession.ID
	other.RuntimeSession = otherSession.SessionID
	other.Status = controlmodel.ChatActive
	if _, err := st.Chats().Create(ctx, &other); err != nil {
		t.Fatal(err)
	}
	if out := chatRequest(server, http.MethodDelete, "/api/v1/chats/"+other.ID.String()+"?tenant=t&namespace=n", ""); out.Code != http.StatusNotFound {
		t.Fatal("other owner's deletion allowed")
	}
}

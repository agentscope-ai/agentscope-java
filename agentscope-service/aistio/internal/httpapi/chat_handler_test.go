// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

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

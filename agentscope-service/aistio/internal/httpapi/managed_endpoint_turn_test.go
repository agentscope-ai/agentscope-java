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
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func TestManagedEndpointTurnsProjectResultAndFailureToTheirInvocation(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agentID, bindingID := uuid.New(), uuid.New()
	ep, err := st.Endpoints().Create(ctx, &controlmodel.Endpoint{Tenant: "t", Namespace: "n", Name: "chat", Slug: "chat", TargetRef: agentID, TargetType: controlmodel.EndpointTargetAgent, InvocationMode: controlmodel.EndpointConversationMode})
	if err != nil {
		t.Fatal(err)
	}
	session, err := st.Sessions().Upsert(ctx, &store.Session{Tenant: "t", Namespace: "n", SessionID: "managed-endpoint", AgentID: agentID, BindingID: bindingID, OriginType: "endpoint", OriginRef: ep.ID.String(), Phase: store.SessionPhaseIdle})
	if err != nil {
		t.Fatal(err)
	}
	conversation, err := st.Endpoints().CreateConversation(ctx, &controlmodel.EndpointConversation{EndpointID: ep.ID, AgentID: agentID, BindingID: bindingID, SessionID: session.SessionID, Status: controlmodel.EndpointConversationActive})
	if err != nil {
		t.Fatal(err)
	}
	srv := NewServer(ServerOptions{Store: st, InternalToken: "test-internal"})
	report := func(seq int64, typ string, payload map[string]any) {
		t.Helper()
		body, _ := json.Marshal(managedSessionEventReport{ID: fmt.Sprintf("evt-%d", seq), SessionID: session.SessionID, Seq: seq, Type: typ, Payload: payload, CreatedAt: time.Now().UnixMilli()})
		req := httptest.NewRequest(http.MethodPost, "/api/internal/runtime-sessions/"+session.SessionID+"/events", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Builder-Internal-Token", "test-internal")
		w := httptest.NewRecorder()
		srv.router.ServeHTTP(w, req)
		if w.Code != http.StatusNoContent {
			t.Fatalf("report %d: %d %s", seq, w.Code, w.Body)
		}
	}
	for i := int64(0); i < 3; i++ {
		turnID := uuid.New()
		inv, _, err := st.Endpoints().ReserveInvocation(ctx, &controlmodel.EndpointInvocation{EndpointID: ep.ID, ConversationID: &conversation.ID, SessionID: session.SessionID, TurnID: &turnID, Mode: controlmodel.EndpointConversationMode, IdempotencyKey: fmt.Sprint(i), Status: controlmodel.EndpointInvocationRunning})
		if err != nil {
			t.Fatal(err)
		}
		base := i * 4
		report(base+1, "user.message", map[string]any{"text": "question", "endpointInvocationId": inv.ID.String(), "endpointTurnId": turnID.String()})
		report(base+2, "session.status_running", nil)
		if i > 0 {
			report(4, "session.status_idle", nil) // replay of the first turn must not close this turn
			current, _ := st.Endpoints().GetInvocation(ctx, inv.ID)
			if current.Status != controlmodel.EndpointInvocationRunning {
				t.Fatalf("old event completed new invocation: %+v", current)
			}
		}
		if i == 2 {
			report(base+3, "session.error", map[string]any{"code": "provider_unavailable", "message": "provider failed"})
		} else {
			report(base+3, "agent.message", map[string]any{"text": fmt.Sprintf("answer %d", i)})
			report(base+4, "session.status_idle", nil)
		}
		current, _ := st.Endpoints().GetInvocation(ctx, inv.ID)
		if i == 2 {
			if current.Status != controlmodel.EndpointInvocationFailed || current.ErrorMessage == "" {
				t.Fatalf("failure not projected: %+v", current)
			}
		} else {
			var answer string
			_ = json.Unmarshal(current.Result, &answer)
			if current.Status != controlmodel.EndpointInvocationCompleted || answer != fmt.Sprintf("answer %d", i) || current.CompletedAt == nil {
				t.Fatalf("result not projected: %+v", current)
			}
		}
	}
	turns, err := st.Turns().List(ctx, session.ID, 10)
	if err != nil || len(turns) != 3 {
		t.Fatalf("turns=%+v err=%v", turns, err)
	}
}

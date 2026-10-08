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
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/asdp"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

type sessionMessageCommandCapture struct {
	instanceKey string
	turn        *asdp.ConversationTurnCommand
}

func (*sessionMessageCommandCapture) SendSessionCommand(_, _, _, _, _, _ string) error {
	return nil
}

func (c *sessionMessageCommandCapture) SendConversationTurn(
	_, _, instanceKey string, turn *asdp.ConversationTurnCommand) error {
	c.instanceKey, c.turn = instanceKey, turn
	return nil
}

func TestPostSessionUserMessageRoutesCatalogSessionWithoutLegacyInstanceRef(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{
		Tenant: "default", Namespace: "default", AgentKey: "catalog-chat",
		DisplayName: "Catalog chat", Status: controlmodel.AgentActive,
	})
	if err != nil {
		t.Fatal(err)
	}
	binding, err := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{
		AgentID: agent.ID, Tenant: agent.Tenant, Namespace: agent.Namespace,
		Kind: controlmodel.DataPlaneExternalApplication, Configuration: json.RawMessage(`{"instanceSelector":{}}`), Enabled: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	instance, err := st.RuntimeRegistry().UpsertAgentInstance(ctx, &controlmodel.AgentInstance{
		Tenant: agent.Tenant, Namespace: agent.Namespace, AgentID: agent.ID, BindingID: binding.ID,
		BackendKind: binding.Kind, InstanceKey: "catalog-instance", Health: controlmodel.RuntimeHealthHealthy,
		Capacity: 1, Capabilities: json.RawMessage(`["conversation-inbound"]`),
	})
	if err != nil {
		t.Fatal(err)
	}
	session, err := st.Sessions().Upsert(ctx, &store.Session{
		Tenant: agent.Tenant, Namespace: agent.Namespace, AgentID: agent.ID, AgentName: agent.AgentKey,
		BindingID: binding.ID, AgentInstanceID: instance.ID, InstanceGeneration: instance.Generation,
		SessionID: "catalog-session", Phase: store.SessionPhaseIdle,
		// Unified catalog sessions do not use the legacy InstanceRef field.
		InstanceRef: "",
	})
	if err != nil {
		t.Fatal(err)
	}
	capture := &sessionMessageCommandCapture{}
	server := NewServer(ServerOptions{Store: st, ASDPCommands: capture})
	req := httptest.NewRequest(http.MethodPost,
		"/api/v1/sessions/"+session.ID.String()+"/user-message",
		bytes.NewBufferString(`{"content":"  hello unified runtime  "}`))
	req.Header.Set("Content-Type", "application/json")
	response := httptest.NewRecorder()
	server.router.ServeHTTP(response, req)
	if response.Code != http.StatusAccepted {
		t.Fatalf("status=%d body=%s", response.Code, response.Body.String())
	}
	if capture.instanceKey != instance.InstanceKey || capture.turn == nil ||
		capture.turn.SessionId != session.SessionID {
		t.Fatalf("turn was not routed through the catalog binding: instance=%q turn=%+v",
			capture.instanceKey, capture.turn)
	}
	var input struct {
		Message string `json:"message"`
	}
	if err = json.Unmarshal(capture.turn.Input, &input); err != nil || input.Message != "hello unified runtime" {
		t.Fatalf("unexpected conversation input=%s err=%v", capture.turn.Input, err)
	}
}

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package product

import (
	"context"
	"encoding/json"
	"github.com/gin-gonic/gin"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestManagedTriggerValidatesOriginAndProviderIdentity(t *testing.T) {
	received := []ManagedTrigger{}
	s := &Server{}
	s.SetManagedTriggerHandler(func(_ context.Context, r ManagedTrigger) (json.RawMessage, error) {
		received = append(received, r)
		return json.RawMessage(`{"sessionId":"native","turn":{"id":"turn"}}`), nil
	})
	valid := ManagedTrigger{OwnerID: "owner", AgentID: "agent", RequestID: "message-123", ExternalKey: "conversation", Text: "hello"}
	invalid := []ManagedTrigger{{}, {OwnerID: "owner", AgentID: "agent", Text: "hello"}, {OwnerID: "owner", AgentID: "agent", RequestID: strings.Repeat("x", 257), Text: "hello"}, {OwnerID: "owner", AgentID: "agent", RequestID: "message", Text: "hello", AgentVersion: -1}, {OwnerID: "owner", AgentID: "agent", Operation: "events", SessionID: "session"}, {OwnerID: "owner", AgentID: "agent", Operation: "legacy"}}
	for _, request := range invalid {
		if _, err := s.SubmitManagedTrigger(t.Context(), request); err == nil {
			t.Fatalf("accepted invalid trigger %+v", request)
		}
	}
	if len(received) != 0 {
		t.Fatal("invalid request reached runtime")
	}
	for range 2 {
		if _, err := s.SubmitManagedTrigger(t.Context(), valid); err != nil {
			t.Fatal(err)
		}
	}
	if len(received) != 2 || received[0] != received[1] || received[0].RequestID != valid.RequestID || received[0].Operation != "submit" {
		t.Fatalf("retry changed identity: %+v", received)
	}
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.POST("/trigger", s.internalManagedTrigger)
	req := httptest.NewRequest("POST", "/trigger", strings.NewReader(`{"ownerId":"victim","agentId":"agent","requestId":"message","text":"hello"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Builder-Internal-User", "other")
	out := httptest.NewRecorder()
	router.ServeHTTP(out, req)
	if out.Code != 403 || len(received) != 2 {
		t.Fatalf("foreign user reached trigger: %d", out.Code)
	}
	if _, err := (&Server{}).SubmitManagedTrigger(t.Context(), valid); err == nil {
		t.Fatal("missing runtime fell back to legacy")
	}
}

func TestManagedProvisionRegistersResourceWithoutRequiringUserText(t *testing.T) {
	s := &Server{}
	called := 0
	s.SetManagedTriggerHandler(func(_ context.Context, request ManagedTrigger) (json.RawMessage, error) {
		called++
		if request.Operation != "provision" || request.Text != "" || request.SessionID != "session" || request.BindingID != "binding" {
			t.Fatal(request)
		}
		return json.RawMessage(`{"sessionId":"session"}`), nil
	})
	request := ManagedTrigger{OwnerID: "owner", ActorID: "human", AgentID: "agent", BindingID: "binding", SessionID: "session", Operation: "provision", RequestID: "create-resource"}
	if _, err := s.SubmitManagedTrigger(t.Context(), request); err != nil {
		t.Fatal(err)
	}
	request.RequestID = ""
	if _, err := s.SubmitManagedTrigger(t.Context(), request); err == nil {
		t.Fatal("missing provision identity accepted")
	}
	if called != 1 {
		t.Fatal(called)
	}
}

func TestNativeManagedConfigurationUsesFrozenDefinitionWithoutLegacySession(t *testing.T) {
	s := resourceTestServer(t)
	owner, environment, session := shortID("native-owner-"), shortID("native-env-"), shortID("native-session-")
	resourceSQL(t, s, `INSERT INTO environments(environment_id,owner_id,name,type,created_at,updated_at) VALUES($1,$2,'native','cloud',1,1)`, environment, owner)
	input := ManagedResourceConfiguration{SessionID: session, OwnerID: owner, AgentID: "frozen-agent", AgentVersion: 7, EnvironmentID: environment, Definition: json.RawMessage(`{"name":"frozen","version":7,"definitionFiles":{}}`), Options: json.RawMessage(`{}`)}
	resolved, err := s.ResolveManagedResourceSession(t.Context(), input)
	if err != nil {
		t.Fatal(err)
	}
	runtime := resolved["session"].(gin.H)
	if runtime["id"] != session || runtime["agentOverridesJson"] != nil || runtime["agentVersion"] != 7 {
		t.Fatalf("native configuration changed: %+v", runtime)
	}
	if resolved["agentSnapshot"].(map[string]any)["name"] != "frozen" {
		t.Fatal("frozen definition changed")
	}
	var count int
	if err = s.db.Pool.QueryRow(t.Context(), `SELECT count(*) FROM sessions WHERE session_id=$1`, session).Scan(&count); err != nil || count != 0 {
		t.Fatalf("legacy Session was created: %d %v", count, err)
	}
	input.Definition = json.RawMessage(`{"name":"frozen","version":7,"definitionFiles":{},"defaultVaultIds":["missing-frozen-vault"]}`)
	if _, err = s.ResolveManagedResourceSession(t.Context(), input); err == nil {
		t.Fatal("frozen default vault was silently omitted")
	}
	input.Options = json.RawMessage(`{"vaultIds":[]}`)
	if _, err = s.ResolveManagedResourceSession(t.Context(), input); err != nil {
		t.Fatalf("explicit empty mounts did not override defaults: %v", err)
	}
	input.Definition = json.RawMessage(`{`)
	if _, err = s.ResolveManagedResourceSession(t.Context(), input); err == nil {
		t.Fatal("invalid frozen definition accepted")
	}
}

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
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func channelSessionRequest(s *Server, body, token string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, "/api/internal/managed-sessions/find-or-create", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Builder-Internal-Token", token)
	out := httptest.NewRecorder()
	s.router.ServeHTTP(out, req)
	return out
}

func TestChannelSessionRequiresInternalAuthAndManagedBinding(t *testing.T) {
	st, agent, _, _ := setupConversationAgent(t)
	agent.OwnerRef = "owner"
	if _, err := st.AgentCatalog().UpdateAgent(t.Context(), agent, agent.Version); err != nil {
		t.Fatal(err)
	}
	s := NewServer(ServerOptions{Store: st, InternalToken: "internal-secret"})
	body := `{"ownerId":"owner","agentId":"` + agent.ID.String() + `","externalKey":"verified-channel:demo:owner:peer"}`
	if out := channelSessionRequest(s, body, ""); out.Code != http.StatusUnauthorized {
		t.Fatalf("missing token: %d", out.Code)
	}
	if out := channelSessionRequest(s, body, "internal-secret"); out.Code != http.StatusServiceUnavailable {
		t.Fatalf("external runtime accepted by managed bridge: %d %s", out.Code, out.Body)
	}
	if out := channelSessionRequest(s, strings.Replace(body, `"ownerId":"owner"`, `"ownerId":"other"`, 1), "internal-secret"); out.Code != http.StatusNotFound {
		t.Fatalf("foreign owner accepted: %d %s", out.Code, out.Body)
	}
}

func TestChannelSessionRequiresAnExternalKey(t *testing.T) {
	st, agent, _, _ := setupConversationAgent(t)
	agent.OwnerRef = "owner"
	if _, err := st.AgentCatalog().UpdateAgent(t.Context(), agent, agent.Version); err != nil {
		t.Fatal(err)
	}
	s := NewServer(ServerOptions{Store: st, InternalToken: "internal-secret"})

	// A missing key used to mint a fresh session per call: unbounded growth behind the internal
	// token, and the find-or-create lock could never deduplicate. Reject it instead.
	body := `{"ownerId":"owner","agentId":"` + agent.ID.String() + `"}`
	out := channelSessionRequest(s, body, "internal-secret")
	if out.Code != http.StatusBadRequest || !strings.Contains(out.Body.String(), "externalKey is required") {
		t.Fatalf("missing externalKey accepted: %d %s", out.Code, out.Body)
	}
}

func TestChannelSessionManagedRegistrationPostgres(t *testing.T) {
	if os.Getenv("AISTIO_TEST_POSTGRES_DSN") == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set")
	}
	ctx := t.Context()
	cfg := product.DefaultConfig()
	cfg.DSN, cfg.SeedUsers, cfg.WorkspaceRoot = os.Getenv("AISTIO_TEST_POSTGRES_DSN"), false, t.TempDir()
	p, err := product.Open(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(p.Close)
	st, err := store.Open(ctx, acceptancePostgresConfig(t))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	owner := "channel-test-" + uuid.NewString()
	agent, err := st.AgentCatalog().CreateAgent(ctx, &model.Agent{Tenant: "test", Namespace: "personal", AgentKey: owner, DisplayName: "Channel test", OwnerRef: owner, OwnerType: "user", Status: model.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = p.EnsureManagedDefinition(ctx, owner, agent.ID.String(), product.ManagedDefinitionInput{Name: "Channel test", ProvisionDefaultEnvironment: true}); err != nil {
		t.Fatal(err)
	}
	configuration, _ := json.Marshal(model.ManagedBindingConfiguration{OwnerRef: owner, ManagedDefinitionRef: agent.ID.String()})
	binding, err := st.AgentCatalog().CreateBinding(ctx, &model.AgentBinding{AgentID: agent.ID, Tenant: agent.Tenant, Namespace: agent.Namespace, Kind: model.DataPlaneManaged, Configuration: configuration, Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	runtimeBinding, err := binding.RuntimeBinding()
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.Orchestration().PutRuntimePolicy(ctx, &model.AgentRuntimePolicy{Tenant: agent.Tenant, Namespace: agent.Namespace, AgentRef: agent.ID.String(), SelectionMode: "ordered", Candidates: []model.RuntimeBindingCandidate{{Binding: runtimeBinding}}}); err != nil {
		t.Fatal(err)
	}
	s := NewServer(ServerOptions{Store: st, Product: p, InternalToken: "internal-secret", AuthToken: "console"})
	for _, existing := range []bool{false, true} {
		t.Run(map[bool]string{false: "new", true: "existing_cp_session"}[existing], func(t *testing.T) {
			key := "verified-channel:demo:" + owner + ":" + uuid.NewString()
			oldID := ""
			if existing {
				oldID, err = p.FindOrCreateSessionID(ctx, owner, agent.ID.String(), "", key)
				if err != nil {
					t.Fatal(err)
				}
			}
			body, _ := json.Marshal(map[string]string{"ownerId": owner, "agentId": agent.ID.String(), "externalKey": key})
			out := channelSessionRequest(s, string(body), "internal-secret")
			var response struct {
				ID string `json:"id"`
			}
			if out.Code != 200 || json.Unmarshal(out.Body.Bytes(), &response) != nil || response.ID == "" {
				t.Fatalf("register: %d %s", out.Code, out.Body)
			}
			if existing && oldID != response.ID {
				t.Fatalf("lost original session: %s != %s", oldID, response.ID)
			}
			sessions, err := st.Sessions().List(ctx, store.SessionFilter{SessionID: response.ID, Limit: 10})
			if err != nil || len(sessions) != 1 {
				t.Fatalf("runtime session missing: %+v %v", sessions, err)
			}
			session := sessions[0]
			if session.BindingID != binding.ID || session.Tenant != agent.Tenant || session.Namespace != agent.Namespace || session.OriginType != "channel" || store.ChannelSessionOwnerRef(session) != owner {
				t.Fatalf("wrong identity: %+v", session)
			}
			for i, typ := range []string{"user.message", "agent.message", "session.status_idle"} {
				event, _ := json.Marshal(managedSessionEventReport{ID: response.ID + typ, SessionID: response.ID, Seq: int64(i + 1), Type: typ, Payload: map[string]any{"text": "AS-WX-channel-history"}, CreatedAt: time.Now().UnixMilli()})
				for replay := 0; replay < 2; replay++ {
					req := httptest.NewRequest(http.MethodPost, "/api/internal/runtime-sessions/"+response.ID+"/events", bytes.NewReader(event))
					req.Header.Set("Content-Type", "application/json")
					req.Header.Set("X-Builder-Internal-Token", "internal-secret")
					result := httptest.NewRecorder()
					s.router.ServeHTTP(result, req)
					if result.Code != 204 {
						t.Fatalf("projection: %d %s", result.Code, result.Body)
					}
				}
			}
			out = channelSessionRequest(s, string(body), "internal-secret")
			if out.Code != 200 {
				t.Fatalf("reuse: %d %s", out.Code, out.Body)
			}
			current, err := st.Sessions().GetByID(ctx, session.ID)
			if err != nil || current.Phase != store.SessionPhaseIdle {
				t.Fatalf("reuse reset phase: %+v %v", current, err)
			}
			events, err := st.Events().List(ctx, session.ID)
			if err != nil || len(events) != 3 {
				t.Fatalf("duplicate/missing events: %d %v", len(events), err)
			}
			page := chatRequest(s, http.MethodGet, "/api/v1/sessions/"+session.ID.String()+"/messages?tenant=test&namespace=personal", "")
			if page.Code != 200 || !bytes.Contains(page.Body.Bytes(), []byte("AS-WX-channel-history")) {
				t.Fatalf("console messages: %d %s", page.Code, page.Body)
			}
			listed := chatRequest(s, http.MethodGet, "/api/v1/sessions?tenant=test&namespace=personal&agentId="+agent.ID.String(), "")
			if listed.Code != 200 || !bytes.Contains(listed.Body.Bytes(), []byte(response.ID)) {
				t.Fatalf("console session list: %d %s", listed.Code, listed.Body)
			}
		})
	}
}

func TestChannelSessionOwnerVisibility(t *testing.T) {
	for _, backend := range []string{"memory", "postgres"} {
		t.Run(backend, func(t *testing.T) {
			cfg := store.Config{Driver: store.DriverMemory}
			if backend == "postgres" {
				if os.Getenv("AISTIO_TEST_POSTGRES_DSN") == "" {
					t.Skip("AISTIO_TEST_POSTGRES_DSN not set")
				}
				cfg = acceptancePostgresConfig(t)
			}
			s, st := accessTestServer(t, cfg)
			for _, origin := range []string{"channel", "runtime"} {
				session, err := st.Sessions().Upsert(t.Context(), &store.Session{
					Tenant: "default", Namespace: "engineering", AgentName: "channel-agent", SessionID: "channel-" + origin,
					OriginType: origin, OriginRef: "verified-channel:demo:bob:peer",
					TaskContext: json.RawMessage(`{"channelOwnerRef":"bob"}`), Phase: store.SessionPhaseIdle,
				})
				if err != nil {
					t.Fatal(err)
				}
				if err = st.Events().Append(t.Context(), &store.SessionEvent{SessionFK: session.ID, Seq: 1, EventType: "user.message", Role: "user", Content: "private-channel-history"}); err != nil {
					t.Fatal(err)
				}
				for _, user := range []string{"bob", "alice", "carol", "outsider", "auditor"} {
					allowed := user == "auditor" || user == "bob" && origin == "channel"
					want := http.StatusNotFound
					if allowed {
						want = http.StatusOK
					}
					page := accessRequest(s, user, http.MethodGet, "/api/v1/sessions/"+session.ID.String()+"/messages", "")
					if page.Code != want {
						t.Errorf("%s %s messages: %d want %d: %s", origin, user, page.Code, want, page.Body)
					}
					listed := accessRequest(s, user, http.MethodGet, "/api/v1/sessions?tenant=default&namespace=engineering", "")
					visible := listed.Code == http.StatusOK && bytes.Contains(listed.Body.Bytes(), []byte(session.SessionID))
					if visible != allowed {
						t.Errorf("%s %s session visibility: %v want %v", origin, user, visible, allowed)
					}
				}
			}
		})
	}
}

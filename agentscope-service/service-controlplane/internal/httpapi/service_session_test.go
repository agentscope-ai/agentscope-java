/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package httpapi

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/artifact"
	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

func sessionTestRequest(s *Server, method, path, body, key, credential string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, bytes.NewBufferString(body))
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("Authorization", "Bearer console")
	if key != "" {
		r.Header.Set("Idempotency-Key", key)
	}
	if credential != "" {
		r.Header.Set("X-API-Key", credential)
	}
	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, r)
	return w
}
func sessionTestFixture(t *testing.T) (*Server, store.Store, *model.Agent) {
	t.Helper()
	st, err := store.Open(t.Context(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	a, err := st.AgentCatalog().CreateAgent(t.Context(), &model.Agent{Tenant: "t", Namespace: "n", AgentKey: uuid.NewString(), DisplayName: "Worker", Status: model.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	s := NewServer(ServerOptions{Store: st, AuthToken: "console", DefaultTenant: "t", DefaultNamespace: "n", ArtifactProvider: &artifact.LocalProvider{Root: t.TempDir()}})
	return s, st, a
}
func decodeSessionTest(t *testing.T, w *httptest.ResponseRecorder, status int) map[string]any {
	t.Helper()
	if w.Code != status {
		t.Fatalf("HTTP %d want %d: %s", w.Code, status, w.Body)
	}
	var v map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &v); err != nil {
		t.Fatal(err)
	}
	return v
}
func TestPublicSessionDirectTargetIdempotencyAndRestart(t *testing.T) {
	s, st, a := sessionTestFixture(t)
	body := `{"target":{"type":"agent","id":"` + a.ID.String() + `"}}`
	v := decodeSessionTest(t, sessionTestRequest(s, "POST", "/api/v1/agent-sessions", body, "create", ""), 201)
	id := v["id"].(string)
	base := "/api/v1/agent-sessions/" + id
	if _, err := st.Endpoints().Get(t.Context(), uuid.MustParse(id)); err != store.ErrNotFound {
		t.Fatalf("Session created hidden Endpoint: %v", err)
	}
	again := decodeSessionTest(t, sessionTestRequest(s, "POST", "/api/v1/agent-sessions", body, "create", ""), 200)
	if again["id"] != id {
		t.Fatal("Session replay changed ID")
	}
	if w := sessionTestRequest(s, "POST", "/api/v1/agent-sessions", `{"target":{"type":"agent","id":"`+a.ID.String()+`"},"timeoutSeconds":3}`, "create", ""); w.Code != 409 {
		t.Fatalf("changed Session replay: %d %s", w.Code, w.Body)
	}
	turn := decodeSessionTest(t, sessionTestRequest(s, "POST", base+"/turns", `{"message":"hello"}`, "turn", ""), 202)
	if w := sessionTestRequest(s, "POST", base+"/turns", `{"message":"changed"}`, "turn", ""); w.Code != 409 {
		t.Fatalf("changed Turn replay: %d %s", w.Code, w.Body)
	}
	restarted := NewServer(ServerOptions{Store: st, AuthToken: "console", DefaultTenant: "t", DefaultNamespace: "n"})
	replay := decodeSessionTest(t, sessionTestRequest(restarted, "POST", base+"/turns", `{"message":"hello"}`, "turn", ""), 202)
	if turn["id"] != replay["id"] {
		t.Fatal("Turn replay changed ID")
	}
	page := decodeSessionTest(t, sessionTestRequest(restarted, "GET", base+"/events", "", "", ""), 200)
	cursor := page["next_cursor"].(string)
	empty := decodeSessionTest(t, sessionTestRequest(restarted, "GET", base+"/events?after="+cursor, "", "", ""), 200)
	if len(empty["data"].([]any)) != 0 {
		t.Fatal("replayed committed events twice")
	}
	for _, path := range []string{"/api/v1/endpoints", "/invoke/v1/endpoints/test/jobs"} {
		if w := sessionTestRequest(s, "GET", path, "", "", ""); w.Code != 404 {
			t.Fatalf("legacy route exposed: %s %d", path, w.Code)
		}
	}
}
func TestPublicSessionApplicationGrantsAndRevocation(t *testing.T) {
	s, _, a := sessionTestFixture(t)
	app := decodeSessionTest(t, sessionTestRequest(s, "POST", "/api/v1/applications", `{"name":"app","tenant":"t","namespace":"n"}`, "", ""), 201)["application"].(map[string]any)
	path := "/api/v1/applications/" + app["id"].(string) + "/credentials"
	grant := `{"name":"key","scopes":["invoke","read","interact","cancel"],"targets":[{"type":"agent","id":"` + a.ID.String() + `"}]}`
	key := decodeSessionTest(t, sessionTestRequest(s, "POST", path, grant, "", ""), 201)
	secret := key["apiKey"].(string)
	v := decodeSessionTest(t, sessionTestRequest(s, "POST", "/api/v1/agent-sessions", `{"target":{"type":"agent","id":"`+a.ID.String()+`"}}`, "", secret), 201)
	base := "/api/v1/agent-sessions/" + v["id"].(string)
	decodeSessionTest(t, sessionTestRequest(s, "GET", base, "", "", secret), 200)
	if w := sessionTestRequest(s, "GET", base, "", "", ""); w.Code != 404 {
		t.Fatalf("different principal accessed application Session: %d", w.Code)
	}
	id := key["credential"].(map[string]any)["id"].(string)
	if w := sessionTestRequest(s, "DELETE", path+"/"+id, "", "", ""); w.Code != 204 {
		t.Fatal(w.Body.String())
	}
	if w := sessionTestRequest(s, "GET", base, "", "", secret); w.Code != 401 {
		t.Fatalf("revoked credential accepted: %d", w.Code)
	}
}

func TestPublicSessionTeamAndWorkflowTargets(t *testing.T) {
	s, st, a := sessionTestFixture(t)
	team := decodeSessionTest(t, sessionTestRequest(s, "POST", "/api/v1/teams", `{"tenant":"t","namespace":"n","name":"Review","leaderAgentId":"`+a.ID.String()+`","members":[]}`, "", ""), 201)["team"].(map[string]any)
	definition := decodeSessionTest(t, sessionTestRequest(s, "POST", "/api/v1/orchestration-definitions", `{"tenant":"t","namespace":"n","name":"Review flow","draftSpec":{"nodes":[{"key":"draft","type":"agent","agentId":"`+a.ID.String()+`"}],"edges":[]}}`, "", ""), 201)["definition"].(map[string]any)
	publish, _ := json.Marshal(map[string]any{"expectedVersion": definition["version"]})
	revision := decodeSessionTest(t, sessionTestRequest(s, "POST", "/api/v1/orchestration-definitions/"+definition["id"].(string)+"/publish", string(publish), "", ""), 201)["revision"].(map[string]any)
	for _, target := range []map[string]any{{"type": "team", "id": team["id"]}, {"type": "workflow", "id": definition["id"], "revisionId": revision["id"]}} {
		t.Run(target["type"].(string), func(t *testing.T) {
			body, _ := json.Marshal(map[string]any{"target": target})
			v := decodeSessionTest(t, sessionTestRequest(s, "POST", "/api/v1/agent-sessions", string(body), "", ""), 201)
			base := "/api/v1/agent-sessions/" + v["id"].(string)
			caps := decodeSessionTest(t, sessionTestRequest(s, "GET", base+"/capabilities", "", "", ""), 200)
			_ = caps
			turn := decodeSessionTest(t, sessionTestRequest(s, "POST", base+"/turns", `{"input":{"request":"Check sources"}}`, "first", ""), 202)
			inv, err := st.Endpoints().GetInvocation(t.Context(), uuid.MustParse(turn["id"].(string)))
			if err != nil || inv.RunID == nil || inv.IssueID == nil {
				t.Fatalf("target not dispatched: %+v %v", inv, err)
			}
			issue, err := st.Collaboration().GetIssue(t.Context(), *inv.IssueID)
			if err != nil || issue.SourceType != "session_turn" {
				t.Fatalf("invalid task lineage: %+v %v", issue, err)
			}
			if _, err = st.Endpoints().Get(t.Context(), uuid.MustParse(v["id"].(string))); err != store.ErrNotFound {
				t.Fatal("hidden Endpoint created", err)
			}
			queued := decodeSessionTest(t, sessionTestRequest(s, "POST", base+"/turns", `{"message":"Second independent task"}`, "second", ""), 202)
			next, err := st.Endpoints().GetInvocation(t.Context(), uuid.MustParse(queued["id"].(string)))
			if err != nil || next.RunID != nil || queued["status"] != "queued" {
				t.Fatalf("later Turn bypassed queue: %+v %v", next, err)
			}
			decodeSessionTest(t, sessionTestRequest(s, "POST", base+"/turns/"+queued["id"].(string)+"/cancel", `{}`, "cancel", ""), 202)
			if err = s.sweepServiceInvocation(t.Context(), next); err != nil {
				t.Fatal(err)
			}
			state := decodeSessionTest(t, sessionTestRequest(s, "GET", base+"/turns/"+queued["id"].(string), "", "", ""), 200)
			if state["status"] != "cancelled" {
				t.Fatal("queued cancellation failed", state)
			}
			snap := decodeSessionTest(t, sessionTestRequest(s, "GET", base+"/snapshot", "", "", ""), 200)
			if w := sessionTestRequest(s, "GET", base+"/turns/"+turn["id"].(string)+"/events?after="+snap["as_of"].(string), "", "", ""); w.Code != 400 {
				t.Fatal("Session cursor accepted by Turn", w.Code, w.Body.String())
			}
		})
	}
}

func TestPublicSessionKeysCannotListAnotherApplicationOrReferenceForeignFiles(t *testing.T) {
	s, _, a := sessionTestFixture(t)
	keys := []string{}
	for _, name := range []string{"one", "two"} {
		app := decodeSessionTest(t, sessionTestRequest(s, "POST", "/api/v1/applications", `{"name":"`+name+`"}`, "", ""), 201)["application"].(map[string]any)
		key := decodeSessionTest(t, sessionTestRequest(s, "POST", "/api/v1/applications/"+app["id"].(string)+"/credentials", `{"name":"backend","scopes":["invoke","read","interact"],"targets":[{"type":"agent","id":"`+a.ID.String()+`"}]}`, "", ""), 201)
		keys = append(keys, key["apiKey"].(string))
	}
	create := func(key string) string {
		v := decodeSessionTest(t, sessionTestRequest(s, "POST", "/api/v1/agent-sessions", `{"target":{"type":"agent","id":"`+a.ID.String()+`"}}`, "", key), 201)
		return "/api/v1/agent-sessions/" + v["id"].(string)
	}
	base := create(keys[0])
	other := create(keys[1])
	page := decodeSessionTest(t, sessionTestRequest(s, "GET", "/api/v1/agent-sessions", "", "", keys[1]), 200)
	if len(page["items"].([]any)) != 1 {
		t.Fatal("cross-application listing", page)
	}
	req := httptest.NewRequest("POST", base+"/files", bytes.NewBufferString(`{"unchanged":true}`))
	req.Header.Set("X-API-Key", keys[0])
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-File-Name", "data.json")
	req.Header.Set("Idempotency-Key", "file")
	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, req)
	file := decodeSessionTest(t, w, 201)
	download := sessionTestRequest(s, "GET", base+"/files/"+file["id"].(string)+"/content", "", "", keys[0])
	if download.Code != 200 || download.Body.String() != `{"unchanged":true}` {
		t.Fatal("file bytes changed", download.Body)
	}
	if w = sessionTestRequest(s, "POST", other+"/turns", `{"input":{"file_id":"`+file["id"].(string)+`"}}`, "foreign", keys[1]); w.Code != 400 {
		t.Fatal("foreign file accepted", w.Code, w.Body)
	}
	if w = sessionTestRequest(s, "GET", base+"/files/"+file["id"].(string)+"/content", "", "", keys[1]); w.Code != 404 {
		t.Fatal("foreign download accepted", w.Code)
	}
}

func TestPublicSessionSchemaAndArchivedLifecycle(t *testing.T) {
	s, _, a := sessionTestFixture(t)
	body := `{"target":{"type":"agent","id":"` + a.ID.String() + `"},"inputSchema":{"type":"string"}}`
	session := decodeSessionTest(t, sessionTestRequest(s, "POST", "/api/v1/agent-sessions", body, "schema", ""), 201)
	base := "/api/v1/agent-sessions/" + session["id"].(string)
	if w := sessionTestRequest(s, "POST", base+"/turns", `{"input":42}`, "bad", ""); w.Code != 400 {
		t.Fatal("schema ignored", w.Code, w.Body)
	}
	decodeSessionTest(t, sessionTestRequest(s, "POST", base+"/archive", `{}`, "", ""), 200)
	if w := sessionTestRequest(s, "POST", base+"/turns", `{"message":"Hello"}`, "task", ""); w.Code != 409 {
		t.Fatal("archived Session accepted Turn", w.Code, w.Body)
	}
	decodeSessionTest(t, sessionTestRequest(s, "POST", base+"/restore", `{}`, "", ""), 200)
	decodeSessionTest(t, sessionTestRequest(s, "POST", base+"/turns", `{"message":"Hello"}`, "task", ""), 202)
}

func TestPublicSessionWebhookAndRetentionSurviveRestart(t *testing.T) {
	s, st, a := sessionTestFixture(t)
	deliveries := make(chan webhookDelivery, 8)
	receiver := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		deliveries <- webhookDelivery{string(raw), r.Header.Get("X-AgentScope-Signature"), r.Header.Get("X-AgentScope-Event-ID")}
		w.WriteHeader(204)
	}))
	defer receiver.Close()
	created := decodeSessionTest(t, sessionTestRequest(s, "POST", "/api/v1/agent-sessions", `{"target":{"type":"agent","id":"`+a.ID.String()+`"}}`, "", ""), 201)
	sessionID := uuid.MustParse(created["id"].(string))
	base := "/api/v1/agent-sessions/" + sessionID.String()
	body, _ := json.Marshal(map[string]any{"url": receiver.URL, "event_types": []string{"turn.completed"}})
	hook := decodeSessionTest(t, sessionTestRequest(s, "POST", base+"/webhooks", string(body), "hook", ""), 201)
	turn := decodeSessionTest(t, sessionTestRequest(s, "POST", base+"/turns", `{"message":"Report"}`, "turn", ""), 202)
	inv, err := st.Endpoints().GetInvocation(t.Context(), uuid.MustParse(turn["id"].(string)))
	if err != nil {
		t.Fatal(err)
	}
	completed := time.Now().Add(-2 * time.Hour)
	inv.Status = model.EndpointInvocationCompleted
	inv.CompletedAt = &completed
	inv.Result = json.RawMessage(`"done"`)
	if _, err = st.Endpoints().UpdateInvocation(t.Context(), inv); err != nil {
		t.Fatal(err)
	}
	restarted := NewServer(ServerOptions{Store: st, AuthToken: "console", DefaultTenant: "t", DefaultNamespace: "n", ServiceEventRetention: time.Hour})
	if err = restarted.projectServiceInvocation(t.Context(), inv.ID); err != nil {
		t.Fatal(err)
	}
	// Retention must commit the Session aggregate even if no client ever reads it.
	if _, err = restarted.nextServicePoll(t.Context(), inv.ID); err != nil {
		t.Fatal(err)
	}
	session, err := sessionapi.Get(t.Context(), st, "t", sessionID)
	if err != nil {
		t.Fatal(err)
	}
	contract, err := serviceapi.ReadContract(session.Contract)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC().Truncate(time.Second)
	if err = restarted.deliverServiceWebhooksWith(t.Context(), sessionWebhookExecution(session), &contract.Endpoint, receiver.Client(), func() time.Time { return now }); err != nil {
		t.Fatal(err)
	}
	delivery := receivedWebhook(t, deliveries)
	verifyWebhookSignature(t, delivery, hook["signing_secret"].(string), now)
	var event map[string]any
	_ = json.Unmarshal([]byte(delivery.body), &event)
	if event["type"] != "turn.completed" || event["session_id"] != sessionID.String() || event["turn_id"] != turn["id"] {
		t.Fatal("wrong callback identity", event)
	}
	page := decodeSessionTest(t, sessionTestRequest(restarted, "GET", base+"/events", "", "", ""), 200)
	if len(page["data"].([]any)) == 0 {
		t.Fatal("Session history lost during Turn retention")
	}
}

func TestPublicSessionExternalConversationDispatchAndReplay(t *testing.T) {
	_, st, a := sessionTestFixture(t)
	binding, err := st.AgentCatalog().CreateBinding(t.Context(), &model.AgentBinding{AgentID: a.ID, Tenant: "t", Namespace: "n", Kind: model.DataPlaneExternalApplication, Configuration: json.RawMessage(`{"instanceSelector":{}}`), Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.RuntimeRegistry().UpsertAgentInstance(t.Context(), &model.AgentInstance{Tenant: "t", Namespace: "n", AgentID: a.ID, BindingID: binding.ID, BackendKind: binding.Kind, InstanceKey: "external-session", Health: model.RuntimeHealthHealthy, Capacity: 4, Capabilities: json.RawMessage(`["conversation-inbound"]`)}); err != nil {
		t.Fatal(err)
	}
	if _, err = st.Orchestration().PutRuntimePolicy(t.Context(), &model.AgentRuntimePolicy{Tenant: "t", Namespace: "n", AgentRef: a.ID.String(), SelectionMode: "ordered", FallbackMode: "disabled", Candidates: []model.RuntimeBindingCandidate{{Binding: model.RuntimeBinding{BindingID: binding.ID, Kind: binding.Kind, InstanceSelector: map[string]string{}}}}}); err != nil {
		t.Fatal(err)
	}
	commands := &endpointCommandCapture{}
	s := NewServer(ServerOptions{Store: st, AuthToken: "console", DefaultTenant: "t", DefaultNamespace: "n", ASDPCommands: commands})
	session := decodeSessionTest(t, sessionTestRequest(s, "POST", "/api/v1/agent-sessions", `{"target":{"type":"agent","id":"`+a.ID.String()+`"}}`, "", ""), 201)
	base := "/api/v1/agent-sessions/" + session["id"].(string)
	turn := decodeSessionTest(t, sessionTestRequest(s, "POST", base+"/turns", `{"message":"Hello external"}`, "task", ""), 202)
	replay := decodeSessionTest(t, sessionTestRequest(s, "POST", base+"/turns", `{"message":"Hello external"}`, "task", ""), 202)
	if turn["id"] != replay["id"] || len(commands.turns) != 1 {
		t.Fatal("external dispatch duplicated", turn, replay, len(commands.turns))
	}
	inv, err := st.Endpoints().GetInvocation(t.Context(), uuid.MustParse(turn["id"].(string)))
	if err != nil || inv.Mode != model.EndpointConversationMode || inv.SessionID == "" {
		t.Fatal("external Session binding invalid", inv, err)
	}
}

func TestPublicSessionHostedConversationCompletion(t *testing.T) {
	st, a, _, host := setupHostedConversationAgent(t)
	s := NewServer(ServerOptions{Store: st, AuthToken: "console", DefaultTenant: "t", DefaultNamespace: "n"})
	session := decodeSessionTest(t, sessionTestRequest(s, "POST", "/api/v1/agent-sessions", `{"target":{"type":"agent","id":"`+a.ID.String()+`"}}`, "", ""), 201)
	base := "/api/v1/agent-sessions/" + session["id"].(string)
	turn := decodeSessionTest(t, sessionTestRequest(s, "POST", base+"/turns", `{"message":"Hello hosted"}`, "task", ""), 202)
	inv, err := st.Endpoints().GetInvocation(t.Context(), uuid.MustParse(turn["id"].(string)))
	if err != nil {
		t.Fatal(err)
	}
	attempts, err := st.ExecutionAttempts().List(t.Context(), store.ExecutionAttemptFilter{Tenant: "t", Namespace: "n", AgentID: a.ID, SessionID: inv.SessionID})
	if err != nil || len(attempts) != 1 {
		t.Fatal("hosted dispatch failed", attempts, err)
	}
	claimed, err := s.taskPlane.Claim(t.Context(), store.ExecutionClaim{Tenant: "t", Namespace: "n", RuntimePoolName: attempts[0].RuntimePoolName, HostID: host.ID, HostGeneration: host.LeaseGeneration, LeaseOwner: "host/session", LeaseToken: "session-test-lease", LeaseTTL: time.Minute})
	if err != nil {
		t.Fatal(err)
	}
	preparing, err := s.taskPlane.MarkPreparing(t.Context(), claimed.ID, claimed.LeaseToken, claimed.FencingToken)
	if err != nil {
		t.Fatal(err)
	}
	running, err := s.taskPlane.MarkRunning(t.Context(), preparing.ID, preparing.LeaseToken, preparing.FencingToken, "provider-session", "workspace")
	if err != nil {
		t.Fatal(err)
	}
	done, err := s.taskPlane.Complete(t.Context(), running.ID, running.LeaseToken, running.FencingToken, json.RawMessage(`{"output":"hosted answer"}`), nil)
	if err != nil {
		t.Fatal(err)
	}
	if err = s.projectHostedAttemptTerminal(t.Context(), done); err != nil {
		t.Fatal(err)
	}
	result := decodeSessionTest(t, sessionTestRequest(s, "GET", base+"/turns/"+turn["id"].(string), "", "", ""), 200)
	if result["status"] != "completed" {
		t.Fatal("hosted completion not visible", result)
	}
}

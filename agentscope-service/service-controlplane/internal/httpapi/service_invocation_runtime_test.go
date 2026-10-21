// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//     http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package httpapi

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/artifact"
	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/product"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

// Portal's Java HTTP fixture owns the endpoint and Session; this exercises the Go invocation adapter.
func TestNativeRuntimeLivePortal(t *testing.T) {
	endpoint, dsn := os.Getenv("MANAGED_PORTAL_TEST_URL"), os.Getenv("CONTROL_PLANE_TEST_POSTGRES_DSN")
	if endpoint == "" || dsn == "" {
		t.Skip("Portal loopback fixture and explicit test database are required")
	}
	var origin runtimeprotocol.Context
	if err := json.Unmarshal([]byte(os.Getenv("MANAGED_PORTAL_TEST_CONTEXT")), &origin); err != nil {
		t.Fatal(err)
	}
	config := product.DefaultConfig()
	config.LocalDev = true
	config.DSN, config.SessionPortalURL, config.InternalToken, config.WorkspaceRoot, config.SeedUsers = dsn, endpoint, os.Getenv("MANAGED_PORTAL_TEST_TOKEN"), t.TempDir(), false
	p, err := product.Open(t.Context(), config)
	if err != nil {
		t.Fatal(err)
	}
	defer p.Close()
	st, err := store.Open(t.Context(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	s := NewServer(ServerOptions{Store: st, Product: p})
	origin.CommandID = "go-invocation-turn"
	raw, err := p.ManagedRuntimeRequest(t.Context(), "owner", product.ManagedRuntimeCall{Context: origin, Operation: "submit_turn", Payload: map[string]any{"input": map[string]any{"message": "Invocation bridge"}}})
	if err != nil {
		t.Fatal(err)
	}
	var turn serviceNativeTurn
	if err = json.Unmarshal(raw, &turn); err != nil || turn.ID == "" {
		t.Fatalf("Turn receipt: %s %v", raw, err)
	}
	turn.Context, turn.Owner = origin, "owner"
	ep := &model.Endpoint{ID: uuid.New(), Tenant: origin.Tenant, Namespace: origin.Namespace}
	inv := &model.EndpointInvocation{ID: uuid.New(), EndpointID: ep.ID, Actor: model.Actor{Type: model.ActorHuman, Ref: "owner"}}
	raw, _ = json.Marshal(turn)
	if _, err = st.KV().Put(t.Context(), ep.Tenant, "service-api/native-turns", inv.ID.String(), raw); err != nil {
		t.Fatal(err)
	}
	read, err := s.nativeRuntimeRequest(t.Context(), turn, product.ManagedRuntimeCall{Operation: "turn"})
	var state map[string]any
	if err != nil || json.Unmarshal(read, &state) != nil || state["status"] != "accepted" {
		t.Fatalf("Turn query: %s %v", read, err)
	}
	snapshot, err := s.nativeServiceSnapshot(t.Context(), inv, turn)
	if err != nil || snapshot["snapshot_id"] == nil || snapshot["resources"] == nil {
		t.Fatalf("pinned snapshot: %v %v", snapshot, err)
	}
	command := &serviceCommand{ID: uuid.New(), Status: "accepted", Actor: inv.Actor}
	if err = s.applyNativeServiceInput(t.Context(), ep, inv, turn, command, "steer", "", map[string]any{"message": "Bridge steering"}); err != nil || command.Status != "submitted" || command.InputStatus != "accepted" {
		t.Fatalf("Input admission: %+v %v", command, err)
	}
	if err = s.refreshNativeServiceCommand(t.Context(), ep, inv, command, command.Actor); err != nil || command.Status != "submitted" {
		t.Fatalf("Input receipt: %+v %v", command, err)
	}
	// Exercise first admission through the actual Control Plane definition/resource resolver.
	s = NewServer(ServerOptions{Store: st, Product: p, LocalDev: true, ArtifactProvider: &artifact.LocalProvider{Root: t.TempDir()}})
	call := func(path, body string, status int) map[string]any {
		r := httptest.NewRequest("POST", path, strings.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		s.router.ServeHTTP(w, r)
		return decodeSessionTest(t, w, status)
	}
	env := call("/api/environments", `{"name":"Native bridge test","type":"cloud","config":{"provider":"acs","apiBaseUrl":"http://127.0.0.1:9","envdBaseUrl":"http://127.0.0.1:9","templateId":"fixture-only"}}`, 200)
	definition := call("/api/v1/agents", `{"agentKey":"bridge-`+uuid.NewString()+`","displayName":"Bridge","binding":{"kind":"managed"},"definition":{"name":"Bridge","system":"Test native admission","defaultEnvironmentId":"`+env["id"].(string)+`"}}`, 201)
	agent, err := uuid.Parse(definition["agent"].(map[string]any)["id"].(string))
	if err != nil {
		t.Fatal(err)
	}
	ep, err = st.Endpoints().Create(t.Context(), &model.Endpoint{Tenant: "default", Namespace: "default", TargetType: model.EndpointTargetAgent, TargetRef: agent, InvocationMode: model.EndpointConversationMode, Status: model.EndpointPublished, Name: "Bridge", Slug: "bridge-" + uuid.NewString()})
	if err != nil {
		t.Fatal(err)
	}
	contract, err := s.freezeServiceContract(t.Context(), ep)
	if err != nil {
		t.Fatal(err)
	}
	frozen, err := serviceapi.ReadContract(contract)
	if err != nil {
		t.Fatal(err)
	}
	ctx := serviceapi.WithContract(t.Context(), frozen)
	physical, err := s.resolveEndpointConversation(ctx, ep, uuid.NewString())
	if err != nil {
		t.Fatal(err)
	}
	conversation, err := st.Endpoints().CreateConversation(ctx, &model.EndpointConversation{EndpointID: ep.ID, AgentID: physical.AgentID, BindingID: physical.BindingID, SessionID: physical.SessionID, Status: model.EndpointConversationActive, PrincipalRef: "platform-user:" + product.LocalDeveloperID, Contract: contract})
	if err != nil {
		t.Fatal(err)
	}
	turnID := uuid.New()
	inv, _, err = st.Endpoints().ReserveInvocation(ctx, &model.EndpointInvocation{EndpointID: ep.ID, ConversationID: &conversation.ID, SessionID: conversation.SessionID, TurnID: &turnID, Contract: contract, Mode: model.EndpointConversationMode, PrincipalRef: conversation.PrincipalRef, Actor: model.Actor{Type: model.ActorHuman, Ref: product.LocalDeveloperID}, Status: model.EndpointInvocationAccepted})
	if err != nil {
		t.Fatal(err)
	}
	for range 2 {
		if err = s.sendEndpointConversationTurn(ctx, ep, conversation, inv, "First native Turn"); err != nil {
			t.Fatal(err)
		}
	}
	native, err := s.nativeServiceTurn(ctx, ep, inv)
	if err != nil || native.Context.SessionID != conversation.ID.String() || native.Context.Actor["user"] != product.LocalDeveloperID {
		t.Fatalf("native identity changed: %+v %v", native, err)
	}
	resource, err := sessionapi.Get(ctx, st, ep.Tenant, conversation.ID)
	if err != nil || resource.SourceEndpointID == nil || *resource.SourceEndpointID != ep.ID {
		t.Fatalf("resource binding missing: %+v %v", resource, err)
	}
	page, err := s.nativeRuntimeRequest(ctx, native, product.ManagedRuntimeCall{Operation: "turns"})
	var turns struct {
		Data []any `json:"data"`
	}
	if err != nil || json.Unmarshal(page, &turns) != nil || len(turns.Data) != 1 {
		t.Fatalf("retry created another Turn: %s %v", page, err)
	}
	// First file-bearing Turn must register Ma before materializing its file.
	freshID := uuid.New()
	frozen.PublicSessionID = freshID
	// Match Session creation: persist the selected Environment before lazy registration.
	if err = s.freezeManagedEnvironment(t.Context(), frozen, agent); err != nil {
		t.Fatal(err)
	}
	if frozen.EnvironmentID != env["id"].(string) {
		t.Fatalf("Session lost the frozen Environment: %q", frozen.EnvironmentID)
	}
	contract, err = json.Marshal(frozen)
	if err != nil {
		t.Fatal(err)
	}
	resource = &sessionapi.Session{ID: freshID, Tenant: ep.Tenant, Namespace: ep.Namespace, Target: sessionapi.Target{Type: "agent", ID: agent}, Principal: "platform-user:" + product.LocalDeveloperID, Contract: contract, Status: "active", CreatedAt: time.Now(), UpdatedAt: time.Now()}
	if err = sessionapi.Save(t.Context(), st, resource, true); err != nil {
		t.Fatal(err)
	}
	ctx = serviceapi.WithContract(t.Context(), frozen)
	physical, err = s.resolveEndpointConversation(ctx, ep, freshID.String())
	if err != nil {
		t.Fatal(err)
	}
	conversation, err = st.Endpoints().CreateConversation(ctx, &model.EndpointConversation{ID: freshID, EndpointID: ep.ID, AgentID: physical.AgentID, BindingID: physical.BindingID, SessionID: physical.SessionID, Status: model.EndpointConversationActive, PrincipalRef: resource.Principal, Contract: contract})
	if err != nil {
		t.Fatal(err)
	}
	content := bytes.Repeat([]byte("first-file"), 320*1024)
	base := "/api/v1/agent-sessions/" + resource.ID.String()
	// Files no longer require a Go artifact provider or an existing Ma Session / Turn.
	s.artifactProvider = nil
	upload := func(data []byte, status int) map[string]any {
		r := httptest.NewRequest("POST", base+"/files", bytes.NewReader(data))
		r.Header.Set("Content-Type", "text/plain")
		r.Header.Set("X-File-Name", "First%20file.txt")
		r.Header.Set("Idempotency-Key", "first-native-file")
		w := httptest.NewRecorder()
		s.router.ServeHTTP(w, r)
		return decodeSessionTest(t, w, status)
	}
	file := upload(content, 201)
	fileID, ok := file["file_id"].(string)
	if !ok || !strings.HasPrefix(fileID, "file_") {
		t.Fatalf("invalid native file metadata: %v", file)
	}
	if upload(content, 201)["file_id"] != fileID {
		t.Fatal("file retry created another file")
	}
	upload([]byte("changed bytes"), 409)
	upload(bytes.Repeat([]byte{42}, (16<<20)+1), 413)
	if _, err = s.getPublicSessionFile(ctx, resource, fileID); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("Managed file mirrored in Go storage: %v", err)
	}
	input, _ := json.Marshal(map[string]any{"input": []any{map[string]any{"role": "user", "content": []any{map[string]any{"type": "file", "file_id": fileID}}}}})
	turnID = uuid.New()
	inv, _, err = st.Endpoints().ReserveInvocation(ctx, &model.EndpointInvocation{EndpointID: ep.ID, ConversationID: &conversation.ID, SessionID: conversation.SessionID, TurnID: &turnID, Contract: contract, Mode: model.EndpointConversationMode, PrincipalRef: conversation.PrincipalRef, Actor: model.Actor{Type: model.ActorHuman, Ref: product.LocalDeveloperID}, Input: input, Status: model.EndpointInvocationAccepted, IdempotencyKey: "first-file"})
	if err != nil {
		t.Fatal(err)
	}
	for range 2 {
		if err = s.sendEndpointConversationTurn(ctx, ep, conversation, inv, "unused typed input"); err != nil {
			t.Fatal(err)
		}
	}
	native, err = s.nativeServiceTurn(ctx, ep, inv)
	if err != nil {
		t.Fatal(err)
	}
	page, err = s.nativeRuntimeRequest(ctx, native, product.ManagedRuntimeCall{Operation: "turns"})
	if err != nil || json.Unmarshal(page, &turns) != nil || len(turns.Data) != 1 {
		t.Fatalf("file retry created another Turn: %s %v", page, err)
	}
	receipt, err := s.nativeRuntimeInput(ctx, ep, inv, native, inv.Actor, "native-file-inject", "inject", "", json.RawMessage(input))
	var injection map[string]any
	if err != nil || json.Unmarshal(receipt, &injection) != nil || injection["turn_id"] != native.ID || injection["status"] != "accepted" {
		t.Fatalf("native file Input was not admitted: %s %v", receipt, err)
	}
	// Management's journal is a different coordinate; it cannot replace a native snapshot cursor.
	if _, err = s.sessionJournal(resource).Append(ctx, "management-only", "test.management", map[string]any{}); err != nil {
		t.Fatal(err)
	}
	cpSnapshot, err := s.sessionJournal(resource).Snapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	nativeSnapshot, err := s.managedPublicSnapshot(ctx, resource, frozen)
	if err != nil || nativeSnapshot["as_of"] == cpSnapshot.AsOf {
		t.Fatalf("management cursor replaced native coordinate: %v %v", nativeSnapshot, err)
	}
	for _, value := range nativeSnapshot["resources"].(map[string]any) {
		if value.(map[string]any)["as_of"] != nativeSnapshot["as_of"] {
			t.Fatal("resource and snapshot cursors differ")
		}
	}
	owner, _, err := s.publicNativeSession(ctx, resource, frozen)
	if err != nil {
		t.Fatal(err)
	}
	events, err := s.nativeSessionEvents(ctx, resource, frozen, owner, "", 256)
	if err != nil || len(events.Data) == 0 || events.Next != nativeSnapshot["as_of"] {
		t.Fatalf("events and snapshot do not share native cursor: %+v %v", events, err)
	}
	if _, err = s.nativeSessionEvents(ctx, resource, frozen, owner, cpSnapshot.AsOf, 256); err == nil {
		t.Fatal("management cursor accepted by native stream")
	}
	// Run the actual public resource handlers, not just their transport helpers.
	resourceCall := func(method, suffix, body, key, revision string, status int) map[string]any {
		r := httptest.NewRequest(method, base+suffix, strings.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		if key != "" {
			r.Header.Set("Idempotency-Key", key)
		}
		if revision != "" {
			r.Header.Set("If-Match", revision)
		}
		w := httptest.NewRecorder()
		s.router.ServeHTTP(w, r)
		return decodeSessionTest(t, w, status)
	}
	caps := resourceCall("GET", "/capabilities", "", "", "", 200)
	capabilities := caps["capabilities"].(map[string]any)
	if capabilities["files"] != true || capabilities["fork"] != true || capabilities["checkpoint_restore"] != true {
		t.Fatalf("capabilities do not match native runtime: %v", caps)
	}
	checkpoints := resourceCall("GET", "/checkpoints?limit=1", "", "", "", 200)
	if len(checkpoints["data"].([]any)) != 0 || checkpoints["as_of"] == nil {
		t.Fatalf("invalid checkpoint page: %v", checkpoints)
	}
	resourceCall("GET", "/checkpoints/missing", "", "", "", 404)
	metadata := resourceCall("GET", "/files/"+fileID, "", "", "", 200)
	if metadata["file_id"] != fileID || metadata["size"] != float64(len(content)) {
		t.Fatalf("native metadata changed: %v", metadata)
	}
	files := resourceCall("GET", "/files?limit=1&offset=0", "", "", "", 200)
	if len(files["data"].([]any)) != 1 || files["has_more"] != false {
		t.Fatalf("native file listing differs: %v", files)
	}
	resourceCall("GET", "/files?offset=bad", "", "", "", 400)
	rawFile := httptest.NewRequest("GET", base+"/files/"+fileID+"/content", nil)
	rawResponse := httptest.NewRecorder()
	s.router.ServeHTTP(rawResponse, rawFile)
	if rawResponse.Code != 200 || !bytes.Equal(rawResponse.Body.Bytes(), content) {
		t.Fatalf("file bytes changed: status=%d size=%d", rawResponse.Code, rawResponse.Body.Len())
	}
	configured := resourceCall("PUT", "/budget", `{"max_model_calls":20}`, "", `"0"`, 202)
	if configured["revision"] != float64(1) {
		t.Fatalf("lost budget revision: %v", configured)
	}
	resourceCall("PUT", "/budget", `{"max_model_calls":30}`, "", `"0"`, 409)
	budget := resourceCall("GET", "/budget", "", "", "", 200)
	if budget["limits"].(map[string]any)["max_model_calls"] != float64(20) {
		t.Fatalf("budget changed after conflict: %v", budget)
	}
	publication := resourceCall("POST", "/artifacts", `{"file_id":"`+fileID+`"}`, "go-native-artifact", "", 201)
	artifactID, ok := publication["artifact_id"].(string)
	if !ok || !strings.HasPrefix(artifactID, "artifact_") {
		t.Fatalf("native artifact ID changed: %v", publication)
	}
	pinned := resourceCall("GET", "/artifacts?limit=1", "", "", "", 200)
	resourceCall("GET", "/artifacts/"+artifactID, "", "", "", 200)
	r := httptest.NewRequest("GET", base+"/artifacts/"+artifactID+"/content", nil)
	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, r)
	if w.Code != 200 || !bytes.Equal(w.Body.Bytes(), content) || w.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("artifact bytes changed: status=%d size=%d", w.Code, w.Body.Len())
	}
	resourceCall("DELETE", "/artifacts/"+artifactID, "", "", "", 200)
	resourceCall("GET", "/artifacts/"+artifactID, "", "", "", 404)
	resourceCall("GET", "/artifacts/"+artifactID+"?snapshot_id="+url.QueryEscape(pinned["snapshot_id"].(string)), "", "", "", 200)
	resourceCall("GET", "/items?limit=bad", "", "", "", 400)
}

func TestNativeRuntimeRetainsHumanAndApplicationCredentialActor(t *testing.T) {
	st, err := store.Open(t.Context(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	s := NewServer(ServerOptions{Store: st})
	app := uuid.New()
	ep := &model.Endpoint{ID: uuid.New(), Tenant: "tenant", Namespace: "namespace"}
	inv := &model.EndpointInvocation{ApplicationID: &app}
	human := model.Actor{Type: model.ActorHuman, Ref: "alice"}
	actor, err := s.nativeRuntimeActor(t.Context(), ep, inv, "resource-owner", human)
	if err != nil || actor["user"] != "alice" || actor["key_hash"] != "" || actor["internal"] != false {
		t.Fatalf("human replaced: %v %v", actor, err)
	}
	key := sessionCredential{ID: uuid.New(), ApplicationID: app, Hash: "original-credential-hash", Status: "active"}
	credential := model.Actor{Type: model.ActorAutomation, Ref: "session-key:" + key.ID.String()}
	write := func() {
		raw, _ := json.Marshal(key)
		if _, err := st.KV().Put(t.Context(), ep.Tenant, applicationCredentialPath(app), key.ID.String(), raw); err != nil {
			t.Fatal(err)
		}
	}
	write()
	actor, err = s.nativeRuntimeActor(t.Context(), ep, inv, "resource-owner", credential)
	if err != nil || actor["key_hash"] != key.Hash || actor["credential_ref"] != credential.Ref || actor["internal"] != false {
		t.Fatalf("key replaced: %v %v", actor, err)
	}
	for _, mutate := range []func(){func() { key.ApplicationID = uuid.New() }, func() { key.ApplicationID = app; key.Status = "revoked" }, func() { key.Status = "active"; past := time.Now().Add(-time.Minute); key.ExpiresAt = &past }} {
		mutate()
		write()
		if _, err = s.nativeRuntimeActor(t.Context(), ep, inv, "resource-owner", credential); err == nil {
			t.Fatal("invalid original credential accepted")
		}
	}
	if _, err = s.nativeRuntimeActor(t.Context(), ep, inv, "resource-owner", model.Actor{Type: model.ActorSystem, Ref: "resource-owner"}); err == nil {
		t.Fatal("system identity replaced the original actor")
	}
}

func TestNativeInputReceiptDoesNotClaimAppliedAtAdmission(t *testing.T) {
	command := &serviceCommand{Status: "accepted"}
	for _, value := range []struct{ status, delivery, command string }{{"accepted", "pending", "submitted"}, {"accepted", "delivered", "submitted"}, {"applied", "delivered", "completed"}} {
		raw, _ := json.Marshal(map[string]any{"id": "input", "turn_id": "turn", "status": value.status, "delivery_status": value.delivery})
		if err := updateNativeCommandReceipt(command, "turn", raw); err != nil || command.Status != value.command || command.InputStatus != value.status || command.InputDeliveryStatus != value.delivery {
			t.Fatalf("receipt changed meaning: %+v %v", command, err)
		}
	}
	before := *command
	for _, raw := range []string{`{"id":"input","turn_id":"other","status":"applied"}`, `{"id":"other","turn_id":"turn","status":"applied"}`, `{"id":"input","turn_id":"turn","status":"unknown"}`} {
		if err := updateNativeCommandReceipt(command, "turn", json.RawMessage(raw)); err == nil || command.Status != before.Status || command.InputID != before.InputID {
			t.Fatal("invalid receipt mutated command")
		}
	}
	if err := updateNativeCommandReceipt(command, "turn", json.RawMessage(`{"id":"input","turn_id":"turn","status":"accepted"}`)); err == nil {
		t.Fatal("terminal receipt moved backwards")
	}
	command = &serviceCommand{Status: "submitted", InputID: "input"}
	if err := updateNativeCommandReceipt(command, "turn", json.RawMessage(`{"id":"input","turn_id":"turn","status":"rejected","rejection_reason":"turn_terminal"}`)); err != nil || command.Status != "failed" || command.Error != "turn_terminal" {
		t.Fatalf("rejection lost: %+v %v", command, err)
	}
}

func TestNativeEndpointPolicyScopeSeparatesEndpointCredentials(t *testing.T) {
	endpoint := uuid.New()
	v := &sessionapi.Session{Tenant: "tenant", Namespace: "namespace", Target: sessionapi.Target{Type: "agent", ID: uuid.New()}, SourceEndpointID: &endpoint}
	contract := &serviceapi.Contract{Endpoint: model.Endpoint{ID: endpoint}}
	first, err := managedRuntimePolicyScope(v, contract)
	if err != nil {
		t.Fatal(err)
	}
	other := uuid.New()
	v.SourceEndpointID = &other
	second, err := managedRuntimePolicyScope(v, contract)
	if err != nil || first == second {
		t.Fatalf("Endpoint credential policies shared: %s %s %v", first, second, err)
	}
}

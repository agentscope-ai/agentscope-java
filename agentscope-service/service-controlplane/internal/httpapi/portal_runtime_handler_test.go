// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package httpapi

import (
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"testing"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

func TestPortalRuntimeAdmissionKeepsImmutableConfigurationAndCommandReceipts(t *testing.T) {
	st, err := store.Open(t.Context(), store.DefaultConfig())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	s := NewServer(ServerOptions{RuntimeOnly: true, Store: st, InternalToken: "portal-secret"})
	request := func(path string, value any, token string) *httptest.ResponseRecorder {
		raw, err := json.Marshal(value)
		if err != nil {
			t.Fatal(err)
		}
		req := httptest.NewRequest("POST", "/api/internal/runtime"+path, bytes.NewReader(raw))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Builder-Internal-Token", token)
		out := httptest.NewRecorder()
		s.router.ServeHTTP(out, req)
		return out
	}
	id := uuid.New()
	contract := serviceapi.Contract{PublicSessionID: id, Endpoint: model.Endpoint{ID: id, Tenant: "tenant", Namespace: "space"}}
	configuration, _ := json.Marshal(map[string]any{"session": map[string]any{"id": id.String(), "ownerId": "owner"}, "contract": contract})
	revision, _ := runtimeprotocol.Digest(json.RawMessage(configuration))
	bootstrap := runtimeprotocol.RuntimeSessionBootstrap{Context: runtimeprotocol.Context{Version: 1, Tenant: "tenant", Namespace: "space", SessionID: id.String(), CommandID: "bootstrap:" + id.String(), TraceID: "trace", Actor: map[string]any{"user": "owner"}, PermissionExpiresAt: time.Now().Add(time.Minute).UnixMilli()}, RuntimeKind: "team", ConfigurationRevision: revision, Configuration: configuration}
	if out := request("/sessions/bootstrap", bootstrap, "wrong"); out.Code != 401 {
		t.Fatalf("unauthorized bootstrap=%d", out.Code)
	}
	for range 2 {
		out := request("/sessions/bootstrap", bootstrap, "portal-secret")
		if out.Code != 200 {
			t.Fatalf("bootstrap=%d %s", out.Code, out.Body.String())
		}
		var receipt runtimeprotocol.RuntimeSessionReady
		if err = json.Unmarshal(out.Body.Bytes(), &receipt); err != nil {
			t.Fatal(err)
		}
		expected, _ := runtimeprotocol.BootstrapDigest(bootstrap)
		if receipt.ConfigurationDigest != expected || receipt.ConfigurationRevision != revision {
			t.Fatalf("bootstrap digest=%+v", receipt)
		}
		bootstrap.Context.PermissionExpiresAt = 1
		bootstrap.Context.TraceID = "replayed"
	}
	changed := bootstrap
	changed.RuntimeKind = "workflow"
	if out := request("/sessions/bootstrap", changed, "portal-secret"); out.Code != 409 {
		t.Fatalf("changed bootstrap=%d", out.Code)
	}
	turnID := uuid.NewString()
	ctx := bootstrap.Context
	ctx.TurnID = &turnID
	ctx.CommandID = turnID
	input := map[string]any{"message": "hello"}
	digest, _ := runtimeprotocol.Digest(input)
	turn := runtimeprotocol.TurnCommand{Context: ctx, IdempotencyKey: "request", InputDigest: digest, AdmissionSequence: 1, Input: input}
	for range 2 {
		if out := request("/commands/turn", turn, "portal-secret"); out.Code != 200 {
			t.Fatalf("Turn=%d %s", out.Code, out.Body.String())
		}
	}
	changedTurn := turn
	changedTurn.IdempotencyKey = "different"
	if out := request("/commands/turn", changedTurn, "portal-secret"); out.Code != 409 {
		t.Fatalf("changed command=%d", out.Code)
	}
	changedTurn = turn
	changedTurn.Context.Namespace = "foreign"
	if out := request("/commands/turn", changedTurn, "portal-secret"); out.Code != 404 {
		t.Fatalf("foreign namespace=%d", out.Code)
	}
	changedTurn = turn
	changedTurn.Context.Version = 2
	if out := request("/commands/turn", changedTurn, "portal-secret"); out.Code != 400 {
		t.Fatalf("future command=%d", out.Code)
	}
	changedTurn = turn
	changedTurn.Input = map[string]any{"message": "changed"}
	if out := request("/commands/turn", changedTurn, "portal-secret"); out.Code != 400 {
		t.Fatalf("invalid digest=%d", out.Code)
	}
	queued, err := st.RuntimePortal().Turn(t.Context(), turnID)
	if err != nil || queued.Status != "queued" || queued.Generation != 0 {
		t.Fatalf("acceptance executed inference: %+v %v", queued, err)
	}
	command := runtimeprotocol.InteractionCommand{Context: ctx, Kind: "cancel", Payload: map[string]any{
		"target_command_id": turnID, "expected_generation": 0, "target_activation_command_id": turnID}}
	command.Context.CommandID = "cancellation"
	command.Context.PermissionExpiresAt = time.Now().Add(time.Minute).UnixMilli()
	if out := request("/commands/interaction", command, "wrong"); out.Code != 401 {
		t.Fatalf("unauthorized cancellation=%d", out.Code)
	}
	for range 2 {
		out := request("/commands/interaction", command, "portal-secret")
		if out.Code != 200 {
			t.Fatalf("cancellation=%d %s", out.Code, out.Body.String())
		}
		var receipt runtimeprotocol.RuntimeInteractionReceipt
		if err = json.Unmarshal(out.Body.Bytes(), &receipt); err != nil {
			t.Fatal(err)
		}
		payloadDigest, _ := runtimeprotocol.Digest(command.Payload)
		if receipt.PayloadDigest != payloadDigest || receipt.Status != "runtime_accepted" {
			t.Fatalf("cancellation receipt=%+v", receipt)
		}
		command.Context.PermissionExpiresAt = 1
	}
	command.Kind = "steer"
	if out := request("/commands/interaction", command, "portal-secret"); out.Code != 422 {
		t.Fatalf("unsupported control=%d", out.Code)
	}
	command.Kind = "cancel"
	command.Payload["expected_generation"] = -1
	if out := request("/commands/interaction", command, "portal-secret"); out.Code != 400 {
		t.Fatalf("invalid cancellation=%d", out.Code)
	}
	lifecycle := runtimeprotocol.RuntimeSessionLifecycle{Context: bootstrap.Context, LifecycleRevision: 2, Lifecycle: "archived"}
	lifecycle.Context.CommandID = "archive-session"
	for range 2 {
		out := request("/sessions/lifecycle", lifecycle, "portal-secret")
		if out.Code != 200 {
			t.Fatalf("lifecycle=%d %s", out.Code, out.Body.String())
		}
		var receipt runtimeprotocol.RuntimeLifecycleReceipt
		if err = json.Unmarshal(out.Body.Bytes(), &receipt); err != nil {
			t.Fatal(err)
		}
		digest, _ := runtimeprotocol.Digest(lifecycle)
		if receipt.PayloadDigest != digest || receipt.Status != "applied" {
			t.Fatalf("lifecycle receipt=%+v", receipt)
		}
	}
	lifecycle.Context.Namespace = "other"
	if out := request("/sessions/lifecycle", lifecycle, "portal-secret"); out.Code != 404 {
		t.Fatalf("foreign lifecycle=%d", out.Code)
	}
}

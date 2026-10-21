// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package runtimeportal

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
)

func TestPortalClientRequiresMatchingGrantsAndFactReceipts(t *testing.T) {
	var change atomic.Bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Builder-Internal-Token") != "service-fixture" {
			w.WriteHeader(401)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/api/internal/runtime/execution-grants":
			var request runtimeprotocol.RuntimeExecutionRequest
			if err := runtimeprotocol.Decode(r.Body, &request); err != nil {
				t.Error(err)
				w.WriteHeader(400)
				return
			}
			if !request.Renewal || request.ActivationCommandID != "turn" {
				t.Errorf("grant lost admission identity: %+v", request)
			}
			grant := runtimeprotocol.RuntimeExecutionGrant{Context: request.Context, ExecutionGeneration: request.ExecutionGeneration, ExecutionFence: request.ExecutionFence, ValidUntil: time.Now().Add(time.Minute).UnixMilli()}
			if change.Load() {
				grant.ExecutionFence = "foreign"
			}
			_ = json.NewEncoder(w).Encode(grant)
		case "/api/internal/runtime/agent-signals":
			var signal runtimeprotocol.RuntimeAgentSignal
			if err := runtimeprotocol.Decode(r.Body, &signal); err != nil {
				t.Error(err)
				w.WriteHeader(400)
				return
			}
			receipt := runtimeprotocol.RuntimeAgentSignalReceipt{Version: 1, CommandID: "durable-signal", SessionID: signal.SessionID,
				TurnID: signal.TurnID, Kind: "signal", Status: "queued", CommandStatus: "queued", CreatedAt: 1}
			if change.Load() {
				receipt.TurnID = "foreign"
			}
			_ = json.NewEncoder(w).Encode(receipt)
		case "/api/internal/runtime/facts":
			var fact runtimeprotocol.RuntimeFact
			if err := runtimeprotocol.Decode(r.Body, &fact); err != nil {
				t.Error(err)
				w.WriteHeader(400)
				return
			}
			receipt := runtimeprotocol.RuntimeFactReceipt{Version: 1, SessionID: fact.Context.SessionID, Source: fact.Source, EventID: fact.EventID, SourceSequence: fact.SourceSequence, PublicSequence: 12}
			if change.Load() {
				receipt.SourceSequence++
			}
			_ = json.NewEncoder(w).Encode(receipt)
		default:
			w.WriteHeader(404)
		}
	}))
	defer server.Close()
	client, err := NewClient(server.URL, "service-fixture")
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	id := "turn"
	ctx := runtimeprotocol.Context{Version: 1, Tenant: "tenant", Namespace: "space", SessionID: "root", TurnID: &id, CommandID: id, TraceID: "trace", Actor: map[string]any{"user": "owner"}}
	turn := Turn{Command: runtimeprotocol.TurnCommand{Context: ctx, InputDigest: "digest", AdmissionSequence: 1}, Generation: 1, Fence: "fence"}
	if _, err = client.Grant(t.Context(), turn, true); err != nil {
		t.Fatal(err)
	}
	fact := runtimeprotocol.RuntimeFact{Context: ctx, Source: "orchestration", EventID: "event", SourceSequence: 1, Type: "turn.running", Payload: map[string]any{}}
	if err = client.DeliverFact(t.Context(), fact); err != nil {
		t.Fatal(err)
	}
	signal := runtimeprotocol.RuntimeAgentSignal{Version: 1, SessionID: "root", TurnID: "turn", TaskID: "task", Value: json.RawMessage(`null`)}
	if receipt, signalErr := client.SubmitAgentSignal(t.Context(), signal); signalErr != nil || receipt["command_id"] != "durable-signal" {
		t.Fatalf("signal ACK=%v, %v", receipt, signalErr)
	}
	change.Store(true)
	if _, signalErr := client.SubmitAgentSignal(t.Context(), signal); !errors.Is(signalErr, ErrConflict) {
		t.Fatalf("foreign signal ACK=%v", signalErr)
	}
	if _, err = client.Grant(t.Context(), turn, true); !errors.Is(err, ErrPermission) {
		t.Fatalf("foreign fence grant=%v", err)
	}
	if err = client.DeliverFact(t.Context(), fact); !errors.Is(err, ErrConflict) {
		t.Fatalf("unmatched fact receipt=%v", err)
	}
}

func TestManagedAttemptBarrierRequiresExplicitResponse(t *testing.T) {
	var response atomic.Value
	response.Store(`{"version":1,"settled":false}`)
	var status atomic.Int32
	status.Store(http.StatusOK)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" || r.URL.Path != "/api/internal/managed-attempts/original-attempt/settled" || r.Header.Get("X-Builder-Internal-Token") != "service-fixture" {
			t.Errorf("wrong barrier request: %s %s", r.Method, r.URL.Path)
		}
		w.WriteHeader(int(status.Load()))
		_, _ = w.Write([]byte(response.Load().(string)))
	}))
	defer server.Close()
	client, err := NewClient(server.URL, "service-fixture")
	if err != nil {
		t.Fatal(err)
	}
	for _, raw := range []string{`{"version":1,"settled":false}`, `{"version":1,"settled":true}`} {
		response.Store(raw)
		settled, err := client.ManagedAttemptSettled(t.Context(), "original-attempt")
		if err != nil || settled != (raw == `{"version":1,"settled":true}`) {
			t.Fatalf("barrier %s: %v %v", raw, settled, err)
		}
	}
	response.Store(`{"version":1}`)
	if settled, err := client.ManagedAttemptSettled(t.Context(), "original-attempt"); err == nil || settled {
		t.Fatalf("missing barrier accepted: %v %v", settled, err)
	}
	status.Store(http.StatusServiceUnavailable)
	if settled, err := client.ManagedAttemptSettled(t.Context(), "original-attempt"); err == nil || settled {
		t.Fatalf("failed observation accepted: %v %v", settled, err)
	}
}

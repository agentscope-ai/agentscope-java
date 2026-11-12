// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package agentservice

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
)

type workerBackendFixture struct {
	stepErr                    error
	step                       func(Turn) Progress
	stop                       func(Turn) Progress
	steps, stops, observations int
}

func (b *workerBackendFixture) Step(_ context.Context, _ *Session, row Turn) (Progress, error) {
	b.steps++
	if b.stepErr != nil {
		return Progress{}, b.stepErr
	}
	return b.step(row), nil
}
func (b *workerBackendFixture) Observe(_ context.Context, _ *Session, row Turn) (Progress, error) {
	b.observations++
	return Progress{Status: row.Status, Cursor: row.NativeCursor}, nil
}
func (b *workerBackendFixture) Stop(_ context.Context, _ *Session, row Turn) (Progress, error) {
	b.stops++
	if b.stop != nil {
		return b.stop(row), nil
	}
	return Progress{Status: "cancelled"}, nil
}

func TestRuntimeWorkerCancellationNeedsStopProofAndPreservesCompletedOutcome(t *testing.T) {
	for _, completed := range []bool{false, true} {
		t.Run(fmt.Sprint(completed), func(t *testing.T) {
			repository := workerRepository(t)
			id := strings.Repeat("a", 64)
			row, _ := repository.Turn(t.Context(), id)
			command := runtimeprotocol.InteractionCommand{Context: row.Command.Context, Kind: "cancel", Payload: map[string]any{
				"target_command_id": id, "expected_generation": 0, "target_activation_command_id": id}}
			command.Context.CommandID = "cancel"
			if _, err := repository.AcceptCancellation(t.Context(), command, time.Now()); err != nil {
				t.Fatal(err)
			}
			backend := &workerBackendFixture{}
			backend.stop = func(Turn) Progress {
				if backend.stops == 1 {
					return Progress{Status: "running"}
				}
				if completed {
					return Progress{Status: "completed", Facts: []runtimeprotocol.RuntimeFact{{EventID: "item", Type: "item.completed"}, {EventID: "done", Type: "turn.completed"}}}
				}
				return Progress{Status: "cancelled"}
			}
			worker := &Worker{Repository: repository, Backend: backend}
			for step := 0; step < 2; step++ {
				rows, err := repository.ClaimTurns(t.Context(), time.Now().Add(3*time.Second), 45*time.Second, 1)
				if err != nil || len(rows) != 1 {
					t.Fatalf("claim=%v %v", rows, err)
				}
				// A completed activation had already received this immutable fence.
				if completed && step == 0 {
					rows[0].Generation, rows[0].Fence, rows[0].GrantUntil = 1, "fence", time.Now().Add(time.Minute).UnixMilli()
				}
				if err = worker.Advance(t.Context(), rows[0]); err != nil {
					t.Fatal(err)
				}
				state, _ := repository.Turn(t.Context(), id)
				expected := "cancel_requested"
				if step == 1 {
					expected = "cancelled"
					if completed {
						expected = "completed"
					}
				}
				if state.Status != expected || backend.steps != 0 {
					t.Fatalf("state=%s expected=%s new execution=%d", state.Status, expected, backend.steps)
				}
			}
		})
	}
}

func workerRepository(t *testing.T) *Memory {
	t.Helper()
	repository := &Memory{}
	ctx := runtimeprotocol.Context{Version: 1, Tenant: "tenant", Namespace: "space", SessionID: "root", CommandID: "bootstrap:root", TraceID: "trace", Actor: map[string]any{"user": "owner"}, PermissionExpiresAt: time.Now().Add(time.Minute).UnixMilli()}
	bootstrap := runtimeprotocol.RuntimeSessionBootstrap{Context: ctx, RuntimeKind: "team", ConfigurationRevision: "revision", Configuration: json.RawMessage(`{"session":{"id":"root","ownerId":"owner"}}`)}
	digest, _ := runtimeprotocol.BootstrapDigest(bootstrap)
	if _, err := repository.Bootstrap(t.Context(), bootstrap, digest, time.Now().UTC()); err != nil {
		t.Fatal(err)
	}
	id := strings.Repeat("a", 64)
	ctx.CommandID = id
	ctx.TurnID = &id
	input := map[string]any{"message": "hello"}
	digest, _ = runtimeprotocol.Digest(input)
	command := runtimeprotocol.TurnCommand{Context: ctx, Input: input, InputDigest: digest, IdempotencyKey: "request", AdmissionSequence: 1}
	digest, _ = runtimeprotocol.TurnDigest(command)
	if _, err := repository.AcceptTurn(t.Context(), command, digest, time.Now().UTC()); err != nil {
		t.Fatal(err)
	}
	return repository
}

func TestRuntimeWorkerPersistsFenceBeforeGrantAndDrainsFinalFacts(t *testing.T) {
	repository := workerRepository(t)
	grants := []runtimeprotocol.RuntimeExecutionRequest{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var request runtimeprotocol.RuntimeExecutionRequest
		if err := runtimeprotocol.Decode(r.Body, &request); err != nil {
			t.Error(err)
			w.WriteHeader(400)
			return
		}
		grants = append(grants, request)
		saved, err := repository.Turn(t.Context(), request.Context.CommandID)
		if err != nil || saved.Generation != request.ExecutionGeneration || saved.Fence != request.ExecutionFence || saved.NativeInvocationID == "" {
			t.Errorf("grant preceded durable fence: %+v %v", saved, err)
		}
		if len(grants) == 1 {
			_, _ = w.Write([]byte("{"))
			return
		} // Remote grant may have committed; ACK is unknown.
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(runtimeprotocol.RuntimeExecutionGrant{Context: request.Context, ExecutionGeneration: request.ExecutionGeneration, ExecutionFence: request.ExecutionFence, ValidUntil: time.Now().Add(time.Minute).UnixMilli()})
	}))
	defer server.Close()
	client, err := NewClient(server.URL, "service-fixture")
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	backend := &workerBackendFixture{}
	backend.step = func(row Turn) Progress {
		if _, err := uuid.Parse(row.NativeInvocationID); err != nil {
			t.Error("public hash was used as a native UUID")
		}
		if row.GrantUntil <= time.Now().UnixMilli() {
			t.Error("native execution lacked a durable fresh grant")
		}
		if row.NativeCursor == "" {
			return Progress{Status: "running", NativeStarted: true, Cursor: "one", Facts: []runtimeprotocol.RuntimeFact{{EventID: "running", Type: "turn.running"}}}
		}
		return Progress{Status: "completed", NativeStarted: true, Cursor: "final", Facts: []runtimeprotocol.RuntimeFact{
			{EventID: "item", Type: "item.completed", Payload: map[string]any{"item_id": "reply", "item": map[string]any{"role": "assistant", "text": "done"}}},
			{EventID: "usage", Type: "usage.recorded", Payload: map[string]any{"input_tokens": 1, "output_tokens": 2}},
			{EventID: "completed", Type: "turn.completed"}}}
	}
	worker := &Worker{Repository: repository, Client: client, Backend: backend, Slots: 1}
	claim := func() Turn {
		rows, err := repository.ClaimTurns(t.Context(), time.Now().UTC().Add(3*time.Second), 45*time.Second, 1)
		if err != nil || len(rows) != 1 {
			t.Fatalf("claim=%+v %v", rows, err)
		}
		return rows[0]
	}
	if err = worker.Advance(t.Context(), claim()); err == nil || backend.steps != 0 {
		t.Fatalf("unknown grant executed work: %v steps=%d", err, backend.steps)
	}
	for range 2 {
		if err = worker.Advance(t.Context(), claim()); err != nil {
			t.Fatal(err)
		}
	}
	if len(grants) != 3 || grants[0].ExecutionFence != grants[1].ExecutionFence || grants[0].ExecutionGeneration != grants[1].ExecutionGeneration || grants[1].Renewal || !grants[2].Renewal {
		t.Fatalf("grant retry identity changed=%+v", grants)
	}
	row, err := repository.Turn(t.Context(), strings.Repeat("a", 64))
	if err != nil || row.Status != "completed" || row.NativeCursor != "final" || backend.steps != 2 {
		t.Fatalf("native progress=%+v %v", row, err)
	}
	for sequence := int64(1); sequence <= 4; sequence++ {
		facts, err := repository.ClaimFacts(t.Context(), time.Now().UTC(), time.Minute, 1)
		if err != nil || len(facts) != 1 || facts[0].Value.SourceSequence != sequence || facts[0].Value.ExecutionFence == nil || *facts[0].Value.ExecutionFence != row.Fence {
			t.Fatalf("final facts=%+v %v", facts, err)
		}
		if sequence == 4 && facts[0].Value.Type != "turn.completed" {
			t.Fatal("terminal event overtook final item/usage")
		}
		if err = repository.AcknowledgeFact(t.Context(), "root", sequence, facts[0].ClaimToken, time.Now().UTC()); err != nil {
			t.Fatal(err)
		}
	}
}

func TestRuntimeWorkerRejectedActivationNeverStartsNativeExecution(t *testing.T) {
	repository := workerRepository(t)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(403) }))
	defer server.Close()
	client, err := NewClient(server.URL, "service-fixture")
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	backend := &workerBackendFixture{step: func(Turn) Progress { t.Error("rejected activation executed"); return Progress{} }}
	worker := &Worker{Repository: repository, Client: client, Backend: backend, Slots: 1}
	rows, err := repository.ClaimTurns(t.Context(), time.Now().UTC(), 45*time.Second, 1)
	if err != nil || len(rows) != 1 {
		t.Fatalf("claim=%+v %v", rows, err)
	}
	if err = worker.Advance(t.Context(), rows[0]); err != nil {
		t.Fatal(err)
	}
	facts, err := repository.ClaimFacts(t.Context(), time.Now().UTC(), time.Minute, 1)
	if err != nil || len(facts) != 1 || facts[0].Value.Type != "turn.failed" || facts[0].Value.ExecutionID != nil || facts[0].Value.Payload["native_started"] != false || backend.steps != 0 || backend.stops != 1 {
		t.Fatalf("rejected activation facts=%+v %v", facts, err)
	}
}

func TestRuntimeWorkerRejectedControlDrainsBeforeReportingInterruption(t *testing.T) {
	repository := workerRepository(t)
	rows, err := repository.ClaimTurns(t.Context(), time.Now(), time.Minute, 1)
	if err != nil || len(rows) != 1 {
		t.Fatalf("initial claim=%v %v", rows, err)
	}
	row := rows[0]
	row.Generation, row.Fence, row.ActivationCommandID = 1, "initial-fence", row.Command.Context.CommandID
	row.Status, row.NativeStarted, row.GrantUntil = "requires_action", true, time.Now().Add(time.Minute).UnixMilli()
	if err = repository.SaveTurn(t.Context(), row, nil, time.Now(), true); err != nil {
		t.Fatal(err)
	}
	control := runtimeprotocol.InteractionCommand{Context: row.Command.Context, Kind: "signal", Payload: map[string]any{
		"target_command_id": row.Command.Context.CommandID, "target_activation_command_id": row.ActivationCommandID, "expected_generation": 1,
		"answers": []any{map[string]any{"request_id": uuid.NewString(), "kind": "signal", "value": true}},
	}}
	control.Context.CommandID = "replaced-task-signal"
	if _, err = repository.AcceptSignal(t.Context(), control, time.Now()); err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var request runtimeprotocol.RuntimeExecutionRequest
		if err := runtimeprotocol.Decode(r.Body, &request); err != nil {
			t.Error(err)
			w.WriteHeader(400)
			return
		}
		_ = json.NewEncoder(w).Encode(runtimeprotocol.RuntimeExecutionGrant{Context: request.Context, ExecutionGeneration: request.ExecutionGeneration, ExecutionFence: request.ExecutionFence, ValidUntil: time.Now().Add(time.Minute).UnixMilli()})
	}))
	defer server.Close()
	client, err := NewClient(server.URL, "fixture")
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	backend := &workerBackendFixture{stepErr: ErrPermission}
	backend.stop = func(Turn) Progress {
		if backend.stops == 1 {
			return Progress{Status: "running", NativeStarted: true}
		}
		return Progress{Status: "cancelled", NativeStarted: true}
	}
	worker := &Worker{Repository: repository, Client: client, Backend: backend}
	for index := 0; index < 2; index++ {
		rows, err = repository.ClaimTurns(t.Context(), time.Now().Add(3*time.Second), time.Minute, 1)
		if err != nil || len(rows) != 1 {
			t.Fatalf("control claim=%v %v", rows, err)
		}
		if err = worker.Advance(t.Context(), rows[0]); err != nil {
			t.Fatal(err)
		}
		state, _ := repository.Turn(t.Context(), row.Command.Context.CommandID)
		expected := "running"
		if index == 1 {
			expected = "interrupted"
		}
		if state.Status != expected || !state.StopRequested || backend.steps != 1 || backend.stops != index+1 {
			t.Fatalf("state=%s steps=%d stops=%d", state.Status, backend.steps, backend.stops)
		}
	}
	facts, err := repository.ClaimFacts(t.Context(), time.Now(), time.Minute, 4)
	if err != nil || len(facts) != 1 || facts[0].Value.Type != "turn.interrupted" || facts[0].Value.Payload["native_stopped"] != true || facts[0].Value.ExecutionFence == nil {
		t.Fatalf("stop proof=%v %v", facts, err)
	}
}

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
	"errors"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
)

func TestRuntimeAgentServiceRepository(t *testing.T) {
	t.Run("memory", func(t *testing.T) { shared := &Memory{}; repositoryContract(t, []Repository{shared, shared}) })
	t.Run("postgres_replicas", func(t *testing.T) {
		dsn := os.Getenv("MANAGED_AGENTS_RUNTIME_INBOX_TEST_DSN")
		if dsn == "" {
			t.Skip("MANAGED_AGENTS_RUNTIME_INBOX_TEST_DSN is not configured")
		}
		ctx := t.Context()
		admin, err := pgxpool.New(ctx, dsn)
		if err != nil {
			t.Fatal(err)
		}
		defer admin.Close()
		schema := "runtime_portal_" + strings.ReplaceAll(uuid.NewString(), "-", "")
		if _, err = admin.Exec(ctx, `CREATE SCHEMA "`+schema+`"`); err != nil {
			t.Fatal(err)
		}
		defer func() { _, _ = admin.Exec(context.Background(), `DROP SCHEMA "`+schema+`" CASCADE`) }()
		replicas := []Repository{}
		for range 2 {
			cfg, err := pgxpool.ParseConfig(dsn)
			if err != nil {
				t.Fatal(err)
			}
			cfg.MaxConns = 4
			cfg.ConnConfig.RuntimeParams["search_path"] = schema
			pool, err := pgxpool.NewWithConfig(ctx, cfg)
			if err != nil {
				t.Fatal(err)
			}
			defer pool.Close()
			if len(replicas) == 0 {
				if _, err = pool.Exec(ctx, SchemaSQL); err != nil {
					t.Fatal(err)
				}
			}
			key, err := NewCipher("runtime-fixture-master-key-0123456789")
			if err != nil {
				t.Fatal(err)
			}
			replicas = append(replicas, &Postgres{Pool: pool, Cipher: key})
		}
		repositoryContract(t, replicas)
	})
}

func repositoryContract(t *testing.T, replicas []Repository) {
	t.Helper()
	ctx := t.Context()
	now := time.Now().UTC().Truncate(time.Millisecond)
	bootstrap := runtimeprotocol.RuntimeSessionBootstrap{Context: runtimeprotocol.Context{Version: 1, Tenant: "tenant", Namespace: "space", SessionID: "root", CommandID: "bootstrap:root", TraceID: "trace", Actor: map[string]any{"user": "owner"}, PermissionExpiresAt: now.Add(time.Minute).UnixMilli()}, RuntimeKind: "team", ConfigurationRevision: strings.Repeat("a", 64), Configuration: json.RawMessage(`{"session":{"id":"root","ownerId":"owner"}}`)}
	digest, _ := runtimeprotocol.BootstrapDigest(bootstrap)
	parallel(t, 16, func(i int) error { _, err := replicas[i%2].Bootstrap(ctx, bootstrap, digest, now); return err })
	replay := bootstrap
	replay.Context.PermissionExpiresAt = 1
	replay.Context.TraceID = "retry"
	if _, err := replicas[1].Bootstrap(ctx, replay, digest, now); err != nil {
		t.Fatal(err)
	}
	if _, err := replicas[1].Bootstrap(ctx, replay, strings.Repeat("b", 64), now); !errors.Is(err, ErrConflict) {
		t.Fatalf("configuration conflict=%v", err)
	}
	foreign := replay
	foreign.Context.SessionID = "foreign"
	foreign.Context.CommandID = "bootstrap:foreign"
	if _, err := replicas[0].Bootstrap(ctx, foreign, digest, now); !errors.Is(err, ErrPermission) {
		t.Fatalf("expired bootstrap=%v", err)
	}
	makeTurn := func(id string, sequence int64) runtimeprotocol.TurnCommand {
		c := bootstrap.Context
		c.CommandID = id
		c.TurnID = &id
		input := map[string]any{"message": "hello <世界>"}
		hash, _ := runtimeprotocol.Digest(input)
		return runtimeprotocol.TurnCommand{Context: c, IdempotencyKey: id, AdmissionSequence: sequence, InputDigest: hash, Input: input}
	}
	first := makeTurn("first", 1)
	firstDigest, _ := runtimeprotocol.TurnDigest(first)
	lifecycle := runtimeprotocol.RuntimeSessionLifecycle{Context: bootstrap.Context, LifecycleRevision: 2, Lifecycle: "archived"}
	lifecycle.Context.CommandID = "archive-root"
	parallel(t, 16, func(i int) error { _, err := replicas[i%2].ApplyLifecycle(ctx, lifecycle); return err })
	if _, err := replicas[0].AcceptTurn(ctx, first, firstDigest, now); !errors.Is(err, ErrConflict) {
		t.Fatalf("archived session admitted Turn=%v", err)
	}
	archive := lifecycle
	lifecycle.Context.CommandID, lifecycle.LifecycleRevision, lifecycle.Lifecycle = "restore-root", 3, "active"
	if _, err := replicas[1].ApplyLifecycle(ctx, lifecycle); err != nil {
		t.Fatal(err)
	}
	if receipt, err := replicas[0].ApplyLifecycle(ctx, archive); err != nil || receipt.Status != "superseded" {
		t.Fatalf("old lifecycle rewound state=%v %v", receipt, err)
	}
	digestLifecycle, _ := runtimeprotocol.Digest(lifecycle)
	if receipt, err := replicas[0].ApplyLifecycle(ctx, lifecycle); err != nil || receipt.PayloadDigest != digestLifecycle {
		t.Fatalf("mirror receipt digest=%v %v", receipt, err)
	}
	changedLifecycle := lifecycle
	changedLifecycle.Context.TraceID = "different"
	if _, err := replicas[1].ApplyLifecycle(ctx, changedLifecycle); !errors.Is(err, ErrConflict) {
		t.Fatalf("lifecycle revision conflict=%v", err)
	}
	changedLifecycle = lifecycle
	changedLifecycle.Context.Namespace = "foreign"
	changedLifecycle.LifecycleRevision = 4
	if _, err := replicas[1].ApplyLifecycle(ctx, changedLifecycle); !errors.Is(err, ErrNotFound) {
		t.Fatalf("foreign lifecycle applied=%v", err)
	}
	parallel(t, 16, func(i int) error { _, err := replicas[i%2].AcceptTurn(ctx, first, firstDigest, now); return err })
	second := makeTurn("second", 2)
	secondDigest, _ := runtimeprotocol.TurnDigest(second)
	if _, err := replicas[1].AcceptTurn(ctx, second, secondDigest, now); err != nil {
		t.Fatal(err)
	}
	if _, err := replicas[1].AcceptTurn(ctx, first, "changed", now); !errors.Is(err, ErrConflict) {
		t.Fatalf("Turn conflict=%v", err)
	}
	foreignTurn := first
	foreignTurn.Context.Namespace = "other"
	if _, err := replicas[1].AcceptTurn(ctx, foreignTurn, firstDigest, now); !errors.Is(err, ErrNotFound) {
		t.Fatalf("foreign Turn=%v", err)
	}
	claims, err := replicas[0].ClaimTurns(ctx, now, 30*time.Second, 128)
	if err != nil || len(claims) != 1 || claims[0].Command.Context.CommandID != "first" {
		t.Fatalf("Session serial claim=%+v %v", claims, err)
	}
	if other, err := replicas[1].ClaimTurns(ctx, now, time.Second, 128); err != nil || len(other) != 0 {
		t.Fatalf("claim duplicated=%+v %v", other, err)
	}
	row := claims[0]
	row.Generation = 1
	row.Fence = "fence"
	row.Status = "running"
	fact := func(id, kind string) runtimeprotocol.RuntimeFact {
		generation := "1"
		fence := "fence"
		return runtimeprotocol.RuntimeFact{Context: first.Context, Source: "orchestration", EventID: id, ExecutionID: &generation, ExecutionFence: &fence, Type: kind, Payload: map[string]any{"turn_id": "first"}}
	}
	if err := replicas[1].SaveTurn(ctx, row, []runtimeprotocol.RuntimeFact{fact("running", "turn.running")}, now.Add(31*time.Second), true); !errors.Is(err, ErrLease) {
		t.Fatalf("expired writer=%v", err)
	}
	invalid := fact("bad", "turn.running")
	invalid.Context.Actor = map[string]any{"user": "foreign"}
	if err := replicas[1].SaveTurn(ctx, row, []runtimeprotocol.RuntimeFact{invalid}, now, false); !errors.Is(err, ErrConflict) {
		t.Fatalf("foreign fact=%v", err)
	}
	a := fact("same", "item.completed")
	b := a
	b.Type = "tool.completed"
	if err := replicas[1].SaveTurn(ctx, row, []runtimeprotocol.RuntimeFact{a, b}, now, false); !errors.Is(err, ErrConflict) {
		t.Fatalf("mixed duplicate batch=%v", err)
	}
	if err := replicas[1].SaveTurn(ctx, row, []runtimeprotocol.RuntimeFact{fact("running", "turn.running"), a}, now, false); err != nil {
		t.Fatal(err)
	}
	// Repeating a previously committed event does not create another source position.
	if err := replicas[0].SaveTurn(ctx, row, []runtimeprotocol.RuntimeFact{a}, now, false); err != nil {
		t.Fatal(err)
	}
	row.Status = "completed"
	if err := replicas[1].SaveTurn(ctx, row, []runtimeprotocol.RuntimeFact{fact("done", "turn.completed")}, now, true); err != nil {
		t.Fatal(err)
	}
	session, err := replicas[0].Session(ctx, "root")
	if err != nil || session.LastSequence != 3 {
		t.Fatalf("atomic positions=%+v %v", session, err)
	}
	claims, err = replicas[0].ClaimTurns(ctx, now, time.Second, 1)
	if err != nil || len(claims) != 1 || claims[0].Command.Context.CommandID != "second" {
		t.Fatalf("successor claim=%+v %v", claims, err)
	}
	for sequence := int64(1); sequence <= 3; sequence++ {
		batch, err := replicas[0].ClaimFacts(ctx, now, 30*time.Second, 128)
		if err != nil || len(batch) != 1 || batch[0].Value.SourceSequence != sequence {
			t.Fatalf("ordered facts=%+v %v", batch, err)
		}
		if extra, err := replicas[1].ClaimFacts(ctx, now, time.Second, 128); err != nil || len(extra) != 0 {
			t.Fatalf("overtaking fact=%+v %v", extra, err)
		}
		if err = replicas[1].AcknowledgeFact(ctx, "root", sequence, "wrong", now); !errors.Is(err, ErrLease) {
			t.Fatalf("unfenced acknowledgement=%v", err)
		}
		for range 2 {
			if err = replicas[1].AcknowledgeFact(ctx, "root", sequence, batch[0].ClaimToken, now); err != nil {
				t.Fatal(err)
			}
		}
	}
	if extra, err := replicas[1].ClaimFacts(ctx, now, time.Second, 128); err != nil || len(extra) != 0 {
		t.Fatalf("delivered fact replay=%+v %v", extra, err)
	}
	// Cancellation revokes a step's claim and stores its receipt atomically.
	old := claims[0]
	command := runtimeprotocol.InteractionCommand{Context: second.Context, Kind: "cancel", Payload: map[string]any{
		"target_command_id": "second", "expected_generation": 0, "target_activation_command_id": "second"}}
	command.Context.CommandID = "cancel-second"
	parallel(t, 16, func(i int) error { _, err := replicas[i%2].AcceptCancellation(ctx, command, now); return err })
	if err = replicas[0].SaveTurn(ctx, old, nil, now, true); !errors.Is(err, ErrLease) {
		t.Fatalf("cancelled claim committed stale work=%v", err)
	}
	command.Context.PermissionExpiresAt = 1
	if _, err = replicas[0].AcceptCancellation(ctx, command, now); err != nil {
		t.Fatalf("lost cancellation ACK could not replay=%v", err)
	}
	if _, err = replicas[1].Interaction(ctx, "other-session", "cancel-second"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("foreign receipt=%v", err)
	}
	command.Payload["expected_generation"] = 1
	if _, err = replicas[0].AcceptCancellation(ctx, command, now); !errors.Is(err, ErrConflict) {
		t.Fatalf("changed cancellation replay=%v", err)
	}
	stopped, err := replicas[1].ClaimTurns(ctx, now, time.Second, 1)
	if err != nil || len(stopped) != 1 || !stopped[0].StopRequested || stopped[0].StopCommandID != "cancel-second" || stopped[0].Status != "cancel_requested" {
		t.Fatalf("durable cancellation did not wake stop worker=%+v %v", stopped, err)
	}
	lifecycle.Context.CommandID, lifecycle.LifecycleRevision, lifecycle.Lifecycle = "delete-root", 4, "deleted"
	if _, err = replicas[0].ApplyLifecycle(ctx, lifecycle); err != nil {
		t.Fatal(err)
	}
	lifecycle.Context.CommandID, lifecycle.LifecycleRevision, lifecycle.Lifecycle = "restore-deleted", 5, "active"
	if _, err = replicas[1].ApplyLifecycle(ctx, lifecycle); !errors.Is(err, ErrConflict) {
		t.Fatalf("deleted session restored=%v", err)
	}
	approvalRepositoryContract(t, replicas, now)
}

func approvalRepositoryContract(t *testing.T, replicas []Repository, now time.Time) {
	t.Helper()
	ctx := t.Context()
	id := "approval-turn"
	context := runtimeprotocol.Context{Version: 1, Tenant: "tenant", Namespace: "space", SessionID: "approval-root", CommandID: "bootstrap-approval",
		TraceID: "approval-fixture", Actor: map[string]any{"user": "owner", "key_hash": "", "internal": false}, PermissionExpiresAt: now.Add(time.Minute).UnixMilli()}
	bootstrap := runtimeprotocol.RuntimeSessionBootstrap{Context: context, RuntimeKind: "hosted-runtime", ConfigurationRevision: strings.Repeat("b", 64),
		Configuration: json.RawMessage(`{"session":{"id":"approval-root","ownerId":"owner"}}`)}
	digest, _ := runtimeprotocol.BootstrapDigest(bootstrap)
	if _, err := replicas[0].Bootstrap(ctx, bootstrap, digest, now); err != nil {
		t.Fatal(err)
	}
	context.CommandID, context.TurnID = id, &id
	input := map[string]any{"message": "request tool approval"}
	inputDigest, _ := runtimeprotocol.Digest(input)
	turn := runtimeprotocol.TurnCommand{Context: context, AdmissionSequence: 1, IdempotencyKey: id, InputDigest: inputDigest, Input: input}
	digest, _ = runtimeprotocol.TurnDigest(turn)
	if _, err := replicas[0].AcceptTurn(ctx, turn, digest, now); err != nil {
		t.Fatal(err)
	}
	rows, err := replicas[0].ClaimTurns(ctx, now, 30*time.Second, 128)
	if err != nil || len(rows) != 1 || rows[0].Command.Context.CommandID != id {
		t.Fatalf("approval claim=%v %v", rows, err)
	}
	old := rows[0]
	old.Status, old.Generation, old.Fence, old.ActivationCommandID = "requires_action", 1, "old-fence", id
	old.GrantUntil, old.NativeStarted = now.Add(time.Minute).UnixMilli(), true
	if err = replicas[0].SaveTurn(ctx, old, nil, now, false); err != nil {
		t.Fatal(err)
	}
	command := runtimeprotocol.InteractionCommand{Context: context, Kind: "approval", Payload: map[string]any{
		"target_command_id": id, "expected_generation": 1, "target_activation_command_id": id,
		"answers": []any{map[string]any{"request_id": uuid.NewString(), "kind": "confirmation", "allow": true}}}}
	command.Context.CommandID = "approve-one"
	parallel(t, 16, func(i int) error { _, err := replicas[i%2].AcceptApproval(ctx, command, now); return err })
	queued, err := replicas[1].Turn(ctx, id)
	if err != nil || queued.Generation != 2 || queued.Fence == old.Fence || queued.GrantUntil != 0 || queued.ActivationCommandID != command.Context.CommandID ||
		queued.ControlCommandID != command.Context.CommandID || queued.Status != "queued" || queued.ClaimToken != "" {
		t.Fatalf("approval did not fence old work before renewal=%v %v", queued, err)
	}
	if err = replicas[1].SaveTurn(ctx, old, nil, now, true); !errors.Is(err, ErrLease) {
		t.Fatalf("approval accepted stale step writes=%v", err)
	}
	command.Context.PermissionExpiresAt = 1
	if _, err = replicas[1].AcceptApproval(ctx, command, now); err != nil {
		t.Fatalf("lost approval ACK could not replay=%v", err)
	}
	changed := copyJSON(command)
	changed.Payload["answers"].([]any)[0].(map[string]any)["allow"] = false
	if _, err = replicas[0].AcceptApproval(ctx, changed, now); !errors.Is(err, ErrConflict) {
		t.Fatalf("changed approval replay=%v", err)
	}
	claims, err := replicas[0].ClaimTurns(ctx, now, 30*time.Second, 1)
	if err != nil || len(claims) != 1 {
		t.Fatalf("approval continuation claim=%v %v", claims, err)
	}
	resumed := claims[0]
	resumed.ControlCommandID, resumed.ControlAnswerIndex, resumed.Status = "", 0, "requires_action"
	resumed.GrantUntil = now.Add(time.Minute).UnixMilli()
	if err = replicas[0].SaveTurn(ctx, resumed, nil, now, true); err != nil {
		t.Fatal(err)
	}
	second := copyJSON(command)
	second.Context.CommandID, second.Context.PermissionExpiresAt = "approve-two", context.PermissionExpiresAt
	second.Payload["expected_generation"], second.Payload["target_activation_command_id"] = int64(2), "approve-one"
	second.Payload["answers"].([]any)[0].(map[string]any)["request_id"] = uuid.NewString()
	if _, err = replicas[1].AcceptApproval(ctx, second, now); err != nil {
		t.Fatal(err)
	}
	claims, err = replicas[1].ClaimTurns(ctx, now, 30*time.Second, 1)
	if err != nil || len(claims) != 1 {
		t.Fatalf("second activation claim=%v %v", claims, err)
	}
	latest := claims[0]
	generation := "2"
	fact := runtimeprotocol.RuntimeFact{Context: turn.Context, Source: "orchestration", EventID: "late-approval-one", Type: "required_action.resolved",
		ExecutionID: &generation, ExecutionFence: &resumed.Fence, Payload: map[string]any{"activation_command_id": "approve-one", "request_id": command.Payload["answers"].([]any)[0].(map[string]any)["request_id"], "kind": "confirmation", "resolution_status": "approved"}}
	forged := copyJSON(fact)
	forged.Payload["request_id"] = uuid.NewString()
	if err = replicas[0].SaveTurn(ctx, latest, []runtimeprotocol.RuntimeFact{forged}, now, false); !errors.Is(err, ErrConflict) {
		t.Fatalf("unrelated historical action=%v", err)
	}
	if err = replicas[0].SaveTurn(ctx, latest, []runtimeprotocol.RuntimeFact{fact}, now, true); err != nil {
		t.Fatalf("delayed approval proof rejected after next activation=%v", err)
	}
	stop := runtimeprotocol.InteractionCommand{Context: context, Kind: "cancel", Payload: map[string]any{
		"target_command_id": id, "expected_generation": 3, "target_activation_command_id": second.Context.CommandID}}
	stop.Context.CommandID = "cancel-approved-turn"
	if _, err = replicas[1].AcceptCancellation(ctx, stop, now); err != nil {
		t.Fatal(err)
	}
	command.Context.CommandID, command.Context.PermissionExpiresAt = "approve-after-cancel", context.PermissionExpiresAt
	command.Payload["expected_generation"], command.Payload["target_activation_command_id"] = 3, "approve-two"
	if _, err = replicas[0].AcceptApproval(ctx, command, now); !errors.Is(err, ErrConflict) {
		t.Fatalf("approval revoked accepted cancellation=%v", err)
	}
}

func parallel(t *testing.T, count int, operation func(int) error) {
	t.Helper()
	failures := make(chan error, count)
	var group sync.WaitGroup
	for i := 0; i < count; i++ {
		group.Add(1)
		go func(index int) { defer group.Done(); failures <- operation(index) }(i)
	}
	group.Wait()
	close(failures)
	for err := range failures {
		if err != nil {
			t.Fatal(err)
		}
	}
}

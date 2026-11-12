// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package storetest

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"testing"

	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

func testManagedTurnBinding(t *testing.T, ctx context.Context, st store.Store) {
	newAttempt := func(kind model.DataPlaneKind) (*model.AgentTask, *model.ExecutionAttempt) {
		t.Helper()
		issue, err := st.Collaboration().CreateIssue(ctx, &model.Issue{Tenant: "native-binding-" + uuid.NewString(),
			Namespace: "n", Title: "native receipt", Creator: model.Actor{Type: model.ActorHuman, Ref: "owner"}})
		if err != nil {
			t.Fatal(err)
		}
		_, task, err := st.Collaboration().AssignIssue(ctx, issue.ID, issue.Version, model.AssigneeAgent, "worker", issue.Creator)
		if err != nil {
			t.Fatal(err)
		}
		session := uuid.NewString()
		task, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
			store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version, SessionID: session, RuntimeBinding: json.RawMessage(`{}`)},
			&model.ExecutionAttempt{BackendKind: kind, State: model.ExecutionAssigned, SessionID: session})
		if err != nil {
			t.Fatal(err)
		}
		return task, attempt
	}
	bind := func(attempt *model.ExecutionAttempt, turn string) store.ManagedTurnBinding {
		return store.ManagedTurnBinding{AttemptID: attempt.ID, DispatchGeneration: attempt.DispatchGeneration, SessionID: attempt.SessionID, TurnID: turn}
	}

	t.Run("concurrent receipts have one immutable winner without a lease", func(t *testing.T) {
		_, attempt := newAttempt(model.DataPlaneManaged)
		var wg sync.WaitGroup
		results := make(chan *model.ExecutionAttempt, 2)
		errorsFound := make(chan error, 2)
		for _, turn := range []string{"turn-one", "turn-two"} {
			wg.Add(1)
			go func(turn string) {
				defer wg.Done()
				value, err := st.ExecutionAttempts().BindManagedTurn(ctx, bind(attempt, turn))
				results <- value
				errorsFound <- err
			}(turn)
		}
		wg.Wait()
		close(results)
		close(errorsFound)
		wins, conflicts := 0, 0
		for err := range errorsFound {
			if err == nil {
				wins++
			} else if errors.Is(err, store.ErrConflict) {
				conflicts++
			} else {
				t.Fatal(err)
			}
		}
		if wins != 1 || conflicts != 1 {
			t.Fatalf("receipt identities were replaced: wins=%d conflicts=%d", wins, conflicts)
		}
		var winner *model.ExecutionAttempt
		for value := range results {
			if value != nil {
				winner = value
			}
		}
		repeated, err := st.ExecutionAttempts().BindManagedTurn(ctx, bind(attempt, winner.TurnID))
		if err != nil || repeated.Version != winner.Version || repeated.State != model.ExecutionAssigned ||
			repeated.LeaseExpiresAt != nil || repeated.HeartbeatAt != nil || repeated.LeaseToken != "" {
			t.Fatalf("retry changed receipt or established liveness: %+v %v", repeated, err)
		}
		wrong := bind(attempt, winner.TurnID)
		wrong.DispatchGeneration++
		if _, err := st.ExecutionAttempts().BindManagedTurn(ctx, wrong); !errors.Is(err, store.ErrConflict) {
			t.Fatalf("wrong generation accepted: %v", err)
		}
		wrong = bind(attempt, winner.TurnID)
		wrong.SessionID = uuid.NewString()
		if _, err := st.ExecutionAttempts().BindManagedTurn(ctx, wrong); !errors.Is(err, store.ErrConflict) {
			t.Fatalf("foreign Session accepted: %v", err)
		}
	})

	t.Run("cancelled or replaced attempts reject delayed admission", func(t *testing.T) {
		task, attempt := newAttempt(model.DataPlaneManaged)
		queued, _, err := st.Collaboration().RequeueAgentTaskAfterAttemptFailure(ctx, task.ID,
			store.TaskFailure{ExpectedVersion: task.Version, AttemptID: attempt.ID, DispatchGeneration: attempt.DispatchGeneration,
				Code: "managed_admission_failed", Message: "retry fixture"})
		if err != nil {
			t.Fatal(err)
		}
		replacementSession := uuid.NewString()
		_, replacement, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
			store.TaskClaim{TaskID: queued.ID, ExpectedVersion: queued.Version, SessionID: replacementSession, RuntimeBinding: json.RawMessage(`{}`)},
			&model.ExecutionAttempt{BackendKind: model.DataPlaneManaged, State: model.ExecutionAssigned, SessionID: replacementSession})
		if err != nil {
			t.Fatal(err)
		}
		if _, err := st.ExecutionAttempts().BindManagedTurn(ctx, bind(attempt, "late-turn")); !errors.Is(err, store.ErrConflict) {
			t.Fatalf("replaced attempt accepted receipt: %v", err)
		}
		cancelled, err := st.ExecutionAttempts().Cancel(ctx, replacement.ID, replacement.Version)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := st.ExecutionAttempts().BindManagedTurn(ctx, bind(cancelled, "late-turn")); !errors.Is(err, store.ErrConflict) {
			t.Fatalf("cancel-requested attempt accepted receipt: %v", err)
		}
	})

	t.Run("other backends cannot claim a Managed receipt", func(t *testing.T) {
		_, attempt := newAttempt(model.DataPlaneExternalApplication)
		if _, err := st.ExecutionAttempts().BindManagedTurn(ctx, bind(attempt, "turn")); !errors.Is(err, store.ErrConflict) {
			t.Fatalf("external backend accepted Managed receipt: %v", err)
		}
	})

	t.Run("native terminal fact can precede its HTTP receipt", func(t *testing.T) {
		task, attempt := newAttempt(model.DataPlaneManaged)
		_, failed, err := st.Collaboration().FailAgentTaskWithAttempt(ctx, task.ID, store.TaskFailure{
			ExpectedVersion: task.Version, AttemptID: attempt.ID, DispatchGeneration: attempt.DispatchGeneration,
			Code: "managed_turn_failed", Message: "native terminal committed before HTTP receipt"})
		if err != nil {
			t.Fatal(err)
		}
		bound, err := st.ExecutionAttempts().BindManagedTurn(ctx, bind(attempt, "already-finished-turn"))
		if err != nil || bound.State != model.ExecutionFailed || bound.FailureCode != failed.FailureCode || bound.LeaseExpiresAt != nil || bound.HeartbeatAt != nil {
			t.Fatalf("late receipt resurrected or lost terminal execution: %+v %v", bound, err)
		}
		repeated, err := st.ExecutionAttempts().BindManagedTurn(ctx, bind(attempt, bound.TurnID))
		if err != nil || repeated.Version != bound.Version {
			t.Fatalf("late receipt retry changed terminal metadata: %+v %v", repeated, err)
		}
	})

	t.Run("native start projects running without a heartbeat lease", func(t *testing.T) {
		task, attempt := newAttempt(model.DataPlaneManaged)
		if _, err := st.Collaboration().StartAgentTask(ctx, task.ID, task.Version); err != nil {
			t.Fatal(err)
		}
		current, err := st.ExecutionAttempts().Get(ctx, attempt.ID)
		if err != nil || current.State != model.ExecutionRunning || current.StartedAt == nil || current.HeartbeatAt != nil || current.LeaseExpiresAt != nil || current.LeaseToken != "" {
			t.Fatalf("native start created control-plane liveness: %+v %v", current, err)
		}
	})
}

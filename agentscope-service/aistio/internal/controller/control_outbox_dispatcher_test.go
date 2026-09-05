// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package controller

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

type testControlHandler struct{ failures int }

func (h *testControlHandler) HandleControlEvent(context.Context, *controlmodel.OutboxEvent) error {
	if h.failures > 0 {
		h.failures--
		return errors.New("retry me")
	}
	return nil
}

type deferredControlHandler struct{ failures int }

func (h *deferredControlHandler) HandleControlEvent(context.Context, *controlmodel.OutboxEvent) error {
	if h.failures > 0 {
		h.failures--
		return fmt.Errorf("%w: runtime capacity unavailable", ErrControlEventDeferred)
	}
	return nil
}

func TestCollaborationOutboxDispatchesQueuedAgentTaskExactlyOnce(t *testing.T) {
	ctx := context.Background()
	st, err := memory.Open(ctx, store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "tenant", Namespace: "default",
		Title: "auto dispatch", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker"})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("task setup: %+v %v", tasks, err)
	}
	calls := 0
	handler := &CollaborationOutboxHandler{Store: st, DispatchAgentTask: func(_ context.Context, id uuid.UUID) error {
		calls++
		current, loadErr := st.Collaboration().GetAgentTask(ctx, id)
		if loadErr != nil {
			return loadErr
		}
		_, claimErr := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: id, ExpectedVersion: current.Version, SessionID: "runtime-session"})
		return claimErr
	}}
	events, err := st.Outbox().Claim(ctx, "inspect", time.Now().UTC().Add(time.Second), time.Minute, 20)
	if err != nil {
		t.Fatal(err)
	}
	var event *controlmodel.OutboxEvent
	for _, candidate := range events {
		if candidate.EventType == "agent-task.queued.v1" && candidate.AggregateID == tasks[0].ID.String() {
			event = candidate
			break
		}
	}
	if event == nil {
		t.Fatalf("AgentTask queued event missing: %+v", events)
	}
	if err := handler.HandleControlEvent(ctx, event); err != nil {
		t.Fatal(err)
	}
	if err := handler.HandleControlEvent(ctx, event); err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatalf("dispatch calls=%d, want 1", calls)
	}
	dispatched, err := st.Collaboration().GetAgentTask(ctx, tasks[0].ID)
	if err != nil || dispatched.Status != controlmodel.AgentTaskDispatched {
		t.Fatalf("task=%+v err=%v", dispatched, err)
	}
}

func TestControlOutboxDispatcherRetriesThenDelivers(t *testing.T) {
	ctx := context.Background()
	st, err := memory.Open(ctx, store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	event, err := st.Outbox().Enqueue(ctx, &controlmodel.OutboxEvent{
		Tenant: "default", AggregateType: "agent-task", AggregateID: "task-1",
		EventType: "agent-task.queued.v1",
	})
	if err != nil {
		t.Fatal(err)
	}
	dispatcher := &ControlOutboxDispatcher{Store: st,
		Handler: &testControlHandler{failures: 1}, WorkerID: "test", MaxBackoff: time.Millisecond}
	now := time.Now().UTC()
	dispatcher.DispatchOnce(ctx, now)
	dispatcher.DispatchOnce(ctx, now.Add(2*time.Millisecond))
	claimed, err := st.Outbox().Claim(ctx, "inspect", now.Add(time.Second), time.Second, 10)
	if err != nil || len(claimed) != 0 {
		t.Fatalf("event %s was not delivered: claimed=%d err=%v", event.ID, len(claimed), err)
	}
}

func TestDeferredQueuedTaskNeverDeadLettersAndEventuallyDispatches(t *testing.T) {
	ctx := context.Background()
	st, err := memory.Open(ctx, store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	event, err := st.Outbox().Enqueue(ctx, &controlmodel.OutboxEvent{Tenant: "default",
		AggregateType: "agent-task", AggregateID: "task-1", EventType: "agent-task.queued.v1"})
	if err != nil {
		t.Fatal(err)
	}
	handler := &deferredControlHandler{failures: 15}
	dispatcher := &ControlOutboxDispatcher{Store: st, Handler: handler, WorkerID: "deferred", MaxBackoff: time.Millisecond}
	now := time.Now().UTC()
	for i := 0; i < 15; i++ {
		dispatcher.DispatchOnce(ctx, now.Add(time.Duration(i)*2*time.Millisecond))
	}
	dead, err := st.Outbox().ListDeadLetters(ctx, "default", "", 10)
	if err != nil || len(dead) != 0 {
		t.Fatalf("deferred event was dead-lettered: events=%+v err=%v", dead, err)
	}
	dispatcher.DispatchOnce(ctx, now.Add(time.Second))
	claimed, err := st.Outbox().Claim(ctx, "inspect", now.Add(2*time.Second), time.Second, 10)
	if err != nil || len(claimed) != 0 {
		t.Fatalf("event %s did not deliver after recovery: claimed=%+v err=%v", event.ID, claimed, err)
	}
}

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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package controller

import (
	"context"
	"encoding/json"
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

type testPermanentDispatchError struct{}

func (testPermanentDispatchError) Error() string { return "runtime candidates exhausted" }

func (testPermanentDispatchError) DispatchFailureCode() string {
	return "runtime_candidates_exhausted"
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

func TestCollaborationOutboxDispatchesApprovalDecision(t *testing.T) {
	ctx := context.Background()
	st, err := memory.Open(ctx, store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	approval, err := st.Collaboration().CreateApproval(ctx, &controlmodel.Approval{
		Tenant: "tenant", Namespace: "default", TargetType: "issue", TargetRef: uuid.NewString(),
		RequestedBy: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "worker"},
		ApproverRef: "owner", Status: controlmodel.ApprovalPending,
	})
	if err != nil {
		t.Fatal(err)
	}
	approval, err = st.Collaboration().DecideApproval(ctx, approval.ID, approval.Version,
		controlmodel.ApprovalApproved, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	events, err := st.Outbox().Claim(ctx, "approval-worker", time.Now().UTC().Add(time.Second), time.Minute, 20)
	if err != nil {
		t.Fatal(err)
	}
	var decided *controlmodel.OutboxEvent
	for _, event := range events {
		if event.EventType == "approval.decided.v1" && event.AggregateID == approval.ID.String() {
			decided = event
			break
		}
	}
	if decided == nil {
		t.Fatalf("approval decision outbox event missing: %+v", events)
	}
	calls := 0
	handler := &CollaborationOutboxHandler{Store: st,
		DispatchApprovalDecision: func(_ context.Context, id uuid.UUID) error {
			calls++
			if id != approval.ID {
				t.Fatalf("approval id=%s want=%s", id, approval.ID)
			}
			return nil
		}}
	if err = handler.HandleControlEvent(ctx, decided); err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatalf("decision callback calls=%d", calls)
	}
}

func TestCollaborationOutboxDispatchesManagedAttemptAbort(t *testing.T) {
	attemptID := uuid.New()
	calls := 0
	handler := &CollaborationOutboxHandler{DispatchManagedAbort: func(_ context.Context, id uuid.UUID) error {
		calls++
		if id != attemptID {
			t.Fatalf("attempt id=%s want=%s", id, attemptID)
		}
		return nil
	}}
	if err := handler.HandleControlEvent(context.Background(), &controlmodel.OutboxEvent{
		AggregateType: "execution-attempt", AggregateID: attemptID.String(),
		EventType: "execution-attempt.abort-managed.v1",
	}); err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatalf("managed abort calls=%d", calls)
	}
}

func TestQueuedAgentTaskDispatchFailureRecordsOneDurableDiagnostic(t *testing.T) {
	ctx := context.Background()
	st, err := memory.Open(ctx, store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "tenant", Namespace: "default",
		Title: "blocked dispatch", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker"})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("task setup: %+v %v", tasks, err)
	}
	task := tasks[0]
	event := &controlmodel.OutboxEvent{AggregateType: "agent-task", AggregateID: task.ID.String(),
		EventType: "agent-task.queued.v1"}
	handler := &CollaborationOutboxHandler{Store: st, DispatchAgentTask: func(context.Context, uuid.UUID) error {
		return errors.New("no environment available for owner admin")
	}}
	for i := 0; i < 2; i++ {
		if err = handler.HandleControlEvent(ctx, event); !errors.Is(err, ErrControlEventDeferred) {
			t.Fatalf("dispatch error=%v, want deferred", err)
		}
	}
	events, err := st.Orchestration().ListRunEvents(ctx, task.OrchestrationRunID, 0, 20)
	if err != nil {
		t.Fatal(err)
	}
	var diagnostics []*controlmodel.RunEvent
	for _, candidate := range events {
		if candidate.Type == "agent-task.dispatch_deferred" {
			diagnostics = append(diagnostics, candidate)
		}
	}
	if len(diagnostics) != 1 {
		t.Fatalf("dispatch diagnostics=%d, want one: %+v", len(diagnostics), events)
	}
	if diagnostics[0].AgentTaskID == nil || *diagnostics[0].AgentTaskID != task.ID {
		t.Fatalf("diagnostic task=%v, want %s", diagnostics[0].AgentTaskID, task.ID)
	}
	var payload map[string]any
	if err = json.Unmarshal(diagnostics[0].Payload, &payload); err != nil {
		t.Fatal(err)
	}
	if payload["message"] != "no environment available for owner admin" || payload["retryable"] != true {
		t.Fatalf("unexpected diagnostic payload: %+v", payload)
	}
}

func TestQueuedAgentTaskPermanentDispatchFailureTerminatesTask(t *testing.T) {
	ctx := context.Background()
	st, err := memory.Open(ctx, store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant", Namespace: "default", Title: "exhausted dispatch",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker",
	})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("task setup: %+v %v", tasks, err)
	}
	event := &controlmodel.OutboxEvent{AggregateType: "agent-task", AggregateID: tasks[0].ID.String(),
		EventType: "agent-task.queued.v1", LastError: "previous retryable failure"}
	handler := &CollaborationOutboxHandler{Store: st,
		DispatchAgentTask: func(context.Context, uuid.UUID) error { return testPermanentDispatchError{} }}
	if err = handler.HandleControlEvent(ctx, event); err != nil {
		t.Fatal(err)
	}
	failed, err := st.Collaboration().GetAgentTask(ctx, tasks[0].ID)
	if err != nil || failed.Status != controlmodel.AgentTaskFailed ||
		failed.ErrorCode != "runtime_candidates_exhausted" || failed.ErrorMessage != "runtime candidates exhausted" {
		t.Fatalf("permanent dispatch failure did not terminate task: task=%+v err=%v", failed, err)
	}
	if err = handler.HandleControlEvent(ctx, event); err != nil {
		t.Fatalf("terminal redelivery must be idempotent: %v", err)
	}
	events, err := st.Orchestration().ListRunEvents(ctx, failed.OrchestrationRunID, 0, 20)
	if err != nil {
		t.Fatal(err)
	}
	for _, candidate := range events {
		if candidate.Type == "agent-task.dispatch_recovered" {
			t.Fatalf("terminal failure must not be reported as recovered: %+v", candidate)
		}
	}
}

func TestQueuedAgentTaskDispatchRecoveryRetainsPreviousError(t *testing.T) {
	ctx := context.Background()
	st, err := memory.Open(ctx, store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "tenant", Namespace: "default",
		Title: "recover dispatch", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker"})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("task setup: %+v %v", tasks, err)
	}
	task := tasks[0]
	event := &controlmodel.OutboxEvent{AggregateType: "agent-task", AggregateID: task.ID.String(),
		EventType: "agent-task.queued.v1", Attempts: 113,
		LastError: "control event delivery deferred: no environment available for owner admin"}
	handler := &CollaborationOutboxHandler{Store: st, DispatchAgentTask: func(_ context.Context, id uuid.UUID) error {
		current, loadErr := st.Collaboration().GetAgentTask(ctx, id)
		if loadErr != nil {
			return loadErr
		}
		_, claimErr := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{
			TaskID: id, ExpectedVersion: current.Version, SessionID: "recovered-session",
		})
		return claimErr
	}}
	if err = handler.HandleControlEvent(ctx, event); err != nil {
		t.Fatal(err)
	}
	if err = handler.HandleControlEvent(ctx, event); err != nil {
		t.Fatal(err)
	}
	events, err := st.Orchestration().ListRunEvents(ctx, task.OrchestrationRunID, 0, 20)
	if err != nil {
		t.Fatal(err)
	}
	var recovered *controlmodel.RunEvent
	recoveryCount := 0
	for _, candidate := range events {
		if candidate.Type == "agent-task.dispatch_recovered" {
			recovered = candidate
			recoveryCount++
		}
	}
	if recovered == nil || recoveryCount != 1 {
		t.Fatalf("dispatch recovery diagnostics=%d, want one: %+v", recoveryCount, events)
	}
	var payload map[string]any
	if err = json.Unmarshal(recovered.Payload, &payload); err != nil {
		t.Fatal(err)
	}
	if payload["attempts"] != float64(113) || payload["previousError"] != event.LastError {
		t.Fatalf("unexpected recovery payload: %+v", payload)
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

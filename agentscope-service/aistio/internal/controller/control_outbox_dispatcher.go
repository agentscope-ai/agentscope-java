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
	"time"

	"github.com/google/uuid"
	"sigs.k8s.io/controller-runtime/pkg/log"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type ControlEventHandler interface {
	HandleControlEvent(context.Context, *controlmodel.OutboxEvent) error
}

var ErrControlEventDeferred = errors.New("control event delivery deferred")

type permanentDispatchFailure interface {
	error
	DispatchFailureCode() string
}

// ControlOutboxDispatcher provides durable at-least-once delivery. All
// replicas may run it because Claim uses SKIP LOCKED and a worker lease.
type ControlOutboxDispatcher struct {
	Store      store.Store
	Handler    ControlEventHandler
	WorkerID   string
	Interval   time.Duration
	ClaimTTL   time.Duration
	Batch      int
	MaxBackoff time.Duration
}

func (d *ControlOutboxDispatcher) Start(ctx context.Context) error {
	if d.Store == nil || d.Handler == nil {
		return errors.New("control outbox store and handler are required")
	}
	if d.WorkerID == "" {
		d.WorkerID = "outbox-" + uuid.NewString()
	}
	interval := d.Interval
	if interval <= 0 {
		interval = time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			d.DispatchOnce(ctx, time.Now().UTC())
		}
	}
}

func (d *ControlOutboxDispatcher) NeedLeaderElection() bool { return false }

func (d *ControlOutboxDispatcher) DispatchOnce(ctx context.Context, now time.Time) {
	lease := d.ClaimTTL
	if lease <= 0 {
		lease = 30 * time.Second
	}
	batch := d.Batch
	if batch <= 0 {
		batch = 100
	}
	events, err := d.Store.Outbox().Claim(ctx, d.WorkerID, now, lease, batch)
	if err != nil {
		log.FromContext(ctx).Error(err, "claiming control outbox")
		return
	}
	for _, event := range events {
		if err := d.Handler.HandleControlEvent(ctx, event); err != nil {
			retryAt := now.Add(d.retryBackoff(event.Attempts))
			var markErr error
			if errors.Is(err, ErrControlEventDeferred) {
				markErr = d.Store.Outbox().MarkDeferred(ctx, event.ID, d.WorkerID, err.Error(), retryAt)
			} else {
				markErr = d.Store.Outbox().MarkFailed(ctx, event.ID, d.WorkerID, err.Error(), retryAt)
			}
			if markErr != nil {
				log.FromContext(ctx).Error(markErr, "marking control event failed", "event", event.ID)
			}
			continue
		}
		if err := d.Store.Outbox().MarkDelivered(ctx, event.ID, d.WorkerID); err != nil {
			log.FromContext(ctx).Error(err, "marking control event delivered", "event", event.ID)
		}
	}
}

func (d *ControlOutboxDispatcher) retryBackoff(attempt int32) time.Duration {
	backoff := time.Second
	for i := int32(1); i < attempt && backoff < time.Minute; i++ {
		backoff *= 2
	}
	max := d.MaxBackoff
	if max <= 0 {
		max = time.Minute
	}
	if backoff > max {
		return max
	}
	return backoff
}

type CollaborationEventSink interface {
	PublishCollaborationEvent(context.Context, *controlmodel.OutboxEvent) error
}

// CollaborationOutboxHandler publishes versioned Issue-domain events to an
// optional websocket/cache-invalidation sink. Durable Activity remains the
// audit truth even when no realtime sink is configured.
type CollaborationOutboxHandler struct {
	Store                    store.Store
	Sink                     CollaborationEventSink
	DispatchAgentTask        func(context.Context, uuid.UUID) error
	DispatchApprovalDecision func(context.Context, uuid.UUID) error
	DispatchManagedAbort     func(context.Context, uuid.UUID) error
	ReconcileRun             func(context.Context, uuid.UUID) error
}

func (h *CollaborationOutboxHandler) HandleControlEvent(ctx context.Context, event *controlmodel.OutboxEvent) error {
	if event == nil || h == nil {
		return nil
	}
	if event.EventType == "agent-task.queued.v1" {
		if h.Store == nil || h.DispatchAgentTask == nil {
			return errors.New("AgentTask dispatcher is unavailable")
		}
		taskID, err := uuid.Parse(event.AggregateID)
		if err != nil {
			return fmt.Errorf("invalid AgentTask aggregate ID %q: %w", event.AggregateID, err)
		}
		task, err := h.Store.Collaboration().GetAgentTask(ctx, taskID)
		if err != nil {
			return err
		}
		// Redelivery after a successful dispatch is expected. Persisted task
		// state is the idempotency fence, so a second runtime is never started.
		if task.Status == controlmodel.AgentTaskQueued {
			if err := h.DispatchAgentTask(ctx, taskID); err != nil {
				current, loadErr := h.Store.Collaboration().GetAgentTask(ctx, taskID)
				if loadErr == nil && current.Status == controlmodel.AgentTaskQueued {
					var permanent permanentDispatchFailure
					if errors.As(err, &permanent) {
						code := permanent.DispatchFailureCode()
						if code == "" {
							code = "runtime_dispatch_exhausted"
						}
						_, failErr := h.Store.Collaboration().FailAgentTask(
							ctx, current.ID, current.Version, code, err.Error())
						if failErr == nil || errors.Is(failErr, store.ErrConflict) {
							return nil
						}
						return failErr
					}
					h.recordDispatchDeferred(ctx, current, err)
					return fmt.Errorf("%w: %v", ErrControlEventDeferred, err)
				}
				return err
			}
			if event.LastError != "" {
				h.recordDispatchRecovered(ctx, task, event)
			}
		} else if event.LastError != "" && task.Status != controlmodel.AgentTaskFailed &&
			task.Status != controlmodel.AgentTaskCancelled {
			// A process may have stopped after dispatch committed but before the
			// diagnostic was appended or the outbox event was acknowledged.
			h.recordDispatchRecovered(ctx, task, event)
		}
	}
	if event.EventType == "approval.decided.v1" {
		if h.DispatchApprovalDecision == nil {
			return errors.New("Approval decision dispatcher is unavailable")
		}
		approvalID, err := uuid.Parse(event.AggregateID)
		if err != nil {
			return fmt.Errorf("invalid Approval aggregate ID %q: %w", event.AggregateID, err)
		}
		if err = h.DispatchApprovalDecision(ctx, approvalID); err != nil {
			return err
		}
	}
	if event.EventType == "execution-attempt.abort-managed.v1" {
		if h.DispatchManagedAbort == nil {
			return errors.New("managed Attempt abort dispatcher is unavailable")
		}
		attemptID, err := uuid.Parse(event.AggregateID)
		if err != nil {
			return fmt.Errorf("invalid execution Attempt aggregate ID %q: %w", event.AggregateID, err)
		}
		if err = h.DispatchManagedAbort(ctx, attemptID); err != nil {
			return err
		}
	}
	if h.ReconcileRun != nil && h.Store != nil {
		var runID uuid.UUID
		switch event.AggregateType {
		case "agent-task":
			if taskID, parseErr := uuid.Parse(event.AggregateID); parseErr == nil {
				if task, loadErr := h.Store.Collaboration().GetAgentTask(ctx, taskID); loadErr == nil {
					runID = task.OrchestrationRunID
				}
			}
		case "approval":
			if approvalID, parseErr := uuid.Parse(event.AggregateID); parseErr == nil {
				if approval, loadErr := h.Store.Collaboration().GetApproval(ctx, approvalID); loadErr == nil && approval.RunID != nil {
					runID = *approval.RunID
				}
			}
		}
		if runID != uuid.Nil {
			if err := h.ReconcileRun(ctx, runID); err != nil && !errors.Is(err, store.ErrConflict) {
				return err
			}
		}
	}
	if h.Sink != nil {
		return h.Sink.PublishCollaborationEvent(ctx, event)
	}
	return nil
}

func (h *CollaborationOutboxHandler) recordDispatchDeferred(ctx context.Context,
	task *controlmodel.AgentTask, cause error) {
	if h == nil || h.Store == nil || task == nil || cause == nil {
		return
	}
	payload, err := json.Marshal(map[string]any{
		"message": cause.Error(), "retryable": true, "source": "control_outbox",
	})
	if err != nil {
		return
	}
	if _, err = h.Store.Orchestration().AppendRunEvent(ctx, &controlmodel.RunEvent{
		RunID: task.OrchestrationRunID, Tenant: task.Tenant, Namespace: task.Namespace,
		NodeID: &task.RunNodeID, AgentTaskID: &task.ID, Type: "agent-task.dispatch_deferred",
		Actor:   controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "runtime-scheduler"},
		Payload: payload, CausationID: task.CausationID, CorrelationID: task.CorrelationID,
		IdempotencyKey: "agent-task-dispatch-deferred:" + task.ID.String(),
	}); err != nil {
		log.FromContext(ctx).Error(err, "recording AgentTask dispatch diagnostic", "task", task.ID)
	}
}

func (h *CollaborationOutboxHandler) recordDispatchRecovered(ctx context.Context,
	task *controlmodel.AgentTask, event *controlmodel.OutboxEvent) {
	if h == nil || h.Store == nil || task == nil || event == nil || event.LastError == "" {
		return
	}
	payload, err := json.Marshal(map[string]any{
		"attempts": event.Attempts, "previousError": event.LastError, "source": "control_outbox",
	})
	if err != nil {
		return
	}
	if _, err = h.Store.Orchestration().AppendRunEvent(ctx, &controlmodel.RunEvent{
		RunID: task.OrchestrationRunID, Tenant: task.Tenant, Namespace: task.Namespace,
		NodeID: &task.RunNodeID, AgentTaskID: &task.ID, Type: "agent-task.dispatch_recovered",
		Actor:   controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "runtime-scheduler"},
		Payload: payload, CausationID: task.CausationID, CorrelationID: task.CorrelationID,
		IdempotencyKey: "agent-task-dispatch-recovered:" + task.ID.String(),
	}); err != nil {
		log.FromContext(ctx).Error(err, "recording AgentTask dispatch recovery", "task", task.ID)
	}
}

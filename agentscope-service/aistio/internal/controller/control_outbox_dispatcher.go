// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package controller

import (
	"context"
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
			if markErr := d.Store.Outbox().MarkFailed(ctx, event.ID, d.WorkerID, err.Error(), retryAt); markErr != nil {
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
	Store             store.Store
	Sink              CollaborationEventSink
	DispatchAgentTask func(context.Context, uuid.UUID) error
	ReconcileRun      func(context.Context, uuid.UUID) error
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
				return err
			}
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

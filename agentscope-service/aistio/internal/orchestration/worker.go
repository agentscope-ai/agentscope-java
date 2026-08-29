// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package orchestration

import (
	"context"
	"time"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// Worker is the timer/deadline safety net for the outbox-driven engine. CAS
// versions and idempotent node materialization make it safe on every replica.
type Worker struct {
	Store    store.Store
	Interval time.Duration
	Batch    int
}

func (w *Worker) Start(ctx context.Context) error {
	interval := w.Interval
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
			w.ReconcileOnce(ctx)
		}
	}
}

func (w *Worker) ReconcileOnce(ctx context.Context) {
	if w == nil || w.Store == nil {
		return
	}
	batch := w.Batch
	if batch <= 0 {
		batch = 100
	}
	runs, err := w.Store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{ActiveOnly: true, Limit: batch})
	if err != nil {
		return
	}
	engine := &Engine{Store: w.Store}
	for _, run := range runs {
		_ = engine.ReconcileRun(ctx, run.ID)
	}
}

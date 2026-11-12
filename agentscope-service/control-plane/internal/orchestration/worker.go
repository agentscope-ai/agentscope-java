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

package orchestration

import (
	"context"
	"time"

	"github.com/google/uuid"

	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

// Worker is the timer/deadline safety net for the outbox-driven engine. CAS
// versions and idempotent node materialization make it safe on every replica.
type Worker struct {
	Store               store.Store
	Interval            time.Duration
	Batch               int
	RequireAgentService bool
	afterCreatedAt      *time.Time
	afterID             uuid.UUID
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
	engine := &Engine{Store: w.Store, RequireAgentService: w.RequireAgentService}
	if batch > 1024 {
		batch = 1024
	}
	runs, err := w.Store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{
		ActiveOnly: true, IncludeTerminalCleanup: true, AgentServiceOnly: w.RequireAgentService, OldestFirst: true, Limit: batch,
		AfterCreatedAt: w.afterCreatedAt, AfterID: w.afterID,
	})
	if err != nil {
		return
	}
	for _, run := range runs {
		if ctx.Err() != nil {
			return
		}
		stepCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
		_ = engine.ReconcileRun(stepCtx, run.ID)
		cancel()
		createdAt := run.CreatedAt
		w.afterCreatedAt, w.afterID = &createdAt, run.ID
	}
	if len(runs) < batch {
		w.afterCreatedAt, w.afterID = nil, uuid.Nil
	}
}

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

package automation

import (
	"context"
	"errors"
	"github.com/google/uuid"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"log/slog"
	"time"
)

type Worker struct {
	Service  *Service
	Interval time.Duration
	Batch    int
}

func (w *Worker) Tick(ctx context.Context, now time.Time) error {
	batch := w.Batch
	if batch <= 0 {
		batch = 100
	}
	var errs []error
	if _, err := w.Service.RunDue(ctx, now.UTC(), batch); err != nil {
		errs = append(errs, err)
	}
	deliveries, err := w.Service.Store.Collaboration().ListAutomationDeliveries(ctx, uuid.Nil, batch, 0)
	if err != nil {
		errs = append(errs, err)
	} else {
		for _, d := range deliveries {
			if _, err = w.Service.ProcessDelivery(ctx, d); err != nil && err != store.ErrConflict {
				errs = append(errs, err)
			}
		}
	}
	runs, err := w.Service.Store.Collaboration().ListPendingAutomationRuns(ctx, batch)
	if err != nil {
		errs = append(errs, err)
	} else {
		for _, run := range runs {
			if _, err = w.Service.ProcessRun(ctx, run); err != nil && err != store.ErrConflict {
				errs = append(errs, err)
			}
		}
	}
	return errors.Join(errs...)
}
func (w *Worker) Start(ctx context.Context) error {
	interval := w.Interval
	if interval <= 0 {
		interval = 2 * time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		if err := w.Tick(ctx, w.Service.now()); err != nil {
			slog.ErrorContext(ctx, "automation reconciliation failed", "error", err)
		}
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
		}
	}
}

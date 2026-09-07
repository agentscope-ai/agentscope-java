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

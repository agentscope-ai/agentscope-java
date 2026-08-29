package automation

import (
	"context"
	"time"
)

type Worker struct {
	Service  *Service
	Interval time.Duration
	Batch    int
}

func (w *Worker) Start(ctx context.Context) error {
	interval := w.Interval
	if interval <= 0 {
		interval = 15 * time.Second
	}
	batch := w.Batch
	if batch <= 0 {
		batch = 100
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case now := <-ticker.C:
			_, _ = w.Service.RunDue(ctx, now.UTC(), batch)
		}
	}
}

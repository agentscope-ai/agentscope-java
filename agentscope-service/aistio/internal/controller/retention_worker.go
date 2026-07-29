package controller

import (
	"context"
	"time"

	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// RetentionWorker periodically purges historical session events, snapshots,
// context snapshots, and metrics older than the configured retention
// windows. Runs only on the leader replica.
type RetentionWorker struct {
	Store     store.Store
	Retention store.RetentionConfig
	Interval  time.Duration // default 1h
}

// Start implements manager.Runnable.
func (w *RetentionWorker) Start(ctx context.Context) error {
	interval := w.Interval
	if interval <= 0 {
		interval = time.Hour
	}

	logger := log.FromContext(ctx).WithName("retention-worker")
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			if w.Store == nil {
				continue
			}
			n, err := w.Store.PurgeOlderThan(ctx, w.Retention)
			if err != nil {
				logger.Error(err, "retention purge failed")
				continue
			}
			if n > 0 {
				logger.Info("retention purge completed", "rowsDeleted", n)
			}
		}
	}
}

// NeedLeaderElection ensures only one replica runs retention purges.
func (w *RetentionWorker) NeedLeaderElection() bool { return true }

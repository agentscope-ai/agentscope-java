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

package controller

import (
	"context"
	"time"

	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

const defaultOrphanSweepErrMsg = "executor lost: no heartbeat (hosted store orphan sweep)"

// TaskSweepWorker marks stale non-terminal hosted subagent tasks as FAILED.
// Runs only on the leader replica.
type TaskSweepWorker struct {
	Store         store.Store
	Interval      time.Duration // default 1 minute
	OrphanTimeout time.Duration // default 10 minutes
	OnSwept       func(count int) // optional metrics hook
}

// Start implements manager.Runnable.
func (w *TaskSweepWorker) Start(ctx context.Context) error {
	interval := w.Interval
	if interval <= 0 {
		interval = time.Minute
	}
	orphanTimeout := w.OrphanTimeout
	if orphanTimeout <= 0 {
		orphanTimeout = 10 * time.Minute
	}

	logger := log.FromContext(ctx).WithName("task-sweep-worker")
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
			swept, err := w.Store.Tasks().SweepOrphaned(ctx, orphanTimeout, defaultOrphanSweepErrMsg)
			if err != nil {
				logger.Error(err, "task orphan sweep failed")
				continue
			}
			if len(swept) > 0 {
				if w.OnSwept != nil {
					w.OnSwept(len(swept))
				}
				logger.Info("task orphan sweep completed", "tasksSwept", len(swept))
			}
		}
	}
}

// NeedLeaderElection ensures only one replica runs orphan sweeps.
func (w *TaskSweepWorker) NeedLeaderElection() bool { return true }

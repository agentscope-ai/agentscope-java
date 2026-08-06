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

package sessionops

import (
	"context"
	"log"
	"time"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// QueueWorker drains session_commands with status=queued once the target
// session is no longer busy. Safe under multi-replica aistiod because each
// ExecuteQueued path takes store.WithSessionLock (advisory lock on Postgres).
type QueueWorker struct {
	Router   *Router
	Store    store.Store
	Interval time.Duration
	Batch    int
}

// Run blocks until ctx is cancelled.
func (w *QueueWorker) Run(ctx context.Context) {
	if w == nil || w.Router == nil || w.Store == nil {
		return
	}
	if w.Interval <= 0 {
		w.Interval = 3 * time.Second
	}
	if w.Batch <= 0 {
		w.Batch = 20
	}
	ticker := time.NewTicker(w.Interval)
	defer ticker.Stop()
	w.tick(ctx)
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			w.tick(ctx)
		}
	}
}

func (w *QueueWorker) tick(ctx context.Context) {
	queued, err := w.Store.Commands().List(ctx, store.SessionCommandFilter{
		Status: store.CommandStatusQueued,
		Limit:  w.Batch,
	})
	if err != nil || len(queued) == 0 {
		return
	}
	// Oldest first for fairness.
	for i, j := 0, len(queued)-1; i < j; i, j = i+1, j-1 {
		queued[i], queued[j] = queued[j], queued[i]
	}
	for _, cmd := range queued {
		if cmd.SessionFK == nil {
			continue
		}
		sess, err := w.Store.Sessions().GetByID(ctx, *cmd.SessionFK)
		if err != nil {
			continue
		}
		if phaseIsActive(sess) || normalizePhase(sess.Phase) == store.SessionPhaseCompressing {
			continue // still mid-turn / compressing — wait
		}
		phase := normalizePhase(sess.Phase)
		if phase != store.SessionPhaseIdle && !cmd.Forced {
			// Unknown phase without force: leave queued until idle is observed.
			if phase == "" && sess.Busy == nil {
				continue
			}
			if phase != "" && phase != store.SessionPhaseIdle {
				continue
			}
		}
		if _, err := w.Router.ExecuteQueued(ctx, sess, cmd); err != nil {
			if opErr, ok := AsError(err); ok && opErr.Code == CodeBusy {
				continue // race: still busy
			}
			log.Printf("sessionops queue: drain %s (%s): %v", cmd.CommandID, cmd.Command, err)
			// Mark failed so we do not spin forever on permanent errors.
			if opErr, ok := AsError(err); ok && opErr.Code != CodeBusy {
				now := time.Now().UTC()
				cmd.Status = store.CommandStatusFailed
				cmd.Code = opErr.Code
				cmd.Error = opErr.Msg
				cmd.CompletedAt = &now
				_ = w.Store.Commands().Update(ctx, cmd)
			}
		}
	}
}

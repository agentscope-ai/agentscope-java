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
	"github.com/spring-ai-alibaba/aistio/internal/team"
)

// TeamSweeper drives shared team lifecycle predicates against the store.
// It is the single timeout / all-complete / TTL / member-health loop for both
// REST-created and CRD-projected teams (registry/K8s are sources only).
type TeamSweeper struct {
	Store     store.Store
	Lifecycle *team.Lifecycle
	Interval  time.Duration // default 30s
}

// Start implements manager.Runnable.
func (w *TeamSweeper) Start(ctx context.Context) error {
	interval := w.Interval
	if interval <= 0 {
		interval = 30 * time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			w.sweepOnce(ctx)
		}
	}
}

// NeedLeaderElection keeps TTL/recovery single-writer under multi-replica kube.
func (w *TeamSweeper) NeedLeaderElection() bool { return true }

func (w *TeamSweeper) sweepOnce(ctx context.Context) {
	if w.Store == nil || w.Lifecycle == nil {
		return
	}
	logger := log.FromContext(ctx).WithName("team-sweeper")

	teams, err := w.Store.Teams().List(ctx, "")
	if err != nil {
		logger.Error(err, "list teams failed")
		return
	}
	for _, t := range teams {
		switch t.Phase {
		case store.TeamPhaseRunning, store.TeamPhaseIdle:
			w.sweepRunning(ctx, t)
		case store.TeamPhaseCompleted, store.TeamPhaseFailed:
			if w.Lifecycle.ShouldCleanup(t) {
				logger.Info("cleaning up team after TTL", "namespace", t.Namespace, "team", t.Name, "phase", t.Phase)
				w.Lifecycle.CleanupTeamState(ctx, t)
			}
		}
	}
}

func (w *TeamSweeper) sweepRunning(ctx context.Context, t *store.Team) {
	logger := log.FromContext(ctx).WithName("team-sweeper")

	if w.Lifecycle.CheckTimeout(t) {
		logger.Info("team exceeded maxDuration", "namespace", t.Namespace, "team", t.Name)
		if err := w.Lifecycle.FailTeam(ctx, t, "timeout"); err != nil {
			logger.Error(err, "FailTeam failed", "team", t.Name)
		}
		return
	}

	if w.Lifecycle.CheckAllComplete(t) {
		cfg := team.ParseTeamConfig(t)
		if cfg.ShutdownPolicy == "all-complete" {
			logger.Info("all tasks complete, shutting down team", "namespace", t.Namespace, "team", t.Name)
			if err := w.Lifecycle.CompleteTeam(ctx, t); err != nil {
				logger.Error(err, "CompleteTeam failed", "team", t.Name)
			}
			return
		}
	}

	w.checkMemberHealth(ctx, t)
}

func (w *TeamSweeper) checkMemberHealth(ctx context.Context, storeTeam *store.Team) {
	logger := log.FromContext(ctx).WithName("team-sweeper")
	members, err := w.Store.Teams().ListMembers(ctx, storeTeam.Namespace, storeTeam.Name)
	if err != nil {
		return
	}
	for _, m := range members {
		if m.MemberName == "lead" {
			continue
		}
		if m.Phase == store.MemberPhaseLost || m.Phase == store.MemberPhaseFailed || m.SessionID == "" {
			continue
		}
		sess, err := w.Store.Sessions().Get(ctx, m.AgentRef, storeTeam.Namespace, m.SessionID)
		if err != nil {
			if err == store.ErrNotFound {
				logger.Info("member session missing", "team", storeTeam.Name, "member", m.MemberName)
				_ = w.Lifecycle.HandleMemberFailure(ctx, storeTeam, m.MemberName, "SessionNotFound")
			}
			continue
		}
		if sess.Phase == store.SessionPhaseTerminated {
			logger.Info("member session terminated", "team", storeTeam.Name, "member", m.MemberName)
			_ = w.Lifecycle.HandleMemberFailure(ctx, storeTeam, m.MemberName, "SessionTerminated")
		}
	}
}

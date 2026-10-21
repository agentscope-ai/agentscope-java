// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package httpapi

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"golang.org/x/sync/errgroup"
	ctrl "sigs.k8s.io/controller-runtime"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controller"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/orchestration"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeportal"
)

// RunExecutionWorkers runs on every replica. Shared claims and fencing, rather
// than a process-local leader, protect execution and outbox delivery. Returning
// waits for all bounded advances to stop before the caller closes its stores.
func (s *Server) RunExecutionWorkers(ctx context.Context, slots, batch int, sweepInterval, offlineTimeout time.Duration) error {
	if s.store == nil || s.portalClient == nil || slots < 1 || slots > 128 || batch < 1 || batch > 128 {
		return fmt.Errorf("invalid integrated execution worker configuration")
	}
	reconcile := func(ctx context.Context, id uuid.UUID) error {
		return (&orchestration.Engine{Store: s.store, RequirePortal: true}).ReconcileRun(ctx, id)
	}
	handler := &controller.CollaborationOutboxHandler{
		Store: s.store, Sink: s.collaborationEvents, DispatchAgentTask: s.DispatchAgentTask,
		DispatchManagedAttempt: s.DispatchManagedAttempt, DispatchApprovalDecision: s.DispatchApprovalDecision,
		DispatchManagedAbort: s.DispatchManagedAttemptAbort, ReconcileRun: reconcile,
	}
	workers := []func(context.Context) error{
		(&runtimeportal.Worker{Repository: s.store.RuntimePortal(), Client: s.portalClient,
			Backend: s.PortalBackend(), Slots: slots, ReportError: func(err error) {
				ctrl.Log.Error(err, "Portal runtime step")
			}}).Start,
		(&orchestration.Worker{Store: s.store, RequirePortal: true, Interval: time.Second, Batch: batch}).Start,
		(&controller.RuntimeControlSweeper{Store: s.store, PortalOnly: true,
			ReconcileManagedAttempt: s.ReconcileManagedAttempt, ReconcileRun: reconcile,
			Interval: sweepInterval, RuntimeTimeout: offlineTimeout, Batch: batch}).Start,
		(&controller.ControlOutboxDispatcher{Store: s.store, Handler: handler, PortalOnly: true,
			WorkerID: uuid.NewString(), Batch: batch, MaxBackoff: 5 * time.Second}).Start,
	}
	group, workerCtx := errgroup.WithContext(ctx)
	for _, start := range workers {
		group.Go(func() error { return start(workerCtx) })
	}
	return group.Wait()
}

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package postgres

import (
	"context"
	"fmt"

	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// Every graph writer takes the same Run row lock and keeps it until commit.
// Counts use the run_id indexes and stop at limit+1 even for old or
// externally modified graphs. No process-local counter can oversubscribe it.
func checkRunGraphCapacityTx(ctx context.Context, tx pgx.Tx, runID uuid.UUID, nodes, edges int) error {
	state, err := lockCoordinatorRunTx(ctx, tx, runID)
	if err != nil {
		return err
	}
	if controlmodel.IsOrchestrationRunTerminal(state) {
		return store.ErrConflict
	}
	if nodes > 0 {
		var count int
		if err := tx.QueryRow(ctx, `SELECT count(*) FROM (SELECT id FROM orchestration_run_nodes WHERE run_id=$1 LIMIT $2) n`, runID, store.MaxRunNodes+1).Scan(&count); err != nil {
			return err
		}
		if nodes > store.MaxRunNodes-count {
			return fmt.Errorf("%w: Run node limit is %d", store.ErrResourceLimit, store.MaxRunNodes)
		}
	}
	if edges > 0 {
		var count int
		if err := tx.QueryRow(ctx, `SELECT count(*) FROM (SELECT id FROM orchestration_run_edges WHERE run_id=$1 LIMIT $2) e`, runID, store.MaxRunEdges+1).Scan(&count); err != nil {
			return err
		}
		if edges > store.MaxRunEdges-count {
			return fmt.Errorf("%w: Run edge limit is %d", store.ErrResourceLimit, store.MaxRunEdges)
		}
	}
	return nil
}

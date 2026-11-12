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
	"github.com/google/uuid"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

func (r *orchestrationRepo) CompleteTerminalCleanup(ctx context.Context, id uuid.UUID, version int64) error {
	result, err := r.pool.Exec(ctx, `UPDATE orchestration_runs SET terminal_cleanup_pending=FALSE,version=version+1,updated_at=now()
      WHERE id=$1 AND version=$2 AND state='failed' AND terminal_cleanup_pending`, id, version)
	if err != nil {
		return err
	}
	if result.RowsAffected() != 1 {
		return store.ErrConflict
	}
	return nil
}

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package memory

import (
	"context"
	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
	"time"
)

func (r *orchestrationRepo) CompleteTerminalCleanup(_ context.Context, id uuid.UUID, version int64) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	run := r.s.runs[id]
	if run == nil {
		return store.ErrNotFound
	}
	if run.Version != version || run.State != model.RunFailed || !run.TerminalCleanupPending {
		return store.ErrConflict
	}
	run.TerminalCleanupPending = false
	run.Version++
	run.UpdatedAt = time.Now().UTC()
	return nil
}

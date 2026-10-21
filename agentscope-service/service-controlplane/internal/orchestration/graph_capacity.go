// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package orchestration

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

const waitingSweepCursorPath = "runtime/graph-waiting-sweep"

type waitingSweepCursor struct {
	Version int       `json:"version"`
	AfterID uuid.UUID `json:"after_id"`
}

// Answers receive half the finite budget; the remaining half rotates over all
// waiting nodes so long-lived approvals cannot starve timers, tasks or subruns.
// The shared Run lock fences this cursor. Commit follows successful side effects;
// a crash replays a finite, idempotent page and another replica continues it.
func (e *Engine) waitingSweepPage(ctx context.Context, run *model.OrchestrationRun, nodes []*model.RunNode, limit int) ([]*model.RunNode, func() error, error) {
	noop := func() error { return nil }
	if limit <= 0 {
		return nil, noop, nil
	}
	cursor := waitingSweepCursor{Version: 1}
	version := int64(0)
	row, err := e.Store.KV().Get(ctx, run.Tenant, waitingSweepCursorPath, run.ID.String())
	if err == nil {
		version = row.Version
		if len(row.Value) > 256 || json.Unmarshal(row.Value, &cursor) != nil || cursor.Version != 1 {
			return nil, nil, fmt.Errorf("%w: invalid graph waiting cursor", store.ErrConflict)
		}
	} else if !errors.Is(err, store.ErrNotFound) {
		return nil, nil, err
	}
	answered, err := e.Store.Orchestration().ListNodesPage(ctx, run.ID, store.RunNodeFilter{AnsweredOnly: true, Limit: (limit + 1) / 2})
	if err != nil {
		return nil, nil, err
	}
	selected := make([]*model.RunNode, 0, limit)
	seen := map[uuid.UUID]bool{}
	for _, node := range answered {
		selected = append(selected, node)
		seen[node.ID] = true
	}
	waiting := make([]*model.RunNode, 0)
	for _, node := range nodes {
		if node.State == model.RunNodeWaiting && !seen[node.ID] {
			waiting = append(waiting, node)
		}
	}
	sort.Slice(waiting, func(i, j int) bool { return waiting[i].ID.String() < waiting[j].ID.String() })
	start := sort.Search(len(waiting), func(i int) bool { return waiting[i].ID.String() > cursor.AfterID.String() })
	for i := 0; i < len(waiting) && len(selected) < limit; i++ {
		node := waiting[(start+i)%len(waiting)]
		selected = append(selected, node)
		cursor.AfterID = node.ID
	}
	if len(selected) == 0 {
		return nil, noop, nil
	}
	commit := func() error {
		raw, err := json.Marshal(cursor)
		if err != nil {
			return err
		}
		_, written, err := e.Store.KV().PutIfVersion(ctx, run.Tenant, waitingSweepCursorPath, run.ID.String(), raw, version)
		if err != nil {
			return err
		}
		if !written {
			return store.ErrConflict
		}
		return nil
	}
	return selected, commit, nil
}

func (e *Engine) failGraphCapacity(ctx context.Context, run *model.OrchestrationRun, cause error) error {
	run, err := e.Store.Orchestration().TransitionRun(ctx, run.ID, run.Version, model.RunCancelling, nil, "resource_limit", cause.Error())
	if err != nil {
		return err
	}
	drained, err := (&Service{Store: e.Store}).advanceCancellation(ctx, run)
	if err != nil || drained.State != model.RunFailed {
		return err
	}
	return e.finishFailedRun(ctx, drained)
}

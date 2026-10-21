// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package controller

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

type runtimeSweepCursor struct {
	Version int       `json:"version"`
	AfterID uuid.UUID `json:"after_id"`
}

// Portal runtime replicas share a finite UUID page cursor. State transitions
// use their own CAS; cursor commit follows side effects, so a crash may replay
// a page but cannot skip an unfinished one. A competing cursor CAS also replays.
func (w *RuntimeControlSweeper) toolApprovalSweepPage(ctx context.Context, limit int) ([]*model.Approval, func() error, error) {
	filter := store.ApprovalFilter{TargetType: model.ApprovalTargetExecutionAttempt, Status: model.ApprovalPending, Limit: limit}
	noop := func() error { return nil }
	if !w.PortalOnly {
		approvals := make([]*model.Approval, 0, limit)
		for offset := 0; ; offset += limit {
			filter.Offset = offset
			page, err := w.Store.Collaboration().ListApprovals(ctx, filter)
			if err != nil {
				return nil, nil, err
			}
			approvals = append(approvals, page...)
			if len(page) < limit {
				return approvals, noop, nil
			}
		}
	}

	after, advance, err := w.beginPortalSweep(ctx, "portal-tool-approvals-v1")
	if err != nil {
		return nil, nil, err
	}
	filter.PortalOnly, filter.AfterID = true, after
	page, err := w.Store.Collaboration().ListApprovals(ctx, filter)
	if err != nil {
		return nil, nil, err
	}
	last := uuid.Nil
	if len(page) > 0 {
		last = page[len(page)-1].ID
	}
	return page, func() error { return advance(last, len(page) == limit) }, nil
}

func (w *RuntimeControlSweeper) beginPortalSweep(ctx context.Context, key string) (*uuid.UUID, func(uuid.UUID, bool) error, error) {
	noop := func(uuid.UUID, bool) error { return nil }
	if !w.PortalOnly {
		return nil, noop, nil
	}
	const tenant, path = "__runtime__", "runtime/scan"
	cursor := runtimeSweepCursor{Version: 1}
	version := int64(0)
	row, err := w.Store.KV().Get(ctx, tenant, path, key)
	if err == nil {
		version = row.Version
		if len(row.Value) > 256 || json.Unmarshal(row.Value, &cursor) != nil || cursor.Version != 1 {
			return nil, nil, fmt.Errorf("%w: invalid runtime scan cursor", store.ErrConflict)
		}
	} else if !errors.Is(err, store.ErrNotFound) {
		return nil, nil, err
	}
	after := cursor.AfterID
	commit := func(last uuid.UUID, full bool) error {
		next := uuid.Nil
		if full {
			next = last
		}
		if next == cursor.AfterID {
			return nil
		}
		raw, err := json.Marshal(runtimeSweepCursor{Version: 1, AfterID: next})
		if err != nil {
			return err
		}
		_, written, err := w.Store.KV().PutIfVersion(ctx, tenant, path, key, raw, version)
		if err != nil {
			return err
		}
		if !written {
			return store.ErrConflict
		}
		return nil
	}
	return &after, commit, nil
}

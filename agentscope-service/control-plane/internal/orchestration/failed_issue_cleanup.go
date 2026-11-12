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

	"github.com/google/uuid"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/collaboration"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

const failedIssueCursorPath = "runtime/failed-issue-cleanup"
const failedIssueCleanupBatch = 16
const failedIssueCleanupDepth = 256

type failedIssueFrame struct {
	IssueID uuid.UUID `json:"issue_id"`
	AfterID uuid.UUID `json:"after_id"`
	Blocked bool      `json:"blocked"`
}
type failedIssueCursor struct {
	Version int                `json:"version"`
	Frames  []failedIssueFrame `json:"frames"`
	Done    bool               `json:"done"`
}

// The Run lock fences cursor writes across replicas. Side effects precede the cursor commit and
// are idempotent, so a crash replays a finite batch without losing an unvisited child.
func (e *Engine) advanceFailedIssueTree(ctx context.Context, run *model.OrchestrationRun) error {
	if run == nil || !model.NeedsFailedIssueCleanup(run) {
		return nil
	}
	cursor := failedIssueCursor{Version: 1, Frames: []failedIssueFrame{{IssueID: run.RootIssueID}}}
	version := int64(0)
	row, err := e.Store.KV().Get(ctx, run.Tenant, failedIssueCursorPath, run.ID.String())
	if err == nil {
		version = row.Version
		if err = json.Unmarshal(row.Value, &cursor); err != nil {
			return err
		}
		if cursor.Version != 1 || len(cursor.Frames) > failedIssueCleanupDepth || (!cursor.Done && (len(cursor.Frames) == 0 || cursor.Frames[0].IssueID != run.RootIssueID)) {
			return fmt.Errorf("%w: invalid failed Issue cleanup cursor", store.ErrConflict)
		}
	} else if !errors.Is(err, store.ErrNotFound) {
		return err
	}
	if cursor.Done {
		return e.finishFailedIssueCleanup(ctx, run)
	}
	latest, err := e.Store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{
		Tenant: run.Tenant, Namespace: run.Namespace, RootIssueID: run.RootIssueID, Limit: 1})
	if err != nil {
		return err
	}
	// A recovery Run owns the current Issue lifecycle. Historical failures must not block it.
	if len(latest) > 0 && latest[0].ID != run.ID && latest[0].CreatedAt.After(run.CreatedAt) {
		cursor.Done = true
		cursor.Frames = nil
	} else {
		if err = (&collaboration.Service{Store: e.Store}).EnsureTerminalTeamSummary(ctx, run); err != nil {
			return err
		}
		actor := model.Actor{Type: model.ActorSystem, Ref: "orchestration-run:" + run.ID.String()}
		cutoff := run.CompletedAt
		if cutoff == nil {
			cutoff = &run.UpdatedAt
		}
		for operations := 0; operations < failedIssueCleanupBatch && len(cursor.Frames) > 0; operations++ {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			frame := &cursor.Frames[len(cursor.Frames)-1]
			if !frame.Blocked {
				if err = e.blockIssueAfterRunFailure(ctx, frame.IssueID, actor, run.FailureCode); err != nil {
					return err
				}
				frame.Blocked = true
				continue
			}
			children, listErr := e.Store.Collaboration().ListIssues(ctx, store.IssueFilter{
				Tenant: run.Tenant, Namespace: run.Namespace, ParentID: &frame.IssueID,
				ChildrenAfterID: &frame.AfterID, CreatedBefore: cutoff, Limit: 1})
			if listErr != nil {
				return listErr
			}
			if len(children) == 0 {
				cursor.Frames = cursor.Frames[:len(cursor.Frames)-1]
				continue
			}
			child := children[0]
			if len(cursor.Frames) >= failedIssueCleanupDepth {
				return fmt.Errorf("%w: failed Issue cleanup exceeds %d ancestors", store.ErrConflict, failedIssueCleanupDepth)
			}
			for _, parent := range cursor.Frames {
				if parent.IssueID == child.ID {
					return fmt.Errorf("%w: failed Issue cleanup contains a cycle", store.ErrConflict)
				}
			}
			frame.AfterID = child.ID
			cursor.Frames = append(cursor.Frames, failedIssueFrame{IssueID: child.ID})
		}
		cursor.Done = len(cursor.Frames) == 0
	}
	raw, err := json.Marshal(cursor)
	if err != nil {
		return err
	}
	_, written, err := e.Store.KV().PutIfVersion(ctx, run.Tenant, failedIssueCursorPath, run.ID.String(), raw, version)
	if err != nil {
		return err
	}
	if !written {
		return store.ErrConflict
	}
	if cursor.Done {
		return e.finishFailedIssueCleanup(ctx, run)
	}
	return nil
}

func (e *Engine) finishFailedIssueCleanup(ctx context.Context, run *model.OrchestrationRun) error {
	if !run.TerminalCleanupPending {
		return nil
	}
	return e.Store.Orchestration().CompleteTerminalCleanup(ctx, run.ID, run.Version)
}

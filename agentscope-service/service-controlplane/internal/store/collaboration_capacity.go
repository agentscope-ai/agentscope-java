// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package store

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/google/uuid"
)

const MaxRunTaskView = 32768
const collaborationReadBatch = 128

// Read all entries needed for a consistency decision, or reject explicitly.
// UUID keysets do not lose entries when priority or status changes between pages.
// Runtime history is chronological; queued task scheduling retains its own
// priority order through the ordinary repository list API.
func ReadTasksBounded(ctx context.Context, repository CollaborationRepository, filter AgentTaskFilter) ([]*model.AgentTask, error) {
	after := uuid.Nil
	filter.AfterID = &after
	filter.Offset = 0
	filter.Limit = collaborationReadBatch
	var out []*model.AgentTask
	bytes := 0
	for {
		page, err := repository.ListAgentTasks(ctx, filter)
		if err != nil {
			return nil, err
		}
		for _, task := range page {
			raw, err := json.Marshal(task)
			if err != nil {
				return nil, err
			}
			bytes += len(raw)
			if len(out) >= MaxRunTaskView || bytes > MaxRunGraphBytes {
				return nil, fmt.Errorf("%w: task consistency view exceeds capacity", ErrResourceLimit)
			}
			out = append(out, task)
			after = task.ID
		}
		if len(page) < filter.Limit {
			break
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].CreatedAt.Equal(out[j].CreatedAt) {
			return out[i].ID.String() < out[j].ID.String()
		}
		return out[i].CreatedAt.Before(out[j].CreatedAt)
	})
	return out, nil
}

func ReadChildIssuesBounded(ctx context.Context, repository CollaborationRepository, filter IssueFilter) ([]*model.Issue, error) {
	if filter.ParentID == nil {
		return nil, fmt.Errorf("parent Issue is required for a bounded child view")
	}
	after := uuid.Nil
	filter.ChildrenAfterID = &after
	filter.Offset = 0
	filter.Limit = collaborationReadBatch
	var out []*model.Issue
	bytes := 0
	for {
		page, err := repository.ListIssues(ctx, filter)
		if err != nil {
			return nil, err
		}
		for _, issue := range page {
			raw, err := json.Marshal(issue)
			if err != nil {
				return nil, err
			}
			bytes += len(raw)
			if len(out) >= MaxRunNodes || bytes > MaxRunGraphBytes {
				return nil, fmt.Errorf("%w: child Issue consistency view exceeds capacity", ErrResourceLimit)
			}
			out = append(out, issue)
			after = issue.ID
		}
		if len(page) < filter.Limit {
			break
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].CreatedAt.Equal(out[j].CreatedAt) {
			return out[i].ID.String() < out[j].ID.String()
		}
		return out[i].CreatedAt.Before(out[j].CreatedAt)
	})
	return out, nil
}

// ReadCommentsBounded keeps history traversal finite and stable under updates.
// Overflow is an explicit resource error, never an incomplete consistency view.
func ReadCommentsBounded(ctx context.Context, repository CollaborationRepository, issueID uuid.UUID) ([]*model.Comment, error) {
	opts := CommentListOptions{Limit: collaborationReadBatch}
	var out []*model.Comment
	bytes := 0
	for {
		page, err := repository.ListComments(ctx, issueID, opts)
		if err != nil {
			return nil, err
		}
		for _, comment := range page {
			raw, err := json.Marshal(comment)
			if err != nil {
				return nil, err
			}
			bytes += len(raw)
			if len(out) >= MaxRunTaskView || bytes > MaxRunGraphBytes {
				return nil, fmt.Errorf("%w: comment consistency view exceeds capacity", ErrResourceLimit)
			}
			out = append(out, comment)
		}
		if len(page) < opts.Limit {
			return out, nil
		}
		last := page[len(page)-1]
		at := last.CreatedAt
		opts.CursorTime, opts.CursorID = &at, last.ID
	}
}

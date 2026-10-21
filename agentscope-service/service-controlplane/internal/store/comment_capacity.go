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
	"encoding/json"
	"fmt"
	"strings"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/google/uuid"
)

const MaxCommentTargets = 128
const MaxCommentRelations = 4096
const MaxCommentRelationBytes = 4 << 20
const MaxCommentBodyBytes = MaxRunGraphBytes - MaxCommentRelationBytes - 4096

var ErrCommentCapacity = fmt.Errorf("%w: comment capacity exceeded", ErrResourceLimit)

type CommentRelationBudget struct {
	count int
	bytes int
}

func (b *CommentRelationBudget) Add(value any) error {
	raw, err := json.Marshal(value)
	if err != nil {
		return err
	}
	b.count++
	b.bytes += len(raw) + 1
	if b.count > MaxCommentRelations || b.bytes > MaxCommentRelationBytes {
		return ErrCommentCapacity
	}
	return nil
}

func ValidateCommentCapacity(comment *model.Comment) error {
	if comment == nil {
		return ErrConflict
	}
	if len(comment.Mentions)+len(comment.Routes) > MaxCommentRelations {
		return ErrCommentCapacity
	}
	base := *comment
	base.Mentions, base.Routes = nil, nil
	if len(base.Content) > MaxCommentBodyBytes {
		return ErrCommentCapacity
	}
	raw, err := json.Marshal(base)
	if err != nil {
		return err
	}
	if len(raw) > MaxCommentBodyBytes {
		return ErrCommentCapacity
	}
	var budget CommentRelationBudget
	for _, mention := range comment.Mentions {
		if err := budget.Add(mention); err != nil {
			return err
		}
	}
	for _, route := range comment.Routes {
		if err := budget.Add(route); err != nil {
			return err
		}
	}
	return nil
}

// Check the full projected comment before routing can create Tasks or events.
// Existing correction routes count toward the same retained-history budget.
func ValidateCommentWrite(comment *model.Comment, mentions []model.Mention, targets []CommentTarget) error {
	if len(mentions) > MaxCommentTargets || len(targets) > MaxCommentTargets {
		return ErrCommentCapacity
	}
	if err := ValidateCommentCapacity(comment); err != nil {
		return err
	}
	projected := *comment
	projected.Mentions = append([]model.Mention(nil), comment.Mentions...)
	projected.Routes = append([]model.CommentRoute(nil), comment.Routes...)
	at := time.Date(9999, 12, 31, 23, 59, 59, 999999999, time.FixedZone("", 3600))
	id := uuid.Nil
	projected.Version, projected.CreatedAt, projected.UpdatedAt = 1<<63-1, at, at
	for _, mention := range mentions {
		mention.ID, mention.Tenant, mention.Namespace = id, comment.Tenant, comment.Namespace
		mention.IssueID, mention.CommentID, mention.CreatedAt = comment.IssueID, comment.ID, at
		projected.Mentions = append(projected.Mentions, mention)
	}
	for _, target := range targets {
		reason := target.ReasonCode
		// Reserve the generated capacity/correction reason and physical Task ID,
		// even for routes that turn out to be suppressed or blocked.
		encodedReason, _ := json.Marshal(reason)
		if len(encodedReason) < 34 {
			reason = strings.Repeat("x", 32)
		}
		projected.Routes = append(projected.Routes, model.CommentRoute{ID: id, Tenant: comment.Tenant, Namespace: comment.Namespace,
			IssueID: comment.IssueID, CommentID: comment.ID, CommentVersion: 1<<63 - 1, TargetType: target.TargetType,
			TargetRef: target.TargetRef, RouteType: target.RouteType, Outcome: model.RouteSuppressed, TaskID: &id, ReasonCode: reason, CreatedAt: at})
	}
	return ValidateCommentCapacity(&projected)
}

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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package store

import (
	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"testing"
)

func TestDelegatedIssueEnteringReviewNotifiesAccountableHuman(t *testing.T) {
	parent := uuid.New()
	issue := &model.Issue{ID: uuid.New(), ParentIssueID: &parent, Status: model.IssueInReview, Kind: model.IssueKindUserWork, Visibility: model.IssueVisibilityWorkHub, CompletionPolicy: model.IssueCompletionReview, AssigneeType: model.AssigneeAgent, AssigneeRef: "worker"}
	items := IssueInboxItems(issue, model.IssueInProgress, model.Actor{Type: model.ActorAgent, Ref: "worker"}, "review result", "owner", nil)
	if len(items) != 1 || items[0].Type != "review_request" || !items[0].NeedsAction || items[0].RecipientRef != "owner" {
		t.Fatalf("review notification missing: %+v", items)
	}
	issue.Status = model.IssueBlocked
	if items := IssueInboxItems(issue, model.IssueInProgress, model.Actor{Type: model.ActorAgent}, "internal blocker", "owner", nil); len(items) != 0 {
		t.Fatal("ordinary child blocker leaked to human")
	}
	issue.Status = model.IssueInReview
	issue.Kind = model.IssueKindEndpointJob
	if items := IssueInboxItems(issue, model.IssueInProgress, model.Actor{Type: model.ActorAgent}, "operational", "owner", nil); len(items) != 0 {
		t.Fatal("operational job leaked into Work Inbox")
	}
}

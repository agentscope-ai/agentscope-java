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

package collaboration

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

func TestIssuePropertiesAcceptanceRequiresResultWithoutAssignee(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "t", Namespace: "n", Title: "acceptance", Status: controlmodel.IssueInProgress, Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, AcceptanceCriteria: json.RawMessage(`{"requiredResult":true}`)})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := svc.TransitionIssue(ctx, issue.ID, issue.Version, controlmodel.IssueDone, issue.Creator, "accept"); err == nil || !strings.Contains(err.Error(), "visible result") {
		t.Fatalf("unassigned result requirement bypassed: %v", err)
	}
	if _, err := svc.AddComment(ctx, AddCommentRequest{IssueID: issue.ID, Author: issue.Creator, Type: controlmodel.CommentResult, Content: "Verified result"}); err != nil {
		t.Fatal(err)
	}
	issue, _ = st.Collaboration().GetIssue(ctx, issue.ID)
	if _, err := svc.TransitionIssue(ctx, issue.ID, issue.Version, controlmodel.IssueDone, issue.Creator, "accept"); err != nil {
		t.Fatal(err)
	}
}

func TestIssuePropertiesAcceptanceUsesReadableCriterion(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "t", Namespace: "n", Title: "acceptance", Status: controlmodel.IssueInProgress, Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, AcceptanceCriteria: json.RawMessage(`{"checklist":[{"id":"opaque-id","text":"Run regression tests","satisfied":false}]}`)})
	if err != nil {
		t.Fatal(err)
	}
	if err := svc.evaluateAcceptanceCriteria(ctx, issue); err == nil || !strings.Contains(err.Error(), "Run regression tests") {
		t.Fatalf("unhelpful error: %v", err)
	}
}

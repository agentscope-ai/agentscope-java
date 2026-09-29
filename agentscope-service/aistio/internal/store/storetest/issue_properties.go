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

package storetest

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func testIssueProperties(t *testing.T, ctx context.Context, s store.Store) {
	actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	issue, err := s.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "properties-" + uuid.NewString(), Namespace: "n", Title: "properties", Status: controlmodel.IssueDone, AssigneeType: controlmodel.AssigneeHuman, AssigneeRef: "owner", Creator: actor})
	if err != nil {
		t.Fatal(err)
	}
	for _, kind := range []controlmodel.AssigneeType{controlmodel.AssigneeAgent, controlmodel.AssigneeTeam} {
		if _, _, err := s.Collaboration().AssignIssue(ctx, issue.ID, issue.Version, kind, uuid.NewString(), actor); err != store.ErrConflict {
			t.Fatalf("terminal assignment: %v", err)
		}
	}
	issue, task, err := s.Collaboration().AssignIssue(ctx, issue.ID, issue.Version, "", "", actor)
	if err != nil || task != nil || issue.AssigneeRef != "" || issue.AssigneeType != "" {
		t.Fatalf("unassign: %+v %v", issue, err)
	}
	due := time.Now().UTC().Truncate(time.Second)
	issue.DueAt = &due
	issue, err = s.Collaboration().UpdateIssue(ctx, issue, issue.Version, actor)
	if err != nil {
		t.Fatal(err)
	}
	issue, err = s.Collaboration().GetIssue(ctx, issue.ID)
	if err != nil || issue.DueAt == nil || !issue.DueAt.Equal(due) {
		t.Fatalf("deadline: %+v %v", issue, err)
	}
	issue.DueAt = nil
	issue, err = s.Collaboration().UpdateIssue(ctx, issue, issue.Version, actor)
	if err != nil || issue.DueAt != nil {
		t.Fatalf("clear deadline: %+v %v", issue, err)
	}
	issue, err = s.Collaboration().ArchiveIssue(ctx, issue.ID, issue.Version, actor)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := s.Collaboration().AssignIssue(ctx, issue.ID, issue.Version, controlmodel.AssigneeHuman, "owner", actor); err != store.ErrConflict {
		t.Fatalf("archived assignment: %v", err)
	}
}

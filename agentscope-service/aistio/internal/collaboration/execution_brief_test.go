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
	"strings"
	"testing"

	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func TestExecutionBriefSeparatesHumanConstraintsFromWorkerFailures(t *testing.T) {
	ctx := t.Context()
	st := openTestStore(t)
	svc := &Service{Store: st}
	issue, err := st.Collaboration().CreateIssue(ctx, &model.Issue{Tenant: "t", Namespace: "n", Title: "Latest phone developments", Description: "Research with fresh sources", Creator: model.Actor{Type: model.ActorHuman, Ref: "owner"}})
	if err != nil {
		t.Fatal(err)
	}
	add := func(actor model.ActorType, content string) *model.Comment {
		t.Helper()
		result, err := st.Collaboration().CreateComment(ctx, store.CreateCommentRequest{Comment: &model.Comment{IssueID: issue.ID, Author: model.Actor{Type: actor, Ref: "owner"}, Content: content}})
		if err != nil {
			t.Fatal(err)
		}
		return result.Comment
	}
	human := add(model.ActorHuman, "你直接根据自己的认知回答就好了")
	failure := add(model.ActorAgent, "MISSING_CREDENTIALS: ask for a key")
	newer := add(model.ActorHuman, "Now require verified sources again")
	envelope := &ContextEnvelope{Issue: issue, Task: &model.AgentTask{ID: uuid.New(), CreatedAt: failure.CreatedAt}, CurrentRequest: failure.Content}
	brief, err := svc.buildExecutionBrief(ctx, envelope)
	if err != nil {
		t.Fatal(err)
	}
	if len(brief.HumanRevisions) != 1 || brief.HumanRevisions[0].Content != human.Content || brief.TriggerInput != failure.Content || !strings.Contains(brief.OriginalObjective, issue.Description) {
		t.Fatalf("bad brief: %+v", brief)
	}
	// A later request belongs only to the task to which it was routed/coalesced.
	envelope.Inputs = []ContextInput{{Comment: newer}}
	brief, err = svc.buildExecutionBrief(ctx, envelope)
	if err != nil || len(brief.HumanRevisions) != 2 || brief.HumanRevisions[1].Content != newer.Content {
		t.Fatalf("lost updated requirement: %+v %v", brief, err)
	}
}

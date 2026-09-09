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
package httpapi

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"

	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func TestInboxIssueReviewDecisions(t *testing.T) {
	for _, action := range []string{"accept", "reject"} {
		t.Run(action, func(t *testing.T) {
			ctx := context.Background()
			st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
			if err != nil {
				t.Fatal(err)
			}
			defer st.Close()
			server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
			actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
			issue, err := st.Collaboration().CreateIssue(ctx, &model.Issue{Tenant: "t", Namespace: "n", Title: "Review result", Status: model.IssueInProgress, Creator: actor})
			if err != nil {
				t.Fatal(err)
			}
			issue, err = st.Collaboration().TransitionIssue(ctx, issue.ID, issue.Version, model.IssueInReview, model.Actor{Type: model.ActorAgent, Ref: "worker"}, "ready")
			if err != nil {
				t.Fatal(err)
			}
			call := func(body string, expected int) {
				t.Helper()
				req := httptest.NewRequest("POST", "/api/v1/issues/"+issue.ID.String()+"/"+action, strings.NewReader(body))
				req.Header.Set("Authorization", "Bearer console")
				req.Header.Set("Content-Type", "application/json")
				out := httptest.NewRecorder()
				server.router.ServeHTTP(out, req)
				if out.Code != expected {
					t.Fatalf("%s: got %d want %d: %s", action, out.Code, expected, out.Body.String())
				}
			}
			call(`{`, 400)
			call(`{}`, 400)
			if action == "reject" {
				call(`{"expectedVersion":2,"reason":"   "}`, 400)
			}
			call(`{"expectedVersion":999,"reason":"Add sources"}`, 409)
			unchanged, _ := st.Collaboration().GetIssue(ctx, issue.ID)
			if unchanged.Status != model.IssueInReview || unchanged.Version != issue.Version {
				t.Fatal("failed review mutated issue")
			}
			body, _ := json.Marshal(map[string]any{"expectedVersion": issue.Version, "reason": "Add dated sources"})
			call(string(body), 200)
			current, err := st.Collaboration().GetIssue(ctx, issue.ID)
			if err != nil {
				t.Fatal(err)
			}
			expected := model.IssueDone
			if action == "reject" {
				expected = model.IssueInProgress
			}
			if current.Status != expected || current.Version != issue.Version+1 {
				t.Fatal("review transition not saved")
			}
			messages, err := st.Collaboration().ListInbox(ctx, store.InboxFilter{Tenant: "t", Namespace: "n", RecipientRef: "owner", Type: "review_request", Archived: true})
			if err != nil {
				t.Fatal(err)
			}
			if len(messages) == 0 {
				t.Fatal("test did not create review notification")
			}
			for _, m := range messages {
				if m.NeedsAction || m.ResolvedAt == nil {
					t.Fatal("review notification remains actionable")
				}
			}
			activities, err := st.Collaboration().ListActivities(ctx, issue.ID, 100, 0)
			if err != nil {
				t.Fatal(err)
			}
			recorded := false
			for _, entry := range activities {
				if strings.Contains(string(entry.Details), "Add dated sources") {
					recorded = true
				}
			}
			if !recorded {
				t.Fatal("review feedback missing from activity")
			}
			call(string(body), 409)
			tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID})
			if err != nil || len(tasks) != 0 {
				t.Fatal("review unexpectedly dispatched work")
			}
		})
	}
}

func TestInboxReviewCannotBypassAcceptanceCriteria(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	issue, err := st.Collaboration().CreateIssue(ctx, &model.Issue{Tenant: "t", Namespace: "n", Title: "Incomplete research", Status: model.IssueInReview, Creator: model.Actor{Type: model.ActorHuman, Ref: "owner"}, AcceptanceCriteria: json.RawMessage(`{"requiredResult":true}`)})
	if err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(map[string]any{"expectedVersion": issue.Version})
	req := httptest.NewRequest("POST", "/api/v1/issues/"+issue.ID.String()+"/accept", strings.NewReader(string(body)))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer console")
	out := httptest.NewRecorder()
	server.router.ServeHTTP(out, req)
	if out.Code < 400 || !strings.Contains(out.Body.String(), "acceptance blocked") {
		t.Fatalf("missing acceptance guard: %d %s", out.Code, out.Body.String())
	}
	current, _ := st.Collaboration().GetIssue(ctx, issue.ID)
	if current.Status != model.IssueInReview {
		t.Fatal("invalid review completed issue")
	}
}

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

package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func TestTaskInputSummaryUsesRecordedVersion(t *testing.T) {
	for _, driver := range []string{"memory", "postgres"} {
		t.Run(driver, func(t *testing.T) {
			cfg := store.Config{Driver: store.DriverMemory}
			if driver == "postgres" {
				cfg = acceptancePostgresConfig(t)
			}
			ctx := context.Background()
			st, err := store.Open(ctx, cfg)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = st.Close() })
			actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
			issue, err := st.Collaboration().CreateIssue(ctx, &model.Issue{Tenant: "t", Namespace: "n", Title: "Input preview", Creator: actor})
			if err != nil {
				t.Fatal(err)
			}
			created, err := st.Collaboration().CreateComment(ctx, store.CreateCommentRequest{
				Comment: &model.Comment{IssueID: issue.ID, Tenant: "t", Namespace: "n", Author: actor, Content: "original input"},
				Targets: []store.CommentTarget{{TargetType: model.AssigneeAgent, TargetRef: "reviewer", AgentRef: "reviewer", RouteType: model.CommentRouteType("mention")}},
			})
			if err != nil {
				t.Fatal(err)
			}
			if len(created.Tasks) != 1 {
				t.Fatalf("tasks=%+v", created.Tasks)
			}
			server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
			check := func(state, content string) {
				t.Helper()
				req := httptest.NewRequest(http.MethodGet, "/api/v1/agent-tasks/"+created.Tasks[0].ID.String(), nil)
				req.Header.Set("Authorization", "Bearer console")
				res := httptest.NewRecorder()
				server.router.ServeHTTP(res, req)
				if res.Code != http.StatusOK {
					t.Fatalf("status=%d body=%s", res.Code, res.Body.String())
				}
				var body struct {
					Inputs []struct{ State, Content string } `json:"inputSummaries"`
				}
				if err := json.Unmarshal(res.Body.Bytes(), &body); err != nil {
					t.Fatal(err)
				}
				if len(body.Inputs) == 0 || body.Inputs[0].State != state || body.Inputs[0].Content != content {
					t.Fatalf("inputs=%+v", body.Inputs)
				}
			}
			check("recorded", "original input")
			updated := *created.Comment
			updated.Content = "edited later"
			comment, err := st.Collaboration().UpdateComment(ctx, &updated, updated.Version)
			if err != nil {
				t.Fatal(err)
			}
			check("changed", "")
			if _, err := st.Collaboration().DeleteComment(ctx, comment.ID, comment.Version, actor); err != nil {
				t.Fatal(err)
			}
			check("unavailable", "")
		})
	}
}

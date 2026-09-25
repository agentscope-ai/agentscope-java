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
	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/postgres"
	"os"
	"slices"
	"strings"
	"testing"
)

func TestReviewFeedbackPreservesWorkUntilExplicitNewRequest(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
			if driver == store.DriverPostgres && dsn == "" {
				t.Skip("PostgreSQL DSN not set")
			}
			ctx := context.Background()
			st, err := store.Open(ctx, store.Config{Driver: driver, PostgresDSN: dsn})
			if err != nil {
				t.Fatal(err)
			}
			defer st.Close()
			svc := &Service{Store: st}
			tenant := "review-" + uuid.NewString()
			team, err := st.Collaboration().CreateTeam(ctx, &model.CollaborationTeam{Tenant: tenant, Namespace: "default", Name: "review", LeaderAgentRef: "lead", Policy: model.TeamPolicy{MaxChildDepth: 4, MaxChildIssues: 4}})
			if err != nil {
				t.Fatal(err)
			}
			_, err = st.Collaboration().AddTeamMember(ctx, &model.CollaborationTeamMember{TeamID: team.ID, AgentRef: "worker", Role: "worker"})
			if err != nil {
				t.Fatal(err)
			}
			issue, initial, err := svc.CreateIssue(ctx, CreateIssueRequest{Tenant: tenant, Namespace: "default", Title: "original task", Creator: model.Actor{Type: model.ActorHuman, Ref: "owner"}, AssigneeType: model.AssigneeTeam, AssigneeRef: team.ID.String()})
			if err != nil {
				t.Fatal(err)
			}
			initial = startRegressionTask(t, st, initial)
			completeRegressionTask(t, svc, initial, "original delivered result", nil)
			run, err := st.Orchestration().GetRun(ctx, initial.OrchestrationRunID)
			if err != nil {
				t.Fatal(err)
			}
			_, err = st.Orchestration().TransitionRun(ctx, run.ID, run.Version, model.RunSucceeded, nil, "", "")
			if err != nil {
				t.Fatal(err)
			}
			issue, err = st.Collaboration().GetIssue(ctx, issue.ID)
			if err != nil {
				t.Fatal(err)
			}
			issue, err = st.Collaboration().TransitionIssue(ctx, issue.ID, issue.Version, model.IssueInReview, issue.Creator, "review delivered work")
			if err != nil {
				t.Fatal(err)
			}
			for _, status := range []model.IssueStatus{model.IssueInReview, model.IssueDone} {
				if status == model.IssueDone {
					issue, err = st.Collaboration().GetIssue(ctx, issue.ID)
					if err == nil {
						issue, err = st.Collaboration().TransitionIssue(ctx, issue.ID, issue.Version, status, issue.Creator, "accepted")
					}
					if err != nil {
						t.Fatal(err)
					}
				}
				reply, err := svc.AddComment(ctx, AddCommentRequest{IssueID: issue.ID, Author: issue.Creator, Content: "非常好"})
				if err != nil || len(reply.Tasks) != 1 {
					t.Fatalf("reply=%+v err=%v", reply, err)
				}
				task := startRegressionTask(t, st, &reply.Tasks[0])
				if task.TriggerType != model.AgentTaskReviewComment {
					t.Fatal(task.TriggerType)
				}
				current, err := st.Collaboration().GetIssue(ctx, issue.ID)
				if err != nil || current.Status != status {
					t.Fatalf("feedback reopened work: %+v %v", current, err)
				}
				context, err := svc.BuildContext(ctx, task.ID)
				if err != nil || context.CurrentRequest != "非常好" || len(context.ReviewResults) == 0 || !slices.Contains(context.AvailableActions, "task.begin_work") {
					t.Fatalf("lost review context: %+v %v", context, err)
				}
				if _, _, err = svc.CreateChildFromTask(ctx, task.ID, CreateIssueRequest{Title: "duplicate", AssigneeType: model.AssigneeAgent, AssigneeRef: "worker"}); err == nil {
					t.Fatal("feedback permitted delegation")
				}
				if _, err = st.Collaboration().BeginReviewWork(ctx, task.ID, task.Version, "original task"); err == nil {
					t.Fatal("old requirements accepted as new request")
				}
				ack := completeRegressionTask(t, svc, task, "感谢认可。", nil)
				if ack.Type != model.CommentGeneral {
					t.Fatal("feedback replaced a deliverable with a result comment")
				}
				current, err = st.Collaboration().GetIssue(ctx, issue.ID)
				if err != nil || current.Status != status {
					t.Fatalf("feedback completion changed work: %+v %v", current, err)
				}
				completedRun, err := st.Orchestration().GetRun(ctx, task.OrchestrationRunID)
				if err != nil || completedRun.State != model.RunSucceeded {
					t.Fatalf("feedback did not end: %+v %v", completedRun, err)
				}
				if err = svc.EnsureTerminalTeamSummary(ctx, completedRun); err != nil {
					t.Fatal(err)
				}
				comments, err := st.Collaboration().ListComments(ctx, issue.ID, store.CommentListOptions{Limit: 50})
				if err != nil {
					t.Fatal(err)
				}
				resultCount := 0
				for _, c := range comments {
					if c.Type == model.CommentResult {
						resultCount++
					}
				}
				if resultCount != 1 {
					t.Fatal("feedback created a coordinator summary")
				}

				tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{RunID: task.OrchestrationRunID, Limit: 20})
				if err != nil || len(tasks) != 1 {
					t.Fatalf("feedback pingpong: %+v %v", tasks, err)
				}
			}
			change, err := svc.AddComment(ctx, AddCommentRequest{IssueID: issue.ID, Author: issue.Creator, Content: "请新增计算 7×8，只补充这一项"})
			if err != nil || len(change.Tasks) != 1 {
				t.Fatalf("change=%+v err=%v", change, err)
			}
			task := startRegressionTask(t, st, &change.Tasks[0])
			task, err = st.Collaboration().BeginReviewWork(ctx, task.ID, task.Version, change.Comment.Content)
			if err != nil {
				t.Fatal(err)
			}
			current, err := st.Collaboration().GetIssue(ctx, issue.ID)
			if err != nil || current.Status != model.IssueInProgress || task.TriggerType != "comment" {
				t.Fatalf("new work not activated: %+v %v", current, err)
			}
			envelope, contextErr := svc.BuildContext(ctx, task.ID)
			if contextErr != nil || envelope.CoordinatorIssue == nil || !strings.Contains(envelope.CoordinatorIssue.Description, change.Comment.Content) {
				t.Fatalf("new request missing from follow-up context: %+v %v", envelope, contextErr)
			}
			_, _, err = svc.CreateChildFromTask(ctx, task.ID, CreateIssueRequest{Title: "only new calculation", AssigneeType: model.AssigneeAgent, AssigneeRef: "worker"})
			if err != nil {
				t.Fatal(err)
			}
		})
	}
}

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

package collaboration_test

import (
	"context"
	"os"
	"strings"
	"sync"
	"testing"

	"github.com/google/uuid"
	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/postgres"
)

func TestCoordinatorSummaryConcurrentRecovery(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
			if driver == store.DriverPostgres && dsn == "" {
				t.Skip("AISTIO_TEST_POSTGRES_DSN not set")
			}
			ctx := context.Background()
			st, err := store.Open(ctx, store.Config{Driver: driver, PostgresDSN: dsn})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = st.Close() })
			tenant := "summary-" + uuid.NewString()
			team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{Tenant: tenant, Namespace: "default", Name: "summary-team", LeaderAgentRef: "leader"})
			if err != nil {
				t.Fatal(err)
			}
			svc := &collaboration.Service{Store: st}
			root, task, err := svc.CreateIssue(ctx, collaboration.CreateIssueRequest{Tenant: tenant, Namespace: "default", Title: "root summary recovery", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String()})
			if err != nil {
				t.Fatal(err)
			}
			_, err = st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: tenant, Namespace: "default", Title: "completed poem", Status: controlmodel.IssueDone, ParentIssueID: &root.ID, Creator: root.Creator})
			if err != nil {
				t.Fatal(err)
			}
			_, err = st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: tenant, Namespace: "default", Title: "unfinished research", Status: controlmodel.IssueBlocked, ParentIssueID: &root.ID, Creator: root.Creator})
			if err != nil {
				t.Fatal(err)
			}
			run, err := st.Orchestration().GetRun(ctx, task.OrchestrationRunID)
			if err != nil {
				t.Fatal(err)
			}
			run, err = st.Orchestration().TransitionRun(ctx, run.ID, run.Version, controlmodel.RunFailed, nil, "MISSING_KEY", "configure web key")
			if err != nil {
				t.Fatal(err)
			}
			var wg sync.WaitGroup
			for range 12 {
				wg.Add(1)
				go func() {
					defer wg.Done()
					if err := svc.EnsureTerminalTeamSummary(ctx, run); err != nil {
						t.Error(err)
					}
				}()
			}
			wg.Wait()
			comments, err := st.Collaboration().ListComments(ctx, root.ID, store.CommentListOptions{Limit: 100})
			if err != nil || len(comments) != 1 {
				t.Fatalf("comments=%d err=%v", len(comments), err)
			}
			for _, want := range []string{"completed poem", "unfinished research", "MISSING_KEY", "configure web key", "下一步", "blocked"} {
				if !strings.Contains(comments[0].Content, want) {
					t.Fatalf("missing %s in %s", want, comments[0].Content)
				}
			}
			tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{RunID: run.ID, Limit: 100})
			if err != nil || len(tasks) != 1 || len(comments[0].Routes) != 0 {
				t.Fatalf("summary routed new work: tasks=%d err=%v", len(tasks), err)
			}
		})
	}
}

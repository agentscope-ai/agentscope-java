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

package orchestration

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"testing"

	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func TestCoordinatorRootSummaryIncludesChildrenAndPrecedesStatus(t *testing.T) {
	for _, scenario := range []string{"success", "blocked", "human_recovery"} {
		t.Run(scenario, func(t *testing.T) {
			failure := scenario == "blocked"
			ctx := context.Background()
			st, svc, engine, root, leader, child, worker := setupTeamFailureRun(t, 0)
			worker = startTask(t, st, worker)
			if _, _, err := svc.CompleteTask(ctx, worker.ID, store.TaskCompletion{ExpectedVersion: worker.Version, Summary: "energy report", Result: json.RawMessage(`"retained research result"`)}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "worker"}); err != nil {
				t.Fatal(err)
			}
			tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: child.ID, AgentRef: "leader", Status: controlmodel.AgentTaskQueued, Limit: 20})
			if err != nil || len(tasks) != 1 {
				t.Fatalf("followups=%v err=%v", tasks, err)
			}
			follow := startTask(t, st, tasks[0])
			if _, err = svc.AcceptIssueFromTask(ctx, follow.ID, "report verified"); err != nil {
				t.Fatal(err)
			}
			if scenario == "human_recovery" {
				root, err = st.Collaboration().GetIssue(ctx, root.ID)
				if err == nil {
					root, err = st.Collaboration().TransitionIssue(ctx, root.ID, root.Version, controlmodel.IssueBlocked, root.Creator, "awaiting human input")
				}
				if err != nil {
					t.Fatal(err)
				}
			}
			if failure {
				_, err = svc.FailTask(ctx, follow.ID, follow.Version, "MISSING_WEB_KEY", "need configured web key for remaining research")
				if err == nil {
					err = engine.ReconcileRun(ctx, leader.OrchestrationRunID)
				}
			} else {
				follow, _, err = svc.CompleteTask(ctx, follow.ID, store.TaskCompletion{ExpectedVersion: follow.Version, Result: json.RawMessage(`"all research delivered"`)}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"})
				if err == nil {
					_, err = (&Service{Store: st}).CompleteCoordinatorNode(ctx, follow.ID, json.RawMessage(`"all research delivered"`), controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"})
				}
			}
			if err != nil {
				t.Fatal(err)
			}
			root, err = st.Collaboration().GetIssue(ctx, root.ID)
			if err != nil {
				t.Fatal(err)
			}
			want := controlmodel.IssueInReview
			if failure {
				want = controlmodel.IssueBlocked
			}
			if root.Status != want {
				t.Fatalf("status=%s", root.Status)
			}
			id := uuid.NewSHA1(leader.OrchestrationRunID, []byte("root-coordinator-summary-v1"))
			comment, err := st.Collaboration().GetComment(ctx, id)
			if err != nil {
				t.Fatal(err)
			}
			for _, text := range []string{"research energy", "retained research result", "下一步", string(want)} {
				if !strings.Contains(comment.Content, text) {
					t.Fatalf("missing %q: %s", text, comment.Content)
				}
			}
			if failure && !strings.Contains(comment.Content, "MISSING_WEB_KEY") {
				t.Fatal(comment.Content)
			}
			if comment.CreatedAt.After(root.UpdatedAt) {
				t.Fatalf("summary appeared after status: %+v %+v", comment, root)
			}
			var wg sync.WaitGroup
			for range 8 {
				wg.Add(1)
				go func() {
					defer wg.Done()
					if err := engine.ReconcileRun(ctx, leader.OrchestrationRunID); err != nil {
						t.Error(err)
					}
				}()
			}
			wg.Wait()
			comments, err := st.Collaboration().ListComments(ctx, root.ID, store.CommentListOptions{Limit: 100})
			if err != nil {
				t.Fatal(err)
			}
			count := 0
			for _, c := range comments {
				if c.ID == id {
					count++
					if len(c.Routes) != 0 {
						t.Fatal("summary scheduled more work")
					}
				}
			}
			if count != 1 {
				t.Fatalf("summaries=%d", count)
			}
			if scenario == "human_recovery" {
				_, err = st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{Tenant: root.Tenant, Namespace: root.Namespace, RootIssueID: root.ID, Mode: controlmodel.RunModeAdaptive, State: controlmodel.RunRunning, CreatedBy: root.Creator})
				if err == nil {
					root, err = st.Collaboration().TransitionIssue(ctx, root.ID, root.Version, controlmodel.IssueBlocked, root.Creator, "new requirement needs human input")
				}
				if err == nil {
					err = engine.ReconcileRun(ctx, leader.OrchestrationRunID)
				}
				if err != nil {
					t.Fatal(err)
				}
				root, err = st.Collaboration().GetIssue(ctx, root.ID)
				if err != nil || root.Status != controlmodel.IssueBlocked {
					t.Fatalf("old completion advanced a newer Run: root=%+v err=%v", root, err)
				}
			}
		})
	}
}

type summaryFailStore struct{ store.Store }
type summaryFailRepo struct{ store.CollaborationRepository }

func (s summaryFailStore) Collaboration() store.CollaborationRepository {
	return summaryFailRepo{s.Store.Collaboration()}
}
func (s summaryFailRepo) CreateComment(context.Context, store.CreateCommentRequest) (*store.CreateCommentResult, error) {
	return nil, errors.New("summary storage unavailable")
}

func TestTerminalRunRetriesSummaryBeforeBlockingRoot(t *testing.T) {
	ctx := context.Background()
	st, _, _, root, leader, _, _ := setupTeamFailureRun(t, 0)
	run, err := st.Orchestration().GetRun(ctx, leader.OrchestrationRunID)
	if err != nil {
		t.Fatal(err)
	}
	run, err = st.Orchestration().TransitionRun(ctx, run.ID, run.Version, controlmodel.RunFailed, nil, "runtime_failure", "runtime disconnected")
	if err != nil {
		t.Fatal(err)
	}
	engine := &Engine{Store: summaryFailStore{st}}
	if err = engine.ReconcileRun(ctx, run.ID); err == nil || !strings.Contains(err.Error(), "summary storage unavailable") {
		t.Fatalf("err=%v", err)
	}
	issue, _ := st.Collaboration().GetIssue(ctx, root.ID)
	if issue.Status != controlmodel.IssueInProgress {
		t.Fatalf("status advanced without summary: %s", issue.Status)
	}
	engine.Store = st
	if err = engine.ReconcileRun(ctx, run.ID); err != nil {
		t.Fatal(err)
	}
	issue, _ = st.Collaboration().GetIssue(ctx, root.ID)
	if issue.Status != controlmodel.IssueBlocked {
		t.Fatal(issue.Status)
	}
	comment, err := st.Collaboration().GetComment(ctx, uuid.NewSHA1(run.ID, []byte("root-coordinator-summary-v1")))
	if err != nil || comment.Author.Type != controlmodel.ActorSystem {
		t.Fatalf("fallback attribution: %+v %v", comment, err)
	}
	_, err = st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{Tenant: run.Tenant, Namespace: run.Namespace, RootIssueID: root.ID, Mode: controlmodel.RunModeAdaptive, State: controlmodel.RunRunning, CreatedBy: run.CreatedBy})
	if err != nil {
		t.Fatal(err)
	}
	issue, err = st.Collaboration().TransitionIssue(ctx, issue.ID, issue.Version, controlmodel.IssueInProgress, run.CreatedBy, "recovery started")
	if err != nil {
		t.Fatal(err)
	}
	if err = engine.ReconcileRun(ctx, run.ID); err != nil {
		t.Fatal(err)
	}
	issue, _ = st.Collaboration().GetIssue(ctx, root.ID)
	if issue.Status != controlmodel.IssueInProgress {
		t.Fatal("old failure blocked the recovery run")
	}

}

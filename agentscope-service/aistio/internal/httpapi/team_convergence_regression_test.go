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
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/orchestration"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func TestTeamMCPConvergesAfterReviewingEachWorkerOutcome(t *testing.T) {
	for _, blocked := range []bool{false, true} {
		name := "two successful workers"
		if blocked {
			name = "missing required capability"
		}
		t.Run(name, func(t *testing.T) {
			ctx := context.Background()
			st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = st.Close() })
			svc := &collaboration.Service{Store: st}
			srv := NewServer(ServerOptions{Store: st})
			team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{Tenant: "t", Namespace: "n", Name: name, LeaderAgentRef: "leader", Policy: controlmodel.TeamPolicy{MaxChildDepth: 4, MaxChildIssues: 4}})
			if err != nil {
				t.Fatal(err)
			}
			for _, agent := range []string{"worker-a", "worker-b"} {
				if _, err := st.Collaboration().AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{ID: uuid.New(), TeamID: team.ID, AgentRef: agent, Role: agent}); err != nil {
					t.Fatal(err)
				}
			}
			_, lead, err := svc.CreateIssue(ctx, collaboration.CreateIssueRequest{Tenant: "t", Namespace: "n", Title: name, Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String()})
			if err != nil {
				t.Fatal(err)
			}
			start := func(task *controlmodel.AgentTask) *controlmodel.AgentTask {
				t.Helper()
				if err := (&orchestration.Engine{Store: st}).ReconcileRun(ctx, task.OrchestrationRunID); err != nil {
					t.Fatal(err)
				}
				current, err := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version})
				if err == nil {
					current, err = st.Collaboration().StartAgentTask(ctx, current.ID, current.Version)
				}
				if err != nil {
					t.Fatal(err)
				}
				return current
			}
			call := func(task *controlmodel.AgentTask, name string, args map[string]any) error {
				c, _ := gin.CreateTestContext(httptest.NewRecorder())
				c.Request = httptest.NewRequest(http.MethodPost, "/mcp/collaboration", nil)
				_, err := srv.callCollaborationMCPTool(c, task, name, args)
				return err
			}
			lead = start(lead)
			var workers []*controlmodel.AgentTask
			for _, agent := range []string{"worker-a", "worker-b"} {
				_, worker, err := svc.CreateChildFromTask(ctx, lead.ID, collaboration.CreateIssueRequest{Title: agent, AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: agent})
				if err != nil {
					t.Fatal(err)
				}
				workers = append(workers, worker)
				if blocked {
					break
				}
			}
			if err := call(lead, "task.complete", map[string]any{"outcome": "succeeded", "summary": "delegated"}); err != nil {
				t.Fatal(err)
			}
			for _, worker := range workers {
				worker = start(worker)
				outcome := "succeeded"
				if blocked {
					outcome = "blocked"
				}
				if err := call(worker, "task.complete", map[string]any{"outcome": outcome, "summary": "worker delivery", "result": map[string]any{"answer": 42}, "code": "missing_tool", "message": "search unavailable"}); err != nil {
					t.Fatal(err)
				}
			}
			for i, worker := range workers {
				// Production reconciliation runs again after the terminal task event.
				if err := (&orchestration.Engine{Store: st}).ReconcileRun(ctx, lead.OrchestrationRunID); err != nil {
					t.Fatal(err)
				}
				follows, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: worker.IssueID, AgentRef: "leader", Status: controlmodel.AgentTaskQueued, Limit: 10})
				if err != nil || len(follows) != 1 {
					node, _ := st.Orchestration().GetNode(ctx, worker.RunNodeID)
					current, _ := st.Collaboration().GetAgentTask(ctx, worker.ID)
					t.Fatalf("follow-ups: %+v %v; worker=%+v node=%+v", follows, err, current, node)
				}
				follow := start(follows[0])
				if err := call(follow, "task.complete", map[string]any{"outcome": "succeeded"}); err == nil {
					t.Fatal("unreviewed child was allowed to wait for a sibling")
				}
				if blocked {
					envelope, err := svc.BuildContext(ctx, follow.ID)
					if err != nil || len(envelope.CoordinatorChildren) != 1 || len(envelope.CoordinatorChildren[0].Outcomes) != 1 {
						t.Fatalf("failure context: %+v %v", envelope, err)
					}
					if outcome := envelope.CoordinatorChildren[0].Outcomes[0]; outcome.Status != controlmodel.AgentTaskFailed || outcome.ErrorCode != "missing_tool" {
						t.Fatalf("failure became a usable result: %+v", outcome)
					}
					if err := call(follow, "issue.accept", nil); err == nil {
						t.Fatal("failed worker without a usable result was accepted")
					}
					if err := call(follow, "run.node.fail", map[string]any{"code": "missing_tool", "message": "required evidence unavailable"}); err != nil {
						t.Fatal(err)
					}
					break
				}
				if err := call(follow, "issue.accept", map[string]any{"reason": "verified structured result"}); err != nil {
					t.Fatal(err)
				}
				if i < len(workers)-1 {
					if err := call(follow, "task.complete", map[string]any{"outcome": "succeeded", "summary": "accepted current child"}); err != nil {
						t.Fatal(err)
					}
				} else {
					if err := call(follow, "task.complete", map[string]any{"outcome": "succeeded"}); err == nil {
						t.Fatal("last coordinator left without a terminal node decision")
					}
					if err := call(follow, "run.node.complete", map[string]any{"output": json.RawMessage(`{"combined":84}`)}); err != nil {
						t.Fatal(err)
					}
				}
			}
			run, err := st.Orchestration().GetRun(ctx, lead.OrchestrationRunID)
			if err != nil || !controlmodel.IsOrchestrationRunTerminal(run.State) {
				t.Fatalf("run did not converge: %+v %v", run, err)
			}
			want := "succeeded"
			if blocked {
				want = "failed"
			}
			if string(run.State) != want {
				t.Fatalf("run state=%s want %s", run.State, want)
			}
		})
	}
}

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
	"os"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/orchestration"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/postgres"
)

func waitTestStart(t *testing.T, st store.Store, task *controlmodel.AgentTask) *controlmodel.AgentTask {
	t.Helper()
	ctx := context.Background()
	current, _, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx, store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version, RuntimeBinding: json.RawMessage(`{}`)}, &controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged, State: controlmodel.ExecutionAssigned, RuntimeBinding: json.RawMessage(`{}`)})
	if err == nil {
		current, err = st.Collaboration().StartAgentTask(ctx, current.ID, current.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	return current
}
func waitTestCall(srv *Server, task *controlmodel.AgentTask, name string, args map[string]any) (any, error) {
	c, _ := gin.CreateTestContext(httptest.NewRecorder())
	c.Request = httptest.NewRequest(http.MethodPost, "/mcp/collaboration", nil)
	return srv.callCollaborationMCPTool(c, task, name, args)
}
func TestLeaderWaitPreservesWorkerAndResumesToRootSummary(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			for _, outcome := range []string{"waiting", "blocked", "explicit_abort"} {
				for _, early := range []bool{false, true} {
					name := outcome
					if early {
						name += "_worker_already_finished"
					}
					t.Run(name, func(t *testing.T) {
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
						tenant := "wait-" + uuid.NewString()
						leadID, workerID := uuid.NewString(), uuid.NewString()
						team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{Tenant: tenant, Namespace: "default", Name: "wait-team", LeaderAgentRef: leadID, Policy: controlmodel.TeamPolicy{MaxChildDepth: 4, MaxChildIssues: 4}})
						if err != nil {
							t.Fatal(err)
						}
						if _, err = st.Collaboration().AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{TeamID: team.ID, AgentRef: workerID, Role: "researcher"}); err != nil {
							t.Fatal(err)
						}
						svc := &collaboration.Service{Store: st}
						engine := &orchestration.Engine{Store: st}
						srv := NewServer(ServerOptions{Store: st})
						root, lead, err := svc.CreateIssue(ctx, collaboration.CreateIssueRequest{Tenant: tenant, Namespace: "default", Title: "verify follow-up delivery", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "admin"}, AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String()})
						if err != nil {
							t.Fatal(err)
						}
						lead = waitTestStart(t, st, lead)
						if _, err = waitTestCall(srv, lead, "task.complete", map[string]any{"outcome": "waiting"}); err == nil {
							t.Fatal("leader waited without a durable wake source")
						}
						child, worker, err := svc.CreateChildFromTask(ctx, lead.ID, collaboration.CreateIssueRequest{Title: "worker report", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: workerID})
						if err != nil {
							t.Fatal(err)
						}
						actor := controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: leadID}
						if _, _, err = svc.CompleteTask(ctx, lead.ID, store.TaskCompletion{ExpectedVersion: lead.Version, Summary: "delegated"}, actor); err != nil {
							t.Fatal(err)
						}
						worker = waitTestStart(t, st, worker)
						if _, _, err = svc.CompleteTask(ctx, worker.ID, store.TaskCompletion{ExpectedVersion: worker.Version, Summary: "first draft", Result: json.RawMessage(`"draft"`)}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: workerID}); err != nil {
							t.Fatal(err)
						}
						findLeader := func() *controlmodel.AgentTask {
							tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{RunID: lead.OrchestrationRunID, AgentRef: leadID, Status: controlmodel.AgentTaskQueued, Limit: 20})
							if err != nil || len(tasks) != 1 {
								t.Fatalf("expected one leader wake, got %d: %v", len(tasks), err)
							}
							return tasks[0]
						}
						follow := waitTestStart(t, st, findLeader())
						routed, err := svc.AddComment(ctx, collaboration.AddCommentRequest{IssueID: child.ID, Author: actor, Type: controlmodel.CommentStatus, Content: "Please provide the verified result as FOLLOWUP_OK_144", Mentions: []collaboration.MentionTarget{{Type: controlmodel.AssigneeAgent, Ref: workerID}}, SourceTaskID: &follow.ID})
						if err != nil || len(routed.Tasks) != 1 {
							t.Fatalf("mention: %+v %v", routed, err)
						}
						nextWorker := waitTestStart(t, st, &routed.Tasks[0])
						if _, err = waitTestCall(srv, nextWorker, "task.complete", map[string]any{"outcome": "waiting"}); err == nil {
							t.Fatal("worker accepted leader-only waiting outcome")
						}
						finishWorker := func() {
							var e error
							nextWorker, _, e = svc.CompleteTask(ctx, nextWorker.ID, store.TaskCompletion{ExpectedVersion: nextWorker.Version, Result: json.RawMessage(`"FOLLOWUP_OK_144"`)}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: workerID})
							if e != nil {
								t.Fatal(e)
							}
						}
						if early {
							finishWorker()
						}
						// Generic failure is ambiguous while an owned wake is pending. It must be
						// rejected without mutating any execution; explicit run.node.fail remains available.
						if _, err = waitTestCall(srv, follow, "task.fail", map[string]any{"code": "objective_blocked", "message": "waiting for worker"}); err == nil || !strings.Contains(err.Error(), "outcome=waiting") {
							t.Fatalf("unsafe generic failure accepted: %v", err)
						}
						if outcome == "explicit_abort" {
							if _, err = waitTestCall(srv, follow, "run.node.fail", map[string]any{"code": "unrecoverable", "message": "explicitly abort all remaining work"}); err != nil {
								t.Fatal(err)
							}
							run, _ := st.Orchestration().GetRun(ctx, lead.OrchestrationRunID)
							stopped, _ := st.Collaboration().GetAgentTask(ctx, nextWorker.ID)
							if run.State != controlmodel.RunFailed || (!early && stopped.Status != controlmodel.AgentTaskCancelled) || (early && stopped.Status != controlmodel.AgentTaskCompleted) {
								t.Fatalf("explicit failure did not converge: run=%s worker=%s", run.State, stopped.Status)
							}
							return
						}
						value, err := waitTestCall(srv, follow, "task.complete", map[string]any{"outcome": outcome, "message": "waiting for worker reply"})
						if err != nil {
							t.Fatal(err)
						}
						if value.(map[string]any)["outcome"] != "waiting" {
							t.Fatal(value)
						}
						if err = engine.ReconcileRun(ctx, lead.OrchestrationRunID); err != nil {
							t.Fatal(err)
						}
						currentWorker, _ := st.Collaboration().GetAgentTask(ctx, nextWorker.ID)
						if (!early && currentWorker.Status != controlmodel.AgentTaskRunning) || (early && currentWorker.Status != controlmodel.AgentTaskCompleted) {
							t.Fatalf("worker interrupted: %+v", currentWorker)
						}
						currentLead, _ := st.Collaboration().GetAgentTask(ctx, follow.ID)
						attempt, _ := st.ExecutionAttempts().Get(ctx, *follow.CurrentAttemptID)
						node, _ := st.Orchestration().GetNode(ctx, follow.RunNodeID)
						run, _ := st.Orchestration().GetRun(ctx, lead.OrchestrationRunID)
						currentRoot, _ := st.Collaboration().GetIssue(ctx, root.ID)
						if currentLead.Status != controlmodel.AgentTaskCompleted || attempt.State != controlmodel.ExecutionSucceeded || node.State != controlmodel.RunNodeWaiting || controlmodel.IsOrchestrationRunTerminal(run.State) || currentRoot.Status != controlmodel.IssueInProgress {
							t.Fatalf("yield states: task=%s attempt=%s node=%s run=%s root=%s", currentLead.Status, attempt.State, node.State, run.State, currentRoot.Status)
						}
						for _, input := range currentLead.Inputs {
							if input.State != controlmodel.TaskInputProcessed {
								t.Fatalf("input not settled: %+v", input)
							}
						}
						comments, _ := st.Collaboration().ListComments(ctx, child.ID, store.CommentListOptions{Limit: 100})
						for _, comment := range comments {
							if comment.SourceTaskID != nil && *comment.SourceTaskID == follow.ID && comment.Content == "waiting for worker reply" && (len(comment.Routes) != 0 || comment.Type != controlmodel.CommentStatus) {
								t.Fatal("waiting status woke another task")
							}
						}
						if !early {
							finishWorker()
						}
						final := waitTestStart(t, st, findLeader())
						if _, err = svc.AcceptIssueFromTask(ctx, final.ID, "FOLLOWUP_OK_144 verified"); err != nil {
							t.Fatal(err)
						}
						if _, err = waitTestCall(srv, final, "run.node.complete", map[string]any{"output": "FOLLOWUP_OK_144 accepted; all work complete"}); err != nil {
							t.Fatal(err)
						}
						run, _ = st.Orchestration().GetRun(ctx, lead.OrchestrationRunID)
						currentRoot, _ = st.Collaboration().GetIssue(ctx, root.ID)
						if run.State != controlmodel.RunSucceeded || currentRoot.Status != controlmodel.IssueInReview {
							t.Fatalf("not converged: %s %s", run.State, currentRoot.Status)
						}
						tasks, _ := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{RunID: run.ID, Limit: 100})
						if len(tasks) != 5 {
							t.Fatalf("unexpected wake loop: tasks=%d", len(tasks))
						}
						for _, task := range tasks {
							execution, loadErr := st.ExecutionAttempts().Get(ctx, *task.CurrentAttemptID)
							if loadErr != nil || execution.State != controlmodel.ExecutionSucceeded {
								t.Fatalf("execution not converged: %+v %v", execution, loadErr)
							}
							if task.Status != controlmodel.AgentTaskCompleted {
								t.Fatalf("unfinished task: %+v", task)
							}
						}
						summary, err := st.Collaboration().GetComment(ctx, uuid.NewSHA1(run.ID, []byte("root-coordinator-summary-v1")))
						if err != nil || !strings.Contains(summary.Content, "FOLLOWUP_OK_144") || summary.CreatedAt.After(currentRoot.UpdatedAt) {
							t.Fatalf("root summary: %+v %v", summary, err)
						}
					})
				}
			}
		})
	}
}

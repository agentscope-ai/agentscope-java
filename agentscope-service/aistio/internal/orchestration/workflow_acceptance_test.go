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

package orchestration

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/postgres"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestWorkflowAcceptance(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(string(driver), func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
			defer cancel()
			cfg := store.Config{Driver: driver, MaxOpenConns: 4}
			if driver == store.DriverPostgres {
				dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
				if dsn == "" {
					t.Skip("AISTIO_TEST_POSTGRES_DSN not set")
				}
				conn, err := pgx.Connect(ctx, dsn)
				if err != nil {
					t.Fatal(err)
				}
				defer conn.Close(context.Background())
				schema := "workflow_test_" + fmt.Sprintf("%x", time.Now().UnixNano())
				if _, err = conn.Exec(ctx, `CREATE SCHEMA `+pgx.Identifier{schema}.Sanitize()); err != nil {
					t.Fatal(err)
				}
				defer conn.Exec(context.Background(), `DROP SCHEMA `+pgx.Identifier{schema}.Sanitize()+` CASCADE`)
				pc, err := pgx.ParseConfig(dsn)
				if err != nil {
					t.Fatal(err)
				}
				cfg.PostgresDSN = pc.ConnString() + "&search_path=" + schema
			}
			st, err := store.Open(ctx, cfg)
			if err != nil {
				t.Fatal(err)
			}
			defer st.Close()
			if err = st.Migrate(ctx); err != nil {
				t.Fatal(err)
			}
			svc := &Service{Store: st}
			actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "workflow-test"}
			makeDefinition := func(raw string) *controlmodel.OrchestrationDefinition {
				t.Helper()
				d, err := st.Orchestration().CreateDefinition(ctx, &controlmodel.OrchestrationDefinition{Tenant: "workflow-test", Namespace: "default", Name: uuid.NewString(), DraftSpec: json.RawMessage(raw), CreatedBy: actor})
				if err != nil {
					t.Fatal(err)
				}
				if _, err = svc.Publish(ctx, d.ID, actor); err != nil {
					t.Fatal(err)
				}
				return d
			}
			request := func(key string) StartRequest {
				return StartRequest{IdempotencyKey: key, Issue: &controlmodel.Issue{Title: key, Creator: actor}, Input: json.RawMessage(`{"value":42}`), Actor: actor}
			}
			t.Run("concurrent_start_is_idempotent", func(t *testing.T) {
				d := makeDefinition(`{"nodes":[{"key":"gate","type":"signal","signalName":"ready"}]}`)
				ids := make(chan uuid.UUID, 8)
				errs := make(chan error, 8)
				var wg sync.WaitGroup
				for i := 0; i < 8; i++ {
					wg.Add(1)
					go func() {
						defer wg.Done()
						run, err := svc.Start(ctx, d.ID, request("same-key"))
						if err != nil {
							errs <- err
						} else {
							ids <- run.ID
						}
					}()
				}
				wg.Wait()
				close(ids)
				close(errs)
				for err := range errs {
					t.Error(err)
				}
				unique := map[uuid.UUID]bool{}
				for id := range ids {
					unique[id] = true
				}
				if len(unique) != 1 {
					t.Fatalf("Runs=%v", unique)
				}
				issues, err := st.Collaboration().ListIssues(ctx, store.IssueFilter{Tenant: "workflow-test", Namespace: "default", Limit: 100})
				if err != nil || len(issues) != 1 {
					t.Fatalf("Issues=%d err=%v", len(issues), err)
				}
			})
			t.Run("pause_early_signal_resume_and_payload", func(t *testing.T) {
				d := makeDefinition(`{"nodes":[{"key":"delay","type":"timer","timer":{"durationSeconds":3600}},{"key":"gate","type":"signal","signalName":"ready"},{"key":"read","type":"condition","input":{"answer":"nodes.gate.output.value"}}],"edges":[{"from":"delay","to":"gate"},{"from":"gate","to":"read"}]}`)
				run, err := svc.Start(ctx, d.ID, request("pause-signal"))
				if err != nil {
					t.Fatal(err)
				}
				if _, err = svc.Pause(ctx, run.ID); err != nil {
					t.Fatal(err)
				}
				if err = svc.Signal(ctx, run.ID, "ready", "one", json.RawMessage(`{"value":42}`), actor); err != nil {
					t.Fatal(err)
				}
				paused, _ := st.Orchestration().GetRun(ctx, run.ID)
				if paused.State != controlmodel.RunPaused {
					t.Fatal("signal resumed paused Run")
				}
				nodes, _ := st.Orchestration().ListNodes(ctx, run.ID)
				for _, n := range nodes {
					if n.NodeKey == "delay" {
						_, err = st.Orchestration().TransitionNode(ctx, n.ID, n.Version, controlmodel.RunNodeSucceeded, nil, "", "")
						if err != nil {
							t.Fatal(err)
						}
					}
				}
				if _, err = svc.Resume(ctx, run.ID); err != nil {
					t.Fatal(err)
				}
				if err = svc.Signal(ctx, run.ID, "ready", "one", json.RawMessage(`{"value":99}`), actor); err != nil {
					t.Fatal(err)
				}
				graph, err := svc.Graph(ctx, run.ID)
				if err != nil {
					t.Fatal(err)
				}
				if graph.Run.State != controlmodel.RunSucceeded {
					t.Fatalf("state=%s", graph.Run.State)
				}
				for _, n := range graph.Nodes {
					if n.NodeKey == "read" {
						var input map[string]int
						if err = json.Unmarshal(n.Input, &input); err != nil || input["answer"] != 42 {
							t.Fatalf("input=%s err=%v", n.Input, err)
						}
					}
				}
			})
			t.Run("resume_partial_materialization", func(t *testing.T) {
				d := makeDefinition(`{"nodes":[{"key":"first","type":"condition"},{"key":"second","type":"condition","issueMode":"child"}],"edges":[{"from":"first","to":"second"}]}`)
				revisions, _ := st.Orchestration().ListRevisions(ctx, d.ID)
				issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: d.Tenant, Namespace: d.Namespace, Title: "interrupted", Creator: actor})
				if err != nil {
					t.Fatal(err)
				}
				run, err := st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{Tenant: d.Tenant, Namespace: d.Namespace, RootIssueID: issue.ID, DefinitionRevisionID: &revisions[0].ID, Mode: controlmodel.RunModeDeclared, State: controlmodel.RunPlanned, CreatedBy: actor})
				if err != nil {
					t.Fatal(err)
				}
				_, err = st.Orchestration().CreateNode(ctx, &controlmodel.RunNode{ID: uuid.NewSHA1(run.ID, []byte("node:first")), RunID: run.ID, Tenant: d.Tenant, Namespace: d.Namespace, NodeKey: "first", Type: controlmodel.RunNodeCondition, State: controlmodel.RunNodeReady, IssueID: &issue.ID, Config: json.RawMessage(`{"key":"first","type":"condition"}`)})
				if err != nil {
					t.Fatal(err)
				}
				if err = (&Engine{Store: st}).ReconcileRun(ctx, run.ID); err != nil {
					t.Fatal(err)
				}
				graph, err := svc.Graph(ctx, run.ID)
				if err != nil || len(graph.Nodes) != 2 || len(graph.Edges) != 1 || graph.Run.State != controlmodel.RunSucceeded {
					t.Fatalf("graph=%+v err=%v", graph, err)
				}
			})
			t.Run("definition_filter_and_page", func(t *testing.T) {
				d := makeDefinition(`{"nodes":[{"key":"done","type":"condition"}]}`)
				for i := 0; i < 3; i++ {
					if _, err := svc.Start(ctx, d.ID, request(fmt.Sprintf("page-%d", i))); err != nil {
						t.Fatal(err)
					}
				}
				runs, err := st.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{Tenant: d.Tenant, Namespace: d.Namespace, DefinitionID: d.ID, Limit: 2, Offset: 2})
				if err != nil || len(runs) != 1 {
					t.Fatalf("page=%d err=%v", len(runs), err)
				}
			})
			t.Run("agent_context_preserves_workflow_step_and_upstream_result", func(t *testing.T) {
				d := makeDefinition(fmt.Sprintf(`{"nodes":[{"key":"draft","type":"agent","agentId":%q},{"key":"source","type":"signal","signalName":"draft-ready"},{"key":"refine","type":"agent","agentId":%q}],"edges":[{"from":"source","to":"refine"}]}`, uuid.NewString(), uuid.NewString()))
				run, err := svc.Start(ctx, d.ID, request("step-context"))
				if err != nil {
					t.Fatal(err)
				}
				tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{RunID: run.ID, Limit: 10})
				if err != nil || len(tasks) != 1 {
					t.Fatalf("tasks=%v err=%v", tasks, err)
				}
				cs := &collaboration.Service{Store: st}
				first, err := cs.BuildContext(ctx, tasks[0].ID)
				if err != nil {
					t.Fatal(err)
				}
				if first.Node == nil || first.ExecutionBrief.Workflow == nil || first.ExecutionBrief.Workflow.NodeKey != "draft" || len(first.ExecutionBrief.Workflow.Predecessors) != 0 {
					t.Fatalf("first context=%+v", first)
				}
				var runInput map[string]int
				if err := json.Unmarshal(first.ExecutionBrief.Workflow.RunInput, &runInput); err != nil || runInput["value"] != 42 {
					t.Fatalf("lost run input: %s", first.ExecutionBrief.Workflow.RunInput)
				}
				if err := svc.Signal(ctx, run.ID, "draft-ready", "draft-output", json.RawMessage(`{"poem":"the actual upstream poem"}`), actor); err != nil {
					t.Fatal(err)
				}
				tasks, err = st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{RunID: run.ID, Limit: 10})
				if err != nil || len(tasks) != 2 {
					t.Fatalf("tasks=%v err=%v", tasks, err)
				}
				for _, task := range tasks {
					next, err := cs.BuildContext(ctx, task.ID)
					if err != nil {
						t.Fatal(err)
					}
					if next.Node.NodeKey != "refine" {
						continue
					}
					step := next.ExecutionBrief.Workflow
					if len(step.Predecessors) != 1 || step.Predecessors[0].NodeKey != "source" || step.Predecessors[0].State != controlmodel.RunNodeSucceeded || !strings.Contains(string(step.Predecessors[0].Output), "the actual upstream poem") {
						t.Fatalf("lost upstream output or included unrelated draft: %+v", step)
					}
					if len(next.Node.Input) > 0 && string(next.Node.Input) != "{}" {
						t.Fatalf("context silently changed explicit mapping: %s", next.Node.Input)
					}
				}
			})
			t.Run("team_reuses_declared_coordinator", func(t *testing.T) {
				team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{Tenant: "workflow-test", Namespace: "default", Name: uuid.NewString(), LeaderAgentRef: "acceptance-leader", Status: controlmodel.TeamActive})
				if err != nil {
					t.Fatal(err)
				}
				if _, err := st.Collaboration().AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{Tenant: "workflow-test", Namespace: "default", TeamID: team.ID, AgentRef: "acceptance-worker", Role: "worker"}); err != nil {
					t.Fatal(err)
				}
				team, err = st.Collaboration().GetTeam(ctx, team.ID)
				if err != nil {
					t.Fatal(err)
				}

				d := makeDefinition(fmt.Sprintf(`{"nodes":[{"key":"team","type":"team","teamRef":%q},{"key":"after","type":"condition"},{"key":"parallel","type":"signal","signalName":"other"}],"edges":[{"from":"team","to":"after"}]}`, team.ID.String()))
				run, err := svc.Start(ctx, d.ID, request("team-coordinator"))
				if err != nil {
					t.Fatal(err)
				}
				for i := 0; i < 3; i++ {
					if err := (&Engine{Store: st}).ReconcileRun(ctx, run.ID); err != nil {
						t.Fatal(err)
					}
				}
				nodes, err := st.Orchestration().ListNodes(ctx, run.ID)
				if err != nil || len(nodes) != 3 {
					t.Fatalf("nodes=%+v err=%v", nodes, err)
				}
				tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{RunID: run.ID, Limit: 10})
				if err != nil || len(tasks) != 1 || tasks[0].TeamID == nil || *tasks[0].TeamID != team.ID {
					t.Fatalf("tasks=%+v err=%v", tasks, err)
				}
				if _, _, err := svc.ValidateCoordinatorNodeCompletion(ctx, tasks[0].ID); err != nil {
					t.Fatalf("unrelated Workflow steps blocked coordinator: %v", err)
				}

				child, worker, err := (&collaboration.Service{Store: st}).CreateChildFromTask(ctx, tasks[0].ID, collaboration.CreateIssueRequest{Title: "delegated calculation", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "acceptance-worker"})
				if err != nil || child == nil || worker == nil || worker.OrchestrationRunID != run.ID {
					t.Fatalf("child=%+v worker=%+v err=%v", child, worker, err)
				}

				if _, _, err := svc.ValidateCoordinatorNodeCompletion(ctx, tasks[0].ID); err == nil {
					t.Fatal("active delegated worker must block coordinator")
				}

				if _, err := svc.Cancel(ctx, run.ID); err != nil {
					t.Fatal(err)
				}
			})
			t.Run("failure_cleans_approval_and_subrun", func(t *testing.T) {
				child := makeDefinition(`{"nodes":[{"key":"wait","type":"signal","signalName":"never"}]}`)
				revs, err := st.Orchestration().ListRevisions(ctx, child.ID)
				if err != nil {
					t.Fatal(err)
				}
				d := makeDefinition(fmt.Sprintf(`{"nodes":[{"key":"approval","type":"approval","approval":{"approverRef":"workflow-test"}},{"key":"child","type":"subrun","definitionRevisionId":%q},{"key":"failure","type":"agent","agentId":%q}]}`, revs[0].ID.String(), uuid.NewString()))
				run, err := svc.Start(ctx, d.ID, request("failure-cleanup"))
				if err != nil {
					t.Fatal(err)
				}
				tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{RunID: run.ID, Limit: 10})
				if err != nil || len(tasks) != 1 {
					t.Fatalf("tasks=%v err=%v", tasks, err)
				}
				if _, err = st.Collaboration().FailAgentTask(ctx, tasks[0].ID, tasks[0].Version, "business", "expected failure"); err != nil {
					t.Fatal(err)
				}
				if err = (&Engine{Store: st}).ReconcileRun(ctx, run.ID); err != nil {
					t.Fatal(err)
				}
				run, _ = st.Orchestration().GetRun(ctx, run.ID)
				if run.State != controlmodel.RunFailed {
					t.Fatalf("run=%+v", run)
				}
				nodes, _ := st.Orchestration().ListNodes(ctx, run.ID)
				for _, n := range nodes {
					if n.Type == controlmodel.RunNodeSubrun {
						children, err := st.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{ParentNodeID: n.ID, Limit: 10})
						if err != nil || len(children) != 1 || children[0].State != controlmodel.RunCancelled {
							t.Fatalf("children=%+v err=%v", children, err)
						}
					}
					if n.Type == controlmodel.RunNodeApproval {
						approvals, err := st.Collaboration().ListApprovals(ctx, store.ApprovalFilter{TargetType: "run-node", TargetRef: n.ID.String(), Limit: 10})
						if err != nil || len(approvals) != 1 || approvals[0].Status != controlmodel.ApprovalCancelled {
							t.Fatalf("approvals=%+v err=%v", approvals, err)
						}
					}
				}
			})
			t.Run("declared_retry_limit", func(t *testing.T) {
				d := makeDefinition(fmt.Sprintf(`{"nodes":[{"key":"attempt","type":"agent","agentId":%q,"retry":{"maxAttempts":2}}]}`, uuid.NewString()))
				run, err := svc.Start(ctx, d.ID, request("retry-limit"))
				if err != nil {
					t.Fatal(err)
				}
				for attempt := 1; attempt <= 2; attempt++ {
					tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{RunID: run.ID, Limit: 10})
					if err != nil || len(tasks) != attempt {
						t.Fatalf("attempt %d tasks=%+v err=%v", attempt, tasks, err)
					}
					task := tasks[len(tasks)-1]
					if _, err = st.Collaboration().FailAgentTask(ctx, task.ID, task.Version, "runtime_unavailable", "expected retryable failure"); err != nil {
						t.Fatal(err)
					}
					if err = (&Engine{Store: st}).ReconcileRun(ctx, run.ID); err != nil {
						t.Fatal(err)
					}
				}
				run, _ = st.Orchestration().GetRun(ctx, run.ID)
				if run.State != controlmodel.RunFailed {
					t.Fatalf("retry limit: %+v", run)
				}
			})
			t.Run("fail_fast_does_not_start_failure_edges", func(t *testing.T) {
				d := makeDefinition(`{"nodes":[{"key":"bad","type":"condition","input":{"value":"run.input.missing"}},{"key":"recover","type":"condition"}],"edges":[{"from":"bad","to":"recover","on":["failed"]}]}`)
				run, err := svc.Start(ctx, d.ID, request("stop-before-routing"))
				if err != nil {
					t.Fatal(err)
				}
				if err = (&Engine{Store: st}).ReconcileRun(ctx, run.ID); err != nil {
					t.Fatal(err)
				}
				nodes, _ := st.Orchestration().ListNodes(ctx, run.ID)
				for _, n := range nodes {
					if n.NodeKey == "recover" && n.State != controlmodel.RunNodeCancelled {
						t.Fatalf("fail_fast executed downstream: %+v", n)
					}
				}
			})
			t.Run("publish_conflict_and_archive", func(t *testing.T) {
				d := makeDefinition(`{"nodes":[{"key":"done","type":"condition"}]}`)
				old := d.Version
				now := time.Now()
				d.ArchivedAt = &now
				d, err = st.Orchestration().UpdateDefinition(ctx, d, old)
				if err != nil {
					t.Fatal(err)
				}
				if _, err = svc.Publish(ctx, d.ID, actor, old); err == nil {
					t.Fatal("published stale or archived definition")
				}
				if _, err = svc.Start(ctx, d.ID, request("archived")); err == nil {
					t.Fatal("started archived definition")
				}
			})
		})
	}
}

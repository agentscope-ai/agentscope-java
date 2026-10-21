// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// https://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package orchestration

import (
	"context"
	"fmt"
	"net/url"
	"os"
	"strings"
	"testing"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

func TestCancellationAdvancesFiniteBatchesAndDoesNotStarvePastWaitingChildren(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			st := cancellationStore(t, driver)
			ctx := t.Context()
			actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
			issue, err := st.Collaboration().CreateIssue(ctx, &model.Issue{Tenant: "t", Namespace: "n", Title: "cancel graph", Creator: actor})
			if err != nil {
				t.Fatal(err)
			}
			run, err := st.Orchestration().CreateRun(ctx, &model.OrchestrationRun{Tenant: "t", Namespace: "n", RootIssueID: issue.ID,
				Mode: model.RunModeDirect, State: model.RunRunning, CreatedBy: actor})
			if err != nil {
				t.Fatal(err)
			}
			nodes := []*model.RunNode{}
			for i := 1; i <= 25; i++ {
				id := uuid.MustParse(fmt.Sprintf("00000000-0000-0000-0000-%012x", i))
				node, err := st.Orchestration().CreateNode(ctx, &model.RunNode{ID: id, RunID: run.ID, Tenant: "t", Namespace: "n",
					NodeKey: fmt.Sprintf("node-%d", i), Type: model.RunNodeSignal, State: model.RunNodeWaiting, IssueID: &issue.ID})
				if err != nil {
					t.Fatal(err)
				}
				nodes = append(nodes, node)
			}
			child, err := st.Orchestration().CreateRun(ctx, &model.OrchestrationRun{Tenant: "t", Namespace: "n", RootIssueID: issue.ID,
				ParentRunID: &run.ID, ParentNodeID: &nodes[0].ID, Mode: model.RunModeSubrun, State: model.RunRunning, CreatedBy: actor})
			if err != nil {
				t.Fatal(err)
			}
			if _, err = st.Orchestration().CreateNode(ctx, &model.RunNode{RunID: child.ID, Tenant: "t", Namespace: "n",
				NodeKey: "child-wait", Type: model.RunNodeSignal, State: model.RunNodeWaiting}); err != nil {
				t.Fatal(err)
			}
			service := &Service{Store: st}
			first, err := service.Cancel(ctx, run.ID)
			if err != nil || first.State != model.RunCancelling {
				t.Fatalf("first cancellation=%v %v", first, err)
			}
			child, _ = st.Orchestration().GetRun(ctx, child.ID)
			if child.State != model.RunCancelling {
				t.Fatalf("child intent was not committed=%s", child.State)
			}
			firstPage, _ := st.Orchestration().ListNodesPage(ctx, run.ID, store.RunNodeFilter{Limit: 128})
			cancelled := 0
			for _, node := range firstPage {
				if node.State == model.RunNodeCancelled {
					cancelled++
				}
			}
			if cancelled != cancellationNodeBatch-1 {
				t.Fatalf("unbounded first pass=%d cancelled", cancelled)
			}
			// A different coordinator uses only the persisted Run/cursor. The
			// waiting first node cannot monopolize the next finite page.
			if _, err = (&Service{Store: st}).Cancel(ctx, run.ID); err != nil {
				t.Fatal(err)
			}
			ninth, _ := st.Orchestration().GetNode(ctx, nodes[8].ID)
			if ninth.State != model.RunNodeCancelled {
				t.Fatal("waiting child starved later nodes after coordinator replacement")
			}
			worker := &Worker{Store: st, Batch: 16}
			for i := 0; i < 12; i++ {
				worker.ReconcileOnce(ctx)
			}
			run, _ = st.Orchestration().GetRun(ctx, run.ID)
			child, _ = st.Orchestration().GetRun(ctx, child.ID)
			if run.State != model.RunCancelled || child.State != model.RunCancelled {
				t.Fatalf("graph failed to converge root=%s child=%s", run.State, child.State)
			}
			issue, _ = st.Collaboration().GetIssue(ctx, issue.ID)
			if issue.Status == model.IssueCancelled || issue.Status == model.IssueDone {
				t.Fatal("runtime cancellation changed the human Issue lifecycle")
			}
		})
	}
}

func TestCancellationDrainsMoreThanOneTaskBatchBehindCompletedHistory(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			st := cancellationStore(t, driver)
			ctx := t.Context()
			actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
			issue, err := st.Collaboration().CreateIssue(ctx, &model.Issue{Tenant: "t", Namespace: "n", Title: "task batches", Creator: actor})
			if err != nil {
				t.Fatal(err)
			}
			run, err := st.Orchestration().CreateRun(ctx, &model.OrchestrationRun{Tenant: "t", Namespace: "n", RootIssueID: issue.ID,
				Mode: model.RunModeDirect, State: model.RunRunning, CreatedBy: actor})
			if err != nil {
				t.Fatal(err)
			}
			node, err := st.Orchestration().CreateNode(ctx, &model.RunNode{RunID: run.ID, Tenant: "t", Namespace: "n", NodeKey: "work",
				Type: model.RunNodeAgent, State: model.RunNodeWaiting, IssueID: &issue.ID})
			if err != nil {
				t.Fatal(err)
			}
			for i := 0; i < 40; i++ {
				task, err := st.Collaboration().CreateRunAgentTask(ctx, store.RunTaskRequest{RunID: run.ID, NodeID: node.ID,
					IssueID: issue.ID, AgentRef: fmt.Sprintf("agent-%d", i), Originator: actor})
				if err != nil {
					t.Fatal(err)
				}
				if i < 7 {
					if _, err = st.Collaboration().CancelAgentTask(ctx, task.ID, task.Version); err != nil {
						t.Fatal(err)
					}
				}
			}
			service := &Service{Store: st}
			first, err := service.Cancel(ctx, run.ID)
			if err != nil || first.State != model.RunCancelling {
				t.Fatalf("first cancellation=%v %v", first, err)
			}
			remaining, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{NodeID: node.ID, ActiveOnly: true, OmitInputs: true, Limit: 100})
			if err != nil || len(remaining) != 17 {
				t.Fatalf("first task batch remaining=%d %v", len(remaining), err)
			}
			for i := 0; i < 3; i++ {
				if _, err = (&Service{Store: st}).Cancel(ctx, run.ID); err != nil {
					t.Fatal(err)
				}
			}
			run, _ = st.Orchestration().GetRun(ctx, run.ID)
			if run.State != model.RunCancelled {
				t.Fatalf("remaining batches did not converge=%s", run.State)
			}
		})
	}
}

func cancellationStore(t *testing.T, driver string) store.Store {
	t.Helper()
	cfg := store.Config{Driver: driver, MaxOpenConns: 4, RuntimeMasterKey: strings.Repeat("fixture-key", 4)}
	if driver == store.DriverPostgres {
		dsn := os.Getenv("CONTROL_PLANE_TEST_POSTGRES_DSN")
		if dsn == "" {
			t.Skip("CONTROL_PLANE_TEST_POSTGRES_DSN is not set")
		}
		conn, err := pgx.Connect(t.Context(), dsn)
		if err != nil {
			t.Fatal(err)
		}
		schema := "cancel_" + strings.ReplaceAll(uuid.NewString(), "-", "")
		quoted := pgx.Identifier{schema}.Sanitize()
		if _, err = conn.Exec(t.Context(), "CREATE SCHEMA "+quoted); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			_, _ = conn.Exec(context.Background(), "DROP SCHEMA "+quoted+" CASCADE")
			_ = conn.Close(context.Background())
		})
		parsed, err := url.Parse(dsn)
		if err != nil {
			t.Fatal(err)
		}
		query := parsed.Query()
		query.Set("search_path", schema)
		parsed.RawQuery = query.Encode()
		cfg.PostgresDSN = parsed.String()
	}
	st, err := store.Open(t.Context(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	return st
}

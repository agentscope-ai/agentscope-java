// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package orchestration

import (
	"fmt"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
	"testing"
)

func TestFailedIssueCleanupIsBoundedDurableAndDiscoveredAfterRunTerminates(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			st := cancellationStore(t, driver)
			ctx := t.Context()
			actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
			root, err := st.Collaboration().CreateIssue(ctx, &model.Issue{Tenant: "t", Namespace: "n", Title: "root", Creator: actor})
			if err != nil {
				t.Fatal(err)
			}
			run, err := st.Orchestration().CreateRun(ctx, &model.OrchestrationRun{Tenant: "t", Namespace: "n", RootIssueID: root.ID, Mode: model.RunModeDirect, State: model.RunRunning, CreatedBy: actor})
			if err != nil {
				t.Fatal(err)
			}
			children := []*model.Issue{}
			for i := 0; i < 50; i++ {
				parent := root.ID
				if i == 49 {
					parent = children[0].ID
				}
				child, err := st.Collaboration().CreateIssue(ctx, &model.Issue{Tenant: "t", Namespace: "n", Title: fmt.Sprintf("child %d", i), Creator: actor, ParentIssueID: &parent})
				if err != nil {
					t.Fatal(err)
				}
				children = append(children, child)
			}
			run, err = st.Orchestration().TransitionRun(ctx, run.ID, run.Version, model.RunFailed, nil, "fixture_failure", "failed")
			if err != nil || !run.TerminalCleanupPending {
				t.Fatalf("cleanup obligation not committed: %v %v", run, err)
			}
			late, err := st.Collaboration().CreateIssue(ctx, &model.Issue{Tenant: "t", Namespace: "n", Title: "created after failure", Creator: actor, ParentIssueID: &root.ID})
			if err != nil {
				t.Fatal(err)
			}
			worker := &Worker{Store: st, Batch: 1}
			worker.ReconcileOnce(ctx)
			blocked := 0
			for _, child := range children {
				current, err := st.Collaboration().GetIssue(ctx, child.ID)
				if err != nil {
					t.Fatal(err)
				}
				if current.Status == model.IssueBlocked {
					blocked++
				}
			}
			if blocked == 0 || blocked > failedIssueCleanupBatch {
				t.Fatalf("unbounded or empty first pass: %d", blocked)
			}
			cursor, err := st.KV().Get(ctx, "t", failedIssueCursorPath, run.ID.String())
			if err != nil || len(cursor.Value) == 0 {
				t.Fatalf("no durable progress: %v", err)
			}
			for pass := 0; pass < 30; pass++ {
				// A new worker/engine must continue from shared progress after each restart.
				(&Worker{Store: st, Batch: 1}).ReconcileOnce(ctx)
				run, err = st.Orchestration().GetRun(ctx, run.ID)
				if err != nil {
					t.Fatal(err)
				}
				if !run.TerminalCleanupPending {
					break
				}
			}
			if run.TerminalCleanupPending {
				t.Fatal("terminal work was not rediscovered or did not finish")
			}
			for _, child := range children {
				current, err := st.Collaboration().GetIssue(ctx, child.ID)
				if err != nil || current.Status != model.IssueBlocked {
					t.Fatalf("unvisited child %s: %+v %v", child.ID, current, err)
				}
			}
			current, err := st.Collaboration().GetIssue(ctx, late.ID)
			if err != nil || current.Status != late.Status {
				t.Fatalf("historical cleanup changed later work: %+v %v", current, err)
			}
			pending, err := st.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{Tenant: "t", Namespace: "n", ActiveOnly: true, IncludeTerminalCleanup: true, Limit: 10})
			if err != nil || len(pending) != 0 {
				t.Fatalf("completed cleanup remained runnable: %v %v", pending, err)
			}
			stable := run.Version
			(&Worker{Store: st, Batch: 1}).ReconcileOnce(ctx)
			run, err = st.Orchestration().GetRun(ctx, run.ID)
			if err != nil || run.Version != stable {
				t.Fatal("completed cleanup was executed again", err)
			}
		})
	}
}

func TestHistoricalFailureCleanupDoesNotBlockNewRecovery(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			st := cancellationStore(t, driver)
			ctx := t.Context()
			actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
			root, err := st.Collaboration().CreateIssue(ctx, &model.Issue{Tenant: "t", Namespace: "n", Title: "recovery", Creator: actor})
			if err != nil {
				t.Fatal(err)
			}
			old, err := st.Orchestration().CreateRun(ctx, &model.OrchestrationRun{Tenant: "t", Namespace: "n", RootIssueID: root.ID, Mode: model.RunModeDirect, State: model.RunRunning, CreatedBy: actor})
			if err != nil {
				t.Fatal(err)
			}
			old, err = st.Orchestration().TransitionRun(ctx, old.ID, old.Version, model.RunFailed, nil, "failed", "")
			if err != nil {
				t.Fatal(err)
			}
			if _, err = st.Orchestration().CreateRun(ctx, &model.OrchestrationRun{Tenant: "t", Namespace: "n", RootIssueID: root.ID, Mode: model.RunModeDirect, State: model.RunRunning, CreatedBy: actor}); err != nil {
				t.Fatal(err)
			}
			if err = (&Engine{Store: st}).ReconcileRun(ctx, old.ID); err != nil {
				t.Fatal(err)
			}
			current, err := st.Collaboration().GetIssue(ctx, root.ID)
			if err != nil || current.Status != root.Status {
				t.Fatal("old failure blocked recovery", current, err)
			}
			old, err = st.Orchestration().GetRun(ctx, old.ID)
			if err != nil || old.TerminalCleanupPending {
				t.Fatal("stale obligation did not close", err)
			}
		})
	}
}

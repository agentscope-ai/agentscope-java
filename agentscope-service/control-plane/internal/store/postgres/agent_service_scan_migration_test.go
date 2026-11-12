// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package postgres

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

func TestAgentServiceScanUpgradeBackfillsChildRunsAndPendingOutbox(t *testing.T) {
	cfg := serviceMigrationConfig(t)
	st, err := store.Open(t.Context(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
	sessionID := uuid.New()
	contract, _ := json.Marshal(map[string]string{"public_session_id": sessionID.String(), "portal_command_id": "upgrade-fixture"})
	inv, _, err := st.Endpoints().ReserveInvocation(t.Context(), &model.EndpointInvocation{EndpointID: sessionID, Mode: model.EndpointJobMode, Contract: contract})
	if err != nil {
		t.Fatal(err)
	}
	root, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "root", Creator: actor, SourceType: "endpoint", SourceRef: inv.ID.String()})
	if err != nil {
		t.Fatal(err)
	}
	child, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "child", Creator: actor, ParentIssueID: &root.ID, AssigneeType: model.AssigneeAgent, AssigneeRef: "child-agent"})
	if err != nil {
		t.Fatal(err)
	}
	legacy, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "legacy", Creator: actor, AssigneeType: model.AssigneeAgent, AssigneeRef: "legacy-agent"})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(t.Context(), store.AgentTaskFilter{IssueID: child.ID, Limit: 2})
	if err != nil || len(tasks) != 1 {
		t.Fatal(tasks, err)
	}
	conn, err := pgx.Connect(t.Context(), cfg.PostgresDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(context.Background())
	// Rebuild only the four new, unpublished migrations around seeded records.
	for _, version := range []string{"0131_runtime_lease_scan", "0130_tool_approval_scan", "0129_portal_runtime_outbox", "0128_portal_owned_run_scan"} {
		sql, err := migrationFS.ReadFile("migrations/" + version + ".down.sql")
		if err != nil {
			t.Fatal(err)
		}
		if _, err = conn.Exec(t.Context(), string(sql)); err != nil {
			t.Fatal(version, err)
		}
		if _, err = conn.Exec(t.Context(), "DELETE FROM schema_migrations WHERE version=$1", version); err != nil {
			t.Fatal(err)
		}
	}
	if err = st.Migrate(t.Context()); err != nil {
		t.Fatal(err)
	}
	run, err := st.Orchestration().GetRun(t.Context(), tasks[0].OrchestrationRunID)
	if err != nil || !run.AgentServiceOwned {
		t.Fatalf("NULL-source child ancestry lost during upgrade: %+v %v", run, err)
	}
	active, err := st.Orchestration().ListRuns(t.Context(), store.OrchestrationRunFilter{AgentServiceOnly: true, ActiveOnly: true, Limit: 10})
	if err != nil || len(active) != 1 || active[0].ID != run.ID {
		t.Fatalf("upgrade Run selection: %+v %v", active, err)
	}
	events, err := st.Outbox().ClaimAgentServiceRuntime(t.Context(), "upgrade", time.Now().UTC().Add(time.Second), time.Minute, 10)
	if err != nil || len(events) != 1 || events[0].AggregateID != tasks[0].ID.String() {
		t.Fatalf("pending outbox backfill: %+v %v", events, err)
	}
	var owned bool
	if err = conn.QueryRow(t.Context(), "SELECT portal_runtime_event_owned('agent-task','invalid')").Scan(&owned); err != nil || owned {
		t.Fatalf("malformed aggregate ref: %v %v", owned, err)
	}
	if err = conn.QueryRow(t.Context(), "SELECT portal_issue_owned($1)", legacy.ID).Scan(&owned); err != nil || owned {
		t.Fatalf("legacy source granted ownership: %v %v", owned, err)
	}
}

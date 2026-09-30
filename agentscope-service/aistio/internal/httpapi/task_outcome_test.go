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
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func TestRESTTaskOutcomeDoesNotTreatBlockedAsObjectiveSuccess(t *testing.T) {
	for _, transport := range []string{"rest", "mcp"} {
		t.Run(transport, func(t *testing.T) {
			ctx := context.Background()
			st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = st.Close() })
			team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{Tenant: "tenant-a", Namespace: "default", Name: "EV", LeaderAgentRef: "lead"})
			if err != nil {
				t.Fatal(err)
			}
			root, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: team.Tenant, Namespace: team.Namespace, Title: "EV report", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String()})
			if err != nil {
				t.Fatal(err)
			}
			tasks, _ := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: root.ID, Limit: 2})
			task, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx, store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version}, &controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged, State: controlmodel.ExecutionAssigned})
			if err == nil {
				task, err = st.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
			}
			if err != nil {
				t.Fatal(err)
			}
			srv := NewServer(ServerOptions{Store: st, TaskTokenSecret: "0123456789abcdef0123456789abcdef"})
			token, err := srv.taskTokens.MintScoped(task.ID, attempt.ID, attempt.DispatchGeneration, time.Now().UTC())
			if err != nil {
				t.Fatal(err)
			}
			args := map[string]any{"expectedVersion": task.Version, "outcome": "blocked", "summary": "need acceptance permission", "result": "full EV report with evidence"}
			path := "/api/v1/agent-tasks/" + task.ID.String() + "/complete"
			var body []byte
			if transport == "mcp" {
				path = "/mcp/collaboration"
				delete(args, "expectedVersion")
				body, _ = json.Marshal(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": map[string]any{"name": "task.complete", "arguments": args}})
			} else {
				body, _ = json.Marshal(args)
			}
			req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(body))
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("X-Agent-Task-Token", token)
			rec := httptest.NewRecorder()
			srv.router.ServeHTTP(rec, req)
			if rec.Code != http.StatusOK || bytes.Contains(rec.Body.Bytes(), []byte(`"isError":true`)) {
				t.Fatalf("outcome rejected: %d %s", rec.Code, rec.Body)
			}
			run, _ := st.Orchestration().GetRun(ctx, task.OrchestrationRunID)
			issue, _ := st.Collaboration().GetIssue(ctx, root.ID)
			ended, _ := st.Collaboration().GetAgentTask(ctx, task.ID)
			if controlmodel.IsOrchestrationRunTerminal(run.State) || issue.Status != controlmodel.IssueBlocked || !strings.Contains(string(ended.Result), "full EV report") {
				t.Fatalf("blocked lost work or aborted run: %s %s %s", run.State, issue.Status, ended.Result)
			}
		})
	}
}

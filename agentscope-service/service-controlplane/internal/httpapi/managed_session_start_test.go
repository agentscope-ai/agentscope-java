/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/google/uuid"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
)

func TestManagedStartBarrierBeforeFastCompletion(t *testing.T) {
	for _, backend := range []string{"memory", "postgres"} {
		t.Run(backend, func(t *testing.T) {
			var replicas [2]store.Store
			if backend == "postgres" {
				replicas = servicePostgresReplicas(t)
			} else {
				st, err := store.Open(t.Context(), store.Config{Driver: store.DriverMemory})
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { _ = st.Close() })
				replicas = [2]store.Store{st, st}
			}
			ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
			defer cancel()
			st := replicas[0]
			agentID := uuid.New()
			issue, err := st.Collaboration().CreateIssue(ctx, &model.Issue{
				Tenant: "t", Namespace: "n", Title: "fast managed completion",
				Creator:      model.Actor{Type: model.ActorHuman, Ref: "owner"},
				AssigneeType: model.AssigneeAgent, AssigneeRef: agentID.String(),
			})
			if err != nil {
				t.Fatal(err)
			}
			tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
			if err != nil || len(tasks) != 1 {
				t.Fatalf("tasks=%v err=%v", tasks, err)
			}
			sessionID := "fast-managed-" + uuid.NewString()
			task, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
				store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version, SessionID: sessionID, RuntimeBinding: json.RawMessage(`{}`)},
				&model.ExecutionAttempt{BackendKind: model.DataPlaneManaged, State: model.ExecutionAssigned,
					SessionID: sessionID, TurnID: "turn-1", RuntimeBinding: json.RawMessage(`{}`)})
			if err != nil {
				t.Fatal(err)
			}
			session, err := st.Sessions().Upsert(ctx, &store.Session{Tenant: "t", Namespace: "n",
				SessionID: sessionID, AgentID: agentID, AgentTaskID: &task.ID, Phase: store.SessionPhaseIdle})
			if err != nil {
				t.Fatal(err)
			}
			servers := [2]*Server{}
			for i := range servers {
				servers[i] = NewServer(ServerOptions{Store: replicas[i], InternalToken: "internal-secret",
					TaskTokenSecret: "0123456789abcdef0123456789abcdef"})
			}
			post := func(server *Server, path string, payload any, tokenHeader, token string) *httptest.ResponseRecorder {
				body, _ := json.Marshal(payload)
				req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(body))
				req = req.WithContext(ctx)
				req.Header.Set("Content-Type", "application/json")
				req.Header.Set(tokenHeader, token)
				response := httptest.NewRecorder()
				server.router.ServeHTTP(response, req)
				return response
			}
			start := managedSessionEventReport{AgentTaskID: task.ID.String(), AttemptID: attempt.ID.String(),
				DispatchGen: attempt.DispatchGeneration, TurnID: attempt.TurnID}
			startPath := "/api/internal/runtime-sessions/" + sessionID + "/start"
			if response := post(servers[0], startPath, start, "X-Builder-Internal-Token", "invalid"); response.Code != http.StatusUnauthorized {
				t.Fatalf("unauthenticated start=%d %s", response.Code, response.Body.String())
			}
			stale := start
			stale.DispatchGen++
			if response := post(servers[0], startPath, stale, "X-Builder-Internal-Token", "internal-secret"); response.Code != http.StatusConflict {
				t.Fatalf("wrong generation start=%d %s", response.Code, response.Body.String())
			}
			// Neither replica has delivered the asynchronous session.status_running event.
			// The first acknowledgement must already make immediate tool completion safe.
			responses := make(chan *httptest.ResponseRecorder, len(servers))
			for _, server := range servers {
				go func() {
					responses <- post(server, startPath, start, "X-Builder-Internal-Token", "internal-secret")
				}()
			}
			for range servers {
				response := <-responses
				if response.Code != http.StatusNoContent {
					t.Fatalf("start=%d %s", response.Code, response.Body.String())
				}
			}
			started, err := replicas[1].Collaboration().GetAgentTask(ctx, task.ID)
			if err != nil || started.Status != model.AgentTaskRunning {
				t.Fatalf("durable start=%+v err=%v", started, err)
			}
			events, err := st.Events().List(ctx, session.ID)
			if err != nil || len(events) != 0 {
				t.Fatalf("start created duplicate log: events=%v err=%v", events, err)
			}
			token, err := servers[1].taskTokens.MintScoped(task.ID, attempt.ID, attempt.DispatchGeneration, time.Now().UTC())
			if err != nil {
				t.Fatal(err)
			}
			response := post(servers[1], "/mcp/collaboration", map[string]any{
				"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": map[string]any{
					"name": "task.complete", "arguments": map[string]any{"summary": "fast result", "result": map[string]any{"answer": "done"}}}},
				"X-Agent-Task-Token", token)
			if response.Code != http.StatusOK || bytes.Contains(response.Body.Bytes(), []byte(`"isError":true`)) || bytes.Contains(response.Body.Bytes(), []byte(`"error":`)) {
				t.Fatalf("fast task.complete=%d %s", response.Code, response.Body.String())
			}
			if response := post(servers[0], startPath, start, "X-Builder-Internal-Token", "internal-secret"); response.Code != http.StatusGone {
				t.Fatalf("terminal start=%d %s", response.Code, response.Body.String())
			}
			start.ID, start.Seq, start.Type = "late-running", 1, "session.status_running"
			response = post(servers[0], "/api/internal/runtime-sessions/"+sessionID+"/events", start, "X-Builder-Internal-Token", "internal-secret")
			if response.Code != http.StatusNoContent {
				t.Fatalf("delayed outbox=%d %s", response.Code, response.Body.String())
			}
			completed, err := st.Collaboration().GetAgentTask(ctx, task.ID)
			if err != nil || completed.Status != model.AgentTaskCompleted {
				t.Fatalf("late event reversed completion: task=%+v err=%v", completed, err)
			}
		})
	}
}

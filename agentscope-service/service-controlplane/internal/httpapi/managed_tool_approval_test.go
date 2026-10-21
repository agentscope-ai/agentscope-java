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
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	_ "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store/memory"
)

func TestRuntimeApprovalRequestDecisionAndAck(t *testing.T) {
	for _, backend := range []controlmodel.DataPlaneKind{controlmodel.DataPlaneHostedRuntime, controlmodel.DataPlaneExternalApplication} {
		t.Run(string(backend), func(t *testing.T) {
			ctx := context.Background()
			st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = st.Close() })
			agentID := uuid.New()
			issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
				Tenant: "tenant-a", Namespace: "default", Title: "hosted approval",
				Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
				AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: agentID.String(),
			})
			if err != nil {
				t.Fatal(err)
			}
			tasks, _ := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
			task, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
				store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version, SessionID: "hosted-session"},
				&controlmodel.ExecutionAttempt{BackendKind: backend,
					State: controlmodel.ExecutionAssigned, SessionID: "hosted-session", TurnID: "hosted-turn"})
			if err != nil {
				t.Fatal(err)
			}
			task, err = st.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
			if err != nil {
				t.Fatal(err)
			}
			srv := NewServer(ServerOptions{Store: st, InternalToken: "internal-secret"})
			token, err := srv.taskTokens.MintScoped(task.ID, attempt.ID, attempt.DispatchGeneration, time.Now().UTC())
			if err != nil {
				t.Fatal(err)
			}
			requestBody := bytes.NewBufferString(`{"kind":"tool_confirmation","toolUseId":"call-1","toolName":"shell","inputPreview":{"command":"date"}}`)
			req := httptest.NewRequest(http.MethodPost, "/api/v1/agent-tasks/"+task.ID.String()+"/runtime-approvals", requestBody)
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("X-Agent-Task-Token", token)
			response := httptest.NewRecorder()
			srv.router.ServeHTTP(response, req)
			if response.Code != http.StatusCreated {
				t.Fatalf("request status=%d body=%s", response.Code, response.Body.String())
			}
			var created struct {
				Approval *controlmodel.Approval `json:"approval"`
			}
			if json.Unmarshal(response.Body.Bytes(), &created) != nil || created.Approval == nil {
				t.Fatalf("created=%s", response.Body.String())
			}
			var envelope managedToolApprovalEnvelope
			if json.Unmarshal(created.Approval.Request, &envelope) != nil ||
				envelope.BackendKind != backend {
				t.Fatalf("envelope=%+v", envelope)
			}
			decided, err := st.Collaboration().DecideApproval(ctx, created.Approval.ID, created.Approval.Version,
				controlmodel.ApprovalApproved, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, nil)
			if err != nil {
				t.Fatal(err)
			}
			if err = srv.DispatchApprovalDecision(ctx, decided.ID); err != nil {
				t.Fatal(err)
			}
			waiting, err := st.Collaboration().GetAgentTask(ctx, task.ID)
			if err != nil || waiting.Status != controlmodel.AgentTaskWaiting {
				t.Fatalf("decision pushed/resumed runtime before fetch: %+v %v", waiting, err)
			}
			decisionURL := "/api/v1/agent-tasks/" + task.ID.String() + "/runtime-approvals/" + decided.ID.String() + "/decision"
			req = httptest.NewRequest(http.MethodGet, decisionURL, nil)
			req.Header.Set("X-Agent-Task-Token", token)
			response = httptest.NewRecorder()
			srv.router.ServeHTTP(response, req)
			if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"allow":true`) {
				t.Fatalf("decision status=%d body=%s", response.Code, response.Body.String())
			}
			ackBody := bytes.NewBufferString(fmt.Sprintf(`{"decisionVersion":%d}`, decided.Version))
			req = httptest.NewRequest(http.MethodPost, strings.TrimSuffix(decisionURL, "/decision")+"/ack", ackBody)
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("X-Agent-Task-Token", token)
			response = httptest.NewRecorder()
			srv.router.ServeHTTP(response, req)
			if response.Code != http.StatusNoContent {
				t.Fatalf("ack status=%d body=%s", response.Code, response.Body.String())
			}
			events, err := st.Orchestration().ListRunEvents(ctx, task.OrchestrationRunID, 0, 100)
			if err != nil {
				t.Fatal(err)
			}
			found := false
			for _, event := range events {
				found = found || event.Type == "approval.delivery_delivered"
			}
			if !found {
				t.Fatal("runtime approval delivery ACK was not recorded")
			}

		})
	}
}

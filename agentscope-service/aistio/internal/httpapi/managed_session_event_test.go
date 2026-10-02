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
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/orchestration"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestManagedSessionEventsProjectMessagesAndAreIdempotent(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	session, err := st.Sessions().Upsert(ctx, &store.Session{
		Tenant: "tenant-a", Namespace: "default", SessionID: "managed-chat-1",
		AgentID: uuid.New(), AgentName: "managed-chat", Framework: string(controlmodel.DataPlaneManaged),
		Phase: store.SessionPhaseIdle,
	})
	if err != nil {
		t.Fatal(err)
	}
	srv := NewServer(ServerOptions{Store: st, InternalToken: "internal-secret"})
	report := func(id, eventType string, seq int, payload map[string]any) {
		t.Helper()
		body, _ := json.Marshal(managedSessionEventReport{ID: id, SessionID: session.SessionID,
			Seq: int64(seq), Type: eventType, Payload: payload, CreatedAt: time.Now().UnixMilli()})
		req := httptest.NewRequest(http.MethodPost,
			"/api/internal/runtime-sessions/"+session.SessionID+"/events", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Builder-Internal-Token", "internal-secret")
		response := httptest.NewRecorder()
		srv.router.ServeHTTP(response, req)
		if response.Code != http.StatusNoContent {
			t.Fatalf("report %s: status=%d body=%s", eventType, response.Code, response.Body.String())
		}
	}
	report("evt-user", "user.message", 1, map[string]any{"text": "hello"})
	report("evt-agent", "agent.message", 2, map[string]any{"text": "managed reply"})
	report("evt-agent", "agent.message", 2, map[string]any{"text": "duplicate"})

	events, err := st.Events().List(ctx, session.ID)
	if err != nil || len(events) != 2 {
		t.Fatalf("mirrored events: events=%+v err=%v", events, err)
	}
	if events[0].Role != "user" || events[0].Content != "hello" ||
		events[1].Role != "assistant" || events[1].Content != "managed reply" {
		t.Fatalf("unexpected message projection: %+v", events)
	}
	page, hit, err := srv.messagePageFromEvents(ctx, session, 0, 10, false)
	if err != nil || !hit || page.Total != 2 || page.Messages[1].Content != "managed reply" {
		t.Fatalf("message page: page=%+v hit=%v err=%v", page, hit, err)
	}
}

func TestManagedRunningEventStartsAssignedAttempt(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agentID := uuid.New()
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "default", Title: "managed work",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: agentID.String(),
	})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("task setup: tasks=%+v err=%v", tasks, err)
	}
	claimed, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version, SessionID: "managed-task-1"},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
			State: controlmodel.ExecutionAssigned, SessionID: "managed-task-1", TurnID: "turn-managed-1"})
	if err != nil {
		t.Fatal(err)
	}
	busy := false
	session, err := st.Sessions().Upsert(ctx, &store.Session{
		Tenant: claimed.Tenant, Namespace: claimed.Namespace, SessionID: "managed-task-1",
		AgentID: agentID, AgentName: "managed-worker", Framework: string(controlmodel.DataPlaneManaged),
		Phase: store.SessionPhaseIdle, Busy: &busy, AgentTaskID: &claimed.ID,
	})
	if err != nil {
		t.Fatal(err)
	}
	srv := NewServer(ServerOptions{Store: st, InternalToken: "internal-secret"})
	body, _ := json.Marshal(managedSessionEventReport{ID: "evt-running", SessionID: session.SessionID,
		Seq: 1, Type: "session.status_running", Payload: map[string]any{"status": "running"},
		CreatedAt: time.Now().UnixMilli(), AgentTaskID: claimed.ID.String(), AttemptID: attempt.ID.String(),
		DispatchGen: attempt.DispatchGeneration, TurnID: attempt.TurnID})
	req := httptest.NewRequest(http.MethodPost,
		"/api/internal/runtime-sessions/"+session.SessionID+"/events", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Builder-Internal-Token", "internal-secret")
	response := httptest.NewRecorder()
	srv.router.ServeHTTP(response, req)
	if response.Code != http.StatusNoContent {
		t.Fatalf("running report: status=%d body=%s", response.Code, response.Body.String())
	}
	started, _ := st.Collaboration().GetAgentTask(ctx, claimed.ID)
	startedAttempt, _ := st.ExecutionAttempts().Get(ctx, attempt.ID)
	updatedSession, _ := st.Sessions().GetByID(ctx, session.ID)
	if started.Status != controlmodel.AgentTaskRunning || startedAttempt.State != controlmodel.ExecutionRunning ||
		updatedSession.Phase != store.SessionPhaseActive || updatedSession.Busy == nil || !*updatedSession.Busy {
		t.Fatalf("lifecycle not synchronized: task=%+v attempt=%+v session=%+v",
			started, startedAttempt, updatedSession)
	}
	if startedAttempt.LeaseExpiresAt == nil || !startedAttempt.LeaseExpiresAt.After(time.Now()) {
		t.Fatalf("fenced managed event did not renew execution lease: %+v", startedAttempt)
	}

	// An optional MCP connector failure must not close the task's live fence.
	optionalBody, _ := json.Marshal(managedSessionEventReport{ID: "evt-optional-mcp", SessionID: session.SessionID,
		Seq: 2, Type: "session.error", Payload: map[string]any{"error": map[string]any{"type": "mcp_connection_failed_error", "code": "mcp_connection_failed_error", "retry_status": "next_turn", "message": "MCP connection failed: github"}},
		CreatedAt: time.Now().UnixMilli(), AgentTaskID: claimed.ID.String(), AttemptID: attempt.ID.String(), DispatchGen: attempt.DispatchGeneration, TurnID: attempt.TurnID})
	optionalReq := httptest.NewRequest(http.MethodPost, "/api/internal/runtime-sessions/"+session.SessionID+"/events", bytes.NewReader(optionalBody))
	optionalReq.Header.Set("Content-Type", "application/json")
	optionalReq.Header.Set("X-Builder-Internal-Token", "internal-secret")
	optionalResponse := httptest.NewRecorder()
	srv.router.ServeHTTP(optionalResponse, optionalReq)
	if optionalResponse.Code != http.StatusNoContent {
		t.Fatalf("optional MCP report: %d %s", optionalResponse.Code, optionalResponse.Body.String())
	}
	liveTask, _ := st.Collaboration().GetAgentTask(ctx, claimed.ID)
	liveAttempt, _ := st.ExecutionAttempts().Get(ctx, attempt.ID)
	if liveTask.Status != controlmodel.AgentTaskRunning || liveAttempt.State != controlmodel.ExecutionRunning {
		t.Fatalf("optional MCP failure terminated task/attempt: %+v %+v", liveTask, liveAttempt)
	}
	events, _ := st.Events().List(ctx, session.ID)
	if events[len(events)-1].EventType != "session.warning" || events[len(events)-1].Content != "MCP connection failed: github" {
		t.Fatalf("lost optional connection diagnostic: %+v", events)
	}

	// Local schema validation never reaches the MCP endpoint. Its fenced tool
	// result must still be durable, correlated and idempotent in Run diagnostics.
	for _, callID := range []string{"local-schema", "remote-schema"} {
		if callID == "remote-schema" {
			srv.recordMCPToolFailure(ctx, started, "issue.get", callID, fmt.Errorf("scope mismatch"))
		}
		report := managedSessionEventReport{ID: "evt-" + callID, SessionID: session.SessionID,
			Seq: 2, Type: "agent.tool_result", Payload: map[string]any{"toolCallId": callID, "toolName": "issue.get", "state": "ERROR", "output": "scope mismatch"},
			CreatedAt: time.Now().UnixMilli(), AgentTaskID: started.ID.String(), AttemptID: attempt.ID.String(), DispatchGen: attempt.DispatchGeneration, TurnID: attempt.TurnID}
		data, _ := json.Marshal(report)
		for retry := 0; retry < 2; retry++ {
			r := httptest.NewRequest(http.MethodPost, "/api/internal/runtime-sessions/"+session.SessionID+"/events", bytes.NewReader(data))
			r.Header.Set("Content-Type", "application/json")
			r.Header.Set("X-Builder-Internal-Token", "internal-secret")
			w := httptest.NewRecorder()
			srv.router.ServeHTTP(w, r)
			if w.Code != http.StatusNoContent {
				t.Fatalf("tool report: %d %s", w.Code, w.Body)
			}
		}
	}
	diagnostics, err := st.Orchestration().ListRunEvents(ctx, started.OrchestrationRunID, 0, 100)
	if err != nil {
		t.Fatal(err)
	}
	failures := map[string]int{}
	for _, e := range diagnostics {
		if e.Type == "agent_tool.failed" {
			failures[e.CausationID]++
		}
	}
	if failures["local-schema"] != 1 || failures["remote-schema"] != 1 || len(failures) != 2 {
		t.Fatalf("missing/duplicate diagnostics: %+v", failures)
	}
	sessionEvents, err := st.Events().List(ctx, session.ID)
	if err != nil {
		t.Fatal(err)
	}
	foundState := false
	for _, e := range sessionEvents {
		if e.EventType == "agent.tool_result" && bytes.Contains(e.FrameworkMeta, []byte(`"state":"ERROR"`)) {
			foundState = true
		}
	}
	if !foundState {
		t.Fatal("tool result state was lost from session metadata")
	}
	heartbeatBody, _ := json.Marshal(managedSessionHeartbeat{AttemptID: attempt.ID,
		DispatchGen: attempt.DispatchGeneration, TurnID: attempt.TurnID})
	heartbeatReq := httptest.NewRequest(http.MethodPost,
		"/api/internal/runtime-sessions/"+session.SessionID+"/heartbeat", bytes.NewReader(heartbeatBody))
	heartbeatReq.Header.Set("Content-Type", "application/json")
	heartbeatReq.Header.Set("X-Builder-Internal-Token", "internal-secret")
	heartbeatResponse := httptest.NewRecorder()
	srv.router.ServeHTTP(heartbeatResponse, heartbeatReq)
	if heartbeatResponse.Code != http.StatusNoContent {
		t.Fatalf("heartbeat: status=%d body=%s", heartbeatResponse.Code, heartbeatResponse.Body.String())
	}
	renewedAttempt, _ := st.ExecutionAttempts().Get(ctx, attempt.ID)
	if renewedAttempt.LeaseExpiresAt == nil || !renewedAttempt.LeaseExpiresAt.After(time.Now()) {
		t.Fatalf("managed execution lease was not renewed: %+v", renewedAttempt)
	}
	suspendedBody, _ := json.Marshal(managedSessionEventReport{ID: "evt-tool-suspended", SessionID: session.SessionID,
		Seq: 2, Type: "session.requires_action", Payload: map[string]any{"reason": "tool_suspended"},
		CreatedAt: time.Now().UnixMilli(), AgentTaskID: claimed.ID.String(), AttemptID: attempt.ID.String(),
		DispatchGen: attempt.DispatchGeneration, TurnID: attempt.TurnID})
	suspendedReq := httptest.NewRequest(http.MethodPost,
		"/api/internal/runtime-sessions/"+session.SessionID+"/events", bytes.NewReader(suspendedBody))
	suspendedReq.Header.Set("Content-Type", "application/json")
	suspendedReq.Header.Set("X-Builder-Internal-Token", "internal-secret")
	suspendedResponse := httptest.NewRecorder()
	srv.router.ServeHTTP(suspendedResponse, suspendedReq)
	if suspendedResponse.Code != http.StatusNoContent {
		t.Fatalf("non-HITL requires_action status=%d body=%s", suspendedResponse.Code, suspendedResponse.Body.String())
	}
	approvals, err := st.Collaboration().ListApprovals(ctx, store.ApprovalFilter{
		Tenant: claimed.Tenant, Namespace: claimed.Namespace, Limit: 10})
	if err != nil || len(approvals) != 0 {
		t.Fatalf("non-HITL requires_action created Approval: approvals=%+v err=%v", approvals, err)
	}
	afterSuspension, _ := st.Collaboration().GetAgentTask(ctx, claimed.ID)
	if afterSuspension.Status != controlmodel.AgentTaskRunning {
		t.Fatalf("non-HITL requires_action changed Task state: %+v", afterSuspension)
	}
	errorBody, _ := json.Marshal(managedSessionEventReport{ID: "evt-error", SessionID: session.SessionID,
		Seq: 3, Type: "session.error", Payload: map[string]any{"error": map[string]any{
			"code": "model_call_failed", "message": "provider unavailable"}}, CreatedAt: time.Now().UnixMilli(),
		AgentTaskID: claimed.ID.String(), AttemptID: attempt.ID.String(),
		DispatchGen: attempt.DispatchGeneration, TurnID: attempt.TurnID})
	errorReq := httptest.NewRequest(http.MethodPost,
		"/api/internal/runtime-sessions/"+session.SessionID+"/events", bytes.NewReader(errorBody))
	errorReq.Header.Set("Content-Type", "application/json")
	errorReq.Header.Set("X-Builder-Internal-Token", "internal-secret")
	errorResponse := httptest.NewRecorder()
	srv.router.ServeHTTP(errorResponse, errorReq)
	if errorResponse.Code != http.StatusNoContent {
		t.Fatalf("error report: status=%d body=%s", errorResponse.Code, errorResponse.Body.String())
	}
	failedTask, _ := st.Collaboration().GetAgentTask(ctx, claimed.ID)
	failedAttempt, _ := st.ExecutionAttempts().Get(ctx, attempt.ID)
	if failedTask.Status != controlmodel.AgentTaskFailed || failedAttempt.State != controlmodel.ExecutionFailed ||
		failedTask.ErrorCode != "model_call_failed" {
		t.Fatalf("turn error did not fail task atomically: task=%+v attempt=%+v", failedTask, failedAttempt)
	}
}

func TestManagedIdleFailsTaskThatReturnedWithoutTerminalAction(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	leaderID, workerID := uuid.New(), uuid.New()
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant-a", Namespace: "default", Name: "managed-team",
		LeaderAgentRef: leaderID.String(), Policy: controlmodel.TeamPolicy{MaxChildDepth: 4, MaxChildIssues: 4},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.Collaboration().AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{
		TeamID: team.ID, AgentRef: workerID.String(), Role: "worker",
	}); err != nil {
		t.Fatal(err)
	}
	svc := &collaboration.Service{Store: st}
	_, leader, err := svc.CreateIssue(ctx, collaboration.CreateIssueRequest{
		Tenant: "tenant-a", Namespace: "default", Title: "coordinate",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String(),
	})
	if err != nil {
		t.Fatal(err)
	}
	leader, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: leader.ID, ExpectedVersion: leader.Version, RuntimeBinding: json.RawMessage(`{}`), SessionID: "managed-leader"},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
			State: controlmodel.ExecutionAssigned, SessionID: "managed-leader", TurnID: "leader-turn"})
	if err != nil {
		t.Fatal(err)
	}
	leader, err = st.Collaboration().StartAgentTask(ctx, leader.ID, leader.Version)
	if err != nil {
		t.Fatal(err)
	}
	srv := NewServer(ServerOptions{Store: st})
	if err = srv.validateMCPTeamLeaderCompletion(ctx, leader); err == nil {
		t.Fatal("Team leader completion was allowed before delegation or coordinator completion")
	}
	followUp := *leader
	parentTaskID := uuid.New()
	followUp.ParentTaskID = &parentTaskID
	if err = srv.validateMCPTeamLeaderCompletion(ctx, &followUp); err == nil {
		t.Fatal("leader follow-up could finish with no remaining task or durable decision")
	}
	if _, _, err = svc.CreateChildFromTask(ctx, leader.ID, collaboration.CreateIssueRequest{
		Title: "worker job", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: workerID.String(),
	}); err != nil {
		t.Fatal(err)
	}
	if err = srv.validateMCPTeamLeaderCompletion(ctx, leader); err != nil {
		t.Fatalf("Team leader completion remained blocked after delegation: %v", err)
	}
	busy := true
	session, err := st.Sessions().Upsert(ctx, &store.Session{
		Tenant: leader.Tenant, Namespace: leader.Namespace, SessionID: "managed-leader",
		AgentID: leaderID, AgentName: "leader", Framework: string(controlmodel.DataPlaneManaged),
		Phase: store.SessionPhaseActive, Busy: &busy, AgentTaskID: &leader.ID,
	})
	if err != nil {
		t.Fatal(err)
	}
	metadata, _ := json.Marshal(map[string]any{"attemptId": attempt.ID.String()})
	if err = st.Events().Append(ctx, &store.SessionEvent{SessionFK: session.ID, Seq: 1,
		EventType: "agent.message", Role: "assistant", Content: "please provide the missing credential",
		FrameworkMeta: metadata, OccurredAt: time.Now().UTC()}); err != nil {
		t.Fatal(err)
	}
	err = srv.applyManagedSessionStatus(ctx, session, &managedSessionEventReport{
		Type: "session.status_idle", AgentTaskID: leader.ID.String(), AttemptID: attempt.ID.String(),
		DispatchGen: attempt.DispatchGeneration, TurnID: attempt.TurnID,
	}, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	failedLeader, _ := st.Collaboration().GetAgentTask(ctx, leader.ID)
	failedAttempt, _ := st.ExecutionAttempts().Get(ctx, attempt.ID)
	if failedLeader.Status != controlmodel.AgentTaskFailed || failedAttempt.State != controlmodel.ExecutionFailed ||
		failedLeader.ErrorCode != "managed_turn_incomplete" ||
		!strings.Contains(failedLeader.ErrorMessage, "please provide the missing credential") {
		t.Fatalf("text-only managed return did not fail immediately: task=%+v attempt=%+v", failedLeader, failedAttempt)
	}
}

func TestManagedTaskEventWithoutAttemptFenceCannotConsumeFencedSourceKey(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agentID := uuid.New()
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "default", Title: "fenced managed work",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: agentID.String(),
	})
	if err != nil {
		t.Fatal(err)
	}
	tasks, _ := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
	claimed, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version, SessionID: "managed-unfenced"},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
			State: controlmodel.ExecutionAssigned, SessionID: "managed-unfenced", TurnID: "current-turn"})
	if err != nil {
		t.Fatal(err)
	}
	busy := false
	session, err := st.Sessions().Upsert(ctx, &store.Session{
		Tenant: claimed.Tenant, Namespace: claimed.Namespace, SessionID: "managed-unfenced",
		AgentID: agentID, AgentName: "managed-worker", Framework: string(controlmodel.DataPlaneManaged),
		Phase: store.SessionPhaseIdle, Busy: &busy, AgentTaskID: &claimed.ID,
	})
	if err != nil {
		t.Fatal(err)
	}
	srv := NewServer(ServerOptions{Store: st, InternalToken: "internal-secret"})
	body, _ := json.Marshal(managedSessionEventReport{ID: "evt-unfenced", SessionID: session.SessionID,
		Seq: 1, Type: "session.status_running", CreatedAt: time.Now().UnixMilli()})
	req := httptest.NewRequest(http.MethodPost,
		"/api/internal/runtime-sessions/"+session.SessionID+"/events", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Builder-Internal-Token", "internal-secret")
	response := httptest.NewRecorder()
	srv.router.ServeHTTP(response, req)
	if response.Code != http.StatusConflict {
		t.Fatalf("unfenced report: status=%d body=%s", response.Code, response.Body.String())
	}
	unchangedTask, _ := st.Collaboration().GetAgentTask(ctx, claimed.ID)
	unchangedAttempt, _ := st.ExecutionAttempts().Get(ctx, attempt.ID)
	if unchangedTask.Status != controlmodel.AgentTaskDispatched || unchangedAttempt.State != controlmodel.ExecutionAssigned ||
		unchangedAttempt.LeaseExpiresAt != nil {
		t.Fatalf("unfenced event mutated current retry: task=%+v attempt=%+v", unchangedTask, unchangedAttempt)
	}
	events, err := st.Events().List(ctx, session.ID)
	if err != nil || len(events) != 0 {
		t.Fatalf("unfenced event consumed its source key: events=%+v err=%v", events, err)
	}

	// A generic event mirror can race the explicit ManagedExecutionScope upload
	// with the same stable event ID. Rejecting (rather than recording) the first
	// unscoped copy lets the correctly fenced copy become the unique ACK.
	fencedBody, _ := json.Marshal(managedSessionEventReport{ID: "evt-unfenced", SessionID: session.SessionID,
		Seq: 1, Type: "session.status_running", CreatedAt: time.Now().UnixMilli(),
		AgentTaskID: claimed.ID.String(), AttemptID: attempt.ID.String(),
		DispatchGen: attempt.DispatchGeneration, TurnID: attempt.TurnID})
	fencedReq := httptest.NewRequest(http.MethodPost,
		"/api/internal/runtime-sessions/"+session.SessionID+"/events", bytes.NewReader(fencedBody))
	fencedReq.Header.Set("Content-Type", "application/json")
	fencedReq.Header.Set("X-Builder-Internal-Token", "internal-secret")
	fencedResponse := httptest.NewRecorder()
	srv.router.ServeHTTP(fencedResponse, fencedReq)
	if fencedResponse.Code != http.StatusNoContent {
		t.Fatalf("fenced retry: status=%d body=%s", fencedResponse.Code, fencedResponse.Body.String())
	}
	startedTask, _ := st.Collaboration().GetAgentTask(ctx, claimed.ID)
	events, _ = st.Events().List(ctx, session.ID)
	if startedTask.Status != controlmodel.AgentTaskRunning || len(events) != 1 {
		t.Fatalf("fenced retry was not uniquely applied: task=%+v events=%+v", startedTask, events)
	}
}

func TestManagedHeartbeatStopsFailedOrReplacedAttemptButAllowsSuccessfulTail(t *testing.T) {
	t.Run("failed and replaced Attempt is gone", func(t *testing.T) {
		ctx := context.Background()
		st, session, task, attemptA, _ := createManagedApprovalFixture(t, ctx)
		queued, _, err := st.Collaboration().RequeueAgentTaskAfterAttemptFailure(ctx, task.ID,
			store.TaskFailure{ExpectedVersion: task.Version, AttemptID: attemptA.ID,
				DispatchGeneration: attemptA.DispatchGeneration, Code: "heartbeat_timeout", Message: "lost A"})
		if err != nil {
			t.Fatal(err)
		}
		srv := NewServer(ServerOptions{Store: st, InternalToken: "internal-secret"})
		postHeartbeat := func(attempt *controlmodel.ExecutionAttempt) *httptest.ResponseRecorder {
			t.Helper()
			body, _ := json.Marshal(managedSessionHeartbeat{AttemptID: attempt.ID,
				DispatchGen: attempt.DispatchGeneration, TurnID: attempt.TurnID})
			req := httptest.NewRequest(http.MethodPost,
				"/api/internal/runtime-sessions/"+session.SessionID+"/heartbeat", bytes.NewReader(body))
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("X-Builder-Internal-Token", "internal-secret")
			response := httptest.NewRecorder()
			srv.router.ServeHTTP(response, req)
			return response
		}
		if response := postHeartbeat(attemptA); response.Code != http.StatusGone {
			t.Fatalf("failed A heartbeat status=%d body=%s", response.Code, response.Body.String())
		}
		_, attemptB, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
			store.TaskClaim{TaskID: queued.ID, ExpectedVersion: queued.Version, SessionID: session.SessionID},
			&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
				State: controlmodel.ExecutionAssigned, SessionID: session.SessionID, TurnID: "turn-b",
				ManagedOwnerRef: "owner"})
		if err != nil {
			t.Fatal(err)
		}
		if response := postHeartbeat(attemptA); response.Code != http.StatusGone {
			t.Fatalf("replaced A heartbeat status=%d body=%s", response.Code, response.Body.String())
		}
		if response := postHeartbeat(attemptB); response.Code != http.StatusNoContent {
			t.Fatalf("current B heartbeat status=%d body=%s", response.Code, response.Body.String())
		}
	})

	t.Run("successful Attempt tail is acknowledged", func(t *testing.T) {
		ctx := context.Background()
		st, session, task, attempt, approval := createManagedApprovalFixture(t, ctx)
		approved, err := st.Collaboration().DecideApproval(ctx, approval.ID, approval.Version,
			controlmodel.ApprovalApproved, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, nil)
		if err != nil {
			t.Fatal(err)
		}
		resumedTask, _, err := st.Collaboration().ResumeManagedToolApproval(ctx, approved.ID,
			mustManagedApprovalEnvelope(t, approved).fence(), managedExecutionLeaseTTL)
		if err != nil {
			t.Fatal(err)
		}
		if _, err = st.Collaboration().CompleteAgentTask(ctx, task.ID, store.TaskCompletion{
			ExpectedVersion: resumedTask.Version, AttemptID: attempt.ID,
			DispatchGeneration: attempt.DispatchGeneration, Summary: "done"}); err != nil {
			t.Fatal(err)
		}
		body, _ := json.Marshal(managedSessionHeartbeat{AttemptID: attempt.ID,
			DispatchGen: attempt.DispatchGeneration, TurnID: attempt.TurnID})
		req := httptest.NewRequest(http.MethodPost,
			"/api/internal/runtime-sessions/"+session.SessionID+"/heartbeat", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Builder-Internal-Token", "internal-secret")
		response := httptest.NewRecorder()
		NewServer(ServerOptions{Store: st, InternalToken: "internal-secret"}).router.ServeHTTP(response, req)
		if response.Code != http.StatusNoContent {
			t.Fatalf("successful tail heartbeat status=%d body=%s", response.Code, response.Body.String())
		}
		tailBody, _ := json.Marshal(managedSessionEventReport{ID: "evt-success-tail", SessionID: session.SessionID,
			Seq: 9, Type: "agent.message", Payload: map[string]any{"text": "final answer after completion"},
			CreatedAt: time.Now().UnixMilli(), AgentTaskID: task.ID.String(), AttemptID: attempt.ID.String(),
			DispatchGen: attempt.DispatchGeneration, TurnID: attempt.TurnID})
		tailReq := httptest.NewRequest(http.MethodPost,
			"/api/internal/runtime-sessions/"+session.SessionID+"/events", bytes.NewReader(tailBody))
		tailReq.Header.Set("Content-Type", "application/json")
		tailReq.Header.Set("X-Builder-Internal-Token", "internal-secret")
		tailResponse := httptest.NewRecorder()
		NewServer(ServerOptions{Store: st, InternalToken: "internal-secret"}).router.ServeHTTP(tailResponse, tailReq)
		if tailResponse.Code != http.StatusNoContent {
			t.Fatalf("successful assistant tail status=%d body=%s", tailResponse.Code, tailResponse.Body.String())
		}
		events, err := st.Events().List(ctx, session.ID)
		if err != nil || len(events) != 1 || events[0].Content != "final answer after completion" {
			t.Fatalf("successful assistant tail was lost: events=%+v err=%v", events, err)
		}
	})
}

func TestManagedRuntimeStatusFenceRejectsOldAttemptAfterRetry(t *testing.T) {
	ctx := context.Background()
	st, session, task, attemptA, _ := createManagedApprovalFixture(t, ctx)
	srv := NewServer(ServerOptions{Store: st})
	if managed, err := srv.validateManagedSessionRuntimeFence(ctx, session.SessionID, product.ManagedRuntimeFence{}); !managed || !errors.Is(err, product.ErrManagedRuntimeFenceConflict) {
		t.Fatalf("managed Session accepted missing fence: managed=%v err=%v", managed, err)
	}
	personalSessionID := "personal-runtime-fence"
	if _, err := st.Sessions().Upsert(ctx, &store.Session{Tenant: "tenant-a", Namespace: "default",
		AgentID: uuid.New(), SessionID: personalSessionID, Phase: store.SessionPhaseActive}); err != nil {
		t.Fatal(err)
	}
	if managed, err := srv.validateManagedSessionRuntimeFence(ctx, personalSessionID, product.ManagedRuntimeFence{}); err != nil || managed {
		t.Fatalf("personal Session rejected empty fence: managed=%v err=%v", managed, err)
	}
	fenceA := product.ManagedRuntimeFence{AgentTaskID: task.ID.String(), AttemptID: attemptA.ID.String(),
		DispatchGeneration: attemptA.DispatchGeneration, TurnID: attemptA.TurnID}
	managed, err := srv.validateManagedSessionRuntimeFence(ctx, session.SessionID, fenceA)
	if err != nil || !managed {
		t.Fatalf("current A fence managed=%v err=%v", managed, err)
	}
	wrongTurn := fenceA
	wrongTurn.TurnID = "different-turn"
	if _, err = srv.validateManagedSessionRuntimeFence(ctx, session.SessionID, wrongTurn); !errors.Is(err, product.ErrManagedRuntimeFenceConflict) {
		t.Fatalf("same Attempt wrong tuple err=%v", err)
	}
	queued, _, err := st.Collaboration().RequeueAgentTaskAfterAttemptFailure(ctx, task.ID,
		store.TaskFailure{ExpectedVersion: task.Version, AttemptID: attemptA.ID,
			DispatchGeneration: attemptA.DispatchGeneration, Code: "heartbeat_timeout", Message: "retry"})
	if err != nil {
		t.Fatal(err)
	}
	_, attemptB, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: queued.ID, ExpectedVersion: queued.Version, SessionID: session.SessionID},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
			State: controlmodel.ExecutionAssigned, SessionID: session.SessionID, TurnID: "turn-b"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = srv.validateManagedSessionRuntimeFence(ctx, session.SessionID, fenceA); !errors.Is(err, product.ErrManagedRuntimeFenceGone) {
		t.Fatalf("old A after B err=%v", err)
	}
	fenceB := product.ManagedRuntimeFence{AgentTaskID: task.ID.String(), AttemptID: attemptB.ID.String(),
		DispatchGeneration: attemptB.DispatchGeneration, TurnID: attemptB.TurnID}
	if managed, err = srv.validateManagedSessionRuntimeFence(ctx, session.SessionID, fenceB); err != nil || !managed {
		t.Fatalf("current B fence managed=%v err=%v", managed, err)
	}
}

func TestManagedExecutionContextUsesCurrentAttemptScopedToken(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agentID := uuid.New()
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "default", Title: "context work",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: agentID.String(),
	})
	if err != nil {
		t.Fatal(err)
	}
	tasks, _ := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
	claimed, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version, SessionID: "managed-context-1"},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
			State: controlmodel.ExecutionAssigned, SessionID: "managed-context-1", TurnID: "turn-1"})
	if err != nil {
		t.Fatal(err)
	}
	_, err = st.Sessions().Upsert(ctx, &store.Session{
		Tenant: claimed.Tenant, Namespace: claimed.Namespace, SessionID: "managed-context-1",
		AgentID: agentID, AgentName: "managed-worker", AgentTaskID: &claimed.ID,
	})
	if err != nil {
		t.Fatal(err)
	}
	srv := NewServer(ServerOptions{Store: st, TaskTokenSecret: "0123456789abcdef0123456789abcdef"})
	raw := srv.managedExecutionContextForSession(ctx, "managed-context-1")
	var executionContext struct {
		AttemptID          uuid.UUID `json:"attemptId"`
		DispatchGeneration int64     `json:"dispatchGeneration"`
		TaskContext        struct {
			TaskToken        string   `json:"taskToken"`
			AvailableActions []string `json:"availableActions"`
		} `json:"taskContext"`
	}
	if err := json.Unmarshal(raw, &executionContext); err != nil {
		t.Fatalf("decode execution context %s: %v", raw, err)
	}
	if executionContext.AttemptID != attempt.ID ||
		executionContext.DispatchGeneration != attempt.DispatchGeneration ||
		executionContext.TaskContext.TaskToken == "" {
		t.Fatalf("unexpected execution context: %+v", executionContext)
	}
	claims, err := srv.taskTokens.VerifyClaims(executionContext.TaskContext.TaskToken, time.Now().UTC())
	if err != nil || claims.TaskID != claimed.ID || claims.AttemptID != attempt.ID ||
		claims.Generation != attempt.DispatchGeneration {
		t.Fatalf("token not fenced to current attempt: claims=%+v err=%v", claims, err)
	}
	foundStart := false
	for _, action := range executionContext.TaskContext.AvailableActions {
		foundStart = foundStart || action == "task.start"
	}
	if !foundStart || bytes.Contains(raw, []byte("attemptToken")) {
		t.Fatalf("unexpected managed action contract: %s", raw)
	}
}

func TestStaleManagedTurnCannotFailRetryAttempt(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agentID := uuid.New()
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "default", Title: "retry managed work",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: agentID.String(),
	})
	if err != nil {
		t.Fatal(err)
	}
	tasks, _ := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
	claimed, oldAttempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version, SessionID: "managed-retry-1"},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
			State: controlmodel.ExecutionAssigned, SessionID: "managed-retry-1", TurnID: "old-turn"})
	if err != nil {
		t.Fatal(err)
	}
	claimed, err = st.Collaboration().StartAgentTask(ctx, claimed.ID, claimed.Version)
	if err != nil {
		t.Fatal(err)
	}
	queued, _, err := st.Collaboration().RequeueAgentTaskAfterAttemptFailure(ctx, claimed.ID, store.TaskFailure{
		ExpectedVersion: claimed.Version, AttemptID: oldAttempt.ID,
		DispatchGeneration: oldAttempt.DispatchGeneration, Code: "heartbeat_timeout", Message: "expired"})
	if err != nil {
		t.Fatal(err)
	}
	claimed, newAttempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: queued.ID, ExpectedVersion: queued.Version, SessionID: "managed-retry-1"},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
			State: controlmodel.ExecutionAssigned, SessionID: "managed-retry-1", TurnID: "new-turn"})
	if err != nil {
		t.Fatal(err)
	}
	_, err = st.Sessions().Upsert(ctx, &store.Session{
		Tenant: claimed.Tenant, Namespace: claimed.Namespace, SessionID: "managed-retry-1",
		AgentID: agentID, AgentName: "managed-worker", AgentTaskID: &claimed.ID,
	})
	if err != nil {
		t.Fatal(err)
	}
	srv := NewServer(ServerOptions{Store: st, InternalToken: "internal-secret"})
	body, _ := json.Marshal(managedSessionEventReport{ID: "evt-old-error", SessionID: "managed-retry-1",
		Seq: 9, Type: "session.error", Payload: map[string]any{"error": map[string]any{
			"code": "old_turn_failed", "message": "stale physical turn"}}, CreatedAt: time.Now().UnixMilli(),
		AgentTaskID: claimed.ID.String(), AttemptID: oldAttempt.ID.String(),
		DispatchGen: oldAttempt.DispatchGeneration, TurnID: oldAttempt.TurnID})
	req := httptest.NewRequest(http.MethodPost,
		"/api/internal/runtime-sessions/managed-retry-1/events", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Builder-Internal-Token", "internal-secret")
	response := httptest.NewRecorder()
	srv.router.ServeHTTP(response, req)
	if response.Code != http.StatusGone {
		t.Fatalf("stale event report: status=%d body=%s", response.Code, response.Body.String())
	}
	current, _ := st.Collaboration().GetAgentTask(ctx, claimed.ID)
	currentAttempt, _ := st.ExecutionAttempts().Get(ctx, newAttempt.ID)
	if current.Status != controlmodel.AgentTaskDispatched || current.CurrentAttemptID == nil ||
		*current.CurrentAttemptID != newAttempt.ID || currentAttempt.State != controlmodel.ExecutionAssigned {
		t.Fatalf("stale turn changed retry: task=%+v attempt=%+v", current, currentAttempt)
	}
}

func TestManagedThinkingPreservesRecordedContentAndTruncation(t *testing.T) {
	event := managedReportToSessionEvent(&managedSessionEventReport{
		ID: "evt-thinking", Type: "agent.thinking", Seq: 2,
		Payload: map[string]any{"text": "Recorded reasoning", "truncated": true, "originalSize": 70000},
	})
	if event.Content != "Recorded reasoning" || event.Role != "assistant" ||
		!bytes.Contains(event.FrameworkMeta, []byte(`"truncated":true`)) ||
		!bytes.Contains(event.FrameworkMeta, []byte(`"originalSize":70000`)) {
		t.Fatalf("thinking projection lost content or truncation: %+v", event)
	}
}

func TestManagedErrorProjectsReadableFailure(t *testing.T) {
	event := managedReportToSessionEvent(&managedSessionEventReport{ID: "evt-error", Type: "session.error",
		Payload: map[string]any{"error": map[string]any{"code": "tool_failed", "message": "Tool could not read the file"}},
	})
	if event.Content != "Tool could not read the file" || !bytes.Contains(event.FrameworkMeta, []byte(`"code":"tool_failed"`)) {
		t.Fatalf("error detail lost: %+v", event)
	}
}

func TestManagedConnectionWarningKeepsRequiredMCPFailuresFatal(t *testing.T) {
	for _, tc := range []struct{ name, kind, retry, want string }{
		{"optional", "mcp_connection_failed_error", "next_turn", "session.warning"},
		{"required bootstrap", "api_error", "next_turn", "session.error"},
		{"unknown severity", "", "next_turn", "session.error"},
		{"not recoverable", "mcp_connection_failed_error", "not_retrying", "session.error"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			report := managedSessionEventReport{Type: "session.error", Payload: map[string]any{"error": map[string]any{"type": tc.kind, "code": "mcp_connection_failed_error", "retry_status": tc.retry}}}
			normalizeManagedConnectionWarning(&report)
			if report.Type != tc.want {
				t.Fatalf("got %s want %s", report.Type, tc.want)
			}
		})
	}
}

func TestWorkflowIncompleteTurnGetsOnlyOneFencedCorrection(t *testing.T) {
	ctx := t.Context()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	agentID := uuid.New()
	osvc := &orchestration.Service{Store: st}
	def, err := st.Orchestration().CreateDefinition(ctx, &controlmodel.OrchestrationDefinition{Tenant: "t", Namespace: "n", Name: "poem", CreatedBy: actor, DraftSpec: json.RawMessage(fmt.Sprintf(`{"nodes":[{"key":"draft","type":"agent","agentId":%q}]}`, agentID.String()))})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = osvc.Publish(ctx, def.ID, actor); err != nil {
		t.Fatal(err)
	}
	run, err := osvc.Start(ctx, def.ID, orchestration.StartRequest{IdempotencyKey: "one", Issue: &controlmodel.Issue{Title: "write a poem", Creator: actor}, Actor: actor})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{RunID: run.ID, Limit: 10})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("tasks=%v %v", tasks, err)
	}
	task := tasks[0]
	srv := NewServer(ServerOptions{Store: st})
	for generation := 1; generation <= 2; generation++ {
		claimed, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx, store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version, SessionID: "workflow-correction"}, &controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged, State: controlmodel.ExecutionAssigned, SessionID: "workflow-correction", TurnID: fmt.Sprint(generation)})
		if err != nil {
			t.Fatal(err)
		}
		claimed, err = st.Collaboration().StartAgentTask(ctx, claimed.ID, claimed.Version)
		if err != nil {
			t.Fatal(err)
		}
		session, err := st.Sessions().Upsert(ctx, &store.Session{Tenant: "t", Namespace: "n", SessionID: "workflow-correction", AgentID: agentID, Framework: "managed", Phase: store.SessionPhaseActive, AgentTaskID: &claimed.ID})
		if err != nil {
			t.Fatal(err)
		}
		meta, _ := json.Marshal(map[string]any{"attemptId": attempt.ID.String()})
		if err = st.Events().Append(ctx, &store.SessionEvent{SessionFK: session.ID, Seq: generation, EventType: "agent.message", Content: "unsubmitted poem", FrameworkMeta: meta, OccurredAt: time.Now()}); err != nil {
			t.Fatal(err)
		}
		err = srv.applyManagedSessionStatus(ctx, session, &managedSessionEventReport{SessionID: session.SessionID, Type: "session.status_idle", AgentTaskID: claimed.ID.String(), AttemptID: attempt.ID.String(), DispatchGen: attempt.DispatchGeneration, TurnID: attempt.TurnID}, time.Now())
		if err != nil {
			t.Fatal(err)
		}
		task, err = st.Collaboration().GetAgentTask(ctx, task.ID)
		if err != nil {
			t.Fatal(err)
		}
		currentAttempt, _ := st.ExecutionAttempts().Get(ctx, attempt.ID)
		if currentAttempt.State != controlmodel.ExecutionFailed {
			t.Fatalf("missing failed attempt: %+v", currentAttempt)
		}
		if generation == 1 {
			if task.Status != controlmodel.AgentTaskQueued || task.OrchestrationRunID != run.ID {
				t.Fatalf("correction broke task lineage: %+v", task)
			}
			brief, err := (&collaboration.Service{Store: st}).BuildContext(ctx, task.ID)
			if err != nil {
				t.Fatal(err)
			}
			if !strings.Contains(brief.ExecutionBrief.Workflow.ProtocolCorrection, "unsubmitted poem") {
				t.Fatalf("missing previous response: %+v", brief.ExecutionBrief)
			}
			wake := brief.ExecutionBrief.Workflow.ProtocolCorrection
			if !strings.Contains(wake, "task.complete") {
				t.Fatal("missing correction instruction")
			}
		} else if task.Status != controlmodel.AgentTaskFailed {
			t.Fatalf("unbounded correction: %+v", task)
		}
	}
	finalRun, _ := st.Orchestration().GetRun(ctx, run.ID)
	if finalRun.State != controlmodel.RunFailed {
		t.Fatalf("retry exhaustion did not converge: %+v", finalRun)
	}
}

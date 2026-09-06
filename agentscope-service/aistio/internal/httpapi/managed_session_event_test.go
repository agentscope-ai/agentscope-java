// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

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

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
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
		CreatedAt: time.Now().UnixMilli()})
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

	errorBody, _ := json.Marshal(managedSessionEventReport{ID: "evt-error", SessionID: session.SessionID,
		Seq: 2, Type: "session.error", Payload: map[string]any{"error": map[string]any{
			"code": "model_call_failed", "message": "provider unavailable"}}, CreatedAt: time.Now().UnixMilli(),
		AttemptID: attempt.ID.String(), DispatchGen: attempt.DispatchGeneration, TurnID: attempt.TurnID})
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
		AttemptID: oldAttempt.ID.String(), DispatchGen: oldAttempt.DispatchGeneration, TurnID: oldAttempt.TurnID})
	req := httptest.NewRequest(http.MethodPost,
		"/api/internal/runtime-sessions/managed-retry-1/events", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Builder-Internal-Token", "internal-secret")
	response := httptest.NewRecorder()
	srv.router.ServeHTTP(response, req)
	if response.Code != http.StatusNoContent {
		t.Fatalf("stale event report: status=%d body=%s", response.Code, response.Body.String())
	}
	current, _ := st.Collaboration().GetAgentTask(ctx, claimed.ID)
	currentAttempt, _ := st.ExecutionAttempts().Get(ctx, newAttempt.ID)
	if current.Status != controlmodel.AgentTaskDispatched || current.CurrentAttemptID == nil ||
		*current.CurrentAttemptID != newAttempt.ID || currentAttempt.State != controlmodel.ExecutionAssigned {
		t.Fatalf("stale turn changed retry: task=%+v attempt=%+v", current, currentAttempt)
	}
}

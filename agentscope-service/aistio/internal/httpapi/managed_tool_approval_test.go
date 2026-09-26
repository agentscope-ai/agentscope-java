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

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

type recordingManagedConfirmationSender struct {
	failures     int
	permanentErr error
	calls        int
	sessionID    string
	toolUseID    string
	decision     product.ManagedToolConfirmationDecision
}

type recordingManagedAbortSender struct {
	calls     int
	sessionID string
	abort     product.ManagedAttemptAbort
	err       error
}

func (s *recordingManagedAbortSender) PostManagedAttemptAbort(_ context.Context,
	sessionID, _ string, abort product.ManagedAttemptAbort) error {
	s.calls++
	s.sessionID, s.abort = sessionID, abort
	return s.err
}

func (s *recordingManagedConfirmationSender) PostManagedToolConfirmation(_ context.Context,
	sessionID, _ string, toolUseID string, decision product.ManagedToolConfirmationDecision) error {
	s.calls++
	s.sessionID, s.toolUseID, s.decision = sessionID, toolUseID, decision
	if s.failures > 0 {
		s.failures--
		return errors.New("temporary data-plane failure")
	}
	if s.permanentErr != nil {
		return s.permanentErr
	}
	return nil
}

func TestManagedToolApprovalProjectsWaitAndReliablyResumes(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agentID := uuid.New()
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "default", Title: "managed approval",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: agentID.String(),
	})
	if err != nil {
		t.Fatal(err)
	}
	tasks, _ := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
	claimed, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version, SessionID: "managed-hitl"},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
			State: controlmodel.ExecutionAssigned, SessionID: "managed-hitl", TurnID: "turn-hitl",
			ManagedOwnerRef: "owner"})
	if err != nil {
		t.Fatal(err)
	}
	approvalID := store.ManagedToolApprovalID(claimed.Tenant, "managed-hitl", attempt.ID.String(),
		attempt.DispatchGeneration, attempt.TurnID, "call-1")
	claimed, err = st.Collaboration().StartAgentTask(ctx, claimed.ID, claimed.Version)
	if err != nil {
		t.Fatal(err)
	}
	busy := true
	session, err := st.Sessions().Upsert(ctx, &store.Session{Tenant: claimed.Tenant,
		Namespace: claimed.Namespace, SessionID: "managed-hitl", AgentID: agentID,
		AgentName: "managed-worker", Framework: string(controlmodel.DataPlaneManaged),
		Phase: store.SessionPhaseActive, Busy: &busy, AgentTaskID: &claimed.ID})
	if err != nil {
		t.Fatal(err)
	}
	sender := &recordingManagedConfirmationSender{failures: 1}
	srv := NewServer(ServerOptions{Store: st, InternalToken: "internal-secret",
		ManagedConfirmations: sender})

	report := func(eventID string, seq int64) {
		t.Helper()
		body, _ := json.Marshal(managedSessionEventReport{ID: eventID, SessionID: session.SessionID,
			Seq: seq, Type: "session.requires_action", AgentTaskID: claimed.ID.String(), AttemptID: attempt.ID.String(),
			DispatchGen: attempt.DispatchGeneration, TurnID: attempt.TurnID,
			CreatedAt: time.Now().UnixMilli(), Payload: map[string]any{
				"kind": "tool_confirmation", "schemaVersion": 1,
				"approvalId": approvalID.String(), "toolUseId": "call-1", "toolName": "dangerous_tool",
				"inputPreview": map[string]any{"query": "safe", "apiToken": "must-not-leak", "api-key": "also-secret"},
				"expiresAt":    time.Now().Add(time.Hour).UnixMilli(),
			}})
		req := httptest.NewRequest(http.MethodPost,
			"/api/internal/runtime-sessions/"+session.SessionID+"/events", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Builder-Internal-Token", "internal-secret")
		response := httptest.NewRecorder()
		srv.router.ServeHTTP(response, req)
		if response.Code != http.StatusNoContent {
			t.Fatalf("requires_action status=%d body=%s", response.Code, response.Body.String())
		}
	}
	report("requires-1", 1)
	report("requires-after-reporter-restart", 2)

	approvals, err := st.Collaboration().ListApprovals(ctx, store.ApprovalFilter{
		Tenant: "tenant-a", Namespace: "default", Status: controlmodel.ApprovalPending, Limit: 10})
	if err != nil || len(approvals) != 1 || approvals[0].ID != approvalID ||
		approvals[0].TargetType != controlmodel.ApprovalTargetExecutionAttempt ||
		approvals[0].TargetRef != attempt.ID.String() {
		t.Fatalf("approval projection: approvals=%+v err=%v", approvals, err)
	}
	if strings.Contains(string(approvals[0].Request), "must-not-leak") ||
		strings.Contains(string(approvals[0].Request), "also-secret") ||
		!strings.Contains(string(approvals[0].Request), "[REDACTED]") {
		t.Fatalf("approval request did not redact input: %s", approvals[0].Request)
	}
	waitingTask, _ := st.Collaboration().GetAgentTask(ctx, claimed.ID)
	waitingAttempt, _ := st.ExecutionAttempts().Get(ctx, attempt.ID)
	if waitingTask.Status != controlmodel.AgentTaskWaiting || waitingAttempt.State != controlmodel.ExecutionWaiting ||
		waitingTask.WaitReason != "approval:"+approvalID.String() {
		t.Fatalf("waiting state not atomic: task=%+v attempt=%+v", waitingTask, waitingAttempt)
	}
	inbox, _ := st.Collaboration().ListInbox(ctx, store.InboxFilter{Tenant: "tenant-a",
		Namespace: "default", RecipientRef: "owner", Limit: 10})
	if len(inbox) != 1 || inbox[0].ApprovalID == nil || *inbox[0].ApprovalID != approvalID {
		t.Fatalf("approval inbox=%+v", inbox)
	}
	if _, err = st.Collaboration().DecideApproval(ctx, approvalID, approvals[0].Version,
		controlmodel.ApprovalApproved, controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "sweeper"}, nil); err == nil {
		t.Fatal("system actor was allowed to approve tool use")
	}
	beforeLease := waitingAttempt.LeaseExpiresAt
	heartbeat, _ := json.Marshal(managedSessionHeartbeat{AttemptID: attempt.ID,
		DispatchGen: attempt.DispatchGeneration, TurnID: attempt.TurnID})
	heartbeatReq := httptest.NewRequest(http.MethodPost,
		"/api/internal/runtime-sessions/"+session.SessionID+"/heartbeat", bytes.NewReader(heartbeat))
	heartbeatReq.Header.Set("Content-Type", "application/json")
	heartbeatReq.Header.Set("X-Builder-Internal-Token", "internal-secret")
	heartbeatResponse := httptest.NewRecorder()
	srv.router.ServeHTTP(heartbeatResponse, heartbeatReq)
	if heartbeatResponse.Code != http.StatusNoContent {
		t.Fatalf("waiting heartbeat status=%d body=%s", heartbeatResponse.Code, heartbeatResponse.Body.String())
	}
	renewed, _ := st.ExecutionAttempts().Get(ctx, attempt.ID)
	if renewed.LeaseExpiresAt == nil || beforeLease != nil && !renewed.LeaseExpiresAt.After(*beforeLease) {
		t.Fatalf("waiting lease was not renewed: before=%v after=%v", beforeLease, renewed.LeaseExpiresAt)
	}

	approved, err := st.Collaboration().DecideApproval(ctx, approvalID, approvals[0].Version,
		controlmodel.ApprovalApproved, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		json.RawMessage(`{"message":"approved for test"}`))
	if err != nil {
		t.Fatal(err)
	}
	if err = srv.DispatchApprovalDecision(ctx, approvalID); err == nil {
		t.Fatal("expected first callback delivery to fail")
	}
	resumedTask, _ := st.Collaboration().GetAgentTask(ctx, claimed.ID)
	resumedAttempt, _ := st.ExecutionAttempts().Get(ctx, attempt.ID)
	if resumedTask.Status != controlmodel.AgentTaskRunning || resumedAttempt.State != controlmodel.ExecutionRunning {
		t.Fatalf("decision did not resume before callback: task=%+v attempt=%+v", resumedTask, resumedAttempt)
	}
	if err = srv.DispatchApprovalDecision(ctx, approvalID); err != nil {
		t.Fatal(err)
	}
	if sender.calls != 2 || sender.sessionID != session.SessionID || sender.toolUseID != "call-1" ||
		sender.decision.ApprovalID != approvalID || sender.decision.AgentTaskID != claimed.ID ||
		sender.decision.AttemptID != attempt.ID || sender.decision.DecisionVersion != approved.Version ||
		!sender.decision.Allow || sender.decision.DispatchGeneration != attempt.DispatchGeneration ||
		sender.decision.TurnID != attempt.TurnID {
		t.Fatalf("unexpected callback: sender=%+v", sender)
	}
	events, err := st.Orchestration().ListRunEvents(ctx, claimed.OrchestrationRunID, 0, 100)
	if err != nil {
		t.Fatal(err)
	}
	delivered := 0
	for _, event := range events {
		if event.Type == "approval.delivery_delivered" {
			delivered++
		}
	}
	if delivered != 1 {
		t.Fatalf("delivered diagnostic count=%d events=%+v", delivered, events)
	}
	if err = srv.DispatchApprovalDecision(ctx, approvalID); err != nil {
		t.Fatal(err)
	}
	events, _ = st.Orchestration().ListRunEvents(ctx, claimed.OrchestrationRunID, 0, 100)
	delivered = 0
	for _, event := range events {
		if event.Type == "approval.delivery_delivered" {
			delivered++
		}
	}
	if sender.calls != 3 || delivered != 1 {
		t.Fatalf("outbox replay calls=%d delivered diagnostics=%d", sender.calls, delivered)
	}
}

func TestManagedToolApprovalPermanentCallbackFailureIsDiagnosedAndRequeued(t *testing.T) {
	ctx := context.Background()
	st, session, task, attempt, approval := createManagedApprovalFixture(t, ctx)
	sender := &recordingManagedConfirmationSender{permanentErr: &product.ManagedToolConfirmationDeliveryError{
		StatusCode: http.StatusGone, Message: "confirmation ticket expired",
	}}
	srv := NewServer(ServerOptions{Store: st, ManagedConfirmations: sender})

	approved, err := st.Collaboration().DecideApproval(ctx, approval.ID, approval.Version,
		controlmodel.ApprovalApproved, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err = srv.DispatchApprovalDecision(ctx, approved.ID); err != nil {
		t.Fatal(err)
	}
	queued, _ := st.Collaboration().GetAgentTask(ctx, task.ID)
	failedAttempt, _ := st.ExecutionAttempts().Get(ctx, attempt.ID)
	if sender.calls != 1 || sender.sessionID != session.SessionID ||
		queued.Status != controlmodel.AgentTaskQueued ||
		failedAttempt.State != controlmodel.ExecutionFailed ||
		failedAttempt.FailureCode != "hitl_continuation_lost" {
		t.Fatalf("permanent callback outcome: sender=%+v task=%+v attempt=%+v", sender, queued, failedAttempt)
	}
	events, err := st.Orchestration().ListRunEvents(ctx, task.OrchestrationRunID, 0, 100)
	if err != nil {
		t.Fatal(err)
	}
	staleEvents := 0
	for _, event := range events {
		if event.Type == "approval.delivery_stale" {
			staleEvents++
			if !strings.Contains(string(event.Payload), "data_plane_http_410") {
				t.Fatalf("stale delivery diagnostic=%s", event.Payload)
			}
		}
	}
	if staleEvents != 1 {
		t.Fatalf("stale delivery diagnostic count=%d events=%+v", staleEvents, events)
	}

	// Simulate a crash after the retry was committed but before the outbox row
	// was acknowledged. The replay recognizes the already-fenced continuation,
	// neither redelivers nor creates a second diagnostic.
	if err = srv.DispatchApprovalDecision(ctx, approved.ID); err != nil {
		t.Fatal(err)
	}
	if sender.calls != 1 {
		t.Fatalf("stale outbox replay redelivered callback: calls=%d", sender.calls)
	}
	events, _ = st.Orchestration().ListRunEvents(ctx, task.OrchestrationRunID, 0, 100)
	staleEvents = 0
	for _, event := range events {
		if event.Type == "approval.delivery_stale" {
			staleEvents++
		}
	}
	if staleEvents != 1 {
		t.Fatalf("stale outbox replay duplicated diagnostic: count=%d", staleEvents)
	}
}

func TestManagedToolApprovalCancelledTaskCannotReleaseTool(t *testing.T) {
	ctx := context.Background()
	st, _, task, attempt, approval := createManagedApprovalFixture(t, ctx)
	sender := &recordingManagedConfirmationSender{}
	srv := NewServer(ServerOptions{Store: st, ManagedConfirmations: sender})
	approved, err := st.Collaboration().DecideApproval(ctx, approval.ID, approval.Version,
		controlmodel.ApprovalApproved, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	current, _ := st.Collaboration().GetAgentTask(ctx, task.ID)
	if _, err = st.Collaboration().CancelAgentTask(ctx, task.ID, current.Version); err != nil {
		t.Fatal(err)
	}
	currentAttempt, _ := st.ExecutionAttempts().Get(ctx, attempt.ID)
	cancelRequested, err := st.ExecutionAttempts().Cancel(ctx, attempt.ID, currentAttempt.Version)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.ExecutionAttempts().ForceCancelled(ctx, attempt.ID, cancelRequested.Version); err != nil {
		t.Fatal(err)
	}
	if _, _, err = st.Collaboration().ResumeManagedToolApproval(ctx, approval.ID,
		mustManagedApprovalEnvelope(t, approval).fence(), managedExecutionLeaseTTL); !errors.Is(err, store.ErrConflict) {
		t.Fatalf("terminal Task/Attempt was accepted as an idempotent resume: err=%v", err)
	}
	if err = srv.DispatchApprovalDecision(ctx, approved.ID); err != nil {
		t.Fatal(err)
	}
	if sender.calls != 0 {
		t.Fatalf("cancelled task released a tool callback: calls=%d", sender.calls)
	}
	events, _ := st.Orchestration().ListRunEvents(ctx, task.OrchestrationRunID, 0, 100)
	found := false
	for _, event := range events {
		found = found || event.Type == "approval.delivery_stale"
	}
	if !found {
		t.Fatal("cancelled Task did not record a stale Approval delivery")
	}
}

func TestManagedConfirmationAfterAttemptReplacementIsRejectedAndNotMirrored(t *testing.T) {
	ctx := context.Background()
	st, session, task, attemptA, approval := createManagedApprovalFixture(t, ctx)
	approved, err := st.Collaboration().DecideApproval(ctx, approval.ID, approval.Version,
		controlmodel.ApprovalApproved, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	envelope := mustManagedApprovalEnvelope(t, approved)
	resumedTask, _, err := st.Collaboration().ResumeManagedToolApproval(ctx, approval.ID,
		envelope.fence(), managedExecutionLeaseTTL)
	if err != nil {
		t.Fatal(err)
	}
	queued, _, err := st.Collaboration().RequeueAgentTaskAfterAttemptFailure(ctx, task.ID,
		store.TaskFailure{ExpectedVersion: resumedTask.Version, AttemptID: attemptA.ID,
			DispatchGeneration: attemptA.DispatchGeneration, Code: "heartbeat_timeout", Message: "replace A"})
	if err != nil {
		t.Fatal(err)
	}
	_, attemptB, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: queued.ID, ExpectedVersion: queued.Version, SessionID: session.SessionID},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
			State: controlmodel.ExecutionAssigned, SessionID: session.SessionID, TurnID: "turn-b",
			ManagedOwnerRef: "owner"})
	if err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(managedSessionEventReport{
		ID: "evt_hitl_decision_" + approved.ID.String(), SessionID: session.SessionID,
		Seq: 2, Type: "user.tool_confirmation", CreatedAt: time.Now().UnixMilli(),
		AgentTaskID: task.ID.String(), AttemptID: attemptA.ID.String(),
		DispatchGen: attemptA.DispatchGeneration, TurnID: attemptA.TurnID,
		Payload: map[string]any{"approvalId": approved.ID.String(), "toolUseId": envelope.ToolUseID,
			"status": string(approved.Status), "decisionVersion": approved.Version, "source": "control_plane"},
	})
	req := httptest.NewRequest(http.MethodPost,
		"/api/internal/runtime-sessions/"+session.SessionID+"/events", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Builder-Internal-Token", "internal-secret")
	response := httptest.NewRecorder()
	NewServer(ServerOptions{Store: st, InternalToken: "internal-secret"}).router.ServeHTTP(response, req)
	if response.Code != http.StatusGone {
		t.Fatalf("stale confirmation status=%d body=%s", response.Code, response.Body.String())
	}
	events, eventErr := st.Events().List(ctx, session.ID)
	if eventErr != nil || len(events) != 0 {
		t.Fatalf("stale confirmation was mirrored: events=%+v err=%v", events, eventErr)
	}
	currentTask, _ := st.Collaboration().GetAgentTask(ctx, task.ID)
	if currentTask.CurrentAttemptID == nil || *currentTask.CurrentAttemptID != attemptB.ID {
		t.Fatalf("stale confirmation changed fresh Attempt B: task=%+v B=%+v", currentTask, attemptB)
	}
}

func TestManagedAcceptedConfirmationMakesTerminalOutboxReplayDelivered(t *testing.T) {
	ctx := context.Background()
	st, session, task, attempt, approval := createManagedApprovalFixture(t, ctx)
	approved, err := st.Collaboration().DecideApproval(ctx, approval.ID, approval.Version,
		controlmodel.ApprovalApproved, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	envelope := mustManagedApprovalEnvelope(t, approved)
	resumedTask, _, err := st.Collaboration().ResumeManagedToolApproval(ctx, approved.ID,
		envelope.fence(), managedExecutionLeaseTTL)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(managedSessionEventReport{
		ID: "evt_hitl_decision_" + approved.ID.String(), SessionID: session.SessionID,
		Seq: 2, Type: "user.tool_confirmation", CreatedAt: time.Now().UnixMilli(),
		AgentTaskID: task.ID.String(), AttemptID: attempt.ID.String(),
		DispatchGen: attempt.DispatchGeneration, TurnID: attempt.TurnID,
		Payload: map[string]any{"approvalId": approved.ID.String(), "toolUseId": envelope.ToolUseID,
			"status": string(approved.Status), "decisionVersion": approved.Version, "source": "control_plane"},
	})
	req := httptest.NewRequest(http.MethodPost,
		"/api/internal/runtime-sessions/"+session.SessionID+"/events", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Builder-Internal-Token", "internal-secret")
	response := httptest.NewRecorder()
	srv := NewServer(ServerOptions{Store: st, InternalToken: "internal-secret"})
	srv.router.ServeHTTP(response, req)
	if response.Code != http.StatusNoContent {
		t.Fatalf("confirmation ACK status=%d body=%s", response.Code, response.Body.String())
	}
	if _, _, err = st.Collaboration().RequeueAgentTaskAfterAttemptFailure(ctx, task.ID,
		store.TaskFailure{ExpectedVersion: resumedTask.Version, AttemptID: attempt.ID,
			DispatchGeneration: attempt.DispatchGeneration, Code: "heartbeat_timeout", Message: "crash after ACK"}); err != nil {
		t.Fatal(err)
	}
	sender := &recordingManagedConfirmationSender{}
	replayServer := NewServer(ServerOptions{Store: st, ManagedConfirmations: sender})
	if err = replayServer.DispatchApprovalDecision(ctx, approved.ID); err != nil {
		t.Fatal(err)
	}
	if sender.calls != 0 {
		t.Fatalf("accepted confirmation was unnecessarily redelivered: calls=%d", sender.calls)
	}
	runEvents, err := st.Orchestration().ListRunEvents(ctx, task.OrchestrationRunID, 0, 100)
	if err != nil {
		t.Fatal(err)
	}
	delivered, stale := 0, 0
	for _, event := range runEvents {
		switch event.Type {
		case "approval.delivery_delivered":
			delivered++
		case "approval.delivery_stale":
			stale++
		}
	}
	if delivered != 1 || stale != 0 {
		t.Fatalf("terminal replay classification delivered=%d stale=%d events=%+v", delivered, stale, runEvents)
	}
}

func TestManagedApprovalDeliveryHasExactlyOneTerminalOutcome(t *testing.T) {
	ctx := context.Background()
	st, _, _, _, approval := createManagedApprovalFixture(t, ctx)
	srv := NewServer(ServerOptions{Store: st})
	envelope := mustManagedApprovalEnvelope(t, approval)
	if err := srv.recordManagedApprovalDelivered(ctx, approval, envelope); err != nil {
		t.Fatal(err)
	}
	if err := srv.recordManagedApprovalDeliveryStale(ctx, approval, envelope,
		"stale_execution_fence", "simulated replay after event retention"); err != nil {
		t.Fatal(err)
	}
	events, err := st.Orchestration().ListRunEvents(ctx, *approval.RunID, 0, 100)
	if err != nil {
		t.Fatal(err)
	}
	delivered, stale := 0, 0
	for _, event := range events {
		switch event.Type {
		case "approval.delivery_delivered":
			delivered++
		case "approval.delivery_stale":
			stale++
		}
	}
	if delivered != 1 || stale != 0 {
		t.Fatalf("delivery outcome was not first-write-wins: delivered=%d stale=%d events=%+v",
			delivered, stale, events)
	}
}

func mustManagedApprovalEnvelope(t *testing.T, approval *controlmodel.Approval) managedToolApprovalEnvelope {
	t.Helper()
	var envelope managedToolApprovalEnvelope
	if approval == nil || json.Unmarshal(approval.Request, &envelope) != nil {
		t.Fatal("invalid managed Approval fixture")
	}
	return envelope
}

func TestManagedToolApprovalToolUseFenceRejectsSecondApprovalID(t *testing.T) {
	ctx := context.Background()
	st, _, task, attempt, approval := createManagedApprovalFixture(t, ctx)
	var secondEnvelope managedToolApprovalEnvelope
	if err := json.Unmarshal(approval.Request, &secondEnvelope); err != nil {
		t.Fatal(err)
	}
	secondEnvelope.ApprovalID = uuid.New()
	secondEnvelope.SourceEventID = "same-tool-different-source-event"
	request, _ := json.Marshal(secondEnvelope)
	second := *approval
	second.ID, second.Request = secondEnvelope.ApprovalID, request
	second.Status, second.Version = controlmodel.ApprovalPending, 0
	if _, _, _, err := st.Collaboration().CreateManagedToolApproval(ctx,
		store.ManagedToolApprovalRequest{Fence: secondEnvelope.fence(), Approval: &second}); !errors.Is(err, store.ErrConflict) {
		t.Fatalf("same tool-use fence with a second approval ID err=%v", err)
	}
	approvals, err := st.Collaboration().ListApprovals(ctx, store.ApprovalFilter{
		Tenant: task.Tenant, Namespace: task.Namespace, TargetType: controlmodel.ApprovalTargetExecutionAttempt,
		TargetRef: attempt.ID.String(), Limit: 10})
	if err != nil || len(approvals) != 1 || approvals[0].ID != approval.ID {
		t.Fatalf("tool-use fence was not unique: approvals=%+v err=%v", approvals, err)
	}
	var substituted managedToolApprovalEnvelope
	if err = json.Unmarshal(approval.Request, &substituted); err != nil {
		t.Fatal(err)
	}
	substituted.InputSHA256 = "different-input-hash"
	substitutedRequest, _ := json.Marshal(substituted)
	substitutedApproval := *approval
	substitutedApproval.Request = substitutedRequest
	if _, _, _, err = st.Collaboration().CreateManagedToolApproval(ctx,
		store.ManagedToolApprovalRequest{Fence: substituted.fence(), Approval: &substitutedApproval}); !errors.Is(err, store.ErrConflict) {
		t.Fatalf("same Approval ID substituted a different input hash: err=%v", err)
	}
}

func TestManagedToolApprovalTimeoutCancellationCallbackMatchesDataPlaneContract(t *testing.T) {
	ctx := context.Background()
	st, _, task, attempt, approval := createManagedApprovalFixture(t, ctx)
	sender := &recordingManagedConfirmationSender{}
	srv := NewServer(ServerOptions{Store: st, ManagedConfirmations: sender})
	cancelled, err := st.Collaboration().DecideApproval(ctx, approval.ID, approval.Version,
		controlmodel.ApprovalCancelled,
		controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "runtime-control-sweeper"},
		json.RawMessage(`{"reason":"approval_timeout"}`))
	if err != nil {
		t.Fatal(err)
	}
	if err = srv.DispatchApprovalDecision(ctx, cancelled.ID); err != nil {
		t.Fatal(err)
	}
	currentTask, _ := st.Collaboration().GetAgentTask(ctx, task.ID)
	currentAttempt, _ := st.ExecutionAttempts().Get(ctx, attempt.ID)
	if sender.calls != 1 || sender.decision.Status != string(controlmodel.ApprovalCancelled) ||
		sender.decision.Allow || sender.decision.DenyMessage != "approval_timeout" ||
		currentTask.Status != controlmodel.AgentTaskRunning ||
		currentAttempt.State != controlmodel.ExecutionRunning {
		t.Fatalf("timeout callback/state mismatch: sender=%+v task=%+v attempt=%+v",
			sender, currentTask, currentAttempt)
	}
}

func TestManagedToolApprovalHumanCannotCancel(t *testing.T) {
	ctx := context.Background()
	st, _, _, _, approval := createManagedApprovalFixture(t, ctx, "token:static")
	srv := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	body, _ := json.Marshal(map[string]any{
		"status": controlmodel.ApprovalCancelled, "expectedVersion": approval.Version,
	})
	req := httptest.NewRequest(http.MethodPost,
		"/api/v1/approvals/"+approval.ID.String()+"/decide", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer console")
	response := httptest.NewRecorder()
	srv.router.ServeHTTP(response, req)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("human managed cancellation status=%d body=%s", response.Code, response.Body.String())
	}
	current, err := st.Collaboration().GetApproval(ctx, approval.ID)
	if err != nil || current.Status != controlmodel.ApprovalPending {
		t.Fatalf("human cancellation mutated Approval: approval=%+v err=%v", current, err)
	}
}

func TestManagedToolApprovalDecisionRequiresVersionCAS(t *testing.T) {
	ctx := context.Background()
	st, _, _, _, approval := createManagedApprovalFixture(t, ctx, "token:static")
	srv := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	body, _ := json.Marshal(map[string]any{"status": controlmodel.ApprovalApproved})
	req := httptest.NewRequest(http.MethodPost,
		"/api/v1/approvals/"+approval.ID.String()+"/decide", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer console")
	response := httptest.NewRecorder()
	srv.router.ServeHTTP(response, req)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("missing decision CAS status=%d body=%s", response.Code, response.Body.String())
	}
	current, err := st.Collaboration().GetApproval(ctx, approval.ID)
	if err != nil || current.Status != controlmodel.ApprovalPending {
		t.Fatalf("missing CAS mutated Approval: approval=%+v err=%v", current, err)
	}
}

func TestManagedToolApprovalRejectNoteIsDeliveredToAgent(t *testing.T) {
	ctx := context.Background()
	st, _, _, _, approval := createManagedApprovalFixture(t, ctx)
	sender := &recordingManagedConfirmationSender{}
	srv := NewServer(ServerOptions{Store: st, ManagedConfirmations: sender})
	rejected, err := st.Collaboration().DecideApproval(ctx, approval.ID, approval.Version,
		controlmodel.ApprovalRejected, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		json.RawMessage(`{"note":"arguments are outside the approved scope"}`))
	if err != nil {
		t.Fatal(err)
	}
	if err = srv.DispatchApprovalDecision(ctx, rejected.ID); err != nil {
		t.Fatal(err)
	}
	if sender.calls != 1 || sender.decision.Allow ||
		sender.decision.DenyMessage != "arguments are outside the approved scope" {
		t.Fatalf("reject note was not delivered: sender=%+v", sender)
	}
}

func TestManagedToolApprovalWithoutHumanFailsPermanently(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agentID := uuid.New()
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "default", Title: "missing approver",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "automation"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: agentID.String(),
	})
	if err != nil {
		t.Fatal(err)
	}
	tasks, _ := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
	task, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version, SessionID: "managed-no-human"},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
			State: controlmodel.ExecutionAssigned, SessionID: "managed-no-human", TurnID: "turn-no-human"})
	if err != nil {
		t.Fatal(err)
	}
	task, err = st.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
	if err != nil {
		t.Fatal(err)
	}
	session, err := st.Sessions().Upsert(ctx, &store.Session{Tenant: task.Tenant,
		Namespace: task.Namespace, SessionID: attempt.SessionID, AgentID: agentID,
		AgentName: "managed", Framework: string(controlmodel.DataPlaneManaged),
		Phase: store.SessionPhaseActive, AgentTaskID: &task.ID})
	if err != nil {
		t.Fatal(err)
	}
	approvalID := store.ManagedToolApprovalID(task.Tenant, session.SessionID, attempt.ID.String(),
		attempt.DispatchGeneration, attempt.TurnID, "call-no-human")
	body, _ := json.Marshal(managedSessionEventReport{ID: "requires-no-human", SessionID: session.SessionID,
		Seq: 1, Type: "session.requires_action", AgentTaskID: task.ID.String(), AttemptID: attempt.ID.String(),
		DispatchGen: attempt.DispatchGeneration, TurnID: attempt.TurnID,
		CreatedAt: time.Now().UnixMilli(), Payload: map[string]any{
			"kind": "tool_confirmation", "schemaVersion": 1, "approvalId": approvalID.String(),
			"toolUseId": "call-no-human", "toolName": "dangerous_tool",
			"requestedAt": time.Now().UnixMilli(), "expiresAt": time.Now().Add(time.Hour).UnixMilli(),
		}})
	req := httptest.NewRequest(http.MethodPost,
		"/api/internal/runtime-sessions/"+session.SessionID+"/events", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Builder-Internal-Token", "internal-secret")
	response := httptest.NewRecorder()
	NewServer(ServerOptions{Store: st, InternalToken: "internal-secret"}).router.ServeHTTP(response, req)
	if response.Code != http.StatusNoContent {
		t.Fatalf("missing approver report status=%d body=%s", response.Code, response.Body.String())
	}
	failedTask, _ := st.Collaboration().GetAgentTask(ctx, task.ID)
	failedAttempt, _ := st.ExecutionAttempts().Get(ctx, attempt.ID)
	if failedTask.Status != controlmodel.AgentTaskFailed || failedTask.ErrorCode != "hitl_approver_unavailable" ||
		failedAttempt.State != controlmodel.ExecutionFailed || failedAttempt.FailureCode != "hitl_approver_unavailable" {
		t.Fatalf("missing approver did not fail deterministically: task=%+v attempt=%+v", failedTask, failedAttempt)
	}
	approvals, _ := st.Collaboration().ListApprovals(ctx, store.ApprovalFilter{
		Tenant: task.Tenant, Namespace: task.Namespace, Limit: 10})
	if len(approvals) != 0 {
		t.Fatalf("missing approver created an unclaimable Approval: %+v", approvals)
	}
	outbox, err := st.Outbox().Claim(ctx, "test", time.Now().UTC().Add(time.Second), time.Minute, 50)
	if err != nil {
		t.Fatal(err)
	}
	abortQueued := false
	for _, event := range outbox {
		if event.EventType == "execution-attempt.abort-managed.v1" && event.AggregateID == attempt.ID.String() {
			abortQueued = true
		}
	}
	if !abortQueued {
		t.Fatalf("missing approver did not durably queue managed session abort: %+v", outbox)
	}
}

func TestManagedAttemptAbortReplayCannotTargetFreshAttempt(t *testing.T) {
	ctx := context.Background()
	st, session, task, attemptA, _ := createManagedApprovalFixture(t, ctx)
	queued, _, err := st.Collaboration().RequeueAgentTaskAfterAttemptFailure(ctx, task.ID,
		store.TaskFailure{ExpectedVersion: task.Version, AttemptID: attemptA.ID,
			DispatchGeneration: attemptA.DispatchGeneration, Code: "heartbeat_timeout", Message: "lost waiter"})
	if err != nil {
		t.Fatal(err)
	}
	_, attemptB, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: queued.ID, ExpectedVersion: queued.Version, SessionID: session.SessionID},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
			State: controlmodel.ExecutionAssigned, SessionID: session.SessionID, TurnID: "turn-b",
			ManagedOwnerRef: "owner"})
	if err != nil {
		t.Fatal(err)
	}
	sender := &recordingManagedAbortSender{err: &product.ManagedToolConfirmationDeliveryError{
		StatusCode: http.StatusConflict, Message: "current scope is Attempt B",
	}}
	srv := NewServer(ServerOptions{Store: st, ManagedAttemptAborts: sender})
	if err = srv.DispatchManagedAttemptAbort(ctx, attemptA.ID); err != nil {
		t.Fatal(err)
	}
	if sender.calls != 1 || sender.sessionID != session.SessionID ||
		sender.abort.AgentTaskID != task.ID || sender.abort.AttemptID != attemptA.ID ||
		sender.abort.DispatchGeneration != attemptA.DispatchGeneration || sender.abort.TurnID != attemptA.TurnID {
		t.Fatalf("abort did not preserve old Attempt fence: sender=%+v", sender)
	}
	if sender.abort.AttemptID == attemptB.ID || sender.abort.TurnID == attemptB.TurnID {
		t.Fatalf("old abort was rebound to fresh Attempt B: abort=%+v B=%+v", sender.abort, attemptB)
	}
}

func createManagedApprovalFixture(t *testing.T, ctx context.Context, approvers ...string) (store.Store, *store.Session,
	*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, *controlmodel.Approval) {
	t.Helper()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	approver := "owner"
	if len(approvers) > 0 && strings.TrimSpace(approvers[0]) != "" {
		approver = strings.TrimSpace(approvers[0])
	}
	agentID := uuid.New()
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "tenant-a", Namespace: "default",
		Title: "managed approval fixture", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: agentID.String()})
	if err != nil {
		t.Fatal(err)
	}
	tasks, _ := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
	task, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version, SessionID: "managed-stale"},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
			State: controlmodel.ExecutionAssigned, SessionID: "managed-stale", TurnID: "turn-stale",
			ManagedOwnerRef: "owner"})
	if err != nil {
		t.Fatal(err)
	}
	approvalID := store.ManagedToolApprovalID(task.Tenant, attempt.SessionID, attempt.ID.String(),
		attempt.DispatchGeneration, attempt.TurnID, "call-stale")
	task, err = st.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
	if err != nil {
		t.Fatal(err)
	}
	session, err := st.Sessions().Upsert(ctx, &store.Session{Tenant: task.Tenant,
		Namespace: task.Namespace, SessionID: attempt.SessionID, AgentID: agentID,
		AgentName: "managed", Framework: string(controlmodel.DataPlaneManaged),
		Phase: store.SessionPhaseActive, AgentTaskID: &task.ID})
	if err != nil {
		t.Fatal(err)
	}
	envelope := managedToolApprovalEnvelope{Kind: controlmodel.ApprovalRequestKindManagedToolConfirmation,
		SchemaVersion: 1, SourceEventID: "requires-stale", Tenant: task.Tenant,
		Namespace: task.Namespace, SessionID: session.SessionID, SessionRef: &session.ID,
		ApprovalID: approvalID, AgentTaskID: task.ID, AttemptID: attempt.ID,
		DispatchGeneration: attempt.DispatchGeneration, TurnID: attempt.TurnID,
		ToolUseID: "call-stale", ToolName: "dangerous_tool",
		RequestedAt: time.Now().UTC(), ExpiresAt: time.Now().UTC().Add(time.Hour)}
	request, _ := json.Marshal(envelope)
	approval := &controlmodel.Approval{ID: approvalID, Tenant: task.Tenant, Namespace: task.Namespace,
		TargetType: controlmodel.ApprovalTargetExecutionAttempt, TargetRef: attempt.ID.String(),
		IssueID: &task.IssueID, RunID: &task.OrchestrationRunID, RunNodeID: &task.RunNodeID,
		RequestedBy: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef},
		ApproverRef: approver, Status: controlmodel.ApprovalPending, Request: request}
	approval, task, attempt, err = st.Collaboration().CreateManagedToolApproval(ctx,
		store.ManagedToolApprovalRequest{Fence: envelope.fence(), Approval: approval})
	if err != nil {
		t.Fatal(err)
	}
	return st, session, task, attempt, approval
}

func TestManagedToolApprovalIDMatchesDataPlaneUUIDv5Contract(t *testing.T) {
	got := store.ManagedToolApprovalID("tenant-a", "session-a", "attempt-a", 3, "turn-a", "tool-a")
	if got.String() != "3bd34c03-5a5a-5dbf-a015-e66363eaa457" {
		t.Fatalf("managed approval UUID mismatch: %s", got)
	}
}

func TestHostedRuntimeApprovalRequestDecisionAndAck(t *testing.T) {
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
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneHostedRuntime,
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
		envelope.BackendKind != controlmodel.DataPlaneHostedRuntime {
		t.Fatalf("envelope=%+v", envelope)
	}
	decided, err := st.Collaboration().DecideApproval(ctx, created.Approval.ID, created.Approval.Version,
		controlmodel.ApprovalApproved, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, nil)
	if err != nil {
		t.Fatal(err)
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
}

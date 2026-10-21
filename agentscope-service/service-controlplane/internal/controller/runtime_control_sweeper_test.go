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

package controller

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store/memory"
)

func TestRuntimeControlSweeperFencesLostAttemptAndRequeuesTask(t *testing.T) {
	ctx := context.Background()
	st, err := memory.Open(ctx, store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	_, _ = st.RuntimeRegistry().UpsertRuntimePool(ctx, &controlmodel.RuntimePool{Tenant: "t", Namespace: "n", Name: "p"})
	host, _ := st.RuntimeRegistry().UpsertRuntimeHost(ctx, &controlmodel.RuntimeHost{
		Tenant: "t", Namespace: "n", HostKey: "h", PoolName: "p", State: controlmodel.RuntimeHostOnline, Capacity: 1,
	})
	issue, _ := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "t", Namespace: "n",
		Title: "work", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}})
	_, task, _ := st.Collaboration().AssignIssue(ctx, issue.ID, issue.Version,
		controlmodel.AssigneeAgent, "a", issue.Creator)
	task, execution, _ := st.Collaboration().ClaimAgentTaskWithAttempt(ctx, store.TaskClaim{TaskID: task.ID,
		ExpectedVersion: task.Version}, &controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneHostedRuntime,
		RuntimePoolName: "p", State: controlmodel.ExecutionQueued})
	execution, _ = st.ExecutionAttempts().Claim(ctx, store.ExecutionClaim{
		Tenant: "t", Namespace: "n", RuntimePoolName: "p", HostID: host.ID,
		HostGeneration: host.LeaseGeneration, LeaseOwner: "h/c", LeaseToken: "l", LeaseTTL: time.Minute,
	})
	execution, _ = st.ExecutionAttempts().MarkPreparing(ctx, execution.ID, "l", execution.FencingToken)
	execution, _ = st.ExecutionAttempts().MarkRunning(ctx, execution.ID, "l", execution.FencingToken, "", "")

	sweeper := &RuntimeControlSweeper{Store: st, RuntimeTimeout: time.Minute}
	sweeper.Sweep(ctx, time.Now().UTC().Add(time.Hour))
	execution, _ = st.ExecutionAttempts().Get(ctx, execution.ID)
	if execution.State != controlmodel.ExecutionFailed || execution.FailureCode != "heartbeat_timeout" {
		t.Fatalf("execution=%+v", execution)
	}
	task, _ = st.Collaboration().GetAgentTask(ctx, task.ID)
	if task.Status != controlmodel.AgentTaskQueued || task.CurrentAttemptID == nil || *task.CurrentAttemptID != execution.ID {
		t.Fatalf("task status=%s", task.Status)
	}
}

func TestRuntimeControlSweeperProjectsTerminalRunToEndpointJob(t *testing.T) {
	for _, cancelled := range []bool{false, true} {
		t.Run(fmt.Sprint("cancelled=", cancelled), func(t *testing.T) {
			ctx := context.Background()
			st, err := memory.Open(ctx, store.Config{})
			if err != nil {
				t.Fatal(err)
			}
			defer st.Close()
			actor := controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "endpoint:test"}
			issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
				Tenant: "t", Namespace: "n", Title: "job", Creator: actor, Status: controlmodel.IssueInProgress,
				Kind: controlmodel.IssueKindEndpointJob, CompletionPolicy: controlmodel.IssueCompletionAutomatic,
			})
			if err != nil {
				t.Fatal(err)
			}
			run, err := st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{
				Tenant: "t", Namespace: "n", RootIssueID: issue.ID, State: controlmodel.RunRunning, CreatedBy: actor,
			})
			if err != nil {
				t.Fatal(err)
			}
			targetRun, targetInvocation, targetIssue := controlmodel.RunFailed, controlmodel.EndpointInvocationFailed, controlmodel.IssueBlocked
			if cancelled {
				run, err = st.Orchestration().TransitionRun(ctx, run.ID, run.Version, controlmodel.RunCancelling, nil, "", "")
				if err != nil {
					t.Fatal(err)
				}
				targetRun, targetInvocation, targetIssue = controlmodel.RunCancelled, controlmodel.EndpointInvocationCancelled, controlmodel.IssueCancelled
			}
			run, err = st.Orchestration().TransitionRun(ctx, run.ID, run.Version, targetRun,
				nil, "team_coordinator_failed", "leader did not converge")
			if err != nil {
				t.Fatal(err)
			}
			endpoint, err := st.Endpoints().Create(ctx, &controlmodel.Endpoint{
				Tenant: "t", Namespace: "n", Name: "team", Slug: "team", TargetType: controlmodel.EndpointTargetTeam,
				TargetRef: uuid.New(), InvocationMode: controlmodel.EndpointJobMode,
			})
			if err != nil {
				t.Fatal(err)
			}
			invocation, _, err := st.Endpoints().ReserveInvocation(ctx, &controlmodel.EndpointInvocation{
				EndpointID: endpoint.ID, Mode: controlmodel.EndpointJobMode, PrincipalRef: "caller",
				IdempotencyKey: "job-1", Status: controlmodel.EndpointInvocationRunning, RunID: &run.ID, IssueID: &issue.ID,
			})
			if err != nil {
				t.Fatal(err)
			}
			(&RuntimeControlSweeper{Store: st}).Sweep(ctx, time.Now().UTC())
			invocation, err = st.Endpoints().GetInvocation(ctx, invocation.ID)
			if err != nil || invocation.Status != targetInvocation ||
				(!cancelled && invocation.ErrorCode != "team_coordinator_failed") || invocation.CompletedAt == nil {
				t.Fatalf("terminal Run was not projected: invocation=%+v err=%v", invocation, err)
			}
			var result map[string]any
			if json.Unmarshal(invocation.Result, &result) != nil || result["runState"] != string(targetRun) {
				t.Fatalf("unexpected invocation result: %s", invocation.Result)
			}
			issue, err = st.Collaboration().GetIssue(ctx, issue.ID)
			if err != nil || issue.Status != targetIssue {
				t.Fatalf("terminal Endpoint Job issue was not closed: issue=%+v err=%v", issue, err)
			}

		})
	}
}

func TestRuntimeControlSweeperSystemCancelsExpiredManagedToolApproval(t *testing.T) {
	ctx := context.Background()
	st, err := memory.Open(ctx, store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agentID := uuid.New()
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "t", Namespace: "n",
		Title: "approval timeout", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: agentID.String()})
	if err != nil {
		t.Fatal(err)
	}
	tasks, _ := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
	task, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx, store.TaskClaim{
		TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version, SessionID: "managed-timeout"},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
			State: controlmodel.ExecutionAssigned, SessionID: "managed-timeout", TurnID: "turn-timeout"})
	if err != nil {
		t.Fatal(err)
	}
	approvalID := store.ManagedToolApprovalID(task.Tenant, "managed-timeout", attempt.ID.String(),
		attempt.DispatchGeneration, attempt.TurnID, "call-timeout")
	task, err = st.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
	if err != nil {
		t.Fatal(err)
	}
	session, err := st.Sessions().Upsert(ctx, &store.Session{Tenant: task.Tenant, Namespace: task.Namespace,
		SessionID: "managed-timeout", AgentID: agentID, AgentName: "managed", Phase: store.SessionPhaseActive,
		Framework: string(controlmodel.DataPlaneManaged), AgentTaskID: &task.ID})
	if err != nil {
		t.Fatal(err)
	}
	fence := store.ManagedToolApprovalFence{SessionID: session.SessionID, TaskID: task.ID,
		AttemptID: attempt.ID, ApprovalID: approvalID, DispatchGeneration: attempt.DispatchGeneration,
		TurnID: attempt.TurnID, ToolUseID: "call-timeout", ToolName: "dangerous_tool"}
	expiresAt := time.Now().UTC().Add(-time.Minute)
	request, _ := json.Marshal(managedToolApprovalRequest{Kind: controlmodel.ApprovalRequestKindManagedToolConfirmation,
		SchemaVersion: 1, Tenant: task.Tenant, Namespace: task.Namespace, SessionID: session.SessionID,
		SessionRef: &session.ID, ApprovalID: approvalID,
		AgentTaskID: task.ID, AttemptID: attempt.ID, DispatchGeneration: attempt.DispatchGeneration,
		TurnID: attempt.TurnID, ToolUseID: fence.ToolUseID, ToolName: fence.ToolName, ExpiresAt: expiresAt})
	approval, _, _, err := st.Collaboration().CreateManagedToolApproval(ctx, store.ManagedToolApprovalRequest{
		Fence: fence, Approval: &controlmodel.Approval{ID: approvalID, Tenant: task.Tenant,
			Namespace: task.Namespace, TargetType: controlmodel.ApprovalTargetExecutionAttempt,
			TargetRef: attempt.ID.String(), IssueID: &task.IssueID, RunID: &task.OrchestrationRunID,
			RunNodeID: &task.RunNodeID, RequestedBy: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef},
			ApproverRef: "owner", Status: controlmodel.ApprovalPending, Request: request}})
	if err != nil {
		t.Fatal(err)
	}
	// Keep a newer, non-managed pending Approval at the head of the newest-first
	// listing. Batch=1 must still reach the older expired managed Approval.
	time.Sleep(time.Millisecond)
	newer, err := st.Collaboration().CreateApproval(ctx, &controlmodel.Approval{ID: uuid.New(),
		Tenant: task.Tenant, Namespace: task.Namespace, TargetType: controlmodel.ApprovalTargetExecutionAttempt,
		TargetRef: uuid.New().String(), RequestedBy: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		ApproverRef: "owner", Status: controlmodel.ApprovalPending, Request: json.RawMessage(`{"kind":"ordinary"}`)})
	if err != nil {
		t.Fatal(err)
	}
	(&RuntimeControlSweeper{Store: st, HeartbeatTimeout: time.Hour, RuntimeTimeout: time.Hour, Batch: 1}).Sweep(ctx, time.Now().UTC())
	approval, err = st.Collaboration().GetApproval(ctx, approval.ID)
	if err != nil || approval.Status != controlmodel.ApprovalCancelled || approval.DecidedBy == nil ||
		approval.DecidedBy.Type != controlmodel.ActorSystem {
		t.Fatalf("expired approval=%+v err=%v", approval, err)
	}
	newer, err = st.Collaboration().GetApproval(ctx, newer.ID)
	if err != nil || newer.Status != controlmodel.ApprovalPending {
		t.Fatalf("unrelated Approval was mutated: approval=%+v err=%v", newer, err)
	}
	inbox, err := st.Collaboration().ListInbox(ctx, store.InboxFilter{Tenant: "t", Namespace: "n",
		RecipientRef: "owner", Archived: false, Limit: 10})
	if err != nil {
		t.Fatal(err)
	}
	for _, item := range inbox {
		if item.ApprovalID != nil && *item.ApprovalID == approval.ID {
			t.Fatalf("expired approval inbox remained actionable: %+v", item)
		}
	}
}

func TestRuntimeControlSweeperPortalApprovalCursorSurvivesReplacement(t *testing.T) {
	ctx := t.Context()
	st, err := memory.Open(ctx, store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	sessionID := uuid.New()
	contract, _ := json.Marshal(map[string]string{"public_session_id": sessionID.String(), "portal_command_id": "scan-fixture"})
	invocation, _, err := st.Endpoints().ReserveInvocation(ctx, &controlmodel.EndpointInvocation{EndpointID: sessionID, Mode: controlmodel.EndpointJobMode, Contract: contract})
	if err != nil {
		t.Fatal(err)
	}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "t", Namespace: "n", Title: "Portal", Creator: actor, SourceType: "endpoint", SourceRef: invocation.ID.String()})
	if err != nil {
		t.Fatal(err)
	}
	run, err := st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{Tenant: "t", Namespace: "n", RootIssueID: issue.ID, State: controlmodel.RunPlanned, CreatedBy: actor})
	if err != nil {
		t.Fatal(err)
	}
	staleRequest, _ := json.Marshal(managedToolApprovalRequest{Kind: controlmodel.ApprovalRequestKindManagedToolConfirmation, SchemaVersion: 1})
	ordinary, err := st.Collaboration().CreateApproval(ctx, &controlmodel.Approval{ID: uuid.MustParse("00000000-0000-0000-0000-000000000001"), Tenant: "t", Namespace: "n", TargetType: controlmodel.ApprovalTargetExecutionAttempt, TargetRef: uuid.NewString(), RunID: &run.ID, RequestedBy: actor, ApproverRef: "owner", Request: json.RawMessage(`{"kind":"ordinary"}`)})
	if err != nil {
		t.Fatal(err)
	}
	stale, err := st.Collaboration().CreateApproval(ctx, &controlmodel.Approval{ID: uuid.MustParse("00000000-0000-0000-0000-000000000099"), Tenant: "t", Namespace: "n", TargetType: controlmodel.ApprovalTargetExecutionAttempt, TargetRef: uuid.NewString(), RunID: &run.ID, RequestedBy: actor, ApproverRef: "owner", Request: staleRequest})
	if err != nil {
		t.Fatal(err)
	}
	legacy, err := st.Collaboration().CreateApproval(ctx, &controlmodel.Approval{ID: uuid.MustParse("00000000-0000-0000-0000-000000000002"), Tenant: "t", Namespace: "n", TargetType: controlmodel.ApprovalTargetExecutionAttempt, TargetRef: uuid.NewString(), RequestedBy: actor, ApproverRef: "owner", Request: staleRequest})
	if err != nil {
		t.Fatal(err)
	}
	(&RuntimeControlSweeper{Store: st, PortalOnly: true, Batch: 1}).Sweep(ctx, time.Now().UTC())
	current, err := st.Collaboration().GetApproval(ctx, stale.ID)
	if err != nil || current.Status != controlmodel.ApprovalPending {
		t.Fatalf("scan exceeded finite page: %+v %v", current, err)
	}
	(&RuntimeControlSweeper{Store: st, PortalOnly: true, Batch: 1}).Sweep(ctx, time.Now().UTC())
	current, err = st.Collaboration().GetApproval(ctx, stale.ID)
	if err != nil || current.Status != controlmodel.ApprovalCancelled {
		t.Fatalf("replacement did not continue shared cursor: %+v %v", current, err)
	}
	for _, id := range []uuid.UUID{ordinary.ID, legacy.ID} {
		current, err = st.Collaboration().GetApproval(ctx, id)
		if err != nil || current.Status != controlmodel.ApprovalPending {
			t.Fatalf("unrelated approval mutated: %+v %v", current, err)
		}
	}
}

func TestRuntimeControlSweeperPortalRotationReachesExpiredAttempt(t *testing.T) {
	ctx := t.Context()
	st, err := memory.Open(ctx, store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	sessionID := uuid.New()
	contract, _ := json.Marshal(map[string]string{"public_session_id": sessionID.String(), "portal_command_id": "rotation-fixture"})
	invocation, _, err := st.Endpoints().ReserveInvocation(ctx, &controlmodel.EndpointInvocation{EndpointID: sessionID, Mode: controlmodel.EndpointJobMode, Contract: contract})
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	future, past := now.Add(time.Hour), now.Add(-time.Minute)
	ids := []uuid.UUID{uuid.MustParse("00000000-0000-0000-0000-000000000001"), uuid.MustParse("00000000-0000-0000-0000-000000000002"), uuid.MustParse("00000000-0000-0000-0000-000000000003"), uuid.MustParse("00000000-0000-0000-0000-000000000099")}
	for i, id := range ids {
		source, ref := "endpoint", invocation.ID.String()
		if i == 2 {
			source, ref = "", ""
		}
		issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "t", Namespace: "n", Title: "rotation", Creator: actor, SourceType: source, SourceRef: ref, AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "a"})
		if err != nil {
			t.Fatal(err)
		}
		tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
		if err != nil || len(tasks) != 1 {
			t.Fatal(tasks, err)
		}
		expiry := future
		if i >= 2 {
			expiry = past
		}
		_, _, err = st.Collaboration().ClaimAgentTaskWithAttempt(ctx, store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version, RuntimeBinding: json.RawMessage(`{}`)}, &controlmodel.ExecutionAttempt{ID: id, BackendKind: controlmodel.DataPlaneExternalApplication, State: controlmodel.ExecutionAssigned, RuntimeBinding: json.RawMessage(`{}`), LeaseExpiresAt: &expiry})
		if err != nil {
			t.Fatal(err)
		}
	}
	for round := 0; round < 3; round++ {
		(&RuntimeControlSweeper{Store: st, PortalOnly: true, Batch: 1}).Sweep(ctx, now)
		late, err := st.ExecutionAttempts().Get(ctx, ids[3])
		if err != nil {
			t.Fatal(err)
		}
		if round < 2 && late.State != controlmodel.ExecutionAssigned {
			t.Fatalf("scan exceeded finite page at round %d: %+v", round, late)
		}
		if round == 2 && (late.State != controlmodel.ExecutionFailed || late.FailureCode != "startup_timeout") {
			t.Fatalf("live first page starved expired attempt after replacement: %+v", late)
		}
	}
	for _, id := range ids[:3] {
		unchanged, err := st.ExecutionAttempts().Get(ctx, id)
		if err != nil || unchanged.State != controlmodel.ExecutionAssigned {
			t.Fatalf("hot or legacy attempt mutated: %+v %v", unchanged, err)
		}
	}
}

func TestManagedAttemptHasNoControlPlaneLivenessTimeout(t *testing.T) {
	ctx := t.Context()
	st, err := memory.Open(ctx, store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "native", Namespace: "n",
		Title: "long native Turn", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "human"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "a"})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
	if err != nil || len(tasks) != 1 {
		t.Fatal(tasks, err)
	}
	task, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version, SessionID: uuid.NewString()},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged, State: controlmodel.ExecutionAssigned})
	if err != nil {
		t.Fatal(err)
	}
	observed := []uuid.UUID{}
	w := &RuntimeControlSweeper{Store: st, ReconcileManagedAttempt: func(_ context.Context, id uuid.UUID) error {
		observed = append(observed, id)
		return nil
	}}
	w.Sweep(ctx, time.Now().Add(24*time.Hour))
	current, err := st.ExecutionAttempts().Get(ctx, attempt.ID)
	if err != nil || current.State != controlmodel.ExecutionAssigned || current.Version != attempt.Version {
		t.Fatalf("native Attempt was timed out: %+v %v", current, err)
	}
	currentTask, err := st.Collaboration().GetAgentTask(ctx, task.ID)
	if err != nil || currentTask.CurrentAttemptID == nil || *currentTask.CurrentAttemptID != attempt.ID || currentTask.Status != controlmodel.AgentTaskDispatched {
		t.Fatalf("native Task was requeued: %+v %v", currentTask, err)
	}
	cancelling, err := st.ExecutionAttempts().Cancel(ctx, current.ID, current.Version)
	if err != nil {
		t.Fatal(err)
	}
	w.Sweep(ctx, time.Now().Add(48*time.Hour))
	current, err = st.ExecutionAttempts().Get(ctx, attempt.ID)
	if err != nil || current.State != controlmodel.ExecutionCancelRequested || current.Version != cancelling.Version {
		t.Fatalf("cancellation was fabricated by grace timeout: %+v %v", current, err)
	}
	if len(observed) != 2 || observed[0] != attempt.ID || observed[1] != attempt.ID {
		t.Fatalf("native reconciliation targets: %v", observed)
	}
}

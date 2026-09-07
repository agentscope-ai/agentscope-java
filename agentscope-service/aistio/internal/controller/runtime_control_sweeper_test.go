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

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/store/memory"
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

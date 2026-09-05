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
	run, err = st.Orchestration().TransitionRun(ctx, run.ID, run.Version, controlmodel.RunFailed,
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
	if err != nil || invocation.Status != controlmodel.EndpointInvocationFailed ||
		invocation.ErrorCode != "team_coordinator_failed" || invocation.CompletedAt == nil {
		t.Fatalf("terminal Run was not projected: invocation=%+v err=%v", invocation, err)
	}
	var result map[string]any
	if json.Unmarshal(invocation.Result, &result) != nil || result["runState"] != string(controlmodel.RunFailed) {
		t.Fatalf("unexpected invocation result: %s", invocation.Result)
	}
	issue, err = st.Collaboration().GetIssue(ctx, issue.ID)
	if err != nil || issue.Status != controlmodel.IssueCancelled {
		t.Fatalf("terminal Endpoint Job issue was not closed: issue=%+v err=%v", issue, err)
	}
}

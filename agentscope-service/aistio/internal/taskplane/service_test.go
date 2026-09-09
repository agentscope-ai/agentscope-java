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

package taskplane

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestHostedAgentTaskLifecycleWritesResultComment(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.DefaultConfig())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	pool, _ := st.RuntimeRegistry().UpsertRuntimePool(ctx, &controlmodel.RuntimePool{Tenant: "tenant", Namespace: "default", Name: "coding", HostSelector: json.RawMessage(`{"region":"cn"}`)})
	profile, _ := st.RuntimeRegistry().UpsertRuntimeProfile(ctx, &controlmodel.RuntimeProfile{Tenant: "tenant", Namespace: "default", Name: "codex", Provider: "codex", Requirements: json.RawMessage(`{"sandbox":{"network":false}}`)})
	host, _ := st.RuntimeRegistry().UpsertRuntimeHost(ctx, &controlmodel.RuntimeHost{Tenant: "tenant", Namespace: "default", HostKey: "host", PoolName: "coding", State: controlmodel.RuntimeHostOnline, Capacity: 1, Labels: json.RawMessage(`{"region":"cn"}`), Capabilities: json.RawMessage(`{"providers":{"codex":"test"},"sandbox":{"network":false}}`)})
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "tenant", Namespace: "default", Title: "implement change", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "ken"}})
	if err != nil {
		t.Fatal(err)
	}
	agentID, bindingID := uuid.New(), uuid.New()
	issue, task, err := st.Collaboration().AssignIssue(ctx, issue.ID, issue.Version, controlmodel.AssigneeAgent, agentID.String(), issue.Creator)
	if err != nil || issue.AssigneeRef != agentID.String() {
		t.Fatalf("assign: issue=%+v task=%+v err=%v", issue, task, err)
	}
	definition := json.RawMessage(`{"version":1,"definitionDigest":"original","files":{"AGENTS.md":"original"}}`)
	svc := &Service{Store: st, ResolveDefinition: func(context.Context, uuid.UUID) (json.RawMessage, error) { return definition, nil }}
	overrides := &controlmodel.HostedExecutionOverrides{ReasoningEffort: "high",
		ProviderConfiguration: json.RawMessage(`{"sandbox":"workspace-write"}`), CustomArgs: []string{"--profile", "work"}}
	dispatched, execution, err := svc.DispatchHosted(ctx, task.ID, controlmodel.RuntimeBinding{AgentID: agentID, BindingID: bindingID, Kind: controlmodel.DataPlaneHostedRuntime, RuntimeProfileID: profile.ID, RuntimePoolID: pool.ID, ExecutionOverrides: overrides}, nil)
	if err != nil || dispatched.Status != controlmodel.AgentTaskDispatched {
		t.Fatalf("dispatch: task=%+v execution=%+v err=%v", dispatched, execution, err)
	}
	var snapshot controlmodel.RuntimeDispatchSnapshot
	if err = json.Unmarshal(execution.RuntimeBinding, &snapshot); err != nil || snapshot.RuntimeProfile == nil || snapshot.RuntimePool == nil || snapshot.RuntimeProfile.Version != profile.Version || snapshot.RuntimePool.Version != pool.Version {
		t.Fatalf("hosted dispatch did not freeze runtime resources: snapshot=%+v err=%v", snapshot, err)
	}
	var resolved map[string]any
	if err = json.Unmarshal(snapshot.ResolvedProviderConfiguration, &resolved); err != nil ||
		resolved["sandbox"] != "workspace-write" || resolved["reasoningEffort"] != "high" ||
		snapshot.ExecutionOverrides == nil || len(snapshot.ExecutionOverrides.CustomArgs) != 2 {
		t.Fatalf("hosted overrides were not frozen: snapshot=%+v resolved=%v err=%v", snapshot, resolved, err)
	}
	definition = json.RawMessage(`{"version":2,"definitionDigest":"changed"}`)
	if string(snapshot.Definition) != `{"version":1,"definitionDigest":"original","files":{"AGENTS.md":"original"}}` {
		t.Fatalf("definition was not frozen: %s", snapshot.Definition)
	}
	profile.Configuration = json.RawMessage(`{"model":"changed-after-dispatch"}`)
	if _, err = st.RuntimeRegistry().UpsertRuntimeProfile(ctx, profile); err != nil {
		t.Fatal(err)
	}
	claimed, err := svc.Claim(ctx, store.ExecutionClaim{Tenant: "tenant", Namespace: "default", RuntimePoolName: "coding", HostID: host.ID, HostGeneration: host.LeaseGeneration, LeaseOwner: "host/1", LeaseToken: "lease", LeaseTTL: time.Minute})
	if err != nil {
		t.Fatal(err)
	}
	preparing, _ := svc.MarkPreparing(ctx, claimed.ID, claimed.LeaseToken, claimed.FencingToken)
	running, _ := svc.MarkRunning(ctx, preparing.ID, preparing.LeaseToken, preparing.FencingToken, "provider-session", "workspace")
	result := json.RawMessage(`{"output":"done"}`)
	if _, err := svc.Complete(ctx, running.ID, running.LeaseToken, running.FencingToken, result, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.Complete(ctx, running.ID, running.LeaseToken, running.FencingToken, result, nil); err != nil {
		t.Fatalf("terminal delivery must be idempotent: %v", err)
	}
	final, _ := st.Collaboration().GetAgentTask(ctx, task.ID)
	if final.Status != controlmodel.AgentTaskCompleted {
		t.Fatalf("task status=%s", final.Status)
	}
	comments, _ := st.Collaboration().ListComments(ctx, issue.ID, store.CommentListOptions{})
	if len(comments) != 1 || comments[0].Type != controlmodel.CommentResult || comments[0].SourceTaskID == nil || *comments[0].SourceTaskID != task.ID {
		t.Fatalf("result comments=%+v", comments)
	}
}

func TestRetryCreatesNewAgentTaskAndAttempt(t *testing.T) {
	ctx := context.Background()
	st, _ := store.Open(ctx, store.DefaultConfig())
	defer st.Close()
	pool, _ := st.RuntimeRegistry().UpsertRuntimePool(ctx, &controlmodel.RuntimePool{Tenant: "tenant", Namespace: "default", Name: "coding"})
	profile, _ := st.RuntimeRegistry().UpsertRuntimeProfile(ctx, &controlmodel.RuntimeProfile{Tenant: "tenant", Namespace: "default", Name: "codex", Provider: "codex"})
	issue, _ := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "tenant", Namespace: "default", Title: "retry", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "ken"}})
	agentID, bindingID := uuid.New(), uuid.New()
	_, task, _ := st.Collaboration().AssignIssue(ctx, issue.ID, issue.Version, controlmodel.AssigneeAgent, agentID.String(), issue.Creator)
	svc := &Service{Store: st}
	_, first, _ := svc.DispatchHosted(ctx, task.ID, controlmodel.RuntimeBinding{AgentID: agentID, BindingID: bindingID, Kind: controlmodel.DataPlaneHostedRuntime, RuntimeProfileID: profile.ID, RuntimePoolID: pool.ID}, nil)
	failed, _ := st.Collaboration().FailAgentTask(ctx, task.ID, task.Version+1, "provider", "failed")
	retry, second, err := svc.RetryTask(ctx, failed.ID)
	if err != nil || retry.ID == failed.ID || retry.RetryOfTaskID == nil || second == nil || second.AgentTaskID != retry.ID || first.ID == second.ID {
		t.Fatalf("retry=%+v execution=%+v err=%v", retry, second, err)
	}
}

func TestConversationRetryUsesLatestSuccessfulWorkspaceCheckpoint(t *testing.T) {
	ctx := context.Background()
	st, _ := store.Open(ctx, store.DefaultConfig())
	defer st.Close()
	pool, _ := st.RuntimeRegistry().UpsertRuntimePool(ctx, &controlmodel.RuntimePool{
		Tenant: "tenant", Namespace: "default", Name: "coding",
	})
	profile, _ := st.RuntimeRegistry().UpsertRuntimeProfile(ctx, &controlmodel.RuntimeProfile{
		Tenant: "tenant", Namespace: "default", Name: "qoder", Provider: "qoder",
	})
	host, _ := st.RuntimeRegistry().UpsertRuntimeHost(ctx, &controlmodel.RuntimeHost{
		Tenant: "tenant", Namespace: "default", HostKey: "host", PoolName: pool.Name,
		State: controlmodel.RuntimeHostOnline, Capacity: 1,
		Capabilities: json.RawMessage(`{"providers":{"qoder":"test"}}`),
	})
	agentID, bindingID := uuid.New(), uuid.New()
	binding := controlmodel.RuntimeBinding{AgentID: agentID, BindingID: bindingID,
		Kind: controlmodel.DataPlaneHostedRuntime, RuntimeProfileID: profile.ID, RuntimePoolID: pool.ID}
	svc := &Service{Store: st}
	dispatchTurn := func(title, turnID, providerSessionID, workspaceKey string) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt) {
		issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
			Tenant: "tenant", Namespace: "default", Title: title,
			Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "ken"},
		})
		if err != nil {
			t.Fatal(err)
		}
		_, task, err := st.Collaboration().AssignIssue(ctx, issue.ID, issue.Version,
			controlmodel.AssigneeAgent, agentID.String(), issue.Creator)
		if err != nil {
			t.Fatal(err)
		}
		dispatched, attempt, err := svc.DispatchHostedConversationCandidate(ctx, task.ID,
			controlmodel.RuntimeBindingCandidate{Binding: binding}, HostedConversationContext{
				SessionID: "conversation-1", TurnID: turnID,
				ProviderSessionID: providerSessionID, WorkspaceKey: workspaceKey,
			})
		if err != nil {
			t.Fatal(err)
		}
		return dispatched, attempt
	}

	_, first := dispatchTurn("first", "turn-1", "", "")
	claimed, _ := svc.Claim(ctx, store.ExecutionClaim{Tenant: "tenant", Namespace: "default",
		RuntimePoolName: pool.Name, HostID: host.ID, HostGeneration: host.LeaseGeneration,
		LeaseOwner: "host/1", LeaseToken: "lease-1", LeaseTTL: time.Minute})
	preparing, _ := svc.MarkPreparing(ctx, claimed.ID, claimed.LeaseToken, claimed.FencingToken)
	running, _ := svc.MarkRunning(ctx, preparing.ID, preparing.LeaseToken, preparing.FencingToken,
		"native-session", "tenant/legacy-first-task")
	if _, err := svc.Complete(ctx, running.ID, running.LeaseToken, running.FencingToken,
		json.RawMessage(`{"output":"done"}`), nil); err != nil {
		t.Fatal(err)
	}

	secondTask, _ := dispatchTurn("second", "turn-2", "native-session", "tenant/wrong-second-task")
	claimed, _ = svc.Claim(ctx, store.ExecutionClaim{Tenant: "tenant", Namespace: "default",
		RuntimePoolName: pool.Name, HostID: host.ID, HostGeneration: host.LeaseGeneration,
		LeaseOwner: "host/1", LeaseToken: "lease-2", LeaseTTL: time.Minute})
	preparing, _ = svc.MarkPreparing(ctx, claimed.ID, claimed.LeaseToken, claimed.FencingToken)
	running, _ = svc.MarkRunning(ctx, preparing.ID, preparing.LeaseToken, preparing.FencingToken,
		"native-session", "tenant/wrong-second-task")
	failed, err := svc.Fail(ctx, running.ID, running.LeaseToken, running.FencingToken,
		"provider_failed", "invalid resume", nil)
	if err != nil || failed.AgentTaskID != secondTask.ID {
		t.Fatalf("fail second turn: attempt=%+v err=%v", failed, err)
	}

	_, retryAttempt, err := svc.RetryTask(ctx, secondTask.ID)
	if err != nil {
		t.Fatal(err)
	}
	if retryAttempt.ProviderSessionID != "native-session" ||
		retryAttempt.WorkspaceKey != "tenant/legacy-first-task" || retryAttempt.TurnID != "turn-2" ||
		first.ID == retryAttempt.ID {
		t.Fatalf("retry did not use successful conversation checkpoint: %+v", retryAttempt)
	}
}

func TestQueuedConversationRedispatchPreservesSessionAndTurn(t *testing.T) {
	ctx := context.Background()
	st, _ := store.Open(ctx, store.DefaultConfig())
	defer st.Close()
	pool, _ := st.RuntimeRegistry().UpsertRuntimePool(ctx, &controlmodel.RuntimePool{
		Tenant: "tenant", Namespace: "default", Name: "coding",
	})
	profile, _ := st.RuntimeRegistry().UpsertRuntimeProfile(ctx, &controlmodel.RuntimeProfile{
		Tenant: "tenant", Namespace: "default", Name: "codex", Provider: "codex",
	})
	host, _ := st.RuntimeRegistry().UpsertRuntimeHost(ctx, &controlmodel.RuntimeHost{
		Tenant: "tenant", Namespace: "default", HostKey: "host", PoolName: pool.Name,
		State: controlmodel.RuntimeHostOnline, Capacity: 1,
		Capabilities: json.RawMessage(`{"providers":{"codex":"test"}}`),
	})
	agentID, bindingID := uuid.New(), uuid.New()
	binding := controlmodel.RuntimeBinding{AgentID: agentID, BindingID: bindingID,
		Kind: controlmodel.DataPlaneHostedRuntime, RuntimeProfileID: profile.ID, RuntimePoolID: pool.ID}
	issue, _ := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant", Namespace: "default", Title: "conversation retry",
		Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "ken"},
	})
	_, task, _ := st.Collaboration().AssignIssue(ctx, issue.ID, issue.Version,
		controlmodel.AssigneeAgent, agentID.String(), issue.Creator)
	svc := &Service{Store: st}
	dispatched, first, err := svc.DispatchHostedConversationCandidate(ctx, task.ID,
		controlmodel.RuntimeBindingCandidate{Binding: binding}, HostedConversationContext{
			SessionID: "conversation-1", TurnID: "turn-1",
		})
	if err != nil {
		t.Fatal(err)
	}
	claimed, _ := svc.Claim(ctx, store.ExecutionClaim{Tenant: "tenant", Namespace: "default",
		RuntimePoolName: pool.Name, HostID: host.ID, HostGeneration: host.LeaseGeneration,
		LeaseOwner: "host/1", LeaseToken: "lease-1", LeaseTTL: time.Minute})
	preparing, _ := svc.MarkPreparing(ctx, claimed.ID, claimed.LeaseToken, claimed.FencingToken)
	running, _ := svc.MarkRunning(ctx, preparing.ID, preparing.LeaseToken,
		preparing.FencingToken, "unfinished-native-session", "tenant/conversation-workspace")
	current, _ := st.Collaboration().GetAgentTask(ctx, dispatched.ID)
	queued, _, err := st.Collaboration().RequeueAgentTaskAfterAttemptFailure(ctx, current.ID,
		store.TaskFailure{ExpectedVersion: current.Version, AttemptID: running.ID,
			DispatchGeneration: running.DispatchGeneration, LeaseToken: running.LeaseToken,
			FencingToken: running.FencingToken, Code: "heartbeat_timeout", Message: "host lost"})
	if err != nil || queued.Status != controlmodel.AgentTaskQueued || queued.ErrorCode == "" {
		t.Fatalf("requeue: task=%+v err=%v", queued, err)
	}

	redispatched, retryAttempt, err := svc.DispatchHostedCandidate(ctx, task.ID,
		controlmodel.RuntimeBindingCandidate{Binding: binding})
	if err != nil {
		t.Fatal(err)
	}
	if retryAttempt.ID == first.ID || retryAttempt.Attempt != 2 ||
		retryAttempt.SessionID != "conversation-1" || retryAttempt.TurnID != "turn-1" ||
		retryAttempt.ProviderSessionID != "" ||
		retryAttempt.WorkspaceKey != "tenant/conversation-workspace" {
		t.Fatalf("redispatch lost conversation identity: %+v", retryAttempt)
	}
	if redispatched.SessionID != "conversation-1" || redispatched.ErrorCode != "" ||
		redispatched.ErrorMessage != "" {
		t.Fatalf("redispatched task retained stale failure state: %+v", redispatched)
	}
}

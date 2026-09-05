// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package runtimebinding

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
	"github.com/spring-ai-alibaba/aistio/internal/taskauth"
	"github.com/spring-ai-alibaba/aistio/internal/taskplane"
)

type managedRecorder struct {
	sessionID string
	wakes     []string
}

func (m *managedRecorder) FindOrCreateSessionID(context.Context, string, string, string, string) (string, error) {
	return m.sessionID, nil
}

func (m *managedRecorder) PostSessionWakeEvent(_ context.Context, _ string, _ string, text string) error {
	m.wakes = append(m.wakes, text)
	return nil
}

func (m *managedRecorder) AbortManagedSession(context.Context, string, string) error { return nil }

type externalRecorder struct {
	tenant, namespace, instance, session, command string
	payload                                       []byte
}

func (e *externalRecorder) SendExecutionAttemptCommand(tenant, namespace, instanceID, sessionID, command string, params []byte) error {
	e.tenant, e.namespace, e.instance, e.session, e.command = tenant, namespace, instanceID, sessionID, command
	e.payload = append([]byte(nil), params...)
	return nil
}

func TestResolverDispatchesSameAgentTaskContractToEveryBackend(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	tokens := &taskauth.Manager{Secret: []byte("0123456789abcdef0123456789abcdef"), TTL: time.Hour}
	creator := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	newTask := func(agent string) *controlmodel.AgentTask {
		t.Helper()
		issue, createErr := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "tenant-a", Namespace: "default",
			Title: "backend contract " + agent, Creator: creator, AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: agent})
		if createErr != nil {
			t.Fatal(createErr)
		}
		tasks, listErr := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
		if listErr != nil || len(tasks) != 1 {
			t.Fatalf("task setup: %+v %v", tasks, listErr)
		}
		return tasks[0]
	}

	t.Run("managed", func(t *testing.T) {
		agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "tenant-a", Namespace: "default",
			AgentKey: "managed-worker", Status: controlmodel.AgentActive})
		if err != nil {
			t.Fatal(err)
		}
		configuration := json.RawMessage(`{"ownerRef":"owner","managedDefinitionRef":"managed-worker"}`)
		binding, err := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{AgentID: agent.ID,
			Tenant: agent.Tenant, Namespace: agent.Namespace, Kind: controlmodel.DataPlaneManaged,
			Configuration: configuration, Priority: 100, Enabled: true})
		if err != nil {
			t.Fatal(err)
		}
		runtimeBinding, _ := binding.RuntimeBinding()
		task := newTask(agent.ID.String())
		managed := &managedRecorder{sessionID: "managed-session"}
		resolver := &Resolver{Store: st, Managed: managed, Tokens: tokens}
		result, err := resolver.Dispatch(ctx, task.ID, &runtimeBinding)
		if err != nil {
			t.Fatal(err)
		}
		if result.Task.Status != controlmodel.AgentTaskDispatched || result.SessionID != managed.sessionID || result.TaskToken == "" || len(managed.wakes) != 1 {
			t.Fatalf("managed dispatch: result=%+v wakes=%+v", result, managed.wakes)
		}
		session, err := st.Sessions().Get(ctx, task.Tenant, agent.AgentKey, task.Namespace, managed.sessionID)
		if err != nil || session.AgentID != agent.ID || session.BindingID != binding.ID || session.OriginType != "agent-task" ||
			session.AgentTaskID == nil || *session.AgentTaskID != task.ID || session.Tenant != task.Tenant {
			t.Fatalf("managed session context: %+v %v", session, err)
		}
	})

	t.Run("external", func(t *testing.T) {
		agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "tenant-a", Namespace: "default",
			AgentKey: "external-worker", Status: controlmodel.AgentActive})
		if err != nil {
			t.Fatal(err)
		}
		configuration := json.RawMessage(`{"instanceSelector":{}}`)
		binding, err := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{AgentID: agent.ID,
			Tenant: agent.Tenant, Namespace: agent.Namespace, Kind: controlmodel.DataPlaneExternalApplication,
			Configuration: configuration, Priority: 100, Enabled: true})
		if err != nil {
			t.Fatal(err)
		}
		runtimeBinding, _ := binding.RuntimeBinding()
		task := newTask(agent.ID.String())
		instance, err := st.RuntimeRegistry().UpsertAgentInstance(ctx, &controlmodel.AgentInstance{Tenant: task.Tenant,
			Namespace: task.Namespace, AgentID: agent.ID, BindingID: binding.ID, BackendKind: controlmodel.DataPlaneExternalApplication,
			InstanceKey: "instance-1", Health: controlmodel.RuntimeHealthHealthy, Capacity: 2, LastSeenAt: time.Now().UTC()})
		if err != nil {
			t.Fatal(err)
		}
		external := &externalRecorder{}
		_, err = st.Orchestration().PutRuntimePolicy(ctx, &controlmodel.AgentRuntimePolicy{
			Tenant: task.Tenant, Namespace: task.Namespace, AgentRef: task.AgentRef,
			Candidates:    []controlmodel.RuntimeBindingCandidate{{Binding: runtimeBinding}},
			SelectionMode: "ordered", FallbackMode: "disabled"})
		if err != nil {
			t.Fatal(err)
		}
		resolver := &Resolver{Store: st, External: external, Tokens: tokens}
		result, err := resolver.Dispatch(ctx, task.ID, nil)
		if err != nil {
			t.Fatal(err)
		}
		if result.AgentInstanceID == nil || *result.AgentInstanceID != instance.ID || result.TaskToken == "" ||
			result.Execution == nil || result.Execution.BackendKind != controlmodel.DataPlaneExternalApplication ||
			external.tenant != task.Tenant || external.namespace != task.Namespace || external.instance != instance.InstanceKey || external.command != commandAttemptDispatch {
			t.Fatalf("external dispatch: result=%+v command=%+v", result, external)
		}
		var payload map[string]any
		if json.Unmarshal(external.payload, &payload) != nil || payload["agentTaskId"] != task.ID.String() || payload["taskToken"] == "" {
			t.Fatalf("external payload: %s", external.payload)
		}
	})

	t.Run("hosted", func(t *testing.T) {
		agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "tenant-a", Namespace: "default",
			AgentKey: "hosted-worker", Status: controlmodel.AgentActive})
		if err != nil {
			t.Fatal(err)
		}
		profile, _ := st.RuntimeRegistry().UpsertRuntimeProfile(ctx, &controlmodel.RuntimeProfile{Tenant: agent.Tenant, Namespace: agent.Namespace, Name: "codex", Provider: "codex"})
		pool, _ := st.RuntimeRegistry().UpsertRuntimePool(ctx, &controlmodel.RuntimePool{Tenant: agent.Tenant, Namespace: agent.Namespace, Name: "coding"})
		configuration, _ := json.Marshal(controlmodel.HostedBindingConfiguration{RuntimeProfileID: profile.ID, RuntimePoolID: pool.ID})
		binding, err := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{AgentID: agent.ID,
			Tenant: agent.Tenant, Namespace: agent.Namespace, Kind: controlmodel.DataPlaneHostedRuntime,
			Configuration: configuration, Priority: 100, Enabled: true})
		if err != nil {
			t.Fatal(err)
		}
		runtimeBinding, _ := binding.RuntimeBinding()
		task := newTask(agent.ID.String())
		resolver := &Resolver{Store: st, Tokens: tokens}
		result, err := resolver.Dispatch(ctx, task.ID, &runtimeBinding)
		if err != nil {
			t.Fatal(err)
		}
		if result.Execution == nil || result.Execution.AgentTaskID != task.ID || result.Execution.Attempt != 1 ||
			result.Task.Status != controlmodel.AgentTaskDispatched || result.TaskToken == "" || result.AgentInstanceID != nil {
			t.Fatalf("hosted dispatch: %+v", result)
		}
	})
}

func TestResolverRejectsCrossTenantInstanceSelection(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agentID := uuid.New()
	issue, _ := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "tenant-a", Namespace: "shared", Title: "isolated",
		Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: agentID.String()})
	tasks, _ := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 1})
	bindingID := uuid.New()
	_, _ = st.RuntimeRegistry().UpsertAgentInstance(ctx, &controlmodel.AgentInstance{ID: uuid.New(), Tenant: "tenant-b", Namespace: "shared",
		AgentID: agentID, BindingID: bindingID, BackendKind: controlmodel.DataPlaneExternalApplication,
		InstanceKey: "same-name", Health: controlmodel.RuntimeHealthHealthy})
	if _, err := (&Resolver{Store: st}).Resolve(ctx, tasks[0].ID, nil); err == nil {
		t.Fatal("cross-tenant AgentInstance was selected")
	}
}

func TestAdaptiveRunTeamSnapshotIsImmutable(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant-a", Namespace: "default", Name: "snapshot", LeaderAgentRef: "leader",
	})
	if err != nil {
		t.Fatal(err)
	}
	originalPolicy := json.RawMessage(`{"selectionMode":"ordered","fallbackMode":"disabled","candidates":[{"binding":{"kind":"external-application","agentSelector":{"agent":"worker"}}}]}`)
	member, err := st.Collaboration().AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{
		TeamID: team.ID, AgentRef: "worker", Role: "worker", RuntimeBindingPolicy: originalPolicy,
	})
	if err != nil {
		t.Fatal(err)
	}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: team.Tenant,
		Namespace: team.Namespace, Title: "snapshot run", AssigneeType: controlmodel.AssigneeTeam,
		AssigneeRef: team.ID.String(), Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}})
	if err != nil {
		t.Fatal(err)
	}
	tasks, _ := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 10})
	if len(tasks) != 1 {
		t.Fatalf("expected coordinator task: %+v", tasks)
	}
	if err = st.Collaboration().RemoveTeamMember(ctx, team.ID, member.ID); err != nil {
		t.Fatal(err)
	}
	_, err = st.Collaboration().AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{
		TeamID: team.ID, AgentRef: "worker-v2", Role: "worker",
		RuntimeBindingPolicy: json.RawMessage(`{"selectionMode":"ordered","fallbackMode":"disabled","candidates":[{"binding":{"kind":"managed","managedOwnerRef":"owner","managedAgentRef":"worker-v2"}}]}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	snapshot, err := LoadRunTeamSnapshot(ctx, st, tasks[0].OrchestrationRunID, team.ID)
	if err != nil || len(snapshot.Members) != 1 || snapshot.Members[0].AgentRef != "worker" ||
		string(snapshot.Members[0].RuntimeBindingPolicy) != string(originalPolicy) {
		t.Fatalf("Run Team snapshot changed with live roster: snapshot=%+v err=%v", snapshot, err)
	}
}

func TestTeamLeaderTaskCompletesThroughHostedAttempt(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	tokens := &taskauth.Manager{Secret: []byte("0123456789abcdef0123456789abcdef"), TTL: time.Hour}
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "tenant-team", Namespace: "default",
		AgentKey: "hosted-team-leader", Status: controlmodel.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	profile, _ := st.RuntimeRegistry().UpsertRuntimeProfile(ctx, &controlmodel.RuntimeProfile{Tenant: agent.Tenant,
		Namespace: agent.Namespace, Name: "codex-team", Provider: "codex"})
	pool, _ := st.RuntimeRegistry().UpsertRuntimePool(ctx, &controlmodel.RuntimePool{Tenant: agent.Tenant,
		Namespace: agent.Namespace, Name: "team-hosts"})
	configuration, _ := json.Marshal(controlmodel.HostedBindingConfiguration{RuntimeProfileID: profile.ID, RuntimePoolID: pool.ID})
	binding, err := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{AgentID: agent.ID,
		Tenant: agent.Tenant, Namespace: agent.Namespace, Kind: controlmodel.DataPlaneHostedRuntime,
		Configuration: configuration, Priority: 100, Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	runtimeBinding, _ := binding.RuntimeBinding()
	if _, err = st.Orchestration().PutRuntimePolicy(ctx, &controlmodel.AgentRuntimePolicy{Tenant: agent.Tenant,
		Namespace: agent.Namespace, AgentRef: agent.ID.String(), SelectionMode: "ordered", FallbackMode: "disabled",
		Candidates: []controlmodel.RuntimeBindingCandidate{{Binding: runtimeBinding}}}); err != nil {
		t.Fatal(err)
	}
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{Tenant: agent.Tenant,
		Namespace: agent.Namespace, Name: "hosted-team", LeaderAgentRef: agent.ID.String()})
	if err != nil {
		t.Fatal(err)
	}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: agent.Tenant, Namespace: agent.Namespace,
		Title: "coordinate hosted work", AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String(),
		Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 10})
	if err != nil || len(tasks) != 1 || tasks[0].TeamID == nil || *tasks[0].TeamID != team.ID || !tasks[0].LeaderTask {
		t.Fatalf("Team coordinator task=%+v err=%v", tasks, err)
	}
	result, err := (&Resolver{Store: st, Tokens: tokens}).DispatchCandidate(ctx, tasks[0].ID, nil)
	if err != nil || result.Execution == nil || result.Execution.BackendKind != controlmodel.DataPlaneHostedRuntime {
		t.Fatalf("hosted Team dispatch=%+v err=%v", result, err)
	}
	host, err := st.RuntimeRegistry().UpsertRuntimeHost(ctx, &controlmodel.RuntimeHost{Tenant: agent.Tenant,
		Namespace: agent.Namespace, HostKey: "team-host", PoolName: pool.Name, State: controlmodel.RuntimeHostOnline,
		Capacity: 1, Capabilities: json.RawMessage(`{"providers":{"codex":"test"}}`)})
	if err != nil {
		t.Fatal(err)
	}
	svc := &taskplane.Service{Store: st}
	claimed, err := svc.Claim(ctx, store.ExecutionClaim{Tenant: agent.Tenant, Namespace: agent.Namespace,
		RuntimePoolName: pool.Name, HostID: host.ID, HostGeneration: host.LeaseGeneration,
		LeaseOwner: "team-host/lease", LeaseToken: "lease", LeaseTTL: time.Minute})
	if err != nil || claimed.ID != result.Execution.ID {
		t.Fatalf("claim hosted Team attempt=%+v err=%v", claimed, err)
	}
	preparing, err := svc.MarkPreparing(ctx, claimed.ID, claimed.LeaseToken, claimed.FencingToken)
	if err != nil {
		t.Fatal(err)
	}
	running, err := svc.MarkRunning(ctx, preparing.ID, preparing.LeaseToken, preparing.FencingToken, "thread-team", "workspace")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = svc.Complete(ctx, running.ID, running.LeaseToken, running.FencingToken,
		json.RawMessage(`{"output":"team leader turn complete"}`), nil); err != nil {
		t.Fatal(err)
	}
	final, err := st.Collaboration().GetAgentTask(ctx, tasks[0].ID)
	if err != nil || final.Status != controlmodel.AgentTaskCompleted {
		t.Fatalf("hosted Team task did not complete: task=%+v err=%v", final, err)
	}
}

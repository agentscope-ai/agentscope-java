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

package runtimebinding

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
	"github.com/spring-ai-alibaba/aistio/internal/taskauth"
	"github.com/spring-ai-alibaba/aistio/internal/taskplane"
)

type managedRecorder struct {
	sessionID    string
	wakes        []string
	claims       []product.ManagedRuntimeFence
	aborts       []product.ManagedAttemptAbort
	abortSession string
	abortOwner   string
	order        []string
	claimErr     error
}

func (m *managedRecorder) FindOrCreateSessionID(context.Context, string, string, string, string) (string, error) {
	return m.sessionID, nil
}

func (m *managedRecorder) PostSessionWakeEvent(_ context.Context, _ string, _ string, text string) error {
	m.order = append(m.order, "wake")
	m.wakes = append(m.wakes, text)
	return nil
}

func (m *managedRecorder) ClaimManagedRuntimeFence(_ context.Context, _ string,
	agentTaskID, attemptID uuid.UUID, dispatchGeneration int64, turnID string) error {
	m.order = append(m.order, "claim")
	m.claims = append(m.claims, product.ManagedRuntimeFence{AgentTaskID: agentTaskID.String(),
		AttemptID: attemptID.String(), DispatchGeneration: dispatchGeneration, TurnID: turnID})
	return m.claimErr
}

func (m *managedRecorder) PostManagedAttemptAbort(_ context.Context, sessionID, ownerID string,
	abort product.ManagedAttemptAbort) error {
	m.abortSession, m.abortOwner = sessionID, ownerID
	m.aborts = append(m.aborts, abort)
	return nil
}

type externalRecorder struct {
	tenant, namespace, agent, instance, session, command string
	payload                                              []byte
}

func TestManagedWakeInstructionsDescribeTeamRoleLifecycle(t *testing.T) {
	standalone := managedWakeInstructions(&controlmodel.AgentTask{})
	if !strings.Contains(standalone, "usable result") || !strings.Contains(standalone, "task.fail") ||
		!strings.Contains(standalone, "human explicit mention") || !strings.Contains(standalone, "one authoritative visible reply") {
		t.Fatalf("standalone instructions: %q", standalone)
	}
	teamID := uuid.New()
	initial := managedWakeInstructions(&controlmodel.AgentTask{TeamID: &teamID, LeaderTask: true})
	if !strings.Contains(initial, "initial Team leader") || !strings.Contains(initial, "task.complete immediately") {
		t.Fatalf("initial leader instructions: %q", initial)
	}
	parentID := uuid.New()
	followUp := managedWakeInstructions(&controlmodel.AgentTask{
		TeamID: &teamID, LeaderTask: true, ParentTaskID: &parentID,
	})
	if !strings.Contains(followUp, "leader follow-up") || !strings.Contains(followUp, "issue.accept") ||
		strings.Contains(followUp, "Delegate suitable child work once") {
		t.Fatalf("leader follow-up instructions: %q", followUp)
	}
	worker := managedWakeInstructions(&controlmodel.AgentTask{TeamID: &teamID})
	if !strings.Contains(worker, "Team worker") || !strings.Contains(worker, "task.complete") {
		t.Fatalf("worker instructions: %q", worker)
	}
}

func TestCancelManagedAttemptUsesCompleteOldTurnFence(t *testing.T) {
	taskID, attemptID := uuid.New(), uuid.New()
	managed := &managedRecorder{}
	attempt := &controlmodel.ExecutionAttempt{ID: attemptID, AgentTaskID: taskID,
		BackendKind: controlmodel.DataPlaneManaged, SessionID: "session-a", ManagedOwnerRef: "owner-a",
		DispatchGeneration: 4, TurnID: "turn-a"}
	if err := (&Resolver{Managed: managed}).CancelAttempt(t.Context(), attempt); err != nil {
		t.Fatal(err)
	}
	if managed.abortSession != attempt.SessionID || managed.abortOwner != attempt.ManagedOwnerRef || len(managed.aborts) != 1 {
		t.Fatalf("managed abort target=%s/%s payload=%+v", managed.abortSession, managed.abortOwner, managed.aborts)
	}
	abort := managed.aborts[0]
	if abort.AgentTaskID != taskID || abort.AttemptID != attemptID || abort.DispatchGeneration != 4 ||
		abort.TurnID != "turn-a" || abort.Reason != "task_cancel_requested" {
		t.Fatalf("managed abort lost physical fence: %+v", abort)
	}
}

func (e *externalRecorder) SendExecutionAttemptCommand(tenant, namespace, agentID, instanceID, sessionID, command string, params []byte) error {
	e.tenant, e.namespace, e.agent, e.instance, e.session, e.command = tenant, namespace, agentID, instanceID, sessionID, command
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
		if result.Task.Status != controlmodel.AgentTaskDispatched || result.SessionID != managed.sessionID ||
			result.TaskToken == "" || len(managed.wakes) != 1 || len(managed.claims) != 1 ||
			len(managed.order) != 2 || managed.order[0] != "claim" || managed.order[1] != "wake" {
			t.Fatalf("managed dispatch: result=%+v wakes=%+v", result, managed.wakes)
		}
		if managed.claims[0].AgentTaskID != task.ID.String() || managed.claims[0].AttemptID != result.Execution.ID.String() ||
			managed.claims[0].DispatchGeneration != result.Execution.DispatchGeneration ||
			managed.claims[0].TurnID != result.Execution.TurnID {
			t.Fatalf("managed dispatch claimed wrong runtime fence: %+v execution=%+v", managed.claims[0], result.Execution)
		}
		if strings.Contains(managed.wakes[0], result.TaskToken) ||
			strings.Contains(strings.ToLower(managed.wakes[0]), "token") ||
			strings.Contains(managed.wakes[0], task.ID.String()) {
			t.Fatalf("managed wake leaked task identity or credentials: %q", managed.wakes[0])
		}
		session, err := st.Sessions().Get(ctx, task.Tenant, agent.AgentKey, task.Namespace, managed.sessionID)
		if err != nil || session.AgentID != agent.ID || session.BindingID != binding.ID || session.OriginType != "agent-task" ||
			session.AgentTaskID == nil || *session.AgentTaskID != task.ID || session.Tenant != task.Tenant {
			t.Fatalf("managed session context: %+v %v", session, err)
		}
		var persistedContext map[string]any
		if json.Unmarshal(session.TaskContext, &persistedContext) != nil || persistedContext["taskToken"] != nil {
			t.Fatalf("public session context persisted task credentials: %s", session.TaskContext)
		}

		failingTask := newTask(agent.ID.String())
		claimFailure := errors.New("product runtime fence unavailable")
		failedManaged := &managedRecorder{sessionID: "managed-session-fence-failure", claimErr: claimFailure}
		failedResolver := &Resolver{Store: st, Managed: failedManaged, Tokens: tokens}
		if result, dispatchErr := failedResolver.Dispatch(ctx, failingTask.ID, &runtimeBinding); !errors.Is(dispatchErr, claimFailure) || result != nil {
			t.Fatalf("fence claim failure result=%+v err=%v", result, dispatchErr)
		}
		if len(failedManaged.wakes) != 0 || len(failedManaged.order) != 1 || failedManaged.order[0] != "claim" {
			t.Fatalf("fence claim failure woke data plane: %+v", failedManaged)
		}
		requeued, getErr := st.Collaboration().GetAgentTask(ctx, failingTask.ID)
		if getErr != nil || requeued.Status != controlmodel.AgentTaskQueued {
			t.Fatalf("fence claim failure did not requeue task: task=%+v err=%v", requeued, getErr)
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
			external.tenant != task.Tenant || external.namespace != task.Namespace || external.agent != task.AgentRef ||
			external.instance != instance.InstanceKey || external.command != commandAttemptDispatch {
			t.Fatalf("external dispatch: result=%+v command=%+v", result, external)
		}
		var payload map[string]any
		if json.Unmarshal(external.payload, &payload) != nil || payload["agentTaskId"] != task.ID.String() || payload["taskToken"] == "" {
			t.Fatalf("external payload: %s", external.payload)
		}
		// A portable definition requires an explicit consumer. Legacy instances remain usable only without one.
		resolver.Tasks = &taskplane.Service{Store: st, ResolveDefinition: func(context.Context, uuid.UUID) (json.RawMessage, error) {
			return json.RawMessage(`{"version":1,"definitionDigest":"frozen"}`), nil
		}}
		next := newTask(agent.ID.String())
		if _, err := resolver.Dispatch(ctx, next.ID, nil); err == nil {
			t.Fatal("legacy instance consumed platform definition")
		}
		consumer := *instance
		consumer.ID = uuid.Nil
		consumer.InstanceKey = "workspace-consumer"
		consumer.Capabilities = json.RawMessage(`["workspace-definition-v1"]`)
		registered, err := st.RuntimeRegistry().UpsertAgentInstance(ctx, &consumer)
		if err != nil {
			t.Fatal(err)
		}
		selected, err := resolver.Dispatch(ctx, next.ID, nil)
		if err != nil {
			t.Fatal(err)
		}
		if selected.AgentInstanceID == nil || *selected.AgentInstanceID != registered.ID {
			t.Fatal("selected incompatible external instance")
		}
		var frozen controlmodel.RuntimeDispatchSnapshot
		if json.Unmarshal(selected.Task.RuntimeBinding, &frozen) != nil || !strings.Contains(string(frozen.Definition), "frozen") {
			t.Fatal("missing immutable definition")
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

func TestManagedWakeCarriesHumanRevisionForWorkersAndLeaders(t *testing.T) {
	teamID, parentID := uuid.New(), uuid.New()
	for _, leader := range []bool{false, true} {
		task := &controlmodel.AgentTask{TeamID: &teamID, ParentTaskID: &parentID, LeaderTask: leader, TriggerType: "comment"}
		revisionID := uuid.New()
		brief := &collaboration.ExecutionBrief{OriginalObjective: "Research latest phone technology", TriggerInput: "worker failed: missing key", HumanRevisions: []collaboration.HumanRevision{{CommentID: revisionID, Content: "直接根据自己的认知回答"}}, DecisionRule: "Respect revised evidence requirements"}
		wake := managedWakeWithBrief(task, brief)
		if !strings.Contains(wake, brief.HumanRevisions[0].Content) || !strings.Contains(wake, "CURRENT EXECUTION BRIEF") || !strings.Contains(wake, brief.DecisionRule) || strings.Contains(wake, revisionID.String()) {
			t.Fatalf("wake lost revised instructions or exposed bookkeeping identity: %s", wake)
		}
	}
}

func TestWorkflowWakeIncludesStepAndUpstreamDeliverable(t *testing.T) {
	brief := &collaboration.ExecutionBrief{OriginalObjective: "Write then refine a poem", Workflow: &collaboration.WorkflowStepContext{NodeKey: "refine", NodeInput: json.RawMessage(`{"style":"concise"}`), Predecessors: []collaboration.WorkflowStepResult{{NodeKey: "draft", State: controlmodel.RunNodeSucceeded, Output: json.RawMessage(`{"poem":"upstream draft"}`)}}}}
	wake := managedWakeWithBrief(&controlmodel.AgentTask{TriggerType: "orchestration_node"}, brief)
	for _, want := range []string{`"nodeKey":"refine"`, `"poem":"upstream draft"`, `"style":"concise"`, "optional preferences", "task.complete before ending the turn", "workflow engine schedules subsequent steps"} {
		if !strings.Contains(wake, want) {
			t.Fatalf("workflow wake missing %q: %s", want, wake)
		}
	}
	plain := managedWakeWithBrief(&controlmodel.AgentTask{}, &collaboration.ExecutionBrief{})
	if strings.Contains(plain, "declared workflow") {
		t.Fatal("workflow contract leaked into ordinary tasks")
	}
}

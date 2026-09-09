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
	"context"
	"testing"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestAttachAttemptSessionRefsUsesControlPlaneSessionIdentity(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	taskID, agentID := uuid.New(), uuid.New()
	runtimeSessionID := uuid.NewString()
	session, err := st.Sessions().Upsert(ctx, &store.Session{
		Tenant: "tenant-a", Namespace: "default", AgentID: agentID, AgentName: "leader",
		SessionID: runtimeSessionID, Phase: store.SessionPhaseIdle, AgentTaskID: &taskID,
	})
	if err != nil {
		t.Fatal(err)
	}
	attempt := &controlmodel.ExecutionAttempt{Tenant: session.Tenant, Namespace: session.Namespace,
		AgentID: agentID, AgentTaskID: taskID, SessionID: runtimeSessionID}
	server := &Server{store: st}
	server.attachAttemptSessionRefs(ctx, []*controlmodel.ExecutionAttempt{attempt})

	if attempt.SessionRef == nil || *attempt.SessionRef != session.ID || attempt.SessionID == session.ID.String() {
		t.Fatalf("attempt session identity was not disambiguated: attempt=%+v session=%+v", attempt, session)
	}
}

func TestHostedAttemptSessionRefSurvivesNextConversationTurn(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	oldTask, newTask, agent, binding := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	session, err := st.Sessions().Upsert(ctx, &store.Session{Tenant: "t", Namespace: "n", AgentID: agent, BindingID: binding, SessionID: "same-runtime", AgentTaskID: &newTask, Phase: store.SessionPhaseIdle})
	if err != nil {
		t.Fatal(err)
	}
	attempt := &controlmodel.ExecutionAttempt{Tenant: "t", Namespace: "n", AgentID: agent, BindingID: binding, AgentTaskID: oldTask, SessionID: session.SessionID, BackendKind: controlmodel.DataPlaneHostedRuntime}
	srv := &Server{store: st}
	srv.attachAttemptSessionRefs(ctx, []*controlmodel.ExecutionAttempt{attempt})
	if attempt.SessionRef == nil || *attempt.SessionRef != session.ID {
		t.Fatal("older hosted turn lost its shared session reference")
	}
	attempt.SessionRef = nil
	attempt.BindingID = uuid.New()
	srv.attachAttemptSessionRefs(ctx, []*controlmodel.ExecutionAttempt{attempt})
	if attempt.SessionRef != nil {
		t.Fatal("linked to another runtime binding")
	}
}

func TestHostedTaskDiagnosticsMaterializeMissingSession(t *testing.T) {
	testHostedTaskDiagnostics(t, store.Config{Driver: store.DriverMemory})
}

func TestHostedTaskDiagnosticsMaterializeMissingSessionPostgres(t *testing.T) {
	testHostedTaskDiagnostics(t, acceptancePostgresConfig(t))
}

func TestHostedTaskProviderEventCreatesSession(t *testing.T) {
	testHostedTaskDiagnostics(t, store.Config{Driver: store.DriverMemory}, true)
}

func testHostedTaskDiagnostics(t *testing.T, cfg store.Config, providerFirst ...bool) {
	s, st := accessTestServer(t, cfg)
	ctx := context.Background()
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "default", Namespace: "engineering", AgentKey: "hosted-task", DisplayName: "Hosted task", Status: controlmodel.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: agent.Tenant, Namespace: agent.Namespace, Title: "Private hosted work", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "bob"}, Access: controlmodel.IssueAccess{Mode: "private"}, AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: agent.ID.String()})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("tasks: %v %v", tasks, err)
	}
	runtimeID := uuid.NewString()
	bindingID := uuid.New()
	snapshot := mustJSON(controlmodel.RuntimeDispatchSnapshot{Binding: controlmodel.RuntimeBinding{AgentID: agent.ID, BindingID: bindingID, Kind: controlmodel.DataPlaneHostedRuntime}, SessionID: runtimeID})
	_, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx, store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version, SessionID: runtimeID, RuntimeBinding: snapshot}, &controlmodel.ExecutionAttempt{AgentID: agent.ID, BindingID: bindingID, BackendKind: controlmodel.DataPlaneHostedRuntime, State: controlmodel.ExecutionAssigned, SessionID: runtimeID, TurnID: uuid.NewString()})
	if err != nil {
		t.Fatal(err)
	}
	_, err = st.Orchestration().AppendRunEvent(ctx, &controlmodel.RunEvent{RunID: attempt.RunID, Tenant: attempt.Tenant, Namespace: attempt.Namespace, AttemptID: &attempt.ID, AgentTaskID: &attempt.AgentTaskID, Type: "attempt.provider_event", Actor: controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "runtime-host"}, IdempotencyKey: "provider-event:" + attempt.ID.String() + ":1", Payload: []byte(`{"provider":"qoder","eventType":"assistant","raw":{"text":"Waiting for approval"}}`)})
	if err != nil {
		t.Fatal(err)
	}
	if len(providerFirst) > 0 && providerFirst[0] {
		if err := s.projectHostedProviderEvent(ctx, attempt, "qoder", "assistant", "", 1, []byte(`{"text":"Waiting for approval"}`)); err != nil {
			t.Fatal(err)
		}
	}
	s.attachAttemptSessionRefs(ctx, []*controlmodel.ExecutionAttempt{attempt})
	if attempt.SessionRef == nil {
		t.Fatal("hosted task has a runtime ID but no diagnostic Session")
	}
	session, err := st.Sessions().GetByID(ctx, *attempt.SessionRef)
	if err != nil {
		t.Fatal(err)
	}
	if session.ID.String() == runtimeID || session.SessionID != runtimeID || session.AgentTaskID == nil || *session.AgentTaskID != attempt.AgentTaskID {
		t.Fatalf("wrong session identity: %+v", session)
	}
	events, err := st.Events().List(ctx, session.ID)
	if err != nil || len(events) != 1 || events[0].Content != "Waiting for approval" {
		t.Fatalf("historical provider event not recovered: %+v %v", events, err)
	}
	for range 2 {
		s.attachAttemptSessionRefs(ctx, []*controlmodel.ExecutionAttempt{attempt})
		if err = s.projectHostedProviderEvent(ctx, attempt, "qoder", "assistant", "", 1, []byte(`{"text":"Waiting for approval"}`)); err != nil {
			t.Fatal(err)
		}
	}
	events, _ = st.Events().List(ctx, session.ID)
	if len(events) != 1 {
		t.Fatalf("replay duplicated events: %d", len(events))
	}
	base := "/api/v1/sessions/" + session.ID.String()
	for _, suffix := range []string{"", "/events", "/turns", "/commands"} {
		for _, user := range []string{"bob", "alice", "developer", "operator", "auditor"} {
			want := 404
			if user == "bob" || user == "auditor" {
				want = 200
			}
			w := accessRequest(s, user, "GET", base+suffix+"?tenant=default&namespace=engineering", "")
			if w.Code != want {
				t.Errorf("%s %s: %d %s", user, suffix, w.Code, w.Body.String())
			}
		}
	}
	// A runtime UUID is not itself a valid control-plane Session link.
	if w := accessRequest(s, "bob", "GET", "/api/v1/sessions/"+runtimeID+"?tenant=default&namespace=engineering", ""); w.Code != 404 {
		t.Fatalf("ambiguous runtime UUID accepted: %d", w.Code)
	}
}

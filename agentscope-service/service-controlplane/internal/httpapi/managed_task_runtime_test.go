// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controller"
	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/product"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimebinding"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeportal"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store/memory"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// The HTTP peer is an explicit Portal fixture, not a substitute production adapter.
// The actual Product transport uses its normal initialized PostgreSQL directory.
func TestNativeManagedTaskAdmissionRetryAndCancellation(t *testing.T) {
	dsn := os.Getenv("CONTROL_PLANE_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("CONTROL_PLANE_TEST_POSTGRES_DSN not set")
	}
	ctx := t.Context()
	admin, err := pgx.Connect(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = admin.Close(context.Background()) })
	database := "native_task_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	if _, err = admin.Exec(ctx, "CREATE DATABASE "+pgx.Identifier{database}.Sanitize()); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = admin.Exec(context.Background(), "DROP DATABASE "+pgx.Identifier{database}.Sanitize()+" WITH (FORCE)")
	})
	u, err := url.Parse(dsn)
	if err != nil {
		t.Fatal(err)
	}
	u.Path = "/" + database
	query := u.Query()
	query.Del("search_path")
	u.RawQuery = query.Encode()
	var mu sync.Mutex
	var submissions []product.ManagedRuntimeCall
	accepted := map[string]int{}
	turnStatus := "waiting_tool"
	observationFailure := false
	returnedTurnID := "one-native-turn"
	actionPending := true
	replyKey := ""
	replyCalls := 0
	replyApplied := false
	inputsOpen := true
	var archives []string
	needsRegistration := ""
	var provisioned []runtimeprotocol.RuntimeSessionAdmission
	portal := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		if r.URL.Path == "/api/internal/runtime/sessions/provision" {
			var admission runtimeprotocol.RuntimeSessionAdmission
			if err := json.NewDecoder(r.Body).Decode(&admission); err != nil {
				t.Error(err)
				w.WriteHeader(400)
				return
			}
			provisioned = append(provisioned, admission)
			w.Header().Set("Content-Type", "application/json")
			_, _ = fmt.Fprintf(w, `{"id":%q}`, admission.Bootstrap.Context.SessionID)
			return
		}
		var call product.ManagedRuntimeCall
		if err := json.NewDecoder(r.Body).Decode(&call); err != nil {
			t.Error(err)
			w.WriteHeader(400)
			return
		}
		if r.Header.Get("X-Builder-Internal-Token") != "internal-secret" || r.Header.Get("X-Builder-Internal-User") != "resource-owner" {
			t.Error("transport binding changed")
		}
		if call.Context.Actor["user"] != "original-human" || call.Context.Actor["internal"] != false {
			t.Errorf("original Actor replaced: %+v", call.Context.Actor)
		}
		w.Header().Set("Content-Type", "application/json")
		switch call.Operation {
		case "session":
			if call.Context.SessionID == needsRegistration && len(provisioned) == 0 {
				w.WriteHeader(404)
				_, _ = w.Write([]byte(`{"error":"not registered"}`))
				return
			}
			_, _ = fmt.Fprintf(w, `{"id":%q}`, call.Context.SessionID)
		case "submit_turn":
			submissions = append(submissions, call)
			accepted[call.Context.SessionID]++
			// The first response is lost after the Portal fixture accepted its single Turn.
			if accepted[call.Context.SessionID] == 1 {
				w.WriteHeader(503)
				_, _ = w.Write([]byte(`{"error":"reply lost"}`))
				return
			}
			_, _ = w.Write([]byte(`{"id":"one-native-turn","status":"accepted"}`))
		case "turn":
			if observationFailure {
				w.WriteHeader(503)
				return
			}
			_, _ = fmt.Fprintf(w, `{"id":%q,"status":%q,"inputs_open":%t}`, returnedTurnID, turnStatus, inputsOpen)
		case "turns":
			_, _ = w.Write([]byte(`{"data":[{"id":"one-native-turn"}]}`))
		case "events":
			switch call.After {
			case "":
				_, _ = w.Write([]byte(`{"data":[{"id":"shared-event-id","type":"item.completed","data":{"turn_id":"one-native-turn","item_id":"answer","item":{"role":"assistant","content":[{"type":"text","text":"task-native-result"}]}}},{"id":"action-event","type":"required_action.created","data":{"turn_id":"one-native-turn","request_id":"confirm-1","kind":"confirmation"}}],"next_cursor":"page-one","has_more":true}`))
			case "page-one":
				_, _ = w.Write([]byte(`{"data":[{"id":"foreign-event","type":"item.completed","data":{"turn_id":"foreign-turn","item_id":"foreign-item"}},{"id":"tool-start","type":"tool.requested","data":{"turn_id":"one-native-turn","tool_call_id":"tool-1","name":"execute"}},{"id":"tool-wait","type":"tool.waiting","data":{"turn_id":"one-native-turn","tool_call_id":"tool-1","status":"waiting"}},{"id":"tool-progress","type":"tool.progress","data":{"turn_id":"one-native-turn","tool_call_id":"tool-1","task_id":"operation","status":"running","source_seq":1}},{"id":"tool-delta","type":"tool.delta","data":{"turn_id":"one-native-turn","tool_call_id":"tool-1","task_id":"operation","source_seq":2,"output":[{"type":"text","text":"progress"}]}},{"id":"tool-gap","type":"tool.output_gap","data":{"turn_id":"one-native-turn","tool_call_id":"tool-1","task_id":"operation","source_seq":3,"log_status":"gapped"}},{"id":"tool-operation","type":"tool.operation.completed","data":{"turn_id":"one-native-turn","tool_call_id":"tool-1","task_id":"operation","name":"sandbox.exec","status":"succeeded","last_chunk_seq":1,"log_status":"gapped"}},{"id":"tool-event","type":"tool.completed","data":{"turn_id":"one-native-turn","tool_call_id":"tool-1","name":"execute","status":"completed","result":"done"}},{"id":"tool-late","type":"tool.progress","data":{"turn_id":"one-native-turn","tool_call_id":"tool-1","task_id":"operation","status":"running","source_seq":5}},{"id":"final-item","type":"item.finalized","data":{"turn_id":"one-native-turn","item_id":"answer","final_output":true,"item":{"role":"assistant","content":[{"type":"text","text":"final-native-result"}]}}}],"next_cursor":"page-two","has_more":false}`))
			default:
				_, _ = w.Write([]byte(`{"data":[],"next_cursor":"page-two","has_more":false}`))
			}
		case "snapshot":
			if actionPending {
				_, _ = w.Write([]byte(`{"snapshot_id":"snapshot","resources":{"required_actions":{"data":[{"data":{"turn_id":"one-native-turn","request_id":"confirm-1","kind":"confirmation"}}],"has_more":false}}}`))
			} else {
				_, _ = w.Write([]byte(`{"snapshot_id":"snapshot","resources":{"required_actions":{"data":[],"has_more":false}}}`))
			}
		case "input":
			status := "accepted"
			if replyApplied {
				status = "applied"
			}
			_, _ = fmt.Fprintf(w, `{"id":"task-reply","turn_id":"one-native-turn","status":%q,"delivery_status":"delivered"}`, status)
		case "submit_input":
			body, _ := call.Payload.(map[string]any)
			if body["type"] == "reply" {
				if body["action_id"] != "confirm-1" || call.Context.TurnID == nil || *call.Context.TurnID != "one-native-turn" {
					t.Errorf("wrong native action scope: %+v", call)
				}
				replyCalls++
				if replyKey == "" {
					replyKey = call.Context.CommandID
					actionPending = false
					w.WriteHeader(503)
					return
				}
				if replyKey != call.Context.CommandID {
					t.Error("lost Input reply retried a different intent")
				}
				_, _ = w.Write([]byte(`{"id":"task-reply","turn_id":"one-native-turn","status":"accepted","delivery_status":"queued"}`))
				return
			}
			payload, _ := json.Marshal(call.Payload)
			if !strings.Contains(string(payload), `"type":"cancel"`) || call.Context.TurnID == nil || *call.Context.TurnID != "one-native-turn" {
				t.Errorf("invalid native cancellation: %+v", call)
			}
			archives = append(archives, call.Context.SessionID)
			_, _ = w.Write([]byte(`{"id":"cancel-input","status":"accepted"}`))
		default:
			t.Errorf("legacy/unsupported Portal call: %s", call.Operation)
			w.WriteHeader(500)
		}
	}))
	t.Cleanup(portal.Close)
	prod, err := product.Open(ctx, product.Config{DSN: u.String(), LocalDev: true, SchemaMode: "migrate",
		JWTSecret: strings.Repeat("native-task-test-", 3), InternalToken: "internal-secret", WorkspaceRoot: t.TempDir(), SessionPortalURL: portal.URL})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(prod.Close)
	st, err := memory.Open(ctx, store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	s := NewServer(ServerOptions{Store: st, Product: prod, LocalDev: true, InternalToken: "internal-secret", TaskTokenSecret: strings.Repeat("task-test", 5)})
	controlHTTP := httptest.NewServer(s.router)
	t.Cleanup(controlHTTP.Close)
	courier, err := runtimeportal.NewClient(controlHTTP.URL, "internal-secret")
	if err != nil {
		t.Fatal(err)
	}
	remote := &Server{runtimeOnly: true, portalClient: courier}
	agent, err := st.AgentCatalog().CreateAgent(ctx, &model.Agent{Tenant: "native", Namespace: "n", AgentKey: "worker", OwnerRef: "resource-owner", Status: model.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	configuration, _ := json.Marshal(model.ManagedBindingConfiguration{OwnerRef: "resource-owner", ManagedDefinitionRef: agent.ID.String()})
	binding, err := st.AgentCatalog().CreateBinding(ctx, &model.AgentBinding{AgentID: agent.ID, Tenant: agent.Tenant, Namespace: agent.Namespace, Kind: model.DataPlaneManaged, Enabled: true, Configuration: configuration})
	if err != nil {
		t.Fatal(err)
	}
	runtime, err := binding.RuntimeBinding()
	if err != nil {
		t.Fatal(err)
	}
	newTask := func(registered bool) *runtimebinding.DispatchResult {
		t.Helper()
		issue, err := st.Collaboration().CreateIssue(ctx, &model.Issue{Tenant: agent.Tenant, Namespace: agent.Namespace, Title: "deliver one answer",
			Creator: model.Actor{Type: model.ActorHuman, Ref: "original-human"}, AssigneeType: model.AssigneeAgent, AssigneeRef: agent.ID.String()})
		if err != nil {
			t.Fatal(err)
		}
		tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
		if err != nil || len(tasks) != 1 {
			t.Fatal(tasks, err)
		}
		result, err := s.runtimeBindings.Dispatch(ctx, tasks[0].ID, &runtime)
		if err != nil {
			t.Fatal(err)
		}
		if registered {
			id := uuid.MustParse(result.SessionID)
			contract, _ := json.Marshal(serviceapi.Contract{PublicSessionID: id, Endpoint: model.Endpoint{ID: id, TargetRef: agent.ID},
				Policies: map[string]*model.AgentRuntimePolicy{agent.ID.String(): {Candidates: []model.RuntimeBindingCandidate{{Binding: runtime}}}}})
			if err = sessionapi.Save(ctx, st, &sessionapi.Session{ID: id, Tenant: agent.Tenant, Namespace: agent.Namespace,
				Target: sessionapi.Target{Type: "agent", ID: agent.ID}, Principal: "platform-user:original-human", TaskAttemptID: &result.Execution.ID,
				Contract: contract, Status: "active", CreatedAt: time.Now().UTC(), UpdatedAt: time.Now().UTC()}, true); err != nil {
				t.Fatal(err)
			}
		}
		return result
	}
	first := newTask(true)
	if err = s.DispatchManagedAttempt(ctx, first.Execution.ID); err == nil {
		t.Fatal("lost response was silently accepted")
	}
	pending, _ := st.Collaboration().GetAgentTask(ctx, first.Task.ID)
	if pending.Status != model.AgentTaskDispatched || *pending.CurrentAttemptID != first.Execution.ID {
		t.Fatalf("ambiguous admission created another Attempt: %+v", pending)
	}
	if err = s.DispatchManagedAttempt(ctx, first.Execution.ID); err != nil {
		t.Fatal(err)
	}
	bound, _ := st.ExecutionAttempts().Get(ctx, first.Execution.ID)
	if bound.TurnID != "one-native-turn" || bound.LeaseExpiresAt != nil || bound.HeartbeatAt != nil {
		t.Fatalf("native receipt: %+v", bound)
	}
	if err = s.DispatchManagedAttempt(ctx, first.Execution.ID); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	if len(submissions) != 2 {
		t.Fatalf("bound Attempt resubmitted: %d", len(submissions))
	}
	a, _ := json.Marshal(submissions[0].Payload)
	b, _ := json.Marshal(submissions[1].Payload)
	if string(a) != string(b) || submissions[0].Context.CommandID != submissions[1].Context.CommandID {
		t.Fatal("retry changed original input/key")
	}
	mu.Unlock()
	if _, err = s.verifyActiveTaskToken(ctx, first.TaskToken, first.Task.ID); err != nil {
		t.Fatal(err)
	}
	started, _ := st.ExecutionAttempts().Get(ctx, first.Execution.ID)
	if started.State != model.ExecutionRunning || started.HeartbeatAt != nil || started.LeaseExpiresAt != nil {
		t.Fatalf("first MCP action required legacy liveness: %+v", started)
	}
	if id, e := remote.AdmitManagedAttempt(ctx, first.Execution.ID); e != nil || id != "one-native-turn" {
		t.Fatalf("private admission courier: %s %v", id, e)
	}
	if e := remote.ReconcileManagedAttempt(ctx, first.Execution.ID); e != nil {
		t.Fatalf("private observation courier: %v", e)
	}
	if _, e := courier.ManagedAttempt(ctx, first.Execution.ID.String(), "authorize"); e != nil {
		t.Fatalf("private authorization courier: %v", e)
	}
	if ready, e := remote.managedAttemptSettled(ctx, first.Execution.ID); e != nil || ready {
		t.Fatalf("private running barrier: %v %v", ready, e)
	}
	if ready, e := s.serviceExecutionsSettled(ctx, first.Task.OrchestrationRunID); e != nil || ready {
		t.Fatalf("running native Turn settled: %v %v", ready, e)
	}
	mu.Lock()
	observationFailure = true
	mu.Unlock()
	if ready, e := s.serviceExecutionsSettled(ctx, first.Task.OrchestrationRunID); e == nil || ready {
		t.Fatalf("lost observation proved completion: %v %v", ready, e)
	}
	mu.Lock()
	observationFailure = false
	returnedTurnID = "foreign-turn"
	mu.Unlock()
	if ready, e := s.serviceExecutionsSettled(ctx, first.Task.OrchestrationRunID); !errors.Is(e, store.ErrConflict) || ready {
		t.Fatalf("foreign Turn proved completion: %v %v", ready, e)
	}
	mu.Lock()
	returnedTurnID = "one-native-turn"
	mu.Unlock()
	// Jobs read native public events and deliver native replies for their exact Attempt.
	ep := &model.Endpoint{ID: uuid.New(), Tenant: agent.Tenant, Namespace: agent.Namespace, TargetRef: agent.ID, TargetType: model.EndpointTargetAgent}
	runID := first.Task.OrchestrationRunID
	inv := &model.EndpointInvocation{ID: uuid.New(), RunID: &runID, Actor: model.Actor{Type: model.ActorHuman, Ref: "original-human"}, PrincipalRef: "platform-user:original-human"}
	journal := serviceJournal(st, ep, inv)
	if e := s.projectNativeManagedTaskEvents(ctx, ep, inv, journal, bound); e != nil {
		t.Fatal(e)
	}
	projected, e := journal.Snapshot(ctx)
	if e != nil {
		t.Fatal(e)
	}
	requestID := "native-task:" + first.Execution.ID.String() + ":confirm-1"
	if len(projected.Items) != 1 || projected.Items[first.Execution.ID.String()+":answer"] == nil || projected.Actions[requestID] == nil || len(projected.Tools) != 1 {
		t.Fatalf("native task public projection: %+v", projected)
	}
	tool := projected.Tools[first.Execution.ID.String()+":tool-1"]
	operations, _ := tool["operations"].(map[string]any)
	operation, _ := operations["operation"].(map[string]any)
	if tool["name"] != "execute" || tool["status"] != "completed" || operation["log_status"] != "gapped" {
		t.Fatalf("operation changed the model tool: %+v", tool)
	}
	facts, _, _, eventErr := journal.Events(ctx, "", 100)
	if eventErr != nil {
		t.Fatal(eventErr)
	}
	seenKinds := map[string]bool{}
	for _, event := range facts {
		seenKinds[event.Type] = true
	}
	for _, kind := range []string{"tool.waiting", "tool.progress", "tool.delta", "tool.output_gap", "tool.operation.completed", "item.finalized"} {
		if !seenKinds[kind] {
			t.Fatalf("native bridge dropped %s: %+v", kind, facts)
		}
	}
	if e := s.projectNativeManagedTaskEvents(ctx, ep, inv, journal, bound); e != nil {
		t.Fatal(e)
	}
	replay, e := journal.Snapshot(ctx)
	if e != nil || replay.AsOf != projected.AsOf {
		t.Fatalf("native event replay duplicated facts: %s %s %v", projected.AsOf, replay.AsOf, e)
	}
	action := serviceActionInput{RequestID: requestID, Payload: json.RawMessage(`{"allow":true}`)}
	command := &serviceCommand{ID: uuid.New(), Kind: "actions", Actor: inv.Actor, Principal: inv.PrincipalRef, Status: "accepted"}
	command.Payload, _ = json.Marshal(action)
	if e := s.validateServiceCommand(ctx, inv, ep, command, action); e != nil {
		t.Fatal(e)
	}
	if e := s.applyServiceCommand(ctx, inv, ep, command, action); e == nil {
		t.Fatal("fixture did not lose first native reply receipt")
	}
	if e := s.applyServiceCommand(ctx, inv, ep, command, action); e != nil || command.Status != "submitted" || command.InputID != "task-reply" {
		t.Fatalf("native action retry: %+v %v", command, e)
	}
	mu.Lock()
	replyApplied = true
	calls := replyCalls
	mu.Unlock()
	if calls != 2 {
		t.Fatalf("reply delivery calls: %d", calls)
	}
	if e := s.refreshNativeServiceCommand(ctx, ep, inv, command, command.Actor); e != nil || command.Status != "completed" {
		t.Fatalf("native task Input polling: %+v %v", command, e)
	}
	foreignInvocation := *inv
	foreignInvocation.RunID = &[]uuid.UUID{uuid.New()}[0]
	if e := s.nativeManagedTaskAction(ctx, ep, &foreignInvocation, command, action, false); e == nil {
		t.Fatal("foreign invocation could target this Attempt")
	}
	sweeper := &controller.RuntimeControlSweeper{Store: st, ReconcileManagedAttempt: s.ReconcileManagedAttempt}
	sweeper.Sweep(ctx, time.Now().Add(24*time.Hour))
	current, _ := st.ExecutionAttempts().Get(ctx, bound.ID)
	if current.State != model.ExecutionRunning || current.Version != started.Version {
		t.Fatalf("long tool timed out at control plane: %+v", current)
	}
	if _, err = st.ExecutionAttempts().Cancel(ctx, current.ID, current.Version); err != nil {
		t.Fatal(err)
	}
	sweeper.Sweep(ctx, time.Now().Add(48*time.Hour))
	current, _ = st.ExecutionAttempts().Get(ctx, bound.ID)
	if current.State != model.ExecutionCancelRequested {
		t.Fatalf("accepted cancel input fabricated physical stop: %+v", current)
	}
	mu.Lock()
	turnStatus = "cancelled"
	mu.Unlock()
	sweeper.Sweep(ctx, time.Now().Add(49*time.Hour))
	current, _ = st.ExecutionAttempts().Get(ctx, bound.ID)
	if current.State != model.ExecutionCancelled {
		t.Fatalf("terminal native cancellation was not projected: %+v", current)
	}
	if ready, e := s.serviceExecutionsSettled(ctx, first.Task.OrchestrationRunID); e != nil || !ready {
		t.Fatalf("durable native cancellation not settled: %v %v", ready, e)
	}
	if ready, e := remote.managedAttemptSettled(ctx, first.Execution.ID); e != nil || !ready {
		t.Fatalf("private durable terminal barrier: %v %v", ready, e)
	}
	if e := s.nativeManagedTaskAction(ctx, ep, inv, command, action, true); !errors.Is(e, store.ErrConflict) {
		t.Fatalf("cancelled Attempt accepted a tool decision: %v", e)
	}
	second := newTask(true)
	if second.SessionID == first.SessionID {
		t.Fatal("Attempts share native Session")
	}
	if err = s.DispatchManagedAttemptAbort(ctx, first.Execution.ID); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	for _, sid := range archives {
		if sid != first.SessionID {
			t.Errorf("old cancellation targeted fresh Session: %s", sid)
		}
	}
	mu.Unlock()
	neverAdmitted := newTask(false)
	if raw := s.managedExecutionContextForSession(ctx, neverAdmitted.SessionID); len(raw) != 0 {
		t.Fatal("legacy Session acquired a Managed credential without native admission")
	}
	if _, err = st.ExecutionAttempts().Cancel(ctx, neverAdmitted.Execution.ID, neverAdmitted.Execution.Version); err != nil {
		t.Fatal(err)
	}
	if err = s.CancelManagedAttempt(ctx, neverAdmitted.Execution.ID); err != nil {
		t.Fatal(err)
	}
	if err = s.DispatchManagedAttempt(ctx, neverAdmitted.Execution.ID); err != nil {
		t.Fatal(err)
	}
	current, _ = st.ExecutionAttempts().Get(ctx, neverAdmitted.Execution.ID)
	if current.State != model.ExecutionCancelled {
		t.Fatalf("unadmitted cancellation was not settled: %+v", current)
	}
	fast := newTask(true)
	mu.Lock()
	turnStatus = "running"
	mu.Unlock()
	if err = s.DispatchManagedAttempt(ctx, fast.Execution.ID); err == nil {
		t.Fatal("fixture did not lose first fast response")
	}
	active, err := s.verifyActiveTaskToken(ctx, fast.TaskToken, fast.Task.ID)
	if err != nil {
		t.Fatal(err)
	}
	current, _ = st.ExecutionAttempts().Get(ctx, fast.Execution.ID)
	if current.TurnID != "one-native-turn" || current.State != model.ExecutionRunning {
		t.Fatalf("first MCP call could not repair its racing receipt: %+v", current)
	}
	inputIDs := []uuid.UUID{}
	for _, input := range active.Inputs {
		inputIDs = append(inputIDs, input.ID)
	}
	completed, _, err := st.Collaboration().CompleteAgentTaskWithComment(ctx, active.ID, store.TaskCompletion{
		ExpectedVersion: active.Version, AttemptID: current.ID, DispatchGeneration: current.DispatchGeneration,
		ProcessedInputIDs: inputIDs, Result: json.RawMessage(`{"answer":"delivered"}`)},
		&model.Comment{IssueID: active.IssueID, Author: model.Actor{Type: model.ActorAgent, Ref: agent.ID.String()}, Content: "delivered"}, nil)
	if err != nil || completed.Status != model.AgentTaskCompleted {
		t.Fatalf("fast task.complete required legacy start/heartbeat: %+v %v", completed, err)
	}
	if err = s.DispatchManagedAttempt(ctx, fast.Execution.ID); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	fastSubmissions := accepted[fast.SessionID]
	mu.Unlock()
	if fastSubmissions != 1 {
		t.Fatalf("fast completed Task was submitted again: %d", fastSubmissions)
	}
	// Exercise first resource creation through the actual Product directory and
	// configuration projection, rather than pre-registering its native Session.
	directory, err := pgx.Connect(ctx, u.String())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = directory.Close(context.Background()) })
	environment := uuid.NewString()
	if _, err = directory.Exec(ctx, `INSERT INTO cp.environments(environment_id,owner_id,name,type,config_json,created_at,updated_at)
		VALUES($1,'resource-owner','task fixture','self_hosted','{}',0,0)`, environment); err != nil {
		t.Fatal(err)
	}
	fresh := newTask(false)
	mu.Lock()
	needsRegistration = fresh.SessionID
	mu.Unlock()
	definition := json.RawMessage(fmt.Sprintf(`{"name":"worker","version":7,"defaultEnvironmentId":%q,"definitionFiles":{},"model":"test-model"}`, environment))
	frozen := &serviceapi.Contract{Endpoint: model.Endpoint{ID: uuid.New(), Tenant: agent.Tenant, Namespace: agent.Namespace, TargetType: model.EndpointTargetAgent, TargetRef: agent.ID},
		EnvironmentID: environment, Agents: []string{agent.ID.String()}, Definitions: map[string]json.RawMessage{agent.ID.String(): definition},
		Policies: map[string]*model.AgentRuntimePolicy{agent.ID.String(): {Candidates: []model.RuntimeBindingCandidate{{Binding: runtime}}}}}
	admitContext := serviceapi.WithContract(ctx, frozen)
	if err = s.DispatchManagedAttempt(admitContext, fresh.Execution.ID); err == nil {
		t.Fatal("fixture did not lose first fresh admission response")
	}
	if err = s.DispatchManagedAttempt(admitContext, fresh.Execution.ID); err != nil {
		t.Fatal(err)
	}
	native, err := sessionapi.Get(ctx, st, agent.Tenant, uuid.MustParse(fresh.SessionID))
	if err != nil || native.TaskAttemptID == nil || *native.TaskAttemptID != fresh.Execution.ID || native.Principal != "platform-user:original-human" || native.Target.Version != 7 {
		t.Fatalf("first task resource creation: %+v %v", native, err)
	}
	mu.Lock()
	admissions := append([]runtimeprotocol.RuntimeSessionAdmission(nil), provisioned...)
	mu.Unlock()
	if len(admissions) != 1 {
		t.Fatalf("retry registered multiple configurations: %d", len(admissions))
	}
	var sealed struct {
		Session struct {
			ID            string `json:"id"`
			ExternalKey   string `json:"externalKey"`
			EnvironmentID string `json:"environmentId"`
		} `json:"session"`
		Execution struct {
			AttemptID string `json:"attemptId"`
			Task      struct {
				TaskToken string `json:"taskToken"`
			} `json:"taskContext"`
		} `json:"executionContext"`
	}
	if err = json.Unmarshal(admissions[0].Bootstrap.Configuration, &sealed); err != nil || sealed.Session.ID != fresh.SessionID || sealed.Session.EnvironmentID != environment || sealed.Execution.AttemptID != fresh.Execution.ID.String() || sealed.Execution.Task.TaskToken == "" || sealed.Session.ExternalKey != "agent-task|"+fresh.Task.ID.String() {
		t.Fatalf("private task configuration: %+v %v", sealed, err)
	}
	capability := sealed.Execution.Task.TaskToken
	claims, err := s.taskTokens.VerifyClaims(capability, time.Now().Add(72*time.Hour))
	if err != nil || claims.ManagedSessionID.String() != fresh.SessionID {
		t.Fatalf("native credential expired with its frozen configuration: %+v %v", claims, err)
	}
	if _, err = s.verifyActiveTaskToken(ctx, capability, fresh.Task.ID); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	inputsOpen = false
	mu.Unlock()
	if _, err = s.verifyActiveTaskToken(ctx, capability, fresh.Task.ID); err == nil {
		t.Fatal("closed native input gate retained task execution authority")
	}
	mu.Lock()
	inputsOpen = true
	mu.Unlock()
	foreign, err := s.taskTokens.MintManaged(fresh.Task.ID, fresh.Execution.ID, fresh.Execution.DispatchGeneration, uuid.New())
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.verifyActiveTaskToken(ctx, foreign, fresh.Task.ID); err == nil {
		t.Fatal("credential for a foreign native Session was accepted")
	}
	liveBinding, err := st.AgentCatalog().GetBinding(ctx, binding.ID)
	if err != nil {
		t.Fatal(err)
	}
	liveBinding.Enabled = false
	liveBinding, err = st.AgentCatalog().UpdateBinding(ctx, liveBinding, liveBinding.Version)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.verifyActiveTaskToken(ctx, capability, fresh.Task.ID); err == nil {
		t.Fatal("disabled runtime binding retained native authority")
	}
	liveBinding.Enabled = true
	if _, err = st.AgentCatalog().UpdateBinding(ctx, liveBinding, liveBinding.Version); err != nil {
		t.Fatal(err)
	}
	current, _ = st.ExecutionAttempts().Get(ctx, fresh.Execution.ID)
	if _, err = st.ExecutionAttempts().Cancel(ctx, current.ID, current.Version); err != nil {
		t.Fatal(err)
	}
	if _, err = s.verifyActiveTaskToken(ctx, capability, fresh.Task.ID); err == nil {
		t.Fatal("cancel_requested did not revoke native credential")
	}
	view, _ := json.Marshal(admissions[0].PublicView)
	if strings.Contains(string(view), sealed.Execution.Task.TaskToken) || strings.Contains(string(view), "executionContext") {
		t.Fatal("private task credentials leaked into public admission view")
	}
	var legacyRows int
	if err = directory.QueryRow(ctx, "SELECT COUNT(*) FROM cp.sessions").Scan(&legacyRows); err != nil || legacyRows != 0 {
		t.Fatalf("native task created a legacy cp Session: rows=%d err=%v", legacyRows, err)
	}
	var markers int
	if err = directory.QueryRow(ctx, `SELECT count(*) FROM information_schema.columns WHERE table_schema='cp' AND table_name='sessions' AND column_name IN ('runtime_agent_task_id','runtime_attempt_id','runtime_dispatch_generation','runtime_turn_id')`).Scan(&markers); err != nil || markers != 0 {
		t.Fatalf("legacy physical markers retained: %d %v", markers, err)
	}
	// A business deadline requests native stop; elapsed time is not stop proof.
	timed := newTask(true)
	timedSession, err := sessionapi.Get(ctx, st, agent.Tenant, uuid.MustParse(timed.SessionID))
	if err != nil {
		t.Fatal(err)
	}
	timedContract, err := serviceapi.ReadContract(timedSession.Contract)
	if err != nil {
		t.Fatal(err)
	}
	timedContract.Policies[agent.ID.String()].AttemptTimeoutSeconds = 1
	timedSession.Contract, _ = json.Marshal(timedContract)
	if err = sessionapi.Save(ctx, st, timedSession, false); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	turnStatus = "running"
	mu.Unlock()
	if err = s.DispatchManagedAttempt(ctx, timed.Execution.ID); err == nil {
		t.Fatal("first timeout fixture response was not lost")
	}
	if _, err = s.verifyActiveTaskToken(ctx, timed.TaskToken, timed.Task.ID); err != nil {
		t.Fatal(err)
	}
	timedAttempt, _ := st.ExecutionAttempts().Get(ctx, timed.Execution.ID)
	time.Sleep(time.Until(timedAttempt.StartedAt.Add(1100 * time.Millisecond)))
	if err = s.ReconcileManagedAttempt(ctx, timed.Execution.ID); err != nil {
		t.Fatal(err)
	}
	timedAttempt, _ = st.ExecutionAttempts().Get(ctx, timed.Execution.ID)
	timedTask, _ := st.Collaboration().GetAgentTask(ctx, timed.Task.ID)
	if timedAttempt.State != model.ExecutionCancelRequested || timedTask.Status != model.AgentTaskRunning {
		t.Fatalf("deadline fabricated execution stop: %+v %+v", timedAttempt, timedTask)
	}
	mu.Lock()
	turnStatus = "cancelled"
	mu.Unlock()
	if err = s.ReconcileManagedAttempt(ctx, timed.Execution.ID); err != nil {
		t.Fatal(err)
	}
	timedAttempt, _ = st.ExecutionAttempts().Get(ctx, timed.Execution.ID)
	timedTask, _ = st.Collaboration().GetAgentTask(ctx, timed.Task.ID)
	if timedAttempt.State != model.ExecutionFailed || timedTask.Status != model.AgentTaskFailed || timedAttempt.FailureCode != "attempt_timeout" || timedTask.ErrorCode != "attempt_timeout" || timedAttempt.HeartbeatAt != nil {
		t.Fatalf("native timeout proof was not applied: %+v %+v", timedAttempt, timedTask)
	}
	// An admitted identity cannot be declared stopped just because metadata is missing.
	lostMetadata := newTask(false)
	boundMetadata, err := st.ExecutionAttempts().BindManagedTurn(ctx, store.ManagedTurnBinding{AttemptID: lostMetadata.Execution.ID,
		DispatchGeneration: lostMetadata.Execution.DispatchGeneration, SessionID: lostMetadata.SessionID, TurnID: "already-admitted-turn"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.ExecutionAttempts().Cancel(ctx, boundMetadata.ID, boundMetadata.Version); err != nil {
		t.Fatal(err)
	}
	if err = s.CancelManagedAttempt(ctx, boundMetadata.ID); err == nil {
		t.Fatal("missing metadata was treated as physical stop proof")
	}
	boundMetadata, _ = st.ExecutionAttempts().Get(ctx, boundMetadata.ID)
	if boundMetadata.State != model.ExecutionCancelRequested {
		t.Fatalf("missing metadata settled a live execution: %+v", boundMetadata)
	}
	// Commit task.complete, discard that HTTP reply, then replay without mutation.
	receiptTask := newTask(true)
	mu.Lock()
	turnStatus = "running"
	mu.Unlock()
	if err = s.DispatchManagedAttempt(ctx, receiptTask.Execution.ID); err == nil {
		t.Fatal("fixture did not lose receipt-task admission response")
	}
	receiptToken, err := s.taskTokens.MintManaged(receiptTask.Task.ID, receiptTask.Execution.ID, receiptTask.Execution.DispatchGeneration, uuid.MustParse(receiptTask.SessionID))
	if err != nil {
		t.Fatal(err)
	}
	mcp := func(name string, arguments map[string]any) *httptest.ResponseRecorder {
		t.Helper()
		raw, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": map[string]any{"name": name, "arguments": arguments}})
		request := httptest.NewRequest(http.MethodPost, "/mcp/collaboration", bytes.NewReader(raw))
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("X-Agent-Task-Token", receiptToken)
		response := httptest.NewRecorder()
		s.router.ServeHTTP(response, request)
		return response
	}
	firstReceipt := mcp("task.complete", map[string]any{"outcome": "succeeded", "result": "original committed deliverable"})
	if firstReceipt.Code != 200 || strings.Contains(firstReceipt.Body.String(), `"isError":true`) {
		t.Fatalf("initial completion failed: %d %s", firstReceipt.Code, firstReceipt.Body.String())
	}
	committed, _ := st.Collaboration().GetAgentTask(ctx, receiptTask.Task.ID)
	if committed.Status != model.AgentTaskCompleted {
		t.Fatalf("completion did not commit: %+v", committed)
	}
	committedComments, err := st.Collaboration().ListComments(ctx, committed.IssueID, store.CommentListOptions{Limit: 100})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.verifyActiveTaskToken(ctx, receiptToken, committed.ID); err == nil {
		t.Fatal("completed task retained ordinary work authority")
	}
	replayed := mcp("task.complete", map[string]any{"outcome": "succeeded", "result": "must never replace committed result"})
	if replayed.Code != 200 || !strings.Contains(replayed.Body.String(), `"replayed":true`) || strings.Contains(replayed.Body.String(), "must never replace") {
		t.Fatalf("lost completion reply could not be replayed: %d %s", replayed.Code, replayed.Body.String())
	}
	unchanged, _ := st.Collaboration().GetAgentTask(ctx, committed.ID)
	if unchanged.Version != committed.Version || string(unchanged.Result) != string(committed.Result) {
		t.Fatalf("receipt replay mutated task: %+v", unchanged)
	}
	deniedWrite := mcp("issue.comment.add", map[string]any{"content": "unauthorized new work"})
	if deniedWrite.Code != 200 || !strings.Contains(deniedWrite.Body.String(), `"isError":true`) {
		t.Fatalf("terminal receipt scope authorized new mutation: %d %s", deniedWrite.Code, deniedWrite.Body.String())
	}
	finalComments, err := st.Collaboration().ListComments(ctx, committed.IssueID, store.CommentListOptions{Limit: 100})
	if err != nil || len(finalComments) != len(committedComments) {
		t.Fatalf("receipt retry or rejected write added comments: %d -> %d %v", len(committedComments), len(finalComments), err)
	}
	mu.Lock()
	inputsOpen = false
	mu.Unlock()
	closed := mcp("task.complete", map[string]any{"outcome": "succeeded"})
	if closed.Code != http.StatusUnauthorized {
		t.Fatalf("closed native Turn retained receipt authority: %d %s", closed.Code, closed.Body.String())
	}
}

func TestManagedDeadlineUsesFrozenPolicyAndStartedTime(t *testing.T) {
	now := time.Now()
	started := now.Add(-time.Minute)
	agent := uuid.New()
	attempt := &model.ExecutionAttempt{AgentID: agent, StartedAt: &started}
	contract := &serviceapi.Contract{Policies: map[string]*model.AgentRuntimePolicy{agent.String(): {AttemptTimeoutSeconds: 60}}}
	if managedAttemptExpired(attempt, contract, now.Add(-time.Nanosecond)) || !managedAttemptExpired(attempt, contract, now) {
		t.Fatal("deadline boundary is not based on start plus frozen policy")
	}
	attempt.StartedAt = nil
	if managedAttemptExpired(attempt, contract, now.Add(24*time.Hour)) {
		t.Fatal("queue wait was treated as runtime execution")
	}
}

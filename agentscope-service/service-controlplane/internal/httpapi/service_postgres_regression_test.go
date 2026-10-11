/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controller"
	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimebinding"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

func servicePostgresReplicas(t *testing.T) [2]store.Store {
	t.Helper()
	cfg := acceptancePostgresConfig(t)
	// Admission holds two nested advisory locks. A one-connection query pool
	// must still make progress because service locks use separate connections.
	cfg.MaxOpenConns = 1
	var replicas [2]store.Store
	for i := range replicas {
		st, err := store.Open(t.Context(), cfg)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = st.Close() })
		replicas[i] = st
	}
	return replicas
}

func servicePostgresEndpoint(t *testing.T, st store.Store, name string) *model.Endpoint {
	t.Helper()
	ep, err := st.Endpoints().Create(t.Context(), &model.Endpoint{Tenant: "t", Namespace: "n", Name: name, Slug: name, TargetRef: uuid.New(), TargetType: model.EndpointTargetAgent, InvocationMode: model.EndpointJobMode, Status: model.EndpointPublished, AuthPolicy: json.RawMessage(`{"type":"platform"}`)})
	if err != nil {
		t.Fatal(err)
	}
	return ep
}

func TestServicePostgresReplicaAdmissionAccountingAndWakeup(t *testing.T) {
	replicas := servicePostgresReplicas(t)
	ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
	defer cancel()
	app, err := replicas[0].Applications().Create(ctx, &model.Application{Tenant: "t", Namespace: "n", Name: "client", OwnerUserID: "owner", Status: "active", MaxConcurrent: 1, TokenBudget: 100})
	if err != nil {
		t.Fatal(err)
	}
	eps := []*model.Endpoint{servicePostgresEndpoint(t, replicas[0], "first"), servicePostgresEndpoint(t, replicas[1], "second")}
	servers := []*Server{newLegacyEndpointTestServer(ServerOptions{Store: replicas[0]}), newLegacyEndpointTestServer(ServerOptions{Store: replicas[1]})}
	type admission struct {
		inv   *model.EndpointInvocation
		fresh bool
		err   error
	}
	results := make(chan admission, 12)
	start := make(chan struct{})
	for i := range 12 {
		go func() {
			<-start
			ep := eps[i%2]
			inv, fresh, err := servers[i%2].reserveServiceInvocation(ctx, ep, &model.EndpointInvocation{EndpointID: ep.ID, ApplicationID: &app.ID, Mode: model.EndpointJobMode, PrincipalRef: "application:" + app.ID.String(), IdempotencyKey: "same", Input: json.RawMessage(`{"input":{}}`)})
			results <- admission{inv, fresh, err}
		}()
	}
	close(start)
	var accepted *model.EndpointInvocation
	fresh, replay, limited := 0, 0, 0
	for range 12 {
		result := <-results
		if errors.Is(result.err, errServiceConcurrency) {
			limited++
			continue
		}
		if result.err != nil {
			t.Fatal(result.err)
		}
		if accepted != nil && accepted.ID != result.inv.ID {
			t.Fatal("two endpoints bypassed the shared application limit")
		}
		accepted = result.inv
		if result.fresh {
			fresh++
		} else {
			replay++
		}
	}
	if fresh != 1 || replay != 5 || limited != 6 {
		t.Fatalf("fresh=%d replay=%d limited=%d", fresh, replay, limited)
	}
	usageErrors := make(chan error, 8)
	for i, n := range []int64{10, 60, 40, 100, 100, 20, 60, 100} {
		go func() { usageErrors <- replicas[i%2].Endpoints().RecordInvocationTokens(ctx, accepted.ID, n) }()
	}
	for range 8 {
		if err := <-usageErrors; err != nil {
			t.Fatal(err)
		}
	}
	app, err = replicas[1].Applications().Get(ctx, app.ID)
	if err != nil || app.TokensUsed != 100 {
		t.Fatalf("concurrent usage double counted: %+v %v", app, err)
	}
	accepted.Status = model.EndpointInvocationPartialSucceeded
	accepted, err = replicas[1].Endpoints().UpdateInvocation(ctx, accepted)
	if err != nil || accepted.ConsumedTokens != 100 {
		t.Fatalf("stale update overwrote accounting: %+v %v", accepted, err)
	}
	if n, err := replicas[0].Endpoints().CountActiveInvocations(ctx, store.EndpointInvocationFilter{ApplicationID: app.ID}); err != nil || n != 0 {
		t.Fatalf("partial success remained active: %d %v", n, err)
	}
	if _, _, err = servers[1].reserveServiceInvocation(ctx, eps[1], &model.EndpointInvocation{EndpointID: eps[1].ID, ApplicationID: &app.ID, Mode: model.EndpointJobMode, PrincipalRef: "application:" + app.ID.String(), IdempotencyKey: "new", Input: json.RawMessage(`{"input":{}}`)}); !errors.Is(err, errApplicationBudget) {
		t.Fatalf("cross-endpoint budget bypass: %v", err)
	}
	lease := time.Now().Add(time.Minute).UTC().Truncate(time.Microsecond)
	claims := make(chan bool, 2)
	for i := range 2 {
		go func() {
			ok, err := replicas[i].Endpoints().ClaimInvocationPoll(ctx, accepted.ID, accepted.NextPollAt, lease)
			if err != nil {
				t.Error(err)
			}
			claims <- ok
		}()
	}
	if first, second := <-claims, <-claims; first == second {
		t.Fatalf("poll CAS must have exactly one winner: %v %v", first, second)
	}
	wake := time.Now().UTC().Truncate(time.Microsecond)
	if err = replicas[1].Endpoints().ScheduleInvocation(ctx, accepted.ID, wake); err != nil {
		t.Fatal(err)
	}
	if ok, err := replicas[0].Endpoints().ClaimInvocationPoll(ctx, accepted.ID, lease, lease.Add(time.Hour)); err != nil || ok {
		t.Fatalf("stale poll completion erased wakeup: %v %v", ok, err)
	}
	due, err := replicas[0].Endpoints().ListInvocations(ctx, store.EndpointInvocationFilter{DueBefore: &wake})
	if err != nil || len(due) != 1 || due[0].ID != accepted.ID || !due[0].NextPollAt.Equal(wake) {
		t.Fatalf("woken invocation missing from due queue: %+v %v", due, err)
	}
}

type failServiceKV struct {
	store.KVRepository
	key string
}

func (f *failServiceKV) Put(ctx context.Context, tenant, path, key string, value json.RawMessage) (int64, error) {
	if key == f.key {
		f.key = ""
		return 0, errors.New("injected durable write failure")
	}
	return f.KVRepository.Put(ctx, tenant, path, key, value)
}

type failServiceStore struct {
	store.Store
	kv store.KVRepository
}

func (f failServiceStore) KV() store.KVRepository { return f.kv }

func TestServicePostgresJournalReplicaReplayRetentionAndWriteRecovery(t *testing.T) {
	replicas := servicePostgresReplicas(t)
	ctx, cancel := context.WithTimeout(t.Context(), 90*time.Second)
	defer cancel()
	id := uuid.New()
	journals := [2]serviceapi.Journal{{Store: replicas[0], Tenant: "t", InvocationID: id}, {Store: replicas[1], Tenant: "t", InvocationID: id}}
	const count = 1005
	var wg sync.WaitGroup
	for worker := range 3 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for n := worker; n < count; n += 3 {
				if _, err := journals[worker%2].Append(ctx, fmt.Sprint(n), "item.delta", map[string]any{"item_id": "response", "content": []any{map[string]any{"type": "text", "text": "x"}}}); err != nil {
					t.Error(err)
					return
				}
			}
		}()
	}
	wg.Wait()
	if t.Failed() {
		return
	}
	// The head write fails after the event and source receipt exist. A different
	// replica commits another source at that position before the sender retries.
	fault := &failServiceKV{KVRepository: replicas[0].KV(), key: "head"}
	j := journals[0]
	j.Store = failServiceStore{Store: replicas[0], kv: fault}
	if _, err := j.Append(ctx, "interrupted", "step.updated", map[string]any{"step_id": "interrupted"}); err == nil {
		t.Fatal("failure injection did not run")
	}
	if _, err := journals[1].Append(ctx, "other", "step.updated", map[string]any{"step_id": "other"}); err != nil {
		t.Fatal(err)
	}
	if event, err := journals[1].Append(ctx, "interrupted", "step.updated", map[string]any{"step_id": "interrupted"}); err != nil || event.Sequence != count+2 {
		t.Fatalf("uncommitted source aliased a committed event: %+v %v", event, err)
	}
	cursor, total := "", 0
	seen := map[string]bool{}
	for {
		events, next, more, err := journals[1].Events(ctx, cursor, 61)
		if err != nil {
			t.Fatal(err)
		}
		for _, event := range events {
			if event.Sequence != int64(total+1) || seen[event.ID] {
				t.Fatalf("duplicate or missing replay event: %+v", event)
			}
			seen[event.ID] = true
			total++
		}
		cursor = next
		if !more {
			break
		}
	}
	if total != count+2 {
		t.Fatalf("replay count=%d", total)
	}
	snapshot, err := journals[1].Snapshot(ctx)
	if err != nil || snapshot.AsOf != cursor {
		t.Fatalf("snapshot cursor: %s %v", snapshot.AsOf, err)
	}
	raw, _ := json.Marshal(snapshot.Items["response"])
	if !bytes.Contains(raw, []byte(strings.Repeat("x", count))) {
		t.Fatal("checkpoint lost delta prefixes")
	}
	fault.key = "snapshot"
	if _, err := j.PruneEvents(ctx); err == nil {
		t.Fatal("failed snapshot should prevent pruning")
	}
	if _, _, _, err := journals[1].Events(ctx, "", 1); err != nil {
		t.Fatalf("failed prune expired readable history: %v", err)
	}
	if done, err := journals[1].PruneEvents(ctx); err != nil || done {
		t.Fatalf("first retention batch: %v %v", done, err)
	}
	if done, err := journals[0].PruneEvents(ctx); err != nil || !done {
		t.Fatalf("resumed retention batch: %v %v", done, err)
	}
	if _, _, _, err := journals[1].Events(ctx, "", 1); !errors.Is(err, serviceapi.ErrCursorExpired) {
		t.Fatalf("expired cursor accepted: %v", err)
	}
	if event, err := journals[0].Append(ctx, "0", "item.delta", nil); err != nil || event.Sequence > count {
		t.Fatalf("retention discarded dedup receipt: %+v %v", event, err)
	}
	if retained, err := journals[0].Snapshot(ctx); err != nil || retained.AsOf != snapshot.AsOf {
		t.Fatalf("snapshot did not survive retention: %+v %v", retained, err)
	}
	if _, err := journals[1].Append(ctx, "suffix", "step.updated", map[string]any{"step_id": "suffix"}); err != nil {
		t.Fatal(err)
	}
	if events, _, more, err := journals[0].Events(ctx, snapshot.AsOf, 10); err != nil || more || len(events) != 1 {
		t.Fatalf("snapshot reconnect suffix: %+v %v %v", events, more, err)
	}
}

func TestServicePostgresCommandSurvivesReplicaRestartBeforeDispatch(t *testing.T) {
	replicas := servicePostgresReplicas(t)
	ctx := t.Context()
	ep := servicePostgresEndpoint(t, replicas[0], "commands")
	contract, err := serviceapi.Freeze(ctx, replicas[0], ep)
	if err != nil {
		t.Fatal(err)
	}
	inv, _, err := replicas[0].Endpoints().ReserveInvocation(ctx, &model.EndpointInvocation{EndpointID: ep.ID, Mode: model.EndpointJobMode, PrincipalRef: "platform-static", IdempotencyKey: "job", Contract: contract, Input: json.RawMessage(`{"title":"hello","input":{}}`)})
	if err != nil {
		t.Fatal(err)
	}
	s := newLegacyEndpointTestServer(ServerOptions{Store: replicas[0], AuthToken: "key"})
	request := httptest.NewRequest("POST", "/invoke/v1/invocations/"+inv.ID.String()+"/cancel", strings.NewReader(`{}`))
	request.Header.Set("Authorization", "Bearer key")
	request.Header.Set("Idempotency-Key", "cancel")
	request.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, request)
	if w.Code != 202 {
		t.Fatalf("cancel admission: %d %s", w.Code, w.Body)
	}
	_ = replicas[0].Close()
	restarted := newLegacyEndpointTestServer(ServerOptions{Store: replicas[1], AuthToken: "key"})
	if err = restarted.SweepServiceInvocations(ctx); err != nil {
		t.Fatal(err)
	}
	got, err := replicas[1].Endpoints().GetInvocation(ctx, inv.ID)
	if err != nil || got.Status != model.EndpointInvocationCancelled || got.RunID != nil {
		t.Fatalf("cancelled invocation dispatched after restart: %+v %v", got, err)
	}
	snapshot, err := serviceJournal(replicas[1], ep, got).Snapshot(ctx)
	if err != nil || snapshot.Invocation["status"] != "cancelled" {
		t.Fatalf("durable terminal projection: %+v %v", snapshot, err)
	}
}

type capabilityDispatchRecorder struct{ instance string }

func (r *capabilityDispatchRecorder) SendExecutionAttemptCommand(_, _, _, instance, _, _ string, _ []byte) error {
	r.instance = instance
	return nil
}

func TestServicePostgresASDPCapabilityPolicySelectsRegisteredInstance(t *testing.T) {
	replicas := servicePostgresReplicas(t)
	ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
	defer cancel()
	s := newLegacyEndpointTestServer(ServerOptions{Store: replicas[0]})
	req := httptest.NewRequest("POST", "/api/v1/agent-registrations", strings.NewReader(`{"tenant":"t","namespace":"n","agentKey":"cap-worker","instanceKey":"one","capacity":2,"capabilities":["execute-task"]}`))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("registration: %d %s", w.Code, w.Body)
	}
	var registered struct {
		Agent    model.Agent         `json:"agent"`
		Binding  model.AgentBinding  `json:"binding"`
		Instance model.AgentInstance `json:"instance"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &registered); err != nil {
		t.Fatal(err)
	}
	required := json.RawMessage(`{"execute-task":true}`)
	if !model.JSONContains(registered.Instance.Capabilities, required) {
		t.Fatalf("registration must persist capability flags: %s", registered.Instance.Capabilities)
	}
	sink := &controller.SessionEventSink{Store: replicas[1]}
	sink.ApplyInstanceConnect(ctx, "t", "n", registered.Agent.ID.String(), registered.Binding.ID.String(), registered.Agent.AgentKey, registered.Instance.InstanceKey, registered.Instance.Generation, "python", "test", []string{"execute-task", "session-abort"})
	observed, err := replicas[0].RuntimeRegistry().GetAgentInstance(ctx, registered.Instance.ID)
	if err != nil || !model.JSONContains(observed.Capabilities, required) {
		t.Fatalf("ASDP connect replaced flags with incompatible representation: %+v %v", observed, err)
	}
	if !serviceBindingCapabilities(&model.Endpoint{InvocationMode: model.EndpointConversationMode}, model.DataPlaneExternalApplication, observed.Capabilities)["cancel"] {
		t.Fatal("selected instance cancellation capability was lost")
	}
	issue, err := replicas[0].Collaboration().CreateIssue(ctx, &model.Issue{Tenant: "t", Namespace: "n", Title: "capability selection", Creator: model.Actor{Type: model.ActorHuman, Ref: "owner"}, AssigneeType: model.AssigneeAgent, AssigneeRef: registered.Agent.ID.String()})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := replicas[0].Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("task setup: %+v %v", tasks, err)
	}
	binding, err := registered.Binding.RuntimeBinding()
	if err != nil {
		t.Fatal(err)
	}
	recorder := &capabilityDispatchRecorder{}
	resolver := &runtimebinding.Resolver{Store: replicas[1], External: recorder}
	candidate := &model.RuntimeBindingCandidate{Binding: binding, RequiredCapabilities: json.RawMessage(`{"missing":true}`)}
	if _, err = resolver.DispatchCandidate(ctx, tasks[0].ID, candidate); err == nil {
		t.Fatal("instance lacking the required capability was selected")
	}
	candidate.RequiredCapabilities = required
	result, err := resolver.DispatchCandidate(ctx, tasks[0].ID, candidate)
	if err != nil || result.AgentInstanceID == nil || *result.AgentInstanceID != observed.ID || recorder.instance != observed.InstanceKey {
		t.Fatalf("capability policy could not select ASDP instance: %+v %v", result, err)
	}
	if !model.JSONContains(result.Execution.RequiredCapabilities, required) {
		t.Fatal("execution lost frozen required capabilities")
	}
}

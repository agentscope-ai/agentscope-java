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
	"net/http/httptest"
	"testing"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	_ "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store/memory"
	"github.com/google/uuid"
)

func TestServiceInvocationCancellationIsDurableAndIdempotent(t *testing.T) {
	ctx := context.Background()
	st, _ := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	defer st.Close()
	ep, err := st.Endpoints().Create(ctx, &model.Endpoint{Tenant: "t", Namespace: "n", Slug: "service", Name: "service", TargetType: model.EndpointTargetAgent, TargetRef: uuid.New(), InvocationMode: model.EndpointJobMode, Status: model.EndpointPublished, AuthPolicy: json.RawMessage(`{"type":"platform"}`)})
	if err != nil {
		t.Fatal(err)
	}
	contract, err := serviceapi.Freeze(ctx, st, ep)
	if err != nil {
		t.Fatal(err)
	}
	inv, _, err := st.Endpoints().ReserveInvocation(ctx, &model.EndpointInvocation{EndpointID: ep.ID, Mode: model.EndpointJobMode, PrincipalRef: "platform-static", IdempotencyKey: "job", Status: model.EndpointInvocationAccepted, Contract: contract, Input: json.RawMessage(`{"title":"hello","input":{}}`)})
	if err != nil {
		t.Fatal(err)
	}
	server := newLegacyEndpointTestServer(ServerOptions{Store: st, AuthToken: "key"})
	submit := func(body string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", "/invoke/v1/invocations/"+inv.ID.String()+"/cancel", bytes.NewBufferString(body))
		r.Header.Set("Authorization", "Bearer key")
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Idempotency-Key", "cancel-1")
		w := httptest.NewRecorder()
		server.router.ServeHTTP(w, r)
		return w
	}
	first, second := submit(`{}`), submit(`{}`)
	if first.Code != 202 || second.Code != 202 {
		t.Fatalf("command submission: %s / %s", first.Body, second.Body)
	}
	if other := submit(`{"message":"changed"}`); other.Code != 409 {
		t.Fatalf("different replay accepted: %s", other.Body)
	}
	// A fresh server process can drain the durable command before materializing a Run.
	restarted := newLegacyEndpointTestServer(ServerOptions{Store: st, AuthToken: "key"})
	if err = restarted.SweepServiceInvocations(ctx); err != nil {
		t.Fatal(err)
	}
	result, _ := st.Endpoints().GetInvocation(ctx, inv.ID)
	if result.Status != model.EndpointInvocationCancelled || result.RunID != nil {
		t.Fatalf("cancelled work dispatched: %+v", result)
	}
	r := httptest.NewRequest("GET", "/invoke/v1/invocations/"+inv.ID.String()+"/snapshot", nil)
	r.Header.Set("Authorization", "Bearer key")
	w := httptest.NewRecorder()
	restarted.router.ServeHTTP(w, r)
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	var snapshot serviceapi.Snapshot
	if err = json.Unmarshal(w.Body.Bytes(), &snapshot); err != nil {
		t.Fatal(err)
	}
	if snapshot.Invocation["status"] != "cancelled" || snapshot.AsOf == "" {
		t.Fatalf("snapshot=%s", w.Body)
	}
}
func TestServiceOutputUsesPublishedSchema(t *testing.T) {
	ctx := context.Background()
	st, _ := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	defer st.Close()
	ep, _ := st.Endpoints().Create(ctx, &model.Endpoint{Tenant: "t", Namespace: "n", Name: "service", Slug: "service", TargetRef: uuid.New(), TargetType: model.EndpointTargetAgent, OutputSchema: json.RawMessage(`{"type":"object","required":["answer"]}`)})
	frozen, err := serviceapi.Freeze(ctx, st, ep)
	if err != nil {
		t.Fatal(err)
	}
	run, err := st.Orchestration().CreateRun(ctx, &model.OrchestrationRun{Tenant: "t", Namespace: "n", RootIssueID: uuid.New(), State: model.RunSucceeded, Mode: model.RunModeDirect, Output: json.RawMessage(`{"wrong":true}`)})
	if err != nil {
		t.Fatal(err)
	}
	inv, _, err := st.Endpoints().ReserveInvocation(ctx, &model.EndpointInvocation{EndpointID: ep.ID, RunID: &run.ID, Contract: frozen, Mode: model.EndpointJobMode, PrincipalRef: "p", IdempotencyKey: "k", Status: model.EndpointInvocationRunning})
	if err != nil {
		t.Fatal(err)
	}
	ep.OutputSchema = json.RawMessage(`{}`)
	_, _ = st.Endpoints().Update(ctx, ep, ep.Version)
	server := newLegacyEndpointTestServer(ServerOptions{Store: st})
	got, err := server.refreshServiceInvocation(ctx, inv.ID)
	if err != nil {
		t.Fatal(err)
	}
	if got.Status != model.EndpointInvocationFailed || got.ErrorCode != "output_schema_violation" {
		t.Fatalf("schema drift: %+v", got)
	}
}

func TestServiceReplaySurvivesInputSchemaChange(t *testing.T) {
	ctx := context.Background()
	st, _ := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	defer st.Close()
	ep, _ := st.Endpoints().Create(ctx, &model.Endpoint{Tenant: "t", Namespace: "n", Name: "service", Slug: "service", InputSchema: json.RawMessage(`{"type":"string"}`)})
	server := newLegacyEndpointTestServer(ServerOptions{Store: st})
	request := &model.EndpointInvocation{EndpointID: ep.ID, Mode: model.EndpointJobMode, PrincipalRef: "p", IdempotencyKey: "job", Status: model.EndpointInvocationAccepted, Input: json.RawMessage(`{"input":"hello"}`)}
	first, fresh, err := server.reserveServiceInvocation(ctx, ep, request)
	if err != nil || !fresh {
		t.Fatalf("reserve: fresh=%v, err=%v", fresh, err)
	}
	ep.InputSchema = json.RawMessage(`{"type":"integer"}`)
	again, fresh, err := server.reserveServiceInvocation(ctx, ep, request)
	if err != nil || fresh || again.ID != first.ID {
		t.Fatalf("replay: fresh=%v, err=%v", fresh, err)
	}
	request.IdempotencyKey = "new-job"
	if _, _, err = server.reserveServiceInvocation(ctx, ep, request); !errors.Is(err, errServiceInput) {
		t.Fatalf("new request should enforce current schema: %v", err)
	}
}

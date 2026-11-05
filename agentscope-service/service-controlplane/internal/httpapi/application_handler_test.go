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
	"context"
	"encoding/json"
	"errors"
	"net/http/httptest"
	"testing"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
)

func TestApplicationCredentialsShareOwnerButKeepActor(t *testing.T) {
	ctx := context.Background()
	st, _ := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	defer st.Close()
	app, _ := st.Applications().Create(ctx, &model.Application{Tenant: "t", Namespace: "n", Name: "client", OwnerUserID: "owner", Status: "active"})
	ep, _ := st.Endpoints().Create(ctx, &model.Endpoint{Tenant: "t", Namespace: "n", Name: "api", Slug: "api", AuthPolicy: json.RawMessage(`{"type":"api_key"}`), Status: model.EndpointPublished})
	s := NewServer(ServerOptions{Store: st})
	var actors []model.Actor
	for _, name := range []string{"first", "second"} {
		credential, secret, err := s.buildEndpointCredential(ep.ID, name, json.RawMessage(`["invoke","read"]`), nil, nil)
		if err != nil {
			t.Fatal(err)
		}
		credential.ApplicationID = app.ID
		credential, err = st.Endpoints().CreateCredential(ctx, credential)
		if err != nil {
			t.Fatal(err)
		}
		c, _ := gin.CreateTestContext(httptest.NewRecorder())
		c.Request = httptest.NewRequest("GET", "/", nil)
		c.Request.Header.Set("X-API-Key", secret)
		if !s.authenticateEndpoint(c, ep, true) || c.GetString(endpointPrincipalContextKey) != "application:"+app.ID.String() {
			t.Fatal("Application owner identity was not authenticated")
		}
		actor := endpointActor(c)
		actors = append(actors, actor)
		if actor.Type != model.ActorAutomation || actor.Ref != "api-key:"+credential.ID.String() {
			t.Fatalf("wrong actor: %+v", actor)
		}
	}
	if actors[0] == actors[1] {
		t.Fatal("distinct credentials lost actor identity")
	}
	for _, scopes := range []string{`null`, `[]`, `["*"]`, `["read","read"]`} {
		if _, err := validateEndpointScopes(json.RawMessage(scopes)); err == nil {
			t.Fatalf("accepted scopes %s", scopes)
		}
	}
	legacy, secret, _ := s.buildEndpointCredential(ep.ID, "unbound", json.RawMessage(`["read"]`), nil, nil)
	_, _ = st.Endpoints().CreateCredential(ctx, legacy)
	c, _ := gin.CreateTestContext(httptest.NewRecorder())
	c.Request = httptest.NewRequest("GET", "/", nil)
	c.Request.Header.Set("X-API-Key", secret)
	if s.authenticateEndpoint(c, ep, false) {
		t.Fatal("unbound legacy key authenticated")
	}
}

func TestApplicationHumanAuthorizationCannotTakeManagedOwnership(t *testing.T) {
	ctx := context.Background()
	st, _ := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	defer st.Close()
	app, _ := st.Applications().Create(ctx, &model.Application{Tenant: "t", Namespace: "n", Name: "client", OwnerUserID: "app-owner", Status: "active", Members: []model.ApplicationMember{{UserID: "reviewer", Roles: []string{"approver"}}, {UserID: "viewer", Roles: []string{"viewer"}}}})
	s := NewServer(ServerOptions{Store: st})
	ep := &model.Endpoint{Tenant: "t", Namespace: "n"}
	inv := &model.EndpointInvocation{ApplicationID: &app.ID}
	if s.authorizeApplicationActor(ctx, inv, ep, "platform-user:stranger", "read") || s.authorizeApplicationActor(ctx, inv, ep, "platform-user:viewer", "approve") {
		t.Fatal("unauthorized member access")
	}
	if !s.authorizeApplicationActor(ctx, inv, ep, "platform-user:reviewer", "approve") {
		t.Fatal("designated app reviewer cannot access approval")
	}
	if s.nativeConfirmationAllowed(ctx, ep, inv, "managed-owner", "platform-user:reviewer", nil) {
		t.Fatal("application reviewer took another managed owner's authority")
	}
	if s.nativeConfirmationAllowed(ctx, ep, inv, "app-owner", "api-key:any", nil) {
		t.Fatal("API key impersonated human approver")
	}
	if !s.nativeConfirmationAllowed(ctx, ep, inv, "app-owner", "platform-user:reviewer", nil) {
		t.Fatal("managed owner delegation rejected")
	}
	if s.nativeConfirmationAllowed(ctx, ep, inv, "app-owner", "platform-user:reviewer", map[string]any{"approver_ref": "other"}) {
		t.Fatal("explicit approver assignment bypassed")
	}
	cmd := &serviceCommand{Kind: "actions", Actor: model.Actor{Type: model.ActorHuman, Ref: "reviewer"}, Principal: "platform-user:reviewer"}
	if !s.authorizeServiceCommandActor(ctx, inv, ep, cmd) {
		t.Fatal("authorized command rejected")
	}
	app.Members = nil
	_, _ = st.Applications().Update(ctx, app, app.Version)
	if s.authorizeServiceCommandActor(ctx, inv, ep, cmd) {
		t.Fatal("queued command survived membership revocation")
	}
}

func TestApplicationQuotaAndPollingAreSharedAndMonotonic(t *testing.T) {
	ctx := context.Background()
	st, _ := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	defer st.Close()
	app, _ := st.Applications().Create(ctx, &model.Application{Tenant: "t", Namespace: "n", Name: "client", OwnerUserID: "owner", Status: "active", MaxConcurrent: 1, TokenBudget: 100})
	first, _ := st.Endpoints().Create(ctx, &model.Endpoint{Tenant: "t", Namespace: "n", Name: "one", Slug: "one"})
	second, _ := st.Endpoints().Create(ctx, &model.Endpoint{Tenant: "t", Namespace: "n", Name: "two", Slug: "two"})
	s := NewServer(ServerOptions{Store: st})
	input := &model.EndpointInvocation{EndpointID: first.ID, ApplicationID: &app.ID, Mode: model.EndpointJobMode, PrincipalRef: "application:" + app.ID.String(), IdempotencyKey: "one", Input: json.RawMessage(`{"input":{}}`)}
	inv, fresh, err := s.reserveServiceInvocation(ctx, first, input)
	if err != nil || !fresh {
		t.Fatal(err)
	}
	if got, fresh, err := s.reserveServiceInvocation(ctx, first, input); err != nil || fresh || got.ID != inv.ID {
		t.Fatal("quota blocked idempotent replay")
	}
	input2 := *input
	input2.EndpointID = second.ID
	input2.IdempotencyKey = "two"
	if _, _, err = s.reserveServiceInvocation(ctx, second, &input2); !errors.Is(err, errServiceConcurrency) {
		t.Fatalf("cross-endpoint app concurrency ignored: %v", err)
	}
	for _, total := range []int64{60, 60, 40, 100} {
		if err = st.Endpoints().RecordInvocationTokens(ctx, inv.ID, total); err != nil {
			t.Fatal(err)
		}
	}
	app, _ = st.Applications().Get(ctx, app.ID)
	if app.TokensUsed != 100 {
		t.Fatalf("usage double counted: %d", app.TokensUsed)
	}
	inv.Status = model.EndpointInvocationCompleted
	inv, _ = st.Endpoints().UpdateInvocation(ctx, inv)
	if inv.ConsumedTokens != 100 {
		t.Fatal("stale projection overwrote token accounting")
	}
	if _, _, err = s.reserveServiceInvocation(ctx, second, &input2); !errors.Is(err, errApplicationBudget) {
		t.Fatalf("application budget ignored: %v", err)
	}
	lease := time.Now().Add(time.Minute).UTC().Truncate(time.Microsecond)
	if ok, err := st.Endpoints().ClaimInvocationPoll(ctx, inv.ID, inv.NextPollAt, lease); err != nil || !ok {
		t.Fatalf("claim: %v %v", ok, err)
	}
	wake := time.Now().UTC().Truncate(time.Microsecond)
	_ = st.Endpoints().ScheduleInvocation(ctx, inv.ID, wake)
	if ok, _ := st.Endpoints().ClaimInvocationPoll(ctx, inv.ID, lease, lease.Add(time.Minute)); ok {
		t.Fatal("poll completion lost command wakeup")
	}
	inv, _ = st.Endpoints().GetInvocation(ctx, inv.ID)
	if !inv.NextPollAt.Equal(wake) {
		t.Fatal("wake time changed")
	}
}

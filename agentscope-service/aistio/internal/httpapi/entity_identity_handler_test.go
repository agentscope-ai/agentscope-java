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
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestResolveEntityIdentitiesUsesNamesAndEnforcesScope(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	now := time.Now().UTC()
	agentID, teamID, endpointID, invocationID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	if _, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{
		ID: agentID, Tenant: "acme", Namespace: "eng", AgentKey: "reviewer", DisplayName: "Code Reviewer",
		Status: controlmodel.AgentActive, CreatedAt: now, UpdatedAt: now,
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		ID: teamID, Tenant: "acme", Namespace: "eng", Name: "Release Team", Status: controlmodel.TeamActive,
		LeaderAgentRef: agentID.String(), CreatedAt: now, UpdatedAt: now,
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := st.Endpoints().Create(ctx, &controlmodel.Endpoint{
		ID: endpointID, Tenant: "acme", Namespace: "eng", Name: "Review API", Slug: "review-api",
		TargetType: controlmodel.EndpointTargetAgent, TargetRef: agentID, InvocationMode: controlmodel.EndpointJobMode,
		Status: controlmodel.EndpointPublished, CreatedAt: now, UpdatedAt: now,
	}); err != nil {
		t.Fatal(err)
	}
	if _, _, err := st.Endpoints().ReserveInvocation(ctx, &controlmodel.EndpointInvocation{
		ID: invocationID, EndpointID: endpointID, Mode: controlmodel.EndpointJobMode,
		Status: controlmodel.EndpointInvocationAccepted, CreatedAt: now, UpdatedAt: now,
	}); err != nil {
		t.Fatal(err)
	}

	server := NewServer(ServerOptions{Store: st, AuthToken: "console-secret"})
	body := `{"refs":[{"type":"agent","ref":"` + agentID.String() + `"},{"type":"team","ref":"` + teamID.String() + `"},{"type":"endpoint","ref":"` + invocationID.String() + `"},{"type":"human","ref":"endpoint:` + endpointID.String() + `"}]}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/entity-identities:resolve?tenant=acme&namespace=eng", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer console-secret")
	response := httptest.NewRecorder()
	server.router.ServeHTTP(response, req)
	if response.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", response.Code, response.Body.String())
	}
	var result struct {
		Items []entityIdentity `json:"items"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if len(result.Items) != 4 {
		t.Fatalf("items=%+v", result.Items)
	}
	if result.Items[0].Name != "Code Reviewer" || !result.Items[0].Resolved {
		t.Fatalf("agent identity=%+v", result.Items[0])
	}
	if result.Items[1].Name != "Release Team" || !result.Items[1].Resolved {
		t.Fatalf("team identity=%+v", result.Items[1])
	}
	if result.Items[2].Name != "Review API" || result.Items[2].Secondary != "Invocation "+invocationID.String()[:8] {
		t.Fatalf("invocation identity=%+v", result.Items[2])
	}
	if result.Items[3].Type != "endpoint" || result.Items[3].Name != "Review API" {
		t.Fatalf("prefixed endpoint identity=%+v", result.Items[3])
	}

	wrongScope := httptest.NewRequest(http.MethodPost, "/api/v1/entity-identities:resolve?tenant=other&namespace=eng", bytes.NewBufferString(`{"refs":[{"type":"agent","ref":"`+agentID.String()+`"}]}`))
	wrongScope.Header.Set("Content-Type", "application/json")
	wrongScope.Header.Set("Authorization", "Bearer console-secret")
	wrongResponse := httptest.NewRecorder()
	server.router.ServeHTTP(wrongResponse, wrongScope)
	if wrongResponse.Code != http.StatusOK {
		t.Fatalf("wrong scope status=%d body=%s", wrongResponse.Code, wrongResponse.Body.String())
	}
	var hidden struct {
		Items []entityIdentity `json:"items"`
	}
	if err := json.Unmarshal(wrongResponse.Body.Bytes(), &hidden); err != nil {
		t.Fatal(err)
	}
	if len(hidden.Items) != 1 || hidden.Items[0].Resolved {
		t.Fatalf("cross-scope identity leaked: %+v", hidden.Items)
	}
}

func TestResolveEntityIdentitiesRejectsOversizedBatch(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	server := NewServer(ServerOptions{Store: st, AuthToken: "console-secret"})
	refs := make([]entityIdentityRef, maxEntityIdentityRefs+1)
	for index := range refs {
		refs[index] = entityIdentityRef{Type: "human", Ref: "user"}
	}
	body, _ := json.Marshal(resolveEntityIdentitiesRequest{Refs: refs})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/entity-identities:resolve?tenant=acme&namespace=eng", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer console-secret")
	response := httptest.NewRecorder()
	server.router.ServeHTTP(response, req)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("status=%d body=%s", response.Code, response.Body.String())
	}
}

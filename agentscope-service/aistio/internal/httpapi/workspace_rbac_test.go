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
	"encoding/json"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestNavigationAccessDoesNotExposeOperationsWorkspace(t *testing.T) {
	t.Parallel()

	for role, expectedDefault := range map[string]string{
		"operator": workspaceWorkHub, "agent_developer": workspaceAgentCenter,
	} {
		role, expectedDefault := role, expectedDefault
		t.Run(role, func(t *testing.T) {
			t.Parallel()
			recorder := httptest.NewRecorder()
			context, _ := gin.CreateTestContext(recorder)
			context.Set("groups", []string{role})

			(&Server{}).navigationAccess(context)

			var response struct {
				Areas       []string `json:"areas"`
				DefaultArea string   `json:"defaultArea"`
			}
			if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
				t.Fatalf("decode navigation response: %v", err)
			}
			if response.DefaultArea != expectedDefault {
				t.Fatalf("default area = %q, want %q", response.DefaultArea, expectedDefault)
			}
			for _, area := range response.Areas {
				if area == workspaceOperations {
					t.Fatalf("operations must remain an internal authorization capability: %+v", response.Areas)
				}
			}
		})
	}
}

func TestCrossAgentActivityRemainsOperatorOnly(t *testing.T) {
	t.Parallel()

	if workspaceAllowed(map[string]bool{"agent_developer": true}, workspaceOperations, false) {
		t.Fatal("agent developer unexpectedly has cross-Agent operations access")
	}
	if !workspaceAllowed(map[string]bool{"operator": true}, workspaceOperations, false) {
		t.Fatal("operator should retain cross-Agent operations access")
	}
}

func TestAgentScopedSessionReadRequiresMatchingOwnership(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	owner, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{
		Tenant: "tenant", Namespace: "namespace", AgentKey: "owner", Status: controlmodel.AgentActive,
	})
	if err != nil {
		t.Fatal(err)
	}
	other, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{
		Tenant: "tenant", Namespace: "namespace", AgentKey: "other", Status: controlmodel.AgentActive,
	})
	if err != nil {
		t.Fatal(err)
	}
	session, err := st.Sessions().Upsert(ctx, &store.Session{
		Tenant: owner.Tenant, Namespace: owner.Namespace, AgentID: owner.ID,
		AgentName: owner.AgentKey, SessionID: "session", Phase: store.SessionPhaseIdle,
	})
	if err != nil {
		t.Fatal(err)
	}
	server := &Server{store: st}
	allowed := func(agentID string) bool {
		recorder := httptest.NewRecorder()
		request := httptest.NewRequest("GET", "/api/v1/sessions/"+session.ID.String()+"/events?agentId="+agentID, nil)
		ginContext, _ := gin.CreateTestContext(recorder)
		ginContext.Request = request
		return server.agentScopedSessionReadAllowed(ginContext)
	}
	if !allowed(owner.ID.String()) {
		t.Fatal("owning Agent was denied its scoped session")
	}
	if allowed(other.ID.String()) {
		t.Fatal("session was exposed through an unrelated Agent scope")
	}
}

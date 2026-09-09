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
package postgres_test

import (
	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"os"
	"testing"
)

func TestResourceGroupMembershipAndFilteredPagination(t *testing.T) {
	dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set")
	}
	st, err := store.Open(t.Context(), store.Config{Driver: store.DriverPostgres, PostgresDSN: dsn})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	tenant := "resource-" + uuid.NewString()
	n, err := st.Access().PutNamespace(t.Context(), &model.Namespace{Tenant: tenant, Name: "shared", DisplayName: "Shared", Kind: "shared", Owner: "owner", Groups: map[string]model.AccessGroup{"builders": {Name: "Builders", Members: []string{"bob"}, Roles: []string{"developer"}}}}, 0, "owner")
	if err != nil {
		t.Fatal(err)
	}
	spaces, err := st.Access().ListNamespaces(t.Context(), tenant, "bob", 50, 0)
	if err != nil || len(spaces) != 1 || !model.NamespaceAllows(spaces[0].Roles("bob"), "configure") {
		t.Fatal("group-only membership absent", spaces, err)
	}
	first, err := st.AgentCatalog().CreateAgent(t.Context(), &model.Agent{Tenant: tenant, Namespace: n.Name, AgentKey: "a", DisplayName: "A", Status: model.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	second, err := st.AgentCatalog().CreateAgent(t.Context(), &model.Agent{Tenant: tenant, Namespace: n.Name, AgentKey: "b", DisplayName: "B", Status: model.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	agents, err := st.AgentCatalog().ListAgents(t.Context(), store.AgentFilter{Tenant: tenant, Namespace: n.Name, ExcludedIDs: []uuid.UUID{first.ID}, Limit: 1})
	if err != nil || len(agents) != 1 || agents[0].ID != second.ID {
		t.Fatal("filter after limit", agents, err)
	}
	agents, err = st.AgentCatalog().ListAgents(t.Context(), store.AgentFilter{Tenant: tenant, Namespace: n.Name, Offset: 1, Limit: 1})
	if err != nil || len(agents) != 1 || agents[0].ID != second.ID {
		t.Fatal("offset ignored", agents, err)
	}
}

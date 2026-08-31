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

package memory_test

import (
	"context"
	"testing"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
	"github.com/spring-ai-alibaba/aistio/internal/store/storetest"
)

func TestMemoryStore(t *testing.T) {
	s, err := store.Open(context.Background(), store.DefaultConfig())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	storetest.RunSuite(t, s)
}

func TestRuntimeDataFiltersByStableAgentID(t *testing.T) {
	ctx := context.Background()
	s, err := store.Open(ctx, store.DefaultConfig())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	agentA, agentB := uuid.New(), uuid.New()
	for _, agentID := range []uuid.UUID{agentA, agentB} {
		saved, saveErr := s.Sessions().Upsert(ctx, &store.Session{Tenant: "tenant", Namespace: "default",
			AgentID: agentID, AgentName: "same-observed-name", SessionID: agentID.String(), Phase: store.SessionPhaseActive})
		if saveErr != nil {
			t.Fatal(saveErr)
		}
		if metricErr := s.Metrics().RecordTokenUsage(ctx, &store.TokenUsageMetric{Tenant: "tenant", AgentID: agentID,
			AgentName: "same-observed-name", Namespace: "default", SessionFK: &saved.ID, TotalTokens: 7}); metricErr != nil {
			t.Fatal(metricErr)
		}
	}
	if rows, _ := s.Sessions().List(ctx, store.SessionFilter{Tenant: "tenant", AgentID: agentA}); len(rows) != 1 || rows[0].AgentID != agentA {
		t.Fatalf("agent session filter leaked: %+v", rows)
	}
	if total, _ := s.Metrics().SumTokenUsage(ctx, store.TokenFilter{Tenant: "tenant", AgentID: agentA}); total != 7 {
		t.Fatalf("agent metric filter leaked: %d", total)
	}
}

func TestSessionsAndMetricsAreTenantIsolated(t *testing.T) {
	ctx := context.Background()
	s, err := store.Open(ctx, store.DefaultConfig())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	for _, tenant := range []string{"tenant-a", "tenant-b"} {
		saved, err := s.Sessions().Upsert(ctx, &store.Session{
			Tenant: tenant, AgentName: "reviewer", Namespace: "shared", SessionID: "same-id",
			Phase: store.SessionPhaseActive,
		})
		if err != nil {
			t.Fatalf("upsert %s: %v", tenant, err)
		}
		if err := s.Metrics().RecordTokenUsage(ctx, &store.TokenUsageMetric{
			Tenant: tenant, SessionFK: &saved.ID, AgentName: "reviewer", Namespace: "shared", TotalTokens: 10,
		}); err != nil {
			t.Fatalf("metric %s: %v", tenant, err)
		}
	}

	a, err := s.Sessions().Get(ctx, "tenant-a", "reviewer", "shared", "same-id")
	if err != nil || a.Tenant != "tenant-a" {
		t.Fatalf("tenant-a session = %+v, %v", a, err)
	}
	b, err := s.Sessions().Get(ctx, "tenant-b", "reviewer", "shared", "same-id")
	if err != nil || b.Tenant != "tenant-b" || b.ID == a.ID {
		t.Fatalf("tenant-b session = %+v, %v", b, err)
	}
	if got, _ := s.Sessions().List(ctx, store.SessionFilter{Tenant: "tenant-a"}); len(got) != 1 || got[0].ID != a.ID {
		t.Fatalf("tenant-a list = %+v", got)
	}
	if total, _ := s.Metrics().SumTokenUsage(ctx, store.TokenFilter{Tenant: "tenant-a"}); total != 10 {
		t.Fatalf("tenant-a tokens = %d", total)
	}
}

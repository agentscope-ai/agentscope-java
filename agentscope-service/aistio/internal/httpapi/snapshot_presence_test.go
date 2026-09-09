// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package httpapi

import (
	"github.com/google/uuid"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"testing"
)

func TestSnapshotPresenceSurvivesPersistence(t *testing.T) {
	for _, driver := range []string{"memory", "postgres"} {
		t.Run(driver, func(t *testing.T) {
			config := store.Config{Driver: store.DriverMemory}
			if driver == "postgres" {
				config = acceptancePostgresConfig(t)
			}
			st, agent, _, _ := setupConversationAgent(t, config)
			server := NewServer(ServerOptions{Store: st})
			session, err := server.resolveAgentConversation(t.Context(), agent, "", "runtime", "")
			if err != nil {
				t.Fatal(err)
			}
			snapshot := &store.SessionSnapshot{SessionFK: session.ID, TokenUsageReported: true, ContextPressureReported: true}
			if err := st.Metrics().RecordSnapshot(t.Context(), snapshot); err != nil {
				t.Fatal(err)
			}
			latest, err := st.Metrics().LatestSnapshot(t.Context(), session.ID)
			if err != nil {
				t.Fatal(err)
			}
			if !latest.TokenUsageReported || !latest.ContextPressureReported || latest.TotalTokens != 0 || latest.ContextPressure != 0 {
				t.Fatalf("presence lost: %+v", latest)
			}
			batch, err := st.Metrics().LatestSnapshots(t.Context(), []uuid.UUID{session.ID})
			if err != nil || !batch[session.ID].TokenUsageReported {
				t.Fatalf("batch lost presence: %+v %v", batch, err)
			}
		})
	}
}

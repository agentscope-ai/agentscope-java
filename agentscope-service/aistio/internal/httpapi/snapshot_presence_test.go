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

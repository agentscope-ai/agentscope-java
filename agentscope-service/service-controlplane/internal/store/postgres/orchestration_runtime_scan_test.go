// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package postgres

import (
	"testing"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
)

func TestRuntimeRunKeysetScanContinuesAfterEarlierRunCloses(t *testing.T) {
	st, err := store.Open(t.Context(), serviceMigrationConfig(t))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	var runs []*model.OrchestrationRun
	for i := 0; i < 3; i++ {
		issue, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{
			Tenant: "tenant", Namespace: "space", Title: "keyset fixture", Status: model.IssueTodo,
			Creator: model.Actor{Type: model.ActorSystem, Ref: "keyset-fixture"},
		})
		if err != nil {
			t.Fatal(err)
		}
		run, err := st.Orchestration().CreateRun(t.Context(), &model.OrchestrationRun{
			Tenant: "tenant", Namespace: "space", RootIssueID: issue.ID,
			Mode: model.RunModeAdaptive, State: model.RunPlanned,
			CreatedBy: model.Actor{Type: model.ActorSystem, Ref: "keyset-fixture"},
		})
		if err != nil {
			t.Fatal(err)
		}
		runs = append(runs, run)
	}
	first, err := st.Orchestration().ListRuns(t.Context(), store.OrchestrationRunFilter{
		ActiveOnly: true, OldestFirst: true, Limit: 2,
	})
	if err != nil || len(first) != 2 || first[0].ID != runs[0].ID || first[1].ID != runs[1].ID {
		t.Fatalf("first bounded page: runs=%v error=%v", first, err)
	}
	if _, err = st.Orchestration().TransitionRun(t.Context(), first[0].ID, first[0].Version,
		model.RunCancelled, nil, "", ""); err != nil {
		t.Fatal(err)
	}
	for _, tenant := range []string{"", "tenant"} {
		next, err := st.Orchestration().ListRuns(t.Context(), store.OrchestrationRunFilter{
			Tenant: tenant, ActiveOnly: true, OldestFirst: true, Limit: 2,
			AfterCreatedAt: &first[1].CreatedAt, AfterID: first[1].ID,
		})
		if err != nil || len(next) != 1 || next[0].ID != runs[2].ID {
			t.Fatalf("keyset page stopped after an earlier row closed: tenant=%q runs=%v error=%v", tenant, next, err)
		}
	}
}

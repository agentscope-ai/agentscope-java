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

package orchestration

import (
	"testing"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	_ "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store/memory"
)

func TestPortalRuntimeEngineRefusesUnadmittedRunsBeforeStateChanges(t *testing.T) {
	for _, state := range []model.OrchestrationRunState{model.RunPlanned, model.RunCancelling, model.RunFailed} {
		t.Run(string(state), func(t *testing.T) {
			st, err := store.Open(t.Context(), store.DefaultConfig())
			if err != nil {
				t.Fatal(err)
			}
			defer st.Close()
			issue, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{
				Tenant: "fixture", Namespace: "space", Title: "unadmitted legacy issue",
				Creator: model.Actor{Type: model.ActorHuman, Ref: "owner"},
			})
			if err != nil {
				t.Fatal(err)
			}
			run, err := st.Orchestration().CreateRun(t.Context(), &model.OrchestrationRun{
				Tenant: issue.Tenant, Namespace: issue.Namespace, RootIssueID: issue.ID, State: state,
			})
			if err != nil {
				t.Fatal(err)
			}
			if err = (&Engine{Store: st, RequirePortal: true}).ReconcileRun(t.Context(), run.ID); err != store.ErrForbidden {
				t.Fatalf("unadmitted run reconciled: %v", err)
			}
			current, err := st.Orchestration().GetRun(t.Context(), run.ID)
			if err != nil || current.State != state || current.Version != run.Version {
				t.Fatalf("rejected run was mutated: %+v %v", current, err)
			}
			currentIssue, err := st.Collaboration().GetIssue(t.Context(), issue.ID)
			if err != nil || currentIssue.Version != issue.Version || currentIssue.Status != issue.Status {
				t.Fatalf("rejected run changed its root issue: %+v %v", currentIssue, err)
			}
		})
	}
}

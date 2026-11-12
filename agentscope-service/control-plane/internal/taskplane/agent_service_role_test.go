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

package taskplane

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
	_ "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store/memory"
)

func TestAgentServiceTaskPlaneRefusesLegacyDispatchAndClaim(t *testing.T) {
	st, err := store.Open(t.Context(), store.DefaultConfig())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	pool, err := st.RuntimeRegistry().UpsertRuntimePool(t.Context(), &model.RuntimePool{Tenant: "fixture", Namespace: "space", Name: "coding"})
	if err != nil {
		t.Fatal(err)
	}
	profile, err := st.RuntimeRegistry().UpsertRuntimeProfile(t.Context(), &model.RuntimeProfile{Tenant: "fixture", Namespace: "space", Name: "codex", Provider: "codex"})
	if err != nil {
		t.Fatal(err)
	}
	host, err := st.RuntimeRegistry().UpsertRuntimeHost(t.Context(), &model.RuntimeHost{
		Tenant: "fixture", Namespace: "space", HostKey: "fixture-host", PoolName: pool.Name,
		State: model.RuntimeHostOnline, Capacity: 1, Capabilities: json.RawMessage(`{"providers":{"codex":"fixture"}}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	issue, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{
		Tenant: "fixture", Namespace: "space", Title: "legacy issue",
		Creator: model.Actor{Type: model.ActorHuman, Ref: "owner"},
	})
	if err != nil {
		t.Fatal(err)
	}
	agent := uuid.New()
	_, task, err := st.Collaboration().AssignIssue(t.Context(), issue.ID, issue.Version, model.AssigneeAgent, agent.String(), issue.Creator)
	if err != nil {
		t.Fatal(err)
	}
	binding := model.RuntimeBinding{AgentID: agent, BindingID: uuid.New(), Kind: model.DataPlaneHostedRuntime, RuntimeProfileID: profile.ID, RuntimePoolID: pool.ID}
	strict := &Service{Store: st, RequireAgentService: true}
	if _, _, err = strict.DispatchHosted(t.Context(), task.ID, binding, nil); err != store.ErrForbidden {
		t.Fatalf("unadmitted dispatch accepted: %v", err)
	}
	current, err := st.Collaboration().GetAgentTask(t.Context(), task.ID)
	if err != nil || current.Status != model.AgentTaskQueued || current.Version != task.Version {
		t.Fatalf("rejected task was dispatched: %+v %v", current, err)
	}
	// A row from the former execution role must not be claimed or mutated by
	// the Agent Service-only execution role, even when it shares the enrolled Host pool.
	_, attempt, err := (&Service{Store: st}).DispatchHosted(t.Context(), task.ID, binding, nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = strict.Claim(t.Context(), store.ExecutionClaim{Tenant: task.Tenant, Namespace: task.Namespace,
		RuntimePoolName: pool.Name, HostID: host.ID, HostGeneration: host.LeaseGeneration,
		LeaseOwner: "fixture-host/1", LeaseToken: "fixture-lease", LeaseTTL: time.Minute}); err != store.ErrNotFound {
		t.Fatalf("unadmitted Host claim accepted: %v", err)
	}
	closed, err := st.ExecutionAttempts().Get(t.Context(), attempt.ID)
	if err != nil || closed.State != model.ExecutionQueued || closed.HostID != nil || closed.Version != attempt.Version || closed.LeaseToken != "" {
		t.Fatalf("Agent Service-only role mutated a legacy assignment: %+v %v", closed, err)
	}
	if _, err = strict.RenewExecution(t.Context(), closed.ID, closed.LeaseToken, closed.FencingToken, time.Minute); err != store.ErrForbidden {
		t.Fatalf("unadmitted lease renewed: %v", err)
	}
}

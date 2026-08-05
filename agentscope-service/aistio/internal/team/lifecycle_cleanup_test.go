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

package team_test

import (
	"context"
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/team"
)

// A deleted team must not leave the product sessions it allocated behind: those
// rows outlive the team otherwise and accumulate as orphans.
func TestCleanupTeamState_ReleasesManagedMemberSessions(t *testing.T) {
	ctx := context.Background()
	st, lc := newTestLifecycle(t)

	api := &fakeManagedAPI{}
	act := team.NewActivator(st, nil, nil)
	act.SetManagedSessionAPI(api)
	lc.SetActivator(act)

	teamRow, err := st.Teams().Create(ctx, &store.Team{
		Name: "research", Namespace: "default", Objective: "ship it", Phase: store.TeamPhaseRunning,
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := st.Teams().UpsertMember(ctx, &store.TeamMember{
		TeamName: "research", Namespace: "default", MemberName: "lead",
		AgentRef: "lead-agent", DeployMode: store.MemberDeployManaged,
		ManagedAgentID: "agt_lead", OwnerID: "user_1",
		ManagedSessionID: "sess_lead", Phase: store.MemberPhaseWorking,
	}); err != nil {
		t.Fatal(err)
	}
	// BYO members run in their own instances; there is no product session to drop.
	if _, err := st.Teams().UpsertMember(ctx, &store.TeamMember{
		TeamName: "research", Namespace: "default", MemberName: "worker-1",
		AgentRef: "worker-agent", DeployMode: store.MemberDeployBYO,
		SessionID: "sess-byo", Phase: store.MemberPhaseWorking,
	}); err != nil {
		t.Fatal(err)
	}

	lc.CleanupTeamState(ctx, teamRow)

	api.mu.Lock()
	deleted := append([]wakeCall(nil), api.deleted...)
	api.mu.Unlock()

	if len(deleted) != 1 {
		t.Fatalf("expected 1 managed session release, got %d (%+v)", len(deleted), deleted)
	}
	if deleted[0].sessionID != "sess_lead" || deleted[0].ownerID != "user_1" {
		t.Fatalf("released %+v, want sess_lead/user_1", deleted[0])
	}
	if _, err := st.Teams().Get(ctx, "default", "research"); err == nil {
		t.Fatal("expected team row to be deleted")
	}
}

// Without a managed API wired (BYO-only deployments, tests) teardown must still
// complete rather than panic on the nil dependency.
func TestCleanupTeamState_NoActivatorStillDeletesTeam(t *testing.T) {
	ctx := context.Background()
	st, lc := newTestLifecycle(t)

	teamRow, err := st.Teams().Create(ctx, &store.Team{
		Name: "solo", Namespace: "default", Objective: "ship it", Phase: store.TeamPhaseRunning,
	})
	if err != nil {
		t.Fatal(err)
	}

	lc.CleanupTeamState(ctx, teamRow)

	if _, err := st.Teams().Get(ctx, "default", "solo"); err == nil {
		t.Fatal("expected team row to be deleted")
	}
}

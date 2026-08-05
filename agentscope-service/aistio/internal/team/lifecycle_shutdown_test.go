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
	"github.com/spring-ai-alibaba/aistio/internal/store/memory"
	"github.com/spring-ai-alibaba/aistio/internal/team"
)

func newTestLifecycle(t *testing.T) (store.Store, *team.Lifecycle) {
	t.Helper()
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	lc := team.NewLifecycle(
		st,
		team.NewTaskStore(st.TeamTasks()),
		team.NewMessageRouter(st.TeamMessages(), st.Sessions()),
		team.NewSessionSpawner(st),
	)
	return st, lc
}

func TestShutdownMember_TerminatesSessionAndFlipsPhase(t *testing.T) {
	ctx := context.Background()
	st, lc := newTestLifecycle(t)

	teamRow, err := st.Teams().Create(ctx, &store.Team{
		Name: "research", Namespace: "default", Objective: "ship it", Phase: store.TeamPhaseRunning,
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := st.Teams().UpsertMember(ctx, &store.TeamMember{
		TeamName: "research", Namespace: "default", MemberName: "worker-1",
		AgentRef: "worker-agent", DeployMode: store.MemberDeployBYO, Phase: store.MemberPhaseWorking,
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := st.Sessions().Upsert(ctx, &store.Session{
		SessionID: "sess-worker-1", AgentName: "worker-agent", Namespace: "default",
		Phase: store.SessionPhaseActive, TeamID: "research", TeamRole: "worker-1",
	}); err != nil {
		t.Fatal(err)
	}

	if err := lc.ShutdownMember(ctx, teamRow, "worker-1"); err != nil {
		t.Fatalf("ShutdownMember: %v", err)
	}

	got, err := st.Teams().GetMember(ctx, "default", "research", "worker-1")
	if err != nil {
		t.Fatal(err)
	}
	if got.Phase != store.MemberPhaseShutdown {
		t.Fatalf("member phase = %q, want %q", got.Phase, store.MemberPhaseShutdown)
	}

	sessions, err := st.Sessions().List(ctx, store.SessionFilter{
		Namespace: "default", TeamID: "research", TeamRole: "worker-1",
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(sessions) != 1 {
		t.Fatalf("expected 1 member session, got %d", len(sessions))
	}
	if sessions[0].Phase != store.SessionPhaseTerminated {
		t.Fatalf("session phase = %q, want %q", sessions[0].Phase, store.SessionPhaseTerminated)
	}
}

func TestShutdownMember_UnknownMember(t *testing.T) {
	ctx := context.Background()
	st, lc := newTestLifecycle(t)
	teamRow, err := st.Teams().Create(ctx, &store.Team{
		Name: "research", Namespace: "default", Objective: "ship it", Phase: store.TeamPhaseRunning,
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := lc.ShutdownMember(ctx, teamRow, "ghost"); err == nil {
		t.Fatal("expected error for unknown member")
	}
}

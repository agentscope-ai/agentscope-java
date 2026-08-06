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

/*
Copyright 2026 the original author or authors.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package team

import (
	"context"
	"testing"
	"time"

	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestSyncMemberPhaseFromSessionStatus(t *testing.T) {
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	_, err = st.Teams().Create(ctx, &store.Team{
		Name: "aaa", Namespace: "default", Objective: "o", Phase: store.TeamPhaseRunning,
	})
	if err != nil {
		t.Fatal(err)
	}
	_, err = st.Teams().UpsertMember(ctx, &store.TeamMember{
		TeamName: "aaa", Namespace: "default", MemberName: "lead",
		AgentRef: "a", Phase: store.MemberPhaseWorking, SessionID: "sess_1",
		CreatedAt: time.Now().UTC(), UpdatedAt: time.Now().UTC(),
	})
	if err != nil {
		t.Fatal(err)
	}
	_, err = st.Teams().UpsertMember(ctx, &store.TeamMember{
		TeamName: "aaa", Namespace: "default", MemberName: "w1",
		AgentRef: "b", Phase: store.MemberPhaseWorking, SessionID: "sess_2",
		CreatedAt: time.Now().UTC(), UpdatedAt: time.Now().UTC(),
	})
	if err != nil {
		t.Fatal(err)
	}

	if err := SyncMemberPhaseFromSessionStatus(ctx, st, "sess_1", "idle"); err != nil {
		t.Fatal(err)
	}
	m, err := st.Teams().GetMember(ctx, "default", "aaa", "lead")
	if err != nil {
		t.Fatal(err)
	}
	if m.Phase != store.MemberPhaseIdle {
		t.Fatalf("phase=%q want Idle", m.Phase)
	}
	team, _ := st.Teams().Get(ctx, "default", "aaa")
	if team.Phase != store.TeamPhaseRunning {
		t.Fatalf("team still busy via w1: phase=%q want Running", team.Phase)
	}

	if err := SyncMemberPhaseFromSessionStatus(ctx, st, "sess_2", "idle"); err != nil {
		t.Fatal(err)
	}
	team, _ = st.Teams().Get(ctx, "default", "aaa")
	if team.Phase != store.TeamPhaseIdle {
		t.Fatalf("team phase=%q want Idle", team.Phase)
	}

	if err := SyncMemberPhaseFromSessionStatus(ctx, st, "sess_1", "running"); err != nil {
		t.Fatal(err)
	}
	m, _ = st.Teams().GetMember(ctx, "default", "aaa", "lead")
	if m.Phase != store.MemberPhaseWorking {
		t.Fatalf("phase=%q want Working", m.Phase)
	}
	team, _ = st.Teams().Get(ctx, "default", "aaa")
	if team.Phase != store.TeamPhaseRunning {
		t.Fatalf("team phase=%q want Running", team.Phase)
	}

	_ = st.Teams().UpdateMemberPhase(ctx, "default", "aaa", "lead", store.MemberPhaseShutdown)
	if err := SyncMemberPhaseFromSessionStatus(ctx, st, "sess_1", "idle"); err != nil {
		t.Fatal(err)
	}
	m, _ = st.Teams().GetMember(ctx, "default", "aaa", "lead")
	if m.Phase != store.MemberPhaseShutdown {
		t.Fatalf("terminal phase overwritten: %q", m.Phase)
	}
}

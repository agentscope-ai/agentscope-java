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

package orchestration

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestMaterializeTeamCoordinatorIsIdempotentAndFreezesOneRoster(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	actor := controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "test"}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant", Namespace: "default", Title: "endpoint work", Creator: actor,
	})
	if err != nil {
		t.Fatal(err)
	}
	run, err := st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{
		Tenant: "tenant", Namespace: "default", RootIssueID: issue.ID,
		Mode: controlmodel.RunModeAdaptive, State: controlmodel.RunRunning, CreatedBy: actor,
	})
	if err != nil {
		t.Fatal(err)
	}
	team := &controlmodel.CollaborationTeam{ID: uuid.New(), Tenant: "tenant", Namespace: "default",
		Name: "endpoint-team", Status: controlmodel.TeamActive, LeaderAgentRef: "leader",
		Members: []controlmodel.CollaborationTeamMember{{AgentRef: "worker", Role: "researcher"}}}
	request := MaterializeTeamRequest{Run: run, IssueID: issue.ID, Team: team, NodeKey: "target", Actor: actor}
	firstNode, firstTask, err := MaterializeTeamCoordinator(ctx, st, request)
	if err != nil {
		t.Fatal(err)
	}
	secondNode, secondTask, err := MaterializeTeamCoordinator(ctx, st, request)
	if err != nil {
		t.Fatal(err)
	}
	if firstNode.ID != secondNode.ID || firstTask.ID != secondTask.ID || !firstTask.LeaderTask || firstTask.TeamID == nil || *firstTask.TeamID != team.ID {
		t.Fatalf("materialization is not idempotent: first=%+v/%+v second=%+v/%+v", firstNode, firstTask, secondNode, secondTask)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{RunID: run.ID, Limit: 10})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("expected exactly one leader obligation: tasks=%+v err=%v", tasks, err)
	}
	snapshots, err := st.Orchestration().ListTeamSnapshots(ctx, run.ID)
	if err != nil || len(snapshots) != 1 {
		t.Fatalf("expected one Team snapshot: snapshots=%+v err=%v", snapshots, err)
	}
	var frozen controlmodel.CollaborationTeam
	if err = json.Unmarshal(snapshots[0].Snapshot, &frozen); err != nil || len(frozen.Members) != 1 || frozen.Members[0].AgentRef != "worker" {
		t.Fatalf("unexpected Team snapshot: team=%+v err=%v", frozen, err)
	}
}

func TestMaterializeTeamCoordinatorRejectsDisabledTeam(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	actor := controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "test"}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "tenant", Namespace: "default", Title: "work", Creator: actor})
	if err != nil {
		t.Fatal(err)
	}
	run, err := st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{Tenant: "tenant", Namespace: "default",
		RootIssueID: issue.ID, Mode: controlmodel.RunModeAdaptive, State: controlmodel.RunRunning, CreatedBy: actor})
	if err != nil {
		t.Fatal(err)
	}
	_, _, err = MaterializeTeamCoordinator(ctx, st, MaterializeTeamRequest{Run: run, IssueID: issue.ID,
		Team: &controlmodel.CollaborationTeam{ID: uuid.New(), Tenant: "tenant", Namespace: "default",
			Name: "disabled", Status: controlmodel.TeamDisabled, LeaderAgentRef: "leader"}, Actor: actor})
	if err == nil {
		t.Fatal("disabled Team was materialized")
	}
}

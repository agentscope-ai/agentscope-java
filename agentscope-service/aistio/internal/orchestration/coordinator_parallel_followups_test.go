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
	"strings"
	"testing"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func TestParallelLeaderFollowUpsDoNotDeadlockCoordinator(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })

	issueID, runID, nodeID := uuid.New(), uuid.New(), uuid.New()
	actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	if _, err = st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		ID: issueID, Tenant: "tenant", Namespace: "default", Title: "parallel coordinator",
		Creator: actor,
	}); err != nil {
		t.Fatal(err)
	}
	if _, err = st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{
		ID: runID, Tenant: "tenant", Namespace: "default", RootIssueID: issueID,
		Mode: controlmodel.RunModeAdaptive, State: controlmodel.RunRunning, CreatedBy: actor,
	}); err != nil {
		t.Fatal(err)
	}
	node, err := st.Orchestration().CreateNode(ctx, &controlmodel.RunNode{
		ID: nodeID, RunID: runID, Tenant: "tenant", Namespace: "default",
		NodeKey: "coordinator", Type: controlmodel.RunNodeTeam, IssueID: &issueID,
		State: controlmodel.RunNodeReady,
	})
	if err != nil {
		t.Fatal(err)
	}
	node, err = st.Orchestration().TransitionNode(ctx, node.ID, node.Version,
		controlmodel.RunNodeWaiting, nil, "team_coordinator", "")
	if err != nil {
		t.Fatal(err)
	}

	leaders := make([]*controlmodel.AgentTask, 0, 2)
	for range 2 {
		task, createErr := st.Collaboration().CreateRunAgentTask(ctx, store.RunTaskRequest{
			RunID: runID, NodeID: node.ID, IssueID: issueID, AgentRef: "leader",
			TeamRole: "leader", Leader: true, Originator: actor,
		})
		if createErr == nil {
			task, createErr = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{
				TaskID: task.ID, ExpectedVersion: task.Version,
			})
		}
		if createErr == nil {
			task, createErr = st.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
		}
		if createErr != nil {
			t.Fatal(createErr)
		}
		leaders = append(leaders, task)
	}

	winner, err := st.Collaboration().CompleteAgentTask(ctx, leaders[0].ID,
		store.TaskCompletion{ExpectedVersion: leaders[0].Version, Result: json.RawMessage(`{"ok":true}`)})
	if err != nil {
		t.Fatal(err)
	}
	node, err = (&Service{Store: st}).CompleteCoordinatorNode(ctx, winner.ID,
		json.RawMessage(`{"ok":true}`), controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"})
	if err != nil {
		t.Fatalf("parallel coordinator completion failed: %v", err)
	}
	if node.State != controlmodel.RunNodeSucceeded {
		t.Fatalf("coordinator state=%s", node.State)
	}
	loser, err := st.Collaboration().GetAgentTask(ctx, leaders[1].ID)
	if err != nil {
		t.Fatal(err)
	}
	if loser.Status != controlmodel.AgentTaskCancelled {
		t.Fatalf("redundant leader state=%s", loser.Status)
	}
	run, err := st.Orchestration().GetRun(ctx, runID)
	if err != nil {
		t.Fatal(err)
	}
	if run.State != controlmodel.RunSucceeded {
		t.Fatalf("run state=%s", run.State)
	}
}

func TestCoordinatorDoesNotCancelPendingLeaderOutcome(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })

	issueID, runID, nodeID, teamID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	if _, err = st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		ID: issueID, Tenant: "tenant", Namespace: "default", Title: "pending outcome", Creator: actor,
	}); err != nil {
		t.Fatal(err)
	}
	if _, err = st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{
		ID: runID, Tenant: "tenant", Namespace: "default", RootIssueID: issueID,
		Mode: controlmodel.RunModeAdaptive, State: controlmodel.RunRunning, CreatedBy: actor,
	}); err != nil {
		t.Fatal(err)
	}
	node, err := st.Orchestration().CreateNode(ctx, &controlmodel.RunNode{
		ID: nodeID, RunID: runID, Tenant: "tenant", Namespace: "default", NodeKey: "coordinator",
		Type: controlmodel.RunNodeTeam, IssueID: &issueID, State: controlmodel.RunNodeReady,
	})
	if err == nil {
		node, err = st.Orchestration().TransitionNode(ctx, node.ID, node.Version,
			controlmodel.RunNodeWaiting, nil, "team_coordinator", "")
	}
	if err != nil {
		t.Fatal(err)
	}
	winner, err := st.Collaboration().CreateRunAgentTask(ctx, store.RunTaskRequest{
		RunID: runID, NodeID: node.ID, IssueID: issueID, AgentRef: "leader", TeamID: &teamID,
		TeamRole: "leader", Leader: true, Originator: actor,
	})
	if err == nil {
		winner, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: winner.ID, ExpectedVersion: winner.Version})
	}
	if err == nil {
		winner, err = st.Collaboration().StartAgentTask(ctx, winner.ID, winner.Version)
	}
	if err == nil {
		winner, err = st.Collaboration().CompleteAgentTask(ctx, winner.ID,
			store.TaskCompletion{ExpectedVersion: winner.Version, Result: json.RawMessage(`{"ok":true}`)})
	}
	if err != nil {
		t.Fatal(err)
	}
	routed, err := st.Collaboration().CreateComment(ctx, store.CreateCommentRequest{
		Comment: &controlmodel.Comment{IssueID: issueID, Author: actor, Content: "second worker result", Type: controlmodel.CommentResult},
		Targets: []store.CommentTarget{{TargetType: controlmodel.AssigneeAgent, TargetRef: "leader", AgentRef: "leader",
			TeamID: &teamID, TeamRole: "leader", ParentTaskID: &winner.ID, RouteType: controlmodel.RouteTeamLeader}},
	})
	if err != nil || len(routed.Tasks) != 1 {
		t.Fatalf("pending outcome route: result=%+v err=%v", routed, err)
	}
	pending, err := st.Collaboration().GetAgentTask(ctx, routed.Tasks[0].ID)
	if err != nil || len(pending.Inputs) != 1 {
		t.Fatalf("pending outcome input: task=%+v err=%v", pending, err)
	}
	if _, err = (&Service{Store: st}).CompleteCoordinatorNode(ctx, winner.ID,
		json.RawMessage(`{"ok":true}`), controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err == nil || !strings.Contains(err.Error(), "pending leader outcome") {
		t.Fatalf("coordinator discarded pending leader outcome: %v", err)
	}
	pending, err = st.Collaboration().GetAgentTask(ctx, routed.Tasks[0].ID)
	if err != nil || pending.Status != controlmodel.AgentTaskQueued {
		t.Fatalf("pending leader outcome was not preserved: task=%+v err=%v", pending, err)
	}
}

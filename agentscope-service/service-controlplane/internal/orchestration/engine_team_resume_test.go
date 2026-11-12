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
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/collaboration"
	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	_ "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store/memory"
)

// setupBlockedTeamRoot creates a Team-assigned root Issue whose adaptive Run is
// waiting on human input: the root is blocked and one delegated child exists.
func setupBlockedTeamRoot(t *testing.T, st store.Store, cancelChild bool) (*controlmodel.Issue, *controlmodel.AgentTask) {
	t.Helper()
	ctx := context.Background()
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant-a", Namespace: "ns-a", Name: "resumers", LeaderAgentRef: "leader",
	})
	if err != nil {
		t.Fatal(err)
	}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "ns-a", Title: "team work awaiting human input",
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String(),
		Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
	})
	if err != nil {
		t.Fatal(err)
	}
	child, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "ns-a", Title: "delegated child", ParentIssueID: &issue.ID,
		Creator: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if cancelChild {
		if _, err = st.Collaboration().TransitionIssue(ctx, child.ID, child.Version,
			controlmodel.IssueCancelled, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, "skipped"); err != nil {
			t.Fatal(err)
		}
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 10})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("leader task: tasks=%+v err=%v", tasks, err)
	}
	// Start the leader task before the root blocks. Starting a task on a
	// blocked Issue reopens it ("agent task started"), which would mask the
	// resume path under test.
	leader, err := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version})
	if err == nil {
		leader, err = st.Collaboration().StartAgentTask(ctx, leader.ID, leader.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	current, err := st.Collaboration().GetIssue(ctx, issue.ID)
	if err != nil {
		t.Fatal(err)
	}
	if current, err = st.Collaboration().TransitionIssue(ctx, issue.ID, current.Version,
		controlmodel.IssueInProgress, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}, "dispatched"); err != nil {
		t.Fatal(err)
	}
	issue, err = st.Collaboration().TransitionIssue(ctx, issue.ID, current.Version,
		controlmodel.IssueBlocked, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}, "waiting for human")
	if err != nil {
		t.Fatal(err)
	}
	return issue, leader
}

func completeTeamCoordinator(t *testing.T, st store.Store, task *controlmodel.AgentTask) {
	t.Helper()
	ctx := context.Background()
	output := json.RawMessage(`{"summary":"done"}`)
	completed, _, err := (&collaboration.Service{Store: st}).CompleteTask(ctx, task.ID,
		store.TaskCompletion{ExpectedVersion: task.Version, Result: output, Summary: "done"},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = (&Service{Store: st}).CompleteCoordinatorNode(ctx, completed.ID, output,
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
}

// A Team Run waiting on human input is routinely joined by newer comment-driven
// direct Runs on the same root. Those Runs never own the Issue lifecycle, so
// they must not strip the Team Run of its resume eligibility.
func TestAdaptiveTeamRunResumesBlockedRootDespiteNewerDirectRuns(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	issue, leader := setupBlockedTeamRoot(t, st, true)
	// Comment-driven direct Runs must not exhaust the query before the owning
	// adaptive Team Run is considered.
	time.Sleep(5 * time.Millisecond)
	for i := 0; i < 50; i++ {
		if _, err = st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{
			Tenant: "tenant-a", Namespace: "ns-a", RootIssueID: issue.ID,
			Mode: controlmodel.RunModeDirect, State: controlmodel.RunSucceeded,
			TriggerType: "comment", CreatedBy: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		}); err != nil {
			t.Fatal(err)
		}
	}
	completeTeamCoordinator(t, st, leader)
	if err = (&Engine{Store: st}).ReconcileRun(ctx, leader.OrchestrationRunID); err != nil {
		t.Fatal(err)
	}
	current, err := st.Collaboration().GetIssue(ctx, issue.ID)
	if err != nil {
		t.Fatal(err)
	}
	if current.Status != controlmodel.IssueInReview {
		t.Fatalf("blocked root did not resume to review after Team Run completed: status=%s version=%d", current.Status, current.Version)
	}
}

// The freshness guard still protects a newer Team Run: an older adaptive Run
// must not advance a root that a newer adaptive Run has taken over.
func TestOlderAdaptiveRunDoesNotResumeRootOwnedByNewerTeamRun(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	issue, leader := setupBlockedTeamRoot(t, st, true)
	// A team continuation creates a newer adaptive Run for the same root.
	time.Sleep(5 * time.Millisecond)
	if _, err = st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{
		Tenant: "tenant-a", Namespace: "ns-a", RootIssueID: issue.ID,
		Mode: controlmodel.RunModeAdaptive, State: controlmodel.RunWaiting,
		TriggerType: "team_continuation", CreatedBy: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
	}); err != nil {
		t.Fatal(err)
	}
	completeTeamCoordinator(t, st, leader)
	if err = (&Engine{Store: st}).ReconcileRun(ctx, leader.OrchestrationRunID); err != nil {
		t.Fatal(err)
	}
	current, err := st.Collaboration().GetIssue(ctx, issue.ID)
	if err != nil {
		t.Fatal(err)
	}
	if current.Status != controlmodel.IssueBlocked {
		t.Fatalf("older Team Run advanced a root owned by a newer Team Run: status=%s", current.Status)
	}
}

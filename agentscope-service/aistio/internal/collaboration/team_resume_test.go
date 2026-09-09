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
package collaboration

import (
	"context"
	"encoding/json"
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/postgres"
	"os"
	"strings"
	"testing"
)

func TestHumanFollowUpOnFailedTeamCreatesRootContinuation(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
			if driver == store.DriverPostgres && dsn == "" {
				t.Skip("AISTIO_TEST_POSTGRES_DSN not set")
			}
			st, err := store.Open(context.Background(), store.Config{Driver: driver, PostgresDSN: dsn})
			if err != nil {
				t.Fatal(err)
			}
			defer st.Close()
			testFailedTeamContinuation(t, st)
		})
	}
}
func testFailedTeamContinuation(t *testing.T, st store.Store) {
	ctx := context.Background()
	tenant := "resume-" + uuid.NewString()
	svc := &Service{Store: st}
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: tenant, Namespace: "default", Name: "child-resume", LeaderAgentRef: "leader",
		Policy: controlmodel.TeamPolicy{MaxChildDepth: 4, MaxChildIssues: 4},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.Collaboration().AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{
		TeamID: team.ID, AgentRef: "worker", Role: "researcher",
	}); err != nil {
		t.Fatal(err)
	}
	_, leader, err := svc.CreateIssue(ctx, CreateIssueRequest{
		Tenant: tenant, Namespace: "default", Title: "coordinate",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String(),
	})
	if err != nil {
		t.Fatal(err)
	}
	leader, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: leader.ID, ExpectedVersion: leader.Version})
	if err == nil {
		leader, err = st.Collaboration().StartAgentTask(ctx, leader.ID, leader.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	child, worker, err := svc.CreateChildFromTask(ctx, leader.ID, CreateIssueRequest{
		Title: "research", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker",
	})
	if err != nil {
		t.Fatal(err)
	}
	child2, worker2, err := svc.CreateChildFromTask(ctx, leader.ID, CreateIssueRequest{Title: "second research", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker"})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = svc.CompleteTask(ctx, leader.ID, store.TaskCompletion{ExpectedVersion: leader.Version,
		Summary: "delegated"}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	worker, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: worker.ID, ExpectedVersion: worker.Version})
	if err == nil {
		worker, err = st.Collaboration().StartAgentTask(ctx, worker.ID, worker.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	worker, err = svc.FailTask(ctx, worker.ID, worker.Version, "credential_missing", "missing key")
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = svc.ConvergeFailedWorker(ctx, worker.ID); err != nil {
		t.Fatal(err)
	}
	leaderFollowUps, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{
		IssueID: child.ID, AgentRef: "leader", Status: controlmodel.AgentTaskQueued, Limit: 10,
	})
	if err != nil || len(leaderFollowUps) != 1 {
		t.Fatalf("leader follow-up missing: tasks=%+v err=%v", leaderFollowUps, err)
	}
	followUp, err := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{
		TaskID: leaderFollowUps[0].ID, ExpectedVersion: leaderFollowUps[0].Version,
	})
	if err == nil {
		followUp, err = st.Collaboration().StartAgentTask(ctx, followUp.ID, followUp.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = svc.CompleteTask(ctx, followUp.ID, store.TaskCompletion{ExpectedVersion: followUp.Version,
		Summary: "waiting"}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	worker2, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: worker2.ID, ExpectedVersion: worker2.Version})
	if err == nil {
		worker2, err = st.Collaboration().StartAgentTask(ctx, worker2.ID, worker2.Version)
	}
	if err == nil {
		worker2, err = svc.FailTask(ctx, worker2.ID, worker2.Version, "missing_key", "no key")
	}
	if err != nil {
		t.Fatal(err)
	}
	root, _ := st.Collaboration().GetIssue(ctx, leader.IssueID)
	if root.Status != controlmodel.IssueBlocked {
		_, err = st.Collaboration().TransitionIssue(ctx, root.ID, root.Version, controlmodel.IssueBlocked, controlmodel.Actor{Type: controlmodel.ActorSystem}, "waiting for human")
		if err != nil {
			t.Fatal(err)
		}
	}
	old, err := st.Orchestration().GetRun(ctx, leader.OrchestrationRunID)
	if err != nil {
		t.Fatal(err)
	}
	old, err = st.Orchestration().TransitionRun(ctx, old.ID, old.Version, controlmodel.RunFailed, nil, "missing_key", "human input required")
	if err != nil {
		t.Fatal(err)
	}
	humanInput, err := svc.AddComment(ctx, AddCommentRequest{IssueID: child.ID,
		Author: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, Content: "continue"})
	if err != nil || len(humanInput.Tasks) != 1 {
		t.Fatalf("child follow-up was not queued: result=%+v err=%v", humanInput, err)
	}
	secondInput, err := svc.AddComment(ctx, AddCommentRequest{IssueID: child2.ID, Author: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, Content: "use supplied facts for the second branch", Mentions: []MentionTarget{{Type: controlmodel.AssigneeAgent, Ref: "worker"}}})
	if err != nil || len(secondInput.Tasks) != 1 {
		t.Fatalf("second reply lost: %+v %v", secondInput, err)
	}
	if secondInput.Tasks[0].OrchestrationRunID != humanInput.Tasks[0].OrchestrationRunID {
		t.Fatal("branches created separate continuations")
	}
	resumed := &humanInput.Tasks[0]
	if resumed.OrchestrationRunID == leader.OrchestrationRunID || resumed.TeamID == nil ||
		*resumed.TeamID != team.ID || resumed.TeamRole != "researcher" || resumed.ParentTaskID == nil ||
		*resumed.ParentTaskID != leader.ID {
		t.Fatalf("child follow-up lost active Team lineage: %+v", resumed)
	}

	continuation, err := st.Orchestration().GetRun(ctx, resumed.OrchestrationRunID)
	if err != nil || continuation.RootIssueID != old.RootIssueID || continuation.RerunOfRunID == nil || *continuation.RerunOfRunID != old.ID {
		t.Fatalf("lost root: %+v %v", continuation, err)
	}
	resumed, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: resumed.ID, ExpectedVersion: resumed.Version})
	if err == nil {
		resumed, err = st.Collaboration().StartAgentTask(ctx, resumed.ID, resumed.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	_, result, err := svc.CompleteTask(ctx, resumed.ID, store.TaskCompletion{ExpectedVersion: resumed.Version, Summary: "new evidence", Result: json.RawMessage(`{"report":"actual research"}`)}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "worker"})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Routes) != 1 || result.Routes[0].TaskID == nil {
		t.Fatalf("lost return route: %+v", result)
	}
	returned, err := st.Collaboration().GetAgentTask(ctx, *result.Routes[0].TaskID)
	if err != nil || !returned.LeaderTask || returned.OrchestrationRunID != continuation.ID {
		t.Fatalf("lost coordinator: %+v %v", returned, err)
	}
	envelope, err := svc.BuildContext(ctx, returned.ID)
	if err != nil || envelope.CoordinatorIssue.ID != old.RootIssueID || !strings.Contains(envelope.CurrentRequest, "actual research") {
		t.Fatalf("bad continuation context: %+v %v", envelope, err)
	}
	if envelope.ExecutionBrief == nil || len(envelope.ExecutionBrief.HumanRevisions) == 0 || envelope.ExecutionBrief.HumanRevisions[0].Content != "continue" {
		t.Fatalf("leader lost current-child human revision: %+v", envelope.ExecutionBrief)
	}
	foundHumanUpdate := false
	for _, branch := range envelope.CoordinatorChildren {
		if branch.Issue.ID == child.ID {
			for _, update := range branch.HumanUpdates {
				if update.Content == "continue" {
					foundHumanUpdate = true
				}
			}
		}
	}
	if !foundHumanUpdate {
		t.Fatal("leader lost the human's revised requirements")
	}
	secondWorker := &secondInput.Tasks[0]
	secondWorker, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: secondWorker.ID, ExpectedVersion: secondWorker.Version})
	if err == nil {
		secondWorker, err = st.Collaboration().StartAgentTask(ctx, secondWorker.ID, secondWorker.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	_, secondResult, err := svc.CompleteTask(ctx, secondWorker.ID, store.TaskCompletion{ExpectedVersion: secondWorker.Version, Summary: "second actual research"}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "worker"})
	if err != nil {
		t.Fatal(err)
	}
	returned, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: returned.ID, ExpectedVersion: returned.Version})
	if err == nil {
		returned, err = st.Collaboration().StartAgentTask(ctx, returned.ID, returned.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	pending, err := svc.PendingDelegationTasks(ctx, returned)
	if err != nil || len(pending) != 0 {
		t.Fatalf("unreviewed child must be decided first: %v %v", pending, err)
	}
	if _, err = svc.AcceptIssueFromTask(ctx, returned.ID, "actual evidence accepted"); err != nil {
		t.Fatal(err)
	}
	pending, err = svc.PendingDelegationTasks(ctx, returned)
	if err != nil || len(pending) != 1 || pending[0] != *secondResult.Routes[0].TaskID {
		t.Fatalf("queued sibling outcome not recognized: %v %v", pending, err)
	}
	if _, _, err = svc.WaitForDelegatedWork(ctx, returned.ID, "current child accepted; next sibling outcome can proceed"); err != nil {
		t.Fatal(err)
	}
	storedOld, _ := st.Orchestration().GetRun(ctx, old.ID)
	if storedOld.State != controlmodel.RunFailed {
		t.Fatal("historical Run was rewritten")
	}
}

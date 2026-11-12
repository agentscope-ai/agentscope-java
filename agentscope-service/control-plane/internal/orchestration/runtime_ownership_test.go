// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package orchestration

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

type ownedScanRepository struct {
	store.OrchestrationRepository
	visited          []uuid.UUID
	agentServiceOnly bool
}

func (r *ownedScanRepository) ListRuns(ctx context.Context, filter store.OrchestrationRunFilter) ([]*model.OrchestrationRun, error) {
	r.agentServiceOnly = filter.AgentServiceOnly
	return r.OrchestrationRepository.ListRuns(ctx, filter)
}
func (r *ownedScanRepository) GetRun(ctx context.Context, id uuid.UUID) (*model.OrchestrationRun, error) {
	r.visited = append(r.visited, id)
	return r.OrchestrationRepository.GetRun(ctx, id)
}

type ownedScanStore struct {
	store.Store
	repository *ownedScanRepository
}

func (s ownedScanStore) Orchestration() store.OrchestrationRepository { return s.repository }

func TestRuntimeOnlyRunScanExcludesLegacyAndRetainsChildRoots(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			st := cancellationStore(t, driver)
			actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
			legacy, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "legacy", Creator: actor, AssigneeType: model.AssigneeAgent, AssigneeRef: "legacy-agent"})
			if err != nil {
				t.Fatal(err)
			}
			legacyRun, err := st.Orchestration().CreateRun(t.Context(), &model.OrchestrationRun{Tenant: "t", Namespace: "n", RootIssueID: legacy.ID, Mode: model.RunModeDirect, State: model.RunPlanned, CreatedBy: actor, AgentServiceOwned: true})
			if err != nil {
				t.Fatal(err)
			}
			if legacyRun.AgentServiceOwned {
				t.Fatal("accepted ownership flag from caller instead of deriving source")
			}
			sessionID := uuid.New()
			contract, err := json.Marshal(map[string]string{"portal_command_id": "missing-live-admission", "public_session_id": sessionID.String()})
			if err != nil {
				t.Fatal(err)
			}
			inv, _, err := st.Endpoints().ReserveInvocation(t.Context(), &model.EndpointInvocation{EndpointID: sessionID, Mode: model.EndpointJobMode, PrincipalType: "portal", PrincipalRef: "fixture", Contract: contract, CorrelationID: "owner-fixture"})
			if err != nil {
				t.Fatal(err)
			}
			root, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "Agent Service", Creator: actor, SourceType: "endpoint", SourceRef: inv.ID.String()})
			if err != nil {
				t.Fatal(err)
			}
			rootRun, err := st.Orchestration().CreateRun(t.Context(), &model.OrchestrationRun{Tenant: "t", Namespace: "n", RootIssueID: root.ID, Mode: model.RunModeDeclared, State: model.RunPlanned, CreatedBy: actor})
			if err != nil || !rootRun.AgentServiceOwned {
				t.Fatalf("Agent Service root not owned: %+v %v", rootRun, err)
			}
			// Empty source_type is NULL in PostgreSQL. An independent child Run still
			// inherits its source through parent ancestry and must not disappear.
			child, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "child", Creator: actor, ParentIssueID: &root.ID, AssigneeType: model.AssigneeAgent, AssigneeRef: "child-agent"})
			if err != nil {
				t.Fatal(err)
			}
			tasks, err := st.Collaboration().ListAgentTasks(t.Context(), store.AgentTaskFilter{IssueID: child.ID, Limit: 10})
			if err != nil || len(tasks) != 1 {
				t.Fatalf("child task: %v %v", tasks, err)
			}
			childRun, err := st.Orchestration().GetRun(t.Context(), tasks[0].OrchestrationRunID)
			if err != nil || !childRun.AgentServiceOwned || childRun.ParentRunID != nil {
				t.Fatalf("independent child root lost ownership: %+v %v", childRun, err)
			}
			for _, event := range []*model.OutboxEvent{
				{Tenant: "t", AggregateType: "issue", AggregateID: root.ID.String(), EventType: "issue.audit.v1", AgentServiceOwned: true},
				{Tenant: "t", AggregateType: "agent-task", AggregateID: "malformed-id", EventType: "agent-task.queued.v1", AgentServiceOwned: true},
			} {
				stored, err := st.Outbox().Enqueue(t.Context(), event)
				if err != nil || stored.AgentServiceOwned {
					t.Fatalf("outbox accepted untrusted ownership: %+v %v", stored, err)
				}
			}
			now := time.Now().UTC().Add(time.Second)
			events, err := st.Outbox().ClaimAgentServiceRuntime(t.Context(), "first", now, time.Second, 1)
			if err != nil || len(events) != 1 || events[0].AggregateID != tasks[0].ID.String() || !events[0].AgentServiceOwned {
				t.Fatalf("old or audit events consumed Runtime claim: %+v %v", events, err)
			}
			taken, err := st.Outbox().ClaimAgentServiceRuntime(t.Context(), "replacement", now.Add(2*time.Second), time.Second, 1)
			if err != nil || len(taken) != 1 || taken[0].ID != events[0].ID || taken[0].Attempts != 2 {
				t.Fatalf("scoped claim lost lease recovery: %+v %v", taken, err)
			}
			if err := st.Outbox().MarkDelivered(t.Context(), events[0].ID, "first"); !errors.Is(err, store.ErrConflict) {
				t.Fatalf("old outbox owner acknowledged replacement: %v", err)
			}
			if err := st.Outbox().MarkDelivered(t.Context(), taken[0].ID, "replacement"); err != nil {
				t.Fatal(err)
			}
			approval, err := st.Collaboration().CreateApproval(t.Context(), &model.Approval{Tenant: "t", Namespace: "n", TargetType: "issue", TargetRef: child.ID.String(), IssueID: &child.ID, RunID: &childRun.ID, RequestedBy: actor, ApproverRef: "owner"})
			if err != nil {
				t.Fatal(err)
			}
			events, err = st.Outbox().ClaimAgentServiceRuntime(t.Context(), "approval", now.Add(4*time.Second), time.Second, 10)
			if err != nil || len(events) != 1 || events[0].AggregateID != approval.ID.String() {
				t.Fatalf("approval ownership was not derived from Run: %+v %v", events, err)
			}
			selected, err := st.Orchestration().ListRuns(t.Context(), store.OrchestrationRunFilter{AgentServiceOnly: true, ActiveOnly: true, IncludeTerminalCleanup: true, OldestFirst: true, Limit: 10})
			if err != nil || len(selected) != 2 {
				t.Fatalf("source scan included legacy or lost a child: %v %v", selected, err)
			}
			encoded, _ := json.Marshal(rootRun)
			if strings.Contains(string(encoded), "AgentServiceOwned") || strings.Contains(string(encoded), "portal_owned") {
				t.Fatal("internal scan hint leaked into public model")
			}
			recorder := &ownedScanRepository{OrchestrationRepository: st.Orchestration()}
			worker := &Worker{Store: ownedScanStore{Store: st, repository: recorder}, RequireAgentService: true, Batch: 1}
			worker.ReconcileOnce(t.Context())
			if !recorder.agentServiceOnly || len(recorder.visited) == 0 || recorder.visited[0] != selected[0].ID {
				t.Fatalf("legacy consumed Runtime batch: filter=%v visited=%v", recorder.agentServiceOnly, recorder.visited)
			}
			current, err := st.Orchestration().GetRun(t.Context(), rootRun.ID)
			if err != nil || current.State != model.RunPlanned {
				t.Fatal("scan ownership replaced live admission authorization", current, err)
			}
			rootRun, err = st.Orchestration().TransitionRun(t.Context(), rootRun.ID, rootRun.Version, model.RunFailed, nil, "fixture", "failure")
			if err != nil {
				t.Fatal(err)
			}
			selected, err = st.Orchestration().ListRuns(t.Context(), store.OrchestrationRunFilter{AgentServiceOnly: true, ActiveOnly: true, IncludeTerminalCleanup: true, OldestFirst: true, Limit: 10})
			if err != nil || len(selected) != 2 {
				t.Fatalf("owned terminal cleanup excluded: %v %v", selected, err)
			}
			cleanupIncluded := false
			for _, run := range selected {
				if run.ID == rootRun.ID && run.TerminalCleanupPending {
					cleanupIncluded = true
				}
			}
			if !cleanupIncluded {
				t.Fatal("owned terminal cleanup obligation disappeared from scan")
			}
			legacyTasks, err := st.Collaboration().ListAgentTasks(t.Context(), store.AgentTaskFilter{IssueID: legacy.ID, Limit: 2})
			if err != nil || len(legacyTasks) != 1 {
				t.Fatalf("legacy fixture task: %+v %v", legacyTasks, err)
			}
			attempts := make([]*model.ExecutionAttempt, 0, 2)
			for _, task := range []*model.AgentTask{legacyTasks[0], tasks[0]} {
				_, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(t.Context(), store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version, RuntimeBinding: json.RawMessage(`{}`)}, &model.ExecutionAttempt{BackendKind: model.DataPlaneHostedRuntime, RuntimePoolName: "p", State: model.ExecutionQueued, RuntimeBinding: json.RawMessage(`{}`), RequiredCapabilities: json.RawMessage(`{}`)})
				if err != nil {
					t.Fatal(err)
				}
				attempts = append(attempts, attempt)
			}
			_, err = st.RuntimeRegistry().UpsertRuntimePool(t.Context(), &model.RuntimePool{Tenant: "t", Namespace: "n", Name: "p"})
			if err != nil {
				t.Fatal(err)
			}
			host, err := st.RuntimeRegistry().UpsertRuntimeHost(t.Context(), &model.RuntimeHost{Tenant: "t", Namespace: "n", HostKey: "claim-fixture", PoolName: "p", State: model.RuntimeHostOnline, Capacity: 2})
			if err != nil {
				t.Fatal(err)
			}
			claimed, err := st.ExecutionAttempts().Claim(t.Context(), store.ExecutionClaim{AgentServiceOnly: true, Tenant: "t", Namespace: "n", RuntimePoolName: "p", HostID: host.ID, HostGeneration: host.LeaseGeneration, LeaseOwner: "claim-fixture", LeaseToken: "claim-token", LeaseTTL: time.Minute})
			if err != nil || claimed.ID != attempts[1].ID {
				t.Fatalf("Host claimed earlier legacy attempt: %+v %v", claimed, err)
			}
			old, err := st.ExecutionAttempts().Get(t.Context(), attempts[0].ID)
			if err != nil || old.State != model.ExecutionQueued || old.HostID != nil {
				t.Fatalf("scoped claim mutated legacy attempt: %+v %v", old, err)
			}
			scopedTasks, err := st.Collaboration().ListAgentTasks(t.Context(), store.AgentTaskFilter{AgentServiceOnly: true, OmitInputs: true, Limit: 10})
			if err != nil || len(scopedTasks) != 1 || scopedTasks[0].ID != tasks[0].ID {
				t.Fatalf("Task scope: %+v %v", scopedTasks, err)
			}
			sweepAfter := uuid.Nil
			scopedAttempts, err := st.ExecutionAttempts().List(t.Context(), store.ExecutionAttemptFilter{AgentServiceOnly: true, State: model.ExecutionAssigned, SweepAfterID: &sweepAfter, Limit: 10})
			if err != nil || len(scopedAttempts) != 1 || scopedAttempts[0].ID != claimed.ID {
				t.Fatalf("Attempt scope: %+v %v", scopedAttempts, err)
			}
			scopedAttempts, err = st.ExecutionAttempts().List(t.Context(), store.ExecutionAttemptFilter{AgentServiceOnly: true, State: model.ExecutionAssigned, SweepAfterID: &claimed.ID, Limit: 10})
			if err != nil || len(scopedAttempts) != 0 {
				t.Fatalf("Attempt UUID continuation repeated row: %+v %v", scopedAttempts, err)
			}
			after := uuid.Nil
			scopedApprovals, err := st.Collaboration().ListApprovals(t.Context(), store.ApprovalFilter{AgentServiceOnly: true, AfterID: &after, Status: model.ApprovalPending, Limit: 10})
			if err != nil || len(scopedApprovals) != 1 || scopedApprovals[0].ID != approval.ID {
				t.Fatalf("Approval scope: %+v %v", scopedApprovals, err)
			}
		})
	}
}

func TestRetryableInputSweepBoundsWritesPerInput(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			st := cancellationStore(t, driver)
			ctx := t.Context()
			actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
			sessionID := uuid.New()
			contract, _ := json.Marshal(map[string]string{"public_session_id": sessionID.String(), "portal_command_id": "retry-fixture"})
			invocation, _, err := st.Endpoints().ReserveInvocation(ctx, &model.EndpointInvocation{EndpointID: sessionID, Mode: model.EndpointJobMode, Contract: contract})
			if err != nil {
				t.Fatal(err)
			}
			seed := func(owned bool) *model.AgentTask {
				t.Helper()
				source, ref := "", ""
				if owned {
					source, ref = "endpoint", invocation.ID.String()
				}
				issue, err := st.Collaboration().CreateIssue(ctx, &model.Issue{Tenant: "t", Namespace: "n", Title: "inputs", Creator: actor, SourceType: source, SourceRef: ref, AssigneeType: model.AssigneeAgent, AssigneeRef: "a"})
				if err != nil {
					t.Fatal(err)
				}
				for i := 0; i < 17; i++ {
					_, err = st.Collaboration().CreateComment(ctx, store.CreateCommentRequest{Comment: &model.Comment{IssueID: issue.ID, Author: actor, Content: "retry input", Type: model.CommentGeneral}, Targets: []store.CommentTarget{{TargetType: model.AssigneeAgent, TargetRef: "a", AgentRef: "a", RouteType: model.RouteExplicit}}})
					if err != nil {
						t.Fatal(err)
					}
				}
				tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
				if err != nil || len(tasks) != 1 || len(tasks[0].Inputs) != 17 {
					t.Fatal(tasks, err)
				}
				claimed, err := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version, RuntimeBinding: json.RawMessage(`{}`)})
				if err != nil {
					t.Fatal(err)
				}
				ids := make([]uuid.UUID, 0, 17)
				for _, input := range claimed.Inputs {
					ids = append(ids, input.ID)
				}
				if _, err = st.Collaboration().FailTaskInputDelivery(ctx, claimed.ID, ids, "temporary delivery failure", 5); err != nil {
					t.Fatal(err)
				}
				return claimed
			}
			legacy, owned := seed(false), seed(true)
			now := time.Now().UTC().Add(time.Hour)
			for round := 0; round < 6; round++ {
				affected, err := st.Collaboration().RequeueRetryableInputs(ctx, now, 3, true)
				if err != nil || len(affected) != 1 || affected[0] != owned.ID {
					t.Fatal(affected, err)
				}
				current, err := st.Collaboration().GetAgentTask(ctx, owned.ID)
				if err != nil {
					t.Fatal(err)
				}
				planned := 0
				for _, input := range current.Inputs {
					if input.State == model.TaskInputPlanned {
						planned++
					}
				}
				expected := min((round+1)*3, 17)
				if planned != expected || current.Status != model.AgentTaskQueued {
					t.Fatalf("one Task expanded the input write budget: round=%d planned=%d expected=%d status=%s", round, planned, expected, current.Status)
				}
			}
			affected, err := st.Collaboration().RequeueRetryableInputs(ctx, now, 3, true)
			if err != nil || len(affected) != 0 {
				t.Fatal(affected, err)
			}
			old, err := st.Collaboration().GetAgentTask(ctx, legacy.ID)
			if err != nil || old.Status != model.AgentTaskDispatched || old.Version != legacy.Version {
				t.Fatalf("legacy task was requeued: %+v %v", old, err)
			}
			for _, input := range old.Inputs {
				if input.State != model.TaskInputRetrying {
					t.Fatal("legacy input was mutated", input)
				}
			}
		})
	}
}

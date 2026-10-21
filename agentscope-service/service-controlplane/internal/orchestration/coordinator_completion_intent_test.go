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
	"encoding/json"
	"errors"
	"testing"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
)

func coordinatorIntentFixture(t *testing.T, st store.Store, modes ...model.OrchestrationRunMode) (*model.Issue, *model.RunNode, *model.AgentTask) {
	t.Helper()
	ctx := t.Context()
	actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
	root, err := st.Collaboration().CreateIssue(ctx, &model.Issue{Tenant: "t", Namespace: "n", Title: "Team recovery", Creator: actor})
	if err != nil {
		t.Fatal(err)
	}
	mode := model.RunModeAdaptive
	if len(modes) > 0 {
		mode = modes[0]
	}
	run, err := st.Orchestration().CreateRun(ctx, &model.OrchestrationRun{Tenant: "t", Namespace: "n", RootIssueID: root.ID, Mode: mode, State: model.RunRunning, CreatedBy: actor})
	if err != nil {
		t.Fatal(err)
	}
	node, err := st.Orchestration().CreateNode(ctx, &model.RunNode{RunID: run.ID, Tenant: "t", Namespace: "n", IssueID: &root.ID, NodeKey: "leader", Type: model.RunNodeTeam, State: model.RunNodeWaiting})
	if err != nil {
		t.Fatal(err)
	}
	task, err := st.Collaboration().CreateRunAgentTask(ctx, store.RunTaskRequest{RunID: run.ID, NodeID: node.ID, IssueID: root.ID, AgentRef: "leader", TeamRole: "leader", Leader: true, Originator: actor})
	if err != nil {
		t.Fatal(err)
	}
	task, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version})
	if err != nil {
		t.Fatal(err)
	}
	task, err = st.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
	if err != nil {
		t.Fatal(err)
	}
	return root, node, task
}

func TestCoordinatorIntentRecoversOnlyExplicitCompletionAndFencesLateWork(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			st := cancellationStore(t, driver)
			for _, requested := range []bool{false, true} {
				t.Run(map[bool]string{true: "explicit", false: "plain-output"}[requested], func(t *testing.T) {
					root, node, task := coordinatorIntentFixture(t, st)
					output := json.RawMessage(`{"count":9007199254740993,"ok":true}`)
					actor := model.Actor{Type: model.ActorAgent, Ref: "leader"}
					if requested {
						if err := (&Service{Store: st}).RequestCoordinatorCompletion(t.Context(), task, output, actor); err != nil {
							t.Fatal(err)
						}
					}
					var err error
					task, err = st.Collaboration().CompleteAgentTask(t.Context(), task.ID, store.TaskCompletion{ExpectedVersion: task.Version, Result: output, CoordinatorConclusion: requested})
					if err != nil {
						t.Fatal(err)
					}
					// Replace the Service/Engine after the physical Task committed, simulating
					// restart before the caller could finalize its semantic Team node.
					if err = (&Engine{Store: st}).ReconcileRun(t.Context(), task.OrchestrationRunID); err != nil {
						t.Fatal(err)
					}
					persisted, err := st.Orchestration().GetNode(t.Context(), node.ID)
					if err != nil {
						t.Fatal(err)
					}
					if !requested {
						if persisted.State != model.RunNodeWaiting {
							t.Fatal("ordinary text completed Team", persisted.State)
						}
						return
					}
					if persisted.State != model.RunNodeSucceeded {
						t.Fatal("explicit conclusion not recovered", persisted.State)
					}
					if _, err = st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "late child", Creator: actor, ParentIssueID: &root.ID, SourceType: "agent-task", SourceRef: task.ID.String()}); !errors.Is(err, store.ErrConflict) {
						t.Fatal("closed leader accepted delegation", err)
					}
					if _, err = st.Collaboration().CreateRunAgentTask(t.Context(), store.RunTaskRequest{RunID: task.OrchestrationRunID, NodeID: node.ID, IssueID: root.ID, AgentRef: "leader", Leader: true, Originator: actor}); !errors.Is(err, store.ErrConflict) {
						t.Fatal("closed coordinator accepted a Task", err)
					}
					if _, err = st.Orchestration().CreateNode(t.Context(), &model.RunNode{RunID: task.OrchestrationRunID, Tenant: "t", Namespace: "n", NodeKey: "late", Type: model.RunNodeAgent}); !errors.Is(err, store.ErrConflict) {
						t.Fatal("closed Run accepted node", err)
					}
					if _, err = (&Service{Store: st}).CompleteCoordinatorNode(t.Context(), task.ID, json.RawMessage(`{"ok":true,"count":9007199254740993}`), actor); err != nil {
						t.Fatal("idempotent completion failed", err)
					}
					if _, err = (&Service{Store: st}).CompleteCoordinatorNode(t.Context(), task.ID, json.RawMessage(`{"ok":false}`), actor); !errors.Is(err, store.ErrConflict) {
						t.Fatal("completion output mutated", err)
					}
					events, err := st.Orchestration().ListRunEvents(t.Context(), task.OrchestrationRunID, 0, 30)
					if err != nil {
						t.Fatal(err)
					}
					count := 0
					for _, event := range events {
						if event.Type == "node.succeeded" {
							count++
						}
					}
					if count != 1 {
						t.Fatal("duplicate completion event", count)
					}
				})
			}
		})
	}
}

func TestCoalescedInputPreventsCancellingAnEmptyCoordinatorSnapshot(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			st := cancellationStore(t, driver)
			root, node, leader := coordinatorIntentFixture(t, st)
			duplicate, err := st.Collaboration().CreateRunAgentTask(t.Context(), store.RunTaskRequest{RunID: leader.OrchestrationRunID, NodeID: node.ID, IssueID: root.ID, AgentRef: "leader", TeamRole: "leader", Leader: true, Originator: model.Actor{Type: model.ActorHuman, Ref: "owner"}})
			if err != nil {
				t.Fatal(err)
			}
			if _, _, err = (&Service{Store: st}).ValidateCoordinatorNodeCompletion(t.Context(), leader.ID); err != nil {
				t.Fatal(err)
			}
			_, err = st.Collaboration().CreateComment(t.Context(), store.CreateCommentRequest{Comment: &model.Comment{IssueID: root.ID, Author: model.Actor{Type: model.ActorHuman, Ref: "owner"}, Content: "new outcome"}, Targets: []store.CommentTarget{{TargetType: model.AssigneeAgent, TargetRef: "leader", AgentRef: "leader", TeamRole: "leader", RouteType: model.RouteTeamLeader, ParentTaskID: &leader.ID}}})
			if err != nil {
				t.Fatal(err)
			}
			if _, err = st.Collaboration().CancelAgentTask(t.Context(), duplicate.ID, duplicate.Version); !errors.Is(err, store.ErrConflict) {
				t.Fatal("stale empty snapshot cancelled input", err)
			}
			current, err := st.Collaboration().GetAgentTask(t.Context(), duplicate.ID)
			if err != nil || current.Status != model.AgentTaskQueued || len(current.Inputs) != 1 {
				t.Fatal(current, err)
			}
			if _, _, err = (&Service{Store: st}).ValidateCoordinatorNodeCompletion(t.Context(), leader.ID); !errors.Is(err, store.ErrConflict) {
				t.Fatal("pending input omitted", err)
			}
		})
	}
}

func TestDeclaredCoordinatorClosesItsScopeWithoutBlockingSiblingDelegation(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			st := cancellationStore(t, driver)
			root, firstNode, first := coordinatorIntentFixture(t, st, model.RunModeDeclared)
			actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
			secondNode, err := st.Orchestration().CreateNode(t.Context(), &model.RunNode{RunID: first.OrchestrationRunID, Tenant: "t", Namespace: "n", IssueID: &root.ID, NodeKey: "other-team", Type: model.RunNodeTeam, State: model.RunNodeWaiting})
			if err != nil {
				t.Fatal(err)
			}
			second, err := st.Collaboration().CreateRunAgentTask(t.Context(), store.RunTaskRequest{RunID: first.OrchestrationRunID, NodeID: secondNode.ID, IssueID: root.ID, AgentRef: "second-leader", TeamRole: "leader", Leader: true, Originator: actor})
			if err != nil {
				t.Fatal(err)
			}
			first, err = st.Collaboration().CompleteAgentTask(t.Context(), first.ID, store.TaskCompletion{ExpectedVersion: first.Version, Result: json.RawMessage(`true`)})
			if err != nil {
				t.Fatal(err)
			}
			firstNode, err = st.Orchestration().GetNode(t.Context(), firstNode.ID)
			if err != nil {
				t.Fatal(err)
			}
			if _, err = st.Orchestration().FinalizeCoordinatorNode(t.Context(), first.ID, firstNode.Version, json.RawMessage(`true`), actor); err != nil {
				t.Fatal("sibling blocks declared scope", err)
			}
			child, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "other Team child", Creator: actor, ParentIssueID: &root.ID, SourceType: "agent-task", SourceRef: second.ID.String(), Status: model.IssueDone})
			if err != nil {
				t.Fatal("closed sibling rejected open Team delegation", err)
			}
			child, err = st.Collaboration().TransitionIssue(t.Context(), child.ID, child.Version, model.IssueInProgress, actor, "new work")
			if err != nil {
				t.Fatal("open coordinator could not reopen child", err)
			}
			if _, _, err = (&Service{Store: st}).ValidateCoordinatorNodeCompletion(t.Context(), second.ID); !errors.Is(err, store.ErrConflict) {
				t.Fatal("reopened child omitted", err)
			}
			child, err = st.Collaboration().TransitionIssue(t.Context(), child.ID, child.Version, model.IssueDone, actor, "finished")
			if err != nil {
				t.Fatal(err)
			}
			second, err = st.Collaboration().ClaimAgentTask(t.Context(), store.TaskClaim{TaskID: second.ID, ExpectedVersion: second.Version})
			if err != nil {
				t.Fatal(err)
			}
			second, err = st.Collaboration().StartAgentTask(t.Context(), second.ID, second.Version)
			if err != nil {
				t.Fatal(err)
			}
			second, err = st.Collaboration().CompleteAgentTask(t.Context(), second.ID, store.TaskCompletion{ExpectedVersion: second.Version, Result: json.RawMessage(`true`)})
			if err != nil {
				t.Fatal(err)
			}
			secondNode, err = st.Orchestration().GetNode(t.Context(), secondNode.ID)
			if err != nil {
				t.Fatal(err)
			}
			if _, err = st.Orchestration().FinalizeCoordinatorNode(t.Context(), second.ID, secondNode.Version, json.RawMessage(`true`), actor); err != nil {
				t.Fatal(err)
			}
			if _, err = st.Collaboration().TransitionIssue(t.Context(), child.ID, child.Version, model.IssueInProgress, actor, "late reopen"); !errors.Is(err, store.ErrConflict) {
				t.Fatal("closed coordinator reopened its child", err)
			}
			// Reclassifying the source is also a barrier write, independent of status.
			child.SourceRef = first.ID.String()
			if _, err = st.Collaboration().UpdateIssue(t.Context(), child, child.Version, actor); !errors.Is(err, store.ErrConflict) {
				t.Fatal("closed coordinator source mutation accepted", err)
			}
		})
	}
}

func TestCoordinatorPendingDecisionCanChangeUntilPhysicalAcceptance(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			st := cancellationStore(t, driver)
			for _, explicit := range []bool{false, true} {
				t.Run(map[bool]string{false: "plain-task-completion", true: "semantic-completion"}[explicit], func(t *testing.T) {
					_, node, task := coordinatorIntentFixture(t, st)
					actor := model.Actor{Type: model.ActorAgent, Ref: "leader"}
					service := &Service{Store: st}
					oldOutput, newOutput := json.RawMessage(`{"decision":"old"}`), json.RawMessage(`{"decision":"new"}`)
					if err := service.RequestCoordinatorCompletion(t.Context(), task, oldOutput, actor); err != nil {
						t.Fatal(err)
					}
					if err := service.RequestCoordinatorCompletion(t.Context(), task, newOutput, actor); err != nil {
						t.Fatal("pending decision could not be revised", err)
					}
					_, _, err := st.Collaboration().CompleteAgentTaskWithComment(t.Context(), task.ID, store.TaskCompletion{ExpectedVersion: task.Version, Result: oldOutput, CoordinatorConclusion: true}, &model.Comment{Content: "old decision", Author: actor}, nil)
					if !errors.Is(err, store.ErrConflict) {
						t.Fatal("superseded decision completed physical Task", err)
					}
					current, err := st.Collaboration().GetAgentTask(t.Context(), task.ID)
					if err != nil || current.Status != model.AgentTaskRunning || current.Version != task.Version {
						t.Fatal("rejected decision mutated Task", current, err)
					}
					intent, err := service.coordinatorCompletionIntent(t.Context(), task)
					if err != nil || intent.Accepted {
						t.Fatal("rejected decision accepted intent", intent, err)
					}
					current, _, err = st.Collaboration().CompleteAgentTaskWithComment(t.Context(), task.ID, store.TaskCompletion{ExpectedVersion: task.Version, Result: newOutput, CoordinatorConclusion: explicit}, &model.Comment{Content: "new decision", Author: actor}, nil)
					if err != nil {
						t.Fatal(err)
					}
					intent, err = service.coordinatorCompletionIntent(t.Context(), task)
					if err != nil || intent.Accepted != explicit {
						t.Fatal("wrong intent acceptance", intent, err)
					}
					if err = (&Engine{Store: st}).ReconcileRun(t.Context(), task.OrchestrationRunID); err != nil {
						t.Fatal(err)
					}
					persisted, err := st.Orchestration().GetNode(t.Context(), node.ID)
					if err != nil {
						t.Fatal(err)
					}
					expected := model.RunNodeWaiting
					if explicit {
						expected = model.RunNodeSucceeded
					}
					if persisted.State != expected {
						t.Fatalf("physical completion changed semantic state: got %s want %s", persisted.State, expected)
					}
					if !explicit {
						if _, err = service.CompleteCoordinatorNode(t.Context(), current.ID, newOutput, actor); err != nil {
							t.Fatal("terminal leader could not conclude explicitly", err)
						}
					}
					if err = service.RequestCoordinatorCompletion(t.Context(), current, oldOutput, actor); !errors.Is(err, store.ErrConflict) {
						t.Fatal("accepted decision changed", err)
					}
				})
			}
		})
	}
}

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

// Package storetest contains a backend-neutral contract suite.
package storetest

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// RunSuite exercises the terminal Store contract against s.
func RunSuite(t *testing.T, s store.Store) {
	t.Helper()
	ctx := context.Background()
	t.Run("NamespaceAccess", func(t *testing.T) { testNamespaceAccess(t, ctx, s) })
	t.Run("AgentCatalog", func(t *testing.T) { testAgentCatalog(t, ctx, s) })
	t.Run("IssueProperties", func(t *testing.T) { testIssueProperties(t, ctx, s) })
	t.Run("Collaboration", func(t *testing.T) { testCollaboration(t, ctx, s) })
	t.Run("Inbox", func(t *testing.T) { testInbox(t, ctx, s) })
	t.Run("CollaborationReliability", func(t *testing.T) { testCollaborationReliability(t, ctx, s) })
	t.Run("RuntimeRegistryAndExecutions", func(t *testing.T) { testRuntime(t, ctx, s) })
	t.Run("Outbox", func(t *testing.T) { testOutbox(t, ctx, s) })
	t.Run("ConversationTurns", func(t *testing.T) { testConversationTurns(t, ctx, s) })
	t.Run("TerminalRunEvents", func(t *testing.T) { testTerminalRunEvents(t, ctx, s) })
	t.Run("ActiveWorkerFollowUp", func(t *testing.T) { testActiveWorkerFollowUp(t, ctx, s) })
	t.Run("FailedTaskInputs", func(t *testing.T) { testFailedTaskInputs(t, ctx, s) })
}

func testTerminalRunEvents(t *testing.T, ctx context.Context, s store.Store) {
	for _, state := range []controlmodel.OrchestrationRunState{controlmodel.RunSucceeded, controlmodel.RunFailed} {
		issue, err := s.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "terminal-events-" + uuid.NewString(), Namespace: "n", Title: "terminal event", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}})
		if err != nil {
			t.Fatal(err)
		}
		run, err := s.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{Tenant: issue.Tenant, Namespace: issue.Namespace, RootIssueID: issue.ID, Mode: controlmodel.RunModeDirect, State: controlmodel.RunRunning, CreatedBy: issue.Creator})
		if err != nil {
			t.Fatal(err)
		}
		if _, err := s.Orchestration().TransitionRun(ctx, run.ID, run.Version, state, json.RawMessage(`{"answer":42}`), "test", "details"); err != nil {
			t.Fatal(err)
		}
		if _, err := s.Orchestration().TransitionRun(ctx, run.ID, run.Version, state, nil, "", ""); err != store.ErrConflict {
			t.Fatalf("stale transition: %v", err)
		}
		current, err := s.Orchestration().GetRun(ctx, run.ID)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := s.Orchestration().TransitionRun(ctx, run.ID, current.Version, state, json.RawMessage(`{"answer":42}`), "test", "details"); err != nil {
			t.Fatal(err)
		}
		events, err := s.Orchestration().ListRunEvents(ctx, run.ID, 0, 20)
		if err != nil || len(events) != 1 || events[0].Type != "run."+string(state) {
			t.Fatalf("terminal event missing or duplicated: %+v %v", events, err)
		}
		var payload map[string]any
		if err := json.Unmarshal(events[0].Payload, &payload); err != nil || payload["failureMessage"] != "details" || payload["output"].(map[string]any)["answer"] != float64(42) {
			t.Fatalf("lost outcome payload: %s %v", events[0].Payload, err)
		}
	}
}

func testActiveWorkerFollowUp(t *testing.T, ctx context.Context, s store.Store) {
	repo := s.Collaboration()
	issue, err := repo.CreateIssue(ctx, &controlmodel.Issue{Tenant: "followup-" + uuid.NewString(), Namespace: "n", Title: "ongoing work", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker"})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := repo.ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 10})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("tasks=%+v err=%v", tasks, err)
	}
	initial, err := repo.ClaimAgentTask(ctx, store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version, RuntimeBinding: json.RawMessage(`{}`)})
	if err == nil {
		initial, err = repo.StartAgentTask(ctx, initial.ID, initial.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	follow, err := repo.CreateComment(ctx, store.CreateCommentRequest{Comment: &controlmodel.Comment{IssueID: issue.ID, Author: issue.Creator, Content: "also answer the follow-up"}, Targets: []store.CommentTarget{{TargetType: controlmodel.AssigneeAgent, TargetRef: "worker", AgentRef: "worker", RouteType: controlmodel.RouteExplicit}}})
	if err != nil || len(follow.Tasks) != 1 {
		t.Fatalf("follow=%+v err=%v", follow, err)
	}
	next := follow.Tasks[0]
	if next.RunNodeID == initial.RunNodeID || next.OrchestrationRunID != initial.OrchestrationRunID {
		t.Fatalf("independent work reused active node: %+v %+v", initial, next)
	}
	if _, err = repo.CompleteAgentTask(ctx, initial.ID, store.TaskCompletion{ExpectedVersion: initial.Version}); err != nil {
		t.Fatal(err)
	}
	run, err := s.Orchestration().GetRun(ctx, initial.OrchestrationRunID)
	if err != nil || controlmodel.IsOrchestrationRunTerminal(run.State) {
		t.Fatalf("run completed before follow-up: %+v %v", run, err)
	}
	current, err := repo.ClaimAgentTask(ctx, store.TaskClaim{TaskID: next.ID, ExpectedVersion: next.Version, RuntimeBinding: json.RawMessage(`{}`)})
	if err == nil {
		current, err = repo.StartAgentTask(ctx, current.ID, current.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	current, err = repo.GetAgentTask(ctx, current.ID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = repo.CompleteAgentTask(ctx, current.ID, store.TaskCompletion{ExpectedVersion: current.Version, ProcessedInputIDs: []uuid.UUID{current.Inputs[0].ID}}); err != nil {
		t.Fatal(err)
	}
	run, err = s.Orchestration().GetRun(ctx, initial.OrchestrationRunID)
	if err != nil || run.State != controlmodel.RunSucceeded {
		t.Fatalf("follow-up did not converge: %+v %v", run, err)
	}
}

func testFailedTaskInputs(t *testing.T, ctx context.Context, s store.Store) {
	repo := s.Collaboration()
	issue, err := repo.CreateIssue(ctx, &controlmodel.Issue{Tenant: "failure-inputs-" + uuid.NewString(), Namespace: "n", Title: "work", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "admin"}})
	if err != nil {
		t.Fatal(err)
	}
	created, err := repo.CreateComment(ctx, store.CreateCommentRequest{Comment: &controlmodel.Comment{IssueID: issue.ID, Author: issue.Creator, Content: "new request", Type: controlmodel.CommentGeneral}, Targets: []store.CommentTarget{{TargetType: controlmodel.AssigneeAgent, TargetRef: "worker", AgentRef: "worker", RouteType: controlmodel.RouteExplicit}}})
	if err != nil || len(created.Tasks) != 1 {
		t.Fatalf("comment: %+v %v", created, err)
	}
	task := created.Tasks[0]
	claimed, attempt, err := repo.ClaimAgentTaskWithAttempt(ctx, store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version, SessionID: "failure-input-session", RuntimeBinding: json.RawMessage(`{}`)}, &controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged, State: controlmodel.ExecutionAssigned, SessionID: "failure-input-session"})
	if err != nil {
		t.Fatal(err)
	}
	running, err := repo.StartAgentTask(ctx, claimed.ID, claimed.Version)
	if err != nil {
		t.Fatal(err)
	}
	failure := store.TaskFailure{ExpectedVersion: running.Version, AttemptID: attempt.ID, DispatchGeneration: attempt.DispatchGeneration + 1, Code: "missing_tool", Message: "required capability unavailable"}
	if _, _, err := repo.FailAgentTaskWithAttempt(ctx, running.ID, failure); err != store.ErrConflict {
		t.Fatalf("stale failure accepted: %v", err)
	}
	current, _ := repo.GetAgentTask(ctx, running.ID)
	if len(current.Inputs) != 1 || current.Inputs[0].State != controlmodel.TaskInputDelivered {
		t.Fatalf("stale failure changed input: %+v", current.Inputs)
	}
	failure.DispatchGeneration = attempt.DispatchGeneration
	for range 2 {
		if _, _, err := repo.FailAgentTaskWithAttempt(ctx, running.ID, failure); err != nil {
			t.Fatal(err)
		}
	}
	current, err = repo.GetAgentTask(ctx, running.ID)
	if err != nil || current.Status != controlmodel.AgentTaskFailed || current.Inputs[0].State != controlmodel.TaskInputBlocked || current.Inputs[0].LastError != failure.Message || current.Inputs[0].NextAttemptAt != nil {
		t.Fatalf("terminal failure left pending input: %+v %v", current, err)
	}
}

func testConversationTurns(t *testing.T, ctx context.Context, s store.Store) {
	session, err := s.Sessions().Upsert(ctx, &store.Session{Tenant: "turns-" + uuid.NewString(), Namespace: "n", AgentID: uuid.New(), AgentName: "chat", SessionID: uuid.NewString(), Phase: store.SessionPhaseIdle})
	if err != nil {
		t.Fatal(err)
	}
	for i, phase := range []string{store.SessionPhaseIdle, store.TurnStatusFailed, store.SessionPhaseTerminated} {
		for range 2 {
			if err := s.Turns().SyncOnPhase(ctx, session.ID, store.SessionPhaseActive); err != nil {
				t.Fatal(err)
			}
		}
		for range 2 {
			if err := s.Turns().SyncOnPhase(ctx, session.ID, phase); err != nil {
				t.Fatal(err)
			}
		}
		turns, err := s.Turns().List(ctx, session.ID, 10)
		want := []string{store.TurnStatusCompleted, store.TurnStatusFailed, store.TurnStatusAborted}[i]
		if err != nil || len(turns) != i+1 || turns[0].TurnIndex != i+1 || turns[0].Status != want || turns[0].EndedAt == nil {
			t.Fatalf("turn %d: %+v %v", i+1, turns, err)
		}
	}
}

func testAgentCatalog(t *testing.T, ctx context.Context, s store.Store) {
	t.Helper()
	tenant := "catalog-" + uuid.NewString()
	const contenders = 12
	type outcome struct {
		index  int
		result *store.ExternalAgentRegistrationResult
		err    error
	}
	start := make(chan struct{})
	outcomes := make(chan outcome, contenders)
	var wg sync.WaitGroup
	for i := 0; i < contenders; i++ {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			<-start
			hash := sha256.Sum256([]byte(fmt.Sprintf("credential-%d", index)))
			result, err := s.AgentCatalog().RegisterExternal(ctx, store.ExternalAgentRegistration{
				Tenant: tenant, Namespace: "default", AgentKey: "reviewer", DisplayName: "Reviewer",
				InstanceKey: fmt.Sprintf("instance-%d", index), TrustedBootstrap: true,
				NewCredentialHash: hash[:], Capabilities: json.RawMessage(`{"review":true}`), Capacity: 2,
			})
			outcomes <- outcome{index: index, result: result, err: err}
		}(i)
	}
	close(start)
	wg.Wait()
	close(outcomes)
	created := 0
	var registered *store.ExternalAgentRegistrationResult
	for value := range outcomes {
		if value.err == nil {
			created++
			registered = value.result
			continue
		}
		if !errors.Is(value.err, store.ErrConflict) {
			t.Fatalf("concurrent registration returned %v, want conflict", value.err)
		}
	}
	if created != contenders || registered == nil || !registered.CredentialCreated || registered.Agent.Status != controlmodel.AgentActive {
		t.Fatalf("concurrent registration did not converge: created=%d result=%+v", created, registered)
	}
	agents, err := s.AgentCatalog().ListAgents(ctx, store.AgentFilter{Tenant: tenant, Namespace: "default"})
	if err != nil || len(agents) != 1 || agents[0].ID != registered.Agent.ID {
		t.Fatalf("logical Agent identity is not unique: agents=%+v err=%v", agents, err)
	}
	bindings, err := s.AgentCatalog().ListBindings(ctx, registered.Agent.ID, true)
	if err != nil || len(bindings) != 1 || bindings[0].ID != registered.Binding.ID {
		t.Fatalf("default external binding is not unique: bindings=%+v err=%v", bindings, err)
	}
	policies, err := s.Orchestration().ListRuntimePolicies(ctx, tenant, "default", 20)
	if err != nil || len(policies) != 1 || policies[0].AgentRef != registered.Agent.ID.String() {
		t.Fatalf("default runtime policy is not unique: policies=%+v err=%v", policies, err)
	}
	followUpHash := sha256.Sum256([]byte("follow-up"))
	followUp, err := s.AgentCatalog().RegisterExternal(ctx, store.ExternalAgentRegistration{
		Tenant: tenant, Namespace: "default", AgentKey: "reviewer", InstanceKey: "scale-out",
		NewCredentialHash: followUpHash[:], Capabilities: json.RawMessage(`{"review":true}`), Capacity: 4,
	})
	if err != nil || !followUp.CredentialCreated || followUp.Agent.ID != registered.Agent.ID || followUp.Binding.ID != registered.Binding.ID {
		t.Fatalf("open registration did not reuse logical identity: result=%+v err=%v", followUp, err)
	}
	badHash := sha256.Sum256([]byte("forged"))
	if _, err := s.AgentCatalog().RegisterExternal(ctx, store.ExternalAgentRegistration{
		Tenant: tenant, Namespace: "default", AgentKey: "reviewer", InstanceKey: "forged",
		NewCredentialHash: badHash[:],
	}); err != nil {
		t.Fatalf("open registration was rejected: %v", err)
	}
	instances, err := s.RuntimeRegistry().ListAgentInstances(ctx, tenant, "default", registered.Agent.ID)
	if err != nil || len(instances) != contenders+2 {
		t.Fatalf("scale-out should add instances only: instances=%+v err=%v", instances, err)
	}
}

func testCollaborationReliability(t *testing.T, ctx context.Context, s store.Store) {
	repo := s.Collaboration()
	creator := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "reliability-owner"}
	issue, err := repo.CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "reliability-" + uuid.NewString(), Namespace: "default", Title: "concurrent routing", Creator: creator,
	})
	if err != nil {
		t.Fatal(err)
	}

	// Concurrent comments for the same Issue/Agent/role must converge on one
	// queued task without dropping or duplicating any input.
	const comments = 12
	start := make(chan struct{})
	errs := make(chan error, comments)
	var wg sync.WaitGroup
	for i := 0; i < comments; i++ {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			<-start
			_, createErr := repo.CreateComment(ctx, store.CreateCommentRequest{
				Comment: &controlmodel.Comment{IssueID: issue.ID, Author: creator, Content: fmt.Sprintf("input-%02d", index), Type: controlmodel.CommentGeneral},
				Targets: []store.CommentTarget{{TargetType: controlmodel.AssigneeAgent, TargetRef: "worker", AgentRef: "worker", RouteType: controlmodel.RouteExplicit}},
			})
			errs <- createErr
		}(i)
	}
	close(start)
	wg.Wait()
	close(errs)
	for createErr := range errs {
		if createErr != nil {
			t.Fatalf("concurrent comment: %v", createErr)
		}
	}
	tasks, err := repo.ListAgentTasks(ctx, store.AgentTaskFilter{Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: issue.ID, AgentRef: "worker", Limit: 100})
	if err != nil {
		t.Fatal(err)
	}
	if len(tasks) != 1 || tasks[0].Status != controlmodel.AgentTaskQueued || len(tasks[0].Inputs) != comments {
		t.Fatalf("queued coalesce lost work: tasks=%+v", tasks)
	}
	seen := make(map[uuid.UUID]bool, comments)
	for _, input := range tasks[0].Inputs {
		if seen[input.CommentID] {
			t.Fatalf("duplicate input for comment %s", input.CommentID)
		}
		seen[input.CommentID] = true
	}

	// Once the snapshot is claimed, a new comment must be placed in a queued
	// successor rather than mutating the dispatched task's input snapshot.
	claimed, err := repo.ClaimAgentTask(ctx, store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version, SessionID: "reliability-session"})
	if err != nil {
		t.Fatal(err)
	}
	before := len(claimed.Inputs)
	result, err := repo.CreateComment(ctx, store.CreateCommentRequest{
		Comment: &controlmodel.Comment{IssueID: issue.ID, Author: creator, Content: "arrived while running", Type: controlmodel.CommentGeneral},
		Targets: []store.CommentTarget{{TargetType: controlmodel.AssigneeAgent, TargetRef: "worker", AgentRef: "worker", RouteType: controlmodel.RouteExplicit}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Tasks) != 1 || result.Tasks[0].ID == claimed.ID || result.Tasks[0].Status != controlmodel.AgentTaskQueued {
		t.Fatalf("running task did not create a successor: %+v", result.Tasks)
	}
	successor, err := repo.GetAgentTask(ctx, result.Tasks[0].ID)
	if err != nil || len(successor.Inputs) != 1 {
		t.Fatalf("successor input missing: task=%+v err=%v", successor, err)
	}
	unchanged, err := repo.GetAgentTask(ctx, claimed.ID)
	if err != nil || len(unchanged.Inputs) != before {
		t.Fatalf("claim snapshot was mutated: task=%+v err=%v", unchanged, err)
	}
	delegated, err := repo.CreateIssue(ctx, &controlmodel.Issue{Tenant: issue.Tenant, Namespace: issue.Namespace,
		Title: "delegated review", Creator: creator, SourceType: "agent-task", SourceRef: claimed.ID.String()})
	if err != nil {
		t.Fatal(err)
	}
	lineage, err := repo.CreateComment(ctx, store.CreateCommentRequest{
		Comment: &controlmodel.Comment{IssueID: delegated.ID, Author: creator, Content: "return to parent", Type: controlmodel.CommentResult},
		Targets: []store.CommentTarget{{TargetType: controlmodel.AssigneeAgent, TargetRef: claimed.AgentRef, AgentRef: claimed.AgentRef,
			ParentTaskID: &claimed.ID, RouteType: controlmodel.RouteFollowUp}},
	})
	if err != nil || len(lineage.Tasks) != 1 {
		t.Fatalf("parent lineage route: %+v %v", lineage, err)
	}
	lineageTask, err := repo.GetAgentTask(ctx, lineage.Tasks[0].ID)
	if err != nil || lineageTask.ParentTaskID == nil || *lineageTask.ParentTaskID != claimed.ID {
		t.Fatalf("parent lineage was lost: task=%+v err=%v", lineageTask, err)
	}
	derived, err := repo.CreateComment(ctx, store.CreateCommentRequest{
		Comment: &controlmodel.Comment{IssueID: delegated.ID,
			Author:  controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: claimed.AgentRef},
			Content: "explicit worker continuation", Type: controlmodel.CommentStatus, SourceTaskID: &claimed.ID},
		Targets: []store.CommentTarget{{TargetType: controlmodel.AssigneeAgent, TargetRef: "review-worker",
			AgentRef: "review-worker", RouteType: controlmodel.RouteExplicit}},
	})
	if err != nil || len(derived.Tasks) != 1 {
		t.Fatalf("derived parent route: %+v %v", derived, err)
	}
	derivedTask, err := repo.GetAgentTask(ctx, derived.Tasks[0].ID)
	if err != nil || derivedTask.ParentTaskID == nil || *derivedTask.ParentTaskID != claimed.ID ||
		derivedTask.DelegatedFromTaskID == nil || *derivedTask.DelegatedFromTaskID != claimed.ID {
		t.Fatalf("comment source lineage was lost: task=%+v err=%v", derivedTask, err)
	}

	// A follow-up created after its source node completed must get a fresh node.
	// Reusing the terminal node lets the Run become succeeded while the new Task
	// is still queued and executing.
	threadIssue, err := repo.CreateIssue(ctx, &controlmodel.Issue{
		Tenant: issue.Tenant, Namespace: issue.Namespace, Title: "terminal node follow-up", Creator: creator,
	})
	if err != nil {
		t.Fatal(err)
	}
	rootInput, err := repo.CreateComment(ctx, store.CreateCommentRequest{
		Comment: &controlmodel.Comment{IssueID: threadIssue.ID, Author: creator,
			Content: "ask responder", Type: controlmodel.CommentGeneral},
		Targets: []store.CommentTarget{{TargetType: controlmodel.AssigneeAgent, TargetRef: "responder",
			AgentRef: "responder", RouteType: controlmodel.RouteExplicit}},
	})
	if err != nil || len(rootInput.Tasks) != 1 {
		t.Fatalf("create responder: result=%+v err=%v", rootInput, err)
	}
	responder, err := repo.ClaimAgentTask(ctx, store.TaskClaim{
		TaskID: rootInput.Tasks[0].ID, ExpectedVersion: rootInput.Tasks[0].Version,
	})
	if err == nil {
		responder, err = repo.StartAgentTask(ctx, responder.ID, responder.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	responder, err = repo.GetAgentTask(ctx, responder.ID)
	if err != nil {
		t.Fatal(err)
	}
	delegation, err := repo.CreateComment(ctx, store.CreateCommentRequest{
		Comment: &controlmodel.Comment{IssueID: threadIssue.ID,
			Author:  controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: responder.AgentRef},
			Content: "ask specialist", Type: controlmodel.CommentGeneral, SourceTaskID: &responder.ID},
		Targets: []store.CommentTarget{{TargetType: controlmodel.AssigneeAgent, TargetRef: "specialist",
			AgentRef: "specialist", RouteType: controlmodel.RouteExplicit}},
	})
	if err != nil || len(delegation.Tasks) != 1 {
		t.Fatalf("create specialist: result=%+v err=%v", delegation, err)
	}
	responderInputIDs := make([]uuid.UUID, 0, len(responder.Inputs))
	for _, input := range responder.Inputs {
		responderInputIDs = append(responderInputIDs, input.ID)
	}
	responder, err = repo.CompleteAgentTask(ctx, responder.ID, store.TaskCompletion{
		ExpectedVersion: responder.Version, ProcessedInputIDs: responderInputIDs,
	})
	if err != nil {
		t.Fatal(err)
	}
	specialist, err := repo.ClaimAgentTask(ctx, store.TaskClaim{
		TaskID: delegation.Tasks[0].ID, ExpectedVersion: delegation.Tasks[0].Version,
	})
	if err == nil {
		specialist, err = repo.StartAgentTask(ctx, specialist.ID, specialist.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	specialist, err = repo.GetAgentTask(ctx, specialist.ID)
	if err != nil {
		t.Fatal(err)
	}
	specialistInputIDs := make([]uuid.UUID, 0, len(specialist.Inputs))
	for _, input := range specialist.Inputs {
		specialistInputIDs = append(specialistInputIDs, input.ID)
	}
	_, returned, err := repo.CompleteAgentTaskWithComment(ctx, specialist.ID, store.TaskCompletion{
		ExpectedVersion: specialist.Version, ProcessedInputIDs: specialistInputIDs,
	}, &controlmodel.Comment{IssueID: threadIssue.ID,
		Author:  controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: specialist.AgentRef},
		Content: "specialist result", Type: controlmodel.CommentResult}, []store.CommentTarget{{
		TargetType: controlmodel.AssigneeAgent, TargetRef: responder.AgentRef, AgentRef: responder.AgentRef,
		ParentTaskID: &responder.ID, RouteType: controlmodel.RouteFollowUp,
	}})
	if err != nil || returned == nil || len(returned.Routes) != 1 || returned.Routes[0].TaskID == nil {
		t.Fatalf("return specialist result: comment=%+v err=%v", returned, err)
	}
	resumed, err := repo.GetAgentTask(ctx, *returned.Routes[0].TaskID)
	if err != nil || resumed.OrchestrationRunID != responder.OrchestrationRunID ||
		resumed.RunNodeID == responder.RunNodeID {
		t.Fatalf("follow-up reused terminal node: responder=%+v resumed=%+v err=%v", responder, resumed, err)
	}
	run, err := s.Orchestration().GetRun(ctx, responder.OrchestrationRunID)
	if err != nil || controlmodel.IsOrchestrationRunTerminal(run.State) {
		t.Fatalf("run terminated with queued follow-up: run=%+v err=%v", run, err)
	}

	// A directly mentioned consultant participates in the conversation without
	// taking lifecycle ownership from the assigned Agent.
	ownedIssue, err := repo.CreateIssue(ctx, &controlmodel.Issue{
		Tenant: issue.Tenant, Namespace: issue.Namespace, Title: "consult without takeover", Creator: creator,
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "issue-owner",
	})
	if err != nil {
		t.Fatal(err)
	}
	ownerTasks, err := repo.ListAgentTasks(ctx, store.AgentTaskFilter{
		IssueID: ownedIssue.ID, AgentRef: "issue-owner", Limit: 2,
	})
	if err != nil || len(ownerTasks) != 1 {
		t.Fatalf("assigned task: tasks=%+v err=%v", ownerTasks, err)
	}
	ownerTask, err := repo.ClaimAgentTask(ctx, store.TaskClaim{
		TaskID: ownerTasks[0].ID, ExpectedVersion: ownerTasks[0].Version,
	})
	if err == nil {
		ownerTask, err = repo.StartAgentTask(ctx, ownerTask.ID, ownerTask.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	consultRequest, err := repo.CreateComment(ctx, store.CreateCommentRequest{
		Comment: &controlmodel.Comment{IssueID: ownedIssue.ID, Author: creator,
			Content: "ask consultant", Type: controlmodel.CommentGeneral},
		Targets: []store.CommentTarget{{TargetType: controlmodel.AssigneeAgent, TargetRef: "consultant",
			AgentRef: "consultant", RouteType: controlmodel.RouteExplicit}},
	})
	if err != nil || len(consultRequest.Tasks) != 1 {
		t.Fatalf("consultant task: result=%+v err=%v", consultRequest, err)
	}
	consultant, err := repo.ClaimAgentTask(ctx, store.TaskClaim{
		TaskID: consultRequest.Tasks[0].ID, ExpectedVersion: consultRequest.Tasks[0].Version,
	})
	if err == nil {
		consultant, err = repo.StartAgentTask(ctx, consultant.ID, consultant.Version)
	}
	if err == nil {
		consultant, err = repo.GetAgentTask(ctx, consultant.ID)
	}
	if err != nil {
		t.Fatal(err)
	}
	consultInputIDs := make([]uuid.UUID, 0, len(consultant.Inputs))
	for _, input := range consultant.Inputs {
		consultInputIDs = append(consultInputIDs, input.ID)
	}
	if _, err = repo.CompleteAgentTask(ctx, consultant.ID, store.TaskCompletion{
		ExpectedVersion: consultant.Version, ProcessedInputIDs: consultInputIDs,
	}); err != nil {
		t.Fatal(err)
	}
	ownedIssue, err = repo.GetIssue(ctx, ownedIssue.ID)
	if err != nil || ownedIssue.Status != controlmodel.IssueInProgress {
		t.Fatalf("consultant advanced assigned Issue lifecycle: issue=%+v err=%v", ownedIssue, err)
	}

	// Duplicate delivery failure reports are monotonic. Max attempts produces a
	// real dead-letter state, and explicit replay creates new lineage without
	// overwriting the original input record.
	dispatched, err := repo.ClaimAgentTask(ctx, store.TaskClaim{TaskID: successor.ID, ExpectedVersion: successor.Version, SessionID: "dead-letter-session"})
	if err != nil {
		t.Fatal(err)
	}
	inputID := dispatched.Inputs[0].ID
	if _, err = repo.FailTaskInputDelivery(ctx, dispatched.ID, []uuid.UUID{inputID}, "transport unavailable", 1); err != nil {
		t.Fatal(err)
	}
	dead, err := repo.GetAgentTask(ctx, dispatched.ID)
	if err != nil || dead.Inputs[0].State != controlmodel.TaskInputDeadLetter {
		t.Fatalf("input was not dead-lettered: task=%+v err=%v", dead, err)
	}
	replay, err := repo.ReplayDeadLetterInputs(ctx, dead.ID, []uuid.UUID{inputID}, creator)
	if err != nil {
		t.Fatal(err)
	}
	replay, err = repo.GetAgentTask(ctx, replay.ID)
	if err != nil {
		t.Fatal(err)
	}
	if replay.ID == dead.ID || replay.RetryOfTaskID == nil || *replay.RetryOfTaskID != dead.ID || len(replay.Inputs) != 1 || replay.Inputs[0].ID == inputID {
		t.Fatalf("dead-letter replay lineage is invalid: source=%+v replay=%+v", dead, replay)
	}
}

func testCollaboration(t *testing.T, ctx context.Context, s store.Store) {
	repo := s.Collaboration()
	creator := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	issue, err := repo.CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "contract", Namespace: "default", Title: "review the change",
		Priority: "high", Creator: creator,
	})
	if err != nil || issue.ID == uuid.Nil || issue.Version != 1 {
		t.Fatalf("create issue: issue=%+v err=%v", issue, err)
	}
	assigned, task, err := repo.AssignIssue(ctx, issue.ID, issue.Version, controlmodel.AssigneeAgent, "reviewer", creator)
	if err != nil || task == nil || task.Status != controlmodel.AgentTaskQueued {
		t.Fatalf("assign issue: issue=%+v task=%+v err=%v", assigned, task, err)
	}
	if _, _, err := repo.AssignIssue(ctx, issue.ID, issue.Version, controlmodel.AssigneeAgent, "other", creator); !errors.Is(err, store.ErrConflict) {
		t.Fatalf("stale issue version must conflict, got %v", err)
	}

	// Endpoint/API-created work has no accountable human. Delegating a child
	// from its task must preserve that nullable lineage instead of failing while
	// reading the source task from PostgreSQL.
	system := controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "endpoint:contract"}
	systemIssue, err := repo.CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "contract", Namespace: "default", Title: "API-created parent", Creator: system,
	})
	if err != nil {
		t.Fatalf("create system issue: %v", err)
	}
	_, systemTask, err := repo.AssignIssue(ctx, systemIssue.ID, systemIssue.Version,
		controlmodel.AssigneeAgent, "system-leader", system)
	if err != nil || systemTask == nil || systemTask.AccountableHumanRef != "" {
		t.Fatalf("assign system issue: task=%+v err=%v", systemTask, err)
	}
	child, err := repo.CreateIssue(ctx, &controlmodel.Issue{
		Tenant: systemIssue.Tenant, Namespace: systemIssue.Namespace, Title: "delegated API child",
		ParentIssueID: &systemIssue.ID, Creator: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "system-leader"},
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "system-worker",
		SourceType: "agent-task", SourceRef: systemTask.ID.String(),
	})
	if err != nil {
		t.Fatalf("create child from source task without accountable human: %v", err)
	}
	childTasks, err := repo.ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: child.ID, Limit: 2})
	if err != nil || len(childTasks) != 1 || childTasks[0].ParentTaskID == nil ||
		*childTasks[0].ParentTaskID != systemTask.ID || childTasks[0].AccountableHumanRef != "" {
		t.Fatalf("system child task lineage: tasks=%+v err=%v", childTasks, err)
	}

	commentID := uuid.New()
	result, err := repo.CreateComment(ctx, store.CreateCommentRequest{
		Comment: &controlmodel.Comment{
			ID: commentID, IssueID: issue.ID, Author: creator,
			Content: "@builder please implement", Type: controlmodel.CommentGeneral,
		},
		Mentions: []controlmodel.Mention{{TargetType: controlmodel.AssigneeAgent, TargetRef: "builder"}},
		Targets:  []store.CommentTarget{{TargetType: controlmodel.AssigneeAgent, TargetRef: "builder", AgentRef: "builder", RouteType: controlmodel.RouteExplicit}},
	})
	if err != nil || len(result.Tasks) != 1 || len(result.Routes) != 1 {
		t.Fatalf("create routed comment: result=%+v err=%v", result, err)
	}
	worker := &result.Tasks[0]
	worker, err = repo.ClaimAgentTask(ctx, store.TaskClaim{TaskID: worker.ID, ExpectedVersion: worker.Version, SessionID: "worker-session"})
	if err != nil || worker.Status != controlmodel.AgentTaskDispatched {
		t.Fatalf("claim task: task=%+v err=%v", worker, err)
	}
	inputIDs := make([]uuid.UUID, 0, len(worker.Inputs))
	for _, input := range worker.Inputs {
		inputIDs = append(inputIDs, input.ID)
	}
	inputs, err := repo.AcknowledgeTaskInputs(ctx, worker.ID, inputIDs)
	if err != nil || len(inputs) != len(inputIDs) {
		t.Fatalf("ack inputs: inputs=%+v err=%v", inputs, err)
	}
	worker, err = repo.StartAgentTask(ctx, worker.ID, worker.Version)
	if err != nil || worker.Status != controlmodel.AgentTaskRunning {
		t.Fatalf("start task: task=%+v err=%v", worker, err)
	}
	worker, err = repo.CompleteAgentTask(ctx, worker.ID, store.TaskCompletion{
		ExpectedVersion: worker.Version, Summary: "implemented",
		Result: json.RawMessage(`{"ok":true}`), ProcessedInputIDs: inputIDs,
	})
	if err != nil || worker.Status != controlmodel.AgentTaskCompleted || worker.CompletedAt == nil {
		t.Fatalf("complete task: task=%+v err=%v", worker, err)
	}

	comments, err := repo.ListComments(ctx, issue.ID, store.CommentListOptions{Limit: 20})
	if err != nil || len(comments) != 1 {
		t.Fatalf("list comments: comments=%+v err=%v", comments, err)
	}
	tasks, err := repo.ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 20})
	if err != nil || len(tasks) < 2 {
		t.Fatalf("list issue tasks: tasks=%+v err=%v", tasks, err)
	}

	team, err := repo.CreateTeam(ctx, &controlmodel.CollaborationTeam{Tenant: "contract", Namespace: "default", Name: "delivery", LeaderAgentRef: "lead"})
	if err != nil {
		t.Fatalf("create team: %v", err)
	}
	teamWithRoster, err := repo.CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "contract", Namespace: "default", Name: "delivery-with-roster", LeaderAgentRef: "lead-roster",
		Members: []controlmodel.CollaborationTeamMember{{AgentRef: "worker-roster", Role: "researcher"}},
	})
	if err != nil || teamWithRoster.Version != 1 || len(teamWithRoster.Members) != 1 || teamWithRoster.Members[0].TeamID != teamWithRoster.ID {
		t.Fatalf("create team with initial roster: team=%+v err=%v", teamWithRoster, err)
	}
	loadedRoster, err := repo.GetTeam(ctx, teamWithRoster.ID)
	if err != nil || len(loadedRoster.Members) != 1 || loadedRoster.Members[0].AgentRef != "worker-roster" {
		t.Fatalf("load team with initial roster: team=%+v err=%v", loadedRoster, err)
	}
	invalidTeamID := uuid.New()
	_, err = repo.CreateTeam(ctx, &controlmodel.CollaborationTeam{
		ID: invalidTeamID, Tenant: "contract", Namespace: "default", Name: "invalid-roster", LeaderAgentRef: "lead-invalid",
		Members: []controlmodel.CollaborationTeamMember{
			{AgentRef: "worker-a", Role: "duplicate"},
			{AgentRef: "worker-b", Role: "duplicate"},
		},
	})
	if err == nil {
		t.Fatal("create team should reject duplicate initial roles")
	}
	if _, loadErr := repo.GetTeam(ctx, invalidTeamID); !errors.Is(loadErr, store.ErrNotFound) {
		t.Fatalf("invalid initial roster must be atomic: err=%v", loadErr)
	}
	member, err := repo.AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{TeamID: team.ID, Role: "worker", AgentRef: "worker"})
	if err != nil || member.ID == uuid.Nil {
		t.Fatalf("add team member: member=%+v err=%v", member, err)
	}
	team, err = repo.GetTeam(ctx, team.ID)
	if err != nil || team.Version != 2 || len(team.Members) != 1 {
		t.Fatalf("Team version after member add: team=%+v err=%v", team, err)
	}
	member.Role, member.Instructions = "coordinator", "Own the delivery hand-off"
	member, err = repo.UpdateTeamMember(ctx, member, team.Version)
	if err != nil || member.Role != "coordinator" || member.Instructions == "" {
		t.Fatalf("update team member: member=%+v err=%v", member, err)
	}
	team, err = repo.GetTeam(ctx, team.ID)
	if err != nil || team.Version != 3 || len(team.Members) != 1 || team.Members[0].Role != "coordinator" {
		t.Fatalf("Team version after member update: team=%+v err=%v", team, err)
	}
	if err := repo.RemoveTeamMember(ctx, team.ID, member.ID); err != nil {
		t.Fatalf("remove team member: %v", err)
	}
	team, err = repo.GetTeam(ctx, team.ID)
	if err != nil || team.Version != 4 || len(team.Members) != 0 {
		t.Fatalf("Team version after member removal: team=%+v err=%v", team, err)
	}
	artifact, err := repo.CreateArtifact(ctx, &controlmodel.Artifact{Tenant: issue.Tenant, Namespace: issue.Namespace,
		StorageProvider: "contract", StorageKey: "objects/result", Filename: "result.txt", ContentType: "text/plain",
		SizeBytes: 6, Checksum: "sha256:contract", Uploader: creator},
		[]controlmodel.ArtifactLink{{TargetType: "issue", TargetRef: issue.ID.String(), Relation: "result"}})
	if err != nil {
		t.Fatalf("create artifact: %v", err)
	}
	loadedArtifact, links, err := repo.GetArtifact(ctx, artifact.ID)
	if err != nil || loadedArtifact.ID != artifact.ID || len(links) != 1 {
		t.Fatalf("get artifact: artifact=%+v links=%+v err=%v", loadedArtifact, links, err)
	}
	artifacts, err := repo.ListArtifacts(ctx, issue.Tenant, issue.Namespace, "issue", issue.ID.String())
	if err != nil || len(artifacts) != 1 || artifacts[0].ID != artifact.ID {
		t.Fatalf("list artifact: artifacts=%+v err=%v", artifacts, err)
	}
}

func testRuntime(t *testing.T, ctx context.Context, s store.Store) {
	registry := s.RuntimeRegistry()
	_, err := registry.UpsertRuntimePool(ctx, &controlmodel.RuntimePool{Tenant: "runtime", Namespace: "default", Name: "coding"})
	if err != nil {
		t.Fatalf("upsert runtime pool: %v", err)
	}
	host, err := registry.UpsertRuntimeHost(ctx, &controlmodel.RuntimeHost{
		Tenant: "runtime", Namespace: "default", HostKey: "host-1", PoolName: "coding",
		State: controlmodel.RuntimeHostOnline, Capacity: 2,
	})
	if err != nil {
		t.Fatalf("upsert runtime host: %v", err)
	}
	issue, err := s.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "runtime", Namespace: "default", Title: "run", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
	})
	if err != nil {
		t.Fatalf("create issue: %v", err)
	}
	_, task, err := s.Collaboration().AssignIssue(ctx, issue.ID, issue.Version, controlmodel.AssigneeAgent, "coder", issue.Creator)
	if err != nil {
		t.Fatalf("assign issue: %v", err)
	}
	execution, err := s.ExecutionAttempts().Create(ctx, &controlmodel.ExecutionAttempt{
		AgentTaskID: task.ID, Tenant: task.Tenant, Namespace: task.Namespace,
		BackendKind: controlmodel.DataPlaneHostedRuntime, RuntimeProfileName: "codex", RuntimePoolName: "coding",
	})
	if err != nil || execution.Attempt != 1 {
		t.Fatalf("create execution: execution=%+v err=%v", execution, err)
	}
	claimed, err := s.ExecutionAttempts().Claim(ctx, store.ExecutionClaim{
		Tenant: "runtime", Namespace: "default", RuntimePoolName: "coding", HostID: host.ID,
		HostGeneration: host.LeaseGeneration, LeaseOwner: "host-1", LeaseToken: "lease", LeaseTTL: time.Minute,
	})
	if err != nil || claimed.FencingToken == 0 {
		t.Fatalf("claim execution: execution=%+v err=%v", claimed, err)
	}
	if _, err := s.ExecutionAttempts().MarkPreparing(ctx, claimed.ID, "lease", claimed.FencingToken-1); !errors.Is(err, store.ErrConflict) {
		t.Fatalf("stale fencing token must conflict, got %v", err)
	}
	preparing, err := s.ExecutionAttempts().MarkPreparing(ctx, claimed.ID, "lease", claimed.FencingToken)
	if err != nil {
		t.Fatalf("prepare execution: %v", err)
	}
	running, err := s.ExecutionAttempts().MarkRunning(ctx, preparing.ID, "lease", preparing.FencingToken, "provider-session", "workspace")
	if err != nil {
		t.Fatalf("run execution: %v", err)
	}
	completed, err := s.ExecutionAttempts().Complete(ctx, running.ID, "lease", running.FencingToken, json.RawMessage(`{"ok":true}`), nil)
	if err != nil || completed.State != controlmodel.ExecutionSucceeded {
		t.Fatalf("complete execution: execution=%+v err=%v", completed, err)
	}

	managedIssue, err := s.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "runtime", Namespace: "default", Title: "managed run",
		Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
	})
	if err != nil {
		t.Fatal(err)
	}
	_, managedTask, err := s.Collaboration().AssignIssue(ctx, managedIssue.ID, managedIssue.Version,
		controlmodel.AssigneeAgent, "managed-coder", managedIssue.Creator)
	if err != nil {
		t.Fatal(err)
	}
	managedTask, managedAttempt, err := s.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: managedTask.ID, ExpectedVersion: managedTask.Version, RuntimeBinding: json.RawMessage(`{}`)},
		&controlmodel.ExecutionAttempt{
			BackendKind: controlmodel.DataPlaneManaged, State: controlmodel.ExecutionAssigned,
		})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.Collaboration().StartAgentTask(ctx, managedTask.ID, managedTask.Version); err != nil {
		t.Fatalf("start managed task: %v", err)
	}
	renewedManaged, err := s.ExecutionAttempts().RenewLease(ctx, managedAttempt.ID, "", 0, time.Minute)
	if err != nil || renewedManaged.State != controlmodel.ExecutionRunning || renewedManaged.LeaseExpiresAt == nil || renewedManaged.HeartbeatAt == nil {
		t.Fatalf("renew unleased managed execution: execution=%+v err=%v", renewedManaged, err)
	}
	queuedManaged, _, err := s.Collaboration().RequeueAgentTaskAfterAttemptFailure(ctx, managedTask.ID,
		store.TaskFailure{ExpectedVersion: managedTask.Version + 1, AttemptID: managedAttempt.ID,
			DispatchGeneration: managedAttempt.DispatchGeneration, Code: "heartbeat_timeout", Message: "retry"})
	if err != nil {
		t.Fatalf("requeue managed attempt: %v", err)
	}
	_, retriedManaged, err := s.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: queuedManaged.ID, ExpectedVersion: queuedManaged.Version, RuntimeBinding: json.RawMessage(`{}`)},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged, State: controlmodel.ExecutionAssigned})
	if err != nil {
		t.Fatalf("claim managed retry: %v", err)
	}
	if retriedManaged.Attempt != managedAttempt.Attempt+1 ||
		retriedManaged.DispatchGeneration != managedAttempt.DispatchGeneration+1 {
		t.Fatalf("managed retry fence did not advance: first=%+v retry=%+v", managedAttempt, retriedManaged)
	}

	secureIssue, err := s.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "runtime", Namespace: "default", Title: "secure run",
		Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
	})
	if err != nil {
		t.Fatal(err)
	}
	_, secureTask, err := s.Collaboration().AssignIssue(ctx, secureIssue.ID, secureIssue.Version,
		controlmodel.AssigneeAgent, "secure-coder", secureIssue.Creator)
	if err != nil {
		t.Fatal(err)
	}
	snapshot, _ := json.Marshal(controlmodel.RuntimeDispatchSnapshot{
		Binding: controlmodel.RuntimeBinding{AgentID: uuid.New(), BindingID: uuid.New(), Kind: controlmodel.DataPlaneHostedRuntime,
			RuntimeProfileID: uuid.New(), RuntimePoolID: uuid.New()},
		SecurityConstraints: json.RawMessage(`{"backendKind":"hosted-runtime","region":"cn"}`),
	})
	secureAttempt, err := s.ExecutionAttempts().Create(ctx, &controlmodel.ExecutionAttempt{
		AgentTaskID: secureTask.ID, Tenant: secureTask.Tenant, Namespace: secureTask.Namespace,
		BackendKind: controlmodel.DataPlaneHostedRuntime, RuntimeProfileName: "codex",
		RuntimePoolName: "coding", RuntimeBinding: snapshot,
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.ExecutionAttempts().Claim(ctx, store.ExecutionClaim{Tenant: "runtime", Namespace: "default",
		RuntimePoolName: "coding", HostID: host.ID, HostGeneration: host.LeaseGeneration,
		LeaseOwner: "host-1", LeaseToken: "secure-miss", LeaseTTL: time.Minute}); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("Host without required security labels claimed Attempt %s: %v", secureAttempt.ID, err)
	}
	host.Labels = json.RawMessage(`{"region":"cn"}`)
	host, err = registry.UpsertRuntimeHost(ctx, host)
	if err != nil {
		t.Fatal(err)
	}
	secureClaim, err := s.ExecutionAttempts().Claim(ctx, store.ExecutionClaim{Tenant: "runtime", Namespace: "default",
		RuntimePoolName: "coding", HostID: host.ID, HostGeneration: host.LeaseGeneration,
		LeaseOwner: "host-1", LeaseToken: "secure-hit", LeaseTTL: time.Minute})
	if err != nil || secureClaim.ID != secureAttempt.ID {
		t.Fatalf("matching secure Host did not claim Attempt: attempt=%+v err=%v", secureClaim, err)
	}
}

func testOutbox(t *testing.T, ctx context.Context, s store.Store) {
	now := time.Now().UTC()
	event, err := s.Outbox().Enqueue(ctx, &controlmodel.OutboxEvent{
		Tenant: "outbox", AggregateType: "agent_task", AggregateID: "task-1",
		EventType: "agent_task.queued", Payload: json.RawMessage(`{"taskId":"task-1"}`), DedupeKey: "task-1/queued",
	})
	if err != nil {
		t.Fatalf("enqueue: %v", err)
	}
	same, err := s.Outbox().Enqueue(ctx, &controlmodel.OutboxEvent{
		Tenant: "outbox", AggregateType: "agent_task", AggregateID: "task-1",
		EventType: "agent_task.queued", DedupeKey: "task-1/queued",
	})
	if err != nil || same.ID != event.ID {
		t.Fatalf("idempotent enqueue: event=%+v err=%v", same, err)
	}
	claimed, err := s.Outbox().Claim(ctx, "worker", now.Add(time.Second), time.Minute, 500)
	found := false
	for _, candidate := range claimed {
		if candidate.ID == event.ID {
			found = true
		}
	}
	if err != nil || !found {
		t.Fatalf("claim outbox: events=%+v err=%v", claimed, err)
	}
	if err := s.Outbox().MarkDelivered(ctx, event.ID, "other"); !errors.Is(err, store.ErrConflict) {
		t.Fatalf("wrong worker must conflict, got %v", err)
	}
	if err := s.Outbox().MarkDelivered(ctx, event.ID, "worker"); err != nil {
		t.Fatalf("deliver outbox: %v", err)
	}
}

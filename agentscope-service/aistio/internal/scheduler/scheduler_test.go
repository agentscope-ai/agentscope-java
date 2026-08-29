// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package scheduler

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestEffectivePriorityAgesEveryTenMinutesAndCaps(t *testing.T) {
	now := time.Date(2026, 8, 26, 0, 0, 0, 0, time.UTC)
	if got := EffectivePriority(10, now.Add(-35*time.Minute), now); got != 13 {
		t.Fatalf("priority=%d", got)
	}
	if got := EffectivePriority(10, now.Add(-24*time.Hour), now); got != 60 {
		t.Fatalf("capped priority=%d", got)
	}
}

func TestOrderedFallbackRequiresExhaustedAttempt(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	workerID := uuid.New()
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "ns-a", Title: "fallback",
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: workerID.String(),
		Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
	})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 10})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("task setup: tasks=%+v err=%v", tasks, err)
	}
	task := tasks[0]
	policy := controlmodel.RuntimeBindingPolicy{SelectionMode: "ordered", FallbackMode: "fresh",
		RetryPolicy: json.RawMessage(`{"maxInfrastructureAttempts":1}`),
		Candidates: []controlmodel.RuntimeBindingCandidate{
			{Binding: controlmodel.RuntimeBinding{AgentID: workerID, BindingID: uuid.New(), Kind: controlmodel.DataPlaneExternalApplication}},
			{Binding: controlmodel.RuntimeBinding{AgentID: workerID, BindingID: uuid.New(), Kind: controlmodel.DataPlaneManaged,
				ManagedOwnerRef: "owner", ManagedDefinitionRef: "worker"}},
		},
	}
	s := &Scheduler{Store: st}
	if _, err = s.selectOrderedCandidate(ctx, task, controlmodel.RuntimeDispatchSnapshot{}, policy, "policy"); err == nil || !strings.Contains(err.Error(), "candidate 0 is unavailable") {
		t.Fatalf("unavailable preferred candidate skipped without an exhausted Attempt: %v", err)
	}
	snapshot, _ := json.Marshal(controlmodel.RuntimeDispatchSnapshot{Binding: policy.Candidates[0].Binding,
		SelectionSource: "policy", CandidateIndex: 0, ResolvedAt: time.Now().UTC()})
	claimed, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx, store.TaskClaim{
		TaskID: task.ID, ExpectedVersion: task.Version, RuntimeBinding: snapshot, SessionID: "external-1",
	}, &controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneExternalApplication,
		State: controlmodel.ExecutionAssigned})
	if err != nil {
		t.Fatal(err)
	}
	task, _, err = st.Collaboration().RequeueAgentTaskAfterAttemptFailure(ctx, task.ID, store.TaskFailure{
		ExpectedVersion: claimed.Version, AttemptID: attempt.ID, DispatchGeneration: attempt.DispatchGeneration,
		Code: "instance_lost", Message: "test",
	})
	if err != nil {
		t.Fatal(err)
	}
	var previous controlmodel.RuntimeDispatchSnapshot
	if err = json.Unmarshal(task.RuntimeBinding, &previous); err != nil {
		t.Fatal(err)
	}
	selected, err := s.selectOrderedCandidate(ctx, task, previous, policy, "policy")
	if err != nil || selected.CandidateIndex != 1 || selected.Binding.Kind != controlmodel.DataPlaneManaged {
		t.Fatalf("fresh fallback did not select candidate 1: selected=%+v err=%v", selected, err)
	}
}

func TestAdmissionEnforcesRunConcurrencyAndSecurityLabels(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "admission", Namespace: "default",
		Title: "bounded run", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "first", Creator: actor})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 10})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("initial task: %+v %v", tasks, err)
	}
	source := tasks[0]
	secondAgentID := uuid.New()
	routed, err := st.Collaboration().CreateComment(ctx, store.CreateCommentRequest{Comment: &controlmodel.Comment{
		IssueID: issue.ID, Author: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: source.AgentRef},
		Content: "delegate", Type: controlmodel.CommentGeneral, SourceTaskID: &source.ID},
		Targets: []store.CommentTarget{{TargetType: controlmodel.AssigneeAgent, TargetRef: secondAgentID.String(),
			AgentRef: secondAgentID.String(), ParentTaskID: &source.ID, RouteType: controlmodel.RouteFollowUp}}})
	if err != nil || len(routed.Tasks) != 1 {
		t.Fatalf("routed task: %+v %v", routed, err)
	}
	queued := routed.Tasks[0]
	if queued.OrchestrationRunID != source.OrchestrationRunID {
		t.Fatal("task-scoped delegation escaped its Run")
	}
	_, _, err = st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: source.ID, ExpectedVersion: source.Version},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged, State: controlmodel.ExecutionAssigned})
	if err != nil {
		t.Fatal(err)
	}
	s := &Scheduler{Store: st, MaxTenantConcurrency: 10, MaxRunConcurrency: 1}
	if err = s.admitConcurrency(ctx, &queued); err == nil || !strings.Contains(err.Error(), "Run concurrency") {
		t.Fatalf("Run concurrency was not enforced: %v", err)
	}
	bindingID := uuid.New()
	instance, err := st.RuntimeRegistry().UpsertAgentInstance(ctx, &controlmodel.AgentInstance{
		Tenant: queued.Tenant, Namespace: queued.Namespace, AgentID: secondAgentID, BindingID: bindingID,
		BackendKind: controlmodel.DataPlaneExternalApplication, InstanceKey: "second-1",
		Health: controlmodel.RuntimeHealthHealthy, Capacity: 2,
		Labels: json.RawMessage(`{"region":"cn","trust":{"tier":"isolated"}}`)})
	if err != nil || instance == nil {
		t.Fatal(err)
	}
	candidate := &controlmodel.RuntimeBindingCandidate{Binding: controlmodel.RuntimeBinding{
		AgentID: secondAgentID, BindingID: bindingID, Kind: controlmodel.DataPlaneExternalApplication},
		SecurityConstraints: json.RawMessage(`{"backendKind":"external-application","trust":{"tier":"isolated"}}`)}
	available, err := s.available(ctx, &queued, candidate)
	if err != nil || !available {
		t.Fatalf("matching secure target unavailable: available=%v err=%v", available, err)
	}
	candidate.SecurityConstraints = json.RawMessage(`{"region":"us"}`)
	available, err = s.available(ctx, &queued, candidate)
	if err != nil || available {
		t.Fatalf("mismatched secure target available: available=%v err=%v", available, err)
	}
}

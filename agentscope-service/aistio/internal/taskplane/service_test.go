// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package taskplane

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestHostedAgentTaskLifecycleWritesResultComment(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.DefaultConfig())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	pool, _ := st.RuntimeRegistry().UpsertRuntimePool(ctx, &controlmodel.RuntimePool{Tenant: "tenant", Namespace: "default", Name: "coding"})
	profile, _ := st.RuntimeRegistry().UpsertRuntimeProfile(ctx, &controlmodel.RuntimeProfile{Tenant: "tenant", Namespace: "default", Name: "codex", Provider: "codex"})
	host, _ := st.RuntimeRegistry().UpsertRuntimeHost(ctx, &controlmodel.RuntimeHost{Tenant: "tenant", Namespace: "default", HostKey: "host", PoolName: "coding", State: controlmodel.RuntimeHostOnline, Capacity: 1})
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "tenant", Namespace: "default", Title: "implement change", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "ken"}})
	if err != nil {
		t.Fatal(err)
	}
	agentID, bindingID := uuid.New(), uuid.New()
	issue, task, err := st.Collaboration().AssignIssue(ctx, issue.ID, issue.Version, controlmodel.AssigneeAgent, agentID.String(), issue.Creator)
	if err != nil || issue.AssigneeRef != agentID.String() {
		t.Fatalf("assign: issue=%+v task=%+v err=%v", issue, task, err)
	}
	svc := &Service{Store: st}
	dispatched, execution, err := svc.DispatchHosted(ctx, task.ID, controlmodel.RuntimeBinding{AgentID: agentID, BindingID: bindingID, Kind: controlmodel.DataPlaneHostedRuntime, RuntimeProfileID: profile.ID, RuntimePoolID: pool.ID}, nil)
	if err != nil || dispatched.Status != controlmodel.AgentTaskDispatched {
		t.Fatalf("dispatch: task=%+v execution=%+v err=%v", dispatched, execution, err)
	}
	claimed, err := svc.Claim(ctx, store.ExecutionClaim{Tenant: "tenant", Namespace: "default", RuntimePoolName: "coding", HostID: host.ID, HostGeneration: host.LeaseGeneration, LeaseOwner: "host/1", LeaseToken: "lease", LeaseTTL: time.Minute})
	if err != nil {
		t.Fatal(err)
	}
	preparing, _ := svc.MarkPreparing(ctx, claimed.ID, claimed.LeaseToken, claimed.FencingToken)
	running, _ := svc.MarkRunning(ctx, preparing.ID, preparing.LeaseToken, preparing.FencingToken, "provider-session", "workspace")
	if _, err := svc.Complete(ctx, running.ID, running.LeaseToken, running.FencingToken, json.RawMessage(`{"output":"done"}`), nil); err != nil {
		t.Fatal(err)
	}
	final, _ := st.Collaboration().GetAgentTask(ctx, task.ID)
	if final.Status != controlmodel.AgentTaskCompleted {
		t.Fatalf("task status=%s", final.Status)
	}
	comments, _ := st.Collaboration().ListComments(ctx, issue.ID, store.CommentListOptions{})
	if len(comments) != 1 || comments[0].Type != controlmodel.CommentResult || comments[0].SourceTaskID == nil || *comments[0].SourceTaskID != task.ID {
		t.Fatalf("result comments=%+v", comments)
	}
}

func TestRetryCreatesNewAgentTaskAndAttempt(t *testing.T) {
	ctx := context.Background()
	st, _ := store.Open(ctx, store.DefaultConfig())
	defer st.Close()
	pool, _ := st.RuntimeRegistry().UpsertRuntimePool(ctx, &controlmodel.RuntimePool{Tenant: "tenant", Namespace: "default", Name: "coding"})
	profile, _ := st.RuntimeRegistry().UpsertRuntimeProfile(ctx, &controlmodel.RuntimeProfile{Tenant: "tenant", Namespace: "default", Name: "codex", Provider: "codex"})
	issue, _ := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "tenant", Namespace: "default", Title: "retry", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "ken"}})
	agentID, bindingID := uuid.New(), uuid.New()
	_, task, _ := st.Collaboration().AssignIssue(ctx, issue.ID, issue.Version, controlmodel.AssigneeAgent, agentID.String(), issue.Creator)
	svc := &Service{Store: st}
	_, first, _ := svc.DispatchHosted(ctx, task.ID, controlmodel.RuntimeBinding{AgentID: agentID, BindingID: bindingID, Kind: controlmodel.DataPlaneHostedRuntime, RuntimeProfileID: profile.ID, RuntimePoolID: pool.ID}, nil)
	failed, _ := st.Collaboration().FailAgentTask(ctx, task.ID, task.Version+1, "provider", "failed")
	retry, second, err := svc.RetryTask(ctx, failed.ID)
	if err != nil || retry.ID == failed.ID || retry.RetryOfTaskID == nil || second == nil || second.AgentTaskID != retry.ID || first.ID == second.ID {
		t.Fatalf("retry=%+v execution=%+v err=%v", retry, second, err)
	}
}

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

package runtimehost

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

type fakeControlPlane struct {
	mu              sync.Mutex
	host            *controlmodel.RuntimeHost
	work            *ClaimedWork
	claimed         bool
	completed       chan struct{}
	restored        map[uuid.UUID]string
	terminalOnStart bool
	terminalOnRenew bool
	completeCalls   int
	renewErrors     []error
	renewed         chan error
}

func (f *fakeControlPlane) RestoreAttemptToken(id uuid.UUID, token string) {
	if f.restored == nil {
		f.restored = make(map[uuid.UUID]string)
	}
	f.restored[id] = token
}

func (f *fakeControlPlane) Register(_ context.Context, registration Registration) (*controlmodel.RuntimeHost, error) {
	f.host = &controlmodel.RuntimeHost{
		ID: uuid.New(), Tenant: registration.Tenant, Namespace: registration.Namespace,
		HostKey: registration.HostKey, PoolName: registration.PoolName,
		State: controlmodel.RuntimeHostOnline, LeaseGeneration: 1,
	}
	return f.host, nil
}
func (f *fakeControlPlane) Heartbeat(context.Context, *controlmodel.RuntimeHost, int32, json.RawMessage) (*controlmodel.RuntimeHost, error) {
	return f.host, nil
}
func (f *fakeControlPlane) Claim(_ context.Context, _ *controlmodel.RuntimeHost, _, token string, _ time.Duration) (*ClaimedWork, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.claimed {
		return nil, ErrNoWork
	}
	f.claimed = true
	f.work.Attempt.LeaseToken = token
	f.work.Attempt.FencingToken = 1
	hostID := f.host.ID
	f.work.Attempt.HostID = &hostID
	return f.work, nil
}
func (f *fakeControlPlane) Prepare(_ context.Context, _ uuid.UUID, execution *controlmodel.ExecutionAttempt) error {
	execution.State = controlmodel.ExecutionPreparing
	return nil
}
func (f *fakeControlPlane) Start(_ context.Context, _ uuid.UUID, execution *controlmodel.ExecutionAttempt, _, _ string) error {
	if f.terminalOnStart {
		execution.State = controlmodel.ExecutionSucceeded
	} else {
		execution.State = controlmodel.ExecutionRunning
	}
	return nil
}
func (f *fakeControlPlane) Renew(_ context.Context, _ uuid.UUID, execution *controlmodel.ExecutionAttempt, _ time.Duration) error {
	f.mu.Lock()
	var err error
	if len(f.renewErrors) > 0 {
		err, f.renewErrors = f.renewErrors[0], f.renewErrors[1:]
	}
	f.mu.Unlock()
	if f.renewed != nil {
		f.renewed <- err
	}
	if err == nil && f.terminalOnRenew {
		execution.State = controlmodel.ExecutionFailed
	}
	return err
}
func (f *fakeControlPlane) Checkpoint(_ context.Context, _ uuid.UUID, execution *controlmodel.ExecutionAttempt, providerSessionID string, checkpoint json.RawMessage) error {
	execution.ProviderSessionID = providerSessionID
	execution.Checkpoint = checkpoint
	return nil
}
func (f *fakeControlPlane) Complete(_ context.Context, _ uuid.UUID, execution *controlmodel.ExecutionAttempt, _, _ json.RawMessage) error {
	f.mu.Lock()
	f.completeCalls++
	f.mu.Unlock()
	execution.State = controlmodel.ExecutionSucceeded
	close(f.completed)
	return nil
}

func TestHostedWorkspaceContextFallsBackToImmutableDispatchSnapshot(t *testing.T) {
	policy := json.RawMessage(`{"workspaceKey":"tenant/legacy-task"}`)
	snapshot, err := json.Marshal(controlmodel.RuntimeDispatchSnapshot{
		SessionID: "conversation-1", Policy: policy,
	})
	if err != nil {
		t.Fatal(err)
	}
	sessionID, workspaceKey := hostedWorkspaceContext(&controlmodel.ExecutionAttempt{
		RuntimeBinding: snapshot,
	}, &controlmodel.AgentTask{SessionID: "task-fallback"})
	if sessionID != "conversation-1" || workspaceKey != "tenant/legacy-task" {
		t.Fatalf("session=%q workspace=%q", sessionID, workspaceKey)
	}

	sessionID, workspaceKey = hostedWorkspaceContext(&controlmodel.ExecutionAttempt{},
		&controlmodel.AgentTask{SessionID: "task-fallback"})
	if sessionID != "task-fallback" || workspaceKey != "" {
		t.Fatalf("task fallback session=%q workspace=%q", sessionID, workspaceKey)
	}
}

func TestRenewLoopToleratesTransientControlPlaneFailureWithinLease(t *testing.T) {
	transient := errors.New("control plane restarting")
	cp := &fakeControlPlane{renewErrors: []error{transient}, renewed: make(chan error, 1)}
	engine := &Engine{Config: Config{LeaseTTL: 3 * time.Second}, Client: cp}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	failures := make(chan error, 1)
	go engine.renewLoop(ctx, cancel, uuid.New(), &controlmodel.ExecutionAttempt{}, failures)
	select {
	case got := <-cp.renewed:
		if !errors.Is(got, transient) {
			t.Fatalf("renew error=%v", got)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("renew was not attempted")
	}
	select {
	case <-ctx.Done():
		t.Fatal("transient renewal failure cancelled provider before lease expiry")
	case err := <-failures:
		t.Fatalf("transient renewal failure was reported as lease loss: %v", err)
	default:
	}
}

func TestRenewLoopCancelsProviderAfterLeaseExpires(t *testing.T) {
	leaseFailure := errors.New("control plane unavailable")
	cp := &fakeControlPlane{renewErrors: []error{leaseFailure}, renewed: make(chan error, 1)}
	engine := &Engine{Config: Config{LeaseTTL: 100 * time.Millisecond}, Client: cp}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	failures := make(chan error, 1)
	go engine.renewLoop(ctx, cancel, uuid.New(), &controlmodel.ExecutionAttempt{}, failures)
	select {
	case err := <-failures:
		if !errors.Is(err, leaseFailure) {
			t.Fatalf("lease failure=%v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("expired lease did not stop provider")
	}
	select {
	case <-ctx.Done():
	default:
		t.Fatal("provider context remained active after lease expiry")
	}
}

func TestRenewLoopCancelsProviderWhenControlPlaneReportsTerminal(t *testing.T) {
	cp := &fakeControlPlane{terminalOnRenew: true, renewed: make(chan error, 1)}
	engine := &Engine{Config: Config{LeaseTTL: 3 * time.Second}, Client: cp}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go engine.renewLoop(ctx, cancel, uuid.New(), &controlmodel.ExecutionAttempt{}, make(chan error, 1))
	select {
	case <-ctx.Done():
	case <-time.After(2 * time.Second):
		t.Fatal("terminal renewal response did not cancel the provider context")
	}
}

func TestEngineConvergesWhenAgentCompletesThroughCollaboration(t *testing.T) {
	taskID, attemptID, hostID := uuid.New(), uuid.New(), uuid.New()
	cp := &fakeControlPlane{terminalOnStart: true, completed: make(chan struct{})}
	work := &ClaimedWork{
		Task: &controlmodel.AgentTask{ID: taskID, Tenant: "tenant", Namespace: "default", AgentRef: "coder"},
		Context: &collaboration.ContextEnvelope{
			Task:  &controlmodel.AgentTask{ID: taskID, Tenant: "tenant", Namespace: "default", AgentRef: "coder"},
			Issue: &controlmodel.Issue{ID: uuid.New(), Tenant: "tenant", Namespace: "default", Title: "work"},
		},
		Attempt: &controlmodel.ExecutionAttempt{ID: attemptID, AgentTaskID: taskID, Tenant: "tenant",
			Namespace: "default", BackendKind: controlmodel.DataPlaneHostedRuntime, State: controlmodel.ExecutionAssigned,
			LeaseToken: "lease", FencingToken: 1},
		Profile: &controlmodel.RuntimeProfile{Provider: "fake"},
	}
	stateRoot := t.TempDir()
	engine := &Engine{Config: Config{WorkspaceRoot: t.TempDir(), StateRoot: stateRoot},
		Client: cp, Providers: map[string]provider.Adapter{"fake": fakeProvider{}}}
	engine.execute(context.Background(), hostID, work)
	if cp.completeCalls != 0 {
		t.Fatalf("daemon completion was delivered %d times after the Agent had already completed", cp.completeCalls)
	}
	records, err := (&Journal{Root: stateRoot}).List()
	if err != nil || len(records) != 0 {
		t.Fatalf("terminal convergence retained journal records=%v err=%v", records, err)
	}
}
func (f *fakeControlPlane) Fail(context.Context, uuid.UUID, *controlmodel.ExecutionAttempt, string, string, json.RawMessage) error {
	return nil
}
func (f *fakeControlPlane) Cancelled(_ context.Context, _ uuid.UUID, attempt *controlmodel.ExecutionAttempt) error {
	attempt.State = controlmodel.ExecutionCancelled
	return nil
}

type fakeProvider struct{}

func (fakeProvider) Name() string                           { return "fake" }
func (fakeProvider) Detect(context.Context) (string, error) { return "fake/1.0", nil }
func (fakeProvider) Run(_ context.Context, request provider.Request, sink provider.EventSink) (*provider.Result, error) {
	_ = sink(provider.Event{Type: "thread.started", ProviderSessionID: "provider-session-1", Raw: json.RawMessage(`{"type":"thread.started"}`)})
	return &provider.Result{
		ProviderSessionID: "provider-session-1", Output: request.Prompt,
		Checkpoint: json.RawMessage(`{"providerSessionId":"provider-session-1"}`),
	}, nil
}

func TestEngineExecutesClaimedWork(t *testing.T) {
	taskID := uuid.New()
	cp := &fakeControlPlane{
		completed: make(chan struct{}),
		work: &ClaimedWork{
			Task: &controlmodel.AgentTask{
				ID: taskID, Tenant: "tenant", Namespace: "default", AgentRef: "coder",
			},
			Context: &collaboration.ContextEnvelope{
				Task:  &controlmodel.AgentTask{ID: taskID, Tenant: "tenant", Namespace: "default", AgentRef: "coder"},
				Issue: &controlmodel.Issue{ID: uuid.New(), Tenant: "tenant", Namespace: "default", Title: "do work"},
			},
			Attempt: &controlmodel.ExecutionAttempt{
				ID: uuid.New(), AgentTaskID: taskID, Tenant: "tenant", Namespace: "default",
				BackendKind: controlmodel.DataPlaneHostedRuntime, State: controlmodel.ExecutionQueued,
			},
			Profile: &controlmodel.RuntimeProfile{Provider: "fake"},
		},
	}
	ctx, cancel := context.WithCancel(context.Background())
	engine := &Engine{
		Config: Config{
			Registration: Registration{Tenant: "tenant", Namespace: "default", HostKey: "host", PoolName: "pool", Capacity: 1},
			PollInterval: 10 * time.Millisecond, HeartbeatInterval: time.Hour, LeaseTTL: time.Minute,
			WorkspaceRoot: t.TempDir(), StateRoot: t.TempDir(),
		},
		Client: cp, Providers: map[string]provider.Adapter{"fake": fakeProvider{}},
	}
	done := make(chan error, 1)
	go func() { done <- engine.Run(ctx) }()
	select {
	case <-cp.completed:
		cancel()
	case <-time.After(5 * time.Second):
		cancel()
		t.Fatal("engine did not complete work")
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if cp.work.Attempt.State != controlmodel.ExecutionSucceeded {
		t.Fatalf("execution state=%s", cp.work.Attempt.State)
	}
	if cp.work.Attempt.ProviderSessionID != "provider-session-1" {
		t.Fatalf("provider session was not checkpointed: %q", cp.work.Attempt.ProviderSessionID)
	}
	var advertised struct {
		Providers            map[string]string              `json:"providers"`
		ProviderCapabilities map[string]provider.Descriptor `json:"providerCapabilities"`
	}
	if err := json.Unmarshal(engine.capabilities, &advertised); err != nil {
		t.Fatal(err)
	}
	if advertised.Providers["fake"] != "fake/1.0" ||
		!advertised.ProviderCapabilities["fake"].Workspace.Supported {
		t.Fatalf("capabilities=%s", engine.capabilities)
	}
}

func TestAppendRuntimeContextIncludesAvailableCollaboration(t *testing.T) {
	teamID := uuid.New()
	task := &controlmodel.AgentTask{TeamID: &teamID, TeamRole: "leader", LeaderTask: true}
	prompt, err := appendRuntimeContext("do work", task,
		provider.Descriptor{MCP: provider.Capability{Supported: true}}, "http://runtime.test/mcp", "", "token")
	if err != nil {
		t.Fatal(err)
	}
	for _, required := range []string{"agentscope-collaboration", teamID.String(), "role leader", "run.node.complete"} {
		if !strings.Contains(prompt, required) {
			t.Fatalf("runtime prompt is missing %q: %s", required, prompt)
		}
	}
}

func TestAppendRuntimeContextRejectsUnsupportedTeamLeader(t *testing.T) {
	task := &controlmodel.AgentTask{LeaderTask: true}
	_, err := appendRuntimeContext("do work", task, provider.Descriptor{}, "", "", "")
	if err == nil || !strings.Contains(err.Error(), "collaboration MCP or AgentScope CLI") {
		t.Fatalf("expected collaboration capability error, got %v", err)
	}
}

func TestAppendRuntimeContextAllowsShellCollaborationFallback(t *testing.T) {
	task := &controlmodel.AgentTask{LeaderTask: true}
	prompt, err := appendRuntimeContext("do work", task,
		provider.Descriptor{Shell: provider.Capability{Supported: true}}, "", "/opt/agentscope", "token")
	if err != nil {
		t.Fatal(err)
	}
	for _, required := range []string{"agentscope task context", "agentscope task run graph", "fallback"} {
		if !strings.Contains(prompt, required) {
			t.Fatalf("runtime prompt is missing %q: %s", required, prompt)
		}
	}
}

func TestShouldPublishProviderEventFiltersInternalHookNoise(t *testing.T) {
	if shouldPublishProviderEvent(provider.Event{Type: "system", Raw: json.RawMessage(`{"subtype":"hook_progress"}`)}) {
		t.Fatal("hook progress should remain local instead of flooding the Run timeline")
	}
	if !shouldPublishProviderEvent(provider.Event{Type: "system", Raw: json.RawMessage(`{"subtype":"init"}`)}) {
		t.Fatal("provider initialization should be observable")
	}
	if !shouldPublishProviderEvent(provider.Event{Type: "assistant", Raw: json.RawMessage(`{"type":"assistant"}`)}) {
		t.Fatal("assistant events should be observable")
	}
	if shouldPublishProviderEvent(provider.Event{Type: "item.completed", Raw: json.RawMessage(
		`{"type":"item.completed","item":{"type":"error","message":"clamping SessionEnd hook timeout from 999999ms"}}`)}) {
		t.Fatal("non-fatal Codex hook timeout warning should remain local")
	}
	if shouldPublishProviderEvent(provider.Event{Type: "warning", Raw: json.RawMessage(
		`{"method":"warning","params":{"message":"clamping SessionEnd hook timeout to 3s"}}`)}) {
		t.Fatal("app-server hook timeout warning should remain local")
	}
}

func TestEngineReplaysDurableTerminalOutbox(t *testing.T) {
	stateRoot := t.TempDir()
	attempt := &controlmodel.ExecutionAttempt{ID: uuid.New(), LeaseToken: "lease", FencingToken: 7}
	hostID := uuid.New()
	j := &Journal{Root: stateRoot}
	if err := j.Save(&JournalRecord{Attempt: attempt, HostID: hostID, AttemptToken: "attempt-token",
		PendingTerminal: &PendingTerminal{Action: "complete", Result: json.RawMessage(`{"output":"done"}`)}}); err != nil {
		t.Fatal(err)
	}
	cp := &fakeControlPlane{completed: make(chan struct{})}
	engine := &Engine{Config: Config{StateRoot: stateRoot}, Client: cp}
	engine.replayPendingTerminals(context.Background())
	select {
	case <-cp.completed:
	default:
		t.Fatal("pending terminal action was not delivered")
	}
	if cp.restored[attempt.ID] != "attempt-token" {
		t.Fatalf("attempt token was not restored: %+v", cp.restored)
	}
	records, err := j.List()
	if err != nil || len(records) != 0 {
		t.Fatalf("delivered terminal record was retained: records=%+v err=%v", records, err)
	}
}

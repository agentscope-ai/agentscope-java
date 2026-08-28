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
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

type fakeControlPlane struct {
	mu        sync.Mutex
	host      *controlmodel.RuntimeHost
	work      *ClaimedWork
	claimed   bool
	completed chan struct{}
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
	execution.State = controlmodel.ExecutionRunning
	return nil
}
func (f *fakeControlPlane) Renew(context.Context, uuid.UUID, *controlmodel.ExecutionAttempt, time.Duration) error {
	return nil
}
func (f *fakeControlPlane) Checkpoint(_ context.Context, _ uuid.UUID, execution *controlmodel.ExecutionAttempt, providerSessionID string, checkpoint json.RawMessage) error {
	execution.ProviderSessionID = providerSessionID
	execution.Checkpoint = checkpoint
	return nil
}
func (f *fakeControlPlane) Complete(_ context.Context, _ uuid.UUID, execution *controlmodel.ExecutionAttempt, _, _ json.RawMessage) error {
	execution.State = controlmodel.ExecutionSucceeded
	close(f.completed)
	return nil
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
}

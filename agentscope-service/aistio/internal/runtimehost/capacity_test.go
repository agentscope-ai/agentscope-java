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
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

type capacityControlPlane struct {
	fakeControlPlane
	capacity atomic.Int32
	claims   chan struct{}
}

func (f *capacityControlPlane) Register(context.Context, Registration) (*controlmodel.RuntimeHost, error) {
	return &controlmodel.RuntimeHost{ID: uuid.New(), Capacity: f.capacity.Load()}, nil
}
func (f *capacityControlPlane) Heartbeat(_ context.Context, h *controlmodel.RuntimeHost, _ int32, _ json.RawMessage) (*controlmodel.RuntimeHost, error) {
	cp := *h
	cp.Capacity = f.capacity.Load()
	return &cp, nil
}
func (f *capacityControlPlane) Claim(context.Context, *controlmodel.RuntimeHost, string, string, time.Duration) (*ClaimedWork, error) {
	select {
	case f.claims <- struct{}{}:
	default:
	}
	return nil, ErrNoWork
}
func TestEngineAppliesSharedCapacityFromHeartbeat(t *testing.T) {
	cp := &capacityControlPlane{claims: make(chan struct{}, 100)}
	cp.capacity.Store(1)
	e := &Engine{Client: cp, Providers: map[string]provider.Adapter{"fake": fakeProvider{}}, Config: Config{Registration: Registration{Capacity: 1}, StateRoot: t.TempDir(), PollInterval: time.Millisecond, HeartbeatInterval: 5 * time.Millisecond}}
	// Simulate one long-running execution, such as an approval wait.
	e.active.Store(1)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- e.Run(ctx) }()
	defer func() {
		cancel()
		if err := <-done; err != nil {
			t.Error(err)
		}
	}()
	select {
	case <-cp.claims:
		t.Fatal("claimed above capacity 1")
	case <-time.After(25 * time.Millisecond):
	}
	cp.capacity.Store(3)
	select {
	case <-cp.claims:
	case <-time.After(time.Second):
		t.Fatal("heartbeat capacity increase did not unblock claims")
	}
	cp.capacity.Store(1)
	deadline := time.After(time.Second)
	for {
		h := e.currentHost()
		if h != nil && h.Capacity == 1 {
			break
		}
		select {
		case <-deadline:
			t.Fatal("capacity decrease not received")
		case <-time.After(time.Millisecond):
		}
	}
	// Allow an already-started claim to finish; subsequent iterations must stay blocked.
	time.Sleep(5 * time.Millisecond)
	for len(cp.claims) > 0 {
		<-cp.claims
	}
	select {
	case <-cp.claims:
		t.Fatal("claimed above lowered capacity")
	case <-time.After(25 * time.Millisecond):
	}
	if e.active.Load() != 1 {
		t.Fatal("capacity decrease interrupted active execution")
	}
	e.active.Store(0)
	select {
	case <-cp.claims:
	case <-time.After(time.Second):
		t.Fatal("claims did not resume after occupancy dropped")
	}
}

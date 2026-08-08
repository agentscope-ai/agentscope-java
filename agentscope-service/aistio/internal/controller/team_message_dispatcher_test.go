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

package controller

import (
	"context"
	"strings"
	"sync"
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/store/memory"
	"github.com/spring-ai-alibaba/aistio/internal/team"
)

type recordedWake struct {
	sessionID string
	text      string
}

type fakeWakeAPI struct {
	mu    sync.Mutex
	wakes []recordedWake
	err   error
}

func (f *fakeWakeAPI) PostSessionWakeEvent(_ context.Context, sessionID, _, text string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.err != nil {
		return f.err
	}
	f.wakes = append(f.wakes, recordedWake{sessionID: sessionID, text: text})
	return nil
}

func (f *fakeWakeAPI) recorded() []recordedWake {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]recordedWake(nil), f.wakes...)
}

// leadWithPending sets up a team whose managed lead has `contents` waiting in its
// mailbox, and returns the dispatcher under test.
func leadWithPending(t *testing.T, wake ManagedWakeAPI, contents ...string) (*TeamMessageDispatcher, store.Store) {
	t.Helper()
	ctx := context.Background()
	st, err := memory.Open(ctx, store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := st.Teams().Create(ctx, &store.Team{
		Name: "research", Namespace: "default", Objective: "ship it", Phase: store.TeamPhaseRunning,
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := st.Teams().UpsertMember(ctx, &store.TeamMember{
		TeamName: "research", Namespace: "default", MemberName: "lead",
		AgentRef: "lead-agent", DeployMode: store.MemberDeployManaged,
		ManagedAgentID: "agt_lead", ManagedSessionID: "sess_lead", OwnerID: "user_1",
		Phase: store.MemberPhaseIdle,
	}); err != nil {
		t.Fatal(err)
	}
	for _, content := range contents {
		if err := st.TeamMessages().Send(ctx, &store.TeamMessage{
			TeamName: "research", Namespace: "default",
			FromMember: "worker1", ToMember: "lead", Content: content, Kind: "message",
		}); err != nil {
			t.Fatal(err)
		}
	}
	return &TeamMessageDispatcher{Store: st, ManagedWake: wake}, st
}

func pendingCount(t *testing.T, st store.Store) int {
	t.Helper()
	msgs, err := st.TeamMessages().ListPendingAll(context.Background(), 100)
	if err != nil {
		t.Fatal(err)
	}
	return len(msgs)
}

func TestBusyMemberKeepsItsMessagesQueuedWithoutSpendingAttempts(t *testing.T) {
	wake := &fakeWakeAPI{err: team.ErrMemberBusy}
	d, st := leadWithPending(t, wake, "task-1 completed")

	d.dispatchOnce(context.Background(), 5)

	msgs, err := st.TeamMessages().ListPendingAll(context.Background(), 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 1 {
		t.Fatalf("report must stay queued while the lead is mid-turn, pending=%d", len(msgs))
	}
	if msgs[0].Attempts != 0 {
		// Otherwise five busy ticks (~10s of one lead turn) drop the report for good.
		t.Errorf("busy must not count as a delivery attempt, attempts=%d", msgs[0].Attempts)
	}

	wake.err = nil
	d.dispatchOnce(context.Background(), 5)
	if got := pendingCount(t, st); got != 0 {
		t.Errorf("report should be delivered once the lead is idle, pending=%d", got)
	}
	if got := len(wake.recorded()); got != 1 {
		t.Errorf("expected one wake after the lead went idle, got %d", got)
	}
}

func TestAllPendingReportsArriveInOneWake(t *testing.T) {
	wake := &fakeWakeAPI{}
	d, st := leadWithPending(t, wake,
		"task-1 completed: e2b findings", "task-3 completed: daytona findings")

	d.dispatchOnce(context.Background(), 5)

	wakes := wake.recorded()
	if len(wakes) != 1 {
		t.Fatalf("a second wake would be rejected by the turn the first one started, got %d", len(wakes))
	}
	for _, want := range []string{"e2b findings", "daytona findings"} {
		if !strings.Contains(wakes[0].text, want) {
			t.Errorf("wake text is missing %q: %s", want, wakes[0].text)
		}
	}
	if got := pendingCount(t, st); got != 0 {
		t.Errorf("both reports should be marked delivered, pending=%d", got)
	}
}

func TestRealDeliveryFailureStillSpendsAnAttempt(t *testing.T) {
	wake := &fakeWakeAPI{err: errBoom}
	d, st := leadWithPending(t, wake, "task-1 completed")

	d.dispatchOnce(context.Background(), 5)

	msgs, err := st.TeamMessages().ListPendingAll(context.Background(), 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 1 || msgs[0].Attempts != 1 {
		t.Fatalf("a broken data plane must be retried a bounded number of times, got %+v", msgs)
	}
}

var errBoom = errTest("data plane unreachable")

type errTest string

func (e errTest) Error() string { return string(e) }

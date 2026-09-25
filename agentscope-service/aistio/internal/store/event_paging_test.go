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

package store_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestEventList_BeforeAndNewestFirst(t *testing.T) {
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	sess, err := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "e1", AgentName: "a", Namespace: "ns", Framework: "x", Phase: store.SessionPhaseActive,
	})
	if err != nil {
		t.Fatal(err)
	}
	base := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	for i := 1; i <= 10; i++ {
		if err := st.Events().Append(context.Background(), &store.SessionEvent{
			SessionFK:  sess.ID,
			Seq:        i,
			EventType:  "message",
			OccurredAt: base.Add(time.Duration(i) * time.Second),
		}); err != nil {
			t.Fatal(err)
		}
	}

	newest, err := st.Events().List(context.Background(), sess.ID, store.WithEventNewestFirst(), store.WithEventLimit(3))
	if err != nil {
		t.Fatal(err)
	}
	if len(newest) != 3 || newest[0].Seq != 8 || newest[2].Seq != 10 {
		t.Fatalf("newest=%v", seqs(newest))
	}

	before, err := st.Events().List(context.Background(), sess.ID,
		store.WithEventBeforeSeq(8), store.WithEventLimit(3))
	if err != nil {
		t.Fatal(err)
	}
	if len(before) != 3 || before[0].Seq != 5 || before[2].Seq != 7 {
		t.Fatalf("before=%v", seqs(before))
	}

	after, err := st.Events().List(context.Background(), sess.ID,
		store.WithEventAfterSeq(7), store.WithEventLimit(2))
	if err != nil {
		t.Fatal(err)
	}
	if len(after) != 2 || after[0].Seq != 8 || after[1].Seq != 9 {
		t.Fatalf("after=%v", seqs(after))
	}
}

func TestEventWaitForNew_WakesOnAppend(t *testing.T) {
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	sess, err := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "wait-1", AgentName: "a", Namespace: "ns", Framework: "x", Phase: store.SessionPhaseActive,
	})
	if err != nil {
		t.Fatal(err)
	}
	waitCtx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	woke := make(chan error, 1)
	go func() {
		woke <- st.Events().WaitForNew(waitCtx, sess.ID, 10)
	}()
	if err := st.Events().Append(context.Background(), &store.SessionEvent{
		SessionFK: sess.ID, Seq: 11, EventType: "message",
	}); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-woke:
		if err != nil {
			t.Fatal(err)
		}
	case <-waitCtx.Done():
		t.Fatal("event waiter did not wake after append")
	}
}

func TestRunEventWaitForNew_WakesOnAppend(t *testing.T) {
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	runID := uuid.New()
	waitCtx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	woke := make(chan error, 1)
	go func() {
		woke <- st.Orchestration().WaitForRunEvent(waitCtx, runID, 0)
	}()
	if _, err := st.Orchestration().AppendRunEvent(context.Background(), &controlmodel.RunEvent{
		RunID: runID, Type: "run.started",
	}); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-woke:
		if err != nil {
			t.Fatal(err)
		}
	case <-waitCtx.Done():
		t.Fatal("run event waiter did not wake after append")
	}
}

func seqs(events []*store.SessionEvent) []int {
	out := make([]int, len(events))
	for i, e := range events {
		out[i] = e.Seq
	}
	return out
}

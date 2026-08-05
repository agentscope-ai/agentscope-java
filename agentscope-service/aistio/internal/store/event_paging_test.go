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
}

func seqs(events []*store.SessionEvent) []int {
	out := make([]int, len(events))
	for i, e := range events {
		out[i] = e.Seq
	}
	return out
}

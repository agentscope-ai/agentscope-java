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

package sessionops

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func TestExecute_QueuesWhenBusy(t *testing.T) {
	st := newTestStore(t)
	busy := true
	sess, err := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "q1", AgentName: "a", Namespace: "default",
		Phase: store.SessionPhaseActive, Busy: &busy, InstanceRef: "inst-q",
	})
	if err != nil {
		t.Fatal(err)
	}

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Fatalf("DP should not be called while queued: %s", r.URL.Path)
	}))
	defer srv.Close()

	reg := dataplane.NewRegistry()
	reg.Upsert(dataplane.Entry{
		AgentName: "a", Namespace: "default", InstanceID: "inst-q",
		BaseURL: srv.URL, ContractLevel: 3,
		Capabilities: []string{v1alpha1.CapabilitySessionCommand},
	})

	r := NewRouter(reg, st, prober.NewHTTPProber(), nil)
	out, err := r.Execute(context.Background(), sess, Request{Command: CommandCompress})
	if err != nil {
		t.Fatalf("expected queue success, got %v", err)
	}
	if !out.Queued || out.CommandID == "" {
		t.Fatalf("expected queued result, got %+v", out)
	}

	rows, _ := st.Commands().List(context.Background(), store.SessionCommandFilter{
		Status: store.CommandStatusQueued, Limit: 10,
	})
	if len(rows) != 1 {
		t.Fatalf("expected 1 queued row, got %d", len(rows))
	}
}

func TestExecute_UnknownPhaseNeedsForce(t *testing.T) {
	st := newTestStore(t)
	sess, _ := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "u1", AgentName: "a", Namespace: "default",
		Phase: store.SessionPhaseIdle, Busy: nil, InstanceRef: "inst-u",
	})
	// Store defaults empty phase → active; clear after upsert to simulate unknown.
	sess.Phase = ""
	sess.Busy = nil
	reg := dataplane.NewRegistry()
	reg.Upsert(dataplane.Entry{
		AgentName: "a", Namespace: "default", InstanceID: "inst-u",
		BaseURL: "http://127.0.0.1:9", ContractLevel: 3,
		Capabilities: []string{v1alpha1.CapabilitySessionCommand},
	})
	r := NewRouter(reg, st, prober.NewHTTPProber(), nil)
	_, err := r.Execute(context.Background(), sess, Request{Command: CommandCompress})
	opErr, ok := AsError(err)
	if !ok || opErr.Hint != HintForceConfirm {
		t.Fatalf("expected force_confirm, got %v", err)
	}
}

func TestQueueWorker_DrainsWhenIdle(t *testing.T) {
	st := newTestStore(t)
	busy := true
	sess, _ := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "d1", AgentName: "a", Namespace: "default",
		Phase: store.SessionPhaseActive, Busy: &busy, InstanceRef: "inst-d",
	})

	called := make(chan struct{}, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			called <- struct{}{}
			_ = json.NewEncoder(w).Encode(map[string]any{
				"accepted": true, "commandId": "x", "phase": "compressing", "result": map[string]any{},
			})
			return
		}
		// state probe
		_ = json.NewEncoder(w).Encode(map[string]any{"phase": "idle", "busy": false})
	}))
	defer srv.Close()

	reg := dataplane.NewRegistry()
	reg.Upsert(dataplane.Entry{
		AgentName: "a", Namespace: "default", InstanceID: "inst-d",
		BaseURL: srv.URL, ContractLevel: 3,
		Capabilities: []string{v1alpha1.CapabilitySessionCommand},
	})
	r := NewRouter(reg, st, prober.NewHTTPProber(), nil)

	out, err := r.Execute(context.Background(), sess, Request{Command: CommandCompress})
	if err != nil || !out.Queued {
		t.Fatalf("queue: %+v err=%v", out, err)
	}

	// Become idle
	idle := false
	sess.Busy = &idle
	sess.Phase = store.SessionPhaseIdle
	_, _ = st.Sessions().Upsert(context.Background(), sess)

	w := &QueueWorker{Router: r, Store: st, Interval: time.Hour, Batch: 10}
	w.tick(context.Background())

	select {
	case <-called:
	case <-time.After(2 * time.Second):
		t.Fatal("expected DP compress call after drain")
	}

	rows, _ := st.Commands().List(context.Background(), store.SessionCommandFilter{
		SessionFK: sess.ID, Limit: 10,
	})
	foundSucceeded := false
	for _, row := range rows {
		if row.Status == store.CommandStatusSucceeded {
			foundSucceeded = true
		}
	}
	if !foundSucceeded {
		t.Fatalf("expected succeeded command after drain, got %+v", rows)
	}
}

func TestWithSessionLock_Serializes(t *testing.T) {
	st := newTestStore(t)
	var order []int
	done := make(chan struct{}, 2)
	go func() {
		_ = st.WithSessionLock(context.Background(), "k", func(context.Context) error {
			order = append(order, 1)
			time.Sleep(50 * time.Millisecond)
			order = append(order, 2)
			done <- struct{}{}
			return nil
		})
	}()
	time.Sleep(10 * time.Millisecond)
	go func() {
		_ = st.WithSessionLock(context.Background(), "k", func(context.Context) error {
			order = append(order, 3)
			done <- struct{}{}
			return nil
		})
	}()
	<-done
	<-done
	if len(order) != 3 || order[0] != 1 || order[1] != 2 || order[2] != 3 {
		t.Fatalf("expected serialized order 1,2,3 got %v", order)
	}
}

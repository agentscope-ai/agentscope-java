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

package runtimehost

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

func TestClientClaimRequiresBothAttemptAndTaskCredentials(t *testing.T) {
	attemptID, taskID := uuid.New(), uuid.New()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"task": map[string]any{"id": taskID, "tenant": "tenant", "namespace": "default"},
			"context": map[string]any{
				"task":  map[string]any{"id": taskID, "tenant": "tenant", "namespace": "default"},
				"issue": map[string]any{"id": uuid.New(), "tenant": "tenant", "namespace": "default", "title": "work"},
			},
			"attempt":        map[string]any{"id": attemptID, "agentTaskId": taskID, "backendKind": "hosted-runtime"},
			"runtimeProfile": map[string]any{"provider": "codex"},
			"attemptToken":   "attempt-token", "taskToken": "task-token",
		})
	}))
	defer server.Close()
	client := &Client{BaseURL: server.URL, HTTPClient: server.Client()}
	work, err := client.Claim(context.Background(), &controlmodel.RuntimeHost{ID: uuid.New(), Tenant: "tenant",
		Namespace: "default", PoolName: "coding", LeaseGeneration: 1}, "host/lease", "lease", time.Minute)
	if err != nil || work.AttemptToken != "attempt-token" || work.TaskToken != "task-token" {
		t.Fatalf("claim work=%+v err=%v", work, err)
	}
}

func TestClientPublishesProviderEventWithAttemptCredential(t *testing.T) {
	hostID, attemptID := uuid.New(), uuid.New()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/runtime-hosts/"+hostID.String()+"/execution-attempts/"+attemptID.String()+"/events" {
			http.NotFound(w, r)
			return
		}
		if r.Header.Get("X-Execution-Attempt-Token") != "attempt-token" {
			http.Error(w, "missing attempt token", http.StatusUnauthorized)
			return
		}
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body["eventType"] != "assistant" || body["ordinal"] != float64(1) {
			http.Error(w, "invalid event", http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(`{"event":{"id":"event-1"},"attempt":{"id":"` + attemptID.String() + `","state":"failed"}}`))
	}))
	defer server.Close()
	client := &Client{BaseURL: server.URL, HTTPClient: server.Client()}
	client.RestoreAttemptToken(attemptID, "attempt-token")
	attempt := &controlmodel.ExecutionAttempt{ID: attemptID, LeaseToken: "lease", FencingToken: 3}
	if err := client.PublishProviderEvent(context.Background(), hostID, attempt, "qoder", 1,
		provider.Event{Type: "assistant", Raw: json.RawMessage(`{"type":"assistant"}`)}); err != nil {
		t.Fatal(err)
	}
	if attempt.State != controlmodel.ExecutionFailed {
		t.Fatalf("provider event response did not propagate terminal state: %+v", attempt)
	}
}

func TestClientUsesBearerForRuntimeHostCredential(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer asrh_scoped" || r.Header.Get("X-Builder-Internal-Token") != "" {
			http.Error(w, "wrong authentication", http.StatusUnauthorized)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"host":{"id":"00000000-0000-0000-0000-000000000001","hostKey":"host-1"}}`))
	}))
	defer server.Close()
	client := &Client{BaseURL: server.URL, InternalToken: "asrh_scoped", HTTPClient: server.Client()}
	host, err := client.Register(context.Background(), Registration{HostKey: "host-1", PoolName: "coding"})
	if err != nil || host == nil || host.HostKey != "host-1" {
		t.Fatalf("host=%+v err=%v", host, err)
	}
}

func TestToolApprovalRetriesTransientCreatePollAndAck(t *testing.T) {
	counts := map[string]int{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Agent-Task-Token") != "task-token" {
			t.Error("missing task credential")
		}
		counts[r.URL.Path]++
		if counts[r.URL.Path] == 1 {
			http.Error(w, "control plane restarting", http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasSuffix(r.URL.Path, "/decision"):
			_, _ = w.Write([]byte(`{"approvalId":"00000000-0000-4000-8000-000000000001","decisionVersion":2,"status":"approved","allow":true}`))
		case strings.HasSuffix(r.URL.Path, "/ack"):
			w.WriteHeader(http.StatusNoContent)
		default:
			_, _ = w.Write([]byte(`{"approval":{"id":"00000000-0000-4000-8000-000000000001"}}`))
		}
	}))
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	decision, err := (&Client{BaseURL: server.URL}).AwaitToolApproval(ctx, "task", "task-token", provider.ToolApprovalRequest{ToolUseID: "tool-one", ToolName: "Read"})
	if err != nil || !decision.Allow || decision.DecisionVersion != 2 {
		t.Fatalf("approval did not recover: %+v %v", decision, err)
	}
	for path, n := range counts {
		if n != 2 {
			t.Errorf("%s requests=%d", path, n)
		}
	}
}

func TestToolApprovalDoesNotRetryStaleDecision(t *testing.T) {
	polls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/decision") {
			polls++
			http.Error(w, "stale attempt", http.StatusConflict)
			return
		}
		_, _ = w.Write([]byte(`{"approval":{"id":"00000000-0000-4000-8000-000000000001"}}`))
	}))
	defer server.Close()
	_, err := (&Client{BaseURL: server.URL}).AwaitToolApproval(context.Background(), "task", "token", provider.ToolApprovalRequest{})
	if err == nil || polls != 1 {
		t.Fatalf("stale approval retried: polls=%d err=%v", polls, err)
	}
}

type approvalTestTransport func(*http.Request) (*http.Response, error)

func (f approvalTestTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestToolApprovalRetriesConnectionFailure(t *testing.T) {
	calls := 0
	client := &Client{BaseURL: "http://control.test", HTTPClient: &http.Client{Transport: approvalTestTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		if calls == 1 {
			return nil, errors.New("connection reset during restart")
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(`{"decisionVersion":2}`)), Request: r}, nil
	})}}
	var result map[string]any
	status, err := client.approvalRequestWithRetry(context.Background(), http.MethodGet, "/decision", nil, &result, nil)
	if err != nil || status != 200 || calls != 2 {
		t.Fatalf("connection retry: status=%d calls=%d err=%v", status, calls, err)
	}
}

func TestToolApprovalRetryHonorsCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	calls := 0
	client := &Client{BaseURL: "http://control.test", HTTPClient: &http.Client{Transport: approvalTestTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		cancel()
		return &http.Response{StatusCode: 503, Header: make(http.Header), Body: io.NopCloser(strings.NewReader("restarting")), Request: r}, nil
	})}}
	_, err := client.approvalRequestWithRetry(ctx, http.MethodGet, "/decision", nil, nil, nil)
	if !errors.Is(err, context.Canceled) || calls != 1 {
		t.Fatalf("cancelled retry continued: calls=%d err=%v", calls, err)
	}
}

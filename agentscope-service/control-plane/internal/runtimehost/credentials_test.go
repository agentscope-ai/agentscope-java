// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package runtimehost

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimehost/provider"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/taskauth"
)

func TestHostCredentialRefreshRestoresExpiredJournalTokenBeforeRenewal(t *testing.T) {
	hostID, id := uuid.New(), uuid.New()
	expires := time.Now().Add(time.Minute)
	attempt := &model.ExecutionAttempt{ID: id, HostID: &hostID, DispatchGeneration: 3,
		FencingToken: 7, LeaseToken: "owned", LeaseExpiresAt: &expires, State: model.ExecutionRunning}
	manager := taskauth.Manager{Secret: []byte(strings.Repeat("s", 32)), TTL: 15 * time.Minute}
	expired, _ := manager.MintAttempt(id, 3, "hosted-runtime", hostID.String(), time.Now().Add(-time.Hour))
	fresh, _ := manager.MintAttempt(id, 3, "hosted-runtime", hostID.String(), time.Now())
	operations := []string{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer asrh_host" || r.Header.Get("X-Managed-Agents-Internal-Token") != "" {
			t.Error("refresh leaked or lost Host identity")
		}
		operation := r.URL.Path[strings.LastIndex(r.URL.Path, "/")+1:]
		operations = append(operations, operation)
		var proof map[string]any
		_ = json.NewDecoder(r.Body).Decode(&proof)
		if proof["leaseToken"] != "owned" || proof["fencingToken"] != float64(7) {
			t.Error("refresh omitted physical ownership proof")
		}
		w.Header().Set("Content-Type", "application/json")
		if operation == "credentials" {
			_ = json.NewEncoder(w).Encode(map[string]any{"attempt": attempt, "attemptToken": fresh, "taskToken": "new-task"})
		} else {
			if r.Header.Get("X-Execution-Attempt-Token") != fresh {
				t.Error("reporting operation retained expired token")
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"attempt": attempt})
		}
	}))
	defer server.Close()
	client := &Client{BaseURL: server.URL, InternalToken: "asrh_host", HTTPClient: server.Client()}
	client.RestoreAttemptToken(id, expired)
	if err := client.Renew(t.Context(), hostID, attempt, time.Minute); err != nil {
		t.Fatal(err)
	}
	if strings.Join(operations, ",") != "credentials,renew" || client.CurrentTaskToken(id) != "new-task" {
		t.Fatalf("credential refresh operations=%v", operations)
	}
}

func TestTaskRelayRotatesFencedIdentityAndRejectsOtherAuthority(t *testing.T) {
	var current atomic.Value
	current.Store("first-task")
	seen := []string{}
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token := r.Header.Get("X-Agent-Task-Token")
		seen = append(seen, token)
		if r.Header.Get("Authorization") != "Bearer "+token || r.Header.Get("X-Managed-Agents-Internal-Token") != "" || r.Header.Get("Cookie") != "" {
			t.Error("relay forwarded unrelated credentials")
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer upstream.Close()
	relay, err := startTaskCredentialRelay(upstream.URL, upstream.URL+"/api/v1/mcp/collaboration", func() string { return current.Load().(string) })
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()
	call := func(path, method, credential string) int {
		r, _ := http.NewRequest(method, relay.URL+path, strings.NewReader(`{}`))
		r.Header.Set("Authorization", "Bearer "+credential)
		r.Header.Set("X-Managed-Agents-Internal-Token", "must-not-forward")
		r.Header.Set("Cookie", "human-session")
		response, err := http.DefaultClient.Do(r)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		return response.StatusCode
	}
	if status := call("/mcp/collaboration", http.MethodPost, relay.Token); status != 200 {
		t.Fatalf("first MCP=%d", status)
	}
	current.Store("second-task")
	if status := call("/api/v1/agent-tasks/"+uuid.NewString()+"/run/node/complete", http.MethodPost, relay.Token); status != 200 {
		t.Fatalf("rotated CLI=%d", status)
	}
	for _, path := range []string{"/api/v1/runtime-hosts/register", "/api/v1/agents/anything", "/api/v1/agent-tasks/../runtime-hosts", "/mcp/collaboration"} {
		if status := call(path, http.MethodGet, relay.Token); status != 404 {
			t.Fatalf("unrelated path %s=%d", path, status)
		}
	}
	if status := call("/mcp/collaboration", http.MethodPost, "host-or-human"); status != 401 {
		t.Fatalf("unrelated authority=%d", status)
	}
	current.Store("")
	if status := call("/mcp/collaboration", http.MethodPost, relay.Token); status != 403 {
		t.Fatalf("revoked relay=%d", status)
	}
	if strings.Join(seen, ",") != "first-task,second-task" {
		t.Fatalf("forwarded tokens=%v", seen)
	}
}

func TestApprovalPollingReadsRotatedTokenBeforeDecisionAck(t *testing.T) {
	id := uuid.New()
	client := &Client{}
	client.taskTokens.Store(id, "first")
	seen := []string{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = append(seen, r.Header.Get("X-Agent-Task-Token"))
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasSuffix(r.URL.Path, "/decision"):
			_, _ = w.Write([]byte(`{"decisionVersion":2,"allow":true}`))
		case strings.HasSuffix(r.URL.Path, "/ack"):
			w.WriteHeader(http.StatusNoContent)
		default:
			client.taskTokens.Store(id, "second")
			_, _ = w.Write([]byte(`{"approval":{"id":"00000000-0000-4000-8000-000000000001"}}`))
		}
	}))
	defer server.Close()
	client.BaseURL = server.URL
	decision, err := client.AwaitToolApprovalRefreshing(t.Context(), uuid.NewString(), id,
		provider.ToolApprovalRequest{ToolUseID: "tool", ToolName: "Read"})
	if err != nil || !decision.Allow || strings.Join(seen, ",") != "first,second,second" {
		t.Fatalf("approval rotation decision=%+v tokens=%v err=%v", decision, seen, err)
	}
}

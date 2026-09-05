// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package runtimehost

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
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

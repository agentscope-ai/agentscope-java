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

package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func withTaskCLIServer(t *testing.T, handler http.HandlerFunc) {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	previousEndpoint, previousToken := apiEndpoint, apiToken
	apiEndpoint, apiToken = server.URL, "must-not-be-sent"
	t.Cleanup(func() { apiEndpoint, apiToken = previousEndpoint, previousToken })
	t.Setenv("MANAGED_AGENTS_TASK_TOKEN", "task-secret")
	t.Setenv("MANAGED_AGENTS_TASK_ID", "task-1")
	t.Setenv("MANAGED_AGENTS_ISSUE_ID", "issue-1")
	t.Setenv("MANAGED_AGENTS_TEAM_ID", "team-1")
}

func TestTaskContextUsesEnvironmentAndOnlyTaskCredential(t *testing.T) {
	withTaskCLIServer(t, func(w http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/api/v1/agent-tasks/task-1/context" {
			t.Errorf("path=%s", request.URL.Path)
		}
		if request.Header.Get("X-Agent-Task-Token") != "task-secret" {
			t.Errorf("task token=%q", request.Header.Get("X-Agent-Task-Token"))
		}
		if request.Header.Get("Authorization") != "" {
			t.Errorf("human credential leaked: %q", request.Header.Get("Authorization"))
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"task":{"id":"task-1"}}`))
	})
	if err := taskContextCmd().Execute(); err != nil {
		t.Fatal(err)
	}
}

func TestIssueCommentAddUsesCurrentIssueAndContentFile(t *testing.T) {
	withTaskCLIServer(t, func(w http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/api/v1/issues/issue-1/comments" || request.Method != http.MethodPost {
			t.Errorf("request=%s %s", request.Method, request.URL.Path)
		}
		if request.Header.Get("X-Agent-Task-Token") != "task-secret" {
			t.Errorf("task token=%q", request.Header.Get("X-Agent-Task-Token"))
		}
		body, _ := io.ReadAll(request.Body)
		var payload struct {
			Content string `json:"content"`
		}
		if err := json.Unmarshal(body, &payload); err != nil || payload.Content != "finished\nwith details\n" {
			t.Errorf("body=%s err=%v", body, err)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"comment":{"id":"comment-1"}}`))
	})
	path := filepath.Join(t.TempDir(), "reply.md")
	if err := os.WriteFile(path, []byte("finished\nwith details\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	cmd := issueCommentAddCmd()
	cmd.SetArgs([]string{"--content-file", path})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
}

func TestTaskRunGraphUsesCurrentTask(t *testing.T) {
	withTaskCLIServer(t, func(w http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/api/v1/agent-tasks/task-1/run/graph" {
			t.Errorf("path=%s", request.URL.Path)
		}
		if request.Header.Get("X-Agent-Task-Token") != "task-secret" {
			t.Errorf("task token=%q", request.Header.Get("X-Agent-Task-Token"))
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"nodes":[]}`))
	})
	if err := taskRunGetCmd("graph", "/graph").Execute(); err != nil {
		t.Fatal(err)
	}
}

func TestAgentTaskTokenSupportsLegacyEnvironmentName(t *testing.T) {
	t.Setenv("MANAGED_AGENTS_TASK_TOKEN", "")
	t.Setenv("CONTROL_PLANE_AGENT_TASK_TOKEN", "legacy-token")
	if got := agentTaskToken(); got != "legacy-token" {
		t.Fatalf("token=%q", got)
	}
}

func TestTaskContextOutputRedactsCredential(t *testing.T) {
	previous := os.Stdout
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	os.Stdout = writer
	t.Cleanup(func() { os.Stdout = previous })
	response := &http.Response{
		StatusCode: http.StatusOK,
		Body:       io.NopCloser(bytes.NewBufferString(`{"task":{"id":"task-1"},"taskToken":"must-not-print"}`)),
	}
	if err = printTaskContextResponse(response, nil); err != nil {
		t.Fatal(err)
	}
	_ = writer.Close()
	output, err := io.ReadAll(reader)
	if err != nil {
		t.Fatal(err)
	}
	os.Stdout = previous
	if bytes.Contains(output, []byte("must-not-print")) || !bytes.Contains(output, []byte("task-1")) {
		t.Fatalf("redacted output=%s", output)
	}
}

func TestTaskRunSignalKeepsPendingIdentityKeyAndExactValue(t *testing.T) {
	called := false
	withTaskCLIServer(t, func(w http.ResponseWriter, request *http.Request) {
		called = true
		if request.URL.Path != "/api/v1/agent-tasks/task-1/run/signals/ready" || request.Header.Get("X-Agent-Task-Token") != "task-secret" || request.Header.Get("Authorization") != "" {
			t.Errorf("signal transport scope=%s", request.URL.Path)
		}
		var body struct {
			RequestID      string          `json:"requestId"`
			IdempotencyKey string          `json:"idempotencyKey"`
			Payload        json.RawMessage `json:"payload"`
		}
		if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		if body.RequestID != "node-1" || body.IdempotencyKey != "original-key" || !bytes.Contains(body.Payload, []byte("9007199254740993")) {
			t.Errorf("signal body=%+v", body)
		}
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(`{"command_id":"durable-command","status":"queued"}`))
	})
	file := filepath.Join(t.TempDir(), "signal.json")
	if err := os.WriteFile(file, []byte(`{"value":9007199254740993}`), 0600); err != nil {
		t.Fatal(err)
	}
	cmd := taskRunSignalCmd()
	cmd.SetArgs([]string{"ready", "--request-id=node-1", "--idempotency-key=original-key", "--file=" + file})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !called {
		t.Fatal("signal was not submitted")
	}
}
func TestTaskClientRejectsRedirectBeforeCredentialForwarding(t *testing.T) {
	redirected := false
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { redirected = true; w.WriteHeader(200) }))
	defer target.Close()
	withTaskCLIServer(t, func(w http.ResponseWriter, request *http.Request) {
		http.Redirect(w, request, target.URL, http.StatusTemporaryRedirect)
	})
	response, err := doTaskAPI(http.MethodPost, "/api/v1/agent-tasks/task-1/run/signals/ready", []byte(`{}`), "task-secret")
	if response != nil && response.Body != nil {
		response.Body.Close()
	}
	if err == nil || redirected {
		t.Fatalf("redirect=%v, err=%v", redirected, err)
	}
}

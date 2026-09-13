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
	t.Setenv("AGENTSCOPE_TASK_TOKEN", "task-secret")
	t.Setenv("AGENTSCOPE_TASK_ID", "task-1")
	t.Setenv("AGENTSCOPE_ISSUE_ID", "issue-1")
	t.Setenv("AGENTSCOPE_TEAM_ID", "team-1")
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
	t.Setenv("AGENTSCOPE_TASK_TOKEN", "")
	t.Setenv("AISTIO_AGENT_TASK_TOKEN", "legacy-token")
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

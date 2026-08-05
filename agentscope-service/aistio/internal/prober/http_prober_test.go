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

package prober

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/google/uuid"
)

func TestProbeInfo(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/agentscope/info" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{
			"name": "chatbot",
			"runtime": "agentscope-java",
			"version": "1.0.0",
			"sdkVersion": "2.1.0",
			"contractLevel": 2,
			"capabilities": ["session-reporting","hot-reload"],
			"port": 8080,
			"agentConfig": {"model": "qwen-max", "modelProvider": "DashScope", "tools": ["search"]}
		}`))
	}))
	defer srv.Close()

	p := NewHTTPProber()
	info, err := p.ProbeInfo(context.Background(), srv.URL)
	if err != nil {
		t.Fatalf("ProbeInfo: %v", err)
	}
	if info.ContractLevel != 2 {
		t.Errorf("contractLevel = %d, want 2", info.ContractLevel)
	}
	if info.Runtime != "agentscope-java" {
		t.Errorf("runtime = %q", info.Runtime)
	}
	if len(info.Capabilities) != 2 {
		t.Errorf("capabilities = %v", info.Capabilities)
	}
	if info.AgentConfig == nil || info.AgentConfig.Model != "qwen-max" {
		t.Errorf("agentConfig = %+v", info.AgentConfig)
	}
}

func TestProbeInfo_Non200(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer srv.Close()

	p := NewHTTPProber()
	if _, err := p.ProbeInfo(context.Background(), srv.URL); err == nil {
		t.Error("expected error on non-200 response")
	}
}

func TestProbeHealth(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	p := NewHTTPProber()
	ok, err := p.ProbeHealth(context.Background(), srv.URL)
	if err != nil {
		t.Fatalf("ProbeHealth: %v", err)
	}
	if !ok {
		t.Error("expected healthy")
	}
}

func TestFetchContext(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/agentscope/sessions/s1/context" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{
			"sessionId": "s1",
			"contextHash": "abc123",
			"systemPrompt": "you are helpful",
			"messages": [{"role": "user", "content": "hi"}],
			"tools": [{"name": "search", "description": "web search"}],
			"isCompacted": true,
			"compactionSummary": "older turns summarized",
			"originalMessageCount": 42,
			"compactedAt": "2026-07-28T10:00:00Z",
			"totalTokens": 12000,
			"maxTokens": 32000,
			"framework": "claude-agent-sdk",
			"frameworkState": {"memoryFile": "/tmp/MEMORY.md"}
		}`))
	}))
	defer srv.Close()

	p := NewHTTPProber()
	snap, err := p.FetchContext(context.Background(), srv.URL, "s1")
	if err != nil {
		t.Fatalf("FetchContext: %v", err)
	}
	if snap.ContextHash != "abc123" {
		t.Errorf("contextHash = %q", snap.ContextHash)
	}
	if len(snap.Messages) != 1 || snap.Messages[0].Role != "user" {
		t.Errorf("messages = %+v", snap.Messages)
	}
	if len(snap.Tools) != 1 || snap.Tools[0].Name != "search" {
		t.Errorf("tools = %+v", snap.Tools)
	}
	if !snap.IsCompacted || snap.OriginalMessageCount != 42 || snap.CompactedAt == "" {
		t.Errorf("compaction fields = %+v", snap)
	}
	if snap.Framework != "claude-agent-sdk" {
		t.Errorf("framework = %q", snap.Framework)
	}
}

func TestFetchContext_NotFound(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer srv.Close()

	p := NewHTTPProber()
	_, err := p.FetchContext(context.Background(), srv.URL, "missing")
	if err != ErrNotFoundOnDataPlane {
		t.Errorf("expected ErrNotFoundOnDataPlane, got %v", err)
	}
}

func TestFetchMessages(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/agentscope/sessions/s1/messages" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		if r.URL.Query().Get("offset") != "10" || r.URL.Query().Get("limit") != "5" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{
			"sessionId": "s1", "offset": 10, "limit": 5, "total": 42,
			"messages": [{"seq": 11, "role": "assistant", "content": "hello", "toolName": "search", "occurredAt": "2026-07-28T10:00:00Z"}]
		}`))
	}))
	defer srv.Close()

	p := NewHTTPProber()
	page, err := p.FetchMessages(context.Background(), srv.URL, "s1", 10, 5)
	if err != nil {
		t.Fatalf("FetchMessages: %v", err)
	}
	if page.Total != 42 || len(page.Messages) != 1 || page.Messages[0].Seq != 11 {
		t.Errorf("page = %+v", page)
	}
	if page.Messages[0].ToolName != "search" {
		t.Errorf("toolName = %q", page.Messages[0].ToolName)
	}
}

func TestFetchSubagents(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/agentscope/subagents" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"subagents": [{"name": "researcher", "tools": ["search"], "workspaceMode": "isolated", "invokeCount": 3, "lastInvokedAt": "2026-07-28T10:00:00Z"}]}`))
	}))
	defer srv.Close()

	p := NewHTTPProber()
	subs, err := p.FetchSubagents(context.Background(), srv.URL)
	if err != nil {
		t.Fatalf("FetchSubagents: %v", err)
	}
	if len(subs) != 1 || subs[0].Name != "researcher" || subs[0].InvokeCount != 3 {
		t.Errorf("subagents = %+v", subs)
	}
}

func TestFetchWorkspaces(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/agentscope/workspaces" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"workspaces": [{"path": "/tmp/ws1", "mode": "shared", "sizeBytes": 1024, "ownerRef": "sess-1"}]}`))
	}))
	defer srv.Close()

	p := NewHTTPProber()
	workspaces, err := p.FetchWorkspaces(context.Background(), srv.URL)
	if err != nil {
		t.Fatalf("FetchWorkspaces: %v", err)
	}
	if len(workspaces) != 1 || workspaces[0].Path != "/tmp/ws1" || workspaces[0].SizeBytes != 1024 {
		t.Errorf("workspaces = %+v", workspaces)
	}
}

func TestToStoreContext(t *testing.T) {
	probed := &ContextSnapshot{
		SessionID:            "s1",
		ContextHash:          "hash-1",
		SystemPrompt:         "sys",
		Messages:             []ContextMessage{{Role: "user", Content: "hi"}},
		Tools:                []ToolInfo{{Name: "search"}},
		IsCompacted:          true,
		OriginalMessageCount: 42,
		CompactedAt:          "2026-07-28T10:00:00Z",
		TotalTokens:          12000,
		MaxTokens:            32000,
	}

	row, err := probed.ToStoreContext(uuid.New(), "fallback-fw")
	if err != nil {
		t.Fatalf("ToStoreContext: %v", err)
	}
	if row.Framework != "fallback-fw" {
		t.Errorf("framework = %q, want fallback-fw", row.Framework)
	}
	if row.ContextHash != "hash-1" || row.OriginalMessageCount != 42 || row.TotalTokens != 12000 {
		t.Errorf("row = %+v", row)
	}
	if row.CompactedAt == nil {
		t.Error("expected CompactedAt parsed")
	}
	if !json.Valid(row.Messages) || !json.Valid(row.Tools) {
		t.Error("messages/tools must be valid JSON")
	}

	// Reported framework wins over the fallback.
	probed.Framework = "claude-agent-sdk"
	row, err = probed.ToStoreContext(uuid.New(), "fallback-fw")
	if err != nil {
		t.Fatalf("ToStoreContext: %v", err)
	}
	if row.Framework != "claude-agent-sdk" {
		t.Errorf("framework = %q", row.Framework)
	}
}

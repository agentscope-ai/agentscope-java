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

package qwenpaw

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

func TestRunSessionUsesACPAndCollectsStreamingText(t *testing.T) {
	responses := strings.Join([]string{
		`{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}`,
		`{"jsonrpc":"2.0","id":2,"result":{"sessionId":"paw-session"}}`,
		`{"jsonrpc":"2.0","id":3,"result":{}}`,
		`{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"paw-session","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"done"}}}}`,
		`{"jsonrpc":"2.0","id":4,"result":{"stopReason":"end_turn"}}`,
	}, "\n")
	var requests bytes.Buffer
	client := newACPClient(&requests, strings.NewReader(responses), nil)
	result, err := runSession(client, provider.Request{
		Workspace: "/tmp/work", Prompt: "task", Definition: &provider.AgentDefinition{Model: "qwen:test"},
	}, configuration{})
	if err != nil {
		t.Fatal(err)
	}
	if result.ProviderSessionID != "paw-session" || result.Output != "done" {
		t.Fatalf("result=%+v", result)
	}
	if got := requests.String(); !strings.Contains(got, `"method":"session/new"`) ||
		!strings.Contains(got, `"method":"session/set_model"`) || !strings.Contains(got, `"modelId":"qwen:test"`) {
		t.Fatalf("requests=%s", got)
	}
}

func TestACPClientRejectsPermissionByDefault(t *testing.T) {
	responses := strings.Join([]string{
		`{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}`,
		`{"jsonrpc":"2.0","id":2,"result":{"sessionId":"paw-session"}}`,
		`{"jsonrpc":"2.0","id":9001,"method":"session/request_permission","params":{"sessionId":"paw-session"}}`,
		`{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}`,
	}, "\n")
	var requests bytes.Buffer
	client := newACPClient(&requests, strings.NewReader(responses), nil)
	if _, err := runSession(client, provider.Request{Workspace: "/tmp/work", Prompt: "task"}, configuration{}); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(requests.String(), `"outcome":"cancelled"`) {
		t.Fatalf("permission response=%s", requests.String())
	}
}

func TestACPClientBridgesPermissionToControlPlaneApprover(t *testing.T) {
	responses := strings.Join([]string{
		`{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}`,
		`{"jsonrpc":"2.0","id":2,"result":{"sessionId":"paw-session"}}`,
		`{"jsonrpc":"2.0","id":9001,"method":"session/request_permission","params":{"sessionId":"paw-session","toolCall":{"id":"call-1","name":"shell","input":{"command":"date"}},"options":[{"optionId":"allow_once","kind":"allow_once","name":"Allow once"},{"optionId":"reject_once","kind":"reject_once","name":"Reject"}]}}`,
		`{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}`,
	}, "\n")
	var requests bytes.Buffer
	client := newACPClient(&requests, strings.NewReader(responses), nil)
	client.approver = func(_ context.Context, request provider.ToolApprovalRequest) (provider.ToolApprovalDecision, error) {
		if request.ToolUseID != "call-1" || request.ToolName != "shell" {
			t.Fatalf("approval request=%+v", request)
		}
		return provider.ToolApprovalDecision{Allow: true, DecisionVersion: 2}, nil
	}
	if _, err := runSession(client, provider.Request{Workspace: "/tmp/work", Prompt: "task"}, configuration{}); err != nil {
		t.Fatal(err)
	}
	if got := requests.String(); !strings.Contains(got, `"outcome":"selected"`) ||
		!strings.Contains(got, `"optionId":"allow_once"`) {
		t.Fatalf("permission response=%s", got)
	}
}

func TestACPMCPServersIncludeTaskCredentialWithoutArgumentExposure(t *testing.T) {
	raw, _ := json.Marshal([]map[string]any{{"name": "docs", "url": "https://example.test/mcp", "headers": map[string]string{"X-Test": "yes"}}})
	servers, err := acpMCPServers(provider.Request{
		Definition: &provider.AgentDefinition{MCPServers: raw}, CollaborationMCP: "https://control.test/mcp", TaskToken: "secret",
	})
	if err != nil || len(servers) != 2 {
		t.Fatalf("servers=%+v err=%v", servers, err)
	}
	encoded, _ := json.Marshal(servers)
	if !strings.Contains(string(encoded), "Bearer secret") || !strings.Contains(string(encoded), "docs") {
		t.Fatalf("servers=%s", encoded)
	}
}

func TestEnableProjectedSkillsRestoresExistingManifest(t *testing.T) {
	workspace := t.TempDir()
	skill := filepath.Join(workspace, ".agentscope", "definition", "skills", "review")
	if err := os.MkdirAll(skill, 0o750); err != nil {
		t.Fatal(err)
	}
	original := []byte(`{"skills":{"owned":{"enabled":false}}}`)
	if err := os.WriteFile(filepath.Join(workspace, "skill.json"), original, 0o640); err != nil {
		t.Fatal(err)
	}
	cleanup, err := enableProjectedSkills(workspace)
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(workspace, "skill.json"))
	if err != nil || !strings.Contains(string(data), `"review":{"channels":["all"],"enabled":true`) {
		t.Fatalf("manifest=%s err=%v", data, err)
	}
	cleanup()
	restored, err := os.ReadFile(filepath.Join(workspace, "skill.json"))
	if err != nil || !bytes.Equal(restored, original) {
		t.Fatalf("restored=%s err=%v", restored, err)
	}
}

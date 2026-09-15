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

package openclaw

import (
	"strings"
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

func TestBuildArgsUsesHeadlessWorkspaceContract(t *testing.T) {
	args := buildArgs(provider.Request{
		Workspace: "/tmp/work", Definition: &provider.AgentDefinition{Model: "openai/test"},
	}, configuration{Fallbacks: []string{"anthropic/fallback"}, Thinking: "high", CodeMode: "code", TimeoutSeconds: 90})
	got := strings.Join(args, " ")
	want := "agent exec --cwd /tmp/work --message-file - --json --model openai/test --fallback anthropic/fallback --thinking high --code-mode code --timeout 90"
	if got != want {
		t.Fatalf("args=%q want=%q", got, want)
	}
}

func TestDecodeResult(t *testing.T) {
	result, err := decodeResult([]byte(`{"ok":true,"status":"ok","final":"done","sessionId":"session-1"}`))
	if err != nil {
		t.Fatal(err)
	}
	if result.Output != "done" || result.ProviderSessionID != "session-1" {
		t.Fatalf("result=%+v", result)
	}
}

func TestDecodeResultFailure(t *testing.T) {
	if _, err := decodeResult([]byte(`{"ok":false,"status":"error","error":{"message":"no model","kind":"config"}}`)); err == nil {
		t.Fatal("expected OpenClaw error envelope")
	}
}

func TestValidateRequestCapabilitiesRejectsResumeAndAgentMCP(t *testing.T) {
	if err := validateRequestCapabilities(provider.Request{ProviderSessionID: "session-1"}); err == nil || !strings.Contains(err.Error(), "resume") {
		t.Fatalf("resume error = %v", err)
	}
	if err := validateRequestCapabilities(provider.Request{Definition: &provider.AgentDefinition{
		MCPServers: []byte(`[{"name":"docs","type":"http","url":"https://example.test/mcp"}]`),
	}}); err == nil || !strings.Contains(err.Error(), "MCP") {
		t.Fatalf("MCP error = %v", err)
	}
	if err := validateRequestCapabilities(provider.Request{Definition: &provider.AgentDefinition{
		MCPServers: []byte(`[]`),
	}}); err != nil {
		t.Fatalf("empty MCP list error = %v", err)
	}
}

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

package provider

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestWriteMCPConfigProtectsTaskCredential(t *testing.T) {
	definitionMCP, _ := json.Marshal([]map[string]any{{
		"name": "repository", "type": "http", "url": "https://mcp.example/repository",
	}})
	path, cleanup, err := WriteMCPConfig(Request{
		CollaborationMCP: "https://control.example/mcp/collaboration", TaskToken: "task-secret",
		Definition: &AgentDefinition{MCPServers: definitionMCP},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer cleanup()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("MCP config permissions=%v", info.Mode().Perm())
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var config struct {
		MCPServers map[string]any `json:"mcpServers"`
	}
	if json.Unmarshal(data, &config) != nil || !json.Valid(data) ||
		config.MCPServers["repository"] == nil || config.MCPServers["agentscope-collaboration"] == nil {
		t.Fatalf("invalid MCP config: %s", data)
	}
}

func TestPortableDefinitionOverridesProviderDefaults(t *testing.T) {
	request := Request{
		Prompt: "fix the test",
		Definition: &AgentDefinition{
			System: "Be precise.",
			Model:  "agent-model",
		},
	}
	if got := DefinitionModel(request, "profile-model"); got != "agent-model" {
		t.Fatalf("model=%q", got)
	}
	if got := PrependInstructions(request.Prompt, DefinitionInstructions(request)); got != "Agent instructions:\nBe precise.\n\nTask:\nfix the test" {
		t.Fatalf("prompt=%q", got)
	}
}

func TestValidateCustomArgsRejectsRuntimeOwnedArguments(t *testing.T) {
	if args, err := ValidateCustomArgs([]string{"--profile", "work"}); err != nil || len(args) != 2 {
		t.Fatalf("safe args=%v err=%v", args, err)
	}
	for _, args := range [][]string{{"--cd", "/tmp/escape"}, {"--model=gpt-test"},
		{"--config", "mcp_servers.evil.url=x"}, {"--tools", "default"},
		{"--allowed-mcp-server-names", "ambient"}, {"--config-dir", "/tmp/ambient"},
		{"--listen", "tcp://127.0.0.1:1234"}} {
		if _, err := ValidateCustomArgs(args); err == nil {
			t.Fatalf("reserved custom args were accepted: %v", args)
		}
	}
}

func TestDefinitionToolPolicyUsesProviderAliases(t *testing.T) {
	enabled, disabled := true, false
	tools, _ := json.Marshal([]map[string]any{{"configs": []map[string]any{
		{"name": "read", "enabled": enabled},
		{"name": "shell", "enabled": disabled},
	}}})
	allowed, denied := DefinitionToolPolicy(Request{
		Definition: &AgentDefinition{Tools: tools},
	}, map[string]string{"read": "Read", "shell": "Bash"})
	if len(allowed) != 1 || allowed[0] != "Read" || len(denied) != 1 || denied[0] != "Bash" {
		t.Fatalf("allowed=%v denied=%v", allowed, denied)
	}
}

func TestApplyTaskEnvironmentInjectsScopedContextAndRemovesHostCredentials(t *testing.T) {
	t.Setenv("AGENTSCOPE_API_TOKEN", "human-secret")
	t.Setenv("AGENTSCOPE_RUNTIME_TOKEN", "runtime-secret")
	t.Setenv("AISTIO_INTERNAL_TOKEN", "host-secret")
	t.Setenv("PATH", "/usr/bin")
	cmd := exec.Command("ignored")
	ApplyTaskEnvironment(cmd, Request{
		CollaborationCLI: "/opt/agentscope/bin/agentscope", ControlPlane: "https://control.example",
		TaskToken: "task-secret", TaskID: "task-1", IssueID: "issue-1", AgentID: "agent-1",
		TeamID: "team-1", RunID: "run-1",
	})
	values := make(map[string][]string)
	for _, item := range cmd.Env {
		key, value, _ := strings.Cut(item, "=")
		values[key] = append(values[key], value)
	}
	for _, key := range []string{"AGENTSCOPE_API_TOKEN", "AGENTSCOPE_RUNTIME_TOKEN", "AISTIO_INTERNAL_TOKEN"} {
		if len(values[key]) != 0 {
			t.Fatalf("%s leaked into Agent environment", key)
		}
	}
	for key, want := range map[string]string{
		"AGENTSCOPE_TASK_TOKEN": "task-secret", "AISTIO_AGENT_TASK_TOKEN": "task-secret",
		"AGENTSCOPE_CONTROL_PLANE": "https://control.example", "AGENTSCOPE_TASK_ID": "task-1",
		"AGENTSCOPE_ISSUE_ID": "issue-1", "AGENTSCOPE_AGENT_ID": "agent-1",
		"AGENTSCOPE_TEAM_ID": "team-1", "AGENTSCOPE_RUN_ID": "run-1",
	} {
		if len(values[key]) != 1 || values[key][0] != want {
			t.Fatalf("%s=%v, want %q", key, values[key], want)
		}
	}
	if len(values["PATH"]) != 1 || !strings.HasPrefix(values["PATH"][0], filepath.FromSlash("/opt/agentscope/bin")+string(os.PathListSeparator)) {
		t.Fatalf("PATH was not safely prefixed: %v", values["PATH"])
	}
}

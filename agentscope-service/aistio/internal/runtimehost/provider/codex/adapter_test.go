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

package codex

import (
	"errors"
	"strings"
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

func TestDescriptorUsesCodexNativeSkillsDirectory(t *testing.T) {
	descriptor := (&Adapter{}).Descriptor()
	if !descriptor.Skills.Supported || descriptor.Skills.Mode != "native-directory" ||
		descriptor.Skills.Target != ".agents/skills" {
		t.Fatalf("skills capability = %+v", descriptor.Skills)
	}
}

func TestBuildArgsNewSession(t *testing.T) {
	args := buildArgs(provider.Request{Workspace: "/tmp/work"}, configuration{Model: "gpt-test"})
	got := strings.Join(args, " ")
	want := "exec --json --model gpt-test --skip-git-repo-check --sandbox workspace-write --cd /tmp/work -"
	if got != want {
		t.Fatalf("args=%q, want %q", got, want)
	}
}

func TestBuildArgsCanRequireGitRepositoryExplicitly(t *testing.T) {
	requireGit := false
	args := buildArgs(provider.Request{Workspace: "/tmp/work"}, configuration{SkipGitRepoCheck: &requireGit})
	if got := strings.Join(args, " "); strings.Contains(got, "--skip-git-repo-check") {
		t.Fatalf("explicit Git repository policy was ignored: %q", got)
	}
}

func TestBuildArgsUsesPortableDefinitionModel(t *testing.T) {
	args := buildArgs(provider.Request{
		Workspace: "/tmp/work", Definition: &provider.AgentDefinition{Model: "agent-model"},
	}, configuration{Model: "profile-model"})
	if got := strings.Join(args, " "); !strings.Contains(got, "--model agent-model") || strings.Contains(got, "profile-model") {
		t.Fatalf("args=%q", got)
	}
}

func TestBuildArgsAppliesExecutionOverridesAndSafeCustomArgs(t *testing.T) {
	args := buildArgs(provider.Request{Workspace: "/tmp/work", CustomArgs: []string{"--profile", "work"}},
		configuration{ReasoningEffort: "high", ServiceTier: "priority"})
	got := strings.Join(args, " ")
	for _, expected := range []string{`model_reasoning_effort="high"`, `service_tier="priority"`, "--profile work"} {
		if !strings.Contains(got, expected) {
			t.Fatalf("args=%q missing %q", got, expected)
		}
	}
}

func TestBuildArgsResume(t *testing.T) {
	args := buildArgs(provider.Request{Workspace: "/tmp/work", ProviderSessionID: "thread-1"}, configuration{})
	got := strings.Join(args, " ")
	if got != "exec resume --json --skip-git-repo-check thread-1 -" {
		t.Fatalf("args=%q", got)
	}
}

func TestBuildArgsInjectsTaskScopedCollaborationMCP(t *testing.T) {
	args := buildArgs(provider.Request{Workspace: "/tmp/work", CollaborationMCP: "https://control.example/mcp/collaboration", TaskToken: "secret"}, configuration{})
	got := strings.Join(args, " ")
	if !strings.Contains(got, `mcp_servers.agentscope_collaboration.url="https://control.example/mcp/collaboration"`) ||
		!strings.Contains(got, `mcp_servers.agentscope_collaboration.bearer_token_env_var="AGENTSCOPE_TASK_TOKEN"`) ||
		!strings.Contains(got, `mcp_servers.agentscope_collaboration.default_tools_approval_mode="approve"`) ||
		strings.Contains(got, "secret") {
		t.Fatalf("task MCP configuration is unsafe or incomplete: %q", got)
	}
}

func TestBuildArgsAllowsHostedAgentToReachTaskScopedCollaboration(t *testing.T) {
	args := buildArgs(provider.Request{Workspace: "/tmp/work", CollaborationMCP: "http://127.0.0.1:18080/mcp/collaboration",
		TaskToken: "secret"}, configuration{})
	got := strings.Join(args, " ")
	if !strings.Contains(got, "sandbox_workspace_write.network_access=true") ||
		!strings.Contains(got, "--sandbox workspace-write") || strings.Contains(got, "danger-full-access") {
		t.Fatalf("hosted collaboration should retain workspace isolation with network access: %q", got)
	}
}

func TestBuildArgsDoesNotRelaxExplicitReadOnlySandbox(t *testing.T) {
	args := buildArgs(provider.Request{Workspace: "/tmp/work", CollaborationMCP: "http://127.0.0.1:18080/mcp/collaboration",
		TaskToken: "secret"}, configuration{Sandbox: "read-only"})
	if got := strings.Join(args, " "); strings.Contains(got, "sandbox_workspace_write.network_access=true") {
		t.Fatalf("explicit read-only sandbox was relaxed: %q", got)
	}
}

func TestConsumeJSONL(t *testing.T) {
	input := strings.Join([]string{
		`{"type":"thread.started","thread_id":"thread-1"}`,
		`{"type":"item.completed","item":{"type":"agent_message","text":"done"}}`,
	}, "\n")
	result := &provider.Result{}
	var eventTypes []string
	if err := consumeJSONL(strings.NewReader(input), result, func(event provider.Event) error {
		eventTypes = append(eventTypes, event.Type)
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if result.ProviderSessionID != "thread-1" || result.Output != "done" || len(eventTypes) != 2 {
		t.Fatalf("result=%+v events=%v", result, eventTypes)
	}
}

func TestConsumeJSONLReturnsPermissionFailureForBlockedOutcome(t *testing.T) {
	input := strings.Join([]string{
		`{"type":"thread.started","thread_id":"thread-1"}`,
		`{"type":"item.completed","item":{"type":"mcp_tool_call","status":"failed","error":{"message":"MCP tool call requires approval, but approval policy is never"}}}`,
		`{"type":"item.completed","item":{"type":"agent_message","text":"无法执行任务，因为控制面权限被拒绝。"}}`,
	}, "\n")
	result := &provider.Result{}
	err := consumeJSONL(strings.NewReader(input), result, nil)
	var executionError *provider.ExecutionError
	if !errors.As(err, &executionError) || executionError.Code != "provider_permission_denied" {
		t.Fatalf("error=%v, want provider_permission_denied", err)
	}
}

func TestConsumeJSONLAllowsSuccessfulFallbackAfterPermissionFailure(t *testing.T) {
	input := strings.Join([]string{
		`{"type":"item.completed","item":{"type":"mcp_tool_call","status":"failed","error":{"message":"MCP tool call requires approval"}}}`,
		`{"type":"item.completed","item":{"type":"agent_message","text":"任务已通过受限 CLI 成功完成。"}}`,
	}, "\n")
	result := &provider.Result{}
	if err := consumeJSONL(strings.NewReader(input), result, nil); err != nil {
		t.Fatalf("successful fallback should remain successful: %v", err)
	}
}

func TestCodexExitErrorOmitsSkillIconWarnings(t *testing.T) {
	err := codexExitError(errors.New("signal: killed"), strings.Join([]string{
		"2026-09-04T15:25:46Z WARN codex_skills::interface: ignoring interface.icon_small: icon path with '..' must resolve under plugin assets/",
		"fatal provider detail",
	}, "\n"))
	if strings.Contains(err.Error(), "icon_small") || !strings.Contains(err.Error(), "fatal provider detail") {
		t.Fatalf("unexpected Codex failure: %v", err)
	}
}

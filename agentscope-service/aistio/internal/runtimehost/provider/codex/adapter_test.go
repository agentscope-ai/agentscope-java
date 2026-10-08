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

package codex

import (
	"bytes"
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

func TestDescriptorAdvertisesBidirectionalApproval(t *testing.T) {
	descriptor := (&Adapter{}).Descriptor()
	if !descriptor.Skills.Supported || descriptor.Skills.Mode != "native-directory" ||
		descriptor.Skills.Target != ".agents/skills" {
		t.Fatalf("skills capability = %+v", descriptor.Skills)
	}
	if !descriptor.Approval.Supported || descriptor.Approval.Mode != "control-plane" ||
		descriptor.CustomArgs.Target != "codex app-server" {
		t.Fatalf("descriptor=%+v", descriptor)
	}
}

func TestBuildArgsStartsAppServer(t *testing.T) {
	args := buildArgs(provider.Request{Workspace: "/tmp/work"}, configuration{Model: "gpt-test"})
	if got, want := strings.Join(args, " "), "app-server --listen stdio://"; got != want {
		t.Fatalf("args=%q, want %q", got, want)
	}
}

func TestBuildArgsAppliesProcessConfig(t *testing.T) {
	args := buildArgs(provider.Request{Workspace: "/tmp/work", CollaborationMCP: "https://control.example/mcp/collaboration",
		TaskToken: "secret", CustomArgs: []string{"--profile", "legacy-work", "--verbose"}}, configuration{Profile: "work"})
	got := strings.Join(args, " ")
	if !strings.HasPrefix(got, "--profile legacy-work app-server --listen stdio://") {
		t.Fatalf("profile must precede app-server: %q", got)
	}
	for _, expected := range []string{
		`mcp_servers.agentscope_collaboration.url="https://control.example/mcp/collaboration"`,
		`mcp_servers.agentscope_collaboration.bearer_token_env_var="AGENTSCOPE_TASK_TOKEN"`,
		`mcp_servers.agentscope_collaboration.default_tools_approval_mode="approve"`,
		"sandbox_workspace_write.network_access=true", "--verbose",
	} {
		if !strings.Contains(got, expected) {
			t.Fatalf("args=%q missing %q", got, expected)
		}
	}
	if strings.Contains(got, "secret") || strings.Contains(got, "danger-full-access") {
		t.Fatalf("task MCP configuration is unsafe: %q", got)
	}
}

func TestBuildArgsDoesNotRelaxExplicitReadOnlySandbox(t *testing.T) {
	args := buildArgs(provider.Request{Workspace: "/tmp/work", CollaborationMCP: "http://127.0.0.1:18080/mcp/collaboration",
		TaskToken: "secret"}, configuration{Sandbox: "read-only"})
	if got := strings.Join(args, " "); strings.Contains(got, "sandbox_workspace_write.network_access=true") {
		t.Fatalf("explicit read-only sandbox was relaxed: %q", got)
	}
}

func TestRunAppServerSessionBridgesCommandApprovalAndCapturesFinalMessage(t *testing.T) {
	responses := strings.Join([]string{
		`{"jsonrpc":"2.0","id":1,"result":{"serverInfo":{"name":"codex","version":"test"}}}`,
		`{"jsonrpc":"2.0","id":2,"result":{"thread":{"id":"thread-1"}}}`,
		`{"jsonrpc":"2.0","id":9001,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","command":"date","cwd":"/tmp/work","reason":"run command","startedAtMs":1}}`,
		`{"jsonrpc":"2.0","id":3,"result":{"turn":{"id":"turn-1","status":"inProgress","items":[]}}}`,
		`{"jsonrpc":"2.0","method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","item":{"type":"agentMessage","id":"message-1","text":"done"}}}`,
		`{"jsonrpc":"2.0","method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed","items":[]}}}`,
	}, "\n")
	var requests bytes.Buffer
	var events []string
	client := newAppServerClient(context.Background(), &requests, strings.NewReader(responses), func(event provider.Event) error {
		events = append(events, event.Type)
		return nil
	}, func(_ context.Context, request provider.ToolApprovalRequest) (provider.ToolApprovalDecision, error) {
		if request.ToolUseID != "item-1" || request.ToolName != "shell" || request.InputSHA256 == "" {
			t.Fatalf("approval request=%+v", request)
		}
		return provider.ToolApprovalDecision{Allow: true}, nil
	})
	result, err := runAppServerSession(client, provider.Request{Workspace: "/tmp/work", Prompt: "do it",
		Definition: &provider.AgentDefinition{System: "Be precise.", Model: "gpt-test"}},
		configuration{ReasoningEffort: "high", ServiceTier: "priority"})
	if err != nil {
		t.Fatal(err)
	}
	if result.ProviderSessionID != "thread-1" || result.Output != "done" {
		t.Fatalf("result=%+v", result)
	}
	got := requests.String()
	for _, expected := range []string{`"method":"initialize"`, `"method":"initialized"`,
		`"method":"thread/start"`, `"approvalPolicy":"on-request"`, `"approvalsReviewer":"user"`, `"developerInstructions":"Be precise."`,
		`"model":"gpt-test"`, `"method":"turn/start"`, `"effort":"high"`, `"decision":"accept"`} {
		if !strings.Contains(got, expected) {
			t.Fatalf("requests=%s missing %s", got, expected)
		}
	}
	if len(events) != 3 || events[0] != "item/commandExecution/requestApproval" {
		t.Fatalf("events=%v", events)
	}
}

func TestRunAppServerSessionResumesThreadAndDeclinesWithoutApprover(t *testing.T) {
	responses := strings.Join([]string{
		`{"jsonrpc":"2.0","id":1,"result":{}}`,
		`{"jsonrpc":"2.0","id":2,"result":{"thread":{"id":"thread-1"}}}`,
		`{"jsonrpc":"2.0","id":77,"method":"item/fileChange/requestApproval","params":{"threadId":"thread-1","turnId":"turn-2","itemId":"patch-1","reason":"write","startedAtMs":1}}`,
		`{"jsonrpc":"2.0","id":3,"result":{"turn":{"id":"turn-2","status":"inProgress","items":[]}}}`,
		`{"jsonrpc":"2.0","method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-2","status":"completed","items":[{"type":"agentMessage","id":"m","text":"continued"}]}}}`,
	}, "\n")
	var requests bytes.Buffer
	client := newAppServerClient(context.Background(), &requests, strings.NewReader(responses), nil, nil)
	result, err := runAppServerSession(client, provider.Request{Workspace: "/tmp/work", Prompt: "continue",
		ProviderSessionID: "thread-1"}, configuration{})
	if err != nil {
		t.Fatal(err)
	}
	got := requests.String()
	if !strings.Contains(got, `"method":"thread/resume"`) || !strings.Contains(got, `"excludeTurns":true`) ||
		!strings.Contains(got, `"decision":"decline"`) ||
		result.Output != "continued" {
		t.Fatalf("requests=%s result=%+v", got, result)
	}
}

func TestAppServerPermissionsApprovalReturnsRequestedSubset(t *testing.T) {
	var responses bytes.Buffer
	client := newAppServerClient(context.Background(), &responses, strings.NewReader(""), nil,
		func(context.Context, provider.ToolApprovalRequest) (provider.ToolApprovalDecision, error) {
			return provider.ToolApprovalDecision{Allow: true}, nil
		})
	message, err := decodeRPCMessage([]byte(`{"jsonrpc":"2.0","id":"approval-1","method":"item/permissions/requestApproval","params":{"itemId":"permission-1","permissions":{"network":{"enabled":true}}}}`))
	if err != nil {
		t.Fatal(err)
	}
	if err = client.handleServerRequest(message); err != nil {
		t.Fatal(err)
	}
	got := responses.String()
	if !strings.Contains(got, `"id":"approval-1"`) || !strings.Contains(got, `"network":{"enabled":true}`) ||
		!strings.Contains(got, `"scope":"turn"`) {
		t.Fatalf("permission response=%s", got)
	}
}

func TestAppServerUnknownRequestGetsProtocolErrorInsteadOfHanging(t *testing.T) {
	var responses bytes.Buffer
	client := newAppServerClient(context.Background(), &responses, strings.NewReader(""), nil, nil)
	message, _ := decodeRPCMessage([]byte(`{"jsonrpc":"2.0","id":42,"method":"item/tool/requestUserInput","params":{}}`))
	if err := client.handleServerRequest(message); err != nil {
		t.Fatal(err)
	}
	if got := responses.String(); !strings.Contains(got, `"code":-32601`) || !strings.Contains(got, `"id":42`) {
		t.Fatalf("response=%s", got)
	}
}

func TestRunAppServerSessionReturnsFailedTurn(t *testing.T) {
	responses := strings.Join([]string{
		`{"jsonrpc":"2.0","id":1,"result":{}}`,
		`{"jsonrpc":"2.0","id":2,"result":{"thread":{"id":"thread-1"}}}`,
		`{"jsonrpc":"2.0","id":3,"result":{"turn":{"id":"turn-1","status":"inProgress","items":[]}}}`,
		`{"jsonrpc":"2.0","method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"failed","error":{"message":"model unavailable"},"items":[]}}}`,
	}, "\n")
	client := newAppServerClient(context.Background(), &bytes.Buffer{}, strings.NewReader(responses), nil, nil)
	_, err := runAppServerSession(client, provider.Request{Workspace: "/tmp/work", Prompt: "task"}, configuration{})
	if err == nil || !strings.Contains(err.Error(), "model unavailable") {
		t.Fatalf("error=%v", err)
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

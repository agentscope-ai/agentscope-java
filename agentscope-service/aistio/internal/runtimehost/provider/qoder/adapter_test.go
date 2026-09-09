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

package qoder

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

func TestBuildArgsDoesNotBypassPermissionsImplicitly(t *testing.T) {
	args := buildArgs(provider.Request{Workspace: "/tmp/work", ProviderSessionID: "session-1"}, configuration{
		Model: "qoder-auto", PermissionMode: "accept_edits", AllowedTools: []string{"Read", "Write"},
	}, "", "")
	got := strings.Join(args, " ")
	want := "-p --input-format stream-json --output-format stream-json --cwd /tmp/work --resume session-1 --model qoder-auto --permission-mode accept_edits --allowed-tools Read --allowed-tools Write"
	if got != want {
		t.Fatalf("args=%q, want %q", got, want)
	}
	if strings.Contains(got, "dangerously-skip-permissions") || strings.Contains(got, "--yolo") {
		t.Fatalf("unsafe permission bypass was injected: %q", got)
	}
}

func TestBuildArgsUsesExplicitFullAccessPermissionMode(t *testing.T) {
	args := buildArgs(provider.Request{Workspace: "/tmp/work"}, configuration{PermissionMode: "bypass_permissions"}, "", "")
	if !strings.Contains(strings.Join(args, " "), "--permission-mode bypass_permissions") {
		t.Fatalf("explicit permission mode not passed to Qoder: %v", args)
	}
}

func TestConsumeStreamJSONBridgesToolApproval(t *testing.T) {
	input := strings.Join([]string{
		`{"type":"system","subtype":"init","session_id":"session-1"}`,
		`{"type":"control_request","request_id":"request-1","request":{"subtype":"can_use_tool","tool_name":"Bash","tool_use_id":"tool-1","input":{"command":"date"}}}`,
		`{"type":"result","subtype":"success","is_error":false,"result":"done","session_id":"session-1"}`,
	}, "\n")
	var responses bytes.Buffer
	result := &provider.Result{}
	err := consumeStreamJSON(context.Background(), strings.NewReader(input), &responses, result, nil,
		func(_ context.Context, request provider.ToolApprovalRequest) (provider.ToolApprovalDecision, error) {
			if request.ToolUseID != "tool-1" || request.ToolName != "Bash" || request.InputSHA256 == "" {
				t.Fatalf("approval request=%+v", request)
			}
			return provider.ToolApprovalDecision{Allow: true}, nil
		})
	if err != nil {
		t.Fatal(err)
	}
	if got := responses.String(); !strings.Contains(got, `"type":"control_response"`) ||
		!strings.Contains(got, `"request_id":"request-1"`) || !strings.Contains(got, `"behavior":"allow"`) ||
		!strings.Contains(got, `"updatedInput":{"command":"date"}`) {
		t.Fatalf("approval response=%s", got)
	}
	if result.Output != "done" || result.ProviderSessionID != "session-1" {
		t.Fatalf("result=%+v", result)
	}
}

func TestConsumeStreamJSONDeniesToolApprovalWithoutHostApprover(t *testing.T) {
	input := `{"type":"control_request","request_id":"request-2","request":{"subtype":"can_use_tool","tool_name":"Write","tool_use_id":"tool-2","input":{"path":"/tmp/a"}}}`
	var responses bytes.Buffer
	if err := consumeStreamJSON(context.Background(), strings.NewReader(input), &responses, &provider.Result{}, nil, nil); err != nil {
		t.Fatal(err)
	}
	if got := responses.String(); !strings.Contains(got, `"behavior":"deny"`) ||
		!strings.Contains(got, `"toolUseID":"tool-2"`) {
		t.Fatalf("approval response=%s", got)
	}
}

func TestConsumeStreamJSONReturnsAtResultWithoutWaitingForInputEOF(t *testing.T) {
	reader, writer := io.Pipe()
	done := make(chan error, 1)
	go func() {
		done <- consumeStreamJSON(context.Background(), reader, io.Discard, &provider.Result{}, nil, nil)
	}()
	if _, err := writer.Write([]byte(`{"type":"result","subtype":"success","is_error":false,"result":"done","session_id":"session-1"}` + "\n")); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("Qoder result waited for stdin/stdout EOF")
	}
	_ = writer.Close()
	_ = reader.Close()
}

func TestConsumeJSONLReportsNonInteractivePermissionDenial(t *testing.T) {
	input := strings.Join([]string{
		`{"type":"system","subtype":"init","session_id":"session-1"}`,
		`{"type":"user","session_id":"session-1","message":{"content":[{"type":"tool_result","content":"Error: Tool use was automatically rejected because the environment is non-interactive.","is_error":true}]}}`,
		`{"type":"result","subtype":"success","is_error":false,"result":"无法读取协作任务，因为权限被拒绝。","session_id":"session-1"}`,
	}, "\n")
	err := consumeJSONL(strings.NewReader(input), &provider.Result{}, nil)
	var executionError *provider.ExecutionError
	if !errors.As(err, &executionError) {
		t.Fatalf("error=%v, want provider.ExecutionError", err)
	}
	if executionError.Code != "provider_permission_denied" {
		t.Fatalf("code=%q", executionError.Code)
	}
}

func TestBuildArgsPreauthorizesCollaborationWithoutHidingDevelopmentTools(t *testing.T) {
	args := buildArgs(provider.Request{Workspace: "/tmp/work"}, configuration{
		ContextWindow: 120000, MaxTurns: 24, MaxOutputTokens: 8000, StrictMCPConfig: true,
		AllowedTools: []string{"mcp__agentscope-collaboration__*"},
	}, "/tmp/mcp.json", "")
	got := strings.Join(args, " ")
	for _, want := range []string{
		"--mcp-config /tmp/mcp.json --strict-mcp-config",
		"--context-window 120000",
		"--allowed-tools mcp__agentscope-collaboration__*",
		"--max-turns 24",
		"--max-output-tokens 8000",
	} {
		if !strings.Contains(got, want) {
			t.Fatalf("args=%q does not contain %q", got, want)
		}
	}
	if strings.Contains(got, "--tools") || strings.Contains(got, "--allowed-mcp-server-names") {
		t.Fatalf("permission grant unexpectedly narrowed tool exposure: %q", got)
	}
}

func TestBuildArgsDisablesUnlistedAmbientToolsAndMCPServers(t *testing.T) {
	enabled := true
	tools, _ := json.Marshal([]map[string]any{{"configs": []map[string]any{{"name": "read", "enabled": enabled}}}})
	args := buildArgs(provider.Request{Workspace: "/tmp/work", Definition: &provider.AgentDefinition{Tools: tools}},
		configuration{}, "/tmp/mcp.json", "")
	want := []string{"--tools", "Read", "--allowed-mcp-server-names", "__agentscope_none__"}
	for i := 0; i <= len(args)-len(want); i++ {
		if strings.Join(args[i:i+len(want)], "\x00") == strings.Join(want, "\x00") {
			return
		}
	}
	t.Fatalf("Qoder exposure allowlist missing from args: %#v", args)
}

func TestLastNonEmptyLineIgnoresDiagnosticPreamble(t *testing.T) {
	if got := lastNonEmptyLine("ambient warning\nqodercli 1.2.3\n"); got != "qodercli 1.2.3" {
		t.Fatalf("version=%q", got)
	}
}

func TestPrepareIsolatedConfigKeepsAuthenticationAndStableSessionRoot(t *testing.T) {
	home, state, workspace := t.TempDir(), t.TempDir(), t.TempDir()
	t.Setenv("HOME", home)
	auth := filepath.Join(home, ".qoder", ".auth")
	if err := os.MkdirAll(auth, 0o700); err != nil {
		t.Fatal(err)
	}
	request := provider.Request{Workspace: workspace, RuntimeStateRoot: state}
	first, err := prepareIsolatedConfig(request)
	if err != nil {
		t.Fatal(err)
	}
	second, err := prepareIsolatedConfig(request)
	if err != nil {
		t.Fatal(err)
	}
	linked, err := os.Readlink(filepath.Join(first, ".auth"))
	if err != nil || first != second || linked != auth {
		t.Fatalf("isolated config is not stable/authenticated: first=%q second=%q link=%q err=%v",
			first, second, linked, err)
	}
}

func TestBuildArgsIsolatesHostedMCPFromAmbientQoderSettings(t *testing.T) {
	args := buildArgs(provider.Request{Workspace: "/tmp/work"}, configuration{}, "/tmp/mcp.json", "/state/qoder")
	got := strings.Join(args, " ")
	if !strings.Contains(got, "--mcp-config /tmp/mcp.json --strict-mcp-config") {
		t.Fatalf("hosted MCP configuration was not isolated: %q", got)
	}
	if !strings.Contains(got, "--config-dir /state/qoder --setting-sources project") {
		t.Fatalf("ambient Qoder plugins and settings were not isolated: %q", got)
	}
}

func TestConsumeJSONL(t *testing.T) {
	input := strings.Join([]string{
		`{"type":"system","subtype":"init","session_id":"session-1"}`,
		`{"type":"assistant","session_id":"session-1","message":{"content":[{"type":"text","text":"working"}]}}`,
		`{"type":"result","subtype":"success","is_error":false,"result":"done","session_id":"session-1"}`,
	}, "\n")
	result := &provider.Result{}
	var events []string
	if err := consumeJSONL(strings.NewReader(input), result, func(event provider.Event) error {
		events = append(events, event.Type)
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if result.ProviderSessionID != "session-1" || result.Output != "done" || len(events) != 3 {
		t.Fatalf("result=%+v events=%v", result, events)
	}
}

func TestQoderExitErrorOmitsAmbientAuthTypeWarning(t *testing.T) {
	err := qoderExitError(errors.New("exit status 42"), strings.Join([]string{
		`Skipped invalid MCP server "ali-skill-market": (root): Unrecognized key(s) in object: 'authType'`,
		`Error resuming session: Invalid session identifier "session-1".`,
	}, "\n"))
	if strings.Contains(err.Error(), "authType") ||
		!strings.Contains(err.Error(), `Invalid session identifier "session-1"`) {
		t.Fatalf("unexpected Qoder failure: %v", err)
	}
}

func TestRunStopsChildWhenApprovalTransportFails(t *testing.T) {
	dir := t.TempDir()
	binary := filepath.Join(dir, "qoder-test")
	script := `#!/bin/sh
printf '%s\n' '{"type":"control_request","request_id":"request","request":{"subtype":"can_use_tool","tool_name":"Read","tool_use_id":"tool","input":{"path":"README.md"}}}'
exec sleep 30
`
	if err := os.WriteFile(binary, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	began := time.Now()
	_, err := (&Adapter{Binary: binary}).Run(ctx, provider.Request{Workspace: dir, ApproveTool: func(context.Context, provider.ToolApprovalRequest) (provider.ToolApprovalDecision, error) {
		return provider.ToolApprovalDecision{}, errors.New("approval transport failed")
	}}, nil)
	if err == nil || !strings.Contains(err.Error(), "approval transport failed") {
		t.Fatalf("unexpected error: %v", err)
	}
	if time.Since(began) > time.Second {
		t.Fatal("approval error waited for provider exit instead of stopping the child")
	}
}

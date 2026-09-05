// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package qoder

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

func TestBuildArgsDoesNotBypassPermissionsImplicitly(t *testing.T) {
	args := buildArgs(provider.Request{Workspace: "/tmp/work", ProviderSessionID: "session-1"}, configuration{
		Model: "qoder-auto", PermissionMode: "accept_edits", AllowedTools: []string{"Read", "Write"},
	}, "", "")
	got := strings.Join(args, " ")
	want := "-p --output-format stream-json --cwd /tmp/work --resume session-1 --model qoder-auto --permission-mode accept_edits --tools Read Write --allowed-tools Read --allowed-tools Write"
	if got != want {
		t.Fatalf("args=%q, want %q", got, want)
	}
	if strings.Contains(got, "dangerously-skip-permissions") || strings.Contains(got, "--yolo") {
		t.Fatalf("unsafe permission bypass was injected: %q", got)
	}
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

func TestBuildArgsAppliesHeadlessAutomationControls(t *testing.T) {
	args := buildArgs(provider.Request{Workspace: "/tmp/work"}, configuration{
		ContextWindow: 120000, MaxTurns: 24, MaxOutputTokens: 8000, StrictMCPConfig: true,
		AllowedTools: []string{"mcp__agentscope-collaboration__*"},
	}, "/tmp/mcp.json", "")
	got := strings.Join(args, " ")
	for _, want := range []string{
		"--mcp-config /tmp/mcp.json --strict-mcp-config",
		"--context-window 120000",
		"--tools  --allowed-mcp-server-names agentscope-collaboration",
		"--allowed-tools mcp__agentscope-collaboration__*",
		"--max-turns 24",
		"--max-output-tokens 8000",
	} {
		if !strings.Contains(got, want) {
			t.Fatalf("args=%q does not contain %q", got, want)
		}
	}
}

func TestBuildArgsDisablesUnlistedAmbientToolsAndMCPServers(t *testing.T) {
	args := buildArgs(provider.Request{Workspace: "/tmp/work"}, configuration{
		AllowedTools: []string{"Read"},
	}, "/tmp/mcp.json", "")
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

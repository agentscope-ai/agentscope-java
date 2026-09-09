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
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

// Adapter uses Qoder's bidirectional stream-json host contract. Tool approval
// requests stay on stdin/stdout and are bridged to the AgentScope control
// plane instead of being rejected merely because Qoder has no terminal UI.
type Adapter struct {
	Binary string
}

type configuration struct {
	Model              string   `json:"model,omitempty"`
	ReasoningEffort    string   `json:"reasoningEffort,omitempty"`
	ContextWindow      int      `json:"contextWindow,omitempty"`
	PermissionMode     string   `json:"permissionMode,omitempty"`
	AllowedTools       []string `json:"allowedTools,omitempty"`
	DisallowedTools    []string `json:"disallowedTools,omitempty"`
	MaxTurns           int      `json:"maxTurns,omitempty"`
	MaxOutputTokens    int      `json:"maxOutputTokens,omitempty"`
	StrictMCPConfig    bool     `json:"strictMCPConfig,omitempty"`
	AppendSystemPrompt string   `json:"appendSystemPrompt,omitempty"`
	Agent              string   `json:"agent,omitempty"`
}

func (a *Adapter) Name() string { return "qoder" }

func (a *Adapter) Descriptor() provider.Descriptor {
	return provider.Descriptor{
		DisplayName:  "Qoder",
		Runtime:      "qodercli",
		Instructions: provider.Capability{Supported: true, Mode: "prompt"},
		Workspace:    provider.Capability{Supported: true, Mode: "cwd"},
		Skills:       provider.Capability{Supported: true, Mode: "context-directory", Target: ".agentscope/definition/skills"},
		Subagents:    provider.Capability{Supported: true, Mode: "cli-argument", Target: "--agents (shared workspace; Qoder 1.0.37+)"},
		Tools:        provider.Capability{Supported: true, Mode: "allowlist"},
		Shell:        provider.Capability{Supported: true, Mode: "native", Target: "Bash"},
		MCP:          provider.Capability{Supported: true, Mode: "cli-config", Target: "--mcp-config"},
		Model:        provider.Capability{Supported: true, Mode: "cli-argument", Target: "--model"},
		CustomArgs:   provider.Capability{Supported: true, Mode: "argv", Target: "qodercli"},
		Approval:     provider.Capability{Supported: true, Mode: "control-plane", Target: "control_request/can_use_tool"},
		Resume:       true,
	}
}

func (a *Adapter) binary() string {
	if a.Binary != "" {
		return a.Binary
	}
	return "qodercli"
}

func (a *Adapter) Detect(ctx context.Context) (string, error) {
	cmd := exec.CommandContext(ctx, a.binary(), "--version")
	out, err := cmd.Output()
	if err != nil {
		detail := ""
		if exitErr, ok := err.(*exec.ExitError); ok {
			detail = strings.TrimSpace(string(exitErr.Stderr))
		}
		return "", fmt.Errorf("qodercli --version: %w: %s", err, detail)
	}
	return lastNonEmptyLine(string(out)), nil
}

func (a *Adapter) Run(ctx context.Context, request provider.Request, sink provider.EventSink) (*provider.Result, error) {
	if request.Workspace == "" {
		return nil, fmt.Errorf("Qoder workspace is required")
	}
	customArgs, err := provider.ValidateCustomArgs(request.CustomArgs)
	if err != nil {
		return nil, err
	}
	request.CustomArgs = customArgs
	subagents, err := provider.QoderSubagentsJSON(request.Definition)
	if err != nil {
		return nil, err
	}
	if subagents != "" {
		version, err := a.Detect(ctx)
		if err != nil {
			return nil, err
		}
		if err := provider.RequireSubagentVersion(version, 1, 0, 37); err != nil {
			return nil, err
		}
		for _, argument := range customArgs {
			if argument == "--agents" || strings.HasPrefix(argument, "--agents=") {
				return nil, fmt.Errorf("custom --agents conflicts with Workspace subagents")
			}
		}
	}
	var cfg configuration
	if len(request.Configuration) > 0 {
		if err := json.Unmarshal(request.Configuration, &cfg); err != nil {
			return nil, fmt.Errorf("decode Qoder configuration: %w", err)
		}
	}
	if subagents != "" {
		subagents, err = constrainSubagentTools(subagents, request, cfg)
		if err != nil {
			return nil, err
		}
	}
	mcpConfig, cleanup, err := provider.WriteMCPConfig(request)
	if err != nil {
		return nil, err
	}
	defer cleanup()
	configDir, err := prepareIsolatedConfig(request)
	if err != nil {
		return nil, err
	}
	args := buildArgs(request, cfg, mcpConfig, configDir)
	if subagents != "" {
		args = append(args, "--agents", subagents)
	}
	cmd := exec.CommandContext(ctx, a.binary(), args...)
	cmd.Dir = request.Workspace
	provider.ApplyTaskEnvironment(cmd, request)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err = cmd.Start(); err != nil {
		return nil, fmt.Errorf("start Qoder: %w", err)
	}
	prompt := provider.PrependInstructions(request.Prompt, provider.DefinitionInstructions(request))
	if err = writeStreamMessage(stdin, map[string]any{
		"type": "user",
		"message": map[string]any{
			"role":    "user",
			"content": []map[string]string{{"type": "text", "text": prompt}},
		},
	}); err != nil {
		_ = stdin.Close()
		_ = cmd.Wait()
		return nil, fmt.Errorf("send Qoder prompt: %w", err)
	}
	result := &provider.Result{ProviderSessionID: request.ProviderSessionID}
	readErr := consumeStreamJSON(ctx, stdout, stdin, result, sink, request.ApproveTool)
	_ = stdin.Close()
	if readErr != nil {
		// Once the protocol reader stops, the child can block on stdout or wait
		// for another approval forever. End it before joining the process.
		_ = cmd.Process.Kill()
	}
	waitErr := cmd.Wait()
	if readErr != nil {
		return nil, readErr
	}
	if waitErr != nil {
		return nil, qoderExitError(waitErr, stderr.String())
	}
	result.Checkpoint, _ = json.Marshal(map[string]string{"providerSessionId": result.ProviderSessionID})
	return result, nil
}

func qoderExitError(waitErr error, stderr string) error {
	lines := strings.Split(stderr, "\n")
	kept := lines[:0]
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "Skipped invalid MCP server ") &&
			strings.Contains(trimmed, "Unrecognized key(s) in object: 'authType'") {
			continue
		}
		if trimmed != "" {
			kept = append(kept, line)
		}
	}
	detail := strings.TrimSpace(strings.Join(kept, "\n"))
	if detail == "" {
		return fmt.Errorf("Qoder exited: %w", waitErr)
	}
	return fmt.Errorf("Qoder exited: %w: %s", waitErr, detail)
}

func buildArgs(request provider.Request, cfg configuration, mcpConfig, configDir string) []string {
	args := []string{"-p", "--input-format", "stream-json", "--output-format", "stream-json", "--cwd", request.Workspace}
	if configDir != "" {
		args = append(args, "--config-dir", configDir, "--setting-sources", "project")
	}
	definitionAllowed, definitionDenied := provider.DefinitionToolPolicy(request, qoderToolAliases)
	allowedTools := provider.MergeUnique(cfg.AllowedTools, definitionAllowed)
	disallowedTools := provider.MergeUnique(cfg.DisallowedTools, definitionDenied)
	// RuntimeProfile allowedTools are permission grants, not an exposure list.
	// Only an explicit portable Agent tool definition narrows --tools; otherwise
	// the automatic collaboration grant must not accidentally hide Bash/Edit.
	explicitAllowlist := len(definitionAllowed) > 0
	if mcpConfig != "" {
		// Hosted executions must be reproducible and must not inherit arbitrary
		// user/project MCP entries. Besides leaking ambient capabilities, a
		// malformed personal entry can prevent Qoder from resuming a valid
		// AgentScope session. The temporary file already contains the complete
		// Agent definition and scoped collaboration server.
		args = append(args, "--mcp-config", mcpConfig, "--strict-mcp-config")
	}
	if request.ProviderSessionID != "" {
		args = append(args, "--resume", request.ProviderSessionID)
	}
	if model := provider.DefinitionModel(request, cfg.Model); model != "" {
		args = append(args, "--model", model)
	}
	if cfg.ReasoningEffort != "" {
		args = append(args, "--reasoning-effort", cfg.ReasoningEffort)
	}
	if cfg.ContextWindow > 0 {
		args = append(args, "--context-window", strconv.Itoa(cfg.ContextWindow))
	}
	if cfg.PermissionMode != "" {
		args = append(args, "--permission-mode", cfg.PermissionMode)
	}
	if explicitAllowlist {
		builtins, mcpServers := qoderToolExposure(allowedTools)
		args = append(args, "--tools")
		if len(builtins) == 0 {
			args = append(args, "")
		} else {
			args = append(args, builtins...)
		}
		if mcpConfig != "" {
			args = append(args, "--allowed-mcp-server-names")
			if len(mcpServers) == 0 {
				args = append(args, "__agentscope_none__")
			} else {
				args = append(args, mcpServers...)
			}
		}
	}
	for _, tool := range allowedTools {
		args = append(args, "--allowed-tools", tool)
	}
	for _, tool := range disallowedTools {
		args = append(args, "--disallowed-tools", tool)
	}
	if cfg.MaxTurns > 0 {
		args = append(args, "--max-turns", strconv.Itoa(cfg.MaxTurns))
	}
	if cfg.MaxOutputTokens > 0 {
		args = append(args, "--max-output-tokens", strconv.Itoa(cfg.MaxOutputTokens))
	}
	if cfg.AppendSystemPrompt != "" {
		args = append(args, "--append-system-prompt", cfg.AppendSystemPrompt)
	}
	if cfg.Agent != "" {
		args = append(args, "--agent", cfg.Agent)
	}
	args = append(args, request.CustomArgs...)
	return args
}

func prepareIsolatedConfig(request provider.Request) (string, error) {
	root := strings.TrimSpace(request.RuntimeStateRoot)
	if root == "" {
		cacheRoot, err := os.UserCacheDir()
		if err != nil {
			return "", fmt.Errorf("resolve Qoder cache directory: %w", err)
		}
		root = filepath.Join(cacheRoot, "aistio-runtime-host")
	}
	key := sha256.Sum256([]byte(request.Workspace))
	dir := filepath.Join(root, "qoder-configs", fmt.Sprintf("%x", key[:16]))
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", fmt.Errorf("create isolated Qoder config: %w", err)
	}
	if err := os.Chmod(dir, 0o700); err != nil {
		return "", fmt.Errorf("secure isolated Qoder config: %w", err)
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("resolve Qoder authentication directory: %w", err)
	}
	sourceRoot := filepath.Join(home, ".qoder")
	for _, name := range []string{".auth", "installation_id"} {
		source := filepath.Join(sourceRoot, name)
		if _, statErr := os.Stat(source); statErr != nil {
			if os.IsNotExist(statErr) {
				continue
			}
			return "", fmt.Errorf("inspect Qoder %s: %w", name, statErr)
		}
		target := filepath.Join(dir, name)
		if existing, linkErr := os.Readlink(target); linkErr == nil {
			if existing == source {
				continue
			}
			return "", fmt.Errorf("isolated Qoder %s points to an unexpected location", name)
		} else if !os.IsNotExist(linkErr) {
			return "", fmt.Errorf("inspect isolated Qoder %s: %w", name, linkErr)
		}
		if err = os.Symlink(source, target); err != nil {
			return "", fmt.Errorf("link Qoder %s into isolated config: %w", name, err)
		}
	}
	return dir, nil
}

func qoderToolExposure(allowed []string) (builtins, mcpServers []string) {
	for _, tool := range allowed {
		if strings.HasPrefix(tool, "mcp__") {
			serverAndTool := strings.TrimPrefix(tool, "mcp__")
			if split := strings.Index(serverAndTool, "__"); split > 0 {
				mcpServers = provider.MergeUnique(mcpServers, []string{serverAndTool[:split]})
			}
			continue
		}
		builtins = provider.MergeUnique(builtins, []string{tool})
	}
	return builtins, mcpServers
}

func lastNonEmptyLine(output string) string {
	lines := strings.Split(output, "\n")
	for i := len(lines) - 1; i >= 0; i-- {
		if line := strings.TrimSpace(lines[i]); line != "" {
			return line
		}
	}
	return ""
}

func consumeJSONL(reader io.Reader, result *provider.Result, sink provider.EventSink) error {
	return consumeStreamJSON(context.Background(), reader, io.Discard, result, sink, nil)
}

func consumeStreamJSON(ctx context.Context, reader io.Reader, writer io.Writer, result *provider.Result,
	sink provider.EventSink, approver provider.ToolApprover) error {
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 64*1024), 8*1024*1024)
	permissionFailure := ""
	for scanner.Scan() {
		raw := append(json.RawMessage(nil), scanner.Bytes()...)
		var envelope struct {
			ParentToolUseID string `json:"parent_tool_use_id"`
			Type            string `json:"type"`
			Subtype         string `json:"subtype"`
			SessionID       string `json:"session_id"`
			IsError         bool   `json:"is_error"`
			Result          string `json:"result"`
			Message         struct {
				Content []struct {
					Type    string `json:"type"`
					Text    string `json:"text"`
					Content any    `json:"content"`
					IsError bool   `json:"is_error"`
				} `json:"content"`
			} `json:"message"`
			RequestID string `json:"request_id"`
			Request   struct {
				Subtype     string `json:"subtype"`
				ToolName    string `json:"tool_name"`
				ToolUseID   string `json:"tool_use_id"`
				DisplayName string `json:"display_name"`
				Input       any    `json:"input"`
			} `json:"request"`
		}
		if err := json.Unmarshal(raw, &envelope); err != nil {
			return fmt.Errorf("decode Qoder JSONL event: %w", err)
		}
		if sink != nil {
			if err := sink(provider.Event{Type: envelope.Type, ProviderSessionID: envelope.SessionID, Raw: raw}); err != nil {
				return err
			}
		}
		if envelope.Type == "control_request" && envelope.Request.Subtype == "can_use_tool" {
			if err := handleToolApproval(ctx, writer, envelope.RequestID, envelope.Request.ToolUseID,
				envelope.Request.ToolName, envelope.Request.DisplayName, envelope.Request.Input, approver); err != nil {
				return err
			}
		}
		if envelope.ParentToolUseID != "" || (result.ProviderSessionID != "" && envelope.SessionID != "" && envelope.SessionID != result.ProviderSessionID) {
			continue
		}
		if envelope.SessionID != "" {
			result.ProviderSessionID = envelope.SessionID
		}
		if envelope.Result != "" {
			result.Output = envelope.Result
		} else if envelope.Type == "assistant" {
			for _, content := range envelope.Message.Content {
				if content.Type == "text" {
					result.Output += content.Text
				}
			}
		}
		for _, content := range envelope.Message.Content {
			if !content.IsError {
				continue
			}
			message := content.Text
			if message == "" && content.Content != nil {
				message = fmt.Sprint(content.Content)
			}
			if isPermissionFailure(message) {
				permissionFailure = message
			}
		}
		if envelope.Type == "result" {
			if envelope.IsError {
				return fmt.Errorf("Qoder result %s: %s", envelope.Subtype, envelope.Result)
			}
			if permissionFailure != "" && (result.Output == "" || describesBlockedOutcome(result.Output)) {
				return provider.NewExecutionError("provider_permission_denied",
					"Qoder refused a required tool call after host permission handling: "+permissionFailure)
			}
			// In stream-json input mode Qoder may wait for another user frame.
			// A Runtime Host execution is one turn, so return now; Run closes stdin
			// before waiting for the child process.
			return nil
		}
	}
	if err := scanner.Err(); err != nil {
		return err
	}
	if permissionFailure != "" && (result.Output == "" || describesBlockedOutcome(result.Output)) {
		return provider.NewExecutionError("provider_permission_denied",
			"Qoder refused a required tool call after host permission handling: "+permissionFailure)
	}
	return nil
}

func handleToolApproval(ctx context.Context, writer io.Writer, requestID, toolUseID, toolName, displayName string,
	input any, approver provider.ToolApprover) error {
	if toolUseID == "" {
		toolUseID = requestID
	}
	if toolName == "" {
		toolName = displayName
	}
	if toolName == "" {
		toolName = "provider_tool"
	}
	decision := provider.ToolApprovalDecision{DenyMessage: "Tool use was not approved by the AgentScope host."}
	if approver != nil {
		inputJSON, _ := json.Marshal(input)
		hash := sha256.Sum256(inputJSON)
		var err error
		decision, err = approver(ctx, provider.ToolApprovalRequest{
			ToolUseID: toolUseID, ToolName: toolName, Input: input, InputSHA256: fmt.Sprintf("%x", hash[:]),
		})
		if err != nil {
			_ = writeQoderApproval(writer, requestID, toolUseID, false, "AgentScope approval failed: "+err.Error(), input)
			return fmt.Errorf("request Qoder tool approval: %w", err)
		}
	}
	return writeQoderApproval(writer, requestID, toolUseID, decision.Allow, decision.DenyMessage, input)
}

func writeQoderApproval(writer io.Writer, requestID, toolUseID string, allow bool, denyMessage string, input any) error {
	response := map[string]any{"behavior": "deny", "message": denyMessage, "toolUseID": toolUseID}
	if allow {
		response = map[string]any{"behavior": "allow", "updatedInput": input, "toolUseID": toolUseID}
	}
	return writeStreamMessage(writer, map[string]any{
		"type":     "control_response",
		"response": map[string]any{"subtype": "success", "request_id": requestID, "response": response},
	})
}

func writeStreamMessage(writer io.Writer, message any) error {
	data, err := json.Marshal(message)
	if err != nil {
		return err
	}
	data = append(data, '\n')
	_, err = writer.Write(data)
	return err
}

func isPermissionFailure(message string) bool {
	message = strings.ToLower(message)
	return (strings.Contains(message, "permission") || strings.Contains(message, "tool use")) &&
		(strings.Contains(message, "denied") || strings.Contains(message, "rejected") ||
			strings.Contains(message, "not allowed") || strings.Contains(message, "non-interactive"))
}

func describesBlockedOutcome(output string) bool {
	output = strings.ToLower(output)
	markers := []string{
		"permission", "denied", "rejected", "not allowed", "cannot", "can't", "unable",
		"权限", "拒绝", "无法", "不能", "不允许",
	}
	for _, marker := range markers {
		if strings.Contains(output, marker) {
			return true
		}
	}
	return false
}

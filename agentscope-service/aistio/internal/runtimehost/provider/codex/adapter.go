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
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

type Adapter struct {
	Binary string
}

type configuration struct {
	Model   string `json:"model,omitempty"`
	Profile string `json:"profile,omitempty"`
	Sandbox string `json:"sandbox,omitempty"`
	// Retained for decoding older RuntimeProfiles. app-server accepts
	// AgentScope-created non-Git workspaces without an exec-only CLI flag.
	SkipGitRepoCheck *bool  `json:"skipGitRepoCheck,omitempty"`
	ReasoningEffort  string `json:"reasoningEffort,omitempty"`
	ServiceTier      string `json:"serviceTier,omitempty"`
}

func (a *Adapter) Name() string { return "codex" }

func (a *Adapter) Descriptor() provider.Descriptor {
	return provider.Descriptor{
		DisplayName:  "Codex",
		Runtime:      "codex",
		Instructions: provider.Capability{Supported: true, Mode: "app-server", Target: "developerInstructions"},
		Workspace:    provider.Capability{Supported: true, Mode: "cwd"},
		Skills:       provider.Capability{Supported: true, Mode: "native-directory", Target: ".agents/skills"},
		Subagents:    provider.Capability{Supported: true, Mode: "cli-config", Target: "agents.<name>.config_file (shared workspace; Codex 0.153.4+)"},
		Tools:        provider.Capability{Supported: false, Mode: "unsupported", Target: "Use Runtime Profile sandbox and approval settings for native tools"},
		Shell:        provider.Capability{Supported: true, Mode: "native", Target: "shell"},
		MCP:          provider.Capability{Supported: true, Mode: "cli-config"},
		Model:        provider.Capability{Supported: true, Mode: "app-server", Target: "thread/start"},
		CustomArgs:   provider.Capability{Supported: true, Mode: "argv", Target: "codex app-server"},
		Approval:     provider.Capability{Supported: true, Mode: "control-plane", Target: "item/*/requestApproval"},
		Resume:       true,
	}
}

func (a *Adapter) binary() string {
	if a.Binary != "" {
		return a.Binary
	}
	return "codex"
}

func (a *Adapter) Detect(ctx context.Context) (string, error) {
	cmd := exec.CommandContext(ctx, a.binary(), "--version")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("codex --version: %w: %s", err, strings.TrimSpace(string(out)))
	}
	return strings.TrimSpace(string(out)), nil
}

func (a *Adapter) Run(ctx context.Context, request provider.Request, sink provider.EventSink) (*provider.Result, error) {
	if request.Workspace == "" {
		return nil, fmt.Errorf("codex workspace is required")
	}
	if err := provider.ValidateDefinition(request.Definition, a.Descriptor()); err != nil {
		return nil, err
	}
	customArgs, err := provider.ValidateCustomArgs(request.CustomArgs)
	if err != nil {
		return nil, err
	}
	request.CustomArgs = customArgs
	subagents, err := provider.NativeSubagents(request.Definition, a.Descriptor().Runtime)
	if err != nil {
		return nil, err
	}
	if len(subagents) > 0 {
		version, err := a.Detect(ctx)
		if err != nil {
			return nil, err
		}
		if err := provider.RequireSubagentVersion(version, 0, 153, 4); err != nil {
			return nil, err
		}
	}
	cleanupSubagents, err := projectSubagents(request.Workspace, subagents)
	if err != nil {
		return nil, err
	}
	defer cleanupSubagents()
	cleanupSkills, err := provider.ProjectSkills(request.Workspace, ".agents/skills", a.Name())
	if err != nil {
		return nil, fmt.Errorf("project Codex skills: %w", err)
	}
	defer cleanupSkills()
	var cfg configuration
	if len(request.Configuration) > 0 {
		if err = json.Unmarshal(request.Configuration, &cfg); err != nil {
			return nil, fmt.Errorf("decode codex configuration: %w", err)
		}
	}
	cmd := exec.CommandContext(ctx, a.binary(), buildArgs(request, cfg)...)
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
		return nil, fmt.Errorf("start Codex app-server: %w", err)
	}
	client := newAppServerClient(ctx, stdin, stdout, sink, request.ApproveTool)
	result, runErr := runAppServerSession(client, request, cfg)
	_ = stdin.Close()
	waitErr := cmd.Wait()
	if runErr != nil {
		return nil, fmt.Errorf("Codex app-server: %w: %s", runErr, strings.TrimSpace(stderr.String()))
	}
	if waitErr != nil {
		return nil, codexExitError(waitErr, stderr.String())
	}
	result.Checkpoint, _ = json.Marshal(map[string]string{"providerSessionId": result.ProviderSessionID})
	return result, nil
}

func codexExitError(waitErr error, stderr string) error {
	lines := strings.Split(stderr, "\n")
	kept := lines[:0]
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.Contains(trimmed, "WARN codex_skills::interface:") &&
			strings.Contains(trimmed, "icon path with '..' must resolve under plugin assets/") {
			continue
		}
		if trimmed != "" {
			kept = append(kept, line)
		}
	}
	detail := strings.TrimSpace(strings.Join(kept, "\n"))
	if detail == "" {
		return fmt.Errorf("Codex exited: %w", waitErr)
	}
	return fmt.Errorf("Codex exited: %w: %s", waitErr, detail)
}

// buildArgs contains only process-level app-server configuration. Per-thread
// model, cwd, sandbox and approval settings are sent through JSON-RPC.
func buildArgs(request provider.Request, cfg configuration) []string {
	profile, customArgs := splitCodexCustomArgs(request.CustomArgs)
	if profile == "" {
		profile = cfg.Profile
	}
	args := make([]string, 0, 8+len(customArgs))
	if profile != "" {
		// --profile is a root Codex option and must precede the subcommand.
		args = append(args, "--profile", profile)
	}
	args = append(args, "app-server", "--listen", "stdio://")
	if request.CollaborationMCP != "" && request.TaskToken != "" {
		args = append(args, "--config", fmt.Sprintf("mcp_servers.agentscope_collaboration.url=%q", request.CollaborationMCP),
			"--config", fmt.Sprintf("mcp_servers.agentscope_collaboration.bearer_token_env_var=%q", provider.TaskTokenEnvironment),
			"--config", `mcp_servers.agentscope_collaboration.default_tools_approval_mode="approve"`)
		if sandboxMode(cfg) == "workspace-write" {
			args = append(args, "--config", "sandbox_workspace_write.network_access=true")
		}
	}
	args = append(args, customArgs...)
	// Explicit registration works in non-Git Hosted workspaces too. Generated
	// files stay outside .codex/agents to avoid duplicate implicit discovery.
	if subagents, err := provider.NativeSubagents(request.Definition, "codex"); err == nil {
		for _, subagent := range subagents {
			key := "agents." + subagent.Name
			args = append(args, "--config", fmt.Sprintf("%s.description=%q", key, subagent.Description),
				"--config", fmt.Sprintf("%s.config_file=%q", key, filepath.Join(request.Workspace, ".agentscope", "native", "codex", "agents", subagent.Name+".toml")))
		}
	}
	return args
}

// Older RuntimeProfiles may store --profile in custom arguments. Extract it so
// it can be moved before the app-server subcommand; the last custom value wins
// over the structured profile just as it did in the previous exec argv.
func splitCodexCustomArgs(custom []string) (string, []string) {
	profile := ""
	remaining := make([]string, 0, len(custom))
	for index := 0; index < len(custom); index++ {
		argument := custom[index]
		if argument == "--profile" && index+1 < len(custom) {
			index++
			profile = custom[index]
			continue
		}
		if strings.HasPrefix(argument, "--profile=") {
			profile = strings.TrimPrefix(argument, "--profile=")
			continue
		}
		remaining = append(remaining, argument)
	}
	return profile, remaining
}

func sandboxMode(cfg configuration) string {
	if cfg.Sandbox != "" {
		return cfg.Sandbox
	}
	return "workspace-write"
}

func runAppServerSession(client *appServerClient, request provider.Request, cfg configuration) (*provider.Result, error) {
	if err := client.call("initialize", map[string]any{
		"clientInfo":   map[string]string{"name": "agentscope-runtime-host", "version": "1"},
		"capabilities": map[string]any{},
	}, nil); err != nil {
		return nil, err
	}
	if err := client.notify("initialized", nil); err != nil {
		return nil, err
	}
	threadParams := map[string]any{
		"cwd": request.Workspace, "sandbox": sandboxMode(cfg), "approvalPolicy": "on-request",
		"approvalsReviewer": "user",
	}
	if model := provider.DefinitionModel(request, cfg.Model); model != "" {
		threadParams["model"] = model
	}
	if instructions := provider.DefinitionInstructions(request); instructions != "" {
		threadParams["developerInstructions"] = instructions
	}
	if cfg.ServiceTier != "" {
		threadParams["serviceTier"] = cfg.ServiceTier
	}
	method := "thread/start"
	if request.ProviderSessionID != "" {
		method = "thread/resume"
		threadParams["threadId"] = request.ProviderSessionID
		threadParams["excludeTurns"] = true
	}
	var started struct {
		Thread struct {
			ID string `json:"id"`
		} `json:"thread"`
	}
	if err := client.call(method, threadParams, &started); err != nil {
		return nil, err
	}
	if started.Thread.ID == "" {
		return nil, fmt.Errorf("Codex returned an empty thread ID")
	}
	client.result.ProviderSessionID = started.Thread.ID
	client.rootThreadID = started.Thread.ID
	turnParams := map[string]any{
		"threadId":          started.Thread.ID,
		"input":             []map[string]string{{"type": "text", "text": request.Prompt}},
		"approvalPolicy":    "on-request",
		"approvalsReviewer": "user",
	}
	if cfg.ReasoningEffort != "" {
		turnParams["effort"] = cfg.ReasoningEffort
	}
	if cfg.ServiceTier != "" {
		turnParams["serviceTier"] = cfg.ServiceTier
	}
	if err := client.call("turn/start", turnParams, nil); err != nil {
		return nil, err
	}
	if err := client.waitForTurn(); err != nil {
		return nil, err
	}
	return client.result, nil
}

type appServerClient struct {
	rootThreadID string
	context      context.Context
	writer       io.Writer
	scanner      *bufio.Scanner
	sink         provider.EventSink
	approver     provider.ToolApprover
	nextID       int64
	result       *provider.Result
	terminal     bool
	turnErr      error
}

func newAppServerClient(ctx context.Context, writer io.Writer, reader io.Reader, sink provider.EventSink,
	approver provider.ToolApprover) *appServerClient {
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 64*1024), 16*1024*1024)
	return &appServerClient{context: ctx, writer: writer, scanner: scanner, sink: sink,
		approver: approver, result: &provider.Result{}}
}

func (c *appServerClient) call(method string, params any, target any) error {
	c.nextID++
	id := c.nextID
	if err := c.write(map[string]any{"jsonrpc": "2.0", "id": id, "method": method, "params": params}); err != nil {
		return err
	}
	for c.scanner.Scan() {
		raw := append(json.RawMessage(nil), c.scanner.Bytes()...)
		message, err := decodeRPCMessage(raw)
		if err != nil {
			return err
		}
		if message.Method != "" {
			if err = c.handleInbound(raw, message); err != nil {
				return err
			}
			continue
		}
		var responseID int64
		if len(message.ID) == 0 || json.Unmarshal(message.ID, &responseID) != nil || responseID != id {
			continue
		}
		if message.Error != nil {
			return fmt.Errorf("app-server %s failed (%d): %s", method, message.Error.Code, message.Error.Message)
		}
		if target != nil && len(message.Result) > 0 && string(message.Result) != "null" {
			if err = json.Unmarshal(message.Result, target); err != nil {
				return fmt.Errorf("decode app-server %s result: %w", method, err)
			}
		}
		return nil
	}
	return c.scanError()
}

func (c *appServerClient) notify(method string, params any) error {
	message := map[string]any{"jsonrpc": "2.0", "method": method}
	if params != nil {
		message["params"] = params
	}
	return c.write(message)
}

func (c *appServerClient) waitForTurn() error {
	for !c.terminal && c.scanner.Scan() {
		raw := append(json.RawMessage(nil), c.scanner.Bytes()...)
		message, err := decodeRPCMessage(raw)
		if err != nil {
			return err
		}
		if message.Method != "" {
			if err = c.handleInbound(raw, message); err != nil {
				return err
			}
		}
	}
	if c.terminal {
		return c.turnErr
	}
	return c.scanError()
}

type rpcMessage struct {
	ID     json.RawMessage `json:"id"`
	Method string          `json:"method"`
	Params json.RawMessage `json:"params"`
	Result json.RawMessage `json:"result"`
	Error  *struct {
		Code    int    `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

func decodeRPCMessage(raw json.RawMessage) (rpcMessage, error) {
	var message rpcMessage
	if err := json.Unmarshal(raw, &message); err != nil {
		return message, fmt.Errorf("decode Codex app-server message: %w", err)
	}
	return message, nil
}

func (c *appServerClient) handleInbound(raw json.RawMessage, message rpcMessage) error {
	var routing struct {
		ThreadID string `json:"threadId"`
		Thread   struct {
			ID string `json:"id"`
		} `json:"thread"`
	}
	_ = json.Unmarshal(message.Params, &routing)
	eventThreadID := routing.ThreadID
	if eventThreadID == "" {
		eventThreadID = routing.Thread.ID
	}
	if eventThreadID == "" {
		eventThreadID = c.result.ProviderSessionID
	}
	if c.sink != nil {
		if err := c.sink(provider.Event{Type: message.Method, ProviderSessionID: eventThreadID, Raw: raw}); err != nil {
			return err
		}
	}
	if len(message.ID) > 0 {
		return c.handleServerRequest(message)
	}
	// Child notifications remain observable and approvals are still bridged,
	// but a child's result/termination cannot finish the parent's execution.
	if c.rootThreadID != "" && eventThreadID != "" && eventThreadID != c.rootThreadID {
		return nil
	}
	var params struct {
		ThreadID string `json:"threadId"`
		Thread   struct {
			ID string `json:"id"`
		} `json:"thread"`
		Item codexItem `json:"item"`
		Turn struct {
			Status string          `json:"status"`
			Error  json.RawMessage `json:"error"`
			Items  []codexItem     `json:"items"`
		} `json:"turn"`
		Error struct {
			Message string `json:"message"`
		} `json:"error"`
		WillRetry bool `json:"willRetry"`
	}
	if json.Unmarshal(message.Params, &params) != nil {
		return nil
	}
	if params.ThreadID != "" {
		c.result.ProviderSessionID = params.ThreadID
	} else if params.Thread.ID != "" {
		c.result.ProviderSessionID = params.Thread.ID
	}
	if message.Method == "item/completed" {
		c.captureItem(params.Item)
	}
	if message.Method == "error" && !params.WillRetry && params.Error.Message != "" {
		c.turnErr = fmt.Errorf("Codex turn failed: %s", params.Error.Message)
	}
	if message.Method == "turn/completed" {
		for _, item := range params.Turn.Items {
			c.captureItem(item)
		}
		c.terminal = true
		if params.Turn.Status != "completed" {
			text := codexErrorMessage(params.Turn.Error)
			if text == "" {
				text = params.Turn.Status
			}
			c.turnErr = fmt.Errorf("Codex turn %s: %s", params.Turn.Status, text)
		}
	}
	return nil
}

type codexItem struct {
	Type   string          `json:"type"`
	Text   string          `json:"text"`
	Status string          `json:"status"`
	Error  json.RawMessage `json:"error"`
}

func (c *appServerClient) captureItem(item codexItem) {
	if item.Type == "agentMessage" && item.Text != "" {
		c.result.Output = item.Text
	}
}

func (c *appServerClient) handleServerRequest(message rpcMessage) error {
	switch message.Method {
	case "item/commandExecution/requestApproval", "item/fileChange/requestApproval", "item/permissions/requestApproval":
		return c.handleApproval(message)
	default:
		return c.write(map[string]any{"jsonrpc": "2.0", "id": json.RawMessage(message.ID),
			"error": map[string]any{"code": -32601, "message": "AgentScope host does not implement " + message.Method}})
	}
}

func (c *appServerClient) handleApproval(message rpcMessage) error {
	var params map[string]any
	if err := json.Unmarshal(message.Params, &params); err != nil {
		return fmt.Errorf("decode Codex approval request: %w", err)
	}
	toolUseID := stringValue(params["approvalId"])
	if toolUseID == "" {
		toolUseID = stringValue(params["itemId"])
	}
	if toolUseID == "" {
		toolUseID = message.Method + ":" + strings.TrimSpace(string(message.ID))
	}
	toolName := "provider_tool"
	switch message.Method {
	case "item/commandExecution/requestApproval":
		toolName = "shell"
	case "item/fileChange/requestApproval":
		toolName = "apply_patch"
	case "item/permissions/requestApproval":
		toolName = "request_permissions"
	}
	decision := provider.ToolApprovalDecision{DenyMessage: "Tool use was not approved by the AgentScope host."}
	if c.approver != nil {
		inputJSON, _ := json.Marshal(params)
		hash := sha256.Sum256(inputJSON)
		var err error
		decision, err = c.approver(c.context, provider.ToolApprovalRequest{ToolUseID: toolUseID,
			ToolName: toolName, Input: params, InputSHA256: fmt.Sprintf("%x", hash[:])})
		if err != nil {
			_ = c.writeApprovalResponse(message, false, params)
			return fmt.Errorf("request Codex tool approval: %w", err)
		}
	}
	return c.writeApprovalResponse(message, decision.Allow, params)
}

func (c *appServerClient) writeApprovalResponse(message rpcMessage, allow bool, params map[string]any) error {
	var result any
	if message.Method == "item/permissions/requestApproval" {
		permissions := map[string]any{}
		if allow {
			if requested, ok := params["permissions"].(map[string]any); ok {
				permissions = requested
			}
		}
		result = map[string]any{"permissions": permissions, "scope": "turn"}
	} else {
		decision := "decline"
		if allow {
			decision = "accept"
		}
		result = map[string]string{"decision": decision}
	}
	return c.write(map[string]any{"jsonrpc": "2.0", "id": json.RawMessage(message.ID), "result": result})
}

func stringValue(value any) string {
	text, _ := value.(string)
	return text
}

func (c *appServerClient) scanError() error {
	if err := c.scanner.Err(); err != nil {
		return err
	}
	return io.ErrUnexpectedEOF
}

func (c *appServerClient) write(message any) error {
	data, err := json.Marshal(message)
	if err != nil {
		return err
	}
	data = append(data, '\n')
	_, err = c.writer.Write(data)
	return err
}

func codexErrorMessage(raw json.RawMessage) string {
	if len(raw) == 0 || string(raw) == "null" {
		return ""
	}
	var detail struct {
		Message string `json:"message"`
	}
	if json.Unmarshal(raw, &detail) == nil && detail.Message != "" {
		return detail.Message
	}
	var message string
	if json.Unmarshal(raw, &message) == nil {
		return message
	}
	return string(raw)
}

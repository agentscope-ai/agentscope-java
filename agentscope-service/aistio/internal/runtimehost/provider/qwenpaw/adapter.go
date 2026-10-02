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
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

// Adapter drives QwenPaw through its official ACP stdio server. Keeping ACP
// inside the provider adapter preserves the Runtime Host's provider-neutral
// execution and checkpoint contract.
type Adapter struct {
	Binary string
}

type configuration struct {
	Agent            string `json:"agent,omitempty"`
	Model            string `json:"model,omitempty"`
	PermissionMode   string `json:"permissionMode,omitempty"`
	RuntimeProvider  string `json:"runtimeProvider,omitempty"`
	LocalDiagnostics bool   `json:"localDiagnostics,omitempty"`
}

func (a *Adapter) Name() string { return "qwenpaw" }

func (a *Adapter) Descriptor() provider.Descriptor {
	return provider.Descriptor{
		DisplayName:  "QwenPaw",
		Runtime:      "qwenpaw",
		Instructions: provider.Capability{Supported: true, Mode: "prompt"},
		Workspace:    provider.Capability{Supported: true, Mode: "cwd"},
		Skills:       provider.Capability{Supported: true, Mode: "native-directory", Target: "skills"},
		Tools:        provider.Capability{Supported: true, Mode: "native-policy"},
		Shell:        provider.Capability{Supported: true, Mode: "native", Target: "shell"},
		MCP:          provider.Capability{Supported: true, Mode: "acp-session"},
		Model:        provider.Capability{Supported: true, Mode: "acp-session", Target: "session/set_model"},
		CustomArgs:   provider.Capability{Supported: true, Mode: "argv", Target: "qwenpaw acp"},
		Approval:     provider.Capability{Supported: true, Mode: "control-plane", Target: "session/request_permission"},
		Resume:       true,
	}
}

func (a *Adapter) binary() string {
	if a.Binary != "" {
		return a.Binary
	}
	return "qwenpaw"
}

func (a *Adapter) Detect(ctx context.Context) (string, error) {
	cmd := exec.CommandContext(ctx, a.binary(), "--version")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("qwenpaw --version: %w: %s", err, strings.TrimSpace(string(out)))
	}
	return strings.TrimSpace(string(out)), nil
}

func (a *Adapter) Run(ctx context.Context, request provider.Request, sink provider.EventSink) (*provider.Result, error) {
	if request.Workspace == "" {
		return nil, fmt.Errorf("QwenPaw workspace is required")
	}
	customArgs, err := provider.ValidateCustomArgs(request.CustomArgs)
	if err != nil {
		return nil, err
	}
	request.CustomArgs = customArgs
	var cfg configuration
	if len(request.Configuration) > 0 {
		if err := json.Unmarshal(request.Configuration, &cfg); err != nil {
			return nil, fmt.Errorf("decode QwenPaw configuration: %w", err)
		}
	}
	cleanupSkills, err := provider.ProjectSkills(request.Workspace, "skills", a.Name())
	if err != nil {
		return nil, fmt.Errorf("project QwenPaw skills: %w", err)
	}
	defer cleanupSkills()
	cleanupManifest, err := enableProjectedSkills(request.Workspace)
	if err != nil {
		return nil, err
	}
	defer cleanupManifest()

	args := []string{"acp", "--workspace", request.Workspace}
	if cfg.Agent != "" {
		args = append(args, "--agent", cfg.Agent)
	}
	if cfg.RuntimeProvider != "" {
		args = append(args, "--runtime-provider", cfg.RuntimeProvider)
	}
	if cfg.LocalDiagnostics {
		args = append(args, "--local-diagnostics")
	}
	args = append(args, request.CustomArgs...)
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
		return nil, fmt.Errorf("start QwenPaw ACP server: %w", err)
	}
	client := newACPClient(stdin, stdout, sink)
	client.context = ctx
	client.approver = request.ApproveTool
	result, runErr := runSession(client, request, cfg)
	_ = stdin.Close()
	waitErr := cmd.Wait()
	if runErr != nil {
		return nil, fmt.Errorf("QwenPaw ACP: %w: %s", runErr, strings.TrimSpace(stderr.String()))
	}
	if waitErr != nil {
		return nil, fmt.Errorf("QwenPaw exited: %w: %s", waitErr, strings.TrimSpace(stderr.String()))
	}
	result.Checkpoint, _ = json.Marshal(map[string]string{"providerSessionId": result.ProviderSessionID})
	return result, nil
}

func runSession(client *acpClient, request provider.Request, cfg configuration) (*provider.Result, error) {
	var initialized struct {
		ProtocolVersion int `json:"protocolVersion"`
	}
	if err := client.call("initialize", map[string]any{
		"protocolVersion":    1,
		"clientCapabilities": map[string]any{},
		"clientInfo":         map[string]string{"name": "agentscope-runtime-host", "version": "1"},
	}, &initialized); err != nil {
		return nil, err
	}
	if initialized.ProtocolVersion != 1 {
		return nil, fmt.Errorf("unsupported ACP protocol version %d", initialized.ProtocolVersion)
	}
	mcpServers, err := acpMCPServers(request)
	if err != nil {
		return nil, err
	}
	sessionID := request.ProviderSessionID
	if sessionID == "" {
		var created struct {
			SessionID string `json:"sessionId"`
		}
		if err = client.call("session/new", map[string]any{
			"cwd": request.Workspace, "mcpServers": mcpServers,
		}, &created); err != nil {
			return nil, err
		}
		sessionID = created.SessionID
		if sessionID == "" {
			return nil, fmt.Errorf("QwenPaw returned an empty ACP session ID")
		}
	} else if err = client.call("session/load", map[string]any{
		"cwd": request.Workspace, "sessionId": sessionID, "mcpServers": mcpServers,
	}, nil); err != nil {
		return nil, err
	}
	model := provider.DefinitionModel(request, cfg.Model)
	if model != "" {
		if err = client.call("session/set_model", map[string]any{"sessionId": sessionID, "modelId": model}, nil); err != nil {
			return nil, err
		}
	}
	if cfg.PermissionMode != "" {
		if cfg.PermissionMode != "default" && cfg.PermissionMode != "bypassPermissions" {
			return nil, fmt.Errorf("unsupported QwenPaw permission mode %q", cfg.PermissionMode)
		}
		if err = client.call("session/set_config_option", map[string]any{
			"sessionId": sessionID, "configId": "mode", "value": cfg.PermissionMode,
		}, nil); err != nil {
			return nil, err
		}
	}
	client.result.ProviderSessionID = sessionID
	var prompted struct {
		StopReason string `json:"stopReason"`
	}
	prompt := provider.PrependInstructions(request.Prompt, provider.DefinitionInstructions(request))
	if err = client.call("session/prompt", map[string]any{
		"sessionId": sessionID,
		"prompt":    []map[string]string{{"type": "text", "text": prompt}},
	}, &prompted); err != nil {
		return nil, err
	}
	if prompted.StopReason == "refusal" {
		return nil, fmt.Errorf("QwenPaw refused the turn")
	}
	return client.result, nil
}

type acpClient struct {
	writer   io.Writer
	scanner  *bufio.Scanner
	sink     provider.EventSink
	nextID   int64
	result   *provider.Result
	context  context.Context
	approver provider.ToolApprover
}

func newACPClient(writer io.Writer, reader io.Reader, sink provider.EventSink) *acpClient {
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 64*1024), 16*1024*1024)
	return &acpClient{writer: writer, scanner: scanner, sink: sink, result: &provider.Result{}}
}

func (c *acpClient) call(method string, params any, target any) error {
	c.nextID++
	id := c.nextID
	if err := c.write(map[string]any{"jsonrpc": "2.0", "id": id, "method": method, "params": params}); err != nil {
		return err
	}
	for c.scanner.Scan() {
		raw := append(json.RawMessage(nil), c.scanner.Bytes()...)
		var message struct {
			ID     json.RawMessage `json:"id"`
			Method string          `json:"method"`
			Params json.RawMessage `json:"params"`
			Result json.RawMessage `json:"result"`
			Error  *struct {
				Code    int    `json:"code"`
				Message string `json:"message"`
			} `json:"error"`
		}
		if err := json.Unmarshal(raw, &message); err != nil {
			return fmt.Errorf("decode ACP message: %w", err)
		}
		if message.Method != "" {
			if err := c.handleInbound(raw, message.ID, message.Method, message.Params); err != nil {
				return err
			}
			continue
		}
		var responseID int64
		if len(message.ID) == 0 || json.Unmarshal(message.ID, &responseID) != nil || responseID != id {
			continue
		}
		if message.Error != nil {
			return fmt.Errorf("ACP %s failed (%d): %s", method, message.Error.Code, message.Error.Message)
		}
		if target != nil && len(message.Result) > 0 && string(message.Result) != "null" {
			if err := json.Unmarshal(message.Result, target); err != nil {
				return fmt.Errorf("decode ACP %s result: %w", method, err)
			}
		}
		return nil
	}
	if err := c.scanner.Err(); err != nil {
		return err
	}
	return io.ErrUnexpectedEOF
}

func (c *acpClient) handleInbound(raw, id json.RawMessage, method string, params json.RawMessage) error {
	if c.sink != nil {
		if err := c.sink(provider.Event{Type: method, ProviderSessionID: c.result.ProviderSessionID, Raw: raw}); err != nil {
			return err
		}
	}
	if method == "session/update" {
		var update struct {
			SessionID string `json:"sessionId"`
			Update    struct {
				Kind    string `json:"sessionUpdate"`
				Content struct {
					Type string `json:"type"`
					Text string `json:"text"`
				} `json:"content"`
			} `json:"update"`
		}
		if json.Unmarshal(params, &update) == nil {
			if update.SessionID != "" {
				c.result.ProviderSessionID = update.SessionID
			}
			if update.Update.Kind == "agent_message_chunk" && update.Update.Content.Type == "text" {
				c.result.Output += update.Update.Content.Text
			}
		}
		return nil
	}
	if method == "session/request_permission" && len(id) > 0 {
		if c.approver != nil {
			var request struct {
				ToolUseID string `json:"toolUseId"`
				ToolCall  struct {
					ID         string `json:"id"`
					ToolCallID string `json:"toolCallId"`
					Name       string `json:"name"`
					Title      string `json:"title"`
					Input      any    `json:"input"`
					RawInput   any    `json:"rawInput"`
				} `json:"toolCall"`
				Options []struct {
					OptionID string `json:"optionId"`
					Kind     string `json:"kind"`
					Name     string `json:"name"`
				} `json:"options"`
			}
			_ = json.Unmarshal(params, &request)
			toolUseID := request.ToolUseID
			if toolUseID == "" {
				toolUseID = request.ToolCall.ToolCallID
			}
			if toolUseID == "" {
				toolUseID = request.ToolCall.ID
			}
			if toolUseID == "" {
				toolUseID = "acp:" + strings.TrimSpace(string(id))
			}
			toolName := request.ToolCall.Name
			if toolName == "" {
				toolName = request.ToolCall.Title
			}
			if toolName == "" {
				toolName = "provider_tool"
			}
			approvalContext := c.context
			if approvalContext == nil {
				approvalContext = context.Background()
			}
			input := request.ToolCall.Input
			if input == nil {
				input = request.ToolCall.RawInput
			}
			decision, err := c.approver(approvalContext, provider.ToolApprovalRequest{
				ToolUseID: toolUseID, ToolName: toolName, Input: input,
			})
			if err != nil {
				return fmt.Errorf("request tool approval: %w", err)
			}
			if decision.Allow {
				optionID := allowedPermissionOption(request.Options)
				if optionID != "" {
					return c.write(map[string]any{"jsonrpc": "2.0", "id": json.RawMessage(id),
						"result": map[string]any{"outcome": map[string]string{"outcome": "selected", "optionId": optionID}}})
				}
			}
		}
		return c.write(map[string]any{
			"jsonrpc": "2.0", "id": json.RawMessage(id),
			"result": map[string]any{"outcome": map[string]string{"outcome": "cancelled"}},
		})
	}
	return nil
}

func allowedPermissionOption(options []struct {
	OptionID string `json:"optionId"`
	Kind     string `json:"kind"`
	Name     string `json:"name"`
}) string {
	for _, option := range options {
		candidate := strings.ToLower(option.Kind + " " + option.Name)
		if strings.Contains(candidate, "allow") || strings.Contains(candidate, "approve") {
			return option.OptionID
		}
	}
	if len(options) > 0 {
		return options[0].OptionID
	}
	return ""
}

func (c *acpClient) write(message any) error {
	data, err := json.Marshal(message)
	if err != nil {
		return err
	}
	data = append(data, '\n')
	_, err = c.writer.Write(data)
	return err
}

func acpMCPServers(request provider.Request) ([]map[string]any, error) {
	var definitions []map[string]any
	if request.Definition != nil && len(request.Definition.MCPServers) > 0 && string(request.Definition.MCPServers) != "null" {
		if err := json.Unmarshal(request.Definition.MCPServers, &definitions); err != nil {
			return nil, fmt.Errorf("decode Agent MCP servers: %w", err)
		}
	}
	servers := make([]map[string]any, 0, len(definitions)+1)
	names := make(map[string]bool, len(definitions)+1)
	for _, definition := range definitions {
		name, _ := definition["name"].(string)
		if name == "" {
			return nil, fmt.Errorf("Agent MCP server name is required")
		}
		if names[name] {
			return nil, fmt.Errorf("duplicate Agent MCP server name %q", name)
		}
		names[name] = true
		server := map[string]any{"name": name}
		if command, _ := definition["command"].(string); command != "" {
			server["command"] = command
			server["args"] = stringValues(definition["args"])
			server["env"] = namedValues(definition["env"])
		} else if url, _ := definition["url"].(string); url != "" {
			typeName, _ := definition["type"].(string)
			if typeName == "" || typeName == "streamable_http" || typeName == "streamable-http" {
				typeName = "http"
			}
			if typeName != "http" && typeName != "sse" {
				return nil, fmt.Errorf("Agent MCP server %q has unsupported ACP transport %q", name, typeName)
			}
			server["type"], server["url"] = typeName, url
			server["headers"] = namedValues(definition["headers"])
		} else {
			return nil, fmt.Errorf("Agent MCP server %q requires command or url", name)
		}
		servers = append(servers, server)
	}
	if request.CollaborationMCP != "" && request.TaskToken != "" {
		if names["agentscope-collaboration"] {
			return nil, fmt.Errorf("Agent MCP server name %q is reserved", "agentscope-collaboration")
		}
		servers = append(servers, map[string]any{
			"type": "http", "name": "agentscope-collaboration", "url": request.CollaborationMCP,
			"headers": []map[string]string{{"name": "Authorization", "value": "Bearer " + request.TaskToken}},
		})
	}
	return servers, nil
}

func stringValues(value any) []string {
	result := make([]string, 0)
	if values, ok := value.([]any); ok {
		for _, raw := range values {
			result = append(result, fmt.Sprint(raw))
		}
	}
	return result
}

func namedValues(value any) []map[string]string {
	result := make([]map[string]string, 0)
	switch values := value.(type) {
	case map[string]any:
		for name, raw := range values {
			result = append(result, map[string]string{"name": name, "value": fmt.Sprint(raw)})
		}
	case map[string]string:
		for name, raw := range values {
			result = append(result, map[string]string{"name": name, "value": raw})
		}
	case []any:
		for _, raw := range values {
			if item, ok := raw.(map[string]any); ok {
				result = append(result, map[string]string{"name": fmt.Sprint(item["name"]), "value": fmt.Sprint(item["value"])})
			}
		}
	}
	return result
}

func enableProjectedSkills(workspace string) (func(), error) {
	manifestPath := filepath.Join(workspace, "skill.json")
	original, readErr := os.ReadFile(manifestPath)
	hadOriginal := readErr == nil
	if readErr != nil && !os.IsNotExist(readErr) {
		return nil, fmt.Errorf("read QwenPaw skill manifest: %w", readErr)
	}
	manifest := map[string]any{}
	if hadOriginal && len(original) > 0 {
		if err := json.Unmarshal(original, &manifest); err != nil {
			return nil, fmt.Errorf("decode QwenPaw skill manifest: %w", err)
		}
	}
	skills, _ := manifest["skills"].(map[string]any)
	if skills == nil {
		skills = map[string]any{}
		manifest["skills"] = skills
	}
	entries, err := os.ReadDir(filepath.Join(workspace, ".agentscope", "definition", "skills"))
	if err != nil && !os.IsNotExist(err) {
		return nil, err
	}
	for _, entry := range entries {
		if entry.IsDir() {
			skills[entry.Name()] = map[string]any{"enabled": true, "channels": []string{"all"}, "source": "customized"}
		}
	}
	data, err := json.Marshal(manifest)
	if err != nil {
		return nil, err
	}
	if err = os.WriteFile(manifestPath, data, 0o600); err != nil {
		return nil, fmt.Errorf("write QwenPaw skill manifest: %w", err)
	}
	return func() {
		if hadOriginal {
			_ = os.WriteFile(manifestPath, original, 0o600)
		} else {
			_ = os.Remove(manifestPath)
		}
	}, nil
}

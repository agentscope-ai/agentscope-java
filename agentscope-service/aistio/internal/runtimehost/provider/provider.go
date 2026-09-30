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

package provider

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

type Request struct {
	Prompt            string
	Workspace         string
	RuntimeStateRoot  string
	ProviderSessionID string
	Configuration     json.RawMessage
	CustomArgs        []string
	Definition        *AgentDefinition
	CollaborationMCP  string
	CollaborationCLI  string
	ControlPlane      string
	TaskToken         string
	TaskID            string
	IssueID           string
	AgentID           string
	TeamID            string
	RunID             string
	ApproveTool       ToolApprover
}

type ToolApprovalRequest struct {
	ToolUseID   string
	ToolName    string
	Input       any
	InputSHA256 string
	ExpiresAt   string
}

type ToolApprovalDecision struct {
	ApprovalID      string
	DecisionVersion int64
	Allow           bool
	DenyMessage     string
}

type ToolApprover func(context.Context, ToolApprovalRequest) (ToolApprovalDecision, error)

// AgentDefinition is the provider-neutral part of an Agent that a Runtime
// Host can materialize. Provider adapters translate these fields into their
// native CLI contract instead of exposing RuntimeProfile details to Agent
// authors.
type AgentDefinition struct {
	System                string            `json:"system,omitempty"`
	Model                 string            `json:"model,omitempty"`
	Tools                 json.RawMessage   `json:"tools,omitempty"`
	MCPServers            json.RawMessage   `json:"mcpServers,omitempty"`
	Skills                json.RawMessage   `json:"skills,omitempty"`
	WorkspaceID           string            `json:"workspaceId,omitempty"`
	DefaultEnvironmentID  string            `json:"defaultEnvironmentId,omitempty"`
	DefaultVaultIDs       []string          `json:"defaultVaultIds,omitempty"`
	DefaultMemoryStoreIDs []string          `json:"defaultMemoryStoreIds,omitempty"`
	Files                 map[string]string `json:"files,omitempty"`
}

// Capability describes how one portable Agent capability is adapted by a
// provider. Modes are intentionally descriptive (for example "prompt",
// "file", "cwd", or "cli-config") so the control plane can explain the
// behavior without depending on provider-specific code.
type Capability struct {
	Supported bool   `json:"supported"`
	Mode      string `json:"mode,omitempty"`
	Target    string `json:"target,omitempty"`
}

// Descriptor is the stable capability contract advertised by a Runtime Host.
// It complements the detected version. Internal RuntimeProfile records may
// still carry provider overrides, but Agent authors select this discovered
// runtime rather than managing those records directly.
type Descriptor struct {
	DisplayName  string     `json:"displayName"`
	Runtime      string     `json:"runtime"`
	Instructions Capability `json:"instructions"`
	Workspace    Capability `json:"workspace"`
	Skills       Capability `json:"skills"`
	Subagents    Capability `json:"subagents"`
	Tools        Capability `json:"tools"`
	Shell        Capability `json:"shell"`
	MCP          Capability `json:"mcp"`
	Model        Capability `json:"model"`
	CustomArgs   Capability `json:"customArgs"`
	Approval     Capability `json:"approval"`
	Resume       bool       `json:"resume"`
}

var reservedCustomArguments = map[string]bool{
	"exec": true, "resume": true, "agent": true, "acp": true, "app-server": true,
	"-p": true, "-C": true, "--cd": true, "--cwd": true,
	"--json": true, "--input-format": true, "--output-format": true, "--message-file": true,
	"--model": true, "--sandbox": true, "--permission-mode": true,
	"--listen": true,
	"--resume": true, "--mcp-config": true, "--config": true,
	"--config-dir": true, "--strict-mcp-config": true, "--setting-sources": true,
	"--allowed-mcp-server-names": true, "--tools": true, "--plugin-dir": true,
	"--skip-git-repo-check": true, "--dangerously-skip-permissions": true,
	"--dangerously-bypass-approvals-and-sandbox": true,
	"--yolo": true, "--full-auto": true, "--add-dir": true,
	"--ask-for-approval": true, "--approval-mode": true, "--workspace": true,
	"--runtime-provider": true, "--allowedTools": true, "--allowed-tools": true,
	"--disallowedTools": true, "--disallowed-tools": true, "--settings": true,
	"--append-system-prompt": true,
}

// ValidateCustomArgs rejects arguments owned by the Runtime Host. Custom
// arguments are passed as argv tokens and never through a shell.
func ValidateCustomArgs(args []string) ([]string, error) {
	result := make([]string, 0, len(args))
	for _, raw := range args {
		arg := strings.TrimSpace(raw)
		if arg == "" || strings.ContainsRune(arg, '\x00') {
			return nil, fmt.Errorf("custom argument is empty or contains NUL")
		}
		name, _, _ := strings.Cut(arg, "=")
		if reservedCustomArguments[name] {
			return nil, fmt.Errorf("custom argument %q is managed by AgentScope", name)
		}
		result = append(result, arg)
	}
	return result, nil
}

// Describer is optional so third-party adapters that implement the original
// process contract remain source compatible.
type Describer interface {
	Descriptor() Descriptor
}

func Describe(adapter Adapter) Descriptor {
	if describer, ok := adapter.(Describer); ok {
		return describer.Descriptor()
	}
	return Descriptor{
		DisplayName: adapter.Name(),
		Runtime:     adapter.Name(),
		Instructions: Capability{
			Supported: true,
			Mode:      "prompt",
		},
		Workspace: Capability{Supported: true, Mode: "cwd"},
	}
}

func DefinitionModel(request Request, fallback string) string {
	if request.Definition != nil && request.Definition.Model != "" {
		return request.Definition.Model
	}
	return fallback
}

func DefinitionInstructions(request Request) string {
	if request.Definition == nil {
		return ""
	}
	return request.Definition.System
}

func PrependInstructions(prompt, instructions string) string {
	if instructions == "" {
		return prompt
	}
	return "Agent instructions:\n" + instructions + "\n\nTask:\n" + prompt
}

// DefinitionToolPolicy returns only explicitly enabled or disabled tools.
// Adapters provide aliases because provider-native tool names are not part of
// the portable Agent definition vocabulary.
func DefinitionToolPolicy(request Request, aliases map[string]string) (allowed, denied []string) {
	if request.Definition == nil || len(request.Definition.Tools) == 0 ||
		string(request.Definition.Tools) == "null" {
		return nil, nil
	}
	var toolsets []struct {
		Configs []struct {
			Name    string `json:"name"`
			Enabled *bool  `json:"enabled,omitempty"`
		} `json:"configs"`
	}
	if json.Unmarshal(request.Definition.Tools, &toolsets) != nil {
		return nil, nil
	}
	for _, toolset := range toolsets {
		for _, config := range toolset.Configs {
			if config.Enabled == nil || config.Name == "" {
				continue
			}
			name := config.Name
			if alias := aliases[name]; alias != "" {
				name = alias
			}
			if *config.Enabled {
				allowed = appendUnique(allowed, name)
			} else {
				denied = appendUnique(denied, name)
			}
		}
	}
	return allowed, denied
}

func MergeUnique(values ...[]string) []string {
	var merged []string
	for _, group := range values {
		for _, value := range group {
			merged = appendUnique(merged, value)
		}
	}
	return merged
}

func appendUnique(values []string, value string) []string {
	for _, existing := range values {
		if existing == value {
			return values
		}
	}
	return append(values, value)
}

const (
	TaskTokenEnvironment       = "AGENTSCOPE_TASK_TOKEN"
	LegacyTaskTokenEnvironment = "AISTIO_AGENT_TASK_TOKEN"
)

// ApplyTaskEnvironment gives a provider process only the task-scoped
// collaboration identity. Runtime-host and human credentials are explicitly
// removed so an Agent can never silently fall back to the daemon owner.
func ApplyTaskEnvironment(cmd *exec.Cmd, request Request) {
	if cmd == nil {
		return
	}
	blocked := map[string]bool{
		"AGENTSCOPE_API_TOKEN":     true,
		"AGENTSCOPE_RUNTIME_TOKEN": true,
		"AISTIO_INTERNAL_TOKEN":    true,
		"BUILDER_INTERNAL_TOKEN":   true,
	}
	overrides := map[string]string{
		TaskTokenEnvironment:       request.TaskToken,
		LegacyTaskTokenEnvironment: request.TaskToken,
		"AGENTSCOPE_CONTROL_PLANE": request.ControlPlane,
		"AGENTSCOPE_TASK_ID":       request.TaskID,
		"AGENTSCOPE_ISSUE_ID":      request.IssueID,
		"AGENTSCOPE_AGENT_ID":      request.AgentID,
		"AGENTSCOPE_TEAM_ID":       request.TeamID,
		"AGENTSCOPE_RUN_ID":        request.RunID,
	}
	for key := range overrides {
		blocked[key] = true
	}
	if request.CollaborationCLI != "" {
		blocked["PATH"] = true
	}
	environment := make([]string, 0, len(cmd.Environ())+len(overrides))
	pathValue := ""
	for _, item := range cmd.Environ() {
		key, value, _ := strings.Cut(item, "=")
		if key == "PATH" {
			pathValue = value
		}
		if !blocked[key] {
			environment = append(environment, item)
		}
	}
	if request.CollaborationCLI != "" {
		binDir := filepath.Dir(request.CollaborationCLI)
		if pathValue == "" {
			pathValue = os.Getenv("PATH")
		}
		overrides["PATH"] = binDir + string(os.PathListSeparator) + pathValue
	}
	for _, key := range []string{
		TaskTokenEnvironment, LegacyTaskTokenEnvironment, "AGENTSCOPE_CONTROL_PLANE",
		"AGENTSCOPE_TASK_ID", "AGENTSCOPE_ISSUE_ID", "AGENTSCOPE_AGENT_ID",
		"AGENTSCOPE_TEAM_ID", "AGENTSCOPE_RUN_ID", "PATH",
	} {
		if value := strings.TrimSpace(overrides[key]); value != "" {
			environment = append(environment, key+"="+value)
		}
	}
	cmd.Env = environment
}

// WriteMCPConfig creates a short-lived, owner-only MCP configuration for CLIs
// that accept JSON MCP files. Keeping the token out of argv avoids exposing it
// through process listings.
func WriteMCPConfig(request Request) (string, func(), error) {
	servers, err := definitionMCPServers(request)
	if err != nil {
		return "", nil, err
	}
	if request.CollaborationMCP != "" && request.TaskToken != "" {
		servers["agentscope-collaboration"] = map[string]any{
			"type": "http", "url": request.CollaborationMCP,
			"headers": map[string]string{"Authorization": "Bearer " + request.TaskToken},
		}
	}
	if len(servers) == 0 {
		return "", func() {}, nil
	}
	dir, err := os.MkdirTemp("", "aistio-mcp-*")
	if err != nil {
		return "", nil, err
	}
	cleanup := func() { _ = os.RemoveAll(dir) }
	config := map[string]any{"mcpServers": servers}
	data, err := json.Marshal(config)
	if err != nil {
		cleanup()
		return "", nil, err
	}
	path := filepath.Join(dir, "mcp.json")
	if err = os.WriteFile(path, data, 0o600); err != nil {
		cleanup()
		return "", nil, fmt.Errorf("write collaboration MCP configuration: %w", err)
	}
	return path, cleanup, nil
}

func definitionMCPServers(request Request) (map[string]any, error) {
	servers := make(map[string]any)
	if request.Definition == nil || len(request.Definition.MCPServers) == 0 ||
		string(request.Definition.MCPServers) == "null" {
		return servers, nil
	}
	var definitions []map[string]any
	if err := json.Unmarshal(request.Definition.MCPServers, &definitions); err != nil {
		return nil, fmt.Errorf("decode Agent MCP servers: %w", err)
	}
	for _, definition := range definitions {
		name, _ := definition["name"].(string)
		if name == "" {
			return nil, fmt.Errorf("Agent MCP server name is required")
		}
		delete(definition, "name")
		servers[name] = definition
	}
	return servers, nil
}

type Event struct {
	Type              string          `json:"type"`
	ProviderSessionID string          `json:"providerSessionId,omitempty"`
	Raw               json.RawMessage `json:"raw"`
}

type Result struct {
	ProviderSessionID string          `json:"providerSessionId,omitempty"`
	Output            string          `json:"output,omitempty"`
	Checkpoint        json.RawMessage `json:"checkpoint,omitempty"`
}

type EventSink func(Event) error

// ExecutionError preserves a stable, user-facing failure code across the
// provider adapter boundary. Provider CLIs occasionally exit successfully
// after refusing a tool call; adapters use this error to prevent that refusal
// from being reported as a successful AgentTask.
type ExecutionError struct {
	Code    string
	Message string
}

func (e *ExecutionError) Error() string {
	if e == nil {
		return ""
	}
	return e.Message
}

func NewExecutionError(code, message string) error {
	return &ExecutionError{Code: code, Message: message}
}

// ExecutionFailure returns the provider-specific code when the adapter could
// classify a failure, otherwise it supplies the generic provider code.
func ExecutionFailure(err error) (string, string) {
	if err == nil {
		return "", ""
	}
	var executionError *ExecutionError
	if errors.As(err, &executionError) {
		return executionError.Code, executionError.Message
	}
	return "provider_failed", err.Error()
}

// Adapter is the process-level contract implemented by each hosted Agent
// runtime, including coding CLIs and ACP-compatible personal agents.
type Adapter interface {
	Name() string
	Detect(ctx context.Context) (version string, err error)
	Run(ctx context.Context, request Request, sink EventSink) (*Result, error)
}

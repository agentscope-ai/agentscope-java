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

package anthropiccli

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os/exec"
	"strconv"
	"strings"

	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

// Adapter runs Claude Code in its documented non-interactive stream-json
// mode. Session IDs remain opaque provider checkpoints owned by aistio.
type Adapter struct {
	Binary string
}

type configuration struct {
	Model              string   `json:"model,omitempty"`
	PermissionMode     string   `json:"permissionMode,omitempty"`
	AllowedTools       []string `json:"allowedTools,omitempty"`
	DisallowedTools    []string `json:"disallowedTools,omitempty"`
	MaxTurns           int      `json:"maxTurns,omitempty"`
	AppendSystemPrompt string   `json:"appendSystemPrompt,omitempty"`
	ReasoningEffort    string   `json:"reasoningEffort,omitempty"`
}

func (a *Adapter) Name() string { return "claude-code" }

func (a *Adapter) Descriptor() provider.Descriptor {
	return provider.Descriptor{
		DisplayName:  "Claude Code",
		Runtime:      "claude",
		Instructions: provider.Capability{Supported: true, Mode: "file", Target: "CLAUDE.md"},
		Workspace:    provider.Capability{Supported: true, Mode: "cwd"},
		Skills:       provider.Capability{Supported: true, Mode: "native-directory", Target: ".claude/skills"},
		Tools:        provider.Capability{Supported: true, Mode: "allowlist"},
		Shell:        provider.Capability{Supported: true, Mode: "native", Target: "Bash"},
		MCP:          provider.Capability{Supported: true, Mode: "cli-config", Target: "--mcp-config"},
		Model:        provider.Capability{Supported: true, Mode: "cli-argument", Target: "--model"},
		CustomArgs:   provider.Capability{Supported: true, Mode: "argv", Target: "claude"},
		Resume:       true,
	}
}

func (a *Adapter) binary() string {
	if a.Binary != "" {
		return a.Binary
	}
	return "claude"
}

func (a *Adapter) Detect(ctx context.Context) (string, error) {
	cmd := exec.CommandContext(ctx, a.binary(), "--version")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("claude --version: %w: %s", err, strings.TrimSpace(string(out)))
	}
	return strings.TrimSpace(string(out)), nil
}

func (a *Adapter) Run(ctx context.Context, request provider.Request, sink provider.EventSink) (*provider.Result, error) {
	if request.Workspace == "" {
		return nil, fmt.Errorf("Claude Code workspace is required")
	}
	customArgs, err := provider.ValidateCustomArgs(request.CustomArgs)
	if err != nil {
		return nil, err
	}
	request.CustomArgs = customArgs
	cleanupSkills, err := provider.ProjectSkills(request.Workspace, ".claude/skills", a.Name())
	if err != nil {
		return nil, fmt.Errorf("project Claude Code skills: %w", err)
	}
	defer cleanupSkills()
	var cfg configuration
	if len(request.Configuration) > 0 {
		if err = json.Unmarshal(request.Configuration, &cfg); err != nil {
			return nil, fmt.Errorf("decode Claude Code configuration: %w", err)
		}
	}
	mcpConfig, cleanup, err := provider.WriteMCPConfig(request)
	if err != nil {
		return nil, err
	}
	defer cleanup()
	cmd := exec.CommandContext(ctx, a.binary(), buildArgs(request, cfg, mcpConfig)...)
	cmd.Dir = request.Workspace
	cmd.Stdin = strings.NewReader(request.Prompt)
	provider.ApplyTaskEnvironment(cmd, request)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("start Claude Code: %w", err)
	}
	result := &provider.Result{ProviderSessionID: request.ProviderSessionID}
	readErr := consumeJSONL(stdout, result, sink)
	waitErr := cmd.Wait()
	if readErr != nil {
		return nil, readErr
	}
	if waitErr != nil {
		return nil, fmt.Errorf("Claude Code exited: %w: %s", waitErr, strings.TrimSpace(stderr.String()))
	}
	checkpoint, _ := json.Marshal(map[string]string{"providerSessionId": result.ProviderSessionID})
	result.Checkpoint = checkpoint
	return result, nil
}

func buildArgs(request provider.Request, cfg configuration, mcpConfig string) []string {
	args := []string{"-p", "--output-format", "stream-json", "--verbose"}
	definitionAllowed, definitionDenied := provider.DefinitionToolPolicy(request, map[string]string{
		"read": "Read", "read_file": "Read", "write": "Edit", "write_file": "Edit",
		"edit": "Edit", "shell": "Bash", "bash": "Bash", "grep": "Grep", "glob": "Glob",
	})
	allowedTools := provider.MergeUnique(cfg.AllowedTools, definitionAllowed)
	disallowedTools := provider.MergeUnique(cfg.DisallowedTools, definitionDenied)
	if mcpConfig != "" {
		args = append(args, "--mcp-config", mcpConfig)
	}
	if request.ProviderSessionID != "" {
		args = append(args, "--resume", request.ProviderSessionID)
	}
	if model := provider.DefinitionModel(request, cfg.Model); model != "" {
		args = append(args, "--model", model)
	}
	if cfg.PermissionMode != "" {
		args = append(args, "--permission-mode", cfg.PermissionMode)
	}
	if len(allowedTools) > 0 {
		args = append(args, "--allowedTools", strings.Join(allowedTools, ","))
	}
	if len(disallowedTools) > 0 {
		args = append(args, "--disallowedTools", strings.Join(disallowedTools, ","))
	}
	if cfg.MaxTurns > 0 {
		args = append(args, "--max-turns", strconv.Itoa(cfg.MaxTurns))
	}
	if cfg.ReasoningEffort != "" {
		args = append(args, "--effort", cfg.ReasoningEffort)
	}
	appendSystemPrompt := strings.TrimSpace(strings.Join([]string{
		cfg.AppendSystemPrompt,
		provider.DefinitionInstructions(request),
	}, "\n\n"))
	if appendSystemPrompt != "" {
		args = append(args, "--append-system-prompt", appendSystemPrompt)
	}
	args = append(args, request.CustomArgs...)
	return args
}

func consumeJSONL(reader io.Reader, result *provider.Result, sink provider.EventSink) error {
	scanner := bufio.NewScanner(reader)
	buffer := make([]byte, 64*1024)
	scanner.Buffer(buffer, 8*1024*1024)
	for scanner.Scan() {
		raw := append(json.RawMessage(nil), scanner.Bytes()...)
		var envelope struct {
			Type      string `json:"type"`
			Subtype   string `json:"subtype"`
			SessionID string `json:"session_id"`
			IsError   bool   `json:"is_error"`
			Result    string `json:"result"`
			Message   struct {
				Content []struct {
					Type string `json:"type"`
					Text string `json:"text"`
				} `json:"content"`
			} `json:"message"`
		}
		if err := json.Unmarshal(raw, &envelope); err != nil {
			return fmt.Errorf("decode Claude Code JSONL event: %w", err)
		}
		if envelope.SessionID != "" {
			result.ProviderSessionID = envelope.SessionID
		}
		if envelope.Result != "" {
			result.Output = envelope.Result
		} else if envelope.Type == "assistant" {
			for _, content := range envelope.Message.Content {
				if content.Type == "text" && content.Text != "" {
					result.Output += content.Text
				}
			}
		}
		if sink != nil {
			if err := sink(provider.Event{
				Type: envelope.Type, ProviderSessionID: envelope.SessionID, Raw: raw,
			}); err != nil {
				return err
			}
		}
		if envelope.Type == "result" && envelope.IsError {
			return fmt.Errorf("Claude Code result %s: %s", envelope.Subtype, envelope.Result)
		}
	}
	return scanner.Err()
}

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
	"encoding/json"
	"fmt"
	"io"
	"os/exec"
	"strings"

	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

type Adapter struct {
	Binary string
}

type configuration struct {
	Model            string `json:"model,omitempty"`
	Profile          string `json:"profile,omitempty"`
	Sandbox          string `json:"sandbox,omitempty"`
	SkipGitRepoCheck *bool  `json:"skipGitRepoCheck,omitempty"`
	ReasoningEffort  string `json:"reasoningEffort,omitempty"`
	ServiceTier      string `json:"serviceTier,omitempty"`
}

func (a *Adapter) Name() string { return "codex" }

func (a *Adapter) Descriptor() provider.Descriptor {
	return provider.Descriptor{
		DisplayName:  "Codex",
		Runtime:      "codex",
		Instructions: provider.Capability{Supported: true, Mode: "file", Target: "AGENTS.md"},
		Workspace:    provider.Capability{Supported: true, Mode: "cwd"},
		Skills:       provider.Capability{Supported: true, Mode: "native-directory", Target: ".agents/skills"},
		Tools:        provider.Capability{Supported: true, Mode: "native"},
		Shell:        provider.Capability{Supported: true, Mode: "native", Target: "shell"},
		MCP:          provider.Capability{Supported: true, Mode: "cli-config"},
		Model:        provider.Capability{Supported: true, Mode: "cli-argument", Target: "--model"},
		CustomArgs:   provider.Capability{Supported: true, Mode: "argv", Target: "codex exec"},
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
	customArgs, err := provider.ValidateCustomArgs(request.CustomArgs)
	if err != nil {
		return nil, err
	}
	request.CustomArgs = customArgs
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
	args := buildArgs(request, cfg)
	cmd := exec.CommandContext(ctx, a.binary(), args...)
	cmd.Dir = request.Workspace
	cmd.Stdin = strings.NewReader(provider.PrependInstructions(request.Prompt, provider.DefinitionInstructions(request)))
	provider.ApplyTaskEnvironment(cmd, request)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("start codex: %w", err)
	}
	result := &provider.Result{ProviderSessionID: request.ProviderSessionID}
	readErr := consumeJSONL(stdout, result, sink)
	waitErr := cmd.Wait()
	if readErr != nil {
		return nil, readErr
	}
	if waitErr != nil {
		return nil, codexExitError(waitErr, stderr.String())
	}
	checkpoint, _ := json.Marshal(map[string]string{"providerSessionId": result.ProviderSessionID})
	result.Checkpoint = checkpoint
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
		return fmt.Errorf("codex exited: %w", waitErr)
	}
	return fmt.Errorf("codex exited: %w: %s", waitErr, detail)
}

func buildArgs(request provider.Request, cfg configuration) []string {
	args := []string{"exec"}
	if request.ProviderSessionID != "" {
		args = append(args, "resume")
	}
	args = append(args, "--json")
	sandbox := cfg.Sandbox
	if sandbox == "" {
		sandbox = "workspace-write"
	}
	if request.CollaborationMCP != "" && request.TaskToken != "" {
		args = append(args, "--config", fmt.Sprintf("mcp_servers.agentscope_collaboration.url=%q", request.CollaborationMCP),
			"--config", fmt.Sprintf("mcp_servers.agentscope_collaboration.bearer_token_env_var=%q", provider.TaskTokenEnvironment),
			"--config", `mcp_servers.agentscope_collaboration.default_tools_approval_mode="approve"`)
		// Codex disables network access inside workspace-write by default. A Team
		// member must be able to reach the task-scoped collaboration MCP endpoint;
		// otherwise it cannot inspect its task, report progress, delegate, or
		// converge its run node.
		// Keep filesystem isolation intact and open only the network capability
		// instead of switching the whole execution to danger-full-access.
		if sandbox == "workspace-write" {
			args = append(args, "--config", "sandbox_workspace_write.network_access=true")
		}
	}
	if model := provider.DefinitionModel(request, cfg.Model); model != "" {
		args = append(args, "--model", model)
	}
	if cfg.Profile != "" {
		args = append(args, "--profile", cfg.Profile)
	}
	if cfg.ReasoningEffort != "" {
		args = append(args, "--config", fmt.Sprintf("model_reasoning_effort=%q", cfg.ReasoningEffort))
	}
	if cfg.ServiceTier != "" {
		args = append(args, "--config", fmt.Sprintf("service_tier=%q", cfg.ServiceTier))
	}
	// Runtime Host workspaces are created and isolated by AgentScope. They are
	// valid Codex workspaces even when an Issue has no repository input, so the
	// hosted default must not depend on a .git directory being present.
	if cfg.SkipGitRepoCheck == nil || *cfg.SkipGitRepoCheck {
		args = append(args, "--skip-git-repo-check")
	}
	if request.ProviderSessionID != "" {
		args = append(args, request.CustomArgs...)
		args = append(args, request.ProviderSessionID, "-")
		return args
	}
	args = append(args, "--sandbox", sandbox, "--cd", request.Workspace)
	args = append(args, request.CustomArgs...)
	args = append(args, "-")
	return args
}

func consumeJSONL(reader io.Reader, result *provider.Result, sink provider.EventSink) error {
	scanner := bufio.NewScanner(reader)
	buffer := make([]byte, 64*1024)
	scanner.Buffer(buffer, 4*1024*1024)
	blockingFailure := ""
	for scanner.Scan() {
		raw := append(json.RawMessage(nil), scanner.Bytes()...)
		var envelope struct {
			Type     string `json:"type"`
			ThreadID string `json:"thread_id"`
			Item     struct {
				Type             string          `json:"type"`
				Text             string          `json:"text"`
				Status           string          `json:"status"`
				Message          string          `json:"message"`
				Error            json.RawMessage `json:"error"`
				AggregatedOutput string          `json:"aggregated_output"`
			} `json:"item"`
		}
		if err := json.Unmarshal(raw, &envelope); err != nil {
			return fmt.Errorf("decode codex JSONL event: %w", err)
		}
		if envelope.ThreadID != "" {
			result.ProviderSessionID = envelope.ThreadID
		}
		if envelope.Item.Type == "agent_message" && envelope.Item.Text != "" {
			result.Output = envelope.Item.Text
		}
		if blockingFailure == "" && envelope.Item.Status == "failed" {
			failure := codexErrorMessage(envelope.Item.Error)
			if failure == "" {
				failure = envelope.Item.AggregatedOutput
			}
			if codexPermissionFailure(failure) {
				blockingFailure = failure
			}
		}
		if sink != nil {
			if err := sink(provider.Event{Type: envelope.Type, ProviderSessionID: envelope.ThreadID, Raw: raw}); err != nil {
				return err
			}
		}
	}
	if err := scanner.Err(); err != nil {
		return err
	}
	if blockingFailure != "" && (result.Output == "" || codexDescribesBlockedOutcome(result.Output)) {
		return provider.NewExecutionError("provider_permission_denied",
			"Codex could not access the task-scoped collaboration control plane: "+strings.TrimSpace(blockingFailure))
	}
	return nil
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

func codexPermissionFailure(message string) bool {
	message = strings.ToLower(message)
	for _, marker := range []string{
		"operation not permitted", "permission denied", "access denied", "requires approval",
		"approval policy", "not allowed", "权限", "拒绝", "不允许",
	} {
		if strings.Contains(message, marker) {
			return true
		}
	}
	return false
}

func codexDescribesBlockedOutcome(output string) bool {
	output = strings.ToLower(output)
	for _, marker := range []string{
		"blocked", "cannot", "can't", "unable", "permission", "denied", "requires approval",
		"无法", "不能", "权限", "拒绝", "阻塞",
	} {
		if strings.Contains(output, marker) {
			return true
		}
	}
	return false
}

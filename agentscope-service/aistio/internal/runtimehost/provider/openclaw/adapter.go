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

package openclaw

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os/exec"
	"strconv"
	"strings"

	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

// Adapter runs the official isolated OpenClaw headless entry point. agent
// exec deliberately owns a one-shot workspace and therefore does not claim
// provider-session resume support.
type Adapter struct {
	Binary string
}

type configuration struct {
	Model           string   `json:"model,omitempty"`
	Fallbacks       []string `json:"fallbacks,omitempty"`
	Thinking        string   `json:"thinking,omitempty"`
	CodeMode        string   `json:"codeMode,omitempty"`
	TimeoutSeconds  int      `json:"timeoutSeconds,omitempty"`
	LocalModelLean  bool     `json:"localModelLean,omitempty"`
	Isolated        bool     `json:"isolated,omitempty"`
	AuthEnvOnly     bool     `json:"authEnvOnly,omitempty"`
	ReasoningEffort string   `json:"reasoningEffort,omitempty"`
}

func (a *Adapter) Name() string { return "openclaw" }

func (a *Adapter) Descriptor() provider.Descriptor {
	return provider.Descriptor{
		DisplayName:  "OpenClaw",
		Runtime:      "openclaw",
		Instructions: provider.Capability{Supported: true, Mode: "prompt"},
		Workspace:    provider.Capability{Supported: true, Mode: "cwd", Target: "--cwd"},
		Skills:       provider.Capability{Supported: true, Mode: "native-directory", Target: "skills"},
		Tools:        provider.Capability{Supported: true, Mode: "native-policy"},
		Shell:        provider.Capability{Supported: true, Mode: "native", Target: "shell"},
		MCP:          provider.Capability{Supported: false},
		Model:        provider.Capability{Supported: true, Mode: "cli-argument", Target: "--model"},
		CustomArgs:   provider.Capability{Supported: true, Mode: "argv", Target: "openclaw agent exec"},
		Resume:       false,
	}
}

func (a *Adapter) binary() string {
	if a.Binary != "" {
		return a.Binary
	}
	return "openclaw"
}

func (a *Adapter) Detect(ctx context.Context) (string, error) {
	cmd := exec.CommandContext(ctx, a.binary(), "--version")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("openclaw --version: %w: %s", err, strings.TrimSpace(string(out)))
	}
	return strings.TrimSpace(string(out)), nil
}

func (a *Adapter) Run(ctx context.Context, request provider.Request, sink provider.EventSink) (*provider.Result, error) {
	if request.Workspace == "" {
		return nil, fmt.Errorf("OpenClaw workspace is required")
	}
	customArgs, err := provider.ValidateCustomArgs(request.CustomArgs)
	if err != nil {
		return nil, err
	}
	request.CustomArgs = customArgs
	if err := validateRequestCapabilities(request); err != nil {
		return nil, err
	}
	var cfg configuration
	if len(request.Configuration) > 0 {
		if err := json.Unmarshal(request.Configuration, &cfg); err != nil {
			return nil, fmt.Errorf("decode OpenClaw configuration: %w", err)
		}
	}
	cleanupSkills, err := provider.ProjectSkills(request.Workspace, "skills", a.Name())
	if err != nil {
		return nil, fmt.Errorf("project OpenClaw skills: %w", err)
	}
	defer cleanupSkills()
	cmd := exec.CommandContext(ctx, a.binary(), buildArgs(request, cfg)...)
	cmd.Dir = request.Workspace
	cmd.Stdin = strings.NewReader(provider.PrependInstructions(request.Prompt, provider.DefinitionInstructions(request)))
	provider.ApplyTaskEnvironment(cmd, request)
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	runErr := cmd.Run()
	raw := bytes.TrimSpace(stdout.Bytes())
	if len(raw) == 0 {
		if runErr != nil {
			return nil, fmt.Errorf("OpenClaw exited: %w: %s", runErr, strings.TrimSpace(stderr.String()))
		}
		return nil, fmt.Errorf("OpenClaw returned an empty result")
	}
	if sink != nil {
		if err = sink(provider.Event{Type: "result", Raw: append(json.RawMessage(nil), raw...)}); err != nil {
			return nil, err
		}
	}
	result, decodeErr := decodeResult(raw)
	if decodeErr != nil {
		return nil, decodeErr
	}
	if runErr != nil {
		return nil, fmt.Errorf("OpenClaw exited: %w: %s", runErr, strings.TrimSpace(stderr.String()))
	}
	result.Checkpoint, _ = json.Marshal(map[string]string{"providerSessionId": result.ProviderSessionID})
	return result, nil
}

func validateRequestCapabilities(request provider.Request) error {
	if request.ProviderSessionID != "" {
		return fmt.Errorf("OpenClaw agent exec does not support provider session resume")
	}
	if request.Definition == nil || len(request.Definition.MCPServers) == 0 ||
		string(request.Definition.MCPServers) == "null" {
		return nil
	}
	var servers []json.RawMessage
	if err := json.Unmarshal(request.Definition.MCPServers, &servers); err != nil {
		return fmt.Errorf("decode Agent MCP servers: %w", err)
	}
	if len(servers) > 0 {
		return fmt.Errorf("OpenClaw agent exec does not support per-run Agent MCP servers")
	}
	return nil
}

func buildArgs(request provider.Request, cfg configuration) []string {
	args := []string{"agent", "exec", "--cwd", request.Workspace, "--message-file", "-", "--json"}
	model := provider.DefinitionModel(request, cfg.Model)
	if model != "" {
		args = append(args, "--model", model)
		for _, fallback := range cfg.Fallbacks {
			if strings.TrimSpace(fallback) != "" {
				args = append(args, "--fallback", fallback)
			}
		}
	}
	thinking := cfg.Thinking
	if thinking == "" {
		thinking = cfg.ReasoningEffort
	}
	if thinking != "" {
		args = append(args, "--thinking", thinking)
	}
	if cfg.CodeMode != "" {
		args = append(args, "--code-mode", cfg.CodeMode)
	}
	if cfg.TimeoutSeconds > 0 {
		args = append(args, "--timeout", strconv.Itoa(cfg.TimeoutSeconds))
	}
	if cfg.LocalModelLean {
		args = append(args, "--local-model-lean")
	}
	if cfg.Isolated {
		args = append(args, "--isolated")
	}
	if cfg.AuthEnvOnly {
		args = append(args, "--auth-env-only")
	}
	args = append(args, request.CustomArgs...)
	return args
}

func decodeResult(raw []byte) (*provider.Result, error) {
	var envelope struct {
		OK        bool   `json:"ok"`
		Status    string `json:"status"`
		Final     string `json:"final"`
		SessionID string `json:"sessionId"`
		Payloads  []struct {
			Text string `json:"text"`
		} `json:"payloads"`
		Error *struct {
			Message string `json:"message"`
			Kind    string `json:"kind"`
		} `json:"error"`
	}
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return nil, fmt.Errorf("decode OpenClaw JSON result: %w", err)
	}
	if !envelope.OK || (envelope.Status != "" && envelope.Status != "ok") {
		message := envelope.Status
		if envelope.Error != nil && envelope.Error.Message != "" {
			message = envelope.Error.Message
		}
		return nil, fmt.Errorf("OpenClaw run failed: %s", message)
	}
	output := envelope.Final
	if output == "" {
		parts := make([]string, 0, len(envelope.Payloads))
		for _, payload := range envelope.Payloads {
			if payload.Text != "" {
				parts = append(parts, payload.Text)
			}
		}
		output = strings.Join(parts, "\n")
	}
	return &provider.Result{ProviderSessionID: envelope.SessionID, Output: output}, nil
}

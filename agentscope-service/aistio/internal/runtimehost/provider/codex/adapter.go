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
	SkipGitRepoCheck bool   `json:"skipGitRepoCheck,omitempty"`
}

func (a *Adapter) Name() string { return "codex" }

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
	var cfg configuration
	if len(request.Configuration) > 0 {
		if err := json.Unmarshal(request.Configuration, &cfg); err != nil {
			return nil, fmt.Errorf("decode codex configuration: %w", err)
		}
	}
	args := buildArgs(request, cfg)
	cmd := exec.CommandContext(ctx, a.binary(), args...)
	cmd.Dir = request.Workspace
	cmd.Stdin = strings.NewReader(request.Prompt)
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
		return nil, fmt.Errorf("codex exited: %w: %s", waitErr, strings.TrimSpace(stderr.String()))
	}
	checkpoint, _ := json.Marshal(map[string]string{"providerSessionId": result.ProviderSessionID})
	result.Checkpoint = checkpoint
	return result, nil
}

func buildArgs(request provider.Request, cfg configuration) []string {
	args := []string{"exec"}
	if request.ProviderSessionID != "" {
		args = append(args, "resume")
	}
	args = append(args, "--json")
	if cfg.Model != "" {
		args = append(args, "--model", cfg.Model)
	}
	if cfg.Profile != "" {
		args = append(args, "--profile", cfg.Profile)
	}
	if cfg.SkipGitRepoCheck {
		args = append(args, "--skip-git-repo-check")
	}
	if request.ProviderSessionID != "" {
		args = append(args, request.ProviderSessionID, "-")
		return args
	}
	sandbox := cfg.Sandbox
	if sandbox == "" {
		sandbox = "workspace-write"
	}
	args = append(args, "--sandbox", sandbox, "--cd", request.Workspace, "-")
	return args
}

func consumeJSONL(reader io.Reader, result *provider.Result, sink provider.EventSink) error {
	scanner := bufio.NewScanner(reader)
	buffer := make([]byte, 64*1024)
	scanner.Buffer(buffer, 4*1024*1024)
	for scanner.Scan() {
		raw := append(json.RawMessage(nil), scanner.Bytes()...)
		var envelope struct {
			Type     string `json:"type"`
			ThreadID string `json:"thread_id"`
			Item     struct {
				Type string `json:"type"`
				Text string `json:"text"`
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
		if sink != nil {
			if err := sink(provider.Event{Type: envelope.Type, ProviderSessionID: envelope.ThreadID, Raw: raw}); err != nil {
				return err
			}
		}
	}
	return scanner.Err()
}

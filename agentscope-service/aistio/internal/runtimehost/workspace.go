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

package runtimehost

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
)

type taskInput struct {
	Prompt     string `json:"prompt,omitempty"`
	Repository *struct {
		URL string `json:"url"`
		Ref string `json:"ref,omitempty"`
	} `json:"repository,omitempty"`
}

type WorkspaceManager struct {
	Root string
}

func (m *WorkspaceManager) Prepare(ctx context.Context, envelope *collaboration.ContextEnvelope) (path, key, prompt string, err error) {
	if envelope == nil || envelope.Task == nil || envelope.Issue == nil {
		return "", "", "", fmt.Errorf("task context is required")
	}
	task, issue := envelope.Task, envelope.Issue
	root, err := filepath.Abs(m.Root)
	if err != nil {
		return "", "", "", err
	}
	key = filepath.Join(safeSegment(task.Tenant), task.ID.String())
	path = filepath.Join(root, key)
	if err := os.MkdirAll(path, 0o750); err != nil {
		return "", "", "", err
	}
	var input taskInput
	if len(issue.ContextRefs) > 0 {
		_ = json.Unmarshal(issue.ContextRefs, &input)
	}
	if input.Repository != nil && input.Repository.URL != "" {
		if err := materializeRepository(ctx, path, input.Repository.URL, input.Repository.Ref); err != nil {
			return "", "", "", err
		}
	}
	prompt = strings.TrimSpace(input.Prompt)
	if prompt == "" {
		prompt = issue.Title
		if issue.Description != "" {
			prompt += "\n\n" + issue.Description
		}
	}
	for _, routed := range envelope.Inputs {
		if routed.Comment != nil {
			prompt += "\n\nDiscussion input:\n" + routed.Comment.Content
		}
	}
	return path, key, prompt, nil
}

func safeSegment(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return "default"
	}
	value = strings.ReplaceAll(value, "/", "_")
	value = strings.ReplaceAll(value, "\\", "_")
	if value == "." || value == ".." {
		return "default"
	}
	return value
}

func materializeRepository(ctx context.Context, path, url, ref string) error {
	if _, err := os.Stat(filepath.Join(path, ".git")); err == nil {
		return nil
	}
	entries, err := os.ReadDir(path)
	if err != nil {
		return err
	}
	if len(entries) > 0 {
		return fmt.Errorf("workspace %s is non-empty and is not a git repository", path)
	}
	args := []string{"clone"}
	if ref != "" {
		args = append(args, "--branch", ref, "--single-branch")
	}
	args = append(args, "--", url, path)
	cmd := exec.CommandContext(ctx, "git", args...)
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("git clone: %w: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

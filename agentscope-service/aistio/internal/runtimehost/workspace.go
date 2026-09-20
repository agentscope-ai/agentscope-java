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
	return m.PrepareForSession(ctx, envelope, "")
}

// PrepareForSession keeps a Hosted Conversation in one workspace across its
// per-turn AgentTasks. Ordinary Jobs retain task-scoped isolation.
func (m *WorkspaceManager) PrepareForSession(ctx context.Context, envelope *collaboration.ContextEnvelope,
	sessionID string) (path, key, prompt string, err error) {
	return m.PrepareForExecution(ctx, envelope, sessionID, "")
}

// PrepareForExecution reuses the exact workspace selected by a previous turn
// when one is supplied. This is required by providers such as Qoder whose
// native session index is scoped to the working directory. It also preserves
// conversations created by Runtime Hosts that predate stable session paths.
func (m *WorkspaceManager) PrepareForExecution(ctx context.Context, envelope *collaboration.ContextEnvelope,
	sessionID, existingKey string) (path, key, prompt string, err error) {
	if envelope == nil || envelope.Task == nil || envelope.Issue == nil {
		return "", "", "", fmt.Errorf("task context is required")
	}
	task, issue := envelope.Task, envelope.Issue
	root, err := filepath.Abs(m.Root)
	if err != nil {
		return "", "", "", err
	}
	key, err = safeWorkspaceKey(existingKey)
	if err != nil {
		return "", "", "", err
	}
	if key == "" {
		if strings.TrimSpace(sessionID) != "" {
			key = filepath.Join(safeSegment(task.Tenant), safeSegment(task.Namespace), "conversations",
				safeSegment(task.AgentRef), safeSegment(sessionID))
		} else {
			key = filepath.Join(safeSegment(task.Tenant), task.ID.String())
		}
	}
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
	if len(envelope.Inputs) > 0 {
		background, _ := json.Marshal(envelope.RequestContext)
		prompt += "\n\nThe Issue description above is historical background. This is a new comment-triggered assignment, " +
			"not a request to repeat the original Issue. Ancestor requests explain how to interpret a reply; do not execute them again.\n" +
			"Request background: " + string(background) + "\n\nCURRENT REQUEST (latest instructions take precedence):\n" + envelope.CurrentRequest
	}
	if envelope.ReplyToOwnDelegation {
		prompt += "\n\nThis is the result of work you previously delegated. Evaluate the answer and finish according to these initiating instructions:\n" + envelope.InitiatingRequest +
			"\nDo not mention the responder merely to acknowledge their answer: that schedules another task. Use task.complete for the final delivery."
	}
	if task.LeaderTask {
		prompt += "\n\nAfter asking a worker for follow-up work with an explicit mention, call task.complete(outcome=waiting) to yield. This ends only your turn and preserves the worker. Waiting for a worker is not failure. Use run.node.fail only to explicitly abort the entire coordinator and its remaining work. Before ending the coordinator, summarize completed work, unfinished work, the reason for the final status, and the next action. Include this summary in run.node.complete output or run.node.fail message; it will be published on the main Issue before its status changes."
	}
	if task.LeaderTask && len(envelope.CoordinatorChildren) > 0 {
		prompt += "\n\nCoordinator child outcomes (synthesize all of these before completing the coordinator):"
		for _, child := range envelope.CoordinatorChildren {
			if child.Issue == nil {
				continue
			}
			prompt += "\n\n- " + child.Issue.Title + " [" + child.Issue.ID.String() + ", " + string(child.Issue.Status) + "]"
			for _, result := range child.Results {
				if result != nil {
					prompt += "\n  Result: " + result.Content
				}
			}
		}
	}
	return path, key, prompt, nil
}

func safeWorkspaceKey(value string) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return "", nil
	}
	key := filepath.Clean(value)
	if filepath.IsAbs(key) || key == "." || key == ".." ||
		strings.HasPrefix(key, ".."+string(filepath.Separator)) {
		return "", fmt.Errorf("workspace key %q is outside the workspace root", value)
	}
	return key, nil
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

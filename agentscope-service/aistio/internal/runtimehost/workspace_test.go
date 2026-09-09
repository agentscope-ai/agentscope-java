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

package runtimehost

import (
	"context"
	"path/filepath"
	"strings"
	"testing"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

func TestWorkspacePromptDoesNotAssumeProviderCapabilities(t *testing.T) {
	task := &controlmodel.AgentTask{Tenant: "tenant"}
	_, _, prompt, err := (&WorkspaceManager{Root: t.TempDir()}).Prepare(context.Background(),
		&collaboration.ContextEnvelope{Task: task, Issue: &controlmodel.Issue{Title: "implement feature"}})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(prompt, "agentscope-collaboration") {
		t.Fatalf("workspace prompt assumes MCP support: %s", prompt)
	}
}

func TestConversationWorkspaceIsStableAcrossTurnTasks(t *testing.T) {
	root := t.TempDir()
	manager := &WorkspaceManager{Root: root}
	makeEnvelope := func(taskID uuid.UUID) *collaboration.ContextEnvelope {
		return &collaboration.ContextEnvelope{
			Task: &controlmodel.AgentTask{ID: taskID, Tenant: "tenant", Namespace: "default",
				AgentRef: "agent-1"},
			Issue: &controlmodel.Issue{Title: "turn"},
		}
	}
	firstPath, firstKey, _, err := manager.PrepareForSession(context.Background(), makeEnvelope(uuid.New()), "session-1")
	if err != nil {
		t.Fatal(err)
	}
	secondPath, secondKey, _, err := manager.PrepareForSession(context.Background(), makeEnvelope(uuid.New()), "session-1")
	if err != nil {
		t.Fatal(err)
	}
	if firstPath != secondPath || firstKey != secondKey {
		t.Fatalf("conversation workspace changed between turns: %q/%q vs %q/%q",
			firstPath, firstKey, secondPath, secondKey)
	}
	jobPath, _, _, err := manager.Prepare(context.Background(), makeEnvelope(uuid.New()))
	if err != nil {
		t.Fatal(err)
	}
	if jobPath == firstPath {
		t.Fatal("ordinary Job workspace must remain task-scoped")
	}
}

func TestConversationWorkspaceReusesLegacyTaskWorkspace(t *testing.T) {
	root := t.TempDir()
	manager := &WorkspaceManager{Root: root}
	envelope := &collaboration.ContextEnvelope{
		Task: &controlmodel.AgentTask{ID: uuid.New(), Tenant: "tenant", Namespace: "default",
			AgentRef: "agent-1"},
		Issue: &controlmodel.Issue{Title: "turn"},
	}
	legacyKey := filepath.Join("tenant", uuid.NewString())
	path, key, _, err := manager.PrepareForExecution(context.Background(), envelope,
		"session-1", legacyKey)
	if err != nil {
		t.Fatal(err)
	}
	if key != legacyKey || path != filepath.Join(root, legacyKey) {
		t.Fatalf("legacy workspace was not reused: path=%q key=%q", path, key)
	}
}

func TestConversationWorkspaceRejectsEscapingPersistedKey(t *testing.T) {
	manager := &WorkspaceManager{Root: t.TempDir()}
	envelope := &collaboration.ContextEnvelope{
		Task:  &controlmodel.AgentTask{ID: uuid.New(), Tenant: "tenant"},
		Issue: &controlmodel.Issue{Title: "turn"},
	}
	if _, _, _, err := manager.PrepareForExecution(context.Background(), envelope,
		"session-1", filepath.Join("..", "escape")); err == nil {
		t.Fatal("escaping persisted workspace key was accepted")
	}
}

func TestReturningDelegationPromptPreservesFinalDeliveryInstructions(t *testing.T) {
	_, _, prompt, err := (&WorkspaceManager{Root: t.TempDir()}).Prepare(context.Background(), &collaboration.ContextEnvelope{
		Task: &controlmodel.AgentTask{Tenant: "t"}, Issue: &controlmodel.Issue{Title: "original"}, ReplyToOwnDelegation: true,
		InitiatingRequest: "Verify the answer and deliver the final summary", CurrentRequest: "worker answer",
	})
	if err != nil || !strings.Contains(prompt, "Verify the answer and deliver the final summary") || !strings.Contains(prompt, "Do not mention the responder") {
		t.Fatalf("returning delegation lost instructions: %s %v", prompt, err)
	}
}

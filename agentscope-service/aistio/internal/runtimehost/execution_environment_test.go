// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package runtimehost

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

func TestMaterializeDefinitionFilesInIsolatedContext(t *testing.T) {
	workspace := t.TempDir()
	root, err := MaterializeDefinition(workspace, &provider.AgentDefinition{Files: map[string]string{
		"AGENTS.md":              "Review carefully.",
		"skills/review/SKILL.md": "Use the review checklist.",
	}})
	if err != nil {
		t.Fatal(err)
	}
	if root != definitionDirectory {
		t.Fatalf("root=%q", root)
	}
	data, err := os.ReadFile(filepath.Join(workspace, definitionDirectory, "skills", "review", "SKILL.md"))
	if err != nil || string(data) != "Use the review checklist." {
		t.Fatalf("skill=%q err=%v", data, err)
	}
	if _, err = os.Stat(filepath.Join(workspace, "AGENTS.md")); !os.IsNotExist(err) {
		t.Fatalf("repository AGENTS.md must not be overwritten: %v", err)
	}
}

func TestMaterializeDefinitionRejectsTraversal(t *testing.T) {
	_, err := MaterializeDefinition(t.TempDir(), &provider.AgentDefinition{Files: map[string]string{
		"../secret": "nope",
	}})
	if err == nil {
		t.Fatal("expected traversal path to be rejected")
	}
}

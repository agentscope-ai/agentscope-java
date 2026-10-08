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

package provider

import (
	"os"
	"path/filepath"
	"testing"
)

func TestProjectSkillsCopiesAndCleansProviderNativeDirectory(t *testing.T) {
	workspace := t.TempDir()
	source := filepath.Join(workspace, ".agentscope", "definition", "skills", "review")
	if err := os.MkdirAll(filepath.Join(source, "scripts"), 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(source, "SKILL.md"), []byte("---\nname: review\n---\n"), 0o640); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(source, "scripts", "check.sh"), []byte("#!/bin/sh\n"), 0o750); err != nil {
		t.Fatal(err)
	}
	cleanup, err := ProjectSkills(workspace, ".agents/skills", "codex")
	if err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(workspace, ".agents", "skills", "review")
	if data, readErr := os.ReadFile(filepath.Join(target, "SKILL.md")); readErr != nil || len(data) == 0 {
		t.Fatalf("projected skill=%q err=%v", data, readErr)
	}
	if info, statErr := os.Stat(filepath.Join(target, "scripts", "check.sh")); statErr != nil || info.Mode()&0o111 == 0 {
		t.Fatalf("script mode=%v err=%v", info, statErr)
	}
	cleanup()
	if _, err = os.Stat(target); !os.IsNotExist(err) {
		t.Fatalf("projection was not cleaned: %v", err)
	}
}

func TestProjectSkillsPreservesRepositoryOwnedCollision(t *testing.T) {
	workspace := t.TempDir()
	source := filepath.Join(workspace, ".agentscope", "definition", "skills", "review")
	target := filepath.Join(workspace, ".claude", "skills", "review")
	for _, path := range []string{source, target} {
		if err := os.MkdirAll(path, 0o750); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(source, "SKILL.md"), []byte("portable"), 0o640); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(target, "SKILL.md"), []byte("repository"), 0o640); err != nil {
		t.Fatal(err)
	}
	if _, err := ProjectSkills(workspace, ".claude/skills", "claude-code"); err == nil {
		t.Fatal("expected repository-owned skill collision")
	}
	if data, err := os.ReadFile(filepath.Join(target, "SKILL.md")); err != nil || string(data) != "repository" {
		t.Fatalf("repository skill was modified: %q err=%v", data, err)
	}
}

func TestProjectSkillsCleanupPreservesProjectionWithChangedOwnership(t *testing.T) {
	workspace := t.TempDir()
	source := filepath.Join(workspace, ".agentscope", "definition", "skills", "review")
	if err := os.MkdirAll(source, 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(source, "SKILL.md"), []byte("portable"), 0o640); err != nil {
		t.Fatal(err)
	}
	cleanup, err := ProjectSkills(workspace, ".agents/skills", "codex")
	if err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(workspace, ".agents", "skills", "review")
	if err = os.WriteFile(filepath.Join(target, projectionMarker),
		[]byte(`{"owner":"repository","source":"repository"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	cleanup()
	if _, err = os.Stat(filepath.Join(target, "SKILL.md")); err != nil {
		t.Fatalf("changed projection ownership was removed: %v", err)
	}
}

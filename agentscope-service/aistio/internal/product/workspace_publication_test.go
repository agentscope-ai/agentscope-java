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
package product

import (
	"encoding/json"
	"os"
	"strings"
	"testing"

	"github.com/google/uuid"
)

func TestWorkspacePublishedBindingIsolation(t *testing.T) {
	dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set")
	}
	db, err := openDB(t.Context(), dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err = migrate(t.Context(), db); err != nil {
		t.Fatal(err)
	}
	s := &Server{db: db, cfg: Config{WorkspaceRoot: t.TempDir()}}
	owner, id, agent := "namespace:test:"+uuid.NewString(), uuid.NewString(), uuid.NewString()
	_, err = db.Pool.Exec(t.Context(), `INSERT INTO workspaces(owner_id,workspace_id,name,tools_json,mcp_servers_json,skills_json,head_version,created_at,updated_at) VALUES($1,$2,'Pack','[]','[]','[]',1,0,0)`, owner, id)
	if err != nil {
		t.Fatal(err)
	}
	put := func(path, content string) {
		t.Helper()
		if err = s.putWorkspaceFile(t.Context(), owner, scopeTypeWorkspace, id, path, content, ""); err != nil {
			t.Fatal(err)
		}
	}
	put("AGENTS.md", "workspace one")
	put("skills/review/SKILL.md", "review one")
	put("memory/private.md", "must not escape")
	put("sessions/user.log.jsonl", "private transcript")
	first, err := s.publishWorkspace(t.Context(), owner, id)
	if err != nil {
		t.Fatal(err)
	}
	if first.Version != 1 || first.Files["AGENTS.md"] != "workspace one" || len(first.Files) != 2 {
		t.Fatalf("invalid published definition: %+v", first)
	}
	duplicate, err := s.publishWorkspace(t.Context(), owner, id)
	if err != nil || duplicate.Version != 1 {
		t.Fatalf("unchanged publication: %+v %v", duplicate, err)
	}
	if _, err = s.workspaceRevision(t.Context(), "namespace:another", id, 1); err == nil {
		t.Fatal("cross-namespace revision was readable")
	}
	created, err := s.EnsureManagedDefinition(t.Context(), owner, agent, ManagedDefinitionInput{Name: "Worker", WorkspaceID: id, WorkspaceBinding: &WorkspaceBinding{Version: 1, Overrides: []string{}, Instructions: "specialize"}})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(created["system"].(string), "workspace one") || !strings.Contains(created["system"].(string), "specialize") {
		t.Fatalf("instructions: %v", created)
	}
	var input ManagedDefinitionInput
	if err = json.Unmarshal([]byte(mustJSON(created)), &input); err != nil {
		t.Fatal(err)
	}
	put("AGENTS.md", "workspace two")
	put("skills/review/SKILL.md", "review two")
	if err = s.bumpWorkspaceVersion(t.Context(), owner, id); err != nil {
		t.Fatal(err)
	}
	s.rematerializeLinkedAgents(t.Context(), owner, id)
	current, err := s.ManagedDefinition(t.Context(), owner, agent)
	if err != nil {
		t.Fatal(err)
	}
	if current["version"] != 1 {
		t.Fatal("Workspace draft changed bound Agent version")
	}
	published, err := s.publishWorkspace(t.Context(), owner, id)
	if err != nil || published.Version != 2 {
		t.Fatalf("second publish: %+v %v", published, err)
	}
	frozen, err := s.RuntimeDefinition(t.Context(), owner, agent)
	if err != nil {
		t.Fatal(err)
	}
	files := frozen["files"].(map[string]any)
	if files["AGENTS.md"] != "workspace one" || frozen["workspaceVersion"] != float64(1) {
		t.Fatal("runtime used new Workspace draft")
	}
	input.Description = "unrelated change"
	updated, err := s.UpdateManagedDefinition(t.Context(), owner, agent, input, 1)
	if err != nil {
		t.Fatal(err)
	}
	if updated["workspaceVersion"] != float64(1) {
		t.Fatal("unrelated edit changed Workspace revision")
	}
	if _, err = s.UpdateManagedDefinition(t.Context(), owner, agent, input, 1); err != ErrManagedDefinitionConflict {
		t.Fatalf("stale edit: %v", err)
	}
	input.WorkspaceBinding.Version = 2
	input.WorkspaceBinding.Overrides = []string{"skills"}
	input.Skills = []any{}
	updated, err = s.UpdateManagedDefinition(t.Context(), owner, agent, input, 2)
	if err != nil {
		t.Fatal(err)
	}
	if updated["workspaceVersion"] != float64(2) || !strings.Contains(updated["system"].(string), "workspace two") || strings.Count(updated["system"].(string), "specialize") != 1 {
		t.Fatalf("rebind: %v", updated)
	}
	old, err := s.definitionSnapshot(t.Context(), owner, agent, 1)
	if err != nil {
		t.Fatal(err)
	}
	if old["definitionFiles"].(map[string]any)["AGENTS.md"] != "workspace one" {
		t.Fatal("published Agent history mutated")
	}
	input.WorkspaceBinding.Overrides = []string{"unknown"}
	if _, err = s.UpdateManagedDefinition(t.Context(), owner, agent, input, 3); err == nil {
		t.Fatal("accepted unknown override")
	}
}

func TestDefinitionFilesExcludeRuntimeState(t *testing.T) {
	for _, path := range []string{"MEMORY.md", "memory/2026.md", "sessions/a.log.jsonl", "logs/a.txt", "inputs/customer.txt", "outputs/report.txt", "artifacts/result", "../secret"} {
		if definitionFile(path) {
			t.Errorf("runtime file accepted: %s", path)
		}
	}
	for _, path := range []string{"AGENTS.md", "skills/review/SKILL.md", "skills/review/scripts/check.py", "subagents/reviewer.md", "reference/api.md"} {
		if !definitionFile(path) {
			t.Errorf("definition file rejected: %s", path)
		}
	}
}

func TestRuntimeSkillProjection(t *testing.T) {
	definition := map[string]any{
		"skills":          []any{map[string]any{"name": "review"}},
		"definitionFiles": map[string]any{"AGENTS.md": "instructions", "skills/review/SKILL.md": "enabled", "skills/disabled/SKILL.md": "disabled", "memory/private": "secret"},
	}
	files := enabledDefinitionFiles(definition)
	if len(files) != 2 || files["skills/review/SKILL.md"] != "enabled" {
		t.Fatalf("projection: %v", files)
	}
	definition["skills"] = []any{}
	if files = enabledDefinitionFiles(definition); len(files) != 1 {
		t.Fatalf("disabled skills leaked: %v", files)
	}
	if len(definition["definitionFiles"].(map[string]any)) != 4 {
		t.Fatal("projection mutated revision")
	}
}

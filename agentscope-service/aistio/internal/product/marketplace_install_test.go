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

package product

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func TestMarketplaceSkillResourceValidation(t *testing.T) {
	for _, invalid := range []string{"symlink", "binary", "missing body"} {
		t.Run(invalid, func(t *testing.T) {
			root := t.TempDir()
			dir := filepath.Join(root, "skills", "review")
			if err := os.MkdirAll(dir, 0o755); err != nil {
				t.Fatal(err)
			}
			if invalid != "missing body" {
				if err := os.WriteFile(filepath.Join(dir, "SKILL.md"), []byte("# Review"), 0o644); err != nil {
					t.Fatal(err)
				}
			}
			switch invalid {
			case "symlink":
				if err := os.Symlink(filepath.Join(dir, "SKILL.md"), filepath.Join(dir, "reference")); err != nil {
					t.Fatal(err)
				}
			case "binary":
				if err := os.WriteFile(filepath.Join(dir, "image.bin"), []byte{0xff, 0}, 0o644); err != nil {
					t.Fatal(err)
				}
			}
			if _, err := readMarketplaceSkillDirectory(root, "skills/review"); err == nil {
				t.Fatal("accepted incomplete or unsafe source")
			}
		})
	}
	s := &Server{}
	config := `{"skillNames":["review"],"serverAddr":"example.test","password":"must-not-be-copied"}`
	if _, _, err := s.marketplaceSkillContents(t.Context(), marketplaceRow{Type: "nacos", ConfigJSON: &config}, "review"); err == nil {
		t.Fatal("installed placeholder instead of Nacos content")
	}
}

func TestMarketplaceInstallAtomicConflictAndProvenance(t *testing.T) {
	dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set")
	}
	db, err := openDB(t.Context(), dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	if err := migrate(t.Context(), db); err != nil {
		t.Fatal(err)
	}
	s := &Server{db: db, cfg: DefaultConfig()}
	s.cfg.WorkspaceRoot = t.TempDir()
	owner, workspace := shortID("market-test-"), shortID("workspace-")
	if _, err := db.Pool.Exec(t.Context(), `INSERT INTO workspaces(owner_id,workspace_id,name,skills_json,created_at,updated_at) VALUES($1,$2,'Test','[]',0,0)`, owner, workspace); err != nil {
		t.Fatal(err)
	}
	disk := s.workspaceDiskRoot(owner, workspace)
	m := marketplaceRow{MarketplaceID: "source", Name: "Team skills", Type: "git"}
	contents := func() map[string]string {
		return map[string]string{"SKILL.md": "---\nname: review\n---\nReview API", "references/api.md": "Reference content"}
	}
	var outcomes [2]error
	var wg sync.WaitGroup
	for index := range outcomes {
		wg.Go(func() {
			outcomes[index] = s.persistMarketplaceSkill(t.Context(), owner, scopeTypeWorkspace, workspace, disk, m, "review", "revision-1", contents())
		})
	}
	wg.Wait()
	successes, conflicts := 0, 0
	for _, err := range outcomes {
		if err == nil {
			successes++
		} else if errors.Is(err, errSkillAlreadyInstalled) {
			conflicts++
		} else {
			t.Fatal(err)
		}
	}
	if successes != 1 || conflicts != 1 {
		t.Fatalf("concurrent outcomes=%v", outcomes)
	}
	files, err := s.listWorkspaceFileContents(t.Context(), owner, scopeTypeWorkspace, workspace, "skills")
	if err != nil {
		t.Fatal(err)
	}
	info := skillSourceInfo(files, "review")
	if info["origin"] != "marketplace" || info["modified"] != false {
		t.Fatalf("provenance=%+v", info)
	}
	if info["marketplace"].(installedSkillSource).Version != "revision-1" {
		t.Fatal("lost exact revision")
	}
	files["skills/review/references/api.md"] = "Local edit"
	if skillSourceInfo(files, "review")["modified"] != true {
		t.Fatal("resource edit not detected")
	}
	w, err := s.loadWorkspace(t.Context(), owner, workspace)
	if err != nil {
		t.Fatal(err)
	}
	var refs []map[string]any
	if err := json.Unmarshal([]byte(deref(w.SkillsJSON)), &refs); err != nil || len(refs) != 1 {
		t.Fatalf("duplicate or missing refs=%v err=%v", refs, err)
	}
	if data, err := os.ReadFile(filepath.Join(disk, "skills/review/references/api.md")); err != nil || string(data) != "Reference content" {
		t.Fatalf("disk mirror=%q err=%v", data, err)
	}
	// A late failure after beginning insertion must leave neither partial rows
	// nor a published directory, and the next attempt remains installable.
	bad := contents()
	bad["../escape"] = "invalid"
	if err := s.persistMarketplaceSkill(t.Context(), owner, scopeTypeWorkspace, workspace, disk, m, "retry", "revision-1", bad); err == nil {
		t.Fatal("accepted invalid resource path")
	}
	partial, err := s.listWorkspaceFileContents(t.Context(), owner, scopeTypeWorkspace, workspace, "skills/retry")
	if err != nil || len(partial) != 0 {
		t.Fatalf("partial files=%v err=%v", partial, err)
	}
	if _, err := os.Stat(filepath.Join(disk, "skills/retry")); !os.IsNotExist(err) {
		t.Fatalf("partial disk mirror remains: %v", err)
	}
	if err := s.persistMarketplaceSkill(t.Context(), owner, scopeTypeWorkspace, workspace, disk, m, "retry", "revision-1", contents()); err != nil {
		t.Fatalf("retry failed: %v", err)
	}
	// SQL wildcard characters in a skill name must never match another skill.
	for _, name := range []string{"review_one", "reviewXone", "review%two", "reviewYtwo"} {
		if err := s.persistMarketplaceSkill(t.Context(), owner, scopeTypeWorkspace, workspace, disk, m, name, "revision-1", contents()); err != nil {
			t.Fatal(err)
		}
	}
	for _, name := range []string{"review_one", "review%two"} {
		entries, err := s.listWorkspaceFilePaths(t.Context(), owner, scopeTypeWorkspace, workspace, "skills/"+name)
		if err != nil || len(entries) != 3 {
			t.Fatalf("listing %s includes neighbors: %v %v", name, entries, err)
		}
		if err := s.deleteWorkspaceFilePrefix(t.Context(), owner, scopeTypeWorkspace, workspace, "skills/"+name, disk); err != nil {
			t.Fatal(err)
		}
	}
	for _, name := range []string{"reviewXone", "reviewYtwo"} {
		content, found, err := s.getWorkspaceFile(t.Context(), owner, scopeTypeWorkspace, workspace, "skills/"+name+"/SKILL.md")
		if err != nil || !found || content == "" {
			t.Fatalf("uninstall removed neighbor %s: %v", name, err)
		}
		if _, err := os.Stat(filepath.Join(disk, "skills", name, "SKILL.md")); err != nil {
			t.Fatal(err)
		}
	}

}

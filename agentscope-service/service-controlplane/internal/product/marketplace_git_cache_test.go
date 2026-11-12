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
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func marketplaceTestGit(t *testing.T, dir string, args ...string) string {
	t.Helper()
	cmd := exec.CommandContext(t.Context(), "git", args...)
	cmd.Dir = dir
	output, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("git %v: %v\n%s", args, err, output)
	}
	return strings.TrimSpace(string(output))
}

func marketplaceGitFixture(t *testing.T) (*Server, marketplaceRow, string) {
	t.Helper()
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git executable required")
	}
	t.Setenv("GIT_CONFIG_GLOBAL", os.DevNull)
	t.Setenv("GIT_CONFIG_NOSYSTEM", "1")
	t.Setenv("GIT_TERMINAL_PROMPT", "0")
	remote := t.TempDir()
	marketplaceTestGit(t, remote, "init", "-b", "main")
	marketplaceTestGit(t, remote, "config", "user.name", "Marketplace Test")
	marketplaceTestGit(t, remote, "config", "user.email", "marketplace@example.test")
	marketplaceCommitSkill(t, remote, "# Review v1\n")
	s := &Server{cfg: DefaultConfig()}
	s.cfg.WorkspaceRoot = t.TempDir()
	config := mustJSON(map[string]any{"remoteUrl": remote})
	m := marketplaceRow{OwnerID: "owner", MarketplaceID: "source", Type: "git", ConfigJSON: &config}
	return s, m, remote
}

func marketplaceCommitSkill(t *testing.T, remote, content string) {
	t.Helper()
	dir := filepath.Join(remote, "skills", "review")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "SKILL.md"), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	marketplaceTestGit(t, remote, "add", ".")
	marketplaceTestGit(t, remote, "commit", "-m", "Update skill")
}

func TestGitMarketplaceCacheReuse(t *testing.T) {
	for _, separateGitDir := range []bool{false, true} {
		name := "directory"
		if separateGitDir {
			name = "gitfile"
		}
		t.Run(name, func(t *testing.T) {
			s, m, remote := marketplaceGitFixture(t)
			cache := filepath.Join(s.cfg.WorkspaceRoot, "_marketplaces", m.OwnerID, m.MarketplaceID)
			if separateGitDir {
				if err := os.MkdirAll(filepath.Dir(cache), 0o755); err != nil {
					t.Fatal(err)
				}
				marketplaceTestGit(t, remote, "clone", "--separate-git-dir", filepath.Join(t.TempDir(), "git"), remote, cache)
			}
			for attempt := 0; attempt < 2; attempt++ {
				skills, err := s.listMarketplaceSkillNames(t.Context(), m)
				if err != nil || len(skills) != 1 || skills[0]["dirName"] != "review" {
					t.Fatalf("browse %d: skills=%v, err=%v", attempt, skills, err)
				}
			}
			files, version, err := s.marketplaceSkillContents(t.Context(), m, "review")
			if err != nil || files["SKILL.md"] != "# Review v1\n" || version == "" {
				t.Fatalf("install source after browse: files=%v, version=%q, err=%v", files, version, err)
			}
			marketplaceCommitSkill(t, remote, "# Review v2\n")
			files, refreshedVersion, err := s.marketplaceSkillContents(t.Context(), m, "review")
			if err != nil || files["SKILL.md"] != "# Review v2\n" || refreshedVersion == version {
				t.Fatalf("refresh: files=%v, version=%q, err=%v", files, refreshedVersion, err)
			}
			if refreshedVersion != marketplaceTestGit(t, remote, "rev-parse", "HEAD") {
				t.Fatal("cache did not advance to remote commit")
			}
		})
	}
}

func TestGitMarketplaceInvalidCachePreserved(t *testing.T) {
	for _, marker := range []string{"absent", "directory", "file"} {
		t.Run(marker, func(t *testing.T) {
			s, m, _ := marketplaceGitFixture(t)
			cache := filepath.Join(s.cfg.WorkspaceRoot, "_marketplaces", m.OwnerID, m.MarketplaceID)
			if err := os.MkdirAll(cache, 0o755); err != nil {
				t.Fatal(err)
			}
			sentinel := filepath.Join(cache, "preserve.txt")
			if err := os.WriteFile(sentinel, []byte("preserve"), 0o644); err != nil {
				t.Fatal(err)
			}
			switch marker {
			case "directory":
				if err := os.Mkdir(filepath.Join(cache, ".git"), 0o755); err != nil {
					t.Fatal(err)
				}
			case "file":
				if err := os.WriteFile(filepath.Join(cache, ".git"), []byte("invalid"), 0o644); err != nil {
					t.Fatal(err)
				}
			}
			_, err := s.ensureGitMarketplaceClone(t.Context(), m, marketplaceConfigMap(m))
			operation := "refresh"
			if marker == "absent" {
				operation = "clone"
			}
			if err == nil || !strings.Contains(err.Error(), "marketplace "+operation+" failed") {
				t.Fatalf("expected %s failure, got %v", operation, err)
			}
			if data, err := os.ReadFile(sentinel); err != nil || string(data) != "preserve" {
				t.Fatalf("existing cache contents changed: %q, %v", data, err)
			}
		})
	}
}

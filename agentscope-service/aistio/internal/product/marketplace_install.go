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
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
)

const marketplaceMetadataFile = ".marketplace.json"

var errSkillAlreadyInstalled = errors.New("a skill with this name already exists; preserve local edits, then remove it before installing another version")

func marketplaceInstallStatus(err error) int {
	if errors.Is(err, errSkillAlreadyInstalled) {
		return http.StatusConflict
	}
	return http.StatusBadRequest
}

type installedSkillSource struct {
	MarketplaceID string `json:"marketplaceId"`
	RepoType      string `json:"repoType"`
	RepoLocation  string `json:"repoLocation"`
	OriginalName  string `json:"originalName"`
	InstalledAt   string `json:"installedAt"`
	Version       string `json:"version"`
	Digest        string `json:"digest"`
}

func skillFilesDigest(files map[string]string) string {
	content := make(map[string]string, len(files))
	for name, body := range files {
		if name != marketplaceMetadataFile {
			content[name] = body
		}
	}
	data, _ := json.Marshal(content)
	return fmt.Sprintf("%x", sha256.Sum256(data))
}

func skillSourceInfo(files map[string]string, name string) gin.H {
	result := gin.H{"origin": "custom"}
	prefix := "skills/" + name + "/"
	var metadata installedSkillSource
	if json.Unmarshal([]byte(files[prefix+marketplaceMetadataFile]), &metadata) != nil || metadata.MarketplaceID == "" || metadata.Digest == "" {
		return result
	}
	skillFiles := map[string]string{}
	for filename, content := range files {
		if strings.HasPrefix(filename, prefix) {
			skillFiles[strings.TrimPrefix(filename, prefix)] = content
		}
	}
	result["origin"], result["marketplace"], result["modified"] = "marketplace", metadata, skillFilesDigest(skillFiles) != metadata.Digest
	return result
}

func validSkillDirectory(name string) bool {
	return name != "" && name != "." && !strings.Contains(name, "..") && !strings.ContainsAny(name, "/\\\x00") && !strings.HasPrefix(name, ".")
}

// Read all resources first. workspace_files stores UTF-8 text, so reject
// unsupported binary assets rather than installing an incomplete skill.
func readMarketplaceSkillDirectory(root, relative string) (map[string]string, error) {
	clean, err := cleanRelPath(relative)
	if err != nil || clean == "" {
		return nil, errPathTraversal
	}
	current := root
	for _, component := range strings.Split(filepath.ToSlash(clean), "/") {
		current = filepath.Join(current, component)
		info, err := os.Lstat(current)
		if err != nil {
			return nil, err
		}
		if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return nil, fmt.Errorf("marketplace skill directory must not contain symbolic links")
		}
	}
	files := map[string]string{}
	err = filepath.WalkDir(current, func(filename string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() {
			return nil
		}
		if !entry.Type().IsRegular() {
			return fmt.Errorf("unsupported skill resource %s", entry.Name())
		}
		rel, err := filepath.Rel(current, filename)
		if err != nil {
			return err
		}
		if rel == marketplaceMetadataFile {
			return nil
		}
		data, err := os.ReadFile(filename)
		if err != nil {
			return err
		}
		if !utf8.Valid(data) || strings.ContainsRune(string(data), '\x00') {
			return fmt.Errorf("skill resource %s is not supported UTF-8 text", rel)
		}
		files[filepath.ToSlash(rel)] = string(data)
		return nil
	})
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(files["SKILL.md"]) == "" {
		return nil, fmt.Errorf("marketplace skill requires a non-empty SKILL.md")
	}
	return files, nil
}

func (s *Server) marketplaceSkillContents(ctx context.Context, m marketplaceRow, name string) (map[string]string, string, error) {
	cfg := marketplaceConfigMap(m)
	switch m.Type {
	case "git":
		root, err := s.ensureGitMarketplaceClone(ctx, m, cfg)
		if err != nil {
			return nil, "", err
		}
		skillsRoot, _ := cfg["skillsRoot"].(string)
		if skillsRoot == "" {
			skillsRoot = "skills"
		}
		if _, err := cleanRelPath(skillsRoot); err != nil {
			return nil, "", err
		}
		files, err := readMarketplaceSkillDirectory(root, skillsRoot+"/"+name)
		if err != nil {
			return nil, "", err
		}
		commit, err := exec.CommandContext(ctx, "git", "-C", root, "rev-parse", "HEAD").Output()
		if err != nil {
			return nil, "", fmt.Errorf("read marketplace revision: %w", err)
		}
		return files, strings.TrimSpace(string(commit)), nil
	case "nacos":
		bodies, _ := cfg["skillBodies"].(map[string]any)
		body, _ := bodies[name].(string)
		if strings.TrimSpace(body) == "" {
			return nil, "", fmt.Errorf("Nacos skill content is unavailable; configure skillBodies or use a Git source before installing")
		}
		files := map[string]string{"SKILL.md": body}
		return files, skillFilesDigest(files), nil
	default:
		return nil, "", fmt.Errorf("unsupported marketplace type: %s", m.Type)
	}
}

// Install files, source metadata and the definition reference in one database
// transaction. Stage the disk mirror separately so a failed write is retryable.
func (s *Server) persistMarketplaceSkill(ctx context.Context, owner, scopeType, scopeID, diskRoot string, m marketplaceRow, name, version string, files map[string]string) error {
	metadata := installedSkillSource{MarketplaceID: m.MarketplaceID, RepoType: m.Type, RepoLocation: m.Name, OriginalName: name, InstalledAt: time.Now().UTC().Format(time.RFC3339), Version: version, Digest: skillFilesDigest(files)}
	encoded, err := json.Marshal(metadata)
	if err != nil {
		return err
	}
	files[marketplaceMetadataFile] = string(encoded)
	tx, err := s.db.Pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(context.WithoutCancel(ctx))
	table, idColumn := "workspaces", "workspace_id"
	if scopeType == scopeTypeAgent {
		table, idColumn = "agents", "agent_id"
	} else if scopeType != scopeTypeWorkspace {
		return fmt.Errorf("invalid definition scope")
	}
	var previous *string
	if err := tx.QueryRow(ctx, "SELECT skills_json FROM "+table+" WHERE owner_id=$1 AND "+idColumn+"=$2 FOR UPDATE", owner, scopeID).Scan(&previous); err != nil {
		return err
	}
	prefix := "skills/" + name + "/"
	var exists bool
	if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM workspace_files WHERE owner_id=$1 AND scope_type=$2 AND scope_id=$3 AND starts_with(path,$4))`, owner, scopeType, scopeID, prefix).Scan(&exists); err != nil {
		return err
	}
	if exists {
		return errSkillAlreadyInstalled
	}
	var staged, target string
	if diskRoot != "" {
		root, err := joinWorkspace(diskRoot, "skills")
		if err != nil {
			return err
		}
		if info, err := os.Lstat(root); err == nil && info.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("skills directory must not be a symbolic link")
		}
		if err := os.MkdirAll(root, 0o755); err != nil {
			return err
		}
		target = filepath.Join(root, name)
		if _, err := os.Lstat(target); err == nil {
			return errSkillAlreadyInstalled
		} else if !os.IsNotExist(err) {
			return err
		}
		staged, err = os.MkdirTemp(root, ".install-")
		if err != nil {
			return err
		}
		defer os.RemoveAll(staged)
	}
	for relative, content := range files {
		clean, err := cleanRelPath(relative)
		if err != nil || clean == "" {
			return errPathTraversal
		}
		if _, err := tx.Exec(ctx, `INSERT INTO workspace_files(owner_id,scope_type,scope_id,path,content,updated_at) VALUES($1,$2,$3,$4,$5,$6)`, owner, scopeType, scopeID, prefix+clean, content, nowMillis()); err != nil {
			return err
		}
		if staged != "" {
			filename := filepath.Join(staged, filepath.FromSlash(clean))
			if err := os.MkdirAll(filepath.Dir(filename), 0o755); err != nil {
				return err
			}
			if err := os.WriteFile(filename, []byte(content), 0o644); err != nil {
				return err
			}
		}
	}
	refs, _ := parseJSONRaw(deref(previous)).([]any)
	next := make([]any, 0, len(refs)+1)
	for _, ref := range refs {
		value, _ := ref.(map[string]any)
		if value["name"] != name && value["id"] != name {
			next = append(next, ref)
		}
	}
	next = append(next, gin.H{"type": "marketplace", "name": name, "id": name, "version": version, "marketplaceId": m.MarketplaceID})
	if _, err := tx.Exec(ctx, "UPDATE "+table+" SET skills_json=$1,head_version=head_version+1,updated_at=$2 WHERE owner_id=$3 AND "+idColumn+"=$4", mustJSON(next), nowMillis(), owner, scopeID); err != nil {
		return err
	}
	if staged != "" {
		if err := os.Rename(staged, target); err != nil {
			return err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		// Commit outcome can be uncertain after a disconnect. Keep a complete
		// mirror for reconciliation rather than deleting a possibly committed skill.
		return fmt.Errorf("could not confirm skill install commit; reload the skill list before retrying: %w", err)
	}
	return nil
}

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
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
)

const workspacePublicationMigration = `
CREATE TABLE IF NOT EXISTS workspace_applications (
 owner_id TEXT NOT NULL,agent_id TEXT NOT NULL,attempt_id TEXT NOT NULL,digest TEXT NOT NULL,
 version_json TEXT NOT NULL,workspace_version_json TEXT NOT NULL,applied_at BIGINT NOT NULL,
 PRIMARY KEY(owner_id,agent_id,attempt_id)
);
CREATE TABLE IF NOT EXISTS workspace_revisions (
 owner_id TEXT NOT NULL, workspace_id TEXT NOT NULL, version INTEGER NOT NULL,
 snapshot_json TEXT NOT NULL, created_at BIGINT NOT NULL,
 PRIMARY KEY(owner_id, workspace_id, version)
);
`

// WorkspaceBinding records author intent separately from the resolved definition.
// Revision zero explicitly publishes and selects the current draft.
type WorkspaceBinding struct {
	Version      int      `json:"version"`
	Digest       string   `json:"digest,omitempty"`
	Overrides    []string `json:"overrides"`
	Instructions string   `json:"instructions,omitempty"`
}

type WorkspaceRevision struct {
	Version      int               `json:"version"`
	DraftVersion int               `json:"draftVersion"`
	Digest       string            `json:"digest"`
	Instructions string            `json:"instructions"`
	Tools        any               `json:"tools"`
	MCPServers   any               `json:"mcpServers"`
	Skills       any               `json:"skills"`
	Files        map[string]string `json:"files"`
	CreatedAt    int64             `json:"createdAt"`
}

func definitionFile(path string) bool {
	path = strings.ToLower(strings.ReplaceAll(path, "\\", "/"))
	for _, segment := range strings.Split(path, "/") {
		if segment == ".." || segment == "sessions" || segment == "memory" || segment == "logs" || segment == "artifacts" || segment == "inputs" || segment == "outputs" || segment == ".git" {
			return false
		}
	}
	return path != "memory.md" && !strings.HasSuffix(path, ".log.jsonl") && !strings.HasPrefix(path, ".managed-")
}

func contentDigest(value any) string {
	raw, _ := json.Marshal(value)
	hash := sha256.Sum256(raw)
	return hex.EncodeToString(hash[:])
}

func (s *Server) publishWorkspace(ctx context.Context, owner, id string) (*WorkspaceRevision, error) {
	tx, err := s.db.Pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.RepeatableRead})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	w, err := s.scanWorkspace(tx.QueryRow(ctx, workspaceSelect+` WHERE owner_id=$1 AND workspace_id=$2 AND archived_at IS NULL FOR UPDATE`, owner, id))
	if err != nil {
		return nil, err
	}
	files := map[string]string{}
	rows, err := tx.Query(ctx, `SELECT path,content FROM workspace_files WHERE owner_id=$1 AND scope_type='workspace' AND scope_id=$2`, owner, id)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var path, content string
		if err = rows.Scan(&path, &content); err != nil {
			rows.Close()
			return nil, err
		}
		if definitionFile(path) {
			files[path] = content
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, err
	}
	revision := &WorkspaceRevision{DraftVersion: w.HeadVersion, Instructions: files["AGENTS.md"], Tools: parseJSONRaw(deref(w.ToolsJSON)), MCPServers: parseJSONRaw(deref(w.McpServersJSON)), Skills: parseJSONRaw(deref(w.SkillsJSON)), Files: files, CreatedAt: nowMillis()}
	revision.Digest = contentDigest(map[string]any{"instructions": revision.Instructions, "tools": revision.Tools, "mcpServers": revision.MCPServers, "skills": revision.Skills, "files": files})
	var raw string
	err = tx.QueryRow(ctx, `SELECT snapshot_json FROM workspace_revisions WHERE owner_id=$1 AND workspace_id=$2 ORDER BY version DESC LIMIT 1`, owner, id).Scan(&raw)
	if err == nil {
		var previous WorkspaceRevision
		if err = json.Unmarshal([]byte(raw), &previous); err != nil {
			return nil, err
		}
		if previous.Digest == revision.Digest {
			return &previous, tx.Commit(ctx)
		}
		revision.Version = previous.Version + 1
	} else if err == pgx.ErrNoRows {
		revision.Version = 1
	} else {
		return nil, err
	}
	_, err = tx.Exec(ctx, `INSERT INTO workspace_revisions(owner_id,workspace_id,version,snapshot_json,created_at) VALUES($1,$2,$3,$4,$5)`, owner, id, revision.Version, mustJSON(revision), revision.CreatedAt)
	if err != nil {
		return nil, err
	}
	return revision, tx.Commit(ctx)
}

func (s *Server) workspaceRevision(ctx context.Context, owner, id string, version int) (*WorkspaceRevision, error) {
	if version == 0 {
		return s.publishWorkspace(ctx, owner, id)
	}
	w, err := s.loadWorkspace(ctx, owner, id)
	if err != nil {
		return nil, err
	}
	if w.ArchivedAt != nil {
		return nil, fmt.Errorf("workspace is archived")
	}
	var raw string
	err = s.db.Pool.QueryRow(ctx, `SELECT snapshot_json FROM workspace_revisions WHERE owner_id=$1 AND workspace_id=$2 AND version=$3`, owner, id, version).Scan(&raw)
	if err != nil {
		return nil, err
	}
	var revision WorkspaceRevision
	err = json.Unmarshal([]byte(raw), &revision)
	return &revision, err
}

func (s *Server) wsPublish(c *gin.Context) {
	revision, err := s.publishWorkspace(c.Request.Context(), currentResourceOwner(c), c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusBadRequest, "Cannot publish workspace: "+err.Error())
		return
	}
	c.JSON(http.StatusOK, revision)
}
func (s *Server) wsRevisions(c *gin.Context) {
	owner, id := currentResourceOwner(c), c.Param("id")
	if _, err := s.loadWorkspace(c.Request.Context(), owner, id); err != nil {
		writeErr(c, 404, "workspace not found")
		return
	}
	rows, err := s.db.Pool.Query(c.Request.Context(), `SELECT snapshot_json FROM workspace_revisions WHERE owner_id=$1 AND workspace_id=$2 ORDER BY version DESC`, owner, id)
	if err != nil {
		writeErr(c, 500, "Cannot load workspace revisions")
		return
	}
	defer rows.Close()
	items := []WorkspaceRevision{}
	for rows.Next() {
		var raw string
		if rows.Scan(&raw) != nil {
			writeErr(c, 500, "Cannot read revision")
			return
		}
		var revision WorkspaceRevision
		if json.Unmarshal([]byte(raw), &revision) != nil {
			writeErr(c, 500, "Invalid revision")
			return
		}
		items = append(items, revision)
	}
	if rows.Err() != nil {
		writeErr(c, 500, "Cannot read revisions")
		return
	}
	c.JSON(200, gin.H{"items": items})
}
func (s *Server) wsLinkedAgents(c *gin.Context) {
	owner, id := currentResourceOwner(c), c.Param("id")
	if _, err := s.loadWorkspace(c.Request.Context(), owner, id); err != nil {
		writeErr(c, 404, "workspace not found")
		return
	}
	rows, err := s.db.Pool.Query(c.Request.Context(), agentSelect+` WHERE owner_id=$1 AND workspace_id=$2 AND archived_at IS NULL`, owner, id)
	if err != nil {
		writeErr(c, 500, "Cannot load linked agents")
		return
	}
	defer rows.Close()
	items := []gin.H{}
	for rows.Next() {
		a, e := s.scanAgent(rows)
		if e != nil {
			writeErr(c, 500, "Cannot read agent")
			return
		}
		items = append(items, gin.H{"id": a.AgentID, "name": a.Name, "version": a.HeadVersion})
	}
	if rows.Err() != nil {
		writeErr(c, 500, "Cannot read agents")
		return
	}
	c.JSON(200, gin.H{"items": items})
}

func (s *Server) resolveWorkspaceInput(ctx context.Context, owner string, in *ManagedDefinitionInput) (*WorkspaceRevision, error) {
	if in.WorkspaceID == "" {
		in.WorkspaceBinding = nil
		return nil, nil
	}
	binding := in.WorkspaceBinding
	if binding == nil {
		binding = &WorkspaceBinding{Overrides: []string{}, Instructions: in.System}
	}
	overrides := map[string]bool{}
	for _, field := range binding.Overrides {
		if field != "tools" && field != "mcpServers" && field != "skills" {
			return nil, fmt.Errorf("unsupported Workspace override %q", field)
		}
		overrides[field] = true
	}
	revision, err := s.workspaceRevision(ctx, owner, in.WorkspaceID, binding.Version)
	if err != nil {
		return nil, fmt.Errorf("workspace revision is unavailable: %w", err)
	}
	binding.Version, binding.Digest = revision.Version, revision.Digest
	if !overrides["tools"] {
		in.Tools = revision.Tools
	}
	if !overrides["mcpServers"] {
		in.MCPServers = revision.MCPServers
	}
	if !overrides["skills"] {
		in.Skills = revision.Skills
	}
	in.System = strings.TrimSpace(revision.Instructions)
	if extra := strings.TrimSpace(binding.Instructions); extra != "" {
		if in.System != "" {
			in.System += "\n\n"
		}
		in.System += "Agent-specific instructions:\n" + extra
	}
	in.WorkspaceBinding = binding
	return revision, nil
}

func (s *Server) RecordWorkspaceApplication(ctx context.Context, owner, agent, attempt, digest string, version, workspaceVersion any) error {
	_, err := s.db.Pool.Exec(ctx, `INSERT INTO workspace_applications(owner_id,agent_id,attempt_id,digest,version_json,workspace_version_json,applied_at) VALUES($1,$2,$3,$4,$5,$6,$7) ON CONFLICT(owner_id,agent_id,attempt_id) DO NOTHING`, owner, agent, attempt, digest, mustJSON(version), mustJSON(workspaceVersion), nowMillis())
	return err
}
func (s *Server) WorkspaceApplications(ctx context.Context, owner, agent string) ([]map[string]any, error) {
	rows, err := s.db.Pool.Query(ctx, `SELECT digest,version_json,workspace_version_json,applied_at FROM workspace_applications WHERE owner_id=$1 AND agent_id=$2 ORDER BY applied_at DESC LIMIT 10`, owner, agent)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var digest, version, workspaceVersion string
		var applied int64
		if err = rows.Scan(&digest, &version, &workspaceVersion, &applied); err != nil {
			return nil, err
		}
		out = append(out, map[string]any{"digest": digest, "version": parseJSONRaw(version), "workspaceVersion": parseJSONRaw(workspaceVersion), "appliedAt": applied})
	}
	return out, rows.Err()
}

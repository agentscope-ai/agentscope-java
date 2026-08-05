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
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/gin-gonic/gin"
)

type workspaceRow struct {
	OwnerID        string
	WorkspaceID    string
	Name           string
	Description    *string
	ToolsJSON      *string
	McpServersJSON *string
	SkillsJSON     *string
	HeadVersion    int
	ArchivedAt     *int64
	CreatedAt      int64
	UpdatedAt      int64
}

type workspaceMaterialized struct {
	Tools      any
	McpServers any
	Skills     any
	System     string
	DiskPath   string
	Version    int
}

func (s *Server) registerWorkspaces(r gin.IRouter) {
	r.GET("/api/workspaces", s.listWorkspaces)
	r.POST("/api/workspaces", s.createWorkspace)
	r.GET("/api/workspaces/:id", s.getWorkspace)
	r.PATCH("/api/workspaces/:id", s.patchWorkspace)
	r.DELETE("/api/workspaces/:id", s.deleteWorkspace)

	base := "/api/workspaces/:id"
	r.GET(base+"/files", s.wsFiles)
	r.GET(base+"/file", s.wsReadFile)
	r.PUT(base+"/file", s.wsWriteFile)
	r.DELETE(base+"/file", s.wsDeleteFile)

	r.GET(base+"/skills", s.wsListSkills)
	r.GET(base+"/skills/:name", s.wsGetSkill)
	r.PUT(base+"/skills/:name", s.wsPutSkill)
	r.DELETE(base+"/skills/:name", s.wsDeleteSkill)
	r.POST(base+"/skills/marketplace-install", s.wsMarketplaceInstall)

	r.GET(base+"/subagents", s.wsListSubagents)
	r.PUT(base+"/subagents/:name", s.wsUpsertSubagent)
	r.DELETE(base+"/subagents/:name", s.wsDeleteSubagent)

	r.GET(base+"/tools", s.wsGetTools)
	r.PUT(base+"/tools", s.wsPutTools)

	r.GET("/api/toolsets/builtin", s.platformBuiltinCatalog)
	r.GET("/api/toolsets/mcp-catalog", s.platformMcpCatalog)
}

func (w workspaceRow) toJSON() gin.H {
	desc := ""
	if w.Description != nil {
		desc = *w.Description
	}
	return gin.H{
		"id":          w.WorkspaceID,
		"name":        w.Name,
		"description": desc,
		"tools":       parseJSONRaw(deref(w.ToolsJSON)),
		"mcpServers":  parseJSONRaw(deref(w.McpServersJSON)),
		"skills":      parseJSONRaw(deref(w.SkillsJSON)),
		"version":     w.HeadVersion,
		"ownerId":     w.OwnerID,
		"createdAt":   w.CreatedAt,
		"updatedAt":   w.UpdatedAt,
		"archivedAt":  nullMillis(w.ArchivedAt),
	}
}

func (s *Server) scanWorkspace(rows interface{ Scan(dest ...any) error }) (workspaceRow, error) {
	var w workspaceRow
	err := rows.Scan(
		&w.OwnerID, &w.WorkspaceID, &w.Name, &w.Description,
		&w.ToolsJSON, &w.McpServersJSON, &w.SkillsJSON,
		&w.HeadVersion, &w.ArchivedAt, &w.CreatedAt, &w.UpdatedAt,
	)
	return w, err
}

const workspaceSelect = `SELECT owner_id, workspace_id, name, description, tools_json, mcp_servers_json,
	skills_json, head_version, archived_at, created_at, updated_at FROM workspaces`

func (s *Server) loadWorkspace(ctx context.Context, owner, id string) (workspaceRow, error) {
	row := s.db.Pool.QueryRow(ctx, workspaceSelect+` WHERE owner_id=$1 AND workspace_id=$2`, owner, id)
	return s.scanWorkspace(row)
}

func (s *Server) workspaceDiskRoot(owner, workspaceID string) string {
	return filepath.Join(s.cfg.WorkspaceRoot, owner, "workspaces", workspaceID)
}

func (s *Server) materializeFromWorkspace(ctx context.Context, owner, workspaceID string) (workspaceMaterialized, error) {
	w, err := s.loadWorkspace(ctx, owner, workspaceID)
	if err != nil {
		return workspaceMaterialized{}, fmt.Errorf("workspace not found: %s", workspaceID)
	}
	system := ""
	if content, ok, _ := s.getWorkspaceFile(ctx, owner, scopeTypeWorkspace, workspaceID, "AGENTS.md"); ok {
		system = content
	}
	return workspaceMaterialized{
		Tools:      parseJSONRaw(deref(w.ToolsJSON)),
		McpServers: parseJSONRaw(deref(w.McpServersJSON)),
		Skills:     parseJSONRaw(deref(w.SkillsJSON)),
		System:     system,
		DiskPath:   s.workspaceDiskRoot(owner, workspaceID),
		Version:    w.HeadVersion,
	}, nil
}

// rematerializeLinkedAgents refreshes tools/mcp/skills (and empty system prompts)
// on every Agent that currently links this Workspace. Each refresh bumps agent head_version.
func (s *Server) rematerializeLinkedAgents(ctx context.Context, owner, workspaceID string) {
	mat, err := s.materializeFromWorkspace(ctx, owner, workspaceID)
	if err != nil {
		return
	}
	rows, err := s.db.Pool.Query(ctx,
		agentSelect+` WHERE owner_id=$1 AND workspace_id=$2 AND archived_at IS NULL`,
		owner, workspaceID)
	if err != nil {
		return
	}
	defer rows.Close()
	now := nowMillis()
	for rows.Next() {
		a, err := s.scanAgent(rows)
		if err != nil {
			continue
		}
		sys := ""
		if a.SysPrompt != nil {
			sys = strings.TrimSpace(*a.SysPrompt)
		}
		if sys == "" {
			sys = mat.System
		}
		maxIters := 20
		if a.MaxIters != nil {
			maxIters = *a.MaxIters
		}
		ws := mat.DiskPath
		newVer := a.HeadVersion + 1
		desc := ""
		if a.Description != nil {
			desc = *a.Description
		}
		model := ""
		if a.Model != nil {
			model = *a.Model
		}
		multi := mustJSON(nil)
		if a.MultiagentJSON != nil {
			multi = *a.MultiagentJSON
		}
		tools := mustJSON(mat.Tools)
		mcp := mustJSON(mat.McpServers)
		skills := mustJSON(mat.Skills)
		_, err = s.db.Pool.Exec(ctx,
			`UPDATE agents SET sys_prompt=$1, tools_json=$2, mcp_servers_json=$3, skills_json=$4,
			 workspace_path=$5, head_version=$6, updated_at=$7
			 WHERE owner_id=$8 AND agent_id=$9`,
			nullStr(sys), tools, mcp, skills, nullStr(ws), newVer, now, owner, a.AgentID)
		if err != nil {
			continue
		}
		snap := s.agentSnapshot(owner, a.AgentID, a.Name, desc, sys, model, maxIters,
			mat.Tools, mat.McpServers, mat.Skills, parseJSONRaw(multi), ws, workspaceID,
			deref(a.DefaultEnvironmentID),
			parseStringSlice(deref(a.DefaultVaultIDsJSON)),
			parseStringSlice(deref(a.DefaultMemoryStoreIDsJSON)),
			newVer, a.CreatedAt, now)
		_, _ = s.db.Pool.Exec(ctx,
			`INSERT INTO agent_versions (owner_id, agent_id, version, snapshot_json, created_at) VALUES ($1,$2,$3,$4,$5)`,
			owner, a.AgentID, newVer, mustJSON(snap), now)
	}
}

func (s *Server) bumpWorkspaceVersion(ctx context.Context, owner, id string) error {
	_, err := s.db.Pool.Exec(ctx,
		`UPDATE workspaces SET head_version=head_version+1, updated_at=$1
		 WHERE owner_id=$2 AND workspace_id=$3`,
		nowMillis(), owner, id)
	return err
}

func (s *Server) listWorkspaces(c *gin.Context) {
	owner := currentUserID(c)
	rows, err := s.db.Pool.Query(c.Request.Context(),
		workspaceSelect+` WHERE owner_id=$1 AND archived_at IS NULL ORDER BY updated_at DESC`, owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []gin.H{}
	for rows.Next() {
		w, err := s.scanWorkspace(rows)
		if err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		item := w.toJSON()
		paths, _ := s.listWorkspaceFilePaths(c.Request.Context(), owner, scopeTypeWorkspace, w.WorkspaceID, "")
		skillCount := 0
		subCount := 0
		agentsMd := false
		for _, p := range paths {
			if p == "AGENTS.md" {
				agentsMd = true
			}
			if strings.HasPrefix(p, "skills/") && strings.HasSuffix(p, "/SKILL.md") {
				skillCount++
			}
			if strings.HasPrefix(p, "subagents/") && strings.HasSuffix(p, ".md") {
				subCount++
			}
		}
		item["agentsMdExists"] = agentsMd
		item["skillCount"] = skillCount
		item["subagentCount"] = subCount
		list = append(list, item)
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) createWorkspace(c *gin.Context) {
	var req struct {
		Name        string `json:"name"`
		Description string `json:"description"`
		Tools       any    `json:"tools"`
		McpServers  any    `json:"mcpServers"`
		Skills      any    `json:"skills"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || strings.TrimSpace(req.Name) == "" {
		writeErr(c, http.StatusBadRequest, "name required")
		return
	}
	owner := currentUserID(c)
	id := shortID("ws_")
	now := nowMillis()
	disk := s.workspaceDiskRoot(owner, id)
	_ = os.MkdirAll(disk, 0o755)
	tools := req.Tools
	if tools == nil {
		// Default: Claude-aligned filesystem + web toolset enabled.
		tools = []gin.H{{
			"type": "agent_toolset",
			"defaultConfig": gin.H{
				"enabled": true, "permissionPolicy": gin.H{"type": "always_allow"},
			},
			"configs": []gin.H{
				{"name": "bash", "enabled": true, "permissionPolicy": gin.H{"type": "always_ask"}},
				{"name": "read", "enabled": true},
				{"name": "write", "enabled": true},
				{"name": "edit", "enabled": true},
				{"name": "glob", "enabled": true},
				{"name": "grep", "enabled": true},
				{"name": "web_fetch", "enabled": true},
				{"name": "web_search", "enabled": true},
			},
		}}
	}
	_, err := s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO workspaces (owner_id, workspace_id, name, description, tools_json, mcp_servers_json,
		 skills_json, head_version, created_at, updated_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,1,$8,$8)`,
		owner, id, req.Name, nullStr(req.Description), mustJSON(tools), mustJSON(req.McpServers),
		mustJSON(req.Skills), now)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	_ = s.putWorkspaceFile(c.Request.Context(), owner, scopeTypeWorkspace, id, "AGENTS.md",
		"# "+req.Name+"\n\nYou are a helpful agent.\n", disk)
	w, _ := s.loadWorkspace(c.Request.Context(), owner, id)
	c.JSON(http.StatusOK, w.toJSON())
}

func (s *Server) getWorkspace(c *gin.Context) {
	owner := currentUserID(c)
	w, err := s.loadWorkspace(c.Request.Context(), owner, c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "workspace not found")
		return
	}
	out := w.toJSON()
	paths, _ := s.listWorkspaceFilePaths(c.Request.Context(), owner, scopeTypeWorkspace, w.WorkspaceID, "")
	skillCount := 0
	subCount := 0
	agentsMd := false
	for _, p := range paths {
		if p == "AGENTS.md" {
			agentsMd = true
		}
		if strings.HasPrefix(p, "skills/") && strings.HasSuffix(p, "/SKILL.md") {
			skillCount++
		}
		if strings.HasPrefix(p, "subagents/") && strings.HasSuffix(p, ".md") {
			subCount++
		}
	}
	out["agentsMdExists"] = agentsMd
	out["skillCount"] = skillCount
	out["subagentCount"] = subCount
	c.JSON(http.StatusOK, out)
}

func (s *Server) patchWorkspace(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	w, err := s.loadWorkspace(c.Request.Context(), owner, id)
	if err != nil {
		writeErr(c, http.StatusNotFound, "workspace not found")
		return
	}
	var req struct {
		Name        *string `json:"name"`
		Description *string `json:"description"`
		Tools       any     `json:"tools"`
		McpServers  any     `json:"mcpServers"`
		Skills      any     `json:"skills"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	name := w.Name
	if req.Name != nil && strings.TrimSpace(*req.Name) != "" {
		name = strings.TrimSpace(*req.Name)
	}
	desc := deref(w.Description)
	if req.Description != nil {
		desc = *req.Description
	}
	tools := deref(w.ToolsJSON)
	if req.Tools != nil {
		tools = mustJSON(req.Tools)
	}
	mcp := deref(w.McpServersJSON)
	if req.McpServers != nil {
		mcp = mustJSON(req.McpServers)
	}
	skills := deref(w.SkillsJSON)
	if req.Skills != nil {
		skills = mustJSON(req.Skills)
	}
	now := nowMillis()
	_, err = s.db.Pool.Exec(c.Request.Context(),
		`UPDATE workspaces SET name=$1, description=$2, tools_json=$3, mcp_servers_json=$4,
		 skills_json=$5, head_version=head_version+1, updated_at=$6
		 WHERE owner_id=$7 AND workspace_id=$8`,
		name, nullStr(desc), tools, mcp, skills, now, owner, id)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if req.Tools != nil || req.McpServers != nil || req.Skills != nil {
		s.rematerializeLinkedAgents(c.Request.Context(), owner, id)
	}
	out, _ := s.loadWorkspace(c.Request.Context(), owner, id)
	c.JSON(http.StatusOK, out.toJSON())
}

func (s *Server) deleteWorkspace(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	var refs int
	_ = s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT COUNT(*) FROM agents WHERE owner_id=$1 AND workspace_id=$2 AND archived_at IS NULL`,
		owner, id).Scan(&refs)
	if refs > 0 {
		writeErr(c, http.StatusConflict, "workspace is still referenced by agents")
		return
	}
	_, err := s.db.Pool.Exec(c.Request.Context(),
		`DELETE FROM workspaces WHERE owner_id=$1 AND workspace_id=$2`, owner, id)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	_, _ = s.db.Pool.Exec(c.Request.Context(),
		`DELETE FROM workspace_files WHERE owner_id=$1 AND scope_type=$2 AND scope_id=$3`,
		owner, scopeTypeWorkspace, id)
	_ = os.RemoveAll(s.workspaceDiskRoot(owner, id))
	c.Status(http.StatusNoContent)
}

func (s *Server) platformBuiltinCatalog(c *gin.Context) {
	c.JSON(http.StatusOK, builtinToolCatalog)
}

func (s *Server) platformMcpCatalog(c *gin.Context) {
	c.JSON(http.StatusOK, builtinMcpCatalog)
}

func (s *Server) wsFiles(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	if _, err := s.loadWorkspace(c.Request.Context(), owner, id); err != nil {
		writeErr(c, http.StatusNotFound, "workspace not found")
		return
	}
	paths, err := s.listWorkspaceFilePaths(c.Request.Context(), owner, scopeTypeWorkspace, id, "")
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	c.JSON(http.StatusOK, gin.H{"files": paths})
}

func (s *Server) wsReadFile(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	path := c.Query("path")
	content, ok, err := s.getWorkspaceFile(c.Request.Context(), owner, scopeTypeWorkspace, id, path)
	if err != nil {
		writeErr(c, http.StatusBadRequest, err.Error())
		return
	}
	if !ok {
		writeErr(c, http.StatusNotFound, "file not found")
		return
	}
	c.JSON(http.StatusOK, gin.H{"path": path, "content": content})
}

func (s *Server) wsWriteFile(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	if _, err := s.loadWorkspace(c.Request.Context(), owner, id); err != nil {
		writeErr(c, http.StatusNotFound, "workspace not found")
		return
	}
	var req struct {
		Path    string `json:"path"`
		Content string `json:"content"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Path == "" {
		writeErr(c, http.StatusBadRequest, "path required")
		return
	}
	disk := s.workspaceDiskRoot(owner, id)
	if err := s.putWorkspaceFile(c.Request.Context(), owner, scopeTypeWorkspace, id, req.Path, req.Content, disk); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	_ = s.bumpWorkspaceVersion(c.Request.Context(), owner, id)
	s.rematerializeLinkedAgents(c.Request.Context(), owner, id)
	c.JSON(http.StatusOK, gin.H{"path": req.Path})
}

func (s *Server) wsDeleteFile(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	path := c.Query("path")
	disk := s.workspaceDiskRoot(owner, id)
	if err := s.deleteWorkspaceFile(c.Request.Context(), owner, scopeTypeWorkspace, id, path, disk); err != nil {
		writeErr(c, http.StatusBadRequest, err.Error())
		return
	}
	_ = s.bumpWorkspaceVersion(c.Request.Context(), owner, id)
	c.Status(http.StatusNoContent)
}

func (s *Server) wsGetTools(c *gin.Context) {
	owner := currentUserID(c)
	w, err := s.loadWorkspace(c.Request.Context(), owner, c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "workspace not found")
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"tools":      parseJSONRaw(deref(w.ToolsJSON)),
		"mcpServers": parseJSONRaw(deref(w.McpServersJSON)),
	})
}

func (s *Server) wsPutTools(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	if _, err := s.loadWorkspace(c.Request.Context(), owner, id); err != nil {
		writeErr(c, http.StatusNotFound, "workspace not found")
		return
	}
	var req struct {
		Tools      any `json:"tools"`
		McpServers any `json:"mcpServers"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	_, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE workspaces SET tools_json=$1, mcp_servers_json=$2, head_version=head_version+1, updated_at=$3
		 WHERE owner_id=$4 AND workspace_id=$5`,
		mustJSON(req.Tools), mustJSON(req.McpServers), nowMillis(), owner, id)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	s.rematerializeLinkedAgents(c.Request.Context(), owner, id)
	c.JSON(http.StatusOK, gin.H{"tools": req.Tools, "mcpServers": req.McpServers})
}

func (s *Server) wsGetSkill(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	name := c.Param("name")
	if _, err := s.loadWorkspace(c.Request.Context(), owner, id); err != nil {
		writeErr(c, http.StatusNotFound, "workspace not found")
		return
	}
	if name == "" || strings.Contains(name, "..") {
		writeErr(c, http.StatusBadRequest, "invalid name")
		return
	}
	md, ok, _ := s.getWorkspaceFile(c.Request.Context(), owner, scopeTypeWorkspace, id, "skills/"+name+"/SKILL.md")
	if !ok {
		writeErr(c, http.StatusNotFound, "skill not found")
		return
	}
	resources := map[string]string{}
	files, _ := s.listWorkspaceFileContents(c.Request.Context(), owner, scopeTypeWorkspace, id, "skills/"+name)
	for path, content := range files {
		rel := strings.TrimPrefix(path, "skills/"+name+"/")
		if rel == "" || rel == "SKILL.md" || rel == path {
			continue
		}
		resources[rel] = content
	}
	display, desc := parseSkillFrontmatter(md)
	if display == "" {
		display = name
	}
	c.JSON(http.StatusOK, gin.H{
		"name": display, "description": nullStr(desc), "markdown": md, "resources": resources,
	})
}

func (s *Server) wsListSkills(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	if _, err := s.loadWorkspace(c.Request.Context(), owner, id); err != nil {
		writeErr(c, http.StatusNotFound, "workspace not found")
		return
	}
	files, err := s.listWorkspaceFileContents(c.Request.Context(), owner, scopeTypeWorkspace, id, "skills")
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	list := []gin.H{}
	seen := map[string]bool{}
	for path, content := range files {
		// skills/<name>/SKILL.md
		parts := strings.Split(path, "/")
		if len(parts) < 3 || parts[0] != "skills" || parts[2] != "SKILL.md" {
			continue
		}
		name := parts[1]
		if seen[name] {
			continue
		}
		seen[name] = true
		display, desc := parseSkillFrontmatter(content)
		if display == "" {
			display = name
		}
		list = append(list, gin.H{
			"dirName": name, "name": display, "description": nullStr(desc), "origin": "custom",
		})
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) wsPutSkill(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	name := c.Param("name")
	if _, err := s.loadWorkspace(c.Request.Context(), owner, id); err != nil {
		writeErr(c, http.StatusNotFound, "workspace not found")
		return
	}
	if name == "" || strings.Contains(name, "..") {
		writeErr(c, http.StatusBadRequest, "invalid name")
		return
	}
	var req struct {
		Markdown  string            `json:"markdown"`
		Resources map[string]string `json:"resources"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	disk := s.workspaceDiskRoot(owner, id)
	if err := s.putWorkspaceFile(c.Request.Context(), owner, scopeTypeWorkspace, id,
		"skills/"+name+"/SKILL.md", req.Markdown, disk); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	for rel, content := range req.Resources {
		relClean, err := cleanRelPath(rel)
		if err != nil || relClean == "" || relClean == "SKILL.md" {
			continue
		}
		_ = s.putWorkspaceFile(c.Request.Context(), owner, scopeTypeWorkspace, id,
			"skills/"+name+"/"+relClean, content, disk)
	}
	// Ensure skills_json includes this workspace skill ref.
	w, _ := s.loadWorkspace(c.Request.Context(), owner, id)
	skills := parseJSONRaw(deref(w.SkillsJSON))
	arr, _ := skills.([]any)
	found := false
	for _, item := range arr {
		if m, ok := item.(map[string]any); ok {
			if n, _ := m["name"].(string); n == name {
				found = true
				break
			}
		}
	}
	if !found {
		arr = append(arr, gin.H{"type": "workspace", "name": name})
		_, _ = s.db.Pool.Exec(c.Request.Context(),
			`UPDATE workspaces SET skills_json=$1, head_version=head_version+1, updated_at=$2
			 WHERE owner_id=$3 AND workspace_id=$4`,
			mustJSON(arr), nowMillis(), owner, id)
	} else {
		_ = s.bumpWorkspaceVersion(c.Request.Context(), owner, id)
	}
	s.rematerializeLinkedAgents(c.Request.Context(), owner, id)
	c.JSON(http.StatusOK, gin.H{"dirName": name, "name": name, "origin": "custom"})
}

func (s *Server) wsDeleteSkill(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	name := c.Param("name")
	disk := s.workspaceDiskRoot(owner, id)
	if err := s.deleteWorkspaceFilePrefix(c.Request.Context(), owner, scopeTypeWorkspace, id, "skills/"+name, disk); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	w, _ := s.loadWorkspace(c.Request.Context(), owner, id)
	arr, _ := parseJSONRaw(deref(w.SkillsJSON)).([]any)
	next := make([]any, 0, len(arr))
	for _, item := range arr {
		m, ok := item.(map[string]any)
		if !ok {
			next = append(next, item)
			continue
		}
		n, _ := m["name"].(string)
		if n == "" {
			n, _ = m["id"].(string)
		}
		if n == name {
			continue
		}
		next = append(next, item)
	}
	_, _ = s.db.Pool.Exec(c.Request.Context(),
		`UPDATE workspaces SET skills_json=$1, head_version=head_version+1, updated_at=$2
		 WHERE owner_id=$3 AND workspace_id=$4`,
		mustJSON(next), nowMillis(), owner, id)
	s.rematerializeLinkedAgents(c.Request.Context(), owner, id)
	c.Status(http.StatusNoContent)
}

type subagentUpsertReq struct {
	Description   string   `json:"description"`
	Model         string   `json:"model"`
	MaxIters      *int     `json:"maxIters"`
	Tools         []string `json:"tools"`
	WorkspaceMode string   `json:"workspaceMode"`
	WorkspacePath string   `json:"workspacePath"`
	InlineBody    string   `json:"inlineBody"`
	SourceAgentID string   `json:"sourceAgentId"`
}

func buildSubagentMarkdown(req subagentUpsertReq) string {
	mode := req.WorkspaceMode
	if mode == "" {
		mode = "isolated"
	}
	var b strings.Builder
	b.WriteString("---\n")
	b.WriteString("description: " + yamlQuote(req.Description) + "\n")
	b.WriteString("workspace:\n")
	b.WriteString("  mode: " + mode + "\n")
	if req.WorkspacePath != "" {
		b.WriteString("  path: " + yamlQuote(req.WorkspacePath) + "\n")
	}
	if req.Model != "" {
		b.WriteString("model: " + yamlQuote(req.Model) + "\n")
	}
	if req.MaxIters != nil {
		b.WriteString(fmt.Sprintf("maxIters: %d\n", *req.MaxIters))
	}
	if len(req.Tools) > 0 {
		b.WriteString("tools: [")
		for i, t := range req.Tools {
			if i > 0 {
				b.WriteString(", ")
			}
			b.WriteString(t)
		}
		b.WriteString("]\n")
	}
	b.WriteString("---\n\n")
	body := req.InlineBody
	if body == "" {
		body = req.Description
	}
	b.WriteString(body)
	if !strings.HasSuffix(body, "\n") {
		b.WriteString("\n")
	}
	return b.String()
}

func yamlQuote(s string) string {
	if s == "" {
		return `""`
	}
	if strings.ContainsAny(s, ":#\n\"'") || strings.HasPrefix(s, " ") {
		return `"` + strings.ReplaceAll(s, `"`, `\"`) + `"`
	}
	return s
}

func parseSubagentMarkdown(content string) gin.H {
	out := gin.H{
		"description":   "",
		"workspaceMode": "isolated",
		"hasInlineBody": true,
	}
	if !strings.HasPrefix(content, "---") {
		out["description"] = strings.TrimSpace(content)
		if d, ok := out["description"].(string); ok && len(d) > 200 {
			out["description"] = d[:200]
		}
		return out
	}
	rest := strings.TrimPrefix(content, "---")
	idx := strings.Index(rest, "\n---")
	if idx < 0 {
		return out
	}
	front := rest[:idx]
	body := strings.TrimSpace(rest[idx+4:])
	for _, line := range strings.Split(front, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "description:") {
			out["description"] = strings.TrimSpace(strings.TrimPrefix(line, "description:"))
			out["description"] = strings.Trim(out["description"].(string), `"'`)
		}
		if strings.HasPrefix(line, "model:") {
			out["model"] = strings.Trim(strings.TrimSpace(strings.TrimPrefix(line, "model:")), `"'`)
		}
		if strings.HasPrefix(line, "maxIters:") {
			out["maxIters"] = strings.TrimSpace(strings.TrimPrefix(line, "maxIters:"))
		}
		if strings.HasPrefix(line, "mode:") {
			out["workspaceMode"] = strings.TrimSpace(strings.TrimPrefix(line, "mode:"))
		}
	}
	out["hasInlineBody"] = body != ""
	out["inlineBody"] = body
	return out
}

func (s *Server) wsListSubagents(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	files, err := s.listWorkspaceFileContents(c.Request.Context(), owner, scopeTypeWorkspace, id, "subagents")
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	list := []gin.H{}
	for path, content := range files {
		if !strings.HasPrefix(path, "subagents/") || !strings.HasSuffix(path, ".md") {
			continue
		}
		name := strings.TrimSuffix(strings.TrimPrefix(path, "subagents/"), ".md")
		info := parseSubagentMarkdown(content)
		info["name"] = name
		list = append(list, info)
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) wsUpsertSubagent(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	name := c.Param("name")
	if _, err := s.loadWorkspace(c.Request.Context(), owner, id); err != nil {
		writeErr(c, http.StatusNotFound, "workspace not found")
		return
	}
	var req subagentUpsertReq
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	if req.Description == "" {
		writeErr(c, http.StatusBadRequest, "description required")
		return
	}
	md := buildSubagentMarkdown(req)
	disk := s.workspaceDiskRoot(owner, id)
	if err := s.putWorkspaceFile(c.Request.Context(), owner, scopeTypeWorkspace, id,
		"subagents/"+name+".md", md, disk); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	_ = s.bumpWorkspaceVersion(c.Request.Context(), owner, id)
	s.rematerializeLinkedAgents(c.Request.Context(), owner, id)
	mode := req.WorkspaceMode
	if mode == "" {
		mode = "isolated"
	}
	c.JSON(http.StatusOK, gin.H{
		"name": name, "description": req.Description, "model": nullStr(req.Model),
		"maxIters": req.MaxIters, "tools": req.Tools, "workspaceMode": mode,
		"workspacePath": nullStr(req.WorkspacePath), "hasInlineBody": true,
	})
}

func (s *Server) wsDeleteSubagent(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	name := c.Param("name")
	disk := s.workspaceDiskRoot(owner, id)
	if err := s.deleteWorkspaceFile(c.Request.Context(), owner, scopeTypeWorkspace, id,
		"subagents/"+name+".md", disk); err != nil {
		writeErr(c, http.StatusBadRequest, err.Error())
		return
	}
	_ = s.bumpWorkspaceVersion(c.Request.Context(), owner, id)
	s.rematerializeLinkedAgents(c.Request.Context(), owner, id)
	c.Status(http.StatusNoContent)
}

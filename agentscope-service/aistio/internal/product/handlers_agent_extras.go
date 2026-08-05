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
	"io"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/gin-gonic/gin"
	"gopkg.in/yaml.v3"
)

func (s *Server) registerAgentExtras(r gin.IRouter) {
	r.GET("/api/agents/:id/skills/workspace", s.listWorkspaceSkills)
	r.GET("/api/agents/:id/skills/workspace/:name", s.getWorkspaceSkill)
	r.PUT("/api/agents/:id/skills/workspace/:name", s.putWorkspaceSkill)
	r.DELETE("/api/agents/:id/skills/workspace/:name", s.deleteWorkspaceSkill)
	r.POST("/api/agents/:id/skills/workspace/marketplace-install", s.marketplaceInstallSkill)

	r.GET("/api/agents/:id/tools/catalog/builtins", s.toolsBuiltinCatalog)
	r.GET("/api/agents/:id/tools/catalog/mcp-servers", s.toolsMcpCatalog)
	r.GET("/api/agents/:id/tools/active", s.toolsActive)

	r.POST("/api/agents/:id/clone", s.cloneAgent)

	r.GET("/api/agents/:id/shares", s.listShares)
	r.POST("/api/agents/:id/shares", s.addShare)
	r.DELETE("/api/agents/:id/shares/:granteeType/:granteeId", s.revokeShare)
}

func skillDir(ws, name string) string {
	return filepath.Join(ws, "skills", name)
}

func parseSkillFrontmatter(markdown string) (name, description string) {
	if !strings.HasPrefix(markdown, "---") {
		return "", ""
	}
	rest := strings.TrimPrefix(markdown, "---")
	idx := strings.Index(rest, "\n---")
	if idx < 0 {
		return "", ""
	}
	var meta struct {
		Name        string `yaml:"name"`
		Description string `yaml:"description"`
	}
	_ = yaml.Unmarshal([]byte(rest[:idx]), &meta)
	return meta.Name, meta.Description
}

func skillInfoFromDir(dir, dirName string) gin.H {
	mdPath := filepath.Join(dir, "SKILL.md")
	b, _ := os.ReadFile(mdPath)
	name, desc := parseSkillFrontmatter(string(b))
	if name == "" {
		name = dirName
	}
	var size int64
	resourceCount := 0
	hasRefs := false
	hasScripts := false
	_ = filepath.WalkDir(dir, func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			if d != nil && d.IsDir() {
				switch d.Name() {
				case "references", "reference":
					hasRefs = true
				case "scripts", "script":
					hasScripts = true
				}
			}
			return nil
		}
		info, err := d.Info()
		if err == nil {
			size += info.Size()
		}
		rel, _ := filepath.Rel(dir, path)
		if rel != "SKILL.md" {
			resourceCount++
		}
		return nil
	})
	out := gin.H{
		"dirName":        dirName,
		"name":           name,
		"description":    nullStr(desc),
		"sizeBytes":      size,
		"resourceCount":  resourceCount,
		"hasReferences":  hasRefs,
		"hasScripts":     hasScripts,
		"origin":         "custom",
	}
	return out
}

func (s *Server) listWorkspaceSkills(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	a, err := s.loadAgent(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	ws, _, err := s.resolveAgentWorkspace(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	scopeType, scopeID := a.resolveDefinitionScope()
	files, _ := s.listWorkspaceFileContents(c.Request.Context(), owner, scopeType, scopeID, "skills")
	list := []gin.H{}
	seen := map[string]bool{}
	for path, content := range files {
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
	if len(list) == 0 {
		dir := filepath.Join(ws, "skills")
		entries, err := os.ReadDir(dir)
		if err == nil {
			for _, e := range entries {
				if !e.IsDir() || strings.HasPrefix(e.Name(), ".") {
					continue
				}
				skillPath := filepath.Join(dir, e.Name())
				if !fileExists(filepath.Join(skillPath, "SKILL.md")) {
					continue
				}
				list = append(list, skillInfoFromDir(skillPath, e.Name()))
			}
		}
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) getWorkspaceSkill(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	a, err := s.loadAgent(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	ws, _, err := s.resolveAgentWorkspace(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	name := c.Param("name")
	if strings.Contains(name, "..") || name == "" {
		writeErr(c, http.StatusBadRequest, "invalid name")
		return
	}
	scopeType, scopeID := a.resolveDefinitionScope()
	md, ok, _ := s.getWorkspaceFile(c.Request.Context(), owner, scopeType, scopeID, "skills/"+name+"/SKILL.md")
	resources := map[string]string{}
	if ok {
		files, _ := s.listWorkspaceFileContents(c.Request.Context(), owner, scopeType, scopeID, "skills/"+name)
		for path, content := range files {
			rel := strings.TrimPrefix(path, "skills/"+name+"/")
			if rel == "" || rel == "SKILL.md" || rel == path {
				continue
			}
			resources[rel] = content
		}
	} else {
		dir := skillDir(ws, name)
		b, rerr := os.ReadFile(filepath.Join(dir, "SKILL.md"))
		if rerr != nil {
			writeErr(c, http.StatusNotFound, "skill not found")
			return
		}
		md = string(b)
		_ = filepath.WalkDir(dir, func(path string, d fs.DirEntry, err error) error {
			if err != nil || d.IsDir() {
				return nil
			}
			rel, _ := filepath.Rel(dir, path)
			rel = filepath.ToSlash(rel)
			if rel == "SKILL.md" {
				return nil
			}
			content, err := os.ReadFile(path)
			if err == nil {
				resources[rel] = string(content)
			}
			return nil
		})
	}
	displayName, desc := parseSkillFrontmatter(md)
	if displayName == "" {
		displayName = name
	}
	c.JSON(http.StatusOK, gin.H{
		"name":        displayName,
		"description": nullStr(desc),
		"markdown":    md,
		"resources":   resources,
	})
}

func (s *Server) putWorkspaceSkill(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	a, err := s.loadAgent(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	ws, _, err := s.resolveAgentWorkspace(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	name := c.Param("name")
	if strings.Contains(name, "..") || name == "" {
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
	scopeType, scopeID := a.resolveDefinitionScope()
	if err := s.putWorkspaceFile(c.Request.Context(), owner, scopeType, scopeID,
		"skills/"+name+"/SKILL.md", req.Markdown, ws); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	for rel, content := range req.Resources {
		relClean, err := cleanRelPath(rel)
		if err != nil || relClean == "" || relClean == "SKILL.md" {
			continue
		}
		_ = s.putWorkspaceFile(c.Request.Context(), owner, scopeType, scopeID,
			"skills/"+name+"/"+relClean, content, ws)
	}
	c.JSON(http.StatusOK, skillInfoFromDir(skillDir(ws, name), name))
}

func (s *Server) deleteWorkspaceSkill(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	a, err := s.loadAgent(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	ws, _, err := s.resolveAgentWorkspace(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	name := c.Param("name")
	if strings.Contains(name, "..") || name == "" {
		writeErr(c, http.StatusBadRequest, "invalid name")
		return
	}
	scopeType, scopeID := a.resolveDefinitionScope()
	_ = s.deleteWorkspaceFilePrefix(c.Request.Context(), owner, scopeType, scopeID, "skills/"+name, ws)
	c.Status(http.StatusNoContent)
}

func (s *Server) marketplaceInstallSkill(c *gin.Context) {
	// Compatibility shim: install into the agent's linked workspace or agent scope.
	owner := currentUserID(c)
	agentID := c.Param("id")
	a, err := s.loadAgent(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	var req marketplaceInstallReq
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	scopeType, scopeID := a.resolveDefinitionScope()
	ws, _, _ := s.resolveAgentWorkspace(c.Request.Context(), owner, agentID)
	if err := s.installMarketplaceSkill(c.Request.Context(), owner, scopeType, scopeID, ws, req); err != nil {
		writeErr(c, http.StatusBadRequest, err.Error())
		return
	}
	c.JSON(http.StatusOK, gin.H{"installed": req.SkillName, "origin": "marketplace"})
}

func (s *Server) toolsBuiltinCatalog(c *gin.Context) {
	if _, err := s.loadAgent(c.Request.Context(), currentUserID(c), c.Param("id")); err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	c.JSON(http.StatusOK, builtinToolCatalog)
}

func (s *Server) toolsMcpCatalog(c *gin.Context) {
	if _, err := s.loadAgent(c.Request.Context(), currentUserID(c), c.Param("id")); err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	c.JSON(http.StatusOK, builtinMcpCatalog)
}

func (s *Server) toolsActive(c *gin.Context) {
	a, err := s.loadAgent(c.Request.Context(), currentUserID(c), c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	tools := []gin.H{}
	if raw := parseJSONRaw(deref(a.ToolsJSON)); raw != nil {
		if arr, ok := raw.([]any); ok {
			for _, item := range arr {
				m, ok := item.(map[string]any)
				if !ok {
					continue
				}
				typ, _ := m["type"].(string)
				if typ == "agent_toolset" {
					if configs, ok := m["configs"].([]any); ok {
						for _, cfg := range configs {
							cm, ok := cfg.(map[string]any)
							if !ok {
								continue
							}
							enabled, _ := cm["enabled"].(bool)
							if en, ok := cm["enabled"].(bool); ok {
								enabled = en
							} else {
								enabled = true
							}
							name, _ := cm["name"].(string)
							if name != "" && enabled {
								tools = append(tools, gin.H{"name": name, "source": "built-in"})
							}
						}
					}
				}
				if typ == "mcp_toolset" {
					name, _ := m["mcpServerName"].(string)
					if name != "" {
						tools = append(tools, gin.H{"name": name, "source": "mcp:" + name})
					}
				}
			}
		}
	}
	if raw := parseJSONRaw(deref(a.McpServersJSON)); raw != nil {
		if arr, ok := raw.([]any); ok {
			for _, item := range arr {
				m, ok := item.(map[string]any)
				if !ok {
					continue
				}
				name, _ := m["name"].(string)
				if name == "" {
					continue
				}
				found := false
				for _, t := range tools {
					if t["name"] == name {
						found = true
						break
					}
				}
				if !found {
					desc, _ := m["url"].(string)
					if desc == "" {
						desc, _ = m["command"].(string)
					}
					tools = append(tools, gin.H{"name": name, "description": desc, "source": "mcp:" + name})
				}
			}
		}
	}
	c.JSON(http.StatusOK, gin.H{"tools": tools})
}

func (s *Server) cloneAgent(c *gin.Context) {
	owner := currentUserID(c)
	srcID := c.Param("id")
	src, err := s.loadAgent(c.Request.Context(), owner, srcID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	var req struct {
		NewAgentID string `json:"newAgentId"`
		Name       string `json:"name"`
	}
	_ = c.ShouldBindJSON(&req)
	newID := strings.TrimSpace(req.NewAgentID)
	if newID == "" {
		newID = shortID("ag_")
	}
	name := strings.TrimSpace(req.Name)
	if name == "" {
		name = src.Name + " (copy)"
	}
	srcWS := ""
	if src.WorkspacePath != nil {
		srcWS = *src.WorkspacePath
	}
	if srcWS == "" {
		srcWS = filepath.Join(s.cfg.WorkspaceRoot, owner, srcID)
	}
	dstWS := filepath.Join(s.cfg.WorkspaceRoot, owner, newID)
	_ = os.MkdirAll(dstWS, 0o755)
	_ = copyDir(srcWS, dstWS)

	now := nowMillis()
	maxIters := 20
	if src.MaxIters != nil {
		maxIters = *src.MaxIters
	}
	wsID := ""
	if src.WorkspaceID != nil {
		wsID = *src.WorkspaceID
	}
	_, err = s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO agents (owner_id, agent_id, workspace_path, workspace_id, name, description, sys_prompt, model,
		 max_iters, tools_json, mcp_servers_json, skills_json, multiagent_json,
		 default_environment_id, default_vault_ids_json, default_memory_store_ids_json,
		 head_version, created_at, updated_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,1,$17,$17)`,
		owner, newID, dstWS, nullStr(wsID), name, src.Description, src.SysPrompt, src.Model,
		maxIters, deref(src.ToolsJSON), deref(src.McpServersJSON), deref(src.SkillsJSON),
		deref(src.MultiagentJSON),
		src.DefaultEnvironmentID, src.DefaultVaultIDsJSON, src.DefaultMemoryStoreIDsJSON,
		now)
	if err != nil {
		writeTextErr(c, http.StatusInternalServerError, err.Error())
		return
	}

	var snap string
	err = s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT snapshot_json FROM agent_versions WHERE owner_id=$1 AND agent_id=$2 AND version=$3`,
		owner, srcID, src.HeadVersion).Scan(&snap)
	if err != nil || snap == "" {
		snap = mustJSON(s.agentSnapshot(owner, newID, name, deref(src.Description), deref(src.SysPrompt),
			deref(src.Model), maxIters, parseJSONRaw(deref(src.ToolsJSON)), parseJSONRaw(deref(src.McpServersJSON)),
			parseJSONRaw(deref(src.SkillsJSON)), parseJSONRaw(deref(src.MultiagentJSON)), dstWS, wsID,
			deref(src.DefaultEnvironmentID),
			parseStringSlice(deref(src.DefaultVaultIDsJSON)),
			parseStringSlice(deref(src.DefaultMemoryStoreIDsJSON)),
			1, now, now))
	} else {
		var m map[string]any
		if jsonErr := jsonUnmarshal(snap, &m); jsonErr == nil {
			m["id"] = newID
			m["name"] = name
			m["workspacePath"] = dstWS
			m["workspaceId"] = nullStr(wsID)
			m["version"] = 1
			m["createdAt"] = now
			m["updatedAt"] = now
			m["forkOf"] = srcID
			snap = mustJSON(m)
		}
	}
	_, _ = s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO agent_versions (owner_id, agent_id, version, snapshot_json, created_at) VALUES ($1,$2,1,$3,$4)`,
		owner, newID, snap, now)

	out, err := s.loadAgent(c.Request.Context(), owner, newID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	c.JSON(http.StatusOK, out.toJSON())
}

func jsonUnmarshal(s string, v any) error {
	return jsonUnmarshalBytes([]byte(s), v)
}

// local helpers to avoid import cycle confusion — use encoding/json via aliases in this file.
func jsonUnmarshalBytes(b []byte, v any) error {
	return json.Unmarshal(b, v)
}

func (s *Server) listShares(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	if _, err := s.loadAgent(c.Request.Context(), owner, agentID); err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	rows, err := s.db.Pool.Query(c.Request.Context(),
		`SELECT grantee_type, grantee_id, tier, created_at FROM agent_shares
		 WHERE owner_id=$1 AND agent_id=$2 ORDER BY created_at`, owner, agentID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []gin.H{}
	for rows.Next() {
		var gType, gID, tier string
		var created int64
		if err := rows.Scan(&gType, &gID, &tier, &created); err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		list = append(list, gin.H{
			"granteeType": gType,
			"granteeId":   gID,
			"tier":        tier,
			"createdAt":   created,
			"createdBy":   owner,
		})
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) addShare(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	if _, err := s.loadAgent(c.Request.Context(), owner, agentID); err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	var req struct {
		GranteeType string  `json:"granteeType"`
		GranteeID   *string `json:"granteeId"`
		Tier        string  `json:"tier"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.GranteeType == "" || req.Tier == "" {
		writeTextErr(c, http.StatusBadRequest, "granteeType and tier required")
		return
	}
	gID := ""
	if req.GranteeID != nil {
		gID = *req.GranteeID
	}
	now := nowMillis()
	_, err := s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO agent_shares (owner_id, agent_id, grantee_type, grantee_id, tier, created_at)
		 VALUES ($1,$2,$3,$4,$5,$6)
		 ON CONFLICT (owner_id, agent_id, grantee_type, grantee_id)
		 DO UPDATE SET tier=EXCLUDED.tier`,
		owner, agentID, req.GranteeType, gID, req.Tier, now)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	s.listShares(c)
}

func (s *Server) revokeShare(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`DELETE FROM agent_shares WHERE owner_id=$1 AND agent_id=$2 AND grantee_type=$3 AND grantee_id=$4`,
		owner, agentID, c.Param("granteeType"), c.Param("granteeId"))
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		// still 204 — idempotent revoke
	}
	c.Status(http.StatusNoContent)
}

func copyDir(src, dst string) error {
	return filepath.WalkDir(src, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			if os.IsNotExist(err) {
				return nil
			}
			return err
		}
		rel, err := filepath.Rel(src, path)
		if err != nil {
			return err
		}
		target := filepath.Join(dst, rel)
		if d.IsDir() {
			return os.MkdirAll(target, 0o755)
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return err
		}
		in, err := os.Open(path)
		if err != nil {
			return err
		}
		defer in.Close()
		out, err := os.Create(target)
		if err != nil {
			return err
		}
		defer out.Close()
		_, err = io.Copy(out, in)
		return err
	})
}

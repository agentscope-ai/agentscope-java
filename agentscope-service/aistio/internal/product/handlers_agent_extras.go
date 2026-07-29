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
	ws, _, err := s.resolveAgentWorkspace(c.Request.Context(), owner, c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	dir := filepath.Join(ws, "skills")
	entries, err := os.ReadDir(dir)
	if err != nil {
		c.JSON(http.StatusOK, []any{})
		return
	}
	list := []gin.H{}
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
	c.JSON(http.StatusOK, list)
}

func (s *Server) getWorkspaceSkill(c *gin.Context) {
	owner := currentUserID(c)
	ws, _, err := s.resolveAgentWorkspace(c.Request.Context(), owner, c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	name := c.Param("name")
	if strings.Contains(name, "..") || name == "" {
		writeErr(c, http.StatusBadRequest, "invalid name")
		return
	}
	dir := skillDir(ws, name)
	mdPath := filepath.Join(dir, "SKILL.md")
	b, err := os.ReadFile(mdPath)
	if err != nil {
		writeErr(c, http.StatusNotFound, "skill not found")
		return
	}
	_, desc := parseSkillFrontmatter(string(b))
	resources := map[string]string{}
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
	displayName, _ := parseSkillFrontmatter(string(b))
	if displayName == "" {
		displayName = name
	}
	c.JSON(http.StatusOK, gin.H{
		"name":        displayName,
		"description": nullStr(desc),
		"markdown":    string(b),
		"resources":   resources,
	})
}

func (s *Server) putWorkspaceSkill(c *gin.Context) {
	owner := currentUserID(c)
	ws, _, err := s.resolveAgentWorkspace(c.Request.Context(), owner, c.Param("id"))
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
	dir := skillDir(ws, name)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if err := os.WriteFile(filepath.Join(dir, "SKILL.md"), []byte(req.Markdown), 0o644); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	for rel, content := range req.Resources {
		relClean, err := cleanRelPath(rel)
		if err != nil || relClean == "" || relClean == "SKILL.md" {
			continue
		}
		full := filepath.Join(dir, filepath.FromSlash(relClean))
		if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
			continue
		}
		_ = os.WriteFile(full, []byte(content), 0o644)
	}
	c.JSON(http.StatusOK, skillInfoFromDir(dir, name))
}

func (s *Server) deleteWorkspaceSkill(c *gin.Context) {
	owner := currentUserID(c)
	ws, _, err := s.resolveAgentWorkspace(c.Request.Context(), owner, c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	name := c.Param("name")
	if strings.Contains(name, "..") || name == "" {
		writeErr(c, http.StatusBadRequest, "invalid name")
		return
	}
	if err := os.RemoveAll(skillDir(ws, name)); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) marketplaceInstallSkill(c *gin.Context) {
	c.JSON(http.StatusNotImplemented, gin.H{"message": "marketplace removed"})
}

func (s *Server) toolsBuiltinCatalog(c *gin.Context) {
	if _, err := s.loadAgent(c.Request.Context(), currentUserID(c), c.Param("id")); err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	c.JSON(http.StatusOK, []gin.H{
		{"id": "shell", "description": "Execute a shell command", "group": "filesystem"},
		{"id": "read_file", "description": "Read a file from the workspace", "group": "filesystem"},
		{"id": "write_file", "description": "Write a file in the workspace", "group": "filesystem"},
		{"id": "list_dir", "description": "List directory contents", "group": "filesystem"},
	})
}

func (s *Server) toolsMcpCatalog(c *gin.Context) {
	if _, err := s.loadAgent(c.Request.Context(), currentUserID(c), c.Param("id")); err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	c.JSON(http.StatusOK, []any{})
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
	_, err = s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO agents (owner_id, agent_id, workspace_path, name, description, sys_prompt, model,
		 max_iters, tools_json, mcp_servers_json, skills_json, multiagent_json, head_version, created_at, updated_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,1,$13,$13)`,
		owner, newID, dstWS, name, src.Description, src.SysPrompt, src.Model,
		maxIters, deref(src.ToolsJSON), deref(src.McpServersJSON), deref(src.SkillsJSON),
		deref(src.MultiagentJSON), now)
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
			parseJSONRaw(deref(src.SkillsJSON)), parseJSONRaw(deref(src.MultiagentJSON)), dstWS, 1, now, now))
	} else {
		var m map[string]any
		if jsonErr := jsonUnmarshal(snap, &m); jsonErr == nil {
			m["id"] = newID
			m["name"] = name
			m["workspacePath"] = dstWS
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

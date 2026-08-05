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
	"encoding/json"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/gin-gonic/gin"
)

type marketplaceInstallReq struct {
	MarketplaceID string `json:"marketplaceId"`
	SkillName     string `json:"skillName"`
	Version       string `json:"version"`
}

type marketplaceRow struct {
	OwnerID       string
	MarketplaceID string
	Name          string
	Type          string
	ConfigJSON    *string
	Enabled       bool
	CreatedAt     int64
	UpdatedAt     int64
}

func (s *Server) registerMarketplaces(r gin.IRouter) {
	r.GET("/api/marketplaces", s.listMarketplaces)
	r.POST("/api/marketplaces", s.createMarketplace)
	r.GET("/api/marketplaces/:id", s.getMarketplace)
	r.DELETE("/api/marketplaces/:id", s.deleteMarketplace)
	r.GET("/api/marketplaces/:id/skills", s.browseMarketplaceSkills)
}

func (m marketplaceRow) toJSON() gin.H {
	return gin.H{
		"id":        m.MarketplaceID,
		"name":      m.Name,
		"type":      m.Type,
		"config":    parseJSONRaw(deref(m.ConfigJSON)),
		"enabled":   m.Enabled,
		"ownerId":   m.OwnerID,
		"createdAt": m.CreatedAt,
		"updatedAt": m.UpdatedAt,
	}
}

func (s *Server) loadMarketplace(ctx context.Context, owner, id string) (marketplaceRow, error) {
	var m marketplaceRow
	err := s.db.Pool.QueryRow(ctx,
		`SELECT owner_id, marketplace_id, name, type, config_json, enabled, created_at, updated_at
		 FROM marketplaces WHERE owner_id=$1 AND marketplace_id=$2`,
		owner, id).Scan(
		&m.OwnerID, &m.MarketplaceID, &m.Name, &m.Type, &m.ConfigJSON, &m.Enabled,
		&m.CreatedAt, &m.UpdatedAt)
	return m, err
}

func (s *Server) listMarketplaces(c *gin.Context) {
	owner := currentUserID(c)
	rows, err := s.db.Pool.Query(c.Request.Context(),
		`SELECT owner_id, marketplace_id, name, type, config_json, enabled, created_at, updated_at
		 FROM marketplaces WHERE owner_id=$1 ORDER BY updated_at DESC`, owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []gin.H{}
	for rows.Next() {
		var m marketplaceRow
		if err := rows.Scan(&m.OwnerID, &m.MarketplaceID, &m.Name, &m.Type, &m.ConfigJSON,
			&m.Enabled, &m.CreatedAt, &m.UpdatedAt); err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		list = append(list, m.toJSON())
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) createMarketplace(c *gin.Context) {
	var req struct {
		Name   string `json:"name"`
		Type   string `json:"type"`
		Config any    `json:"config"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Name == "" {
		writeErr(c, http.StatusBadRequest, "name required")
		return
	}
	typ := strings.ToLower(strings.TrimSpace(req.Type))
	if typ != "git" && typ != "nacos" {
		writeErr(c, http.StatusBadRequest, "type must be git or nacos")
		return
	}
	owner := currentUserID(c)
	id := shortID("mkt_")
	now := nowMillis()
	_, err := s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO marketplaces (owner_id, marketplace_id, name, type, config_json, enabled, created_at, updated_at)
		 VALUES ($1,$2,$3,$4,$5,TRUE,$6,$6)`,
		owner, id, req.Name, typ, mustJSON(req.Config), now)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	m, _ := s.loadMarketplace(c.Request.Context(), owner, id)
	c.JSON(http.StatusOK, m.toJSON())
}

func (s *Server) getMarketplace(c *gin.Context) {
	m, err := s.loadMarketplace(c.Request.Context(), currentUserID(c), c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "marketplace not found")
		return
	}
	c.JSON(http.StatusOK, m.toJSON())
}

func (s *Server) deleteMarketplace(c *gin.Context) {
	owner := currentUserID(c)
	_, err := s.db.Pool.Exec(c.Request.Context(),
		`DELETE FROM marketplaces WHERE owner_id=$1 AND marketplace_id=$2`,
		owner, c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) browseMarketplaceSkills(c *gin.Context) {
	owner := currentUserID(c)
	m, err := s.loadMarketplace(c.Request.Context(), owner, c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "marketplace not found")
		return
	}
	skills, err := s.listMarketplaceSkillNames(c.Request.Context(), m)
	if err != nil {
		writeErr(c, http.StatusBadRequest, err.Error())
		return
	}
	c.JSON(http.StatusOK, skills)
}

func (s *Server) wsMarketplaceInstall(c *gin.Context) {
	owner := currentUserID(c)
	wsID := c.Param("id")
	if _, err := s.loadWorkspace(c.Request.Context(), owner, wsID); err != nil {
		writeErr(c, http.StatusNotFound, "workspace not found")
		return
	}
	var req marketplaceInstallReq
	if err := c.ShouldBindJSON(&req); err != nil || req.SkillName == "" || req.MarketplaceID == "" {
		writeErr(c, http.StatusBadRequest, "marketplaceId and skillName required")
		return
	}
	disk := s.workspaceDiskRoot(owner, wsID)
	if err := s.installMarketplaceSkill(c.Request.Context(), owner, scopeTypeWorkspace, wsID, disk, req); err != nil {
		writeErr(c, http.StatusBadRequest, err.Error())
		return
	}
	// Add skill ref on workspace.
	w, _ := s.loadWorkspace(c.Request.Context(), owner, wsID)
	arr, _ := parseJSONRaw(deref(w.SkillsJSON)).([]any)
	arr = append(arr, gin.H{"type": "marketplace", "name": req.SkillName, "id": req.SkillName, "version": req.Version})
	_, _ = s.db.Pool.Exec(c.Request.Context(),
		`UPDATE workspaces SET skills_json=$1, head_version=head_version+1, updated_at=$2
		 WHERE owner_id=$3 AND workspace_id=$4`,
		mustJSON(arr), nowMillis(), owner, wsID)
	s.rematerializeLinkedAgents(c.Request.Context(), owner, wsID)
	c.JSON(http.StatusOK, gin.H{"installed": req.SkillName, "origin": "marketplace"})
}

func (s *Server) installMarketplaceSkill(ctx context.Context, owner, scopeType, scopeID, diskRoot string, req marketplaceInstallReq) error {
	m, err := s.loadMarketplace(ctx, owner, req.MarketplaceID)
	if err != nil {
		return fmt.Errorf("marketplace not found")
	}
	if !m.Enabled {
		return fmt.Errorf("marketplace disabled")
	}
	switch m.Type {
	case "git":
		return s.installGitMarketplaceSkill(ctx, owner, scopeType, scopeID, diskRoot, m, req.SkillName)
	case "nacos":
		return s.installNacosMarketplaceSkill(ctx, owner, scopeType, scopeID, diskRoot, m, req.SkillName)
	default:
		return fmt.Errorf("unsupported marketplace type: %s", m.Type)
	}
}

func marketplaceConfigMap(m marketplaceRow) map[string]any {
	raw := parseJSONRaw(deref(m.ConfigJSON))
	if mm, ok := raw.(map[string]any); ok {
		return mm
	}
	return map[string]any{}
}

func (s *Server) listMarketplaceSkillNames(ctx context.Context, m marketplaceRow) ([]gin.H, error) {
	cfg := marketplaceConfigMap(m)
	switch m.Type {
	case "git":
		root, err := s.ensureGitMarketplaceClone(ctx, m, cfg)
		if err != nil {
			return nil, err
		}
		skillsRoot := "skills"
		if v, ok := cfg["skillsRoot"].(string); ok && v != "" {
			skillsRoot = v
		}
		dir := filepath.Join(root, skillsRoot)
		entries, err := os.ReadDir(dir)
		if err != nil {
			return []gin.H{}, nil
		}
		out := []gin.H{}
		for _, e := range entries {
			if !e.IsDir() || strings.HasPrefix(e.Name(), ".") {
				continue
			}
			md := filepath.Join(dir, e.Name(), "SKILL.md")
			if !fileExists(md) {
				continue
			}
			b, _ := os.ReadFile(md)
			name, desc := parseSkillFrontmatter(string(b))
			if name == "" {
				name = e.Name()
			}
			out = append(out, gin.H{"name": name, "dirName": e.Name(), "description": nullStr(desc), "type": "git"})
		}
		return out, nil
	case "nacos":
		// Nacos browse lists configured skill names from config.skillNames[] until a live client is wired.
		names, _ := cfg["skillNames"].([]any)
		out := []gin.H{}
		for _, n := range names {
			if s, ok := n.(string); ok && s != "" {
				out = append(out, gin.H{"name": s, "dirName": s, "description": "Nacos skill", "type": "nacos"})
			}
		}
		return out, nil
	default:
		return nil, fmt.Errorf("unsupported type")
	}
}

func (s *Server) ensureGitMarketplaceClone(ctx context.Context, m marketplaceRow, cfg map[string]any) (string, error) {
	remote, _ := cfg["remoteUrl"].(string)
	if remote == "" {
		return "", fmt.Errorf("git marketplace requires config.remoteUrl")
	}
	branch, _ := cfg["branch"].(string)
	if branch == "" {
		branch = "main"
	}
	cache := filepath.Join(s.cfg.WorkspaceRoot, "_marketplaces", m.OwnerID, m.MarketplaceID)
	if fileExists(filepath.Join(cache, ".git")) {
		cmd := exec.CommandContext(ctx, "git", "-C", cache, "pull", "--ff-only")
		_ = cmd.Run()
		return cache, nil
	}
	_ = os.MkdirAll(filepath.Dir(cache), 0o755)
	args := []string{"clone", "--depth", "1", "--branch", branch, remote, cache}
	cmd := exec.CommandContext(ctx, "git", args...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("git clone failed: %s (%w)", strings.TrimSpace(string(out)), err)
	}
	return cache, nil
}

func (s *Server) installGitMarketplaceSkill(ctx context.Context, owner, scopeType, scopeID, diskRoot string, m marketplaceRow, skillName string) error {
	cfg := marketplaceConfigMap(m)
	root, err := s.ensureGitMarketplaceClone(ctx, m, cfg)
	if err != nil {
		return err
	}
	skillsRoot := "skills"
	if v, ok := cfg["skillsRoot"].(string); ok && v != "" {
		skillsRoot = v
	}
	src := filepath.Join(root, skillsRoot, skillName)
	mdPath := filepath.Join(src, "SKILL.md")
	b, err := os.ReadFile(mdPath)
	if err != nil {
		return fmt.Errorf("skill %s not found in marketplace", skillName)
	}
	if err := s.putWorkspaceFile(ctx, owner, scopeType, scopeID, "skills/"+skillName+"/SKILL.md", string(b), diskRoot); err != nil {
		return err
	}
	_ = filepath.WalkDir(src, func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		rel, _ := filepath.Rel(src, path)
		rel = filepath.ToSlash(rel)
		if rel == "SKILL.md" {
			return nil
		}
		content, rerr := os.ReadFile(path)
		if rerr != nil {
			return nil
		}
		_ = s.putWorkspaceFile(ctx, owner, scopeType, scopeID, "skills/"+skillName+"/"+rel, string(content), diskRoot)
		return nil
	})
	return nil
}

func (s *Server) installNacosMarketplaceSkill(ctx context.Context, owner, scopeType, scopeID, diskRoot string, m marketplaceRow, skillName string) error {
	cfg := marketplaceConfigMap(m)
	// Prefer inline skillBodies map in config for control-plane install without Java Nacos client.
	if bodies, ok := cfg["skillBodies"].(map[string]any); ok {
		if body, ok := bodies[skillName].(string); ok && body != "" {
			return s.putWorkspaceFile(ctx, owner, scopeType, scopeID, "skills/"+skillName+"/SKILL.md", body, diskRoot)
		}
	}
	// Placeholder SKILL.md documenting nacos coordinates for DP-side NacosSkillRepository resolve.
	serverAddr, _ := cfg["serverAddr"].(string)
	dataID, _ := cfg["dataId"].(string)
	group, _ := cfg["group"].(string)
	md := fmt.Sprintf("---\nname: %s\ndescription: Installed from Nacos marketplace\n---\n\n# %s\n\nSource: nacos serverAddr=%s dataId=%s group=%s\n",
		skillName, skillName, serverAddr, dataID, group)
	meta, _ := json.Marshal(gin.H{"marketplaceId": m.MarketplaceID, "type": "nacos", "skillName": skillName, "config": cfg})
	_ = s.putWorkspaceFile(ctx, owner, scopeType, scopeID, "skills/"+skillName+"/.marketplace.json", string(meta), diskRoot)
	return s.putWorkspaceFile(ctx, owner, scopeType, scopeID, "skills/"+skillName+"/SKILL.md", md, diskRoot)
}

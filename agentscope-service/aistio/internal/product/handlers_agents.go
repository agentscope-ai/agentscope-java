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
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
)

func (s *Server) registerAgents(r gin.IRouter) {
	r.GET("/api/agents", s.listAgents)
	r.POST("/api/agents", s.createAgent)
	r.GET("/api/agents/:id", s.getAgent)
	r.PUT("/api/agents/:id", s.updateAgent)
	r.DELETE("/api/agents/:id", s.deleteAgent)
	r.POST("/api/agents/:id/archive", s.archiveAgent)
	r.GET("/api/agents/:id/versions", s.listAgentVersions)
	r.GET("/api/agents/:id/versions/:version", s.getAgentVersion)
}

type agentCreateReq struct {
	ID            string `json:"id"`
	Name          string `json:"name"`
	Description   string `json:"description"`
	System        string `json:"system"`
	SysPrompt     string `json:"sysPrompt"`
	Model         string `json:"model"`
	MaxIters      *int   `json:"maxIters"`
	Tools         any    `json:"tools"`
	McpServers    any    `json:"mcpServers"`
	Skills        any    `json:"skills"`
	Multiagent    any    `json:"multiagent"`
	WorkspacePath string  `json:"workspacePath"`
	WorkspaceID   *string `json:"workspaceId"`
	Version       *int    `json:"version"`
	// Session defaults: omitted on update keeps previous; "" / [] clears.
	DefaultEnvironmentID  *string   `json:"defaultEnvironmentId"`
	DefaultVaultIDs       *[]string `json:"defaultVaultIds"`
	DefaultMemoryStoreIDs *[]string `json:"defaultMemoryStoreIds"`
}

type agentRow struct {
	OwnerID                    string
	AgentID                    string
	WorkspacePath              *string
	WorkspaceID                *string
	Name                       string
	Description                *string
	SysPrompt                  *string
	Model                      *string
	MaxIters                   *int
	ToolsJSON                  *string
	McpServersJSON             *string
	SkillsJSON                 *string
	MultiagentJSON             *string
	DefaultEnvironmentID       *string
	DefaultVaultIDsJSON        *string
	DefaultMemoryStoreIDsJSON  *string
	HeadVersion                int
	ArchivedAt                 *int64
	CreatedAt                  int64
	UpdatedAt                  int64

	// EffectiveTier 是当前请求用户对该 Agent 持有的控制台 ShareTier
	// （CLONE/RUN/EDIT），不持久化；空串表示"无权限/未知"。
	EffectiveTier string
}

func (a agentRow) toJSON() gin.H {
	system := ""
	if a.SysPrompt != nil {
		system = *a.SysPrompt
	}
	desc := ""
	if a.Description != nil {
		desc = *a.Description
	}
	model := ""
	if a.Model != nil {
		model = *a.Model
	}
	ws := ""
	if a.WorkspacePath != nil {
		ws = *a.WorkspacePath
	}
	wsID := ""
	if a.WorkspaceID != nil {
		wsID = *a.WorkspaceID
	}
	out := gin.H{
		"id":            a.AgentID,
		"name":          a.Name,
		"description":   desc,
		"system":        system,
		"model":         model,
		"maxIters":      a.MaxIters,
		"tools":         parseJSONRaw(deref(a.ToolsJSON)),
		"mcpServers":    parseJSONRaw(deref(a.McpServersJSON)),
		"skills":        parseJSONRaw(deref(a.SkillsJSON)),
		"scope":         "user",
		"ownerId":       a.OwnerID,
		"createdAt":     a.CreatedAt,
		"updatedAt":     a.UpdatedAt,
		"workspacePath": ws,
		"workspaceId":   nullStr(wsID),
		"defaultEnvironmentId":  nullStr(deref(a.DefaultEnvironmentID)),
		"defaultVaultIds":       parseStringSlice(deref(a.DefaultVaultIDsJSON)),
		"defaultMemoryStoreIds": parseStringSlice(deref(a.DefaultMemoryStoreIDsJSON)),
		"version":       a.HeadVersion,
		"archivedAt":    nullMillis(a.ArchivedAt),
	}
	if a.MultiagentJSON != nil && *a.MultiagentJSON != "" && *a.MultiagentJSON != "null" {
		out["multiagent"] = parseJSONRaw(*a.MultiagentJSON)
	}
	// tierForCurrentUser 控制控制台 Agent 详情标签页的渲染：EDIT 全部可编辑，
	// RUN 标签页只读，CLONE 仅显示克隆卡片；无权限时不输出该字段。
	if a.EffectiveTier != "" {
		out["tierForCurrentUser"] = a.EffectiveTier
	}
	return out
}

// agentTierForUser 解析当前用户对某 Agent 持有的控制台 ShareTier：
// owner 本人 → EDIT；否则取 agent_shares 中匹配的最高档授权（授权给该用户的
// USER 授权，或共享给所有人的 WORKSPACE '*' 授权）。无任何授权时返回空串。
// 档位排序需与控制台保持一致（CLONE < RUN < EDIT）。
func (s *Server) agentTierForUser(ctx context.Context, ownerID, agentID, userID string) string {
	if userID != "" && userID == ownerID {
		return "EDIT"
	}
	if userID == "" {
		return ""
	}
	var tier string
	err := s.db.Pool.QueryRow(ctx,
		`SELECT tier FROM agent_shares
		 WHERE owner_id=$1 AND agent_id=$2
		   AND ((grantee_type='WORKSPACE' AND grantee_id='*')
		     OR (grantee_type='USER' AND grantee_id=$3))
		 ORDER BY CASE tier WHEN 'EDIT' THEN 3 WHEN 'RUN' THEN 2 WHEN 'CLONE' THEN 1 ELSE 0 END DESC
		 LIMIT 1`,
		ownerID, agentID, userID).Scan(&tier)
	if err != nil {
		return ""
	}
	return tier
}

func deref(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}

func (s *Server) scanAgent(rows interface {
	Scan(dest ...any) error
}) (agentRow, error) {
	var a agentRow
	err := rows.Scan(
		&a.OwnerID, &a.AgentID, &a.WorkspacePath, &a.WorkspaceID, &a.Name, &a.Description,
		&a.SysPrompt, &a.Model, &a.MaxIters, &a.ToolsJSON, &a.McpServersJSON,
		&a.SkillsJSON, &a.MultiagentJSON,
		&a.DefaultEnvironmentID, &a.DefaultVaultIDsJSON, &a.DefaultMemoryStoreIDsJSON,
		&a.HeadVersion, &a.ArchivedAt,
		&a.CreatedAt, &a.UpdatedAt,
	)
	return a, err
}

const agentSelect = `SELECT owner_id, agent_id, workspace_path, workspace_id, name, description, sys_prompt, model,
	max_iters, tools_json, mcp_servers_json, skills_json, multiagent_json,
	default_environment_id, default_vault_ids_json, default_memory_store_ids_json,
	head_version, archived_at, created_at, updated_at FROM agents`

// agentVisibilityClause 把可见范围限定为：用户自己拥有的 Agent（$1），
// 或通过 agent_shares 分享给该用户的 Agent（授权给该用户的 USER 授权，
// 或共享给所有人的 WORKSPACE '*' 授权）。
const agentVisibilityClause = `(a.owner_id=$1
	OR EXISTS (SELECT 1 FROM agent_shares s
		WHERE s.owner_id=a.owner_id AND s.agent_id=a.agent_id
		  AND ((s.grantee_type='WORKSPACE' AND s.grantee_id='*')
		    OR (s.grantee_type='USER' AND s.grantee_id=$1))))`

// agentTierExpr 在 SQL 中按行计算当前用户的有效 ShareTier：owner 为 EDIT；
// 否则取匹配授权中的最高档（CLONE < RUN < EDIT）。
const agentTierExpr = `COALESCE(
	CASE WHEN a.owner_id=$1 THEN 'EDIT' END,
	(SELECT s.tier FROM agent_shares s
		WHERE s.owner_id=a.owner_id AND s.agent_id=a.agent_id
		  AND ((s.grantee_type='WORKSPACE' AND s.grantee_id='*')
		    OR (s.grantee_type='USER' AND s.grantee_id=$1))
		ORDER BY CASE s.tier WHEN 'EDIT' THEN 3 WHEN 'RUN' THEN 2 WHEN 'CLONE' THEN 1 ELSE 0 END DESC
		LIMIT 1))`

func (s *Server) listAgents(c *gin.Context) {
	owner := currentUserID(c)
	limit, offset, ok := pageParams(c)
	if !ok {
		writeErr(c, http.StatusBadRequest, "invalid limit/offset")
		return
	}
	var total int64
	if err := s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT COUNT(*) FROM agents a WHERE a.archived_at IS NULL AND `+agentVisibilityClause,
		owner).Scan(&total); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	writeTotalCount(c, total)
	q := `SELECT a.*, ` + agentTierExpr + ` AS effective_tier
		FROM (` + agentSelect + `) a
		WHERE a.archived_at IS NULL AND ` + agentVisibilityClause + `
		ORDER BY a.updated_at DESC`
	args := []any{owner}
	q, args = appendPage(q, limit, offset, args)
	rows, err := s.db.Pool.Query(c.Request.Context(), q, args...)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []gin.H{}
	for rows.Next() {
		var a agentRow
		var tier *string
		if err := rows.Scan(
			&a.OwnerID, &a.AgentID, &a.WorkspacePath, &a.WorkspaceID, &a.Name, &a.Description,
			&a.SysPrompt, &a.Model, &a.MaxIters, &a.ToolsJSON, &a.McpServersJSON,
			&a.SkillsJSON, &a.MultiagentJSON,
			&a.DefaultEnvironmentID, &a.DefaultVaultIDsJSON, &a.DefaultMemoryStoreIDsJSON,
			&a.HeadVersion, &a.ArchivedAt, &a.CreatedAt, &a.UpdatedAt, &tier); err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		a.EffectiveTier = deref(tier)
		list = append(list, a.toJSON())
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) createAgent(c *gin.Context) {
	var req agentCreateReq
	if err := c.ShouldBindJSON(&req); err != nil || req.Name == "" {
		writeTextErr(c, http.StatusBadRequest, "name required")
		return
	}
	owner := currentUserID(c)
	agentID := req.ID
	if agentID == "" {
		agentID = shortID("ag_")
	}
	sys := req.System
	if sys == "" {
		sys = req.SysPrompt
	}
	maxIters := 20
	if req.MaxIters != nil {
		maxIters = *req.MaxIters
	}
	ws := req.WorkspacePath
	if ws == "" {
		ws = filepath.Join(s.cfg.WorkspaceRoot, owner, agentID)
	}
	_ = os.MkdirAll(ws, 0o755)

	now := nowMillis()
	toolsAny := req.Tools
	mcpAny := req.McpServers
	skillsAny := req.Skills
	sysPrompt := sys
	wsID := ""
	if req.WorkspaceID != nil {
		wsID = strings.TrimSpace(*req.WorkspaceID)
	}
	if wsID != "" {
		mat, merr := s.materializeFromWorkspace(c.Request.Context(), owner, wsID)
		if merr != nil {
			writeTextErr(c, http.StatusBadRequest, merr.Error())
			return
		}
		if toolsAny == nil {
			toolsAny = mat.Tools
		}
		if mcpAny == nil {
			mcpAny = mat.McpServers
		}
		if skillsAny == nil {
			skillsAny = mat.Skills
		}
		if sysPrompt == "" && mat.System != "" {
			sysPrompt = mat.System
		}
		if mat.DiskPath != "" {
			ws = mat.DiskPath
		}
	}
	tools := mustJSON(toolsAny)
	mcp := mustJSON(mcpAny)
	skills := mustJSON(skillsAny)
	multi := mustJSON(req.Multiagent)
	defEnv := ""
	if req.DefaultEnvironmentID != nil {
		defEnv = strings.TrimSpace(*req.DefaultEnvironmentID)
	}
	defVault := []string{}
	if req.DefaultVaultIDs != nil {
		defVault = *req.DefaultVaultIDs
	}
	defMem := []string{}
	if req.DefaultMemoryStoreIDs != nil {
		defMem = *req.DefaultMemoryStoreIDs
	}

	_, err := s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO agents (owner_id, agent_id, workspace_path, workspace_id, name, description, sys_prompt, model,
		 max_iters, tools_json, mcp_servers_json, skills_json, multiagent_json,
		 default_environment_id, default_vault_ids_json, default_memory_store_ids_json,
		 head_version, created_at, updated_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,1,$17,$17)`,
		owner, agentID, ws, nullStr(wsID), req.Name, nullStr(req.Description), nullStr(sysPrompt), nullStr(req.Model),
		maxIters, tools, mcp, skills, multi,
		nullStr(defEnv), mustJSON(defVault), mustJSON(defMem), now)
	if err != nil {
		writeTextErr(c, http.StatusInternalServerError, err.Error())
		return
	}

	snap := s.agentSnapshot(owner, agentID, req.Name, req.Description, sysPrompt, req.Model, maxIters,
		toolsAny, mcpAny, skillsAny, req.Multiagent, ws, wsID, defEnv, defVault, defMem, 1, now, now)
	_, _ = s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO agent_versions (owner_id, agent_id, version, snapshot_json, created_at) VALUES ($1,$2,1,$3,$4)`,
		owner, agentID, mustJSON(snap), now)

	a, err := s.loadAgent(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	a.EffectiveTier = "EDIT"
	c.JSON(http.StatusOK, a.toJSON())
}

func nullStr(s string) any {
	if s == "" {
		return nil
	}
	return s
}

func (s *Server) agentSnapshot(owner, id, name, desc, system, model string, maxIters int,
	tools, mcp, skills, multi any, ws, workspaceID, defaultEnv string, defaultVault, defaultMem []string,
	version int, created, updated int64) gin.H {
	if defaultVault == nil {
		defaultVault = []string{}
	}
	if defaultMem == nil {
		defaultMem = []string{}
	}
	return gin.H{
		"id": id, "name": name, "description": desc, "system": system, "model": model,
		"maxIters": maxIters, "tools": tools, "mcpServers": mcp, "skills": skills,
		"multiagent": multi, "scope": "user", "ownerId": owner, "workspacePath": ws,
		"workspaceId":           nullStr(workspaceID),
		"defaultEnvironmentId":  nullStr(defaultEnv),
		"defaultVaultIds":       defaultVault,
		"defaultMemoryStoreIds": defaultMem,
		"version":               version, "createdAt": created, "updatedAt": updated,
	}
}

func (s *Server) loadAgent(ctx context.Context, owner, agentID string) (agentRow, error) {
	row := s.db.Pool.QueryRow(ctx, agentSelect+` WHERE owner_id=$1 AND agent_id=$2`, owner, agentID)
	return s.scanAgent(row)
}

func (s *Server) getAgent(c *gin.Context) {
	user := currentUserID(c)
	agentID := c.Param("id")
	if a, err := s.loadAgent(c.Request.Context(), user, agentID); err == nil {
		a.EffectiveTier = "EDIT"
		c.JSON(http.StatusOK, a.toJSON())
		return
	}
	// 非本人所有：仅当 owner 把该 Agent 分享给当前用户时可读。
	a, err := s.loadSharedAgent(c.Request.Context(), user, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	c.JSON(http.StatusOK, a.toJSON())
}

// loadSharedAgent 加载他人所有、但已通过 agent_shares 分享给当前用户的
// Agent。Agent 不存在或用户无任何授权时返回错误。
func (s *Server) loadSharedAgent(ctx context.Context, user, agentID string) (agentRow, error) {
	row := s.db.Pool.QueryRow(ctx,
		agentSelect+` WHERE agent_id=$1 AND archived_at IS NULL`, agentID)
	a, err := s.scanAgent(row)
	if err != nil {
		return a, err
	}
	tier := s.agentTierForUser(ctx, a.OwnerID, agentID, user)
	if tier == "" {
		return a, fmt.Errorf("agent not shared with user")
	}
	a.EffectiveTier = tier
	return a, nil
}

func (s *Server) updateAgent(c *gin.Context) {
	var req agentCreateReq
	if err := c.ShouldBindJSON(&req); err != nil || req.Name == "" {
		writeTextErr(c, http.StatusBadRequest, "name required")
		return
	}
	owner := currentUserID(c)
	agentID := c.Param("id")
	a, err := s.loadAgent(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	if req.Version != nil && *req.Version != a.HeadVersion {
		writeTextErr(c, http.StatusConflict, "version conflict")
		return
	}
	sys := req.System
	if sys == "" {
		sys = req.SysPrompt
	}
	maxIters := 20
	if req.MaxIters != nil {
		maxIters = *req.MaxIters
	} else if a.MaxIters != nil {
		maxIters = *a.MaxIters
	}
	ws := req.WorkspacePath
	if ws == "" && a.WorkspacePath != nil {
		ws = *a.WorkspacePath
	}
	// workspaceId: omitted keeps previous; "" unlinks; non-empty links/rematerializes.
	wsID := ""
	if a.WorkspaceID != nil {
		wsID = *a.WorkspaceID
	}
	if req.WorkspaceID != nil {
		wsID = strings.TrimSpace(*req.WorkspaceID)
	}
	newVer := a.HeadVersion + 1
	now := nowMillis()
	toolsAny := req.Tools
	mcpAny := req.McpServers
	skillsAny := req.Skills
	sysPrompt := sys
	if req.Tools == nil && a.ToolsJSON != nil {
		toolsAny = parseJSONRaw(*a.ToolsJSON)
	}
	if req.McpServers == nil && a.McpServersJSON != nil {
		mcpAny = parseJSONRaw(*a.McpServersJSON)
	}
	if req.Skills == nil && a.SkillsJSON != nil {
		skillsAny = parseJSONRaw(*a.SkillsJSON)
	}
	if wsID != "" {
		mat, merr := s.materializeFromWorkspace(c.Request.Context(), owner, wsID)
		if merr != nil {
			writeTextErr(c, http.StatusBadRequest, merr.Error())
			return
		}
		// Relink / keep link: refresh capability fields from Workspace when caller did not override.
		if req.Tools == nil {
			toolsAny = mat.Tools
		}
		if req.McpServers == nil {
			mcpAny = mat.McpServers
		}
		if req.Skills == nil {
			skillsAny = mat.Skills
		}
		if sysPrompt == "" && mat.System != "" {
			sysPrompt = mat.System
		}
		if mat.DiskPath != "" {
			ws = mat.DiskPath
		}
	} else if req.WorkspaceID != nil {
		// Explicit unlink: restore agent-private disk root.
		ws = filepath.Join(s.cfg.WorkspaceRoot, owner, agentID)
		_ = os.MkdirAll(ws, 0o755)
	}
	tools := mustJSON(toolsAny)
	mcp := mustJSON(mcpAny)
	skills := mustJSON(skillsAny)
	multi := mustJSON(req.Multiagent)
	if req.Multiagent == nil && a.MultiagentJSON != nil {
		multi = *a.MultiagentJSON
	}
	defEnv := deref(a.DefaultEnvironmentID)
	if req.DefaultEnvironmentID != nil {
		defEnv = strings.TrimSpace(*req.DefaultEnvironmentID)
	}
	defVault := parseStringSlice(deref(a.DefaultVaultIDsJSON))
	if req.DefaultVaultIDs != nil {
		defVault = *req.DefaultVaultIDs
		if defVault == nil {
			defVault = []string{}
		}
	}
	defMem := parseStringSlice(deref(a.DefaultMemoryStoreIDsJSON))
	if req.DefaultMemoryStoreIDs != nil {
		defMem = *req.DefaultMemoryStoreIDs
		if defMem == nil {
			defMem = []string{}
		}
	}

	_, err = s.db.Pool.Exec(c.Request.Context(),
		`UPDATE agents SET name=$1, description=$2, sys_prompt=$3, model=$4, max_iters=$5,
		 tools_json=$6, mcp_servers_json=$7, skills_json=$8, multiagent_json=$9,
		 workspace_path=$10, workspace_id=$11,
		 default_environment_id=$12, default_vault_ids_json=$13, default_memory_store_ids_json=$14,
		 head_version=$15, updated_at=$16
		 WHERE owner_id=$17 AND agent_id=$18`,
		req.Name, nullStr(req.Description), nullStr(sysPrompt), nullStr(req.Model), maxIters,
		tools, mcp, skills, multi, nullStr(ws), nullStr(wsID),
		nullStr(defEnv), mustJSON(defVault), mustJSON(defMem),
		newVer, now, owner, agentID)
	if err != nil {
		writeTextErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	snap := s.agentSnapshot(owner, agentID, req.Name, req.Description, sysPrompt, req.Model, maxIters,
		toolsAny, mcpAny, skillsAny, req.Multiagent, ws, wsID, defEnv, defVault, defMem, newVer, a.CreatedAt, now)
	_, _ = s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO agent_versions (owner_id, agent_id, version, snapshot_json, created_at) VALUES ($1,$2,$3,$4,$5)`,
		owner, agentID, newVer, mustJSON(snap), now)

	out, err := s.loadAgent(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	out.EffectiveTier = "EDIT"
	c.JSON(http.StatusOK, out.toJSON())
}

func (s *Server) deleteAgent(c *gin.Context) {
	owner := currentUserID(c)
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`DELETE FROM agents WHERE owner_id=$1 AND agent_id=$2`, owner, c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	_, _ = s.db.Pool.Exec(c.Request.Context(),
		`DELETE FROM agent_versions WHERE owner_id=$1 AND agent_id=$2`, owner, c.Param("id"))
	c.Status(http.StatusNoContent)
}

func (s *Server) archiveAgent(c *gin.Context) {
	owner := currentUserID(c)
	now := nowMillis()
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE agents SET archived_at=$1, updated_at=$1 WHERE owner_id=$2 AND agent_id=$3 AND archived_at IS NULL`,
		now, owner, c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	a, _ := s.loadAgent(c.Request.Context(), owner, c.Param("id"))
	a.EffectiveTier = "EDIT"
	c.JSON(http.StatusOK, a.toJSON())
}

func (s *Server) listAgentVersions(c *gin.Context) {
	owner := currentUserID(c)
	rows, err := s.db.Pool.Query(c.Request.Context(),
		`SELECT version, snapshot_json, created_at FROM agent_versions
		 WHERE owner_id=$1 AND agent_id=$2 ORDER BY version DESC`, owner, c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []gin.H{}
	for rows.Next() {
		var ver int
		var snap string
		var created int64
		if err := rows.Scan(&ver, &snap, &created); err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		var snapshot any
		_ = json.Unmarshal([]byte(snap), &snapshot)
		list = append(list, gin.H{"version": ver, "snapshot": snapshot, "createdAt": created})
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) getAgentVersion(c *gin.Context) {
	owner := currentUserID(c)
	ver, err := strconv.Atoi(c.Param("version"))
	if err != nil {
		writeErr(c, http.StatusBadRequest, "invalid version")
		return
	}
	var snap string
	var created int64
	err = s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT snapshot_json, created_at FROM agent_versions
		 WHERE owner_id=$1 AND agent_id=$2 AND version=$3`,
		owner, c.Param("id"), ver).Scan(&snap, &created)
	if err != nil {
		writeErr(c, http.StatusNotFound, "version not found")
		return
	}
	var snapshot any
	_ = json.Unmarshal([]byte(snap), &snapshot)
	c.JSON(http.StatusOK, gin.H{"version": ver, "snapshot": snapshot, "createdAt": created})
}

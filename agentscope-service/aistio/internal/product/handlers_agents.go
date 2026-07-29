package product

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strconv"

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
	WorkspacePath string `json:"workspacePath"`
	Version       *int   `json:"version"`
}

type agentRow struct {
	OwnerID        string
	AgentID        string
	WorkspacePath  *string
	Name           string
	Description    *string
	SysPrompt      *string
	Model          *string
	MaxIters       *int
	ToolsJSON      *string
	McpServersJSON *string
	SkillsJSON     *string
	MultiagentJSON *string
	HeadVersion    int
	ArchivedAt     *int64
	CreatedAt      int64
	UpdatedAt      int64
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
		"version":       a.HeadVersion,
		"archivedAt":    nullMillis(a.ArchivedAt),
	}
	if a.MultiagentJSON != nil && *a.MultiagentJSON != "" && *a.MultiagentJSON != "null" {
		out["multiagent"] = parseJSONRaw(*a.MultiagentJSON)
	}
	return out
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
		&a.OwnerID, &a.AgentID, &a.WorkspacePath, &a.Name, &a.Description,
		&a.SysPrompt, &a.Model, &a.MaxIters, &a.ToolsJSON, &a.McpServersJSON,
		&a.SkillsJSON, &a.MultiagentJSON, &a.HeadVersion, &a.ArchivedAt,
		&a.CreatedAt, &a.UpdatedAt,
	)
	return a, err
}

const agentSelect = `SELECT owner_id, agent_id, workspace_path, name, description, sys_prompt, model,
	max_iters, tools_json, mcp_servers_json, skills_json, multiagent_json, head_version,
	archived_at, created_at, updated_at FROM agents`

func (s *Server) listAgents(c *gin.Context) {
	owner := currentUserID(c)
	rows, err := s.db.Pool.Query(c.Request.Context(),
		agentSelect+` WHERE owner_id=$1 AND archived_at IS NULL ORDER BY updated_at DESC`, owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []gin.H{}
	for rows.Next() {
		a, err := s.scanAgent(rows)
		if err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
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
	tools := mustJSON(req.Tools)
	mcp := mustJSON(req.McpServers)
	skills := mustJSON(req.Skills)
	multi := mustJSON(req.Multiagent)

	_, err := s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO agents (owner_id, agent_id, workspace_path, name, description, sys_prompt, model,
		 max_iters, tools_json, mcp_servers_json, skills_json, multiagent_json, head_version, created_at, updated_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,1,$13,$13)`,
		owner, agentID, ws, req.Name, nullStr(req.Description), nullStr(sys), nullStr(req.Model),
		maxIters, tools, mcp, skills, multi, now)
	if err != nil {
		writeTextErr(c, http.StatusInternalServerError, err.Error())
		return
	}

	snap := s.agentSnapshot(owner, agentID, req.Name, req.Description, sys, req.Model, maxIters,
		req.Tools, req.McpServers, req.Skills, req.Multiagent, ws, 1, now, now)
	_, _ = s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO agent_versions (owner_id, agent_id, version, snapshot_json, created_at) VALUES ($1,$2,1,$3,$4)`,
		owner, agentID, mustJSON(snap), now)

	a, err := s.loadAgent(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	c.JSON(http.StatusOK, a.toJSON())
}

func nullStr(s string) any {
	if s == "" {
		return nil
	}
	return s
}

func (s *Server) agentSnapshot(owner, id, name, desc, system, model string, maxIters int,
	tools, mcp, skills, multi any, ws string, version int, created, updated int64) gin.H {
	return gin.H{
		"id": id, "name": name, "description": desc, "system": system, "model": model,
		"maxIters": maxIters, "tools": tools, "mcpServers": mcp, "skills": skills,
		"multiagent": multi, "scope": "user", "ownerId": owner, "workspacePath": ws,
		"version": version, "createdAt": created, "updatedAt": updated,
	}
}

func (s *Server) loadAgent(ctx context.Context, owner, agentID string) (agentRow, error) {
	row := s.db.Pool.QueryRow(ctx, agentSelect+` WHERE owner_id=$1 AND agent_id=$2`, owner, agentID)
	return s.scanAgent(row)
}

func (s *Server) getAgent(c *gin.Context) {
	owner := currentUserID(c)
	a, err := s.loadAgent(c.Request.Context(), owner, c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	c.JSON(http.StatusOK, a.toJSON())
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
	newVer := a.HeadVersion + 1
	now := nowMillis()
	tools := mustJSON(req.Tools)
	mcp := mustJSON(req.McpServers)
	skills := mustJSON(req.Skills)
	multi := mustJSON(req.Multiagent)

	_, err = s.db.Pool.Exec(c.Request.Context(),
		`UPDATE agents SET name=$1, description=$2, sys_prompt=$3, model=$4, max_iters=$5,
		 tools_json=$6, mcp_servers_json=$7, skills_json=$8, multiagent_json=$9,
		 workspace_path=$10, head_version=$11, updated_at=$12
		 WHERE owner_id=$13 AND agent_id=$14`,
		req.Name, nullStr(req.Description), nullStr(sys), nullStr(req.Model), maxIters,
		tools, mcp, skills, multi, nullStr(ws), newVer, now, owner, agentID)
	if err != nil {
		writeTextErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	snap := s.agentSnapshot(owner, agentID, req.Name, req.Description, sys, req.Model, maxIters,
		req.Tools, req.McpServers, req.Skills, req.Multiagent, ws, newVer, a.CreatedAt, now)
	_, _ = s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO agent_versions (owner_id, agent_id, version, snapshot_json, created_at) VALUES ($1,$2,$3,$4,$5)`,
		owner, agentID, newVer, mustJSON(snap), now)

	out, err := s.loadAgent(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
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

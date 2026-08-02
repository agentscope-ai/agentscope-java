package product

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
)

func (s *Server) registerInternal(r gin.IRouter) {
	r.GET("/api/internal/sessions", s.internalListSessions)
	r.GET("/api/internal/sessions/:id/resolve", s.internalResolveSession)
	r.POST("/api/internal/sessions/find-or-create", s.internalFindOrCreateSession)
	r.PATCH("/api/internal/sessions/:id/runtime", s.internalPatchSessionRuntime)
	r.PATCH("/api/internal/sessions/:id/overrides", s.internalPatchSessionOverrides)
	r.GET("/api/internal/environments/:id", s.internalGetEnvironment)
	r.POST("/api/internal/environments/:id/verify-key", s.internalVerifyEnvironmentKey)
	r.GET("/api/internal/agents/:ownerId/:agentId/versions/:version", s.internalGetAgentVersion)
	r.POST("/api/internal/vaults/resolve", s.internalResolveVaults)
	r.GET("/api/internal/memory-stores/:id/mount", s.internalMemoryMount)
	r.POST("/api/internal/deployments/:id/fire", s.internalFireDeployment)
	r.GET("/api/internal/channels/config", s.internalChannelsConfig)
	r.POST("/api/internal/channels/runtime", s.internalChannelRuntimeReport)
}

func (s *Server) internalListSessions(c *gin.Context) {
	limit := 500
	if v := c.Query("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			limit = n
		}
	}
	if limit > 2000 {
		limit = 2000
	}
	rows, err := s.db.Pool.Query(c.Request.Context(),
		`SELECT session_id, status, agent_id, owner_id, created_at, updated_at, archived_at
		 FROM sessions ORDER BY updated_at DESC LIMIT $1`, limit)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []gin.H{}
	for rows.Next() {
		var id, status, agentID, ownerID string
		var createdAt, updatedAt int64
		var archivedAt *int64
		if err := rows.Scan(&id, &status, &agentID, &ownerID, &createdAt, &updatedAt, &archivedAt); err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		list = append(list, gin.H{
			"id":         id,
			"status":     status,
			"agentId":    agentID,
			"ownerId":    ownerID,
			"createdAt":  createdAt,
			"updatedAt":  updatedAt,
			"archivedAt": nullMillis(archivedAt),
		})
	}
	c.JSON(http.StatusOK, gin.H{"sessions": list})
}

func (s *Server) internalResolveSession(c *gin.Context) {
	sess, err := s.loadSession(c.Request.Context(), c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "session not found")
		return
	}
	agentOwner := sess.OwnerID
	if sess.AgentOwnerID != nil && *sess.AgentOwnerID != "" {
		agentOwner = *sess.AgentOwnerID
	}
	ver := 1
	if sess.AgentVersion != nil {
		ver = *sess.AgentVersion
	}

	var snap any
	var workspace string
	var snapStr string
	err = s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT snapshot_json FROM agent_versions WHERE owner_id=$1 AND agent_id=$2 AND version=$3`,
		agentOwner, sess.AgentID, ver).Scan(&snapStr)
	if err == nil {
		_ = json.Unmarshal([]byte(snapStr), &snap)
		if m, ok := snap.(map[string]any); ok {
			if wp, ok := m["workspacePath"].(string); ok {
				workspace = wp
			}
		}
	}
	if workspace == "" {
		a, aerr := s.loadAgent(c.Request.Context(), agentOwner, sess.AgentID)
		if aerr == nil {
			if snap == nil {
				snap = a.toJSON()
			}
			if a.WorkspacePath != nil {
				workspace = *a.WorkspacePath
			}
		}
	}

	env, _ := s.loadEnv(c.Request.Context(), sess.EnvironmentID)
	vaultIDs := parseStringSlice(deref(sess.VaultIDsJSON))
	creds, _ := s.resolveVaultCredentials(c.Request.Context(), vaultIDs, sess.OwnerID)

	memIDs := parseStringSlice(deref(sess.MemoryStoreIDsJSON))
	mounts := []gin.H{}
	for _, mid := range memIDs {
		m, err := s.buildMemoryMount(c.Request.Context(), mid)
		if err == nil {
			mounts = append(mounts, m)
		}
	}

	refType := deref(sess.AgentRefType)
	if refType == "" {
		refType = "latest"
	}

	definitionFiles := map[string]string{}
	workspaceID := ""
	workspaceVersion := 0
	if a, aerr := s.loadAgent(c.Request.Context(), agentOwner, sess.AgentID); aerr == nil {
		scopeType, scopeID := a.resolveDefinitionScope()
		if files, ferr := s.listWorkspaceFileContents(c.Request.Context(), agentOwner, scopeType, scopeID, ""); ferr == nil {
			definitionFiles = files
		}
		if a.WorkspaceID != nil {
			workspaceID = *a.WorkspaceID
		}
		if workspaceID != "" {
			if w, werr := s.loadWorkspace(c.Request.Context(), agentOwner, workspaceID); werr == nil {
				workspaceVersion = w.HeadVersion
			}
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"session": gin.H{
			"id":                 sess.SessionID,
			"ownerId":            sess.OwnerID,
			"agentId":            sess.AgentID,
			"agentOwnerId":       agentOwner,
			"agentVersion":       ver,
			"agentRefType":       refType,
			"agentOverridesJson": nullStrPtr(sess.AgentOverridesJSON),
			"environmentId":      sess.EnvironmentID,
			"memoryStoreIds":     memIDs,
			"vaultIds":           vaultIDs,
			"resources": s.expandFileResources(c.Request.Context(), sess.OwnerID,
				parseJSONRaw(deref(sess.ResourcesJSON))),
			"status": sess.Status,
		},
		"agentSnapshot":     snap,
		"workspacePath":     workspace,
		"workspaceId":       nullStr(workspaceID),
		"workspaceVersion":  workspaceVersion,
		"definitionFiles":   definitionFiles,
		"environment":       env.toJSON(),
		"vaultCredentials":  creds,
		"memoryMounts":      mounts,
	})
}

type findOrCreateReq struct {
	OwnerID       string `json:"ownerId"`
	AgentID       string `json:"agentId"`
	EnvironmentID string `json:"environmentId"`
	ExternalKey   string `json:"externalKey"`
}

func (s *Server) resolveDefaultEnvironmentID(ctx context.Context, ownerID, agentID string) (string, error) {
	if a, err := s.loadAgent(ctx, ownerID, agentID); err == nil {
		if id := strings.TrimSpace(deref(a.DefaultEnvironmentID)); id != "" {
			return id, nil
		}
	}
	var envID string
	err := s.db.Pool.QueryRow(ctx,
		`SELECT environment_id FROM deployments
		 WHERE owner_id=$1 AND agent_id=$2 AND archived_at IS NULL
		 ORDER BY updated_at DESC LIMIT 1`,
		ownerID, agentID).Scan(&envID)
	if err == nil && envID != "" {
		return envID, nil
	}
	err = s.db.Pool.QueryRow(ctx,
		`SELECT environment_id FROM environments
		 WHERE owner_id=$1 AND archived_at IS NULL
		 ORDER BY created_at ASC LIMIT 1`,
		ownerID).Scan(&envID)
	if err != nil {
		return "", fmt.Errorf("no environment available for owner %s (create an environment or set agent.defaultEnvironmentId)", ownerID)
	}
	return envID, nil
}

func (s *Server) internalFindOrCreateSession(c *gin.Context) {
	var req findOrCreateReq
	if err := c.ShouldBindJSON(&req); err != nil || req.OwnerID == "" || req.AgentID == "" {
		writeErr(c, http.StatusBadRequest, "ownerId, agentId required")
		return
	}
	a, err := s.loadAgent(c.Request.Context(), req.OwnerID, req.AgentID)
	if err != nil {
		writeErr(c, http.StatusBadRequest, "agent not found")
		return
	}
	envID := strings.TrimSpace(req.EnvironmentID)
	if envID == "" {
		resolved, err := s.resolveDefaultEnvironmentID(c.Request.Context(), req.OwnerID, req.AgentID)
		if err != nil {
			writeErr(c, http.StatusBadRequest, err.Error())
			return
		}
		envID = resolved
	}
	if req.ExternalKey != "" {
		var id string
		err := s.db.Pool.QueryRow(c.Request.Context(),
			`SELECT session_id FROM sessions
			 WHERE owner_id=$1 AND agent_id=$2 AND environment_id=$3 AND external_key=$4
			   AND archived_at IS NULL ORDER BY created_at DESC LIMIT 1`,
			req.OwnerID, req.AgentID, envID, req.ExternalKey).Scan(&id)
		if err == nil {
			sess, _ := s.loadSession(c.Request.Context(), id)
			c.JSON(http.StatusOK, sess.toJSON())
			return
		}
	}
	_, memIDs, vaultIDs := mergeSessionMounts(a, envID, nil, nil, false, false)
	sess, err := s.insertSession(c.Request.Context(), req.OwnerID, req.AgentID, req.OwnerID,
		a.HeadVersion, "latest", envID, req.ExternalKey, memIDs, vaultIDs, nil, nil)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	c.JSON(http.StatusOK, sess.toJSON())
}

func (s *Server) internalPatchSessionRuntime(c *gin.Context) {
	var req struct {
		Status     *string `json:"status"`
		StopReason any     `json:"stopReason"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	sess, err := s.loadSession(c.Request.Context(), c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "session not found")
		return
	}
	status := sess.Status
	if req.Status != nil {
		status = *req.Status
	}
	var stop any
	if req.StopReason != nil {
		stop = mustJSON(req.StopReason)
	} else if sess.StopReasonJSON != nil {
		stop = *sess.StopReasonJSON
	}
	now := nowMillis()
	_, err = s.db.Pool.Exec(c.Request.Context(),
		`UPDATE sessions SET status=$1, stop_reason_json=$2, updated_at=$3 WHERE session_id=$4`,
		status, stop, now, sess.SessionID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	out, _ := s.loadSession(c.Request.Context(), sess.SessionID)
	c.JSON(http.StatusOK, out.toJSON())
}

func (s *Server) internalPatchSessionOverrides(c *gin.Context) {
	owner := currentUserID(c) // may be empty when internal token has no user header
	out, err := s.applySessionOverrides(c, c.Param("id"), owner, false)
	if err != nil {
		return
	}
	c.JSON(http.StatusOK, out.toJSON())
}

func (s *Server) internalGetEnvironment(c *gin.Context) {
	e, err := s.loadEnv(c.Request.Context(), c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "environment not found")
		return
	}
	c.JSON(http.StatusOK, e.toJSON())
}

func (s *Server) internalVerifyEnvironmentKey(c *gin.Context) {
	var req struct {
		Key string `json:"key"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Key == "" {
		c.JSON(http.StatusOK, gin.H{"ok": false})
		return
	}
	var hash *string
	err := s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT api_key_hash FROM environments WHERE environment_id=$1 AND archived_at IS NULL`,
		c.Param("id")).Scan(&hash)
	if err != nil || hash == nil || *hash == "" {
		c.JSON(http.StatusOK, gin.H{"ok": false})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": sha256Hex(req.Key) == *hash})
}

func (s *Server) internalGetAgentVersion(c *gin.Context) {
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
		c.Param("ownerId"), c.Param("agentId"), ver).Scan(&snap, &created)
	if err != nil {
		writeErr(c, http.StatusNotFound, "version not found")
		return
	}
	var snapshot any
	_ = json.Unmarshal([]byte(snap), &snapshot)
	c.JSON(http.StatusOK, gin.H{"version": ver, "snapshot": snapshot, "createdAt": created})
}

func (s *Server) internalResolveVaults(c *gin.Context) {
	var req struct {
		VaultIDs []string `json:"vaultIds"`
		OwnerID  string   `json:"ownerId"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "vaultIds required")
		return
	}
	creds, err := s.resolveVaultCredentials(c.Request.Context(), req.VaultIDs, req.OwnerID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	c.JSON(http.StatusOK, gin.H{"credentials": creds})
}

func (s *Server) buildMemoryMount(ctx context.Context, storeID string) (gin.H, error) {
	var n int
	if err := s.db.Pool.QueryRow(ctx,
		`SELECT COUNT(*) FROM memory_stores WHERE store_id=$1 AND archived_at IS NULL`, storeID).Scan(&n); err != nil || n == 0 {
		return nil, err
	}
	rows, err := s.db.Pool.Query(ctx,
		`SELECT path, content FROM memories WHERE store_id=$1 ORDER BY path`, storeID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	files := []gin.H{}
	for rows.Next() {
		var path, content string
		if err := rows.Scan(&path, &content); err != nil {
			return nil, err
		}
		files = append(files, gin.H{"path": path, "content": content})
	}
	return gin.H{"storeId": storeID, "files": files}, nil
}

func (s *Server) internalMemoryMount(c *gin.Context) {
	m, err := s.buildMemoryMount(c.Request.Context(), c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "memory store not found")
		return
	}
	c.JSON(http.StatusOK, m)
}

func (s *Server) internalFireDeployment(c *gin.Context) {
	d, err := s.loadDeploy(c.Request.Context(), c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "deployment not found")
		return
	}
	var body struct {
		Text string `json:"text"`
	}
	_ = c.ShouldBindJSON(&body)
	out, err := s.fireDeployment(c.Request.Context(), d, body.Text)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	c.JSON(http.StatusOK, out.toJSON())
}

func (s *Server) internalChannelsConfig(c *gin.Context) {
	rows, err := s.db.Pool.Query(c.Request.Context(),
		channelSelect+` WHERE disabled=FALSE ORDER BY channel_id`)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	// Scheduler expects a map keyed by channelId (agentscope.json shape), not {channels:[...]}.
	out := gin.H{}
	for rows.Next() {
		ch, err := s.scanChannel(rows)
		if err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		cfg := ch.fullConfigJSON()
		delete(cfg, "channelId")
		out[ch.ChannelID] = cfg
	}
	c.JSON(http.StatusOK, out)
}

type channelRuntimeReport struct {
	Channels []struct {
		ChannelID string  `json:"channelId"`
		Started   bool    `json:"started"`
		Error     *string `json:"error"`
	} `json:"channels"`
}

func (s *Server) internalChannelRuntimeReport(c *gin.Context) {
	var req channelRuntimeReport
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	now := nowMillis()
	for _, item := range req.Channels {
		id := strings.TrimSpace(item.ChannelID)
		if id == "" {
			continue
		}
		var errVal any
		if item.Error != nil && strings.TrimSpace(*item.Error) != "" {
			errVal = strings.TrimSpace(*item.Error)
		}
		_, _ = s.db.Pool.Exec(c.Request.Context(),
			`UPDATE channels SET runtime_started=$1, runtime_error=$2, runtime_updated_at=$3
			 WHERE channel_id=$4`,
			item.Started, errVal, now, id)
	}
	c.Status(http.StatusNoContent)
}

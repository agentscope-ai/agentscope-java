package product

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
)

func (s *Server) registerInternal(r gin.IRouter) {
	r.GET("/api/internal/sessions/:id/resolve", s.internalResolveSession)
	r.POST("/api/internal/sessions/find-or-create", s.internalFindOrCreateSession)
	r.PATCH("/api/internal/sessions/:id/runtime", s.internalPatchSessionRuntime)
	r.GET("/api/internal/environments/:id", s.internalGetEnvironment)
	r.POST("/api/internal/environments/:id/verify-key", s.internalVerifyEnvironmentKey)
	r.GET("/api/internal/agents/:ownerId/:agentId/versions/:version", s.internalGetAgentVersion)
	r.POST("/api/internal/vaults/resolve", s.internalResolveVaults)
	r.GET("/api/internal/memory-stores/:id/mount", s.internalMemoryMount)
	r.POST("/api/internal/deployments/:id/fire", s.internalFireDeployment)
	r.GET("/api/internal/channels/config", s.internalChannelsConfig)
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
			"resources":          parseJSONRaw(deref(sess.ResourcesJSON)),
			"status":             sess.Status,
		},
		"agentSnapshot":    snap,
		"workspacePath":    workspace,
		"environment":      env.toJSON(),
		"vaultCredentials": creds,
		"memoryMounts":     mounts,
	})
}

type findOrCreateReq struct {
	OwnerID       string `json:"ownerId"`
	AgentID       string `json:"agentId"`
	EnvironmentID string `json:"environmentId"`
	ExternalKey   string `json:"externalKey"`
}

func (s *Server) internalFindOrCreateSession(c *gin.Context) {
	var req findOrCreateReq
	if err := c.ShouldBindJSON(&req); err != nil || req.OwnerID == "" || req.AgentID == "" || req.EnvironmentID == "" {
		writeErr(c, http.StatusBadRequest, "ownerId, agentId, environmentId required")
		return
	}
	if req.ExternalKey != "" {
		var id string
		err := s.db.Pool.QueryRow(c.Request.Context(),
			`SELECT session_id FROM sessions
			 WHERE owner_id=$1 AND agent_id=$2 AND environment_id=$3 AND external_key=$4
			   AND archived_at IS NULL ORDER BY created_at DESC LIMIT 1`,
			req.OwnerID, req.AgentID, req.EnvironmentID, req.ExternalKey).Scan(&id)
		if err == nil {
			sess, _ := s.loadSession(c.Request.Context(), id)
			c.JSON(http.StatusOK, sess.toJSON())
			return
		}
	}
	a, err := s.loadAgent(c.Request.Context(), req.OwnerID, req.AgentID)
	if err != nil {
		writeErr(c, http.StatusBadRequest, "agent not found")
		return
	}
	sess, err := s.insertSession(c.Request.Context(), req.OwnerID, req.AgentID, req.OwnerID,
		a.HeadVersion, "latest", req.EnvironmentID, req.ExternalKey, nil, nil, nil, nil)
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
	if err := s.db.Pool.QueryRow(ctx, `SELECT COUNT(*) FROM memory_stores WHERE store_id=$1`, storeID).Scan(&n); err != nil || n == 0 {
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
	channels := []gin.H{}
	for rows.Next() {
		ch, err := s.scanChannel(rows)
		if err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		channels = append(channels, ch.fullConfigJSON())
	}
	c.JSON(http.StatusOK, gin.H{"channels": channels})
}

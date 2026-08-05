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
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

var (
	errNotFound   = errors.New("not found")
	errConflict   = errors.New("conflict")
	errBadRequest = errors.New("bad request")
)

func (s *Server) registerSessions(r gin.IRouter) {
	r.POST("/api/sessions", s.createSession)
	r.GET("/api/sessions", s.listSessions)
	r.GET("/api/sessions/:id", s.getSession)
	r.PATCH("/api/sessions/:id", s.updateSession)
	r.POST("/api/sessions/:id/archive", s.archiveSession)
	r.POST("/api/sessions/:id/restore", s.restoreSession)
	r.DELETE("/api/sessions/:id", s.deleteSession)
}

// sessionOverrideKeys is the closed set of session-scoped overrides the harness
// currently applies (see HarnessAgentBuildService). Tools and MCP overrides are
// intentionally rejected until the data plane consumes them.
var sessionOverrideKeys = map[string]bool{
	"system": true, "model": true, "maxIters": true,
	"name": true, "description": true,
}

type createSessionReq struct {
	Agent          any       `json:"agent"`
	EnvironmentID  string    `json:"environmentId"`
	MemoryStoreIDs *[]string `json:"memoryStoreIds"`
	VaultIDs       *[]string `json:"vaultIds"`
	ExternalKey    string    `json:"externalKey"`
	AgentOverrides any       `json:"agentOverrides"`
	Resources      any       `json:"resources"`
}

type updateSessionReq struct {
	AgentOverrides *map[string]any `json:"agentOverrides"`
	EnvironmentID  *string         `json:"environmentId"`
	MemoryStoreIDs *[]string       `json:"memoryStoreIds"`
	VaultIDs       *[]string       `json:"vaultIds"`
}

type sessionRow struct {
	SessionID          string
	OwnerID            string
	AgentID            string
	AgentOwnerID       *string
	AgentVersion       *int
	AgentRefType       *string
	AgentOverridesJSON *string
	EnvironmentID      string
	ExternalKey        *string
	MemoryStoreIDsJSON *string
	VaultIDsJSON       *string
	ResourcesJSON      *string
	Status             string
	StopReasonJSON     *string
	Version            int
	ArchivedAt         *int64
	CreatedAt          int64
	UpdatedAt          int64
}

func (r sessionRow) toJSON() gin.H {
	agentOwner := r.OwnerID
	if r.AgentOwnerID != nil && *r.AgentOwnerID != "" {
		agentOwner = *r.AgentOwnerID
	}
	refType := "latest"
	if r.AgentRefType != nil && *r.AgentRefType != "" {
		refType = *r.AgentRefType
	}
	var stop any
	if r.StopReasonJSON != nil && *r.StopReasonJSON != "" {
		stop = parseJSONRaw(*r.StopReasonJSON)
	}
	return gin.H{
		"id":                 r.SessionID,
		"ownerId":            r.OwnerID,
		"agentId":            r.AgentID,
		"agentOwnerId":       agentOwner,
		"agentVersion":       r.AgentVersion,
		"agentRefType":       refType,
		"agentOverridesJson": deref(r.AgentOverridesJSON),
		"environmentId":      r.EnvironmentID,
		"memoryStoreIds":     parseStringSlice(deref(r.MemoryStoreIDsJSON)),
		"vaultIds":           parseStringSlice(deref(r.VaultIDsJSON)),
		"status":             r.Status,
		"stopReason":         stop,
		"createdAt":          r.CreatedAt,
		"updatedAt":          r.UpdatedAt,
		"archivedAt":         nullMillis(r.ArchivedAt),
		"externalKey":        nullStrPtr(r.ExternalKey),
	}
}

func nullStrPtr(p *string) any {
	if p == nil || *p == "" {
		return nil
	}
	return *p
}

const sessionSelect = `SELECT session_id, owner_id, agent_id, agent_owner_id, agent_version, agent_ref_type,
	agent_overrides_json, environment_id, external_key, memory_store_ids_json, vault_ids_json,
	resources_json, status, stop_reason_json, version, archived_at, created_at, updated_at FROM sessions`

func (s *Server) scanSession(sc interface{ Scan(dest ...any) error }) (sessionRow, error) {
	var r sessionRow
	err := sc.Scan(
		&r.SessionID, &r.OwnerID, &r.AgentID, &r.AgentOwnerID, &r.AgentVersion, &r.AgentRefType,
		&r.AgentOverridesJSON, &r.EnvironmentID, &r.ExternalKey, &r.MemoryStoreIDsJSON, &r.VaultIDsJSON,
		&r.ResourcesJSON, &r.Status, &r.StopReasonJSON, &r.Version, &r.ArchivedAt, &r.CreatedAt, &r.UpdatedAt,
	)
	return r, err
}

func (s *Server) loadSession(ctx context.Context, id string) (sessionRow, error) {
	return s.scanSession(s.db.Pool.QueryRow(ctx, sessionSelect+` WHERE session_id=$1`, id))
}

func parseAgentRef(agent any) (agentID string, version *int, refType string) {
	refType = "latest"
	switch v := agent.(type) {
	case string:
		agentID = v
	case map[string]any:
		if id, ok := v["id"].(string); ok {
			agentID = id
		}
		if t, ok := v["type"].(string); ok && t != "" {
			refType = t
		}
		switch n := v["version"].(type) {
		case float64:
			iv := int(n)
			version = &iv
			if refType == "latest" {
				refType = "version"
			}
		case json.Number:
			iv, _ := n.Int64()
			i := int(iv)
			version = &i
			if refType == "latest" {
				refType = "version"
			}
		}
	}
	return
}

func (s *Server) createSession(c *gin.Context) {
	var req createSessionReq
	if err := c.ShouldBindJSON(&req); err != nil {
		writeTextErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	agentID, pinnedVer, refType := parseAgentRef(req.Agent)
	if agentID == "" {
		writeTextErr(c, http.StatusBadRequest, "agent required")
		return
	}
	owner := currentUserID(c)
	a, err := s.loadAgent(c.Request.Context(), owner, agentID)
	if err != nil {
		writeTextErr(c, http.StatusBadRequest, "agent not found")
		return
	}
	ver := a.HeadVersion
	if pinnedVer != nil {
		ver = *pinnedVer
		refType = "version"
	}
	var memIDs, vaultIDs []string
	memProvided := req.MemoryStoreIDs != nil
	vaultProvided := req.VaultIDs != nil
	if memProvided {
		memIDs = *req.MemoryStoreIDs
	}
	if vaultProvided {
		vaultIDs = *req.VaultIDs
	}
	envID, memIDs, vaultIDs := mergeSessionMounts(a, req.EnvironmentID, memIDs, vaultIDs, memProvided, vaultProvided)
	if envID == "" {
		writeTextErr(c, http.StatusBadRequest, "environmentId required (set on session or agent.defaultEnvironmentId)")
		return
	}
	sess, err := s.insertSession(c.Request.Context(), owner, agentID, owner, ver, refType,
		envID, req.ExternalKey, memIDs, vaultIDs, req.AgentOverrides, req.Resources)
	if err != nil {
		writeTextErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	c.JSON(http.StatusOK, sess.toJSON())
}

func (s *Server) insertSession(ctx context.Context, owner, agentID, agentOwner string, ver int, refType,
	envID, externalKey string, memIDs, vaultIDs []string, overrides, resources any) (sessionRow, error) {
	id := shortID("sess_")
	now := nowMillis()
	if memIDs == nil {
		memIDs = []string{}
	}
	if vaultIDs == nil {
		vaultIDs = []string{}
	}
	var ext any
	if externalKey != "" {
		ext = externalKey
	}
	var overridesJSON any
	if overrides != nil {
		overridesJSON = mustJSON(overrides)
	}
	_, err := s.db.Pool.Exec(ctx,
		`INSERT INTO sessions (session_id, owner_id, agent_id, agent_owner_id, agent_version, agent_ref_type,
		 agent_overrides_json, environment_id, external_key, memory_store_ids_json, vault_ids_json,
		 resources_json, status, version, created_at, updated_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,'idle',1,$13,$13)`,
		id, owner, agentID, agentOwner, ver, refType, overridesJSON, envID, ext,
		mustJSON(memIDs), mustJSON(vaultIDs), mustJSON(resources), now)
	if err != nil {
		return sessionRow{}, err
	}
	return s.loadSession(ctx, id)
}

// sessionListArchiveFilter maps ?status= to an SQL fragment on archived_at.
// Default (empty / "active") keeps the historical behaviour of listing only
// non-archived sessions.
func sessionListArchiveFilter(status string) (clause string, ok bool) {
	switch strings.ToLower(strings.TrimSpace(status)) {
	case "", "active":
		return ` AND archived_at IS NULL`, true
	case "archived":
		return ` AND archived_at IS NOT NULL`, true
	case "all":
		return ``, true
	default:
		return ``, false
	}
}

func (s *Server) listSessions(c *gin.Context) {
	owner := currentUserID(c)
	limit, offset, ok := pageParams(c)
	if !ok {
		writeErr(c, http.StatusBadRequest, "invalid limit/offset")
		return
	}
	archiveClause, ok := sessionListArchiveFilter(c.Query("status"))
	if !ok {
		writeErr(c, http.StatusBadRequest, "status must be active, archived, or all")
		return
	}
	agentID := c.Query("agentId")
	countQ := `SELECT COUNT(*) FROM sessions WHERE owner_id=$1` + archiveClause
	q := sessionSelect + ` WHERE owner_id=$1` + archiveClause
	args := []any{owner}
	if agentID != "" {
		countQ += ` AND agent_id=$2`
		q += ` AND agent_id=$2`
		args = append(args, agentID)
	}
	var total int64
	if err := s.db.Pool.QueryRow(c.Request.Context(), countQ, args...).Scan(&total); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	writeTotalCount(c, total)
	q += ` ORDER BY updated_at DESC`
	q, args = appendPage(q, limit, offset, args)
	rows, err := s.db.Pool.Query(c.Request.Context(), q, args...)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []gin.H{}
	for rows.Next() {
		r, err := s.scanSession(rows)
		if err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		list = append(list, r.toJSON())
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) getSession(c *gin.Context) {
	r, err := s.loadSession(c.Request.Context(), c.Param("id"))
	if err != nil || r.OwnerID != currentUserID(c) {
		writeErr(c, http.StatusNotFound, "session not found")
		return
	}
	c.JSON(http.StatusOK, r.toJSON())
}

func (s *Server) updateSession(c *gin.Context) {
	owner := currentUserID(c)
	out, err := s.applySessionUpdate(c, c.Param("id"), owner, true)
	if err != nil {
		return
	}
	c.JSON(http.StatusOK, out.toJSON())
}

// applySessionUpdate merges optional agentOverrides and/or mount bindings into
// the session row. requireOwner enforces owner match; when false (internal
// path), owner may be empty.
func (s *Server) applySessionUpdate(c *gin.Context, sessionID, owner string, requireOwner bool) (sessionRow, error) {
	sess, err := s.loadSession(c.Request.Context(), sessionID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "session not found")
		return sessionRow{}, errNotFound
	}
	if requireOwner && sess.OwnerID != owner {
		writeErr(c, http.StatusNotFound, "session not found")
		return sessionRow{}, errNotFound
	}
	if !requireOwner && owner != "" && sess.OwnerID != owner {
		writeErr(c, http.StatusNotFound, "session not found")
		return sessionRow{}, errNotFound
	}
	if sess.ArchivedAt != nil {
		writeErr(c, http.StatusConflict, "session is archived")
		return sessionRow{}, errConflict
	}
	var req updateSessionReq
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return sessionRow{}, errBadRequest
	}
	hasOverrides := req.AgentOverrides != nil
	hasMounts := req.EnvironmentID != nil || req.MemoryStoreIDs != nil || req.VaultIDs != nil
	if !hasOverrides && !hasMounts {
		writeErr(c, http.StatusBadRequest, "agentOverrides or mount fields required")
		return sessionRow{}, errBadRequest
	}

	envID := sess.EnvironmentID
	memIDs := parseStringSlice(deref(sess.MemoryStoreIDsJSON))
	vaultIDs := parseStringSlice(deref(sess.VaultIDsJSON))
	if req.EnvironmentID != nil {
		envID = strings.TrimSpace(*req.EnvironmentID)
		if envID == "" {
			writeErr(c, http.StatusBadRequest, "environmentId must not be empty")
			return sessionRow{}, errBadRequest
		}
	}
	if req.MemoryStoreIDs != nil {
		memIDs = *req.MemoryStoreIDs
		if memIDs == nil {
			memIDs = []string{}
		}
	}
	if req.VaultIDs != nil {
		vaultIDs = *req.VaultIDs
		if vaultIDs == nil {
			vaultIDs = []string{}
		}
	}

	overridesJSON := deref(sess.AgentOverridesJSON)
	if hasOverrides {
		for k := range *req.AgentOverrides {
			if !sessionOverrideKeys[k] {
				writeErr(c, http.StatusBadRequest, "unsupported override key: "+k)
				return sessionRow{}, errBadRequest
			}
		}
		merged := map[string]any{}
		if raw := parseJSONRaw(overridesJSON); raw != nil {
			if m, ok := raw.(map[string]any); ok {
				for k, v := range m {
					merged[k] = v
				}
			}
		}
		for k, v := range *req.AgentOverrides {
			if v == nil {
				delete(merged, k)
				continue
			}
			merged[k] = v
		}
		overridesJSON = mustJSON(merged)
	}

	now := nowMillis()
	if _, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE sessions SET agent_overrides_json=$1, environment_id=$2, memory_store_ids_json=$3,
		 vault_ids_json=$4, version=version+1, updated_at=$5
		 WHERE session_id=$6`,
		nullIfEmpty(overridesJSON), envID, mustJSON(memIDs), mustJSON(vaultIDs), now, sessionID); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return sessionRow{}, err
	}
	return s.loadSession(c.Request.Context(), sessionID)
}

// applySessionOverrides keeps the internal overrides-only path used by
// handlers_internal.go.
func (s *Server) applySessionOverrides(c *gin.Context, sessionID, owner string, requireOwner bool) (sessionRow, error) {
	return s.applySessionUpdate(c, sessionID, owner, requireOwner)
}

func nullIfEmpty(s string) any {
	if s == "" {
		return nil
	}
	return s
}

func (s *Server) archiveSession(c *gin.Context) {
	owner := currentUserID(c)
	now := nowMillis()
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE sessions SET archived_at=$1, updated_at=$1, status='archived'
		 WHERE session_id=$2 AND owner_id=$3 AND archived_at IS NULL`,
		now, c.Param("id"), owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "session not found")
		return
	}
	r, _ := s.loadSession(c.Request.Context(), c.Param("id"))
	c.JSON(http.StatusOK, r.toJSON())
}

func (s *Server) restoreSession(c *gin.Context) {
	owner := currentUserID(c)
	now := nowMillis()
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE sessions SET archived_at=NULL, updated_at=$1, status='active'
		 WHERE session_id=$2 AND owner_id=$3 AND archived_at IS NOT NULL`,
		now, c.Param("id"), owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "session not found")
		return
	}
	r, _ := s.loadSession(c.Request.Context(), c.Param("id"))
	c.JSON(http.StatusOK, r.toJSON())
}

func (s *Server) deleteSession(c *gin.Context) {
	owner := currentUserID(c)
	sessionID := c.Param("id")
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`DELETE FROM sessions WHERE session_id=$1 AND owner_id=$2`, sessionID, owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "session not found")
		return
	}
	s.bestEffortDeleteSessionEvents(c.Request.Context(), sessionID, owner)
	c.Status(http.StatusNoContent)
}

// bestEffortDeleteSessionEvents asks the data plane to drop builder_session_event
// rows for the session. Failures are logged only — the product session row is
// already gone and must not be rolled back.
func (s *Server) bestEffortDeleteSessionEvents(ctx context.Context, sessionID, ownerID string) {
	if s.cfg.DataURL == "" {
		return
	}
	url := strings.TrimRight(s.cfg.DataURL, "/") + "/api/sessions/" + sessionID + "/events"
	req, err := http.NewRequestWithContext(ctx, http.MethodDelete, url, bytes.NewReader(nil))
	if err != nil {
		log.Printf("session event cleanup: build request: %v", err)
		return
	}
	req.Header.Set("X-Builder-Internal-Token", s.cfg.InternalToken)
	if ownerID != "" {
		req.Header.Set("X-Builder-Internal-User", ownerID)
	}
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		log.Printf("session event cleanup failed session=%s: %v", sessionID, err)
		return
	}
	_ = resp.Body.Close()
	if resp.StatusCode >= 300 {
		log.Printf("session event cleanup status=%d session=%s", resp.StatusCode, sessionID)
	}
}

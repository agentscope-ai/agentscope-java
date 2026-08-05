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
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

type presenceUpsertReq struct {
	ChannelID   string         `json:"channelId"`
	Platform    string         `json:"platform"`
	Isolation   string         `json:"isolation"` // per_person | shared
	Enabled     *bool          `json:"enabled"`
	Credentials map[string]any `json:"credentials"`
}

func isolationToDmScope(isolation string) string {
	switch strings.ToLower(strings.TrimSpace(isolation)) {
	case "shared", "main":
		return "MAIN"
	default:
		return "PER_PEER"
	}
}

func dmScopeToIsolation(dm *string) string {
	if dm != nil && strings.EqualFold(*dm, "MAIN") {
		return "shared"
	}
	return "per_person"
}

func (ch channelRow) toPresenceJSON(maskSecrets bool) gin.H {
	spec := channelTypeSpec(ch.Type)
	callback := ""
	if spec != nil && spec.CallbackURL != "" {
		callback = strings.ReplaceAll(spec.CallbackURL, "{channelId}", ch.ChannelID)
	}
	props := parseJSONRaw(deref(ch.PropertiesJSON))
	if maskSecrets {
		props = maskChannelProperties(props)
	}
	return gin.H{
		"channelId":   ch.ChannelID,
		"platform":    ch.Type,
		"isolation":   dmScopeToIsolation(ch.DmScope),
		"enabled":     !ch.Disabled,
		"started":     ch.RuntimeStarted,
		"lastError":   nullStrPtr(ch.RuntimeError),
		"credentials": props,
		"callbackUrl": func() any {
			if callback == "" {
				return nil
			}
			return callback
		}(),
	}
}

func (s *Server) listAgentPresences(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	if _, err := s.loadAgent(c.Request.Context(), owner, agentID); err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	rows, err := s.db.Pool.Query(c.Request.Context(),
		channelSelect+` WHERE owner_id=$1 AND default_agent_id=$2 ORDER BY channel_id`,
		owner, agentID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []gin.H{}
	for rows.Next() {
		ch, err := s.scanChannel(rows)
		if err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		list = append(list, ch.toPresenceJSON(true))
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) createAgentPresence(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	if _, err := s.loadAgent(c.Request.Context(), owner, agentID); err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	var req presenceUpsertReq
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	platform := strings.TrimSpace(req.Platform)
	if platform == "" {
		writeTextErr(c, http.StatusBadRequest, "platform is required")
		return
	}
	if !knownChannelType(platform) {
		writeTextErr(c, http.StatusBadRequest, "Unknown platform: "+platform)
		return
	}
	if missing, err := validateChannelProperties(platform, req.Credentials, nil, false); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error(), "missingFields": missing})
		return
	}
	channelID := strings.TrimSpace(req.ChannelID)
	if channelID == "" {
		channelID = platform + "-" + agentID
	}
	disabled := false
	if req.Enabled != nil {
		disabled = !*req.Enabled
	}
	dm := isolationToDmScope(req.Isolation)
	now := nowMillis()
	props := mustJSON(req.Credentials)
	_, err := s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO channels (channel_id, owner_id, type, dm_scope, default_agent_id, disabled,
		 properties_json, bindings_json, created_at, updated_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,'[]',$8,$8)`,
		channelID, owner, platform, dm, agentID, disabled, props, now)
	if err != nil {
		if strings.Contains(err.Error(), "duplicate") || strings.Contains(err.Error(), "unique") {
			writeTextErr(c, http.StatusConflict, "Channel already exists: "+channelID)
			return
		}
		writeTextErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	_ = s.syncAgentBindingsFromChannel(c.Request.Context(), owner, channelID, "[]")
	ch, _ := s.loadChannel(c.Request.Context(), channelID)
	c.JSON(http.StatusOK, ch.toPresenceJSON(true))
}

func (s *Server) updateAgentPresence(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	channelID := c.Param("channelId")
	ch, err := s.loadChannel(c.Request.Context(), channelID)
	if err != nil || ch.OwnerID != owner {
		writeErr(c, http.StatusNotFound, "presence not found")
		return
	}
	if ch.DefaultAgentID == nil || *ch.DefaultAgentID != agentID {
		writeErr(c, http.StatusNotFound, "presence not found for this agent")
		return
	}
	var req presenceUpsertReq
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	platform := ch.Type
	if strings.TrimSpace(req.Platform) != "" {
		platform = strings.TrimSpace(req.Platform)
		if !knownChannelType(platform) {
			writeTextErr(c, http.StatusBadRequest, "Unknown platform: "+platform)
			return
		}
	}
	existingProps := propsAsMap(parseJSONRaw(deref(ch.PropertiesJSON)))
	incoming := req.Credentials
	if incoming == nil {
		incoming = map[string]any{}
	}
	merged := map[string]any{}
	for k, v := range existingProps {
		merged[k] = v
	}
	for k, v := range incoming {
		merged[k] = v
	}
	if missing, err := validateChannelProperties(platform, merged, existingProps, true); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error(), "missingFields": missing})
		return
	}
	dm := ch.DmScope
	if strings.TrimSpace(req.Isolation) != "" {
		sVal := isolationToDmScope(req.Isolation)
		dm = &sVal
	}
	disabled := ch.Disabled
	if req.Enabled != nil {
		disabled = !*req.Enabled
	}
	props := mergeChannelProperties(ch.PropertiesJSON, req.Credentials)
	now := nowMillis()
	_, err = s.db.Pool.Exec(c.Request.Context(),
		`UPDATE channels SET type=$1, dm_scope=$2, disabled=$3, properties_json=$4, updated_at=$5
		 WHERE channel_id=$6`,
		platform, nullStrPtrVal(dm), disabled, nullStr(props), now, channelID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	out, _ := s.loadChannel(c.Request.Context(), channelID)
	c.JSON(http.StatusOK, out.toPresenceJSON(true))
}

func (s *Server) deleteAgentPresence(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	channelID := c.Param("channelId")
	ch, err := s.loadChannel(c.Request.Context(), channelID)
	if err != nil || ch.OwnerID != owner {
		writeErr(c, http.StatusNotFound, "presence not found")
		return
	}
	if ch.DefaultAgentID == nil || *ch.DefaultAgentID != agentID {
		writeErr(c, http.StatusNotFound, "presence not found for this agent")
		return
	}
	_, _ = s.db.Pool.Exec(c.Request.Context(),
		`DELETE FROM channels WHERE channel_id=$1 AND owner_id=$2`, channelID, owner)
	_, _ = s.db.Pool.Exec(c.Request.Context(),
		`DELETE FROM agent_bindings WHERE owner_id=$1 AND channel_id=$2`, owner, channelID)
	c.Status(http.StatusNoContent)
}

func (s *Server) getAgentPresence(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	channelID := c.Param("channelId")
	ch, err := s.loadChannel(c.Request.Context(), channelID)
	if err != nil || ch.OwnerID != owner {
		writeErr(c, http.StatusNotFound, "presence not found")
		return
	}
	if ch.DefaultAgentID == nil || *ch.DefaultAgentID != agentID {
		writeErr(c, http.StatusNotFound, "presence not found for this agent")
		return
	}
	c.JSON(http.StatusOK, ch.toPresenceJSON(true))
}

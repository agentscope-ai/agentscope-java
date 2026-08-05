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
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
)

var channelSecretKeys = map[string]bool{
	"appSecret": true, "clientSecret": true, "token": true, "botToken": true,
	"webhookSecret": true, "signingSecret": true, "secret": true, "password": true,
	"accessToken": true, "refreshToken": true, "apiKey": true, "webhookToken": true,
	"encodingAesKey": true, "encryptKey": true, "verificationToken": true,
}

const secretMask = "********"

func (s *Server) registerChannels(r gin.IRouter) {
	r.GET("/api/channels", s.listChannels)
	r.GET("/api/channels/types", s.listChannelTypes)
	r.GET("/api/channels/:channelId", s.getChannel)
	r.POST("/api/channels", s.createChannel)
	r.PUT("/api/channels/:channelId", s.updateChannel)
	r.DELETE("/api/channels/:channelId", s.deleteChannel)
	r.POST("/api/channels/:channelId/enable", s.enableChannel)
	r.POST("/api/channels/:channelId/disable", s.disableChannel)

	r.GET("/api/agents/:id/bindings", s.listAgentBindings)
	r.PUT("/api/agents/:id/bindings", s.replaceAgentBindings)
	r.POST("/api/agents/:id/bindings", s.addAgentBinding)
	r.PUT("/api/agents/:id/bindings/:index", s.updateAgentBinding)
	r.DELETE("/api/agents/:id/bindings/:index", s.deleteAgentBinding)
	r.POST("/api/agents/:id/channels/:channelId/default", s.setChannelDefault)

	r.GET("/api/agents/:id/presences", s.listAgentPresences)
	r.POST("/api/agents/:id/presences", s.createAgentPresence)
	r.GET("/api/agents/:id/presences/:channelId", s.getAgentPresence)
	r.PUT("/api/agents/:id/presences/:channelId", s.updateAgentPresence)
	r.DELETE("/api/agents/:id/presences/:channelId", s.deleteAgentPresence)
}

type channelRow struct {
	ChannelID       string
	OwnerID         string
	Type            string
	DmScope         *string
	DefaultAgentID  *string
	Disabled        bool
	PropertiesJSON  *string
	BindingsJSON    *string
	RuntimeStarted  bool
	RuntimeError    *string
	CreatedAt       int64
	UpdatedAt       int64
}

const channelSelect = `SELECT channel_id, owner_id, type, dm_scope, default_agent_id, disabled,
	properties_json, bindings_json,
	COALESCE(runtime_started, FALSE), runtime_error,
	created_at, updated_at FROM channels`

func (s *Server) scanChannel(sc interface{ Scan(dest ...any) error }) (channelRow, error) {
	var ch channelRow
	err := sc.Scan(
		&ch.ChannelID, &ch.OwnerID, &ch.Type, &ch.DmScope, &ch.DefaultAgentID, &ch.Disabled,
		&ch.PropertiesJSON, &ch.BindingsJSON,
		&ch.RuntimeStarted, &ch.RuntimeError,
		&ch.CreatedAt, &ch.UpdatedAt,
	)
	return ch, err
}

func (s *Server) loadChannel(ctx context.Context, channelID string) (channelRow, error) {
	return s.scanChannel(s.db.Pool.QueryRow(ctx, channelSelect+` WHERE channel_id=$1`, channelID))
}

func (ch channelRow) infoJSON() gin.H {
	return gin.H{
		"channelId":      ch.ChannelID,
		"type":           ch.Type,
		"dmScope":        nullStrPtr(ch.DmScope),
		"defaultAgentId": nullStrPtr(ch.DefaultAgentID),
		"disabled":       ch.Disabled,
		"started":        ch.RuntimeStarted,
		"lastError":      nullStrPtr(ch.RuntimeError),
	}
}

func (ch channelRow) detailJSON(maskSecrets bool) gin.H {
	props := parseJSONRaw(deref(ch.PropertiesJSON))
	if maskSecrets {
		props = maskChannelProperties(props)
	}
	bindings := parseJSONArray(deref(ch.BindingsJSON))
	return gin.H{
		"channelId":      ch.ChannelID,
		"type":           ch.Type,
		"dmScope":        nullStrPtr(ch.DmScope),
		"defaultAgentId": nullStrPtr(ch.DefaultAgentID),
		"disabled":       ch.Disabled,
		"started":        ch.RuntimeStarted,
		"lastError":      nullStrPtr(ch.RuntimeError),
		"properties":     props,
		"bindings":       bindings,
	}
}

func (ch channelRow) fullConfigJSON() gin.H {
	out := gin.H{
		"channelId":      ch.ChannelID,
		"type":           ch.Type,
		"ownerId":        ch.OwnerID,
		"dmScope":        nullStrPtr(ch.DmScope),
		"defaultAgentId": nullStrPtr(ch.DefaultAgentID),
		"disabled":       ch.Disabled,
		"properties":     parseJSONRaw(deref(ch.PropertiesJSON)),
		"bindings":       parseJSONArray(deref(ch.BindingsJSON)),
	}
	return out
}

func parseJSONArray(s string) []any {
	if s == "" || s == "null" {
		return []any{}
	}
	var out []any
	if err := json.Unmarshal([]byte(s), &out); err != nil {
		return []any{}
	}
	if out == nil {
		return []any{}
	}
	return out
}

func maskChannelProperties(v any) any {
	m, ok := v.(map[string]any)
	if !ok || m == nil {
		if v == nil {
			return gin.H{}
		}
		return v
	}
	out := make(map[string]any, len(m))
	for k, val := range m {
		if channelSecretKeys[k] {
			if str, ok := val.(string); ok && str != "" {
				out[k] = secretMask
				continue
			}
		}
		out[k] = val
	}
	return out
}

func (s *Server) listChannels(c *gin.Context) {
	owner := currentUserID(c)
	rows, err := s.db.Pool.Query(c.Request.Context(),
		channelSelect+` WHERE owner_id=$1 ORDER BY channel_id`, owner)
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
		list = append(list, ch.infoJSON())
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) listChannelTypes(c *gin.Context) {
	c.JSON(http.StatusOK, supportedChannelTypes)
}

func (s *Server) getChannel(c *gin.Context) {
	ch, err := s.loadChannel(c.Request.Context(), c.Param("channelId"))
	if err != nil || ch.OwnerID != currentUserID(c) {
		writeErr(c, http.StatusNotFound, "Channel not found: "+c.Param("channelId"))
		return
	}
	c.JSON(http.StatusOK, ch.detailJSON(true))
}

type channelUpsertReq struct {
	ChannelID      string `json:"channelId"`
	Type           string `json:"type"`
	DmScope        *string `json:"dmScope"`
	DefaultAgentID *string `json:"defaultAgentId"`
	Disabled       *bool  `json:"disabled"`
	Properties     any    `json:"properties"`
	Bindings       any    `json:"bindings"`
}

func mergeChannelProperties(existingJSON *string, incoming any) string {
	if incoming == nil {
		return deref(existingJSON)
	}
	incomingMap, ok := incoming.(map[string]any)
	if !ok {
		return mustJSON(incoming)
	}
	existing := map[string]any{}
	if existingJSON != nil && *existingJSON != "" && *existingJSON != "null" {
		_ = json.Unmarshal([]byte(*existingJSON), &existing)
	}
	for k, v := range incomingMap {
		if channelSecretKeys[k] {
			if str, ok := v.(string); ok && str == secretMask {
				continue
			}
		}
		if v == nil {
			delete(existing, k)
		} else {
			existing[k] = v
		}
	}
	return mustJSON(existing)
}

func (s *Server) createChannel(c *gin.Context) {
	var req channelUpsertReq
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	channelID := strings.TrimSpace(req.ChannelID)
	if channelID == "" {
		channelID = shortID("ch_")
	}
	typ := strings.TrimSpace(req.Type)
	if typ == "" {
		writeTextErr(c, http.StatusBadRequest, "type is required")
		return
	}
	if !knownChannelType(typ) {
		writeTextErr(c, http.StatusBadRequest, "Unknown channel type: "+typ)
		return
	}
	incomingProps := propsAsMap(req.Properties)
	if missing, err := validateChannelProperties(typ, incomingProps, nil, false); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error(), "missingFields": missing})
		return
	}
	owner := currentUserID(c)
	disabled := false
	if req.Disabled != nil {
		disabled = *req.Disabled
	}
	dmScope := req.DmScope
	if dmScope == nil || strings.TrimSpace(*dmScope) == "" {
		def := "PER_PEER"
		dmScope = &def
	} else {
		s := strings.ToUpper(strings.TrimSpace(*dmScope))
		if s != "MAIN" && s != "PER_PEER" {
			writeTextErr(c, http.StatusBadRequest, "dmScope must be MAIN or PER_PEER")
			return
		}
		dmScope = &s
	}
	now := nowMillis()
	props := mergeChannelProperties(nil, req.Properties)
	bindings := "[]"
	if req.Bindings != nil {
		bindings = mustJSON(req.Bindings)
	}
	_, err := s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO channels (channel_id, owner_id, type, dm_scope, default_agent_id, disabled,
		 properties_json, bindings_json, created_at, updated_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$9)`,
		channelID, owner, typ, nullStrPtrVal(dmScope), nullStrPtrVal(req.DefaultAgentID),
		disabled, nullStr(props), bindings, now)
	if err != nil {
		if strings.Contains(err.Error(), "duplicate") || strings.Contains(err.Error(), "unique") {
			writeTextErr(c, http.StatusConflict, "Channel already exists: "+channelID)
			return
		}
		writeTextErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	_ = s.syncAgentBindingsFromChannel(c.Request.Context(), owner, channelID, bindings)
	ch, _ := s.loadChannel(c.Request.Context(), channelID)
	c.JSON(http.StatusOK, ch.detailJSON(true))
}

func nullStrPtrVal(p *string) any {
	if p == nil {
		return nil
	}
	t := strings.TrimSpace(*p)
	if t == "" {
		return nil
	}
	return t
}

func (s *Server) updateChannel(c *gin.Context) {
	channelID := c.Param("channelId")
	ch, err := s.loadChannel(c.Request.Context(), channelID)
	if err != nil || ch.OwnerID != currentUserID(c) {
		writeErr(c, http.StatusNotFound, "Channel not found: "+channelID)
		return
	}
	var req channelUpsertReq
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	typ := ch.Type
	if strings.TrimSpace(req.Type) != "" {
		typ = strings.TrimSpace(req.Type)
		if !knownChannelType(typ) {
			writeTextErr(c, http.StatusBadRequest, "Unknown channel type: "+typ)
			return
		}
	}
	existingProps := propsAsMap(parseJSONRaw(deref(ch.PropertiesJSON)))
	incomingProps := propsAsMap(req.Properties)
	mergedForCheck := map[string]any{}
	for k, v := range existingProps {
		mergedForCheck[k] = v
	}
	for k, v := range incomingProps {
		mergedForCheck[k] = v
	}
	if missing, err := validateChannelProperties(typ, mergedForCheck, existingProps, true); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error(), "missingFields": missing})
		return
	}
	dmScope := ch.DmScope
	if req.DmScope != nil {
		sVal := strings.ToUpper(strings.TrimSpace(*req.DmScope))
		if sVal != "" && sVal != "MAIN" && sVal != "PER_PEER" {
			writeTextErr(c, http.StatusBadRequest, "dmScope must be MAIN or PER_PEER")
			return
		}
		if sVal == "" {
			dmScope = nil
		} else {
			dmScope = &sVal
		}
	}
	defaultAgent := ch.DefaultAgentID
	if req.DefaultAgentID != nil {
		defaultAgent = req.DefaultAgentID
	}
	disabled := ch.Disabled
	if req.Disabled != nil {
		disabled = *req.Disabled
	}
	props := mergeChannelProperties(ch.PropertiesJSON, req.Properties)
	bindings := deref(ch.BindingsJSON)
	if req.Bindings != nil {
		bindings = mustJSON(req.Bindings)
	}
	now := nowMillis()
	_, err = s.db.Pool.Exec(c.Request.Context(),
		`UPDATE channels SET type=$1, dm_scope=$2, default_agent_id=$3, disabled=$4,
		 properties_json=$5, bindings_json=$6, updated_at=$7 WHERE channel_id=$8`,
		typ, nullStrPtrVal(dmScope), nullStrPtrVal(defaultAgent), disabled,
		nullStr(props), bindings, now, channelID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if req.Bindings != nil {
		_ = s.syncAgentBindingsFromChannel(c.Request.Context(), ch.OwnerID, channelID, bindings)
	}
	out, _ := s.loadChannel(c.Request.Context(), channelID)
	c.JSON(http.StatusOK, out.detailJSON(true))
}

func (s *Server) deleteChannel(c *gin.Context) {
	owner := currentUserID(c)
	channelID := c.Param("channelId")
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`DELETE FROM channels WHERE channel_id=$1 AND owner_id=$2`, channelID, owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "Channel not found: "+channelID)
		return
	}
	_, _ = s.db.Pool.Exec(c.Request.Context(),
		`DELETE FROM agent_bindings WHERE owner_id=$1 AND channel_id=$2`, owner, channelID)
	c.Status(http.StatusNoContent)
}

func (s *Server) enableChannel(c *gin.Context) {
	s.setChannelDisabled(c, false)
}

func (s *Server) disableChannel(c *gin.Context) {
	s.setChannelDisabled(c, true)
}

func (s *Server) setChannelDisabled(c *gin.Context, disabled bool) {
	owner := currentUserID(c)
	now := nowMillis()
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE channels SET disabled=$1, updated_at=$2 WHERE channel_id=$3 AND owner_id=$4`,
		disabled, now, c.Param("channelId"), owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "Channel not found: "+c.Param("channelId"))
		return
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) setChannelDefault(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	channelID := c.Param("channelId")
	if _, err := s.loadAgent(c.Request.Context(), owner, agentID); err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	now := nowMillis()
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE channels SET default_agent_id=$1, updated_at=$2 WHERE channel_id=$3 AND owner_id=$4`,
		agentID, now, channelID, owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "Channel not found: "+channelID)
		return
	}
	c.Status(http.StatusNoContent)
}

// --- agent bindings ---

type bindingPayload struct {
	ChannelID   string   `json:"channelId"`
	Index       int      `json:"index"`
	Tier        string   `json:"tier"`
	Peer        string   `json:"peer"`
	ParentPeer  string   `json:"parentPeer"`
	Guild       string   `json:"guild"`
	Roles       []string `json:"roles"`
	Team        string   `json:"team"`
	Account     string   `json:"account"`
	Channel     string   `json:"channel"`
	SessionScope string  `json:"sessionScope"`
	AgentID     string   `json:"agentId"`
}

func bindingView(channelID string, index int, tier string, p map[string]any) gin.H {
	out := gin.H{
		"channelId": channelID,
		"index":     index,
		"tier":      tier,
	}
	copyStr := func(key string) {
		if v, ok := p[key].(string); ok && v != "" {
			out[key] = v
		}
	}
	copyStr("peer")
	copyStr("parentPeer")
	copyStr("guild")
	copyStr("team")
	copyStr("account")
	copyStr("channel")
	copyStr("sessionScope")
	if roles, ok := p["roles"].([]any); ok && len(roles) > 0 {
		out["roles"] = roles
	} else if roles, ok := p["roles"].([]string); ok && len(roles) > 0 {
		out["roles"] = roles
	}
	return out
}

func payloadFromBindingReq(agentID string, req bindingPayload) map[string]any {
	m := map[string]any{"agentId": agentID}
	set := func(k, v string) {
		if strings.TrimSpace(v) != "" {
			m[k] = strings.TrimSpace(v)
		}
	}
	set("peer", req.Peer)
	set("parentPeer", req.ParentPeer)
	set("guild", req.Guild)
	set("team", req.Team)
	set("account", req.Account)
	set("channel", req.Channel)
	set("sessionScope", req.SessionScope)
	if len(req.Roles) > 0 {
		m["roles"] = req.Roles
	}
	return m
}

func deriveTier(p map[string]any) string {
	has := func(k string) bool {
		v, _ := p[k].(string)
		return strings.TrimSpace(v) != ""
	}
	if has("peer") {
		return "peer"
	}
	if has("parentPeer") {
		return "parentPeer"
	}
	if has("guild") {
		if roles, ok := p["roles"].([]any); ok && len(roles) > 0 {
			return "guildRoles"
		}
		if roles, ok := p["roles"].([]string); ok && len(roles) > 0 {
			return "guildRoles"
		}
		return "guild"
	}
	if has("team") {
		return "team"
	}
	if has("account") {
		return "account"
	}
	if has("channel") {
		return "channel"
	}
	return "channel"
}

func (s *Server) listAgentBindings(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	if _, err := s.loadAgent(c.Request.Context(), owner, agentID); err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	list, err := s.collectAgentBindings(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) collectAgentBindings(ctx context.Context, owner, agentID string) ([]gin.H, error) {
	rows, err := s.db.Pool.Query(ctx,
		`SELECT channel_id, binding_index, tier, payload_json FROM agent_bindings
		 WHERE owner_id=$1 AND agent_id=$2 ORDER BY channel_id, binding_index`, owner, agentID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	list := []gin.H{}
	for rows.Next() {
		var channelID, tier string
		var index int
		var payload string
		if err := rows.Scan(&channelID, &index, &tier, &payload); err != nil {
			return nil, err
		}
		p, _ := parseJSONRaw(payload).(map[string]any)
		if p == nil {
			p = map[string]any{}
		}
		list = append(list, bindingView(channelID, index, tier, p))
	}
	if len(list) > 0 {
		return list, nil
	}
	// Fallback: scan channel bindings_json (Java-compatible source of truth).
	chRows, err := s.db.Pool.Query(ctx,
		channelSelect+` WHERE owner_id=$1`, owner)
	if err != nil {
		return nil, err
	}
	defer chRows.Close()
	for chRows.Next() {
		ch, err := s.scanChannel(chRows)
		if err != nil {
			return nil, err
		}
		for i, raw := range parseJSONArray(deref(ch.BindingsJSON)) {
			m, ok := raw.(map[string]any)
			if !ok {
				continue
			}
			aid, _ := m["agentId"].(string)
			if aid != agentID {
				continue
			}
			list = append(list, bindingView(ch.ChannelID, i, deriveTier(m), m))
		}
	}
	return list, nil
}

func (s *Server) replaceAgentBindings(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	if _, err := s.loadAgent(c.Request.Context(), owner, agentID); err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	var reqs []bindingPayload
	if err := c.ShouldBindJSON(&reqs); err != nil {
		writeErr(c, http.StatusBadRequest, "body must be an array")
		return
	}
	ctx := c.Request.Context()
	_, _ = s.db.Pool.Exec(ctx, `DELETE FROM agent_bindings WHERE owner_id=$1 AND agent_id=$2`, owner, agentID)

	// Clear this agent from all channel binding lists, then re-add.
	chRows, err := s.db.Pool.Query(ctx, channelSelect+` WHERE owner_id=$1`, owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	channelBindings := map[string][]any{}
	for chRows.Next() {
		ch, err := s.scanChannel(chRows)
		if err != nil {
			chRows.Close()
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		kept := []any{}
		for _, raw := range parseJSONArray(deref(ch.BindingsJSON)) {
			m, ok := raw.(map[string]any)
			if !ok {
				continue
			}
			if aid, _ := m["agentId"].(string); aid == agentID {
				continue
			}
			kept = append(kept, raw)
		}
		channelBindings[ch.ChannelID] = kept
	}
	chRows.Close()

	now := nowMillis()
	out := []gin.H{}
	perChannelIndex := map[string]int{}
	for _, req := range reqs {
		channelID := strings.TrimSpace(req.ChannelID)
		if channelID == "" {
			continue
		}
		payload := payloadFromBindingReq(agentID, req)
		tier := req.Tier
		if tier == "" {
			tier = deriveTier(payload)
		}
		idx := perChannelIndex[channelID]
		perChannelIndex[channelID] = idx + 1
		entry := map[string]any{"agentId": agentID}
		for k, v := range payload {
			entry[k] = v
		}
		channelBindings[channelID] = append(channelBindings[channelID], entry)
		_, _ = s.db.Pool.Exec(ctx,
			`INSERT INTO agent_bindings (owner_id, agent_id, channel_id, binding_index, tier, payload_json, created_at)
			 VALUES ($1,$2,$3,$4,$5,$6,$7)`,
			owner, agentID, channelID, idx, tier, mustJSON(payload), now)
		out = append(out, bindingView(channelID, idx, tier, payload))
	}
	for channelID, bindings := range channelBindings {
		_, _ = s.db.Pool.Exec(ctx,
			`UPDATE channels SET bindings_json=$1, updated_at=$2 WHERE channel_id=$3 AND owner_id=$4`,
			mustJSON(bindings), now, channelID, owner)
	}
	c.JSON(http.StatusOK, out)
}

func (s *Server) addAgentBinding(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	if _, err := s.loadAgent(c.Request.Context(), owner, agentID); err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	var req bindingPayload
	if err := c.ShouldBindJSON(&req); err != nil || strings.TrimSpace(req.ChannelID) == "" {
		writeTextErr(c, http.StatusBadRequest, "channelId is required")
		return
	}
	ch, err := s.loadChannel(c.Request.Context(), req.ChannelID)
	if err != nil || ch.OwnerID != owner {
		// Auto-create a minimal channel stub so bindings can attach.
		now := nowMillis()
		_, err = s.db.Pool.Exec(c.Request.Context(),
			`INSERT INTO channels (channel_id, owner_id, type, disabled, bindings_json, created_at, updated_at)
			 VALUES ($1,$2,'custom',FALSE,'[]',$3,$3) ON CONFLICT (channel_id) DO NOTHING`,
			req.ChannelID, owner, now)
		if err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		ch, err = s.loadChannel(c.Request.Context(), req.ChannelID)
		if err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
	}
	bindings := parseJSONArray(deref(ch.BindingsJSON))
	payload := payloadFromBindingReq(agentID, req)
	tier := req.Tier
	if tier == "" {
		tier = deriveTier(payload)
	}
	entry := map[string]any{"agentId": agentID}
	for k, v := range payload {
		entry[k] = v
	}
	bindings = append(bindings, entry)
	index := len(bindings) - 1
	now := nowMillis()
	_, err = s.db.Pool.Exec(c.Request.Context(),
		`UPDATE channels SET bindings_json=$1, updated_at=$2 WHERE channel_id=$3`,
		mustJSON(bindings), now, req.ChannelID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	_, _ = s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO agent_bindings (owner_id, agent_id, channel_id, binding_index, tier, payload_json, created_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7)`,
		owner, agentID, req.ChannelID, index, tier, mustJSON(payload), now)
	c.JSON(http.StatusOK, bindingView(req.ChannelID, index, tier, payload))
}

func (s *Server) updateAgentBinding(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	channelID := c.Query("channelId")
	index, err := strconv.Atoi(c.Param("index"))
	if err != nil || channelID == "" {
		writeErr(c, http.StatusBadRequest, "channelId and index required")
		return
	}
	var req bindingPayload
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	ch, err := s.loadChannel(c.Request.Context(), channelID)
	if err != nil || ch.OwnerID != owner {
		writeErr(c, http.StatusNotFound, "Channel has no bindings: "+channelID)
		return
	}
	bindings := parseJSONArray(deref(ch.BindingsJSON))
	if index < 0 || index >= len(bindings) {
		writeErr(c, http.StatusNotFound, "Binding index out of range: "+c.Param("index"))
		return
	}
	existing, _ := bindings[index].(map[string]any)
	if existing == nil {
		writeErr(c, http.StatusForbidden, "Binding does not belong to agent: "+agentID)
		return
	}
	if aid, _ := existing["agentId"].(string); aid != agentID {
		writeErr(c, http.StatusForbidden, "Binding does not belong to agent: "+agentID)
		return
	}
	payload := payloadFromBindingReq(agentID, req)
	tier := req.Tier
	if tier == "" {
		tier = deriveTier(payload)
	}
	entry := map[string]any{"agentId": agentID}
	for k, v := range payload {
		entry[k] = v
	}
	bindings[index] = entry
	now := nowMillis()
	_, err = s.db.Pool.Exec(c.Request.Context(),
		`UPDATE channels SET bindings_json=$1, updated_at=$2 WHERE channel_id=$3`,
		mustJSON(bindings), now, channelID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	_, _ = s.db.Pool.Exec(c.Request.Context(),
		`UPDATE agent_bindings SET tier=$1, payload_json=$2
		 WHERE owner_id=$3 AND agent_id=$4 AND channel_id=$5 AND binding_index=$6`,
		tier, mustJSON(payload), owner, agentID, channelID, index)
	c.JSON(http.StatusOK, bindingView(channelID, index, tier, payload))
}

func (s *Server) deleteAgentBinding(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	channelID := c.Query("channelId")
	index, err := strconv.Atoi(c.Param("index"))
	if err != nil || channelID == "" {
		writeErr(c, http.StatusBadRequest, "channelId and index required")
		return
	}
	ch, err := s.loadChannel(c.Request.Context(), channelID)
	if err != nil || ch.OwnerID != owner {
		writeErr(c, http.StatusNotFound, "Channel has no bindings: "+channelID)
		return
	}
	bindings := parseJSONArray(deref(ch.BindingsJSON))
	if index < 0 || index >= len(bindings) {
		writeErr(c, http.StatusNotFound, "Binding index out of range: "+c.Param("index"))
		return
	}
	existing, _ := bindings[index].(map[string]any)
	if existing == nil {
		writeErr(c, http.StatusForbidden, "Binding does not belong to agent: "+agentID)
		return
	}
	if aid, _ := existing["agentId"].(string); aid != agentID {
		writeErr(c, http.StatusForbidden, "Binding does not belong to agent: "+agentID)
		return
	}
	bindings = append(bindings[:index], bindings[index+1:]...)
	now := nowMillis()
	_, err = s.db.Pool.Exec(c.Request.Context(),
		`UPDATE channels SET bindings_json=$1, updated_at=$2 WHERE channel_id=$3`,
		mustJSON(bindings), now, channelID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	_ = s.syncAgentBindingsFromChannel(c.Request.Context(), owner, channelID, mustJSON(bindings))
	c.Status(http.StatusNoContent)
}

func (s *Server) syncAgentBindingsFromChannel(ctx context.Context, owner, channelID, bindingsJSON string) error {
	_, _ = s.db.Pool.Exec(ctx,
		`DELETE FROM agent_bindings WHERE owner_id=$1 AND channel_id=$2`, owner, channelID)
	now := nowMillis()
	for i, raw := range parseJSONArray(bindingsJSON) {
		m, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		agentID, _ := m["agentId"].(string)
		if agentID == "" {
			continue
		}
		tier := deriveTier(m)
		_, err := s.db.Pool.Exec(ctx,
			`INSERT INTO agent_bindings (owner_id, agent_id, channel_id, binding_index, tier, payload_json, created_at)
			 VALUES ($1,$2,$3,$4,$5,$6,$7)`,
			owner, agentID, channelID, i, tier, mustJSON(m), now)
		if err != nil {
			return err
		}
	}
	return nil
}

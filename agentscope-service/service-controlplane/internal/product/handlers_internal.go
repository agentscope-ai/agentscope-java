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
	"errors"
	"fmt"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
)

func (s *Server) registerInternal(r gin.IRouter) {
	r.POST("/api/internal/managed/triggers", s.internalManagedTrigger)
	r.GET("/api/internal/sessions", s.internalListSessions)
	r.GET("/api/internal/sessions/:id/resolve", s.internalResolveSession)
	r.POST("/api/internal/sessions/find-or-create", s.internalFindOrCreateSession)
	r.PATCH("/api/internal/sessions/:id/overrides", s.internalPatchSessionOverrides)
	r.GET("/api/internal/environments/:id", s.internalGetEnvironment)
	r.POST("/api/internal/environments/:id/verify-key", s.internalVerifyEnvironmentKey)
	r.GET("/api/internal/agents/:ownerId/:agentId/versions/:version", s.internalGetAgentVersion)
	r.POST("/api/internal/vaults/resolve", s.internalResolveVaults)
	r.GET("/api/internal/memory-stores/:id/mount", s.internalMemoryMount)
	r.GET("/api/internal/memory-stores/:id/memories", s.nativeMemory(s.listMemories))
	r.GET("/api/internal/memory-stores/:id/memories/*path", s.nativeMemory(s.getMemory))
	r.PUT("/api/internal/memory-stores/:id/memories/*path", s.nativeMemory(s.putMemory))
	r.DELETE("/api/internal/memory-stores/:id/memories/*path", s.nativeMemory(s.deleteMemory))
	r.GET("/api/internal/sessions/:id/memory-stores/:storeId/memories", s.sessionMemory(s.listMemories))
	r.GET("/api/internal/sessions/:id/memory-stores/:storeId/memories/*path", s.sessionMemory(s.getMemory))
	r.PUT("/api/internal/sessions/:id/memory-stores/:storeId/memories/*path", s.sessionMemory(s.putMemory))
	r.DELETE("/api/internal/sessions/:id/memory-stores/:storeId/memories/*path", s.sessionMemory(s.deleteMemory))
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
	out, err := s.ResolveManagedSession(c.Request.Context(), c.Param("id"))
	if err != nil {
		var response *ManagedServiceError
		if errors.As(err, &response) {
			writeErr(c, response.StatusCode, response.Message)
		} else {
			writeErr(c, http.StatusInternalServerError, err.Error())
		}
		return
	}
	c.JSON(http.StatusOK, out)
}

// ResolveManagedSession materializes a versioned runtime configuration at admission.
func (s *Server) ResolveManagedSession(ctx context.Context, id string) (gin.H, error) {
	sess, err := s.loadSession(ctx, id)
	if err != nil {
		return nil, &ManagedServiceError{StatusCode: http.StatusNotFound, Message: "session not found"}
	}
	return s.resolveManagedSession(ctx, sess, nil)
}

// ManagedResourceConfiguration materializes a frozen native Session without a legacy cp Session.
type ManagedResourceConfiguration struct {
	SessionID     string
	OwnerID       string
	AgentID       string
	AgentVersion  int
	EnvironmentID string
	Definition    json.RawMessage
	Options       json.RawMessage
}

func (s *Server) ResolveManagedResourceSession(ctx context.Context, input ManagedResourceConfiguration) (gin.H, error) {
	if input.SessionID == "" || input.OwnerID == "" || input.AgentID == "" || len(input.Definition) == 0 {
		return nil, fmt.Errorf("native Managed resource configuration is incomplete")
	}
	var options createSessionReq
	if len(input.Options) > 0 {
		if err := json.Unmarshal(input.Options, &options); err != nil {
			return nil, err
		}
	}
	var defaults struct {
		MemoryStoreIDs []string `json:"defaultMemoryStoreIds"`
		VaultIDs       []string `json:"defaultVaultIds"`
	}
	if err := json.Unmarshal(input.Definition, &defaults); err != nil {
		return nil, fmt.Errorf("invalid frozen Managed definition: %w", err)
	}
	memory, vault := defaults.MemoryStoreIDs, defaults.VaultIDs
	if memory == nil {
		memory = []string{}
	}
	if vault == nil {
		vault = []string{}
	}
	if options.MemoryStoreIDs != nil {
		memory = *options.MemoryStoreIDs
	}
	if options.VaultIDs != nil {
		vault = *options.VaultIDs
	}
	memoryJSON, vaultJSON, resources := mustJSON(memory), mustJSON(vault), mustJSON(options.Resources)
	var overrides *string
	if options.AgentOverrides != nil {
		raw := mustJSON(options.AgentOverrides)
		overrides = &raw
	}
	refType := "version"
	return s.resolveManagedSession(ctx, sessionRow{SessionID: input.SessionID, OwnerID: input.OwnerID, AgentID: input.AgentID, AgentVersion: &input.AgentVersion, AgentRefType: &refType, EnvironmentID: input.EnvironmentID, MemoryStoreIDsJSON: &memoryJSON, VaultIDsJSON: &vaultJSON, AgentOverridesJSON: overrides, ResourcesJSON: &resources, Status: "idle"}, input.Definition)
}

func (s *Server) resolveManagedSession(ctx context.Context, sess sessionRow, frozen json.RawMessage) (gin.H, error) {
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
	var err error
	if len(frozen) > 0 {
		snapStr = string(frozen)
	} else {
		err = s.db.Pool.QueryRow(ctx,
			`SELECT snapshot_json FROM agent_versions WHERE owner_id=$1 AND agent_id=$2 AND version=$3`,
			agentOwner, sess.AgentID, ver).Scan(&snapStr)
	}
	if err == nil {
		if decodeErr := json.Unmarshal([]byte(snapStr), &snap); decodeErr != nil && len(frozen) > 0 {
			return nil, fmt.Errorf("invalid frozen Managed definition: %w", decodeErr)
		}
		if m, ok := snap.(map[string]any); ok {
			if wp, ok := m["workspacePath"].(string); ok {
				workspace = wp
			}
		}
	}
	if workspace == "" && len(frozen) == 0 {
		a, aerr := s.loadAgent(ctx, agentOwner, sess.AgentID)
		if aerr == nil {
			if snap == nil {
				snap = a.toJSON()
			}
			if a.WorkspacePath != nil {
				workspace = *a.WorkspacePath
			}
		}
	}

	env, err := s.loadEnv(ctx, sess.EnvironmentID)
	if err != nil || env.OwnerID != sess.OwnerID || env.ArchivedAt != nil {
		return nil, &ManagedServiceError{StatusCode: http.StatusConflict, Message: "Session environment is unavailable"}
	}
	vaultIDs := parseStringSlice(deref(sess.VaultIDsJSON))
	creds, err := s.resolveVaultCredentials(ctx, vaultIDs, sess.OwnerID)
	if err != nil {
		return nil, &ManagedServiceError{StatusCode: http.StatusConflict, Message: "Session vault is unavailable"}
	}

	memIDs := parseStringSlice(deref(sess.MemoryStoreIDsJSON))
	mounts := []gin.H{}
	for _, mid := range memIDs {
		m, err := s.buildMemoryMount(ctx, mid, sess.OwnerID)
		if err != nil {
			return nil, &ManagedServiceError{StatusCode: http.StatusConflict, Message: "Session memory store is unavailable"}
		}
		mounts = append(mounts, m)
	}

	refType := deref(sess.AgentRefType)
	if refType == "" {
		refType = "latest"
	}

	definitionFiles := map[string]string{}
	workspaceID := ""
	workspaceVersion := 0
	if snapshot, ok := snap.(map[string]any); ok && snapshot["definitionFiles"] != nil {
		raw, _ := json.Marshal(snapshot["definitionFiles"])
		if err := json.Unmarshal(raw, &definitionFiles); err != nil {
			return nil, &ManagedServiceError{StatusCode: http.StatusInternalServerError, Message: "Invalid definition snapshot"}
		}
		workspaceID, _ = snapshot["workspaceId"].(string)
		if version, ok := snapshot["workspaceVersion"].(float64); ok {
			workspaceVersion = int(version)
		}
	} else if a, aerr := s.loadAgent(ctx, agentOwner, sess.AgentID); len(frozen) == 0 && aerr == nil {
		scopeType, scopeID := a.resolveDefinitionScope()
		if files, ferr := s.listWorkspaceFileContents(ctx, agentOwner, scopeType, scopeID, ""); ferr == nil {
			definitionFiles = files
		}
		if a.WorkspaceID != nil {
			workspaceID = *a.WorkspaceID
		}
		if workspaceID != "" {
			if w, werr := s.loadWorkspace(ctx, agentOwner, workspaceID); werr == nil {
				workspaceVersion = w.HeadVersion
			}
		}
	}

	out := gin.H{
		"session": gin.H{
			"id":                 sess.SessionID,
			"ownerId":            sess.OwnerID,
			"agentId":            sess.AgentID,
			"agentOwnerId":       agentOwner,
			"agentVersion":       ver,
			"agentRefType":       refType,
			"agentOverridesJson": nullStrPtr(sess.AgentOverridesJSON),
			"environmentId":      sess.EnvironmentID,
			"externalKey":        nullStrPtr(sess.ExternalKey),
			"memoryStoreIds":     memIDs,
			"vaultIds":           vaultIDs,
			"resources": s.expandFileResources(ctx, sess.OwnerID,
				parseJSONRaw(deref(sess.ResourcesJSON))),
			"status": sess.Status,
		},
		"agentSnapshot":    snap,
		"workspacePath":    workspace,
		"workspaceId":      nullStr(workspaceID),
		"workspaceVersion": workspaceVersion,
		"definitionFiles":  definitionFiles,
		"environment":      env.toJSON(),
		"vaultCredentials": creds,
		"memoryMounts":     mounts,
	}
	if s.teamContextLookup != nil {
		if tc := s.teamContextLookup(ctx, sess.SessionID); len(tc) > 0 {
			var parsed any
			if err := json.Unmarshal(tc, &parsed); err == nil {
				out["teamContext"] = parsed
			} else {
				out["teamContext"] = json.RawMessage(tc)
			}
		}
	}
	if s.executionContextLookup != nil {
		if executionContext := s.executionContextLookup(ctx, sess.SessionID); len(executionContext) > 0 {
			var parsed any
			if err := json.Unmarshal(executionContext, &parsed); err == nil {
				out["executionContext"] = parsed
			}
		}
	}
	return out, nil
}

type findOrCreateReq struct {
	OwnerID       string `json:"ownerId"`
	AgentID       string `json:"agentId"`
	EnvironmentID string `json:"environmentId"`
	ExternalKey   string `json:"externalKey"`
}

// ResolveManagedEnvironment selects and validates an Environment before freezing a native Session.
func (s *Server) ResolveManagedEnvironment(ctx context.Context, owner, agent, selected string) (string, error) {
	if selected == "" {
		return s.resolveDefaultEnvironmentID(ctx, owner, agent)
	}
	if _, err := s.validateEnvironmentBinding(ctx, owner, selected); err != nil {
		return "", err
	}
	return selected, nil
}

func (s *Server) resolveDefaultEnvironmentID(ctx context.Context, ownerID, agentID string) (string, error) {
	a, err := s.loadAgent(ctx, ownerID, agentID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", fmt.Errorf("agent not found")
	}
	if err != nil {
		return "", err
	}
	if id := strings.TrimSpace(deref(a.DefaultEnvironmentID)); id != "" {
		if _, err = s.validateEnvironmentBinding(ctx, ownerID, id); err != nil {
			return "", err
		}
		return id, nil
	}
	var envID string
	err = s.db.Pool.QueryRow(ctx,
		`SELECT environment_id FROM deployments
		 WHERE owner_id=$1 AND agent_id=$2 AND archived_at IS NULL
		 ORDER BY updated_at DESC LIMIT 1`,
		ownerID, agentID).Scan(&envID)
	if err == nil && envID != "" {
		if _, err = s.validateEnvironmentBinding(ctx, ownerID, envID); err != nil {
			return "", err
		}
		return envID, nil
	}
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return "", fmt.Errorf("resolve deployment environment for owner %s: %w", ownerID, err)
	}
	typeClause := ""
	if !s.cfg.AllowLocalEnvironment {
		typeClause = ` AND lower(type) <> 'local'`
	}
	err = s.db.Pool.QueryRow(ctx,
		`SELECT environment_id FROM environments
		 WHERE owner_id=$1 AND archived_at IS NULL`+typeClause+`
		 ORDER BY created_at ASC LIMIT 1`,
		ownerID).Scan(&envID)
	if err == nil {
		return envID, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return "", fmt.Errorf("resolve environment for owner %s: %w", ownerID, err)
	}
	if s.cfg.AllowLocalEnvironment {
		return s.ensureDefaultLocalEnvironment(ctx, ownerID)
	}
	return "", fmt.Errorf("%w; %w", ErrNoRunnableEnvironment, ErrLocalEnvironmentDisabled)
}

// ensureDefaultLocalEnvironment makes the Managed Agent UI's "Automatic local
// default" promise true for background AgentTasks as well as interactive Chat.
// The advisory lock prevents concurrent outbox retries from creating several
// defaults for the same owner.
func (s *Server) ensureDefaultLocalEnvironment(ctx context.Context, ownerID string) (string, error) {
	if !s.cfg.AllowLocalEnvironment {
		return "", ErrLocalEnvironmentDisabled
	}
	tx, err := s.db.Pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err = tx.Exec(ctx,
		`SELECT pg_advisory_xact_lock(hashtext('controlplane-default-environment'), hashtext($1))`, ownerID); err != nil {
		return "", err
	}
	var envID string
	err = tx.QueryRow(ctx,
		`SELECT environment_id FROM environments
		 WHERE owner_id=$1 AND archived_at IS NULL AND lower(type)='local'
		 ORDER BY created_at ASC LIMIT 1`, ownerID).Scan(&envID)
	if err == nil {
		if err = tx.Commit(ctx); err != nil {
			return "", err
		}
		return envID, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return "", err
	}
	envID = shortID("env_")
	now := nowMillis()
	keyHash := sha256Hex(shortID("ek_"))
	if _, err = tx.Exec(ctx,
		`INSERT INTO environments (environment_id, owner_id, name, type, config_json, api_key_hash, created_at, updated_at)
		 VALUES ($1,$2,'default-local','local',$3,$4,$5,$5)`,
		envID, ownerID, mustJSON(map[string]any{}), keyHash, now); err != nil {
		return "", err
	}
	if err = tx.Commit(ctx); err != nil {
		return "", err
	}
	return envID, nil
}

func (s *Server) internalFindOrCreateSession(c *gin.Context) {
	var req findOrCreateReq
	if err := c.ShouldBindJSON(&req); err != nil || req.OwnerID == "" || req.AgentID == "" {
		writeErr(c, http.StatusBadRequest, "ownerId, agentId required")
		return
	}
	sess, err := s.FindOrCreateSession(c.Request.Context(), req.OwnerID, req.AgentID, req.EnvironmentID, req.ExternalKey)
	if err != nil {
		status := environmentBindingHTTPStatus(err)
		if strings.Contains(err.Error(), "agent not found") || strings.Contains(err.Error(), "no environment") {
			status = http.StatusBadRequest
		}
		writeErr(c, status, err.Error())
		return
	}
	c.JSON(http.StatusOK, sess.toJSON())
}

// FindOrCreateSession is the in-process form of POST /api/internal/sessions/find-or-create.
// Empty environmentID resolves via agent default / latest deployment / first owner env.
func (s *Server) FindOrCreateSession(ctx context.Context, ownerID, agentID, environmentID, externalKey string) (sessionRow, error) {
	a, err := s.loadAgent(ctx, ownerID, agentID)
	if err != nil {
		return sessionRow{}, fmt.Errorf("agent not found")
	}
	envID := strings.TrimSpace(environmentID)
	if envID == "" {
		resolved, err := s.resolveDefaultEnvironmentID(ctx, ownerID, agentID)
		if err != nil {
			return sessionRow{}, err
		}
		envID = resolved
	}
	if _, err = s.validateEnvironmentBinding(ctx, ownerID, envID); err != nil {
		return sessionRow{}, err
	}
	if externalKey != "" {
		var id string
		err := s.db.Pool.QueryRow(ctx,
			`SELECT session_id FROM sessions
			 WHERE owner_id=$1 AND agent_id=$2 AND environment_id=$3 AND external_key=$4
			   AND archived_at IS NULL ORDER BY created_at DESC LIMIT 1`,
			ownerID, agentID, envID, externalKey).Scan(&id)
		if err == nil {
			return s.loadSession(ctx, id)
		}
	}
	_, memIDs, vaultIDs := mergeSessionMounts(a, envID, nil, nil, false, false)
	version, refType := a.HeadVersion, "latest"
	if pinned := serviceapi.ContextVersion(ctx, agentID); pinned > 0 {
		version, refType = pinned, "version"
	}
	var options createSessionReq
	if contract := serviceapi.ContextContract(ctx); contract != nil && len(contract.SessionOptions) > 0 {
		if err = json.Unmarshal(contract.SessionOptions, &options); err != nil {
			return sessionRow{}, err
		}
		if options.MemoryStoreIDs != nil {
			memIDs = *options.MemoryStoreIDs
		}
		if options.VaultIDs != nil {
			vaultIDs = *options.VaultIDs
		}
	}
	return s.insertSession(ctx, ownerID, agentID, ownerID, version, refType, envID, externalKey, memIDs, vaultIDs, options.AgentOverrides, options.Resources)
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

func (s *Server) buildMemoryMount(ctx context.Context, storeID, ownerID string) (gin.H, error) {
	var name string
	err := s.db.Pool.QueryRow(ctx,
		`SELECT name FROM memory_stores WHERE store_id=$1 AND owner_id=$2 AND archived_at IS NULL`,
		storeID, ownerID).Scan(&name)
	if err != nil {
		return nil, err
	}
	return gin.H{"storeId": storeID, "name": name}, nil
}

func (s *Server) internalMemoryMount(c *gin.Context) {
	m, err := s.buildMemoryMount(c.Request.Context(), c.Param("id"), currentUserID(c))
	if err != nil {
		writeErr(c, http.StatusNotFound, "memory store not found")
		return
	}
	c.JSON(http.StatusOK, m)
}

// Every operation revalidates the session binding; a revoked or archived mount cannot be used
// by a cached Brain. Internal transport authentication is applied before this handler.
func (s *Server) sessionMemory(next gin.HandlerFunc) gin.HandlerFunc {
	return func(c *gin.Context) {
		sess, err := s.loadSession(c.Request.Context(), c.Param("id"))
		if err != nil {
			writeErr(c, http.StatusNotFound, "session not found")
			return
		}
		storeID := c.Param("storeId")
		bound := false
		for _, id := range parseStringSlice(deref(sess.MemoryStoreIDsJSON)) {
			if id == storeID {
				bound = true
			}
		}
		if !bound {
			writeErr(c, http.StatusForbidden, "memory store is not bound to this session")
			return
		}
		if _, err := s.buildMemoryMount(c.Request.Context(), storeID, sess.OwnerID); err != nil {
			writeErr(c, http.StatusNotFound, "memory store not found")
			return
		}
		if c.Request.Method != http.MethodGet && sess.EnvironmentID != "" {
			env, err := s.loadEnv(c.Request.Context(), sess.EnvironmentID)
			if err != nil {
				writeErr(c, http.StatusConflict, "environment unavailable")
				return
			}
			var cfg struct {
				MemoryAccess map[string]string `json:"memoryAccess"`
			}
			if env.ConfigJSON != nil {
				_ = json.Unmarshal([]byte(*env.ConfigJSON), &cfg)
			}
			if cfg.MemoryAccess[storeID] == "read_only" {
				writeErr(c, http.StatusForbidden, "memory store is mounted read_only")
				return
			}
		}
		c.Set(ctxUserID, sess.OwnerID)
		for i := range c.Params {
			if c.Params[i].Key == "id" {
				c.Params[i].Value = storeID
			}
		}
		next(c)
	}
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
	out, err := s.fireDeployment(c.Request.Context(), d, body.Text, c.GetHeader("Idempotency-Key"), currentUserID(c))
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

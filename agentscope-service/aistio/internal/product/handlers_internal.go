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
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

var errManagedSessionBusy = errors.New("managed session is busy")

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

	env, err := s.loadEnv(c.Request.Context(), sess.EnvironmentID)
	if err != nil || env.OwnerID != sess.OwnerID || env.ArchivedAt != nil {
		writeErr(c, http.StatusConflict, "Session environment is unavailable")
		return
	}
	vaultIDs := parseStringSlice(deref(sess.VaultIDsJSON))
	creds, err := s.resolveVaultCredentials(c.Request.Context(), vaultIDs, sess.OwnerID)
	if err != nil {
		writeErr(c, http.StatusConflict, "Session vault is unavailable")
		return
	}

	memIDs := parseStringSlice(deref(sess.MemoryStoreIDsJSON))
	mounts := []gin.H{}
	for _, mid := range memIDs {
		m, err := s.buildMemoryMount(c.Request.Context(), mid, sess.OwnerID)
		if err != nil {
			writeErr(c, http.StatusConflict, "Session memory store is unavailable")
			return
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
			writeErr(c, http.StatusInternalServerError, "Invalid definition snapshot")
			return
		}
		workspaceID, _ = snapshot["workspaceId"].(string)
		if version, ok := snapshot["workspaceVersion"].(float64); ok {
			workspaceVersion = int(version)
		}
	} else if a, aerr := s.loadAgent(c.Request.Context(), agentOwner, sess.AgentID); aerr == nil {
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
			"resources": s.expandFileResources(c.Request.Context(), sess.OwnerID,
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
		if tc := s.teamContextLookup(c.Request.Context(), sess.SessionID); len(tc) > 0 {
			var parsed any
			if err := json.Unmarshal(tc, &parsed); err == nil {
				out["teamContext"] = parsed
			} else {
				out["teamContext"] = json.RawMessage(tc)
			}
		}
	}
	if s.executionContextLookup != nil {
		if executionContext := s.executionContextLookup(c.Request.Context(), sess.SessionID); len(executionContext) > 0 {
			var parsed any
			if err := json.Unmarshal(executionContext, &parsed); err == nil {
				out["executionContext"] = parsed
			}
		}
	}
	c.JSON(http.StatusOK, out)
}

type findOrCreateReq struct {
	OwnerID       string `json:"ownerId"`
	AgentID       string `json:"agentId"`
	EnvironmentID string `json:"environmentId"`
	ExternalKey   string `json:"externalKey"`
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
		`SELECT pg_advisory_xact_lock(hashtext('aistio-default-environment'), hashtext($1))`, ownerID); err != nil {
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
	return s.insertSession(ctx, ownerID, agentID, ownerID,
		a.HeadVersion, "latest", envID, externalKey, memIDs, vaultIDs, nil, nil)
}

// FindOrCreateSessionID returns the session selected by the runtime binding resolver.
func (s *Server) FindOrCreateSessionID(ctx context.Context, ownerID, agentID, environmentID, externalKey string) (string, error) {
	sess, err := s.FindOrCreateSession(ctx, ownerID, agentID, environmentID, externalKey)
	if err != nil {
		return "", err
	}
	return sess.SessionID, nil
}

// ClaimManagedRuntimeFence advances the product Session's persisted physical
// turn marker before the data plane is woken. This closes the cross-store gap
// where Attempt B was current in the runtime Store but delayed Attempt A still
// won a product status PATCH before B's first status callback.
func (s *Server) ClaimManagedRuntimeFence(ctx context.Context, sessionID string,
	agentTaskID, attemptID uuid.UUID, dispatchGeneration int64, turnID string) error {
	fence := ManagedRuntimeFence{AgentTaskID: agentTaskID.String(), AttemptID: attemptID.String(),
		DispatchGeneration: dispatchGeneration, TurnID: turnID}
	if strings.TrimSpace(sessionID) == "" || agentTaskID == uuid.Nil || attemptID == uuid.Nil || !fence.Complete() {
		return ErrManagedRuntimeFenceConflict
	}
	var version int
	err := s.db.Pool.QueryRow(ctx, `UPDATE sessions SET runtime_agent_task_id=$1,runtime_attempt_id=$2,
		runtime_dispatch_generation=$3,runtime_turn_id=$4,stop_reason_json=NULL,version=version+1,updated_at=$5
		WHERE session_id=$6 AND (runtime_dispatch_generation IS NULL OR
			runtime_dispatch_generation<$3 OR (runtime_dispatch_generation=$3 AND
			runtime_agent_task_id=$1 AND runtime_attempt_id=$2 AND runtime_turn_id=$4))
		RETURNING version`, fence.AgentTaskID, fence.AttemptID, fence.DispatchGeneration,
		fence.TurnID, nowMillis(), sessionID).Scan(&version)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrManagedRuntimeFenceConflict
	}
	return err
}

// DeleteManagedSession removes a product session row and asks the data plane to
// drop its event rows.
func (s *Server) DeleteManagedSession(ctx context.Context, ownerID, sessionID string) error {
	if sessionID == "" || ownerID == "" {
		return nil
	}
	tag, err := s.db.Pool.Exec(ctx,
		`DELETE FROM sessions WHERE session_id=$1 AND owner_id=$2`, sessionID, ownerID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return nil
	}
	s.bestEffortDeleteSessionEvents(ctx, sessionID, ownerID)
	return nil
}

// PostSessionWakeEvent posts a user.message to the data plane to start a managed turn.
// Requires BUILDER_DATA_URL and InternalToken.
func (s *Server) PostSessionWakeEvent(ctx context.Context, sessionID, ownerID, text string) error {
	return s.postSessionWakePayload(ctx, sessionID, ownerID, map[string]any{"text": text})
}

// PostEndpointSessionWakeEvent preserves the public invocation identity in the
// durable user event, so the resulting Managed turn can be projected exactly.
func (s *Server) PostEndpointSessionWakeEvent(ctx context.Context, sessionID, ownerID, text, invocationID, turnID string) error {
	return s.postSessionWakePayload(ctx, sessionID, ownerID, map[string]any{
		"text": text, "endpointInvocationId": invocationID, "endpointTurnId": turnID,
	})
}

func (s *Server) postSessionWakePayload(ctx context.Context, sessionID, ownerID string, message map[string]any) error {
	if s.cfg.DataURL == "" {
		return fmt.Errorf("BUILDER_DATA_URL not configured")
	}
	if message["text"] == "" {
		message["text"] = "AgentTask is ready."
	}
	payload := map[string]any{
		"events": []map[string]any{
			{"type": "user.message", "payload": message},
		},
	}
	b, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	url := strings.TrimRight(s.cfg.DataURL, "/") + "/api/sessions/" + sessionID + "/events"
	client := &http.Client{Timeout: 15 * time.Second}
	for attempt := 0; attempt < 20; attempt++ {
		req, requestErr := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(b))
		if requestErr != nil {
			return requestErr
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Builder-Internal-Token", s.cfg.InternalToken)
		if ownerID != "" {
			req.Header.Set("X-Builder-Internal-User", ownerID)
		}
		resp, requestErr := client.Do(req)
		if requestErr != nil {
			return requestErr
		}
		if resp.StatusCode == http.StatusConflict {
			_ = resp.Body.Close()
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(50 * time.Millisecond):
			}
			continue
		}
		if resp.StatusCode >= 300 {
			msg, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
			_ = resp.Body.Close()
			return fmt.Errorf("wake event %s: %s", resp.Status, string(msg))
		}
		_ = resp.Body.Close()
		log.Printf("managed AgentTask wake posted session=%s status=%d", sessionID, resp.StatusCode)
		return nil
	}
	// A genuinely long-running turn stays queued at the orchestration caller;
	// only the narrow final-event/lease-release race is retried inline.
	return errManagedSessionBusy
}

// ManagedToolConfirmationDecision is the immutable, fully fenced callback
// body accepted by the managed data plane.
type ManagedToolConfirmationDecision struct {
	ApprovalID         uuid.UUID `json:"approvalId"`
	DecisionVersion    int64     `json:"decisionVersion"`
	Status             string    `json:"status"`
	Allow              bool      `json:"allow"`
	DenyMessage        string    `json:"denyMessage,omitempty"`
	AgentTaskID        uuid.UUID `json:"agentTaskId"`
	AttemptID          uuid.UUID `json:"attemptId"`
	DispatchGeneration int64     `json:"dispatchGeneration"`
	TurnID             string    `json:"turnId"`
}

// ManagedAttemptAbort is the immutable old-turn fence used when draining a
// managed continuation after its logical Attempt has been failed or replaced.
type ManagedAttemptAbort struct {
	AgentTaskID        uuid.UUID `json:"agentTaskId"`
	AttemptID          uuid.UUID `json:"attemptId"`
	DispatchGeneration int64     `json:"dispatchGeneration"`
	TurnID             string    `json:"turnId"`
	Reason             string    `json:"reason"`
}

// ManagedToolConfirmationDeliveryError distinguishes a permanently stale or
// lost continuation from a retryable transport/server failure.
type ManagedToolConfirmationDeliveryError struct {
	StatusCode int
	Message    string
}

func (e *ManagedToolConfirmationDeliveryError) Error() string {
	return fmt.Sprintf("tool confirmation returned status %d: %s", e.StatusCode, e.Message)
}

func (e *ManagedToolConfirmationDeliveryError) Permanent() bool {
	return e != nil && (e.StatusCode == http.StatusNotFound || e.StatusCode == http.StatusConflict ||
		e.StatusCode == http.StatusGone)
}

// PostManagedToolConfirmation delivers a control-plane Approval decision to
// the durable HITL ticket owned by the managed data plane. Callers retry this
// method through the control outbox; the data plane resolves the complete
// physical-turn fence idempotently, so an ambiguous HTTP outcome is safe to
// redeliver.
func (s *Server) PostManagedToolConfirmation(ctx context.Context, sessionID, ownerID, toolUseID string, decision ManagedToolConfirmationDecision) error {
	if s == nil || strings.TrimSpace(s.cfg.DataURL) == "" {
		return fmt.Errorf("BUILDER_DATA_URL not configured")
	}
	if strings.TrimSpace(sessionID) == "" || strings.TrimSpace(toolUseID) == "" ||
		decision.ApprovalID == uuid.Nil || decision.AgentTaskID == uuid.Nil || decision.AttemptID == uuid.Nil ||
		decision.DecisionVersion <= 0 || decision.DispatchGeneration <= 0 || strings.TrimSpace(decision.TurnID) == "" {
		return fmt.Errorf("managed tool confirmation requires approval, session, attempt, generation, turn, and toolUseId")
	}
	payload, err := json.Marshal(decision)
	if err != nil {
		return err
	}
	endpoint := strings.TrimRight(s.cfg.DataURL, "/") + "/api/internal/sessions/" + url.PathEscape(sessionID) +
		"/tool-confirmations/" + url.PathEscape(toolUseID) + "/decision"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Builder-Internal-Token", s.cfg.InternalToken)
	if ownerID != "" {
		req.Header.Set("X-Builder-Internal-User", ownerID)
	}
	resp, err := (&http.Client{Timeout: 15 * time.Second}).Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		message, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return &ManagedToolConfirmationDeliveryError{StatusCode: resp.StatusCode, Message: string(message)}
	}
	return nil
}

// PostManagedAttemptAbort asks the data plane to interrupt only the managed
// turn matching abort's complete physical fence. A delayed outbox delivery for
// Attempt A must be harmless after the same Session has started Attempt B.
func (s *Server) PostManagedAttemptAbort(ctx context.Context, sessionID, ownerID string, abort ManagedAttemptAbort) error {
	if s == nil || strings.TrimSpace(s.cfg.DataURL) == "" {
		return fmt.Errorf("BUILDER_DATA_URL not configured")
	}
	if strings.TrimSpace(sessionID) == "" || abort.AgentTaskID == uuid.Nil || abort.AttemptID == uuid.Nil ||
		abort.DispatchGeneration <= 0 || strings.TrimSpace(abort.TurnID) == "" {
		return fmt.Errorf("managed Attempt abort requires session, task, attempt, generation, and turn fence")
	}
	payload, err := json.Marshal(abort)
	if err != nil {
		return err
	}
	endpoint := strings.TrimRight(s.cfg.DataURL, "/") + "/api/internal/sessions/" +
		url.PathEscape(sessionID) + "/managed-attempts/" + url.PathEscape(abort.AttemptID.String()) + "/abort"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Builder-Internal-Token", s.cfg.InternalToken)
	if strings.TrimSpace(ownerID) != "" {
		req.Header.Set("X-Builder-Internal-User", ownerID)
	}
	resp, err := (&http.Client{Timeout: 15 * time.Second}).Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		message, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return &ManagedToolConfirmationDeliveryError{StatusCode: resp.StatusCode, Message: string(message)}
	}
	return nil
}

// AbortManagedSession interrupts the active managed Turn through the same
// authenticated event ingress used by ordinary user messages.
func (s *Server) AbortManagedSession(ctx context.Context, sessionID, ownerID string) error {
	if s.cfg.DataURL == "" {
		return fmt.Errorf("BUILDER_DATA_URL not configured")
	}
	payload, _ := json.Marshal(map[string]any{"events": []map[string]any{{
		"type": "user.interrupt", "payload": map[string]any{"source": "execution-attempt-cancel"},
	}}})
	url := strings.TrimRight(s.cfg.DataURL, "/") + "/api/sessions/" + sessionID + "/events"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Builder-Internal-Token", s.cfg.InternalToken)
	if ownerID != "" {
		req.Header.Set("X-Builder-Internal-User", ownerID)
	}
	resp, err := (&http.Client{Timeout: 15 * time.Second}).Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		message, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return &ManagedToolConfirmationDeliveryError{StatusCode: resp.StatusCode,
			Message: "abort managed session: " + string(message)}
	}
	return nil
}

func (s *Server) internalPatchSessionRuntime(c *gin.Context) {
	var req struct {
		Status *string `json:"status"`
		ManagedRuntimeFence
		StopReason any `json:"stopReason"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	tx, err := s.db.Pool.Begin(c.Request.Context())
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer func() { _ = tx.Rollback(c.Request.Context()) }()
	sess, err := s.scanSession(tx.QueryRow(c.Request.Context(), sessionSelect+` WHERE session_id=$1 FOR UPDATE`, c.Param("id")))
	if err != nil {
		writeErr(c, http.StatusNotFound, "session not found")
		return
	}
	fence := req.ManagedRuntimeFence
	managed := false
	if s.managedRuntimeValidator != nil {
		managed, err = s.managedRuntimeValidator(c.Request.Context(), sess.SessionID, fence)
		if err != nil {
			switch {
			case errors.Is(err, ErrManagedRuntimeFenceGone):
				writeErr(c, http.StatusGone, err.Error())
			case errors.Is(err, ErrManagedRuntimeFenceConflict):
				writeErr(c, http.StatusConflict, err.Error())
			default:
				writeErr(c, http.StatusInternalServerError, err.Error())
			}
			return
		}
	}
	if managed && !fence.Complete() || !managed && !fence.Empty() {
		writeErr(c, http.StatusConflict, ErrManagedRuntimeFenceConflict.Error())
		return
	}
	if managed && !managedRuntimeFenceCanAdvance(sess, fence) || !managed && sess.RuntimeDispatchGen != nil {
		writeErr(c, http.StatusConflict, "managed runtime fence is older than the accepted session scope")
		return
	}
	status := sess.Status
	if req.Status != nil {
		status = *req.Status
	}
	stop := sessionRuntimeStopReason(sess, req.Status, req.StopReason)
	now := nowMillis()
	var updatedVersion int
	if managed {
		err = tx.QueryRow(c.Request.Context(), `UPDATE sessions SET status=$1, stop_reason_json=$2,
			runtime_agent_task_id=$3,runtime_attempt_id=$4,runtime_dispatch_generation=$5,
			runtime_turn_id=$6,version=version+1,updated_at=$7
			WHERE session_id=$8 AND (runtime_dispatch_generation IS NULL OR
				runtime_dispatch_generation<$5 OR (runtime_dispatch_generation=$5 AND
				runtime_agent_task_id=$3 AND runtime_attempt_id=$4 AND runtime_turn_id=$6))
			RETURNING version`, status, stop, fence.AgentTaskID, fence.AttemptID,
			fence.DispatchGeneration, fence.TurnID, now, sess.SessionID).Scan(&updatedVersion)
	} else {
		err = tx.QueryRow(c.Request.Context(), `UPDATE sessions SET status=$1, stop_reason_json=$2,
			version=version+1,updated_at=$3 WHERE session_id=$4 AND runtime_dispatch_generation IS NULL
			RETURNING version`, status, stop, now, sess.SessionID).Scan(&updatedVersion)
	}
	if errors.Is(err, pgx.ErrNoRows) {
		writeErr(c, http.StatusConflict, "managed runtime fence lost its monotonic update race")
		return
	}
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if err = tx.Commit(c.Request.Context()); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if req.Status != nil && s.teamMemberActivityHook != nil {
		s.teamMemberActivityHook(c.Request.Context(), sess.SessionID, status)
	}
	out, _ := s.loadSession(c.Request.Context(), sess.SessionID)
	c.JSON(http.StatusOK, out.toJSON())
}

// managedRuntimeFenceCanAdvance mirrors the conditional UPDATE predicate. A
// newer retry may replace an older marker, the same physical turn may patch
// repeatedly, and every older or same-generation/different tuple is rejected.
func managedRuntimeFenceCanAdvance(sess sessionRow, incoming ManagedRuntimeFence) bool {
	if !incoming.Complete() {
		return false
	}
	if sess.RuntimeDispatchGen == nil {
		return true
	}
	if incoming.DispatchGeneration != *sess.RuntimeDispatchGen {
		return incoming.DispatchGeneration > *sess.RuntimeDispatchGen
	}
	return sess.RuntimeAgentTaskID != nil && *sess.RuntimeAgentTaskID == incoming.AgentTaskID &&
		sess.RuntimeAttemptID != nil && *sess.RuntimeAttemptID == incoming.AttemptID &&
		sess.RuntimeTurnID != nil && *sess.RuntimeTurnID == incoming.TurnID
}

// sessionRuntimeStopReason prevents a newly running physical turn from
// inheriting an error recorded by the previous Attempt. Other status-only
// patches retain the existing reason for backwards compatibility.
func sessionRuntimeStopReason(sess sessionRow, requestedStatus *string, requestedReason any) any {
	if requestedStatus != nil && strings.EqualFold(strings.TrimSpace(*requestedStatus), "running") {
		return nil
	}
	if requestedReason != nil {
		return mustJSON(requestedReason)
	}
	if sess.StopReasonJSON != nil {
		return *sess.StopReasonJSON
	}
	return nil
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

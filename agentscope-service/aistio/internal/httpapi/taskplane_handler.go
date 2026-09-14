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

package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/runtimeauth"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

const runtimeHostClaimsContextKey = "runtimeHostClaims"

type runtimeHostRegistrationRequest struct {
	Tenant        string          `json:"tenant"`
	Namespace     string          `json:"namespace"`
	HostKey       string          `json:"hostKey"`
	PoolName      string          `json:"poolName"`
	DaemonVersion string          `json:"daemonVersion,omitempty"`
	OS            string          `json:"os,omitempty"`
	Arch          string          `json:"arch,omitempty"`
	Labels        json.RawMessage `json:"labels,omitempty"`
	Capabilities  json.RawMessage `json:"capabilities,omitempty"`
	Capacity      int32           `json:"capacity"`
}

func (s *Server) createRuntimeHostEnrollment(c *gin.Context) {
	var request struct {
		HostKey   string `json:"hostKey"`
		Tenant    string `json:"tenant"`
		Namespace string `json:"namespace"`
	}
	if err := c.ShouldBindJSON(&request); err != nil || strings.TrimSpace(request.HostKey) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "hostKey is required"})
		return
	}
	if len(request.HostKey) > 200 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "hostKey is too long"})
		return
	}
	if s.scopeMode == ScopeModeSingle {
		request.Tenant, request.Namespace = s.defaultTenant, s.defaultNamespace
	} else {
		if request.Tenant == "" {
			request.Tenant = "default"
		}
		if request.Namespace == "" {
			request.Namespace = defaultNamespace
		}
	}
	token, claims, err := s.runtimeTokens.Mint(request.HostKey, request.Tenant, request.Namespace, time.Now().UTC())
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: "generate Runtime Host credential"})
		return
	}
	c.JSON(http.StatusCreated, gin.H{
		"runtimeToken": token,
		"hostKey":      claims.HostKey,
		"tenant":       claims.Tenant,
		"namespace":    claims.Namespace,
		"expiresAt":    time.Unix(claims.ExpiresAt, 0).UTC(),
	})
}

func (s *Server) createRuntimeHostEnrollmentToken(c *gin.Context) {
	var request struct {
		Tenant    string `json:"tenant"`
		Namespace string `json:"namespace"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid enrollment token request"})
		return
	}
	request.Tenant, request.Namespace = strings.TrimSpace(request.Tenant), strings.TrimSpace(request.Namespace)
	if s.scopeMode == ScopeModeSingle {
		request.Tenant, request.Namespace = s.defaultTenant, s.defaultNamespace
	} else if request.Tenant == "" || request.Namespace == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "tenant and namespace are required in multi scope mode"})
		return
	}
	token, claims, err := s.runtimeTokens.MintEnrollment(request.Tenant, request.Namespace, time.Now().UTC())
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: "generate Runtime Host enrollment token"})
		return
	}
	c.JSON(http.StatusCreated, gin.H{
		"enrollmentToken": token,
		"tenant":          claims.Tenant,
		"namespace":       claims.Namespace,
		"expiresAt":       time.Unix(claims.ExpiresAt, 0).UTC(),
	})
}

// exchangeRuntimeHostEnrollment is the narrow public bootstrap boundary used
// by `agentscope connect`. The enrollment token supplies the scope; callers
// can supply only the local host identity.
func (s *Server) exchangeRuntimeHostEnrollment(c *gin.Context) {
	claims, err := s.runtimeTokens.VerifyEnrollment(requestBearerToken(c), time.Now().UTC())
	if err != nil {
		c.JSON(http.StatusUnauthorized, ErrorResponse{Error: "invalid Runtime Host enrollment token"})
		return
	}
	var request struct {
		HostKey string `json:"hostKey"`
	}
	if err := c.ShouldBindJSON(&request); err != nil || strings.TrimSpace(request.HostKey) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "hostKey is required"})
		return
	}
	if len(request.HostKey) > 200 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "hostKey is too long"})
		return
	}
	token, runtimeClaims, err := s.runtimeTokens.Mint(request.HostKey, claims.Tenant, claims.Namespace, time.Now().UTC())
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: "generate Runtime Host credential"})
		return
	}
	c.JSON(http.StatusCreated, gin.H{
		"runtimeToken": token,
		"hostKey":      runtimeClaims.HostKey,
		"tenant":       runtimeClaims.Tenant,
		"namespace":    runtimeClaims.Namespace,
		"expiresAt":    time.Unix(runtimeClaims.ExpiresAt, 0).UTC(),
	})
}

// runtimeHostCredentialMiddleware accepts the legacy shared internal secret
// on private links and Host-scoped credentials on public Gateway links.
func (s *Server) runtimeHostCredentialMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		if token := c.GetHeader("X-Builder-Internal-Token"); s.internalToken != "" && token == s.internalToken {
			c.Next()
			return
		}
		claims, err := s.runtimeTokens.Verify(requestBearerToken(c), time.Now().UTC())
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, ErrorResponse{Error: "invalid Runtime Host credential"})
			return
		}
		if rawID := c.Param("hostId"); rawID != "" {
			hostID, parseErr := uuid.Parse(rawID)
			host, loadErr := s.store.RuntimeRegistry().GetRuntimeHost(c.Request.Context(), hostID)
			if parseErr != nil || loadErr != nil || host.HostKey != claims.HostKey || host.Tenant != claims.Tenant || host.Namespace != claims.Namespace {
				c.AbortWithStatusJSON(http.StatusUnauthorized, ErrorResponse{Error: "Runtime Host credential scope mismatch"})
				return
			}
		}
		c.Set(runtimeHostClaimsContextKey, claims)
		c.Next()
	}
}

var runtimeProfileNameCleaner = regexp.MustCompile(`[^a-z0-9-]+`)

func automaticRuntimeProfileName(provider string) string {
	name := strings.ToLower(strings.TrimSpace(provider))
	name = strings.ReplaceAll(name, "_", "-")
	name = runtimeProfileNameCleaner.ReplaceAllString(name, "-")
	name = strings.Trim(name, "-")
	if name == "" {
		return ""
	}
	return "auto-" + name
}

// automaticRuntimeProfileConfiguration is the safe, usable baseline applied
// when `agentscope connect` discovers a provider for the first time. These
// values describe AgentScope's headless execution contract, not the user's
// ambient CLI preferences. Per-Agent settings may layer stricter or more
// specific values over this profile.
func automaticRuntimeProfileConfiguration(provider string) json.RawMessage {
	switch strings.ToLower(strings.TrimSpace(provider)) {
	case "codex":
		return json.RawMessage(`{"sandbox":"workspace-write"}`)
	case "claude-code":
		return json.RawMessage(`{"permissionMode":"default","allowedTools":["mcp__agentscope-collaboration__*"]}`)
	case "qoder":
		return json.RawMessage(`{"permissionMode":"default","allowedTools":["mcp__agentscope-collaboration__*"],"strictMCPConfig":true}`)
	case "qwenpaw":
		return json.RawMessage(`{"permissionMode":"default"}`)
	case "openclaw":
		return json.RawMessage(`{"codeMode":"auto","timeoutSeconds":600}`)
	default:
		return json.RawMessage(`{}`)
	}
}

func emptyRuntimeProfileConfiguration(raw json.RawMessage) bool {
	if len(raw) == 0 || string(raw) == "null" {
		return true
	}
	var value map[string]any
	return json.Unmarshal(raw, &value) == nil && len(value) == 0
}

// ensureRuntimeDefaults turns a Runtime Host's observed processes into the
// internal Profile/Pool records required by the scheduler. They are not a
// user-facing product area; Agent authors only see a discovered Runtime choice.
func (s *Server) ensureRuntimeDefaults(c *gin.Context, req runtimeHostRegistrationRequest) error {
	registry := s.store.RuntimeRegistry()
	if _, err := registry.GetRuntimePool(c.Request.Context(), req.Tenant, req.Namespace, req.PoolName); err != nil {
		if !errors.Is(err, store.ErrNotFound) {
			return err
		}
		if _, err = registry.UpsertRuntimePool(c.Request.Context(), &controlmodel.RuntimePool{
			Tenant: req.Tenant, Namespace: req.Namespace, Name: req.PoolName,
		}); err != nil {
			return err
		}
	}
	var capabilities struct {
		Providers            map[string]string `json:"providers"`
		ProviderCapabilities map[string]struct {
			Runtime string `json:"runtime"`
		} `json:"providerCapabilities"`
	}
	if len(req.Capabilities) == 0 || json.Unmarshal(req.Capabilities, &capabilities) != nil {
		return nil
	}
	for providerName := range capabilities.Providers {
		profileName := automaticRuntimeProfileName(providerName)
		if profileName == "" {
			continue
		}
		configuration := automaticRuntimeProfileConfiguration(providerName)
		if current, err := registry.GetRuntimeProfile(c.Request.Context(), req.Tenant, req.Namespace, profileName); err == nil {
			// Profiles created by older Runtime Hosts used an empty object and
			// could be unusable in a non-interactive provider. Upgrade only that
			// legacy shape; never overwrite an operator-customized profile.
			if emptyRuntimeProfileConfiguration(current.Configuration) && !emptyRuntimeProfileConfiguration(configuration) {
				current.Configuration = configuration
				if len(current.Requirements) == 0 {
					current.Requirements = json.RawMessage(`{}`)
				}
				if _, updateErr := registry.UpsertRuntimeProfile(c.Request.Context(), current); updateErr != nil {
					return updateErr
				}
			}
			continue
		} else if !errors.Is(err, store.ErrNotFound) {
			return err
		}
		runtimeName := capabilities.ProviderCapabilities[providerName].Runtime
		if runtimeName == "" {
			runtimeName = providerName
		}
		if _, err := registry.UpsertRuntimeProfile(c.Request.Context(), &controlmodel.RuntimeProfile{
			Tenant: req.Tenant, Namespace: req.Namespace, Name: profileName,
			Provider: providerName, Runtime: runtimeName,
			Configuration: configuration, Requirements: json.RawMessage(`{}`),
		}); err != nil {
			return err
		}
	}
	return nil
}

func (s *Server) registerRuntimeHost(c *gin.Context) {
	var req runtimeHostRegistrationRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.HostKey == "" || req.PoolName == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "hostKey and poolName are required"})
		return
	}
	if req.Tenant == "" {
		req.Tenant = "default"
	}
	if req.Namespace == "" {
		req.Namespace = defaultNamespace
	}
	if rawClaims, ok := c.Get(runtimeHostClaimsContextKey); ok {
		claims, valid := rawClaims.(runtimeauth.Claims)
		if !valid || claims.HostKey != req.HostKey || claims.Tenant != req.Tenant || claims.Namespace != req.Namespace {
			c.JSON(http.StatusUnauthorized, ErrorResponse{Error: "Runtime Host credential scope mismatch"})
			return
		}
	}
	if err := s.ensureRuntimeDefaults(c, req); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	host, err := s.store.RuntimeRegistry().UpsertRuntimeHost(c.Request.Context(), &controlmodel.RuntimeHost{
		Tenant: req.Tenant, Namespace: req.Namespace, HostKey: req.HostKey,
		PoolName: req.PoolName, DaemonVersion: req.DaemonVersion, OS: req.OS, Arch: req.Arch,
		Labels: req.Labels, Capabilities: req.Capabilities, Capacity: req.Capacity,
		State: controlmodel.RuntimeHostOnline,
	})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"host": host, "heartbeatIntervalSeconds": 15})
}

func (s *Server) listRuntimeHosts(c *gin.Context) {
	hosts, err := s.store.RuntimeRegistry().ListRuntimeHosts(c.Request.Context(), c.Query("tenant"),
		c.Query("namespace"), c.Query("poolName"), c.Query("state"))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": hosts})
}

func (s *Server) getRuntimeHost(c *gin.Context) {
	id, ok := parseUUIDParam(c, "hostId")
	if !ok {
		return
	}
	host, err := s.store.RuntimeRegistry().GetRuntimeHost(c.Request.Context(), id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"host": host})
}

func (s *Server) updateRuntimeHostCapacity(c *gin.Context) {
	id, ok := parseUUIDParam(c, "hostId")
	if !ok {
		return
	}
	var req struct {
		Capacity         int32  `json:"capacity"`
		ExpectedCapacity *int32 `json:"expectedCapacity"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.ExpectedCapacity == nil || req.Capacity < 1 || req.Capacity > controlmodel.MaxRuntimeHostCapacity {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "capacity must be an integer between 1 and 50; expectedCapacity is required"})
		return
	}
	host, err := s.store.RuntimeRegistry().GetRuntimeHost(c.Request.Context(), id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	host, err = s.store.RuntimeRegistry().SetRuntimeHostCapacity(c.Request.Context(), id, *req.ExpectedCapacity, req.Capacity)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"host": host})
}

func (s *Server) drainRuntimeHost(c *gin.Context) {
	s.setRuntimeHostStateFromOperations(c, controlmodel.RuntimeHostDraining)
}
func (s *Server) resumeRuntimeHost(c *gin.Context) {
	s.setRuntimeHostStateFromOperations(c, controlmodel.RuntimeHostOnline)
}

func (s *Server) setRuntimeHostStateFromOperations(c *gin.Context, state string) {
	id, ok := parseUUIDParam(c, "hostId")
	if !ok {
		return
	}
	host, err := s.store.RuntimeRegistry().GetRuntimeHost(c.Request.Context(), id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	host, err = s.store.RuntimeRegistry().SetRuntimeHostState(c.Request.Context(), id, host.LeaseGeneration, state)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"host": host})
}

func (s *Server) disableRuntimeBinding(c *gin.Context) { s.setRuntimeBindingEnabled(c, false) }
func (s *Server) enableRuntimeBinding(c *gin.Context)  { s.setRuntimeBindingEnabled(c, true) }

func (s *Server) setRuntimeBindingEnabled(c *gin.Context, enabled bool) {
	id, ok := parseUUIDParam(c, "bindingId")
	if !ok {
		return
	}
	binding, err := s.store.AgentCatalog().GetBinding(c.Request.Context(), id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	binding.Enabled = enabled
	binding, err = s.store.AgentCatalog().UpdateBinding(c.Request.Context(), binding, binding.Version)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"binding": binding})
}

func (s *Server) listOutboxDeadLetters(c *gin.Context) {
	items, err := s.store.Outbox().ListDeadLetters(c.Request.Context(), c.Query("tenant"), c.Query("namespace"), queryInt(c, "limit", 100))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if a := accessFrom(c); a != nil {
		filtered := items[:0]
		for _, event := range items {
			if s.canReceiveWorkEvent(c.Request.Context(), a, event) {
				filtered = append(filtered, event)
			}
		}
		items = filtered
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) replayOutboxDeadLetter(c *gin.Context) {
	if accessFrom(c) != nil {
		c.JSON(403, ErrorResponse{Error: "dead-letter replay requires the infrastructure service identity"})
		return
	}
	id, ok := parseUUIDParam(c, "eventId")
	if !ok {
		return
	}
	event, err := s.store.Outbox().ReplayDeadLetter(c.Request.Context(), id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusAccepted, gin.H{"event": event})
}

func (s *Server) heartbeatRuntimeHost(c *gin.Context) {
	id, ok := parseUUIDParam(c, "hostId")
	if !ok {
		return
	}
	var req struct {
		Generation   int64           `json:"generation"`
		Active       int32           `json:"active"`
		Capabilities json.RawMessage `json:"capabilities,omitempty"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Generation <= 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "generation is required"})
		return
	}
	host, err := s.store.RuntimeRegistry().HeartbeatRuntimeHost(c.Request.Context(), id, req.Generation, req.Active, req.Capabilities)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"host": host})
}

func (s *Server) setRuntimeHostState(c *gin.Context) {
	id, ok := parseUUIDParam(c, "hostId")
	if !ok {
		return
	}
	var req struct {
		Generation int64  `json:"generation"`
		State      string `json:"state"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Generation <= 0 || req.State == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "generation and state are required"})
		return
	}
	host, err := s.store.RuntimeRegistry().SetRuntimeHostState(c.Request.Context(), id, req.Generation, req.State)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"host": host})
}

type executionLeaseRequest struct {
	Generation   int64  `json:"generation"`
	LeaseOwner   string `json:"leaseOwner"`
	LeaseToken   string `json:"leaseToken"`
	FencingToken int64  `json:"fencingToken"`
	LeaseSeconds int64  `json:"leaseSeconds,omitempty"`
}

func (s *Server) claimExecutionAttempt(c *gin.Context) {
	hostID, ok := parseUUIDParam(c, "hostId")
	if !ok {
		return
	}
	var req struct {
		Tenant          string `json:"tenant"`
		Namespace       string `json:"namespace"`
		RuntimePoolName string `json:"runtimePoolName"`
		executionLeaseRequest
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Generation <= 0 || req.LeaseOwner == "" || req.LeaseToken == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "generation, leaseOwner, and leaseToken are required"})
		return
	}
	if req.LeaseSeconds <= 0 {
		req.LeaseSeconds = 30
	}
	execution, err := s.taskPlane.Claim(c.Request.Context(), store.ExecutionClaim{
		Tenant: req.Tenant, Namespace: req.Namespace, RuntimePoolName: req.RuntimePoolName,
		HostID: hostID, HostGeneration: req.Generation, LeaseOwner: req.LeaseOwner,
		LeaseToken: req.LeaseToken, LeaseTTL: time.Duration(req.LeaseSeconds) * time.Second,
	})
	if errors.Is(err, store.ErrNotFound) {
		c.Status(http.StatusNoContent)
		return
	}
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if _, err = s.ensureHostedTaskSession(c.Request.Context(), execution); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	task, err := s.store.Collaboration().GetAgentTask(c.Request.Context(), execution.AgentTaskID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	var snapshot controlmodel.RuntimeDispatchSnapshot
	if err = json.Unmarshal(execution.RuntimeBinding, &snapshot); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: "invalid execution runtime snapshot"})
		return
	}
	profile := snapshot.RuntimeProfile
	if profile == nil {
		// Transitional fallback for Attempts created before hosted snapshots
		// carried the immutable RuntimeProfile.
		profile, err = s.store.RuntimeRegistry().GetRuntimeProfile(c.Request.Context(), execution.Tenant,
			execution.Namespace, execution.RuntimeProfileName)
		if err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
	}
	// Hosts receive the immutable, per-attempt resolved configuration. Keep the
	// registry profile itself unchanged because it may be shared by many Agents.
	if len(snapshot.ResolvedProviderConfiguration) > 0 {
		resolved := *profile
		resolved.Configuration = append(json.RawMessage(nil), snapshot.ResolvedProviderConfiguration...)
		profile = &resolved
	}
	contextEnvelope, err := s.collaborationService().BuildContext(c.Request.Context(), task.ID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	var definition map[string]any
	if len(snapshot.Definition) > 0 {
		if err := json.Unmarshal(snapshot.Definition, &definition); err != nil {
			c.JSON(500, ErrorResponse{Error: "invalid frozen Agent definition"})
			return
		}
	} else if s.product != nil {
		if agentID, parseErr := uuid.Parse(task.AgentRef); parseErr == nil {
			if agent, agentErr := s.store.AgentCatalog().GetAgent(c.Request.Context(), agentID); agentErr == nil {
				loaded, definitionErr := s.product.RuntimeDefinition(
					c.Request.Context(), agent.OwnerRef, agent.ID.String())
				if definitionErr == nil {
					definition = loaded
				}
			}
		}
	}
	hostedTokens := s.taskTokens
	hostedTokens.TTL = 24 * time.Hour
	attemptToken, err := hostedTokens.MintAttempt(execution.ID, execution.DispatchGeneration,
		string(execution.BackendKind), hostID.String(), time.Now().UTC())
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	taskToken, err := hostedTokens.MintScoped(task.ID, execution.ID,
		execution.DispatchGeneration, time.Now().UTC())
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"task": task, "context": contextEnvelope, "attempt": execution,
		"attemptToken": attemptToken, "taskToken": taskToken, "runtimeProfile": profile,
		"executionOverrides": snapshot.ExecutionOverrides, "definition": definition})
}

func (s *Server) renewExecutionAttempt(c *gin.Context) {
	_, executionID, req, ok := s.bindExecutionLease(c)
	if !ok {
		return
	}
	if req.LeaseSeconds <= 0 {
		req.LeaseSeconds = 30
	}
	current, err := s.store.ExecutionAttempts().Get(c.Request.Context(), executionID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	// An Agent may complete/fail its own task through the collaboration MCP
	// while the provider process is still flushing its final JSONL records.
	// Return that authoritative terminal state instead of turning a successful
	// cooperative completion into a daemon-side lease error.
	if controlmodel.IsExecutionAttemptTerminal(current.State) && current.LeaseToken == req.LeaseToken && current.FencingToken == req.FencingToken {
		c.JSON(http.StatusOK, gin.H{"attempt": current})
		return
	}
	execution, err := s.store.ExecutionAttempts().RenewLease(c.Request.Context(), executionID,
		req.LeaseToken, req.FencingToken, time.Duration(req.LeaseSeconds)*time.Second)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"attempt": execution})
}

func (s *Server) prepareExecutionAttempt(c *gin.Context) {
	_, executionID, req, ok := s.bindExecutionLease(c)
	if !ok {
		return
	}
	execution, err := s.taskPlane.MarkPreparing(c.Request.Context(), executionID, req.LeaseToken, req.FencingToken)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"attempt": execution})
}

func (s *Server) startExecutionAttempt(c *gin.Context) {
	_, executionID, req, ok := s.bindExecutionLease(c)
	if !ok {
		return
	}
	var payload struct {
		ProviderSessionID string `json:"providerSessionId,omitempty"`
		WorkspaceKey      string `json:"workspaceKey,omitempty"`
	}
	_ = json.Unmarshal(req.extra, &payload)
	execution, err := s.taskPlane.MarkRunning(c.Request.Context(), executionID, req.LeaseToken,
		req.FencingToken, payload.ProviderSessionID, payload.WorkspaceKey)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"attempt": execution})
}

func (s *Server) completeExecutionAttempt(c *gin.Context) {
	_, executionID, req, ok := s.bindExecutionLease(c)
	if !ok {
		return
	}
	var payload struct {
		Result     json.RawMessage `json:"result,omitempty"`
		Checkpoint json.RawMessage `json:"checkpoint,omitempty"`
	}
	_ = json.Unmarshal(req.extra, &payload)
	execution, err := s.taskPlane.Complete(c.Request.Context(), executionID, req.LeaseToken,
		req.FencingToken, payload.Result, payload.Checkpoint)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if err = s.projectHostedAttemptTerminal(c.Request.Context(), execution); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"attempt": execution})
}

func (s *Server) checkpointExecutionAttempt(c *gin.Context) {
	_, executionID, req, ok := s.bindExecutionLease(c)
	if !ok {
		return
	}
	var payload struct {
		ProviderSessionID string          `json:"providerSessionId,omitempty"`
		Checkpoint        json.RawMessage `json:"checkpoint,omitempty"`
	}
	_ = json.Unmarshal(req.extra, &payload)
	execution, err := s.taskPlane.Checkpoint(c.Request.Context(), executionID, req.LeaseToken,
		req.FencingToken, payload.ProviderSessionID, payload.Checkpoint)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"attempt": execution})
}

// appendExecutionAttemptEvent persists the provider's observable stream on the
// Run timeline while its Attempt is active. A terminal Attempt seals the
// timeline; the response returns accepted=false so the Runtime Host can stop a
// provider that is still flushing output.
func (s *Server) appendExecutionAttemptEvent(c *gin.Context) {
	hostID, attemptID, req, ok := s.bindExecutionLease(c)
	if !ok {
		return
	}
	var payload struct {
		Ordinal           int64           `json:"ordinal"`
		Provider          string          `json:"provider"`
		EventType         string          `json:"eventType"`
		ProviderSessionID string          `json:"providerSessionId,omitempty"`
		Raw               json.RawMessage `json:"raw,omitempty"`
	}
	if err := json.Unmarshal(req.extra, &payload); err != nil || payload.Ordinal <= 0 || strings.TrimSpace(payload.EventType) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "ordinal and eventType are required"})
		return
	}
	if len(payload.Raw) > 256*1024 {
		c.JSON(http.StatusRequestEntityTooLarge, ErrorResponse{Error: "provider event exceeds 256 KiB"})
		return
	}
	attempt, err := s.store.ExecutionAttempts().Get(c.Request.Context(), attemptID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if attempt.LeaseToken != req.LeaseToken || attempt.FencingToken != req.FencingToken {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "stale execution attempt lease"})
		return
	}
	if controlmodel.IsExecutionAttemptTerminal(attempt.State) {
		c.JSON(http.StatusOK, gin.H{"attempt": attempt, "accepted": false})
		return
	}
	eventPayload, err := json.Marshal(gin.H{
		"provider": payload.Provider, "eventType": payload.EventType,
		"providerSessionId": payload.ProviderSessionID, "raw": payload.Raw,
	})
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid provider event"})
		return
	}
	event, err := s.store.Orchestration().AppendRunEvent(c.Request.Context(), &controlmodel.RunEvent{
		RunID: attempt.RunID, Tenant: attempt.Tenant, Namespace: attempt.Namespace,
		NodeID: &attempt.NodeID, AgentTaskID: &attempt.AgentTaskID, AttemptID: &attempt.ID,
		Type: "attempt.provider_event", Actor: controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "runtime-host:" + hostID.String()},
		Payload: eventPayload, IdempotencyKey: "provider-event:" + attempt.ID.String() + ":" + strconv.FormatInt(payload.Ordinal, 10),
	})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if err = s.projectHostedProviderEvent(c.Request.Context(), attempt, payload.Provider,
		payload.EventType, payload.ProviderSessionID, payload.Ordinal, payload.Raw); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusAccepted, gin.H{"event": event, "attempt": attempt, "accepted": true})
}

func (s *Server) failExecutionAttempt(c *gin.Context) {
	_, executionID, req, ok := s.bindExecutionLease(c)
	if !ok {
		return
	}
	var payload struct {
		FailureCode    string          `json:"failureCode"`
		FailureMessage string          `json:"failureMessage"`
		Checkpoint     json.RawMessage `json:"checkpoint,omitempty"`
	}
	_ = json.Unmarshal(req.extra, &payload)
	execution, err := s.taskPlane.Fail(c.Request.Context(), executionID, req.LeaseToken,
		req.FencingToken, payload.FailureCode, payload.FailureMessage, payload.Checkpoint)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if err = s.projectHostedAttemptTerminal(c.Request.Context(), execution); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"attempt": execution})
}

func (s *Server) cancelledExecutionAttempt(c *gin.Context) {
	_, attemptID, req, ok := s.bindExecutionLease(c)
	if !ok {
		return
	}
	attempt, err := s.taskPlane.ConfirmCancelled(c.Request.Context(), attemptID, req.LeaseToken, req.FencingToken)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if err = s.projectHostedAttemptTerminal(c.Request.Context(), attempt); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"attempt": attempt})
}

type boundExecutionLease struct {
	executionLeaseRequest
	extra json.RawMessage
}

func (s *Server) bindExecutionLease(c *gin.Context) (uuid.UUID, uuid.UUID, boundExecutionLease, bool) {
	hostID, ok := parseUUIDParam(c, "hostId")
	if !ok {
		return uuid.Nil, uuid.Nil, boundExecutionLease{}, false
	}
	executionID, ok := parseUUIDParam(c, "attemptId")
	if !ok {
		return uuid.Nil, uuid.Nil, boundExecutionLease{}, false
	}
	body, err := c.GetRawData()
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return uuid.Nil, uuid.Nil, boundExecutionLease{}, false
	}
	var req boundExecutionLease
	if err := json.Unmarshal(body, &req.executionLeaseRequest); err != nil || req.LeaseToken == "" || req.FencingToken <= 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "leaseToken and fencingToken are required"})
		return uuid.Nil, uuid.Nil, boundExecutionLease{}, false
	}
	current, err := s.store.ExecutionAttempts().Get(c.Request.Context(), executionID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return uuid.Nil, uuid.Nil, boundExecutionLease{}, false
	}
	if current.HostID == nil || *current.HostID != hostID {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "execution is not leased to this host"})
		return uuid.Nil, uuid.Nil, boundExecutionLease{}, false
	}
	if err := s.taskTokens.VerifyAttempt(c.GetHeader("X-Execution-Attempt-Token"), current.ID,
		current.DispatchGeneration, string(current.BackendKind), hostID.String(), time.Now().UTC()); err != nil {
		c.JSON(http.StatusUnauthorized, ErrorResponse{Error: "invalid execution attempt token"})
		return uuid.Nil, uuid.Nil, boundExecutionLease{}, false
	}
	req.extra = body
	return hostID, executionID, req, true
}

func parseUUIDParam(c *gin.Context, name string) (uuid.UUID, bool) {
	id, err := uuid.Parse(c.Param(name))
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid " + name})
		return uuid.Nil, false
	}
	return id, true
}

func (s *Server) writeControlPlaneError(c *gin.Context, err error) {
	switch {
	case errors.Is(err, store.ErrNotFound):
		c.JSON(http.StatusNotFound, ErrorResponse{Error: err.Error()})
	case errors.Is(err, store.ErrConflict):
		c.JSON(http.StatusConflict, ErrorResponse{Error: err.Error()})
	case errors.Is(err, store.ErrForbidden):
		c.JSON(http.StatusForbidden, ErrorResponse{Error: err.Error()})
	default:
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
	}
}

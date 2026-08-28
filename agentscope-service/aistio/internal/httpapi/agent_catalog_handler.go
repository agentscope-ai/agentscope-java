// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func (s *Server) activeAgentInScope(ctx context.Context, tenant, namespace, raw string) (*controlmodel.Agent, error) {
	id, err := uuid.Parse(raw)
	if err != nil {
		return nil, err
	}
	agent, err := s.store.AgentCatalog().GetAgent(ctx, id)
	if err != nil {
		return nil, err
	}
	if agent.Tenant != tenant || agent.Namespace != namespace {
		return nil, store.ErrNotFound
	}
	if agent.Status != controlmodel.AgentActive {
		return nil, store.ErrConflict
	}
	return agent, nil
}

type agentRegistrationRequest struct {
	Tenant           string          `json:"tenant"`
	Namespace        string          `json:"namespace"`
	AgentKey         string          `json:"agentKey"`
	DisplayName      string          `json:"displayName,omitempty"`
	Description      string          `json:"description,omitempty"`
	OwnerType        string          `json:"ownerType,omitempty"`
	OwnerRef         string          `json:"ownerRef,omitempty"`
	InstanceKey      string          `json:"instanceKey"`
	Framework        string          `json:"framework,omitempty"`
	FrameworkVersion string          `json:"frameworkVersion,omitempty"`
	SDKVersion       string          `json:"sdkVersion,omitempty"`
	Capabilities     json.RawMessage `json:"capabilities,omitempty"`
	Labels           json.RawMessage `json:"labels,omitempty"`
	RoutingKey       string          `json:"routingKey,omitempty"`
	Capacity         int32           `json:"capacity,omitempty"`
	CredentialTTL    int64           `json:"credentialTtlSeconds,omitempty"`
}

func bearerToken(c *gin.Context) string {
	raw := strings.TrimSpace(c.GetHeader("Authorization"))
	if len(raw) > 7 && strings.EqualFold(raw[:7], "Bearer ") {
		return strings.TrimSpace(raw[7:])
	}
	return ""
}

func newRegistrationToken() (string, []byte, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", nil, err
	}
	token := "asreg_" + base64.RawURLEncoding.EncodeToString(raw)
	hash := sha256.Sum256([]byte(token))
	return token, hash[:], nil
}

func registrationTokenHash(token string) []byte {
	if token == "" {
		return nil
	}
	hash := sha256.Sum256([]byte(token))
	return hash[:]
}

func (s *Server) registerExternalAgent(c *gin.Context) {
	if s.store == nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "agent catalog is unavailable"})
		return
	}
	var req agentRegistrationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if req.Tenant == "" {
		req.Tenant = "default"
	}
	if req.Namespace == "" {
		req.Namespace = defaultNamespace
	}
	if req.AgentKey == "" || req.InstanceKey == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "agentKey and instanceKey are required"})
		return
	}
	provided := c.GetHeader("X-Agent-Registration-Credential")
	if provided == "" {
		provided = bearerToken(c)
	}
	bootstrap := c.GetHeader("X-Builder-Internal-Token")
	trustedBootstrap := s.internalToken != "" && (bootstrap == s.internalToken || provided == s.internalToken)
	if trustedBootstrap && provided == s.internalToken {
		provided = ""
	}
	plaintext, newHash, err := newRegistrationToken()
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: "failed to generate registration credential"})
		return
	}
	var expires *time.Time
	if req.CredentialTTL > 0 {
		value := time.Now().UTC().Add(time.Duration(req.CredentialTTL) * time.Second)
		expires = &value
	}
	result, err := s.store.AgentCatalog().RegisterExternal(c.Request.Context(), store.ExternalAgentRegistration{
		Tenant: req.Tenant, Namespace: req.Namespace, AgentKey: req.AgentKey,
		DisplayName: req.DisplayName, Description: req.Description, OwnerType: req.OwnerType, OwnerRef: req.OwnerRef,
		InstanceKey: req.InstanceKey, Framework: req.Framework, FrameworkVersion: req.FrameworkVersion,
		SDKVersion: req.SDKVersion, RoutingKey: req.RoutingKey, Capabilities: req.Capabilities,
		Labels: req.Labels, Capacity: req.Capacity, TrustedBootstrap: trustedBootstrap,
		ClaimCredentialHash: registrationTokenHash(provided), NewCredentialHash: newHash,
		CredentialExpiresAt: expires,
	})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if s.registry != nil && req.RoutingKey != "" {
		entry := dataplane.Entry{Tenant: result.Agent.Tenant, Namespace: result.Agent.Namespace,
			AgentName: result.Agent.AgentKey, InstanceID: result.Instance.ID.String(), BaseURL: req.RoutingKey,
			Framework: req.Framework, Source: dataplane.SourceSelfRegister}
		s.registry.Upsert(entry)
	}
	status := http.StatusOK
	response := gin.H{"agent": result.Agent, "binding": result.Binding, "instance": result.Instance}
	if result.CredentialCreated {
		status = http.StatusCreated
		response["registrationCredential"] = plaintext
		response["credential"] = result.Credential
	}
	c.JSON(status, response)
}

func (s *Server) listCatalogAgents(c *gin.Context) {
	limit, _ := strconv.Atoi(c.Query("limit"))
	agents, err := s.store.AgentCatalog().ListAgents(c.Request.Context(), store.AgentFilter{
		Tenant: c.Query("tenant"), Namespace: c.Query("namespace"), Status: controlmodel.AgentStatus(c.Query("status")),
		IncludeArchived: c.Query("includeArchived") == "true", Limit: limit,
	})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": agents})
}

type createCatalogAgentRequest struct {
	controlmodel.Agent
	Binding    *createCatalogBindingRequest    `json:"binding,omitempty"`
	Definition *product.ManagedDefinitionInput `json:"definition,omitempty"`
}

type createCatalogBindingRequest struct {
	Kind          controlmodel.DataPlaneKind `json:"kind"`
	Configuration json.RawMessage            `json:"configuration,omitempty"`
	Priority      int32                      `json:"priority,omitempty"`
}

func catalogOwnerRef(c *gin.Context, requested string) string {
	if value, ok := c.Get("userId"); ok {
		if id, valid := value.(string); valid && strings.TrimSpace(id) != "" {
			return strings.TrimSpace(id)
		}
	}
	if strings.TrimSpace(requested) != "" {
		return strings.TrimSpace(requested)
	}
	if value, ok := c.Get("username"); ok {
		if username, valid := value.(string); valid && strings.TrimSpace(username) != "" {
			return strings.TrimSpace(username)
		}
	}
	return "system"
}

func (s *Server) markAgentProvisioningFailed(ctx context.Context, agent *controlmodel.Agent, cause error) {
	if agent == nil {
		return
	}
	current, err := s.store.AgentCatalog().GetAgent(ctx, agent.ID)
	if err != nil || current.Status == controlmodel.AgentActive || current.Status == controlmodel.AgentArchived {
		return
	}
	current.Status = controlmodel.AgentProvisioningFailed
	current.Metadata, _ = json.Marshal(map[string]string{"provisioningError": cause.Error()})
	_, _ = s.store.AgentCatalog().UpdateAgent(ctx, current, current.Version)
}

func (s *Server) prepareCatalogAgent(ctx context.Context, in *controlmodel.Agent) (*controlmodel.Agent, bool, error) {
	existing, err := s.store.AgentCatalog().GetAgentByKey(ctx, in.Tenant, in.Namespace, in.AgentKey)
	if err == nil {
		if existing.OwnerRef != "" && in.OwnerRef != "" && existing.OwnerRef != in.OwnerRef {
			return nil, false, store.ErrForbidden
		}
		switch existing.Status {
		case controlmodel.AgentActive:
			return existing, false, nil
		case controlmodel.AgentProvisioning, controlmodel.AgentProvisioningFailed:
			if existing.Status == controlmodel.AgentProvisioningFailed {
				existing.Status = controlmodel.AgentProvisioning
				existing.Metadata = nil
				existing, err = s.store.AgentCatalog().UpdateAgent(ctx, existing, existing.Version)
			}
			return existing, false, err
		default:
			return nil, false, store.ErrConflict
		}
	}
	if !errors.Is(err, store.ErrNotFound) {
		return nil, false, err
	}
	in.Status = controlmodel.AgentProvisioning
	created, err := s.store.AgentCatalog().CreateAgent(ctx, in)
	return created, err == nil, err
}

func (s *Server) ensureCatalogBinding(ctx context.Context, agent *controlmodel.Agent, request createCatalogBindingRequest) (*controlmodel.AgentBinding, error) {
	bindings, err := s.store.AgentCatalog().ListBindings(ctx, agent.ID, true)
	if err != nil {
		return nil, err
	}
	for _, binding := range bindings {
		if binding.Kind != request.Kind {
			continue
		}
		if !binding.Enabled || binding.ArchivedAt != nil {
			return nil, store.ErrConflict
		}
		return binding, nil
	}
	priority := request.Priority
	if priority == 0 {
		priority = 100
	}
	binding := &controlmodel.AgentBinding{ID: uuid.NewSHA1(agent.ID, []byte("runtime-binding:"+string(request.Kind))),
		AgentID: agent.ID, Tenant: agent.Tenant, Namespace: agent.Namespace, Kind: request.Kind,
		Configuration: request.Configuration, Priority: priority, Enabled: true}
	created, err := s.store.AgentCatalog().CreateBinding(ctx, binding)
	if errors.Is(err, store.ErrConflict) {
		bindings, listErr := s.store.AgentCatalog().ListBindings(ctx, agent.ID, true)
		if listErr != nil {
			return nil, listErr
		}
		for _, candidate := range bindings {
			if candidate.ID == binding.ID && candidate.Enabled {
				return candidate, nil
			}
		}
	}
	return created, err
}

func (s *Server) activateCatalogAgent(ctx context.Context, agent *controlmodel.Agent, binding *controlmodel.AgentBinding) (*controlmodel.Agent, *controlmodel.AgentRuntimePolicy, error) {
	runtimeBinding, err := binding.RuntimeBinding()
	if err != nil {
		return nil, nil, err
	}
	policy, err := s.store.Orchestration().PutRuntimePolicy(ctx, &controlmodel.AgentRuntimePolicy{
		Tenant: agent.Tenant, Namespace: agent.Namespace, AgentRef: agent.ID.String(),
		SelectionMode: "ordered", FallbackMode: "disabled",
		Candidates: []controlmodel.RuntimeBindingCandidate{{Binding: runtimeBinding}},
	})
	if err != nil {
		return nil, nil, err
	}
	current, err := s.store.AgentCatalog().GetAgent(ctx, agent.ID)
	if err != nil {
		return nil, nil, err
	}
	if current.Status != controlmodel.AgentActive {
		current.Status = controlmodel.AgentActive
		current.Metadata = nil
		current, err = s.store.AgentCatalog().UpdateAgent(ctx, current, current.Version)
	}
	return current, policy, err
}

func (s *Server) createCatalogAgent(c *gin.Context) {
	var req createCatalogAgentRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	agent := &req.Agent
	if agent.Tenant == "" {
		agent.Tenant = "default"
	}
	if agent.Namespace == "" {
		agent.Namespace = defaultNamespace
	}
	if agent.AgentKey == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "agentKey is required"})
		return
	}
	agent.OwnerRef = catalogOwnerRef(c, agent.OwnerRef)
	if agent.OwnerType == "" {
		agent.OwnerType = "user"
	}
	if req.Binding == nil {
		created, err := s.store.AgentCatalog().CreateAgent(c.Request.Context(), agent)
		if err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
		c.JSON(http.StatusCreated, gin.H{"agent": created})
		return
	}
	if req.Binding.Kind == controlmodel.DataPlaneExternalApplication {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "external applications must use POST /api/v1/agent-registrations"})
		return
	}
	prepared, newlyCreated, err := s.prepareCatalogAgent(c.Request.Context(), agent)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	fail := func(err error) {
		s.markAgentProvisioningFailed(c.Request.Context(), prepared, err)
		s.writeControlPlaneError(c, err)
	}

	var definition map[string]any
	switch req.Binding.Kind {
	case controlmodel.DataPlaneManaged:
		if req.Definition == nil {
			fail(fmt.Errorf("managed Agent requires definition"))
			return
		}
		if s.product == nil {
			fail(fmt.Errorf("managed control plane is unavailable"))
			return
		}
		if req.Definition.Name == "" {
			req.Definition.Name = prepared.DisplayName
		}
		definition, err = s.product.EnsureManagedDefinition(c.Request.Context(), prepared.OwnerRef, prepared.ID.String(), *req.Definition)
		if err != nil {
			fail(err)
			return
		}
		configuration, _ := json.Marshal(controlmodel.ManagedBindingConfiguration{
			OwnerRef: prepared.OwnerRef, ManagedDefinitionRef: prepared.ID.String(),
		})
		req.Binding.Configuration = configuration
	case controlmodel.DataPlaneHostedRuntime:
		var cfg controlmodel.HostedBindingConfiguration
		if err = json.Unmarshal(req.Binding.Configuration, &cfg); err != nil {
			fail(fmt.Errorf("hosted binding configuration is invalid"))
			return
		}
		profile, profileErr := s.store.RuntimeRegistry().GetRuntimeProfileByID(c.Request.Context(), cfg.RuntimeProfileID)
		pool, poolErr := s.store.RuntimeRegistry().GetRuntimePoolByID(c.Request.Context(), cfg.RuntimePoolID)
		if profileErr != nil || poolErr != nil || profile.Tenant != prepared.Tenant || profile.Namespace != prepared.Namespace || pool.Tenant != prepared.Tenant || pool.Namespace != prepared.Namespace {
			fail(fmt.Errorf("hosted runtime profile and pool must exist in the Agent scope"))
			return
		}
	default:
		fail(fmt.Errorf("unsupported binding kind %q", req.Binding.Kind))
		return
	}
	binding, err := s.ensureCatalogBinding(c.Request.Context(), prepared, *req.Binding)
	if err != nil {
		fail(err)
		return
	}
	active, policy, err := s.activateCatalogAgent(c.Request.Context(), prepared, binding)
	if err != nil {
		fail(err)
		return
	}
	status := http.StatusOK
	if newlyCreated {
		status = http.StatusCreated
	}
	c.JSON(status, gin.H{"agent": active, "binding": binding, "policy": policy, "definition": definition})
}

func (s *Server) getManagedAgentDefinition(c *gin.Context) {
	if s.product == nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "managed control plane is unavailable"})
		return
	}
	agentID, ok := parseUUIDParam(c, "agentId")
	if !ok {
		return
	}
	agent, err := s.store.AgentCatalog().GetAgent(c.Request.Context(), agentID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	definition, err := s.product.ManagedDefinition(c.Request.Context(), agent.OwnerRef, agent.ID.String())
	if err != nil {
		if errors.Is(err, product.ErrManagedDefinitionNotFound) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: err.Error()})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"agentId": agent.ID, "definition": definition})
}

func (s *Server) listManagedAgentVersions(c *gin.Context) {
	if s.product == nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "managed control plane is unavailable"})
		return
	}
	agentID, ok := parseUUIDParam(c, "agentId")
	if !ok {
		return
	}
	agent, err := s.store.AgentCatalog().GetAgent(c.Request.Context(), agentID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	versions, err := s.product.ManagedDefinitionVersions(c.Request.Context(), agent.OwnerRef, agent.ID.String())
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"agentId": agent.ID, "versions": versions})
}

func (s *Server) getCatalogAgent(c *gin.Context) {
	id, ok := parseUUIDParam(c, "agentId")
	if !ok {
		return
	}
	agent, err := s.store.AgentCatalog().GetAgent(c.Request.Context(), id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"agent": agent})
}

type patchAgentRequest struct {
	DisplayName  *string                   `json:"displayName"`
	Description  *string                   `json:"description"`
	OwnerType    *string                   `json:"ownerType"`
	OwnerRef     *string                   `json:"ownerRef"`
	Status       *controlmodel.AgentStatus `json:"status"`
	Capabilities *json.RawMessage          `json:"capabilities"`
	Labels       *json.RawMessage          `json:"labels"`
	Metadata     *json.RawMessage          `json:"metadata"`
	Version      int64                     `json:"version"`
}

func (s *Server) patchCatalogAgent(c *gin.Context) {
	id, ok := parseUUIDParam(c, "agentId")
	if !ok {
		return
	}
	agent, err := s.store.AgentCatalog().GetAgent(c.Request.Context(), id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	var req patchAgentRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.Version <= 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "valid version is required"})
		return
	}
	if req.DisplayName != nil {
		agent.DisplayName = *req.DisplayName
	}
	if req.Description != nil {
		agent.Description = *req.Description
	}
	if req.OwnerType != nil {
		agent.OwnerType = *req.OwnerType
	}
	if req.OwnerRef != nil {
		agent.OwnerRef = *req.OwnerRef
	}
	if req.Status != nil {
		agent.Status = *req.Status
	}
	if req.Capabilities != nil {
		agent.Capabilities = *req.Capabilities
	}
	if req.Labels != nil {
		agent.Labels = *req.Labels
	}
	if req.Metadata != nil {
		agent.Metadata = *req.Metadata
	}
	updated, err := s.store.AgentCatalog().UpdateAgent(c.Request.Context(), agent, req.Version)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"agent": updated})
}

func (s *Server) listAgentBindings(c *gin.Context) {
	id, ok := parseUUIDParam(c, "agentId")
	if !ok {
		return
	}
	bindings, err := s.store.AgentCatalog().ListBindings(c.Request.Context(), id, c.Query("includeDisabled") == "true")
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": bindings})
}

func (s *Server) createAgentBinding(c *gin.Context) {
	agentID, ok := parseUUIDParam(c, "agentId")
	if !ok {
		return
	}
	agent, err := s.store.AgentCatalog().GetAgent(c.Request.Context(), agentID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	var binding controlmodel.AgentBinding
	if err := c.ShouldBindJSON(&binding); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	binding.AgentID, binding.Tenant, binding.Namespace = agent.ID, agent.Tenant, agent.Namespace
	created, err := s.store.AgentCatalog().CreateBinding(c.Request.Context(), &binding)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"binding": created})
}

func (s *Server) patchAgentBinding(c *gin.Context) {
	agentID, ok := parseUUIDParam(c, "agentId")
	if !ok {
		return
	}
	bindingID, ok := parseUUIDParam(c, "bindingId")
	if !ok {
		return
	}
	var req controlmodel.AgentBinding
	if err := c.ShouldBindJSON(&req); err != nil || req.Version <= 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "valid version is required"})
		return
	}
	current, err := s.store.AgentCatalog().GetBinding(c.Request.Context(), bindingID)
	if err != nil || current.AgentID != agentID {
		if err == nil {
			err = store.ErrNotFound
		}
		s.writeControlPlaneError(c, err)
		return
	}
	current.Configuration, current.Priority, current.Enabled = req.Configuration, req.Priority, req.Enabled
	updated, err := s.store.AgentCatalog().UpdateBinding(c.Request.Context(), current, req.Version)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"binding": updated})
}

func (s *Server) listCatalogAgentInstances(c *gin.Context) {
	agentID, ok := parseUUIDParam(c, "agentId")
	if !ok {
		return
	}
	agent, err := s.store.AgentCatalog().GetAgent(c.Request.Context(), agentID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	instances, err := s.store.RuntimeRegistry().ListAgentInstances(c.Request.Context(), agent.Tenant, agent.Namespace, agent.ID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": instances})
}

func (s *Server) rotateAgentRegistrationCredential(c *gin.Context) {
	agentID, ok := parseUUIDParam(c, "agentId")
	if !ok {
		return
	}
	var req struct {
		TTL int64 `json:"ttlSeconds"`
	}
	_ = c.ShouldBindJSON(&req)
	plaintext, hash, err := newRegistrationToken()
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: "failed to generate credential"})
		return
	}
	var expires *time.Time
	if req.TTL > 0 {
		value := time.Now().UTC().Add(time.Duration(req.TTL) * time.Second)
		expires = &value
	}
	credential, err := s.store.AgentCatalog().RotateRegistrationCredential(c.Request.Context(), agentID, hash, expires)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"credential": credential, "registrationCredential": plaintext})
}

func (s *Server) revokeAgentRegistrationCredential(c *gin.Context) {
	agentID, ok := parseUUIDParam(c, "agentId")
	if !ok {
		return
	}
	credentialID, ok := parseUUIDParam(c, "credentialId")
	if !ok {
		return
	}
	if err := s.store.AgentCatalog().RevokeRegistrationCredential(c.Request.Context(), agentID, credentialID); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

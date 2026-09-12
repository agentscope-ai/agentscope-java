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
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	runtimeprovider "github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
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
	if a := accessFrom(c); a != nil {
		req.OwnerRef = namespaceResourceOwner(a.Namespace)
		req.OwnerType = "namespace"
	}
	result, err := s.store.AgentCatalog().RegisterExternal(c.Request.Context(), store.ExternalAgentRegistration{
		Tenant: req.Tenant, Namespace: req.Namespace, AgentKey: req.AgentKey,
		DisplayName: req.DisplayName, Description: req.Description, OwnerType: req.OwnerType, OwnerRef: req.OwnerRef,
		InstanceKey: req.InstanceKey, Framework: req.Framework, FrameworkVersion: req.FrameworkVersion,
		SDKVersion: req.SDKVersion, RoutingKey: req.RoutingKey, Capabilities: req.Capabilities,
		Labels: req.Labels, Capacity: req.Capacity, TrustedBootstrap: true,
		NewCredentialHash:   newHash,
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
	status = http.StatusCreated
	response["registrationCredential"] = plaintext
	response["credential"] = result.Credential
	c.JSON(status, response)
}

func (s *Server) listCatalogAgents(c *gin.Context) {
	limit, _ := strconv.Atoi(c.Query("limit"))
	tenant, namespace := c.Query("tenant"), c.Query("namespace")
	if tenant == "" {
		tenant = "default"
	}
	if namespace == "" {
		namespace = defaultNamespace
	}
	agents, err := s.store.AgentCatalog().ListAgents(c.Request.Context(), store.AgentFilter{
		Tenant: tenant, Namespace: namespace, Status: controlmodel.AgentStatus(c.Query("status")),
		IncludeArchived: c.Query("includeArchived") == "true", Limit: limit, ExcludedIDs: excludedResourceIDs(c, "agent"),
	})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if a := accessFrom(c); a != nil {
		filtered := agents[:0]
		for _, agent := range agents {
			if !a.Namespace.Decide(a.User, "agent:"+agent.ID.String(), "discover").Allowed {
				continue
			}
			if !a.Namespace.Decide(a.User, "agent:"+agent.ID.String(), "inspect").Allowed {
				agent.Metadata = nil
				agent.Capabilities = nil
				agent.Labels = nil
			}
			filtered = append(filtered, agent)
		}
		agents = filtered
	}
	c.JSON(http.StatusOK, gin.H{"items": agents})
}

type agentRuntimeOption struct {
	ID               string          `json:"id"`
	Name             string          `json:"name"`
	Provider         string          `json:"provider"`
	Version          string          `json:"version,omitempty"`
	RuntimeProfileID uuid.UUID       `json:"runtimeProfileId"`
	RuntimePoolID    uuid.UUID       `json:"runtimePoolId"`
	HostCount        int             `json:"hostCount"`
	Capabilities     json.RawMessage `json:"capabilities,omitempty"`
	hostKeys         []string
}

type advertisedRuntimeCapabilities struct {
	Providers            map[string]string          `json:"providers"`
	ProviderCapabilities map[string]json.RawMessage `json:"providerCapabilities"`
}

// listAgentRuntimeOptions flattens the operator-facing Profile/Pool/Host
// model into the same concept Agent authors care about: an available Runtime.
// The original collections remain in the response for API compatibility.
func (s *Server) listAgentRuntimeOptions(c *gin.Context) {
	tenant, namespace := c.Query("tenant"), c.Query("namespace")
	if tenant == "" {
		tenant = "default"
	}
	if namespace == "" {
		namespace = defaultNamespace
	}
	profiles, err := s.store.RuntimeRegistry().ListRuntimeProfiles(c.Request.Context(), tenant, namespace)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	pools, err := s.store.RuntimeRegistry().ListRuntimePools(c.Request.Context(), tenant, namespace)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	hosts, err := s.store.RuntimeRegistry().ListRuntimeHosts(
		c.Request.Context(), tenant, namespace, "", controlmodel.RuntimeHostOnline)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	poolByName := make(map[string]*controlmodel.RuntimePool, len(pools))
	for _, pool := range pools {
		poolByName[pool.Name] = pool
	}
	defaultProfileByProvider := make(map[string]*controlmodel.RuntimeProfile)
	for _, profile := range profiles {
		current := defaultProfileByProvider[profile.Provider]
		if current == nil || profile.Name == automaticRuntimeProfileName(profile.Provider) {
			defaultProfileByProvider[profile.Provider] = profile
		}
	}
	optionsByID := make(map[string]*agentRuntimeOption)
	for _, host := range hosts {
		pool := poolByName[host.PoolName]
		if pool == nil {
			continue
		}
		var advertised advertisedRuntimeCapabilities
		if json.Unmarshal(host.Capabilities, &advertised) != nil {
			continue
		}
		for providerName, version := range advertised.Providers {
			profile := defaultProfileByProvider[providerName]
			if profile == nil {
				continue
			}
			id := profile.ID.String() + ":" + pool.ID.String()
			option := optionsByID[id]
			if option == nil {
				displayName := profile.Provider
				capabilities := advertised.ProviderCapabilities[providerName]
				var descriptor struct {
					DisplayName string `json:"displayName"`
				}
				if json.Unmarshal(capabilities, &descriptor) == nil && descriptor.DisplayName != "" {
					displayName = descriptor.DisplayName
				}
				option = &agentRuntimeOption{
					ID: id, Name: displayName, Provider: providerName, Version: version,
					RuntimeProfileID: profile.ID, RuntimePoolID: pool.ID,
					Capabilities: capabilities,
				}
				optionsByID[id] = option
			}
			option.HostCount++
			option.hostKeys = append(option.hostKeys, host.HostKey)
		}
	}
	runtimes := make([]*agentRuntimeOption, 0, len(optionsByID))
	for _, option := range optionsByID {
		sort.Strings(option.hostKeys)
		if option.HostCount == 1 {
			option.Name += " (" + option.hostKeys[0] + ")"
		} else {
			option.Name += fmt.Sprintf(" (%d hosts)", option.HostCount)
		}
		runtimes = append(runtimes, option)
	}
	sort.Slice(runtimes, func(i, j int) bool {
		if runtimes[i].Provider != runtimes[j].Provider {
			return runtimes[i].Provider < runtimes[j].Provider
		}
		return runtimes[i].Name < runtimes[j].Name
	})
	c.JSON(http.StatusOK, gin.H{"runtimes": runtimes, "profiles": profiles, "pools": pools})
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
	if a := accessFrom(c); a != nil {
		agent.OwnerRef = namespaceResourceOwner(a.Namespace)
		agent.OwnerType = "namespace"
		agent.Tenant = a.Namespace.Tenant
		agent.Namespace = a.Namespace.Name
	}
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
		if errors.Is(err, product.ErrLocalEnvironmentDisabled) ||
			errors.Is(err, product.ErrEnvironmentNotAvailable) ||
			errors.Is(err, product.ErrNoRunnableEnvironment) {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
			return
		}
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
		req.Definition.ProvisionDefaultEnvironment = true
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
		// A Hosted Agent uses the same portable definition as a Managed Agent.
		// RuntimeProfile and RuntimePool are execution details, not an alternate
		// place to store instructions, skills, tools, or workspace intent.
		if req.Definition != nil {
			if s.product == nil {
				fail(fmt.Errorf("Agent definition control plane is unavailable"))
				return
			}
			if req.Definition.Name == "" {
				req.Definition.Name = prepared.DisplayName
			}
			definition, err = s.product.EnsureManagedDefinition(
				c.Request.Context(), prepared.OwnerRef, prepared.ID.String(), *req.Definition)
			if err != nil {
				fail(err)
				return
			}
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

func (s *Server) getManagedAgentVersion(c *gin.Context) {
	if s.product == nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "managed control plane is unavailable"})
		return
	}
	agentID, ok := parseUUIDParam(c, "agentId")
	if !ok {
		return
	}
	version, err := strconv.Atoi(c.Param("version"))
	if err != nil || version <= 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "valid version is required"})
		return
	}
	agent, err := s.store.AgentCatalog().GetAgent(c.Request.Context(), agentID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	entry, err := s.product.ManagedDefinitionVersion(c.Request.Context(), agent.OwnerRef, agent.ID.String(), version)
	if err != nil {
		if errors.Is(err, product.ErrManagedDefinitionNotFound) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: err.Error()})
		} else {
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		}
		return
	}
	c.JSON(http.StatusOK, gin.H{"agentId": agent.ID, "version": entry})
}

func (s *Server) patchManagedAgentDefinition(c *gin.Context) {
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
	var req struct {
		product.ManagedDefinitionInput
		Version int `json:"version"`
	}
	if err = c.ShouldBindJSON(&req); err != nil || req.Version <= 0 || req.Name == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "name and valid version are required"})
		return
	}
	definition, err := s.product.UpdateManagedDefinition(c.Request.Context(), agent.OwnerRef, agent.ID.String(), req.ManagedDefinitionInput, req.Version)
	if err != nil {
		if errors.Is(err, product.ErrManagedDefinitionConflict) {
			c.JSON(http.StatusConflict, ErrorResponse{Error: err.Error()})
		} else if errors.Is(err, product.ErrManagedDefinitionNotFound) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: err.Error()})
		} else if errors.Is(err, product.ErrLocalEnvironmentDisabled) ||
			errors.Is(err, product.ErrEnvironmentNotAvailable) ||
			errors.Is(err, product.ErrNoRunnableEnvironment) {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		} else {
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		}
		return
	}
	if agent.DisplayName != req.Name || agent.Description != req.Description {
		agent.DisplayName, agent.Description = req.Name, req.Description
		if agent, err = s.store.AgentCatalog().UpdateAgent(c.Request.Context(), agent, agent.Version); err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
	}
	c.JSON(http.StatusOK, gin.H{"agent": agent, "definition": definition})
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
	if a := accessFrom(c); a != nil && !a.Namespace.Decide(a.User, "agent:"+agent.ID.String(), "inspect").Allowed {
		agent.Metadata = nil
		agent.Capabilities = nil
		agent.Labels = nil
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
	if a := accessFrom(c); a != nil && (req.OwnerRef != nil && *req.OwnerRef != agent.OwnerRef || req.OwnerType != nil && *req.OwnerType != agent.OwnerType) {
		c.JSON(400, ErrorResponse{Error: "resource ownership changes require a namespace migration"})
		return
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

type hostedAgentSettingsResponse struct {
	AgentID            uuid.UUID                              `json:"agentId"`
	BindingID          uuid.UUID                              `json:"bindingId"`
	BindingVersion     int64                                  `json:"bindingVersion"`
	RuntimeProfile     *controlmodel.RuntimeProfile           `json:"runtimeProfile"`
	RuntimePool        *controlmodel.RuntimePool              `json:"runtimePool"`
	ExecutionOverrides *controlmodel.HostedExecutionOverrides `json:"executionOverrides,omitempty"`
	MaxConcurrency     int32                                  `json:"maxConcurrency"`
	PolicyVersion      int64                                  `json:"policyVersion"`
}

func (s *Server) hostedAgentSettings(ctx context.Context, agentID uuid.UUID) (*hostedAgentSettingsResponse, *controlmodel.AgentBinding, *controlmodel.AgentRuntimePolicy, error) {
	agent, err := s.store.AgentCatalog().GetAgent(ctx, agentID)
	if err != nil {
		return nil, nil, nil, err
	}
	bindings, err := s.store.AgentCatalog().ListBindings(ctx, agent.ID, true)
	if err != nil {
		return nil, nil, nil, err
	}
	var binding *controlmodel.AgentBinding
	for _, candidate := range bindings {
		if candidate.Kind == controlmodel.DataPlaneHostedRuntime && candidate.Enabled && candidate.ArchivedAt == nil {
			binding = candidate
			break
		}
	}
	if binding == nil {
		return nil, nil, nil, store.ErrNotFound
	}
	var configuration controlmodel.HostedBindingConfiguration
	if err = json.Unmarshal(binding.Configuration, &configuration); err != nil {
		return nil, nil, nil, fmt.Errorf("invalid hosted binding configuration: %w", err)
	}
	profile, err := s.store.RuntimeRegistry().GetRuntimeProfileByID(ctx, configuration.RuntimeProfileID)
	if err != nil {
		return nil, nil, nil, err
	}
	pool, err := s.store.RuntimeRegistry().GetRuntimePoolByID(ctx, configuration.RuntimePoolID)
	if err != nil {
		return nil, nil, nil, err
	}
	policy, err := s.store.Orchestration().GetRuntimePolicy(ctx, agent.Tenant, agent.Namespace, agent.ID.String())
	if errors.Is(err, store.ErrNotFound) {
		policy, err = nil, nil
	}
	if err != nil {
		return nil, nil, nil, err
	}
	response := &hostedAgentSettingsResponse{AgentID: agent.ID, BindingID: binding.ID,
		BindingVersion: binding.Version, RuntimeProfile: profile, RuntimePool: pool,
		ExecutionOverrides: configuration.ExecutionOverrides}
	if policy != nil {
		response.MaxConcurrency, response.PolicyVersion = policy.MaxConcurrency, policy.Version
	}
	return response, binding, policy, nil
}

func (s *Server) getHostedAgentSettings(c *gin.Context) {
	agentID, ok := parseUUIDParam(c, "agentId")
	if !ok {
		return
	}
	settings, _, _, err := s.hostedAgentSettings(c.Request.Context(), agentID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"settings": settings})
}

type patchHostedAgentSettingsRequest struct {
	RuntimeProfileID   uuid.UUID                              `json:"runtimeProfileId"`
	RuntimePoolID      uuid.UUID                              `json:"runtimePoolId"`
	ExecutionOverrides *controlmodel.HostedExecutionOverrides `json:"executionOverrides,omitempty"`
	MaxConcurrency     *int32                                 `json:"maxConcurrency,omitempty"`
	BindingVersion     int64                                  `json:"bindingVersion"`
	PolicyVersion      int64                                  `json:"policyVersion"`
}

func validateHostedExecutionOverrides(profile *controlmodel.RuntimeProfile, overrides *controlmodel.HostedExecutionOverrides) error {
	if err := overrides.Validate(); err != nil || overrides == nil {
		return err
	}
	for _, value := range []string{overrides.ReasoningEffort, overrides.ServiceTier} {
		if len(value) > 64 || strings.ContainsAny(value, " \t\r\n\x00") {
			return fmt.Errorf("reasoningEffort and serviceTier must be single setting tokens")
		}
	}
	if _, err := runtimeprovider.ValidateCustomArgs(overrides.CustomArgs); err != nil {
		return err
	}
	configuration := map[string]any{}
	if len(overrides.ProviderConfiguration) > 0 {
		_ = json.Unmarshal(overrides.ProviderConfiguration, &configuration)
	}
	allowed := map[string]map[string]bool{
		"codex":       {"profile": true, "sandbox": true, "skipGitRepoCheck": true},
		"claude-code": {"permissionMode": true, "allowedTools": true, "disallowedTools": true, "maxTurns": true, "appendSystemPrompt": true},
		"qoder":       {"permissionMode": true, "allowedTools": true, "disallowedTools": true, "maxTurns": true, "maxOutputTokens": true, "contextWindow": true, "strictMCPConfig": true, "appendSystemPrompt": true, "agent": true},
		"qwenpaw":     {"agent": true, "permissionMode": true, "runtimeProvider": true, "localDiagnostics": true},
		"openclaw":    {"fallbacks": true, "thinking": true, "codeMode": true, "timeoutSeconds": true, "localModelLean": true, "isolated": true, "authEnvOnly": true},
	}
	for key := range configuration {
		if !allowed[profile.Provider][key] {
			return fmt.Errorf("provider setting %q is not supported for %s", key, profile.Provider)
		}
	}
	for _, key := range []string{"profile", "sandbox", "permissionMode", "appendSystemPrompt", "agent", "runtimeProvider", "thinking", "codeMode"} {
		if value, exists := configuration[key]; exists {
			text, valid := value.(string)
			if !valid || len(text) > 16*1024 || strings.ContainsRune(text, '\x00') {
				return fmt.Errorf("provider setting %q must be a valid string", key)
			}
		}
	}
	for _, key := range []string{"skipGitRepoCheck", "strictMCPConfig", "localDiagnostics", "localModelLean", "isolated", "authEnvOnly"} {
		if value, exists := configuration[key]; exists {
			if _, valid := value.(bool); !valid {
				return fmt.Errorf("provider setting %q must be a boolean", key)
			}
		}
	}
	for _, key := range []string{"maxTurns", "maxOutputTokens", "contextWindow", "timeoutSeconds"} {
		if value, exists := configuration[key]; exists {
			number, valid := value.(float64)
			if !valid || number < 0 || number != float64(int64(number)) {
				return fmt.Errorf("provider setting %q must be a non-negative integer", key)
			}
		}
	}
	for _, key := range []string{"allowedTools", "disallowedTools", "fallbacks"} {
		if value, exists := configuration[key]; exists {
			items, valid := value.([]any)
			if !valid || len(items) > 64 {
				return fmt.Errorf("provider setting %q must be a list of at most 64 strings", key)
			}
			for _, item := range items {
				text, ok := item.(string)
				if !ok || strings.TrimSpace(text) == "" || len(text) > 512 || strings.ContainsRune(text, '\x00') {
					return fmt.Errorf("provider setting %q contains an invalid value", key)
				}
			}
		}
	}
	base := map[string]any{}
	_ = json.Unmarshal(profile.Configuration, &base)
	if profile.Provider == "codex" {
		if sandbox, ok := configuration["sandbox"].(string); ok && sandbox != "" {
			rank := map[string]int{"read-only": 0, "workspace-write": 1, "danger-full-access": 2}
			requested, valid := rank[sandbox]
			if !valid {
				return fmt.Errorf("unsupported Codex sandbox %q", sandbox)
			}
			baseline := 1
			if configured, ok := base["sandbox"].(string); ok {
				if value, found := rank[configured]; found {
					baseline = value
				}
			}
			if requested > baseline {
				return fmt.Errorf("Agent sandbox cannot be more permissive than Runtime Profile sandbox")
			}
		}
	}
	if mode, ok := configuration["permissionMode"].(string); ok {
		if profile.Provider == "qoder" {
			switch mode {
			case "", "default", "auto", "accept_edits", "dont_ask", "bypass_permissions":
				return nil
			default:
				return fmt.Errorf("unsupported Qoder permission mode %q", mode)
			}
		}
		lower := strings.ToLower(mode)
		if strings.Contains(lower, "bypass") || strings.Contains(lower, "danger") || strings.Contains(lower, "yolo") {
			if baseline, _ := base["permissionMode"].(string); baseline != mode {
				return fmt.Errorf("Agent permissionMode cannot relax the Runtime Profile security baseline")
			}
		}
	}
	return nil
}

func (s *Server) patchHostedAgentSettings(c *gin.Context) {
	agentID, ok := parseUUIDParam(c, "agentId")
	if !ok {
		return
	}
	var req patchHostedAgentSettingsRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.BindingVersion <= 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "runtimeProfileId, runtimePoolId, and a valid bindingVersion are required"})
		return
	}
	if req.MaxConcurrency != nil && (*req.MaxConcurrency < 0 || *req.MaxConcurrency > 50) {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "maxConcurrency must be between 0 and 50"})
		return
	}
	if err := req.ExecutionOverrides.Validate(); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	currentSettings, binding, policy, err := s.hostedAgentSettings(c.Request.Context(), agentID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if req.RuntimeProfileID == uuid.Nil {
		req.RuntimeProfileID = currentSettings.RuntimeProfile.ID
	}
	if req.RuntimePoolID == uuid.Nil {
		req.RuntimePoolID = currentSettings.RuntimePool.ID
	}
	if req.ExecutionOverrides == nil {
		req.ExecutionOverrides = currentSettings.ExecutionOverrides
	}
	profile, profileErr := s.store.RuntimeRegistry().GetRuntimeProfileByID(c.Request.Context(), req.RuntimeProfileID)
	pool, poolErr := s.store.RuntimeRegistry().GetRuntimePoolByID(c.Request.Context(), req.RuntimePoolID)
	agent, agentErr := s.store.AgentCatalog().GetAgent(c.Request.Context(), agentID)
	if profileErr != nil || poolErr != nil || agentErr != nil || profile.Tenant != agent.Tenant || profile.Namespace != agent.Namespace || pool.Tenant != agent.Tenant || pool.Namespace != agent.Namespace {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "hosted runtime profile and pool must exist in the Agent scope"})
		return
	}
	if err = validateHostedExecutionOverrides(profile, req.ExecutionOverrides); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	configuration, _ := json.Marshal(controlmodel.HostedBindingConfiguration{RuntimeProfileID: req.RuntimeProfileID,
		RuntimePoolID: req.RuntimePoolID, ExecutionOverrides: req.ExecutionOverrides})
	previousConfiguration := append(json.RawMessage(nil), binding.Configuration...)
	binding.Configuration = configuration
	updatedBinding, err := s.store.AgentCatalog().UpdateBinding(c.Request.Context(), binding, req.BindingVersion)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	runtimeBinding, err := updatedBinding.RuntimeBinding()
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if policy == nil {
		policy = &controlmodel.AgentRuntimePolicy{Tenant: agent.Tenant, Namespace: agent.Namespace,
			AgentRef: agent.ID.String(), SelectionMode: "ordered", FallbackMode: "disabled"}
	} else if req.PolicyVersion > 0 {
		policy.Version = req.PolicyVersion
	}
	if req.MaxConcurrency != nil {
		policy.MaxConcurrency = *req.MaxConcurrency
	}
	matched := false
	for index := range policy.Candidates {
		if policy.Candidates[index].Binding.BindingID == binding.ID {
			policy.Candidates[index].Binding = runtimeBinding
			matched = true
		}
	}
	if !matched {
		policy.Candidates = append(policy.Candidates, controlmodel.RuntimeBindingCandidate{Binding: runtimeBinding})
	}
	if _, err = s.store.Orchestration().PutRuntimePolicy(c.Request.Context(), policy); err != nil {
		// Best-effort compensation keeps the binding and policy aligned when the
		// optimistic policy update loses a race.
		updatedBinding.Configuration = previousConfiguration
		_, _ = s.store.AgentCatalog().UpdateBinding(c.Request.Context(), updatedBinding, updatedBinding.Version)
		s.writeControlPlaneError(c, err)
		return
	}
	settings, _, _, err := s.hostedAgentSettings(c.Request.Context(), agentID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"settings": settings})
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

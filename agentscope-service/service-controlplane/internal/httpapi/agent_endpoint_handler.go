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
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/asdp"
	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/orchestration"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/secretcrypto"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
)

type endpointRateLimit struct {
	MaxConcurrent       int   `json:"maxConcurrent,omitempty"`
	MaxInvocationTokens int64 `json:"maxInvocationTokens,omitempty"`
	Requests            int   `json:"requests"`
	WindowSeconds       int   `json:"windowSeconds"`
}

type endpointAuthPolicy struct {
	Type string `json:"type"`
}

var endpointSlugPattern = regexp.MustCompile(`^[a-z0-9]+(?:-[a-z0-9]+)*$`)

type endpointReadiness struct {
	State      string `json:"state"`
	Reason     string `json:"reason"`
	Compatible bool   `json:"compatible"`
}

const endpointPrincipalContextKey = "endpoint-principal"

func newEndpointKey() (string, string, []byte, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", "", nil, err
	}
	encoded := base64.RawURLEncoding.EncodeToString(raw)
	key := "asep_" + encoded
	sum := sha256.Sum256([]byte(key))
	return key, encoded[:10], sum[:], nil
}

func endpointCredentialAAD(endpointID, credentialID uuid.UUID) []byte {
	return []byte(endpointID.String() + ":" + credentialID.String())
}

func (s *Server) buildEndpointCredential(endpointID uuid.UUID, name string, scopes json.RawMessage,
	expiresAt *time.Time, rotatedFrom *uuid.UUID) (*controlmodel.EndpointCredential, string, error) {
	key, prefix, hash, err := newEndpointKey()
	if err != nil {
		return nil, "", err
	}
	credentialID := uuid.New()
	ciphertext, err := secretcrypto.Encrypt(s.endpointCredentialKey, []byte(key),
		endpointCredentialAAD(endpointID, credentialID))
	if err != nil {
		return nil, "", err
	}
	return &controlmodel.EndpointCredential{
		ID: credentialID, EndpointID: endpointID, Name: name, KeyPrefix: prefix,
		SecretHash: hash, SecretCiphertext: ciphertext, Status: controlmodel.EndpointCredentialActive,
		Scopes: scopes, ExpiresAt: expiresAt, RotatedFrom: rotatedFrom,
	}, key, nil
}

func endpointKeyPrefix(key string) string {
	encoded := strings.TrimPrefix(strings.TrimSpace(key), "asep_")
	if len(encoded) < 10 || encoded == key {
		return ""
	}
	return encoded[:10]
}

func requestCorrelationID(c *gin.Context) string {
	if value := strings.TrimSpace(c.GetHeader("X-Correlation-ID")); value != "" {
		return value
	}
	return uuid.NewString()
}

func endpointPublic(v *controlmodel.Endpoint) *controlmodel.Endpoint {
	if v == nil {
		return nil
	}
	c := *v
	return &c
}

func endpointInvocationPublic(v *controlmodel.EndpointInvocation) gin.H {
	if v == nil {
		return nil
	}
	out := gin.H{"id": v.ID, "endpointId": v.EndpointID, "mode": v.Mode, "status": v.Status,
		"releaseId": v.ReleaseID, "correlationId": v.CorrelationID, "createdAt": v.CreatedAt, "updatedAt": v.UpdatedAt}
	if v.ConversationID != nil {
		out["conversationId"] = v.ConversationID
	}
	if v.TurnID != nil {
		out["turnId"] = v.TurnID
	}
	if v.SessionID != "" {
		out["sessionId"] = v.SessionID
	}
	if v.IssueID != nil {
		out["issueId"] = v.IssueID
	}
	if v.RunID != nil {
		out["runId"] = v.RunID
	}
	if len(v.Result) > 0 {
		out["result"] = v.Result
	}
	if v.ErrorCode != "" {
		out["errorCode"], out["errorMessage"] = v.ErrorCode, v.ErrorMessage
	}
	if v.StartedAt != nil {
		out["startedAt"] = v.StartedAt
	}
	if v.CompletedAt != nil {
		out["completedAt"] = v.CompletedAt
	}
	return out
}

func endpointConversationPublic(v *controlmodel.EndpointConversation) gin.H {
	if v == nil {
		return nil
	}
	out := gin.H{"id": v.ID, "endpointId": v.EndpointID, "sessionId": v.SessionID,
		"status": v.Status, "createdAt": v.CreatedAt, "updatedAt": v.UpdatedAt}
	if v.LastTurnAt != nil {
		out["lastTurnAt"] = v.LastTurnAt
	}
	return out
}

func sameJSON(a, b json.RawMessage) bool {
	if !json.Valid(a) || !json.Valid(b) {
		return bytes.Equal(a, b)
	}
	// jsonb preserves values, not object key order. Canonicalize objects before
	// comparing a stored request with a retry; retain exact numeric precision.
	canonical := func(raw json.RawMessage) ([]byte, error) {
		var value any
		decoder := json.NewDecoder(bytes.NewReader(raw))
		decoder.UseNumber()
		if err := decoder.Decode(&value); err != nil {
			return nil, err
		}
		return json.Marshal(value)
	}
	x, errA := canonical(a)
	y, errB := canonical(b)
	return errA == nil && errB == nil && bytes.Equal(x, y)
}

func validateEndpointSchema(raw json.RawMessage) error { return invocation.CheckSchema(raw) }
func validateEndpointInput(raw json.RawMessage, value any) error {
	return invocation.Validate(raw, value)
}

func validateEndpoint(in *controlmodel.Endpoint) error {
	if in.Name == "" || in.Slug == "" || in.TargetRef == uuid.Nil {
		return fmt.Errorf("name, slug and targetRef are required")
	}
	if !endpointSlugPattern.MatchString(in.Slug) {
		return fmt.Errorf("slug must contain lowercase letters, numbers, and single hyphen separators")
	}
	if in.InvocationMode != controlmodel.EndpointConversationMode && in.InvocationMode != controlmodel.EndpointJobMode {
		return fmt.Errorf("invocationMode must be conversation or job")
	}
	if in.InvocationMode == controlmodel.EndpointConversationMode && in.TargetType != controlmodel.EndpointTargetAgent {
		return fmt.Errorf("conversation endpoints require targetType=agent")
	}
	if err := validateEndpointSchema(in.InputSchema); err != nil {
		return fmt.Errorf("inputSchema: %w", err)
	}
	if err := invocation.CheckResultMapping(in.ResultMapping); err != nil {
		return fmt.Errorf("resultMapping: %w", err)
	}
	if err := validateEndpointSchema(in.OutputSchema); err != nil {
		return fmt.Errorf("outputSchema: %w", err)
	}
	if in.TimeoutSeconds < 0 || in.MaxPayloadBytes < 0 {
		return fmt.Errorf("timeoutSeconds and maxPayloadBytes cannot be negative")
	}
	if len(in.RateLimit) > 0 {
		var rate endpointRateLimit
		if err := json.Unmarshal(in.RateLimit, &rate); err != nil || rate.Requests < 0 || rate.MaxConcurrent < 0 || rate.MaxInvocationTokens < 0 || (rate.Requests > 0 && rate.WindowSeconds <= 0) {
			return fmt.Errorf("rateLimit requires positive requests and windowSeconds")
		}
	}
	return nil
}
func (s *Server) validateEndpointTarget(c *gin.Context, in *controlmodel.Endpoint) error {
	switch in.TargetType {
	case controlmodel.EndpointTargetAgent:
		_, err := s.activeAgentInScope(c, in.Tenant, in.Namespace, in.TargetRef.String())
		return err
	case controlmodel.EndpointTargetTeam:
		t, err := s.store.Collaboration().GetTeam(c, in.TargetRef)
		if err == nil && (t.Tenant != in.Tenant || t.Namespace != in.Namespace || t.Status != controlmodel.TeamActive) {
			return store.ErrNotFound
		}
		return err
	case controlmodel.EndpointTargetOrchestrationRevision:
		r, err := s.store.Orchestration().GetRevision(c, in.TargetRef)
		if err == nil && (r.Tenant != in.Tenant || r.Namespace != in.Namespace) {
			return store.ErrNotFound
		}
		return err
	default:
		return fmt.Errorf("unsupported targetType")
	}
}

func (s *Server) createEndpoint(c *gin.Context) {
	var in controlmodel.Endpoint
	if err := c.ShouldBindJSON(&in); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if in.Tenant == "" {
		in.Tenant = "default"
	}
	if in.Namespace == "" {
		in.Namespace = defaultNamespace
	}
	if err := validateEndpoint(&in); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if err := s.validateEndpointTarget(c, &in); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	var authPolicy endpointAuthPolicy
	if len(in.AuthPolicy) == 0 {
		authPolicy.Type = "api_key"
		in.AuthPolicy = json.RawMessage(`{"type":"api_key"}`)
	} else if json.Unmarshal(in.AuthPolicy, &authPolicy) != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "authPolicy is invalid"})
		return
	}
	if authPolicy.Type != "api_key" && authPolicy.Type != "platform" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "authPolicy.type must be api_key or platform"})
		return
	}
	if s.writeEndpointCreateConflict(c, &in) {
		return
	}
	in.Status = controlmodel.EndpointDraft
	v, err := s.store.Endpoints().Create(c, &in)
	if err != nil {
		if errors.Is(err, store.ErrConflict) {
			if s.writeEndpointCreateConflict(c, &in) {
				return
			}
			c.JSON(http.StatusConflict, ErrorResponse{
				Error: "Endpoint name or slug is already in use",
				Code:  "endpoint_identity_conflict",
				Hint:  "Choose a different Endpoint name and public URL slug.",
			})
			return
		}
		s.writeControlPlaneError(c, err)
		return
	}
	response := gin.H{"endpoint": endpointPublic(v)}
	c.JSON(http.StatusCreated, response)
}

func (s *Server) writeEndpointCreateConflict(c *gin.Context, in *controlmodel.Endpoint) bool {
	if _, err := s.store.Endpoints().GetBySlug(c, in.Slug); err == nil {
		c.JSON(http.StatusConflict, ErrorResponse{
			Error: fmt.Sprintf("Endpoint slug %q is already in use", in.Slug),
			Code:  "endpoint_slug_conflict",
			Hint:  "Choose a different slug for the public Endpoint URL.",
		})
		return true
	} else if !errors.Is(err, store.ErrNotFound) {
		s.writeControlPlaneError(c, err)
		return true
	}
	items, err := s.store.Endpoints().List(c, in.Tenant, in.Namespace)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return true
	}
	for _, item := range items {
		if item.Name == in.Name {
			c.JSON(http.StatusConflict, ErrorResponse{
				Error: fmt.Sprintf("Endpoint name %q is already in use in this scope", in.Name),
				Code:  "endpoint_name_conflict",
				Hint:  "Choose a different Endpoint name.",
			})
			return true
		}
	}
	return false
}

func (s *Server) listEndpoints(c *gin.Context) {
	tenant, namespace := c.Query("tenant"), c.Query("namespace")
	if tenant == "" {
		tenant = "default"
	}
	if namespace == "" {
		namespace = defaultNamespace
	}
	items, err := s.store.Endpoints().List(c, tenant, namespace)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	targetType := strings.TrimSpace(c.Query("targetType"))
	targetRef := strings.TrimSpace(c.Query("targetRef"))
	// Keep the collection contract stable for empty namespaces. A nil slice is
	// encoded as JSON null and breaks clients that correctly expect an array.
	filtered := make([]*controlmodel.Endpoint, 0, len(items))
	for i := range items {
		if targetType != "" && string(items[i].TargetType) != targetType {
			continue
		}
		if targetRef != "" && items[i].TargetRef.String() != targetRef {
			continue
		}
		filtered = append(filtered, endpointPublic(items[i]))
	}
	c.JSON(http.StatusOK, gin.H{"items": filtered})
}
func (s *Server) getEndpoint(c *gin.Context) {
	id, ok := parseUUIDParam(c, "endpointId")
	if !ok {
		return
	}
	v, err := s.store.Endpoints().Get(c, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"endpoint": endpointPublic(v)})
}
func (s *Server) patchEndpoint(c *gin.Context) {
	id, ok := parseUUIDParam(c, "endpointId")
	if !ok {
		return
	}
	v, err := s.store.Endpoints().Get(c, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if v.Status == controlmodel.EndpointArchived {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "archived Endpoint is immutable"})
		return
	}
	var in struct {
		Name            *string         `json:"name"`
		Description     *string         `json:"description"`
		InputSchema     json.RawMessage `json:"inputSchema"`
		OutputSchema    json.RawMessage `json:"outputSchema"`
		ResultMapping   json.RawMessage `json:"resultMapping"`
		RateLimit       json.RawMessage `json:"rateLimit"`
		TimeoutSeconds  *int            `json:"timeoutSeconds"`
		MaxPayloadBytes *int64          `json:"maxPayloadBytes"`
		Version         int64           `json:"version"`
	}
	if err = c.ShouldBindJSON(&in); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if in.Version == 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "version is required"})
		return
	}
	if v.Status == controlmodel.EndpointPublished && (len(in.InputSchema) > 0 || len(in.OutputSchema) > 0 || len(in.ResultMapping) > 0) {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "disable the Endpoint before changing its public schema"})
		return
	}
	if in.Name != nil {
		v.Name = *in.Name
	}
	if in.Description != nil {
		v.Description = *in.Description
	}
	if len(in.InputSchema) > 0 {
		if schemaErr := validateEndpointSchema(in.InputSchema); schemaErr != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "inputSchema: " + schemaErr.Error()})
			return
		}
		v.InputSchema = in.InputSchema
		if string(in.InputSchema) == "null" {
			v.InputSchema = nil
		}
	}
	if len(in.OutputSchema) > 0 {
		if schemaErr := validateEndpointSchema(in.OutputSchema); schemaErr != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "outputSchema: " + schemaErr.Error()})
			return
		}
		v.OutputSchema = in.OutputSchema
		if string(in.OutputSchema) == "null" {
			v.OutputSchema = nil
		}
	}
	if len(in.ResultMapping) > 0 {
		if err := invocation.CheckResultMapping(in.ResultMapping); err != nil {
			c.JSON(400, ErrorResponse{Error: err.Error()})
			return
		}
		v.ResultMapping = in.ResultMapping
		if string(in.ResultMapping) == "null" {
			v.ResultMapping = nil
		}
	}
	if len(in.RateLimit) > 0 {
		if string(in.RateLimit) == "null" {
			v.RateLimit = nil
		} else {
			var rate endpointRateLimit
			if json.Unmarshal(in.RateLimit, &rate) != nil || rate.Requests < 0 || rate.MaxConcurrent < 0 || rate.MaxInvocationTokens < 0 || (rate.Requests > 0 && rate.WindowSeconds <= 0) {
				c.JSON(http.StatusBadRequest, ErrorResponse{Error: "rateLimit requires positive requests and windowSeconds"})
				return
			}
			v.RateLimit = in.RateLimit
		}
	}
	if in.TimeoutSeconds != nil {
		if *in.TimeoutSeconds <= 0 {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "timeoutSeconds must be positive"})
			return
		}
		v.TimeoutSeconds = *in.TimeoutSeconds
	}
	if in.MaxPayloadBytes != nil {
		if *in.MaxPayloadBytes <= 0 {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "maxPayloadBytes must be positive"})
			return
		}
		v.MaxPayloadBytes = *in.MaxPayloadBytes
	}
	v, err = s.store.Endpoints().Update(c, v, in.Version)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"endpoint": endpointPublic(v)})
}

func (s *Server) inspectEndpointReadiness(ctx *gin.Context, endpoint *controlmodel.Endpoint) endpointReadiness {
	if endpoint.Status == controlmodel.EndpointArchived {
		return endpointReadiness{State: "disabled", Reason: "Endpoint is archived"}
	}
	switch endpoint.TargetType {
	case controlmodel.EndpointTargetAgent:
		agent, err := s.activeAgentInScope(ctx, endpoint.Tenant, endpoint.Namespace, endpoint.TargetRef.String())
		if err != nil {
			return endpointReadiness{State: "unavailable", Reason: "Target Agent is not active"}
		}
		bindings, _ := s.store.AgentCatalog().ListBindings(ctx, agent.ID, true)
		instances, _ := s.store.RuntimeRegistry().ListAgentInstances(ctx, endpoint.Tenant, endpoint.Namespace, agent.ID)
		readiness, _ := s.inspectAgentReadiness(ctx, agent, bindings, instances)
		if endpoint.InvocationMode == controlmodel.EndpointJobMode {
			compatible := readiness.State != "inactive" && readiness.State != "unbound"
			return endpointReadiness{State: readiness.State, Reason: readiness.Reason, Compatible: compatible}
		}
		policy, policyErr := s.store.Orchestration().GetRuntimePolicy(ctx, endpoint.Tenant, endpoint.Namespace, agent.ID.String())
		if policyErr != nil {
			return endpointReadiness{State: "unavailable", Reason: "Agent has no runtime policy"}
		}
		for _, candidate := range policy.Candidates {
			binding, bindingErr := s.store.AgentCatalog().GetBinding(ctx, candidate.Binding.BindingID)
			if bindingErr != nil || !binding.Enabled || binding.ArchivedAt != nil {
				continue
			}
			switch binding.Kind {
			case controlmodel.DataPlaneManaged:
				if s.product != nil && controlmodel.RuntimeSecurityMatches(binding.Kind, nil, candidate.SecurityConstraints) {
					return endpointReadiness{State: readiness.State, Reason: readiness.Reason, Compatible: true}
				}
			case controlmodel.DataPlaneExternalApplication:
				for _, instance := range instances {
					if externalConversationCandidate(instance, binding, candidate) {
						return endpointReadiness{State: readiness.State, Reason: readiness.Reason, Compatible: true}
					}
				}
			case controlmodel.DataPlaneHostedRuntime:
				if s.hostedConversationCandidate(ctx, agent, candidate) {
					return endpointReadiness{State: readiness.State, Reason: readiness.Reason, Compatible: true}
				}
			}
		}
		return endpointReadiness{State: "incompatible", Reason: "No runtime candidate supports conversation-inbound"}
	case controlmodel.EndpointTargetTeam:
		if endpoint.InvocationMode != controlmodel.EndpointJobMode {
			return endpointReadiness{State: "incompatible", Reason: "Team endpoints support job mode only"}
		}
		team, err := s.store.Collaboration().GetTeam(ctx, endpoint.TargetRef)
		if err != nil || team.Tenant != endpoint.Tenant || team.Namespace != endpoint.Namespace ||
			team.LeaderAgentRef == "" || team.Status != controlmodel.TeamActive || team.ArchivedAt != nil {
			return endpointReadiness{State: "incompatible", Reason: "Team target has no valid leader"}
		}
		leaderID, parseErr := uuid.Parse(team.LeaderAgentRef)
		if parseErr != nil {
			return endpointReadiness{State: "incompatible", Reason: "Team leader does not reference a stable agentId"}
		}
		leader, agentErr := s.activeAgentInScope(ctx, endpoint.Tenant, endpoint.Namespace, leaderID.String())
		if agentErr != nil {
			return endpointReadiness{State: "incompatible", Reason: "Team leader Agent is not active"}
		}
		bindings, _ := s.store.AgentCatalog().ListBindings(ctx, leader.ID, true)
		instances, _ := s.store.RuntimeRegistry().ListAgentInstances(ctx, endpoint.Tenant, endpoint.Namespace, leader.ID)
		readiness, _ := s.inspectAgentReadiness(ctx, leader, bindings, instances)
		compatible := readiness.State != "inactive" && readiness.State != "unbound"
		return endpointReadiness{State: readiness.State, Reason: "Team leader: " + readiness.Reason, Compatible: compatible}
	case controlmodel.EndpointTargetOrchestrationRevision:
		if endpoint.InvocationMode != controlmodel.EndpointJobMode {
			return endpointReadiness{State: "incompatible", Reason: "Workflow endpoints support job mode only"}
		}
		revision, err := s.store.Orchestration().GetRevision(ctx, endpoint.TargetRef)
		if err != nil || revision.Tenant != endpoint.Tenant || revision.Namespace != endpoint.Namespace {
			return endpointReadiness{State: "incompatible", Reason: "Workflow revision is unavailable"}
		}
		return endpointReadiness{State: "ready", Reason: "Published immutable revision is available", Compatible: true}
	default:
		return endpointReadiness{State: "incompatible", Reason: "Unsupported Endpoint target"}
	}
}

func (s *Server) getEndpointReadiness(c *gin.Context) {
	id, ok := parseUUIDParam(c, "endpointId")
	if !ok {
		return
	}
	endpoint, err := s.store.Endpoints().Get(c, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"readiness": s.inspectEndpointReadiness(c, endpoint)})
}

func (s *Server) publishEndpoint(c *gin.Context) {
	id, ok := parseUUIDParam(c, "endpointId")
	if !ok {
		return
	}
	endpoint, err := s.store.Endpoints().Get(c, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if endpoint.Status == controlmodel.EndpointArchived {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "archived Endpoint cannot be published"})
		return
	}
	if endpoint.Status == controlmodel.EndpointPublished {
		c.JSON(http.StatusOK, gin.H{"endpoint": endpointPublic(endpoint), "readiness": s.inspectEndpointReadiness(c, endpoint)})
		return
	}
	var request struct {
		Version int64 `json:"version"`
	}
	if err = c.ShouldBindJSON(&request); err != nil || request.Version == 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "version is required"})
		return
	}
	readiness := s.inspectEndpointReadiness(c, endpoint)
	if !readiness.Compatible {
		c.JSON(http.StatusConflict, gin.H{"error": "endpoint target is incompatible", "readiness": readiness})
		return
	}
	if endpoint.ActiveReleaseID == nil {
		contract, freezeErr := s.freezeServiceContract(c, endpoint)
		if freezeErr != nil {
			s.writeControlPlaneError(c, freezeErr)
			return
		}
		endpoint, _, err = s.store.Endpoints().DeployRelease(c, endpoint.ID, endpoint.TargetType,
			endpoint.TargetRef, request.Version, collaborationActor(c, s), "initial publication", contract)
		if err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
	}
	endpoint.Status = controlmodel.EndpointPublished
	endpoint, err = s.store.Endpoints().Update(c, endpoint, endpoint.Version)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"endpoint": endpointPublic(endpoint), "readiness": readiness})
}

func (s *Server) listEndpointReleases(c *gin.Context) {
	endpointID, ok := parseUUIDParam(c, "endpointId")
	if !ok {
		return
	}
	if _, err := s.store.Endpoints().Get(c, endpointID); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	items, err := s.store.Endpoints().ListReleases(c, endpointID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) deployEndpointRelease(c *gin.Context) {
	endpointID, ok := parseUUIDParam(c, "endpointId")
	if !ok {
		return
	}
	endpoint, err := s.store.Endpoints().Get(c, endpointID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if endpoint.Status == controlmodel.EndpointArchived {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "archived Endpoint cannot deploy releases"})
		return
	}
	if endpoint.Status == controlmodel.EndpointDraft || endpoint.ActiveReleaseID == nil {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "publish the Endpoint before deploying another release"})
		return
	}
	var request struct {
		TargetRef uuid.UUID `json:"targetRef"`
		Version   int64     `json:"version"`
		Reason    string    `json:"reason"`
	}
	if err = c.ShouldBindJSON(&request); err != nil || request.TargetRef == uuid.Nil || request.Version == 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "targetRef and version are required"})
		return
	}
	candidate := *endpoint
	candidate.TargetRef = request.TargetRef
	if err = s.validateEndpointTarget(c, &candidate); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	readiness := s.inspectEndpointReadiness(c, &candidate)
	if !readiness.Compatible {
		c.JSON(http.StatusConflict, gin.H{"error": "release target is incompatible", "readiness": readiness})
		return
	}
	contract, freezeErr := s.freezeServiceContract(c, &candidate)
	if freezeErr != nil {
		s.writeControlPlaneError(c, freezeErr)
		return
	}
	endpoint, release, err := s.store.Endpoints().DeployRelease(c, endpointID, endpoint.TargetType,
		request.TargetRef, request.Version, collaborationActor(c, s), strings.TrimSpace(request.Reason), contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"endpoint": endpointPublic(endpoint), "release": release, "readiness": readiness})
}

func (s *Server) rollbackEndpointRelease(c *gin.Context) {
	endpointID, ok := parseUUIDParam(c, "endpointId")
	if !ok {
		return
	}
	releaseID, ok := parseUUIDParam(c, "releaseId")
	if !ok {
		return
	}
	endpoint, err := s.store.Endpoints().Get(c, endpointID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if endpoint.Status == controlmodel.EndpointArchived || endpoint.ActiveReleaseID == nil {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "Endpoint has no active release to roll back"})
		return
	}
	previous, err := s.store.Endpoints().GetRelease(c, endpointID, releaseID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	var request struct {
		Version int64 `json:"version"`
	}
	if err = c.ShouldBindJSON(&request); err != nil || request.Version == 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "version is required"})
		return
	}
	if previous.TargetType != endpoint.TargetType {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "release target type no longer matches Endpoint"})
		return
	}
	candidate := *endpoint
	candidate.TargetRef = previous.TargetRef
	if err = s.validateEndpointTarget(c, &candidate); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	readiness := s.inspectEndpointReadiness(c, &candidate)
	if !readiness.Compatible {
		c.JSON(http.StatusConflict, gin.H{"error": "rollback target is incompatible", "readiness": readiness})
		return
	}
	endpoint, release, err := s.store.Endpoints().DeployRelease(c, endpointID, endpoint.TargetType,
		previous.TargetRef, request.Version, collaborationActor(c, s), fmt.Sprintf("rollback to release %d", previous.Number), previous.Contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"endpoint": endpointPublic(endpoint), "release": release, "readiness": readiness})
}

func (s *Server) disableEndpoint(c *gin.Context) {
	id, ok := parseUUIDParam(c, "endpointId")
	if !ok {
		return
	}
	endpoint, err := s.store.Endpoints().Get(c, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if endpoint.Status != controlmodel.EndpointPublished {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "only a published Endpoint can be disabled"})
		return
	}
	var request struct {
		Version int64 `json:"version"`
	}
	if err = c.ShouldBindJSON(&request); err != nil || request.Version == 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "version is required"})
		return
	}
	endpoint.Status = controlmodel.EndpointDisabled
	endpoint, err = s.store.Endpoints().Update(c, endpoint, request.Version)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"endpoint": endpointPublic(endpoint)})
}

func (s *Server) archiveEndpoint(c *gin.Context) {
	id, ok := parseUUIDParam(c, "endpointId")
	if !ok {
		return
	}
	endpoint, err := s.store.Endpoints().Get(c, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	var request struct {
		Version int64 `json:"version"`
	}
	if err = c.ShouldBindJSON(&request); err != nil || request.Version == 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "version is required"})
		return
	}
	now := time.Now().UTC()
	endpoint.Status, endpoint.ArchivedAt = controlmodel.EndpointArchived, &now
	endpoint, err = s.store.Endpoints().Update(c, endpoint, request.Version)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"endpoint": endpointPublic(endpoint)})
}

func (s *Server) listEndpointInvocations(c *gin.Context) {
	endpointID, ok := parseUUIDParam(c, "endpointId")
	if !ok {
		return
	}
	if _, err := s.store.Endpoints().Get(c, endpointID); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "50"))
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	filter := store.EndpointInvocationFilter{EndpointID: endpointID, Limit: limit}
	if mode := controlmodel.EndpointInvocationMode(c.Query("mode")); mode == controlmodel.EndpointJobMode || mode == controlmodel.EndpointConversationMode {
		filter.Mode = mode
	}
	if status := controlmodel.EndpointInvocationStatus(c.Query("status")); status != "" {
		filter.Status = status
	}
	items, err := s.store.Endpoints().ListInvocations(c, filter)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) listEndpointCredentials(c *gin.Context) {
	id, ok := parseUUIDParam(c, "endpointId")
	if !ok {
		return
	}
	credentials, err := s.store.Endpoints().ListCredentials(c, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": credentials})
}

func (s *Server) createEndpointCredential(c *gin.Context) {
	id, ok := parseUUIDParam(c, "endpointId")
	if !ok {
		return
	}
	endpoint, err := s.store.Endpoints().Get(c, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if endpoint.Status == controlmodel.EndpointArchived {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "archived Endpoint cannot create credentials"})
		return
	}
	var request struct {
		ApplicationID uuid.UUID       `json:"applicationId"`
		Name          string          `json:"name"`
		Scopes        json.RawMessage `json:"scopes"`
		ExpiresAt     *time.Time      `json:"expiresAt"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if strings.TrimSpace(request.Name) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "name is required"})
		return
	}
	app, err := s.store.Applications().Get(c, request.ApplicationID)
	if err != nil || app.Tenant != endpoint.Tenant || app.Namespace != endpoint.Namespace || app.Status != "active" {
		c.JSON(400, ErrorResponse{Error: "applicationId must identify an active Application in the Endpoint namespace"})
		return
	}
	if !s.applicationManager(c, app) {
		return
	}
	if _, err = validateEndpointScopes(request.Scopes); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	if request.ExpiresAt != nil && !request.ExpiresAt.After(time.Now()) {
		c.JSON(400, ErrorResponse{Error: "expiresAt must be in the future"})
		return
	}
	candidate, key, err := s.buildEndpointCredential(id, strings.TrimSpace(request.Name),
		request.Scopes, request.ExpiresAt, nil)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: "generate endpoint credential"})
		return
	}
	candidate.ApplicationID = app.ID
	credential, err := s.store.Endpoints().CreateCredential(c, candidate)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"credential": credential, "secret": key})
}

func (s *Server) rotateEndpointCredential(c *gin.Context) {
	endpointID, ok := parseUUIDParam(c, "endpointId")
	if !ok {
		return
	}
	endpoint, err := s.store.Endpoints().Get(c, endpointID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if endpoint.Status == controlmodel.EndpointArchived {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "archived Endpoint cannot rotate credentials"})
		return
	}
	credentialID, ok := parseUUIDParam(c, "credentialId")
	if !ok {
		return
	}
	credentials, err := s.store.Endpoints().ListCredentials(c, endpointID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	var previous *controlmodel.EndpointCredential
	for _, credential := range credentials {
		if credential.ID == credentialID && credential.Status == controlmodel.EndpointCredentialActive {
			previous = credential
			break
		}
	}
	if previous == nil {
		s.writeControlPlaneError(c, store.ErrNotFound)
		return
	}
	app, err := s.store.Applications().Get(c, previous.ApplicationID)
	if err != nil || app.Status != "active" {
		c.JSON(400, ErrorResponse{Error: "credential has no active Application"})
		return
	}
	if !s.applicationManager(c, app) {
		return
	}
	root := previous.ID
	if previous.RotatedFrom != nil {
		root = *previous.RotatedFrom
	}
	candidate, key, err := s.buildEndpointCredential(endpointID, previous.Name,
		previous.Scopes, previous.ExpiresAt, &root)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: "generate endpoint credential"})
		return
	}
	candidate.ApplicationID = previous.ApplicationID
	candidate.Name = fmt.Sprintf("%s-%s", previous.Name, candidate.KeyPrefix[:6])
	credential, err := s.store.Endpoints().CreateCredential(c, candidate)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"credential": credential, "secret": key})
}

func (s *Server) revealEndpointCredential(c *gin.Context) {
	endpointID, ok := parseUUIDParam(c, "endpointId")
	if !ok {
		return
	}
	credentialID, ok := parseUUIDParam(c, "credentialId")
	if !ok {
		return
	}
	if _, err := s.store.Endpoints().Get(c, endpointID); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	credentials, err := s.store.Endpoints().ListCredentials(c, endpointID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	var credential *controlmodel.EndpointCredential
	for _, item := range credentials {
		if item.ID == credentialID {
			credential = item
			break
		}
	}
	if credential == nil {
		s.writeControlPlaneError(c, store.ErrNotFound)
		return
	}
	app, err := s.store.Applications().Get(c, credential.ApplicationID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !s.applicationManager(c, app) {
		return
	}
	if credential.Status != controlmodel.EndpointCredentialActive {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "only active credentials can be revealed"})
		return
	}
	if len(credential.SecretCiphertext) == 0 {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "credential secret is unavailable; create a new credential"})
		return
	}
	plaintext, err := secretcrypto.Decrypt(s.endpointCredentialKey, credential.SecretCiphertext,
		endpointCredentialAAD(endpointID, credentialID))
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: "credential cannot be decrypted with the configured key"})
		return
	}
	c.Header("Cache-Control", "no-store")
	c.Header("Pragma", "no-cache")
	c.JSON(http.StatusOK, gin.H{"credentialId": credential.ID, "secret": string(plaintext)})
}

func (s *Server) revokeEndpointCredential(c *gin.Context) {
	endpointID, ok := parseUUIDParam(c, "endpointId")
	if !ok {
		return
	}
	credentialID, ok := parseUUIDParam(c, "credentialId")
	if !ok {
		return
	}
	credentials, err := s.store.Endpoints().ListCredentials(c, endpointID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	for _, credential := range credentials {
		if credential.ID != credentialID {
			continue
		}
		app, loadErr := s.store.Applications().Get(c, credential.ApplicationID)
		if loadErr != nil {
			s.writeControlPlaneError(c, loadErr)
			return
		}
		if !s.applicationManager(c, app) {
			return
		}
		now := time.Now().UTC()
		credential.Status, credential.RevokedAt = controlmodel.EndpointCredentialRevoked, &now
		credential, err = s.store.Endpoints().UpdateCredential(c, credential)
		if err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
		c.JSON(http.StatusOK, gin.H{"credential": credential})
		return
	}
	s.writeControlPlaneError(c, store.ErrNotFound)
}

func (s *Server) authenticateEndpoint(c *gin.Context, endpoint *controlmodel.Endpoint, requirePublished bool) bool {
	if endpoint == nil || endpoint.Status == controlmodel.EndpointArchived ||
		(requirePublished && endpoint.Status != controlmodel.EndpointPublished) {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "endpoint is unavailable"})
		return false
	}
	var policy endpointAuthPolicy
	if json.Unmarshal(endpoint.AuthPolicy, &policy) != nil {
		c.JSON(http.StatusUnauthorized, ErrorResponse{Error: "endpoint authentication policy is invalid"})
		return false
	}
	if policy.Type == "platform" {
		principal, ok := s.platformPrincipal(c.Request.Context(), requestBearerToken(c))
		if !ok {
			c.JSON(http.StatusUnauthorized, ErrorResponse{Error: "invalid platform credential"})
			return false
		}
		c.Set(endpointPrincipalContextKey, principal)
		return true
	}
	if policy.Type != "api_key" {
		c.JSON(401, ErrorResponse{Error: "unsupported endpoint authentication policy"})
		return false
	}
	key := c.GetHeader("X-API-Key")
	if key == "" {
		key = bearerToken(c)
	}
	prefix := endpointKeyPrefix(key)
	credential, err := s.store.Endpoints().GetCredentialByPrefix(c, endpoint.ID, prefix)
	if err != nil || credential.Status != controlmodel.EndpointCredentialActive ||
		(credential.ExpiresAt != nil && !credential.ExpiresAt.After(time.Now().UTC())) {
		c.JSON(http.StatusUnauthorized, ErrorResponse{Error: "invalid endpoint credential"})
		return false
	}
	sum := sha256.Sum256([]byte(key))
	if len(credential.SecretHash) != len(sum) || subtle.ConstantTimeCompare(credential.SecretHash, sum[:]) != 1 {
		c.JSON(http.StatusUnauthorized, ErrorResponse{Error: "invalid endpoint credential"})
		return false
	}
	scopes, scopeErr := validateEndpointScopes(credential.Scopes)
	app, appErr := s.store.Applications().Get(c, credential.ApplicationID)
	if scopeErr != nil || appErr != nil || app.Status != "active" || app.Tenant != endpoint.Tenant || app.Namespace != endpoint.Namespace {
		c.JSON(401, ErrorResponse{Error: "credential requires an active Application and explicit scopes"})
		return false
	}
	_ = s.store.Endpoints().TouchCredential(c, credential.ID, time.Now().UTC())
	c.Set(endpointScopesContextKey, scopes)
	c.Set(endpointApplicationContextKey, app)
	c.Set(endpointCredentialContextKey, credential.ID)
	c.Set(endpointActorContextKey, controlmodel.Actor{Type: controlmodel.ActorAutomation, Ref: "api-key:" + credential.ID.String()})
	c.Set(endpointPrincipalContextKey, "application:"+app.ID.String())
	return true
}

func (s *Server) allowEndpointRequest(c *gin.Context, endpoint *controlmodel.Endpoint) bool {
	if !endpointScope(c, "invoke") {
		return false
	}
	var limit endpointRateLimit
	if len(endpoint.RateLimit) == 0 || json.Unmarshal(endpoint.RateLimit, &limit) != nil || limit.Requests <= 0 {
		return true
	}
	if limit.WindowSeconds <= 0 {
		limit.WindowSeconds = 60
	}
	now := time.Now().UTC()
	principal, _ := c.Get(endpointPrincipalContextKey)
	allowed, retryAfter, err := s.store.Endpoints().ConsumeRateLimit(c, endpoint.ID, fmt.Sprint(principal),
		limit.Requests, limit.WindowSeconds, now)
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "Endpoint rate limiter is unavailable"})
		return false
	}
	if allowed {
		return true
	}
	c.Header("Retry-After", fmt.Sprint(max(1, int(retryAfter.Seconds()))))
	c.JSON(http.StatusTooManyRequests, ErrorResponse{Error: "endpoint rate limit exceeded"})
	return false
}

func (s *Server) invokeEndpointConversation(c *gin.Context) {
	endpoint, err := s.store.Endpoints().GetBySlug(c, c.Param("slug"))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !s.authenticateEndpoint(c, endpoint, true) {
		return
	}
	if !s.allowEndpointRequest(c, endpoint) {
		return
	}
	if endpoint.InvocationMode != controlmodel.EndpointConversationMode || endpoint.TargetType != controlmodel.EndpointTargetAgent {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "endpoint does not accept conversations"})
		return
	}
	s.invokeEndpointConversationTurn(c, endpoint, uuid.Nil)
}

func (s *Server) continueEndpointConversation(c *gin.Context) {
	conversationID, err := uuid.Parse(c.Param("conversationId"))
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid conversationId"})
		return
	}
	conversation, err := s.store.Endpoints().GetConversation(c, conversationID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	endpoint, err := s.store.Endpoints().Get(c, conversation.EndpointID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !s.authenticateEndpoint(c, endpoint, true) || !s.allowEndpointRequest(c, endpoint) {
		return
	}
	s.invokeEndpointConversationTurn(c, endpoint, conversationID)
}

func (s *Server) invokeEndpointConversationTurn(c *gin.Context, endpoint *controlmodel.Endpoint, conversationID uuid.UUID) {
	bound, contract, boundErr := s.endpointSubmissionContract(c, endpoint, &conversationID)
	if boundErr != nil {
		s.writeControlPlaneError(c, boundErr)
		return
	}
	endpoint = bound
	var req struct {
		Message string `json:"message"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if strings.TrimSpace(req.Message) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "message is required"})
		return
	}
	if endpoint.MaxPayloadBytes > 0 && int64(len(req.Message)) > endpoint.MaxPayloadBytes {
		c.JSON(http.StatusRequestEntityTooLarge, ErrorResponse{Error: "Endpoint payload is too large"})
		return
	}
	idem := strings.TrimSpace(c.GetHeader("Idempotency-Key"))
	if idem == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "Idempotency-Key is required"})
		return
	}
	principalValue, _ := c.Get(endpointPrincipalContextKey)
	principal := fmt.Sprint(principalValue)
	payload, _ := json.Marshal(gin.H{"message": req.Message})

	var conversationRef *uuid.UUID
	if conversationID != uuid.Nil {
		if _, _, err := s.loadEndpointConversationSession(c, endpoint, conversationID, principal); err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
		conversationRef = &conversationID
	}
	inv, fresh, err := s.reserveEndpointInvocation(c, endpoint, &controlmodel.EndpointInvocation{ReleaseID: endpoint.ActiveReleaseID, Contract: contract, EndpointID: endpoint.ID, Mode: controlmodel.EndpointConversationMode, PrincipalType: "credential", PrincipalRef: principal, IdempotencyKey: idem, Status: controlmodel.EndpointInvocationAccepted, ConversationID: conversationRef, Input: payload, CorrelationID: requestCorrelationID(c)})
	if err != nil {
		s.writeServiceAdmissionError(c, err)
		return
	}
	if !sameJSON(inv.Input, payload) || (conversationID != uuid.Nil && (inv.ConversationID == nil || *inv.ConversationID != conversationID)) {
		c.JSON(409, ErrorResponse{Error: "Idempotency-Key was already used with different input or conversation"})
		return
	}
	if fresh || inv.Status == controlmodel.EndpointInvocationAccepted || inv.Status == controlmodel.EndpointInvocationDispatching {
		_ = s.dispatchServiceConversation(c, inv.ID)
	}
	if latest, err := s.store.Endpoints().GetInvocation(c, inv.ID); err == nil {
		inv = latest
	}
	var sessionRef uuid.UUID
	if sessions, e := s.store.Sessions().List(c, store.SessionFilter{Tenant: endpoint.Tenant, Namespace: endpoint.Namespace, SessionID: inv.SessionID, Limit: 1}); e == nil && len(sessions) == 1 {
		sessionRef = sessions[0].ID
	}
	c.JSON(http.StatusAccepted, gin.H{"invocationId": inv.ID, "conversationId": inv.ConversationID, "turnId": inv.TurnID, "status": inv.Status, "sessionId": inv.SessionID, "sessionRef": sessionRef,
		"eventsUrl": "/invoke/v1/invocations/" + inv.ID.String() + "/events/stream", "snapshotUrl": "/invoke/v1/invocations/" + inv.ID.String() + "/snapshot", "statusUrl": "/invoke/v1/invocations/" + inv.ID.String()})
}

func (s *Server) loadEndpointConversationSession(ctx context.Context, endpoint *controlmodel.Endpoint,
	conversationID uuid.UUID, principal string) (*controlmodel.EndpointConversation, *store.Session, error) {
	conversation, err := s.store.Endpoints().GetConversation(ctx, conversationID)
	if err != nil || conversation.EndpointID != endpoint.ID || conversation.Status != controlmodel.EndpointConversationActive ||
		conversation.PrincipalRef != principal {
		return nil, nil, store.ErrNotFound
	}
	sessions, err := s.store.Sessions().List(ctx, store.SessionFilter{Tenant: endpoint.Tenant,
		Namespace: endpoint.Namespace, AgentID: conversation.AgentID, SessionID: conversation.SessionID, Limit: 1})
	if err != nil || len(sessions) == 0 {
		return nil, nil, store.ErrNotFound
	}
	return conversation, sessions[0], nil
}

func (s *Server) resolveEndpointConversation(ctx context.Context, endpoint *controlmodel.Endpoint, requestedSessionID string) (*store.Session, error) {
	agent, err := s.store.AgentCatalog().GetAgent(ctx, endpoint.TargetRef)
	if err != nil || agent.Status != controlmodel.AgentActive {
		return nil, fmt.Errorf("endpoint Agent is unavailable")
	}
	return s.resolveAgentConversation(ctx, agent, requestedSessionID, "endpoint", endpoint.ID.String())
}

func (s *Server) sendEndpointConversationTurn(ctx context.Context, endpoint *controlmodel.Endpoint,
	conversation *controlmodel.EndpointConversation, invocation *controlmodel.EndpointInvocation, message string) error {
	binding, err := s.store.AgentCatalog().GetBinding(ctx, conversation.BindingID)
	if err != nil || !binding.Enabled || binding.ArchivedAt != nil || binding.AgentID != conversation.AgentID {
		return fmt.Errorf("conversation Binding is unavailable")
	}
	switch binding.Kind {
	case controlmodel.DataPlaneManaged:
		if s.product == nil {
			return fmt.Errorf("Managed runtime is unavailable")
		}
		var cfg controlmodel.ManagedBindingConfiguration
		if json.Unmarshal(binding.Configuration, &cfg) != nil {
			return fmt.Errorf("Managed binding configuration is invalid")
		}
		if invocation.TurnID == nil {
			return fmt.Errorf("conversation turnId is missing")
		}

		return s.acceptNativeServiceTurn(ctx, endpoint, invocation, cfg.OwnerRef, message)
	case controlmodel.DataPlaneExternalApplication:
		if conversation.AgentInstanceID == uuid.Nil {
			return fmt.Errorf("conversation AgentInstance is unavailable")
		}
		instance, instanceErr := s.store.RuntimeRegistry().GetAgentInstance(ctx, conversation.AgentInstanceID)
		if instanceErr != nil || instance.BindingID != binding.ID || instance.Generation != conversation.InstanceGeneration ||
			instance.Health != controlmodel.RuntimeHealthHealthy {
			return fmt.Errorf("conversation AgentInstance is unavailable")
		}
		sender, ok := s.asdpCommands.(ConversationTurnSender)
		if !ok {
			return fmt.Errorf("External conversation transport is unavailable")
		}
		input, _ := json.Marshal(gin.H{"message": message})
		deadline := time.Now().Add(time.Duration(endpoint.TimeoutSeconds) * time.Second).UnixMilli()
		return sender.SendConversationTurn(endpoint.Tenant, endpoint.Namespace, instance.InstanceKey, &asdp.ConversationTurnCommand{
			InvocationId: invocation.ID.String(), ConversationId: conversation.ID.String(), TurnId: invocation.TurnID.String(),
			SessionId: conversation.SessionID, AgentId: conversation.AgentID.String(), BindingId: conversation.BindingID.String(),
			InstanceId: instance.ID.String(), Generation: conversation.InstanceGeneration, Input: input,
			Deadline: deadline, CorrelationId: invocation.CorrelationID,
		})
	case controlmodel.DataPlaneHostedRuntime:
		if invocation.TurnID == nil {
			return fmt.Errorf("conversation turnId is missing")
		}
		sessions, listErr := s.store.Sessions().List(ctx, store.SessionFilter{Tenant: endpoint.Tenant,
			Namespace: endpoint.Namespace, AgentID: conversation.AgentID,
			SessionID: conversation.SessionID, Limit: 1})
		if listErr != nil || len(sessions) == 0 {
			return fmt.Errorf("conversation Session is unavailable")
		}
		result, dispatchErr := s.dispatchHostedConversationTurn(ctx, sessions[0], binding, message,
			invocation.TurnID.String(), "endpoint_conversation", invocation.ID.String())
		if dispatchErr != nil {
			return dispatchErr
		}
		now := time.Now().UTC()
		invocation.Status, invocation.StartedAt = controlmodel.EndpointInvocationRunning, &now
		invocation.IssueID, invocation.RunID = &result.IssueID, &result.RunID
		conversation.LastTurnAt = &now
		_, _ = s.store.Endpoints().UpdateConversation(ctx, conversation)
		_, dispatchErr = s.store.Endpoints().UpdateInvocation(ctx, invocation)
		return dispatchErr
	default:
		return fmt.Errorf("runtime binding %q does not support conversations", binding.Kind)
	}
}

func (s *Server) invokeEndpointJob(c *gin.Context) {
	endpoint, err := s.store.Endpoints().GetBySlug(c, c.Param("slug"))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !s.authenticateEndpoint(c, endpoint, true) {
		return
	}
	if !s.allowEndpointRequest(c, endpoint) {
		return
	}
	if endpoint.InvocationMode != controlmodel.EndpointJobMode {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "endpoint does not accept jobs"})
		return
	}
	bound, contract, boundErr := s.endpointSubmissionContract(c, endpoint, nil)
	if boundErr != nil {
		s.writeControlPlaneError(c, boundErr)
		return
	}
	endpoint = bound
	idem := strings.TrimSpace(c.GetHeader("Idempotency-Key"))
	if idem == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "Idempotency-Key is required"})
		return
	}
	var req struct {
		Title       string          `json:"title"`
		Description string          `json:"description"`
		Input       json.RawMessage `json:"input"`
	}
	if err = c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if endpoint.MaxPayloadBytes > 0 && int64(len(req.Title)+len(req.Description)+len(req.Input)) > endpoint.MaxPayloadBytes {
		c.JSON(http.StatusRequestEntityTooLarge, ErrorResponse{Error: "Endpoint payload is too large"})
		return
	}
	if req.Title == "" {
		req.Title = "Endpoint job"
	}
	input, _ := json.Marshal(gin.H{"title": req.Title, "description": req.Description, "input": req.Input})
	principal, _ := c.Get(endpointPrincipalContextKey)
	invocation, fresh, err := s.reserveEndpointInvocation(c, endpoint, &controlmodel.EndpointInvocation{
		ReleaseID: endpoint.ActiveReleaseID, Contract: contract, EndpointID: endpoint.ID, Mode: controlmodel.EndpointJobMode, PrincipalType: "credential",
		PrincipalRef: fmt.Sprint(principal), IdempotencyKey: idem, Status: controlmodel.EndpointInvocationAccepted,
		Input: input, CorrelationID: requestCorrelationID(c),
	})
	if err != nil {
		s.writeServiceAdmissionError(c, err)
		return
	}
	if !fresh {
		if !sameJSON(invocation.Input, input) {
			c.JSON(http.StatusConflict, ErrorResponse{Error: "Idempotency-Key was already used with different input"})
			return
		}
		s.endpointJobAccepted(c, invocation)
		return
	}
	// Materialization is retryable from the durable invocation, including after a process crash.
	_ = s.dispatchEndpointInvocation(c.Request.Context(), invocation.ID)
	if latest, loadErr := s.store.Endpoints().GetInvocation(c, invocation.ID); loadErr == nil {
		invocation = latest
	}
	s.endpointJobAccepted(c, invocation)
}

func (s *Server) materializeEndpointTarget(ctx context.Context, endpoint *controlmodel.Endpoint, run *controlmodel.OrchestrationRun, issueID uuid.UUID, actor controlmodel.Actor) error {
	nodeType := controlmodel.RunNodeAgent
	agentID := endpoint.TargetRef
	if endpoint.TargetType == controlmodel.EndpointTargetTeam {
		team, err := s.store.Collaboration().GetTeam(ctx, endpoint.TargetRef)
		if err != nil {
			return err
		}
		_, _, err = orchestration.MaterializeTeamCoordinator(ctx, s.store, orchestration.MaterializeTeamRequest{
			Run: run, IssueID: issueID, Team: team, NodeID: uuid.NewSHA1(run.ID, []byte("target")),
			NodeKey: "target", Actor: actor,
		})
		if err != nil {
			return err
		}
		return (&orchestration.Engine{Store: s.store}).ReconcileRun(ctx, run.ID)
	}
	nodeID := uuid.NewSHA1(run.ID, []byte("target"))
	node, err := s.store.Orchestration().CreateNode(ctx, &controlmodel.RunNode{ID: nodeID, RunID: run.ID,
		Tenant: run.Tenant, Namespace: run.Namespace, NodeKey: "target", Type: nodeType,
		IssueID: &issueID, State: controlmodel.RunNodeReady, Iteration: 1})
	if err == store.ErrConflict {
		node, err = s.store.Orchestration().GetNode(ctx, nodeID)
	}
	if err != nil {
		return err
	}
	existing, err := s.store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{RunID: run.ID, NodeID: node.ID, AgentRef: agentID.String(), Limit: 1})
	if err != nil || len(existing) > 0 {
		return err
	}
	_, err = s.store.Collaboration().CreateRunAgentTask(ctx, store.RunTaskRequest{RunID: run.ID,
		NodeID: node.ID, IssueID: issueID, AgentRef: agentID.String(), Originator: actor})
	if err != nil {
		return err
	}
	return (&orchestration.Engine{Store: s.store}).ReconcileRun(ctx, run.ID)
}

func (s *Server) getEndpointConversation(c *gin.Context) {
	conversationID, err := uuid.Parse(c.Param("conversationId"))
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid conversationId"})
		return
	}
	conversation, err := s.store.Endpoints().GetConversation(c, conversationID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	endpoint, err := s.store.Endpoints().Get(c, conversation.EndpointID)
	if err != nil || !s.authenticateEndpoint(c, endpoint, false) {
		return
	}
	principal, _ := c.Get(endpointPrincipalContextKey)
	if conversation.PrincipalRef != fmt.Sprint(principal) {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "conversation is unavailable"})
		return
	}
	invocations, _ := s.store.Endpoints().ListInvocations(c, store.EndpointInvocationFilter{
		EndpointID: endpoint.ID, Mode: controlmodel.EndpointConversationMode, Limit: 100,
	})
	turns := make([]*controlmodel.EndpointInvocation, 0)
	for _, invocation := range invocations {
		if invocation.ConversationID != nil && *invocation.ConversationID == conversation.ID {
			turns = append(turns, s.expireEndpointInvocation(c, endpoint, invocation))
		}
	}
	publicTurns := make([]gin.H, 0, len(turns))
	for _, turn := range turns {
		publicTurns = append(publicTurns, endpointInvocationPublic(turn))
	}
	c.JSON(http.StatusOK, gin.H{"conversation": endpointConversationPublic(conversation), "turns": publicTurns})
}
func (s *Server) failEndpointInvocation(ctx context.Context, invocation *controlmodel.EndpointInvocation, code string, cause error) {
	if invocation == nil {
		return
	}
	now := time.Now().UTC()
	invocation.Status, invocation.ErrorCode = controlmodel.EndpointInvocationFailed, code
	invocation.ErrorMessage, invocation.CompletedAt = cause.Error(), &now
	_, _ = s.store.Endpoints().UpdateInvocation(ctx, invocation)
}

func (s *Server) expireEndpointInvocation(ctx context.Context, endpoint *controlmodel.Endpoint,
	invocation *controlmodel.EndpointInvocation) *controlmodel.EndpointInvocation {
	current, err := s.refreshServiceInvocation(ctx, invocation.ID)
	if err == nil {
		return current
	}
	return invocation
}

func (s *Server) endpointJobAccepted(c *gin.Context, invocation *controlmodel.EndpointInvocation) {
	response := gin.H{
		"invocationId": invocation.ID, "status": invocation.Status,
		"statusUrl":   "/invoke/v1/invocations/" + invocation.ID.String(),
		"snapshotUrl": "/invoke/v1/invocations/" + invocation.ID.String() + "/snapshot",
		"eventsUrl":   "/invoke/v1/invocations/" + invocation.ID.String() + "/events/stream",
	}
	if invocation.IssueID != nil {
		response["issueId"] = invocation.IssueID
	}
	if invocation.RunID != nil {
		response["runId"] = invocation.RunID
	}
	c.JSON(http.StatusAccepted, response)
}

func (s *Server) downloadEndpointJobArtifact(c *gin.Context) {
	invocation, endpoint, ok := s.loadServiceInvocation(c, "read")
	if !ok {
		return
	}
	artifactID, parseErr := uuid.Parse(c.Param("artifactId"))
	if parseErr != nil || invocation.IssueID == nil {
		s.writeControlPlaneError(c, store.ErrNotFound)
		return
	}
	item, links, err := s.store.Collaboration().GetArtifact(c, artifactID)
	if err != nil || item.Tenant != endpoint.Tenant || item.Namespace != endpoint.Namespace {
		s.writeControlPlaneError(c, store.ErrNotFound)
		return
	}
	targets, targetErr := s.serviceArtifactTargets(c, invocation)
	if targetErr != nil {
		s.writeControlPlaneError(c, targetErr)
		return
	}
	linked := false
	for _, link := range links {
		for _, ref := range targets[link.TargetType] {
			if ref == link.TargetRef {
				linked = true
			}
		}
	}
	if !linked {
		s.writeControlPlaneError(c, store.ErrNotFound)
		return
	}
	if item.ExpiresAt != nil && item.ExpiresAt.Before(time.Now().UTC()) {
		c.JSON(http.StatusGone, ErrorResponse{Error: "artifact has expired"})
		return
	}
	if s.artifactProvider == nil || item.StorageProvider != s.artifactProvider.Name() {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "artifact provider is unavailable"})
		return
	}
	reader, info, err := s.artifactProvider.Open(c, item.StorageKey)
	if err != nil {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "artifact is unavailable"})
		return
	}
	defer reader.Close()
	if info.Checksum != item.Checksum {
		c.JSON(http.StatusUnprocessableEntity, ErrorResponse{Error: "artifact checksum mismatch"})
		return
	}
	c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=%q", item.Filename))
	c.DataFromReader(http.StatusOK, info.Size, item.ContentType, reader, nil)
}

func prepareEventStream(c *gin.Context) {
	c.Header("Content-Type", "text/event-stream")
	c.Header("Cache-Control", "no-cache, no-transform")
	c.Header("Connection", "keep-alive")
	c.Header("X-Accel-Buffering", "no")
}

func endpointInvocationTerminal(status controlmodel.EndpointInvocationStatus) bool {
	return status == controlmodel.EndpointInvocationCompleted || status == controlmodel.EndpointInvocationPartialSucceeded || status == controlmodel.EndpointInvocationFailed ||
		status == controlmodel.EndpointInvocationCancelled || status == controlmodel.EndpointInvocationTimedOut
}

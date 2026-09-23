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

	"github.com/spring-ai-alibaba/aistio/internal/asdp"
	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/orchestration"
	"github.com/spring-ai-alibaba/aistio/internal/secretcrypto"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type endpointRateLimit struct {
	Requests      int `json:"requests"`
	WindowSeconds int `json:"windowSeconds"`
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
		"correlationId": v.CorrelationID, "createdAt": v.CreatedAt, "updatedAt": v.UpdatedAt}
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

func validateEndpointSchema(raw json.RawMessage) error {
	if len(raw) == 0 || string(raw) == "null" {
		return nil
	}
	var schema map[string]any
	if err := json.Unmarshal(raw, &schema); err != nil {
		return fmt.Errorf("schema must be a JSON object: %w", err)
	}
	return nil
}

// validateEndpointInput intentionally implements the stable, useful subset of
// JSON Schema needed at the public boundary. Unknown keywords remain forward
// compatible instead of being interpreted incorrectly.
func validateEndpointInput(schemaRaw json.RawMessage, value any) error {
	if len(schemaRaw) == 0 || string(schemaRaw) == "null" {
		return nil
	}
	var schema map[string]any
	if err := json.Unmarshal(schemaRaw, &schema); err != nil {
		return fmt.Errorf("Endpoint input schema is invalid")
	}
	return validateEndpointSchemaValue(schema, value, "input")
}

func validateEndpointSchemaValue(schema map[string]any, value any, path string) error {
	typeName, _ := schema["type"].(string)
	switch typeName {
	case "object":
		object, ok := value.(map[string]any)
		if !ok {
			return fmt.Errorf("%s must be an object", path)
		}
		if required, ok := schema["required"].([]any); ok {
			for _, item := range required {
				name, _ := item.(string)
				if name != "" {
					if _, exists := object[name]; !exists {
						return fmt.Errorf("%s.%s is required", path, name)
					}
				}
			}
		}
		if properties, ok := schema["properties"].(map[string]any); ok {
			for name, childRaw := range properties {
				child, schemaOK := childRaw.(map[string]any)
				childValue, exists := object[name]
				if schemaOK && exists {
					if err := validateEndpointSchemaValue(child, childValue, path+"."+name); err != nil {
						return err
					}
				}
			}
		}
	case "array":
		items, ok := value.([]any)
		if !ok {
			return fmt.Errorf("%s must be an array", path)
		}
		if child, ok := schema["items"].(map[string]any); ok {
			for i, item := range items {
				if err := validateEndpointSchemaValue(child, item, fmt.Sprintf("%s[%d]", path, i)); err != nil {
					return err
				}
			}
		}
	case "string":
		if _, ok := value.(string); !ok {
			return fmt.Errorf("%s must be a string", path)
		}
	case "number":
		if _, ok := value.(float64); !ok {
			return fmt.Errorf("%s must be a number", path)
		}
	case "integer":
		number, ok := value.(float64)
		if !ok || number != float64(int64(number)) {
			return fmt.Errorf("%s must be an integer", path)
		}
	case "boolean":
		if _, ok := value.(bool); !ok {
			return fmt.Errorf("%s must be a boolean", path)
		}
	case "null":
		if value != nil {
			return fmt.Errorf("%s must be null", path)
		}
	}
	return nil
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
	if err := validateEndpointSchema(in.OutputSchema); err != nil {
		return fmt.Errorf("outputSchema: %w", err)
	}
	if in.TimeoutSeconds < 0 || in.MaxPayloadBytes < 0 {
		return fmt.Errorf("timeoutSeconds and maxPayloadBytes cannot be negative")
	}
	if len(in.RateLimit) > 0 {
		var rate endpointRateLimit
		if err := json.Unmarshal(in.RateLimit, &rate); err != nil || rate.Requests <= 0 || rate.WindowSeconds <= 0 {
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
	if authPolicy.Type == "api_key" {
		candidate, key, keyErr := s.buildEndpointCredential(v.ID, "default", nil, nil, nil)
		if keyErr != nil {
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: "generate endpoint credential"})
			return
		}
		credential, createErr := s.store.Endpoints().CreateCredential(c, candidate)
		if createErr != nil {
			s.writeControlPlaneError(c, createErr)
			return
		}
		response["credential"] = key
		response["credentialResource"] = credential
	}
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
	if v.Status == controlmodel.EndpointPublished && (len(in.InputSchema) > 0 || len(in.OutputSchema) > 0) {
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
	if len(in.RateLimit) > 0 {
		if string(in.RateLimit) == "null" {
			v.RateLimit = nil
		} else {
			var rate endpointRateLimit
			if json.Unmarshal(in.RateLimit, &rate) != nil || rate.Requests <= 0 || rate.WindowSeconds <= 0 {
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
		endpoint, _, err = s.store.Endpoints().DeployRelease(c, endpoint.ID, endpoint.TargetType,
			endpoint.TargetRef, request.Version, collaborationActor(c, s), "initial publication")
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
	endpoint, release, err := s.store.Endpoints().DeployRelease(c, endpointID, endpoint.TargetType,
		request.TargetRef, request.Version, collaborationActor(c, s), strings.TrimSpace(request.Reason))
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
		previous.TargetRef, request.Version, collaborationActor(c, s), fmt.Sprintf("rollback to release %d", previous.Number))
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
		Name      string          `json:"name"`
		Scopes    json.RawMessage `json:"scopes"`
		ExpiresAt *time.Time      `json:"expiresAt"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if strings.TrimSpace(request.Name) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "name is required"})
		return
	}
	candidate, key, err := s.buildEndpointCredential(id, strings.TrimSpace(request.Name),
		request.Scopes, request.ExpiresAt, nil)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: "generate endpoint credential"})
		return
	}
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
	if credential.Status != controlmodel.EndpointCredentialActive {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "only active credentials can be revealed"})
		return
	}
	if len(credential.SecretCiphertext) == 0 {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "credential predates recoverable storage; rotate it once to enable copy"})
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
	now := time.Now().UTC()
	credential.LastUsedAt = &now
	_, _ = s.store.Endpoints().UpdateCredential(c, credential)
	principalID := credential.ID
	if credential.RotatedFrom != nil {
		principalID = *credential.RotatedFrom
	}
	c.Set(endpointPrincipalContextKey, "api-key:"+principalID.String())
	return true
}

func (s *Server) allowEndpointRequest(c *gin.Context, endpoint *controlmodel.Endpoint) bool {
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
	if schemaErr := validateEndpointInput(endpoint.InputSchema, map[string]any{"message": req.Message}); schemaErr != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: schemaErr.Error()})
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
	var conversation *controlmodel.EndpointConversation
	var session *store.Session
	var invocation *controlmodel.EndpointInvocation
	var fresh bool
	var err error
	if conversationID != uuid.Nil {
		conversation, session, err = s.loadEndpointConversationSession(c, endpoint, conversationID, principal)
		if err == nil {
			turnID := uuid.New()
			invocation, fresh, err = s.store.Endpoints().ReserveInvocation(c, &controlmodel.EndpointInvocation{
				EndpointID: endpoint.ID, Mode: controlmodel.EndpointConversationMode, PrincipalType: "credential",
				PrincipalRef: principal, IdempotencyKey: idem, Status: controlmodel.EndpointInvocationDispatching,
				ConversationID: &conversation.ID, TurnID: &turnID, SessionID: session.SessionID,
				Input: payload, CorrelationID: requestCorrelationID(c),
			})
		}
	} else {
		invocation, fresh, err = s.store.Endpoints().ReserveInvocation(c, &controlmodel.EndpointInvocation{
			EndpointID: endpoint.ID, Mode: controlmodel.EndpointConversationMode, PrincipalType: "credential",
			PrincipalRef: principal, IdempotencyKey: idem, Status: controlmodel.EndpointInvocationDispatching,
			Input: payload, CorrelationID: requestCorrelationID(c),
		})
		if err == nil && !fresh {
			if !sameJSON(invocation.Input, payload) {
				c.JSON(http.StatusConflict, ErrorResponse{Error: "Idempotency-Key was already used with different input"})
				return
			}
			if invocation.ConversationID == nil {
				c.JSON(http.StatusAccepted, gin.H{"invocationId": invocation.ID, "status": invocation.Status})
				return
			}
			conversation, session, err = s.loadEndpointConversationSession(c, endpoint, *invocation.ConversationID, principal)
		}
		if err == nil && fresh {
			session, err = s.resolveEndpointConversation(c.Request.Context(), endpoint, invocation.ID.String())
			if err == nil {
				conversation, err = s.store.Endpoints().CreateConversation(c, &controlmodel.EndpointConversation{
					EndpointID: endpoint.ID, AgentID: session.AgentID, SessionID: session.SessionID,
					BindingID: session.BindingID, AgentInstanceID: session.AgentInstanceID,
					InstanceGeneration: session.InstanceGeneration, Status: controlmodel.EndpointConversationActive,
					PrincipalRef: principal,
				})
			}
			if err == nil {
				turnID := uuid.New()
				invocation.ConversationID, invocation.TurnID, invocation.SessionID = &conversation.ID, &turnID, session.SessionID
				invocation, err = s.store.Endpoints().UpdateInvocation(c, invocation)
			}
		}
	}
	if err != nil {
		if invocation != nil && fresh {
			s.failEndpointInvocation(c, invocation, "conversation_resolve_failed", err)
		}
		s.writeControlPlaneError(c, err)
		return
	}
	if !fresh && !sameJSON(invocation.Input, payload) {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "Idempotency-Key was already used with different input"})
		return
	}
	if fresh {
		err = s.sendEndpointConversationTurn(c.Request.Context(), endpoint, conversation, invocation, req.Message)
		if err != nil {
			s.failEndpointInvocation(c, invocation, "conversation_dispatch_failed", err)
			c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: err.Error()})
			return
		}
	}
	c.JSON(http.StatusAccepted, gin.H{
		"invocationId": invocation.ID, "conversationId": conversation.ID, "turnId": invocation.TurnID,
		"sessionId": session.SessionID, "sessionRef": session.ID, "status": invocation.Status,
		"eventsUrl": "/invoke/v1/conversations/" + conversation.ID.String() + "/events?invocationId=" + invocation.ID.String(),
		"statusUrl": "/invoke/v1/conversations/" + conversation.ID.String(),
	})
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
		now := time.Now().UTC()
		invocation.Status, invocation.StartedAt = controlmodel.EndpointInvocationRunning, &now
		if _, err = s.store.Endpoints().UpdateInvocation(ctx, invocation); err != nil {
			return err
		}
		return s.product.PostEndpointSessionWakeEvent(ctx, conversation.SessionID, cfg.OwnerRef, message,
			invocation.ID.String(), invocation.TurnID.String())
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
	var inputValue any
	if len(req.Input) > 0 && string(req.Input) != "null" {
		if err = json.Unmarshal(req.Input, &inputValue); err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "input must be valid JSON"})
			return
		}
	}
	if schemaErr := validateEndpointInput(endpoint.InputSchema, inputValue); schemaErr != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: schemaErr.Error()})
		return
	}
	if req.Title == "" {
		req.Title = "Endpoint job"
	}
	input, _ := json.Marshal(gin.H{"title": req.Title, "description": req.Description, "input": req.Input})
	principal, _ := c.Get(endpointPrincipalContextKey)
	invocation, fresh, err := s.store.Endpoints().ReserveInvocation(c, &controlmodel.EndpointInvocation{
		EndpointID: endpoint.ID, Mode: controlmodel.EndpointJobMode, PrincipalType: "credential",
		PrincipalRef: fmt.Sprint(principal), IdempotencyKey: idem, Status: controlmodel.EndpointInvocationAccepted,
		Input: input, CorrelationID: requestCorrelationID(c),
	})
	if err != nil {
		s.writeControlPlaneError(c, err)
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
	now := time.Now().UTC()
	invocation.Status, invocation.StartedAt = controlmodel.EndpointInvocationDispatching, &now
	invocation, _ = s.store.Endpoints().UpdateInvocation(c, invocation)
	issueID := uuid.NewSHA1(invocation.ID, []byte("issue"))
	actor := controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "endpoint:" + endpoint.ID.String()}
	issue := &controlmodel.Issue{ID: issueID, Tenant: endpoint.Tenant, Namespace: endpoint.Namespace,
		Title: req.Title, Description: collaboration.EndpointIssueDescription(req.Description, req.Input), Status: controlmodel.IssueInProgress, Priority: "normal",
		Kind: controlmodel.IssueKindEndpointJob, Visibility: controlmodel.IssueVisibilityOperational,
		CompletionPolicy: controlmodel.IssueCompletionAutomatic, Creator: actor,
		SourceType: "endpoint", SourceRef: invocation.ID.String(),
		ExecutionTargetType: string(endpoint.TargetType), ExecutionTargetRef: endpoint.TargetRef.String()}
	var assigneeType controlmodel.AssigneeType
	var assigneeRef string
	switch endpoint.TargetType {
	case controlmodel.EndpointTargetAgent:
		assigneeType, assigneeRef = controlmodel.AssigneeAgent, endpoint.TargetRef.String()
	case controlmodel.EndpointTargetTeam:
		assigneeType, assigneeRef = controlmodel.AssigneeTeam, endpoint.TargetRef.String()
	}
	createdIssue, err := s.store.Collaboration().CreateIssue(c, issue)
	if err != nil && err != store.ErrConflict {
		s.failEndpointInvocation(c, invocation, "issue_create_failed", err)
		s.writeControlPlaneError(c, err)
		return
	}
	if createdIssue == nil {
		createdIssue, err = s.store.Collaboration().GetIssue(c, issueID)
	}
	if err != nil {
		s.failEndpointInvocation(c, invocation, "issue_load_failed", err)
		s.writeControlPlaneError(c, err)
		return
	}
	// Persist assignment as business state without asking Issue creation to
	// synthesize a second, non-Run AgentTask.
	if assigneeType != "" && (createdIssue.AssigneeType != assigneeType || createdIssue.AssigneeRef != assigneeRef) {
		createdIssue.AssigneeType, createdIssue.AssigneeRef = assigneeType, assigneeRef
		if _, err = s.store.Collaboration().UpdateIssue(c, createdIssue, createdIssue.Version, actor); err != nil {
			s.failEndpointInvocation(c, invocation, "issue_assign_failed", err)
			s.writeControlPlaneError(c, err)
			return
		}
	}
	var run *controlmodel.OrchestrationRun
	if endpoint.TargetType == controlmodel.EndpointTargetOrchestrationRevision {
		revision, loadErr := s.store.Orchestration().GetRevision(c, endpoint.TargetRef)
		if loadErr != nil {
			s.writeControlPlaneError(c, loadErr)
			return
		}
		run, err = s.orchestrationService().Start(c, revision.DefinitionID, orchestration.StartRequest{
			RevisionID: &revision.ID, IdempotencyKey: endpoint.ID.String() + ":" + idem,
			Input: req.Input, IssueID: &issueID, TriggerType: "endpoint",
			TriggerRef: endpoint.ID.String(), Actor: actor,
		})
	} else {
		mode := controlmodel.RunModeDirect
		if endpoint.TargetType == controlmodel.EndpointTargetTeam {
			mode = controlmodel.RunModeAdaptive
		}
		runID := uuid.NewSHA1(invocation.ID, []byte("run"))
		run, err = s.store.Orchestration().CreateRun(c, &controlmodel.OrchestrationRun{ID: runID,
			Tenant: endpoint.Tenant, Namespace: endpoint.Namespace, RootIssueID: issueID, Mode: mode,
			TriggerType: "endpoint", TriggerRef: endpoint.ID.String(),
			IdempotencyKey: endpoint.ID.String() + ":" + idem, Input: req.Input,
			State: controlmodel.RunRunning, CreatedBy: actor})
		if err == nil {
			err = s.materializeEndpointTarget(c.Request.Context(), endpoint, run, issueID, actor)
		}
	}
	if err != nil {
		s.failEndpointInvocation(c, invocation, "run_create_failed", err)
		s.writeControlPlaneError(c, err)
		return
	}
	tasks, _ := s.store.Collaboration().ListAgentTasks(c, store.AgentTaskFilter{RunID: run.ID, Limit: 100})
	for _, task := range tasks {
		if task.Status == controlmodel.AgentTaskQueued {
			_ = s.DispatchAgentTask(c.Request.Context(), task.ID)
		}
	}
	invocation.IssueID, invocation.RunID = &issueID, &run.ID
	invocation.Status = controlmodel.EndpointInvocationRunning
	invocation, err = s.store.Endpoints().UpdateInvocation(c, invocation)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
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

func (s *Server) getEndpointConversationEvents(c *gin.Context) {
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
	if !s.authenticateEndpoint(c, endpoint, false) {
		return
	}
	principal, _ := c.Get(endpointPrincipalContextKey)
	if conversation.PrincipalRef != fmt.Sprint(principal) {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "conversation is unavailable"})
		return
	}
	sessions, err := s.store.Sessions().List(c, store.SessionFilter{Tenant: endpoint.Tenant,
		Namespace: endpoint.Namespace, AgentID: conversation.AgentID, SessionID: conversation.SessionID, Limit: 1})
	if err != nil || len(sessions) == 0 {
		s.writeControlPlaneError(c, store.ErrNotFound)
		return
	}
	session := sessions[0]
	after, _ := strconv.Atoi(c.Query("after"))
	if headerAfter, parseErr := strconv.Atoi(c.GetHeader("Last-Event-ID")); parseErr == nil && headerAfter > after {
		after = headerAfter
	}
	var invocation *controlmodel.EndpointInvocation
	if raw := strings.TrimSpace(c.Query("invocationId")); raw != "" {
		invocationID, parseErr := uuid.Parse(raw)
		if parseErr != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid invocationId"})
			return
		}
		invocation, err = s.store.Endpoints().GetInvocation(c, invocationID)
		if err != nil || invocation.ConversationID == nil || *invocation.ConversationID != conversation.ID {
			s.writeControlPlaneError(c, store.ErrNotFound)
			return
		}
	}
	prepareEventStream(c)
	flusher, _ := c.Writer.(http.Flusher)
	for {
		events, listErr := s.store.Events().List(c, session.ID,
			store.WithEventAfterSeq(after), store.WithEventLimit(1000))
		if listErr != nil {
			return
		}
		for _, event := range events {
			payload, _ := json.Marshal(event)
			_, _ = fmt.Fprintf(c.Writer, "id: %d\nevent: %s\ndata: %s\n\n", event.Seq, event.EventType, payload)
			after = event.Seq
		}
		if flusher != nil {
			flusher.Flush()
		}
		if invocation != nil {
			latest, loadErr := s.store.Endpoints().GetInvocation(c, invocation.ID)
			if loadErr == nil {
				latest = s.expireEndpointInvocation(c, endpoint, latest)
			}
			if loadErr == nil && endpointInvocationTerminal(latest.Status) {
				return
			}
		}
		if len(events) > 0 {
			continue
		}
		waitCtx, cancel := context.WithTimeout(c.Request.Context(), 15*time.Second)
		waitErr := s.store.Events().WaitForNew(waitCtx, session.ID, after)
		cancel()
		if waitErr == nil {
			continue
		}
		if c.Request.Context().Err() != nil {
			return
		}
		if waitErr == context.DeadlineExceeded {
			_, _ = fmt.Fprint(c.Writer, ": heartbeat\n\n")
			if flusher != nil {
				flusher.Flush()
			}
			continue
		}
		return
	}
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
	if invocation == nil || endpoint == nil || endpointInvocationTerminal(invocation.Status) || endpoint.TimeoutSeconds <= 0 ||
		time.Now().UTC().Before(invocation.CreatedAt.Add(time.Duration(endpoint.TimeoutSeconds)*time.Second)) {
		return invocation
	}
	now := time.Now().UTC()
	if invocation.Mode == controlmodel.EndpointJobMode && invocation.RunID != nil {
		_, _ = s.orchestrationService().Cancel(ctx, *invocation.RunID)
	}
	invocation.Status, invocation.ErrorCode = controlmodel.EndpointInvocationTimedOut, "endpoint_timeout"
	invocation.ErrorMessage, invocation.CompletedAt = "Endpoint invocation exceeded its configured timeout", &now
	updated, err := s.store.Endpoints().UpdateInvocation(ctx, invocation)
	if err == nil {
		return updated
	}
	return invocation
}

func (s *Server) endpointJobAccepted(c *gin.Context, invocation *controlmodel.EndpointInvocation) {
	response := gin.H{
		"invocationId": invocation.ID, "status": invocation.Status,
		"statusUrl": "/invoke/v1/jobs/" + invocation.ID.String(),
		"eventsUrl": "/invoke/v1/jobs/" + invocation.ID.String() + "/events",
	}
	if invocation.IssueID != nil {
		response["issueId"] = invocation.IssueID
	}
	if invocation.RunID != nil {
		response["runId"] = invocation.RunID
	}
	c.JSON(http.StatusAccepted, response)
}

func (s *Server) loadAuthorizedEndpointJob(c *gin.Context) (*controlmodel.EndpointInvocation, *controlmodel.Endpoint, bool) {
	invocationID, err := uuid.Parse(c.Param("invocationId"))
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid invocationId"})
		return nil, nil, false
	}
	invocation, err := s.store.Endpoints().GetInvocation(c, invocationID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return nil, nil, false
	}
	if invocation.Mode != controlmodel.EndpointJobMode {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "invocation is not a job"})
		return nil, nil, false
	}
	endpoint, err := s.store.Endpoints().Get(c, invocation.EndpointID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return nil, nil, false
	}
	if !s.authenticateEndpoint(c, endpoint, false) {
		return nil, nil, false
	}
	principal, _ := c.Get(endpointPrincipalContextKey)
	if invocation.PrincipalRef != fmt.Sprint(principal) {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "job is unavailable"})
		return nil, nil, false
	}
	return invocation, endpoint, true
}
func (s *Server) getEndpointJob(c *gin.Context) {
	invocation, endpoint, ok := s.loadAuthorizedEndpointJob(c)
	if !ok {
		return
	}
	var issue *controlmodel.Issue
	var run *controlmodel.OrchestrationRun
	if invocation.RunID != nil {
		run, _ = s.store.Orchestration().GetRun(c, *invocation.RunID)
		if run != nil && controlmodel.IsOrchestrationRunTerminal(run.State) {
			// Also converges Jobs completed before the Issue review invariant was
			// introduced. Reconciliation is idempotent and never auto-accepts work.
			_ = (&orchestration.Engine{Store: s.store}).ReconcileRun(c, run.ID)
		}
		if run != nil && controlmodel.IsOrchestrationRunTerminal(run.State) && invocation.CompletedAt == nil {
			now := time.Now().UTC()
			invocation.CompletedAt = &now
			switch run.State {
			case controlmodel.RunSucceeded, controlmodel.RunPartialSucceeded:
				invocation.Status = controlmodel.EndpointInvocationCompleted
			case controlmodel.RunCancelled:
				invocation.Status = controlmodel.EndpointInvocationCancelled
			default:
				invocation.Status = controlmodel.EndpointInvocationFailed
				invocation.ErrorCode, invocation.ErrorMessage = run.FailureCode, run.FailureMessage
				if invocation.ErrorCode == "" {
					invocation.ErrorCode = "run_failed"
				}
			}
			nodes, err := s.store.Orchestration().ListNodes(c, run.ID)
			if err != nil {
				s.writeControlPlaneError(c, err)
				return
			}
			invocation.Result = orchestration.CompletedRunOutput(run, nodes)
			if len(invocation.Result) == 0 {
				invocation.Result, _ = json.Marshal(gin.H{"runState": run.State})
			}
			invocation, _ = s.store.Endpoints().UpdateInvocation(c, invocation)
		}
	}
	if invocation.IssueID != nil {
		issue, _ = s.store.Collaboration().GetIssue(c, *invocation.IssueID)
	}
	invocation = s.expireEndpointInvocation(c, endpoint, invocation)
	response := gin.H{"invocation": endpointInvocationPublic(invocation)}
	if issue != nil {
		response["issue"] = gin.H{"id": issue.ID, "status": issue.Status, "updatedAt": issue.UpdatedAt}
	}
	if run != nil {
		response["run"] = gin.H{"id": run.ID, "state": run.State, "updatedAt": run.UpdatedAt}
	}
	c.JSON(http.StatusOK, response)
}

func (s *Server) getEndpointJobArtifacts(c *gin.Context) {
	invocation, endpoint, ok := s.loadAuthorizedEndpointJob(c)
	if !ok {
		return
	}
	if invocation.IssueID == nil {
		c.JSON(http.StatusOK, gin.H{"items": []*controlmodel.Artifact{}})
		return
	}
	items, err := s.store.Collaboration().ListArtifacts(c, endpoint.Tenant, endpoint.Namespace,
		"issue", invocation.IssueID.String())
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	public := make([]gin.H, 0, len(items))
	for _, item := range items {
		public = append(public, gin.H{"id": item.ID, "filename": item.Filename, "contentType": item.ContentType,
			"sizeBytes": item.SizeBytes, "createdAt": item.CreatedAt, "expiresAt": item.ExpiresAt,
			"downloadUrl": "/invoke/v1/jobs/" + invocation.ID.String() + "/artifacts/" + item.ID.String()})
	}
	c.JSON(http.StatusOK, gin.H{"items": public})
}

func (s *Server) downloadEndpointJobArtifact(c *gin.Context) {
	invocation, endpoint, ok := s.loadAuthorizedEndpointJob(c)
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
	linked := false
	for _, link := range links {
		if link.TargetType == "issue" && link.TargetRef == invocation.IssueID.String() {
			linked = true
			break
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

func (s *Server) getEndpointJobEvents(c *gin.Context) {
	invocation, endpoint, ok := s.loadAuthorizedEndpointJob(c)
	if !ok {
		return
	}
	if invocation.RunID == nil {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "job has not created a Run"})
		return
	}
	after, _ := strconv.ParseInt(c.Query("after"), 10, 64)
	if headerAfter, parseErr := strconv.ParseInt(c.GetHeader("Last-Event-ID"), 10, 64); parseErr == nil && headerAfter > after {
		after = headerAfter
	}
	prepareEventStream(c)
	flusher, _ := c.Writer.(http.Flusher)
	for {
		events, listErr := s.store.Orchestration().ListRunEvents(c, *invocation.RunID, after, 1000)
		if listErr != nil {
			return
		}
		for _, event := range events {
			payload, _ := json.Marshal(event)
			_, _ = fmt.Fprintf(c.Writer, "id: %d\nevent: %s\ndata: %s\n\n", event.Sequence, event.Type, payload)
			after = event.Sequence
		}
		if flusher != nil {
			flusher.Flush()
		}
		run, _ := s.store.Orchestration().GetRun(c, *invocation.RunID)
		if run != nil && controlmodel.IsOrchestrationRunTerminal(run.State) {
			return
		}
		latest, loadErr := s.store.Endpoints().GetInvocation(c, invocation.ID)
		if loadErr == nil && endpointInvocationTerminal(s.expireEndpointInvocation(c, endpoint, latest).Status) {
			return
		}
		if len(events) > 0 {
			continue
		}
		waitCtx, cancel := context.WithTimeout(c.Request.Context(), 15*time.Second)
		waitErr := s.store.Orchestration().WaitForRunEvent(waitCtx, *invocation.RunID, after)
		cancel()
		if waitErr == nil {
			continue
		}
		if c.Request.Context().Err() != nil {
			return
		}
		if waitErr == context.DeadlineExceeded {
			_, _ = fmt.Fprint(c.Writer, ": heartbeat\n\n")
			if flusher != nil {
				flusher.Flush()
			}
			continue
		}
		return
	}
}

func prepareEventStream(c *gin.Context) {
	c.Header("Content-Type", "text/event-stream")
	c.Header("Cache-Control", "no-cache, no-transform")
	c.Header("Connection", "keep-alive")
	c.Header("X-Accel-Buffering", "no")
}

func endpointInvocationTerminal(status controlmodel.EndpointInvocationStatus) bool {
	return status == controlmodel.EndpointInvocationCompleted || status == controlmodel.EndpointInvocationFailed ||
		status == controlmodel.EndpointInvocationCancelled || status == controlmodel.EndpointInvocationTimedOut
}

func (s *Server) cancelEndpointJob(c *gin.Context) {
	invocation, _, ok := s.loadAuthorizedEndpointJob(c)
	if !ok {
		return
	}
	if invocation.RunID == nil {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "job has not created a Run"})
		return
	}
	if _, err := s.orchestrationService().Cancel(c, *invocation.RunID); err != nil && err != store.ErrConflict {
		s.writeControlPlaneError(c, err)
		return
	}
	now := time.Now().UTC()
	invocation.Status, invocation.CompletedAt = controlmodel.EndpointInvocationCancelled, &now
	invocation, _ = s.store.Endpoints().UpdateInvocation(c, invocation)
	c.JSON(http.StatusOK, gin.H{"invocation": endpointInvocationPublic(invocation)})
}

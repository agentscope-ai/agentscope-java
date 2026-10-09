/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package httpapi

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

const publicSessionContextKey = "public-service-session"

func (s *Server) registerPublicSessions() {
	g := s.router.Group("/api/v1/agent-sessions")
	g.Use(s.publicSessionAuth(), s.scopeMiddleware(), s.namespaceAccessMiddleware(), s.authzMiddleware())
	g.POST("", s.createPublicSession)
	g.GET("", s.listPublicSessions)
	g.GET("/:publicSessionId", s.getPublicSession)
	g.PATCH("/:publicSessionId", s.patchPublicSession)
	g.POST("/:publicSessionId/archive", s.archivePublicSession)
	g.POST("/:publicSessionId/restore", s.restorePublicSession)
	g.DELETE("/:publicSessionId", s.deletePublicSession)
	g.GET("/:publicSessionId/capabilities", s.publicSessionCapabilities)
	g.POST("/:publicSessionId/turns", s.createPublicSessionTurn)
	g.GET("/:publicSessionId/turns", s.listPublicSessionTurns)
	g.GET("/:publicSessionId/snapshot", s.publicSessionSnapshot)
	g.GET("/:publicSessionId/events", s.publicSessionEvents)
	g.GET("/:publicSessionId/events/stream", s.publicSessionStream)
	turns := g.Group("/:publicSessionId/turns/:turnId")
	turns.Use(s.publicSessionTurnContext())
	turns.GET("", s.getPublicSessionTurn)
	turns.GET("/capabilities", s.getServiceInvocationCapabilities)
	turns.GET("/snapshot", s.publicTurnSnapshot)
	turns.GET("/events", s.getServiceEvents)
	turns.GET("/events/stream", s.streamServiceEvents)
	turns.GET("/actions", s.publicTurnSnapshot)
	turns.GET("/usage", s.publicTurnSnapshot)
	turns.GET("/artifacts", s.publicTurnSnapshot)
	turns.GET("/artifacts/:artifactId", s.downloadServiceArtifact)
	turns.POST("/actions", s.createServiceCommand)
	turns.POST("/inputs", s.createServiceCommand)
	turns.POST("/steer", s.publicSessionSteer)
	turns.POST("/cancel", s.createServiceCommand)
	turns.POST("/resume", s.createServiceCommand)
	turns.GET("/commands/:commandId", s.getServiceCommand)
	turns.POST("/webhooks", s.createServiceWebhook)
	turns.GET("/webhooks", s.listServiceWebhooks)
	turns.DELETE("/webhooks/:webhookId", s.updateServiceWebhook)
	turns.POST("/webhooks/:webhookId/retry", s.updateServiceWebhook)
	hooks := g.Group("/:publicSessionId/webhooks", s.publicSessionWebhookContext())
	hooks.POST("", s.createServiceWebhook)
	hooks.GET("", s.listServiceWebhooks)
	hooks.DELETE("/:webhookId", s.updateServiceWebhook)
	hooks.POST("/:webhookId/retry", s.updateServiceWebhook)
	g.POST("/:publicSessionId/files", s.publicSessionFileUpload)
	g.GET("/:publicSessionId/files", s.publicSessionFiles)
	g.GET("/:publicSessionId/files/:fileId/content", s.publicSessionFileContent)
	for _, path := range []string{"budget", "usage", "checkpoints", "subagents", "artifacts", "items", "tools", "inputs", "required-actions"} {
		g.GET("/:publicSessionId/"+path, s.publicSessionNativeResource)
	}
	g.PUT("/:publicSessionId/budget", s.publicSessionNativeResource)
	g.POST("/:publicSessionId/fork", s.forkPublicSession)
	g.POST("/:publicSessionId/artifacts", s.publicSessionNativeResource)
	g.GET("/:publicSessionId/artifacts/:resourceId", s.publicSessionArtifactMetadata)
	g.POST("/:publicSessionId/inputs/inject", s.publicSessionNativeResource)
	g.POST("/:publicSessionId/checkpoints/restore", s.publicSessionNativeResource)
	g.GET("/:publicSessionId/export", s.publicSessionNativeResource)
	g.GET("/:publicSessionId/subagents/:resourceId", s.publicSessionNativeResource)
	g.GET("/:publicSessionId/subagents/:resourceId/snapshot", s.publicSessionNativeResource)
	for _, resource := range []string{"items", "tools", "turns", "runs", "required-actions", "usage", "subagents", "events", "events/stream"} {
		g.GET("/:publicSessionId/subagents/:resourceId/"+resource, s.publicSessionNativeResource)
	}
}

func (s *Server) sessionTargetConfiguration(c *gin.Context, tenant, namespace string, t sessionapi.Target) (*model.Endpoint, error) {
	if err := sessionapi.ValidateTarget(t); err != nil {
		return nil, err
	}
	ep := &model.Endpoint{ID: uuid.New(), Tenant: tenant, Namespace: namespace, TargetRef: t.ID, InvocationMode: model.EndpointJobMode, Status: model.EndpointPublished, MaxPayloadBytes: 16 << 20}
	switch t.Type {
	case "agent":
		ep.TargetType = model.EndpointTargetAgent
		a, err := s.activeAgentInScope(c, tenant, namespace, t.ID.String())
		if err != nil {
			return nil, err
		}
		cap := s.inspectInvocationCapabilities(c, a)
		if cap.Conversation.State == "available" {
			ep.InvocationMode = model.EndpointConversationMode
		}
		// Managed sessions retain their native execution API even before a tool
		// environment is selected by the session-create request.
		p, e := s.store.Orchestration().GetRuntimePolicy(c, tenant, namespace, t.ID.String())
		if e == nil {
			for _, candidate := range p.Candidates {
				if candidate.Binding.Kind == model.DataPlaneManaged {
					ep.InvocationMode = model.EndpointConversationMode
					break
				}
			}
		}
	case "team":
		ep.TargetType = model.EndpointTargetTeam
	case "workflow":
		d, err := s.store.Orchestration().GetDefinition(c, t.ID)
		if err != nil {
			return nil, err
		}
		if d.Tenant != tenant || d.Namespace != namespace || d.ArchivedAt != nil {
			return nil, store.ErrNotFound
		}
		if t.RevisionID != nil {
			ep.TargetRef = *t.RevisionID
		} else {
			revs, e := s.store.Orchestration().ListRevisions(c, t.ID)
			if e != nil {
				return nil, e
			}
			if len(revs) == 0 {
				return nil, fmt.Errorf("Workflow requires a published revision")
			}
			ep.TargetRef = revs[0].ID
		}
		r, err := s.store.Orchestration().GetRevision(c, ep.TargetRef)
		if err != nil {
			return nil, err
		}
		if r.DefinitionID != t.ID {
			return nil, store.ErrNotFound
		}
		ep.TargetType = model.EndpointTargetOrchestrationRevision
	}
	if err := s.validateEndpointTarget(c, ep); err != nil {
		return nil, err
	}
	return ep, nil
}

func (s *Server) createPublicSession(c *gin.Context) {
	if !endpointScope(c, "invoke") {
		return
	}
	var req struct {
		Target         sessionapi.Target `json:"target"`
		Agent          json.RawMessage   `json:"agent"`
		EnvironmentID  string            `json:"environmentId"`
		MemoryStoreIDs *[]string         `json:"memoryStoreIds,omitempty"`
		VaultIDs       *[]string         `json:"vaultIds,omitempty"`
		AgentOverrides map[string]any    `json:"agentOverrides,omitempty"`
		Resources      []any             `json:"resources,omitempty"`
		Budget         struct {
			MaxTokens int64 `json:"maxTokens"`
		} `json:"budget"`
		TimeoutSeconds int             `json:"timeoutSeconds"`
		InputSchema    json.RawMessage `json:"inputSchema,omitempty"`
		OutputSchema   json.RawMessage `json:"outputSchema,omitempty"`
		ResultMapping  json.RawMessage `json:"resultMapping,omitempty"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, ErrorResponse{Error: "invalid session request"})
		return
	}
	if req.Target.ID == uuid.Nil && len(req.Agent) > 0 {
		var id string
		if json.Unmarshal(req.Agent, &id) != nil {
			var a struct {
				ID      string `json:"id"`
				Version int    `json:"version"`
			}
			_ = json.Unmarshal(req.Agent, &a)
			id = a.ID
			req.Target.Version = a.Version
		}
		req.Target.ID, _ = uuid.Parse(id)
		req.Target.Type = "agent"
	}
	if req.TimeoutSeconds < 0 || req.Budget.MaxTokens < 0 {
		c.JSON(400, ErrorResponse{Error: "timeout and budget cannot be negative"})
		return
	}
	for _, schema := range []json.RawMessage{req.InputSchema, req.OutputSchema} {
		if err := validateEndpointSchema(schema); err != nil {
			c.JSON(400, ErrorResponse{Error: err.Error()})
			return
		}
	}
	if err := serviceapi.CheckResultMapping(req.ResultMapping); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	tenant, namespace := sessionScope(c, s.defaultTenant, s.defaultNamespace)
	principal := c.GetString(endpointPrincipalContextKey)
	requestJSON, _ := json.Marshal(req)
	if !s.validatePublicSessionOptions(c, requestJSON) {
		return
	}
	sum := sha256.Sum256(requestJSON)
	hash := hex.EncodeToString(sum[:])
	key := c.GetHeader("Idempotency-Key")
	if len(key) > 256 {
		c.JSON(400, ErrorResponse{Error: "Idempotency-Key must contain at most 256 characters"})
		return
	}
	id := uuid.New()
	if key != "" {
		id = uuid.NewSHA1(uuid.NameSpaceURL, []byte("session:"+tenant+":"+namespace+":"+principal+":"+key))
		if existing, e := sessionapi.Get(c, s.store, tenant, id); e == nil {
			if !s.sessionAccess(c, existing, "invoke") {
				return
			}
			if existing.InputHash != hash {
				s.writeControlPlaneError(c, store.ErrConflict)
				return
			}
			c.JSON(200, s.publicSessionView(c, existing))
			return
		} else if !errors.Is(e, store.ErrNotFound) {
			s.writeControlPlaneError(c, e)
			return
		}
	}
	ep, err := s.sessionTargetConfiguration(c, tenant, namespace, req.Target)
	if err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	if a := accessFrom(c); a != nil {
		if !a.Namespace.Decide(a.User, req.Target.Type+":"+req.Target.ID.String(), "use").Allowed {
			c.JSON(403, ErrorResponse{Error: "target use is not authorized"})
			return
		}
		items, e := s.resourceInventory(c, a.Namespace)
		if e != nil {
			s.writeControlPlaneError(c, e)
			return
		}
		if e = checkResourceGraph(a.Namespace, resourceMap(items), a.User, req.Target.Type+":"+req.Target.ID.String()); e != nil {
			s.writeControlPlaneError(c, e)
			return
		}
	}
	if raw, ok := c.Get("session-credential"); ok {
		granted := false
		for _, t := range raw.(sessionCredential).Targets {
			if t.Type == req.Target.Type && t.ID == req.Target.ID {
				granted = true
			}
		}
		if !granted {
			c.JSON(403, ErrorResponse{Error: "credential does not grant this target"})
			return
		}
	}
	ep.ID = id
	err = s.store.WithSessionLock(c, "public-session:"+ep.ID.String(), func(ctx context.Context) error {
		if existing, e := sessionapi.Get(ctx, s.store, tenant, ep.ID); e == nil {
			if existing.InputHash != hash {
				return store.ErrConflict
			}
			c.JSON(200, s.publicSessionView(ctx, existing))
			return nil
		} else if !errors.Is(e, store.ErrNotFound) {
			return e
		}
		ep.TimeoutSeconds = req.TimeoutSeconds
		ep.InputSchema = req.InputSchema
		ep.OutputSchema = req.OutputSchema
		ep.ResultMapping = req.ResultMapping
		ep.RateLimit, _ = json.Marshal(gin.H{"maxConcurrent": 0, "maxInvocationTokens": req.Budget.MaxTokens})
		raw, e := s.freezeServiceContract(ctx, ep)
		if e != nil {
			return e
		}
		contract, e := serviceapi.ReadContract(raw)
		if e != nil {
			return e
		}
		contract.PublicSessionID = ep.ID
		contract.EnvironmentID = req.EnvironmentID
		contract.SessionOptions = requestJSON
		if req.Target.Type == "workflow" {
			r := ep.TargetRef
			req.Target.RevisionID = &r
		}
		if req.Target.Type == "agent" && req.Target.Version > 0 {
			if s.product == nil {
				return fmt.Errorf("versioned Agent definitions are unavailable")
			}
			agent, e := s.store.AgentCatalog().GetAgent(ctx, req.Target.ID)
			if e != nil {
				return e
			}
			snapshot, e := s.product.RuntimeDefinitionVersion(ctx, agent.OwnerRef, agent.ID.String(), req.Target.Version)
			if e != nil {
				return e
			}
			snapshot["version"] = req.Target.Version
			contract.Definitions[agent.ID.String()], e = json.Marshal(snapshot)
			if e != nil {
				return e
			}
		}
		if req.Target.Type == "agent" {
			req.Target.Version = serviceapi.ContextVersion(serviceapi.WithContract(ctx, contract), req.Target.ID.String())
			// Materialization may happen after an Agent edit. Preserve the selected
			// definition's default mounts rather than reading the later head.
			if s.publicSessionIsManaged(contract) {
				var definition map[string]json.RawMessage
				if e = json.Unmarshal(contract.Definitions[req.Target.ID.String()], &definition); e != nil {
					return e
				}
				var options map[string]json.RawMessage
				_ = json.Unmarshal(requestJSON, &options)
				if req.EnvironmentID == "" {
					_ = json.Unmarshal(definition["defaultEnvironmentId"], &contract.EnvironmentID)
				}
				if req.MemoryStoreIDs == nil {
					value := definition["defaultMemoryStoreIds"]
					if len(value) == 0 || string(value) == "null" {
						value = json.RawMessage(`[]`)
					}
					options["memoryStoreIds"] = value
				}
				if req.VaultIDs == nil {
					value := definition["defaultVaultIds"]
					if len(value) == 0 || string(value) == "null" {
						value = json.RawMessage(`[]`)
					}
					options["vaultIds"] = value
				}
				contract.SessionOptions, e = json.Marshal(options)
				if e != nil {
					return e
				}
			}
		}
		raw, e = json.Marshal(contract)
		if e != nil {
			return e
		}
		now := time.Now().UTC()
		v := &sessionapi.Session{ID: ep.ID, Tenant: tenant, Namespace: namespace, Target: req.Target, Principal: principal, Contract: raw, Status: "active", CreatedAt: now, UpdatedAt: now, InputHash: hash}
		if a, ok := c.Get(endpointApplicationContextKey); ok {
			id := a.(*model.Application).ID
			v.ApplicationID = &id
		}
		if e = sessionapi.Save(ctx, s.store, v, true); e != nil {
			return e
		}
		c.JSON(201, s.publicSessionView(ctx, v))
		return nil
	})
	if err != nil {
		s.writeControlPlaneError(c, err)
	}
}

func (s *Server) getPublicSession(c *gin.Context) {
	if v, ok := s.loadPublicSession(c, "read"); ok {
		c.JSON(200, s.publicSessionView(c, v))
	}
}
func (s *Server) listPublicSessions(c *gin.Context) {
	if !endpointScope(c, "read") {
		return
	}
	tenant, namespace := sessionScope(c, s.defaultTenant, s.defaultNamespace)
	limit := queryInt(c, "limit", 50)
	if limit < 1 || limit > 200 {
		limit = 50
	}
	offset := max(0, queryInt(c, "offset", 0))
	result := []map[string]any{}
	applicationRead := map[uuid.UUID]bool{}
	for {
		rows, err := s.store.KV().Search(c, tenant, sessionapi.Path, 100, offset)
		if err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
		for _, r := range rows {
			offset++
			var v sessionapi.Session
			if json.Unmarshal(r.Value, &v) != nil || v.Namespace != namespace || v.Status == "deleted" {
				continue
			}
			status := c.DefaultQuery("status", "active")
			if status != "all" && v.Status != status {
				continue
			}
			if agent := c.Query("agentId"); agent != "" && v.Target.ID.String() != agent {
				continue
			}
			allowed := v.Principal == c.GetString(endpointPrincipalContextKey)
			if !allowed && v.ApplicationID != nil && strings.HasPrefix(c.GetString(endpointPrincipalContextKey), "platform-user:") {
				var known bool
				allowed, known = applicationRead[*v.ApplicationID]
				if !known {
					app, e := s.store.Applications().Get(c, *v.ApplicationID)
					allowed = e == nil && app.Status == "active" && app.Allows(strings.TrimPrefix(c.GetString(endpointPrincipalContextKey), "platform-user:"), "read")
					applicationRead[*v.ApplicationID] = allowed
				}
			}
			if value, ok := c.Get("session-credential"); ok {
				allowed = false
				for _, target := range value.(sessionCredential).Targets {
					if v.Principal == c.GetString(endpointPrincipalContextKey) && target.Type == v.Target.Type && target.ID == v.Target.ID {
						allowed = true
						break
					}
				}
			}
			if allowed {
				result = append(result, s.publicSessionView(c, &v))
			}
			if len(result) >= limit {
				c.JSON(200, gin.H{"items": result, "next_offset": offset})
				return
			}
		}
		if len(rows) < 100 {
			break
		}
	}
	c.JSON(200, gin.H{"items": result, "next_offset": nil})
}

func (s *Server) publicSessionState(c *gin.Context, status string) {
	v, ok := s.loadPublicSession(c, "interact")
	if !ok {
		return
	}
	err := s.store.WithSessionLock(c, "public-session:"+v.ID.String(), func(ctx context.Context) error {
		count, e := s.store.Endpoints().CountActiveInvocations(ctx, store.EndpointInvocationFilter{EndpointID: v.ID})
		if e != nil {
			return e
		}
		if count > 0 {
			return fmt.Errorf("%w: session has active turns; cancel them before archiving or deleting", store.ErrConflict)
		}
		current, e := sessionapi.Get(ctx, s.store, v.Tenant, v.ID)
		if e != nil {
			return e
		}
		if current.Status == "deleted" {
			return store.ErrNotFound
		}
		current.Status = status
		current.UpdatedAt = time.Now().UTC()
		if e = sessionapi.Save(ctx, s.store, current, false); e != nil {
			return e
		}
		c.JSON(200, current.View())
		return nil
	})
	if err != nil {
		c.JSON(409, ErrorResponse{Error: err.Error()})
	}
}
func (s *Server) archivePublicSession(c *gin.Context) { s.publicSessionState(c, "archived") }
func (s *Server) restorePublicSession(c *gin.Context) { s.publicSessionState(c, "active") }
func (s *Server) deletePublicSession(c *gin.Context)  { s.publicSessionState(c, "deleted") }

func (s *Server) ensurePublicConversation(ctx context.Context, v *sessionapi.Session, contract *serviceapi.Contract) (*model.EndpointConversation, error) {
	var result *model.EndpointConversation
	err := s.store.WithSessionLock(ctx, "public-conversation:"+v.ID.String(), func(ctx context.Context) error {
		var err error
		result, err = s.store.Endpoints().GetConversation(ctx, v.ID)
		if err == nil {
			return nil
		}
		if !errors.Is(err, store.ErrNotFound) {
			return err
		}
		ctx = serviceapi.WithContract(ctx, contract)
		session, err := s.resolveEndpointConversation(ctx, &contract.Endpoint, v.ID.String())
		if err != nil {
			return err
		}
		result, err = s.store.Endpoints().CreateConversation(ctx, &model.EndpointConversation{ID: v.ID, EndpointID: v.ID, AgentID: session.AgentID, SessionID: session.SessionID, BindingID: session.BindingID, AgentInstanceID: session.AgentInstanceID, InstanceGeneration: session.InstanceGeneration, Status: model.EndpointConversationActive, PrincipalRef: v.Principal, Contract: v.Contract})
		return err
	})
	return result, err
}

func (s *Server) createPublicSessionTurn(c *gin.Context) {
	v, ok := s.loadPublicSession(c, "invoke")
	if !ok {
		return
	}
	key := strings.TrimSpace(c.GetHeader("Idempotency-Key"))
	if key == "" || len(key) > 256 {
		c.JSON(400, ErrorResponse{Error: "Idempotency-Key must contain 1..256 characters"})
		return
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 16<<20)
	var input map[string]json.RawMessage
	if err := c.ShouldBindJSON(&input); err != nil {
		c.JSON(400, ErrorResponse{Error: "invalid task input"})
		return
	}
	// Middleware scope fields do not form part of a task's idempotent payload.
	delete(input, "tenant")
	delete(input, "namespace")
	if len(input["message"]) == 0 && len(input["input"]) == 0 {
		c.JSON(400, ErrorResponse{Error: "message or input is required"})
		return
	}
	if len(input["message"]) > 0 && len(input["input"]) > 0 {
		c.JSON(400, ErrorResponse{Error: "supply message or input, not both"})
		return
	}
	var message string
	if len(input["message"]) > 0 && (json.Unmarshal(input["message"], &message) != nil || strings.TrimSpace(message) == "") {
		c.JSON(400, ErrorResponse{Error: "message must be nonempty text"})
		return
	}
	contract, err := serviceapi.ReadContract(v.Contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if err = s.validatePublicSessionInput(c, v, contract, input); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	raw, _ := json.Marshal(input)
	var inv *model.EndpointInvocation
	err = s.store.WithSessionLock(c, "public-session:"+v.ID.String(), func(ctx context.Context) error {
		current, e := sessionapi.Get(ctx, s.store, v.Tenant, v.ID)
		if e != nil {
			return e
		}
		if current.Status != "active" {
			return fmt.Errorf("%w: session is %s", store.ErrConflict, current.Status)
		}
		if existing, e := s.store.Endpoints().GetInvocationByIdempotency(ctx, v.ID, contract.Endpoint.InvocationMode, v.Principal, key); e == nil {
			if !sameJSON(existing.Input, raw) {
				return store.ErrConflict
			}
			inv = existing
			return nil
		} else if !errors.Is(e, store.ErrNotFound) {
			return e
		}
		in := &model.EndpointInvocation{EndpointID: v.ID, Contract: v.Contract, Mode: contract.Endpoint.InvocationMode, PrincipalRef: v.Principal, IdempotencyKey: key, Status: model.EndpointInvocationAccepted, Input: raw, CorrelationID: requestCorrelationID(c), ApplicationID: v.ApplicationID}
		if in.Mode == model.EndpointConversationMode {
			conv, e := s.ensurePublicConversation(ctx, v, contract)
			if e != nil {
				return e
			}
			in.ConversationID = &conv.ID
			in.SessionID = conv.SessionID
		}
		var e2 error
		inv, _, e2 = s.reserveEndpointInvocation(c, &contract.Endpoint, in)
		return e2
	})
	if err != nil {
		s.writeServiceAdmissionError(c, err)
		return
	}
	_ = s.sweepServiceInvocation(c, inv)
	if current, e := s.store.Endpoints().GetInvocation(c, inv.ID); e == nil {
		inv = current
	}
	c.JSON(202, s.publicTurnView(v, inv))
}

func (s *Server) publicTurnView(v *sessionapi.Session, inv *model.EndpointInvocation) gin.H {
	base := "/api/v1/agent-sessions/" + v.ID.String()
	turn := base + "/turns/" + inv.ID.String()
	out := gin.H{"id": inv.ID, "sessionId": v.ID, "status": publicTurnStatus(inv.Status), "createdAt": inv.CreatedAt, "statusUrl": turn, "snapshotUrl": base + "/snapshot", "eventsUrl": base + "/events/stream"}
	if len(inv.Result) > 0 {
		out["result"] = inv.Result
	}
	if inv.ErrorCode != "" {
		out["error"] = gin.H{"code": inv.ErrorCode, "message": inv.ErrorMessage}
	}
	return out
}
func publicTurnStatus(status model.EndpointInvocationStatus) string {
	switch status {
	case model.EndpointInvocationAccepted, model.EndpointInvocationDispatching:
		return "queued"
	case model.EndpointInvocationWaiting:
		return "requires_action"
	default:
		return string(status)
	}
}

func (s *Server) publicSessionTurnContext() gin.HandlerFunc {
	return func(c *gin.Context) {
		scope := "read"
		if c.Request.Method != "GET" {
			scope = "interact"
		}
		if c.Request.Method != "GET" && strings.Contains(c.FullPath(), "/webhooks") {
			scope = "webhooks:write"
		}
		if strings.HasSuffix(c.FullPath(), "/cancel") {
			scope = "cancel"
		}
		if strings.HasSuffix(c.FullPath(), "/actions") && c.Request.Method == "POST" {
			scope = "approve"
		}
		v, ok := s.loadPublicSession(c, scope)
		if !ok {
			c.Abort()
			return
		}
		if v.Status != "active" && c.Request.Method != "GET" && scope != "cancel" && scope != "webhooks:write" {
			c.AbortWithStatusJSON(409, ErrorResponse{Error: "Session is not active"})
			return
		}
		id, e := uuid.Parse(c.Param("turnId"))
		if e != nil {
			c.AbortWithStatus(400)
			return
		}
		inv, e := s.store.Endpoints().GetInvocation(c, id)
		if e != nil || inv.EndpointID != v.ID {
			c.AbortWithStatus(404)
			return
		}
		c.Set(publicSessionContextKey, v)
		c.Params = append(c.Params, gin.Param{Key: "invocationId", Value: id.String()})
		c.Next()
	}
}

func (s *Server) getPublicSessionTurn(c *gin.Context) {
	v := c.MustGet(publicSessionContextKey).(*sessionapi.Session)
	id, _ := uuid.Parse(c.Param("turnId"))
	inv, err := s.refreshServiceInvocation(c, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(200, s.publicTurnView(v, inv))
}
func (s *Server) listPublicSessionTurns(c *gin.Context) {
	v, ok := s.loadPublicSession(c, "read")
	if !ok {
		return
	}
	limit := queryInt(c, "limit", 50)
	if limit < 1 || limit > 200 {
		limit = 50
	}
	offset := max(0, queryInt(c, "offset", 0))
	rows, err := s.store.Endpoints().ListInvocations(c, store.EndpointInvocationFilter{EndpointID: v.ID, Limit: limit + 1, Offset: offset, OldestFirst: true})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	more := len(rows) > limit
	if more {
		rows = rows[:limit]
	}
	out := []gin.H{}
	for _, r := range rows {
		out = append(out, s.publicTurnView(v, r))
	}
	var next any
	if more {
		next = offset + len(rows)
	}
	c.JSON(200, gin.H{"items": out, "next_offset": next})
}

func (s *Server) publicSessionCapabilities(c *gin.Context) {
	v, ok := s.loadPublicSession(c, "read")
	if !ok {
		return
	}
	contract, err := serviceapi.ReadContract(v.Contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	caps := guaranteedServiceCapabilities(contract)
	caps["files"] = s.artifactProvider != nil
	caps["file_input"] = caps["files"] && (s.publicSessionIsManaged(contract) || contract.Endpoint.InvocationMode == model.EndpointJobMode)
	caps["checkpoints"] = s.publicSessionIsManaged(contract)
	caps["subagents"] = caps["checkpoints"]
	caps["budget"] = caps["checkpoints"]
	c.JSON(200, gin.H{"sessionId": v.ID, "target": v.Target, "capabilities": caps, "submission": "queued_turns", "context": map[bool]string{true: "conversation", false: "independent_tasks"}[contract.Endpoint.InvocationMode == model.EndpointConversationMode]})
}

func (s *Server) publicSessionIsManaged(contract *serviceapi.Contract) bool {
	p := contract.Policies[contract.Endpoint.TargetRef.String()]
	if p == nil || len(p.Candidates) == 0 {
		return false
	}
	for _, v := range p.Candidates {
		if v.Binding.Kind != model.DataPlaneManaged {
			return false
		}
	}
	return true
}

func (s *Server) publicSessionSteer(c *gin.Context) {
	inv, ep, ok := s.loadServiceInvocation(c, "interact")
	if !ok {
		return
	}
	contract, err := serviceapi.ReadContract(inv.Contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if s.publicSessionIsManaged(contract) {
		s.publicManagedInput(c, inv, ep, "steer")
		return
	}
	c.Set("service-command-kind", "inputs")
	s.createServiceCommand(c)
}

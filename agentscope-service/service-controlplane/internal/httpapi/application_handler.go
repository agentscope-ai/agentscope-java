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
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

const endpointActorContextKey = "endpoint-actor"
const endpointApplicationContextKey = "endpoint-application"
const endpointCredentialContextKey = "endpoint-credential"

func validateApplication(a *model.Application) error {
	if strings.TrimSpace(a.Name) == "" {
		return fmt.Errorf("name is required")
	}
	if a.Status != "active" && a.Status != "disabled" {
		return fmt.Errorf("status must be active or disabled")
	}
	if a.MaxConcurrent < 0 || a.TokenBudget < 0 {
		return fmt.Errorf("maxConcurrent and tokenBudget must be non-negative")
	}
	seen := map[string]bool{}
	for _, m := range a.Members {
		if strings.TrimSpace(m.UserID) == "" || seen[m.UserID] || len(m.Roles) == 0 {
			return fmt.Errorf("members require unique userId and non-empty roles")
		}
		seen[m.UserID] = true
		for _, role := range m.Roles {
			if role != "viewer" && role != "operator" && role != "approver" {
				return fmt.Errorf("member roles must be viewer, operator, or approver")
			}
		}
	}
	return nil
}
func (s *Server) applicationManager(c *gin.Context, a *model.Application) bool {
	if a.OwnerUserID != s.operatorFromContext(c) {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "only the Application owner may manage it or its credentials"})
		return false
	}
	return true
}
func (s *Server) createApplication(c *gin.Context) {
	var in model.Application
	if err := c.ShouldBindJSON(&in); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	if in.Tenant == "" {
		in.Tenant = s.defaultTenant
	}
	if in.Namespace == "" {
		in.Namespace = s.defaultNamespace
	}
	in.OwnerUserID = s.operatorFromContext(c)
	in.Status = "active"
	in.ID = uuid.Nil
	in.TokensUsed = 0
	if err := validateApplication(&in); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	v, err := s.store.Applications().Create(c, &in)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(201, gin.H{"application": v})
}
func (s *Server) listApplications(c *gin.Context) {
	tenant, namespace := c.Query("tenant"), c.Query("namespace")
	if tenant == "" {
		tenant = s.defaultTenant
	}
	if namespace == "" {
		namespace = s.defaultNamespace
	}
	apps, err := s.store.Applications().List(c, tenant, namespace)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	out := []*model.Application{}
	for _, app := range apps {
		if app.Allows(s.operatorFromContext(c), "read") {
			out = append(out, app)
		}
	}
	c.JSON(200, gin.H{"items": out})
}
func (s *Server) getApplication(c *gin.Context) {
	id, ok := parseUUIDParam(c, "applicationId")
	if !ok {
		return
	}
	v, err := s.store.Applications().Get(c, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !v.Allows(s.operatorFromContext(c), "read") {
		s.writeControlPlaneError(c, store.ErrNotFound)
		return
	}
	c.JSON(200, gin.H{"application": v})
}
func (s *Server) patchApplication(c *gin.Context) {
	id, ok := parseUUIDParam(c, "applicationId")
	if !ok {
		return
	}
	v, err := s.store.Applications().Get(c, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !s.applicationManager(c, v) {
		return
	}
	var in struct {
		Name          *string                    `json:"name"`
		Description   *string                    `json:"description"`
		Status        *string                    `json:"status"`
		Members       *[]model.ApplicationMember `json:"members"`
		MaxConcurrent *int                       `json:"maxConcurrent"`
		TokenBudget   *int64                     `json:"tokenBudget"`
		Version       int64                      `json:"version"`
	}
	if err = c.ShouldBindJSON(&in); err != nil || in.Version <= 0 {
		c.JSON(400, ErrorResponse{Error: "valid JSON and version are required"})
		return
	}
	if in.MaxConcurrent != nil {
		v.MaxConcurrent = *in.MaxConcurrent
	}
	if in.TokenBudget != nil {
		v.TokenBudget = *in.TokenBudget
	}
	if in.Name != nil {
		v.Name = *in.Name
	}
	if in.Description != nil {
		v.Description = *in.Description
	}
	if in.Status != nil {
		v.Status = *in.Status
	}
	if in.Members != nil {
		v.Members = *in.Members
	}
	if err = validateApplication(v); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	v, err = s.store.Applications().Update(c, v, in.Version)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(200, gin.H{"application": v})
}
func validateEndpointScopes(raw json.RawMessage) ([]string, error) {
	var scopes []string
	if json.Unmarshal(raw, &scopes) != nil || len(scopes) == 0 {
		return nil, fmt.Errorf("explicit non-empty scopes are required")
	}
	seen := map[string]bool{}
	for _, scope := range scopes {
		if scope != "invoke" && scope != "read" && scope != "cancel" && scope != "interact" && scope != "webhooks:write" {
			return nil, fmt.Errorf("unknown credential scope: %s", scope)
		}
		if seen[scope] {
			return nil, fmt.Errorf("duplicate credential scope: %s", scope)
		}
		seen[scope] = true
	}
	return scopes, nil
}
func endpointActor(c *gin.Context) model.Actor {
	if actor, ok := c.Get(endpointActorContextKey); ok {
		if v, ok := actor.(model.Actor); ok {
			return v
		}
	}
	principal := c.GetString(endpointPrincipalContextKey)
	if strings.HasPrefix(principal, "platform-user:") {
		return model.Actor{Type: model.ActorHuman, Ref: strings.TrimPrefix(principal, "platform-user:")}
	}
	return model.Actor{Type: model.ActorAutomation, Ref: principal}
}
func (s *Server) reserveEndpointInvocation(c *gin.Context, ep *model.Endpoint, in *model.EndpointInvocation) (*model.EndpointInvocation, bool, error) {
	in.Actor = endpointActor(c)
	in.PrincipalType = "platform"
	if raw, ok := c.Get(endpointApplicationContextKey); ok {
		app := raw.(*model.Application)
		in.ApplicationID = &app.ID
		in.PrincipalType = "application"
	}
	if raw, ok := c.Get(endpointCredentialContextKey); ok {
		id := raw.(uuid.UUID)
		in.CredentialID = &id
	}
	return s.reserveServiceInvocation(c, ep, in)
}

// An authenticated application member may inspect/operate its own invocations. Endpoint keys from
// another application never gain access through membership or the endpoint management role.
func (s *Server) authorizeApplicationActor(ctx context.Context, inv *model.EndpointInvocation, ep *model.Endpoint, principal, scope string) bool {
	if inv.ApplicationID == nil || !strings.HasPrefix(principal, "platform-user:") {
		return false
	}
	app, err := s.store.Applications().Get(ctx, *inv.ApplicationID)
	if err != nil || app.Tenant != ep.Tenant || app.Namespace != ep.Namespace {
		return false
	}
	if scope != "read" && scope != "cancel" && app.Status != "active" {
		return false
	}
	user := strings.TrimPrefix(principal, "platform-user:")
	switch scope {
	case "read":
		return app.Allows(user, "read")
	case "cancel":
		return app.Allows(user, "operate")
	case "approve":
		return app.Allows(user, "approve")
	case "interact", "webhooks:write":
		return app.Allows(user, "operate")
	}
	return false
}

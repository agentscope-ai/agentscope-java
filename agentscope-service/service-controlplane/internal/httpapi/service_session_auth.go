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
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type sessionCredential struct {
	ID            uuid.UUID           `json:"id"`
	ApplicationID uuid.UUID           `json:"applicationId"`
	Name          string              `json:"name"`
	Scopes        []string            `json:"scopes"`
	Targets       []sessionapi.Target `json:"targets"`
	Hash          string              `json:"hash,omitempty"`
	Status        string              `json:"status"`
	CreatedAt     time.Time           `json:"createdAt"`
	ExpiresAt     *time.Time          `json:"expiresAt,omitempty"`
}

func applicationCredentialPath(id uuid.UUID) string { return "application-credentials/" + id.String() }

func (s *Server) createSessionCredential(c *gin.Context) {
	id, ok := parseUUIDParam(c, "applicationId")
	if !ok {
		return
	}
	app, err := s.store.Applications().Get(c, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !s.applicationManager(c, app) {
		return
	}
	var v sessionCredential
	if err = c.ShouldBindJSON(&v); err != nil || strings.TrimSpace(v.Name) == "" || len(v.Targets) == 0 {
		c.JSON(400, ErrorResponse{Error: "name, scopes and explicit target grants are required"})
		return
	}
	raw, _ := json.Marshal(v.Scopes)
	if _, err = validateEndpointScopes(raw); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	for _, target := range v.Targets {
		if target.Version != 0 || target.RevisionID != nil {
			c.JSON(400, ErrorResponse{Error: "credential grants identify resources; choose versions when creating a Session"})
			return
		}
		if err = sessionapi.ValidateTarget(target); err != nil {
			c.JSON(400, ErrorResponse{Error: err.Error()})
			return
		}
		if _, err = s.sessionTargetConfiguration(c, app.Tenant, app.Namespace, target); err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
		if a := accessFrom(c); a != nil && !a.Namespace.Decide(a.User, target.Type+":"+target.ID.String(), "use").Allowed {
			c.JSON(403, ErrorResponse{Error: "target is not available to the application owner"})
			return
		}
	}
	if v.ExpiresAt != nil && !v.ExpiresAt.After(time.Now()) {
		c.JSON(400, ErrorResponse{Error: "expiresAt must be in the future"})
		return
	}
	v.ID, v.ApplicationID, v.Status, v.CreatedAt = uuid.New(), app.ID, "active", time.Now().UTC()
	secret := make([]byte, 32)
	if _, err = rand.Read(secret); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	key := "ass_" + app.ID.String() + "_" + v.ID.String() + "_" + base64.RawURLEncoding.EncodeToString(secret)
	sum := sha256.Sum256([]byte(key))
	v.Hash = hex.EncodeToString(sum[:])
	raw, _ = json.Marshal(v)
	if _, err = s.store.KV().Put(c, app.Tenant, applicationCredentialPath(app.ID), v.ID.String(), raw); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	v.Hash = ""
	c.JSON(201, gin.H{"credential": v, "apiKey": key})
}

func (s *Server) listSessionCredentials(c *gin.Context) {
	id, ok := parseUUIDParam(c, "applicationId")
	if !ok {
		return
	}
	app, err := s.store.Applications().Get(c, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !s.applicationManager(c, app) {
		return
	}
	items := []sessionCredential{}
	for offset := 0; ; offset += 100 {
		rows, e := s.store.KV().Search(c, app.Tenant, applicationCredentialPath(id), 100, offset)
		if e != nil {
			s.writeControlPlaneError(c, e)
			return
		}
		for _, r := range rows {
			var v sessionCredential
			if json.Unmarshal(r.Value, &v) == nil {
				v.Hash = ""
				items = append(items, v)
			}
		}
		if len(rows) < 100 {
			break
		}
	}
	c.JSON(200, gin.H{"items": items})
}

func (s *Server) revokeSessionCredential(c *gin.Context) {
	id, ok := parseUUIDParam(c, "applicationId")
	if !ok {
		return
	}
	app, err := s.store.Applications().Get(c, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !s.applicationManager(c, app) {
		return
	}
	r, err := s.store.KV().Get(c, app.Tenant, applicationCredentialPath(id), c.Param("credentialId"))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	var v sessionCredential
	if err = json.Unmarshal(r.Value, &v); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	v.Status = "revoked"
	raw, _ := json.Marshal(v)
	_, written, err := s.store.KV().PutIfVersion(c, app.Tenant, applicationCredentialPath(id), v.ID.String(), raw, r.Version)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !written {
		s.writeControlPlaneError(c, store.ErrConflict)
		return
	}
	c.Status(204)
}

// Application authentication is separate from human approval identity. The owner's
// namespace membership bounds the credential; it never makes the key a human actor.
func (s *Server) publicSessionAuth() gin.HandlerFunc {
	return func(c *gin.Context) {
		key := c.GetHeader("X-API-Key")
		if key == "" {
			principal, valid := s.platformPrincipal(c, requestBearerToken(c))
			if !valid {
				c.AbortWithStatusJSON(401, ErrorResponse{Error: "a platform token or application API key is required"})
				return
			}
			c.Set(endpointPrincipalContextKey, principal)
			if strings.HasPrefix(principal, "platform-user:") {
				c.Set(endpointActorContextKey, model.Actor{Type: model.ActorHuman, Ref: strings.TrimPrefix(principal, "platform-user:")})
			}
			s.authMiddleware()(c)
			return
		}
		parts := strings.Split(key, "_")
		if len(parts) < 4 || parts[0] != "ass" {
			c.AbortWithStatusJSON(401, ErrorResponse{Error: "invalid application credential"})
			return
		}
		appID, err := uuid.Parse(parts[1])
		if err != nil {
			c.AbortWithStatus(401)
			return
		}
		app, err := s.store.Applications().Get(c, appID)
		if err != nil || app.Status != "active" {
			c.AbortWithStatus(401)
			return
		}
		r, err := s.store.KV().Get(c, app.Tenant, applicationCredentialPath(appID), parts[2])
		if err != nil {
			c.AbortWithStatus(401)
			return
		}
		var credential sessionCredential
		sum := sha256.Sum256([]byte(key))
		if json.Unmarshal(r.Value, &credential) != nil || credential.Status != "active" || credential.ApplicationID != app.ID ||
			subtle.ConstantTimeCompare([]byte(credential.Hash), []byte(hex.EncodeToString(sum[:]))) != 1 || credential.ExpiresAt != nil && !credential.ExpiresAt.After(time.Now()) {
			c.AbortWithStatus(401)
			return
		}
		for header, value := range map[string]string{"X-AgentScope-Tenant": app.Tenant, "X-AgentScope-Namespace": app.Namespace} {
			if supplied := c.GetHeader(header); supplied != "" && supplied != value {
				c.AbortWithStatus(403)
				return
			}
			c.Request.Header.Set(header, value)
		}
		c.Set("session-credential", credential)
		c.Set(endpointPrincipalContextKey, "application:"+app.ID.String())
		c.Set(endpointApplicationContextKey, app)
		c.Set(endpointCredentialContextKey, credential.ID)
		c.Set(endpointScopesContextKey, credential.Scopes)
		c.Set(endpointActorContextKey, model.Actor{Type: model.ActorAutomation, Ref: "session-key:" + credential.ID.String()})
		if s.product != nil {
			c.Set("userId", app.OwnerUserID)
			c.Set(ctxConsoleAuth, true)
		}
		c.Next()
	}
}

func sessionScope(c *gin.Context, fallbackTenant, fallbackNamespace string) (string, string) {
	if a := accessFrom(c); a != nil {
		return a.Namespace.Tenant, a.Namespace.Name
	}
	tenant, namespace := c.Request.URL.Query().Get("tenant"), c.Request.URL.Query().Get("namespace")
	if tenant == "" {
		tenant = c.GetHeader("X-AgentScope-Tenant")
	}
	if namespace == "" {
		namespace = c.GetHeader("X-AgentScope-Namespace")
	}
	if tenant == "" {
		tenant = fallbackTenant
	}
	if namespace == "" {
		namespace = fallbackNamespace
	}
	return tenant, namespace
}

func (s *Server) sessionAccess(c *gin.Context, v *sessionapi.Session, scope string) bool {
	tenant, namespace := sessionScope(c, s.defaultTenant, s.defaultNamespace)
	if v.Status == "deleted" || tenant != v.Tenant || namespace != v.Namespace {
		c.JSON(404, ErrorResponse{Error: "session not found"})
		return false
	}
	principal := c.GetString(endpointPrincipalContextKey)
	if v.Principal == principal {
		if raw, ok := c.Get("session-credential"); ok {
			credential := raw.(sessionCredential)
			granted := false
			for _, t := range credential.Targets {
				if t.Type == v.Target.Type && t.ID == v.Target.ID {
					granted = true
					break
				}
			}
			if !granted {
				c.JSON(403, ErrorResponse{Error: "credential does not grant this target"})
				return false
			}
		}
		return endpointScope(c, scope)
	}
	if v.ApplicationID != nil && strings.HasPrefix(principal, "platform-user:") {
		app, err := s.store.Applications().Get(c, *v.ApplicationID)
		action := "operate"
		if scope == "read" {
			action = "read"
		}
		if scope == "approve" {
			action = "approve"
		}
		if err == nil && app.Status == "active" && app.Allows(strings.TrimPrefix(principal, "platform-user:"), action) {
			return true
		}
	}
	c.JSON(404, ErrorResponse{Error: "session not found"})
	return false
}

func (s *Server) loadPublicSession(c *gin.Context, scope string) (*sessionapi.Session, bool) {
	id, err := uuid.Parse(c.Param("publicSessionId"))
	if err != nil {
		c.JSON(400, ErrorResponse{Error: "invalid session ID"})
		return nil, false
	}
	tenant, _ := sessionScope(c, s.defaultTenant, s.defaultNamespace)
	v, err := sessionapi.Get(c, s.store, tenant, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return nil, false
	}
	if !s.sessionAccess(c, v, scope) {
		return nil, false
	}
	if a := accessFrom(c); a != nil && scope != "read" && scope != "cancel" && scope != "approve" {
		if err = s.checkResourceUse(c, a.Namespace, a.User, v.Target.Type+":"+v.Target.ID.String()); err != nil {
			c.JSON(403, ErrorResponse{Error: err.Error()})
			return nil, false
		}
	}
	// Session ownership and the requested capability have been checked. Internal
	// Issue/Run rows are implementation details rather than a second user ACL.
	c.Request = c.Request.WithContext(store.WithWorkAccess(c.Request.Context(), store.WorkAccess{}))
	return v, true
}

func sessionUnsupported(c *gin.Context, capability string) {
	c.JSON(409, gin.H{"error": "capability_not_supported", "message": fmt.Sprintf("this session target does not support %s", capability)})
}

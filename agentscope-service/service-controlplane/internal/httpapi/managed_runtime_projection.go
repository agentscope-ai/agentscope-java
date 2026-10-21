// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package httpapi

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"slices"
	"strings"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/product"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// This restricted projection is configuration, never a Turn or event delivery path.
func (s *Server) requireRuntimeProjectionToken(c *gin.Context) {
	if !s.localDev && s.internalToken == "" {
		c.AbortWithStatusJSON(503, ErrorResponse{Error: "runtime projection internal token is not configured"})
		return
	}
	c.Next()
}

func (s *Server) managedRuntimeProjection(c *gin.Context) {
	ctx, cancel := context.WithTimeout(c.Request.Context(), 4*time.Second)
	defer cancel()
	id, err := uuid.Parse(c.Param("sessionId"))
	if err != nil {
		c.Status(400)
		return
	}
	tenant := c.Query("tenant")
	if tenant == "" {
		tenant = s.defaultTenant
	}
	projection, err := s.runtimeSessionProjection(ctx, tenant, id, c.Query("configuration") != "false")
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(200, projection)
}

// Reused by trusted native admission; it never dispatches a Turn or contacts a Brain replica.
func (s *Server) runtimeSessionProjection(ctx context.Context, tenant string, id uuid.UUID, includeConfiguration bool) (gin.H, error) {
	v, err := sessionapi.Get(ctx, s.store, tenant, id)
	if err != nil {
		return nil, err
	}
	contract, err := serviceapi.ReadContract(v.Contract)
	if err != nil {
		return nil, err
	}
	managed := s.publicSessionIsManaged(contract)
	ns, err := s.store.Access().GetNamespace(ctx, v.Tenant, v.Namespace)
	if err != nil && !s.localDev {
		return nil, err
	}
	humans := map[string][]string{}
	keys := []sessionCredential{}
	var application any
	applicationEnabled := true
	if v.SourceEndpointID != nil {
		endpoint, e := s.store.Endpoints().Get(ctx, *v.SourceEndpointID)
		if e != nil || endpoint.Tenant != v.Tenant || endpoint.Namespace != v.Namespace || endpoint.TargetRef != v.Target.ID {
			return nil, fmt.Errorf("native Session Endpoint binding is unavailable")
		}
		applicationEnabled = endpoint.Status == model.EndpointPublished
	}
	var graph map[string]model.ResourceDescriptor
	if managed {
		graph, err = s.managedRuntimeResources(ctx, ns, v, contract)
	} else {
		graph, err = s.goRuntimeResources(ctx, ns, v, contract)
	}
	if err != nil && !s.localDev {
		return nil, err
	}
	accountsEnabled := map[string]bool{}
	traceAccounts := map[string]bool{}
	active := func(user string) bool {
		if s.localDev {
			return true
		}
		if result, found := accountsEnabled[user]; found {
			return result
		}
		if s.product == nil {
			return false
		}
		accounts, e := s.product.LookupAccounts(ctx, "", []string{user}, 1)
		result := e == nil && len(accounts) == 1 && !accounts[0].Disabled && len(ns.Roles(user)) > 0
		if result {
			for _, role := range accounts[0].Roles {
				if strings.EqualFold(role, "admin") || strings.EqualFold(role, "session_trace") {
					traceAccounts[user] = true
				}
			}
		}
		accountsEnabled[user] = result
		return result
	}
	canUse := func(user string) bool {
		if s.localDev {
			return true
		}
		target := v.Target.Type + ":" + v.Target.ID.String()
		return active(user) && ns.Decide(user, target, "use").Allowed && checkResourceGraph(ns, graph, user, target) == nil
	}
	ownerScopes := func(user string) []string {
		scopes := []string{"read", "cancel"}
		if applicationEnabled && canUse(user) {
			scopes = append(scopes, "approve")
			scopes = append(scopes, "invoke", "interact")
		}
		return scopes
	}
	if strings.HasPrefix(v.Principal, "platform-user:") {
		user := strings.TrimPrefix(v.Principal, "platform-user:")
		if active(user) {
			humans[user] = ownerScopes(user)
		}
	}
	if v.ApplicationID != nil {
		app, e := s.store.Applications().Get(ctx, *v.ApplicationID)
		if e != nil {
			return nil, e
		}
		application = app
		applicationEnabled = applicationEnabled && app.Status == "active"
		if active(app.OwnerUserID) {
			humans[app.OwnerUserID] = ownerScopes(app.OwnerUserID)
			for _, member := range app.Members {
				if !active(member.UserID) {
					continue
				}
				scopes := []string{}
				if app.Allows(member.UserID, "read") {
					scopes = append(scopes, "read")
				}
				if app.Allows(member.UserID, "operate") {
					scopes = append(scopes, "cancel")
					if applicationEnabled && canUse(member.UserID) {
						scopes = append(scopes, "invoke", "interact")
					}
				}
				if applicationEnabled && app.Allows(member.UserID, "approve") {
					scopes = append(scopes, "approve")
				}
				humans[member.UserID] = scopes
			}
			for offset := 0; ; offset += 100 {
				rows, e := s.store.KV().Search(ctx, v.Tenant, applicationCredentialPath(app.ID), 100, offset)
				if e != nil {
					return nil, e
				}
				for _, row := range rows {
					var key sessionCredential
					if json.Unmarshal(row.Value, &key) != nil || key.Status != "active" || key.ExpiresAt != nil && !key.ExpiresAt.After(time.Now()) {
						continue
					}
					for _, target := range key.Targets {
						if target.Type == v.Target.Type && target.ID == v.Target.ID {
							if !applicationEnabled || !canUse(app.OwnerUserID) {
								allowed := []string{}
								for _, scope := range key.Scopes {
									if scope == "read" || scope == "cancel" {
										allowed = append(allowed, scope)
									}
								}
								key.Scopes = allowed
							}
							keys = append(keys, key)
							break
						}
					}
				}
				if len(rows) < 100 {
					break
				}
			}
			if v.SourceEndpointID != nil {
				credentials, e := s.store.Endpoints().ListCredentials(ctx, *v.SourceEndpointID)
				if e != nil {
					return nil, e
				}
				for _, credential := range credentials {
					if credential.ApplicationID != app.ID || credential.Status != model.EndpointCredentialActive || credential.ExpiresAt != nil && !credential.ExpiresAt.After(time.Now()) {
						continue
					}
					scopes, e := validateEndpointScopes(credential.Scopes)
					if e != nil {
						return nil, e
					}
					allowed := []string{}
					for _, scope := range scopes {
						if scope == "read" || scope == "cancel" || applicationEnabled && canUse(app.OwnerUserID) {
							allowed = append(allowed, scope)
						}
					}
					keys = append(keys, sessionCredential{ID: credential.ID, ApplicationID: app.ID, Hash: hex.EncodeToString(credential.SecretHash), Scopes: allowed, ExpiresAt: credential.ExpiresAt})
				}
			}
		}
	}
	if !applicationEnabled {
		// Resource ownership cannot bypass a disabled Application's execution boundary.
		for user, scopes := range humans {
			retained := []string{}
			for _, scope := range scopes {
				if scope == "read" || scope == "cancel" {
					retained = append(retained, scope)
				}
			}
			humans[user] = retained
		}
	}
	// Privileged reads remain conditional on current account roles and ordinary Session access.
	// A stale administrator JWT cannot restore a revoked trace grant.
	for user, scopes := range humans {
		if traceAccounts[user] && slices.Contains(scopes, "read") {
			humans[user] = append(scopes, "trace")
		}
	}
	scopeID, err := managedRuntimePolicyScope(v, contract)
	if err != nil {
		return nil, err
	}
	policy := gin.H{"scope_id": scopeID, "tenant": v.Tenant, "namespace": v.Namespace, "humans": humans, "credentials": keys, "application": application, "valid_until": time.Now().Add(time.Minute).UnixMilli(), "enabled": v.Status != "deleted", "input_schema": contract.Endpoint.InputSchema, "token_limit": json.RawMessage(contract.Endpoint.RateLimit)}
	out := gin.H{"managed": managed, "runtimeKind": s.publicSessionView(ctx, v)["runtimeKind"], "policy": policy, "view": s.publicSessionView(ctx, v)}
	if includeConfiguration && !managed {
		configuration, e := s.goRuntimeConfiguration(ctx, v, contract)
		if e != nil {
			return nil, e
		}
		out["resolved"] = configuration
	} else if includeConfiguration {
		owner, _, e := s.publicNativeSession(ctx, v, contract)
		if e != nil {
			return nil, e
		}
		resolved, e := s.product.ResolveManagedResourceSession(ctx, product.ManagedResourceConfiguration{
			SessionID: v.ID.String(), OwnerID: owner, AgentID: v.Target.ID.String(), AgentVersion: v.Target.Version,
			EnvironmentID: contract.EnvironmentID, Definition: contract.Definitions[v.Target.ID.String()], Options: contract.SessionOptions,
		})
		if e != nil {
			return nil, e
		}
		// Public and Harness identities are the same in the data-plane-owned runtime.
		resolved["session"].(gin.H)["id"] = v.ID.String()
		resolved["session"].(gin.H)["createdAt"] = v.CreatedAt.UnixMilli()
		resolved["session"].(gin.H)["updatedAt"] = v.UpdatedAt.UnixMilli()
		delete(resolved, "teamContext")
		if v.TaskAttemptID == nil {
			delete(resolved, "executionContext")
		} else {
			attempt, e := s.store.ExecutionAttempts().Get(ctx, *v.TaskAttemptID)
			if e != nil || attempt.SessionID != v.ID.String() || attempt.Tenant != v.Tenant || attempt.Namespace != v.Namespace || attempt.AgentID != v.Target.ID {
				return nil, fmt.Errorf("native task Session does not match its Attempt")
			}
			if _, ok := resolved["executionContext"]; !ok {
				return nil, fmt.Errorf("native task execution context is unavailable")
			}
			resolved["session"].(gin.H)["externalKey"] = "agent-task|" + attempt.AgentTaskID.String()
		}
		if v.RuntimeOwner != "dataplane" {
			e = s.store.WithSessionLock(ctx, "public-session:"+v.ID.String(), func(ctx context.Context) error {
				current, err := sessionapi.Get(ctx, s.store, v.Tenant, v.ID)
				if err != nil {
					return err
				}
				current.RuntimeOwner = "dataplane"
				return sessionapi.Save(ctx, s.store, current, false)
			})
			if e != nil {
				return nil, e
			}
		}
		out["resolved"] = resolved
	}
	return out, nil
}

// Equal resource, application and principal scopes share one finite-lived Portal policy.
// Session IDs, execution state, usage and projection expiry cannot fragment the refresh cache.
func managedRuntimePolicyScope(v *sessionapi.Session, contract *serviceapi.Contract) (string, error) {
	var options map[string]json.RawMessage
	if len(contract.SessionOptions) > 0 {
		if err := json.Unmarshal(contract.SessionOptions, &options); err != nil {
			return "", err
		}
	}
	authorizationOptions := map[string]json.RawMessage{}
	for _, key := range []string{"memoryStoreIds", "vaultIds", "agentOverrides"} {
		if value, ok := options[key]; ok {
			authorizationOptions[key] = value
		}
	}
	scope := map[string]any{
		"schema": 1, "runtime": "managed", "tenant": v.Tenant, "namespace": v.Namespace,
		"principal": v.Principal, "application": v.ApplicationID, "target": v.Target,
		"environment": contract.EnvironmentID, "input_schema": contract.Endpoint.InputSchema,
		"definitions": contract.Definitions, "mounts_and_overrides": authorizationOptions,
		"token_limit": json.RawMessage(contract.Endpoint.RateLimit), "enabled": v.Status != "deleted",
	}
	if v.SourceEndpointID != nil {
		scope["source_endpoint"] = v.SourceEndpointID
	}
	if v.Target.Type != "agent" || len(contract.Teams) > 0 || len(contract.Revisions) > 0 || !safelyManagedContract(contract) {
		scope["runtime"] = "orchestration"
		scope["policies"] = contract.Policies
		scope["teams"] = contract.Teams
		scope["revisions"] = contract.Revisions
	}
	raw, err := json.Marshal(scope)
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256(raw)
	return hex.EncodeToString(digest[:]), nil
}

// Summary is an eventually consistent management projection, not execution authority.
func (s *Server) managedRuntimeSummary(c *gin.Context) {
	id, err := uuid.Parse(c.Param("sessionId"))
	if err != nil {
		c.Status(400)
		return
	}
	var in struct {
		Tenant            string `json:"tenant"`
		Revision          int64  `json:"revision"`
		Status            string `json:"status"`
		RuntimeStatus     string `json:"runtimeStatus"`
		UpdatedAt         int64  `json:"updatedAt"`
		ApplicationTokens int64  `json:"applicationTokens"`
	}
	if c.ShouldBindJSON(&in) != nil || in.Tenant == "" || in.Revision < 0 || (in.Status != "active" && in.Status != "archived" && in.Status != "deleted") {
		c.Status(400)
		return
	}
	err = s.store.WithSessionLock(c, "public-session:"+id.String(), func(ctx context.Context) error {
		v, e := sessionapi.Get(ctx, s.store, in.Tenant, id)
		if e != nil {
			return e
		}
		contract, e := serviceapi.ReadContract(v.Contract)
		if e != nil {
			return e
		}
		if !s.publicSessionIsManaged(contract) {
			return fmt.Errorf("summary requires a Managed Session")
		}
		if v.ApplicationID != nil {
			if e = s.store.Applications().RecordRuntimeTokens(ctx, *v.ApplicationID, in.ApplicationTokens); e != nil {
				return e
			}
		}
		if v.RuntimeOwner == "dataplane" && in.Revision <= v.RuntimeRevision {
			return nil
		}
		v.RuntimeOwner = "dataplane"
		v.RuntimeRevision = in.Revision
		v.RuntimeStatus = in.RuntimeStatus
		v.Status = in.Status
		v.UpdatedAt = time.UnixMilli(in.UpdatedAt).UTC()
		return sessionapi.Save(ctx, s.store, v, false)
	})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.Status(204)
}

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package httpapi

import (
	"encoding/json"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// Check structural references before any handler writes them. User content,
// model parameters and expression inputs are not interpreted as resource IDs.
func (s *Server) authorizeNestedWorkReferences(c *gin.Context, body map[string]json.RawMessage) bool {
	a := accessFrom(c)
	if a == nil {
		return true
	}
	ctx := c.Request.Context()
	var visit func(map[string]json.RawMessage) bool
	visit = func(object map[string]json.RawMessage) bool {
		value := func(key string) string { var v string; _ = json.Unmarshal(object[key], &v); return v }
		fail := func() bool { s.accessFailure(c, store.ErrNotFound); return false }
		for _, key := range []string{"agentId", "agentRef", "leaderAgentId", "leaderAgentRef"} {
			if ref := value(key); ref != "" {
				if !a.Namespace.Decide(a.User, "agent:"+ref, "use").Allowed {
					return fail()
				}
				if _, e := s.activeAgentInScope(ctx, a.Namespace.Tenant, a.Namespace.Name, ref); e != nil {
					return fail()
				}
			}
		}
		teamRef := value("teamRef")
		if value("assigneeType") == "team" {
			teamRef = value("assigneeRef")
		}
		if teamRef != "" {
			if !a.Namespace.Decide(a.User, "team:"+teamRef, "use").Allowed {
				return fail()
			}
			id, e := uuid.Parse(teamRef)
			if e != nil {
				return fail()
			}
			t, e := s.store.Collaboration().GetTeam(ctx, id)
			if e != nil || t.Tenant != a.Namespace.Tenant || t.Namespace != a.Namespace.Name {
				return fail()
			}
		}
		if value("assigneeType") == "agent" && value("assigneeRef") != "" {
			if !a.Namespace.Decide(a.User, "agent:"+value("assigneeRef"), "use").Allowed {
				return fail()
			}
			if _, e := s.activeAgentInScope(ctx, a.Namespace.Tenant, a.Namespace.Name, value("assigneeRef")); e != nil {
				return fail()
			}
		}
		for _, key := range []string{"issueId", "parentIssueId", "rootIssueId"} {
			if raw := value(key); raw != "" {
				id, e := uuid.Parse(raw)
				if e != nil {
					return fail()
				}
				if _, e = s.canAccessIssue(ctx, a, id, c.Request.Method != "GET"); e != nil {
					return fail()
				}
			}
		}
		for _, key := range []string{"definitionId", "definitionRevisionId", "revisionId"} {
			if raw := value(key); raw != "" {
				id, e := uuid.Parse(raw)
				if e != nil {
					return fail()
				}
				if key == "definitionId" {
					if !a.Namespace.Decide(a.User, "workflow:"+raw, "use").Allowed {
						return fail()
					}
					d, e := s.store.Orchestration().GetDefinition(ctx, id)
					if e != nil || d.Tenant != a.Namespace.Tenant || d.Namespace != a.Namespace.Name {
						return fail()
					}
				} else {
					r, e := s.store.Orchestration().GetRevision(ctx, id)
					if e != nil || r.Tenant != a.Namespace.Tenant || r.Namespace != a.Namespace.Name {
						return fail()
					}
				}
			}
		}
		if raw := value("artifactId"); raw != "" {
			id, e := uuid.Parse(raw)
			if e != nil || !s.canAccessArtifact(ctx, a, id, false) {
				return fail()
			}
		}
		for _, key := range []string{"draftSpec", "spec", "execution", "actionConfig", "issue", "contextRefs", "runtimeCandidate"} {
			if raw := object[key]; len(raw) > 0 {
				var nested map[string]json.RawMessage
				if json.Unmarshal(raw, &nested) == nil && nested != nil {
					if !visit(nested) {
						return false
					}
				}
			}
		}
		for _, key := range []string{"nodes", "members", "attachments"} {
			var items []map[string]json.RawMessage
			_ = json.Unmarshal(object[key], &items)
			for _, item := range items {
				if !visit(item) {
					return false
				}
			}
		}
		return true
	}
	return visit(body)
}

// Membership never implies permission to inspect arbitrary integration input.
func (s *Server) automationDiagnosticsAllowed(c *gin.Context) bool {
	a := accessFrom(c)
	if a == nil {
		return true
	}
	if controlmodel.NamespaceAllows(a.Roles, "work.audit") && c.Request.Method == "GET" {
		return true
	}
	id, e := uuid.Parse(c.Param("automationId"))
	if e != nil {
		return false
	}
	rule, e := s.store.Collaboration().GetAutomation(c.Request.Context(), id)
	if e != nil || rule.Tenant != a.Namespace.Tenant || rule.Namespace != a.Namespace.Name {
		return false
	}
	return rule.CreatedBy.Type == controlmodel.ActorHuman && rule.CreatedBy.Ref == a.User
}

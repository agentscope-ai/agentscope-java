// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

const (
	workspaceWorkHub     = "work_hub"
	workspaceAgentCenter = "agent_center"
	workspaceOperations  = "operations"
)

var workHubResources = map[string]bool{
	"issues": true, "inbox": true, "approvals": true, "automations": true,
	"activity": true, "events": true,
}

var agentCenterResources = map[string]bool{
	"agents": true, "teams": true, "orchestration-definitions": true,
	"endpoints": true, "entrypoints": true, "channels": true, "playground": true,
}

func requestWorkspace(path string) string {
	rest := strings.TrimPrefix(path, "/api/v1/")
	resource, _, _ := strings.Cut(rest, "/")
	if workHubResources[resource] {
		return workspaceWorkHub
	}
	if agentCenterResources[resource] {
		return workspaceAgentCenter
	}
	switch resource {
	case "overview", "metrics", "agent-instances", "runtime-profiles", "runtime-pools", "runtime-hosts", "runtime-host-enrollments",
		"sessions", "orchestration-runs", "agent-tasks", "execution-attempts", "agent-runtime-policies",
		"runtime-bindings", "dataplanes", "audit", "dead-letters", "usage", "budgets":
		return workspaceOperations
	default:
		return ""
	}
}

func roleSet(c *gin.Context) map[string]bool {
	out := map[string]bool{}
	if raw, ok := c.Get("groups"); ok {
		if roles, ok := raw.([]string); ok {
			for _, role := range roles {
				out[strings.ToLower(strings.TrimSpace(role))] = true
			}
		}
	}
	return out
}

func workspaceAllowed(roles map[string]bool, workspace string, write bool) bool {
	if roles["admin"] {
		return true
	}
	switch workspace {
	case workspaceWorkHub:
		return roles["user"] || roles["agent_developer"] || roles["operator"]
	case workspaceAgentCenter:
		return roles["agent_developer"] || roles["operator"] && !write
	case workspaceOperations:
		return roles["operator"]
	default:
		return true
	}
}

// workspaceRBACMiddleware enforces the Work Hub and Agent Center boundaries
// exposed by the console. The internal operations capability remains the
// authorization boundary for cross-Agent activity and runtime infrastructure.
func (s *Server) workspaceRBACMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		if _, ok := c.Get(ctxInternalAuth); ok {
			c.Next()
			return
		}
		if _, ok := c.Get(ctxTaskAuth); ok {
			c.Next()
			return
		}
		if _, console := c.Get(ctxConsoleAuth); !console {
			c.Next()
			return
		}
		workspace := requestWorkspace(c.Request.URL.Path)
		write := c.Request.Method != http.MethodGet && c.Request.Method != http.MethodHead && c.Request.Method != http.MethodOptions
		// Agent Details reads runtime projections through their canonical APIs.
		// Stable agentId scope makes these Agent Center summaries, while the
		// unscoped fleet APIs remain Operations-only.
		if !write && strings.TrimSpace(c.Query("agentId")) != "" {
			resourcePath := strings.TrimPrefix(c.Request.URL.Path, "/api/v1/")
			switch {
			case resourcePath == "sessions", resourcePath == "metrics/agents",
				resourcePath == "metrics/tokens", resourcePath == "agent-tasks":
				workspace = workspaceAgentCenter
			case strings.HasPrefix(resourcePath, "sessions/") && s.agentScopedSessionReadAllowed(c):
				workspace = workspaceAgentCenter
			}
		}
		if !workspaceAllowed(roleSet(c), workspace, write) {
			c.AbortWithStatusJSON(http.StatusForbidden, ErrorResponse{Error: "role is not allowed to access this workspace"})
			return
		}
		c.Next()
	}
}

// agentScopedSessionReadAllowed verifies that an Agent Center session-detail
// request cannot use an arbitrary agentId query to escape the cross-Agent
// Operations boundary. Agent-scoped details are read-only; mutations continue
// to require the Operator/Admin workspace.
func (s *Server) agentScopedSessionReadAllowed(c *gin.Context) bool {
	if s.store == nil {
		return false
	}
	agentID, err := uuid.Parse(strings.TrimSpace(c.Query("agentId")))
	if err != nil {
		return false
	}
	resourcePath := strings.TrimPrefix(c.Request.URL.Path, "/api/v1/sessions/")
	sessionIDText, _, _ := strings.Cut(resourcePath, "/")
	sessionID, err := uuid.Parse(sessionIDText)
	if err != nil {
		return false
	}
	agent, err := s.store.AgentCatalog().GetAgent(c.Request.Context(), agentID)
	if err != nil {
		return false
	}
	session, err := s.store.Sessions().GetByID(c.Request.Context(), sessionID)
	if err != nil {
		return false
	}
	return session.AgentID == agent.ID && session.Tenant == agent.Tenant && session.Namespace == agent.Namespace
}

func (s *Server) navigationAccess(c *gin.Context) {
	roles := roleSet(c)
	areas := make([]string, 0, 2)
	for _, area := range []string{workspaceWorkHub, workspaceAgentCenter} {
		if workspaceAllowed(roles, area, false) {
			areas = append(areas, area)
		}
	}
	defaultArea := workspaceWorkHub
	if (roles["operator"] || roles["agent_developer"]) && !roles["admin"] {
		defaultArea = workspaceAgentCenter
	}
	c.JSON(http.StatusOK, gin.H{"areas": areas, "defaultArea": defaultArea})
}

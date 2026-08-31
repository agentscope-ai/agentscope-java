// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
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
	"agent-endpoints": true, "entrypoints": true, "channels": true,
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
	case "overview", "metrics", "agent-instances", "runtime-profiles", "runtime-pools", "runtime-hosts",
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

// workspaceRBACMiddleware enforces the same Work Hub / Agent Center /
// Operations boundary as the console. Kubernetes identities continue through
// SAR; static development tokens retain full access.
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
			switch strings.TrimPrefix(c.Request.URL.Path, "/api/v1/") {
			case "sessions", "metrics/agents", "metrics/tokens", "agent-tasks":
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

func (s *Server) navigationAccess(c *gin.Context) {
	roles := roleSet(c)
	areas := make([]string, 0, 3)
	for _, area := range []string{workspaceWorkHub, workspaceAgentCenter, workspaceOperations} {
		if workspaceAllowed(roles, area, false) {
			areas = append(areas, area)
		}
	}
	defaultArea := workspaceWorkHub
	if !workspaceAllowed(roles, defaultArea, false) {
		if roles["agent_developer"] {
			defaultArea = workspaceAgentCenter
		} else if roles["operator"] {
			defaultArea = workspaceOperations
		}
	}
	c.JSON(http.StatusOK, gin.H{"areas": areas, "defaultArea": defaultArea})
}

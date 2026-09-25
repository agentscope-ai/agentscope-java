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
	"encoding/json"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"slices"
)

// Check structural references before any handler writes them. User content,
// model parameters and expression inputs are not interpreted as resource IDs.
func (s *Server) authorizeNestedWorkReferences(c *gin.Context, body map[string]json.RawMessage) bool {
	a := accessFrom(c)
	if a == nil {
		return true
	}
	ctx := c.Request.Context()
	parentKind, parentID, _ := resourceRoute(c)
	mayUse := func(key string) bool {
		return a.Namespace.Decide(a.User, key, "use").Allowed || parentID != "" && slices.Contains(a.Namespace.Resources[key].Consumers, parentKind+":"+parentID)
	}
	var visit func(map[string]json.RawMessage) bool
	visit = func(object map[string]json.RawMessage) bool {
		deps := []string{}
		for field, kind := range map[string]string{"workspaceId": "workspace", "defaultEnvironmentId": "environment"} {
			var id string
			_ = json.Unmarshal(object[field], &id)
			if id != "" {
				deps = append(deps, kind+":"+id)
			}
		}
		for field, kind := range map[string]string{"defaultVaultIds": "vault", "defaultMemoryStoreIds": "memory"} {
			var ids []string
			_ = json.Unmarshal(object[field], &ids)
			for _, id := range ids {
				deps = append(deps, kind+":"+id)
			}
		}
		var mcp any
		if json.Unmarshal(object["mcpServers"], &mcp) == nil {
			deps = append(deps, product.ResourceVaultRefs(mcp)...)
		}
		if len(deps) > 0 {
			inventory, err := s.resourceInventory(ctx, a.Namespace)
			if err != nil {
				c.JSON(503, ErrorResponse{Error: "Unable to resolve dependencies"})
				return false
			}
			items := resourceMap(inventory)
			for _, dep := range deps {
				if _, exists := items[dep]; !exists || !mayUse(dep) {
					s.accessFailure(c, store.ErrNotFound)
					return false
				}
			}
		}
		value := func(key string) string { var v string; _ = json.Unmarshal(object[key], &v); return v }
		fail := func() bool { s.accessFailure(c, store.ErrNotFound); return false }
		for _, key := range []string{"agentId", "agentRef", "leaderAgentId", "leaderAgentRef"} {
			if ref := value(key); ref != "" {
				if !mayUse("agent:" + ref) {
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
			if !mayUse("team:" + teamRef) {
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
			if !mayUse("agent:" + value("assigneeRef")) {
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
					if !mayUse("workflow:" + raw) {
						return fail()
					}
					d, e := s.store.Orchestration().GetDefinition(ctx, id)
					if e != nil || d.Tenant != a.Namespace.Tenant || d.Namespace != a.Namespace.Name {
						return fail()
					}
				} else {
					r, e := s.store.Orchestration().GetRevision(ctx, id)
					if e != nil || r.Tenant != a.Namespace.Tenant || r.Namespace != a.Namespace.Name || !mayUse("workflow:"+r.DefinitionID.String()) {
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
		for _, key := range []string{"draftSpec", "spec", "definition", "execution", "actionConfig", "issue", "contextRefs", "runtimeCandidate"} {
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

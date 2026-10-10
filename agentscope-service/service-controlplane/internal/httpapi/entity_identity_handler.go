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
	"context"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

const maxEntityIdentityRefs = 200

type entityIdentityRef struct {
	Type string `json:"type"`
	Ref  string `json:"ref"`
}

type entityIdentity struct {
	Type      string `json:"type"`
	Ref       string `json:"ref"`
	Name      string `json:"name"`
	Secondary string `json:"secondary,omitempty"`
	Resolved  bool   `json:"resolved"`
}

type resolveEntityIdentitiesRequest struct {
	Refs []entityIdentityRef `json:"refs"`
}

func normalizeIdentityRef(input entityIdentityRef) entityIdentityRef {
	kind := strings.ToLower(strings.TrimSpace(input.Type))
	ref := strings.TrimSpace(input.Ref)
	// Older Endpoint-created collaboration records stored endpoint:<uuid> in
	// a human Actor ref. Understand that durable representation without
	// changing historical rows.
	if prefix, value, ok := strings.Cut(ref, ":"); ok && isIdentityType(prefix) {
		kind, ref = strings.ToLower(prefix), strings.TrimSpace(value)
	}
	switch kind {
	case "workflow", "orchestration":
		kind = "orchestration_definition"
	case "workflow_revision", "workflow-revision", "orchestration-revision":
		kind = "orchestration_revision"
	case "execution", "orchestration-run":
		kind = "orchestration_run"
	case "task", "agent-task":
		kind = "agent_task"
	case "attempt", "execution-attempt":
		kind = "execution_attempt"
	case "endpoint_job", "endpoint_source", "endpoint-invocation":
		kind = "endpoint_invocation"
	case "runtime-profile":
		kind = "runtime_profile"
	case "runtime-pool":
		kind = "runtime_pool"
	case "runtime-host":
		kind = "runtime_host"
	case "work-source":
		kind = "work_source"
	}
	return entityIdentityRef{Type: kind, Ref: ref}
}

func isIdentityType(value string) bool {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "agent", "team", "endpoint", "endpoint_invocation", "endpoint_job", "endpoint_source",
		"endpoint-invocation",
		"workflow", "workflow_revision", "orchestration", "orchestration_definition",
		"workflow-revision", "orchestration-revision", "orchestration_revision", "execution",
		"orchestration-run", "orchestration_run", "run", "task", "agent-task", "agent_task",
		"attempt", "execution-attempt", "execution_attempt", "issue", "work-source", "work_source",
		"runtime-profile", "runtime_profile", "runtime-pool", "runtime_pool", "runtime-host",
		"runtime_host", "human", "system", "automation":
		return true
	default:
		return false
	}
}

func (s *Server) resolveEntityIdentities(c *gin.Context) {
	tenant, namespace, ok := requireCollaborationScope(c)
	if !ok {
		return
	}
	var request resolveEntityIdentitiesRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if len(request.Refs) > maxEntityIdentityRefs {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: fmt.Sprintf("refs cannot contain more than %d items", maxEntityIdentityRefs)})
		return
	}
	items := make([]entityIdentity, 0, len(request.Refs))
	seen := make(map[string]struct{}, len(request.Refs))
	for _, raw := range request.Refs {
		input := normalizeIdentityRef(raw)
		if input.Type == "" || input.Ref == "" || len(input.Type) > 64 || len(input.Ref) > 1024 {
			continue
		}
		key := input.Type + "\x00" + input.Ref
		if _, exists := seen[key]; exists {
			continue
		}
		seen[key] = struct{}{}
		items = append(items, s.resolveEntityIdentity(c.Request.Context(), tenant, namespace, input))
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func unresolvedIdentity(input entityIdentityRef) entityIdentity {
	return entityIdentity{Type: input.Type, Ref: input.Ref, Name: input.Ref, Resolved: false}
}

func identityInScope(entityTenant, entityNamespace, tenant, namespace string) bool {
	return entityTenant == tenant && entityNamespace == namespace
}

func shortIdentity(ref string) string {
	if len(ref) <= 8 {
		return ref
	}
	return ref[:8]
}

func (s *Server) resolveEntityIdentity(ctx context.Context, tenant, namespace string, input entityIdentityRef) entityIdentity {
	if input.Type == "human" {
		return entityIdentity{Type: input.Type, Ref: input.Ref, Name: input.Ref, Resolved: true}
	}
	if input.Type == "system" {
		name := "AgentScope"
		if input.Ref != "" && input.Ref != "internal" && input.Ref != "system" {
			name = input.Ref
		}
		return entityIdentity{Type: input.Type, Ref: input.Ref, Name: name, Resolved: true}
	}
	if input.Type == "automation" {
		if id, err := uuid.Parse(input.Ref); err == nil {
			value, getErr := s.store.Collaboration().GetAutomation(ctx, id)
			if getErr == nil && identityInScope(value.Tenant, value.Namespace, tenant, namespace) {
				return entityIdentity{Type: input.Type, Ref: input.Ref, Name: value.Name, Secondary: "Automation", Resolved: true}
			}
		}
		return entityIdentity{Type: input.Type, Ref: input.Ref, Name: "Automation", Secondary: shortIdentity(input.Ref), Resolved: true}
	}
	id, err := uuid.Parse(input.Ref)
	if err != nil {
		return unresolvedIdentity(input)
	}
	result := unresolvedIdentity(input)
	switch input.Type {
	case "agent":
		value, err := s.store.AgentCatalog().GetAgent(ctx, id)
		if err == nil && identityInScope(value.Tenant, value.Namespace, tenant, namespace) {
			name := value.DisplayName
			if name == "" {
				name = value.AgentKey
			}
			result.Name, result.Secondary, result.Resolved = name, value.AgentKey, true
		}
	case "team":
		value, err := s.store.Collaboration().GetTeam(ctx, id)
		if err == nil && identityInScope(value.Tenant, value.Namespace, tenant, namespace) {
			result.Name, result.Resolved = value.Name, true
		}
	case "endpoint":
		if value, err := s.store.Endpoints().Get(ctx, id); err == nil && identityInScope(value.Tenant, value.Namespace, tenant, namespace) {
			result.Name, result.Secondary, result.Resolved = value.Name, value.Slug, true
		} else {
			// Some early Endpoint jobs used sourceType=endpoint while sourceRef
			// already held the invocation ID.
			result = s.resolveEndpointInvocationIdentity(ctx, tenant, namespace, input, id)
		}
	case "endpoint_invocation":
		result = s.resolveEndpointInvocationIdentity(ctx, tenant, namespace, input, id)
	case "orchestration_definition":
		value, err := s.store.Orchestration().GetDefinition(ctx, id)
		if err == nil && identityInScope(value.Tenant, value.Namespace, tenant, namespace) {
			result.Name, result.Resolved = value.Name, true
		}
	case "orchestration_revision":
		value, err := s.store.Orchestration().GetRevision(ctx, id)
		if err == nil && identityInScope(value.Tenant, value.Namespace, tenant, namespace) {
			if definition, getErr := s.store.Orchestration().GetDefinition(ctx, value.DefinitionID); getErr == nil && identityInScope(definition.Tenant, definition.Namespace, tenant, namespace) {
				result.Name, result.Secondary, result.Resolved = definition.Name, "Revision "+strconv.FormatInt(value.Revision, 10), true
			}
		}
	case "issue":
		value, err := s.store.Collaboration().GetIssue(ctx, id)
		if err == nil && identityInScope(value.Tenant, value.Namespace, tenant, namespace) {
			result.Name, result.Secondary, result.Resolved = value.Title, value.Identifier, true
		}
	case "agent_task":
		value, err := s.store.Collaboration().GetAgentTask(ctx, id)
		if err == nil && identityInScope(value.Tenant, value.Namespace, tenant, namespace) {
			result.Name, result.Secondary, result.Resolved = "Agent task "+shortIdentity(input.Ref), value.AgentRef, true
			if agentID, parseErr := uuid.Parse(value.AgentRef); parseErr == nil {
				if agent, getErr := s.store.AgentCatalog().GetAgent(ctx, agentID); getErr == nil && identityInScope(agent.Tenant, agent.Namespace, tenant, namespace) {
					result.Name = agent.DisplayName
					if result.Name == "" {
						result.Name = agent.AgentKey
					}
					result.Secondary = "Task " + shortIdentity(input.Ref)
				}
			}
		}
	case "orchestration_run", "run":
		value, err := s.store.Orchestration().GetRun(ctx, id)
		if err == nil && identityInScope(value.Tenant, value.Namespace, tenant, namespace) {
			result.Name, result.Secondary, result.Resolved = "Execution "+shortIdentity(input.Ref), string(value.State), true
		}
	case "execution_attempt":
		value, err := s.store.ExecutionAttempts().Get(ctx, id)
		if err == nil && identityInScope(value.Tenant, value.Namespace, tenant, namespace) {
			result.Name, result.Secondary, result.Resolved = "Attempt "+strconv.Itoa(int(value.Attempt)), string(value.State), true
		}
	case "runtime_profile":
		value, err := s.store.RuntimeRegistry().GetRuntimeProfileByID(ctx, id)
		if err == nil && identityInScope(value.Tenant, value.Namespace, tenant, namespace) {
			result.Name, result.Secondary, result.Resolved = value.Name, value.Provider, true
		}
	case "runtime_pool":
		value, err := s.store.RuntimeRegistry().GetRuntimePoolByID(ctx, id)
		if err == nil && identityInScope(value.Tenant, value.Namespace, tenant, namespace) {
			result.Name, result.Resolved = value.Name, true
		}
	case "runtime_host":
		value, err := s.store.RuntimeRegistry().GetRuntimeHost(ctx, id)
		if err == nil && identityInScope(value.Tenant, value.Namespace, tenant, namespace) {
			result.Name, result.Secondary, result.Resolved = value.HostKey, value.PoolName, true
		}
	case "work_source":
		value, err := s.store.WorkSources().GetWorkSource(ctx, id)
		if err == nil && identityInScope(value.Tenant, value.Namespace, tenant, namespace) {
			result.Name, result.Secondary, result.Resolved = value.Name, value.Kind, true
		}
	}
	return result
}

func (s *Server) resolveEndpointInvocationIdentity(ctx context.Context, tenant, namespace string, input entityIdentityRef, id uuid.UUID) entityIdentity {
	result := unresolvedIdentity(input)
	invocation, err := s.store.Endpoints().GetInvocation(ctx, id)
	if err != nil {
		return result
	}
	endpoint, err := s.store.Endpoints().Get(ctx, invocation.EndpointID)
	if err != nil || !identityInScope(endpoint.Tenant, endpoint.Namespace, tenant, namespace) {
		return result
	}
	result.Name = endpoint.Name
	result.Secondary = "Invocation " + shortIdentity(input.Ref)
	result.Resolved = true
	return result
}

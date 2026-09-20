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
	"errors"

	"github.com/gin-gonic/gin"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider/anthropiccli"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider/codex"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider/openclaw"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider/qoder"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider/qwenpaw"
)

func (s *Server) initializePortableDefinition(c *gin.Context) {
	id, ok := parseUUIDParam(c, "agentId")
	if !ok {
		return
	}
	agent, err := s.store.AgentCatalog().GetAgent(c.Request.Context(), id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if s.product == nil {
		c.JSON(503, ErrorResponse{Error: "definition store unavailable"})
		return
	}
	def, err := s.product.EnsureManagedDefinition(c.Request.Context(), agent.OwnerRef, id.String(), product.ManagedDefinitionInput{Name: agent.DisplayName, Description: agent.Description})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(200, gin.H{"definition": def})
}

func (s *Server) agentWorkspaceCapabilities(c *gin.Context) {
	id, ok := parseUUIDParam(c, "agentId")
	if !ok {
		return
	}
	agent, err := s.store.AgentCatalog().GetAgent(c.Request.Context(), id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	var definition *provider.AgentDefinition
	var raw map[string]any
	if s.product != nil {
		raw, err = s.product.RuntimeDefinition(c.Request.Context(), agent.OwnerRef, id.String())
		if err != nil && !errors.Is(err, product.ErrManagedDefinitionNotFound) {
			s.writeControlPlaneError(c, err)
			return
		}
		if err == nil {
			body, _ := json.Marshal(raw)
			if json.Unmarshal(body, &definition) != nil {
				c.JSON(500, ErrorResponse{Error: "invalid definition"})
				return
			}
		}
	}
	bindings, err := s.store.AgentCatalog().ListBindings(c.Request.Context(), id, false)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	items := []gin.H{}
	harness := provider.Capability{Supported: true, Mode: "harness"}
	for _, binding := range bindings {
		descriptor := provider.Descriptor{DisplayName: "Harness", Instructions: harness, Skills: harness, Tools: harness, MCP: harness, Subagents: harness}
		status := "platform-managed"
		switch binding.Kind {
		case controlmodel.DataPlaneExternalApplication:
			status = "application-managed"
			instances, e := s.store.RuntimeRegistry().ListAgentInstances(c.Request.Context(), agent.Tenant, agent.Namespace, id)
			if e != nil {
				s.writeControlPlaneError(c, e)
				return
			}
			for _, instance := range instances {
				if instance.BindingID != binding.ID || instance.Health != controlmodel.RuntimeHealthHealthy {
					continue
				}
				if controlmodel.ConsumesWorkspaceDefinition(instance.Capabilities) {
					status = "consumer-enabled"
				}
			}
			if status == "application-managed" {
				descriptor = provider.Descriptor{DisplayName: "External application"}
			}
		case controlmodel.DataPlaneHostedRuntime:
			status = "provider-adapted"
			var config controlmodel.HostedBindingConfiguration
			if json.Unmarshal(binding.Configuration, &config) != nil {
				continue
			}
			profile, e := s.store.RuntimeRegistry().GetRuntimeProfileByID(c.Request.Context(), config.RuntimeProfileID)
			if e != nil {
				s.writeControlPlaneError(c, e)
				return
			}
			switch profile.Provider {
			case "codex":
				descriptor = (&codex.Adapter{}).Descriptor()
			case "claude", "anthropic-cli":
				descriptor = (&anthropiccli.Adapter{}).Descriptor()
			case "qoder":
				descriptor = (&qoder.Adapter{}).Descriptor()
			case "qwenpaw":
				descriptor = (&qwenpaw.Adapter{}).Descriptor()
			case "openclaw":
				descriptor = (&openclaw.Adapter{}).Descriptor()
			default:
				descriptor = provider.Descriptor{DisplayName: profile.Provider}
			}
		}
		items = append(items, gin.H{"bindingId": binding.ID, "kind": binding.Kind, "runtime": descriptor.DisplayName, "status": status, "capabilities": provider.DefinitionCapabilities(definition, descriptor)})
	}
	out := gin.H{"bindings": items, "definitionVersion": raw["version"], "workspaceVersion": raw["workspaceVersion"], "definitionDigest": raw["definitionDigest"]}
	if s.product != nil {
		applications, e := s.product.WorkspaceApplications(c.Request.Context(), agent.OwnerRef, id.String())
		if e != nil {
			s.writeControlPlaneError(c, e)
			return
		}
		out["applications"] = applications
	}
	c.JSON(200, out)
}

func (s *Server) reportWorkspaceApplication(c *gin.Context) {
	task := c.MustGet(ctxTaskAuth).(*controlmodel.AgentTask)
	var request struct {
		Digest string `json:"digest"`
	}
	if c.ShouldBindJSON(&request) != nil || request.Digest == "" {
		c.JSON(400, ErrorResponse{Error: "digest is required"})
		return
	}
	var snapshot controlmodel.RuntimeDispatchSnapshot
	if json.Unmarshal(task.RuntimeBinding, &snapshot) != nil || len(snapshot.Definition) == 0 || task.CurrentAttemptID == nil {
		c.JSON(409, ErrorResponse{Error: "task has no frozen Workspace definition"})
		return
	}
	var definition map[string]any
	if json.Unmarshal(snapshot.Definition, &definition) != nil || definition["definitionDigest"] != request.Digest {
		c.JSON(409, ErrorResponse{Error: "applied definition does not match dispatch"})
		return
	}
	agent, err := s.store.AgentCatalog().GetAgent(c.Request.Context(), snapshot.Binding.AgentID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if s.product == nil {
		c.JSON(503, ErrorResponse{Error: "definition store unavailable"})
		return
	}
	err = s.product.RecordWorkspaceApplication(c.Request.Context(), agent.OwnerRef, agent.ID.String(), task.CurrentAttemptID.String(), request.Digest, definition["version"], definition["workspaceVersion"])
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(200, gin.H{"status": "applied", "digest": request.Digest})
}

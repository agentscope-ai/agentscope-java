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

package httpapi

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/types"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/asdp"
	"github.com/spring-ai-alibaba/aistio/internal/endpoints"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
)

// subagentInstance is one per-instance entry of the subagents response.
type subagentInstance struct {
	InstanceID string                `json:"instanceId"`
	Source     string                `json:"source"` // asdp | http
	ReportedAt string                `json:"reportedAt,omitempty"`
	Healthy    *bool                 `json:"healthy,omitempty"`
	Subagents  []prober.SubagentInfo `json:"subagents"`
}

// workspaceInstance is one per-instance entry of the workspaces response.
type workspaceInstance struct {
	InstanceID string                 `json:"instanceId"`
	Source     string                 `json:"source"` // asdp | http
	ReportedAt string                 `json:"reportedAt,omitempty"`
	Healthy    *bool                  `json:"healthy,omitempty"`
	Workspaces []prober.WorkspaceInfo `json:"workspaces"`
}

// listAgentSubagents handles GET /api/v1/agents/:name/subagents. It prefers
// the ASDP inventory registry (live connected instances) and falls back to
// probing the data plane over the HTTP contract.
func (s *Server) listAgentSubagents(c *gin.Context) {
	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	if s.asdpInventory != nil {
		if invs := s.asdpInventory.GetInventoriesForAgent(namespace, name); len(invs) > 0 {
			instances := make([]subagentInstance, 0, len(invs))
			for _, inv := range invs {
				instances = append(instances, subagentInstance{
					InstanceID: inv.InstanceID,
					Source:     "asdp",
					ReportedAt: inv.UpdatedAt.UTC().Format(time.RFC3339),
					Healthy:    asdpHealth(inv),
					Subagents:  asdpSubagentsToProber(inv.Report.GetSubagents()),
				})
			}
			c.JSON(http.StatusOK, gin.H{"agent": name, "namespace": namespace, "source": "asdp", "instances": instances})
			return
		}
	}

	if endpoint, caps, ok := s.registryInventoryEndpoint(name, namespace, v1alpha1.CapabilitySubagentInventory); ok {
		subs, err := s.prober.FetchSubagents(c.Request.Context(), endpoint)
		if err != nil {
			c.JSON(http.StatusBadGateway, ErrorResponse{Error: "failed to fetch subagents from data plane: " + err.Error()})
			return
		}
		_ = caps
		c.JSON(http.StatusOK, gin.H{
			"agent":     name,
			"namespace": namespace,
			"source":    "registry",
			"instances": []subagentInstance{{InstanceID: endpoint, Source: "http", Subagents: subs}},
		})
		return
	}

	if s.client == nil {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "agent not found or inventory unavailable"})
		return
	}

	agent, ok := s.getAgentForInventory(c, name, namespace, v1alpha1.CapabilitySubagentInventory)
	if !ok {
		return
	}
	endpoint, err := endpoints.ResolveAgentHTTP(c.Request.Context(), s.client, agent)
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "failed to resolve agent endpoint: " + err.Error()})
		return
	}
	subs, err := s.prober.FetchSubagents(c.Request.Context(), endpoint)
	if err != nil {
		c.JSON(http.StatusBadGateway, ErrorResponse{Error: "failed to fetch subagents from data plane: " + err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"agent":     name,
		"namespace": namespace,
		"source":    "http",
		"instances": []subagentInstance{{InstanceID: endpoint, Source: "http", Subagents: subs}},
	})
}

// listAgentWorkspaces handles GET /api/v1/agents/:name/workspaces. It prefers
// the ASDP inventory registry and falls back to the HTTP contract.
func (s *Server) listAgentWorkspaces(c *gin.Context) {
	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	if s.asdpInventory != nil {
		if invs := s.asdpInventory.GetInventoriesForAgent(namespace, name); len(invs) > 0 {
			instances := make([]workspaceInstance, 0, len(invs))
			for _, inv := range invs {
				instances = append(instances, workspaceInstance{
					InstanceID: inv.InstanceID,
					Source:     "asdp",
					ReportedAt: inv.UpdatedAt.UTC().Format(time.RFC3339),
					Healthy:    asdpHealth(inv),
					Workspaces: asdpWorkspacesToProber(inv.Report.GetWorkspaces()),
				})
			}
			c.JSON(http.StatusOK, gin.H{"agent": name, "namespace": namespace, "source": "asdp", "instances": instances})
			return
		}
	}

	if endpoint, caps, ok := s.registryInventoryEndpoint(name, namespace, v1alpha1.CapabilityWorkspaceInventory); ok {
		workspaces, err := s.prober.FetchWorkspaces(c.Request.Context(), endpoint)
		if err != nil {
			c.JSON(http.StatusBadGateway, ErrorResponse{Error: "failed to fetch workspaces from data plane: " + err.Error()})
			return
		}
		_ = caps
		c.JSON(http.StatusOK, gin.H{
			"agent":     name,
			"namespace": namespace,
			"source":    "registry",
			"instances": []workspaceInstance{{InstanceID: endpoint, Source: "http", Workspaces: workspaces}},
		})
		return
	}

	if s.client == nil {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "agent not found or inventory unavailable"})
		return
	}

	agent, ok := s.getAgentForInventory(c, name, namespace, v1alpha1.CapabilityWorkspaceInventory)
	if !ok {
		return
	}
	endpoint, err := endpoints.ResolveAgentHTTP(c.Request.Context(), s.client, agent)
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "failed to resolve agent endpoint: " + err.Error()})
		return
	}
	workspaces, err := s.prober.FetchWorkspaces(c.Request.Context(), endpoint)
	if err != nil {
		c.JSON(http.StatusBadGateway, ErrorResponse{Error: "failed to fetch workspaces from data plane: " + err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"agent":     name,
		"namespace": namespace,
		"source":    "http",
		"instances": []workspaceInstance{{InstanceID: endpoint, Source: "http", Workspaces: workspaces}},
	})
}

// registryInventoryEndpoint finds a healthy self-registered instance advertising capability.
func (s *Server) registryInventoryEndpoint(name, namespace, capability string) (endpoint string, caps []string, ok bool) {
	if s.registry == nil || s.prober == nil {
		return "", nil, false
	}
	for _, dp := range s.registry.ListByAgent(name, namespace) {
		if !dp.Healthy || dp.BaseURL == "" {
			continue
		}
		has := false
		for _, c := range dp.Capabilities {
			if c == capability {
				has = true
				break
			}
		}
		if !has {
			continue
		}
		return dp.BaseURL, dp.Capabilities, true
	}
	return "", nil, false
}

// getAgentForInventory loads the Agent for the HTTP fallback path, verifying
// the data plane advertises the required inventory capability.
func (s *Server) getAgentForInventory(c *gin.Context, name, namespace, capability string) (*v1alpha1.Agent, bool) {
	var agent v1alpha1.Agent
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: name, Namespace: namespace}, &agent); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "agent not found"})
		} else {
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		}
		return nil, false
	}
	if !agent.Status.DataPlaneInfo.HasCapability(capability) {
		c.JSON(http.StatusNotImplemented, ErrorResponse{Error: "data plane does not advertise the " + capability + " capability"})
		return nil, false
	}
	return &agent, true
}

// asdpHealth extracts the health flag from an inventory report, if present.
func asdpHealth(inv *asdp.InstanceInventory) *bool {
	if inv.Report == nil || inv.Report.GetHealth() == nil {
		return nil
	}
	h := inv.Report.GetHealth().GetHealthy()
	return &h
}

// asdpSubagentsToProber maps ASDP inventory subagents to the REST shape
// (identical to the HTTP contract response).
func asdpSubagentsToProber(in []*asdp.SubagentInfo) []prober.SubagentInfo {
	out := make([]prober.SubagentInfo, 0, len(in))
	for _, sa := range in {
		item := prober.SubagentInfo{
			Name:          sa.GetName(),
			Description:   sa.GetDescription(),
			Tools:         sa.GetTools(),
			WorkspaceMode: sa.GetWorkspaceMode(),
			URL:           sa.GetUrl(),
			InvokeCount:   sa.GetInvokeCount(),
		}
		if ms := sa.GetLastInvokedAt(); ms > 0 {
			item.LastInvokedAt = time.UnixMilli(ms).UTC().Format(time.RFC3339)
		}
		out = append(out, item)
	}
	return out
}

// asdpWorkspacesToProber maps ASDP inventory workspaces to the REST shape.
func asdpWorkspacesToProber(in []*asdp.WorkspaceInfo) []prober.WorkspaceInfo {
	out := make([]prober.WorkspaceInfo, 0, len(in))
	for _, ws := range in {
		out = append(out, prober.WorkspaceInfo{
			Path:      ws.GetPath(),
			Mode:      ws.GetMode(),
			SizeBytes: ws.GetSizeBytes(),
			OwnerRef:  ws.GetOwnerRef(),
		})
	}
	return out
}

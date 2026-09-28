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
	"encoding/json"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
)

type registerReq struct {
	Tenant        string   `json:"tenant"`
	AgentName     string   `json:"agentName"`
	Namespace     string   `json:"namespace"`
	InstanceID    string   `json:"instanceId"`
	BaseURL       string   `json:"baseUrl"`
	Runtime       string   `json:"runtime"`
	Framework     string   `json:"framework"`
	ContractLevel int32    `json:"contractLevel"`
	Capabilities  []string `json:"capabilities"`
	Source        string   `json:"source"`
}

func (s *Server) registerDataPlane(c *gin.Context) {
	c.JSON(http.StatusGone, ErrorResponse{Error: "use POST /api/v1/agent-registrations with agentKey and a registration credential"})
}

func (s *Server) heartbeatDataPlane(c *gin.Context) {
	if s.registry == nil || s.store == nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "data plane registry not enabled"})
		return
	}
	id, err := uuid.Parse(c.Param("instanceId"))
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid instanceId"})
		return
	}
	var req struct {
		Generation     int64    `json:"generation"`
		ActiveSessions int32    `json:"activeSessions,omitempty"`
		Capabilities   []string `json:"capabilities,omitempty"`
	}
	_ = c.ShouldBindJSON(&req)
	capabilities, _ := json.Marshal(req.Capabilities)
	if req.Capabilities == nil {
		capabilities = nil
	}
	instance, err := s.store.RuntimeRegistry().HeartbeatAgentInstance(c.Request.Context(), id, req.Generation, req.ActiveSessions, capabilities)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	_ = s.registry.Heartbeat(id.String())
	c.JSON(http.StatusOK, gin.H{"instanceId": id, "generation": instance.Generation, "status": "ok"})
}

func (s *Server) deleteDataPlane(c *gin.Context) {
	if s.registry == nil || s.store == nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "data plane registry not enabled"})
		return
	}
	id, err := uuid.Parse(c.Param("instanceId"))
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid instanceId"})
		return
	}
	var req struct {
		Generation int64 `json:"generation"`
	}
	_ = c.ShouldBindJSON(&req)
	if _, err := s.store.RuntimeRegistry().SetAgentInstanceHealth(c.Request.Context(), id, req.Generation, controlmodel.RuntimeHealthUnhealthy); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	_ = s.registry.Delete(id.String())
	invalidateOverviewCache()
	c.Status(http.StatusNoContent)
}

func (s *Server) listDataPlanes(c *gin.Context) {
	if s.registry == nil {
		c.JSON(http.StatusOK, gin.H{"dataplanes": []any{}})
		return
	}
	items := s.registry.List()
	tenant := c.DefaultQuery("tenant", "default")
	var tenantItems []*dataplane.Entry
	for _, e := range items {
		if a := accessFrom(c); a != nil && e.Namespace != a.Namespace.Name {
			continue
		}
		if e.Tenant == tenant {
			tenantItems = append(tenantItems, e)
		}
	}
	items = tenantItems
	if agent := c.Query("agent"); agent != "" {
		ns := c.DefaultQuery("namespace", defaultNamespace)
		var filtered []*dataplane.Entry
		for _, e := range items {
			if e.AgentName == agent && e.Namespace == ns {
				filtered = append(filtered, e)
			}
		}
		items = filtered
	}
	if items == nil {
		items = []*dataplane.Entry{}
	}
	c.JSON(http.StatusOK, gin.H{"dataplanes": items})
}

// listAgentsFromRegistry serves GET /api/v1/agents when no Kubernetes client
// is configured. Defaults to presence=live (at least one healthy instance).
func (s *Server) listAgentsFromRegistry(c *gin.Context) {
	presence, ok := parsePresence(c.DefaultQuery("presence", dataplane.PresenceLive))
	if !ok {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "presence must be live, offline, historical, or all"})
		return
	}
	if s.registry == nil && presence != dataplane.PresenceHistorical && presence != dataplane.PresenceAll {
		c.JSON(http.StatusOK, AgentListResponse{Items: []AgentSummary{}})
		return
	}

	nsFilter := c.Query("namespace")
	tenant := c.DefaultQuery("tenant", "default")
	_, _, registryKeys, summaries := registryAgentBuckets(s.registry, tenant)
	if summaries == nil {
		summaries = []dataplane.AgentSummary{}
	}

	items := make([]AgentSummary, 0)

	includeRegistry := func(want string) {
		filtered := dataplane.FilterAgentsByPresence(summaries, want)
		for _, a := range filtered {
			if nsFilter != "" && a.Namespace != nsFilter {
				continue
			}
			var active int32
			if s.store != nil {
				n, _ := s.store.Sessions().CountActive(c.Request.Context(), tenant, a.Name, a.Namespace)
				active = n
			}
			p := dataplane.ClassifyPresence(a.HealthyCount, a.InstanceCount)
			items = append(items, AgentSummary{
				Name:           a.Name,
				Namespace:      a.Namespace,
				Type:           "BYO",
				Runtime:        a.Runtime,
				DisplayName:    a.Name,
				Replicas:       a.Replicas,
				ActiveSessions: active,
				Presence:       p,
				HealthyCount:   a.HealthyCount,
				InstanceCount:  a.InstanceCount,
			})
		}
	}

	includeHistorical := func() {
		sessions := listSessionsForPresence(c.Request.Context(), s.store, tenant)
		hist := historicalAgentKeys(sessions, registryKeys)
		for key := range hist {
			ns, name := splitAgentKey(key)
			if nsFilter != "" && ns != nsFilter {
				continue
			}
			var active int32
			if s.store != nil {
				n, _ := s.store.Sessions().CountActive(c.Request.Context(), tenant, name, ns)
				active = n
			}
			items = append(items, AgentSummary{
				Name:           name,
				Namespace:      ns,
				Type:           "BYO",
				DisplayName:    name,
				Replicas:       "0/0",
				ActiveSessions: active,
				Presence:       dataplane.PresenceHistorical,
				HealthyCount:   0,
				InstanceCount:  0,
			})
		}
	}

	switch presence {
	case dataplane.PresenceLive:
		includeRegistry(dataplane.PresenceLive)
	case dataplane.PresenceOffline:
		includeRegistry(dataplane.PresenceOffline)
	case dataplane.PresenceHistorical:
		includeHistorical()
	case dataplane.PresenceAll:
		includeRegistry(dataplane.PresenceAll)
		includeHistorical()
	}

	c.JSON(http.StatusOK, AgentListResponse{Items: items})
}

func (s *Server) getAgentFromRegistry(c *gin.Context) {
	if s.registry == nil {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "agent not found"})
		return
	}
	name := c.Param("name")
	ns := c.DefaultQuery("namespace", defaultNamespace)
	tenant := c.DefaultQuery("tenant", "default")
	entries := s.registry.ListByAgent(tenant, name, ns)
	if len(entries) == 0 {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "agent not found"})
		return
	}
	sum := s.registry.AggregateAgents()
	var match *dataplane.AgentSummary
	for i := range sum {
		if sum[i].Tenant == tenant && sum[i].Name == name && sum[i].Namespace == ns {
			match = &sum[i]
			break
		}
	}
	if match == nil {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "agent not found"})
		return
	}
	var active int32
	if s.store != nil {
		active, _ = s.store.Sessions().CountActive(c.Request.Context(), tenant, name, ns)
	}
	var agentConfig map[string]interface{}
	for _, e := range entries {
		if e.AgentConfig != nil {
			agentConfig = e.AgentConfig
			break
		}
	}
	if agentConfig == nil && s.prober != nil {
		for _, e := range entries {
			if !e.Healthy || e.BaseURL == "" {
				continue
			}
			if info, err := s.prober.ProbeInfo(c.Request.Context(), e.BaseURL); err == nil && info != nil && info.AgentConfig != nil {
				agentConfig = probeAgentConfigToMap(info.AgentConfig)
				s.registry.Upsert(dataplane.Entry{
					Tenant:        e.Tenant,
					AgentName:     e.AgentName,
					Namespace:     e.Namespace,
					InstanceID:    e.InstanceID,
					BaseURL:       e.BaseURL,
					Runtime:       e.Runtime,
					Framework:     e.Framework,
					ContractLevel: e.ContractLevel,
					Capabilities:  e.Capabilities,
					AgentConfig:   agentConfig,
					Source:        e.Source,
				})
				break
			}
		}
	}
	resp := gin.H{
		"name":           match.Name,
		"namespace":      match.Namespace,
		"type":           "BYO",
		"runtime":        match.Runtime,
		"framework":      match.Framework,
		"replicas":       match.Replicas,
		"activeSessions": active,
		"contractLevel":  match.ContractLevel,
		"capabilities":   match.Capabilities,
		"instances":      entries,
		"source":         "registry",
	}
	if agentConfig != nil {
		resp["agentConfig"] = agentConfig
	}
	c.JSON(http.StatusOK, resp)
}

func probeAgentConfigToMap(cfg *prober.ProbeAgentConfig) map[string]interface{} {
	if cfg == nil {
		return nil
	}
	raw, err := json.Marshal(cfg)
	if err != nil {
		return nil
	}
	var out map[string]interface{}
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil
	}
	return out
}

// internalTokenMiddleware authenticates data-plane self-registration calls.
func (s *Server) internalTokenMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		if s.internalToken == "" {
			c.Next()
			return
		}
		tok := c.GetHeader("X-Builder-Internal-Token")
		if tok == "" || tok != s.internalToken {
			c.AbortWithStatusJSON(http.StatusUnauthorized, ErrorResponse{Error: "invalid internal token"})
			return
		}
		c.Next()
	}
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

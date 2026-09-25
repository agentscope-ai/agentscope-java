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
	"net/http"
	"sort"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type telemetryState string

const (
	telemetryAvailable     telemetryState = "available"
	telemetryPartial       telemetryState = "partial"
	telemetryNotReporting  telemetryState = "not_reporting"
	telemetryNotSupported  telemetryState = "not_supported"
	telemetryNotApplicable telemetryState = "not_applicable"
)

type agentReadiness struct {
	State           string    `json:"state"`
	Mode            string    `json:"mode"`
	Reason          string    `json:"reason"`
	ActiveBindingID uuid.UUID `json:"activeBindingId,omitempty"`
}

type agentDetailOverview struct {
	AgentID    uuid.UUID      `json:"agentId"`
	ObservedAt time.Time      `json:"observedAt"`
	Window     string         `json:"window"`
	Lifecycle  string         `json:"lifecycle"`
	Readiness  agentReadiness `json:"readiness"`
	Bindings   struct {
		Total        int `json:"total"`
		Enabled      int `json:"enabled"`
		Dispatchable int `json:"dispatchable"`
	} `json:"bindings"`
	Instances struct {
		Total          int    `json:"total"`
		Healthy        int    `json:"healthy"`
		Unhealthy      int    `json:"unhealthy"`
		Capacity       *int32 `json:"capacity"`
		ActiveSessions int32  `json:"activeSessions"`
		Available      *int32 `json:"availableCapacity"`
	} `json:"instances"`
	Sessions struct {
		Active          int            `json:"active"`
		Idle            int            `json:"idle"`
		Compressing     int            `json:"compressing"`
		History         int            `json:"history"`
		CreatedInWindow int            `json:"createdInWindow"`
		LastActiveAt    *time.Time     `json:"lastActiveAt,omitempty"`
		Status          telemetryState `json:"status"`
	} `json:"sessions"`
	Usage struct {
		TotalTokens *int64         `json:"totalTokens"`
		ErrorCount  *int32         `json:"errorCount"`
		Status      telemetryState `json:"status"`
	} `json:"usage"`
	Entrypoints struct {
		Total        int `json:"total"`
		Enabled      int `json:"enabled"`
		Conversation int `json:"conversation"`
		Jobs         int `json:"jobs"`
	} `json:"entrypoints"`
	Telemetry map[string]telemetryState `json:"telemetry"`
}

func (s *Server) getAgentDetailOverview(c *gin.Context) {
	agentID, ok := parseUUIDParam(c, "agentId")
	if !ok {
		return
	}
	agent, err := s.store.AgentCatalog().GetAgent(c, agentID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	window := parseAgentDetailWindow(c.Query("window"))
	since := time.Now().UTC().Add(-window)
	bindings, err := s.store.AgentCatalog().ListBindings(c, agentID, true)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	instances, err := s.store.RuntimeRegistry().ListAgentInstances(c, agent.Tenant, agent.Namespace, agentID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	overview := agentDetailOverview{AgentID: agentID, ObservedAt: time.Now().UTC(),
		Window: window.String(), Lifecycle: string(agent.Status), Telemetry: map[string]telemetryState{}}
	overview.Bindings.Total = len(bindings)
	for _, binding := range bindings {
		if binding.Enabled && binding.ArchivedAt == nil {
			overview.Bindings.Enabled++
		}
	}
	var totalCapacity, availableCapacity int32
	unlimitedCapacity := false
	for _, instance := range instances {
		overview.Instances.Total++
		if instance.Health == controlmodel.RuntimeHealthHealthy {
			overview.Instances.Healthy++
		} else {
			overview.Instances.Unhealthy++
		}
		overview.Instances.ActiveSessions += instance.ActiveSessions
		if instance.Capacity <= 0 {
			unlimitedCapacity = true
			continue
		}
		totalCapacity += instance.Capacity
		if instance.Capacity > instance.ActiveSessions {
			availableCapacity += instance.Capacity - instance.ActiveSessions
		}
	}
	if len(instances) > 0 && !unlimitedCapacity {
		overview.Instances.Capacity = &totalCapacity
		overview.Instances.Available = &availableCapacity
	}
	overview.Readiness, overview.Bindings.Dispatchable = s.inspectAgentReadiness(c, agent, bindings, instances)

	sessions, _ := s.store.Sessions().List(c, store.SessionFilter{Tenant: agent.Tenant, AgentID: agentID})
	for _, session := range sessions {
		switch strings.ToLower(session.Phase) {
		case store.SessionPhaseActive:
			overview.Sessions.Active++
		case store.SessionPhaseIdle:
			overview.Sessions.Idle++
		case store.SessionPhaseCompressing:
			overview.Sessions.Compressing++
		default:
			overview.Sessions.History++
		}
		if session.CreatedAt.After(since) {
			overview.Sessions.CreatedInWindow++
		}
		activity := session.UpdatedAt
		if session.LastActiveAt != nil {
			activity = *session.LastActiveAt
		}
		if overview.Sessions.LastActiveAt == nil || activity.After(*overview.Sessions.LastActiveAt) {
			value := activity
			overview.Sessions.LastActiveAt = &value
		}
	}
	if len(sessions) > 0 {
		overview.Sessions.Status = telemetryAvailable
	} else if overview.Instances.Healthy > 0 {
		overview.Sessions.Status = telemetryNotReporting
	} else if overview.Readiness.Mode == "on-demand" {
		overview.Sessions.Status = telemetryNotApplicable
	} else {
		overview.Sessions.Status = telemetryNotReporting
	}

	tokens, _ := s.store.Metrics().QueryTokenUsage(c, store.TokenFilter{Tenant: agent.Tenant, AgentID: agentID, Since: &since, Limit: 1})
	agentMetrics, _ := s.store.Metrics().QueryAgentMetrics(c, store.AgentMetricFilter{Tenant: agent.Tenant, AgentID: agentID, Since: &since, Limit: 1})
	if len(tokens) > 0 || len(agentMetrics) > 0 {
		total, _ := s.store.Metrics().SumTokenUsage(c, store.TokenFilter{Tenant: agent.Tenant, AgentID: agentID, Since: &since})
		errors, _ := s.store.Metrics().SumErrorCount(c, store.AgentMetricFilter{Tenant: agent.Tenant, AgentID: agentID, Since: &since})
		overview.Usage.TotalTokens, overview.Usage.ErrorCount = &total, &errors
		overview.Usage.Status = telemetryAvailable
	} else {
		overview.Usage.Status = telemetryNotReporting
	}

	endpoints, _ := s.store.Endpoints().List(c, agent.Tenant, agent.Namespace)
	for _, endpoint := range endpoints {
		if endpoint.TargetType != controlmodel.EndpointTargetAgent || endpoint.TargetRef != agentID {
			continue
		}
		overview.Entrypoints.Total++
		if endpoint.Status == controlmodel.EndpointPublished {
			overview.Entrypoints.Enabled++
		}
		if endpoint.InvocationMode == controlmodel.EndpointConversationMode {
			overview.Entrypoints.Conversation++
		} else if endpoint.InvocationMode == controlmodel.EndpointJobMode {
			overview.Entrypoints.Jobs++
		}
	}
	overview.Telemetry = telemetryCoverage(instances, overview.Readiness.Mode)
	c.JSON(http.StatusOK, overview)
}

func parseAgentDetailWindow(raw string) time.Duration {
	if duration, err := time.ParseDuration(raw); err == nil && duration >= time.Hour && duration <= 30*24*time.Hour {
		return duration
	}
	return 24 * time.Hour
}

func (s *Server) inspectAgentReadiness(ctx *gin.Context, agent *controlmodel.Agent,
	bindings []*controlmodel.AgentBinding, instances []*controlmodel.AgentInstance) (agentReadiness, int) {
	policy, err := s.store.Orchestration().GetRuntimePolicy(ctx, agent.Tenant, agent.Namespace, agent.ID.String())
	if err != nil {
		return inspectAgentReadinessCandidates(ctx, s, agent, bindings, instances, nil)
	}
	return inspectAgentReadinessCandidates(ctx, s, agent, bindings, instances, policy.Candidates)
}

func inspectAgentReadinessCandidates(ctx *gin.Context, s *Server, agent *controlmodel.Agent,
	bindings []*controlmodel.AgentBinding, instances []*controlmodel.AgentInstance,
	candidates []controlmodel.RuntimeBindingCandidate) (agentReadiness, int) {
	if agent.Status != controlmodel.AgentActive {
		return agentReadiness{State: "inactive", Mode: "online", Reason: "Agent lifecycle is not active"}, 0
	}
	enabled := map[uuid.UUID]*controlmodel.AgentBinding{}
	for _, binding := range bindings {
		if binding.Enabled && binding.ArchivedAt == nil {
			enabled[binding.ID] = binding
		}
	}
	if len(enabled) == 0 || len(candidates) == 0 {
		return agentReadiness{State: "unbound", Mode: "online", Reason: "No enabled runtime policy candidate"}, 0
	}
	dispatchable := 0
	selectedIndex := -1
	selectedMode := "online"
	selectedBinding := uuid.Nil
	unavailableReason := ""
	for index, candidate := range candidates {
		binding := enabled[candidate.Binding.BindingID]
		if binding == nil || binding.Kind != candidate.Binding.Kind {
			continue
		}
		available := false
		switch binding.Kind {
		case controlmodel.DataPlaneManaged:
			var cfg controlmodel.ManagedBindingConfiguration
			configurationValid := json.Unmarshal(binding.Configuration, &cfg) == nil &&
				cfg.OwnerRef != "" && cfg.ManagedDefinitionRef != ""
			if s.product != nil && configurationValid &&
				controlmodel.RuntimeSecurityMatches(binding.Kind, nil, candidate.SecurityConstraints) {
				validationErr := s.product.ValidateManagedRuntime(ctx, cfg.OwnerRef, cfg.ManagedDefinitionRef)
				available = validationErr == nil
				if validationErr != nil && unavailableReason == "" {
					unavailableReason = validationErr.Error()
				}
			}
		case controlmodel.DataPlaneExternalApplication:
			for _, instance := range instances {
				if instance.BindingID != binding.ID ||
					(candidate.Binding.InstanceSelector["instance"] != "" &&
						candidate.Binding.InstanceSelector["instance"] != instance.InstanceKey) {
					continue
				}
				if instance.Health == controlmodel.RuntimeHealthHealthy &&
					(instance.Capacity <= 0 || instance.ActiveSessions < instance.Capacity) &&
					controlmodel.JSONContains(instance.Capabilities, candidate.RequiredCapabilities) &&
					controlmodel.RuntimeSecurityMatches(binding.Kind, instance.Labels, candidate.SecurityConstraints) {
					available = true
					break
				}
			}
		case controlmodel.DataPlaneHostedRuntime:
			selectedMode = "on-demand"
			profile, profileErr := s.store.RuntimeRegistry().GetRuntimeProfileByID(ctx, candidate.Binding.RuntimeProfileID)
			if profileErr == nil && profile.Tenant == agent.Tenant && profile.Namespace == agent.Namespace {
				if pool, poolErr := s.store.RuntimeRegistry().GetRuntimePoolByID(ctx, candidate.Binding.RuntimePoolID); poolErr == nil &&
					pool.Tenant == agent.Tenant && pool.Namespace == agent.Namespace {
					hosts, _ := s.store.RuntimeRegistry().ListRuntimeHosts(ctx, agent.Tenant, agent.Namespace, pool.Name, controlmodel.RuntimeHostOnline)
					for _, host := range hosts {
						if (host.Capacity <= 0 || host.Active < host.Capacity) &&
							controlmodel.JSONContains(host.Capabilities, candidate.RequiredCapabilities) &&
							controlmodel.RuntimeSecurityMatches(binding.Kind, host.Labels, candidate.SecurityConstraints) {
							available = true
							break
						}
					}
				}
			}
		}
		if available {
			dispatchable++
			if selectedIndex < 0 {
				selectedIndex, selectedBinding = index, binding.ID
				if binding.Kind != controlmodel.DataPlaneHostedRuntime {
					selectedMode = "online"
				}
			}
		}
	}
	if selectedIndex < 0 {
		if unavailableReason != "" {
			return agentReadiness{State: "unavailable", Mode: selectedMode, Reason: unavailableReason}, 0
		}
		return agentReadiness{State: "unavailable", Mode: selectedMode, Reason: "No runtime candidate currently has capacity"}, 0
	}
	if selectedIndex > 0 {
		return agentReadiness{State: "degraded", Mode: selectedMode, Reason: "Primary candidate is unavailable; fallback is active", ActiveBindingID: selectedBinding}, dispatchable
	}
	return agentReadiness{State: "ready", Mode: selectedMode, Reason: "Primary runtime candidate is dispatchable", ActiveBindingID: selectedBinding}, dispatchable
}

func telemetryCoverage(instances []*controlmodel.AgentInstance, mode string) map[string]telemetryState {
	capabilities := []string{"session-reporting", "event-reporting", "context-query", "message-query",
		"subagent-inventory", "workspace-inventory", "agent-task"}
	result := make(map[string]telemetryState, len(capabilities))
	healthy := 0
	counts := map[string]int{}
	for _, instance := range instances {
		if instance.Health != controlmodel.RuntimeHealthHealthy {
			continue
		}
		healthy++
		for _, capability := range decodeCapabilities(instance.Capabilities) {
			counts[capability]++
		}
	}
	for _, capability := range capabilities {
		switch {
		case healthy == 0 && mode == "on-demand":
			result[capability] = telemetryNotApplicable
		case healthy == 0:
			result[capability] = telemetryNotReporting
		case counts[capability] == 0:
			result[capability] = telemetryNotSupported
		case counts[capability] < healthy:
			result[capability] = telemetryPartial
		default:
			result[capability] = telemetryAvailable
		}
	}
	return result
}

func decodeCapabilities(raw json.RawMessage) []string {
	var list []string
	if json.Unmarshal(raw, &list) == nil {
		return list
	}
	var object map[string]any
	if json.Unmarshal(raw, &object) == nil {
		for key, value := range object {
			if enabled, ok := value.(bool); !ok || enabled {
				list = append(list, key)
			}
		}
	}
	sort.Strings(list)
	return list
}

func (s *Server) getAgentRuntimeInventory(c *gin.Context) {
	agentID, ok := parseUUIDParam(c, "agentId")
	if !ok {
		return
	}
	agent, err := s.store.AgentCatalog().GetAgent(c, agentID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if s.asdpInventory == nil {
		c.JSON(http.StatusOK, gin.H{"status": telemetryNotReporting, "items": []any{}})
		return
	}
	items := make([]gin.H, 0)
	for _, inventory := range s.asdpInventory.GetInventoriesForAgent(agent.Tenant, agent.Namespace, agent.AgentKey) {
		if inventory.AgentID != agentID.String() {
			continue
		}
		healthy := false
		activeSessions := int32(0)
		if health := inventory.Report.GetHealth(); health != nil {
			healthy, activeSessions = health.GetHealthy(), health.GetActiveSessions()
		}
		items = append(items, gin.H{"agentId": inventory.AgentID, "bindingId": inventory.BindingID,
			"instanceKey": inventory.InstanceID, "generation": inventory.Generation, "reportedAt": inventory.UpdatedAt,
			"healthy": healthy, "activeSessions": activeSessions,
			"subagents":  asdpSubagentsToProber(inventory.Report.GetSubagents()),
			"workspaces": asdpWorkspacesToProber(inventory.Report.GetWorkspaces())})
	}
	status := telemetryNotReporting
	if len(items) > 0 {
		status = telemetryAvailable
	}
	c.JSON(http.StatusOK, gin.H{"status": status, "items": items})
}

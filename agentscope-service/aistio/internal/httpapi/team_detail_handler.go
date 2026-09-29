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
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type teamMemberReadiness struct {
	AgentID   string         `json:"agentId"`
	Role      string         `json:"role"`
	Leader    bool           `json:"leader"`
	Lifecycle string         `json:"lifecycle"`
	Readiness agentReadiness `json:"readiness"`
}

type teamOverviewAgentRef struct {
	agentID              string
	role                 string
	leader               bool
	runtimeBindingPolicy json.RawMessage
}

type teamDetailOverview struct {
	TeamID     uuid.UUID             `json:"teamId"`
	ObservedAt time.Time             `json:"observedAt"`
	Readiness  string                `json:"readiness"`
	Reason     string                `json:"reason"`
	Members    []teamMemberReadiness `json:"members"`
	Runs       struct {
		Total       int `json:"total"`
		ActiveTasks int `json:"activeTasks"`
	} `json:"runs"`
	Endpoints struct {
		Total     int `json:"total"`
		Published int `json:"published"`
	} `json:"endpoints"`
}

func (s *Server) getCollaborationTeamOverview(c *gin.Context) {
	teamID, ok := parseUUIDParam(c, "teamId")
	if !ok {
		return
	}
	team, err := s.store.Collaboration().GetTeam(c, teamID)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	overview := teamDetailOverview{TeamID: team.ID, ObservedAt: time.Now().UTC(), Readiness: "ready",
		Reason: "Leader and all Team members are dispatchable"}
	if team.Status != controlmodel.TeamActive {
		overview.Readiness, overview.Reason = "unavailable", "Team lifecycle is not active"
	}
	refs := []teamOverviewAgentRef{{agentID: team.LeaderAgentRef, role: "leader", leader: true}}
	for _, member := range team.Members {
		refs = append(refs, teamOverviewAgentRef{agentID: member.AgentRef, role: member.Role,
			runtimeBindingPolicy: member.RuntimeBindingPolicy})
	}
	for _, ref := range refs {
		item := teamMemberReadiness{AgentID: ref.agentID, Role: ref.role, Leader: ref.leader,
			Readiness: agentReadiness{State: "unavailable", Reason: "Agent is unavailable"}}
		agentID, parseErr := uuid.Parse(ref.agentID)
		if parseErr == nil {
			agent, agentErr := s.store.AgentCatalog().GetAgent(c, agentID)
			if agentErr == nil && agent.Tenant == team.Tenant && agent.Namespace == team.Namespace {
				item.Lifecycle = string(agent.Status)
				bindings, _ := s.store.AgentCatalog().ListBindings(c, agentID, true)
				instances, _ := s.store.RuntimeRegistry().ListAgentInstances(c, team.Tenant, team.Namespace, agentID)
				var memberPolicy controlmodel.RuntimeBindingPolicy
				if len(ref.runtimeBindingPolicy) > 0 && json.Unmarshal(ref.runtimeBindingPolicy, &memberPolicy) == nil && len(memberPolicy.Candidates) > 0 {
					item.Readiness, _ = inspectAgentReadinessCandidates(c, s, agent, bindings, instances, memberPolicy.Candidates)
				} else {
					item.Readiness, _ = s.inspectAgentReadiness(c, agent, bindings, instances)
				}
			}
		}
		overview.Members = append(overview.Members, item)
		if item.Readiness.State != "ready" {
			if ref.leader && item.Readiness.State != "degraded" {
				overview.Readiness, overview.Reason = "unavailable", "Team leader is not dispatchable"
			} else if overview.Readiness == "ready" {
				overview.Readiness, overview.Reason = "degraded", "One or more Team members are not fully dispatchable"
			}
		}
	}
	tasks, _ := s.store.Collaboration().ListAgentTasks(c, store.AgentTaskFilter{
		Tenant: team.Tenant, Namespace: team.Namespace, TeamID: team.ID, Limit: 500,
	})
	runs := map[uuid.UUID]bool{}
	for _, task := range tasks {
		runs[task.OrchestrationRunID] = true
		if !controlmodel.IsAgentTaskTerminal(task.Status) {
			overview.Runs.ActiveTasks++
		}
	}
	overview.Runs.Total = len(runs)
	endpoints, _ := s.store.Endpoints().List(c, team.Tenant, team.Namespace)
	for _, endpoint := range endpoints {
		if endpoint.TargetType != controlmodel.EndpointTargetTeam || endpoint.TargetRef != team.ID {
			continue
		}
		overview.Endpoints.Total++
		if endpoint.Status == controlmodel.EndpointPublished {
			overview.Endpoints.Published++
		}
	}
	c.JSON(http.StatusOK, gin.H{"team": team, "overview": overview})
}

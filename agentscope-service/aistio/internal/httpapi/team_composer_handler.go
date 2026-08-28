// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"encoding/json"
	"net/http"
	"sort"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type teamRequirements struct {
	Capabilities        []string          `json:"capabilities"`
	Labels              map[string]string `json:"labels"`
	MaxMembers          int               `json:"maxMembers"`
	MaxBudgetMicros     int64             `json:"maxBudgetMicros,omitempty"`
	SecurityConstraints json.RawMessage   `json:"securityConstraints,omitempty"`
}

func jsonStringSet(raw json.RawMessage) map[string]bool {
	out := map[string]bool{}
	var list []string
	if json.Unmarshal(raw, &list) == nil {
		for _, v := range list {
			out[v] = true
		}
		return out
	}
	var values map[string]any
	if json.Unmarshal(raw, &values) == nil {
		for k, v := range values {
			if b, ok := v.(bool); !ok || b {
				out[k] = true
			}
		}
	}
	return out
}
func jsonLabels(raw json.RawMessage) map[string]string {
	out := map[string]string{}
	_ = json.Unmarshal(raw, &out)
	return out
}

func (s *Server) createTeamProposal(c *gin.Context) {
	issueID, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	issue, err := s.store.Collaboration().GetIssue(c, issueID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	var req teamRequirements
	if err = c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if req.MaxMembers <= 0 {
		req.MaxMembers = 3
	}
	if req.MaxMembers > 20 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "maxMembers exceeds 20"})
		return
	}
	agents, err := s.store.AgentCatalog().ListAgents(c, store.AgentFilter{Tenant: issue.Tenant, Namespace: issue.Namespace, Status: controlmodel.AgentActive, Limit: 500})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	members := []controlmodel.TeamProposalMember{}
	for _, agent := range agents {
		caps := jsonStringSet(agent.Capabilities)
		labels := jsonLabels(agent.Labels)
		score := 0
		reasons := []string{}
		matched := true
		for _, required := range req.Capabilities {
			if !caps[required] {
				matched = false
				break
			}
			score += 10
			reasons = append(reasons, "capability:"+required)
		}
		if !matched {
			continue
		}
		for k, v := range req.Labels {
			if labels[k] != v {
				matched = false
				break
			}
			score += 3
			reasons = append(reasons, "label:"+k)
		}
		if !matched {
			continue
		}
		bindings, e := s.store.AgentCatalog().ListBindings(c, agent.ID, false)
		if e != nil || len(bindings) == 0 {
			continue
		}
		enabled := false
		for _, binding := range bindings {
			if binding.Enabled && binding.ArchivedAt == nil {
				enabled = true
				break
			}
		}
		if !enabled {
			continue
		}
		members = append(members, controlmodel.TeamProposalMember{AgentID: agent.ID, Score: score, Reasons: reasons})
	}
	sort.Slice(members, func(i, j int) bool {
		if members[i].Score == members[j].Score {
			return members[i].AgentID.String() < members[j].AgentID.String()
		}
		return members[i].Score > members[j].Score
	})
	if len(members) > req.MaxMembers {
		members = members[:req.MaxMembers]
	}
	if len(members) == 0 {
		c.JSON(http.StatusUnprocessableEntity, ErrorResponse{Error: "no active Agent satisfies the proposal constraints"})
		return
	}
	for i := range members {
		members[i].Role = "worker"
		if i == 0 {
			members[i].Role = "leader"
		}
	}
	raw, _ := json.Marshal(req)
	proposal, err := s.store.TeamProposals().Create(c, &controlmodel.TeamProposal{IssueID: issue.ID, Tenant: issue.Tenant, Namespace: issue.Namespace, Requirements: raw, Members: members})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"proposal": proposal, "started": false})
}

func (s *Server) confirmTeamProposal(c *gin.Context) {
	issueID, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	proposalID, ok := parseUUIDParam(c, "proposalId")
	if !ok {
		return
	}
	var req struct {
		Version int64 `json:"version"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Version == 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "version is required"})
		return
	}
	proposal, err := s.store.TeamProposals().Get(c, proposalID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if proposal.IssueID != issueID {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "proposal does not belong to Issue"})
		return
	}
	runID := uuid.NewSHA1(proposal.ID, []byte("adaptive-run"))
	actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "team-proposal-confirmation"}
	run, err := s.store.Orchestration().CreateRun(c, &controlmodel.OrchestrationRun{ID: runID, Tenant: proposal.Tenant, Namespace: proposal.Namespace, RootIssueID: issueID, Mode: controlmodel.RunModeAdaptive, TriggerType: "team_proposal", TriggerRef: proposal.ID.String(), IdempotencyKey: "team-proposal:" + proposal.ID.String(), State: controlmodel.RunRunning, CreatedBy: actor})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	snapshot, _ := json.Marshal(gin.H{"origin": "dynamic", "proposalId": proposal.ID, "members": proposal.Members, "requirements": json.RawMessage(proposal.Requirements)})
	if _, err = s.store.Orchestration().PutTeamSnapshot(c, &controlmodel.RunTeamSnapshot{RunID: run.ID, TeamID: proposal.ID, Tenant: proposal.Tenant, Namespace: proposal.Namespace, Snapshot: snapshot}); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	for i, member := range proposal.Members {
		nodeID := uuid.NewSHA1(run.ID, []byte(member.AgentID.String()))
		node, createErr := s.store.Orchestration().CreateNode(c, &controlmodel.RunNode{ID: nodeID, RunID: run.ID, Tenant: proposal.Tenant, Namespace: proposal.Namespace, NodeKey: "agent-" + member.AgentID.String(), Type: controlmodel.RunNodeAgent, Role: member.Role, IssueID: &issueID, State: controlmodel.RunNodeReady, Iteration: 1})
		if createErr == store.ErrConflict {
			node, _ = s.store.Orchestration().GetNode(c, nodeID)
		} else if createErr != nil {
			s.writeControlPlaneError(c, createErr)
			return
		}
		if node != nil {
			_, _ = s.store.Collaboration().CreateRunAgentTask(c, store.RunTaskRequest{RunID: run.ID, NodeID: node.ID, IssueID: issueID, AgentRef: member.AgentID.String(), TeamID: &proposal.ID, TeamRole: member.Role, Leader: i == 0, Originator: actor})
		}
	}
	confirmed, err := s.store.TeamProposals().Confirm(c, proposal.ID, req.Version, run.ID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"proposal": confirmed, "run": run, "teamSnapshotOrigin": "dynamic"})
}

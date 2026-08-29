// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"context"
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
	AutoConfirm         bool              `json:"autoConfirm,omitempty"`
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
	if req.AutoConfirm {
		confirmed, run, confirmErr := s.confirmTeamProposalInternal(c.Request.Context(), proposal, collaborationActor(c, s))
		if confirmErr != nil {
			s.writeControlPlaneError(c, confirmErr)
			return
		}
		c.JSON(http.StatusCreated, gin.H{"proposal": confirmed, "run": run, "started": true, "teamSnapshotOrigin": "dynamic"})
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
	if proposal.Version != req.Version {
		s.writeControlPlaneError(c, store.ErrConflict)
		return
	}
	confirmed, run, err := s.confirmTeamProposalInternal(c.Request.Context(), proposal, collaborationActor(c, s))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"proposal": confirmed, "run": run, "teamSnapshotOrigin": "dynamic"})
}

func (s *Server) confirmTeamProposalInternal(ctx context.Context, proposal *controlmodel.TeamProposal, actor controlmodel.Actor) (*controlmodel.TeamProposal, *controlmodel.OrchestrationRun, error) {
	if proposal.Status == controlmodel.TeamProposalConfirmed && proposal.RunID != nil {
		run, err := s.store.Orchestration().GetRun(ctx, *proposal.RunID)
		return proposal, run, err
	}
	runID := uuid.NewSHA1(proposal.ID, []byte("adaptive-run"))
	run, err := s.store.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{ID: runID, Tenant: proposal.Tenant, Namespace: proposal.Namespace, RootIssueID: proposal.IssueID, Mode: controlmodel.RunModeAdaptive, TriggerType: "team_proposal", TriggerRef: proposal.ID.String(), IdempotencyKey: "team-proposal:" + proposal.ID.String(), State: controlmodel.RunRunning, CreatedBy: actor})
	if err == store.ErrConflict {
		run, err = s.store.Orchestration().GetRun(ctx, runID)
	}
	if err != nil {
		return nil, nil, err
	}
	snapshot, _ := json.Marshal(gin.H{"origin": "dynamic", "proposalId": proposal.ID, "members": proposal.Members, "requirements": json.RawMessage(proposal.Requirements)})
	if _, err = s.store.Orchestration().PutTeamSnapshot(ctx, &controlmodel.RunTeamSnapshot{RunID: run.ID, TeamID: proposal.ID, Tenant: proposal.Tenant, Namespace: proposal.Namespace, Snapshot: snapshot}); err != nil {
		return nil, nil, err
	}
	for i, member := range proposal.Members {
		nodeID := uuid.NewSHA1(run.ID, []byte(member.AgentID.String()))
		node, createErr := s.store.Orchestration().CreateNode(ctx, &controlmodel.RunNode{ID: nodeID, RunID: run.ID, Tenant: proposal.Tenant, Namespace: proposal.Namespace, NodeKey: "agent-" + member.AgentID.String(), Type: controlmodel.RunNodeAgent, Role: member.Role, IssueID: &proposal.IssueID, State: controlmodel.RunNodeReady, Iteration: 1})
		if createErr == store.ErrConflict {
			node, _ = s.store.Orchestration().GetNode(ctx, nodeID)
		} else if createErr != nil {
			return nil, nil, createErr
		}
		if node != nil {
			existing, listErr := s.store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{RunID: run.ID, NodeID: node.ID, AgentRef: member.AgentID.String(), Limit: 1})
			if listErr != nil {
				return nil, nil, listErr
			}
			if len(existing) == 0 {
				if _, createErr = s.store.Collaboration().CreateRunAgentTask(ctx, store.RunTaskRequest{RunID: run.ID, NodeID: node.ID, IssueID: proposal.IssueID, AgentRef: member.AgentID.String(), TeamID: &proposal.ID, TeamRole: member.Role, Leader: i == 0, Originator: actor}); createErr != nil {
					return nil, nil, createErr
				}
			}
		}
	}
	confirmed, err := s.store.TeamProposals().Confirm(ctx, proposal.ID, proposal.Version, run.ID)
	if err != nil {
		return nil, nil, err
	}
	for _, task := range proposal.Members {
		items, _ := s.store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{RunID: run.ID, AgentRef: task.AgentID.String(), Status: controlmodel.AgentTaskQueued, Limit: 10})
		for _, item := range items {
			_ = s.DispatchAgentTask(ctx, item.ID)
		}
	}
	return confirmed, run, nil
}

func (s *Server) saveTeamProposalAsTeam(c *gin.Context) {
	proposalID, ok := parseUUIDParam(c, "proposalId")
	if !ok {
		return
	}
	proposal, err := s.store.TeamProposals().Get(c, proposalID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	var req struct {
		Name        string                  `json:"name"`
		Description string                  `json:"description,omitempty"`
		Policy      controlmodel.TeamPolicy `json:"policy,omitempty"`
	}
	if err = c.ShouldBindJSON(&req); err != nil || req.Name == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "name is required"})
		return
	}
	if len(proposal.Members) == 0 {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "proposal has no members"})
		return
	}
	team, err := s.store.Collaboration().CreateTeam(c, &controlmodel.CollaborationTeam{Tenant: proposal.Tenant,
		Namespace: proposal.Namespace, Name: req.Name, Description: req.Description,
		LeaderAgentRef: proposal.Members[0].AgentID.String(), Policy: req.Policy})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	for _, member := range proposal.Members[1:] {
		if _, err = s.store.Collaboration().AddTeamMember(c, &controlmodel.CollaborationTeamMember{TeamID: team.ID,
			Tenant: team.Tenant, Namespace: team.Namespace, AgentRef: member.AgentID.String(), Role: member.Role}); err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
	}
	team, _ = s.store.Collaboration().GetTeam(c, team.ID)
	c.JSON(http.StatusCreated, gin.H{"team": team, "origin": "dynamic", "proposalId": proposal.ID})
}

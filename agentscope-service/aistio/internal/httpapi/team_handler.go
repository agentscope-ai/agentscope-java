package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/team"
)

func (s *Server) requireTeamStores(c *gin.Context) bool {
	if s.store == nil || s.taskStore == nil || s.messageRouter == nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "team store not configured"})
		return false
	}
	return true
}

func (s *Server) createTeam(c *gin.Context) {
	if !s.requireTeamStores(c) {
		return
	}
	var req TeamCreateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if req.Name == "" || req.Objective == "" || req.Lead.AgentRef == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "name, objective, lead.agentRef required"})
		return
	}

	namespace := req.Namespace
	if namespace == "" {
		namespace = defaultNamespace
	}

	cfg := team.TeamConfigJSON{TaskClaimHint: "both", ShutdownPolicy: "lead-decides"}
	if req.ShutdownPolicy != "" {
		cfg.ShutdownPolicy = req.ShutdownPolicy
	}
	cfgJSON, _ := json.Marshal(cfg)

	extra := team.DefaultSpecExtra()
	if req.DynamicMembers != nil {
		extra.DynamicMembers = req.DynamicMembers
	}
	if req.Recovery != nil {
		extra.Recovery = req.Recovery
	}
	if req.Lifecycle != nil {
		extra.Lifecycle = req.Lifecycle
	}
	extraJSON, _ := json.Marshal(extra)

	created, err := s.store.Teams().Create(c.Request.Context(), &store.Team{
		Name:       req.Name,
		Namespace:  namespace,
		Objective:  req.Objective,
		Phase:      store.TeamPhasePending,
		LeadRef:    req.Lead.AgentRef,
		LeadPrompt: req.Lead.Prompt,
		Config:     cfgJSON,
		SpecExtra:  extraJSON,
	})
	if err != nil {
		if errors.Is(err, store.ErrConflict) {
			c.JSON(http.StatusConflict, ErrorResponse{Error: "team already exists"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	leadMode := req.Lead.DeployMode
	if leadMode == "" {
		leadMode = store.MemberDeployBYO
	}
	_, err = s.store.Teams().UpsertMember(c.Request.Context(), &store.TeamMember{
		TeamName:       req.Name,
		Namespace:      namespace,
		MemberName:     "lead",
		AgentRef:       req.Lead.AgentRef,
		Prompt:         req.Lead.Prompt,
		Origin:         store.MemberOriginStatic,
		DeployMode:     leadMode,
		ManagedAgentID: req.Lead.ManagedAgentID,
		OwnerID:        req.Lead.OwnerID,
		Phase:          store.MemberPhaseJoining,
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	s.messageRouter.RegisterMember(req.Name, &team.MemberLocation{
		MemberName: "lead", AgentName: req.Lead.AgentRef, Connected: true,
	})

	for _, m := range req.Members {
		mode := m.DeployMode
		if mode == "" {
			mode = store.MemberDeployBYO
		}
		_, err = s.store.Teams().UpsertMember(c.Request.Context(), &store.TeamMember{
			TeamName:       req.Name,
			Namespace:      namespace,
			MemberName:     m.Name,
			AgentRef:       m.AgentRef,
			Prompt:         m.Prompt,
			PlanApproval:   m.PlanApproval,
			Origin:         store.MemberOriginStatic,
			DeployMode:     mode,
			ManagedAgentID: m.ManagedAgentID,
			OwnerID:        m.OwnerID,
			Phase:          store.MemberPhaseJoining,
		})
		if err != nil {
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
			return
		}
		s.messageRouter.RegisterMember(req.Name, &team.MemberLocation{
			MemberName: m.Name, AgentName: m.AgentRef, Connected: true,
		})
	}

	// Start lifecycle if available (session rows). Activator wakes DPs in P2.
	if s.teamLifecycle != nil {
		if err := s.teamLifecycle.StartTeam(c.Request.Context(), created); err != nil {
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
			return
		}
		created, _ = s.store.Teams().Get(c.Request.Context(), namespace, req.Name)
	}

	members, _ := s.store.Teams().ListMembers(c.Request.Context(), namespace, req.Name)
	c.JSON(http.StatusCreated, gin.H{"team": created, "members": members})
}

func (s *Server) listTeams(c *gin.Context) {
	if !s.requireTeamStores(c) {
		return
	}
	namespace := c.DefaultQuery("namespace", "")
	items, err := s.store.Teams().List(c.Request.Context(), namespace)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) getTeam(c *gin.Context) {
	if !s.requireTeamStores(c) {
		return
	}
	teamName := c.Param("team")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	t, err := s.store.Teams().Get(c.Request.Context(), namespace, teamName)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "team not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	members, _ := s.store.Teams().ListMembers(c.Request.Context(), namespace, teamName)
	total, pending, inProgress, completed := s.taskStore.GetSummary(namespace, teamName)
	c.JSON(http.StatusOK, gin.H{
		"team":    t,
		"members": members,
		"tasks": gin.H{
			"total": total, "pending": pending,
			"inProgress": inProgress, "completed": completed,
		},
	})
}

func (s *Server) completeTeam(c *gin.Context) {
	if !s.requireTeamStores(c) {
		return
	}
	teamName := c.Param("team")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	t, err := s.store.Teams().Get(c.Request.Context(), namespace, teamName)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "team not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	if s.teamLifecycle == nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "team lifecycle not configured"})
		return
	}
	if err := s.teamLifecycle.CompleteTeam(c.Request.Context(), t); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	fresh, _ := s.store.Teams().Get(c.Request.Context(), namespace, teamName)
	c.JSON(http.StatusOK, gin.H{"team": fresh})
}

func (s *Server) deleteTeam(c *gin.Context) {
	if !s.requireTeamStores(c) {
		return
	}
	teamName := c.Param("team")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	t, err := s.store.Teams().Get(c.Request.Context(), namespace, teamName)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "team not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	// Force teardown: complete then immediately cleanup (skips TTL retention).
	if s.teamLifecycle != nil {
		_ = s.teamLifecycle.CompleteTeam(c.Request.Context(), t)
		s.teamLifecycle.CleanupTeamState(c.Request.Context(), t)
	} else {
		s.taskStore.DeleteTeam(namespace, teamName)
		s.messageRouter.DeleteTeam(teamName, namespace)
		_ = s.store.Teams().Delete(c.Request.Context(), namespace, teamName)
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) addTeamMember(c *gin.Context) {
	if !s.requireTeamStores(c) {
		return
	}
	teamName := c.Param("team")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var req TeamMemberRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	t, err := s.store.Teams().Get(c.Request.Context(), namespace, teamName)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "team not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	if s.teamLifecycle != nil {
		if err := s.teamLifecycle.SpawnDynamicMember(c.Request.Context(), t, req.Name, req.AgentRef, req.Prompt); err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
			return
		}
	} else {
		mode := req.DeployMode
		if mode == "" {
			mode = store.MemberDeployBYO
		}
		_, err = s.store.Teams().UpsertMember(c.Request.Context(), &store.TeamMember{
			TeamName: teamName, Namespace: namespace, MemberName: req.Name,
			AgentRef: req.AgentRef, Prompt: req.Prompt, PlanApproval: req.PlanApproval,
			Origin: store.MemberOriginDynamic, DeployMode: mode,
			ManagedAgentID: req.ManagedAgentID, OwnerID: req.OwnerID,
			Phase: store.MemberPhaseJoining,
		})
		if err != nil {
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
			return
		}
		s.messageRouter.RegisterMember(teamName, &team.MemberLocation{
			MemberName: req.Name, AgentName: req.AgentRef, Connected: true,
		})
	}
	c.JSON(http.StatusAccepted, gin.H{"member": req.Name, "status": "joining"})
}

func (s *Server) removeTeamMember(c *gin.Context) {
	if !s.requireTeamStores(c) {
		return
	}
	teamName := c.Param("team")
	memberName := c.Param("memberName")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	if s.teamLifecycle != nil {
		t, err := s.store.Teams().Get(c.Request.Context(), namespace, teamName)
		if err != nil {
			if errors.Is(err, store.ErrNotFound) {
				c.JSON(http.StatusNotFound, ErrorResponse{Error: "team not found"})
				return
			}
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
			return
		}
		if err := s.teamLifecycle.ShutdownMember(c.Request.Context(), t, memberName); err != nil {
			if errors.Is(err, store.ErrNotFound) {
				c.JSON(http.StatusNotFound, ErrorResponse{Error: "member not found"})
				return
			}
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
			return
		}
		c.JSON(http.StatusOK, gin.H{"member": memberName, "status": "shutdown"})
		return
	}

	if err := s.store.Teams().UpdateMemberPhase(c.Request.Context(), namespace, teamName, memberName, store.MemberPhaseShutdown); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "member not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	s.messageRouter.UnregisterMember(teamName, memberName)
	c.JSON(http.StatusOK, gin.H{"member": memberName, "status": "shutdown"})
}

// submitTeamMemberPlan records a member's proposed plan and notifies the lead.
func (s *Server) submitTeamMemberPlan(c *gin.Context) {
	if !s.requireTeamStores(c) {
		return
	}
	teamName := c.Param("team")
	memberName := c.Param("memberName")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var req TeamPlanRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if req.PlanText == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "planText required"})
		return
	}

	member, err := s.updateMemberPlan(c, namespace, teamName, memberName, req.PlanText, store.PlanStatusPending)
	if err != nil {
		return
	}
	if memberName != "lead" {
		_, _ = s.messageRouter.RouteMessage(namespace, teamName, memberName, "lead",
			"Plan submitted for approval by "+memberName+":\n"+req.PlanText)
	}
	c.JSON(http.StatusOK, gin.H{"member": member})
}

// approveTeamMemberPlan and rejectTeamMemberPlan are the lead's decision on a
// pending plan; the member is notified through the outbox.
func (s *Server) approveTeamMemberPlan(c *gin.Context) {
	s.decideTeamMemberPlan(c, store.PlanStatusApproved)
}

func (s *Server) rejectTeamMemberPlan(c *gin.Context) {
	s.decideTeamMemberPlan(c, store.PlanStatusRejected)
}

func (s *Server) decideTeamMemberPlan(c *gin.Context, status string) {
	if !s.requireTeamStores(c) {
		return
	}
	teamName := c.Param("team")
	memberName := c.Param("memberName")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var req TeamPlanDecisionRequest
	_ = c.ShouldBindJSON(&req)

	member, err := s.updateMemberPlan(c, namespace, teamName, memberName, "", status)
	if err != nil {
		return
	}
	content := "Your plan was " + status + " by the lead."
	if req.Note != "" {
		content += "\nNote: " + req.Note
	}
	_, _ = s.messageRouter.RouteMessage(namespace, teamName, "lead", memberName, content)
	c.JSON(http.StatusOK, gin.H{"member": member})
}

// updateMemberPlan reads-modifies-writes the member row. planText is only
// applied when non-empty so approve/reject keep the submitted text. It writes
// the HTTP error itself and returns it so callers can just bail out.
func (s *Server) updateMemberPlan(
	c *gin.Context,
	namespace, teamName, memberName, planText, planStatus string,
) (*store.TeamMember, error) {
	member, err := s.store.Teams().GetMember(c.Request.Context(), namespace, teamName, memberName)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "member not found"})
			return nil, err
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return nil, err
	}
	if planText != "" {
		member.PlanText = planText
	}
	member.PlanStatus = planStatus
	saved, err := s.store.Teams().UpsertMember(c.Request.Context(), member)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return nil, err
	}
	return saved, nil
}

func (s *Server) listTeamMembers(c *gin.Context) {
	if !s.requireTeamStores(c) {
		return
	}
	teamName := c.Param("team")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	members, err := s.store.Teams().ListMembers(c.Request.Context(), namespace, teamName)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	var lead *store.TeamMember
	var workers []*store.TeamMember
	for _, m := range members {
		if m.MemberName == "lead" {
			cp := m
			lead = cp
			continue
		}
		workers = append(workers, m)
	}
	c.JSON(http.StatusOK, gin.H{"lead": lead, "members": workers})
}

func (s *Server) createTeamTask(c *gin.Context) {
	if !s.requireTeamStores(c) {
		return
	}
	teamName := c.Param("team")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var req TeamTaskRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	var (
		task *store.TeamTask
		err  error
	)
	if req.Owner != "" {
		task, err = s.taskStore.CreateWithOwner(namespace, teamName, req.Subject, req.Description, req.BlockedBy, req.Owner)
	} else {
		task, err = s.taskStore.Create(namespace, teamName, req.Subject, req.Description, req.BlockedBy)
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusCreated, task)
}

func (s *Server) listTeamTasks(c *gin.Context) {
	if !s.requireTeamStores(c) {
		return
	}
	teamName := c.Param("team")
	namespace := c.DefaultQuery("namespace", defaultNamespace)
	tasks := s.taskStore.List(namespace, teamName)
	total, pending, inProgress, completed := s.taskStore.GetSummary(namespace, teamName)

	c.JSON(http.StatusOK, gin.H{
		"tasks": tasks,
		"summary": gin.H{
			"total": total, "pending": pending,
			"inProgress": inProgress, "completed": completed,
		},
	})
}

func (s *Server) assignTeamTask(c *gin.Context) {
	if !s.requireTeamStores(c) {
		return
	}
	teamName := c.Param("team")
	taskID := c.Param("taskId")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var req TeamTaskAssignRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	var expectedVersion int64
	if req.ResourceVersion != "" {
		v, _ := strconv.ParseInt(req.ResourceVersion, 10, 64)
		expectedVersion = v
	}
	task, err := s.taskStore.Assign(namespace, teamName, taskID, req.Owner, expectedVersion)
	if err != nil {
		s.writeTaskErr(c, namespace, teamName, taskID, err)
		return
	}
	c.JSON(http.StatusOK, task)
}

func (s *Server) claimTeamTask(c *gin.Context) {
	if !s.requireTeamStores(c) {
		return
	}
	teamName := c.Param("team")
	taskID := c.Param("taskId")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var req TeamTaskClaimRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	var expectedVersion int64
	if req.ResourceVersion != "" {
		v, _ := strconv.ParseInt(req.ResourceVersion, 10, 64)
		expectedVersion = v
	}

	task, err := s.taskStore.Claim(namespace, teamName, taskID, req.ClaimedBy, expectedVersion)
	if err != nil {
		s.writeTaskErr(c, namespace, teamName, taskID, err)
		return
	}
	c.JSON(http.StatusOK, task)
}

func (s *Server) unclaimTeamTask(c *gin.Context) {
	if !s.requireTeamStores(c) {
		return
	}
	teamName := c.Param("team")
	taskID := c.Param("taskId")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	task, err := s.taskStore.Unclaim(namespace, teamName, taskID)
	if err != nil {
		s.writeTaskErr(c, namespace, teamName, taskID, err)
		return
	}
	c.JSON(http.StatusOK, task)
}

func (s *Server) completeTeamTask(c *gin.Context) {
	if !s.requireTeamStores(c) {
		return
	}
	teamName := c.Param("team")
	taskID := c.Param("taskId")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var req struct {
		Result string `json:"result"`
	}
	_ = c.ShouldBindJSON(&req)

	task, err := s.taskStore.Complete(namespace, teamName, taskID, req.Result)
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusOK, task)
}

func (s *Server) sendTeamMessage(c *gin.Context) {
	if !s.requireTeamStores(c) {
		return
	}
	teamName := c.Param("team")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var req TeamMessageRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	// Empty `to` means broadcast (reuse MessageRouter.BroadcastMessage).
	if req.To == "" {
		msgs, err := s.messageRouter.BroadcastMessage(namespace, teamName, req.From, req.Content)
		if err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
			return
		}
		c.JSON(http.StatusOK, gin.H{"messages": msgs})
		return
	}

	msg, err := s.messageRouter.RouteMessage(namespace, teamName, req.From, req.To, req.Content)
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusOK, msg)
}

func (s *Server) listTeamMessages(c *gin.Context) {
	if !s.requireTeamStores(c) {
		return
	}
	teamName := c.Param("team")
	namespace := c.DefaultQuery("namespace", defaultNamespace)
	limit := parseLimit(c, 50)

	msgs := s.messageRouter.GetMessageHistory(namespace, teamName, limit)
	c.JSON(http.StatusOK, gin.H{"messages": msgs})
}

func (s *Server) listTeamEvents(c *gin.Context) {
	if !s.requireTeamStores(c) {
		return
	}
	// Minimal cursor API: reuse message history ordered by id as team events for now.
	// P2 will expand dedicated team_events; ?after= filters by message id.
	teamName := c.Param("team")
	namespace := c.DefaultQuery("namespace", defaultNamespace)
	after, _ := strconv.ParseInt(c.DefaultQuery("after", "0"), 10, 64)
	limit := parseLimit(c, 50)

	msgs := s.messageRouter.GetMessageHistory(namespace, teamName, limit*2)
	var out []*store.TeamMessage
	for i := len(msgs) - 1; i >= 0; i-- {
		m := msgs[i]
		if m.ID <= after {
			continue
		}
		out = append(out, m)
		if len(out) >= limit {
			break
		}
	}
	c.JSON(http.StatusOK, gin.H{"events": out, "after": after})
}

func (s *Server) writeTaskErr(c *gin.Context, namespace, teamName, taskID string, err error) {
	if errors.Is(err, store.ErrConflict) {
		current, getErr := s.taskStore.Get(namespace, teamName, taskID)
		resp := gin.H{"error": "conflict", "message": err.Error()}
		if getErr == nil {
			resp["currentState"] = current.State
			resp["currentOwner"] = current.Owner
			resp["resourceVersion"] = current.Version
		}
		c.JSON(http.StatusConflict, resp)
		return
	}
	if errors.Is(err, store.ErrNotFound) {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
}

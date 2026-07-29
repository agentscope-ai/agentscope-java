package httpapi

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/team"
)

func (s *Server) createTeam(c *gin.Context) {
	var req TeamCreateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	namespace := req.Namespace
	if namespace == "" {
		namespace = defaultNamespace
	}

	members := make([]v1alpha1.TeamMemberSpec, 0, len(req.Members))
	for _, m := range req.Members {
		members = append(members, v1alpha1.TeamMemberSpec{
			Name:     m.Name,
			AgentRef: v1alpha1.ObjectReference{Name: m.AgentRef},
			Prompt:   m.Prompt,
		})
	}

	agentTeam := &v1alpha1.AgentTeam{
		ObjectMeta: metav1.ObjectMeta{
			Name:      req.Name,
			Namespace: namespace,
		},
		Spec: v1alpha1.AgentTeamSpec{
			Objective: req.Objective,
			Lead: v1alpha1.TeamLeadSpec{
				AgentRef: v1alpha1.ObjectReference{Name: req.Lead.AgentRef},
				Prompt:   req.Lead.Prompt,
			},
			Members: members,
			DynamicMembers: &v1alpha1.DynamicMembersSpec{
				Enabled:  true,
				MaxTotal: 8,
			},
			Config: &v1alpha1.TeamConfig{
				TaskClaimStrategy: "self-claim",
				ShutdownPolicy:    "lead-decides",
			},
		},
	}

	if err := s.client.Create(c.Request.Context(), agentTeam); err != nil {
		if errors.IsAlreadyExists(err) {
			c.JSON(http.StatusConflict, ErrorResponse{Error: "team already exists"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	// Register lead in message router
	s.messageRouter.RegisterMember(req.Name, &team.MemberLocation{
		MemberName: "lead",
		AgentName:  req.Lead.AgentRef,
		Connected:  true,
	})
	for _, m := range req.Members {
		s.messageRouter.RegisterMember(req.Name, &team.MemberLocation{
			MemberName: m.Name,
			AgentName:  m.AgentRef,
			Connected:  true,
		})
	}

	c.JSON(http.StatusCreated, agentTeam)
}

func (s *Server) listTeams(c *gin.Context) {
	namespace := c.DefaultQuery("namespace", "")

	var list v1alpha1.AgentTeamList
	opts := []client.ListOption{}
	if namespace != "" {
		opts = append(opts, client.InNamespace(namespace))
	}
	if err := s.client.List(c.Request.Context(), &list, opts...); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"items": list.Items})
}

func (s *Server) getTeam(c *gin.Context) {
	teamName := c.Param("team")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var agentTeam v1alpha1.AgentTeam
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: teamName, Namespace: namespace}, &agentTeam); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "team not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	// Augment with live task summary
	total, pending, inProgress, completed := s.taskStore.GetSummary(namespace, teamName)
	if total > 0 {
		agentTeam.Status.Tasks = &v1alpha1.TeamTaskSummary{
			Total: total, Pending: pending, InProgress: inProgress, Completed: completed,
		}
	}

	c.JSON(http.StatusOK, agentTeam)
}

func (s *Server) deleteTeam(c *gin.Context) {
	teamName := c.Param("team")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var agentTeam v1alpha1.AgentTeam
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: teamName, Namespace: namespace}, &agentTeam); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "team not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	s.taskStore.DeleteTeam(namespace, teamName)
	s.messageRouter.DeleteTeam(teamName, namespace)

	if err := s.client.Delete(c.Request.Context(), &agentTeam); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.Status(http.StatusNoContent)
}

func (s *Server) addTeamMember(c *gin.Context) {
	teamName := c.Param("team")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var req TeamMemberRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	var agentTeam v1alpha1.AgentTeam
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: teamName, Namespace: namespace}, &agentTeam); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "team not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	if agentTeam.Spec.DynamicMembers == nil || !agentTeam.Spec.DynamicMembers.Enabled {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "dynamic members not enabled"})
		return
	}

	currentCount := int32(len(agentTeam.Spec.Members)) + int32(len(agentTeam.Status.Members))
	if agentTeam.Spec.DynamicMembers.MaxTotal > 0 && currentCount >= agentTeam.Spec.DynamicMembers.MaxTotal {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "max team members reached"})
		return
	}

	newMember := v1alpha1.TeamMemberStatus{
		Name:     req.Name,
		Origin:   v1alpha1.MemberOriginDynamic,
		AgentRef: req.AgentRef,
		Phase:    v1alpha1.MemberPhaseJoining,
	}
	agentTeam.Status.Members = append(agentTeam.Status.Members, newMember)

	if err := s.client.Status().Update(c.Request.Context(), &agentTeam); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	s.messageRouter.RegisterMember(teamName, &team.MemberLocation{
		MemberName: req.Name,
		AgentName:  req.AgentRef,
		Connected:  true,
	})

	c.JSON(http.StatusAccepted, gin.H{"member": req.Name, "status": "joining"})
}

func (s *Server) removeTeamMember(c *gin.Context) {
	teamName := c.Param("team")
	memberName := c.Param("memberName")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var agentTeam v1alpha1.AgentTeam
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: teamName, Namespace: namespace}, &agentTeam); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "team not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	for i, m := range agentTeam.Status.Members {
		if m.Name == memberName {
			agentTeam.Status.Members[i].Phase = v1alpha1.MemberPhaseShutdown
			break
		}
	}

	if err := s.client.Status().Update(c.Request.Context(), &agentTeam); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	s.messageRouter.UnregisterMember(teamName, memberName)
	c.JSON(http.StatusOK, gin.H{"member": memberName, "status": "shutdown"})
}

func (s *Server) listTeamMembers(c *gin.Context) {
	teamName := c.Param("team")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var agentTeam v1alpha1.AgentTeam
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: teamName, Namespace: namespace}, &agentTeam); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "team not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"lead":    agentTeam.Status.Lead,
		"members": agentTeam.Status.Members,
	})
}

func (s *Server) createTeamTask(c *gin.Context) {
	teamName := c.Param("team")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var req TeamTaskRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	task, err := s.taskStore.Create(namespace, teamName, req.Subject, req.Description, req.BlockedBy)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusCreated, task)
}

func (s *Server) listTeamTasks(c *gin.Context) {
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

func (s *Server) claimTeamTask(c *gin.Context) {
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
		if err == store.ErrConflict {
			// Conflict — return the current state for the caller to retry against.
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
		if err == store.ErrNotFound {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: err.Error()})
			return
		}
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusOK, task)
}

func (s *Server) completeTeamTask(c *gin.Context) {
	teamName := c.Param("team")
	taskID := c.Param("taskId")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var req struct {
		Result string `json:"result"`
	}
	c.ShouldBindJSON(&req)

	task, err := s.taskStore.Complete(namespace, teamName, taskID, req.Result)
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusOK, task)
}

func (s *Server) sendTeamMessage(c *gin.Context) {
	teamName := c.Param("team")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var req TeamMessageRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	msg, err := s.messageRouter.RouteMessage(namespace, teamName, req.From, req.To, req.Content)
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusOK, msg)
}

// listTeamMessages handles GET /api/v1/teams/:team/messages, returning recent
// message history for the team from the store outbox.
func (s *Server) listTeamMessages(c *gin.Context) {
	teamName := c.Param("team")
	namespace := c.DefaultQuery("namespace", defaultNamespace)
	limit := parseLimit(c, 50)

	msgs := s.messageRouter.GetMessageHistory(namespace, teamName, limit)
	c.JSON(http.StatusOK, gin.H{"messages": msgs})
}

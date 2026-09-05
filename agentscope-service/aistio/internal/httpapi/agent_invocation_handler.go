// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/asdp"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/orchestration"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type invocationModeCapability struct {
	State  string `json:"state"`
	Reason string `json:"reason"`
}

type agentInvocationCapabilities struct {
	AgentID      uuid.UUID                           `json:"agentId"`
	Job          invocationModeCapability            `json:"job"`
	Conversation invocationModeCapability            `json:"conversation"`
	Features     map[string]invocationModeCapability `json:"features"`
}

func (s *Server) getAgentInvocationCapabilities(c *gin.Context) {
	agentID, ok := parseUUIDParam(c, "agentId")
	if !ok {
		return
	}
	agent, err := s.store.AgentCatalog().GetAgent(c, agentID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !requestMatchesAgentScope(c, agent) {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "Agent not found in scope"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"capabilities": s.inspectInvocationCapabilities(c, agent)})
}

func requestMatchesAgentScope(c *gin.Context, agent *controlmodel.Agent) bool {
	tenant, namespace := strings.TrimSpace(c.Query("tenant")), strings.TrimSpace(c.Query("namespace"))
	return (tenant == "" || tenant == agent.Tenant) && (namespace == "" || namespace == agent.Namespace)
}

func (s *Server) inspectInvocationCapabilities(c *gin.Context, agent *controlmodel.Agent) agentInvocationCapabilities {
	result := agentInvocationCapabilities{AgentID: agent.ID, Features: map[string]invocationModeCapability{
		"events.stream": {State: "partial", Reason: "Event availability depends on runtime reporting and transport"},
		"cancel":        {State: "partial", Reason: "Cancellation depends on the selected runtime binding"},
		"resume":        {State: "not_supported", Reason: "No conversation-capable runtime candidate is available"},
	}}
	if agent.Status != controlmodel.AgentActive {
		reason := "Agent lifecycle is not active"
		result.Job, result.Conversation = invocationModeCapability{State: "unavailable", Reason: reason}, invocationModeCapability{State: "unavailable", Reason: reason}
		return result
	}
	bindings, _ := s.store.AgentCatalog().ListBindings(c, agent.ID, true)
	instances, _ := s.store.RuntimeRegistry().ListAgentInstances(c, agent.Tenant, agent.Namespace, agent.ID)
	readiness, _ := s.inspectAgentReadiness(c, agent, bindings, instances)
	if readiness.State == "ready" || readiness.State == "degraded" {
		result.Job = invocationModeCapability{State: "available", Reason: readiness.Reason}
	} else {
		result.Job = invocationModeCapability{State: "unavailable", Reason: readiness.Reason}
	}
	policy, err := s.store.Orchestration().GetRuntimePolicy(c, agent.Tenant, agent.Namespace, agent.ID.String())
	if err != nil {
		result.Conversation = invocationModeCapability{State: "unavailable", Reason: "Agent has no runtime policy"}
		return result
	}
	hasConversationKind := false
	for _, candidate := range policy.Candidates {
		binding, bindingErr := s.store.AgentCatalog().GetBinding(c, candidate.Binding.BindingID)
		if bindingErr != nil || binding.AgentID != agent.ID || !binding.Enabled || binding.ArchivedAt != nil {
			continue
		}
		switch binding.Kind {
		case controlmodel.DataPlaneManaged:
			hasConversationKind = true
			var cfg controlmodel.ManagedBindingConfiguration
			configurationValid := json.Unmarshal(binding.Configuration, &cfg) == nil &&
				cfg.OwnerRef != "" && cfg.ManagedDefinitionRef != ""
			if s.product != nil && configurationValid &&
				controlmodel.RuntimeSecurityMatches(binding.Kind, nil, candidate.SecurityConstraints) {
				result.Conversation = invocationModeCapability{State: "available", Reason: "Managed runtime supports interactive sessions"}
				result.Features["resume"] = invocationModeCapability{State: "available", Reason: "Managed sessions can accept additional turns"}
				return result
			}
		case controlmodel.DataPlaneExternalApplication:
			hasConversationKind = true
			for _, instance := range instances {
				if externalConversationCandidate(instance, binding, candidate) {
					result.Conversation = invocationModeCapability{State: "available", Reason: "A healthy External instance accepts conversation turns"}
					result.Features["resume"] = invocationModeCapability{State: "available", Reason: "The selected External instance supports continued turns"}
					return result
				}
			}
		case controlmodel.DataPlaneHostedRuntime:
			hasConversationKind = true
			if s.hostedConversationCandidate(c, agent, candidate) {
				result.Conversation = invocationModeCapability{State: "available", Reason: "Hosted runtime supports durable conversation turns"}
				result.Features["resume"] = invocationModeCapability{State: "available", Reason: "Hosted turns resume through the provider session or persisted transcript"}
				return result
			}
		}
	}
	if hasConversationKind {
		result.Conversation = invocationModeCapability{State: "unavailable", Reason: "Conversation-capable bindings currently have no eligible runtime"}
	} else {
		result.Conversation = invocationModeCapability{State: "not_supported", Reason: "No configured runtime supports conversation turns"}
	}
	return result
}

func externalConversationCandidate(instance *controlmodel.AgentInstance, binding *controlmodel.AgentBinding,
	candidate controlmodel.RuntimeBindingCandidate) bool {
	if instance.BindingID != binding.ID ||
		(candidate.Binding.InstanceSelector["instance"] != "" && candidate.Binding.InstanceSelector["instance"] != instance.InstanceKey) ||
		instance.Health != controlmodel.RuntimeHealthHealthy ||
		(instance.Capacity > 0 && instance.ActiveSessions >= instance.Capacity) ||
		!controlmodel.JSONContains(instance.Capabilities, candidate.RequiredCapabilities) ||
		!controlmodel.RuntimeSecurityMatches(binding.Kind, instance.Labels, candidate.SecurityConstraints) {
		return false
	}
	for _, capability := range decodeCapabilities(instance.Capabilities) {
		if capability == "conversation-inbound" || capability == "inbound-message" {
			return true
		}
	}
	return false
}

func (s *Server) resolveAgentConversation(ctx context.Context, agent *controlmodel.Agent, requestedSessionID,
	originType, originRef string) (*store.Session, error) {
	policy, err := s.store.Orchestration().GetRuntimePolicy(ctx, agent.Tenant, agent.Namespace, agent.ID.String())
	if err != nil || policy.SelectionMode != "ordered" || len(policy.Candidates) == 0 {
		return nil, fmt.Errorf("Agent has no runtime policy")
	}
	if requestedSessionID == "" {
		requestedSessionID = uuid.NewString()
	}
	for _, candidate := range policy.Candidates {
		binding, bindingErr := s.store.AgentCatalog().GetBinding(ctx, candidate.Binding.BindingID)
		if bindingErr != nil || binding.AgentID != agent.ID || !binding.Enabled || binding.ArchivedAt != nil || binding.Kind != candidate.Binding.Kind {
			continue
		}
		now := time.Now().UTC()
		sessionID, instanceRef := requestedSessionID, ""
		var instanceID uuid.UUID
		var generation int64
		switch binding.Kind {
		case controlmodel.DataPlaneManaged:
			if s.product == nil || !controlmodel.RuntimeSecurityMatches(binding.Kind, nil, candidate.SecurityConstraints) {
				continue
			}
			var cfg controlmodel.ManagedBindingConfiguration
			if json.Unmarshal(binding.Configuration, &cfg) != nil {
				continue
			}
			sessionID, err = s.product.FindOrCreateSessionID(ctx, cfg.OwnerRef, cfg.ManagedDefinitionRef, "",
				originType+"|"+originRef+"|"+requestedSessionID)
			if err != nil {
				return nil, err
			}
		case controlmodel.DataPlaneExternalApplication:
			instances, listErr := s.store.RuntimeRegistry().ListAgentInstances(ctx, agent.Tenant, agent.Namespace, agent.ID)
			if listErr != nil {
				return nil, listErr
			}
			for _, instance := range instances {
				if externalConversationCandidate(instance, binding, candidate) {
					instanceID, instanceRef, generation = instance.ID, instance.InstanceKey, instance.Generation
					break
				}
			}
			if instanceID == uuid.Nil {
				continue
			}
		case controlmodel.DataPlaneHostedRuntime:
			if !s.hostedConversationCandidate(ctx, agent, candidate) {
				continue
			}
			// Hosted providers create their native session lazily on the first
			// turn. The control-plane session ID remains stable across attempts.
			generation = 0
		default:
			continue
		}
		phase := store.SessionPhaseActive
		if binding.Kind == controlmodel.DataPlaneHostedRuntime {
			phase = store.SessionPhaseIdle
			if existing, listErr := s.store.Sessions().List(ctx, store.SessionFilter{Tenant: agent.Tenant,
				Namespace: agent.Namespace, AgentID: agent.ID, SessionID: sessionID, Limit: 1}); listErr == nil && len(existing) > 0 {
				phase = existing[0].Phase
			}
		}
		payload, _ := json.Marshal(gin.H{"originType": originType, "originRef": originRef})
		return s.store.Sessions().Upsert(ctx, &store.Session{Tenant: agent.Tenant, Namespace: agent.Namespace,
			AgentID: agent.ID, BindingID: binding.ID, AgentInstanceID: instanceID, InstanceGeneration: generation,
			AgentName: agent.AgentKey, SessionID: sessionID, InstanceRef: instanceRef, OriginType: originType,
			OriginRef: originRef, Framework: string(binding.Kind), Phase: phase,
			TaskContext: payload, StartedAt: &now, LastActiveAt: &now})
	}
	return nil, fmt.Errorf("no conversation-capable runtime candidate is available")
}

func (s *Server) sendAgentConversationTurn(ctx context.Context, session *store.Session, message,
	sourceType, sourceRef string) error {
	binding, err := s.store.AgentCatalog().GetBinding(ctx, session.BindingID)
	if err != nil || !binding.Enabled || binding.ArchivedAt != nil || binding.AgentID != session.AgentID {
		return fmt.Errorf("conversation Binding is unavailable")
	}
	switch binding.Kind {
	case controlmodel.DataPlaneManaged:
		if s.product == nil {
			return fmt.Errorf("Managed runtime is unavailable")
		}
		var cfg controlmodel.ManagedBindingConfiguration
		if json.Unmarshal(binding.Configuration, &cfg) != nil {
			return fmt.Errorf("Managed binding configuration is invalid")
		}
		return s.product.PostSessionWakeEvent(ctx, session.SessionID, cfg.OwnerRef, message)
	case controlmodel.DataPlaneExternalApplication:
		instance, instanceErr := s.store.RuntimeRegistry().GetAgentInstance(ctx, session.AgentInstanceID)
		if instanceErr != nil || instance.BindingID != binding.ID || instance.Generation != session.InstanceGeneration || instance.Health != controlmodel.RuntimeHealthHealthy {
			return fmt.Errorf("conversation AgentInstance is unavailable")
		}
		sender, ok := s.asdpCommands.(ConversationTurnSender)
		if !ok {
			return fmt.Errorf("External conversation transport is unavailable")
		}
		invocationID, conversationID, turnID := uuid.NewString(), uuid.NewString(), uuid.NewString()
		input, _ := json.Marshal(gin.H{"message": message})
		return sender.SendConversationTurn(session.Tenant, session.Namespace, instance.InstanceKey, &asdp.ConversationTurnCommand{
			InvocationId: invocationID, ConversationId: conversationID, TurnId: turnID, SessionId: session.SessionID,
			AgentId: session.AgentID.String(), BindingId: session.BindingID.String(), InstanceId: instance.ID.String(),
			Generation: session.InstanceGeneration, Input: input, Deadline: time.Now().Add(5 * time.Minute).UnixMilli(),
			CorrelationId: invocationID,
		})
	case controlmodel.DataPlaneHostedRuntime:
		_, err = s.dispatchHostedConversationTurn(ctx, session, binding, message, uuid.NewString(),
			sourceType, sourceRef)
		return err
	default:
		return fmt.Errorf("runtime binding %q does not support conversations", binding.Kind)
	}
}

func (s *Server) sendPlaygroundConversationTurn(ctx context.Context, session *store.Session, message string) error {
	return s.sendAgentConversationTurn(ctx, session, message, "playground_conversation", session.OriginRef)
}

type playgroundInvocationRequest struct {
	Tenant     string          `json:"tenant"`
	Namespace  string          `json:"namespace"`
	TargetType string          `json:"targetType"`
	TargetRef  uuid.UUID       `json:"targetRef"`
	Mode       string          `json:"mode"`
	Message    string          `json:"message"`
	Title      string          `json:"title"`
	Input      json.RawMessage `json:"input"`
	SessionID  string          `json:"sessionId"`
}

func (s *Server) invokePlayground(c *gin.Context) {
	var req playgroundInvocationRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.TargetRef == uuid.Nil || req.Tenant == "" || req.Namespace == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "tenant, namespace, targetType, targetRef and mode are required"})
		return
	}
	if req.Mode == "conversation" {
		if req.TargetType != "agent" || strings.TrimSpace(req.Message) == "" {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "conversation requires an Agent target and message"})
			return
		}
		agent, err := s.activeAgentInScope(c.Request.Context(), req.Tenant, req.Namespace, req.TargetRef.String())
		if err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
		invocationID := uuid.NewString()
		session, err := s.resolveAgentConversation(c, agent, req.SessionID, "playground", invocationID)
		if err == nil {
			err = s.sendPlaygroundConversationTurn(c, session, strings.TrimSpace(req.Message))
		}
		if err != nil {
			c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: err.Error()})
			return
		}
		c.JSON(http.StatusAccepted, gin.H{"invocationId": invocationID, "mode": "conversation", "status": "running",
			"sessionId": session.SessionID, "sessionRef": session.ID, "bindingId": session.BindingID,
			"eventsUrl":      "/api/v1/sessions/" + session.ID.String() + "/events",
			"eventStreamUrl": "/api/v1/sessions/" + session.ID.String() + "/events/stream"})
		return
	}
	if req.Mode != "job" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "mode must be conversation or job"})
		return
	}
	s.invokePlaygroundJob(c, req)
}

func (s *Server) continuePlaygroundConversation(c *gin.Context) {
	var req struct {
		Tenant    string    `json:"tenant"`
		Namespace string    `json:"namespace"`
		Message   string    `json:"message"`
		AgentID   uuid.UUID `json:"agentId"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.AgentID == uuid.Nil || strings.TrimSpace(req.Message) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "agentId and message are required"})
		return
	}
	sessions, err := s.store.Sessions().List(c, store.SessionFilter{Tenant: req.Tenant, Namespace: req.Namespace,
		AgentID: req.AgentID, SessionID: c.Param("sessionId"), Limit: 1})
	if err != nil || len(sessions) == 0 || !isPlaygroundSession(sessions[0]) {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "Playground session not found"})
		return
	}
	if err = s.sendPlaygroundConversationTurn(c, sessions[0], strings.TrimSpace(req.Message)); err != nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusAccepted, gin.H{"invocationId": uuid.New(), "mode": "conversation", "status": "running",
		"sessionId": sessions[0].SessionID, "sessionRef": sessions[0].ID, "bindingId": sessions[0].BindingID,
		"eventsUrl":      "/api/v1/sessions/" + sessions[0].ID.String() + "/events",
		"eventStreamUrl": "/api/v1/sessions/" + sessions[0].ID.String() + "/events/stream"})
}

// isPlaygroundSession also recognizes sessions written before runtime inventory
// stopped overwriting origin_type. task_context is the durable control-plane
// provenance and lets existing conversations continue without a data migration.
func isPlaygroundSession(session *store.Session) bool {
	if session == nil {
		return false
	}
	if session.OriginType == "playground" {
		return true
	}
	var provenance struct {
		OriginType string `json:"originType"`
	}
	return json.Unmarshal(session.TaskContext, &provenance) == nil && provenance.OriginType == "playground"
}

func (s *Server) invokePlaygroundJob(c *gin.Context, req playgroundInvocationRequest) {
	actor := humanActor(c, s)
	invocationID := uuid.New()
	if req.Title == "" {
		req.Title = "Playground job"
	}
	if len(req.Input) == 0 {
		req.Input, _ = json.Marshal(gin.H{"prompt": req.Message})
	}
	issueID := uuid.NewSHA1(invocationID, []byte("issue"))
	issue := &controlmodel.Issue{ID: issueID, Tenant: req.Tenant, Namespace: req.Namespace, Title: req.Title,
		Description: req.Message, Status: controlmodel.IssueInProgress, Priority: "normal",
		Kind: controlmodel.IssueKindPlaygroundJob, Visibility: controlmodel.IssueVisibilityOperational,
		CompletionPolicy: controlmodel.IssueCompletionAutomatic, Creator: actor, SourceType: "playground",
		SourceRef: invocationID.String(), ExecutionTargetType: req.TargetType, ExecutionTargetRef: req.TargetRef.String()}
	var endpointTarget controlmodel.EndpointTargetType
	switch req.TargetType {
	case "agent":
		agent, err := s.activeAgentInScope(c, req.Tenant, req.Namespace, req.TargetRef.String())
		if err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
		if capability := s.inspectInvocationCapabilities(c, agent).Job; capability.State != "available" {
			c.JSON(http.StatusConflict, ErrorResponse{Error: capability.Reason})
			return
		}
		endpointTarget, issue.AssigneeType, issue.AssigneeRef = controlmodel.EndpointTargetAgent, controlmodel.AssigneeAgent, req.TargetRef.String()
	case "team":
		team, err := s.store.Collaboration().GetTeam(c, req.TargetRef)
		if err != nil || team.Tenant != req.Tenant || team.Namespace != req.Namespace || team.Status != controlmodel.TeamActive {
			c.JSON(http.StatusConflict, ErrorResponse{Error: "Team target is not active in scope"})
			return
		}
		endpointTarget, issue.AssigneeType, issue.AssigneeRef = controlmodel.EndpointTargetTeam, controlmodel.AssigneeTeam, req.TargetRef.String()
		readiness := s.inspectEndpointReadiness(c, &controlmodel.Endpoint{Tenant: req.Tenant, Namespace: req.Namespace,
			TargetType: endpointTarget, TargetRef: req.TargetRef, InvocationMode: controlmodel.EndpointJobMode})
		if !readiness.Compatible || readiness.State == "unavailable" || readiness.State == "inactive" {
			c.JSON(http.StatusConflict, ErrorResponse{Error: readiness.Reason})
			return
		}
	case "workflow":
		definition, err := s.store.Orchestration().GetDefinition(c, req.TargetRef)
		if err != nil || definition.Tenant != req.Tenant || definition.Namespace != req.Namespace {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "Workflow target was not found in scope"})
			return
		}
		revisions, revisionErr := s.store.Orchestration().ListRevisions(c, req.TargetRef)
		if revisionErr != nil || len(revisions) == 0 {
			c.JSON(http.StatusConflict, ErrorResponse{Error: "Workflow must have a published revision before it can run"})
			return
		}
	default:
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "targetType must be agent, team or workflow"})
		return
	}
	// Assignment is persisted after creation so stores never synthesize a second task outside the Run.
	assigneeType, assigneeRef := issue.AssigneeType, issue.AssigneeRef
	issue.AssigneeType, issue.AssigneeRef = "", ""
	created, err := s.store.Collaboration().CreateIssue(c, issue)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if assigneeType != "" {
		created.AssigneeType, created.AssigneeRef = assigneeType, assigneeRef
		created, err = s.store.Collaboration().UpdateIssue(c, created, created.Version, actor)
		if err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
	}
	var run *controlmodel.OrchestrationRun
	if req.TargetType == "workflow" {
		run, err = s.orchestrationService().Start(c, req.TargetRef, orchestration.StartRequest{IssueID: &issueID,
			IdempotencyKey: "playground:" + invocationID.String(), Input: req.Input, TriggerType: "playground",
			TriggerRef: invocationID.String(), Actor: actor})
	} else {
		mode := controlmodel.RunModeDirect
		if req.TargetType == "team" {
			mode = controlmodel.RunModeAdaptive
		}
		run, err = s.store.Orchestration().CreateRun(c, &controlmodel.OrchestrationRun{Tenant: req.Tenant,
			Namespace: req.Namespace, RootIssueID: issueID, Mode: mode, TriggerType: "playground",
			TriggerRef: invocationID.String(), IdempotencyKey: "playground:" + invocationID.String(), Input: req.Input,
			State: controlmodel.RunRunning, CreatedBy: actor})
		if err == nil {
			endpoint := &controlmodel.Endpoint{TargetType: endpointTarget, TargetRef: req.TargetRef}
			err = s.materializeEndpointTarget(c, endpoint, run, issueID, actor)
		}
	}
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	tasks, _ := s.store.Collaboration().ListAgentTasks(c, store.AgentTaskFilter{RunID: run.ID, Limit: 100})
	for _, task := range tasks {
		if task.Status == controlmodel.AgentTaskQueued {
			_ = s.DispatchAgentTask(c, task.ID)
		}
	}
	c.JSON(http.StatusAccepted, gin.H{"invocationId": invocationID, "mode": "job", "status": "running",
		"issueId": issueID, "runId": run.ID, "statusUrl": "/api/v1/orchestration-runs/" + run.ID.String()})
}

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/orchestration"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type endpointRateWindow struct {
	Started time.Time
	Count   int
}

type endpointRateLimit struct {
	Requests      int `json:"requests"`
	WindowSeconds int `json:"windowSeconds"`
}

type endpointAuthPolicy struct {
	Type string `json:"type"`
}

func newEndpointKey() (string, []byte, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", nil, err
	}
	key := "asep_" + base64.RawURLEncoding.EncodeToString(raw)
	sum := sha256.Sum256([]byte(key))
	return key, sum[:], nil
}
func endpointPublic(v *controlmodel.AgentEndpoint) *controlmodel.AgentEndpoint {
	if v == nil {
		return nil
	}
	c := *v
	c.CredentialHash = nil
	return &c
}
func validateEndpoint(in *controlmodel.AgentEndpoint) error {
	if in.Name == "" || in.Slug == "" || in.TargetRef == uuid.Nil {
		return fmt.Errorf("name, slug and targetRef are required")
	}
	if in.InvocationMode != controlmodel.EndpointConversation && in.InvocationMode != controlmodel.EndpointJobMode {
		return fmt.Errorf("invocationMode must be conversation or job")
	}
	if in.InvocationMode == controlmodel.EndpointConversation && in.TargetType != controlmodel.EndpointTargetAgent {
		return fmt.Errorf("conversation endpoints require targetType=agent")
	}
	return nil
}
func (s *Server) validateEndpointTarget(c *gin.Context, in *controlmodel.AgentEndpoint) error {
	switch in.TargetType {
	case controlmodel.EndpointTargetAgent:
		_, err := s.activeAgentInScope(c, in.Tenant, in.Namespace, in.TargetRef.String())
		return err
	case controlmodel.EndpointTargetTeam:
		t, err := s.store.Collaboration().GetTeam(c, in.TargetRef)
		if err == nil && (t.Tenant != in.Tenant || t.Namespace != in.Namespace) {
			return store.ErrNotFound
		}
		return err
	case controlmodel.EndpointTargetOrchestrationRevision:
		r, err := s.store.Orchestration().GetRevision(c, in.TargetRef)
		if err == nil && (r.Tenant != in.Tenant || r.Namespace != in.Namespace) {
			return store.ErrNotFound
		}
		return err
	default:
		return fmt.Errorf("unsupported targetType")
	}
}

func (s *Server) createAgentEndpoint(c *gin.Context) {
	var in controlmodel.AgentEndpoint
	if err := c.ShouldBindJSON(&in); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if in.Tenant == "" {
		in.Tenant = "default"
	}
	if in.Namespace == "" {
		in.Namespace = defaultNamespace
	}
	if err := validateEndpoint(&in); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if err := s.validateEndpointTarget(c, &in); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	var authPolicy endpointAuthPolicy
	if len(in.AuthPolicy) == 0 {
		authPolicy.Type = "api_key"
		in.AuthPolicy = json.RawMessage(`{"type":"api_key"}`)
	} else if json.Unmarshal(in.AuthPolicy, &authPolicy) != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "authPolicy is invalid"})
		return
	}
	if authPolicy.Type != "api_key" && authPolicy.Type != "platform" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "authPolicy.type must be api_key or platform"})
		return
	}
	var key string
	if authPolicy.Type == "api_key" {
		var hash []byte
		var err error
		key, hash, err = newEndpointKey()
		if err != nil {
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: "generate endpoint credential"})
			return
		}
		in.CredentialHash = hash
	}
	in.Enabled = true
	v, err := s.store.AgentEndpoints().Create(c, &in)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	response := gin.H{"endpoint": endpointPublic(v)}
	if key != "" {
		response["credential"] = key
	}
	c.JSON(http.StatusCreated, response)
}
func (s *Server) listAgentEndpoints(c *gin.Context) {
	tenant, namespace := c.Query("tenant"), c.Query("namespace")
	if tenant == "" {
		tenant = "default"
	}
	if namespace == "" {
		namespace = defaultNamespace
	}
	items, err := s.store.AgentEndpoints().List(c, tenant, namespace)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	targetType := strings.TrimSpace(c.Query("targetType"))
	targetRef := strings.TrimSpace(c.Query("targetRef"))
	var filtered []*controlmodel.AgentEndpoint
	for i := range items {
		if targetType != "" && string(items[i].TargetType) != targetType {
			continue
		}
		if targetRef != "" && items[i].TargetRef.String() != targetRef {
			continue
		}
		filtered = append(filtered, endpointPublic(items[i]))
	}
	c.JSON(http.StatusOK, gin.H{"items": filtered})
}
func (s *Server) getAgentEndpoint(c *gin.Context) {
	id, ok := parseUUIDParam(c, "endpointId")
	if !ok {
		return
	}
	v, err := s.store.AgentEndpoints().Get(c, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"endpoint": endpointPublic(v)})
}
func (s *Server) patchAgentEndpoint(c *gin.Context) {
	id, ok := parseUUIDParam(c, "endpointId")
	if !ok {
		return
	}
	v, err := s.store.AgentEndpoints().Get(c, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	var in struct {
		Name             *string          `json:"name"`
		Enabled          *bool            `json:"enabled"`
		RateLimit        *json.RawMessage `json:"rateLimit"`
		Version          int64            `json:"version"`
		RotateCredential bool             `json:"rotateCredential"`
	}
	if err = c.ShouldBindJSON(&in); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if in.Version == 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "version is required"})
		return
	}
	if in.Name != nil {
		v.Name = *in.Name
	}
	if in.Enabled != nil {
		v.Enabled = *in.Enabled
	}
	if in.RateLimit != nil {
		v.RateLimit = *in.RateLimit
	}
	response := gin.H{}
	if in.RotateCredential {
		key, hash, e := newEndpointKey()
		if e != nil {
			c.JSON(500, ErrorResponse{Error: e.Error()})
			return
		}
		v.CredentialHash = hash
		response["credential"] = key
	}
	v, err = s.store.AgentEndpoints().Update(c, v, in.Version)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	response["endpoint"] = endpointPublic(v)
	c.JSON(http.StatusOK, response)
}

func (s *Server) authenticateEndpoint(c *gin.Context, endpoint *controlmodel.AgentEndpoint) bool {
	if endpoint == nil || !endpoint.Enabled {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "endpoint is unavailable"})
		return false
	}
	var policy endpointAuthPolicy
	if json.Unmarshal(endpoint.AuthPolicy, &policy) != nil {
		c.JSON(http.StatusUnauthorized, ErrorResponse{Error: "endpoint authentication policy is invalid"})
		return false
	}
	if policy.Type == "platform" {
		if !s.validPlatformToken(c.Request.Context(), requestBearerToken(c)) {
			c.JSON(http.StatusUnauthorized, ErrorResponse{Error: "invalid platform credential"})
			return false
		}
		return true
	}
	key := c.GetHeader("X-API-Key")
	if key == "" {
		key = bearerToken(c)
	}
	sum := sha256.Sum256([]byte(key))
	if len(endpoint.CredentialHash) != len(sum) || subtle.ConstantTimeCompare(endpoint.CredentialHash, sum[:]) != 1 {
		c.JSON(http.StatusUnauthorized, ErrorResponse{Error: "invalid endpoint credential"})
		return false
	}
	return true
}

func (s *Server) allowEndpointRequest(c *gin.Context, endpoint *controlmodel.AgentEndpoint) bool {
	var limit endpointRateLimit
	if len(endpoint.RateLimit) == 0 || json.Unmarshal(endpoint.RateLimit, &limit) != nil || limit.Requests <= 0 {
		return true
	}
	if limit.WindowSeconds <= 0 {
		limit.WindowSeconds = 60
	}
	now := time.Now().UTC()
	s.endpointRateMu.Lock()
	window := s.endpointRates[endpoint.ID]
	if window == nil || now.Sub(window.Started) >= time.Duration(limit.WindowSeconds)*time.Second {
		window = &endpointRateWindow{Started: now}
		s.endpointRates[endpoint.ID] = window
	}
	window.Count++
	allowed := window.Count <= limit.Requests
	retryAfter := time.Duration(limit.WindowSeconds)*time.Second - now.Sub(window.Started)
	s.endpointRateMu.Unlock()
	if allowed {
		return true
	}
	c.Header("Retry-After", fmt.Sprint(max(1, int(retryAfter.Seconds()))))
	c.JSON(http.StatusTooManyRequests, ErrorResponse{Error: "endpoint rate limit exceeded"})
	return false
}

func (s *Server) invokeEndpointConversation(c *gin.Context) {
	endpoint, err := s.store.AgentEndpoints().GetBySlug(c, c.Param("slug"))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !s.authenticateEndpoint(c, endpoint) {
		return
	}
	if !s.allowEndpointRequest(c, endpoint) {
		return
	}
	if endpoint.InvocationMode != controlmodel.EndpointConversation || endpoint.TargetType != controlmodel.EndpointTargetAgent {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "endpoint does not accept conversations"})
		return
	}
	var req struct {
		SessionID string `json:"sessionId"`
		Message   string `json:"message"`
	}
	if err = c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if strings.TrimSpace(req.Message) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "message is required"})
		return
	}
	session, err := s.dispatchEndpointConversation(c.Request.Context(), endpoint, req.SessionID, req.Message)
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusAccepted, gin.H{"sessionId": session.SessionID, "eventsUrl": "/invoke/v1/endpoints/" + endpoint.Slug + "/conversations/" + session.SessionID + "/events"})
}

type endpointConversationSender interface {
	SendSessionCommandWithParams(tenant, namespace, instanceID, sessionID, command string, params []byte) error
}

func (s *Server) dispatchEndpointConversation(ctx context.Context, endpoint *controlmodel.AgentEndpoint, requestedSessionID, message string) (*store.Session, error) {
	agent, err := s.store.AgentCatalog().GetAgent(ctx, endpoint.TargetRef)
	if err != nil || agent.Status != controlmodel.AgentActive {
		return nil, fmt.Errorf("endpoint Agent is unavailable")
	}
	policy, err := s.store.Orchestration().GetRuntimePolicy(ctx, endpoint.Tenant, endpoint.Namespace, agent.ID.String())
	if err != nil || policy.SelectionMode != "ordered" || len(policy.Candidates) == 0 {
		return nil, fmt.Errorf("endpoint Agent has no runtime policy")
	}
	binding := policy.Candidates[0].Binding
	stored, err := s.store.AgentCatalog().GetBinding(ctx, binding.BindingID)
	if err != nil || stored.AgentID != agent.ID || !stored.Enabled || stored.ArchivedAt != nil {
		return nil, fmt.Errorf("endpoint Agent binding is unavailable")
	}
	if requestedSessionID == "" {
		requestedSessionID = uuid.NewString()
	}
	now := time.Now().UTC()
	contextPayload, _ := json.Marshal(gin.H{"endpointId": endpoint.ID, "message": message})
	sessionID, instanceRef := requestedSessionID, ""
	var agentInstanceID uuid.UUID
	var instanceGeneration int64
	switch binding.Kind {
	case controlmodel.DataPlaneManaged:
		if s.product == nil {
			return nil, fmt.Errorf("Managed runtime is unavailable")
		}
		sessionID, err = s.product.FindOrCreateSessionID(ctx, binding.ManagedOwnerRef, binding.ManagedDefinitionRef, "", "agent-endpoint|"+requestedSessionID)
		if err == nil {
			err = s.product.PostSessionWakeEvent(ctx, sessionID, binding.ManagedOwnerRef, message)
		}
	case controlmodel.DataPlaneExternalApplication:
		sender, ok := s.asdpCommands.(endpointConversationSender)
		if !ok {
			return nil, fmt.Errorf("External conversation transport is unavailable")
		}
		instances, listErr := s.store.RuntimeRegistry().ListAgentInstances(ctx, endpoint.Tenant, endpoint.Namespace, agent.ID)
		if listErr != nil {
			return nil, listErr
		}
		for _, instance := range instances {
			if instance.BindingID == binding.BindingID && instance.Health == controlmodel.RuntimeHealthHealthy &&
				(instance.Capacity <= 0 || instance.ActiveSessions < instance.Capacity) {
				instanceRef = instance.InstanceKey
				agentInstanceID = instance.ID
				instanceGeneration = instance.Generation
				break
			}
		}
		if instanceRef == "" {
			return nil, fmt.Errorf("no healthy AgentInstance is available")
		}
		params, _ := json.Marshal(gin.H{"message": message, "endpointId": endpoint.ID})
		err = sender.SendSessionCommandWithParams(endpoint.Tenant, endpoint.Namespace, instanceRef, sessionID, "message", params)
	case controlmodel.DataPlaneHostedRuntime:
		return nil, fmt.Errorf("hosted-runtime does not support online conversations")
	default:
		return nil, fmt.Errorf("unsupported conversation binding %q", binding.Kind)
	}
	if err != nil {
		return nil, err
	}
	return s.store.Sessions().Upsert(ctx, &store.Session{Tenant: endpoint.Tenant, Namespace: endpoint.Namespace,
		AgentID: agent.ID, BindingID: binding.BindingID, AgentInstanceID: agentInstanceID,
		InstanceGeneration: instanceGeneration, AgentName: agent.AgentKey, SessionID: sessionID, InstanceRef: instanceRef,
		OriginType: "endpoint", OriginRef: endpoint.ID.String(),
		Framework: "agent-endpoint", Phase: store.SessionPhaseActive, TaskContext: contextPayload,
		StartedAt: &now, LastActiveAt: &now})
}

func (s *Server) invokeEndpointJob(c *gin.Context) {
	endpoint, err := s.store.AgentEndpoints().GetBySlug(c, c.Param("slug"))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !s.authenticateEndpoint(c, endpoint) {
		return
	}
	if !s.allowEndpointRequest(c, endpoint) {
		return
	}
	if endpoint.InvocationMode != controlmodel.EndpointJobMode {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "endpoint does not accept jobs"})
		return
	}
	idem := strings.TrimSpace(c.GetHeader("Idempotency-Key"))
	if idem == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "Idempotency-Key is required"})
		return
	}
	var req struct {
		Title       string          `json:"title"`
		Description string          `json:"description"`
		Input       json.RawMessage `json:"input"`
	}
	if err = c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if req.Title == "" {
		req.Title = "Endpoint job"
	}
	reservation, fresh, err := s.store.AgentEndpoints().ReserveJob(c, endpoint.ID, idem)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !fresh && reservation.IssueID != nil && reservation.RunID != nil {
		s.endpointJobAccepted(c, *reservation.IssueID, *reservation.RunID)
		return
	}
	issueID := uuid.NewSHA1(endpoint.ID, []byte("issue:"+idem))
	actor := controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "endpoint:" + endpoint.ID.String()}
	issue := &controlmodel.Issue{ID: issueID, Tenant: endpoint.Tenant, Namespace: endpoint.Namespace, Title: req.Title, Description: req.Description, Status: controlmodel.IssueInProgress, Priority: "normal", Creator: actor, SourceType: "agent_endpoint", SourceRef: endpoint.ID.String()}
	var assigneeType controlmodel.AssigneeType
	var assigneeRef string
	switch endpoint.TargetType {
	case controlmodel.EndpointTargetAgent:
		assigneeType, assigneeRef = controlmodel.AssigneeAgent, endpoint.TargetRef.String()
	case controlmodel.EndpointTargetTeam:
		assigneeType, assigneeRef = controlmodel.AssigneeTeam, endpoint.TargetRef.String()
	}
	createdIssue, err := s.store.Collaboration().CreateIssue(c, issue)
	if err != nil && err != store.ErrConflict {
		s.writeControlPlaneError(c, err)
		return
	}
	if createdIssue == nil {
		createdIssue, err = s.store.Collaboration().GetIssue(c, issueID)
	}
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	// Persist assignment as business state without asking Issue creation to
	// synthesize a second, non-Run AgentTask.
	if assigneeType != "" && (createdIssue.AssigneeType != assigneeType || createdIssue.AssigneeRef != assigneeRef) {
		createdIssue.AssigneeType, createdIssue.AssigneeRef = assigneeType, assigneeRef
		if _, err = s.store.Collaboration().UpdateIssue(c, createdIssue, createdIssue.Version, actor); err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
	}
	var run *controlmodel.OrchestrationRun
	if endpoint.TargetType == controlmodel.EndpointTargetOrchestrationRevision {
		revision, loadErr := s.store.Orchestration().GetRevision(c, endpoint.TargetRef)
		if loadErr != nil {
			s.writeControlPlaneError(c, loadErr)
			return
		}
		run, err = s.orchestrationService().Start(c, revision.DefinitionID, orchestration.StartRequest{
			RevisionID: &revision.ID, IdempotencyKey: endpoint.ID.String() + ":" + idem,
			Input: req.Input, IssueID: &issueID, TriggerType: "agent_endpoint",
			TriggerRef: endpoint.ID.String(), Actor: actor,
		})
	} else {
		mode := controlmodel.RunModeDirect
		if endpoint.TargetType == controlmodel.EndpointTargetTeam {
			mode = controlmodel.RunModeAdaptive
		}
		runID := uuid.NewSHA1(endpoint.ID, []byte("run:"+idem))
		run, err = s.store.Orchestration().CreateRun(c, &controlmodel.OrchestrationRun{ID: runID,
			Tenant: endpoint.Tenant, Namespace: endpoint.Namespace, RootIssueID: issueID, Mode: mode,
			TriggerType: "agent_endpoint", TriggerRef: endpoint.ID.String(),
			IdempotencyKey: endpoint.ID.String() + ":" + idem, Input: req.Input,
			State: controlmodel.RunRunning, CreatedBy: actor})
		if err == nil {
			err = s.materializeEndpointTarget(c.Request.Context(), endpoint, run, issueID, actor)
		}
	}
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	tasks, _ := s.store.Collaboration().ListAgentTasks(c, store.AgentTaskFilter{RunID: run.ID, Limit: 100})
	for _, task := range tasks {
		if task.Status == controlmodel.AgentTaskQueued {
			_ = s.DispatchAgentTask(c.Request.Context(), task.ID)
		}
	}
	if _, err = s.store.AgentEndpoints().CompleteJob(c, reservation.ID, issueID, run.ID); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	s.endpointJobAccepted(c, issueID, run.ID)
}

func (s *Server) materializeEndpointTarget(ctx context.Context, endpoint *controlmodel.AgentEndpoint, run *controlmodel.OrchestrationRun, issueID uuid.UUID, actor controlmodel.Actor) error {
	nodeType := controlmodel.RunNodeAgent
	agentID := endpoint.TargetRef
	var teamID *uuid.UUID
	if endpoint.TargetType == controlmodel.EndpointTargetTeam {
		team, err := s.store.Collaboration().GetTeam(ctx, endpoint.TargetRef)
		if err != nil {
			return err
		}
		snapshot, _ := json.Marshal(team)
		if _, err = s.store.Orchestration().PutTeamSnapshot(ctx, &controlmodel.RunTeamSnapshot{
			RunID: run.ID, TeamID: team.ID, Tenant: run.Tenant, Namespace: run.Namespace, Snapshot: snapshot,
		}); err != nil {
			return err
		}
		leaderID, parseErr := uuid.Parse(team.LeaderAgentRef)
		if parseErr != nil {
			return fmt.Errorf("Team leaderAgentRef must be an agentId: %w", parseErr)
		}
		nodeType, agentID, teamID = controlmodel.RunNodeTeam, leaderID, &team.ID
	}
	nodeID := uuid.NewSHA1(run.ID, []byte("target"))
	node, err := s.store.Orchestration().CreateNode(ctx, &controlmodel.RunNode{ID: nodeID, RunID: run.ID,
		Tenant: run.Tenant, Namespace: run.Namespace, NodeKey: "target", Type: nodeType,
		IssueID: &issueID, State: controlmodel.RunNodeReady, Iteration: 1})
	if err == store.ErrConflict {
		node, err = s.store.Orchestration().GetNode(ctx, nodeID)
	}
	if err != nil {
		return err
	}
	existing, err := s.store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{RunID: run.ID, NodeID: node.ID, AgentRef: agentID.String(), Limit: 1})
	if err != nil || len(existing) > 0 {
		return err
	}
	_, err = s.store.Collaboration().CreateRunAgentTask(ctx, store.RunTaskRequest{RunID: run.ID,
		NodeID: node.ID, IssueID: issueID, AgentRef: agentID.String(), TeamID: teamID,
		TeamRole: map[bool]string{true: "leader"}[teamID != nil], Leader: teamID != nil, Originator: actor})
	if err != nil {
		return err
	}
	return (&orchestration.Engine{Store: s.store}).ReconcileRun(ctx, run.ID)
}

func (s *Server) getEndpointConversationEvents(c *gin.Context) {
	endpoint, err := s.store.AgentEndpoints().GetBySlug(c, c.Param("slug"))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !s.authenticateEndpoint(c, endpoint) {
		return
	}
	session, err := s.store.Sessions().Get(c, endpoint.Tenant, endpoint.TargetRef.String(), endpoint.Namespace, c.Param("sessionId"))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	after, _ := strconv.ParseInt(c.GetHeader("Last-Event-ID"), 10, 64)
	events, err := s.store.Events().List(c, session.ID, store.WithEventLimit(1000))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.Header("Content-Type", "text/event-stream")
	c.Header("Cache-Control", "no-cache")
	c.Header("Connection", "keep-alive")
	for _, event := range events {
		if event.ID <= after {
			continue
		}
		payload, _ := json.Marshal(event)
		_, _ = fmt.Fprintf(c.Writer, "id: %d\nevent: %s\ndata: %s\n\n", event.ID, event.EventType, payload)
	}
	c.Writer.Flush()
}
func (s *Server) endpointJobAccepted(c *gin.Context, issueID, runID uuid.UUID) {
	c.JSON(http.StatusAccepted, gin.H{"issueId": issueID, "runId": runID, "statusUrl": "/invoke/v1/jobs/" + issueID.String(), "eventsUrl": "/invoke/v1/jobs/" + issueID.String() + "/events"})
}
func (s *Server) loadAuthorizedEndpointJob(c *gin.Context) (*controlmodel.EndpointJob, *controlmodel.AgentEndpoint, bool) {
	issueID, err := uuid.Parse(c.Param("issueId"))
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid issueId"})
		return nil, nil, false
	}
	job, err := s.store.AgentEndpoints().GetJobByIssue(c, issueID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return nil, nil, false
	}
	endpoint, err := s.store.AgentEndpoints().Get(c, job.EndpointID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return nil, nil, false
	}
	return job, endpoint, s.authenticateEndpoint(c, endpoint)
}
func (s *Server) getEndpointJob(c *gin.Context) {
	job, _, ok := s.loadAuthorizedEndpointJob(c)
	if !ok {
		return
	}
	issue, err := s.store.Collaboration().GetIssue(c, *job.IssueID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	run, err := s.store.Orchestration().GetRun(c, *job.RunID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"issue": issue, "run": run})
}
func (s *Server) getEndpointJobEvents(c *gin.Context) {
	job, _, ok := s.loadAuthorizedEndpointJob(c)
	if !ok {
		return
	}
	after, _ := strconv.ParseInt(c.GetHeader("Last-Event-ID"), 10, 64)
	events, err := s.store.Orchestration().ListRunEvents(c, *job.RunID, after, 1000)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.Header("Content-Type", "text/event-stream")
	c.Header("Cache-Control", "no-cache")
	c.Header("Connection", "keep-alive")
	for _, event := range events {
		payload, _ := json.Marshal(event)
		_, _ = fmt.Fprintf(c.Writer, "id: %d\nevent: %s\ndata: %s\n\n", event.Sequence, event.Type, payload)
	}
	c.Writer.Flush()
}

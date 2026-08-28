// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

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
	key, hash, err := newEndpointKey()
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: "generate endpoint credential"})
		return
	}
	in.AuthPolicy = json.RawMessage(`{"type":"api_key"}`)
	in.CredentialHash = hash
	in.Enabled = true
	v, err := s.store.AgentEndpoints().Create(c, &in)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"endpoint": endpointPublic(v), "credential": key})
}
func (s *Server) listAgentEndpoints(c *gin.Context) {
	items, err := s.store.AgentEndpoints().List(c, c.Query("tenant"), c.Query("namespace"))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	for i := range items {
		items[i] = endpointPublic(items[i])
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
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

func (s *Server) invokeEndpointConversation(c *gin.Context) {
	endpoint, err := s.store.AgentEndpoints().GetBySlug(c, c.Param("slug"))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !s.authenticateEndpoint(c, endpoint) {
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
	if req.SessionID == "" {
		req.SessionID = uuid.NewString()
	}
	session, err := s.store.Sessions().Upsert(c, &store.Session{Tenant: endpoint.Tenant, Namespace: endpoint.Namespace, AgentName: endpoint.TargetRef.String(), SessionID: req.SessionID, Framework: "agent-endpoint", Phase: store.SessionPhaseActive, TaskContext: json.RawMessage(fmt.Sprintf(`{"initialMessage":%q}`, req.Message))})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusAccepted, gin.H{"sessionId": session.SessionID, "eventsUrl": "/api/v1/sessions/" + session.ID.String() + "/events"})
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
	runID := uuid.NewSHA1(endpoint.ID, []byte("run:"+idem))
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
	mode := controlmodel.RunModeDirect
	var revisionID *uuid.UUID
	if endpoint.TargetType == controlmodel.EndpointTargetTeam {
		mode = controlmodel.RunModeAdaptive
	}
	if endpoint.TargetType == controlmodel.EndpointTargetOrchestrationRevision {
		mode = controlmodel.RunModeDeclared
		revisionID = &endpoint.TargetRef
	}
	run, err := s.store.Orchestration().CreateRun(c, &controlmodel.OrchestrationRun{ID: runID, Tenant: endpoint.Tenant, Namespace: endpoint.Namespace, RootIssueID: issueID, Mode: mode, DefinitionRevisionID: revisionID, TriggerType: "agent_endpoint", TriggerRef: endpoint.ID.String(), IdempotencyKey: endpoint.ID.String() + ":" + idem, Input: req.Input, State: controlmodel.RunRunning, CreatedBy: actor})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if endpoint.TargetType == controlmodel.EndpointTargetAgent {
		nodeID := uuid.NewSHA1(run.ID, []byte("target"))
		node, createErr := s.store.Orchestration().CreateNode(c, &controlmodel.RunNode{ID: nodeID, RunID: run.ID, Tenant: endpoint.Tenant, Namespace: endpoint.Namespace, NodeKey: "target", Type: controlmodel.RunNodeAgent, IssueID: &issueID, State: controlmodel.RunNodeReady, Iteration: 1})
		if createErr != nil && createErr != store.ErrConflict {
			s.writeControlPlaneError(c, createErr)
			return
		}
		if node == nil {
			node, _ = s.store.Orchestration().GetNode(c, nodeID)
		}
		if node != nil {
			existing, listErr := s.store.Collaboration().ListAgentTasks(c, store.AgentTaskFilter{RunID: run.ID, NodeID: node.ID, AgentRef: endpoint.TargetRef.String(), Limit: 1})
			if listErr != nil {
				s.writeControlPlaneError(c, listErr)
				return
			}
			if len(existing) == 0 {
				if _, err = s.store.Collaboration().CreateRunAgentTask(c, store.RunTaskRequest{RunID: run.ID, NodeID: node.ID, IssueID: issueID, AgentRef: endpoint.TargetRef.String(), Originator: actor}); err != nil {
					s.writeControlPlaneError(c, err)
					return
				}
			}
		}
	}
	if _, err = s.store.AgentEndpoints().CompleteJob(c, reservation.ID, issueID, run.ID); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	s.endpointJobAccepted(c, issueID, run.ID)
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
	events, err := s.store.Orchestration().ListRunEvents(c, *job.RunID, 0, 1000)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": events, "reconnectFrom": "event sequence"})
}

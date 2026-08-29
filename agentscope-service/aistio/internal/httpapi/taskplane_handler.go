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
	"errors"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type runtimeHostRegistrationRequest struct {
	Tenant        string          `json:"tenant"`
	Namespace     string          `json:"namespace"`
	HostKey       string          `json:"hostKey"`
	PoolName      string          `json:"poolName"`
	DaemonVersion string          `json:"daemonVersion,omitempty"`
	OS            string          `json:"os,omitempty"`
	Arch          string          `json:"arch,omitempty"`
	Labels        json.RawMessage `json:"labels,omitempty"`
	Capabilities  json.RawMessage `json:"capabilities,omitempty"`
	Capacity      int32           `json:"capacity"`
}

func (s *Server) registerRuntimeHost(c *gin.Context) {
	var req runtimeHostRegistrationRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.HostKey == "" || req.PoolName == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "hostKey and poolName are required"})
		return
	}
	if req.Tenant == "" {
		req.Tenant = "default"
	}
	if req.Namespace == "" {
		req.Namespace = defaultNamespace
	}
	host, err := s.store.RuntimeRegistry().UpsertRuntimeHost(c.Request.Context(), &controlmodel.RuntimeHost{
		Tenant: req.Tenant, Namespace: req.Namespace, HostKey: req.HostKey,
		PoolName: req.PoolName, DaemonVersion: req.DaemonVersion, OS: req.OS, Arch: req.Arch,
		Labels: req.Labels, Capabilities: req.Capabilities, Capacity: req.Capacity,
		State: controlmodel.RuntimeHostOnline,
	})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"host": host, "heartbeatIntervalSeconds": 15})
}

func (s *Server) listRuntimeHosts(c *gin.Context) {
	hosts, err := s.store.RuntimeRegistry().ListRuntimeHosts(c.Request.Context(), c.Query("tenant"),
		c.Query("namespace"), c.Query("poolName"), c.Query("state"))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": hosts})
}

func (s *Server) getRuntimeHost(c *gin.Context) {
	id, ok := parseUUIDParam(c, "hostId")
	if !ok {
		return
	}
	host, err := s.store.RuntimeRegistry().GetRuntimeHost(c.Request.Context(), id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"host": host})
}

func (s *Server) drainRuntimeHost(c *gin.Context) {
	s.setRuntimeHostStateFromOperations(c, controlmodel.RuntimeHostDraining)
}
func (s *Server) resumeRuntimeHost(c *gin.Context) {
	s.setRuntimeHostStateFromOperations(c, controlmodel.RuntimeHostOnline)
}

func (s *Server) setRuntimeHostStateFromOperations(c *gin.Context, state string) {
	id, ok := parseUUIDParam(c, "hostId")
	if !ok {
		return
	}
	host, err := s.store.RuntimeRegistry().GetRuntimeHost(c.Request.Context(), id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	host, err = s.store.RuntimeRegistry().SetRuntimeHostState(c.Request.Context(), id, host.LeaseGeneration, state)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"host": host})
}

func (s *Server) disableRuntimeBinding(c *gin.Context) { s.setRuntimeBindingEnabled(c, false) }
func (s *Server) enableRuntimeBinding(c *gin.Context)  { s.setRuntimeBindingEnabled(c, true) }

func (s *Server) setRuntimeBindingEnabled(c *gin.Context, enabled bool) {
	id, ok := parseUUIDParam(c, "bindingId")
	if !ok {
		return
	}
	binding, err := s.store.AgentCatalog().GetBinding(c.Request.Context(), id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	binding.Enabled = enabled
	binding, err = s.store.AgentCatalog().UpdateBinding(c.Request.Context(), binding, binding.Version)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"binding": binding})
}

func (s *Server) listOutboxDeadLetters(c *gin.Context) {
	items, err := s.store.Outbox().ListDeadLetters(c.Request.Context(), c.Query("tenant"), c.Query("namespace"), queryInt(c, "limit", 100))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) replayOutboxDeadLetter(c *gin.Context) {
	id, ok := parseUUIDParam(c, "eventId")
	if !ok {
		return
	}
	event, err := s.store.Outbox().ReplayDeadLetter(c.Request.Context(), id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusAccepted, gin.H{"event": event})
}

func (s *Server) heartbeatRuntimeHost(c *gin.Context) {
	id, ok := parseUUIDParam(c, "hostId")
	if !ok {
		return
	}
	var req struct {
		Generation   int64           `json:"generation"`
		Active       int32           `json:"active"`
		Capabilities json.RawMessage `json:"capabilities,omitempty"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Generation <= 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "generation is required"})
		return
	}
	host, err := s.store.RuntimeRegistry().HeartbeatRuntimeHost(c.Request.Context(), id, req.Generation, req.Active, req.Capabilities)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"host": host})
}

func (s *Server) setRuntimeHostState(c *gin.Context) {
	id, ok := parseUUIDParam(c, "hostId")
	if !ok {
		return
	}
	var req struct {
		Generation int64  `json:"generation"`
		State      string `json:"state"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Generation <= 0 || req.State == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "generation and state are required"})
		return
	}
	host, err := s.store.RuntimeRegistry().SetRuntimeHostState(c.Request.Context(), id, req.Generation, req.State)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"host": host})
}

type executionLeaseRequest struct {
	Generation   int64  `json:"generation"`
	LeaseOwner   string `json:"leaseOwner"`
	LeaseToken   string `json:"leaseToken"`
	FencingToken int64  `json:"fencingToken"`
	LeaseSeconds int64  `json:"leaseSeconds,omitempty"`
}

func (s *Server) claimExecutionAttempt(c *gin.Context) {
	hostID, ok := parseUUIDParam(c, "hostId")
	if !ok {
		return
	}
	var req struct {
		Tenant          string `json:"tenant"`
		Namespace       string `json:"namespace"`
		RuntimePoolName string `json:"runtimePoolName"`
		executionLeaseRequest
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Generation <= 0 || req.LeaseOwner == "" || req.LeaseToken == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "generation, leaseOwner, and leaseToken are required"})
		return
	}
	if req.LeaseSeconds <= 0 {
		req.LeaseSeconds = 30
	}
	execution, err := s.taskPlane.Claim(c.Request.Context(), store.ExecutionClaim{
		Tenant: req.Tenant, Namespace: req.Namespace, RuntimePoolName: req.RuntimePoolName,
		HostID: hostID, HostGeneration: req.Generation, LeaseOwner: req.LeaseOwner,
		LeaseToken: req.LeaseToken, LeaseTTL: time.Duration(req.LeaseSeconds) * time.Second,
	})
	if errors.Is(err, store.ErrNotFound) {
		c.Status(http.StatusNoContent)
		return
	}
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	task, err := s.store.Collaboration().GetAgentTask(c.Request.Context(), execution.AgentTaskID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	profile, err := s.store.RuntimeRegistry().GetRuntimeProfile(c.Request.Context(), execution.Tenant,
		execution.Namespace, execution.RuntimeProfileName)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	contextEnvelope, err := s.collaborationService().BuildContext(c.Request.Context(), task.ID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	attemptToken, err := s.taskTokens.MintAttempt(execution.ID, execution.DispatchGeneration,
		string(execution.BackendKind), hostID.String(), time.Now().UTC())
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"task": task, "context": contextEnvelope, "attempt": execution,
		"attemptToken": attemptToken, "runtimeProfile": profile})
}

func (s *Server) renewExecutionAttempt(c *gin.Context) {
	_, executionID, req, ok := s.bindExecutionLease(c)
	if !ok {
		return
	}
	if req.LeaseSeconds <= 0 {
		req.LeaseSeconds = 30
	}
	execution, err := s.store.ExecutionAttempts().RenewLease(c.Request.Context(), executionID,
		req.LeaseToken, req.FencingToken, time.Duration(req.LeaseSeconds)*time.Second)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"attempt": execution})
}

func (s *Server) prepareExecutionAttempt(c *gin.Context) {
	_, executionID, req, ok := s.bindExecutionLease(c)
	if !ok {
		return
	}
	execution, err := s.taskPlane.MarkPreparing(c.Request.Context(), executionID, req.LeaseToken, req.FencingToken)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"attempt": execution})
}

func (s *Server) startExecutionAttempt(c *gin.Context) {
	_, executionID, req, ok := s.bindExecutionLease(c)
	if !ok {
		return
	}
	var payload struct {
		ProviderSessionID string `json:"providerSessionId,omitempty"`
		WorkspaceKey      string `json:"workspaceKey,omitempty"`
	}
	_ = json.Unmarshal(req.extra, &payload)
	execution, err := s.taskPlane.MarkRunning(c.Request.Context(), executionID, req.LeaseToken,
		req.FencingToken, payload.ProviderSessionID, payload.WorkspaceKey)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"attempt": execution})
}

func (s *Server) completeExecutionAttempt(c *gin.Context) {
	_, executionID, req, ok := s.bindExecutionLease(c)
	if !ok {
		return
	}
	var payload struct {
		Result     json.RawMessage `json:"result,omitempty"`
		Checkpoint json.RawMessage `json:"checkpoint,omitempty"`
	}
	_ = json.Unmarshal(req.extra, &payload)
	execution, err := s.taskPlane.Complete(c.Request.Context(), executionID, req.LeaseToken,
		req.FencingToken, payload.Result, payload.Checkpoint)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"attempt": execution})
}

func (s *Server) checkpointExecutionAttempt(c *gin.Context) {
	_, executionID, req, ok := s.bindExecutionLease(c)
	if !ok {
		return
	}
	var payload struct {
		ProviderSessionID string          `json:"providerSessionId,omitempty"`
		Checkpoint        json.RawMessage `json:"checkpoint,omitempty"`
	}
	_ = json.Unmarshal(req.extra, &payload)
	execution, err := s.taskPlane.Checkpoint(c.Request.Context(), executionID, req.LeaseToken,
		req.FencingToken, payload.ProviderSessionID, payload.Checkpoint)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"attempt": execution})
}

func (s *Server) failExecutionAttempt(c *gin.Context) {
	_, executionID, req, ok := s.bindExecutionLease(c)
	if !ok {
		return
	}
	var payload struct {
		FailureCode    string          `json:"failureCode"`
		FailureMessage string          `json:"failureMessage"`
		Checkpoint     json.RawMessage `json:"checkpoint,omitempty"`
	}
	_ = json.Unmarshal(req.extra, &payload)
	execution, err := s.taskPlane.Fail(c.Request.Context(), executionID, req.LeaseToken,
		req.FencingToken, payload.FailureCode, payload.FailureMessage, payload.Checkpoint)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"attempt": execution})
}

func (s *Server) cancelledExecutionAttempt(c *gin.Context) {
	_, attemptID, req, ok := s.bindExecutionLease(c)
	if !ok {
		return
	}
	attempt, err := s.taskPlane.ConfirmCancelled(c.Request.Context(), attemptID, req.LeaseToken, req.FencingToken)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"attempt": attempt})
}

type boundExecutionLease struct {
	executionLeaseRequest
	extra json.RawMessage
}

func (s *Server) bindExecutionLease(c *gin.Context) (uuid.UUID, uuid.UUID, boundExecutionLease, bool) {
	hostID, ok := parseUUIDParam(c, "hostId")
	if !ok {
		return uuid.Nil, uuid.Nil, boundExecutionLease{}, false
	}
	executionID, ok := parseUUIDParam(c, "attemptId")
	if !ok {
		return uuid.Nil, uuid.Nil, boundExecutionLease{}, false
	}
	body, err := c.GetRawData()
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return uuid.Nil, uuid.Nil, boundExecutionLease{}, false
	}
	var req boundExecutionLease
	if err := json.Unmarshal(body, &req.executionLeaseRequest); err != nil || req.LeaseToken == "" || req.FencingToken <= 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "leaseToken and fencingToken are required"})
		return uuid.Nil, uuid.Nil, boundExecutionLease{}, false
	}
	current, err := s.store.ExecutionAttempts().Get(c.Request.Context(), executionID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return uuid.Nil, uuid.Nil, boundExecutionLease{}, false
	}
	if current.HostID == nil || *current.HostID != hostID {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "execution is not leased to this host"})
		return uuid.Nil, uuid.Nil, boundExecutionLease{}, false
	}
	if err := s.taskTokens.VerifyAttempt(c.GetHeader("X-Execution-Attempt-Token"), current.ID,
		current.DispatchGeneration, string(current.BackendKind), hostID.String(), time.Now().UTC()); err != nil {
		c.JSON(http.StatusUnauthorized, ErrorResponse{Error: "invalid execution attempt token"})
		return uuid.Nil, uuid.Nil, boundExecutionLease{}, false
	}
	req.extra = body
	return hostID, executionID, req, true
}

func parseUUIDParam(c *gin.Context, name string) (uuid.UUID, bool) {
	id, err := uuid.Parse(c.Param(name))
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid " + name})
		return uuid.Nil, false
	}
	return id, true
}

func (s *Server) writeControlPlaneError(c *gin.Context, err error) {
	switch {
	case errors.Is(err, store.ErrNotFound):
		c.JSON(http.StatusNotFound, ErrorResponse{Error: err.Error()})
	case errors.Is(err, store.ErrConflict):
		c.JSON(http.StatusConflict, ErrorResponse{Error: err.Error()})
	case errors.Is(err, store.ErrForbidden):
		c.JSON(http.StatusForbidden, ErrorResponse{Error: err.Error()})
	default:
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
	}
}

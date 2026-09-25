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
	"context"
	"encoding/json"
	"errors"
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

func (s *Server) orchestrationService() *orchestration.Service {
	return &orchestration.Service{Store: s.store, TaskPlane: s.taskPlane}
}

func (s *Server) createOrchestrationDefinition(c *gin.Context) {
	var in controlmodel.OrchestrationDefinition
	if err := c.ShouldBindJSON(&in); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if in.Tenant == "" || in.Namespace == "" || in.Name == "" || len(in.DraftSpec) == 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "tenant, namespace, name, and draftSpec are required"})
		return
	}
	if _, err := s.orchestrationService().ValidateDefinition(in.DraftSpec); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	in.CreatedBy = collaborationActor(c, s)
	created, err := s.store.Orchestration().CreateDefinition(c.Request.Context(), &in)
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"definition": created})
}
func (s *Server) listOrchestrationDefinitions(c *gin.Context) {
	tenant, namespace, ok := requireCollaborationScope(c)
	if !ok {
		return
	}
	items, err := s.store.Orchestration().ListDefinitions(c.Request.Context(), store.OrchestrationDefinitionFilter{ExcludedIDs: excludedResourceIDs(c, "workflow"), Tenant: tenant, Namespace: namespace, Name: c.Query("name"), IncludeArchived: c.Query("archived") == "true", Offset: max(0, queryInt(c, "offset", 0)), Limit: queryInt(c, "limit", 100)})
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	if a := accessFrom(c); a != nil {
		filtered := items[:0]
		for _, item := range items {
			if !a.Namespace.Decide(a.User, "workflow:"+item.ID.String(), "discover").Allowed {
				continue
			}
			if !a.Namespace.Decide(a.User, "workflow:"+item.ID.String(), "inspect").Allowed {
				item.DraftSpec = nil
			}
			filtered = append(filtered, item)
		}
		items = filtered
	}
	c.JSON(http.StatusOK, gin.H{"definitions": items})
}
func (s *Server) getOrchestrationDefinition(c *gin.Context) {
	id, ok := parseUUIDParam(c, "definitionId")
	if !ok {
		return
	}
	item, err := s.store.Orchestration().GetDefinition(c.Request.Context(), id)
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"definition": item})
}
func (s *Server) patchOrchestrationDefinition(c *gin.Context) {
	id, ok := parseUUIDParam(c, "definitionId")
	if !ok {
		return
	}
	current, err := s.store.Orchestration().GetDefinition(c.Request.Context(), id)
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	var req struct {
		Name            *string         `json:"name"`
		Description     *string         `json:"description"`
		DraftSpec       json.RawMessage `json:"draftSpec"`
		ExpectedVersion int64           `json:"expectedVersion"`
		Archived        *bool           `json:"archived"`
	}
	if err = c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if req.Name != nil {
		current.Name = *req.Name
	}
	if req.Description != nil {
		current.Description = *req.Description
	}
	if len(req.DraftSpec) > 0 {
		var draft struct {
			Nodes []json.RawMessage `json:"nodes"`
		}
		if err = json.Unmarshal(req.DraftSpec, &draft); err != nil || draft.Nodes == nil {
			if err == nil {
				err = fmt.Errorf("draftSpec.nodes must be an array")
			}
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
			return
		}
		current.DraftSpec = req.DraftSpec
	}
	if req.Archived != nil {
		current.ArchivedAt = nil
		if *req.Archived {
			now := time.Now().UTC()
			current.ArchivedAt = &now
		}
	}
	expected := req.ExpectedVersion
	if expected == 0 {
		expected = current.Version
	}
	updated, err := s.store.Orchestration().UpdateDefinition(c.Request.Context(), current, expected)
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"definition": updated})
}
func (s *Server) validateOrchestrationDefinition(c *gin.Context) {
	id, ok := parseUUIDParam(c, "definitionId")
	if !ok {
		return
	}
	definition, err := s.store.Orchestration().GetDefinition(c.Request.Context(), id)
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	var req struct {
		Spec json.RawMessage `json:"spec"`
	}
	_ = c.ShouldBindJSON(&req)
	if len(req.Spec) == 0 {
		req.Spec = definition.DraftSpec
	}
	spec, err := s.orchestrationService().ValidateDefinition(req.Spec)
	if err != nil {
		c.JSON(http.StatusUnprocessableEntity, gin.H{"valid": false, "error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"valid": true, "spec": spec})
}
func (s *Server) publishOrchestrationDefinition(c *gin.Context) {
	id, ok := parseUUIDParam(c, "definitionId")
	if !ok {
		return
	}
	var request struct {
		ExpectedVersion int64 `json:"expectedVersion"`
	}
	if c.Request.ContentLength > 0 {
		if err := c.ShouldBindJSON(&request); err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
			return
		}
	}
	revision, err := s.orchestrationService().Publish(c.Request.Context(), id, collaborationActor(c, s), request.ExpectedVersion)
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"revision": revision})
}
func (s *Server) listOrchestrationRevisions(c *gin.Context) {
	id, ok := parseUUIDParam(c, "definitionId")
	if !ok {
		return
	}
	items, err := s.store.Orchestration().ListRevisions(c.Request.Context(), id)
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"revisions": items})
}
func (s *Server) startOrchestrationRun(c *gin.Context) {
	id, ok := parseUUIDParam(c, "definitionId")
	if !ok {
		return
	}
	var req orchestration.StartRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	req.Actor = collaborationActor(c, s)
	if a := accessFrom(c); a != nil && len(a.Namespace.Resources) > 0 {
		var revision *controlmodel.OrchestrationRevision
		var err error
		if req.RevisionID != nil {
			revision, err = s.store.Orchestration().GetRevision(c.Request.Context(), *req.RevisionID)
		} else {
			revisions, e := s.store.Orchestration().ListRevisions(c.Request.Context(), id)
			err = e
			if len(revisions) > 0 {
				revision = revisions[0]
			}
		}
		if err != nil || revision == nil || revision.DefinitionID != id {
			s.accessFailure(c, store.ErrNotFound)
			return
		}
		items, err := s.resourceInventory(c.Request.Context(), a.Namespace)
		if err != nil {
			c.JSON(503, ErrorResponse{Error: "Unable to resolve Workflow dependencies"})
			return
		}
		graph := resourceMap(items)
		err = s.applyPublishedResourceDependencies(c.Request.Context(), a.Namespace, graph, revision, map[uuid.UUID]bool{})
		if err == nil {
			err = checkResourceGraph(a.Namespace, graph, a.User, "workflow:"+id.String())
		}
		if err != nil {
			c.JSON(403, ErrorResponse{Error: err.Error()})
			return
		}
		req.RevisionID = &revision.ID
	}
	run, err := s.orchestrationService().Start(c.Request.Context(), id, req)
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"run": run})
}

func (s *Server) listOrchestrationRuns(c *gin.Context) {
	tenant, namespace, ok := requireCollaborationScope(c)
	if !ok {
		return
	}
	f := store.OrchestrationRunFilter{Tenant: tenant, Namespace: namespace, State: controlmodel.OrchestrationRunState(c.Query("state")), ActiveOnly: c.Query("active") == "true", Offset: max(0, queryInt(c, "offset", 0)), Limit: queryInt(c, "limit", 100)}
	if raw := c.Query("definitionId"); raw != "" {
		var err error
		f.DefinitionID, err = uuid.Parse(raw)
		if err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid definitionId"})
			return
		}
	}
	if raw := c.Query("issueId"); raw != "" {
		var err error
		f.IssueID, err = uuid.Parse(raw)
		if err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid issueId"})
			return
		}
	}
	items, err := s.store.Orchestration().ListRuns(c.Request.Context(), f)
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"runs": items})
}
func (s *Server) getOrchestrationRun(c *gin.Context) {
	id, ok := parseUUIDParam(c, "runId")
	if !ok {
		return
	}
	run, err := s.store.Orchestration().GetRun(c.Request.Context(), id)
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"run": run})
}
func (s *Server) getOrchestrationGraph(c *gin.Context) {
	id, ok := parseUUIDParam(c, "runId")
	if !ok {
		return
	}
	graph, err := s.orchestrationService().Graph(c.Request.Context(), id)
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	s.attachAttemptSessionRefs(c.Request.Context(), graph.Attempts)
	c.JSON(http.StatusOK, graph)
}
func (s *Server) listOrchestrationEvents(c *gin.Context) {
	id, ok := parseUUIDParam(c, "runId")
	if !ok {
		return
	}
	after, _ := strconv.ParseInt(c.Query("after"), 10, 64)
	events, err := s.store.Orchestration().ListRunEvents(c.Request.Context(), id, after, queryInt(c, "limit", 200))
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"events": events})
}
func (s *Server) pauseOrchestrationRun(c *gin.Context) {
	s.mutateRun(c, s.orchestrationService().Pause)
}
func (s *Server) resumeOrchestrationRun(c *gin.Context) {
	s.mutateRun(c, s.orchestrationService().Resume)
}
func (s *Server) cancelOrchestrationRun(c *gin.Context) {
	s.mutateRun(c, s.orchestrationService().Cancel)
}
func (s *Server) rerunOrchestrationRun(c *gin.Context) {
	id, ok := parseUUIDParam(c, "runId")
	if !ok {
		return
	}
	var req struct {
		IdempotencyKey string          `json:"idempotencyKey"`
		Input          json.RawMessage `json:"input,omitempty"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	run, err := s.orchestrationService().Rerun(c.Request.Context(), id, req.IdempotencyKey,
		req.Input, collaborationActor(c, s))
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"run": run})
}
func (s *Server) mutateRun(c *gin.Context, fn func(context.Context, uuid.UUID) (*controlmodel.OrchestrationRun, error)) {
	id, ok := parseUUIDParam(c, "runId")
	if !ok {
		return
	}
	run, err := fn(c.Request.Context(), id)
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"run": run})
}
func (s *Server) signalOrchestrationRun(c *gin.Context) {
	id, ok := parseUUIDParam(c, "runId")
	if !ok {
		return
	}
	name := strings.TrimSpace(c.Param("name"))
	var req struct {
		IdempotencyKey string          `json:"idempotencyKey"`
		Payload        json.RawMessage `json:"payload"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if err := s.orchestrationService().Signal(c.Request.Context(), id, name, req.IdempotencyKey, req.Payload, collaborationActor(c, s)); err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.Status(http.StatusAccepted)
}

func (s *Server) getAgentRuntimePolicy(c *gin.Context) {
	tenant, namespace, ok := requireCollaborationScope(c)
	if !ok {
		return
	}
	agentID, err := uuid.Parse(c.Param("agentId"))
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid agentId"})
		return
	}
	policy, err := s.store.Orchestration().GetRuntimePolicy(c.Request.Context(), tenant, namespace, agentID.String())
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"policy": policy})
}
func (s *Server) putAgentRuntimePolicy(c *gin.Context) {
	var in controlmodel.AgentRuntimePolicy
	if err := c.ShouldBindJSON(&in); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	agentID, err := uuid.Parse(c.Param("agentId"))
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid agentId"})
		return
	}
	agent, err := s.store.AgentCatalog().GetAgent(c.Request.Context(), agentID)
	if err != nil || agent.Tenant != in.Tenant || agent.Namespace != in.Namespace {
		if err == nil {
			err = store.ErrNotFound
		}
		s.writeOrchestrationError(c, err)
		return
	}
	in.AgentRef = agentID.String()
	if in.Tenant == "" || in.Namespace == "" || len(in.Candidates) == 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "tenant, namespace, and candidates are required"})
		return
	}
	if in.SelectionMode == "" {
		in.SelectionMode = "ordered"
	}
	if in.FallbackMode == "" {
		in.FallbackMode = "disabled"
	}
	if in.SelectionMode != "ordered" || in.FallbackMode != "disabled" && in.FallbackMode != "fresh" ||
		in.MaxConcurrency < 0 || in.QueueTimeoutSeconds < 0 || in.AttemptTimeoutSeconds < 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "runtime policy requires ordered selection, disabled|fresh fallback, and non-negative limits"})
		return
	}
	for i := range in.Candidates {
		candidate := &in.Candidates[i]
		if candidate.Binding.BindingID == uuid.Nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "bindingId is required"})
			return
		}
		binding, err := s.store.AgentCatalog().GetBinding(c.Request.Context(), candidate.Binding.BindingID)
		if err != nil || binding.AgentID != agentID || !binding.Enabled || binding.ArchivedAt != nil {
			if err == nil {
				err = store.ErrNotFound
			}
			s.writeOrchestrationError(c, err)
			return
		}
		candidate.Binding, err = binding.RuntimeBinding()
		if err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
			return
		}
		if err := controlmodel.ValidateJSONObject(candidate.RequiredCapabilities, "requiredCapabilities"); err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
			return
		}
		if err := controlmodel.ValidateJSONObject(candidate.SecurityConstraints, "securityConstraints"); err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
			return
		}
		candidate.SelectionSource = ""
		candidate.CandidateIndex = 0
	}
	policy, err := s.store.Orchestration().PutRuntimePolicy(c.Request.Context(), &in)
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"policy": policy})
}
func (s *Server) listExecutionAttempts(c *gin.Context) {
	tenant, namespace, ok := requireCollaborationScope(c)
	if !ok {
		return
	}
	f := store.ExecutionAttemptFilter{Tenant: tenant, Namespace: namespace, State: controlmodel.ExecutionAttemptState(c.Query("state")), Limit: queryInt(c, "limit", 100)}
	if raw := c.Query("taskId"); raw != "" {
		f.AgentTaskID, _ = uuid.Parse(raw)
	}
	items, err := s.store.ExecutionAttempts().List(c.Request.Context(), f)
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	s.attachAttemptSessionRefs(c.Request.Context(), items)
	c.JSON(http.StatusOK, gin.H{"attempts": items})
}
func (s *Server) getExecutionAttempt(c *gin.Context) {
	id, ok := parseUUIDParam(c, "attemptId")
	if !ok {
		return
	}
	attempt, err := s.store.ExecutionAttempts().Get(c.Request.Context(), id)
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	s.attachAttemptSessionRefs(c.Request.Context(), []*controlmodel.ExecutionAttempt{attempt})
	c.JSON(http.StatusOK, gin.H{"attempt": attempt})
}

// attachAttemptSessionRefs connects runtime-reported session IDs to the
// control-plane Session primary key used by diagnostics routes. Runtime IDs
// are allowed to look like UUIDs, so linking by SessionID alone is ambiguous.
func (s *Server) attachAttemptSessionRefs(ctx context.Context, attempts []*controlmodel.ExecutionAttempt) {
	for _, attempt := range attempts {
		if attempt == nil || attempt.AgentTaskID == uuid.Nil {
			continue
		}
		sessions, err := s.store.Sessions().List(ctx, store.SessionFilter{
			Tenant: attempt.Tenant, Namespace: attempt.Namespace, AgentID: attempt.AgentID,
			SessionID: attempt.SessionID, AgentTaskID: attempt.AgentTaskID, Limit: 2,
		})
		if err != nil {
			continue
		}
		if len(sessions) == 0 && attempt.BackendKind == controlmodel.DataPlaneHostedRuntime && attempt.SessionID != "" {
			// Hosted Chat reuses a session whose AgentTaskID advances each turn.
			// Resolve older attempts by the full agent/binding/runtime identity.
			candidates, lookupErr := s.store.Sessions().List(ctx, store.SessionFilter{
				Tenant: attempt.Tenant, Namespace: attempt.Namespace, AgentID: attempt.AgentID,
				SessionID: attempt.SessionID, Limit: 2})
			if lookupErr == nil && len(candidates) == 1 && candidates[0].BindingID == attempt.BindingID {
				sessions = candidates
			}
		}
		if len(sessions) == 0 && attempt.BackendKind == controlmodel.DataPlaneHostedRuntime {
			if projected, projectErr := s.ensureHostedTaskSession(ctx, attempt); projectErr == nil && projected != nil {
				// Keep the same private Issue access check as every Session detail read.
				visible, readErr := s.store.Sessions().List(ctx, store.SessionFilter{Tenant: attempt.Tenant, Namespace: attempt.Namespace, AgentID: attempt.AgentID, SessionID: attempt.SessionID, AgentTaskID: attempt.AgentTaskID, Limit: 2})
				if readErr == nil {
					sessions = visible
				}
			}
		}
		if len(sessions) != 1 {
			continue
		}
		// AgentTaskID and the selected Agent identity make this deterministic;
		// SessionID further separates retries that use a fresh runtime session.
		attempt.SessionRef = &sessions[0].ID
	}
}

func (s *Server) getTaskRun(c *gin.Context) {
	task, ok := taskPrincipal(c)
	if !ok {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "task token required"})
		return
	}
	run, err := s.store.Orchestration().GetRun(c.Request.Context(), task.OrchestrationRunID)
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"run": run})
}
func (s *Server) getTaskRunGraph(c *gin.Context) {
	task, ok := taskPrincipal(c)
	if !ok {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "task token required"})
		return
	}
	graph, err := s.orchestrationService().Graph(c.Request.Context(), task.OrchestrationRunID)
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusOK, graph)
}
func (s *Server) completeTaskRunNode(c *gin.Context) {
	task, ok := taskPrincipal(c)
	if !ok {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "task token required"})
		return
	}
	var req struct {
		Output json.RawMessage `json:"output"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	completed, node, err := s.concludeCoordinator(c.Request.Context(), task, req.Output, collaborationActor(c, s))
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"task": completed, "node": node})
}
func (s *Server) failTaskRunNode(c *gin.Context) {
	task, ok := taskPrincipal(c)
	if !ok {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "task token required"})
		return
	}
	var req struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	failed, node, err := s.failCoordinator(c.Request.Context(), task, req.Code, req.Message, collaborationActor(c, s))
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"task": failed, "node": node})
}
func (s *Server) replanTaskRun(c *gin.Context) {
	task, ok := taskPrincipal(c)
	if !ok {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "task token required"})
		return
	}
	var req orchestration.DefinitionNode
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	node, err := s.orchestrationService().Replan(c.Request.Context(), task.ID, req, collaborationActor(c, s))
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"node": node})
}
func (s *Server) signalTaskRun(c *gin.Context) {
	task, ok := taskPrincipal(c)
	if !ok {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "task token required"})
		return
	}
	name := c.Param("name")
	var req struct {
		IdempotencyKey string          `json:"idempotencyKey"`
		Payload        json.RawMessage `json:"payload"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if err := s.orchestrationService().Signal(c.Request.Context(), task.OrchestrationRunID, name, req.IdempotencyKey, req.Payload, collaborationActor(c, s)); err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.Status(http.StatusAccepted)
}
func (s *Server) getTaskRunArtifacts(c *gin.Context) {
	task, ok := taskPrincipal(c)
	if !ok {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "task token required"})
		return
	}
	items, err := s.store.Collaboration().ListArtifacts(c.Request.Context(), task.Tenant, task.Namespace, "issue", task.IssueID.String())
	if err != nil {
		s.writeOrchestrationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"artifacts": items})
}

func queryInt(c *gin.Context, name string, fallback int) int {
	value, err := strconv.Atoi(c.Query(name))
	if err != nil || value <= 0 {
		return fallback
	}
	return value
}

func (s *Server) writeOrchestrationError(c *gin.Context, err error) {
	if errors.Is(err, orchestration.ErrInvalidDefinition) {
		c.JSON(http.StatusUnprocessableEntity, ErrorResponse{Error: err.Error()})
		return
	}
	s.writeCollaborationError(c, err)
}

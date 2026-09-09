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
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/automation"
	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/metrics"
	"github.com/spring-ai-alibaba/aistio/internal/orchestration"
	"github.com/spring-ai-alibaba/aistio/internal/scheduler"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func (s *Server) collaborationService() *collaboration.Service {
	return &collaboration.Service{Store: s.store}
}

func (s *Server) automationService() *automation.Service { return &automation.Service{Store: s.store} }

func (s *Server) collaborationEventStream(c *gin.Context) {
	tenant, namespace := strings.TrimSpace(c.Query("tenant")), strings.TrimSpace(c.Query("namespace"))
	if tenant == "" || namespace == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "tenant and namespace are required"})
		return
	}
	if a := accessFrom(c); a != nil {
		s.collaborationEvents.ServeAuthorized(c.Writer, c.Request, tenant, namespace, func(ctx context.Context, e *controlmodel.OutboxEvent) bool { return s.canReceiveWorkEvent(ctx, a, e) })
		return
	}
	s.collaborationEvents.Serve(c.Writer, c.Request, tenant, namespace)
}

func humanActor(c *gin.Context, s *Server) controlmodel.Actor {
	if internal, _ := c.Get(ctxInternalAuth); internal == true {
		return controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "internal"}
	}
	return controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: catalogOwnerRef(c, s.operatorFromContext(c))}
}

func taskPrincipal(c *gin.Context) (*controlmodel.AgentTask, bool) {
	value, ok := c.Get(ctxTaskAuth)
	task, valid := value.(*controlmodel.AgentTask)
	return task, ok && valid && task != nil
}

func collaborationActor(c *gin.Context, s *Server) controlmodel.Actor {
	if task, ok := taskPrincipal(c); ok {
		return controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
	}
	return humanActor(c, s)
}

// requireHumanPrincipal preserves the product meaning of Inbox and approval
// ownership: "you" is the authenticated human user, never an AgentTask or a
// data-plane process using the shared internal credential.
func requireHumanPrincipal(c *gin.Context) bool {
	if internal, _ := c.Get(ctxInternalAuth); internal == true {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "a human principal is required"})
		return false
	}
	return true
}

func requireCollaborationScope(c *gin.Context) (string, string, bool) {
	tenant, namespace := strings.TrimSpace(c.Query("tenant")), strings.TrimSpace(c.Query("namespace"))
	if tenant == "" || namespace == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "tenant and namespace are required"})
		return "", "", false
	}
	return tenant, namespace, true
}

func collaborationPagination(c *gin.Context) (int, int) {
	limit, err := strconv.Atoi(c.DefaultQuery("limit", "100"))
	if err != nil || limit <= 0 {
		limit = 100
	}
	// Cursor endpoints request limit+1 from stores whose safety ceiling is 500.
	if limit > 499 {
		limit = 499
	}
	offset, err := strconv.Atoi(c.DefaultQuery("offset", "0"))
	if err != nil || offset < 0 {
		offset = 0
	}
	return limit, offset
}

type issueRequest struct {
	Access              controlmodel.IssueAccess  `json:"access"`
	Tenant              string                    `json:"tenant"`
	Namespace           string                    `json:"namespace"`
	Title               string                    `json:"title"`
	Description         string                    `json:"description,omitempty"`
	Priority            string                    `json:"priority,omitempty"`
	AssigneeType        controlmodel.AssigneeType `json:"assigneeType,omitempty"`
	AssigneeRef         string                    `json:"assigneeRef,omitempty"`
	ExecutionTargetType string                    `json:"executionTargetType,omitempty"`
	ExecutionTargetRef  string                    `json:"executionTargetRef,omitempty"`
	ParentIssueID       *uuid.UUID                `json:"parentIssueId,omitempty"`
	AcceptanceCriteria  json.RawMessage           `json:"acceptanceCriteria,omitempty"`
	ContextRefs         json.RawMessage           `json:"contextRefs,omitempty"`
	SourceType          string                    `json:"sourceType,omitempty"`
	SourceRef           string                    `json:"sourceRef,omitempty"`
	DueAt               *time.Time                `json:"dueAt,omitempty"`
}

func (s *Server) createIssue(c *gin.Context) {
	var req issueRequest
	if err := c.ShouldBindJSON(&req); err != nil || strings.TrimSpace(req.Title) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "title is required"})
		return
	}
	if strings.TrimSpace(req.Tenant) == "" || strings.TrimSpace(req.Namespace) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "tenant and namespace are required"})
		return
	}
	if req.Access.Mode == "" {
		req.Access.Mode = "private"
	}
	if err := req.Access.Validate(); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	if a := accessFrom(c); a != nil {
		for member := range req.Access.Members {
			if len(a.Namespace.Roles(member)) == 0 {
				c.JSON(400, ErrorResponse{Error: "collaborators must be namespace members"})
				return
			}
		}
	}
	if req.AssigneeType == controlmodel.AssigneeAgent {
		if _, err := s.activeAgentInScope(c.Request.Context(), req.Tenant, req.Namespace, req.AssigneeRef); err != nil {
			if _, parseErr := uuid.Parse(req.AssigneeRef); parseErr != nil {
				c.JSON(http.StatusBadRequest, ErrorResponse{Error: "assigneeRef must be a valid agentId"})
			} else {
				s.writeCollaborationError(c, err)
			}
			return
		}
	}
	var workflowDefinition *controlmodel.OrchestrationDefinition
	var workflowRevision *controlmodel.OrchestrationRevision
	if req.ExecutionTargetType != "" || req.ExecutionTargetRef != "" {
		if req.ExecutionTargetType != "workflow" || req.ExecutionTargetRef == "" {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "execution target must be a Workflow"})
			return
		}
		if req.AssigneeType != "" || req.AssigneeRef != "" {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "Workflow execution target cannot also be an assignee"})
			return
		}
		definitionID, parseErr := uuid.Parse(req.ExecutionTargetRef)
		if parseErr != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "executionTargetRef must be a valid workflowId"})
			return
		}
		definition, definitionErr := s.store.Orchestration().GetDefinition(c.Request.Context(), definitionID)
		workflowDefinition = definition
		if definitionErr != nil || workflowDefinition.Tenant != req.Tenant || workflowDefinition.Namespace != req.Namespace || workflowDefinition.ArchivedAt != nil {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "Workflow target was not found in scope"})
			return
		}
		revisions, revisionErr := s.store.Orchestration().ListRevisions(c.Request.Context(), definitionID)
		if revisionErr != nil {
			s.writeCollaborationError(c, revisionErr)
			return
		}
		if len(revisions) == 0 {
			c.JSON(http.StatusConflict, ErrorResponse{Error: "Workflow must have a published revision before it can be selected for an Issue"})
			return
		}
		workflowRevision = revisions[0]
		req.ExecutionTargetType = string(controlmodel.EndpointTargetOrchestrationRevision)
		req.ExecutionTargetRef = workflowRevision.ID.String()
	}
	issue, task, err := s.collaborationService().CreateIssue(c.Request.Context(), collaboration.CreateIssueRequest{
		Access: req.Access, Tenant: req.Tenant, Namespace: req.Namespace, Title: req.Title,
		Description: req.Description, Priority: req.Priority, Creator: humanActor(c, s),
		AssigneeType: req.AssigneeType, AssigneeRef: req.AssigneeRef,
		ExecutionTargetType: req.ExecutionTargetType, ExecutionTargetRef: req.ExecutionTargetRef,
		ParentIssueID: req.ParentIssueID, AcceptanceCriteria: req.AcceptanceCriteria,
		ContextRefs: req.ContextRefs, SourceType: req.SourceType, SourceRef: req.SourceRef, DueAt: req.DueAt,
	})
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	if workflowDefinition != nil && workflowRevision != nil {
		actor := humanActor(c, s)
		run, startErr := s.orchestrationService().Start(c.Request.Context(), workflowDefinition.ID, orchestration.StartRequest{
			RevisionID: &workflowRevision.ID, IdempotencyKey: "issue:" + issue.ID.String(),
			Input: json.RawMessage(`{}`), IssueID: &issue.ID, TriggerType: "issue",
			TriggerRef: issue.ID.String(), Actor: actor,
		})
		if startErr != nil {
			s.writeCollaborationError(c, startErr)
			return
		}
		if current, loadErr := s.store.Collaboration().GetIssue(c.Request.Context(), issue.ID); loadErr == nil {
			issue = current
		}
		c.JSON(http.StatusCreated, gin.H{"issue": issue, "orchestrationRun": run})
		return
	}
	c.JSON(http.StatusCreated, gin.H{"issue": issue, "agentTask": task})
}

func (s *Server) listIssues(c *gin.Context) {
	tenant, namespace, ok := requireCollaborationScope(c)
	if !ok {
		return
	}
	limit, offset := collaborationPagination(c)
	var parentID *uuid.UUID
	if raw := c.Query("parentIssueId"); raw != "" {
		id, err := uuid.Parse(raw)
		if err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid parentIssueId"})
			return
		}
		parentID = &id
	}
	cursorTime, cursorID, cursorErr := decodeCollaborationCursor(c.Query("cursor"))
	if cursorErr != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid cursor"})
		return
	}
	items, err := s.store.Collaboration().ListIssues(c.Request.Context(), store.IssueFilter{
		Tenant: tenant, Namespace: namespace,
		Status:       controlmodel.IssueStatus(c.Query("status")),
		AssigneeType: controlmodel.AssigneeType(c.Query("assigneeType")),
		AssigneeRef:  c.Query("assigneeRef"), ParentID: parentID, Limit: limit + 1, Offset: offset,
		CursorTime: cursorTime, CursorID: cursorID, Search: c.Query("search"), Archived: c.Query("archived") == "true",
		Kind: controlmodel.IssueKind(c.Query("kind")), Visibility: issueListVisibility(c),
	})
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	next := ""
	if len(items) > limit {
		items = items[:limit]
		last := items[len(items)-1]
		next = encodeCollaborationCursor(last.UpdatedAt, last.ID)
	}
	c.JSON(http.StatusOK, gin.H{"items": items, "nextCursor": next})
}

func issueListVisibility(c *gin.Context) controlmodel.IssueVisibility {
	if c.Query("includeOperational") == "true" {
		return ""
	}
	if value := controlmodel.IssueVisibility(c.Query("visibility")); value != "" {
		return value
	}
	return controlmodel.IssueVisibilityWorkHub
}

func (s *Server) getIssue(c *gin.Context) {
	id, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	issue, err := s.store.Collaboration().GetIssue(c.Request.Context(), id)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"issue": issue})
}

func (s *Server) updateIssue(c *gin.Context) {
	id, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	current, err := s.store.Collaboration().GetIssue(c.Request.Context(), id)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	var req struct {
		Title              *string          `json:"title"`
		Description        *string          `json:"description"`
		Priority           *string          `json:"priority"`
		AcceptanceCriteria *json.RawMessage `json:"acceptanceCriteria"`
		ContextRefs        *json.RawMessage `json:"contextRefs"`
		DueAt              json.RawMessage  `json:"dueAt"`
		ExpectedVersion    int64            `json:"expectedVersion"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if req.Title != nil {
		current.Title = strings.TrimSpace(*req.Title)
	}
	if req.Description != nil {
		current.Description = *req.Description
	}
	if req.Priority != nil {
		current.Priority = *req.Priority
	}
	if req.AcceptanceCriteria != nil {
		if err := validateIssueCriteria(*req.AcceptanceCriteria); err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
			return
		}
		current.AcceptanceCriteria = *req.AcceptanceCriteria
	}
	if req.ContextRefs != nil {
		current.ContextRefs = *req.ContextRefs
	}
	if len(req.DueAt) > 0 {
		// An omitted PATCH field preserves the date; an explicit null clears it.
		var dueAt *time.Time
		if err := json.Unmarshal(req.DueAt, &dueAt); err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "dueAt must be an RFC3339 timestamp or null"})
			return
		}
		current.DueAt = dueAt
	}
	if req.ExpectedVersion <= 0 {
		req.ExpectedVersion = current.Version
	}
	if current.Version != req.ExpectedVersion {
		s.writeCollaborationError(c, store.ErrConflict)
		return
	}
	if req.Title != nil || req.Description != nil {
		command := map[string]any{}
		if req.Title != nil {
			command["title"] = current.Title
		}
		if req.Description != nil {
			command["body"] = current.Description
		}
		payload, _ := json.Marshal(command)
		if err := s.workSources.ApplyIssueCommand(c.Request.Context(), id, "update", payload); err != nil {
			c.JSON(http.StatusBadGateway, ErrorResponse{Error: err.Error()})
			return
		}
	}
	updated, err := s.store.Collaboration().UpdateIssue(c.Request.Context(), current, req.ExpectedVersion, humanActor(c, s))
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"issue": updated})
}

func (s *Server) createChildIssue(c *gin.Context) {
	parentID, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	parent, err := s.store.Collaboration().GetIssue(c.Request.Context(), parentID)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	var req issueRequest
	if err := c.ShouldBindJSON(&req); err != nil || strings.TrimSpace(req.Title) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "title is required"})
		return
	}
	issue, task, err := s.collaborationService().CreateIssue(c.Request.Context(), collaboration.CreateIssueRequest{
		Tenant: parent.Tenant, Namespace: parent.Namespace, Title: req.Title,
		Description: req.Description, Priority: req.Priority, Creator: humanActor(c, s),
		AssigneeType: req.AssigneeType, AssigneeRef: req.AssigneeRef, ParentIssueID: &parent.ID,
		AcceptanceCriteria: req.AcceptanceCriteria, ContextRefs: req.ContextRefs,
		SourceType: req.SourceType, SourceRef: req.SourceRef, DueAt: req.DueAt,
	})
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"issue": issue, "agentTask": task})
}

func (s *Server) transitionIssue(c *gin.Context) {
	id, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	var req struct {
		Status          controlmodel.IssueStatus `json:"status"`
		Reason          string                   `json:"reason,omitempty"`
		ExpectedVersion int64                    `json:"expectedVersion"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Status == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "status is required"})
		return
	}
	actor := humanActor(c, s)
	if _, err := s.collaborationService().ValidateIssueTransition(c.Request.Context(), id, req.ExpectedVersion, req.Status, actor); err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	payload, _ := json.Marshal(gin.H{"status": req.Status, "reason": req.Reason, "expectedVersion": req.ExpectedVersion})
	if err := s.workSources.ApplyIssueCommand(c.Request.Context(), id, "transition", payload); err != nil {
		c.JSON(http.StatusBadGateway, ErrorResponse{Error: err.Error()})
		return
	}
	issue, err := s.collaborationService().TransitionIssue(c.Request.Context(), id,
		req.ExpectedVersion, req.Status, actor, req.Reason)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"issue": issue})
}

func (s *Server) acceptIssue(c *gin.Context) {
	s.reviewIssue(c, controlmodel.IssueDone, "accepted")
}

func (s *Server) rejectIssue(c *gin.Context) {
	s.reviewIssue(c, controlmodel.IssueInProgress, "rejected")
}

func (s *Server) reopenIssue(c *gin.Context) {
	s.reviewIssue(c, controlmodel.IssueInProgress, "reopened")
}

func (s *Server) reviewIssue(c *gin.Context, status controlmodel.IssueStatus, defaultReason string) {
	id, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	var req struct {
		ExpectedVersion int64  `json:"expectedVersion"`
		Reason          string `json:"reason,omitempty"`
	}
	if err := c.ShouldBindJSON(&req); err != nil && defaultReason != "reopened" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid review request"})
		return
	}
	if defaultReason != "reopened" {
		if req.ExpectedVersion <= 0 {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "expectedVersion is required for review"})
			return
		}
		if defaultReason == "rejected" && strings.TrimSpace(req.Reason) == "" {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "describe the changes needed before returning this work"})
			return
		}
		current, err := s.store.Collaboration().GetIssue(c.Request.Context(), id)
		if err != nil {
			s.writeCollaborationError(c, err)
			return
		}
		if current.Status != controlmodel.IssueInReview || current.ArchivedAt != nil {
			c.JSON(http.StatusConflict, ErrorResponse{Error: "this issue is no longer awaiting review"})
			return
		}
	}
	if strings.TrimSpace(req.Reason) == "" {
		req.Reason = defaultReason
	}
	actor := humanActor(c, s)
	if _, err := s.collaborationService().ValidateIssueTransition(c.Request.Context(), id, req.ExpectedVersion, status, actor); err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	payload, _ := json.Marshal(gin.H{"status": status, "reason": req.Reason, "expectedVersion": req.ExpectedVersion})
	if err := s.workSources.ApplyIssueCommand(c.Request.Context(), id, "transition", payload); err != nil {
		c.JSON(http.StatusBadGateway, ErrorResponse{Error: err.Error()})
		return
	}
	issue, err := s.collaborationService().TransitionIssue(c.Request.Context(), id,
		req.ExpectedVersion, status, actor, req.Reason)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"issue": issue})
}

func (s *Server) archiveIssue(c *gin.Context) {
	id, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	var req struct {
		ExpectedVersion int64 `json:"expectedVersion"`
	}
	_ = c.ShouldBindJSON(&req)
	issue, err := s.store.Collaboration().ArchiveIssue(c.Request.Context(), id, req.ExpectedVersion, humanActor(c, s))
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"issue": issue})
}

func (s *Server) getIssueSummary(c *gin.Context) {
	id, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	issue, err := s.store.Collaboration().GetIssue(c.Request.Context(), id)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	comments, tasks, children, err := s.loadIssueCollections(c.Request.Context(), issue)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"summary": collaborationSummary(issue, comments, tasks, children)})
}

func (s *Server) exportIssue(c *gin.Context) {
	id, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	issue, err := s.store.Collaboration().GetIssue(c.Request.Context(), id)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	comments, tasks, children, err := s.loadIssueCollections(c.Request.Context(), issue)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	artifacts, err := s.store.Collaboration().ListArtifacts(c.Request.Context(), issue.Tenant, issue.Namespace, "issue", id.String())
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	activities, err := s.loadAllActivities(c.Request.Context(), id)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	subscribers, err := s.store.Collaboration().ListIssueSubscribers(c.Request.Context(), id)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	runs, err := s.store.Orchestration().ListRuns(c.Request.Context(), store.OrchestrationRunFilter{
		Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: issue.ID, Limit: 100,
	})
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	runDiagnostics := make([]gin.H, 0, len(runs))
	for _, run := range runs {
		graph, graphErr := s.orchestrationService().Graph(c.Request.Context(), run.ID)
		if graphErr != nil {
			s.writeCollaborationError(c, graphErr)
			return
		}
		s.attachAttemptSessionRefs(c.Request.Context(), graph.Attempts)
		events, eventErr := s.store.Orchestration().ListRunEvents(c.Request.Context(), run.ID, 0, 1000)
		if eventErr != nil {
			s.writeCollaborationError(c, eventErr)
			return
		}
		toolFailures := make([]*controlmodel.RunEvent, 0)
		dispatchDeferrals := make([]*controlmodel.RunEvent, 0)
		dispatchRecoveries := make([]*controlmodel.RunEvent, 0)
		for _, event := range events {
			if event.Type == "agent_tool.failed" {
				toolFailures = append(toolFailures, event)
			}
			if event.Type == "agent-task.dispatch_deferred" {
				dispatchDeferrals = append(dispatchDeferrals, event)
			}
			if event.Type == "agent-task.dispatch_recovered" {
				dispatchRecoveries = append(dispatchRecoveries, event)
			}
		}
		runDiagnostics = append(runDiagnostics, gin.H{"graph": graph, "events": events,
			"toolFailures": toolFailures, "dispatchDeferrals": dispatchDeferrals,
			"dispatchRecoveries": dispatchRecoveries})
	}
	c.Header("Content-Disposition", `attachment; filename="issue-`+id.String()+`.json"`)
	c.JSON(http.StatusOK, gin.H{"schemaVersion": 2, "exportedAt": time.Now().UTC(), "issue": issue,
		"comments": comments, "tasks": tasks, "children": children, "artifacts": artifacts,
		"subscribers": subscribers, "activity": activities, "runs": runs,
		"runDiagnostics": runDiagnostics})
}

func (s *Server) loadIssueCollections(ctx context.Context, issue *controlmodel.Issue) ([]*controlmodel.Comment, []*controlmodel.AgentTask, []*controlmodel.Issue, error) {
	comments := make([]*controlmodel.Comment, 0)
	tasks := make([]*controlmodel.AgentTask, 0)
	children := make([]*controlmodel.Issue, 0)
	for offset := 0; ; offset += 500 {
		page, err := s.store.Collaboration().ListComments(ctx, issue.ID, store.CommentListOptions{Limit: 500, Offset: offset})
		if err != nil {
			return nil, nil, nil, err
		}
		comments = append(comments, page...)
		if len(page) < 500 {
			break
		}
	}
	for offset := 0; ; offset += 500 {
		page, err := s.store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{Tenant: issue.Tenant, Namespace: issue.Namespace, IssueID: issue.ID, Limit: 500, Offset: offset})
		if err != nil {
			return nil, nil, nil, err
		}
		tasks = append(tasks, page...)
		if len(page) < 500 {
			break
		}
	}
	for offset := 0; ; offset += 500 {
		page, err := s.store.Collaboration().ListIssues(ctx, store.IssueFilter{Tenant: issue.Tenant, Namespace: issue.Namespace, ParentID: &issue.ID, Limit: 500, Offset: offset})
		if err != nil {
			return nil, nil, nil, err
		}
		children = append(children, page...)
		if len(page) < 500 {
			break
		}
	}
	return comments, tasks, children, nil
}

func (s *Server) loadAllActivities(ctx context.Context, issueID uuid.UUID) ([]*controlmodel.Activity, error) {
	var out []*controlmodel.Activity
	for offset := 0; ; offset += 500 {
		page, err := s.store.Collaboration().ListActivities(ctx, issueID, 500, offset)
		if err != nil {
			return nil, err
		}
		out = append(out, page...)
		if len(page) < 500 {
			return out, nil
		}
	}
}

func collaborationSummary(issue *controlmodel.Issue, comments []*controlmodel.Comment, tasks []*controlmodel.AgentTask, children []*controlmodel.Issue) gin.H {
	result := ""
	unresolvedThreads, activeTasks, completedTasks := 0, 0, 0
	for _, comment := range comments {
		if comment.DeletedAt == nil && comment.ResolvedAt == nil && comment.ParentID == nil {
			unresolvedThreads++
		}
		if comment.DeletedAt == nil && comment.Type == controlmodel.CommentResult {
			result = comment.Content
		}
	}
	for _, task := range tasks {
		if controlmodel.IsAgentTaskTerminal(task.Status) {
			completedTasks++
		} else {
			activeTasks++
		}
	}
	return gin.H{"issueId": issue.ID, "title": issue.Title, "status": issue.Status,
		"commentCount": len(comments), "unresolvedThreads": unresolvedThreads,
		"activeTasks": activeTasks, "terminalTasks": completedTasks, "childCount": len(children),
		"latestResult": result, "updatedAt": issue.UpdatedAt}
}

func (s *Server) assignIssue(c *gin.Context) {
	id, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	var req struct {
		AssigneeType    *controlmodel.AssigneeType `json:"assigneeType"`
		AssigneeRef     *string                    `json:"assigneeRef"`
		ExpectedVersion int64                      `json:"expectedVersion"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.AssigneeType == nil || req.AssigneeRef == nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "assigneeType and assigneeRef are required"})
		return
	}
	assigneeType, assigneeRef := *req.AssigneeType, strings.TrimSpace(*req.AssigneeRef)
	if (assigneeType == "") != (assigneeRef == "") ||
		(assigneeType != "" && assigneeType != controlmodel.AssigneeHuman && assigneeType != controlmodel.AssigneeAgent && assigneeType != controlmodel.AssigneeTeam) {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "select a human, agent or team with a reference, or clear both assignee fields"})
		return
	}
	if assigneeType == controlmodel.AssigneeAgent {
		current, err := s.store.Collaboration().GetIssue(c.Request.Context(), id)
		if err != nil {
			s.writeCollaborationError(c, err)
			return
		}
		if _, err := s.activeAgentInScope(c.Request.Context(), current.Tenant, current.Namespace, assigneeRef); err != nil {
			if _, parseErr := uuid.Parse(assigneeRef); parseErr != nil {
				c.JSON(http.StatusBadRequest, ErrorResponse{Error: "assigneeRef must be a valid agentId"})
			} else {
				s.writeCollaborationError(c, err)
			}
			return
		}
	}
	issue, task, err := s.store.Collaboration().AssignIssue(c.Request.Context(), id,
		req.ExpectedVersion, assigneeType, assigneeRef, humanActor(c, s))
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"issue": issue, "agentTask": task})
}

type commentRequest struct {
	ParentID *uuid.UUID                    `json:"parentId,omitempty"`
	Content  string                        `json:"content"`
	Type     controlmodel.CommentType      `json:"type,omitempty"`
	Mentions []collaboration.MentionTarget `json:"mentions,omitempty"`
}

func (s *Server) addIssueComment(c *gin.Context) {
	issueID, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	var req commentRequest
	if err := c.ShouldBindJSON(&req); err != nil || strings.TrimSpace(req.Content) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "content is required"})
		return
	}
	var sourceTaskID *uuid.UUID
	if task, scoped := taskPrincipal(c); scoped {
		sourceTaskID = &task.ID
	}
	result, err := s.collaborationService().AddComment(c.Request.Context(), collaboration.AddCommentRequest{
		IssueID: issueID, ParentID: req.ParentID, Author: collaborationActor(c, s),
		Content: req.Content, Type: req.Type, Mentions: req.Mentions,
		SourceTaskID: sourceTaskID,
	})
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	if s.workSources != nil {
		_ = s.workSources.QueueComment(c.Request.Context(), result.Comment)
	}
	c.JSON(http.StatusCreated, result)
}

func (s *Server) listIssueComments(c *gin.Context) {
	issueID, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	limit, offset := collaborationPagination(c)
	tail, _ := strconv.Atoi(c.DefaultQuery("tail", "0"))
	if tail < 0 {
		tail = 0
	}
	if tail > limit {
		tail = limit
	}
	var threadID *uuid.UUID
	if raw := c.Query("threadId"); raw != "" {
		id, err := uuid.Parse(raw)
		if err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid threadId"})
			return
		}
		threadID = &id
	}
	cursorTime, cursorID, cursorErr := decodeCollaborationCursor(c.Query("cursor"))
	if cursorErr != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid cursor"})
		return
	}
	items, err := s.store.Collaboration().ListComments(c.Request.Context(), issueID,
		store.CommentListOptions{RootsOnly: c.Query("rootsOnly") == "true", ThreadID: threadID,
			Limit: limit + 1, Offset: offset, Tail: tail, CursorTime: cursorTime, CursorID: cursorID})
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	next := ""
	if len(items) > limit {
		items = items[:limit]
		last := items[len(items)-1]
		next = encodeCollaborationCursor(last.CreatedAt, last.ID)
	}
	response := gin.H{"items": items, "nextCursor": next}
	if s.workSources != nil {
		if issue, loadErr := s.store.Collaboration().GetIssue(c.Request.Context(), issueID); loadErr == nil {
			if sourceID, parseErr := uuid.Parse(issue.SourceRef); parseErr == nil {
				syncStates := make(map[string]*controlmodel.CommentExternalRef)
				for _, comment := range items {
					if ref, refErr := s.store.WorkSources().GetCommentExternalRef(c.Request.Context(), sourceID, comment.ID); refErr == nil {
						syncStates[comment.ID.String()] = ref
					}
				}
				if len(syncStates) > 0 {
					response["externalSync"] = syncStates
				}
			}
		}
	}
	if c.Query("summary") == "true" {
		roots, unresolved, results := 0, 0, 0
		for _, comment := range items {
			if comment.ParentID == nil {
				roots++
				if comment.ResolvedAt == nil && comment.DeletedAt == nil {
					unresolved++
				}
			}
			if comment.Type == controlmodel.CommentResult && comment.DeletedAt == nil {
				results++
			}
		}
		response["summary"] = gin.H{"returned": len(items), "roots": roots, "unresolvedRoots": unresolved, "results": results}
	}
	c.JSON(http.StatusOK, response)
}

type collaborationCursor struct {
	Time time.Time `json:"time"`
	ID   uuid.UUID `json:"id"`
}

func encodeCollaborationCursor(value time.Time, id uuid.UUID) string {
	data, _ := json.Marshal(collaborationCursor{Time: value, ID: id})
	return base64.RawURLEncoding.EncodeToString(data)
}

func decodeCollaborationCursor(raw string) (*time.Time, uuid.UUID, error) {
	if raw == "" {
		return nil, uuid.Nil, nil
	}
	data, err := base64.RawURLEncoding.DecodeString(raw)
	if err != nil {
		return nil, uuid.Nil, err
	}
	var cursor collaborationCursor
	if err := json.Unmarshal(data, &cursor); err != nil || cursor.Time.IsZero() || cursor.ID == uuid.Nil {
		return nil, uuid.Nil, fmt.Errorf("invalid cursor")
	}
	return &cursor.Time, cursor.ID, nil
}

func (s *Server) updateIssueComment(c *gin.Context) {
	commentID, ok := parseUUIDParam(c, "commentId")
	if !ok {
		return
	}
	current, err := s.store.Collaboration().GetComment(c.Request.Context(), commentID)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	if current.Author.Type != controlmodel.ActorHuman || current.Author.Ref != s.operatorFromContext(c) {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "only the author can edit a comment"})
		return
	}
	var req struct {
		Content         string `json:"content"`
		ExpectedVersion int64  `json:"expectedVersion"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || strings.TrimSpace(req.Content) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "content is required"})
		return
	}
	if err := s.collaborationService().ValidateIssueContent(c.Request.Context(), current.IssueID, current.SourceTaskID, req.Content); err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	current.Content = strings.TrimSpace(req.Content)
	updated, err := s.store.Collaboration().UpdateComment(c.Request.Context(), current, req.ExpectedVersion)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"comment": updated})
}

func (s *Server) deleteIssueComment(c *gin.Context) {
	commentID, ok := parseUUIDParam(c, "commentId")
	if !ok {
		return
	}
	current, err := s.store.Collaboration().GetComment(c.Request.Context(), commentID)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	if current.IssueID.String() != c.Param("issueId") {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "comment not found"})
		return
	}
	if current.Author.Type != controlmodel.ActorHuman || current.Author.Ref != s.operatorFromContext(c) {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "only the author can delete a comment"})
		return
	}
	version, _ := strconv.ParseInt(c.Query("expectedVersion"), 10, 64)
	deleted, err := s.store.Collaboration().DeleteComment(c.Request.Context(), commentID, version, humanActor(c, s))
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"comment": deleted})
}

func (s *Server) resolveIssueComment(c *gin.Context) {
	commentID, ok := parseUUIDParam(c, "commentId")
	if !ok {
		return
	}
	var req struct {
		Resolved        *bool `json:"resolved"`
		ExpectedVersion int64 `json:"expectedVersion"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	resolved := true
	if req.Resolved != nil {
		resolved = *req.Resolved
	}
	comment, err := s.store.Collaboration().ResolveComment(c.Request.Context(), commentID,
		req.ExpectedVersion, humanActor(c, s), resolved)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"comment": comment})
}

func (s *Server) previewIssueCommentRouting(c *gin.Context) {
	issueID, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	var req commentRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	targets, err := s.collaborationService().PreviewCommentRoutes(c.Request.Context(), issueID,
		req.ParentID, humanActor(c, s), req.Mentions)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"targets": targets})
}

func (s *Server) listIssueActivities(c *gin.Context) {
	issueID, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	limit, offset := collaborationPagination(c)
	items, err := s.store.Collaboration().ListActivities(c.Request.Context(), issueID, limit, offset)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) listIssueArtifacts(c *gin.Context) {
	issueID, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	issue, err := s.store.Collaboration().GetIssue(c.Request.Context(), issueID)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	items, err := s.store.Collaboration().ListArtifacts(c.Request.Context(), issue.Tenant,
		issue.Namespace, "issue", issueID.String())
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) subscribeIssue(c *gin.Context) {
	issueID, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	issue, err := s.store.Collaboration().GetIssue(c.Request.Context(), issueID)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	var req struct {
		Type controlmodel.AssigneeType `json:"type"`
		Ref  string                    `json:"ref"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if req.Type == "" {
		req.Type = controlmodel.AssigneeHuman
	}
	if req.Ref == "" && req.Type == controlmodel.AssigneeHuman {
		req.Ref = s.operatorFromContext(c)
	}
	item, err := s.store.Collaboration().SubscribeIssue(c.Request.Context(), &controlmodel.IssueSubscriber{
		IssueID: issue.ID, Tenant: issue.Tenant, Namespace: issue.Namespace,
		SubscriberType: req.Type, SubscriberRef: req.Ref,
	})
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"subscriber": item})
}

func (s *Server) listIssueSubscribers(c *gin.Context) {
	issueID, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	items, err := s.store.Collaboration().ListIssueSubscribers(c.Request.Context(), issueID)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) unsubscribeIssue(c *gin.Context) {
	issueID, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	typeValue := controlmodel.AssigneeType(c.Param("subscriberType"))
	ref := c.Param("subscriberRef")
	if typeValue == controlmodel.AssigneeHuman && ref != s.operatorFromContext(c) {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "cannot unsubscribe another user"})
		return
	}
	if err := s.store.Collaboration().UnsubscribeIssue(c.Request.Context(), issueID, typeValue, ref); err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) listAgentTasks(c *gin.Context) {
	tenant, namespace, ok := requireCollaborationScope(c)
	if !ok {
		return
	}
	limit, offset := collaborationPagination(c)
	agentID := c.Query("agentId")
	if agentID != "" {
		if _, err := uuid.Parse(agentID); err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid agentId"})
			return
		}
	}
	filter := store.AgentTaskFilter{Tenant: tenant, Namespace: namespace,
		AgentRef: agentID, Status: controlmodel.AgentTaskStatus(c.Query("status")),
		Limit: limit, Offset: offset}
	if raw := c.Query("issueId"); raw != "" {
		filter.IssueID, _ = uuid.Parse(raw)
	}
	if raw := c.Query("teamId"); raw != "" {
		filter.TeamID, _ = uuid.Parse(raw)
	}
	items, err := s.store.Collaboration().ListAgentTasks(c.Request.Context(), filter)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) getAgentTask(c *gin.Context) {
	id, ok := parseUUIDParam(c, "taskId")
	if !ok {
		return
	}
	task, err := s.store.Collaboration().GetAgentTask(c.Request.Context(), id)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	inputs := make([]gin.H, 0, len(task.Inputs))
	for _, input := range task.Inputs {
		preview := gin.H{"inputId": input.ID, "commentId": input.CommentID, "version": input.CommentVersion, "state": "unavailable"}
		if comment, err := s.store.Collaboration().GetComment(c.Request.Context(), input.CommentID); err == nil && comment.IssueID == task.IssueID && comment.Tenant == task.Tenant && comment.Namespace == task.Namespace && comment.DeletedAt == nil {
			if comment.Version == input.CommentVersion {
				preview["state"], preview["content"] = "recorded", comment.Content
			} else {
				preview["state"] = "changed"
			}
		}
		inputs = append(inputs, preview)
	}
	c.JSON(http.StatusOK, gin.H{"task": task, "inputSummaries": inputs})
}

func (s *Server) getAgentTaskContext(c *gin.Context) {
	id, ok := parseUUIDParam(c, "taskId")
	if !ok {
		return
	}
	ctx, err := s.collaborationService().BuildContext(c.Request.Context(), id)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	token := ""
	if task, loadErr := s.store.Collaboration().GetAgentTask(c.Request.Context(), id); loadErr == nil {
		if task.CurrentAttemptID != nil {
			if attempt, attemptErr := s.store.ExecutionAttempts().Get(c.Request.Context(), *task.CurrentAttemptID); attemptErr == nil {
				token, _ = s.taskTokens.MintScoped(id, attempt.ID, attempt.DispatchGeneration, time.Now().UTC())
			}
		} else {
			token, _ = s.taskTokens.Mint(id, time.Now().UTC())
		}
	}
	ctx.TaskToken = token
	c.JSON(http.StatusOK, ctx)
}

func (s *Server) taskTokenMiddleware() gin.HandlerFunc {
	return s.taskTokenMiddlewareWithVerifier(s.verifyActiveTaskToken)
}

func (s *Server) coordinatorTaskTokenMiddleware() gin.HandlerFunc {
	return s.taskTokenMiddlewareWithVerifier(s.verifyCoordinatorTaskToken)
}

func (s *Server) taskTokenMiddlewareWithVerifier(
	verify func(context.Context, string, uuid.UUID) (*controlmodel.AgentTask, error),
) gin.HandlerFunc {
	return func(c *gin.Context) {
		id, err := uuid.Parse(c.Param("taskId"))
		if err != nil {
			c.AbortWithStatusJSON(http.StatusBadRequest, ErrorResponse{Error: "invalid taskId"})
			return
		}
		token := c.GetHeader("X-Agent-Task-Token")
		if token == "" {
			auth := c.GetHeader("Authorization")
			token = strings.TrimPrefix(auth, "Bearer ")
		}
		task, err := verify(c.Request.Context(), token, id)
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, ErrorResponse{Error: err.Error()})
			return
		}
		c.Set(ctxInternalAuth, true)
		c.Set(ctxTaskAuth, task)
		c.Next()
	}
}

func (s *Server) claimAgentTask(c *gin.Context) {
	id, ok := parseUUIDParam(c, "taskId")
	if !ok {
		return
	}
	var req struct {
		ExpectedVersion int64           `json:"expectedVersion"`
		RuntimeBinding  json.RawMessage `json:"runtimeBinding,omitempty"`
		SessionID       string          `json:"sessionId,omitempty"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	task, err := s.store.Collaboration().ClaimAgentTask(c.Request.Context(), store.TaskClaim{
		TaskID: id, ExpectedVersion: req.ExpectedVersion, RuntimeBinding: req.RuntimeBinding,
		SessionID: req.SessionID,
	})
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	recordAgentTaskState(task)
	c.JSON(http.StatusOK, gin.H{"task": task})
}

func (s *Server) dispatchAgentTask(c *gin.Context) {
	id, ok := parseUUIDParam(c, "taskId")
	if !ok {
		return
	}
	var req struct {
		Binding              controlmodel.RuntimeBinding `json:"runtimeBinding"`
		RequiredCapabilities json.RawMessage             `json:"requiredCapabilities,omitempty"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if s.runtimeBindings == nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "runtime binding resolver is unavailable"})
		return
	}
	var requested *controlmodel.RuntimeBindingCandidate
	if req.Binding.Kind != "" {
		requested = &controlmodel.RuntimeBindingCandidate{Binding: req.Binding, RequiredCapabilities: req.RequiredCapabilities}
	}
	result, err := (&scheduler.Scheduler{Store: s.store, Resolver: s.runtimeBindings}).DispatchTaskCandidate(c.Request.Context(), id, requested)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, result)
}

func (s *Server) acknowledgeAgentTask(c *gin.Context) {
	id, ok := parseUUIDParam(c, "taskId")
	if !ok {
		return
	}
	var req struct {
		InputIDs []uuid.UUID `json:"inputIds"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || len(req.InputIDs) == 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "inputIds are required"})
		return
	}
	inputs, err := s.store.Collaboration().AcknowledgeTaskInputs(c.Request.Context(), id, req.InputIDs)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	observeInputStates(inputs, req.InputIDs)
	c.JSON(http.StatusOK, gin.H{"inputs": inputs})
}

func (s *Server) failAgentTaskInputDelivery(c *gin.Context) {
	id, ok := parseUUIDParam(c, "taskId")
	if !ok {
		return
	}
	var req struct {
		InputIDs    []uuid.UUID `json:"inputIds"`
		Message     string      `json:"message"`
		MaxAttempts int         `json:"maxAttempts,omitempty"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || len(req.InputIDs) == 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "inputIds are required"})
		return
	}
	inputs, err := s.store.Collaboration().FailTaskInputDelivery(c.Request.Context(), id, req.InputIDs, req.Message, req.MaxAttempts)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	observeInputStates(inputs, req.InputIDs)
	c.JSON(http.StatusOK, gin.H{"inputs": inputs})
}

func (s *Server) replayAgentTaskInputs(c *gin.Context) {
	id, ok := parseUUIDParam(c, "taskId")
	if !ok {
		return
	}
	var req struct {
		InputIDs []uuid.UUID `json:"inputIds"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || len(req.InputIDs) == 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "inputIds are required"})
		return
	}
	task, err := s.store.Collaboration().ReplayDeadLetterInputs(c.Request.Context(), id, req.InputIDs, humanActor(c, s))
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"task": task})
}

func (s *Server) startAgentTask(c *gin.Context) {
	s.mutateAgentTaskVersion(c, func(id uuid.UUID, version int64) (any, error) {
		task, err := s.store.Collaboration().StartAgentTask(c.Request.Context(), id, version)
		if err == nil {
			recordAgentTaskState(task)
		}
		return task, err
	})
}

func (s *Server) progressAgentTask(c *gin.Context) {
	id, ok := parseUUIDParam(c, "taskId")
	if !ok {
		return
	}
	task, err := s.store.Collaboration().GetAgentTask(c.Request.Context(), id)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	var req commentRequest
	if err := c.ShouldBindJSON(&req); err != nil || strings.TrimSpace(req.Content) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "content is required"})
		return
	}
	result, err := s.collaborationService().AddComment(c.Request.Context(), collaboration.AddCommentRequest{
		IssueID: task.IssueID, ParentID: req.ParentID,
		Author:  controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef},
		Content: req.Content, Type: controlmodel.CommentProgress, Mentions: req.Mentions, SourceTaskID: &task.ID,
		SuppressImplicitRouting: true,
	})
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	if s.workSources != nil {
		_ = s.workSources.QueueComment(c.Request.Context(), result.Comment)
	}
	c.JSON(http.StatusCreated, result)
}

func (s *Server) failAgentTask(c *gin.Context) {
	id, ok := parseUUIDParam(c, "taskId")
	if !ok {
		return
	}
	var req struct {
		ExpectedVersion int64  `json:"expectedVersion"`
		Code            string `json:"code"`
		Message         string `json:"message"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	task, err := s.collaborationService().FailTask(c.Request.Context(), id,
		req.ExpectedVersion, req.Code, req.Message)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	recordAgentTaskState(task)
	c.JSON(http.StatusOK, gin.H{"task": task})
}

func (s *Server) cancelAgentTask(c *gin.Context) {
	s.mutateAgentTaskVersion(c, func(id uuid.UUID, version int64) (any, error) {
		task, err := s.taskPlane.CancelTask(c.Request.Context(), id, version)
		if err == nil {
			recordAgentTaskState(task)
		}
		return task, err
	})
}

func (s *Server) retryAgentTask(c *gin.Context) {
	id, ok := parseUUIDParam(c, "taskId")
	if !ok {
		return
	}
	task, err := s.collaborationService().RetryTask(c.Request.Context(), id, humanActor(c, s))
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	recordAgentTaskState(task)
	c.JSON(http.StatusCreated, gin.H{"task": task})
}

func (s *Server) respondAgentTask(c *gin.Context) {
	id, ok := parseUUIDParam(c, "taskId")
	if !ok {
		return
	}
	task, err := s.store.Collaboration().GetAgentTask(c.Request.Context(), id)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	var req commentRequest
	if err := c.ShouldBindJSON(&req); err != nil || strings.TrimSpace(req.Content) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "content is required"})
		return
	}
	result, err := s.collaborationService().AddComment(c.Request.Context(), collaboration.AddCommentRequest{
		IssueID: task.IssueID, ParentID: req.ParentID,
		Author:  controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef},
		Content: req.Content, Type: req.Type, Mentions: req.Mentions, SourceTaskID: &task.ID,
	})
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	if s.workSources != nil {
		_ = s.workSources.QueueComment(c.Request.Context(), result.Comment)
	}
	c.JSON(http.StatusCreated, result)
}

func (s *Server) createAgentTaskChildIssue(c *gin.Context) {
	id, ok := parseUUIDParam(c, "taskId")
	if !ok {
		return
	}
	var req issueRequest
	if err := c.ShouldBindJSON(&req); err != nil || strings.TrimSpace(req.Title) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "title is required"})
		return
	}
	issue, task, err := s.collaborationService().CreateChildFromTask(c.Request.Context(), id, collaboration.CreateIssueRequest{
		Title: req.Title, Description: req.Description, Priority: req.Priority,
		AssigneeType: req.AssigneeType, AssigneeRef: req.AssigneeRef,
		AcceptanceCriteria: req.AcceptanceCriteria, ContextRefs: req.ContextRefs,
	})
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"issue": issue, "agentTask": task})
}

func (s *Server) completeAgentTask(c *gin.Context) {
	id, ok := parseUUIDParam(c, "taskId")
	if !ok {
		return
	}
	task, err := s.store.Collaboration().GetAgentTask(c.Request.Context(), id)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	var req struct {
		store.TaskCompletion
		Outcome string `json:"outcome"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if req.Outcome != "" {
		if req.ExpectedVersion > 0 && req.ExpectedVersion != task.Version {
			s.writeCollaborationError(c, store.ErrConflict)
			return
		}
		raw, _ := json.Marshal(req)
		var args map[string]any
		if err := json.Unmarshal(raw, &args); err != nil {
			s.writeCollaborationError(c, err)
			return
		}
		result, err := s.callCollaborationMCPTool(c, task, "task.complete", args)
		if err != nil {
			s.writeCollaborationError(c, err)
			return
		}
		c.JSON(http.StatusOK, result)
		return
	}
	completed, comment, err := s.collaborationService().CompleteTask(c.Request.Context(), id, req.TaskCompletion,
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef})
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	recordAgentTaskState(completed)
	if s.workSources != nil && comment != nil {
		_ = s.workSources.QueueComment(c.Request.Context(), comment)
	}
	selected := append(append([]uuid.UUID(nil), req.ProcessedInputIDs...), req.DeferredInputIDs...)
	if current, loadErr := s.store.Collaboration().GetAgentTask(c.Request.Context(), completed.ID); loadErr == nil {
		observeInputStates(current.Inputs, selected)
	}
	c.JSON(http.StatusOK, gin.H{"task": completed, "comment": comment})
}

func recordAgentTaskState(task *controlmodel.AgentTask) {
	if task == nil {
		return
	}
	backend := "unbound"
	var binding controlmodel.RuntimeBinding
	if len(task.RuntimeBinding) > 0 && json.Unmarshal(task.RuntimeBinding, &binding) == nil && binding.Kind != "" {
		backend = string(binding.Kind)
	}
	metrics.RecordAgentTaskTransition(task.Namespace, backend, string(task.Status))
}

func observeInputStates(inputs []controlmodel.AgentTaskInput, selected []uuid.UUID) {
	set := make(map[uuid.UUID]struct{}, len(selected))
	for _, id := range selected {
		set[id] = struct{}{}
	}
	now := time.Now().UTC()
	for _, input := range inputs {
		if len(set) > 0 {
			if _, ok := set[input.ID]; !ok {
				continue
			}
		}
		metrics.ObserveAgentTaskInputAge(input.Namespace, string(input.State), now.Sub(input.CreatedAt))
	}
}

func (s *Server) mutateAgentTaskVersion(c *gin.Context, fn func(uuid.UUID, int64) (any, error)) {
	id, ok := parseUUIDParam(c, "taskId")
	if !ok {
		return
	}
	var req struct {
		ExpectedVersion int64 `json:"expectedVersion"`
	}
	_ = c.ShouldBindJSON(&req)
	result, err := fn(id, req.ExpectedVersion)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"task": result})
}

func (s *Server) createCollaborationTeam(c *gin.Context) {
	var team controlmodel.CollaborationTeam
	if err := c.ShouldBindJSON(&team); err != nil || team.Name == "" || team.LeaderAgentRef == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "name and leaderAgentId are required"})
		return
	}
	if strings.TrimSpace(team.Tenant) == "" || strings.TrimSpace(team.Namespace) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "tenant and namespace are required"})
		return
	}
	if team.Status == "" {
		team.Status = controlmodel.TeamActive
	}
	if team.Status != controlmodel.TeamActive && team.Status != controlmodel.TeamDisabled {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "status must be active or disabled"})
		return
	}
	if _, err := s.activeAgentInScope(c.Request.Context(), team.Tenant, team.Namespace, team.LeaderAgentRef); err != nil {
		if _, parseErr := uuid.Parse(team.LeaderAgentRef); parseErr != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "leaderAgentId must be a valid UUID"})
		} else {
			s.writeCollaborationError(c, err)
		}
		return
	}
	if err := collaboration.ValidateTeamPolicy(team.Policy); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	memberAgents := make(map[string]struct{}, len(team.Members))
	memberRoles := make(map[string]struct{}, len(team.Members))
	for index := range team.Members {
		member := &team.Members[index]
		member.AgentRef = strings.TrimSpace(member.AgentRef)
		member.Role = strings.TrimSpace(member.Role)
		if member.AgentRef == "" || member.Role == "" {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "each Team member requires agentId and role"})
			return
		}
		if member.AgentRef == team.LeaderAgentRef {
			c.JSON(http.StatusConflict, ErrorResponse{Error: "leader Agent cannot also be a Team member"})
			return
		}
		if _, exists := memberAgents[member.AgentRef]; exists {
			c.JSON(http.StatusConflict, ErrorResponse{Error: "Team member Agents must be unique"})
			return
		}
		if _, exists := memberRoles[member.Role]; exists {
			c.JSON(http.StatusConflict, ErrorResponse{Error: "Team member roles must be unique"})
			return
		}
		if _, err := s.activeAgentInScope(c.Request.Context(), team.Tenant, team.Namespace, member.AgentRef); err != nil {
			if _, parseErr := uuid.Parse(member.AgentRef); parseErr != nil {
				c.JSON(http.StatusBadRequest, ErrorResponse{Error: "member agentId must be a valid UUID"})
			} else {
				s.writeCollaborationError(c, err)
			}
			return
		}
		if err := collaboration.ValidateRuntimeBindingPolicy(member.RuntimeBindingPolicy); err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
			return
		}
		memberAgents[member.AgentRef] = struct{}{}
		memberRoles[member.Role] = struct{}{}
	}
	created, err := s.store.Collaboration().CreateTeam(c.Request.Context(), &team)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"team": created})
}

func (s *Server) listCollaborationTeams(c *gin.Context) {
	tenant, namespace, ok := requireCollaborationScope(c)
	if !ok {
		return
	}
	items, err := s.store.Collaboration().ListTeams(c.Request.Context(), tenant, namespace)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	if a := accessFrom(c); a != nil {
		filtered := items[:0]
		for _, team := range items {
			if a.Namespace.Decide(a.User, "team:"+team.ID.String(), "discover").Allowed {
				filtered = append(filtered, team)
			}
		}
		items = filtered
	}
	for _, team := range items {
		redactTeamForDiscovery(c, team)
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) getCollaborationTeam(c *gin.Context) {
	id, ok := parseUUIDParam(c, "teamId")
	if !ok {
		return
	}
	team, err := s.store.Collaboration().GetTeam(c.Request.Context(), id)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	redactTeamForDiscovery(c, team)
	c.JSON(http.StatusOK, gin.H{"team": team})
}

func (s *Server) updateCollaborationTeam(c *gin.Context) {
	id, ok := parseUUIDParam(c, "teamId")
	if !ok {
		return
	}
	var req struct {
		Name            string                  `json:"name"`
		Description     string                  `json:"description,omitempty"`
		Instructions    *string                 `json:"instructions"`
		Status          controlmodel.TeamStatus `json:"status,omitempty"`
		LeaderAgentRef  string                  `json:"leaderAgentId"`
		Policy          controlmodel.TeamPolicy `json:"policy"`
		ExpectedVersion int64                   `json:"expectedVersion"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Name == "" || req.LeaderAgentRef == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "name and leaderAgentId are required"})
		return
	}
	current, err := s.store.Collaboration().GetTeam(c.Request.Context(), id)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	for _, member := range current.Members {
		if member.AgentRef == req.LeaderAgentRef {
			c.JSON(http.StatusConflict, ErrorResponse{Error: "leader Agent cannot also be a Team member"})
			return
		}
	}
	if _, err := s.activeAgentInScope(c.Request.Context(), current.Tenant, current.Namespace, req.LeaderAgentRef); err != nil {
		if _, parseErr := uuid.Parse(req.LeaderAgentRef); parseErr != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "leaderAgentId must be a valid UUID"})
		} else {
			s.writeCollaborationError(c, err)
		}
		return
	}
	if req.Status == "" {
		req.Status = current.Status
	}
	instructions := current.Instructions
	if req.Instructions != nil {
		instructions = *req.Instructions
	}
	if req.Status != controlmodel.TeamActive && req.Status != controlmodel.TeamDisabled {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "status must be active or disabled"})
		return
	}
	if err := collaboration.ValidateTeamPolicy(req.Policy); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	team, err := s.store.Collaboration().UpdateTeam(c.Request.Context(), &controlmodel.CollaborationTeam{
		ID: id, Name: req.Name, Description: req.Description, Instructions: instructions, Status: req.Status,
		LeaderAgentRef: req.LeaderAgentRef, Policy: req.Policy,
	}, req.ExpectedVersion)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"team": team})
}

func (s *Server) addCollaborationTeamMember(c *gin.Context) {
	teamID, ok := parseUUIDParam(c, "teamId")
	if !ok {
		return
	}
	var member controlmodel.CollaborationTeamMember
	if err := c.ShouldBindJSON(&member); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	team, err := s.store.Collaboration().GetTeam(c.Request.Context(), teamID)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	if team.LeaderAgentRef == member.AgentRef {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "leader Agent is already represented by the Team leader role"})
		return
	}
	if _, err := s.activeAgentInScope(c.Request.Context(), team.Tenant, team.Namespace, member.AgentRef); err != nil {
		if _, parseErr := uuid.Parse(member.AgentRef); parseErr != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "agentId must be a valid UUID"})
		} else {
			s.writeCollaborationError(c, err)
		}
		return
	}
	if err := collaboration.ValidateRuntimeBindingPolicy(member.RuntimeBindingPolicy); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	member.TeamID = teamID
	created, err := s.store.Collaboration().AddTeamMember(c.Request.Context(), &member)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"member": created})
}

func (s *Server) updateCollaborationTeamMember(c *gin.Context) {
	teamID, ok := parseUUIDParam(c, "teamId")
	if !ok {
		return
	}
	memberID, ok := parseUUIDParam(c, "memberId")
	if !ok {
		return
	}
	var req struct {
		Role                   string          `json:"role"`
		Instructions           string          `json:"instructions,omitempty"`
		CapabilityRequirements json.RawMessage `json:"capabilityRequirements,omitempty"`
		RuntimeBindingPolicy   json.RawMessage `json:"runtimeBindingPolicy,omitempty"`
		ExpectedTeamVersion    int64           `json:"expectedTeamVersion"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || strings.TrimSpace(req.Role) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "role is required"})
		return
	}
	team, err := s.store.Collaboration().GetTeam(c.Request.Context(), teamID)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	found := false
	for _, member := range team.Members {
		if member.ID == memberID {
			found = true
			break
		}
	}
	if !found {
		s.writeCollaborationError(c, store.ErrNotFound)
		return
	}
	if err := collaboration.ValidateRuntimeBindingPolicy(req.RuntimeBindingPolicy); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	updated, err := s.store.Collaboration().UpdateTeamMember(c.Request.Context(), &controlmodel.CollaborationTeamMember{
		ID: memberID, TeamID: teamID, Role: strings.TrimSpace(req.Role), Instructions: req.Instructions,
		CapabilityRequirements: req.CapabilityRequirements, RuntimeBindingPolicy: req.RuntimeBindingPolicy,
	}, req.ExpectedTeamVersion)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"member": updated})
}

func (s *Server) removeCollaborationTeamMember(c *gin.Context) {
	teamID, ok := parseUUIDParam(c, "teamId")
	if !ok {
		return
	}
	memberID, ok := parseUUIDParam(c, "memberId")
	if !ok {
		return
	}
	if err := s.store.Collaboration().RemoveTeamMember(c.Request.Context(), teamID, memberID); err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) uploadArtifact(c *gin.Context) {
	if s.artifactProvider == nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "artifact provider is unavailable"})
		return
	}
	const maxArtifactSize = 100 << 20
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxArtifactSize)
	file, header, err := c.Request.FormFile("file")
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "multipart file is required"})
		return
	}
	defer file.Close()
	tenant, namespace := c.PostForm("tenant"), c.PostForm("namespace")
	if s.scopeMode == ScopeModeSingle {
		tenant, namespace = s.defaultTenant, s.defaultNamespace
	}
	if a := accessFrom(c); a != nil {
		if c.PostForm("tenant") != "" && c.PostForm("tenant") != a.Namespace.Tenant || c.PostForm("namespace") != "" && c.PostForm("namespace") != a.Namespace.Name {
			s.accessFailure(c, store.ErrNotFound)
			return
		}
		tenant, namespace = a.Namespace.Tenant, a.Namespace.Name
		if c.PostForm("sourceTaskId") != "" {
			c.JSON(403, ErrorResponse{Error: "human uploads cannot impersonate an Agent Task"})
			return
		}
		if target := c.PostForm("targetRef"); target != "" {
			id, err := uuid.Parse(target)
			if err != nil || c.PostForm("targetType") != "issue" {
				c.JSON(400, ErrorResponse{Error: "uploads may link to an authorized Issue"})
				return
			}
			if _, err := s.canAccessIssue(c.Request.Context(), a, id, true); err != nil {
				s.accessFailure(c, err)
				return
			}
		}
	}
	uploader := humanActor(c, s)
	var sourceTaskID *uuid.UUID
	var artifactPolicy controlmodel.TeamPolicy
	if raw := c.PostForm("sourceTaskId"); raw != "" {
		id, parseErr := uuid.Parse(raw)
		if parseErr != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid sourceTaskId"})
			return
		}
		sourceTaskID = &id
	}
	if token := c.GetHeader("X-Agent-Task-Token"); token != "" {
		if sourceTaskID == nil || s.taskTokens.Verify(token, *sourceTaskID, time.Now().UTC()) != nil {
			c.JSON(http.StatusUnauthorized, ErrorResponse{Error: "artifact upload requires a valid source task token"})
			return
		}
		task, loadErr := s.store.Collaboration().GetAgentTask(c.Request.Context(), *sourceTaskID)
		if loadErr != nil {
			s.writeCollaborationError(c, loadErr)
			return
		}
		tenant, namespace = task.Tenant, task.Namespace
		uploader = controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
		if task.TeamID != nil {
			if team, teamErr := s.collaborationService().TeamForTask(c.Request.Context(), task); teamErr == nil {
				artifactPolicy = team.Policy
			}
		}
	}
	if tenant == "" || namespace == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "tenant and namespace are required"})
		return
	}
	id := uuid.New()
	storageKey := fmt.Sprintf("%s/%s/%s", tenant, namespace, id)
	reader := bufio.NewReader(file)
	sample, _ := reader.Peek(512)
	contentType := strings.TrimSpace(header.Header.Get("Content-Type"))
	if contentType == "" || contentType == "application/octet-stream" {
		contentType = http.DetectContentType(sample)
	}
	if strings.HasPrefix(strings.ToLower(contentType), "text/") {
		if policyErr := collaboration.ValidateContentPolicy(artifactPolicy, string(sample)); policyErr != nil {
			c.JSON(http.StatusUnprocessableEntity, ErrorResponse{Error: policyErr.Error()})
			return
		}
	}
	filename := filepath.Base(strings.TrimSpace(header.Filename))
	if filename == "" || filename == "." {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "artifact filename is required"})
		return
	}
	var expiresAt *time.Time
	if raw := strings.TrimSpace(c.PostForm("expiresAt")); raw != "" {
		value, parseErr := time.Parse(time.RFC3339, raw)
		if parseErr != nil || !value.After(time.Now().UTC()) {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "expiresAt must be a future RFC3339 timestamp"})
			return
		}
		expiresAt = &value
	}
	metadata := json.RawMessage(nil)
	if raw := strings.TrimSpace(c.PostForm("metadata")); raw != "" {
		if !json.Valid([]byte(raw)) {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "metadata must be valid JSON"})
			return
		}
		metadata = json.RawMessage(raw)
	}
	info, err := s.artifactProvider.Put(c.Request.Context(), storageKey, reader)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	if expected := c.PostForm("checksum"); expected != "" && expected != info.Checksum {
		_ = s.artifactProvider.Delete(c.Request.Context(), storageKey)
		c.JSON(http.StatusUnprocessableEntity, ErrorResponse{Error: "artifact checksum mismatch"})
		return
	}
	if artifactPolicy.MaxArtifactBytes > 0 && info.Size > artifactPolicy.MaxArtifactBytes {
		_ = s.artifactProvider.Delete(c.Request.Context(), storageKey)
		c.JSON(http.StatusRequestEntityTooLarge, ErrorResponse{Error: "artifact exceeds Team policy size limit"})
		return
	}
	if len(artifactPolicy.AllowedArtifactMediaTypes) > 0 && !mediaTypeAllowed(contentType, artifactPolicy.AllowedArtifactMediaTypes) {
		_ = s.artifactProvider.Delete(c.Request.Context(), storageKey)
		c.JSON(http.StatusUnsupportedMediaType, ErrorResponse{Error: "artifact media type is blocked by Team policy"})
		return
	}
	artifact := &controlmodel.Artifact{ID: id, Tenant: tenant, Namespace: namespace,
		StorageProvider: s.artifactProvider.Name(), StorageKey: storageKey,
		Filename: filename, ContentType: contentType, SizeBytes: info.Size, Checksum: info.Checksum,
		Uploader: uploader, SourceTaskID: sourceTaskID, Metadata: metadata, ExpiresAt: expiresAt}
	links := make([]controlmodel.ArtifactLink, 0, 1)
	if targetType, targetRef := c.PostForm("targetType"), c.PostForm("targetRef"); targetType != "" && targetRef != "" {
		relation := c.PostForm("relation")
		if relation == "" {
			relation = "attachment"
		}
		links = append(links, controlmodel.ArtifactLink{TargetType: targetType, TargetRef: targetRef, Relation: relation})
	}
	created, err := s.store.Collaboration().CreateArtifact(c.Request.Context(), artifact, links)
	if err != nil {
		_ = s.artifactProvider.Delete(c.Request.Context(), storageKey)
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"artifact": created, "links": links})
}

func mediaTypeAllowed(contentType string, allowed []string) bool {
	base, _, _ := strings.Cut(strings.ToLower(strings.TrimSpace(contentType)), ";")
	for _, candidate := range allowed {
		candidate = strings.ToLower(strings.TrimSpace(candidate))
		if candidate == base || strings.HasSuffix(candidate, "/*") && strings.HasPrefix(base, strings.TrimSuffix(candidate, "*")) {
			return true
		}
	}
	return false
}

func (s *Server) getArtifact(c *gin.Context) {
	id, ok := parseUUIDParam(c, "artifactId")
	if !ok {
		return
	}
	artifact, links, err := s.store.Collaboration().GetArtifact(c.Request.Context(), id)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	if task, scoped := taskPrincipal(c); scoped && !taskCanReadArtifact(task, artifact, links) {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "artifact is outside task scope"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"artifact": artifact, "links": links})
}

func (s *Server) completeArtifact(c *gin.Context) {
	id, ok := parseUUIDParam(c, "artifactId")
	if !ok {
		return
	}
	artifact, links, err := s.store.Collaboration().GetArtifact(c.Request.Context(), id)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	if task, scoped := taskPrincipal(c); scoped && !taskCanReadArtifact(task, artifact, links) {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "artifact is outside task scope"})
		return
	}
	if s.artifactProvider == nil || artifact.StorageProvider != s.artifactProvider.Name() {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "artifact provider is unavailable"})
		return
	}
	info, err := s.artifactProvider.Stat(c.Request.Context(), artifact.StorageKey)
	if err != nil {
		c.JSON(http.StatusUnprocessableEntity, ErrorResponse{Error: err.Error()})
		return
	}
	if info.Size != artifact.SizeBytes || info.Checksum != artifact.Checksum {
		c.JSON(http.StatusUnprocessableEntity, ErrorResponse{Error: "stored artifact integrity check failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"artifact": artifact, "links": links, "complete": true})
}

func (s *Server) downloadArtifact(c *gin.Context) {
	id, ok := parseUUIDParam(c, "artifactId")
	if !ok {
		return
	}
	artifact, links, err := s.store.Collaboration().GetArtifact(c.Request.Context(), id)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	if artifact.ExpiresAt != nil && artifact.ExpiresAt.Before(time.Now().UTC()) {
		c.JSON(http.StatusGone, ErrorResponse{Error: "artifact has expired"})
		return
	}
	if token := c.GetHeader("X-Agent-Task-Token"); token != "" {
		taskID, parseErr := uuid.Parse(c.Query("taskId"))
		if parseErr != nil || s.taskTokens.Verify(token, taskID, time.Now().UTC()) != nil {
			c.JSON(http.StatusUnauthorized, ErrorResponse{Error: "valid taskId and task token are required"})
			return
		}
		task, loadErr := s.store.Collaboration().GetAgentTask(c.Request.Context(), taskID)
		if loadErr != nil || !taskCanReadArtifact(task, artifact, links) {
			c.JSON(http.StatusForbidden, ErrorResponse{Error: "artifact is outside task scope"})
			return
		}
	}
	if s.artifactProvider == nil || artifact.StorageProvider != s.artifactProvider.Name() {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "artifact provider is unavailable"})
		return
	}
	reader, info, err := s.artifactProvider.Open(c.Request.Context(), artifact.StorageKey)
	if err != nil {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: err.Error()})
		return
	}
	defer reader.Close()
	if info.Checksum != artifact.Checksum {
		c.JSON(http.StatusUnprocessableEntity, ErrorResponse{Error: "artifact checksum mismatch"})
		return
	}
	c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=%q", artifact.Filename))
	c.Header("X-Artifact-Checksum", artifact.Checksum)
	c.DataFromReader(http.StatusOK, info.Size, artifact.ContentType, reader, nil)
}

func taskCanReadArtifact(task *controlmodel.AgentTask, artifact *controlmodel.Artifact, links []controlmodel.ArtifactLink) bool {
	if task == nil || artifact == nil || task.Tenant != artifact.Tenant || task.Namespace != artifact.Namespace {
		return false
	}
	if artifact.SourceTaskID != nil && *artifact.SourceTaskID == task.ID {
		return true
	}
	for _, link := range links {
		if link.TargetType == "issue" && link.TargetRef == task.IssueID.String() || link.TargetType == "agent_task" && link.TargetRef == task.ID.String() {
			return true
		}
	}
	return false
}

func (s *Server) createApproval(c *gin.Context) {
	var approval controlmodel.Approval
	if err := c.ShouldBindJSON(&approval); err != nil || approval.TargetType == "" || approval.TargetRef == "" || approval.ApproverRef == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "targetType, targetRef, and approverRef are required"})
		return
	}
	if task, scoped := taskPrincipal(c); scoped {
		approval.Tenant, approval.Namespace = task.Tenant, task.Namespace
		approval.RequestedBy = controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
		approval.IssueID = &task.IssueID
		if !s.approvalWithinTaskScope(c.Request.Context(), task, &approval) {
			c.JSON(http.StatusForbidden, ErrorResponse{Error: "approval target is outside task scope"})
			return
		}
	} else {
		approval.RequestedBy = humanActor(c, s)
	}
	if strings.TrimSpace(approval.Tenant) == "" || strings.TrimSpace(approval.Namespace) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "tenant and namespace are required"})
		return
	}
	if a := accessFrom(c); a != nil {
		if len(a.Namespace.Roles(approval.ApproverRef)) == 0 {
			c.JSON(400, ErrorResponse{Error: "approver must be a namespace member"})
			return
		}
		if !s.authorizeApprovalWork(c, &approval, true) {
			return
		}
	}
	created, err := s.store.Collaboration().CreateApproval(c.Request.Context(), &approval)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"approval": created})
}

func (s *Server) approvalWithinTaskScope(ctx context.Context, task *controlmodel.AgentTask, approval *controlmodel.Approval) bool {
	switch approval.TargetType {
	case "issue":
		return approval.TargetRef == task.IssueID.String()
	case "agent_task":
		return approval.TargetRef == task.ID.String()
	case "execution_attempt":
		id, err := uuid.Parse(approval.TargetRef)
		if err != nil {
			return false
		}
		execution, err := s.store.ExecutionAttempts().Get(ctx, id)
		return err == nil && execution.AgentTaskID == task.ID && execution.Tenant == task.Tenant && execution.Namespace == task.Namespace
	default:
		return false
	}
}

func (s *Server) listApprovals(c *gin.Context) {
	if !requireHumanPrincipal(c) {
		return
	}
	tenant, namespace, ok := requireCollaborationScope(c)
	if !ok {
		return
	}
	limit, offset := collaborationPagination(c)
	items, err := s.store.Collaboration().ListApprovals(c.Request.Context(), store.ApprovalFilter{
		Tenant: tenant, Namespace: namespace,
		ApproverRef: s.operatorFromContext(c), TargetType: c.Query("targetType"),
		TargetRef: c.Query("targetRef"), Status: controlmodel.ApprovalStatus(c.Query("status")),
		Limit: limit, Offset: offset,
	})
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) getApproval(c *gin.Context) {
	if !requireHumanPrincipal(c) {
		return
	}
	id, ok := parseUUIDParam(c, "approvalId")
	if !ok {
		return
	}
	approval, err := s.store.Collaboration().GetApproval(c.Request.Context(), id)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	if approval.ApproverRef != s.operatorFromContext(c) && approval.RequestedBy.Ref != s.operatorFromContext(c) {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "approval is not visible to this user"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"approval": approval})
}

func (s *Server) decideApproval(c *gin.Context) {
	if !requireHumanPrincipal(c) {
		return
	}
	id, ok := parseUUIDParam(c, "approvalId")
	if !ok {
		return
	}
	var req struct {
		Status          controlmodel.ApprovalStatus `json:"status"`
		ExpectedVersion int64                       `json:"expectedVersion"`
		Decision        json.RawMessage             `json:"decision,omitempty"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if req.ExpectedVersion <= 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "positive expectedVersion is required"})
		return
	}
	current, err := s.store.Collaboration().GetApproval(c.Request.Context(), id)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	if current.ApproverRef != s.operatorFromContext(c) {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "only the designated approver may decide this approval"})
		return
	}
	if envelope, parseErr := parseManagedToolApproval(current); parseErr == nil {
		if req.Status != controlmodel.ApprovalApproved && req.Status != controlmodel.ApprovalRejected {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "managed tool approval must be approved or rejected by its human approver"})
			return
		}
		if _, validateErr := s.validateManagedApprovalCurrent(c.Request.Context(), current); validateErr != nil {
			c.JSON(http.StatusConflict, ErrorResponse{Error: "managed tool approval no longer matches the active execution"})
			return
		}
		if !envelope.ExpiresAt.IsZero() && !time.Now().UTC().Before(envelope.ExpiresAt) {
			c.JSON(http.StatusConflict, ErrorResponse{Error: "managed tool approval has expired"})
			return
		}
	}
	approval, err := s.store.Collaboration().DecideApproval(c.Request.Context(), id, req.ExpectedVersion, req.Status, humanActor(c, s), req.Decision)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"approval": approval})
}

func (s *Server) writeCollaborationError(c *gin.Context, err error) {
	if strings.Contains(err.Error(), "required") || strings.Contains(err.Error(), "unsupported") ||
		strings.Contains(err.Error(), "transition") || strings.Contains(err.Error(), "acceptance blocked") {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	s.writeControlPlaneError(c, err)
}

func redactTeamForDiscovery(c *gin.Context, team *controlmodel.CollaborationTeam) {
	if a := accessFrom(c); a != nil && !a.Namespace.Decide(a.User, "team:"+team.ID.String(), "inspect").Allowed {
		team.Instructions = ""
		team.Policy = controlmodel.TeamPolicy{}
		for i := range team.Members {
			team.Members[i].Instructions = ""
			team.Members[i].CapabilityRequirements = nil
			team.Members[i].RuntimeBindingPolicy = nil
		}
	}
}

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
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"net/http"
	"slices"
	"strings"
)

func (s *Server) canReceiveWorkEvent(ctx context.Context, a *namespaceAccess, event *controlmodel.OutboxEvent) bool {
	n, err := s.store.Access().GetNamespace(ctx, a.Namespace.Tenant, a.Namespace.Name)
	if err != nil || len(n.Roles(a.User)) == 0 {
		return false
	}
	current := *a
	current.Namespace = n
	current.Roles = n.Roles(a.User)
	id, err := uuid.Parse(event.AggregateID)
	if err != nil {
		return false
	}
	var issueID uuid.UUID
	switch event.AggregateType {
	case "issue":
		issueID = id
	case "agent-task", "agent_task":
		t, e := s.store.Collaboration().GetAgentTask(ctx, id)
		if e == nil {
			issueID = t.IssueID
		}
	case "comment":
		v, e := s.store.Collaboration().GetComment(ctx, id)
		if e == nil {
			issueID = v.IssueID
		}
	case "orchestration-run", "orchestration_run":
		v, e := s.store.Orchestration().GetRun(ctx, id)
		if e == nil {
			issueID = v.RootIssueID
		}
	case "inbox":
		v, e := s.store.Collaboration().GetInbox(ctx, id, a.User)
		if e != nil {
			return false
		}
		if v.IssueID != nil {
			issueID = *v.IssueID
		} else {
			return true
		}
	default:
		return false
	}
	_, err = s.canAccessIssue(ctx, &current, issueID, false)
	return err == nil
}

func (s *Server) authorizeWorkObject(c *gin.Context, body map[string]json.RawMessage) bool {
	a := accessFrom(c)
	if a == nil {
		return true
	}
	ctx := c.Request.Context()
	write := c.Request.Method != http.MethodGet && c.Request.Method != http.MethodHead && !strings.HasSuffix(c.Request.URL.Path, "/download")
	checkIssue := func(id uuid.UUID) bool {
		_, err := s.canAccessIssue(ctx, a, id, write)
		if err != nil {
			s.accessFailure(c, err)
			return false
		}
		return true
	}
	for _, key := range []string{"issueId", "parentIssueId", "rootIssueId"} {
		values := []string{c.Param(key), c.Query(key)}
		var value string
		_ = json.Unmarshal(body[key], &value)
		values = append(values, value)
		for _, raw := range values {
			if raw != "" {
				id, err := uuid.Parse(raw)
				if err != nil {
					s.accessFailure(c, store.ErrNotFound)
					return false
				}
				if !checkIssue(id) {
					return false
				}
			}
		}
	}
	if raw := c.Param("taskId"); raw != "" && !strings.Contains(c.FullPath(), "subagent-tasks") {
		id, err := uuid.Parse(raw)
		if err != nil {
			s.accessFailure(c, store.ErrNotFound)
			return false
		}
		task, err := s.store.Collaboration().GetAgentTask(ctx, id)
		if err != nil {
			s.accessFailure(c, err)
			return false
		}
		if !checkIssue(task.IssueID) {
			return false
		}
	}
	if raw := c.Param("runId"); raw != "" {
		id, _ := uuid.Parse(raw)
		run, err := s.store.Orchestration().GetRun(ctx, id)
		if err != nil {
			s.accessFailure(c, err)
			return false
		}
		if !checkIssue(run.RootIssueID) {
			return false
		}
	}
	if raw := c.Param("attemptId"); raw != "" {
		id, _ := uuid.Parse(raw)
		v, err := s.store.ExecutionAttempts().Get(ctx, id)
		if err != nil {
			s.accessFailure(c, err)
			return false
		}
		task, err := s.store.Collaboration().GetAgentTask(ctx, v.AgentTaskID)
		if err != nil {
			s.accessFailure(c, err)
			return false
		}
		if !checkIssue(task.IssueID) {
			return false
		}
	}
	if raw := c.Param("sessionId"); raw != "" {
		id, _ := uuid.Parse(raw)
		session, err := s.store.Sessions().GetByID(ctx, id)
		if err != nil || !s.canAccessSession(ctx, a, session, write) {
			s.accessFailure(c, store.ErrNotFound)
			return false
		}
	}
	if raw := c.Param("artifactId"); raw != "" {
		id, _ := uuid.Parse(raw)
		if !s.canAccessArtifact(ctx, a, id, write) {
			s.accessFailure(c, store.ErrNotFound)
			return false
		}
	}
	if raw := c.Param("proposalId"); raw != "" {
		id, _ := uuid.Parse(raw)
		v, err := s.store.TeamProposals().Get(ctx, id)
		if err != nil {
			s.accessFailure(c, err)
			return false
		}
		if !checkIssue(v.IssueID) {
			return false
		}
	}
	if raw := c.Param("approvalId"); raw != "" {
		id, _ := uuid.Parse(raw)
		v, err := s.store.Collaboration().GetApproval(ctx, id)
		if err != nil {
			s.accessFailure(c, err)
			return false
		}
		if !s.authorizeApprovalWork(c, v, write) {
			return false
		}
	}
	if raw := c.Param("commentId"); raw != "" {
		id, _ := uuid.Parse(raw)
		v, err := s.store.Collaboration().GetComment(ctx, id)
		if err != nil {
			s.accessFailure(c, err)
			return false
		}
		if c.Param("issueId") != "" && c.Param("issueId") != v.IssueID.String() {
			s.accessFailure(c, store.ErrNotFound)
			return false
		}
		if !checkIssue(v.IssueID) {
			return false
		}
	}
	if raw := c.Param("inboxId"); raw != "" {
		id, _ := uuid.Parse(raw)
		item, err := s.store.Collaboration().GetInbox(ctx, id, a.User)
		if err != nil || item.Tenant != a.Namespace.Tenant || item.Namespace != a.Namespace.Name {
			s.accessFailure(c, store.ErrNotFound)
			return false
		}
		if item.IssueID != nil {
			if _, err := s.canAccessIssue(ctx, a, *item.IssueID, false); err != nil {
				s.accessFailure(c, err)
				return false
			}
		}
	}
	if c.Param("automationId") != "" && !s.automationDiagnosticsAllowed(c) {
		s.accessFailure(c, store.ErrNotFound)
		return false
	}
	if !s.authorizeExecutionResources(c, body) {
		return false
	}
	if !s.authorizeNestedWorkReferences(c, body) {
		return false
	}
	// All Issue execution targets are validated against this same namespace.
	// Creation, reassignment, and direct references cannot use dual membership
	// as implicit permission to export source data.
	for _, key := range []string{"agentId", "leaderAgentRef", "agentRef"} {
		var raw string
		_ = json.Unmarshal(body[key], &raw)
		if raw != "" {
			if _, err := s.activeAgentInScope(ctx, a.Namespace.Tenant, a.Namespace.Name, raw); err != nil {
				s.accessFailure(c, err)
				return false
			}
		}
	}
	if path := c.Request.URL.Path; strings.Contains(path, "/invocations") || strings.Contains(path, "/deliveries") || strings.Contains(path, "/automations/") && strings.Contains(path, "/runs") {
		// Integration diagnostics may contain many independent callers' data.
		allowed := controlmodel.NamespaceAllows(a.Roles, "work.audit") && c.Request.Method == "GET"
		if c.Param("automationId") != "" {
			allowed = s.automationDiagnosticsAllowed(c)
		}
		if !allowed {
			c.AbortWithStatusJSON(403, ErrorResponse{Error: "integration diagnostics require the owner or namespace auditor"})
			return false
		}
	}
	return true
}

func (s *Server) canAccessSession(ctx context.Context, a *namespaceAccess, session *store.Session, write bool) bool {
	if session == nil || session.Tenant != a.Namespace.Tenant || session.Namespace != a.Namespace.Name {
		return false
	}
	if session.AgentTaskID != nil {
		task, err := s.store.Collaboration().GetAgentTask(ctx, *session.AgentTaskID)
		if err != nil {
			return false
		}
		_, err = s.canAccessIssue(ctx, a, task.IssueID, write)
		return err == nil
	}
	if !write && controlmodel.NamespaceAllows(a.Roles, "work.audit") {
		return true
	}
	// Chat deletion keeps execution history. Its owner may still read that
	// history, but a deleted Chat must not grant Session mutation access.
	filters := []store.ChatFilter{{}, {Archived: true}}
	if !write {
		filters = append(filters, store.ChatFilter{Deleted: true})
	}
	for _, filter := range filters {
		filter.Tenant = session.Tenant
		filter.Namespace = session.Namespace
		filter.CreatorRef = a.User
		filter.Limit = 500
		for offset := 0; ; offset += 500 {
			filter.Offset = offset
			chats, err := s.store.Chats().List(ctx, filter)
			if err != nil {
				return false
			}
			for _, chat := range chats {
				if chat.SessionID == session.ID {
					return true
				}
			}
			if len(chats) < 500 {
				break
			}
		}
	}
	return false
}

func (s *Server) canAccessArtifact(ctx context.Context, a *namespaceAccess, id uuid.UUID, write bool) bool {
	v, links, err := s.store.Collaboration().GetArtifact(ctx, id)
	if err != nil || v.Tenant != a.Namespace.Tenant || v.Namespace != a.Namespace.Name {
		return false
	}
	// Linked business content follows the Issue even if its uploader is removed.
	linked := false
	for _, link := range links {
		if link.TargetType == "issue" {
			linked = true
			issueID, e := uuid.Parse(link.TargetRef)
			if e == nil {
				if _, e = s.canAccessIssue(ctx, a, issueID, write); e == nil {
					return true
				}
			}
		}
	}
	if v.SourceTaskID != nil {
		linked = true
		task, e := s.store.Collaboration().GetAgentTask(ctx, *v.SourceTaskID)
		if e == nil {
			_, e = s.canAccessIssue(ctx, a, task.IssueID, write)
			if e == nil {
				return true
			}
		}
	}
	if linked {
		return false
	}
	return v.Uploader.Type == controlmodel.ActorHuman && slices.Contains(a.Refs, v.Uploader.Ref) || !write && controlmodel.NamespaceAllows(a.Roles, "work.audit")
}

func (s *Server) authorizeApprovalWork(c *gin.Context, v *controlmodel.Approval, write bool) bool {
	a := accessFrom(c)
	if a == nil {
		return true
	}
	id, err := uuid.Parse(v.TargetRef)
	if err != nil {
		s.accessFailure(c, store.ErrNotFound)
		return false
	}
	var issueID uuid.UUID
	switch v.TargetType {
	case "issue":
		issueID = id
	case "agent-task", "agent_task":
		t, e := s.store.Collaboration().GetAgentTask(c.Request.Context(), id)
		if e == nil {
			issueID = t.IssueID
		}
	case "execution-attempt", "execution_attempt":
		v, e := s.store.ExecutionAttempts().Get(c.Request.Context(), id)
		if e == nil {
			t, e := s.store.Collaboration().GetAgentTask(c.Request.Context(), v.AgentTaskID)
			if e == nil {
				issueID = t.IssueID
			}
		}
	case "run-node", "run_node":
		node, e := s.store.Orchestration().GetNode(c.Request.Context(), id)
		if e == nil {
			run, e := s.store.Orchestration().GetRun(c.Request.Context(), node.RunID)
			if e == nil {
				issueID = run.RootIssueID
			}
		}
	default:
		s.accessFailure(c, store.ErrNotFound)
		return false
	}
	_, err = s.canAccessIssue(c.Request.Context(), a, issueID, write)
	if err != nil {
		s.accessFailure(c, err)
		return false
	}
	return true
}

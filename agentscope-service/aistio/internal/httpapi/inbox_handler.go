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
	"net/http"

	"github.com/gin-gonic/gin"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func (s *Server) inboxFilter(c *gin.Context) (store.InboxFilter, bool) {
	if !requireHumanPrincipal(c) {
		return store.InboxFilter{}, false
	}
	tenant, namespace, ok := requireCollaborationScope(c)
	if !ok {
		return store.InboxFilter{}, false
	}
	view := c.Query("view")
	if view != "" && view != "all" && view != "unread" && view != "attention" && view != "action" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid inbox view"})
		return store.InboxFilter{}, false
	}
	if _, err := store.DecodeInboxCursor(c.Query("cursor")); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return store.InboxFilter{}, false
	}
	limit, offset := collaborationPagination(c)
	if limit <= 0 {
		limit = 50
	}
	if limit > 499 {
		limit = 499
	}
	return store.InboxFilter{Tenant: tenant, Namespace: namespace, RecipientRef: s.operatorFromContext(c),
		Archived: c.Query("archived") == "true", Type: c.Query("type"), View: view, Cursor: c.Query("cursor"), Limit: limit, Offset: offset}, true
}

func (s *Server) listInbox(c *gin.Context) {
	filter, ok := s.inboxFilter(c)
	if !ok {
		return
	}
	limit := filter.Limit
	filter.Limit++
	items, err := s.store.Collaboration().ListInbox(c.Request.Context(), filter)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	hasMore := len(items) > limit
	cursor := ""
	if hasMore {
		items = items[:limit]
		cursor = store.EncodeInboxCursor(items[len(items)-1])
	}
	c.JSON(http.StatusOK, gin.H{"items": items, "hasMore": hasMore, "nextCursor": cursor})
}

func (s *Server) inboxSummary(c *gin.Context) {
	filter, ok := s.inboxFilter(c)
	if !ok {
		return
	}
	summary, err := s.store.Collaboration().InboxSummary(c.Request.Context(), filter)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"summary": summary})
}

func (s *Server) inboxForUser(c *gin.Context) (*controlmodel.InboxItem, bool) {
	if !requireHumanPrincipal(c) {
		return nil, false
	}
	id, ok := parseUUIDParam(c, "inboxId")
	if !ok {
		return nil, false
	}
	item, err := s.store.Collaboration().GetInbox(c.Request.Context(), id, s.operatorFromContext(c))
	if err != nil {
		s.writeCollaborationError(c, err)
		return nil, false
	}
	// Legacy read/archive clients only send an ID. Scoped clients additionally
	// fence changes against the currently selected workspace.
	if c.Query("tenant") != "" || c.Query("namespace") != "" {
		tenant, namespace, ok := requireCollaborationScope(c)
		if !ok {
			return nil, false
		}
		if item.Tenant != tenant || item.Namespace != namespace {
			s.writeCollaborationError(c, store.ErrNotFound)
			return nil, false
		}
	}
	return item, true
}

func (s *Server) getInbox(c *gin.Context) {
	item, ok := s.inboxForUser(c)
	if ok {
		c.JSON(http.StatusOK, gin.H{"item": item})
	}
}

func (s *Server) readInbox(c *gin.Context) {
	current, ok := s.inboxForUser(c)
	if !ok {
		return
	}
	value := true
	item, err := s.store.Collaboration().UpdateInbox(c.Request.Context(), current.ID, s.operatorFromContext(c), &value, nil)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"item": item})
}

func (s *Server) archiveInbox(c *gin.Context) {
	current, ok := s.inboxForUser(c)
	if !ok {
		return
	}
	value := true
	item, err := s.store.Collaboration().UpdateInbox(c.Request.Context(), current.ID, s.operatorFromContext(c), nil, &value)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"item": item})
}

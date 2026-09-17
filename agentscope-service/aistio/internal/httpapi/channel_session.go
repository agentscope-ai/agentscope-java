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
	"context"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type managedSessionRegistrationRequest struct {
	OwnerID     string `json:"ownerId"`
	AgentID     string `json:"agentId"`
	ExternalKey string `json:"externalKey"`
}

// internalFindOrCreateManagedSession is the control-plane boundary used by
// channel adapters. It registers the product session in the runtime store
// before the data plane starts emitting events, so runtime projections and
// console session lists have the same durable identity.
func (s *Server) internalFindOrCreateManagedSession(c *gin.Context) {
	var req managedSessionRegistrationRequest
	if err := c.ShouldBindJSON(&req); err != nil || strings.TrimSpace(req.OwnerID) == "" ||
		strings.TrimSpace(req.AgentID) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "ownerId and agentId are required"})
		return
	}
	if strings.TrimSpace(req.ExternalKey) == "" {
		// Preserve the bridge's one-off overload: absent keys start a new session.
		req.ExternalKey = "channel:" + uuid.NewString()
	}
	agentID, err := uuid.Parse(req.AgentID)
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "agentId must be a UUID"})
		return
	}
	agent, err := s.store.AgentCatalog().GetAgent(c.Request.Context(), agentID)
	if err != nil || agent.OwnerRef != req.OwnerID || agent.Status != controlmodel.AgentActive || agent.ArchivedAt != nil {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "agent not found"})
		return
	}
	var session *store.Session
	// Serialize find-or-create across Scheduler replicas before cp allocates an ID.
	err = s.store.WithSessionLock(c.Request.Context(), "channel-session:"+agent.ID.String()+":"+req.ExternalKey, func(ctx context.Context) error {
		var resolveErr error
		session, resolveErr = s.resolveAgentConversation(ctx, agent, req.ExternalKey, "channel", req.ExternalKey)
		return resolveErr
	})
	if err != nil {
		writeConversationTurnError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"id": session.SessionID})
}

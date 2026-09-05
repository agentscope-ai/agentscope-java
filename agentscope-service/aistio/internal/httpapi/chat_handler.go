// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type createChatRequest struct {
	Tenant    string    `json:"tenant"`
	Namespace string    `json:"namespace"`
	AgentID   uuid.UUID `json:"agentId"`
	Title     string    `json:"title"`
}

type patchChatRequest struct {
	Title       *string                  `json:"title"`
	Status      *controlmodel.ChatStatus `json:"status"`
	Pinned      *bool                    `json:"pinned"`
	LastReadSeq *int                     `json:"lastReadSeq"`
	Version     int64                    `json:"version"`
}

type chatAgentOption struct {
	ID          uuid.UUID                `json:"id"`
	Name        string                   `json:"name"`
	Description string                   `json:"description,omitempty"`
	Capability  invocationModeCapability `json:"capability"`
}

func chatTitle(value, agentName string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		value = "Chat with " + agentName
	}
	runes := []rune(value)
	if len(runes) > 120 {
		value = string(runes[:120])
	}
	return value
}

func (s *Server) listChats(c *gin.Context) {
	items, err := s.store.Chats().List(c, store.ChatFilter{
		Tenant: c.DefaultQuery("tenant", "default"), Namespace: c.DefaultQuery("namespace", "default"),
		CreatorRef: catalogOwnerRef(c, ""), Archived: parseTruthyQuery(c.Query("archived")),
		Limit: parseLimit(c, 100), Offset: parseOffset(c),
	})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if items == nil {
		items = []*controlmodel.Chat{}
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) listChatAgents(c *gin.Context) {
	tenant, namespace := c.DefaultQuery("tenant", "default"), c.DefaultQuery("namespace", "default")
	agents, err := s.store.AgentCatalog().ListAgents(c, store.AgentFilter{
		Tenant: tenant, Namespace: namespace, Status: controlmodel.AgentActive, Limit: parseLimit(c, 200),
	})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	items := make([]chatAgentOption, 0, len(agents))
	for _, agent := range agents {
		capability := s.inspectInvocationCapabilities(c, agent).Conversation
		items = append(items, chatAgentOption{ID: agent.ID, Name: agent.DisplayName,
			Description: agent.Description, Capability: capability})
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) createChat(c *gin.Context) {
	var req createChatRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.AgentID == uuid.Nil ||
		strings.TrimSpace(req.Tenant) == "" || strings.TrimSpace(req.Namespace) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "tenant, namespace and agentId are required"})
		return
	}
	agent, err := s.activeAgentInScope(c, req.Tenant, req.Namespace, req.AgentID.String())
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	chatID := uuid.New()
	session, err := s.resolveAgentConversation(c, agent, "", "chat", chatID.String())
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: err.Error()})
		return
	}
	value, err := s.store.Chats().Create(c, &controlmodel.Chat{
		ID: chatID, Tenant: agent.Tenant, Namespace: agent.Namespace, CreatorRef: catalogOwnerRef(c, ""),
		AgentID: agent.ID, AgentName: agent.DisplayName, SessionID: session.ID,
		RuntimeSession: session.SessionID, Title: chatTitle(req.Title, agent.DisplayName), Status: controlmodel.ChatActive,
	})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"chat": value})
}

func (s *Server) ownedChat(c *gin.Context) (*controlmodel.Chat, bool) {
	id, ok := parseUUIDParam(c, "chatId")
	if !ok {
		return nil, false
	}
	value, err := s.store.Chats().Get(c, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return nil, false
	}
	if value.CreatorRef != catalogOwnerRef(c, "") ||
		value.Tenant != c.DefaultQuery("tenant", "default") ||
		value.Namespace != c.DefaultQuery("namespace", "default") {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "Chat not found in scope"})
		return nil, false
	}
	return value, true
}

func (s *Server) getChat(c *gin.Context) {
	value, ok := s.ownedChat(c)
	if ok {
		c.JSON(http.StatusOK, gin.H{"chat": value})
	}
}

func (s *Server) patchChat(c *gin.Context) {
	value, ok := s.ownedChat(c)
	if !ok {
		return
	}
	var req patchChatRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.Version <= 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "valid version is required"})
		return
	}
	if req.Title != nil {
		value.Title = chatTitle(*req.Title, value.AgentName)
	}
	if req.Status != nil {
		if *req.Status != controlmodel.ChatActive && *req.Status != controlmodel.ChatArchived {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "status must be active or archived"})
			return
		}
		value.Status = *req.Status
	}
	if req.Pinned != nil {
		value.Pinned = *req.Pinned
	}
	if req.LastReadSeq != nil && *req.LastReadSeq >= 0 {
		value.LastReadSeq = *req.LastReadSeq
	}
	updated, err := s.store.Chats().Update(c, value, req.Version)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"chat": updated})
}

func (s *Server) sendChatTurn(c *gin.Context) {
	value, ok := s.ownedChat(c)
	if !ok {
		return
	}
	if value.Status != controlmodel.ChatActive {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "Archived Chat is read-only"})
		return
	}
	var req struct {
		Message string `json:"message"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || strings.TrimSpace(req.Message) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "message is required"})
		return
	}
	session, err := s.store.Sessions().GetByID(c, value.SessionID)
	if err != nil || session.AgentID != value.AgentID || session.Tenant != value.Tenant || session.Namespace != value.Namespace {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "Chat runtime session is unavailable"})
		return
	}
	if err = s.sendAgentConversationTurn(c, session, strings.TrimSpace(req.Message), "chat", value.ID.String()); err != nil {
		writeConversationTurnError(c, err)
		return
	}
	_ = s.store.Chats().Touch(c, value.ID)
	c.JSON(http.StatusAccepted, gin.H{
		"chatId": value.ID, "status": "running", "sessionId": session.ID,
		"runtimeSessionId": session.SessionID, "bindingId": session.BindingID,
		"eventsUrl":      "/api/v1/sessions/" + session.ID.String() + "/events?chatId=" + value.ID.String(),
		"eventStreamUrl": "/api/v1/sessions/" + session.ID.String() + "/events/stream?chatId=" + value.ID.String(),
	})
}

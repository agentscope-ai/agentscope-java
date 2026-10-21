/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package product

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

// ManagedTrigger retains the verified user and stable provider request identity.
// ExternalKey identifies a conversation; RequestID identifies one inbound message or fire.
type ManagedTrigger struct {
	OwnerID       string `json:"ownerId"`
	ActorID       string `json:"actorId,omitempty"`
	BindingID     string `json:"bindingId,omitempty"`
	AgentID       string `json:"agentId"`
	EnvironmentID string `json:"environmentId,omitempty"`
	AgentVersion  int    `json:"agentVersion,omitempty"`
	ExternalKey   string `json:"externalKey,omitempty"`
	RequestID     string `json:"requestId,omitempty"`
	Text          string `json:"text,omitempty"`
	Operation     string `json:"operation,omitempty"`
	SessionID     string `json:"sessionId,omitempty"`
	TurnID        string `json:"turnId,omitempty"`
	After         string `json:"after,omitempty"`
}

type ManagedTriggerHandler func(context.Context, ManagedTrigger) (json.RawMessage, error)

func (s *Server) SetManagedTriggerHandler(handler ManagedTriggerHandler) {
	s.managedTriggerHandler = handler
}

func (s *Server) SubmitManagedTrigger(ctx context.Context, request ManagedTrigger) (json.RawMessage, error) {
	if s == nil || s.managedTriggerHandler == nil {
		return nil, fmt.Errorf("Managed trigger runtime is unavailable")
	}
	if strings.TrimSpace(request.OwnerID) == "" || strings.TrimSpace(request.AgentID) == "" {
		return nil, fmt.Errorf("Managed trigger ownerId and agentId are required")
	}
	if request.Operation == "" {
		request.Operation = "submit"
	}
	switch request.Operation {
	case "provision":
		if request.RequestID == "" || len(request.RequestID) > 256 || request.AgentVersion < 0 {
			return nil, fmt.Errorf("Managed provision requires a stable requestId and valid version")
		}
	case "submit":
		if request.AgentVersion < 0 {
			return nil, fmt.Errorf("Managed Agent version cannot be negative")
		}
		if strings.TrimSpace(request.RequestID) == "" || len(request.RequestID) > 256 || strings.TrimSpace(request.Text) == "" {
			return nil, fmt.Errorf("Managed trigger requires text and a requestId of at most 256 characters")
		}
	case "events":
		if request.SessionID == "" || request.TurnID == "" {
			return nil, fmt.Errorf("Managed trigger requires sessionId and turnId")
		}
	default:
		return nil, fmt.Errorf("unsupported Managed trigger operation")
	}
	return s.managedTriggerHandler(ctx, request)
}

func (s *Server) internalManagedTrigger(c *gin.Context) {
	var request ManagedTrigger
	if c.ShouldBindJSON(&request) != nil {
		writeErr(c, http.StatusBadRequest, "invalid Managed trigger")
		return
	}
	if request.OwnerID != c.GetHeader("X-Builder-Internal-User") || request.OwnerID == "" {
		writeErr(c, http.StatusForbidden, "Managed trigger original user is required")
		return
	}
	result, err := s.SubmitManagedTrigger(c.Request.Context(), request)
	if err != nil {
		status := http.StatusBadRequest
		var upstream *ManagedServiceError
		switch {
		case errors.As(err, &upstream):
			status = upstream.StatusCode
		case errors.Is(err, store.ErrNotFound):
			status = http.StatusNotFound
		case errors.Is(err, store.ErrForbidden):
			status = http.StatusForbidden
		case errors.Is(err, store.ErrConflict):
			status = http.StatusConflict
		}
		writeErr(c, status, err.Error())
		return
	}
	c.Data(http.StatusOK, "application/json", result)
}

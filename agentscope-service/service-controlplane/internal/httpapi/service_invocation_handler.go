/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package httpapi

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

const endpointScopesContextKey = "endpoint-scopes"

func endpointScope(c *gin.Context, scope string) bool {
	value, exists := c.Get(endpointScopesContextKey)
	if !exists {
		return true
	} // Authenticated platform principals.
	scopes, _ := value.([]string)
	if scope == "approve" {
		scope = "interact"
	}
	for _, v := range scopes {
		if v == scope {
			return true
		}
	}
	c.JSON(http.StatusForbidden, ErrorResponse{Error: "credential requires scope: " + scope})
	return false
}
func (s *Server) loadServiceInvocation(c *gin.Context, scope string) (*model.EndpointInvocation, *model.Endpoint, bool) {
	if c.GetBool("public-session-webhook") {
		v := c.MustGet(publicSessionContextKey).(*sessionapi.Session)
		if !s.sessionAccess(c, v, scope) {
			return nil, nil, false
		}
		contract, err := serviceapi.ReadContract(v.Contract)
		if err != nil {
			s.writeControlPlaneError(c, err)
			return nil, nil, false
		}
		return sessionWebhookExecution(v), &contract.Endpoint, true
	}
	id, err := uuid.Parse(c.Param("invocationId"))
	if err != nil {
		c.JSON(400, ErrorResponse{Error: "invalid invocationId"})
		return nil, nil, false
	}
	inv, err := s.store.Endpoints().GetInvocation(c, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return nil, nil, false
	}
	if value, exists := c.Get(publicSessionContextKey); exists {
		v := value.(*sessionapi.Session)
		if inv.EndpointID != v.ID || !s.sessionAccess(c, v, scope) {
			return nil, nil, false
		}
		contract, e := serviceapi.ReadContract(inv.Contract)
		if e != nil || contract.PublicSessionID != v.ID {
			c.JSON(500, ErrorResponse{Error: "invalid session execution contract"})
			return nil, nil, false
		}
		return inv, &contract.Endpoint, true
	}
	ep, err := s.store.Endpoints().Get(c, inv.EndpointID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return nil, nil, false
	}
	// A platform human is an actor, not the Application owner identity. Membership grants
	// access to only this Application's invocation; approval still checks its designated human.
	if inv.ApplicationID != nil && c.GetHeader("X-API-Key") == "" {
		principal, authenticated := s.platformPrincipal(c.Request.Context(), requestBearerToken(c))
		if authenticated && strings.HasPrefix(principal, "platform-user:") {
			if !s.authorizeApplicationActor(c, inv, ep, principal, scope) {
				c.JSON(404, ErrorResponse{Error: "invocation is unavailable"})
				return nil, nil, false
			}
			c.Set(endpointPrincipalContextKey, principal)
			c.Set(endpointActorContextKey, model.Actor{Type: model.ActorHuman, Ref: strings.TrimPrefix(principal, "platform-user:")})
			return inv, ep, true
		}
	}
	if !s.authenticateEndpoint(c, ep, false) || !endpointScope(c, scope) {
		return nil, nil, false
	}
	if inv.PrincipalRef != c.GetString(endpointPrincipalContextKey) {
		c.JSON(404, ErrorResponse{Error: "invocation is unavailable"})
		return nil, nil, false
	}
	return inv, ep, true
}
func (s *Server) getServiceInvocation(c *gin.Context) {
	inv, _, ok := s.loadServiceInvocation(c, "read")
	if !ok {
		return
	}
	inv, err := s.refreshServiceInvocation(c, inv.ID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(200, gin.H{"invocation": serviceInvocationView(inv)})
}
func (s *Server) getServiceSnapshot(c *gin.Context) {
	inv, ep, ok := s.loadServiceInvocation(c, "read")
	if !ok {
		return
	}
	if _, err := s.refreshServiceInvocation(c, inv.ID); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if err := s.projectServiceInvocation(c, inv.ID); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	snapshot, err := serviceJournal(s.store, ep, inv).Snapshot(c)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	switch {
	case strings.HasSuffix(c.FullPath(), "/actions"):
		c.JSON(200, gin.H{"required_actions": snapshot.Actions, "as_of": snapshot.AsOf})
	case strings.HasSuffix(c.FullPath(), "/usage"):
		c.JSON(200, gin.H{"usage": snapshot.Usage, "as_of": snapshot.AsOf})
	case strings.HasSuffix(c.FullPath(), "/artifacts"):
		c.JSON(200, gin.H{"artifacts": snapshot.Artifacts, "as_of": snapshot.AsOf})
	default:
		c.JSON(200, snapshot)
	}
}
func serviceAfter(c *gin.Context) string {
	if cursor := c.GetHeader("Last-Event-ID"); cursor != "" {
		return cursor
	}
	return c.Query("after")
}
func (s *Server) getServiceEvents(c *gin.Context) {
	inv, ep, ok := s.loadServiceInvocation(c, "read")
	if !ok {
		return
	}
	after := serviceAfter(c)
	if _, err := serviceapi.Position(inv.ID, after); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	limit := 100
	if raw := c.Query("limit"); raw != "" {
		limit, _ = strconv.Atoi(raw)
	}
	if limit < 1 || limit > 1000 {
		c.JSON(400, ErrorResponse{Error: "limit must be 1..1000"})
		return
	}
	if _, err := s.refreshServiceInvocation(c, inv.ID); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if err := s.projectServiceInvocation(c, inv.ID); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	events, next, more, err := serviceJournal(s.store, ep, inv).Events(c, after, limit)
	if errors.Is(err, serviceapi.ErrCursorExpired) {
		c.JSON(410, gin.H{"error": "cursor_expired", "message": err.Error(), "snapshot_url": publicCommandBase(inv) + "/snapshot"})
		return
	}
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if value, exists := c.Get(publicSessionContextKey); exists {
		v := value.(*sessionapi.Session)
		data := []gin.H{}
		for _, event := range events {
			event.Data["turn_id"] = inv.ID.String()
			data = append(data, publicEventView(v, event))
		}
		c.JSON(200, gin.H{"data": data, "next_cursor": next, "has_more": more})
		return
	}
	c.JSON(200, gin.H{"data": events, "next_cursor": next, "has_more": more})
}
func (s *Server) streamServiceEvents(c *gin.Context) {
	inv, ep, ok := s.loadServiceInvocation(c, "read")
	if !ok {
		return
	}
	after := serviceAfter(c)
	if _, err := serviceapi.Position(inv.ID, after); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	j := serviceJournal(s.store, ep, inv)
	if _, err := s.refreshServiceInvocation(c, inv.ID); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if err := s.projectServiceInvocation(c, inv.ID); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	events, next, more, err := j.Events(c, after, 200)
	if errors.Is(err, serviceapi.ErrCursorExpired) {
		c.JSON(410, gin.H{"error": "cursor_expired", "message": err.Error(), "snapshot_url": publicCommandBase(inv) + "/snapshot"})
		return
	}
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	prepareEventStream(c)
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	lastHeartbeat := time.Now()
	for {
		for _, e := range events {
			var value any = e
			kind := e.Type
			if session, ok := c.Get(publicSessionContextKey); ok {
				e.Data["turn_id"] = inv.ID.String()
				value = publicEventView(session.(*sessionapi.Session), e)
				kind = strings.Replace(kind, "invocation.", "turn.", 1)
			}
			raw, _ := json.Marshal(value)
			if _, err = fmt.Fprintf(c.Writer, "id: %s\nevent: %s\ndata: %s\n\n", e.Cursor, kind, raw); err != nil {
				return
			}
		}
		after = next
		c.Writer.Flush()
		latest, err := s.store.Endpoints().GetInvocation(c, inv.ID)
		if err != nil {
			return
		}
		// End only after a terminal record is in the committed journal and all pages drained.
		snapshot, err := j.Snapshot(c)
		if err != nil {
			return
		}
		if !more && serviceapi.Terminal(latest.Status) && snapshot.Invocation["status"] == string(latest.Status) {
			return
		}
		if !more {
			select {
			case <-c.Request.Context().Done():
				return
			case <-ticker.C:
			}
		}
		if _, err = s.refreshServiceInvocation(c, inv.ID); err != nil {
			return
		}
		if err = s.projectServiceInvocation(c, inv.ID); err != nil {
			return
		}
		events, next, more, err = j.Events(c, after, 200)
		if err != nil {
			return
		}
		if time.Since(lastHeartbeat) >= 15*time.Second {
			if _, err = fmt.Fprint(c.Writer, ": heartbeat\n\n"); err != nil {
				return
			}
			c.Writer.Flush()
			lastHeartbeat = time.Now()
		}
	}
}
func (s *Server) getServiceCapabilities(c *gin.Context) {
	ep, err := s.store.Endpoints().GetBySlug(c, c.Param("slug"))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !s.authenticateEndpoint(c, ep, false) || !endpointScope(c, "read") {
		return
	}
	bound, raw, err := s.endpointSubmissionContract(c, ep, nil)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	contract, err := serviceapi.ReadContract(raw)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(200, gin.H{"endpoint_id": ep.ID, "release_id": ep.ActiveReleaseID, "mode": bound.InvocationMode, "target_type": bound.TargetType, "capability_basis": "all_published_candidates", "capabilities": guaranteedServiceCapabilities(contract), "input_schema": bound.InputSchema, "output_schema": bound.OutputSchema, "result_mapping": bound.ResultMapping, "event_schema_version": 1})
}

// Artifact download verifies the invocation owner and its Issue linkage using the same authorization path.
func (s *Server) downloadServiceArtifact(c *gin.Context) { s.downloadEndpointJobArtifact(c) }

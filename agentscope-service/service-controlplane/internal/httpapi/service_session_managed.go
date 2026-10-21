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
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/product"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/gin-gonic/gin"
)

func (s *Server) publicManagedInput(c *gin.Context, inv *model.EndpointInvocation, ep *model.Endpoint, kind string) {
	key := c.GetHeader("Idempotency-Key")
	if key == "" {
		c.JSON(400, ErrorResponse{Error: "Idempotency-Key is required"})
		return
	}
	turn, err := s.nativeServiceTurn(c, ep, inv)
	if err != nil {
		c.JSON(409, ErrorResponse{Error: "Turn has not started"})
		return
	}
	raw, err := io.ReadAll(http.MaxBytesReader(c.Writer, c.Request.Body, 16<<20))
	if err != nil {
		c.Status(413)
		return
	}
	var body any
	if err = json.Unmarshal(raw, &body); err != nil {
		c.JSON(400, ErrorResponse{Error: "invalid Input body"})
		return
	}
	raw, err = s.nativeRuntimeInput(c, ep, inv, turn, endpointActor(c), key, kind, "", body)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	var result any
	_ = json.Unmarshal(raw, &result)
	c.JSON(202, result)
}

func (s *Server) managedPublicSnapshot(ctx context.Context, v *sessionapi.Session, c *serviceapi.Contract) (map[string]any, error) {
	owner, _, err := s.publicNativeSession(ctx, v, c)
	if err != nil {
		return nil, err
	}
	origin, err := s.nativePublicSessionOrigin(ctx, v, c, owner, "session-snapshot:"+v.ID.String())
	if err != nil {
		return nil, err
	}
	raw, err := s.product.ManagedRuntimeRequest(ctx, owner, product.ManagedRuntimeCall{Context: origin, Operation: "snapshot", Limit: 256})
	if err != nil {
		return nil, err
	}
	var snapshot map[string]any
	if err = json.Unmarshal(raw, &snapshot); err != nil {
		return nil, err
	}
	snapshot["session"] = s.publicSessionView(ctx, v)
	return snapshot, nil
}

func (s *Server) forkPublicSession(c *gin.Context) {
	source, ok := s.loadPublicSession(c, "interact")
	if !ok {
		return
	}
	var req struct {
		Target     string `json:"target_session_id"`
		Checkpoint string `json:"checkpoint_id"`
	}
	key := c.GetHeader("Idempotency-Key")
	if strings.TrimSpace(key) == "" || len(key) > 256 {
		c.JSON(400, ErrorResponse{Error: "Idempotency-Key must contain 1..256 characters"})
		return
	}
	decoder := json.NewDecoder(http.MaxBytesReader(c.Writer, c.Request.Body, 16<<10))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&req) != nil || decoder.Decode(new(any)) != io.EOF || req.Target == "" || req.Target == source.ID.String() || req.Checkpoint == "" {
		c.JSON(400, ErrorResponse{Error: "a different target_session_id and checkpoint_id are required"})
		return
	}
	// Reuse the same ownership and scope checks for the destination before exposing
	// either native ID to the internal fork endpoint.
	saved := c.Params
	c.Params = append(gin.Params{}, saved...)
	for i := range c.Params {
		if c.Params[i].Key == "publicSessionId" {
			c.Params[i].Value = req.Target
		}
	}
	dest, ok := s.loadPublicSession(c, "interact")
	c.Params = saved
	if !ok {
		return
	}
	sc, err := serviceapi.ReadContract(source.Contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	dc, err := serviceapi.ReadContract(dest.Contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !s.publicSessionIsManaged(sc) || !s.publicSessionIsManaged(dc) {
		sessionUnsupported(c, "fork")
		return
	}
	if source.Status != "active" || dest.Status != "active" {
		c.JSON(409, ErrorResponse{Error: "source and destination Sessions must be active"})
		return
	}
	so, _, err := s.publicNativeSession(c, source, sc)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	do, _, err := s.publicNativeSession(c, dest, dc)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if so != do {
		c.JSON(409, ErrorResponse{Error: "fork requires Agent definitions with the same runtime owner"})
		return
	}
	origin, err := s.ensureNativePublicSession(c, source, sc, so, key)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if _, err = s.ensureNativePublicSession(c, dest, dc, do, "fork-target:"+key); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	raw, err := s.product.ManagedRuntimeRequest(c, so, product.ManagedRuntimeCall{Context: origin,
		Operation: "fork_checkpoint", Payload: gin.H{"target_session_id": dest.ID.String(), "checkpoint_id": req.Checkpoint}})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.Data(http.StatusAccepted, "application/json", raw)
}

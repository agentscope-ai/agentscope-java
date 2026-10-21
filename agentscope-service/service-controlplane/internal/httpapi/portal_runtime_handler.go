// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeportal"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
)

func goRuntimeKind(kind string) bool {
	switch kind {
	case "external", "hosted-runtime", "team", "workflow", "mixed":
		return true
	}
	return false
}

func runtimeHTTPError(c *gin.Context, err error) {
	var response *runtimeportal.ResponseError
	if errors.As(err, &response) {
		switch response.Status {
		case 400, 401, 403, 404, 409, 413, 422, 429:
			c.Status(response.Status)
			return
		}
	}
	switch {
	case errors.Is(err, runtimeportal.ErrInvalid):
		c.Status(http.StatusBadRequest)
	case errors.Is(err, runtimeportal.ErrNotFound), errors.Is(err, store.ErrNotFound):
		c.Status(http.StatusNotFound)
	case errors.Is(err, runtimeportal.ErrConflict), errors.Is(err, runtimeportal.ErrLease), errors.Is(err, store.ErrConflict):
		c.Status(http.StatusConflict)
	case errors.Is(err, runtimeportal.ErrPermission), errors.Is(err, store.ErrForbidden):
		c.Status(http.StatusForbidden)
	default:
		c.Status(http.StatusServiceUnavailable)
	}
}

func (s *Server) bootstrapPortalRuntime(c *gin.Context) {
	var in runtimeprotocol.RuntimeSessionBootstrap
	if err := runtimeprotocol.Decode(c.Request.Body, &in); err != nil {
		c.Status(400)
		return
	}
	if !goRuntimeKind(in.RuntimeKind) || in.Context.TurnID != nil || len(in.ConfigurationRevision) != 64 {
		c.Status(422)
		return
	}
	var configuration struct {
		Session struct {
			ID      string `json:"id"`
			OwnerID string `json:"ownerId"`
		} `json:"session"`
		Contract json.RawMessage `json:"contract"`
	}
	if json.Unmarshal(in.Configuration, &configuration) != nil || configuration.Session.ID != in.Context.SessionID || strings.TrimSpace(configuration.Session.OwnerID) == "" {
		c.Status(400)
		return
	}
	frozen, err := serviceapi.ReadContract(configuration.Contract)
	if err != nil || frozen.PublicSessionID.String() != in.Context.SessionID || frozen.Endpoint.ID != frozen.PublicSessionID || frozen.Endpoint.Tenant != in.Context.Tenant || frozen.Endpoint.Namespace != in.Context.Namespace {
		c.Status(422)
		return
	}
	revision, err := runtimeprotocol.Digest(in.Configuration)
	if err != nil || revision != in.ConfigurationRevision {
		c.Status(400)
		return
	}
	digest, err := runtimeprotocol.BootstrapDigest(in)
	if err != nil {
		c.Status(400)
		return
	}
	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()
	row, err := s.store.RuntimePortal().Bootstrap(ctx, in, digest, time.Now().UTC())
	if err != nil {
		runtimeHTTPError(c, err)
		return
	}
	c.JSON(200, runtimeprotocol.RuntimeSessionReady{Version: runtimeprotocol.Version, SessionID: row.Bootstrap.Context.SessionID,
		CommandID: row.Bootstrap.Context.CommandID, ConfigurationRevision: row.Bootstrap.ConfigurationRevision, ConfigurationDigest: row.Digest})
}

func (s *Server) acceptPortalRuntimeTurn(c *gin.Context) {
	var in runtimeprotocol.TurnCommand
	if err := runtimeprotocol.Decode(c.Request.Body, &in); err != nil {
		c.Status(400)
		return
	}
	if in.Context.TurnID == nil || *in.Context.TurnID != in.Context.CommandID || in.AdmissionSequence < 1 || strings.TrimSpace(in.IdempotencyKey) == "" || len(in.IdempotencyKey) > 256 {
		c.Status(400)
		return
	}
	digest, err := runtimeprotocol.Digest(in.Input)
	if err != nil || digest != in.InputDigest {
		c.Status(400)
		return
	}
	digest, err = runtimeprotocol.TurnDigest(in)
	if err != nil {
		c.Status(400)
		return
	}
	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()
	row, err := s.store.RuntimePortal().AcceptTurn(ctx, in, digest, time.Now().UTC())
	if err != nil {
		runtimeHTTPError(c, err)
		return
	}
	command := row.Command
	c.JSON(200, runtimeprotocol.RuntimeCommandReceipt{Version: runtimeprotocol.Version, SessionID: command.Context.SessionID,
		TurnID: *command.Context.TurnID, CommandID: command.Context.CommandID, InputDigest: command.InputDigest, AdmissionSequence: command.AdmissionSequence, Status: "runtime_accepted"})
}

func (s *Server) portalRuntimeTurnReceipt(c *gin.Context) {
	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()
	row, err := s.store.RuntimePortal().Turn(ctx, c.Param("id"))
	if err != nil {
		runtimeHTTPError(c, err)
		return
	}
	command := row.Command
	if command.Context.SessionID != c.Param("session") {
		c.Status(404)
		return
	}
	c.JSON(200, runtimeprotocol.RuntimeCommandReceipt{Version: runtimeprotocol.Version, SessionID: command.Context.SessionID,
		TurnID: *command.Context.TurnID, CommandID: command.Context.CommandID, InputDigest: command.InputDigest, AdmissionSequence: command.AdmissionSequence, Status: "runtime_accepted"})
}

func (s *Server) acceptPortalRuntimeInteraction(c *gin.Context) {
	var command runtimeprotocol.InteractionCommand
	if err := runtimeprotocol.Decode(c.Request.Body, &command); err != nil || command.Context.TurnID == nil {
		c.Status(400)
		return
	}
	if command.Kind != "cancel" && command.Kind != "approval" && command.Kind != "signal" {
		c.Status(422)
		return
	}
	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()
	var row *runtimeportal.Interaction
	var err error
	if command.Kind == "approval" {
		if _, priorErr := s.store.RuntimePortal().Interaction(ctx, command.Context.SessionID, command.Context.CommandID); errors.Is(priorErr, runtimeportal.ErrNotFound) {
			if err = (&portalNativeBackend{server: s}).validateApprovalAdmission(ctx, command); err != nil {
				runtimeHTTPError(c, err)
				return
			}
		} else if priorErr != nil {
			runtimeHTTPError(c, priorErr)
			return
		}
		row, err = s.store.RuntimePortal().AcceptApproval(ctx, command, time.Now().UTC())
	} else if command.Kind == "signal" {
		if _, priorErr := s.store.RuntimePortal().Interaction(ctx, command.Context.SessionID, command.Context.CommandID); errors.Is(priorErr, runtimeportal.ErrNotFound) {
			if err = (&portalNativeBackend{server: s}).validateSignalAdmission(ctx, command); err != nil {
				runtimeHTTPError(c, err)
				return
			}
		} else if priorErr != nil {
			runtimeHTTPError(c, priorErr)
			return
		}
		row, err = s.store.RuntimePortal().AcceptSignal(ctx, command, time.Now().UTC())
	} else {
		row, err = s.store.RuntimePortal().AcceptCancellation(ctx, command, time.Now().UTC())
	}
	if err != nil {
		runtimeHTTPError(c, err)
		return
	}
	c.JSON(200, row.Receipt)
}

func (s *Server) applyPortalRuntimeLifecycle(c *gin.Context) {
	var request runtimeprotocol.RuntimeSessionLifecycle
	if err := runtimeprotocol.Decode(c.Request.Body, &request); err != nil {
		c.Status(400)
		return
	}
	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()
	receipt, err := s.store.RuntimePortal().ApplyLifecycle(ctx, request)
	if err != nil {
		runtimeHTTPError(c, err)
		return
	}
	c.JSON(200, receipt)
}

func (s *Server) portalRuntimeInteractionReceipt(c *gin.Context) {
	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()
	row, err := s.store.RuntimePortal().Interaction(ctx, c.Param("session"), c.Param("id"))
	if err != nil {
		runtimeHTTPError(c, err)
		return
	}
	c.JSON(200, row.Receipt)
}

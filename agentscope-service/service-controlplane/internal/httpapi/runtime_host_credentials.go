// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package httpapi

import (
	"context"
	"errors"
	"net/http"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeauth"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
)

const hostedCredentialTTL = 15 * time.Minute

// Machine credential renewal preserves identity and does not renew a Host or
// Attempt lease. A valid Host bearer and its current registration are required.
func (s *Server) refreshRuntimeHostCredential(c *gin.Context) {
	id, ok := parseUUIDParam(c, "hostId")
	if !ok {
		return
	}
	rawClaims, exists := c.Get(runtimeHostClaimsContextKey)
	claims, valid := rawClaims.(runtimeauth.Claims)
	if !exists || !valid {
		c.JSON(http.StatusUnauthorized, ErrorResponse{Error: "Host-scoped credential is required"})
		return
	}
	var request struct {
		Generation int64 `json:"generation"`
	}
	if err := c.ShouldBindJSON(&request); err != nil || request.Generation <= 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "generation is required"})
		return
	}
	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()
	host, err := s.store.RuntimeRegistry().GetRuntimeHost(ctx, id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if host.LeaseGeneration != request.Generation || host.HostKey != claims.HostKey ||
		host.Tenant != claims.Tenant || host.Namespace != claims.Namespace {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "Host registration changed"})
		return
	}
	token, renewed, err := s.runtimeTokens.Mint(claims.HostKey, claims.Tenant, claims.Namespace, time.Now().UTC())
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: "generate Runtime Host credential"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"runtimeToken": token, "hostKey": renewed.HostKey,
		"tenant": renewed.Tenant, "namespace": renewed.Namespace,
		"expiresAt": time.Unix(renewed.ExpiresAt, 0).UTC()})
}

// Host authentication and the immutable lease proof are the renewal authority.
// An expired task/attempt token alone cannot obtain another credential. This
// read does not extend a physical lease or restore a revoked Portal permit.
func (s *Server) refreshExecutionCredentials(c *gin.Context) {
	hostID, id, lease, ok := s.bindExecutionLeaseCredential(c, false)
	if !ok {
		return
	}
	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()
	err := s.store.WithSessionLock(ctx, "portal-attempt-stream:"+id.String(), func(ctx context.Context) error {
		attempt, err := s.store.ExecutionAttempts().Get(ctx, id)
		if err != nil {
			return err
		}
		if attempt.HostID == nil || *attempt.HostID != hostID ||
			attempt.LeaseToken != lease.LeaseToken || attempt.FencingToken != lease.FencingToken {
			return store.ErrConflict
		}
		stopped, err := s.portalNativeStopped(ctx, attempt)
		if err != nil {
			return err
		}
		tokens := s.taskTokens
		tokens.TTL = hostedCredentialTTL
		now := time.Now().UTC()
		attemptToken, err := tokens.MintAttempt(attempt.ID, attempt.DispatchGeneration,
			string(attempt.BackendKind), hostID.String(), now)
		if err != nil {
			return err
		}
		taskToken := ""
		if !stopped && !model.IsExecutionAttemptTerminal(attempt.State) && attempt.State != model.ExecutionCancelRequested &&
			attempt.LeaseExpiresAt != nil && attempt.LeaseExpiresAt.After(now) {
			task, err := s.store.Collaboration().GetAgentTask(ctx, attempt.AgentTaskID)
			if err != nil {
				return err
			}
			contract, loadErr := serviceapi.ContractForIssue(ctx, s.store, task.IssueID)
			if loadErr != nil {
				return loadErr
			}
			if s.portalExecution() && (contract == nil || contract.PortalCommandID == "") {
				return store.ErrForbidden
			}
			if attempt.State == model.ExecutionWaiting {
				err = serviceapi.CheckPortalWaiting(ctx, s.store, contract, task.Tenant, task.Namespace, false)
			} else {
				err = serviceapi.CheckTaskPortalExecution(ctx, s.store, task)
			}
			if err != nil && !errors.Is(err, store.ErrForbidden) {
				return err
			}
			if err == nil {
				taskToken, err = tokens.MintFenced(task.ID, attempt.ID, attempt.DispatchGeneration,
					attempt.FencingToken, hostID, now)
				if err != nil {
					return err
				}
			}
		}
		c.JSON(http.StatusOK, gin.H{"attempt": attempt, "attemptToken": attemptToken, "taskToken": taskToken})
		return nil
	})
	if err != nil {
		s.writeControlPlaneError(c, err)
	}
}

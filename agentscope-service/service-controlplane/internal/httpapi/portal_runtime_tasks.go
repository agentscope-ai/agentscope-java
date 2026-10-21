// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
)

type runtimeSignalReplayKey struct{}

// The Portal's credential authenticates transport only. Collaboration also
// requires the original physical task lease and its current root grant.
func (s *Server) runtimeTaskGrantMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		ctx, cancel := context.WithTimeout(c.Request.Context(), 10*time.Second)
		defer cancel()
		c.Request = c.Request.WithContext(ctx)
		task, ok := taskPrincipal(c)
		if !ok {
			c.AbortWithStatusJSON(http.StatusUnauthorized, ErrorResponse{Error: "task credential required"})
			return
		}
		token := c.GetHeader("X-Agent-Task-Token")
		if token == "" {
			token = requestBearerToken(c)
		}
		claims, err := s.taskTokens.VerifyClaims(token, time.Now())
		if err != nil || claims.Fence <= 0 {
			c.AbortWithStatusJSON(http.StatusUnauthorized, ErrorResponse{Error: "physical task lease credential required"})
			return
		}
		contract, err := serviceapi.ContractForIssue(ctx, s.store, task.IssueID)
		if err == nil && (contract == nil || contract.PortalCommandID == "") {
			err = store.ErrForbidden
		}
		if err == nil {
			err = serviceapi.CheckTaskPortalExecution(ctx, s.store, task)
		}
		if errors.Is(err, store.ErrForbidden) && contract != nil && task.Status == model.AgentTaskRunning &&
			(strings.HasSuffix(c.FullPath(), "/mcp/collaboration") || strings.HasSuffix(c.FullPath(), "/run/signals/:name")) {
			turn, loadErr := s.store.RuntimePortal().Turn(ctx, contract.PortalCommandID)
			if loadErr == nil && turn.Status == "queued" && !turn.StopRequested && turn.ControlCommandID != "" {
				// A freshly admitted control deliberately clears the execution grant.
				// Its live physical task may replay that receipt, but may not start new work.
				if scopeErr := serviceapi.CheckPortalWaiting(ctx, s.store, contract, task.Tenant, task.Namespace, false); scopeErr == nil {
					ctx = context.WithValue(ctx, runtimeSignalReplayKey{}, true)
					err = nil
				}
			}
		}
		if err != nil {
			status := http.StatusServiceUnavailable
			if errors.Is(err, store.ErrForbidden) || errors.Is(err, store.ErrNotFound) {
				status = http.StatusForbidden
			}
			c.AbortWithStatusJSON(status, ErrorResponse{Error: "root execution authorization unavailable"})
			return
		}
		c.Request = c.Request.WithContext(serviceapi.WithContract(ctx, contract))
		c.Next()
	}
}

func (s *Server) runtimeApprovalScopeMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		ctx, cancel := context.WithTimeout(c.Request.Context(), 10*time.Second)
		defer cancel()
		c.Request = c.Request.WithContext(ctx)
		task, ok := taskPrincipal(c)
		claims, err := s.taskTokens.VerifyClaims(c.GetHeader("X-Agent-Task-Token"), time.Now())
		if !ok || err != nil || claims.Fence <= 0 || c.Param("taskId") != task.ID.String() {
			c.AbortWithStatusJSON(http.StatusUnauthorized, ErrorResponse{Error: "physical task lease credential required"})
			return
		}
		contract, err := serviceapi.ContractForIssue(ctx, s.store, task.IssueID)
		if err == nil && (contract == nil || contract.PortalCommandID == "") {
			err = store.ErrForbidden
		}
		if err == nil {
			err = serviceapi.CheckPortalWaiting(ctx, s.store, contract, task.Tenant, task.Namespace, false)
		}
		if err != nil {
			status := http.StatusServiceUnavailable
			if errors.Is(err, store.ErrForbidden) || errors.Is(err, store.ErrNotFound) {
				status = http.StatusForbidden
			}
			c.AbortWithStatusJSON(status, ErrorResponse{Error: "approval task scope is unavailable"})
			return
		}
		c.Next()
	}
}

func (s *Server) checkRuntimeApprovalDelivery(ctx context.Context, approval *model.Approval) error {
	// The legacy control plane has no Portal-owned grant. Its existing approval
	// contract remains unchanged; the private execution listener requires one.
	if !s.portalExecution() {
		return nil
	}
	envelope, err := parseManagedToolApproval(approval)
	if err != nil {
		return err
	}
	task, err := s.store.Collaboration().GetAgentTask(ctx, envelope.AgentTaskID)
	if err != nil {
		return err
	}
	contract, err := serviceapi.ContractForIssue(ctx, s.store, task.IssueID)
	if err != nil {
		return err
	}
	if contract == nil || contract.PortalCommandID == "" {
		return store.ErrForbidden
	}
	turn, err := s.store.RuntimePortal().Turn(ctx, contract.PortalCommandID)
	if err != nil {
		return err
	}
	var decision map[string]any
	if json.Unmarshal(approval.Decision, &decision) != nil || decision["portal_command_id"] != turn.ActivationCommandID ||
		decision["portal_fence"] != turn.Fence || firstMetadataInt64(decision, "portal_generation") != turn.Generation {
		return store.ErrForbidden
	}
	interaction, err := s.store.RuntimePortal().Interaction(ctx, turn.Command.Context.SessionID, turn.ActivationCommandID)
	if err != nil {
		return err
	}
	if interaction.Command.Kind != "approval" || interaction.ActivationGeneration != turn.Generation || interaction.ActivationFence != turn.Fence ||
		interaction.Command.Payload["target_command_id"] != turn.Command.Context.CommandID || approval.DecidedBy == nil ||
		approval.DecidedBy.Type != model.ActorHuman || approval.DecidedBy.Ref != interaction.Command.Context.Actor["user"] {
		return store.ErrForbidden
	}
	answers, _ := interaction.Command.Payload["answers"].([]any)
	matched := false
	for _, raw := range answers {
		answer, _ := raw.(map[string]any)
		matched = matched || answer["request_id"] == approval.ID.String() &&
			(answer["allow"] == true && approval.Status == model.ApprovalApproved || answer["allow"] == false && approval.Status == model.ApprovalRejected)
	}
	if !matched {
		return store.ErrForbidden
	}
	return serviceapi.CheckPortalWaiting(ctx, s.store, contract, task.Tenant, task.Namespace, true)
}

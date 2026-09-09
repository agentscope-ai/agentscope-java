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
	"errors"
	"fmt"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/asdp"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/conversation"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type invocationModeCapability struct {
	State  string `json:"state"`
	Reason string `json:"reason"`
}

type agentInvocationCapabilities struct {
	AgentID      uuid.UUID                           `json:"agentId"`
	Job          invocationModeCapability            `json:"job"`
	Conversation invocationModeCapability            `json:"conversation"`
	Features     map[string]invocationModeCapability `json:"features"`
}

func (s *Server) inspectInvocationCapabilities(c *gin.Context, agent *controlmodel.Agent) agentInvocationCapabilities {
	result := agentInvocationCapabilities{AgentID: agent.ID, Features: map[string]invocationModeCapability{
		"events.stream": {State: "partial", Reason: "Event availability depends on runtime reporting and transport"},
		"cancel":        {State: "partial", Reason: "Cancellation depends on the selected runtime binding"},
		"resume":        {State: "not_supported", Reason: "No conversation-capable runtime candidate is available"},
	}}
	if agent.Status != controlmodel.AgentActive {
		reason := "Agent lifecycle is not active"
		result.Job, result.Conversation = invocationModeCapability{State: "unavailable", Reason: reason}, invocationModeCapability{State: "unavailable", Reason: reason}
		return result
	}
	bindings, _ := s.store.AgentCatalog().ListBindings(c, agent.ID, true)
	instances, _ := s.store.RuntimeRegistry().ListAgentInstances(c, agent.Tenant, agent.Namespace, agent.ID)
	readiness, _ := s.inspectAgentReadiness(c, agent, bindings, instances)
	if readiness.State == "ready" || readiness.State == "degraded" {
		result.Job = invocationModeCapability{State: "available", Reason: readiness.Reason}
	} else {
		result.Job = invocationModeCapability{State: "unavailable", Reason: readiness.Reason}
	}
	policy, err := s.store.Orchestration().GetRuntimePolicy(c, agent.Tenant, agent.Namespace, agent.ID.String())
	if err != nil {
		result.Conversation = invocationModeCapability{State: "unavailable", Reason: "Agent has no runtime policy"}
		return result
	}
	hasConversationKind := false
	managedUnavailableReason := ""
	for _, candidate := range policy.Candidates {
		binding, bindingErr := s.store.AgentCatalog().GetBinding(c, candidate.Binding.BindingID)
		if bindingErr != nil || binding.AgentID != agent.ID || !binding.Enabled || binding.ArchivedAt != nil {
			continue
		}
		switch binding.Kind {
		case controlmodel.DataPlaneManaged:
			hasConversationKind = true
			var cfg controlmodel.ManagedBindingConfiguration
			configurationValid := json.Unmarshal(binding.Configuration, &cfg) == nil &&
				cfg.OwnerRef != "" && cfg.ManagedDefinitionRef != ""
			if s.product != nil && configurationValid &&
				controlmodel.RuntimeSecurityMatches(binding.Kind, nil, candidate.SecurityConstraints) {
				if validationErr := s.product.ValidateManagedRuntime(c, cfg.OwnerRef, cfg.ManagedDefinitionRef); validationErr == nil {
					result.Conversation = invocationModeCapability{State: "available", Reason: "Managed runtime supports interactive sessions"}
					result.Features["resume"] = invocationModeCapability{State: "available", Reason: "Managed sessions can accept additional turns"}
					return result
				} else if managedUnavailableReason == "" {
					managedUnavailableReason = validationErr.Error()
				}
			}
		case controlmodel.DataPlaneExternalApplication:
			hasConversationKind = true
			for _, instance := range instances {
				if externalConversationCandidate(instance, binding, candidate) {
					result.Conversation = invocationModeCapability{State: "available", Reason: "A healthy External instance accepts conversation turns"}
					result.Features["resume"] = invocationModeCapability{State: "available", Reason: "The selected External instance supports continued turns"}
					return result
				}
			}
		case controlmodel.DataPlaneHostedRuntime:
			hasConversationKind = true
			if s.hostedConversationCandidate(c, agent, candidate) {
				result.Conversation = invocationModeCapability{State: "available", Reason: "Hosted runtime supports durable conversation turns"}
				result.Features["resume"] = invocationModeCapability{State: "available", Reason: "Hosted turns resume through the provider session or persisted transcript"}
				return result
			}
		}
	}
	if hasConversationKind {
		reason := "Conversation-capable bindings currently have no eligible runtime"
		if managedUnavailableReason != "" {
			reason = managedUnavailableReason
		}
		result.Conversation = invocationModeCapability{State: "unavailable", Reason: reason}
	} else {
		result.Conversation = invocationModeCapability{State: "not_supported", Reason: "No configured runtime supports conversation turns"}
	}
	return result
}

func externalConversationCandidate(instance *controlmodel.AgentInstance, binding *controlmodel.AgentBinding,
	candidate controlmodel.RuntimeBindingCandidate) bool {
	if instance.BindingID != binding.ID ||
		(candidate.Binding.InstanceSelector["instance"] != "" && candidate.Binding.InstanceSelector["instance"] != instance.InstanceKey) ||
		instance.Health != controlmodel.RuntimeHealthHealthy ||
		(instance.Capacity > 0 && instance.ActiveSessions >= instance.Capacity) ||
		!controlmodel.JSONContains(instance.Capabilities, candidate.RequiredCapabilities) ||
		!controlmodel.RuntimeSecurityMatches(binding.Kind, instance.Labels, candidate.SecurityConstraints) {
		return false
	}
	for _, capability := range decodeCapabilities(instance.Capabilities) {
		if capability == "conversation-inbound" || capability == "inbound-message" {
			return true
		}
	}
	return false
}

func (s *Server) resolveAgentConversation(ctx context.Context, agent *controlmodel.Agent, requestedSessionID,
	originType, originRef string) (*store.Session, error) {
	policy, err := s.store.Orchestration().GetRuntimePolicy(ctx, agent.Tenant, agent.Namespace, agent.ID.String())
	if err != nil || policy.SelectionMode != "ordered" || len(policy.Candidates) == 0 {
		return nil, fmt.Errorf("Agent has no runtime policy")
	}
	if requestedSessionID == "" {
		requestedSessionID = uuid.NewString()
	}
	for _, candidate := range policy.Candidates {
		binding, bindingErr := s.store.AgentCatalog().GetBinding(ctx, candidate.Binding.BindingID)
		if bindingErr != nil || binding.AgentID != agent.ID || !binding.Enabled || binding.ArchivedAt != nil || binding.Kind != candidate.Binding.Kind {
			continue
		}
		now := time.Now().UTC()
		sessionID, instanceRef := requestedSessionID, ""
		var instanceID uuid.UUID
		var generation int64
		switch binding.Kind {
		case controlmodel.DataPlaneManaged:
			if s.product == nil || !controlmodel.RuntimeSecurityMatches(binding.Kind, nil, candidate.SecurityConstraints) {
				continue
			}
			var cfg controlmodel.ManagedBindingConfiguration
			if json.Unmarshal(binding.Configuration, &cfg) != nil {
				continue
			}
			sessionID, err = s.product.FindOrCreateSessionID(ctx, cfg.OwnerRef, cfg.ManagedDefinitionRef, "",
				originType+"|"+originRef+"|"+requestedSessionID)
			if err != nil {
				return nil, err
			}
		case controlmodel.DataPlaneExternalApplication:
			instances, listErr := s.store.RuntimeRegistry().ListAgentInstances(ctx, agent.Tenant, agent.Namespace, agent.ID)
			if listErr != nil {
				return nil, listErr
			}
			for _, instance := range instances {
				if externalConversationCandidate(instance, binding, candidate) {
					instanceID, instanceRef, generation = instance.ID, instance.InstanceKey, instance.Generation
					break
				}
			}
			if instanceID == uuid.Nil {
				continue
			}
		case controlmodel.DataPlaneHostedRuntime:
			if !s.hostedConversationCandidate(ctx, agent, candidate) {
				continue
			}
			// Hosted providers create their native session lazily on the first
			// turn. The control-plane session ID remains stable across attempts.
			generation = 0
		default:
			continue
		}
		phase := store.SessionPhaseActive
		if binding.Kind == controlmodel.DataPlaneHostedRuntime {
			phase = store.SessionPhaseIdle
			if existing, listErr := s.store.Sessions().List(ctx, store.SessionFilter{Tenant: agent.Tenant,
				Namespace: agent.Namespace, AgentID: agent.ID, SessionID: sessionID, Limit: 1}); listErr == nil && len(existing) > 0 {
				phase = existing[0].Phase
			}
		}
		payload, _ := json.Marshal(gin.H{"originType": originType, "originRef": originRef})
		return s.store.Sessions().Upsert(ctx, &store.Session{Tenant: agent.Tenant, Namespace: agent.Namespace,
			AgentID: agent.ID, BindingID: binding.ID, AgentInstanceID: instanceID, InstanceGeneration: generation,
			AgentName: agent.AgentKey, SessionID: sessionID, InstanceRef: instanceRef, OriginType: originType,
			OriginRef: originRef, Framework: string(binding.Kind), Phase: phase,
			TaskContext: payload, StartedAt: &now, LastActiveAt: &now})
	}
	return nil, fmt.Errorf("no conversation-capable runtime candidate is available")
}

func (s *Server) sendAgentConversationTurn(ctx context.Context, session *store.Session, message,
	sourceType, sourceRef string) error {
	binding, err := s.store.AgentCatalog().GetBinding(ctx, session.BindingID)
	if err != nil || !binding.Enabled || binding.ArchivedAt != nil || binding.AgentID != session.AgentID {
		return fmt.Errorf("conversation Binding is unavailable")
	}
	switch binding.Kind {
	case controlmodel.DataPlaneManaged:
		if s.product == nil {
			return fmt.Errorf("Managed runtime is unavailable")
		}
		var cfg controlmodel.ManagedBindingConfiguration
		if json.Unmarshal(binding.Configuration, &cfg) != nil {
			return fmt.Errorf("Managed binding configuration is invalid")
		}
		key := session.Tenant + "\x00" + session.Namespace + "\x00" + session.AgentID.String() + "\x00" + session.SessionID
		return s.store.WithSessionLock(ctx, key, func(ctx context.Context) error {
			if err := conversation.CheckChatActive(ctx, s.store, session); err != nil {
				return err
			}
			return s.product.PostSessionWakeEvent(ctx, session.SessionID, cfg.OwnerRef, message)
		})
	case controlmodel.DataPlaneExternalApplication:
		instance, instanceErr := s.store.RuntimeRegistry().GetAgentInstance(ctx, session.AgentInstanceID)
		if instanceErr != nil || instance.BindingID != binding.ID || instance.Generation != session.InstanceGeneration || instance.Health != controlmodel.RuntimeHealthHealthy {
			return fmt.Errorf("conversation AgentInstance is unavailable")
		}
		sender, ok := s.asdpCommands.(ConversationTurnSender)
		if !ok {
			return fmt.Errorf("External conversation transport is unavailable")
		}
		turn, err := conversation.Begin(ctx, s.store, session, message, time.Now().UTC())
		if err != nil {
			return err
		}
		input, _ := json.Marshal(gin.H{"message": message})
		err = sender.SendConversationTurn(session.Tenant, session.Namespace, instance.InstanceKey, &asdp.ConversationTurnCommand{
			InvocationId: turn.InvocationID.String(), ConversationId: turn.ConversationID.String(), TurnId: turn.ID.String(), SessionId: session.SessionID,
			AgentId: session.AgentID.String(), BindingId: session.BindingID.String(), InstanceId: instance.ID.String(),
			Generation: session.InstanceGeneration, Input: input, Deadline: turn.Deadline.UnixMilli(),
			CorrelationId: turn.InvocationID.String(),
		})
		if err != nil {
			if failErr := conversation.Fail(ctx, s.store, session, turn.ID, "conversation_dispatch_failed", err.Error(), time.Now().UTC()); failErr != nil {
				return errors.Join(err, failErr)
			}
		}
		return err
	case controlmodel.DataPlaneHostedRuntime:
		_, err = s.dispatchHostedConversationTurn(ctx, session, binding, message, uuid.NewString(),
			sourceType, sourceRef)
		return err
	default:
		return fmt.Errorf("runtime binding %q does not support conversations", binding.Kind)
	}
}

func writeConversationTurnError(c *gin.Context, err error) {
	if errors.Is(err, store.ErrConflict) {
		c.JSON(http.StatusConflict, ErrorResponse{Error: err.Error(), Code: "conversation_turn_conflict"})
		return
	}
	c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: err.Error(), Code: "conversation_unavailable"})
}

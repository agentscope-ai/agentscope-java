// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//     http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package httpapi

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	serviceapi "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/invocation"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/product"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/sessionapi"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

func managedTriggerSessionID(agent *model.Agent, request product.ManagedTrigger) uuid.UUID {
	conversation := request.ExternalKey
	if conversation == "" {
		conversation = "request:" + request.RequestID
	}
	identity, _ := json.Marshal([]string{"managed-trigger", agent.Tenant, agent.Namespace, request.OwnerID, request.ActorID, agent.ID.String(), request.EnvironmentID, conversation})
	return uuid.NewSHA1(uuid.NameSpaceURL, identity)
}

// Trusted Channel/Cron adapters share native resource registration and Turn admission.
// They never create a cp legacy Session or contact an executing HarnessRuntime instance.
func (s *Server) handleManagedTrigger(ctx context.Context, request product.ManagedTrigger) (json.RawMessage, error) {
	id, err := uuid.Parse(request.AgentID)
	if err != nil {
		return nil, err
	}
	agent, err := s.store.AgentCatalog().GetAgent(ctx, id)
	if err != nil {
		return nil, err
	}
	if agent.OwnerRef != request.OwnerID || agent.Status != model.AgentActive || agent.ArchivedAt != nil {
		return nil, store.ErrForbidden
	}
	if request.ActorID == "" {
		namespace, e := s.store.Access().GetNamespace(ctx, agent.Tenant, agent.Namespace)
		if e != nil {
			return nil, e
		}
		request.ActorID = namespace.Owner
	}
	if request.ActorID == "" {
		return nil, store.ErrForbidden
	}
	sessionID := managedTriggerSessionID(agent, request)
	if request.SessionID != "" {
		sessionID, err = uuid.Parse(request.SessionID)
		if err != nil {
			return nil, err
		}
	}
	var session *sessionapi.Session
	err = s.store.WithSessionLock(ctx, "public-session:"+sessionID.String(), func(ctx context.Context) error {
		existing, e := sessionapi.Get(ctx, s.store, agent.Tenant, sessionID)
		if e == nil {
			if existing.Namespace != agent.Namespace || existing.Target.ID != id || existing.Principal != "platform-user:"+request.ActorID || existing.Status != "active" {
				return store.ErrForbidden
			}
			if request.Operation != "events" && request.AgentVersion > 0 && existing.Target.Version != request.AgentVersion {
				return store.ErrConflict
			}
			if request.BindingID != "" {
				contract, e := serviceapi.ReadContract(existing.Contract)
				if e != nil {
					return e
				}
				policy := contract.Policies[id.String()]
				if policy == nil || len(policy.Candidates) != 1 || policy.Candidates[0].Binding.BindingID.String() != request.BindingID {
					return store.ErrConflict
				}
			}
			session = existing
			return nil
		}
		if !errors.Is(e, store.ErrNotFound) || request.Operation == "events" || request.SessionID != "" && request.Operation != "provision" {
			return e
		}
		ep := &model.Endpoint{ID: sessionID, Tenant: agent.Tenant, Namespace: agent.Namespace, TargetType: model.EndpointTargetAgent, TargetRef: id, InvocationMode: model.EndpointConversationMode, Status: model.EndpointPublished, MaxPayloadBytes: 16 << 20}
		raw, e := s.freezeServiceContract(ctx, ep)
		if e != nil {
			return e
		}
		contract, e := serviceapi.ReadContract(raw)
		if e != nil {
			return e
		}
		if request.BindingID != "" {
			selected, e := uuid.Parse(request.BindingID)
			if e != nil {
				return e
			}
			policy := contract.Policies[id.String()]
			if policy == nil {
				return store.ErrNotFound
			}
			candidates := []model.RuntimeBindingCandidate{}
			for _, candidate := range policy.Candidates {
				if candidate.Binding.BindingID == selected && candidate.Binding.Kind == model.DataPlaneManaged {
					candidates = append(candidates, candidate)
				}
			}
			if len(candidates) != 1 {
				return store.ErrForbidden
			}
			fixed := *policy
			fixed.Candidates = candidates
			contract.Policies[id.String()] = &fixed
		}
		if !s.publicSessionIsManaged(contract) {
			return fmt.Errorf("trigger requires a Managed Agent runtime policy")
		}
		if request.AgentVersion > 0 {
			snapshot, e := s.product.RuntimeDefinitionVersion(ctx, request.OwnerID, id.String(), request.AgentVersion)
			if e != nil {
				return e
			}
			snapshot["version"] = request.AgentVersion
			contract.Definitions[id.String()], e = json.Marshal(snapshot)
			if e != nil {
				return e
			}
		}
		var definition map[string]json.RawMessage
		if e = json.Unmarshal(contract.Definitions[id.String()], &definition); e != nil {
			return e
		}
		contract.PublicSessionID, contract.EnvironmentID = sessionID, request.EnvironmentID
		if contract.EnvironmentID == "" {
			_ = json.Unmarshal(definition["defaultEnvironmentId"], &contract.EnvironmentID)
		}
		if e = s.freezeManagedEnvironment(ctx, contract, id); e != nil {
			return e
		}
		options := map[string]any{"environmentId": contract.EnvironmentID}
		for _, pair := range [][2]string{{"memoryStoreIds", "defaultMemoryStoreIds"}, {"vaultIds", "defaultVaultIds"}} {
			value := definition[pair[1]]
			if len(value) == 0 || string(value) == "null" {
				value = json.RawMessage(`[]`)
			}
			options[pair[0]] = value
		}
		contract.SessionOptions, e = json.Marshal(options)
		if e != nil {
			return e
		}
		raw, e = json.Marshal(contract)
		if e != nil {
			return e
		}
		now := time.Now().UTC()
		session = &sessionapi.Session{ID: sessionID, Tenant: agent.Tenant, Namespace: agent.Namespace, Target: sessionapi.Target{Type: "agent", ID: id, Version: serviceapi.ContextVersion(serviceapi.WithContract(ctx, contract), id.String())}, Principal: "platform-user:" + request.ActorID, Contract: raw, Status: "active", CreatedAt: now, UpdatedAt: now}
		return sessionapi.Save(ctx, s.store, session, true)
	})
	if err != nil {
		return nil, err
	}
	contract, err := serviceapi.ReadContract(session.Contract)
	if err != nil {
		return nil, err
	}
	// Hash the structured provider coordinate; raw IDs can contain delimiters or exceed key bounds.
	coordinate, _ := json.Marshal([]string{request.OwnerID, id.String(), request.ExternalKey, request.RequestID})
	sum := sha256.Sum256(coordinate)
	admissionContext := ctx
	if caller, ok := ctx.(*gin.Context); ok {
		admissionContext = caller.Request.Context()
	}
	origin, err := s.ensureNativePublicSession(admissionContext, session, contract, request.OwnerID, "trigger:"+hex.EncodeToString(sum[:]))
	if err != nil {
		return nil, err
	}
	if request.Operation == "provision" {
		return json.Marshal(map[string]any{"sessionId": session.ID})
	}
	if request.Operation == "events" {
		origin.TurnID = &request.TurnID
		turn, e := s.product.ManagedRuntimeRequest(ctx, request.OwnerID, product.ManagedRuntimeCall{Context: origin, Operation: "turn"})
		if e != nil {
			return nil, e
		}
		// Read Turn first: terminal status implies all corresponding public events were committed.
		page, e := s.product.ManagedRuntimeRequest(ctx, request.OwnerID, product.ManagedRuntimeCall{Context: origin, Operation: "events", After: request.After, Limit: 100})
		if e != nil {
			return nil, e
		}
		return json.Marshal(map[string]any{"sessionId": session.ID, "turn": json.RawMessage(turn), "events": json.RawMessage(page)})
	}
	turn, err := s.product.ManagedRuntimeRequest(ctx, request.OwnerID, product.ManagedRuntimeCall{Context: origin, Operation: "submit_turn", Payload: map[string]any{"input": map[string]any{"message": request.Text}}})
	if err != nil {
		return nil, err
	}
	return json.Marshal(map[string]any{"sessionId": session.ID, "turn": json.RawMessage(turn)})
}

// Resolve once at admission so later default edits cannot move an existing Session.
func (s *Server) freezeManagedEnvironment(ctx context.Context, contract *serviceapi.Contract, agentID uuid.UUID) error {
	if s.product == nil {
		return fmt.Errorf("Managed resource directory is unavailable")
	}
	if contract.EnvironmentID == "" {
		var definition struct {
			EnvironmentID string `json:"defaultEnvironmentId"`
		}
		if err := json.Unmarshal(contract.Definitions[agentID.String()], &definition); err != nil {
			return err
		}
		contract.EnvironmentID = definition.EnvironmentID
	}
	agent, err := s.store.AgentCatalog().GetAgent(ctx, agentID)
	if err != nil {
		return err
	}
	selected, err := s.product.ResolveManagedEnvironment(ctx, agent.OwnerRef, agentID.String(), contract.EnvironmentID)
	if err != nil {
		return err
	}
	contract.EnvironmentID = selected
	return nil
}

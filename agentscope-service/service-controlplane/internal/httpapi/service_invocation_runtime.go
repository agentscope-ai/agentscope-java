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
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/product"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// Actor credentials remain the original key hash; the resource owner is only transport binding.
func (s *Server) nativeRuntimeActor(ctx context.Context, ep *model.Endpoint, inv *model.EndpointInvocation, owner string, actor model.Actor) (map[string]any, error) {
	if s.localDev && actor.Ref == "" {
		actor = model.Actor{Type: model.ActorHuman, Ref: "local-developer"}
	}
	if actor.Type == model.ActorHuman && actor.Ref != "" {
		return map[string]any{"user": actor.Ref, "key_hash": "", "internal": false}, nil
	}
	if actor.Type != model.ActorAutomation || inv.ApplicationID == nil {
		return nil, fmt.Errorf("original Managed runtime Actor is required")
	}
	var hash string
	if strings.HasPrefix(actor.Ref, "session-key:") {
		row, err := s.store.KV().Get(ctx, ep.Tenant, applicationCredentialPath(*inv.ApplicationID), strings.TrimPrefix(actor.Ref, "session-key:"))
		if err != nil {
			return nil, err
		}
		var key sessionCredential
		if json.Unmarshal(row.Value, &key) != nil || key.ApplicationID != *inv.ApplicationID || key.Status != "active" || key.ExpiresAt != nil && !key.ExpiresAt.After(time.Now()) {
			return nil, store.ErrForbidden
		}
		hash = key.Hash
	} else if strings.HasPrefix(actor.Ref, "api-key:") {
		keys, err := s.store.Endpoints().ListCredentials(ctx, ep.ID)
		if err != nil {
			return nil, err
		}
		for _, key := range keys {
			if actor.Ref == "api-key:"+key.ID.String() && key.ApplicationID == *inv.ApplicationID && key.Status == model.EndpointCredentialActive && (key.ExpiresAt == nil || key.ExpiresAt.After(time.Now())) {
				hash = hex.EncodeToString(key.SecretHash)
				break
			}
		}
	}
	if hash == "" {
		return nil, store.ErrForbidden
	}
	return map[string]any{"user": owner, "key_hash": hash, "internal": false, "credential_ref": actor.Ref}, nil
}

func (s *Server) ensureNativeRuntimeSession(ctx context.Context, ep *model.Endpoint, inv *model.EndpointInvocation, owner string) (runtimeprotocol.Context, error) {
	frozen, err := serviceapi.ReadContract(inv.Contract)
	if err != nil {
		return runtimeprotocol.Context{}, err
	}
	if inv.ConversationID != nil && frozen.PublicSessionID == uuid.Nil {
		conversation, e := s.store.Endpoints().GetConversation(ctx, *inv.ConversationID)
		if e != nil {
			return runtimeprotocol.Context{}, e
		}
		policy := frozen.Policies[ep.TargetRef.String()]
		if policy == nil {
			return runtimeprotocol.Context{}, store.ErrNotFound
		}
		candidates := []model.RuntimeBindingCandidate{}
		for _, candidate := range policy.Candidates {
			if candidate.Binding.BindingID == conversation.BindingID && candidate.Binding.Kind == model.DataPlaneManaged {
				candidates = append(candidates, candidate)
			}
		}
		if len(candidates) != 1 {
			return runtimeprotocol.Context{}, store.ErrForbidden
		}
		selected := *policy
		selected.Candidates = candidates
		frozen.Policies[ep.TargetRef.String()] = &selected
	}
	id := frozen.PublicSessionID
	endpointOwned := id == uuid.Nil
	if id == uuid.Nil {
		if inv.ConversationID == nil {
			return runtimeprotocol.Context{}, fmt.Errorf("Managed native Session requires its conversation identity")
		}
		id = *inv.ConversationID
	}
	actor, err := s.nativeRuntimeActor(ctx, ep, inv, owner, inv.Actor)
	if err != nil {
		return runtimeprotocol.Context{}, err
	}
	origin := runtimeprotocol.Context{Version: runtimeprotocol.Version, Tenant: ep.Tenant, Namespace: ep.Namespace, SessionID: id.String(), CommandID: inv.ID.String(), TraceID: inv.ID.String(), Actor: actor, PermissionExpiresAt: time.Now().Add(time.Minute).UnixMilli()}
	if inv.ApplicationID != nil {
		value := inv.ApplicationID.String()
		origin.ApplicationID = &value
	}
	_, err = s.product.ManagedRuntimeRequest(ctx, owner, product.ManagedRuntimeCall{Context: origin, Operation: "session"})
	if err == nil {
		return origin, nil
	}
	var missing *product.ManagedServiceError
	if !errors.As(err, &missing) || missing.StatusCode != 404 {
		return runtimeprotocol.Context{}, err
	}
	// An Endpoint conversation gets the same resource registry and policy refresh as native Sessions.
	if _, err = sessionapi.Get(ctx, s.store, ep.Tenant, id); errors.Is(err, store.ErrNotFound) {
		frozen.PublicSessionID = id
		frozen.PortalCommandID = ""
		if err = s.freezeManagedEnvironment(ctx, frozen, ep.TargetRef); err != nil {
			return runtimeprotocol.Context{}, err
		}
		raw, marshalErr := json.Marshal(frozen)
		if marshalErr != nil {
			return runtimeprotocol.Context{}, marshalErr
		}
		now := time.Now().UTC()
		v := &sessionapi.Session{ID: id, Tenant: ep.Tenant, Namespace: ep.Namespace, Target: sessionapi.Target{Type: "agent", ID: ep.TargetRef, Version: serviceapi.ContextVersion(serviceapi.WithContract(ctx, frozen), ep.TargetRef.String())}, Principal: inv.PrincipalRef, ApplicationID: inv.ApplicationID, Contract: raw, Status: "active", CreatedAt: now, UpdatedAt: now, RuntimeOwner: "dataplane"}
		if endpointOwned {
			endpoint := ep.ID
			v.SourceEndpointID = &endpoint
		}
		if err = sessionapi.Save(ctx, s.store, v, true); err != nil && !errors.Is(err, store.ErrConflict) {
			return runtimeprotocol.Context{}, err
		}
	} else if err != nil {
		return runtimeprotocol.Context{}, err
	}
	registered, err := sessionapi.Get(ctx, s.store, ep.Tenant, id)
	if err != nil {
		return runtimeprotocol.Context{}, err
	}
	if registered.Namespace != ep.Namespace || registered.Target.Type != "agent" || registered.Target.ID != ep.TargetRef || (registered.ApplicationID == nil) != (inv.ApplicationID == nil) || registered.ApplicationID != nil && *registered.ApplicationID != *inv.ApplicationID || endpointOwned && (registered.SourceEndpointID == nil || *registered.SourceEndpointID != ep.ID) {
		return runtimeprotocol.Context{}, store.ErrForbidden
	}
	if err = s.provisionNativeRuntimeSession(ctx, owner, origin); err != nil {
		return runtimeprotocol.Context{}, err
	}
	return origin, nil
}

// Resource registration is independent of Brain execution and never creates a Turn.
func (s *Server) provisionNativeRuntimeSession(ctx context.Context, owner string, origin runtimeprotocol.Context) error {
	id, err := uuid.Parse(origin.SessionID)
	if err != nil {
		return err
	}
	projection, err := s.runtimeSessionProjection(ctx, origin.Tenant, id, true)
	if err != nil {
		return err
	}
	configuration, ok := projection["resolved"].(gin.H)
	if !ok {
		return fmt.Errorf("native Managed configuration is unavailable")
	}
	policy, ok := projection["policy"].(gin.H)
	if !ok {
		return fmt.Errorf("native Managed policy is unavailable")
	}
	revision, err := runtimeprotocol.Digest(configuration)
	if err != nil {
		return err
	}
	raw, err := json.Marshal(configuration)
	if err != nil {
		return err
	}
	view := projection["view"].(map[string]any)
	var definition map[string]any
	encoded, err := json.Marshal(configuration["agentSnapshot"])
	if err != nil {
		return err
	}
	if err = json.Unmarshal(encoded, &definition); err != nil {
		return err
	}
	var selected map[string]any
	encoded, err = json.Marshal(configuration["session"])
	if err != nil {
		return err
	}
	if err = json.Unmarshal(encoded, &selected); err != nil {
		return err
	}
	for _, field := range []string{"agentId", "agentVersion", "environmentId", "memoryStoreIds", "vaultIds"} {
		if value, found := selected[field]; found {
			view[field] = value
		}
	}
	modelName, _ := definition["model"].(string)
	agentName, _ := definition["name"].(string)
	if model, ok := definition["model"].(map[string]any); ok {
		modelName, _ = model["id"].(string)
	}
	view["sessionAgentReference"] = map[string]any{"id": view["agentId"], "version": view["agentVersion"], "name": agentName, "model": modelName}
	admission := runtimeprotocol.RuntimeSessionAdmission{Bootstrap: runtimeprotocol.RuntimeSessionBootstrap{Context: origin, RuntimeKind: "managed", ConfigurationRevision: revision, Configuration: raw}, Policy: policy, PublicView: view}
	admission.Bootstrap.Context.CommandID = "register:" + id.String()
	if _, err = s.product.ManagedRuntimeProvision(ctx, owner, admission); err != nil {
		return err
	}
	return nil
}

func (s *Server) ensureNativePublicSession(ctx context.Context, session *sessionapi.Session, contract *serviceapi.Contract, owner, key string) (runtimeprotocol.Context, error) {
	origin, err := s.nativePublicSessionOrigin(ctx, session, contract, owner, key)
	if err != nil {
		return runtimeprotocol.Context{}, err
	}
	if _, err = s.product.ManagedRuntimeRequest(ctx, owner, product.ManagedRuntimeCall{Context: origin, Operation: "session"}); err == nil {
		return origin, nil
	}
	var missing *product.ManagedServiceError
	if !errors.As(err, &missing) || missing.StatusCode != 404 {
		return runtimeprotocol.Context{}, err
	}
	if err = s.provisionNativeRuntimeSession(ctx, owner, origin); err != nil {
		return runtimeprotocol.Context{}, err
	}
	return origin, nil
}

func (s *Server) nativeRuntimeRequest(ctx context.Context, turn serviceNativeTurn, call product.ManagedRuntimeCall) (json.RawMessage, error) {
	call.Context = turn.Context
	call.Context.TurnID = &turn.ID
	call.Context.CommandID = "read:" + turn.ID
	call.Context.PermissionExpiresAt = time.Now().Add(time.Minute).UnixMilli()
	return s.product.ManagedRuntimeRequest(ctx, turn.Owner, call)
}

func (s *Server) nativePublicSessionOrigin(ctx context.Context, session *sessionapi.Session, contract *serviceapi.Contract, owner, key string) (runtimeprotocol.Context, error) {
	var actor model.Actor
	if request, ok := ctx.(*gin.Context); ok {
		actor = endpointActor(request)
	}
	if actor.Ref == "" {
		switch {
		case strings.HasPrefix(session.Principal, "platform-user:"):
			actor = model.Actor{Type: model.ActorHuman, Ref: strings.TrimPrefix(session.Principal, "platform-user:")}
		case strings.HasPrefix(session.Principal, "session-key:"), strings.HasPrefix(session.Principal, "api-key:"):
			actor = model.Actor{Type: model.ActorAutomation, Ref: session.Principal}
		}
	}
	endpoint := contract.Endpoint
	if session.SourceEndpointID != nil {
		endpoint.ID = *session.SourceEndpointID
	}
	caller, err := s.nativeRuntimeActor(ctx, &endpoint, &model.EndpointInvocation{ApplicationID: session.ApplicationID}, owner, actor)
	if err != nil {
		return runtimeprotocol.Context{}, err
	}
	if key == "" {
		key = "read:" + session.ID.String()
	}
	origin := runtimeprotocol.Context{Version: runtimeprotocol.Version, Tenant: session.Tenant, Namespace: session.Namespace, SessionID: session.ID.String(), CommandID: key, TraceID: key, Actor: caller, PermissionExpiresAt: time.Now().Add(time.Minute).UnixMilli()}
	if session.ApplicationID != nil {
		app := session.ApplicationID.String()
		origin.ApplicationID = &app
	}
	return origin, nil
}

func (s *Server) nativeRuntimeInput(ctx context.Context, ep *model.Endpoint, inv *model.EndpointInvocation, turn serviceNativeTurn, actor model.Actor, key, kind, action string, payload any) (json.RawMessage, error) {
	caller, err := s.nativeRuntimeActor(ctx, ep, inv, turn.Owner, actor)
	if err != nil {
		return nil, err
	}
	origin := turn.Context
	origin.Actor, origin.CommandID, origin.TurnID = caller, key, &turn.ID
	origin.PermissionExpiresAt = time.Now().Add(time.Minute).UnixMilli()
	body := map[string]any{"type": kind, "payload": payload}
	if action != "" {
		body["action_id"] = action
	}
	return s.product.ManagedRuntimeRequest(ctx, turn.Owner, product.ManagedRuntimeCall{Context: origin, Operation: "submit_input", Payload: body})
}

func updateNativeCommandReceipt(cmd *serviceCommand, turnID string, raw json.RawMessage) error {
	var input struct {
		ID       string `json:"id"`
		TurnID   string `json:"turn_id"`
		Status   string `json:"status"`
		Delivery string `json:"delivery_status"`
		Reason   string `json:"rejection_reason"`
	}
	if err := json.Unmarshal(raw, &input); err != nil {
		return err
	}
	if input.ID == "" || input.TurnID != turnID || cmd.InputID != "" && cmd.InputID != input.ID {
		return fmt.Errorf("native Input receipt differs from its command")
	}
	if input.Status != "accepted" && input.Status != "applied" && input.Status != "rejected" {
		return fmt.Errorf("invalid native Input receipt status")
	}
	if (cmd.InputStatus == "applied" || cmd.InputStatus == "rejected") && cmd.InputStatus != input.Status {
		return fmt.Errorf("native Input receipt moved past its terminal outcome")
	}
	cmd.InputID, cmd.InputStatus, cmd.InputDeliveryStatus = input.ID, input.Status, input.Delivery
	switch input.Status {
	case "accepted":
		cmd.Status = "submitted"
	case "applied":
		cmd.Status = "completed"
	case "rejected":
		cmd.Status, cmd.Error = "failed", input.Reason
	}
	return nil
}

func (s *Server) applyNativeServiceInput(ctx context.Context, ep *model.Endpoint, inv *model.EndpointInvocation, turn serviceNativeTurn, cmd *serviceCommand, kind, action string, payload any) error {
	raw, err := s.nativeRuntimeInput(ctx, ep, inv, turn, cmd.Actor, cmd.ID.String(), kind, action, payload)
	if err != nil {
		return err
	}
	return updateNativeCommandReceipt(cmd, turn.ID, raw)
}

func (s *Server) refreshNativeServiceCommand(ctx context.Context, ep *model.Endpoint, inv *model.EndpointInvocation, cmd *serviceCommand, reader model.Actor) error {
	var turn serviceNativeTurn
	var err error
	var action serviceActionInput
	if cmd.Kind == "actions" && json.Unmarshal(cmd.Payload, &action) == nil && strings.HasPrefix(action.RequestID, "native-task:") {
		var id uuid.UUID
		id, _, err = parseNativeTaskAction(action.RequestID)
		if err == nil {
			turn, _, _, err = s.nativeManagedTaskTurn(ctx, ep, inv, id)
		}
	} else {
		turn, err = s.nativeServiceTurn(ctx, ep, inv)
	}
	if err != nil {
		return err
	}
	actor, err := s.nativeRuntimeActor(ctx, ep, inv, turn.Owner, reader)
	if err != nil {
		return err
	}
	origin := turn.Context
	origin.Actor, origin.TurnID, origin.CommandID = actor, &turn.ID, "receipt:"+cmd.ID.String()
	origin.PermissionExpiresAt = time.Now().Add(time.Minute).UnixMilli()
	raw, err := s.product.ManagedRuntimeRequest(ctx, turn.Owner, product.ManagedRuntimeCall{Context: origin, Operation: "input", InputID: cmd.InputID})
	if err != nil {
		return err
	}
	return updateNativeCommandReceipt(cmd, turn.ID, raw)
}

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
	"errors"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

func (s *Server) dispatchServiceConversation(ctx context.Context, id uuid.UUID) error {
	return s.store.WithSessionLock(ctx, "service-invocation:"+id.String(), func(ctx context.Context) error {
		inv, err := s.store.Endpoints().GetInvocation(ctx, id)
		if err != nil {
			return err
		}
		if inv.Mode != model.EndpointConversationMode || (inv.Status != model.EndpointInvocationAccepted && inv.Status != model.EndpointInvocationDispatching) {
			return nil
		}
		ep, err := serviceapi.BoundEndpoint(ctx, s.store, inv)
		if err != nil {
			return err
		}
		if ep.TimeoutSeconds > 0 && time.Now().After(inv.CreatedAt.Add(time.Duration(ep.TimeoutSeconds)*time.Second)) {
			return s.expireServiceInvocation(ctx, inv)
		}
		if len(inv.Contract) > 0 {
			frozen, err := serviceapi.ReadContract(inv.Contract)
			if err != nil {
				return err
			}
			ctx = serviceapi.WithContract(ctx, frozen)
		}
		var input struct {
			Message string `json:"message"`
		}
		if err = json.Unmarshal(inv.Input, &input); err != nil {
			return err
		}
		var conv *model.EndpointConversation
		if inv.ConversationID == nil {
			cid := uuid.NewSHA1(id, []byte("conversation"))
			conv, err = s.store.Endpoints().GetConversation(ctx, cid)
			if errors.Is(err, store.ErrNotFound) {
				sess, resolveErr := s.resolveEndpointConversation(ctx, ep, id.String())
				if resolveErr != nil {
					return resolveErr
				}
				conv, err = s.store.Endpoints().CreateConversation(ctx, &model.EndpointConversation{ID: cid, EndpointID: ep.ID, AgentID: sess.AgentID, SessionID: sess.SessionID, BindingID: sess.BindingID, AgentInstanceID: sess.AgentInstanceID, InstanceGeneration: sess.InstanceGeneration, Status: model.EndpointConversationActive, PrincipalRef: inv.PrincipalRef, ReleaseID: inv.ReleaseID, Contract: inv.Contract})
				if errors.Is(err, store.ErrConflict) {
					conv, err = s.store.Endpoints().GetConversation(ctx, cid)
				}
			}
			if err != nil {
				return err
			}
			inv.ConversationID = &cid
		} else {
			conv, _, err = s.loadEndpointConversationSession(ctx, ep, *inv.ConversationID, inv.PrincipalRef)
			if err != nil {
				return err
			}
		}
		inv.SessionID = conv.SessionID
		if inv.TurnID == nil {
			turn := uuid.NewSHA1(id, []byte("turn"))
			inv.TurnID = &turn
		}
		if _, err = s.store.Endpoints().UpdateInvocation(ctx, inv); err != nil {
			return err
		}
		if err = s.sendEndpointConversationTurn(ctx, ep, conv, inv, input.Message); err != nil {
			return err
		}
		latest, err := s.store.Endpoints().GetInvocation(ctx, id)
		if err != nil {
			return err
		}
		if serviceapi.Terminal(latest.Status) {
			return nil
		}
		now := time.Now().UTC()
		latest.Status = model.EndpointInvocationRunning
		latest.StartedAt = &now
		_, err = s.store.Endpoints().UpdateInvocation(ctx, latest)
		return err
	})
}

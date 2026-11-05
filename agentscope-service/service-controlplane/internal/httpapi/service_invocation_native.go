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
	"fmt"
	"net/url"
	"strings"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

var errNativeActionNotPending = errors.New("native action is no longer pending")

type serviceNativeTurn struct {
	ID    string `json:"id"`
	Owner string `json:"owner"`
}

func (s *Server) nativeServiceTurn(ctx context.Context, ep *model.Endpoint, inv *model.EndpointInvocation) (serviceNativeTurn, error) {
	item, err := s.store.KV().Get(ctx, ep.Tenant, "service-api/native-turns", inv.ID.String())
	if err != nil {
		return serviceNativeTurn{}, err
	}
	var turn serviceNativeTurn
	err = json.Unmarshal(item.Value, &turn)
	return turn, err
}
func (s *Server) acceptNativeServiceTurn(ctx context.Context, ep *model.Endpoint, inv *model.EndpointInvocation, owner, message string) error {
	if s.product == nil {
		return fmt.Errorf("managed runtime unavailable")
	}
	var body any = map[string]any{"message": message}
	if frozen := serviceapi.ContextContract(ctx); frozen != nil && frozen.PublicSessionID != uuid.Nil {
		var err error
		body, err = s.nativePublicSessionInput(ctx, inv, owner)
		if err != nil {
			return err
		}
	}
	raw, err := s.product.ManagedServiceRequest(ctx, owner, inv.SessionID, "POST", "/turns", inv.ID.String(), body)
	if err != nil {
		return err
	}
	var turn serviceNativeTurn
	if err = json.Unmarshal(raw, &turn); err != nil {
		return err
	}
	if turn.ID == "" {
		return fmt.Errorf("managed runtime did not return a turn ID")
	}
	turn.Owner = owner
	raw, err = json.Marshal(turn)
	if err != nil {
		return err
	}
	_, err = s.store.KV().Put(ctx, ep.Tenant, "service-api/native-turns", inv.ID.String(), raw)
	return err
}
func (s *Server) nativeServiceSnapshot(ctx context.Context, inv *model.EndpointInvocation, turn serviceNativeTurn) (map[string]any, error) {
	raw, err := s.product.ManagedServiceRequest(ctx, turn.Owner, inv.SessionID, "GET", "/snapshot", "", nil)
	if err != nil {
		return nil, err
	}
	var snapshot map[string]any
	err = json.Unmarshal(raw, &snapshot)
	return snapshot, err
}
func (s *Server) refreshNativeServiceTurn(ctx context.Context, ep *model.Endpoint, inv *model.EndpointInvocation) (*model.EndpointInvocation, error) {
	turn, err := s.nativeServiceTurn(ctx, ep, inv)
	if err == store.ErrNotFound {
		return inv, nil
	}
	if err != nil {
		return nil, err
	}
	if s.product == nil {
		return inv, nil
	}
	raw, err := s.product.ManagedServiceRequest(ctx, turn.Owner, inv.SessionID, "GET", "/turns/"+url.PathEscape(turn.ID), "", nil)
	if err != nil {
		return nil, err
	}
	var native struct {
		Status string `json:"status"`
		Error  string `json:"errorCode"`
	}
	if err = json.Unmarshal(raw, &native); err != nil {
		return nil, err
	}
	old := inv.Status
	switch native.Status {
	case "queued":
		inv.Status = model.EndpointInvocationDispatching
	case "running":
		inv.Status = model.EndpointInvocationRunning
	case "requires_action", "interrupted":
		inv.Status = model.EndpointInvocationWaiting
		if old == model.EndpointInvocationCancelRequested && native.Status == "interrupted" {
			inv.Status = model.EndpointInvocationCancelled
		}
	case "cancel_requested":
		inv.Status = model.EndpointInvocationCancelRequested
	case "completed":
		snapshot, err := s.nativeServiceSnapshot(ctx, inv, turn)
		if err != nil {
			return nil, err
		}
		answer := nativeServiceTurnAnswer(snapshot, turn.ID)
		inv.Result, _ = json.Marshal(answer)
		inv.Status = model.EndpointInvocationCompleted
		serviceapi.CompleteResult(ep, inv, inv.Result)
	case "failed":
		inv.Status = model.EndpointInvocationFailed
		inv.ErrorCode = native.Error
	case "cancelled":
		inv.Status = model.EndpointInvocationCancelled
	}
	if old == model.EndpointInvocationCancelRequested && !serviceapi.Terminal(inv.Status) {
		inv.Status = old
	}
	if serviceapi.Terminal(inv.Status) {
		if inv.ErrorCode == "endpoint_timeout" {
			inv.Status = model.EndpointInvocationTimedOut
		}
		now := time.Now().UTC()
		inv.CompletedAt = &now
	}
	if old == inv.Status {
		return inv, nil
	}
	return s.store.Endpoints().UpdateInvocation(ctx, inv)
}

// Completed native message roles use the Core enum spelling (for example
// ASSISTANT). Prefer an explicit final output, even when that output is empty.
func nativeServiceTurnAnswer(snapshot map[string]any, turnID string) string {
	answer, hasFinal := "", false
	items, _ := snapshot["items"].([]any)
	for _, value := range items {
		event, _ := value.(map[string]any)
		data, _ := event["data"].(map[string]any)
		if data["turn_id"] != turnID {
			continue
		}
		item, _ := data["item"].(map[string]any)
		if !strings.EqualFold(firstPayloadString(item, "role"), "assistant") {
			continue
		}
		var text strings.Builder
		parts, _ := item["content"].([]any)
		for _, part := range parts {
			block, _ := part.(map[string]any)
			if block["type"] == "text" {
				text.WriteString(firstPayloadString(block, "text"))
			}
		}
		final, _ := data["final_output"].(bool)
		if final || !hasFinal && text.Len() > 0 {
			answer = text.String()
			hasFinal = final
		}
	}
	return answer
}

func (s *Server) nativeServiceAction(ctx context.Context, ep *model.Endpoint, inv *model.EndpointInvocation, cmd *serviceCommand, in serviceActionInput, apply bool) error {
	turn, err := s.nativeServiceTurn(ctx, ep, inv)
	if err != nil {
		return err
	}
	if apply {
		// The native inbox returns a previous command before checking pending state.
		// Retrying its stable key is safe after a lost response; a new stale answer is rejected.
		if err = s.nativeServiceAction(ctx, ep, inv, cmd, in, false); err != nil && !errors.Is(err, errNativeActionNotPending) {
			return err
		}
		var answer map[string]any
		if err = json.Unmarshal(in.Payload, &answer); err != nil || answer == nil {
			return fmt.Errorf("native action payload must be an object")
		}
		answer["request_id"] = strings.TrimPrefix(in.RequestID, "native:")
		_, err = s.product.ManagedServiceRequest(ctx, turn.Owner, inv.SessionID, "POST", "/turns/"+url.PathEscape(turn.ID)+"/actions", cmd.ID.String(), map[string]any{"answers": []any{answer}})
		return err
	}
	snapshot, err := s.nativeServiceSnapshot(ctx, inv, turn)
	if err != nil {
		return err
	}
	actions, _ := snapshot["required_actions"].([]any)
	for _, v := range actions {
		event, _ := v.(map[string]any)
		data, _ := event["data"].(map[string]any)
		if data["turn_id"] != turn.ID || data["request_id"] != strings.TrimPrefix(in.RequestID, "native:") {
			continue
		}
		if data["kind"] == "confirmation" && !s.nativeConfirmationAllowed(ctx, ep, inv, turn.Owner, cmd.Principal, data) {
			return fmt.Errorf("tool confirmation requires the designated human or an approver explicitly delegated by the Managed Agent owner")
		}
		return nil
	}
	return errNativeActionNotPending
}

func (s *Server) projectNativeServiceEvents(ctx context.Context, ep *model.Endpoint, inv *model.EndpointInvocation, j serviceapi.Journal, turn serviceNativeTurn) error {
	cursor := ""
	path := "service-api/native-cursors"
	if item, err := s.store.KV().Get(ctx, ep.Tenant, path, inv.ID.String()); err == nil {
		if err = json.Unmarshal(item.Value, &cursor); err != nil {
			return err
		}
	} else if err != store.ErrNotFound {
		return err
	}
	for {
		raw, err := s.product.ManagedServiceRequest(ctx, turn.Owner, inv.SessionID, "GET", "/events?limit=100&after="+url.QueryEscape(cursor), "", nil)
		if err != nil {
			return err
		}
		var page struct {
			Data []struct {
				ID   string         `json:"id"`
				Type string         `json:"type"`
				Data map[string]any `json:"data"`
			} `json:"data"`
			Next string `json:"next_cursor"`
			More bool   `json:"has_more"`
		}
		if err = json.Unmarshal(raw, &page); err != nil {
			return err
		}
		for _, event := range page.Data {
			if event.Data["turn_id"] != turn.ID || !publicServiceRuntimeType(event.Type) {
				continue
			}
			data := map[string]any{"execution_id": inv.ID.String(), "agent_id": ep.TargetRef.String()}
			for k, v := range event.Data {
				switch k {
				case "source", "run_id", "turn_id", "execution_id", "agent_id":
					continue
				}
				data[k] = v
			}
			if id, ok := data["item_id"].(string); ok {
				data["item_id"] = inv.ID.String() + ":" + id
			}
			kind := event.Type
			if kind == "run.ended" {
				kind = "execution.ended"
			}
			if strings.HasPrefix(kind, "required_action.") {
				if id, ok := data["request_id"].(string); ok {
					data["request_id"] = "native:" + id
				}
			}
			if _, err = j.Append(ctx, "native:"+event.ID, kind, data); err != nil {
				return err
			}
		}
		if page.Next == "" || page.More && page.Next == cursor {
			return fmt.Errorf("managed event cursor did not advance")
		}
		cursor = page.Next
		raw, _ = json.Marshal(cursor)
		if _, err = s.store.KV().Put(ctx, ep.Tenant, path, inv.ID.String(), raw); err != nil {
			return err
		}
		if !page.More {
			return nil
		}
	}
}

func (s *Server) nativeConfirmationAllowed(ctx context.Context, ep *model.Endpoint, inv *model.EndpointInvocation, owner, principal string, request map[string]any) bool {
	if !strings.HasPrefix(principal, "platform-user:") {
		return false
	}
	user := strings.TrimPrefix(principal, "platform-user:")
	if designated, ok := request["approver_ref"].(string); ok && designated != "" {
		return designated == user || designated == principal
	}
	if user == owner {
		return true
	}
	if inv.ApplicationID == nil {
		return false
	}
	app, err := s.store.Applications().Get(ctx, *inv.ApplicationID)
	// Only the Managed Agent owner can delegate confirmation through an Application they own.
	return err == nil && app.Status == "active" && app.Tenant == ep.Tenant && app.Namespace == ep.Namespace && app.OwnerUserID == owner && app.Allows(user, "approve")
}

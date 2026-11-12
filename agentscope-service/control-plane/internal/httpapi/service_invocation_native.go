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
	"strings"
	"time"

	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	serviceapi "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/invocation"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/product"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

var errNativeActionNotPending = errors.New("native action is no longer pending")

type serviceNativeTurn struct {
	ID      string                  `json:"id"`
	Owner   string                  `json:"owner"`
	Context runtimeprotocol.Context `json:"context"`
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
	origin, err := s.ensureNativeRuntimeSession(ctx, ep, inv, owner)
	if err != nil {
		return err
	}
	var body any = map[string]any{"message": message}
	if frozen := serviceapi.ContextContract(ctx); frozen != nil && frozen.PublicSessionID != uuid.Nil {
		body = inv.Input
	}
	raw, err := s.product.ManagedRuntimeRequest(ctx, owner, product.ManagedRuntimeCall{Context: origin, Operation: "submit_turn", Payload: map[string]any{"input": body}})
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
	turn.Context = origin
	raw, err = json.Marshal(turn)
	if err != nil {
		return err
	}
	_, err = s.store.KV().Put(ctx, ep.Tenant, "service-api/native-turns", inv.ID.String(), raw)
	return err
}
func (s *Server) nativeServiceSnapshot(ctx context.Context, inv *model.EndpointInvocation, turn serviceNativeTurn) (map[string]any, error) {
	raw, err := s.nativeRuntimeRequest(ctx, turn, product.ManagedRuntimeCall{Operation: "snapshot", Limit: 256})
	if err != nil {
		return nil, err
	}
	var snapshot map[string]any
	if err = json.Unmarshal(raw, &snapshot); err != nil {
		return nil, err
	}
	resources, _ := snapshot["resources"].(map[string]any)
	for kind, value := range resources {
		page, _ := value.(map[string]any)
		items, _ := page["data"].([]any)
		for page["has_more"] == true {
			cursor, _ := page["next_cursor"].(string)
			if cursor == "" {
				return nil, fmt.Errorf("native snapshot resource cursor did not advance")
			}
			id, _ := snapshot["snapshot_id"].(string)
			raw, err = s.nativeRuntimeRequest(ctx, turn, product.ManagedRuntimeCall{Operation: "resources", ResourceKind: kind, SnapshotID: id, After: cursor, Limit: 256})
			if err != nil {
				return nil, err
			}
			if err = json.Unmarshal(raw, &page); err != nil {
				return nil, err
			}
			next, _ := page["data"].([]any)
			items = append(items, next...)
			if page["has_more"] == true && page["next_cursor"] == cursor {
				return nil, fmt.Errorf("native snapshot resource cursor did not advance")
			}
		}
		page["data"] = items
		page["has_more"] = false
		resources[kind] = page
	}
	return snapshot, nil
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
	raw, err := s.nativeRuntimeRequest(ctx, turn, product.ManagedRuntimeCall{Operation: "turn"})
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
	case "accepted", "preparing":
		inv.Status = model.EndpointInvocationDispatching
	case "running":
		inv.Status = model.EndpointInvocationRunning
	case "requires_action":
		inv.Status = model.EndpointInvocationWaiting
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
func nativeSnapshotItems(snapshot map[string]any, kind string) []any {
	resources, _ := snapshot["resources"].(map[string]any)
	page, _ := resources[kind].(map[string]any)
	data, _ := page["data"].([]any)
	return data
}

func nativeServiceTurnAnswer(snapshot map[string]any, turnID string) string {
	answer, hasFinal := "", false
	items := nativeSnapshotItems(snapshot, "items")
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
	return s.nativeActionOnTurn(ctx, ep, inv, turn, cmd, in, strings.TrimPrefix(in.RequestID, "native:"), apply)
}

func (s *Server) nativeActionOnTurn(ctx context.Context, ep *model.Endpoint, inv *model.EndpointInvocation, turn serviceNativeTurn, cmd *serviceCommand, in serviceActionInput, actionID string, apply bool) error {
	var err error
	if apply {
		// The native inbox returns a previous command before checking pending state.
		// Retrying its stable key is safe after a lost response; a new stale answer is rejected.
		if err = s.nativeActionOnTurn(ctx, ep, inv, turn, cmd, in, actionID, false); err != nil && !errors.Is(err, errNativeActionNotPending) {
			return err
		}
		var answer map[string]any
		if err = json.Unmarshal(in.Payload, &answer); err != nil || answer == nil {
			return fmt.Errorf("native action payload must be an object")
		}
		return s.applyNativeServiceInput(ctx, ep, inv, turn, cmd, "reply", actionID, answer)
	}
	snapshot, err := s.nativeServiceSnapshot(ctx, inv, turn)
	if err != nil {
		return err
	}
	actions := nativeSnapshotItems(snapshot, "required_actions")
	for _, v := range actions {
		event, _ := v.(map[string]any)
		data, _ := event["data"].(map[string]any)
		if data["turn_id"] != turn.ID || data["request_id"] != actionID {
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
	return s.projectNativeRuntimeEvents(ctx, ep.Tenant, j, turn, inv.ID.String(), ep.TargetRef.String(), inv.ID.String(), "native:")
}

// Each original Attempt has an independent durable cursor and public identity.
func (s *Server) projectNativeRuntimeEvents(ctx context.Context, tenant string, j serviceapi.Journal, turn serviceNativeTurn, executionID, agentID, cursorKey, actionPrefix string) error {
	cursor := ""
	path := "service-api/native-cursors"
	if item, err := s.store.KV().Get(ctx, tenant, path, cursorKey); err == nil {
		if err = json.Unmarshal(item.Value, &cursor); err != nil {
			return err
		}
	} else if err != store.ErrNotFound {
		return err
	}
	for {
		raw, err := s.nativeRuntimeRequest(ctx, turn, product.ManagedRuntimeCall{Operation: "events", Limit: 100, After: cursor})
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
			data := map[string]any{"execution_id": executionID, "agent_id": agentID}
			for k, v := range event.Data {
				switch k {
				case "source", "run_id", "turn_id", "execution_id", "agent_id":
					continue
				}
				data[k] = v
			}
			if id, ok := data["item_id"].(string); ok {
				data["item_id"] = executionID + ":" + id
			}
			kind := event.Type
			if kind == "run.ended" {
				kind = "execution.ended"
			}
			if strings.HasPrefix(kind, "required_action.") {
				if id, ok := data["request_id"].(string); ok {
					data["request_id"] = actionPrefix + id
				}
			}
			if _, err = j.Append(ctx, "native:"+turn.Context.SessionID+":"+event.ID, kind, data); err != nil {
				return err
			}
		}
		if page.Next == "" || page.More && page.Next == cursor {
			return fmt.Errorf("managed event cursor did not advance")
		}
		cursor = page.Next
		raw, _ = json.Marshal(cursor)
		if _, err = s.store.KV().Put(ctx, tenant, path, cursorKey, raw); err != nil {
			return err
		}
		if !page.More {
			return nil
		}
	}
}

func (s *Server) nativeConfirmationAllowed(ctx context.Context, ep *model.Endpoint, inv *model.EndpointInvocation, owner, principal string, request map[string]any) bool {
	if s.localDev {
		return true
	}
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

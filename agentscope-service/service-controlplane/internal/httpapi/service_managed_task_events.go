// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
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
	"fmt"
	"strings"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/product"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

func parseNativeTaskAction(request string) (uuid.UUID, string, error) {
	parts := strings.SplitN(request, ":", 3)
	if len(parts) != 3 || parts[0] != "native-task" || parts[2] == "" {
		return uuid.Nil, "", store.ErrNotFound
	}
	id, err := uuid.Parse(parts[1])
	return id, parts[2], err
}

// The public identifier never selects an arbitrary Session. Its Attempt must
// belong to this invocation's run tree and its private native Session marker.
func (s *Server) nativeManagedTaskTurn(ctx context.Context, ep *model.Endpoint, inv *model.EndpointInvocation, id uuid.UUID) (serviceNativeTurn, *model.ExecutionAttempt, *model.AgentTask, error) {
	var turn serviceNativeTurn
	if inv.RunID == nil || s.product == nil {
		return turn, nil, nil, store.ErrNotFound
	}
	attempt, err := s.store.ExecutionAttempts().Get(ctx, id)
	if err != nil {
		return turn, nil, nil, err
	}
	if attempt.BackendKind != model.DataPlaneManaged || attempt.Tenant != ep.Tenant || attempt.Namespace != ep.Namespace {
		return turn, nil, nil, store.ErrNotFound
	}
	tasks, err := s.serviceTasks(ctx, *inv.RunID)
	if err != nil {
		return turn, nil, nil, err
	}
	var task *model.AgentTask
	for _, candidate := range tasks {
		if candidate.ID == attempt.AgentTaskID {
			task = candidate
			break
		}
	}
	if task == nil {
		return turn, nil, nil, store.ErrNotFound
	}
	sid, err := uuid.Parse(attempt.SessionID)
	if err != nil {
		return turn, nil, nil, err
	}
	session, err := sessionapi.Get(ctx, s.store, attempt.Tenant, sid)
	if err != nil {
		return turn, nil, nil, err
	}
	if session.Namespace != attempt.Namespace || session.Target.ID != attempt.AgentID || session.TaskAttemptID == nil || *session.TaskAttemptID != id {
		return turn, nil, nil, store.ErrConflict
	}
	contract, err := serviceapi.ReadContract(session.Contract)
	if err != nil {
		return turn, nil, nil, err
	}
	origin, err := s.nativePublicSessionOrigin(ctx, session, contract, attempt.ManagedOwnerRef, "task-events:"+id.String())
	if err != nil {
		return turn, nil, nil, err
	}
	turn = serviceNativeTurn{ID: attempt.TurnID, Owner: attempt.ManagedOwnerRef, Context: origin}
	if turn.ID == "" {
		raw, e := s.product.ManagedRuntimeRequest(ctx, turn.Owner, product.ManagedRuntimeCall{Context: origin, Operation: "turns", Limit: 2})
		if e != nil {
			return turn, nil, nil, e
		}
		var page struct {
			Data []struct {
				ID string `json:"id"`
			} `json:"data"`
		}
		if e = json.Unmarshal(raw, &page); e != nil {
			return turn, nil, nil, e
		}
		if len(page.Data) == 0 {
			return turn, nil, nil, store.ErrNotFound
		}
		if len(page.Data) != 1 || page.Data[0].ID == "" {
			return turn, nil, nil, fmt.Errorf("invalid Managed Attempt Turn association")
		}
		turn.ID = page.Data[0].ID
	}
	return turn, attempt, task, nil
}

func (s *Server) projectNativeManagedTaskEvents(ctx context.Context, ep *model.Endpoint, inv *model.EndpointInvocation, j serviceapi.Journal, attempt *model.ExecutionAttempt) error {
	turn, _, task, err := s.nativeManagedTaskTurn(ctx, ep, inv, attempt.ID)
	if errors.Is(err, store.ErrNotFound) && attempt.TurnID == "" && attempt.StartedAt == nil {
		return nil
	}
	if err != nil {
		return err
	}
	return s.projectNativeRuntimeEvents(ctx, ep.Tenant, j, turn, attempt.ID.String(), task.AgentRef, inv.ID.String()+":"+attempt.ID.String(), "native-task:"+attempt.ID.String()+":")
}

func (s *Server) nativeManagedTaskAction(ctx context.Context, ep *model.Endpoint, inv *model.EndpointInvocation, cmd *serviceCommand, in serviceActionInput, apply bool) error {
	id, action, err := parseNativeTaskAction(in.RequestID)
	if err != nil {
		return err
	}
	turn, attempt, task, err := s.nativeManagedTaskTurn(ctx, ep, inv, id)
	if err != nil {
		return err
	}
	if task.CurrentAttemptID == nil || *task.CurrentAttemptID != id || task.Status == model.AgentTaskCancelled || attempt.State == model.ExecutionCancelRequested || attempt.State == model.ExecutionCancelled {
		return store.ErrConflict
	}
	if !apply && (model.IsAgentTaskTerminal(task.Status) || model.IsExecutionAttemptTerminal(attempt.State)) {
		return errNativeActionNotPending
	}
	return s.nativeActionOnTurn(ctx, ep, inv, turn, cmd, in, action, apply)
}

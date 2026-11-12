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
	"errors"

	"github.com/google/uuid"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/agentservice"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

func (cursor *agentServiceNativeCursor) trackHostedAction(fact runtimeprotocol.RuntimeFact) error {
	id, _ := fact.Payload["request_id"].(string)
	if id == "" {
		return store.ErrConflict
	}
	if fact.Type == "required_action.resolved" {
		delete(cursor.HostedActions, id)
		return nil
	}
	if cursor.HostedActions == nil {
		cursor.HostedActions = map[string]bool{}
	}
	if !cursor.HostedActions[id] && len(cursor.HostedActions) >= 8192 {
		return agentServiceObservationCapacityError("hosted_actions")
	}
	cursor.HostedActions[id] = true
	return nil
}

// Only called after the native/physical stop barrier. A received human decision
// without delivery ACK must not be presented as applied to the provider. Close
// these pending actions with cancellation, in finite batches before Turn end.
func (b *agentServiceNativeBackend) resolveStoppedHostedActions(ctx context.Context, turn agentservice.Turn, cursor *agentServiceNativeCursor, progress *agentservice.Progress) (bool, error) {
	limit := min(16, agentServiceObservationFactLimit-len(progress.Facts))
	for id := range cursor.HostedActions {
		if limit <= 0 {
			break
		}
		approval, err := b.server.store.Collaboration().GetApproval(ctx, uuid.MustParse(id))
		if err != nil {
			return false, err
		}
		if err = b.validateApprovalScope(ctx, approval, turn, false); err != nil {
			return false, err
		}
		if approval.Status == model.ApprovalPending {
			if _, err = b.server.store.Collaboration().DecideApproval(ctx, approval.ID, approval.Version, model.ApprovalCancelled,
				model.Actor{Type: model.ActorSystem, Ref: "portal-stopped-execution"}, nil); err != nil && !errors.Is(err, store.ErrConflict) {
				return false, err
			}
		}
		progress.Facts = append(progress.Facts, runtimeprotocol.RuntimeFact{EventID: "native:approval:" + id + ":stopped", Type: "required_action.resolved",
			Payload: map[string]any{"request_id": id, "kind": "confirmation", "resolution_status": "cancelled", "reason": "execution_stopped_before_delivery"}})
		delete(cursor.HostedActions, id)
		limit--
	}
	return len(cursor.HostedActions) == 0, nil
}

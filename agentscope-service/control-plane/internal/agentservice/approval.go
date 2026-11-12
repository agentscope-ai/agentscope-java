// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// https://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package agentservice

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
)

func approvalIntent(command runtimeprotocol.InteractionCommand) (*Interaction, string, error) {
	intent, target, err := interactionIntent(command, "approval")
	if err != nil {
		return nil, "", fmt.Errorf("%w: approval command", ErrInvalid)
	}
	answers, ok := command.Payload["answers"].([]any)
	if !ok || len(answers) == 0 || len(answers) > 100 {
		return nil, "", fmt.Errorf("%w: approval answers", ErrInvalid)
	}
	seen := map[string]bool{}
	for _, raw := range answers {
		answer, ok := raw.(map[string]any)
		id, _ := answer["request_id"].(string)
		_, boolean := answer["allow"].(bool)
		if !ok || answer["kind"] != "confirmation" || id == "" || seen[id] || !boolean || answer["child_session_id"] != nil {
			return nil, "", fmt.Errorf("%w: approval answer", ErrInvalid)
		}
		if _, err = uuid.Parse(id); err != nil {
			return nil, "", fmt.Errorf("%w: approval identity", ErrInvalid)
		}
		if reason := answer["reason"]; reason != nil {
			if text, ok := reason.(string); !ok || len(text) > 4096 {
				return nil, "", fmt.Errorf("%w: approval reason", ErrInvalid)
			}
		}
		seen[id] = true
	}
	if proof, err := runtimeprotocol.ParseAgentSignalProof(command.Context.Actor); err != nil || proof != nil {
		return nil, "", ErrPermission
	}
	user, _ := command.Context.Actor["user"].(string)
	key, _ := command.Context.Actor["key_hash"].(string)
	if user == "" || key != "" || command.Context.Actor["internal"] == true || command.Context.Actor["agent_task"] != nil {
		return nil, "", ErrPermission
	}
	return intent, target, err
}

func applyApproval(command runtimeprotocol.InteractionCommand, session *Session, row *Turn, now time.Time) error {
	if !SameScope(command.Context, session.Bootstrap.Context) || !SameScope(command.Context, row.Command.Context) ||
		row.Command.Context.TurnID == nil || *row.Command.Context.TurnID != *command.Context.TurnID {
		return ErrNotFound
	}
	if command.Context.PermissionExpiresAt <= now.UnixMilli() {
		return ErrPermission
	}
	generation, _ := cancellationGeneration(command)
	graphRunning := (session.Bootstrap.RuntimeKind == "team" || session.Bootstrap.RuntimeKind == "workflow") && row.Status == "running"
	if session.Lifecycle != "active" || row.StopRequested || (row.Status != "requires_action" && !graphRunning) || row.ControlCommandID != "" ||
		generation != row.Generation || command.Payload["target_activation_command_id"] != row.ActivationCommandID {
		return ErrConflict
	}
	if row.GrantUntil > 0 {
		row.ObservationCommandID, row.ObservationGeneration, row.ObservationFence = row.ActivationCommandID, row.Generation, row.Fence
	}
	row.Generation++
	row.ActivationCommandID = command.Context.CommandID
	row.Fence = uuid.NewString()
	row.GrantUntil = 0
	row.ControlCommandID, row.ControlAnswerIndex = command.Context.CommandID, 0
	row.Status, row.NextAttempt = "queued", now
	row.ClaimToken, row.ClaimUntil = "", time.Time{}
	return nil
}

func (r *Memory) AcceptApproval(_ context.Context, command runtimeprotocol.InteractionCommand, now time.Time) (*Interaction, error) {
	intent, target, err := approvalIntent(command)
	if err != nil {
		return nil, err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.init()
	if prior := r.interactions[command.Context.CommandID]; prior != nil {
		if prior.Digest != intent.Digest {
			return nil, ErrConflict
		}
		return copyJSON(prior), nil
	}
	session, row := r.sessions[command.Context.SessionID], r.turns[target]
	if session == nil || row == nil {
		return nil, ErrNotFound
	}
	if err = applyApproval(command, session, row, now); err != nil {
		return nil, err
	}
	intent.ActivationGeneration, intent.ActivationFence = row.Generation, row.Fence
	r.interactions[command.Context.CommandID] = copyJSON(intent)
	return copyJSON(intent), nil
}

func (r *Postgres) AcceptApproval(ctx context.Context, command runtimeprotocol.InteractionCommand, now time.Time) (*Interaction, error) {
	intent, target, err := approvalIntent(command)
	if err != nil {
		return nil, err
	}
	return r.acceptInteraction(ctx, command, now, intent, target, applyApproval)
}

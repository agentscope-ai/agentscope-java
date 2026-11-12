// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package agentservice

import (
	"encoding/json"
	"fmt"
	"time"

	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
)

func cancellationIntent(command runtimeprotocol.InteractionCommand) (*Interaction, string, error) {
	return interactionIntent(command, "cancel")
}

func interactionIntent(command runtimeprotocol.InteractionCommand, kind string) (*Interaction, string, error) {
	c := command.Context
	target, ok := command.Payload["target_command_id"].(string)
	activation, active := command.Payload["target_activation_command_id"].(string)
	_, generation := cancellationGeneration(command)
	if c.Validate() != nil || command.Kind != kind || c.TurnID == nil || target != *c.TurnID || !ok || !active || activation == "" || !generation {
		return nil, "", fmt.Errorf("%w: interaction payload", ErrInvalid)
	}
	digest, err := runtimeprotocol.Digest(map[string]any{"tenant": c.Tenant, "namespace": c.Namespace,
		"applicationId": c.ApplicationID, "sessionId": c.SessionID, "turnId": c.TurnID, "actor": c.Actor, "kind": command.Kind, "payload": command.Payload})
	if err != nil {
		return nil, "", err
	}
	payload, err := runtimeprotocol.Digest(command.Payload)
	if err != nil {
		return nil, "", err
	}
	return &Interaction{Command: command, Digest: digest, Receipt: runtimeprotocol.RuntimeInteractionReceipt{
		Version: runtimeprotocol.Version, SessionID: c.SessionID, TurnID: c.TurnID, CommandID: c.CommandID,
		Kind: command.Kind, PayloadDigest: payload, Status: "runtime_accepted"}}, target, nil
}

func cancellationGeneration(command runtimeprotocol.InteractionCommand) (int64, bool) {
	switch value := command.Payload["expected_generation"].(type) {
	case json.Number:
		n, err := value.Int64()
		return n, err == nil && n >= 0
	case int64:
		return value, value >= 0
	case int:
		return int64(value), value >= 0
	}
	return 0, false
}

func applyCancellation(command runtimeprotocol.InteractionCommand, session *Session, row *Turn, now time.Time) error {
	if !SameScope(command.Context, session.Bootstrap.Context) || !SameScope(command.Context, row.Command.Context) ||
		row.Command.Context.TurnID == nil || *row.Command.Context.TurnID != *command.Context.TurnID {
		return ErrNotFound
	}
	activation := row.ActivationCommandID
	if activation == "" {
		activation = row.Command.Context.CommandID
	}
	if command.Payload["target_activation_command_id"] != activation {
		return ErrConflict
	}
	if command.Context.PermissionExpiresAt <= now.UnixMilli() {
		return ErrPermission
	}
	if Terminal(row.Status) {
		return nil
	}
	generation, _ := cancellationGeneration(command)
	if generation != row.Generation && row.GrantUntil > 0 || generation > row.Generation {
		return ErrConflict
	}
	if row.StopRequested && row.StopCommandID != "" {
		return nil
	}
	row.StopRequested, row.StopCommandID = true, command.Context.CommandID
	row.ActivationCommandID = activation
	row.Status, row.NextAttempt = "cancel_requested", now
	// Revoke the current step's write claim. Its outstanding HTTP response may
	// arrive, but cannot commit facts; new dispatch gates read StopRequested.
	row.ClaimToken, row.ClaimUntil = "", time.Time{}
	return nil
}

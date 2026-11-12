// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package agentservice

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
)

func signalIntent(command runtimeprotocol.InteractionCommand) (*Interaction, string, error) {
	intent, target, err := interactionIntent(command, "signal")
	if err != nil {
		return nil, "", err
	}
	answers, ok := command.Payload["answers"].([]any)
	if !ok || len(answers) < 1 || len(answers) > 100 || command.Context.Actor["internal"] == true {
		return nil, "", ErrInvalid
	}
	if _, err = runtimeprotocol.ParseAgentSignalProof(command.Context.Actor); err != nil {
		return nil, "", ErrPermission
	}
	seen := map[string]bool{}
	for _, raw := range answers {
		answer, ok := raw.(map[string]any)
		id, _ := answer["request_id"].(string)
		_, hasValue := answer["value"]
		value, err := json.Marshal(answer["value"])
		if !ok || answer["kind"] != "signal" || seen[id] || !hasValue || answer["child_session_id"] != nil || err != nil || len(value) > 65536 {
			return nil, "", ErrInvalid
		}
		if _, err = uuid.Parse(id); err != nil {
			return nil, "", ErrInvalid
		}
		seen[id] = true
	}
	return intent, target, nil
}

func applySignal(command runtimeprotocol.InteractionCommand, session *Session, turn *Turn, now time.Time) error {
	if session.Bootstrap.RuntimeKind != "workflow" && session.Bootstrap.RuntimeKind != "team" {
		return ErrInvalid
	}
	return applyApproval(command, session, turn, now)
}

func (r *Memory) AcceptSignal(_ context.Context, command runtimeprotocol.InteractionCommand, now time.Time) (*Interaction, error) {
	intent, target, err := signalIntent(command)
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
	session, turn := r.sessions[command.Context.SessionID], r.turns[target]
	if session == nil || turn == nil {
		return nil, ErrNotFound
	}
	if err = applySignal(command, session, turn, now); err != nil {
		return nil, err
	}
	intent.ActivationGeneration, intent.ActivationFence = turn.Generation, turn.Fence
	r.interactions[command.Context.CommandID] = copyJSON(intent)
	return copyJSON(intent), nil
}

func (r *Postgres) AcceptSignal(ctx context.Context, command runtimeprotocol.InteractionCommand, now time.Time) (*Interaction, error) {
	intent, target, err := signalIntent(command)
	if err != nil {
		return nil, err
	}
	return r.acceptInteraction(ctx, command, now, intent, target, applySignal)
}

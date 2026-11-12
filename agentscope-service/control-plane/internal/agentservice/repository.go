// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

// Package agentservice owns the Go runtime's Agent Service inbox and fact outbox.
package agentservice

import (
	"context"
	"errors"
	"strconv"
	"time"

	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
)

var (
	ErrConflict   = errors.New("runtime admission content conflicts with its receipt")
	ErrNotFound   = errors.New("runtime admission not found")
	ErrLease      = errors.New("runtime claim is stale")
	ErrPermission = errors.New("initial runtime admission has expired")
	ErrInvalid    = errors.New("invalid runtime command")
)

type Session struct {
	Bootstrap          runtimeprotocol.RuntimeSessionBootstrap `json:"bootstrap"`
	Digest             string                                  `json:"digest"`
	Lifecycle          string                                  `json:"lifecycle"`
	LifecycleRevision  int64                                   `json:"lifecycle_revision"`
	LifecycleCommandID string                                  `json:"lifecycle_command_id,omitempty"`
	LifecycleDigest    string                                  `json:"lifecycle_digest,omitempty"`
	LastSequence       int64                                   `json:"last_sequence"`
}

type Turn struct {
	Command               runtimeprotocol.TurnCommand `json:"command"`
	Digest                string                      `json:"digest"`
	Status                string                      `json:"status"`
	ClaimToken            string                      `json:"claim_token"`
	ClaimUntil            time.Time                   `json:"claim_until"`
	NextAttempt           time.Time                   `json:"next_attempt"`
	Generation            int64                       `json:"generation"`
	ActivationCommandID   string                      `json:"activation_command_id"`
	Fence                 string                      `json:"fence"`
	GrantUntil            int64                       `json:"grant_until"`
	ObservationCommandID  string                      `json:"observation_command_id,omitempty"`
	ObservationGeneration int64                       `json:"observation_generation,omitempty"`
	ObservationFence      string                      `json:"observation_fence,omitempty"`
	NativeInvocationID    string                      `json:"native_invocation_id"`
	NativeStarted         bool                        `json:"native_started"`
	StopRequested         bool                        `json:"stop_requested"`
	StopCommandID         string                      `json:"stop_command_id,omitempty"`
	ControlCommandID      string                      `json:"control_command_id,omitempty"`
	ControlAnswerIndex    int                         `json:"control_answer_index,omitempty"`
	NativeCursor          string                      `json:"native_cursor"`
	ReportedTokens        int64                       `json:"reported_tokens"`
}

type Fact struct {
	Value      runtimeprotocol.RuntimeFact `json:"value"`
	ClaimToken string                      `json:"claim_token"`
	ClaimUntil time.Time                   `json:"claim_until"`
}

// Claims are finite and use backend-owned leases. SaveTurn commits state and
// all supplied facts atomically. Fact delivery advances only after Agent Service's
// matching receipt; retries replay the same immutable fact and source position.
type Repository interface {
	Bootstrap(context.Context, runtimeprotocol.RuntimeSessionBootstrap, string, time.Time) (*Session, error)
	Session(context.Context, string) (*Session, error)
	ApplyLifecycle(context.Context, runtimeprotocol.RuntimeSessionLifecycle) (*runtimeprotocol.RuntimeLifecycleReceipt, error)
	AcceptTurn(context.Context, runtimeprotocol.TurnCommand, string, time.Time) (*Turn, error)
	Turn(context.Context, string) (*Turn, error)
	AcceptCancellation(context.Context, runtimeprotocol.InteractionCommand, time.Time) (*Interaction, error)
	AcceptApproval(context.Context, runtimeprotocol.InteractionCommand, time.Time) (*Interaction, error)
	AcceptSignal(context.Context, runtimeprotocol.InteractionCommand, time.Time) (*Interaction, error)
	Interaction(context.Context, string, string) (*Interaction, error)
	ClaimTurns(context.Context, time.Time, time.Duration, int) ([]Turn, error)
	SaveTurn(context.Context, Turn, []runtimeprotocol.RuntimeFact, time.Time, bool) error
	ClaimFacts(context.Context, time.Time, time.Duration, int) ([]Fact, error)
	AcknowledgeFact(context.Context, string, int64, string, time.Time) error
}

type Interaction struct {
	Command              runtimeprotocol.InteractionCommand        `json:"command"`
	Digest               string                                    `json:"digest"`
	Receipt              runtimeprotocol.RuntimeInteractionReceipt `json:"receipt"`
	ActivationGeneration int64                                     `json:"activation_generation,omitempty"`
	ActivationFence      string                                    `json:"activation_fence,omitempty"`
}

// Only an acknowledged approval may carry an earlier activation proof. This
// permits delayed delivery without granting old executors another write claim.
func approvalFactProof(fact runtimeprotocol.RuntimeFact, turn Turn, interaction *Interaction) (Turn, bool) {
	if fact.Type != "required_action.resolved" || interaction == nil || (interaction.Command.Kind != "approval" && interaction.Command.Kind != "signal") ||
		!SameScope(interaction.Command.Context, turn.Command.Context) ||
		interaction.Command.Payload["target_command_id"] != turn.Command.Context.CommandID ||
		fact.Payload["activation_command_id"] != interaction.Command.Context.CommandID ||
		interaction.ActivationGeneration < 1 || interaction.ActivationFence == "" {
		return turn, false
	}
	answers, _ := interaction.Command.Payload["answers"].([]any)
	for _, raw := range answers {
		answer, _ := raw.(map[string]any)
		if interaction.Command.Kind == "signal" && answer["request_id"] == fact.Payload["request_id"] && answer["kind"] == "signal" && fact.Payload["kind"] == "signal" && fact.Payload["resolution_status"] == "signalled" {
			turn.Generation, turn.Fence = interaction.ActivationGeneration, interaction.ActivationFence
			return turn, true
		}
		if answer["request_id"] == fact.Payload["request_id"] && answer["kind"] == "confirmation" && fact.Payload["kind"] == "confirmation" &&
			(answer["allow"] == true && fact.Payload["resolution_status"] == "approved" || answer["allow"] == false && fact.Payload["resolution_status"] == "rejected") {
			turn.Generation, turn.Fence = interaction.ActivationGeneration, interaction.ActivationFence
			return turn, true
		}
	}
	return turn, false
}

func Terminal(status string) bool {
	return status == "completed" || status == "failed" || status == "interrupted" || status == "cancelled"
}

func SameScope(a, b runtimeprotocol.Context) bool {
	return a.Tenant == b.Tenant && a.Namespace == b.Namespace && a.SessionID == b.SessionID &&
		(a.ApplicationID == nil && b.ApplicationID == nil || a.ApplicationID != nil && b.ApplicationID != nil && *a.ApplicationID == *b.ApplicationID)
}

func ClaimLimits(ttl time.Duration, limit int) bool {
	return ttl > 0 && ttl <= time.Minute && limit > 0 && limit <= 128
}

func observationProof(fact runtimeprotocol.RuntimeFact, turn Turn) Turn {
	// An ungranted control activation can stop and drain earlier execution, but
	// cannot invent a grant or use the previous proof to dispatch new work.
	if turn.GrantUntil == 0 && turn.ObservationGeneration > 0 &&
		fact.Payload["activation_command_id"] == turn.ObservationCommandID {
		turn.Generation, turn.Fence = turn.ObservationGeneration, turn.ObservationFence
	}
	return turn
}

func validFact(fact runtimeprotocol.RuntimeFact, turn Turn) bool {
	a, b := fact.Context, turn.Command.Context
	if !SameScope(a, b) || a.TurnID == nil || b.TurnID == nil || *a.TurnID != *b.TurnID || a.CommandID != b.CommandID ||
		fact.EventID == "" || fact.Source != "orchestration" || fact.SourceSequence != 0 {
		return false
	}
	if fact.ExecutionID != nil || fact.ExecutionFence != nil {
		if fact.ExecutionID == nil || fact.ExecutionFence == nil || turn.Generation < 1 || *fact.ExecutionID != strconv.FormatInt(turn.Generation, 10) || *fact.ExecutionFence != turn.Fence {
			return false
		}
	} else if fact.Type != "turn.failed" && fact.Type != "turn.interrupted" && fact.Type != "turn.cancelled" &&
		!(fact.Type == "turn.completed" && fact.Payload["control_stop"] == true && fact.Payload["native_stopped"] == true) {
		return false
	}
	left, err := runtimeprotocol.Digest(a.Actor)
	if err != nil {
		return false
	}
	right, err := runtimeprotocol.Digest(b.Actor)
	return err == nil && left == right
}

func validUpdate(value, prior Turn) bool {
	digest, err := runtimeprotocol.TurnDigest(value.Command)
	return err == nil && digest == prior.Digest && value.Digest == prior.Digest &&
		value.Generation >= prior.Generation &&
		(value.Generation != prior.Generation || prior.Generation == 0 || value.Fence == prior.Fence)
}

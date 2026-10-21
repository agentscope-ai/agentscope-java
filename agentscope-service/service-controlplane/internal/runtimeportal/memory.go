// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package runtimeportal

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"sync"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
	"github.com/google/uuid"
)

type memoryFact struct {
	Fact
	delivered bool
}
type Memory struct {
	mu           sync.Mutex
	sessions     map[string]*Session
	turns        map[string]*Turn
	facts        map[string][]*memoryFact
	interactions map[string]*Interaction
}

func copyJSON[T any](value T) T {
	raw, err := json.Marshal(value)
	if err != nil {
		panic(err)
	}
	var out T
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	if err = decoder.Decode(&out); err != nil {
		panic(err)
	}
	return out
}

func (r *Memory) init() {
	if r.sessions == nil {
		r.sessions = map[string]*Session{}
		r.turns = map[string]*Turn{}
		r.facts = map[string][]*memoryFact{}
		r.interactions = map[string]*Interaction{}
	}
}

func (r *Memory) Interaction(_ context.Context, session, id string) (*Interaction, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.init()
	row := r.interactions[id]
	if row == nil || row.Command.Context.SessionID != session {
		return nil, ErrNotFound
	}
	return copyJSON(row), nil
}

func (r *Memory) AcceptCancellation(_ context.Context, command runtimeprotocol.InteractionCommand, now time.Time) (*Interaction, error) {
	intent, target, err := cancellationIntent(command)
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
	if err = applyCancellation(command, session, row, now); err != nil {
		return nil, err
	}
	r.interactions[command.Context.CommandID] = copyJSON(intent)
	return copyJSON(intent), nil
}

func (r *Memory) Bootstrap(_ context.Context, request runtimeprotocol.RuntimeSessionBootstrap, digest string, now time.Time) (*Session, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.init()
	if existing := r.sessions[request.Context.SessionID]; existing != nil {
		if existing.Digest != digest || existing.Bootstrap.Context.CommandID != request.Context.CommandID {
			return nil, ErrConflict
		}
		return copyJSON(existing), nil
	}
	if request.Context.PermissionExpiresAt <= now.UnixMilli() {
		return nil, ErrPermission
	}
	for _, existing := range r.sessions {
		if existing.Bootstrap.Context.CommandID == request.Context.CommandID {
			return nil, ErrConflict
		}
	}
	v := &Session{Bootstrap: copyJSON(request), Digest: digest, Lifecycle: "active", LifecycleRevision: 1}
	r.sessions[request.Context.SessionID] = v
	return copyJSON(v), nil
}

func (r *Memory) Session(_ context.Context, id string) (*Session, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.init()
	v := r.sessions[id]
	if v == nil {
		return nil, ErrNotFound
	}
	return copyJSON(v), nil
}

func (r *Memory) AcceptTurn(_ context.Context, command runtimeprotocol.TurnCommand, digest string, now time.Time) (*Turn, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.init()
	session := r.sessions[command.Context.SessionID]
	if session == nil || !SameScope(session.Bootstrap.Context, command.Context) {
		return nil, ErrNotFound
	}
	if existing := r.turns[command.Context.CommandID]; existing != nil {
		if existing.Digest != digest {
			return nil, ErrConflict
		}
		return copyJSON(existing), nil
	}
	if session.Lifecycle != "active" {
		return nil, ErrConflict
	}
	for _, row := range r.turns {
		if row.Command.Context.SessionID == command.Context.SessionID &&
			(row.Command.AdmissionSequence == command.AdmissionSequence || *row.Command.Context.TurnID == *command.Context.TurnID) {
			return nil, ErrConflict
		}
	}
	v := &Turn{Command: copyJSON(command), Digest: digest, Status: "queued", NextAttempt: now}
	r.turns[command.Context.CommandID] = v
	return copyJSON(v), nil
}

func (r *Memory) Turn(_ context.Context, id string) (*Turn, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.init()
	v := r.turns[id]
	if v == nil {
		return nil, ErrNotFound
	}
	return copyJSON(v), nil
}

func (r *Memory) ClaimTurns(_ context.Context, now time.Time, ttl time.Duration, limit int) ([]Turn, error) {
	if !ClaimLimits(ttl, limit) {
		return nil, fmt.Errorf("invalid runtime Turn claim limits")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.init()
	candidates := []*Turn{}
	for _, row := range r.turns {
		if Terminal(row.Status) || row.NextAttempt.After(now) || row.ClaimUntil.After(now) {
			continue
		}
		blocked := false
		for _, older := range r.turns {
			if older.Command.Context.SessionID == row.Command.Context.SessionID && older.Command.AdmissionSequence < row.Command.AdmissionSequence && !Terminal(older.Status) {
				blocked = true
				break
			}
		}
		if !blocked {
			candidates = append(candidates, row)
		}
	}
	sort.Slice(candidates, func(i, j int) bool {
		if !candidates[i].NextAttempt.Equal(candidates[j].NextAttempt) {
			return candidates[i].NextAttempt.Before(candidates[j].NextAttempt)
		}
		return candidates[i].Command.Context.CommandID < candidates[j].Command.Context.CommandID
	})
	out := []Turn{}
	for _, row := range candidates {
		if len(out) >= limit {
			break
		}
		row.ClaimToken = uuid.NewString()
		row.ClaimUntil = now.Add(ttl)
		out = append(out, copyJSON(*row))
	}
	return out, nil
}

func (r *Memory) SaveTurn(_ context.Context, value Turn, facts []runtimeprotocol.RuntimeFact, now time.Time, release bool) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.init()
	row := r.turns[value.Command.Context.CommandID]
	if row == nil || row.ClaimToken != value.ClaimToken || !row.ClaimUntil.After(now) || row.Digest != value.Digest {
		return ErrLease
	}
	if !validUpdate(value, *row) {
		return ErrConflict
	}
	session := r.sessions[row.Command.Context.SessionID]
	if len(facts) > 128 {
		return fmt.Errorf("runtime fact batch exceeds 128")
	}
	// Validate the entire batch before any memory state changes, as a SQL transaction does.
	batch := map[string]string{}
	for _, fact := range facts {
		proof := value
		proof.Command = row.Command
		proof = observationProof(fact, proof)
		if activation, _ := fact.Payload["activation_command_id"].(string); fact.Type == "required_action.resolved" && fact.Payload["resolution_status"] != "cancelled" {
			var valid bool
			proof, valid = approvalFactProof(fact, proof, r.interactions[activation])
			if !valid {
				return ErrConflict
			}
		}
		if !validFact(fact, proof) {
			return ErrConflict
		}
		digest, err := runtimeprotocol.Digest(fact)
		if err != nil {
			return err
		}
		if prior, found := batch[fact.EventID]; found && prior != digest {
			return ErrConflict
		}
		batch[fact.EventID] = digest
		for _, existing := range r.facts[session.Bootstrap.Context.SessionID] {
			if existing.Value.EventID == fact.EventID {
				prior := existing.Value
				prior.SourceSequence = 0
				a, _ := runtimeprotocol.Digest(prior)
				b, _ := runtimeprotocol.Digest(fact)
				if a != b {
					return ErrConflict
				}
			}
		}
	}
	for _, fact := range facts {
		exists := false
		for _, prior := range r.facts[session.Bootstrap.Context.SessionID] {
			if prior.Value.EventID == fact.EventID {
				exists = true
				break
			}
		}
		if exists {
			continue
		}
		session.LastSequence++
		fact.SourceSequence = session.LastSequence
		r.facts[session.Bootstrap.Context.SessionID] = append(r.facts[session.Bootstrap.Context.SessionID], &memoryFact{Fact: Fact{Value: copyJSON(fact)}})
	}
	value.Command = row.Command
	if release {
		value.ClaimToken = ""
		value.ClaimUntil = time.Time{}
	}
	r.turns[value.Command.Context.CommandID] = copyJSON(&value)
	return nil
}

func (r *Memory) ClaimFacts(_ context.Context, now time.Time, ttl time.Duration, limit int) ([]Fact, error) {
	if !ClaimLimits(ttl, limit) {
		return nil, fmt.Errorf("invalid runtime fact claim limits")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.init()
	ids := []string{}
	for id := range r.facts {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	out := []Fact{}
	for _, id := range ids {
		for _, row := range r.facts[id] {
			if row.delivered {
				continue
			}
			if !row.ClaimUntil.After(now) {
				row.ClaimToken = uuid.NewString()
				row.ClaimUntil = now.Add(ttl)
				out = append(out, copyJSON(row.Fact))
			}
			break // Only the first undelivered fact of a Session is eligible.
		}
		if len(out) >= limit {
			break
		}
	}
	return out, nil
}

func (r *Memory) AcknowledgeFact(_ context.Context, session string, sequence int64, token string, now time.Time) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.init()
	for _, row := range r.facts[session] {
		if row.Value.SourceSequence == sequence {
			if row.ClaimToken != token || !row.delivered && !row.ClaimUntil.After(now) {
				return ErrLease
			}
			row.delivered = true
			return nil
		}
	}
	return ErrNotFound
}

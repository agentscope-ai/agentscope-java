// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package runtimeportal

import (
	"context"
	"errors"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
	"github.com/jackc/pgx/v5"
)

func (r *Postgres) scanInteraction(row pgx.Row) (*Interaction, error) {
	var id string
	var raw []byte
	if err := row.Scan(&id, &raw); err != nil {
		return nil, databaseError(err)
	}
	var value Interaction
	if err := r.Cipher.decode("interaction:"+id, raw, &value); err != nil {
		return nil, err
	}
	return &value, nil
}

func (r *Postgres) Interaction(ctx context.Context, session, id string) (*Interaction, error) {
	return r.scanInteraction(r.Pool.QueryRow(ctx, `SELECT id,payload FROM orchestration_portal_interactions WHERE session_id=$1 AND id=$2`, session, id))
}

func (r *Postgres) AcceptCancellation(ctx context.Context, command runtimeprotocol.InteractionCommand, now time.Time) (*Interaction, error) {
	intent, target, err := cancellationIntent(command)
	if err != nil {
		return nil, err
	}
	return r.acceptInteraction(ctx, command, now, intent, target, applyCancellation)
}

func (r *Postgres) acceptInteraction(ctx context.Context, command runtimeprotocol.InteractionCommand, now time.Time,
	intent *Interaction, target string, apply func(runtimeprotocol.InteractionCommand, *Session, *Turn, time.Time) error) (*Interaction, error) {
	tx, err := r.Pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	session, err := r.scanSession(tx.QueryRow(ctx, `SELECT id,payload,last_sequence FROM orchestration_portal_sessions WHERE id=$1 FOR UPDATE`, command.Context.SessionID))
	if err != nil {
		return nil, err
	}
	prior, err := r.scanInteraction(tx.QueryRow(ctx, `SELECT id,payload FROM orchestration_portal_interactions WHERE id=$1`, command.Context.CommandID))
	if err == nil {
		if prior.Digest != intent.Digest {
			return nil, ErrConflict
		}
		return prior, tx.Commit(ctx)
	}
	if !errors.Is(err, ErrNotFound) {
		return nil, err
	}
	row, err := r.scanTurn(tx.QueryRow(ctx, `SELECT `+turnColumns+` FROM orchestration_portal_turns WHERE id=$1 FOR UPDATE`, target))
	if err != nil {
		return nil, err
	}
	if err = apply(command, session, row, now); err != nil {
		return nil, err
	}
	if command.Kind == "approval" || command.Kind == "signal" {
		intent.ActivationGeneration, intent.ActivationFence = row.Generation, row.Fence
	}
	raw, err := r.Cipher.encode("turn:"+target, row)
	if err != nil {
		return nil, err
	}
	if _, err = tx.Exec(ctx, `UPDATE orchestration_portal_turns SET payload=$2,status=$3,next_attempt=$4,claim_token=$5,claim_until=$6 WHERE id=$1`,
		target, raw, row.Status, row.NextAttempt, nullableClaim(row.ClaimToken), nullableClaimTime(row.ClaimUntil)); err != nil {
		return nil, err
	}
	raw, err = r.Cipher.encode("interaction:"+command.Context.CommandID, intent)
	if err != nil {
		return nil, err
	}
	if _, err = tx.Exec(ctx, `INSERT INTO orchestration_portal_interactions(id,session_id,payload) VALUES($1,$2,$3)`, command.Context.CommandID, command.Context.SessionID, raw); err != nil {
		return nil, databaseError(err)
	}
	return intent, tx.Commit(ctx)
}

func nullableClaim(value string) *string {
	if value == "" {
		return nil
	}
	return &value
}
func nullableClaimTime(value time.Time) *time.Time {
	if value.IsZero() {
		return nil
	}
	return &value
}

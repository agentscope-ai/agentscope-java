// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package agentservice

import (
	"bytes"
	"context"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
)

//go:embed schema.sql
var SchemaSQL string

type Postgres struct {
	Pool   *pgxpool.Pool
	Cipher *Cipher
}

func databaseError(err error) error {
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	var constraint *pgconn.PgError
	if errors.As(err, &constraint) && constraint.Code == "23505" {
		return ErrConflict
	}
	return err
}

func decodeJSON(raw []byte, out any) error {
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	return decoder.Decode(out)
}

func (r *Postgres) scanSession(row pgx.Row) (*Session, error) {
	var raw []byte
	var id string
	var sequence int64
	if err := row.Scan(&id, &raw, &sequence); err != nil {
		return nil, databaseError(err)
	}
	var value Session
	if err := r.Cipher.decode("session:"+id, raw, &value); err != nil {
		return nil, err
	}
	value.LastSequence = sequence
	return &value, nil
}

func (r *Postgres) Session(ctx context.Context, id string) (*Session, error) {
	return r.scanSession(r.Pool.QueryRow(ctx, `SELECT id,payload,last_sequence FROM orchestration_portal_sessions WHERE id=$1`, id))
}

func (r *Postgres) Bootstrap(ctx context.Context, request runtimeprotocol.RuntimeSessionBootstrap, digest string, now time.Time) (*Session, error) {
	existing, err := r.Session(ctx, request.Context.SessionID)
	if err == nil {
		if existing.Digest != digest || existing.Bootstrap.Context.CommandID != request.Context.CommandID {
			return nil, ErrConflict
		}
		return existing, nil
	}
	if !errors.Is(err, ErrNotFound) {
		return nil, err
	}
	if request.Context.PermissionExpiresAt <= now.UnixMilli() {
		return nil, ErrPermission
	}
	v := Session{Bootstrap: request, Digest: digest, Lifecycle: "active", LifecycleRevision: 1}
	raw, err := r.Cipher.encode("session:"+request.Context.SessionID, v)
	if err != nil {
		return nil, err
	}
	_, err = r.Pool.Exec(ctx, `INSERT INTO orchestration_portal_sessions(id,command_id,digest,payload)
 VALUES($1,$2,$3,$4) ON CONFLICT DO NOTHING`, request.Context.SessionID, request.Context.CommandID, digest, raw)
	if err != nil {
		return nil, databaseError(err)
	}
	existing, err = r.Session(ctx, request.Context.SessionID)
	if errors.Is(err, ErrNotFound) {
		return nil, ErrConflict
	}
	if err == nil && (existing.Digest != digest || existing.Bootstrap.Context.CommandID != request.Context.CommandID) {
		return nil, ErrConflict
	}
	return existing, err
}

const turnColumns = `id,payload,status,next_attempt,claim_token,claim_until`

func (r *Postgres) scanTurn(row pgx.Row) (*Turn, error) {
	var raw []byte
	var id string
	var status string
	var next time.Time
	var token *string
	var until *time.Time
	if err := row.Scan(&id, &raw, &status, &next, &token, &until); err != nil {
		return nil, databaseError(err)
	}
	var value Turn
	if err := r.Cipher.decode("turn:"+id, raw, &value); err != nil {
		return nil, err
	}
	value.Status = status
	value.NextAttempt = next
	value.ClaimToken = ""
	value.ClaimUntil = time.Time{}
	if token != nil {
		value.ClaimToken = *token
	}
	if until != nil {
		value.ClaimUntil = *until
	}
	return &value, nil
}

func (r *Postgres) Turn(ctx context.Context, id string) (*Turn, error) {
	return r.scanTurn(r.Pool.QueryRow(ctx, `SELECT `+turnColumns+` FROM orchestration_portal_turns WHERE id=$1`, id))
}

func (r *Postgres) AcceptTurn(ctx context.Context, command runtimeprotocol.TurnCommand, digest string, now time.Time) (*Turn, error) {
	tx, err := r.Pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	session, err := r.scanSession(tx.QueryRow(ctx, `SELECT id,payload,last_sequence FROM orchestration_portal_sessions WHERE id=$1 FOR UPDATE`, command.Context.SessionID))
	if err != nil {
		return nil, err
	}
	if !SameScope(session.Bootstrap.Context, command.Context) {
		return nil, ErrNotFound
	}
	existing, err := r.scanTurn(tx.QueryRow(ctx, `SELECT `+turnColumns+` FROM orchestration_portal_turns WHERE id=$1`, command.Context.CommandID))
	if err == nil {
		if existing.Digest != digest {
			return nil, ErrConflict
		}
		return existing, tx.Commit(ctx)
	}
	if !errors.Is(err, ErrNotFound) {
		return nil, err
	}
	if session.Lifecycle != "active" {
		return nil, ErrConflict
	}
	v := Turn{Command: command, Digest: digest, Status: "queued", NextAttempt: now}
	raw, err := r.Cipher.encode("turn:"+command.Context.CommandID, v)
	if err != nil {
		return nil, err
	}
	_, err = tx.Exec(ctx, `INSERT INTO orchestration_portal_turns(id,session_id,turn_id,admission_sequence,digest,payload,status,next_attempt)
 VALUES($1,$2,$3,$4,$5,$6,'queued',$7)`, command.Context.CommandID, command.Context.SessionID, *command.Context.TurnID, command.AdmissionSequence, digest, raw, now)
	if err != nil {
		return nil, databaseError(err)
	}
	return &v, tx.Commit(ctx)
}

func (r *Postgres) ClaimTurns(ctx context.Context, now time.Time, ttl time.Duration, limit int) ([]Turn, error) {
	if !ClaimLimits(ttl, limit) {
		return nil, fmt.Errorf("invalid runtime Turn claim limits")
	}
	token := uuid.NewString()
	rows, err := r.Pool.Query(ctx, `WITH candidates AS (
 SELECT t.id FROM orchestration_portal_turns t
 WHERE t.status NOT IN ('completed','failed','interrupted','cancelled')
 AND t.next_attempt<=$1 AND (t.claim_until IS NULL OR t.claim_until<=$1)
 AND NOT EXISTS(SELECT 1 FROM orchestration_portal_turns older WHERE older.session_id=t.session_id
 AND older.admission_sequence<t.admission_sequence AND older.status NOT IN ('completed','failed','interrupted','cancelled'))
 ORDER BY t.next_attempt,t.id FOR UPDATE OF t SKIP LOCKED LIMIT $2)
 UPDATE orchestration_portal_turns t SET claim_token=$3,claim_until=$4 FROM candidates c WHERE t.id=c.id
 RETURNING t.id,t.payload,t.status,t.next_attempt,t.claim_token,t.claim_until`, now, limit, token, now.Add(ttl))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Turn{}
	for rows.Next() {
		row, err := r.scanTurn(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, *row)
	}
	return out, rows.Err()
}

func (r *Postgres) SaveTurn(ctx context.Context, value Turn, facts []runtimeprotocol.RuntimeFact, now time.Time, release bool) error {
	if len(facts) > 128 {
		return fmt.Errorf("runtime fact batch exceeds 128")
	}
	tx, err := r.Pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	// Always lock Session before Turn, matching acceptance and lifecycle operations.
	session, err := r.scanSession(tx.QueryRow(ctx, `SELECT id,payload,last_sequence FROM orchestration_portal_sessions WHERE id=$1 FOR UPDATE`, value.Command.Context.SessionID))
	if err != nil {
		return err
	}
	prior, err := r.scanTurn(tx.QueryRow(ctx, `SELECT `+turnColumns+` FROM orchestration_portal_turns WHERE id=$1 FOR UPDATE`, value.Command.Context.CommandID))
	if err != nil {
		return err
	}
	if prior.ClaimToken != value.ClaimToken || !prior.ClaimUntil.After(now) || prior.Digest != value.Digest {
		return ErrLease
	}
	if !validUpdate(value, *prior) || !SameScope(session.Bootstrap.Context, prior.Command.Context) {
		return ErrConflict
	}
	for _, fact := range facts {
		proof := value
		proof.Command = prior.Command
		proof = observationProof(fact, proof)
		if activation, _ := fact.Payload["activation_command_id"].(string); fact.Type == "required_action.resolved" && fact.Payload["resolution_status"] != "cancelled" {
			interaction, loadErr := r.scanInteraction(tx.QueryRow(ctx, `SELECT id,payload FROM orchestration_portal_interactions WHERE id=$1 AND session_id=$2`, activation, prior.Command.Context.SessionID))
			if loadErr != nil {
				return loadErr
			}
			var valid bool
			proof, valid = approvalFactProof(fact, proof, interaction)
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
		var previous string
		err = tx.QueryRow(ctx, `SELECT digest FROM orchestration_portal_facts WHERE session_id=$1 AND event_id=$2`, fact.Context.SessionID, fact.EventID).Scan(&previous)
		if err == nil {
			if previous != digest {
				return ErrConflict
			}
			continue
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		session.LastSequence++
		fact.SourceSequence = session.LastSequence
		raw, err := r.Cipher.encode("fact:"+fact.Context.SessionID+":"+strconv.FormatInt(fact.SourceSequence, 10), fact)
		if err != nil {
			return err
		}
		_, err = tx.Exec(ctx, `INSERT INTO orchestration_portal_facts(session_id,sequence,event_id,digest,payload) VALUES($1,$2,$3,$4,$5)`, fact.Context.SessionID, fact.SourceSequence, fact.EventID, digest, raw)
		if err != nil {
			return databaseError(err)
		}
	}
	_, err = tx.Exec(ctx, `UPDATE orchestration_portal_sessions SET last_sequence=$2 WHERE id=$1`, value.Command.Context.SessionID, session.LastSequence)
	if err != nil {
		return err
	}
	value.Command = prior.Command
	var token *string
	var until *time.Time
	if !release {
		token = &value.ClaimToken
		until = &value.ClaimUntil
	} else {
		value.ClaimToken = ""
		value.ClaimUntil = time.Time{}
	}
	raw, err := r.Cipher.encode("turn:"+value.Command.Context.CommandID, value)
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `UPDATE orchestration_portal_turns SET payload=$2,status=$3,next_attempt=$4,claim_token=$5,claim_until=$6 WHERE id=$1`, value.Command.Context.CommandID, raw, value.Status, value.NextAttempt, token, until)
	if err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (r *Postgres) ClaimFacts(ctx context.Context, now time.Time, ttl time.Duration, limit int) ([]Fact, error) {
	if !ClaimLimits(ttl, limit) {
		return nil, fmt.Errorf("invalid runtime fact claim limits")
	}
	token := uuid.NewString()
	rows, err := r.Pool.Query(ctx, `WITH candidates AS (
 SELECT f.session_id,f.sequence FROM orchestration_portal_facts f WHERE NOT f.delivered
 AND (f.claim_until IS NULL OR f.claim_until<=$1)
 AND NOT EXISTS(SELECT 1 FROM orchestration_portal_facts older WHERE older.session_id=f.session_id AND older.sequence<f.sequence AND NOT older.delivered)
 ORDER BY f.session_id,f.sequence FOR UPDATE OF f SKIP LOCKED LIMIT $2)
 UPDATE orchestration_portal_facts f SET claim_token=$3,claim_until=$4 FROM candidates c
 WHERE f.session_id=c.session_id AND f.sequence=c.sequence RETURNING f.session_id,f.sequence,f.payload,f.claim_token,f.claim_until`, now, limit, token, now.Add(ttl))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Fact{}
	for rows.Next() {
		var raw []byte
		var value Fact
		var session string
		var sequence int64
		if err = rows.Scan(&session, &sequence, &raw, &value.ClaimToken, &value.ClaimUntil); err != nil {
			return nil, err
		}
		if err = r.Cipher.decode("fact:"+session+":"+strconv.FormatInt(sequence, 10), raw, &value.Value); err != nil {
			return nil, err
		}
		out = append(out, value)
	}
	return out, rows.Err()
}

func (r *Postgres) AcknowledgeFact(ctx context.Context, session string, sequence int64, token string, now time.Time) error {
	tag, err := r.Pool.Exec(ctx, `UPDATE orchestration_portal_facts SET delivered=TRUE WHERE session_id=$1 AND sequence=$2 AND claim_token=$3 AND (delivered OR claim_until>$4)`, session, sequence, token, now)
	if err != nil {
		return err
	}
	if tag.RowsAffected() != 1 {
		return ErrLease
	}
	return nil
}

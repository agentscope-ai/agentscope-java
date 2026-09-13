// Copyright 2024-2026 the original author or authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package postgres

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type outboxRepo struct{ pool *pgxpool.Pool }

const outboxColumns = `id,tenant,namespace,aggregate_type,aggregate_id,event_type,schema_version,
	actor_type,actor_ref,causation_id,correlation_id,occurred_at,payload,dedupe_key,attempts,
	available_at,claimed_by,claimed_until,delivered_at,dead_lettered_at,last_error,created_at`

func scanOutboxEvent(row scannable) (*controlmodel.OutboxEvent, error) {
	event := &controlmodel.OutboxEvent{}
	var dedupeKey, claimedBy, lastError *string
	var actorRef, causationID, correlationID *string
	err := row.Scan(&event.ID, &event.Tenant, &event.Namespace, &event.AggregateType, &event.AggregateID,
		&event.EventType, &event.SchemaVersion, &event.Actor.Type, &actorRef, &causationID,
		&correlationID, &event.OccurredAt, &event.Payload, &dedupeKey, &event.Attempts, &event.AvailableAt,
		&claimedBy, &event.ClaimedUntil, &event.DeliveredAt, &event.DeadLetteredAt, &lastError, &event.CreatedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	event.DedupeKey = deref(dedupeKey)
	event.Actor.Ref = deref(actorRef)
	event.CausationID = deref(causationID)
	event.CorrelationID = deref(correlationID)
	event.ClaimedBy = deref(claimedBy)
	event.LastError = deref(lastError)
	return event, nil
}

func (r *outboxRepo) Enqueue(ctx context.Context, event *controlmodel.OutboxEvent) (*controlmodel.OutboxEvent, error) {
	if event == nil || event.AggregateType == "" || event.AggregateID == "" || event.EventType == "" {
		return nil, fmt.Errorf("outbox event requires aggregateType, aggregateId, and eventType")
	}
	if event.ID == uuid.Nil {
		event.ID = uuid.New()
	}
	if event.AvailableAt.IsZero() {
		event.AvailableAt = time.Now().UTC()
	}
	if event.OccurredAt.IsZero() {
		event.OccurredAt = time.Now().UTC()
	}
	if event.SchemaVersion <= 0 {
		event.SchemaVersion = 1
	}
	if event.Actor.Type == "" {
		event.Actor.Type = controlmodel.ActorSystem
	}
	payload := event.Payload
	if len(payload) == 0 {
		payload = []byte(`{}`)
	}
	return scanOutboxEvent(r.pool.QueryRow(ctx, `INSERT INTO control_outbox (
		id,tenant,namespace,aggregate_type,aggregate_id,event_type,schema_version,actor_type,
		actor_ref,causation_id,correlation_id,occurred_at,payload,dedupe_key,attempts,
		available_at,claimed_by,claimed_until,delivered_at,dead_lettered_at,last_error)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21)
		ON CONFLICT (tenant,dedupe_key) WHERE dedupe_key IS NOT NULL
		DO UPDATE SET id=control_outbox.id RETURNING `+outboxColumns,
		event.ID, event.Tenant, event.Namespace, event.AggregateType, event.AggregateID, event.EventType,
		event.SchemaVersion, event.Actor.Type, nullStr(event.Actor.Ref), nullStr(event.CausationID),
		nullStr(event.CorrelationID), event.OccurredAt, payload, nullStr(event.DedupeKey), event.Attempts,
		event.AvailableAt, nullStr(event.ClaimedBy), event.ClaimedUntil, event.DeliveredAt,
		event.DeadLetteredAt, nullStr(event.LastError)))
}

func (r *outboxRepo) Claim(ctx context.Context, worker string, now time.Time, ttl time.Duration, limit int) ([]*controlmodel.OutboxEvent, error) {
	if worker == "" || ttl <= 0 {
		return nil, fmt.Errorf("outbox claim requires worker and positive ttl")
	}
	if limit <= 0 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `WITH candidates AS (
		SELECT id AS event_id FROM control_outbox
		WHERE delivered_at IS NULL AND dead_lettered_at IS NULL AND available_at<=$1
			AND (claimed_until IS NULL OR claimed_until<=$1)
		ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT $2
	)
	UPDATE control_outbox o SET claimed_by=$3,claimed_until=$1+$4::interval,
		attempts=o.attempts+1 FROM candidates c WHERE o.id=c.event_id
	RETURNING `+outboxColumns, now, limit, worker, intervalSeconds(ttl))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.OutboxEvent, 0)
	for rows.Next() {
		event, err := scanOutboxEvent(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, event)
	}
	return out, rows.Err()
}

func (r *outboxRepo) MarkDelivered(ctx context.Context, id uuid.UUID, worker string) error {
	tag, err := r.pool.Exec(ctx, `UPDATE control_outbox SET delivered_at=now(),claimed_until=NULL
		WHERE id=$1 AND claimed_by=$2 AND delivered_at IS NULL`, id, worker)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrConflict
	}
	return nil
}

func (r *outboxRepo) MarkFailed(ctx context.Context, id uuid.UUID, worker, lastError string, retryAt time.Time) error {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var event controlmodel.OutboxEvent
	var actorRef *string
	err = tx.QueryRow(ctx, `UPDATE control_outbox SET last_error=$3,available_at=$4,
		dead_lettered_at=CASE WHEN attempts>=12 THEN now() ELSE dead_lettered_at END,
		claimed_by=NULL,claimed_until=NULL WHERE id=$1 AND claimed_by=$2 AND delivered_at IS NULL
		RETURNING tenant,namespace,aggregate_type,aggregate_id,event_type,actor_type,actor_ref,dead_lettered_at`,
		id, worker, nullStr(lastError), retryAt).Scan(&event.Tenant, &event.Namespace,
		&event.AggregateType, &event.AggregateID, &event.EventType, &event.Actor.Type,
		&actorRef, &event.DeadLetteredAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return store.ErrConflict
		}
		return err
	}
	event.Actor.Ref = deref(actorRef)
	if event.DeadLetteredAt != nil {
		recipient := "admin"
		if event.AggregateType == "agent-task" {
			if taskID, parseErr := uuid.Parse(event.AggregateID); parseErr == nil {
				var accountable *string
				if scanErr := tx.QueryRow(ctx, `SELECT accountable_human_ref FROM agent_tasks WHERE id=$1`, taskID).Scan(&accountable); scanErr == nil && accountable != nil && *accountable != "" {
					recipient = *accountable
				}
			}
		} else if event.Actor.Type == controlmodel.ActorHuman && event.Actor.Ref != "" {
			recipient = event.Actor.Ref
		}
		_, err = tx.Exec(ctx, `INSERT INTO inbox_items
			(id,tenant,namespace,recipient_type,recipient_ref,type,severity,actor_type,actor_ref,title,body,dedupe_key)
			VALUES ($1,$2,$3,$4,$5,'outbox_dead_letter','error',$6,'outbox',$7,$8,$9)
			ON CONFLICT (tenant,dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING`, uuid.New(),
			event.Tenant, event.Namespace, controlmodel.AssigneeHuman, recipient,
			controlmodel.ActorSystem, "Delivery requires attention", event.EventType+": "+lastError,
			"outbox-dead-letter:"+id.String())
		if err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

func (r *outboxRepo) MarkDeferred(ctx context.Context, id uuid.UUID, worker, lastError string, retryAt time.Time) error {
	tag, err := r.pool.Exec(ctx, `UPDATE control_outbox SET last_error=$3,available_at=$4,
		claimed_by=NULL,claimed_until=NULL WHERE id=$1 AND claimed_by=$2 AND delivered_at IS NULL
		AND dead_lettered_at IS NULL`, id, worker, nullStr(lastError), retryAt)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrConflict
	}
	return nil
}

func (r *outboxRepo) ListDeadLetters(ctx context.Context, tenant, namespace string, limit int) ([]*controlmodel.OutboxEvent, error) {
	if limit <= 0 || limit > 1000 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `SELECT `+outboxColumns+` FROM control_outbox
		WHERE dead_lettered_at IS NOT NULL AND ($1='' OR tenant=$1) AND ($2='' OR namespace=$2)
		ORDER BY dead_lettered_at DESC LIMIT $3`, tenant, namespace, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.OutboxEvent, 0)
	for rows.Next() {
		event, scanErr := scanOutboxEvent(rows)
		if scanErr != nil {
			return nil, scanErr
		}
		out = append(out, event)
	}
	return out, rows.Err()
}

func (r *outboxRepo) ReplayDeadLetter(ctx context.Context, id uuid.UUID) (*controlmodel.OutboxEvent, error) {
	event, err := scanOutboxEvent(r.pool.QueryRow(ctx, `UPDATE control_outbox SET
		dead_lettered_at=NULL,claimed_by=NULL,claimed_until=NULL,last_error=NULL,attempts=0,available_at=now()
		WHERE id=$1 AND dead_lettered_at IS NOT NULL AND delivered_at IS NULL RETURNING `+outboxColumns, id))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return event, err
}

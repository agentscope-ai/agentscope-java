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
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

func insertActivityTx(ctx context.Context, tx pgx.Tx, activity *controlmodel.Activity) error {
	if activity == nil {
		return nil
	}
	if activity.ID == uuid.Nil {
		activity.ID = uuid.New()
	}
	if activity.CreatedAt.IsZero() {
		activity.CreatedAt = time.Now().UTC()
	}
	_, err := tx.Exec(ctx, `INSERT INTO activity_log
		(id,tenant,namespace,issue_id,actor_type,actor_ref,action,object_type,
		 object_ref,causation_id,correlation_id,details,created_at)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)`,
		activity.ID, activity.Tenant, activity.Namespace, activity.IssueID,
		activity.Actor.Type, nullStr(activity.Actor.Ref), activity.Action,
		activity.ObjectType, activity.ObjectRef, nullStr(activity.CausationID),
		nullStr(activity.CorrelationID), nullJSON(activity.Details), activity.CreatedAt)
	return err
}

func enqueueCollaborationEventTx(ctx context.Context, tx pgx.Tx, tenant, aggregateType string, aggregateID uuid.UUID, eventType string, payload any, dedupe string) error {
	data, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	now := time.Now().UTC()
	meta := inferEventMetadata(data)
	_, err = tx.Exec(ctx, `INSERT INTO control_outbox
		(id,tenant,namespace,aggregate_type,aggregate_id,event_type,schema_version,
		 actor_type,actor_ref,causation_id,correlation_id,occurred_at,payload,dedupe_key,
		 attempts,available_at,created_at)
		VALUES ($1,$2,$3,$4,$5,$6,1,$7,$8,$9,$10,$11,$12,$13,0,$11,$11)
		ON CONFLICT (tenant,dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING`,
		uuid.New(), tenant, meta.Namespace, aggregateType, aggregateID.String(), eventType,
		meta.Actor.Type, nullStr(meta.Actor.Ref), nullStr(meta.CausationID), nullStr(meta.CorrelationID),
		now, data, nullStr(dedupe))
	return err
}

type inferredEventMetadata struct {
	Namespace, CausationID, CorrelationID string
	Actor                                 controlmodel.Actor
}

func inferEventMetadata(data []byte) inferredEventMetadata {
	meta := inferredEventMetadata{Actor: controlmodel.Actor{Type: controlmodel.ActorSystem}}
	var root map[string]any
	if json.Unmarshal(data, &root) != nil {
		return meta
	}
	for _, key := range []string{"comment", "task", "issue", "artifact", "approval"} {
		if nested, ok := root[key].(map[string]any); ok {
			for k, v := range nested {
				if _, exists := root[k]; !exists {
					root[k] = v
				}
			}
			break
		}
	}
	meta.Namespace, _ = root["namespace"].(string)
	meta.CausationID, _ = root["causationId"].(string)
	meta.CorrelationID, _ = root["correlationId"].(string)
	for _, key := range []string{"actor", "author", "creator", "originator", "requestedBy"} {
		if actor, ok := root[key].(map[string]any); ok {
			if value, ok := actor["type"].(string); ok && value != "" {
				meta.Actor.Type = controlmodel.ActorType(value)
			}
			meta.Actor.Ref, _ = actor["ref"].(string)
			break
		}
	}
	return meta
}

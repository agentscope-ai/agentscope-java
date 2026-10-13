/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Package sessionapi owns application-facing sessions independently of runtime sessions.
package sessionapi

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

const Path = "service-sessions"

type Target struct {
	Type       string     `json:"type"`
	ID         uuid.UUID  `json:"id"`
	Version    int        `json:"version,omitempty"`
	RevisionID *uuid.UUID `json:"revisionId,omitempty"`
}

// Contract and Principal are persisted, but never returned by the public API.
type Session struct {
	ID            uuid.UUID       `json:"id"`
	Tenant        string          `json:"tenant"`
	Namespace     string          `json:"namespace"`
	Target        Target          `json:"target"`
	Principal     string          `json:"principal"`
	ApplicationID *uuid.UUID      `json:"applicationId,omitempty"`
	Contract      json.RawMessage `json:"contract"`
	Status        string          `json:"status"`
	CreatedAt     time.Time       `json:"createdAt"`
	UpdatedAt     time.Time       `json:"updatedAt"`
	InputHash     string          `json:"inputHash,omitempty"`
}

func (v *Session) View() map[string]any {
	return map[string]any{"id": v.ID, "target": v.Target, "status": v.Status,
		"applicationId": v.ApplicationID, "createdAt": v.CreatedAt, "updatedAt": v.UpdatedAt}
}

func Get(ctx context.Context, st store.Store, tenant string, id uuid.UUID) (*Session, error) {
	r, err := st.KV().Get(ctx, tenant, Path, id.String())
	if err != nil {
		return nil, err
	}
	var v Session
	if err = json.Unmarshal(r.Value, &v); err != nil {
		return nil, err
	}
	return &v, nil
}

func Save(ctx context.Context, st store.Store, v *Session, create bool) error {
	raw, err := json.Marshal(v)
	if err != nil {
		return err
	}
	if create {
		_, ok, e := st.KV().PutIfVersion(ctx, v.Tenant, Path, v.ID.String(), raw, 0)
		if e != nil {
			return e
		}
		if !ok {
			return store.ErrConflict
		}
		return nil
	}
	_, err = st.KV().Put(ctx, v.Tenant, Path, v.ID.String(), raw)
	return err
}

func ValidateTarget(t Target) error {
	if t.ID == uuid.Nil {
		return fmt.Errorf("target.id is required")
	}
	if t.Type != "agent" && t.Type != "team" && t.Type != "workflow" {
		return fmt.Errorf("target.type must be agent, team or workflow")
	}
	if t.Version < 0 {
		return fmt.Errorf("target.version must be positive")
	}
	if t.Type != "agent" && t.Version != 0 {
		return fmt.Errorf("version is supported for Agent targets; Workflow uses revisionId")
	}
	if t.Type != "workflow" && t.RevisionID != nil {
		return fmt.Errorf("revisionId is only supported for Workflow targets")
	}
	return nil
}

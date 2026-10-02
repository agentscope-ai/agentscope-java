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

package httpapi

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// ensureHostedTaskSession materializes the diagnostic resource for hosted work
// that did not start from Chat or an Endpoint conversation. Runtime session IDs
// remain opaque; the returned Session has its own control-plane primary key.
func (s *Server) ensureHostedTaskSession(ctx context.Context, attempt *model.ExecutionAttempt) (*store.Session, error) {
	if attempt == nil || attempt.BackendKind != model.DataPlaneHostedRuntime || attempt.SessionID == "" || attempt.AgentID == uuid.Nil {
		return nil, nil
	}
	key := attempt.Tenant + "\x00" + attempt.Namespace + "\x00" + attempt.AgentID.String() + "\x00" + attempt.SessionID
	var session *store.Session
	err := s.store.WithSessionLock(ctx, key, func(lockCtx context.Context) error {
		// Detect identity collisions even if an unrelated private task would be
		// filtered from this caller's list. Never overwrite its Session.
		lookupCtx := store.WithWorkAccess(lockCtx, store.WorkAccess{})
		existing, err := s.store.Sessions().List(lookupCtx, store.SessionFilter{Tenant: attempt.Tenant, AgentID: attempt.AgentID, SessionID: attempt.SessionID, Limit: 2})
		if err != nil {
			return err
		}
		if len(existing) > 0 {
			if len(existing) != 1 || existing[0].Namespace != attempt.Namespace || existing[0].BindingID != attempt.BindingID {
				return store.ErrConflict
			}
			session = existing[0]
			return nil
		}
		task, err := s.store.Collaboration().GetAgentTask(lockCtx, attempt.AgentTaskID)
		if err != nil {
			return err
		}
		if task.Tenant != attempt.Tenant || task.Namespace != attempt.Namespace || task.AgentRef != attempt.AgentID.String() {
			return store.ErrConflict
		}
		agent, err := s.store.AgentCatalog().GetAgent(lockCtx, attempt.AgentID)
		if err != nil {
			return err
		}
		if agent.Tenant != attempt.Tenant || agent.Namespace != attempt.Namespace {
			return store.ErrConflict
		}
		phase := store.SessionPhaseActive
		if model.IsExecutionAttemptTerminal(attempt.State) {
			phase = store.SessionPhaseIdle
		}
		session, err = s.store.Sessions().Upsert(lockCtx, &store.Session{Tenant: attempt.Tenant, Namespace: attempt.Namespace, AgentID: attempt.AgentID, BindingID: attempt.BindingID, AgentName: agent.AgentKey, SessionID: attempt.SessionID, AgentTaskID: &attempt.AgentTaskID, OriginType: "agent-task", OriginRef: attempt.AgentTaskID.String(), Framework: string(model.DataPlaneHostedRuntime), Phase: phase, StartedAt: attempt.StartedAt, LastActiveAt: &attempt.UpdatedAt})
		if err != nil {
			return err
		}
		// Older workers already wrote provider events to the Run timeline even
		// when no Session projection existed. Recover only this Attempt's events.
		for after := int64(0); attempt.RunID != uuid.Nil; {
			events, err := s.store.Orchestration().ListRunEvents(lockCtx, attempt.RunID, after, 500)
			if err != nil {
				return err
			}
			for _, event := range events {
				if event.Sequence > after {
					after = event.Sequence
				}
				if event.Type != "attempt.provider_event" || event.AttemptID == nil || *event.AttemptID != attempt.ID {
					continue
				}
				var payload struct {
					Provider          string          `json:"provider"`
					EventType         string          `json:"eventType"`
					ProviderSessionID string          `json:"providerSessionId"`
					Raw               json.RawMessage `json:"raw"`
				}
				if err = json.Unmarshal(event.Payload, &payload); err != nil {
					return err
				}
				sourceKey := event.IdempotencyKey
				if sourceKey == "" {
					sourceKey = "run-event:" + event.ID.String()
				}
				ordinal := int64(0)
				_, _ = fmt.Sscanf(strings.TrimPrefix(sourceKey, "provider-event:"+attempt.ID.String()+":"), "%d", &ordinal)
				projected := hostedProviderSessionEvent(attempt, payload.Provider, payload.EventType, payload.ProviderSessionID, ordinal, payload.Raw)
				projected.OccurredAt = event.OccurredAt
				if err = s.appendSessionEventLocked(lockCtx, session.ID, sourceKey, projected); err != nil {
					return err
				}
			}
			if len(events) < 500 {
				break
			}
		}
		return s.store.Turns().SyncOnPhase(lockCtx, session.ID, phase)
	})
	return session, err
}

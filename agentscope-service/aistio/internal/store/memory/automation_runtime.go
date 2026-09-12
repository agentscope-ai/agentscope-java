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

package memory

import (
	"context"
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"sort"
	"time"
)

func (r *collaborationRepo) AdmitAutomationRun(_ context.Context, req store.AutomationAdmission) (*controlmodel.AutomationRun, bool, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	rule := cloneAutomation(r.s.automations[req.Run.AutomationID])
	if rule == nil {
		return nil, false, store.ErrNotFound
	}
	active := false
	for _, run := range r.s.automationRuns {
		if run.AutomationID != rule.ID {
			continue
		}
		if run.IdempotencyKey == req.Run.IdempotencyKey {
			if !store.SameAutomationInput(run.Input, req.Run.Input) {
				return nil, false, store.ErrConflict
			}
			return cloneAutomationRun(run), false, nil
		}
		if !controlmodel.IsAutomationRunTerminal(run.Status) && run.ExecutionCompletedAt == nil {
			active = true
		}
	}
	run := cloneAutomationRun(req.Run)
	req.Run = run
	run.ID = nonNilUUID(run.ID)
	if err := store.PrepareAutomationAdmission(rule, req, active, time.Now().UTC()); err != nil {
		return nil, false, err
	}
	r.s.automations[rule.ID] = rule
	r.s.automationRuns[run.ID] = cloneAutomationRun(run)
	return run, true, nil
}

func (r *collaborationRepo) GetAutomationRun(_ context.Context, id uuid.UUID) (*controlmodel.AutomationRun, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	v := r.s.automationRuns[id]
	if v == nil {
		return nil, store.ErrNotFound
	}
	return cloneAutomationRun(v), nil
}

func (r *collaborationRepo) ListPendingAutomationRuns(_ context.Context, limit int) ([]*controlmodel.AutomationRun, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*controlmodel.AutomationRun{}
	for _, v := range r.s.automationRuns {
		if v.Snapshot != nil && !controlmodel.IsAutomationRunTerminal(v.Status) {
			out = append(out, cloneAutomationRun(v))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UpdatedAt.Before(out[j].UpdatedAt) })
	return page(out, 0, limit), nil
}

func (r *collaborationRepo) ClaimAutomationRun(_ context.Context, id uuid.UUID, now time.Time, ttl time.Duration) (*controlmodel.AutomationRun, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	v := r.s.automationRuns[id]
	if v == nil {
		return nil, store.ErrNotFound
	}
	if v.Status != controlmodel.AutomationRunQueued && v.Status != controlmodel.AutomationRunDispatching {
		return nil, store.ErrConflict
	}
	if v.LeaseUntil != nil && v.LeaseUntil.After(now) {
		return nil, store.ErrConflict
	}
	if v.Snapshot != nil && v.Snapshot.Execution != nil {
		for _, other := range r.s.automationRuns {
			if other.ID != v.ID && other.AutomationID == v.AutomationID && !controlmodel.IsAutomationRunTerminal(other.Status) && other.ExecutionCompletedAt == nil && (other.Status != controlmodel.AutomationRunQueued || other.IssueID != nil || other.CreatedAt.Before(v.CreatedAt) || (other.CreatedAt.Equal(v.CreatedAt) && other.ID.String() < v.ID.String())) {
				v.UpdatedAt = now
				v.Version++
				return nil, store.ErrConflict
			}
		}
	}
	until := now.Add(ttl)
	v.Status = controlmodel.AutomationRunDispatching
	v.LeaseUntil = &until
	v.LeaseToken = uuid.New()
	v.Version++
	v.UpdatedAt = now
	return cloneAutomationRun(v), nil
}

func cloneAutomationDelivery(v *controlmodel.AutomationDelivery) *controlmodel.AutomationDelivery {
	if v == nil {
		return nil
	}
	c := *v
	c.Input = cloneJSON(v.Input)
	return &c
}

func (r *collaborationRepo) SaveAutomationDelivery(_ context.Context, in *controlmodel.AutomationDelivery) (*controlmodel.AutomationDelivery, bool, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if old := r.s.automationDeliveries[in.ID]; old != nil {
		if old.Status == "queued" {
			r.s.automationDeliveries[in.ID] = cloneAutomationDelivery(in)
			return cloneAutomationDelivery(in), false, nil
		}
		return cloneAutomationDelivery(old), false, nil
	}
	for _, old := range r.s.automationDeliveries {
		if old.AutomationID == in.AutomationID && old.TriggerID == in.TriggerID && old.IdempotencyKey == in.IdempotencyKey {
			if old.Event != in.Event || !store.SameAutomationInput(old.Input, in.Input) {
				return nil, false, store.ErrConflict
			}
			return cloneAutomationDelivery(old), false, nil
		}
	}
	v := cloneAutomationDelivery(in)
	v.ID = nonNilUUID(v.ID)
	v.CreatedAt = time.Now().UTC()
	r.s.automationDeliveries[v.ID] = cloneAutomationDelivery(v)
	return v, true, nil
}
func (r *collaborationRepo) GetAutomationDelivery(_ context.Context, id uuid.UUID) (*controlmodel.AutomationDelivery, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	v := r.s.automationDeliveries[id]
	if v == nil {
		return nil, store.ErrNotFound
	}
	return cloneAutomationDelivery(v), nil
}
func (r *collaborationRepo) ListAutomationDeliveries(_ context.Context, id uuid.UUID, limit, offset int) ([]*controlmodel.AutomationDelivery, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*controlmodel.AutomationDelivery{}
	for _, v := range r.s.automationDeliveries {
		if (id == uuid.Nil && v.Status == "queued") || v.AutomationID == id {
			out = append(out, cloneAutomationDelivery(v))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.After(out[j].CreatedAt) })
	return page(out, offset, limit), nil
}

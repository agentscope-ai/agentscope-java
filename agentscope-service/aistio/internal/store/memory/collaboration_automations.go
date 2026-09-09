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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package memory

import (
	"context"
	"encoding/json"
	"slices"
	"sort"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func cloneAutomation(in *controlmodel.Automation) *controlmodel.Automation {
	if in == nil {
		return nil
	}
	out := *in
	out.TriggerConfig, out.ActionConfig = cloneJSON(in.TriggerConfig), cloneJSON(in.ActionConfig)
	out.Execution = nil
	out.Triggers = nil
	raw, _ := json.Marshal(in.Execution)
	_ = json.Unmarshal(raw, &out.Execution)
	raw, _ = json.Marshal(in.Triggers)
	_ = json.Unmarshal(raw, &out.Triggers)
	out.WebhookConfigured = in.WebhookSecretHash != ""
	return &out
}

func cloneAutomationRun(in *controlmodel.AutomationRun) *controlmodel.AutomationRun {
	if in == nil {
		return nil
	}
	out := *in
	out.Input, out.Output = cloneJSON(in.Input), cloneJSON(in.Output)
	out.Snapshot = cloneAutomation(in.Snapshot)
	return &out
}

func (r *collaborationRepo) CreateAutomation(_ context.Context, in *controlmodel.Automation) (*controlmodel.Automation, error) {
	if in == nil || in.Name == "" || in.TriggerType == "" || in.ActionType == "" {
		return nil, store.ErrConflict
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for _, candidate := range r.s.automations {
		if candidate.Tenant == in.Tenant && candidate.Namespace == in.Namespace && candidate.Name == in.Name && candidate.ArchivedAt == nil {
			return nil, store.ErrConflict
		}
	}
	out := cloneAutomation(in)
	out.ID = nonNilUUID(out.ID)
	now := time.Now().UTC()
	out.Version, out.CreatedAt, out.UpdatedAt = 1, now, now
	r.s.automations[out.ID] = cloneAutomation(out)
	r.enqueueEventLocked(out.Tenant, "automation", out.ID, "automation.created.v1", out, "automation-created:"+out.ID.String())
	return out, nil
}

func (r *collaborationRepo) GetAutomation(_ context.Context, id uuid.UUID) (*controlmodel.Automation, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	item := r.s.automations[id]
	if item == nil {
		return nil, store.ErrNotFound
	}
	return cloneAutomation(item), nil
}

func (r *collaborationRepo) ListAutomations(ctx context.Context, filter store.AutomationFilter) ([]*controlmodel.Automation, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*controlmodel.Automation, 0)
	for _, item := range r.s.automations {
		if access := store.WorkAccessFrom(ctx); access.Restricted && (item.CreatedBy.Type != controlmodel.ActorHuman || !slices.Contains(access.Refs, item.CreatedBy.Ref)) {
			continue
		}
		if item.ArchivedAt != nil || filter.Tenant != "" && item.Tenant != filter.Tenant || filter.Namespace != "" && item.Namespace != filter.Namespace || filter.Enabled != nil && item.Enabled != *filter.Enabled || filter.DueBefore != nil && (item.NextRunAt == nil || item.NextRunAt.After(*filter.DueBefore)) {
			continue
		}
		out = append(out, cloneAutomation(item))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.Before(out[j].CreatedAt) })
	return page(out, filter.Offset, filter.Limit), nil
}

func (r *collaborationRepo) UpdateAutomation(_ context.Context, in *controlmodel.Automation, expectedVersion int64) (*controlmodel.Automation, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	current := r.s.automations[in.ID]
	if current == nil {
		return nil, store.ErrNotFound
	}
	if expectedVersion > 0 && current.Version != expectedVersion {
		return nil, store.ErrConflict
	}
	next := cloneAutomation(in)
	store.MergeAutomationScheduleOnEdit(current, next)
	next.Tenant, next.Namespace, next.CreatedAt, next.CreatedBy = current.Tenant, current.Namespace, current.CreatedAt, current.CreatedBy
	next.Version, next.UpdatedAt = current.Version+1, time.Now().UTC()
	r.s.automations[next.ID] = cloneAutomation(next)
	r.enqueueEventLocked(next.Tenant, "automation", next.ID, "automation.updated.v1", next, "automation-updated:"+next.ID.String()+":"+time.Now().UTC().Format(time.RFC3339Nano))
	return next, nil
}

func (r *collaborationRepo) ArchiveAutomation(_ context.Context, id uuid.UUID, expectedVersion int64) (*controlmodel.Automation, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	current := r.s.automations[id]
	if current == nil {
		return nil, store.ErrNotFound
	}
	if expectedVersion > 0 && current.Version != expectedVersion {
		return nil, store.ErrConflict
	}
	now := time.Now().UTC()
	current.ArchivedAt = &now
	current.Enabled = false
	current.NextRunAt = nil
	current.Version++
	current.UpdatedAt = now
	return cloneAutomation(current), nil
}

func (r *collaborationRepo) BeginAutomationRun(_ context.Context, in *controlmodel.AutomationRun) (*controlmodel.AutomationRun, bool, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if r.s.automations[in.AutomationID] == nil {
		return nil, false, store.ErrNotFound
	}
	for _, run := range r.s.automationRuns {
		if run.AutomationID == in.AutomationID && run.IdempotencyKey == in.IdempotencyKey {
			return cloneAutomationRun(run), false, nil
		}
	}
	out := cloneAutomationRun(in)
	out.ID = nonNilUUID(out.ID)
	out.Status = controlmodel.AutomationRunRunning
	out.CreatedAt = time.Now().UTC()
	r.s.automationRuns[out.ID] = cloneAutomationRun(out)
	return out, true, nil
}

func (r *collaborationRepo) FinishAutomationRun(_ context.Context, in *controlmodel.AutomationRun) (*controlmodel.AutomationRun, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	current := r.s.automationRuns[in.ID]
	if current == nil {
		return nil, store.ErrNotFound
	}
	if controlmodel.IsAutomationRunTerminal(current.Status) || (in.Version > 0 && current.Version != in.Version) {
		return nil, store.ErrConflict
	}
	out := cloneAutomationRun(in)
	now := time.Now().UTC()
	out.UpdatedAt = now
	out.Version = current.Version + 1
	if controlmodel.IsAutomationRunTerminal(out.Status) {
		out.CompletedAt = &now
	} else {
		out.CompletedAt = nil
	}
	out.LeaseUntil = nil
	out.LeaseToken = uuid.Nil
	r.s.automationRuns[out.ID] = cloneAutomationRun(out)
	if item := store.AutomationFailureInbox(out); item != nil {
		r.s.inboxItems[item.ID] = item
	}
	if controlmodel.IsAutomationRunTerminal(out.Status) {
		r.enqueueEventLocked(out.Tenant, "automation-run", out.ID, "automation-run."+string(out.Status)+".v1", out, "automation-run-finished:"+out.ID.String())
	}
	return out, nil
}

func (r *collaborationRepo) ListAutomationRuns(_ context.Context, automationID uuid.UUID, limit, offset int) ([]*controlmodel.AutomationRun, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*controlmodel.AutomationRun, 0)
	for _, run := range r.s.automationRuns {
		if run.AutomationID == automationID {
			out = append(out, cloneAutomationRun(run))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.After(out[j].CreatedAt) })
	return page(out, offset, limit), nil
}

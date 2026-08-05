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
	"encoding/json"
	"strings"
	"time"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type dpTaskRepo struct{ s *Store }

func dpTaskKey(tenant, parentAgentID, parentSessionID, taskID string) string {
	return tenant + "\x00" + parentAgentID + "\x00" + parentSessionID + "\x00" + taskID
}

func cloneDPTask(t *store.DPTask) *store.DPTask {
	if t == nil {
		return nil
	}
	cp := *t
	if t.RemoteHeaders != nil {
		cp.RemoteHeaders = append(json.RawMessage(nil), t.RemoteHeaders...)
	}
	if t.LastCheckedAt != nil {
		v := *t.LastCheckedAt
		cp.LastCheckedAt = &v
	}
	if t.DeliveredAt != nil {
		v := *t.DeliveredAt
		cp.DeliveredAt = &v
	}
	return &cp
}

func (r *dpTaskRepo) Upsert(_ context.Context, task *store.DPTask) (*store.DPTask, error) {
	if task == nil || strings.TrimSpace(task.TaskID) == "" {
		return nil, store.ErrNotFound
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	now := time.Now().UTC()
	k := dpTaskKey(task.Tenant, task.ParentAgentID, task.ParentSessionID, task.TaskID)
	if existing, ok := r.s.dpTasks[k]; ok {
		task.Version = existing.Version + 1
		if task.CreatedAt.IsZero() {
			task.CreatedAt = existing.CreatedAt
		}
	} else {
		if task.Version == 0 {
			task.Version = 1
		}
		if task.CreatedAt.IsZero() {
			task.CreatedAt = now
		}
	}
	if task.Status == "" {
		task.Status = store.DPTaskStatusPending
	}
	task.Terminal = store.IsTerminalTaskStatus(task.Status)
	task.LastUpdatedAt = now
	cp := cloneDPTask(task)
	r.s.dpTasks[k] = cp
	return cloneDPTask(cp), nil
}

func (r *dpTaskRepo) Get(_ context.Context, tenant, parentAgentID, parentSessionID, taskID string) (*store.DPTask, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	t, ok := r.s.dpTasks[dpTaskKey(tenant, parentAgentID, parentSessionID, taskID)]
	if !ok {
		return nil, store.ErrNotFound
	}
	return cloneDPTask(t), nil
}

func (r *dpTaskRepo) List(_ context.Context, tenant, parentAgentID, parentSessionID, status string) ([]*store.DPTask, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var out []*store.DPTask
	for _, t := range r.s.dpTasks {
		if t.Tenant != tenant || t.ParentAgentID != parentAgentID || t.ParentSessionID != parentSessionID {
			continue
		}
		if status != "" && t.Status != status {
			continue
		}
		out = append(out, cloneDPTask(t))
	}
	if out == nil {
		out = []*store.DPTask{}
	}
	return out, nil
}

func (r *dpTaskRepo) Heartbeat(_ context.Context, tenant, parentAgentID string, refs []store.DPTaskRef) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	now := time.Now().UTC()
	for _, ref := range refs {
		k := dpTaskKey(tenant, parentAgentID, ref.ParentSessionID, ref.TaskID)
		t, ok := r.s.dpTasks[k]
		if !ok || t.Terminal {
			continue
		}
		t.LastUpdatedAt = now
	}
	return nil
}

func (r *dpTaskRepo) RequestCancel(_ context.Context, tenant, parentAgentID, parentSessionID, taskID string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	t, ok := r.s.dpTasks[dpTaskKey(tenant, parentAgentID, parentSessionID, taskID)]
	if !ok {
		return store.ErrNotFound
	}
	t.CancelRequested = true
	t.LastUpdatedAt = time.Now().UTC()
	return nil
}

func (r *dpTaskRepo) MarkDelivered(_ context.Context, tenant, parentAgentID, parentSessionID, taskID string) (bool, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	t, ok := r.s.dpTasks[dpTaskKey(tenant, parentAgentID, parentSessionID, taskID)]
	if !ok {
		return false, store.ErrNotFound
	}
	if t.DeliveredAt != nil {
		return false, nil
	}
	now := time.Now().UTC()
	t.DeliveredAt = &now
	return true, nil
}

func (r *dpTaskRepo) ListPendingDeliveries(_ context.Context, tenant, parentAgentID, parentSessionID string) ([]*store.DPTask, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var out []*store.DPTask
	for _, t := range r.s.dpTasks {
		if t.Tenant != tenant || t.ParentAgentID != parentAgentID || t.ParentSessionID != parentSessionID {
			continue
		}
		if !t.Terminal || t.DeliveredAt != nil {
			continue
		}
		out = append(out, cloneDPTask(t))
	}
	if out == nil {
		out = []*store.DPTask{}
	}
	return out, nil
}

func (r *dpTaskRepo) Delete(_ context.Context, tenant, parentAgentID, parentSessionID, taskID string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	k := dpTaskKey(tenant, parentAgentID, parentSessionID, taskID)
	if _, ok := r.s.dpTasks[k]; !ok {
		return store.ErrNotFound
	}
	delete(r.s.dpTasks, k)
	return nil
}

func (r *dpTaskRepo) SweepOrphaned(_ context.Context, orphanTimeout time.Duration, errMsg string) ([]*store.DPTask, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	cut := time.Now().UTC().Add(-orphanTimeout)
	var out []*store.DPTask
	for _, t := range r.s.dpTasks {
		if t.Terminal {
			continue
		}
		if strings.EqualFold(t.TransportType, "agent-protocol") {
			continue
		}
		if !t.LastUpdatedAt.Before(cut) {
			continue
		}
		t.Status = store.DPTaskStatusFailed
		t.Terminal = true
		t.ErrorMessage = errMsg
		t.LastUpdatedAt = time.Now().UTC()
		t.Version++
		out = append(out, cloneDPTask(t))
	}
	if out == nil {
		out = []*store.DPTask{}
	}
	return out, nil
}

func (r *dpTaskRepo) PurgeTerminalOlderThan(_ context.Context, olderThan time.Duration) (int64, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	cut := time.Now().UTC().Add(-olderThan)
	var n int64
	for k, t := range r.s.dpTasks {
		if !t.Terminal {
			continue
		}
		if t.LastUpdatedAt.Before(cut) {
			delete(r.s.dpTasks, k)
			n++
		}
	}
	return n, nil
}

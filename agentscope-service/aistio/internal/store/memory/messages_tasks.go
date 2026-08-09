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
	"fmt"
	"time"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type messageRepo struct{ s *Store }

func (r *messageRepo) Send(_ context.Context, msg *store.TeamMessage) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if msg.Kind == "" {
		msg.Kind = "message"
	}
	if msg.CreatedAt.IsZero() {
		msg.CreatedAt = time.Now().UTC()
	}
	msg.ID = nextID(&r.s.nextMsgID)
	r.s.messages = append(r.s.messages, *msg)
	return nil
}

func (r *messageRepo) ListPending(_ context.Context, teamName, namespace string) ([]*store.TeamMessage, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var out []*store.TeamMessage
	for i := range r.s.messages {
		m := r.s.messages[i]
		if m.TeamName == teamName && m.Namespace == namespace && !m.Delivered {
			cp := m
			out = append(out, &cp)
		}
	}
	return out, nil
}

func (r *messageRepo) ListPendingAll(_ context.Context, limit int) ([]*store.TeamMessage, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if limit <= 0 {
		limit = 100
	}
	var out []*store.TeamMessage
	for i := range r.s.messages {
		m := r.s.messages[i]
		if !m.Delivered {
			cp := m
			out = append(out, &cp)
			if len(out) >= limit {
				break
			}
		}
	}
	return out, nil
}

func (r *messageRepo) MarkDelivered(_ context.Context, id int64) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for i := range r.s.messages {
		if r.s.messages[i].ID == id {
			now := time.Now().UTC()
			r.s.messages[i].Delivered = true
			r.s.messages[i].DeliveredAt = &now
			r.s.messages[i].Attempts++
			return nil
		}
	}
	return store.ErrNotFound
}

func (r *messageRepo) IncrementAttempts(_ context.Context, id int64) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for i := range r.s.messages {
		if r.s.messages[i].ID == id {
			r.s.messages[i].Attempts++
			return nil
		}
	}
	return store.ErrNotFound
}

func (r *messageRepo) History(_ context.Context, teamName, namespace string, limit int) ([]*store.TeamMessage, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	if limit <= 0 {
		limit = 50
	}
	var out []*store.TeamMessage
	for i := len(r.s.messages) - 1; i >= 0; i-- {
		m := r.s.messages[i]
		if m.TeamName == teamName && m.Namespace == namespace {
			cp := m
			out = append(out, &cp)
			if len(out) >= limit {
				break
			}
		}
	}
	return out, nil
}

func (r *messageRepo) DeleteByTeam(_ context.Context, teamName, namespace string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	kept := r.s.messages[:0]
	for _, m := range r.s.messages {
		if m.TeamName == teamName && m.Namespace == namespace {
			continue
		}
		kept = append(kept, m)
	}
	r.s.messages = kept
	return nil
}

type taskRepo struct{ s *Store }

func (r *taskRepo) Create(_ context.Context, namespace, teamName, subject, description string, blockedBy []string, owner string) (*store.TeamTask, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	var maxSeq int64
	for _, t := range r.s.tasks {
		if t.Namespace == namespace && t.TeamName == teamName {
			var n int64
			if _, err := fmt.Sscanf(t.TaskID, "task-%d", &n); err == nil && n > maxSeq {
				maxSeq = n
			}
		}
	}
	now := time.Now().UTC()
	var blockedJSON []byte
	if len(blockedBy) > 0 {
		blockedJSON, _ = json.Marshal(blockedBy)
	}
	t := store.TeamTask{
		ID:          nextID(&r.s.nextTaskID),
		TaskID:      fmt.Sprintf("task-%d", maxSeq+1),
		TeamName:    teamName,
		Namespace:   namespace,
		Subject:     subject,
		Description: description,
		State:       store.TaskStatePending,
		Owner:       owner,
		BlockedBy:   blockedJSON,
		Version:     1,
		CreatedAt:   now,
		UpdatedAt:   now,
	}
	r.s.tasks = append(r.s.tasks, t)
	r.s.history = append(r.s.history, store.TeamTaskHistory{
		ID: nextID(&r.s.nextHistID), TaskFK: t.ID, TeamName: teamName, Namespace: namespace,
		FromState: "", ToState: store.TaskStatePending, Owner: owner,
		TransitionedAt: now,
	})
	cp := t
	return &cp, nil
}

func (r *taskRepo) Get(_ context.Context, namespace, teamName, taskID string) (*store.TeamTask, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	for i := range r.s.tasks {
		t := r.s.tasks[i]
		if t.Namespace == namespace && t.TeamName == teamName && t.TaskID == taskID {
			cp := t
			return &cp, nil
		}
	}
	return nil, store.ErrNotFound
}

func (r *taskRepo) List(_ context.Context, namespace, teamName string) ([]*store.TeamTask, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var out []*store.TeamTask
	for i := range r.s.tasks {
		t := r.s.tasks[i]
		if t.Namespace == namespace && t.TeamName == teamName {
			cp := t
			out = append(out, &cp)
		}
	}
	return out, nil
}

func (r *taskRepo) Assign(_ context.Context, namespace, teamName, taskID, owner string, expectedVersion int64) (*store.TeamTask, error) {
	if owner == "" {
		return nil, fmt.Errorf("memory tasks assign: owner required")
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for i := range r.s.tasks {
		t := &r.s.tasks[i]
		if t.Namespace != namespace || t.TeamName != teamName || t.TaskID != taskID {
			continue
		}
		if t.Version != expectedVersion || t.State != store.TaskStatePending {
			return nil, store.ErrConflict
		}
		now := time.Now().UTC()
		t.Owner = owner
		t.Version++
		t.UpdatedAt = now
		r.s.history = append(r.s.history, store.TeamTaskHistory{
			ID: nextID(&r.s.nextHistID), TaskFK: t.ID, TeamName: teamName, Namespace: namespace,
			FromState: store.TaskStatePending, ToState: store.TaskStatePending, Owner: owner,
			TransitionedAt: now,
		})
		cp := *t
		return &cp, nil
	}
	return nil, store.ErrNotFound
}

func (r *taskRepo) Claim(_ context.Context, namespace, teamName, taskID, claimedBy string, expectedVersion int64) (*store.TeamTask, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for i := range r.s.tasks {
		t := &r.s.tasks[i]
		if t.Namespace != namespace || t.TeamName != teamName || t.TaskID != taskID {
			continue
		}
		// Idempotent: already claimed by this member.
		if t.State == store.TaskStateInProgress && t.Owner == claimedBy {
			cp := *t
			return &cp, nil
		}
		version := expectedVersion
		if version <= 0 {
			version = t.Version
		}
		if t.Version != version || t.State != store.TaskStatePending {
			return nil, store.ErrConflict
		}
		if t.Owner != "" && t.Owner != claimedBy {
			return nil, store.ErrConflict
		}
		if isTaskBlockedLocked(r.s.tasks, namespace, teamName, t) {
			return nil, store.ErrConflict
		}
		now := time.Now().UTC()
		t.State = store.TaskStateInProgress
		t.Owner = claimedBy
		t.Version++
		t.UpdatedAt = now
		r.s.history = append(r.s.history, store.TeamTaskHistory{
			ID: nextID(&r.s.nextHistID), TaskFK: t.ID, TeamName: teamName, Namespace: namespace,
			FromState: store.TaskStatePending, ToState: store.TaskStateInProgress, Owner: claimedBy,
			TransitionedAt: now,
		})
		cp := *t
		return &cp, nil
	}
	return nil, store.ErrNotFound
}

func (r *taskRepo) Complete(_ context.Context, namespace, teamName, taskID, result string) (*store.TeamTask, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for i := range r.s.tasks {
		t := &r.s.tasks[i]
		if t.Namespace != namespace || t.TeamName != teamName || t.TaskID != taskID {
			continue
		}
		if t.State != store.TaskStateInProgress {
			return nil, store.ErrNotFound
		}
		now := time.Now().UTC()
		t.State = store.TaskStateCompleted
		t.Result = result
		t.Version++
		t.UpdatedAt = now
		t.CompletedAt = &now
		cp := *t
		return &cp, nil
	}
	return nil, store.ErrNotFound
}

func (r *taskRepo) Fail(_ context.Context, namespace, teamName, taskID, reason string) (*store.TeamTask, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for i := range r.s.tasks {
		t := &r.s.tasks[i]
		if t.Namespace != namespace || t.TeamName != teamName || t.TaskID != taskID {
			continue
		}
		if store.IsTaskTerminal(t.State) {
			return nil, store.ErrConflict
		}
		now := time.Now().UTC()
		from := t.State
		t.State = store.TaskStateFailed
		t.Result = reason
		t.Version++
		t.UpdatedAt = now
		t.CompletedAt = &now
		r.s.history = append(r.s.history, store.TeamTaskHistory{
			ID: nextID(&r.s.nextHistID), TaskFK: t.ID, TeamName: teamName, Namespace: namespace,
			FromState: from, ToState: store.TaskStateFailed, Owner: t.Owner,
			TransitionedAt: now,
		})
		cp := *t
		return &cp, nil
	}
	return nil, store.ErrNotFound
}

func (r *taskRepo) Unclaim(_ context.Context, namespace, teamName, taskID string) (*store.TeamTask, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for i := range r.s.tasks {
		t := &r.s.tasks[i]
		if t.Namespace != namespace || t.TeamName != teamName || t.TaskID != taskID {
			continue
		}
		if t.State != store.TaskStateInProgress {
			return nil, store.ErrNotFound
		}
		now := time.Now().UTC()
		t.State = store.TaskStatePending
		t.Owner = ""
		t.Version++
		t.UpdatedAt = now
		r.s.history = append(r.s.history, store.TeamTaskHistory{
			ID: nextID(&r.s.nextHistID), TaskFK: t.ID, TeamName: teamName, Namespace: namespace,
			FromState: store.TaskStateInProgress, ToState: store.TaskStatePending, Owner: "",
			TransitionedAt: now,
		})
		cp := *t
		return &cp, nil
	}
	return nil, store.ErrNotFound
}

func (r *taskRepo) GetUnblockedPending(ctx context.Context, namespace, teamName string) ([]*store.TeamTask, error) {
	tasks, err := r.List(ctx, namespace, teamName)
	if err != nil {
		return nil, err
	}
	completed := map[string]bool{}
	for _, t := range tasks {
		if t.State == store.TaskStateCompleted {
			completed[t.TaskID] = true
		}
	}
	var out []*store.TeamTask
	for _, t := range tasks {
		if t.State != store.TaskStatePending || t.Owner != "" {
			continue
		}
		var blocked []string
		if len(t.BlockedBy) > 0 {
			_ = json.Unmarshal(t.BlockedBy, &blocked)
		}
		ok := true
		for _, b := range blocked {
			if !completed[b] {
				ok = false
				break
			}
		}
		if ok {
			out = append(out, t)
		}
	}
	return out, nil
}

func isTaskBlockedLocked(tasks []store.TeamTask, namespace, teamName string, task *store.TeamTask) bool {
	var blocked []string
	if len(task.BlockedBy) > 0 {
		_ = json.Unmarshal(task.BlockedBy, &blocked)
	}
	if len(blocked) == 0 {
		return false
	}
	completed := map[string]bool{}
	for _, t := range tasks {
		if t.Namespace == namespace && t.TeamName == teamName && t.State == store.TaskStateCompleted {
			completed[t.TaskID] = true
		}
	}
	for _, b := range blocked {
		if !completed[b] {
			return true
		}
	}
	return false
}

func (r *taskRepo) GetSummary(_ context.Context, namespace, teamName string) (total, pending, inProgress, completed int32, err error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	for _, t := range r.s.tasks {
		if t.Namespace != namespace || t.TeamName != teamName {
			continue
		}
		total++
		switch t.State {
		case store.TaskStatePending:
			pending++
		case store.TaskStateInProgress:
			inProgress++
		case store.TaskStateCompleted:
			completed++
		}
	}
	return
}

func (r *taskRepo) DeleteByTeam(_ context.Context, namespace, teamName string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	kept := r.s.tasks[:0]
	for _, t := range r.s.tasks {
		if t.Namespace == namespace && t.TeamName == teamName {
			continue
		}
		kept = append(kept, t)
	}
	r.s.tasks = kept
	return nil
}

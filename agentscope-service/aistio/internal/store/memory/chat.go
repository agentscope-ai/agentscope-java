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
	"sort"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type chatRepo struct{ s *Store }

func cloneChat(in *controlmodel.Chat) *controlmodel.Chat {
	if in == nil {
		return nil
	}
	out := *in
	return &out
}

func (r *chatRepo) Create(_ context.Context, in *controlmodel.Chat) (*controlmodel.Chat, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	if _, exists := r.s.chats[in.ID]; exists {
		return nil, store.ErrConflict
	}
	now := time.Now().UTC()
	value := cloneChat(in)
	if value.Status == "" {
		value.Status = controlmodel.ChatActive
	}
	value.Version = 1
	value.CreatedAt, value.UpdatedAt = now, now
	r.s.chats[value.ID] = value
	return cloneChat(value), nil
}

func (r *chatRepo) Get(_ context.Context, id uuid.UUID) (*controlmodel.Chat, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	value, ok := r.s.chats[id]
	if !ok {
		return nil, store.ErrNotFound
	}
	return cloneChat(value), nil
}

func (r *chatRepo) List(_ context.Context, filter store.ChatFilter) ([]*controlmodel.Chat, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*controlmodel.Chat, 0)
	status := controlmodel.ChatActive
	if filter.Archived {
		status = controlmodel.ChatArchived
	}
	if filter.Deleted {
		status = controlmodel.ChatDeleted
	}
	for _, value := range r.s.chats {
		if filter.Tenant != "" && value.Tenant != filter.Tenant ||
			filter.Namespace != "" && value.Namespace != filter.Namespace ||
			filter.CreatorRef != "" && value.CreatorRef != filter.CreatorRef ||
			value.Status != status {
			continue
		}
		out = append(out, cloneChat(value))
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Pinned != out[j].Pinned {
			return out[i].Pinned
		}
		return out[i].UpdatedAt.After(out[j].UpdatedAt)
	})
	if filter.Offset >= len(out) {
		return nil, nil
	}
	if filter.Offset > 0 {
		out = out[filter.Offset:]
	}
	if filter.Limit > 0 && len(out) > filter.Limit {
		out = out[:filter.Limit]
	}
	return out, nil
}

func (r *chatRepo) Update(_ context.Context, in *controlmodel.Chat, expectedVersion int64) (*controlmodel.Chat, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	current, ok := r.s.chats[in.ID]
	if !ok {
		return nil, store.ErrNotFound
	}
	if current.Version != expectedVersion {
		return nil, store.ErrConflict
	}
	value := cloneChat(in)
	value.CreatedAt = current.CreatedAt
	value.Version = current.Version + 1
	value.UpdatedAt = time.Now().UTC()
	r.s.chats[value.ID] = value
	return cloneChat(value), nil
}

func (r *chatRepo) Touch(_ context.Context, id uuid.UUID) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	value, ok := r.s.chats[id]
	if !ok {
		return store.ErrNotFound
	}
	value.UpdatedAt = time.Now().UTC()
	return nil
}

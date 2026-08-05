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
	"time"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type snapshotRepo struct{ s *Store }

func snapKey(tenant, snapshotID string) string {
	return tenant + "\x00" + snapshotID
}

func (r *snapshotRepo) Put(_ context.Context, tenant, snapshotID string, payload []byte, mode string) (*store.SnapshotMeta, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	now := time.Now().UTC()
	if mode == "" {
		mode = store.SnapshotModeInline
	}
	meta := store.SnapshotMeta{
		SnapshotID:  snapshotID,
		SizeBytes:   int64(len(payload)),
		StorageMode: mode,
		CreatedAt:   now,
		AccessedAt:  now,
	}
	r.s.dpSnapshots[snapKey(tenant, snapshotID)] = &memSnapshot{
		meta:    meta,
		payload: append([]byte(nil), payload...),
	}
	cp := meta
	return &cp, nil
}

func (r *snapshotRepo) Get(_ context.Context, tenant, snapshotID string) ([]byte, *store.SnapshotMeta, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	snap, ok := r.s.dpSnapshots[snapKey(tenant, snapshotID)]
	if !ok {
		return nil, nil, store.ErrNotFound
	}
	now := time.Now().UTC()
	snap.meta.AccessedAt = now
	meta := snap.meta
	return append([]byte(nil), snap.payload...), &meta, nil
}

func (r *snapshotRepo) Exists(_ context.Context, tenant, snapshotID string) (bool, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	_, ok := r.s.dpSnapshots[snapKey(tenant, snapshotID)]
	return ok, nil
}

func (r *snapshotRepo) Touch(_ context.Context, tenant, snapshotID string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	snap, ok := r.s.dpSnapshots[snapKey(tenant, snapshotID)]
	if !ok {
		return store.ErrNotFound
	}
	snap.meta.AccessedAt = time.Now().UTC()
	return nil
}

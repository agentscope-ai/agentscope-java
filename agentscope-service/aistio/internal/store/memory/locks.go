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

type lockRepo struct{ s *Store }

func lockKey(tenant, name string) string {
	return tenant + "\x00" + name
}

func (r *lockRepo) Acquire(_ context.Context, tenant, name, ownerToken, holder string, ttl time.Duration) (*store.Lock, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	now := time.Now().UTC()
	k := lockKey(tenant, name)
	if cur, ok := r.s.locks[k]; ok && cur.ExpiresAt.After(now) {
		cp := *cur
		return &cp, store.ErrConflict
	}
	r.s.nextFencing++
	lk := &store.Lock{
		Name:         name,
		OwnerToken:   ownerToken,
		FencingToken: r.s.nextFencing,
		Holder:       holder,
		ExpiresAt:    now.Add(ttl),
	}
	r.s.locks[k] = lk
	cp := *lk
	return &cp, nil
}

func (r *lockRepo) Renew(_ context.Context, tenant, name, ownerToken string, ttl time.Duration) (*store.Lock, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	k := lockKey(tenant, name)
	cur, ok := r.s.locks[k]
	if !ok || cur.OwnerToken != ownerToken {
		return nil, store.ErrConflict
	}
	cur.ExpiresAt = time.Now().UTC().Add(ttl)
	cp := *cur
	return &cp, nil
}

func (r *lockRepo) Release(_ context.Context, tenant, name, ownerToken string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	k := lockKey(tenant, name)
	cur, ok := r.s.locks[k]
	if !ok || cur.OwnerToken != ownerToken {
		return nil // idempotent
	}
	delete(r.s.locks, k)
	return nil
}

func (r *lockRepo) Peek(_ context.Context, tenant, name string) (*store.Lock, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	cur, ok := r.s.locks[lockKey(tenant, name)]
	if !ok || !cur.ExpiresAt.After(time.Now().UTC()) {
		return nil, store.ErrNotFound
	}
	cp := *cur
	return &cp, nil
}

func (r *lockRepo) PurgeExpired(_ context.Context, olderThan time.Duration) (int64, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	cut := time.Now().UTC().Add(-olderThan)
	var n int64
	for k, lk := range r.s.locks {
		if lk.ExpiresAt.Before(cut) {
			delete(r.s.locks, k)
			n++
		}
	}
	return n, nil
}

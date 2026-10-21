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

package policychanges

import (
	"context"
	"fmt"
	"sort"
	"sync"
	"time"

	"github.com/google/uuid"
)

type Memory struct {
	mu   sync.Mutex
	rows map[uuid.UUID]*memoryRow
}
type memoryRow struct {
	change    Change
	until     time.Time
	delivered bool
}

func (r *Memory) Record(tenant, namespace, application string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.rows == nil {
		r.rows = map[uuid.UUID]*memoryRow{}
	}
	v := Change{ID: uuid.New(), Tenant: tenant, Namespace: namespace, ApplicationID: application, CreatedAt: time.Now().UTC()}
	r.rows[v.ID] = &memoryRow{change: v}
}
func (r *Memory) Claim(_ context.Context, now time.Time, ttl time.Duration, limit int) ([]Change, error) {
	if limit < 1 || limit > 100 || ttl <= 0 || ttl > time.Minute {
		return nil, fmt.Errorf("invalid policy change claim limits")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	candidates := []*memoryRow{}
	for _, v := range r.rows {
		if !v.delivered && !v.until.After(now) {
			candidates = append(candidates, v)
		}
	}
	sort.Slice(candidates, func(i, j int) bool { return candidates[i].change.CreatedAt.Before(candidates[j].change.CreatedAt) })
	if len(candidates) > limit {
		candidates = candidates[:limit]
	}
	token := uuid.NewString()
	out := []Change{}
	for _, v := range candidates {
		v.change.ClaimToken = token
		v.until = now.Add(ttl)
		out = append(out, v.change)
	}
	return out, nil
}
func (r *Memory) Acknowledge(_ context.Context, id uuid.UUID, token string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	v := r.rows[id]
	if v == nil || v.change.ClaimToken != token || (!v.delivered && !v.until.After(time.Now())) {
		return ErrClaim
	}
	v.delivered = true
	return nil
}

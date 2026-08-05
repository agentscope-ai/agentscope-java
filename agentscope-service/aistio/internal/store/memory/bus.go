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
	"strconv"
	"time"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type busRepo struct{ s *Store }

func (r *busRepo) QueuePush(_ context.Context, tenant, key string, payload json.RawMessage) (string, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	r.s.nextBusID++
	id := r.s.nextBusID
	r.s.busEntries = append(r.s.busEntries, memBusEntry{
		id:      id,
		tenant:  tenant,
		key:     key,
		kind:    store.BusKindQueue,
		payload: append([]byte(nil), payload...),
		created: time.Now().UTC(),
	})
	return strconv.FormatInt(id, 10), nil
}

func (r *busRepo) QueueDrain(_ context.Context, tenant, key string, maxCount int) ([]*store.BusEntry, error) {
	if maxCount <= 0 {
		maxCount = 1
	}
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	var out []*store.BusEntry
	kept := r.s.busEntries[:0]
	for _, e := range r.s.busEntries {
		if e.tenant == tenant && e.key == key && e.kind == store.BusKindQueue && len(out) < maxCount {
			out = append(out, &store.BusEntry{
				EntryID: strconv.FormatInt(e.id, 10),
				Payload: append(json.RawMessage(nil), e.payload...),
			})
			continue
		}
		kept = append(kept, e)
	}
	r.s.busEntries = kept
	return out, nil
}

func (r *busRepo) QueueDelete(_ context.Context, tenant, key string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	kept := r.s.busEntries[:0]
	for _, e := range r.s.busEntries {
		if e.tenant == tenant && e.key == key && e.kind == store.BusKindQueue {
			continue
		}
		kept = append(kept, e)
	}
	r.s.busEntries = kept
	return nil
}

func (r *busRepo) QueuePeek(_ context.Context, tenant, key string) (bool, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	for _, e := range r.s.busEntries {
		if e.tenant == tenant && e.key == key && e.kind == store.BusKindQueue {
			return true, nil
		}
	}
	return false, nil
}

func (r *busRepo) LogAppend(_ context.Context, tenant, key string, payload json.RawMessage, maxLen int) (string, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	r.s.nextBusID++
	id := r.s.nextBusID
	r.s.busEntries = append(r.s.busEntries, memBusEntry{
		id:      id,
		tenant:  tenant,
		key:     key,
		kind:    store.BusKindLog,
		payload: append([]byte(nil), payload...),
		created: time.Now().UTC(),
	})
	if maxLen > 0 {
		var count int
		for i := len(r.s.busEntries) - 1; i >= 0; i-- {
			e := r.s.busEntries[i]
			if e.tenant == tenant && e.key == key && e.kind == store.BusKindLog {
				count++
			}
		}
		for count > maxLen {
			for i, e := range r.s.busEntries {
				if e.tenant == tenant && e.key == key && e.kind == store.BusKindLog {
					r.s.busEntries = append(r.s.busEntries[:i], r.s.busEntries[i+1:]...)
					count--
					break
				}
			}
		}
	}
	return strconv.FormatInt(id, 10), nil
}

func (r *busRepo) LogRead(_ context.Context, tenant, key, since string, maxCount int) ([]*store.BusEntry, error) {
	if maxCount <= 0 {
		maxCount = 100
	}
	var sinceID int64
	if since != "" {
		var err error
		sinceID, err = strconv.ParseInt(since, 10, 64)
		if err != nil {
			sinceID = 0
		}
	}
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var out []*store.BusEntry
	for _, e := range r.s.busEntries {
		if e.tenant != tenant || e.key != key || e.kind != store.BusKindLog {
			continue
		}
		if e.id <= sinceID {
			continue
		}
		out = append(out, &store.BusEntry{
			EntryID: strconv.FormatInt(e.id, 10),
			Payload: append(json.RawMessage(nil), e.payload...),
		})
		if len(out) >= maxCount {
			break
		}
	}
	return out, nil
}

func (r *busRepo) LogTrim(_ context.Context, tenant, key string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	kept := r.s.busEntries[:0]
	for _, e := range r.s.busEntries {
		if e.tenant == tenant && e.key == key && e.kind == store.BusKindLog {
			continue
		}
		kept = append(kept, e)
	}
	r.s.busEntries = kept
	return nil
}

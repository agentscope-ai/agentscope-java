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
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func init() {
	store.RegisterOpener(store.DriverMemory, Open)
}

// Store is an in-memory store.Store used for local/dev and unit tests.
type Store struct {
	mu           sync.RWMutex
	sessionLocks *keyedMutex
	sessions     map[uuid.UUID]*store.Session
	sessKey      map[string]uuid.UUID // agent/ns/sessionID -> uuid
	snapshots    []store.SessionSnapshot
	events       []store.SessionEvent
	contexts     []store.ContextSnapshot
	tokens       []store.TokenUsageMetric
	agents       []store.AgentMetric
	messages     []store.TeamMessage
	tasks        []store.TeamTask
	history      []store.TeamTaskHistory
	commands     []store.SessionCommand
	turns        []store.SessionTurn
	transcriptIndex map[uuid.UUID]store.SessionTranscriptIndex
	teams        map[string]*store.Team // namespace/name
	teamMembers  []store.TeamMember

	// Hosted DistributedStore backends.
	kv          map[string]*store.KVItem          // tenant+\x00+nsPath+\x00+itemKey
	locks       map[string]*store.Lock            // tenant+\x00+lockName
	dpSnapshots map[string]*memSnapshot           // tenant+\x00+snapshotID
	busEntries  []memBusEntry
	asyncTools  map[string]*store.AsyncToolRecord // tenant+\x00+recordID
	dpTasks     map[string]*store.DPTask          // tenant+\x00+parentAgent+\x00+session+\x00+taskID
	nextBusID   int64
	nextFencing int64

	nextSnapID int64
	nextEvtID  int64
	nextCtxID  int64
	nextTokID  int64
	nextAgID   int64
	nextMsgID  int64
	nextTaskID int64
	nextHistID int64
	nextTeamID int64
	nextMemberID int64

	retention store.RetentionConfig
}

type memSnapshot struct {
	meta    store.SnapshotMeta
	payload []byte
}

type memBusEntry struct {
	id      int64
	tenant  string
	key     string
	kind    int16
	payload []byte
	created time.Time
}

// Open creates a memory store.
func Open(_ context.Context, cfg store.Config) (store.Store, error) {
	s := &Store{
		sessions:     make(map[uuid.UUID]*store.Session),
		sessKey:      make(map[string]uuid.UUID),
		sessionLocks: newKeyedMutex(),
		teams:        make(map[string]*store.Team),
		kv:           make(map[string]*store.KVItem),
		locks:        make(map[string]*store.Lock),
		dpSnapshots:  make(map[string]*memSnapshot),
		asyncTools:   make(map[string]*store.AsyncToolRecord),
		dpTasks:      make(map[string]*store.DPTask),
		retention:    cfg.Retention,
	}
	return s, nil
}

func (s *Store) Sessions() store.SessionRepository                 { return &sessionRepo{s} }
func (s *Store) Turns() store.TurnRepository                       { return &turnRepo{s} }
func (s *Store) Events() store.EventRepository                     { return &eventRepo{s} }
func (s *Store) ContextSnapshots() store.ContextSnapshotRepository { return &contextRepo{s} }
func (s *Store) Metrics() store.MetricsRepository                  { return &metricsRepo{s} }
func (s *Store) TranscriptIndex() store.TranscriptIndexRepository  { return &transcriptIndexRepo{s} }
func (s *Store) TeamMessages() store.TeamMessageRepository         { return &messageRepo{s} }
func (s *Store) TeamTasks() store.TeamTaskRepository               { return &taskRepo{s} }
func (s *Store) Teams() store.TeamRepository                       { return &teamRepo{s} }
func (s *Store) Commands() store.SessionCommandRepository          { return &commandRepo{s} }
func (s *Store) KV() store.KVRepository                             { return &kvRepo{s} }
func (s *Store) Locks() store.LockRepository                       { return &lockRepo{s} }
func (s *Store) Snapshots() store.SnapshotRepository               { return &snapshotRepo{s} }
func (s *Store) Bus() store.BusRepository                           { return &busRepo{s} }
func (s *Store) AsyncTools() store.AsyncToolRepository             { return &asyncToolRepo{s} }
func (s *Store) Tasks() store.TaskRepository                       { return &dpTaskRepo{s} }

func (s *Store) Migrate(context.Context) error { return nil }
func (s *Store) Ping(context.Context) error    { return nil }
func (s *Store) Close() error                  { return nil }

// WithSessionLock serializes fn per sessionKey within this process.
func (s *Store) WithSessionLock(ctx context.Context, sessionKey string, fn func(context.Context) error) error {
	if fn == nil {
		return nil
	}
	if s.sessionLocks == nil {
		s.sessionLocks = newKeyedMutex()
	}
	unlock := s.sessionLocks.Lock(sessionKey)
	defer unlock()
	return fn(ctx)
}

func (s *Store) PurgeOlderThan(_ context.Context, r store.RetentionConfig) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now().UTC()
	var n int64
	if r.SessionEvents > 0 {
		cut := now.Add(-r.SessionEvents)
		kept := s.events[:0]
		for _, e := range s.events {
			if e.OccurredAt.Before(cut) {
				n++
				continue
			}
			kept = append(kept, e)
		}
		s.events = kept
	}
	if r.Snapshots > 0 {
		cut := now.Add(-r.Snapshots)
		kept := s.snapshots[:0]
		for _, e := range s.snapshots {
			if e.CapturedAt.Before(cut) {
				n++
				continue
			}
			kept = append(kept, e)
		}
		s.snapshots = kept
	}
	if r.ContextSnapshots > 0 {
		cut := now.Add(-r.ContextSnapshots)
		kept := s.contexts[:0]
		for _, e := range s.contexts {
			if e.CapturedAt.Before(cut) {
				n++
				continue
			}
			kept = append(kept, e)
		}
		s.contexts = kept
	}
	if r.Metrics > 0 {
		cut := now.Add(-r.Metrics)
		kept := s.tokens[:0]
		for _, e := range s.tokens {
			if e.RecordedAt.Before(cut) {
				n++
				continue
			}
			kept = append(kept, e)
		}
		s.tokens = kept
		keptA := s.agents[:0]
		for _, e := range s.agents {
			if e.RecordedAt.Before(cut) {
				n++
				continue
			}
			keptA = append(keptA, e)
		}
		s.agents = keptA
	}
	// Hosted store retention — dp_kv is NEVER purged.
	if r.BusQueue > 0 || r.BusLog > 0 {
		kept := s.busEntries[:0]
		for _, e := range s.busEntries {
			var cut time.Time
			switch e.kind {
			case store.BusKindQueue:
				if r.BusQueue > 0 {
					cut = now.Add(-r.BusQueue)
				}
			case store.BusKindLog:
				if r.BusLog > 0 {
					cut = now.Add(-r.BusLog)
				}
			}
			if !cut.IsZero() && e.created.Before(cut) {
				n++
				continue
			}
			kept = append(kept, e)
		}
		s.busEntries = kept
	}
	if r.AsyncTools > 0 {
		cut := now.Add(-r.AsyncTools)
		for k, rec := range s.asyncTools {
			if rec.UpdatedAt.Before(cut) {
				delete(s.asyncTools, k)
				n++
			}
		}
	}
	if r.SandboxSnapshots > 0 {
		cut := now.Add(-r.SandboxSnapshots)
		for k, snap := range s.dpSnapshots {
			if snap.meta.AccessedAt.Before(cut) {
				delete(s.dpSnapshots, k)
				n++
			}
		}
	}
	if r.Tasks > 0 {
		cut := now.Add(-r.Tasks)
		for k, t := range s.dpTasks {
			if t.Terminal && t.LastUpdatedAt.Before(cut) {
				delete(s.dpTasks, k)
				n++
			}
		}
	}
	// Drop expired locks that have been expired for more than 1h.
	for k, lk := range s.locks {
		if lk.ExpiresAt.Before(now.Add(-time.Hour)) {
			delete(s.locks, k)
			n++
		}
	}
	return n, nil
}

func sessCompositeKey(agent, ns, sid string) string {
	return agent + "\x00" + ns + "\x00" + sid
}

func cloneSession(s *store.Session) *store.Session {
	c := *s
	if s.TeamContext != nil {
		c.TeamContext = append([]byte(nil), s.TeamContext...)
	}
	if s.Busy != nil {
		b := *s.Busy
		c.Busy = &b
	}
	return &c
}

func nextID(counter *int64) int64 {
	return atomic.AddInt64(counter, 1)
}

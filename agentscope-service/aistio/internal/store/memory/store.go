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

	nextSnapID int64
	nextEvtID  int64
	nextCtxID  int64
	nextTokID  int64
	nextAgID   int64
	nextMsgID  int64
	nextTaskID int64
	nextHistID int64

	retention store.RetentionConfig
}

// Open creates a memory store.
func Open(_ context.Context, cfg store.Config) (store.Store, error) {
	s := &Store{
		sessions:     make(map[uuid.UUID]*store.Session),
		sessKey:      make(map[string]uuid.UUID),
		sessionLocks: newKeyedMutex(),
		retention:    cfg.Retention,
	}
	return s, nil
}

func (s *Store) Sessions() store.SessionRepository                 { return &sessionRepo{s} }
func (s *Store) Events() store.EventRepository                     { return &eventRepo{s} }
func (s *Store) ContextSnapshots() store.ContextSnapshotRepository { return &contextRepo{s} }
func (s *Store) Metrics() store.MetricsRepository                  { return &metricsRepo{s} }
func (s *Store) TeamMessages() store.TeamMessageRepository         { return &messageRepo{s} }
func (s *Store) TeamTasks() store.TeamTaskRepository               { return &taskRepo{s} }
func (s *Store) Commands() store.SessionCommandRepository          { return &commandRepo{s} }

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

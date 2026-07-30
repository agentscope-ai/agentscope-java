package postgres

import (
	"context"
	"fmt"
	"hash/fnv"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func init() {
	store.RegisterOpener(store.DriverPostgres, Open)
}

// Store is the PostgreSQL implementation of store.Store.
type Store struct {
	pool      *pgxpool.Pool
	retention store.RetentionConfig

	sessions *sessionRepo
	events   *eventRepo
	contexts *contextRepo
	metrics  *metricsRepo
	messages *messageRepo
	tasks    *taskRepo
	commands *commandRepo
}

// Open creates a PostgreSQL store from cfg.
func Open(ctx context.Context, cfg store.Config) (store.Store, error) {
	poolCfg, err := pgxpool.ParseConfig(cfg.PostgresDSN)
	if err != nil {
		return nil, fmt.Errorf("postgres: parse dsn: %w", err)
	}
	if cfg.MaxOpenConns > 0 {
		poolCfg.MaxConns = int32(cfg.MaxOpenConns)
	}
	if cfg.MaxIdleConns > 0 {
		poolCfg.MinConns = int32(cfg.MaxIdleConns)
	}
	if cfg.ConnMaxLifetime > 0 {
		poolCfg.MaxConnLifetime = cfg.ConnMaxLifetime
	}

	pool, err := pgxpool.NewWithConfig(ctx, poolCfg)
	if err != nil {
		return nil, fmt.Errorf("postgres: connect: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("postgres: ping: %w", err)
	}

	s := &Store{pool: pool, retention: cfg.Retention}
	s.sessions = &sessionRepo{pool: pool}
	s.events = &eventRepo{pool: pool}
	s.contexts = &contextRepo{pool: pool}
	s.metrics = &metricsRepo{pool: pool}
	s.messages = &messageRepo{pool: pool}
	s.tasks = &taskRepo{pool: pool}
	s.commands = &commandRepo{pool: pool}
	return s, nil
}

func (s *Store) Sessions() store.SessionRepository                 { return s.sessions }
func (s *Store) Events() store.EventRepository                     { return s.events }
func (s *Store) ContextSnapshots() store.ContextSnapshotRepository { return s.contexts }
func (s *Store) Metrics() store.MetricsRepository                  { return s.metrics }
func (s *Store) TeamMessages() store.TeamMessageRepository         { return s.messages }
func (s *Store) TeamTasks() store.TeamTaskRepository               { return s.tasks }
func (s *Store) Commands() store.SessionCommandRepository          { return s.commands }

func (s *Store) Ping(ctx context.Context) error {
	return s.pool.Ping(ctx)
}

func (s *Store) Close() error {
	s.pool.Close()
	return nil
}

// WithSessionLock acquires a Postgres session-level advisory lock keyed by
// sessionKey, then runs fn. The lock is held on a dedicated pool connection
// for the duration of fn so it is visible to other aistiod replicas.
func (s *Store) WithSessionLock(ctx context.Context, sessionKey string, fn func(context.Context) error) error {
	if fn == nil {
		return nil
	}
	conn, err := s.pool.Acquire(ctx)
	if err != nil {
		return fmt.Errorf("postgres session lock acquire: %w", err)
	}
	defer conn.Release()

	key := advisorySessionKey(sessionKey)
	if _, err := conn.Exec(ctx, `SELECT pg_advisory_lock($1)`, key); err != nil {
		return fmt.Errorf("postgres session lock: %w", err)
	}
	defer func() {
		_, _ = conn.Exec(context.Background(), `SELECT pg_advisory_unlock($1)`, key)
	}()
	return fn(ctx)
}

// advisorySessionKey hashes sessionKey to an int64 distinct from the migrate
// advisory lock (0x415354494F0001).
func advisorySessionKey(sessionKey string) int64 {
	h := fnv.New64a()
	_, _ = h.Write([]byte("aistio-session-cmd:"))
	_, _ = h.Write([]byte(sessionKey))
	k := int64(h.Sum64())
	const migrateKey int64 = 0x415354494F0001
	if k == 0 || k == migrateKey {
		k = migrateKey + 1
	}
	return k
}

func (s *Store) PurgeOlderThan(ctx context.Context, r store.RetentionConfig) (int64, error) {
	now := time.Now().UTC()
	var total int64

	type purge struct {
		sql string
		cut time.Duration
	}
	ops := []purge{
		{`DELETE FROM session_events WHERE occurred_at < $1`, r.SessionEvents},
		{`DELETE FROM session_snapshots WHERE captured_at < $1`, r.Snapshots},
		{`DELETE FROM context_snapshots WHERE captured_at < $1`, r.ContextSnapshots},
		{`DELETE FROM token_usage_metrics WHERE recorded_at < $1`, r.Metrics},
		{`DELETE FROM agent_metrics WHERE recorded_at < $1`, r.Metrics},
	}
	for _, op := range ops {
		if op.cut <= 0 {
			continue
		}
		tag, err := s.pool.Exec(ctx, op.sql, now.Add(-op.cut))
		if err != nil {
			return total, fmt.Errorf("postgres: purge: %w", err)
		}
		total += tag.RowsAffected()
	}
	return total, nil
}

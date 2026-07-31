package store

import (
	"context"
	"time"

	"github.com/google/uuid"
)

// Store is the runtime-data persistence facade.
// Implementations: PostgreSQL (production), memory (dev/tests).
type Store interface {
	Sessions() SessionRepository
	Turns() TurnRepository
	Events() EventRepository
	ContextSnapshots() ContextSnapshotRepository
	Metrics() MetricsRepository
	TeamMessages() TeamMessageRepository
	TeamTasks() TeamTaskRepository
	Commands() SessionCommandRepository

	// Migrate applies schema migrations. No-op for memory.
	Migrate(ctx context.Context) error
	// Ping checks connectivity (used by /readyz).
	Ping(ctx context.Context) error
	// Close releases resources.
	Close() error

	// PurgeOlderThan deletes historical rows older than the given cutoffs.
	// Used by the RetentionWorker. Returns total rows deleted.
	PurgeOlderThan(ctx context.Context, r RetentionConfig) (int64, error)

	// WithSessionLock runs fn while holding an exclusive lock for sessionKey.
	// Memory uses an in-process keyed mutex; Postgres uses pg_advisory_lock so
	// the lock is safe across aistiod replicas that share the same database.
	WithSessionLock(ctx context.Context, sessionKey string, fn func(context.Context) error) error
}

// SessionRepository manages the sessions table.
type SessionRepository interface {
	Upsert(ctx context.Context, s *Session) (*Session, error)
	Get(ctx context.Context, agentName, namespace, sessionID string) (*Session, error)
	GetByID(ctx context.Context, id uuid.UUID) (*Session, error)
	List(ctx context.Context, filter SessionFilter) ([]*Session, error)
	UpdatePhase(ctx context.Context, id uuid.UUID, phase string) error
	// ArchiveMissing marks sessions for the agent whose session_id is NOT in
	// keepSessionIDs and whose created_at is older than olderThan as archived
	// (History). DP stopping Level-1 listing is not a hard destroy — use
	// explicit terminate for terminated. Already archived/terminated rows are
	// left alone. Returns the number of rows updated.
	ArchiveMissing(ctx context.Context, agentName, namespace string, keepSessionIDs []string, olderThan time.Duration) (int, error)
	// ArchiveIdleOlderThan marks idle sessions inactive longer than olderThan as archived.
	ArchiveIdleOlderThan(ctx context.Context, olderThan time.Duration) (int, error)
	CountActive(ctx context.Context, agentName, namespace string) (int32, error)
	// CountByPhase returns session counts keyed by lowercase phase.
	CountByPhase(ctx context.Context, filter SessionFilter) (map[string]int, error)
	// ListByPressure returns sessions whose latest snapshot context_pressure
	// is >= minPressure, ordered by pressure descending.
	ListByPressure(ctx context.Context, filter SessionFilter, minPressure float64, limit int) ([]*SessionWithSnapshot, error)
	DeleteByAgent(ctx context.Context, agentName, namespace string) error
	DeleteByTeam(ctx context.Context, teamName, namespace string) error
}

// TurnRepository manages session_turns (one row per inference turn).
type TurnRepository interface {
	// SyncOnPhase opens a running turn when phase becomes active, and closes
	// any running turn when phase leaves active. Idempotent across polls.
	SyncOnPhase(ctx context.Context, sessionFK uuid.UUID, phase string) error
	List(ctx context.Context, sessionFK uuid.UUID, limit int) ([]*SessionTurn, error)
	CurrentRunning(ctx context.Context, sessionFK uuid.UUID) (*SessionTurn, error)
}

// EventRepository manages the session_events table (Level 2).
type EventRepository interface {
	Append(ctx context.Context, event *SessionEvent) error
	List(ctx context.Context, sessionFK uuid.UUID, opts ...EventOption) ([]*SessionEvent, error)
}

// ContextSnapshotRepository manages context_snapshots (Level 4).
type ContextSnapshotRepository interface {
	// PutIfChanged writes only when context_hash differs from the latest for
	// this session. Returns (true, nil) if a new row was inserted.
	PutIfChanged(ctx context.Context, snapshot *ContextSnapshot) (bool, error)
	Latest(ctx context.Context, sessionFK uuid.UUID) (*ContextSnapshot, error)
}

// MetricsRepository manages token_usage_metrics, session_snapshots, and agent_metrics.
type MetricsRepository interface {
	RecordTokenUsage(ctx context.Context, metric *TokenUsageMetric) error
	RecordSnapshot(ctx context.Context, snapshot *SessionSnapshot) error
	RecordAgentMetric(ctx context.Context, metric *AgentMetric) error
	QueryTokenUsage(ctx context.Context, filter TokenFilter) ([]*TokenUsageMetric, error)
	// LatestSnapshot returns the most recent Level-1 snapshot for a session.
	LatestSnapshot(ctx context.Context, sessionFK uuid.UUID) (*SessionSnapshot, error)
	// LatestSnapshots returns the most recent Level-1 snapshot for each session
	// FK. Missing sessions are omitted from the result map.
	LatestSnapshots(ctx context.Context, sessionFKs []uuid.UUID) (map[uuid.UUID]*SessionSnapshot, error)
	// QueryAgentMetrics returns agent-level metric samples.
	QueryAgentMetrics(ctx context.Context, filter AgentMetricFilter) ([]*AgentMetric, error)
	// AggregateTokens buckets token usage by the given duration (e.g. time.Hour).
	AggregateTokens(ctx context.Context, filter TokenFilter, bucket time.Duration) ([]TokenBucket, error)
	// TopAgents returns agents ranked by total tokens since the given time.
	TopAgents(ctx context.Context, since time.Time, limit int) ([]AgentUsage, error)
	// TopSessionsByTokens returns sessions ranked by summed token deltas since the given time.
	TopSessionsByTokens(ctx context.Context, since time.Time, limit int) ([]SessionUsage, error)
	// TopSessionsByDuration returns active sessions ranked by current running
	// turn elapsed (now - turn.started_at). Idle/archived sessions are excluded.
	TopSessionsByDuration(ctx context.Context, since time.Time, limit int) ([]SessionDuration, error)
	// TopAgentsByActiveSessions ranks agents by peak active_sessions in agent_metrics since.
	TopAgentsByActiveSessions(ctx context.Context, since time.Time, limit int) ([]AgentUsage, error)
	// PressureStats returns average and p95 context pressure across latest snapshots.
	PressureStats(ctx context.Context, filter SessionFilter) (avg, p95 float64, err error)
	// SumTokenUsage returns the sum of total_tokens matching the filter.
	SumTokenUsage(ctx context.Context, filter TokenFilter) (int64, error)
	// SumErrorCount returns the sum of error_count from agent_metrics matching the filter.
	SumErrorCount(ctx context.Context, filter AgentMetricFilter) (int32, error)
}

// SessionCommandRepository manages the session_commands audit table.
type SessionCommandRepository interface {
	Insert(ctx context.Context, cmd *SessionCommand) error
	Update(ctx context.Context, cmd *SessionCommand) error
	GetByCommandID(ctx context.Context, commandID string) (*SessionCommand, error)
	List(ctx context.Context, filter SessionCommandFilter) ([]*SessionCommand, error)
}

// TeamMessageRepository manages the team_messages outbox.
type TeamMessageRepository interface {
	Send(ctx context.Context, msg *TeamMessage) error
	ListPending(ctx context.Context, teamName, namespace string) ([]*TeamMessage, error)
	// ListPendingAll returns undelivered messages across all teams, limited,
	// ordered by created_at ASC. Used by the outbox dispatcher.
	ListPendingAll(ctx context.Context, limit int) ([]*TeamMessage, error)
	MarkDelivered(ctx context.Context, id int64) error
	IncrementAttempts(ctx context.Context, id int64) error
	History(ctx context.Context, teamName, namespace string, limit int) ([]*TeamMessage, error)
	DeleteByTeam(ctx context.Context, teamName, namespace string) error
}

// TeamTaskRepository manages team_tasks. Method signatures align with the
// previous TaskStoreInterface so callers can switch with minimal changes.
type TeamTaskRepository interface {
	Create(ctx context.Context, namespace, teamName, subject, description string, blockedBy []string) (*TeamTask, error)
	Get(ctx context.Context, namespace, teamName, taskID string) (*TeamTask, error)
	List(ctx context.Context, namespace, teamName string) ([]*TeamTask, error)
	Claim(ctx context.Context, namespace, teamName, taskID, claimedBy string, expectedVersion int64) (*TeamTask, error)
	Complete(ctx context.Context, namespace, teamName, taskID, result string) (*TeamTask, error)
	Unclaim(ctx context.Context, namespace, teamName, taskID string) (*TeamTask, error)
	GetUnblockedPending(ctx context.Context, namespace, teamName string) ([]*TeamTask, error)
	GetSummary(ctx context.Context, namespace, teamName string) (total, pending, inProgress, completed int32, err error)
	DeleteByTeam(ctx context.Context, namespace, teamName string) error
}

// ApplyEventOptions is exported for implementations in sub-packages.
func ApplyEventOptions(opts []EventOption) (eventType string, since, until *time.Time, limit, offset int) {
	o := applyEventOptions(opts)
	return o.EventType, o.Since, o.Until, o.Limit, o.Offset
}

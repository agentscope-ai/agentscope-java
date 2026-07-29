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
	Events() EventRepository
	ContextSnapshots() ContextSnapshotRepository
	Metrics() MetricsRepository
	TeamMessages() TeamMessageRepository
	TeamTasks() TeamTaskRepository

	// Migrate applies schema migrations. No-op for memory.
	Migrate(ctx context.Context) error
	// Ping checks connectivity (used by /readyz).
	Ping(ctx context.Context) error
	// Close releases resources.
	Close() error

	// PurgeOlderThan deletes historical rows older than the given cutoffs.
	// Used by the RetentionWorker. Returns total rows deleted.
	PurgeOlderThan(ctx context.Context, r RetentionConfig) (int64, error)
}

// SessionRepository manages the sessions table.
type SessionRepository interface {
	Upsert(ctx context.Context, s *Session) (*Session, error)
	Get(ctx context.Context, agentName, namespace, sessionID string) (*Session, error)
	GetByID(ctx context.Context, id uuid.UUID) (*Session, error)
	List(ctx context.Context, filter SessionFilter) ([]*Session, error)
	UpdatePhase(ctx context.Context, id uuid.UUID, phase string) error
	// TerminateMissing marks sessions for the agent whose session_id is NOT in
	// keepSessionIDs and whose created_at is older than olderThan as terminated.
	// Returns the number of rows updated.
	TerminateMissing(ctx context.Context, agentName, namespace string, keepSessionIDs []string, olderThan time.Duration) (int, error)
	CountActive(ctx context.Context, agentName, namespace string) (int32, error)
	DeleteByAgent(ctx context.Context, agentName, namespace string) error
	DeleteByTeam(ctx context.Context, teamName, namespace string) error
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

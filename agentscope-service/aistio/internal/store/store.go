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

package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

// Store is the runtime-data persistence facade.
// Implementations: PostgreSQL (production), memory (dev/tests).
type Store interface {
	Access() AccessRepository
	Sessions() SessionRepository
	Turns() TurnRepository
	Events() EventRepository
	ContextSnapshots() ContextSnapshotRepository
	Metrics() MetricsRepository
	TranscriptIndex() TranscriptIndexRepository
	Commands() SessionCommandRepository
	AgentCatalog() AgentCatalogRepository
	RuntimeRegistry() RuntimeRegistryRepository
	ExecutionAttempts() ExecutionAttemptRepository
	Orchestration() OrchestrationRepository
	Outbox() OutboxRepository
	Collaboration() CollaborationRepository
	WorkSources() WorkSourceRepository
	Endpoints() EndpointRepository
	TeamProposals() TeamProposalRepository
	Chats() ChatRepository

	// Hosted DistributedStore backends (data-plane coordination).
	KV() KVRepository
	Locks() LockRepository
	Snapshots() SnapshotRepository
	Bus() BusRepository
	AsyncTools() AsyncToolRepository
	DPTasks() DPTaskRepository

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
	Get(ctx context.Context, tenant, agentName, namespace, sessionID string) (*Session, error)
	GetByID(ctx context.Context, id uuid.UUID) (*Session, error)
	List(ctx context.Context, filter SessionFilter) ([]*Session, error)
	UpdatePhase(ctx context.Context, id uuid.UUID, phase string) error
	// ArchiveMissing marks sessions for the agent whose session_id is NOT in
	// keepSessionIDs and whose created_at is older than olderThan as archived
	// (History). DP stopping Level-1 listing is not a hard destroy — use
	// explicit terminate for terminated. Already archived/terminated rows are
	// left alone. Returns the number of rows updated.
	ArchiveMissing(ctx context.Context, tenant, agentName, namespace string, keepSessionIDs []string, olderThan time.Duration) (int, error)
	// ArchiveIdleOlderThan marks idle sessions inactive longer than olderThan as archived.
	ArchiveIdleOlderThan(ctx context.Context, olderThan time.Duration) (int, error)
	CountActive(ctx context.Context, tenant, agentName, namespace string) (int32, error)
	// CountByPhase returns session counts keyed by lowercase phase.
	CountByPhase(ctx context.Context, filter SessionFilter) (map[string]int, error)
	// ListByPressure returns sessions whose latest snapshot context_pressure
	// is >= minPressure, ordered by pressure descending.
	ListByPressure(ctx context.Context, filter SessionFilter, minPressure float64, limit int) ([]*SessionWithSnapshot, error)
	DeleteByAgent(ctx context.Context, tenant, agentName, namespace string) error
}

// TurnRepository manages session_turns (one row per inference turn).
type TurnRepository interface {
	// SyncOnPhase opens a running turn when phase becomes active, and closes
	// any running turn when phase leaves active. The logical outcome "failed"
	// closes a failed provider turn without terminating its reusable session. Idempotent across polls.
	SyncOnPhase(ctx context.Context, sessionFK uuid.UUID, phase string) error
	List(ctx context.Context, sessionFK uuid.UUID, limit int) ([]*SessionTurn, error)
	CurrentRunning(ctx context.Context, sessionFK uuid.UUID) (*SessionTurn, error)
}

// EventRepository manages the session_events table (Level 2).
type EventRepository interface {
	Append(ctx context.Context, event *SessionEvent) error
	List(ctx context.Context, sessionFK uuid.UUID, opts ...EventOption) ([]*SessionEvent, error)
	// WaitForNew blocks until an event with seq greater than afterSeq is
	// available for the session or ctx is cancelled. Implementations should use
	// a notification primitive rather than polling durable storage.
	WaitForNew(ctx context.Context, sessionFK uuid.UUID, afterSeq int) error
}

// ContextSnapshotRepository manages context_snapshots (Level 4).
type ContextSnapshotRepository interface {
	// PutIfChanged writes only when context_hash differs from the latest for
	// this session. Returns (true, nil) if a new row was inserted.
	PutIfChanged(ctx context.Context, snapshot *ContextSnapshot) (bool, error)
	Latest(ctx context.Context, sessionFK uuid.UUID) (*ContextSnapshot, error)
}

// TranscriptIndexRepository manages the narrow session_transcript_index table.
type TranscriptIndexRepository interface {
	// Upsert replaces aggregate counts for the session (absolute snapshot values).
	Upsert(ctx context.Context, idx *SessionTranscriptIndex) error
	Get(ctx context.Context, sessionFK uuid.UUID) (*SessionTranscriptIndex, error)
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
	TopAgents(ctx context.Context, tenant string, since time.Time, limit int) ([]AgentUsage, error)
	// TopSessionsByTokens returns sessions ranked by summed token deltas since the given time.
	TopSessionsByTokens(ctx context.Context, tenant string, since time.Time, limit int) ([]SessionUsage, error)
	// TopSessionsByDuration returns active sessions ranked by current running
	// turn elapsed (now - turn.started_at). Idle/archived sessions are excluded.
	TopSessionsByDuration(ctx context.Context, tenant string, since time.Time, limit int) ([]SessionDuration, error)
	// TopAgentsByActiveSessions ranks agents by peak active_sessions in agent_metrics since.
	TopAgentsByActiveSessions(ctx context.Context, tenant string, since time.Time, limit int) ([]AgentUsage, error)
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

// KVRepository is the hosted BaseStore backend (workspace KV + CAS).
type KVRepository interface {
	Get(ctx context.Context, tenant, nsPath, key string) (*KVItem, error)
	// Put writes unconditionally and returns the new version.
	Put(ctx context.Context, tenant, nsPath, key string, value json.RawMessage) (int64, error)
	// PutIfVersion writes only when the stored version equals expectedVersion.
	// expectedVersion == 0 means create-if-absent. Returns (newVersion, true, nil)
	// on success, or (currentVersion, false, nil) on conflict.
	PutIfVersion(ctx context.Context, tenant, nsPath, key string, value json.RawMessage, expectedVersion int64) (newVersion int64, written bool, err error)
	Delete(ctx context.Context, tenant, nsPath, key string) error
	// Search returns items whose ns_path equals nsPath or has nsPath as a
	// prefix (recursive into child namespaces), ordered by key, paginated.
	Search(ctx context.Context, tenant, nsPath string, limit, offset int) ([]*KVItem, error)
}

// LockRepository is the hosted SandboxExecutionGuard backend (TTL leases).
type LockRepository interface {
	// Acquire tries to take the lock. On success returns the held Lock.
	// On conflict (held by another owner and not expired) returns ErrConflict
	// and a Lock describing the current holder (may be partially filled).
	Acquire(ctx context.Context, tenant, name, ownerToken, holder string, ttl time.Duration) (*Lock, error)
	// Renew extends the lease if ownerToken matches. Returns ErrConflict if
	// the lock is missing or owned by someone else.
	Renew(ctx context.Context, tenant, name, ownerToken string, ttl time.Duration) (*Lock, error)
	// Release deletes the lock if ownerToken matches. Idempotent: missing or
	// mismatched token is not an error.
	Release(ctx context.Context, tenant, name, ownerToken string) error
	// Peek returns the current lock holder if held and not expired.
	// Returns ErrNotFound when missing or expired.
	Peek(ctx context.Context, tenant, name string) (*Lock, error)
	// PurgeExpired deletes locks whose expires_at is older than olderThan.
	PurgeExpired(ctx context.Context, olderThan time.Duration) (int64, error)
}

// SnapshotRepository is the hosted SandboxSnapshotSpec backend.
type SnapshotRepository interface {
	Put(ctx context.Context, tenant, snapshotID string, payload []byte, mode string) (*SnapshotMeta, error)
	Get(ctx context.Context, tenant, snapshotID string) (payload []byte, meta *SnapshotMeta, err error)
	Exists(ctx context.Context, tenant, snapshotID string) (bool, error)
	// Touch updates accessed_at for retention.
	Touch(ctx context.Context, tenant, snapshotID string) error
}

// Bus kind constants.
const (
	BusKindQueue int16 = 0
	BusKindLog   int16 = 1
)

// BusRepository is the hosted MessageBus backend (queue + replay log).
type BusRepository interface {
	QueuePush(ctx context.Context, tenant, key string, payload json.RawMessage) (entryID string, err error)
	// QueueDrain removes up to maxCount oldest queue entries and returns them
	// (ack-on-read). Multi-replica safe via FOR UPDATE SKIP LOCKED.
	QueueDrain(ctx context.Context, tenant, key string, maxCount int) ([]*BusEntry, error)
	QueueDelete(ctx context.Context, tenant, key string) error
	QueuePeek(ctx context.Context, tenant, key string) (bool, error)

	LogAppend(ctx context.Context, tenant, key string, payload json.RawMessage, maxLen int) (entryID string, err error)
	// LogRead returns entries with id > since (numeric), limited to maxCount.
	LogRead(ctx context.Context, tenant, key, since string, maxCount int) ([]*BusEntry, error)
	LogTrim(ctx context.Context, tenant, key string) error
}

// AsyncToolRepository is the hosted AsyncToolRegistry backend.
type AsyncToolRepository interface {
	Register(ctx context.Context, rec *AsyncToolRecord) error
	Complete(ctx context.Context, tenant, recordID, result string) error
	Fail(ctx context.Context, tenant, recordID, errMsg string) error
	MarkTimeout(ctx context.Context, tenant, recordID string) error
	FindStale(ctx context.Context, tenant, sessionID string, ttl time.Duration) ([]*AsyncToolRecord, error)
}

// TaskRepository is the hosted subagent background task backend.
type DPTaskRepository interface {
	Upsert(ctx context.Context, task *DPTask) (*DPTask, error)
	Get(ctx context.Context, tenant, parentAgentID, parentSessionID, taskID string) (*DPTask, error)
	List(ctx context.Context, tenant, parentAgentID, parentSessionID, status string) ([]*DPTask, error)
	// Heartbeat refreshes last_updated_at for non-terminal tasks in the batch.
	Heartbeat(ctx context.Context, tenant, parentAgentID string, refs []DPTaskRef) error
	RequestCancel(ctx context.Context, tenant, parentAgentID, parentSessionID, taskID string) error
	// MarkDelivered sets delivered_at on first write; returns true when this call wrote it.
	MarkDelivered(ctx context.Context, tenant, parentAgentID, parentSessionID, taskID string) (bool, error)
	ListPendingDeliveries(ctx context.Context, tenant, parentAgentID, parentSessionID string) ([]*DPTask, error)
	Delete(ctx context.Context, tenant, parentAgentID, parentSessionID, taskID string) error
	// SweepOrphaned marks stale non-terminal, non-agent-protocol tasks as FAILED.
	SweepOrphaned(ctx context.Context, orphanTimeout time.Duration, errMsg string) ([]*DPTask, error)
	PurgeTerminalOlderThan(ctx context.Context, olderThan time.Duration) (int64, error)
}

// ApplyEventOptions is exported for implementations in sub-packages.
// Prefer ResolveEventOptions when before/newest-first reverse paging is needed.
func ApplyEventOptions(opts []EventOption) (eventType string, since, until *time.Time, limit, offset int) {
	o := applyEventOptions(opts)
	return o.EventType, o.Since, o.Until, o.Limit, o.Offset
}

// ResolveEventOptions returns the full event list options including reverse-paging fields.
func ResolveEventOptions(opts []EventOption) EventListOpts {
	o := applyEventOptions(opts)
	return EventListOpts{
		EventType:   o.EventType,
		Since:       o.Since,
		Until:       o.Until,
		Before:      o.Before,
		BeforeSeq:   o.BeforeSeq,
		AfterSeq:    o.AfterSeq,
		Limit:       o.Limit,
		Offset:      o.Offset,
		NewestFirst: o.NewestFirst,
	}
}

// UpsertTranscriptIndexFromSnapshot writes absolute Level-1 aggregates into the
// narrow transcript index. Callers pass DP snapshot messageCount / token fields.
func UpsertTranscriptIndexFromSnapshot(ctx context.Context, st Store, sessionFK uuid.UUID, entryCount int32, promptTokens, completionTokens int64) error {
	if st == nil {
		return nil
	}
	return st.TranscriptIndex().Upsert(ctx, &SessionTranscriptIndex{
		SessionFK:        sessionFK,
		EntryCount:       entryCount,
		PromptTokens:     promptTokens,
		CompletionTokens: completionTokens,
	})
}

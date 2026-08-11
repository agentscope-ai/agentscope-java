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
	"time"

	"github.com/google/uuid"
)

// SessionFilter selects sessions for List.
type SessionFilter struct {
	AgentName string
	Namespace string
	SessionID string
	Phase     string
	Framework string
	TeamID    string
	TeamRole  string
	// Limit / Offset for pagination. Zero Limit means no limit.
	Limit  int
	Offset int
}

// TokenFilter selects token-usage metrics for QueryTokenUsage.
type TokenFilter struct {
	AgentName string
	Namespace string
	Model     string
	Since     *time.Time
	Until     *time.Time
	Limit     int
}

// AgentMetricFilter selects agent_metrics rows for QueryAgentMetrics.
type AgentMetricFilter struct {
	AgentName string
	Namespace string
	Since     *time.Time
	Until     *time.Time
	Limit     int
}

// SessionCommandFilter selects session_commands audit rows.
type SessionCommandFilter struct {
	SessionFK uuid.UUID
	AgentName string
	Namespace string
	Status    string
	Since     *time.Time
	Limit     int
}

// EventOption configures EventRepository.List.
type EventOption func(*eventListOpts)

type eventListOpts struct {
	EventType   string
	Since       *time.Time
	Until       *time.Time
	Before      *time.Time // exclusive upper bound on occurred_at (reverse paging)
	BeforeSeq   *int       // exclusive upper bound on seq (reverse paging)
	Limit       int
	Offset      int
	NewestFirst bool // when Limit > 0, take the newest matching rows then return ASC
}

// EventListOpts is the resolved form of EventOption (exported for store drivers).
type EventListOpts struct {
	EventType   string
	Since       *time.Time
	Until       *time.Time
	Before      *time.Time
	BeforeSeq   *int
	Limit       int
	Offset      int
	NewestFirst bool
}

// WithEventType filters events by type.
func WithEventType(t string) EventOption {
	return func(o *eventListOpts) { o.EventType = t }
}

// WithEventSince filters events occurring at or after t.
func WithEventSince(t time.Time) EventOption {
	return func(o *eventListOpts) { o.Since = &t }
}

// WithEventUntil filters events occurring at or before t.
func WithEventUntil(t time.Time) EventOption {
	return func(o *eventListOpts) { o.Until = &t }
}

// WithEventBefore filters events with occurred_at strictly before t (reverse paging).
func WithEventBefore(t time.Time) EventOption {
	return func(o *eventListOpts) {
		o.Before = &t
		o.NewestFirst = true
	}
}

// WithEventBeforeSeq filters events with seq strictly less than seq (reverse paging).
func WithEventBeforeSeq(seq int) EventOption {
	return func(o *eventListOpts) {
		o.BeforeSeq = &seq
		o.NewestFirst = true
	}
}

// WithEventNewestFirst requests the newest matching rows when Limit is set
// (first page of reverse paging without a before cursor).
func WithEventNewestFirst() EventOption {
	return func(o *eventListOpts) { o.NewestFirst = true }
}

// WithEventLimit sets a limit on returned events.
func WithEventLimit(n int) EventOption {
	return func(o *eventListOpts) { o.Limit = n }
}

// WithEventOffset sets an offset for event pagination.
func WithEventOffset(n int) EventOption {
	return func(o *eventListOpts) { o.Offset = n }
}

func applyEventOptions(opts []EventOption) eventListOpts {
	var o eventListOpts
	for _, fn := range opts {
		fn(&o)
	}
	return o
}

// RetentionConfig controls how long historical data is kept.
// Note: dp_kv (hosted BaseStore) is user-persistent data and is NEVER purged.
type RetentionConfig struct {
	SessionEvents     time.Duration // default 7d
	Snapshots         time.Duration // default 30d
	ContextSnapshots  time.Duration // default 14d
	Metrics           time.Duration // default 90d
	BusQueue          time.Duration // default 7d — undrained queue entries
	BusLog            time.Duration // default 3d — replay log entries
	AsyncTools        time.Duration // default 7d — async tool records
	SandboxSnapshots  time.Duration // default 7d — hosted sandbox blobs
	Tasks             time.Duration // default 7d — terminal hosted subagent tasks
}

// DefaultRetention returns the retention defaults from the design doc.
func DefaultRetention() RetentionConfig {
	return RetentionConfig{
		SessionEvents:    7 * 24 * time.Hour,
		Snapshots:        30 * 24 * time.Hour,
		ContextSnapshots: 14 * 24 * time.Hour,
		Metrics:          90 * 24 * time.Hour,
		BusQueue:         7 * 24 * time.Hour,
		BusLog:           3 * 24 * time.Hour,
		AsyncTools:       7 * 24 * time.Hour,
		SandboxSnapshots: 7 * 24 * time.Hour,
		Tasks:            7 * 24 * time.Hour,
	}
}

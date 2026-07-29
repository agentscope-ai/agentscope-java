package store

import (
	"time"
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

// EventOption configures EventRepository.List.
type EventOption func(*eventListOpts)

type eventListOpts struct {
	EventType string
	Since     *time.Time
	Until     *time.Time
	Limit     int
	Offset    int
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
type RetentionConfig struct {
	SessionEvents    time.Duration // default 7d
	Snapshots        time.Duration // default 30d
	ContextSnapshots time.Duration // default 14d
	Metrics          time.Duration // default 90d
}

// DefaultRetention returns the retention defaults from the design doc.
func DefaultRetention() RetentionConfig {
	return RetentionConfig{
		SessionEvents:    7 * 24 * time.Hour,
		Snapshots:        30 * 24 * time.Hour,
		ContextSnapshots: 14 * 24 * time.Hour,
		Metrics:          90 * 24 * time.Hour,
	}
}

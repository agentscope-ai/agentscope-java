package prober

import (
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// ToStoreContext converts a probed Level-4 context snapshot into the store
// representation (deduplicated by context_hash via PutIfChanged).
// fallbackFramework is used when the data plane did not report a framework.
func (p *ContextSnapshot) ToStoreContext(sessionFK uuid.UUID, fallbackFramework string) (*store.ContextSnapshot, error) {
	messages, err := json.Marshal(p.Messages)
	if err != nil {
		return nil, fmt.Errorf("marshaling context messages: %w", err)
	}
	var tools json.RawMessage
	if len(p.Tools) > 0 {
		tools, err = json.Marshal(p.Tools)
		if err != nil {
			return nil, fmt.Errorf("marshaling context tools: %w", err)
		}
	}
	framework := p.Framework
	if framework == "" {
		framework = fallbackFramework
	}
	return &store.ContextSnapshot{
		SessionFK:            sessionFK,
		CapturedAt:           time.Now().UTC(),
		ContextHash:          p.ContextHash,
		SystemPrompt:         p.SystemPrompt,
		Messages:             messages,
		Tools:                tools,
		IsCompacted:          p.IsCompacted,
		CompactionSummary:    p.CompactionSummary,
		OriginalMessageCount: int(p.OriginalMessageCount),
		CompactedAt:          parseTimePtr(p.CompactedAt),
		TotalTokens:          int(p.TotalTokens),
		MaxTokens:            int(p.MaxTokens),
		Framework:            framework,
		FrameworkState:       p.FrameworkState,
	}, nil
}

// parseTimePtr parses an RFC3339 timestamp, returning nil for empty/invalid.
func parseTimePtr(s string) *time.Time {
	if s == "" {
		return nil
	}
	if t, err := time.Parse(time.RFC3339, s); err == nil {
		return &t
	}
	return nil
}

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package model

import (
	"time"

	"github.com/google/uuid"
)

// ChatStatus is the user-facing lifecycle of a private Chat. Runtime Session
// phases remain independent so a Chat can survive provider or host changes.
type ChatStatus string

const (
	ChatActive   ChatStatus = "active"
	ChatArchived ChatStatus = "archived"
)

// Chat is a user-owned Work Hub conversation. It deliberately references, but
// does not duplicate, the runtime Session that stores messages and events.
type Chat struct {
	ID             uuid.UUID  `json:"id"`
	Tenant         string     `json:"tenant"`
	Namespace      string     `json:"namespace"`
	CreatorRef     string     `json:"creatorRef"`
	AgentID        uuid.UUID  `json:"agentId"`
	AgentName      string     `json:"agentName"`
	SessionID      uuid.UUID  `json:"sessionId"`
	RuntimeSession string     `json:"runtimeSessionId"`
	Title          string     `json:"title"`
	Status         ChatStatus `json:"status"`
	Pinned         bool       `json:"pinned"`
	LastReadSeq    int        `json:"lastReadSeq"`
	Version        int64      `json:"version"`
	CreatedAt      time.Time  `json:"createdAt"`
	UpdatedAt      time.Time  `json:"updatedAt"`
}

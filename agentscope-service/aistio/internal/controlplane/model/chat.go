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
	ChatDeleted  ChatStatus = "deleted"
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

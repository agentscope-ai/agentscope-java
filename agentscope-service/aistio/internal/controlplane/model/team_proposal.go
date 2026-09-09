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
	"encoding/json"
	"github.com/google/uuid"
	"time"
)

type TeamProposalStatus string

const (
	TeamProposalProposed  TeamProposalStatus = "proposed"
	TeamProposalConfirmed TeamProposalStatus = "confirmed"
	TeamProposalRejected  TeamProposalStatus = "rejected"
)

type TeamProposalMember struct {
	AgentID uuid.UUID `json:"agentId"`
	Role    string    `json:"role"`
	Score   int       `json:"score"`
	Reasons []string  `json:"reasons,omitempty"`
}
type TeamProposal struct {
	ID           uuid.UUID            `json:"id"`
	IssueID      uuid.UUID            `json:"issueId"`
	Tenant       string               `json:"tenant"`
	Namespace    string               `json:"namespace"`
	Requirements json.RawMessage      `json:"requirements"`
	Members      []TeamProposalMember `json:"members"`
	Status       TeamProposalStatus   `json:"status"`
	RunID        *uuid.UUID           `json:"runId,omitempty"`
	Version      int64                `json:"version"`
	CreatedAt    time.Time            `json:"createdAt"`
	UpdatedAt    time.Time            `json:"updatedAt"`
}

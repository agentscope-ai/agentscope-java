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

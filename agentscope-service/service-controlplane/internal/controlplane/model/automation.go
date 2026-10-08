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

package model

import (
	"encoding/json"
	"github.com/google/uuid"
	"time"
)

// AutomationExecution describes work independently of how it is triggered.
// Runtime workspace, credentials and placement are inherited from the target.
type AutomationExecution struct {
	Runbook             string                `json:"runbook"`
	AssigneeType        AssigneeType          `json:"assigneeType"`
	AssigneeRef         string                `json:"assigneeRef"`
	OutputMode          string                `json:"outputMode"`
	CompletionPolicy    IssueCompletionPolicy `json:"completionPolicy"`
	ContextRefs         json.RawMessage       `json:"contextRefs,omitempty"`
	ConcurrencyPolicy   string                `json:"concurrencyPolicy"`
	QueueTimeoutSeconds int                   `json:"queueTimeoutSeconds"`
	RunTimeoutSeconds   int                   `json:"runTimeoutSeconds"`
	Subscribers         []string              `json:"subscribers,omitempty"`
}

// Trigger identity is stable across edits. The rule and its trigger collection
// are stored atomically; runtime cursor changes do not publish a new revision.
type AutomationTrigger struct {
	ID          uuid.UUID             `json:"id"`
	Type        AutomationTriggerType `json:"type"`
	Enabled     bool                  `json:"enabled"`
	Schedule    string                `json:"schedule,omitempty"`
	Timezone    string                `json:"timezone,omitempty"`
	Events      []string              `json:"events,omitempty"`
	NextRunAt   *time.Time            `json:"nextRunAt,omitempty"`
	LastFiredAt *time.Time            `json:"lastFiredAt,omitempty"`
}

const (
	AutomationRunQueued      AutomationRunStatus = "queued"
	AutomationRunDispatching AutomationRunStatus = "dispatching"
	AutomationRunWaiting     AutomationRunStatus = "waiting"
	AutomationRunSkipped     AutomationRunStatus = "skipped"
	AutomationRunCancelled   AutomationRunStatus = "cancelled"
)

func IsAutomationRunTerminal(status AutomationRunStatus) bool {
	return status == AutomationRunCompleted || status == AutomationRunFailed || status == AutomationRunSkipped || status == AutomationRunCancelled || status == AutomationRunDuplicate
}

type AutomationDelivery struct {
	ID             uuid.UUID       `json:"id"`
	AutomationID   uuid.UUID       `json:"automationId"`
	TriggerID      uuid.UUID       `json:"triggerId"`
	IdempotencyKey string          `json:"idempotencyKey"`
	Input          json.RawMessage `json:"input,omitempty"`
	Event          string          `json:"event,omitempty"`
	Status         string          `json:"status"`
	Reason         string          `json:"reason,omitempty"`
	RunID          *uuid.UUID      `json:"runId,omitempty"`
	ReplayedFrom   *uuid.UUID      `json:"replayedFrom,omitempty"`
	CreatedAt      time.Time       `json:"createdAt"`
}

// AutomationRunDetails contains the durable execution snapshot and progress.
type AutomationRunDetails struct {
	Snapshot             *Automation `json:"snapshot,omitempty"`
	Source               string      `json:"source,omitempty"`
	TriggerID            uuid.UUID   `json:"triggerId,omitempty"`
	ScheduledAt          *time.Time  `json:"scheduledAt,omitempty"`
	StartedAt            *time.Time  `json:"startedAt,omitempty"`
	ExecutionCompletedAt *time.Time  `json:"executionCompletedAt,omitempty"`
	WaitReason           string      `json:"waitReason,omitempty"`
	Version              int64       `json:"version"`
	UpdatedAt            time.Time   `json:"updatedAt"`
	LeaseUntil           *time.Time  `json:"-"`
	LeaseToken           uuid.UUID   `json:"-"`
	RerunOf              *uuid.UUID  `json:"rerunOf,omitempty"`
}

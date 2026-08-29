// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package model

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

type WorkSource struct {
	ID            uuid.UUID       `json:"id"`
	Tenant        string          `json:"tenant"`
	Namespace     string          `json:"namespace"`
	Kind          string          `json:"kind"`
	Name          string          `json:"name"`
	Configuration json.RawMessage `json:"configuration,omitempty"`
	Enabled       bool            `json:"enabled"`
	Version       int64           `json:"version"`
	CreatedAt     time.Time       `json:"createdAt"`
	UpdatedAt     time.Time       `json:"updatedAt"`
	ArchivedAt    *time.Time      `json:"archivedAt,omitempty"`
}

type IssueExternalRef struct {
	WorkSourceID    uuid.UUID       `json:"workSourceId"`
	IssueID         uuid.UUID       `json:"issueId"`
	ExternalID      string          `json:"externalId"`
	ExternalNumber  string          `json:"externalNumber,omitempty"`
	ExternalURL     string          `json:"externalUrl,omitempty"`
	ExternalVersion string          `json:"externalVersion,omitempty"`
	Projection      json.RawMessage `json:"projection,omitempty"`
	UpdatedAt       time.Time       `json:"updatedAt"`
}

type CommentSyncState string

const (
	CommentPendingSync CommentSyncState = "pending_sync"
	CommentSynced      CommentSyncState = "synced"
	CommentSyncFailed  CommentSyncState = "sync_failed"
)

type CommentExternalRef struct {
	WorkSourceID    uuid.UUID        `json:"workSourceId"`
	CommentID       uuid.UUID        `json:"commentId"`
	ExternalID      string           `json:"externalId,omitempty"`
	ExternalVersion string           `json:"externalVersion,omitempty"`
	SyncState       CommentSyncState `json:"syncState"`
	LastError       string           `json:"lastError,omitempty"`
	Attempts        int32            `json:"attempts"`
	UpdatedAt       time.Time        `json:"updatedAt"`
}

type ExternalLink struct {
	ID           uuid.UUID       `json:"id"`
	WorkSourceID uuid.UUID       `json:"workSourceId"`
	IssueID      uuid.UUID       `json:"issueId"`
	Type         string          `json:"type"`
	ExternalID   string          `json:"externalId,omitempty"`
	URL          string          `json:"url"`
	Title        string          `json:"title,omitempty"`
	Metadata     json.RawMessage `json:"metadata,omitempty"`
	CreatedAt    time.Time       `json:"createdAt"`
	UpdatedAt    time.Time       `json:"updatedAt"`
}

type WebhookDelivery struct {
	ID           uuid.UUID  `json:"id"`
	WorkSourceID uuid.UUID  `json:"workSourceId"`
	DeliveryID   string     `json:"deliveryId"`
	PayloadHash  string     `json:"payloadHash"`
	Status       string     `json:"status"`
	Attempts     int32      `json:"attempts"`
	LastError    string     `json:"lastError,omitempty"`
	ReceivedAt   time.Time  `json:"receivedAt"`
	ProcessedAt  *time.Time `json:"processedAt,omitempty"`
}

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package store

import (
	"context"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

type ChatFilter struct {
	Tenant     string
	Namespace  string
	CreatorRef string
	Archived   bool
	Limit      int
	Offset     int
}

// ChatRepository persists the user-facing Chat aggregate independently of the
// runtime Session inventory.
type ChatRepository interface {
	Create(ctx context.Context, chat *controlmodel.Chat) (*controlmodel.Chat, error)
	Get(ctx context.Context, id uuid.UUID) (*controlmodel.Chat, error)
	List(ctx context.Context, filter ChatFilter) ([]*controlmodel.Chat, error)
	Update(ctx context.Context, chat *controlmodel.Chat, expectedVersion int64) (*controlmodel.Chat, error)
	Touch(ctx context.Context, id uuid.UUID) error
}

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package store

import (
	"context"
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

type TeamProposalRepository interface {
	Create(context.Context, *controlmodel.TeamProposal) (*controlmodel.TeamProposal, error)
	Get(context.Context, uuid.UUID) (*controlmodel.TeamProposal, error)
	Confirm(context.Context, uuid.UUID, int64, uuid.UUID) (*controlmodel.TeamProposal, error)
}

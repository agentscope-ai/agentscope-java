// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package store

import (
	"context"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

type AgentEndpointRepository interface {
	Create(context.Context, *controlmodel.AgentEndpoint) (*controlmodel.AgentEndpoint, error)
	Get(context.Context, uuid.UUID) (*controlmodel.AgentEndpoint, error)
	GetBySlug(context.Context, string) (*controlmodel.AgentEndpoint, error)
	List(context.Context, string, string) ([]*controlmodel.AgentEndpoint, error)
	Update(context.Context, *controlmodel.AgentEndpoint, int64) (*controlmodel.AgentEndpoint, error)
	ReserveJob(context.Context, uuid.UUID, string) (*controlmodel.EndpointJob, bool, error)
	CompleteJob(context.Context, uuid.UUID, uuid.UUID, uuid.UUID) (*controlmodel.EndpointJob, error)
	GetJobByIssue(context.Context, uuid.UUID) (*controlmodel.EndpointJob, error)
}

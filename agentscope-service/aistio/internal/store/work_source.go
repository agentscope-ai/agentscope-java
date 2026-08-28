// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package store

import (
	"context"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

type WorkSourceRepository interface {
	CreateWorkSource(context.Context, *controlmodel.WorkSource) (*controlmodel.WorkSource, error)
	GetWorkSource(context.Context, uuid.UUID) (*controlmodel.WorkSource, error)
	ListWorkSources(context.Context, string, string) ([]*controlmodel.WorkSource, error)
	UpdateWorkSource(context.Context, *controlmodel.WorkSource, int64) (*controlmodel.WorkSource, error)

	BeginWebhookDelivery(context.Context, uuid.UUID, string, string) (*controlmodel.WebhookDelivery, bool, error)
	CompleteWebhookDelivery(context.Context, uuid.UUID) error
	FailWebhookDelivery(context.Context, uuid.UUID, string) error

	PutIssueExternalRef(context.Context, *controlmodel.IssueExternalRef) (*controlmodel.IssueExternalRef, error)
	GetIssueExternalRef(context.Context, uuid.UUID, string) (*controlmodel.IssueExternalRef, error)
	PutCommentExternalRef(context.Context, *controlmodel.CommentExternalRef) (*controlmodel.CommentExternalRef, error)
	ListExternalLinks(context.Context, uuid.UUID) ([]*controlmodel.ExternalLink, error)
	PutExternalLink(context.Context, *controlmodel.ExternalLink) (*controlmodel.ExternalLink, error)
}

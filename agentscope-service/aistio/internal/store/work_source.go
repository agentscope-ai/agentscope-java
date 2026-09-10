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
	GetIssueExternalRefByIssue(context.Context, uuid.UUID, uuid.UUID) (*controlmodel.IssueExternalRef, error)
	ListIssueExternalRefs(context.Context, uuid.UUID, int) ([]*controlmodel.IssueExternalRef, error)
	PutCommentExternalRef(context.Context, *controlmodel.CommentExternalRef) (*controlmodel.CommentExternalRef, error)
	GetCommentExternalRef(context.Context, uuid.UUID, uuid.UUID) (*controlmodel.CommentExternalRef, error)
	GetCommentExternalRefByExternalID(context.Context, uuid.UUID, string) (*controlmodel.CommentExternalRef, error)
	ListCommentExternalRefs(context.Context, []controlmodel.CommentSyncState, int) ([]*controlmodel.CommentExternalRef, error)
	ListExternalLinks(context.Context, uuid.UUID) ([]*controlmodel.ExternalLink, error)
	PutExternalLink(context.Context, *controlmodel.ExternalLink) (*controlmodel.ExternalLink, error)
}

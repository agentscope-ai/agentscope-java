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

type AccessRepository interface {
	GetNamespace(context.Context, string, string) (*controlmodel.Namespace, error)
	// ListNamespaces filters membership before pagination. Empty user is admin inventory.
	ListNamespaces(context.Context, string, string, int, int) ([]*controlmodel.Namespace, error)
	// PutNamespace uses optimistic concurrency; version zero means create only.
	PutNamespace(context.Context, *controlmodel.Namespace, int64, string) (*controlmodel.Namespace, error)
	TransferNamespace(context.Context, string, string, string, int64, string) (*controlmodel.Namespace, error)
	ListNamespaceAudit(context.Context, string, string, int, int) ([]*controlmodel.NamespaceAudit, error)
}

// WorkAccess is attached only by the authenticated API boundary. Empty Refs with
// Restricted=true grants no private work; absence is for trusted background work.
type WorkAccess struct {
	Refs       []string
	Restricted bool
}
type workAccessKey struct{}

func WithWorkAccess(ctx context.Context, access WorkAccess) context.Context {
	return context.WithValue(ctx, workAccessKey{}, access)
}
func WorkAccessFrom(ctx context.Context) WorkAccess {
	value, _ := ctx.Value(workAccessKey{}).(WorkAccess)
	return value
}

// CheckIssueWorkAccess protects service-level operations (including idempotent
// requests) whose target becomes known only after HTTP authorization.
func CheckIssueWorkAccess(ctx context.Context, repo CollaborationRepository, id uuid.UUID, write bool) error {
	access := WorkAccessFrom(ctx)
	if len(access.Refs) == 0 && !access.Restricted {
		return nil
	}
	seen := map[uuid.UUID]bool{}
	for !seen[id] {
		seen[id] = true
		v, e := repo.GetIssue(ctx, id)
		if e != nil {
			return e
		}
		if v.ParentIssueID != nil {
			id = *v.ParentIssueID
			continue
		}
		if v.Access.Allows(v.Creator, access.Refs, write) || !write && !access.Restricted {
			return nil
		}
		return ErrNotFound
	}
	return ErrNotFound
}

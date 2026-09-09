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

package memory

import (
	"context"
	"sort"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type workSourceRepo struct{ s *Store }

func cloneWorkSource(v *controlmodel.WorkSource) *controlmodel.WorkSource {
	c := *v
	c.Configuration = cloneJSON(v.Configuration)
	return &c
}

func issueRefKey(sourceID uuid.UUID, externalID string) string {
	return sourceID.String() + "\x00" + externalID
}

func (r *workSourceRepo) CreateWorkSource(_ context.Context, in *controlmodel.WorkSource) (*controlmodel.WorkSource, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for _, v := range r.s.workSources {
		if v.Tenant == in.Tenant && v.Namespace == in.Namespace && v.Kind == in.Kind && v.Name == in.Name && v.ArchivedAt == nil {
			return nil, store.ErrConflict
		}
	}
	now := time.Now().UTC()
	c := cloneWorkSource(in)
	if c.ID == uuid.Nil {
		c.ID = uuid.New()
	}
	c.Version, c.CreatedAt, c.UpdatedAt = 1, now, now
	r.s.workSources[c.ID] = c
	return cloneWorkSource(c), nil
}

func (r *workSourceRepo) GetWorkSource(_ context.Context, id uuid.UUID) (*controlmodel.WorkSource, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	v := r.s.workSources[id]
	if v == nil {
		return nil, store.ErrNotFound
	}
	return cloneWorkSource(v), nil
}

func (r *workSourceRepo) ListWorkSources(_ context.Context, tenant, namespace string) ([]*controlmodel.WorkSource, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*controlmodel.WorkSource{}
	for _, v := range r.s.workSources {
		if (tenant == "" || v.Tenant == tenant) && (namespace == "" || v.Namespace == namespace) && v.ArchivedAt == nil {
			out = append(out, cloneWorkSource(v))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UpdatedAt.After(out[j].UpdatedAt) })
	return out, nil
}

func (r *workSourceRepo) UpdateWorkSource(_ context.Context, in *controlmodel.WorkSource, expected int64) (*controlmodel.WorkSource, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	v := r.s.workSources[in.ID]
	if v == nil {
		return nil, store.ErrNotFound
	}
	if v.Version != expected {
		return nil, store.ErrConflict
	}
	c := cloneWorkSource(in)
	c.CreatedAt, c.Version, c.UpdatedAt = v.CreatedAt, v.Version+1, time.Now().UTC()
	r.s.workSources[c.ID] = c
	return cloneWorkSource(c), nil
}

func (r *workSourceRepo) BeginWebhookDelivery(_ context.Context, sourceID uuid.UUID, deliveryID, payloadHash string) (*controlmodel.WebhookDelivery, bool, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for _, v := range r.s.webhookDeliveries {
		if v.WorkSourceID == sourceID && v.DeliveryID == deliveryID {
			if v.PayloadHash != payloadHash {
				return nil, false, store.ErrConflict
			}
			if v.Status == "failed" {
				v.Status = "processing"
				v.Attempts++
				v.LastError = ""
				v.ProcessedAt = nil
				c := *v
				return &c, true, nil
			}
			c := *v
			return &c, false, nil
		}
	}
	v := &controlmodel.WebhookDelivery{ID: uuid.New(), WorkSourceID: sourceID, DeliveryID: deliveryID, PayloadHash: payloadHash, Status: "processing", Attempts: 1, ReceivedAt: time.Now().UTC()}
	r.s.webhookDeliveries[v.ID] = v
	c := *v
	return &c, true, nil
}

func (r *workSourceRepo) CompleteWebhookDelivery(_ context.Context, id uuid.UUID) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	v := r.s.webhookDeliveries[id]
	if v == nil {
		return store.ErrNotFound
	}
	now := time.Now().UTC()
	v.Status, v.ProcessedAt, v.LastError = "processed", &now, ""
	return nil
}

func (r *workSourceRepo) FailWebhookDelivery(_ context.Context, id uuid.UUID, message string) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	v := r.s.webhookDeliveries[id]
	if v == nil {
		return store.ErrNotFound
	}
	now := time.Now().UTC()
	v.Status, v.ProcessedAt, v.LastError = "failed", &now, message
	return nil
}

func cloneIssueRef(v *controlmodel.IssueExternalRef) *controlmodel.IssueExternalRef {
	c := *v
	c.Projection = cloneJSON(v.Projection)
	return &c
}
func (r *workSourceRepo) PutIssueExternalRef(_ context.Context, in *controlmodel.IssueExternalRef) (*controlmodel.IssueExternalRef, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	c := cloneIssueRef(in)
	c.UpdatedAt = time.Now().UTC()
	r.s.issueExternalRefs[issueRefKey(c.WorkSourceID, c.ExternalID)] = c
	return cloneIssueRef(c), nil
}
func (r *workSourceRepo) GetIssueExternalRef(_ context.Context, sourceID uuid.UUID, externalID string) (*controlmodel.IssueExternalRef, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	v := r.s.issueExternalRefs[issueRefKey(sourceID, externalID)]
	if v == nil {
		return nil, store.ErrNotFound
	}
	return cloneIssueRef(v), nil
}
func (r *workSourceRepo) GetIssueExternalRefByIssue(_ context.Context, sourceID, issueID uuid.UUID) (*controlmodel.IssueExternalRef, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	for _, v := range r.s.issueExternalRefs {
		if v.WorkSourceID == sourceID && v.IssueID == issueID {
			return cloneIssueRef(v), nil
		}
	}
	return nil, store.ErrNotFound
}
func (r *workSourceRepo) ListIssueExternalRefs(_ context.Context, sourceID uuid.UUID, limit int) ([]*controlmodel.IssueExternalRef, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*controlmodel.IssueExternalRef, 0)
	for _, ref := range r.s.issueExternalRefs {
		if ref.WorkSourceID == sourceID {
			out = append(out, cloneIssueRef(ref))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UpdatedAt.Before(out[j].UpdatedAt) })
	if limit > 0 && len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}
func (r *workSourceRepo) PutCommentExternalRef(_ context.Context, in *controlmodel.CommentExternalRef) (*controlmodel.CommentExternalRef, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	c := *in
	c.UpdatedAt = time.Now().UTC()
	r.s.commentExternalRefs[c.CommentID] = &c
	return &c, nil
}
func (r *workSourceRepo) GetCommentExternalRef(_ context.Context, sourceID, commentID uuid.UUID) (*controlmodel.CommentExternalRef, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	v := r.s.commentExternalRefs[commentID]
	if v == nil || v.WorkSourceID != sourceID {
		return nil, store.ErrNotFound
	}
	c := *v
	return &c, nil
}
func (r *workSourceRepo) GetCommentExternalRefByExternalID(_ context.Context, sourceID uuid.UUID, externalID string) (*controlmodel.CommentExternalRef, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	for _, v := range r.s.commentExternalRefs {
		if v.WorkSourceID == sourceID && v.ExternalID == externalID {
			c := *v
			return &c, nil
		}
	}
	return nil, store.ErrNotFound
}
func (r *workSourceRepo) ListCommentExternalRefs(_ context.Context, states []controlmodel.CommentSyncState, limit int) ([]*controlmodel.CommentExternalRef, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	allowed := make(map[controlmodel.CommentSyncState]bool, len(states))
	for _, state := range states {
		allowed[state] = true
	}
	out := make([]*controlmodel.CommentExternalRef, 0)
	for _, v := range r.s.commentExternalRefs {
		if len(allowed) > 0 && !allowed[v.SyncState] {
			continue
		}
		c := *v
		out = append(out, &c)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UpdatedAt.Before(out[j].UpdatedAt) })
	if limit > 0 && len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}
func (r *workSourceRepo) ListExternalLinks(_ context.Context, issueID uuid.UUID) ([]*controlmodel.ExternalLink, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*controlmodel.ExternalLink{}
	for _, v := range r.s.externalLinks {
		if v.IssueID == issueID {
			c := *v
			c.Metadata = cloneJSON(v.Metadata)
			out = append(out, &c)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.Before(out[j].CreatedAt) })
	return out, nil
}
func (r *workSourceRepo) PutExternalLink(_ context.Context, in *controlmodel.ExternalLink) (*controlmodel.ExternalLink, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	c := *in
	c.Metadata = cloneJSON(in.Metadata)
	if c.ID == uuid.Nil {
		c.ID = uuid.New()
	}
	now := time.Now().UTC()
	if c.CreatedAt.IsZero() {
		c.CreatedAt = now
	}
	c.UpdatedAt = now
	r.s.externalLinks[c.ID] = &c
	out := c
	out.Metadata = cloneJSON(c.Metadata)
	return &out, nil
}

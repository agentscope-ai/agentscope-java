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

// Package worksource owns the anti-corruption boundary between external Work
// systems and AgentScope's Issue projection.
package worksource

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type Event struct {
	DeliveryID string
	EventType  string
	Payload    []byte
}

type IssueCommand struct {
	IssueID uuid.UUID
	Action  string
	Payload json.RawMessage
}

type PublishedComment struct {
	ExternalID string
}

// Adapter is the v5 SPI. Implementations preserve external authority over Work
// fields while AgentScope owns execution, approvals, artifacts and audit facts.
type Adapter interface {
	HandleEvent(context.Context, *controlmodel.WorkSource, Event) error
	FetchWork(context.Context, *controlmodel.WorkSource, string) (*controlmodel.IssueExternalRef, error)
	ApplyIssueCommand(context.Context, *controlmodel.WorkSource, IssueCommand) error
	PublishComment(context.Context, *controlmodel.WorkSource, *controlmodel.Comment) (*PublishedComment, error)
	Reconcile(context.Context, *controlmodel.WorkSource) error
}

type Registry struct{ adapters map[string]Adapter }

func NewRegistry() *Registry                              { return &Registry{adapters: map[string]Adapter{}} }
func (r *Registry) Register(kind string, adapter Adapter) { r.adapters[kind] = adapter }
func (r *Registry) Resolve(kind string) (Adapter, bool)   { a, ok := r.adapters[kind]; return a, ok }

type Service struct {
	Store    store.Store
	Adapters *Registry
}

func VerifyGitHubSignature(secret string, body []byte, signature string) bool {
	if secret == "" || len(signature) < len("sha256=") || signature[:len("sha256=")] != "sha256=" {
		return false
	}
	want, err := hex.DecodeString(signature[len("sha256="):])
	if err != nil {
		return false
	}
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write(body)
	return hmac.Equal(want, mac.Sum(nil))
}

// HandleEvent provides delivery-ID dedupe around every adapter invocation.
func (s *Service) HandleEvent(ctx context.Context, source *controlmodel.WorkSource, event Event) error {
	if source == nil || !source.Enabled {
		return fmt.Errorf("work source is disabled")
	}
	adapter, ok := s.Adapters.Resolve(source.Kind)
	if !ok {
		return fmt.Errorf("unsupported work source kind %q", source.Kind)
	}
	h := sha256.Sum256(event.Payload)
	delivery, fresh, err := s.Store.WorkSources().BeginWebhookDelivery(ctx, source.ID, event.DeliveryID, hex.EncodeToString(h[:]))
	if err != nil || !fresh {
		return err
	}
	if err = adapter.HandleEvent(ctx, source, event); err != nil {
		_ = s.Store.WorkSources().FailWebhookDelivery(ctx, delivery.ID, err.Error())
		return err
	}
	return s.Store.WorkSources().CompleteWebhookDelivery(ctx, delivery.ID)
}

func (s *Service) sourceForIssue(ctx context.Context, issueID uuid.UUID) (*controlmodel.Issue, *controlmodel.WorkSource, error) {
	issue, err := s.Store.Collaboration().GetIssue(ctx, issueID)
	if err != nil {
		return nil, nil, err
	}
	if issue.SourceType == "" {
		return issue, nil, nil
	}
	if _, ok := s.Adapters.Resolve(issue.SourceType); !ok {
		return issue, nil, nil
	}
	sourceID, err := uuid.Parse(issue.SourceRef)
	if err != nil {
		return nil, nil, fmt.Errorf("invalid Work Source reference: %w", err)
	}
	source, err := s.Store.WorkSources().GetWorkSource(ctx, sourceID)
	if err != nil {
		return nil, nil, err
	}
	if !source.Enabled || source.Kind != issue.SourceType {
		return nil, nil, fmt.Errorf("Work Source is disabled or mismatched")
	}
	return issue, source, nil
}

// ApplyIssueCommand changes an externally authoritative Issue before its local
// projection is updated. A transport failure therefore leaves the projection
// unchanged.
func (s *Service) ApplyIssueCommand(ctx context.Context, issueID uuid.UUID, action string, payload json.RawMessage) error {
	_, source, err := s.sourceForIssue(ctx, issueID)
	if err != nil || source == nil {
		return err
	}
	adapter, ok := s.Adapters.Resolve(source.Kind)
	if !ok {
		return fmt.Errorf("unsupported work source kind %q", source.Kind)
	}
	return adapter.ApplyIssueCommand(ctx, source, IssueCommand{IssueID: issueID, Action: action, Payload: payload})
}

// QueueComment records the durable pending_sync state after the local Comment
// transaction commits. The periodic publisher owns retries and final IDs.
func (s *Service) QueueComment(ctx context.Context, comment *controlmodel.Comment) error {
	if comment == nil {
		return nil
	}
	_, source, err := s.sourceForIssue(ctx, comment.IssueID)
	if err != nil || source == nil {
		return err
	}
	_, err = s.Store.WorkSources().PutCommentExternalRef(ctx, &controlmodel.CommentExternalRef{
		WorkSourceID: source.ID,
		CommentID:    comment.ID,
		SyncState:    controlmodel.CommentPendingSync,
	})
	return err
}

// FlushPendingComments publishes a bounded page of pending/failed comments.
// Failure state is durable and retried on a later pass.
func (s *Service) FlushPendingComments(ctx context.Context, limit int) (int, error) {
	refs, err := s.Store.WorkSources().ListCommentExternalRefs(ctx, []controlmodel.CommentSyncState{
		controlmodel.CommentPendingSync, controlmodel.CommentSyncFailed,
	}, limit)
	if err != nil {
		return 0, err
	}
	published := 0
	for _, ref := range refs {
		comment, loadErr := s.Store.Collaboration().GetComment(ctx, ref.CommentID)
		if loadErr != nil {
			ref.Attempts++
			ref.SyncState, ref.LastError = controlmodel.CommentSyncFailed, loadErr.Error()
			_, _ = s.Store.WorkSources().PutCommentExternalRef(ctx, ref)
			continue
		}
		source, loadErr := s.Store.WorkSources().GetWorkSource(ctx, ref.WorkSourceID)
		if loadErr != nil || !source.Enabled {
			if loadErr == nil {
				loadErr = fmt.Errorf("Work Source is disabled")
			}
			ref.Attempts++
			ref.SyncState, ref.LastError = controlmodel.CommentSyncFailed, loadErr.Error()
			_, _ = s.Store.WorkSources().PutCommentExternalRef(ctx, ref)
			continue
		}
		adapter, ok := s.Adapters.Resolve(source.Kind)
		if !ok {
			loadErr = fmt.Errorf("unsupported work source kind %q", source.Kind)
		} else {
			var result *PublishedComment
			result, loadErr = adapter.PublishComment(ctx, source, comment)
			if loadErr == nil && result != nil {
				ref.ExternalID = result.ExternalID
			}
		}
		ref.Attempts++
		if loadErr != nil {
			ref.SyncState, ref.LastError = controlmodel.CommentSyncFailed, loadErr.Error()
		} else {
			ref.SyncState, ref.LastError = controlmodel.CommentSynced, ""
			published++
		}
		if _, saveErr := s.Store.WorkSources().PutCommentExternalRef(ctx, ref); saveErr != nil && err == nil {
			err = saveErr
		}
	}
	return published, err
}

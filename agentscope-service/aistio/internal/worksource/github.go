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

package worksource

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// GitHubAdapter projects authoritative issue webhook fields. Outbound API
// transport is injected so tests and enterprise GitHub installations share the
// same synchronization semantics.
type GitHubTransport interface {
	FetchIssue(context.Context, *controlmodel.WorkSource, string) (*controlmodel.IssueExternalRef, error)
	ApplyIssueCommand(context.Context, *controlmodel.WorkSource, *controlmodel.IssueExternalRef, IssueCommand) error
	PublishComment(context.Context, *controlmodel.WorkSource, *controlmodel.IssueExternalRef, *controlmodel.Comment) (*PublishedComment, error)
	Reconcile(context.Context, *controlmodel.WorkSource) error
}

type GitHubAdapter struct {
	Store     store.Store
	Transport GitHubTransport
}

type githubIssue struct {
	ID        int64  `json:"id"`
	Number    int64  `json:"number"`
	Title     string `json:"title"`
	Body      string `json:"body"`
	State     string `json:"state"`
	HTMLURL   string `json:"html_url"`
	UpdatedAt string `json:"updated_at"`
}

type githubIssueEvent struct {
	Action string      `json:"action"`
	Issue  githubIssue `json:"issue"`
}

type githubIssueCommentEvent struct {
	Action string `json:"action"`
	Issue  struct {
		ID int64 `json:"id"`
	} `json:"issue"`
	Comment struct {
		ID        int64  `json:"id"`
		Body      string `json:"body"`
		HTMLURL   string `json:"html_url"`
		UpdatedAt string `json:"updated_at"`
		User      struct {
			Login string `json:"login"`
		} `json:"user"`
	} `json:"comment"`
}

func (a *GitHubAdapter) HandleEvent(ctx context.Context, source *controlmodel.WorkSource, event Event) error {
	if event.EventType == "issue_comment" {
		return a.handleIssueComment(ctx, source, event)
	}
	if event.EventType != "issues" {
		return nil
	}
	var payload githubIssueEvent
	if err := json.Unmarshal(event.Payload, &payload); err != nil {
		return fmt.Errorf("decode GitHub issue webhook: %w", err)
	}
	externalID := strconv.FormatInt(payload.Issue.ID, 10)
	ref, err := a.Store.WorkSources().GetIssueExternalRef(ctx, source.ID, externalID)
	if err != nil && err != store.ErrNotFound {
		return err
	}
	status := controlmodel.IssueTodo
	if payload.Issue.State == "closed" {
		status = controlmodel.IssueDone
	}
	actor := controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "work-source:" + source.ID.String()}
	if ref == nil {
		issue, createErr := a.Store.Collaboration().CreateIssue(ctx, &controlmodel.Issue{ID: uuid.New(), Tenant: source.Tenant, Namespace: source.Namespace, Identifier: "GH-" + strconv.FormatInt(payload.Issue.Number, 10), Title: payload.Issue.Title, Description: payload.Issue.Body, Status: status, Priority: "normal", Creator: actor, SourceType: "github", SourceRef: source.ID.String()})
		if createErr != nil {
			return createErr
		}
		ref = &controlmodel.IssueExternalRef{WorkSourceID: source.ID, IssueID: issue.ID, ExternalID: externalID}
	} else {
		if ref.ExternalVersion != "" && payload.Issue.UpdatedAt != "" && ref.ExternalVersion >= payload.Issue.UpdatedAt {
			return nil
		}
		issue, getErr := a.Store.Collaboration().GetIssue(ctx, ref.IssueID)
		if getErr != nil {
			return getErr
		}
		issue.Title, issue.Description, issue.Status = payload.Issue.Title, payload.Issue.Body, status
		if _, err = a.Store.Collaboration().UpdateIssue(ctx, issue, issue.Version, actor); err != nil {
			return err
		}
	}
	ref.ExternalNumber, ref.ExternalURL, ref.ExternalVersion = strconv.FormatInt(payload.Issue.Number, 10), payload.Issue.HTMLURL, payload.Issue.UpdatedAt
	ref.Projection = append(ref.Projection[:0], event.Payload...)
	_, err = a.Store.WorkSources().PutIssueExternalRef(ctx, ref)
	return err
}

func (a *GitHubAdapter) handleIssueComment(ctx context.Context, source *controlmodel.WorkSource, event Event) error {
	var payload githubIssueCommentEvent
	if err := json.Unmarshal(event.Payload, &payload); err != nil {
		return fmt.Errorf("decode GitHub issue_comment webhook: %w", err)
	}
	issueRef, err := a.Store.WorkSources().GetIssueExternalRef(ctx, source.ID, strconv.FormatInt(payload.Issue.ID, 10))
	if err != nil {
		return err
	}
	externalCommentID := strconv.FormatInt(payload.Comment.ID, 10)
	commentID := uuid.NewSHA1(source.ID, []byte("github-comment:"+externalCommentID))
	var commentRef *controlmodel.CommentExternalRef
	if existingRef, refErr := a.Store.WorkSources().GetCommentExternalRefByExternalID(ctx, source.ID, externalCommentID); refErr == nil {
		commentRef = existingRef
		commentID = existingRef.CommentID
		if existingRef.ExternalVersion != "" && payload.Comment.UpdatedAt != "" && existingRef.ExternalVersion >= payload.Comment.UpdatedAt {
			return nil
		}
	} else if refErr != store.ErrNotFound {
		return refErr
	}
	if payload.Action == "deleted" {
		if existing, loadErr := a.Store.Collaboration().GetComment(ctx, commentID); loadErr == nil {
			_, err = a.Store.Collaboration().DeleteComment(ctx, existing.ID, existing.Version,
				controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "work-source:" + source.ID.String()})
		} else if loadErr == store.ErrNotFound {
			return nil
		} else {
			return loadErr
		}
		if err != nil {
			return err
		}
		if commentRef == nil {
			commentRef = &controlmodel.CommentExternalRef{WorkSourceID: source.ID, CommentID: commentID, ExternalID: externalCommentID}
		}
		commentRef.ExternalVersion, commentRef.SyncState = payload.Comment.UpdatedAt, controlmodel.CommentSynced
		_, err = a.Store.WorkSources().PutCommentExternalRef(ctx, commentRef)
		return err
	}
	existing, err := a.Store.Collaboration().GetComment(ctx, commentID)
	if err != nil && err != store.ErrNotFound {
		return err
	}
	if existing == nil {
		created, createErr := a.Store.Collaboration().CreateComment(ctx, store.CreateCommentRequest{Comment: &controlmodel.Comment{
			ID: commentID, IssueID: issueRef.IssueID,
			Author:  controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "github:" + payload.Comment.User.Login},
			Content: payload.Comment.Body, Type: controlmodel.CommentGeneral,
		}})
		if createErr != nil {
			return createErr
		}
		existing = created.Comment
	} else if existing.Content != payload.Comment.Body {
		existing.Content = payload.Comment.Body
		existing, err = a.Store.Collaboration().UpdateComment(ctx, existing, existing.Version)
		if err != nil {
			return err
		}
	}
	_, err = a.Store.WorkSources().PutCommentExternalRef(ctx, &controlmodel.CommentExternalRef{
		WorkSourceID: source.ID, CommentID: existing.ID,
		ExternalID: externalCommentID, ExternalVersion: payload.Comment.UpdatedAt,
		SyncState: controlmodel.CommentSynced,
	})
	return err
}
func (a *GitHubAdapter) FetchWork(ctx context.Context, source *controlmodel.WorkSource, id string) (*controlmodel.IssueExternalRef, error) {
	if a.Transport == nil {
		return nil, fmt.Errorf("GitHub transport unavailable")
	}
	return a.Transport.FetchIssue(ctx, source, id)
}
func (a *GitHubAdapter) ApplyIssueCommand(ctx context.Context, source *controlmodel.WorkSource, command IssueCommand) error {
	if a.Transport == nil {
		return fmt.Errorf("GitHub transport unavailable")
	}
	ref, err := a.Store.WorkSources().GetIssueExternalRefByIssue(ctx, source.ID, command.IssueID)
	if err != nil {
		return err
	}
	return a.Transport.ApplyIssueCommand(ctx, source, ref, command)
}
func (a *GitHubAdapter) PublishComment(ctx context.Context, source *controlmodel.WorkSource, comment *controlmodel.Comment) (*PublishedComment, error) {
	if a.Transport == nil {
		return nil, fmt.Errorf("GitHub transport unavailable")
	}
	ref, err := a.Store.WorkSources().GetIssueExternalRefByIssue(ctx, source.ID, comment.IssueID)
	if err != nil {
		return nil, err
	}
	return a.Transport.PublishComment(ctx, source, ref, comment)
}
func (a *GitHubAdapter) Reconcile(ctx context.Context, source *controlmodel.WorkSource) error {
	if a.Transport == nil {
		return fmt.Errorf("GitHub transport unavailable")
	}
	refs, err := a.Store.WorkSources().ListIssueExternalRefs(ctx, source.ID, 500)
	if err != nil {
		return err
	}
	for _, ref := range refs {
		remote, fetchErr := a.Transport.FetchIssue(ctx, source, ref.ExternalNumber)
		if fetchErr != nil {
			return fetchErr
		}
		if remote.ExternalVersion != "" && ref.ExternalVersion >= remote.ExternalVersion {
			continue
		}
		var issue githubIssue
		if json.Unmarshal(remote.Projection, &issue) != nil {
			return fmt.Errorf("GitHub reconcile response has no Issue projection")
		}
		payload, _ := json.Marshal(githubIssueEvent{Action: "reconcile", Issue: issue})
		if err = a.HandleEvent(ctx, source, Event{EventType: "issues", Payload: payload}); err != nil {
			return err
		}
	}
	return a.Transport.Reconcile(ctx, source)
}

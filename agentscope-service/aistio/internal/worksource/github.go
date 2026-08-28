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
	ApplyIssueCommand(context.Context, *controlmodel.WorkSource, IssueCommand) error
	PublishComment(context.Context, *controlmodel.WorkSource, *controlmodel.Comment) (*PublishedComment, error)
	Reconcile(context.Context, *controlmodel.WorkSource) error
}

type GitHubAdapter struct {
	Store     store.Store
	Transport GitHubTransport
}

type githubIssueEvent struct {
	Action string `json:"action"`
	Issue  struct {
		ID        int64  `json:"id"`
		Number    int64  `json:"number"`
		Title     string `json:"title"`
		Body      string `json:"body"`
		State     string `json:"state"`
		HTMLURL   string `json:"html_url"`
		UpdatedAt string `json:"updated_at"`
	} `json:"issue"`
}

func (a *GitHubAdapter) HandleEvent(ctx context.Context, source *controlmodel.WorkSource, event Event) error {
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
	return a.Transport.ApplyIssueCommand(ctx, source, command)
}
func (a *GitHubAdapter) PublishComment(ctx context.Context, source *controlmodel.WorkSource, comment *controlmodel.Comment) (*PublishedComment, error) {
	if a.Transport == nil {
		return nil, fmt.Errorf("GitHub transport unavailable")
	}
	return a.Transport.PublishComment(ctx, source, comment)
}
func (a *GitHubAdapter) Reconcile(ctx context.Context, source *controlmodel.WorkSource) error {
	if a.Transport == nil {
		return fmt.Errorf("GitHub transport unavailable")
	}
	return a.Transport.Reconcile(ctx, source)
}

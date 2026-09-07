// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package store

import (
	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"testing"
)

func TestDelegatedIssueEnteringReviewNotifiesAccountableHuman(t *testing.T) {
	parent := uuid.New()
	issue := &model.Issue{ID: uuid.New(), ParentIssueID: &parent, Status: model.IssueInReview, Kind: model.IssueKindUserWork, Visibility: model.IssueVisibilityWorkHub, CompletionPolicy: model.IssueCompletionReview, AssigneeType: model.AssigneeAgent, AssigneeRef: "worker"}
	items := IssueInboxItems(issue, model.IssueInProgress, model.Actor{Type: model.ActorAgent, Ref: "worker"}, "review result", "owner", nil)
	if len(items) != 1 || items[0].Type != "review_request" || !items[0].NeedsAction || items[0].RecipientRef != "owner" {
		t.Fatalf("review notification missing: %+v", items)
	}
	issue.Status = model.IssueBlocked
	if items := IssueInboxItems(issue, model.IssueInProgress, model.Actor{Type: model.ActorAgent}, "internal blocker", "owner", nil); len(items) != 0 {
		t.Fatal("ordinary child blocker leaked to human")
	}
	issue.Status = model.IssueInReview
	issue.Kind = model.IssueKindEndpointJob
	if items := IssueInboxItems(issue, model.IssueInProgress, model.Actor{Type: model.ActorAgent}, "operational", "owner", nil); len(items) != 0 {
		t.Fatal("operational job leaked into Work Inbox")
	}
}

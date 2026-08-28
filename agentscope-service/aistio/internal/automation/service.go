// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

// Package automation turns durable Cron/Webhook/Channel ingress into the same
// Issue/Comment/AgentTask collaboration path used by humans and agents.
package automation

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/orchestration"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type Service struct{ Store store.Store }

type IssueAction struct {
	Title              string                        `json:"title"`
	Description        string                        `json:"description,omitempty"`
	Priority           string                        `json:"priority,omitempty"`
	AssigneeType       controlmodel.AssigneeType     `json:"assigneeType,omitempty"`
	AssigneeRef        string                        `json:"assigneeRef,omitempty"`
	AcceptanceCriteria json.RawMessage               `json:"acceptanceCriteria,omitempty"`
	ContextRefs        json.RawMessage               `json:"contextRefs,omitempty"`
	InitialComment     string                        `json:"initialComment,omitempty"`
	Mentions           []collaboration.MentionTarget `json:"mentions,omitempty"`
}
type CommentAction struct {
	IssueID  uuid.UUID                     `json:"issueId"`
	ParentID *uuid.UUID                    `json:"parentId,omitempty"`
	Content  string                        `json:"content"`
	Mentions []collaboration.MentionTarget `json:"mentions,omitempty"`
}

type StartOrchestrationAction struct {
	DefinitionID uuid.UUID           `json:"definitionId"`
	RevisionID   *uuid.UUID          `json:"revisionId,omitempty"`
	IssueID      *uuid.UUID          `json:"issueId,omitempty"`
	Issue        *controlmodel.Issue `json:"issue,omitempty"`
	Input        json.RawMessage     `json:"input,omitempty"`
}

type SignalOrchestrationAction struct {
	RunID   uuid.UUID       `json:"runId"`
	Name    string          `json:"name"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

func (s *Service) Create(ctx context.Context, item *controlmodel.Automation) (*controlmodel.Automation, error) {
	if s == nil || s.Store == nil {
		return nil, fmt.Errorf("automation store is unavailable")
	}
	if item == nil || strings.TrimSpace(item.Name) == "" {
		return nil, fmt.Errorf("name is required")
	}
	if item.TriggerType != controlmodel.AutomationTriggerCron && item.TriggerType != controlmodel.AutomationTriggerWebhook && item.TriggerType != controlmodel.AutomationTriggerChannel {
		return nil, fmt.Errorf("unsupported triggerType %q", item.TriggerType)
	}
	if err := validateAction(item.ActionType, item.ActionConfig); err != nil {
		return nil, err
	}
	if item.TriggerType == controlmodel.AutomationTriggerCron {
		next, err := nextCron(item.TriggerConfig, time.Now().UTC())
		if err != nil {
			return nil, err
		}
		item.NextRunAt = &next
	}
	return s.Store.Collaboration().CreateAutomation(ctx, item)
}

func (s *Service) Trigger(ctx context.Context, id uuid.UUID, triggerRef, idempotencyKey string, input json.RawMessage) (*controlmodel.AutomationRun, error) {
	automation, err := s.Store.Collaboration().GetAutomation(ctx, id)
	if err != nil {
		return nil, err
	}
	if !automation.Enabled || automation.ArchivedAt != nil {
		return nil, fmt.Errorf("automation is disabled")
	}
	if idempotencyKey == "" {
		return nil, fmt.Errorf("idempotency key is required")
	}
	run, created, err := s.Store.Collaboration().BeginAutomationRun(ctx, &controlmodel.AutomationRun{AutomationID: id, Tenant: automation.Tenant, Namespace: automation.Namespace, TriggerType: automation.TriggerType, TriggerRef: triggerRef, IdempotencyKey: idempotencyKey, Input: input})
	if err != nil || !created {
		return run, err
	}
	finishFailure := func(code string, cause error) (*controlmodel.AutomationRun, error) {
		run.Status = controlmodel.AutomationRunFailed
		run.ErrorCode, run.ErrorMessage = code, cause.Error()
		finished, finishErr := s.Store.Collaboration().FinishAutomationRun(ctx, run)
		if finishErr != nil {
			return nil, finishErr
		}
		return finished, cause
	}
	collab := &collaboration.Service{Store: s.Store}
	switch automation.ActionType {
	case controlmodel.AutomationCreateIssue:
		var action IssueAction
		if err = json.Unmarshal(automation.ActionConfig, &action); err != nil {
			return finishFailure("invalid_action", err)
		}
		issue, task, createErr := collab.CreateIssue(ctx, collaboration.CreateIssueRequest{Tenant: automation.Tenant, Namespace: automation.Namespace, Title: action.Title, Description: action.Description, Priority: action.Priority, Creator: controlmodel.Actor{Type: controlmodel.ActorAutomation, Ref: automation.ID.String()}, AssigneeType: action.AssigneeType, AssigneeRef: action.AssigneeRef, AcceptanceCriteria: action.AcceptanceCriteria, ContextRefs: action.ContextRefs, SourceType: "automation", SourceRef: run.ID.String()})
		if createErr != nil {
			return finishFailure("create_issue_failed", createErr)
		}
		run.IssueID = &issue.ID
		if task != nil {
			run.AgentTaskID = &task.ID
		}
		if strings.TrimSpace(action.InitialComment) != "" {
			if _, commentErr := collab.AddComment(ctx, collaboration.AddCommentRequest{IssueID: issue.ID, Author: controlmodel.Actor{Type: controlmodel.ActorAutomation, Ref: automation.ID.String()}, Content: action.InitialComment, Mentions: action.Mentions}); commentErr != nil {
				return finishFailure("create_comment_failed", commentErr)
			}
		}
	case controlmodel.AutomationAddComment:
		var action CommentAction
		if err = json.Unmarshal(automation.ActionConfig, &action); err != nil {
			return finishFailure("invalid_action", err)
		}
		result, commentErr := collab.AddComment(ctx, collaboration.AddCommentRequest{IssueID: action.IssueID, ParentID: action.ParentID, Author: controlmodel.Actor{Type: controlmodel.ActorAutomation, Ref: automation.ID.String()}, Content: action.Content, Mentions: action.Mentions})
		if commentErr != nil {
			return finishFailure("create_comment_failed", commentErr)
		}
		run.IssueID = &action.IssueID
		if len(result.Tasks) > 0 {
			run.AgentTaskID = &result.Tasks[0].ID
		}
	case controlmodel.AutomationStartRun:
		var action StartOrchestrationAction
		if err = json.Unmarshal(automation.ActionConfig, &action); err != nil {
			return finishFailure("invalid_action", err)
		}
		runInput := action.Input
		if len(input) > 0 {
			runInput = input
		}
		started, startErr := (&orchestration.Service{Store: s.Store}).Start(ctx, action.DefinitionID,
			orchestration.StartRequest{RevisionID: action.RevisionID, IdempotencyKey: "automation:" + automation.ID.String() + ":" + idempotencyKey,
				Input: runInput, IssueID: action.IssueID, Issue: action.Issue, TriggerType: "automation", TriggerRef: run.ID.String(),
				Actor: controlmodel.Actor{Type: controlmodel.ActorAutomation, Ref: automation.ID.String()}})
		if startErr != nil {
			return finishFailure("start_orchestration_failed", startErr)
		}
		run.OrchestrationRunID, run.IssueID = &started.ID, &started.RootIssueID
	case controlmodel.AutomationSignalRun:
		var action SignalOrchestrationAction
		if err = json.Unmarshal(automation.ActionConfig, &action); err != nil {
			return finishFailure("invalid_action", err)
		}
		payload := action.Payload
		if len(input) > 0 {
			payload = input
		}
		if signalErr := (&orchestration.Service{Store: s.Store}).Signal(ctx, action.RunID, action.Name,
			"automation:"+automation.ID.String()+":"+idempotencyKey, payload,
			controlmodel.Actor{Type: controlmodel.ActorAutomation, Ref: automation.ID.String()}); signalErr != nil {
			return finishFailure("signal_orchestration_failed", signalErr)
		}
		run.OrchestrationRunID = &action.RunID
	default:
		return finishFailure("invalid_action", fmt.Errorf("unsupported actionType %q", automation.ActionType))
	}
	run.Status = controlmodel.AutomationRunCompleted
	run.Output = []byte(`{"accepted":true}`)
	return s.Store.Collaboration().FinishAutomationRun(ctx, run)
}

func (s *Service) RunDue(ctx context.Context, now time.Time, limit int) (int, error) {
	enabled := true
	items, err := s.Store.Collaboration().ListAutomations(ctx, store.AutomationFilter{Enabled: &enabled, DueBefore: &now, Limit: limit})
	if err != nil {
		return 0, err
	}
	count := 0
	for _, item := range items {
		if item.TriggerType != controlmodel.AutomationTriggerCron || item.NextRunAt == nil {
			continue
		}
		scheduled := *item.NextRunAt
		next, nextErr := nextCron(item.TriggerConfig, scheduled)
		if nextErr != nil {
			continue
		}
		copy := *item
		copy.NextRunAt = &next
		if _, updateErr := s.Store.Collaboration().UpdateAutomation(ctx, &copy, item.Version); updateErr != nil {
			continue
		}
		key := "cron:" + scheduled.UTC().Format(time.RFC3339Nano)
		if _, triggerErr := s.Trigger(ctx, item.ID, key, key, nil); triggerErr == nil {
			count++
		}
	}
	return count, nil
}

func validateAction(kind controlmodel.AutomationActionType, raw json.RawMessage) error {
	switch kind {
	case controlmodel.AutomationCreateIssue:
		var v IssueAction
		if err := json.Unmarshal(raw, &v); err != nil {
			return err
		}
		if strings.TrimSpace(v.Title) == "" {
			return fmt.Errorf("actionConfig.title is required")
		}
	case controlmodel.AutomationAddComment:
		var v CommentAction
		if err := json.Unmarshal(raw, &v); err != nil {
			return err
		}
		if v.IssueID == uuid.Nil || strings.TrimSpace(v.Content) == "" {
			return fmt.Errorf("actionConfig.issueId and content are required")
		}
	case controlmodel.AutomationStartRun:
		var v StartOrchestrationAction
		if err := json.Unmarshal(raw, &v); err != nil {
			return err
		}
		if v.DefinitionID == uuid.Nil || (v.IssueID == nil) == (v.Issue == nil) {
			return fmt.Errorf("actionConfig.definitionId and exactly one of issueId or issue are required")
		}
	case controlmodel.AutomationSignalRun:
		var v SignalOrchestrationAction
		if err := json.Unmarshal(raw, &v); err != nil {
			return err
		}
		if v.RunID == uuid.Nil || strings.TrimSpace(v.Name) == "" {
			return fmt.Errorf("actionConfig.runId and name are required")
		}
	default:
		return fmt.Errorf("unsupported actionType %q", kind)
	}
	return nil
}

// nextCron intentionally supports deterministic, reviewable schedules without
// embedding a second workflow engine: @every duration, */N minutes, or exact
// "minute hour * * *" UTC schedules.
func nextCron(raw json.RawMessage, after time.Time) (time.Time, error) {
	var config struct {
		Schedule string `json:"schedule"`
	}
	if err := json.Unmarshal(raw, &config); err != nil {
		return time.Time{}, err
	}
	schedule := strings.TrimSpace(config.Schedule)
	if strings.HasPrefix(schedule, "@every ") {
		d, err := time.ParseDuration(strings.TrimSpace(strings.TrimPrefix(schedule, "@every ")))
		if err != nil || d < time.Minute {
			return time.Time{}, fmt.Errorf("cron @every duration must be at least one minute")
		}
		return after.Add(d), nil
	}
	parts := strings.Fields(schedule)
	if len(parts) != 5 || parts[2] != "*" || parts[3] != "*" || parts[4] != "*" {
		return time.Time{}, fmt.Errorf("cron schedule must be @every, */N * * * *, or minute hour * * *")
	}
	base := after.UTC().Truncate(time.Minute).Add(time.Minute)
	if strings.HasPrefix(parts[0], "*/") && parts[1] == "*" {
		n, err := strconv.Atoi(strings.TrimPrefix(parts[0], "*/"))
		if err != nil || n < 1 || n > 59 {
			return time.Time{}, fmt.Errorf("invalid cron minute interval")
		}
		for i := 0; i <= 60; i++ {
			if base.Minute()%n == 0 {
				return base, nil
			}
			base = base.Add(time.Minute)
		}
	}
	minute, mErr := strconv.Atoi(parts[0])
	hour, hErr := strconv.Atoi(parts[1])
	if mErr != nil || hErr != nil || minute < 0 || minute > 59 || hour < 0 || hour > 23 {
		return time.Time{}, fmt.Errorf("invalid cron minute/hour")
	}
	candidate := time.Date(base.Year(), base.Month(), base.Day(), hour, minute, 0, 0, time.UTC)
	if candidate.Before(base) {
		candidate = candidate.Add(24 * time.Hour)
	}
	return candidate, nil
}

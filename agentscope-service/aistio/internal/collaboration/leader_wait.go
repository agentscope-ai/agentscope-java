// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package collaboration

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// PendingDelegationTasks uses durable lineage, not words such as "waiting" in
// model output. A result already queued for another leader turn is also a wake
// source: the worker can finish between the mention and the leader yielding.
func (s *Service) PendingDelegationTasks(ctx context.Context, task *controlmodel.AgentTask) ([]uuid.UUID, error) {
	if task == nil || !task.LeaderTask || task.TeamID == nil {
		return nil, nil
	}
	tasks, err := s.listAllTasks(ctx, store.AgentTaskFilter{Tenant: task.Tenant, Namespace: task.Namespace, RunID: task.OrchestrationRunID})
	if err != nil {
		return nil, err
	}
	delegated := map[uuid.UUID]bool{}
	pending := []uuid.UUID{}
	for _, candidate := range tasks {
		if candidate.LeaderTask || candidate.TeamID == nil || *candidate.TeamID != *task.TeamID {
			continue
		}
		if candidate.ParentTaskID != nil && *candidate.ParentTaskID == task.ID || candidate.DelegatedFromTaskID != nil && *candidate.DelegatedFromTaskID == task.ID {
			delegated[candidate.ID] = true
			if !controlmodel.IsAgentTaskTerminal(candidate.Status) {
				pending = append(pending, candidate.ID)
			}
		}
	}
	for _, candidate := range tasks {
		if candidate.ID == task.ID || !candidate.LeaderTask || candidate.RunNodeID != task.RunNodeID || controlmodel.IsAgentTaskTerminal(candidate.Status) || candidate.TriggerCommentID == nil {
			continue
		}
		comment, err := s.Store.Collaboration().GetComment(ctx, *candidate.TriggerCommentID)
		if err != nil {
			return nil, err
		}
		if comment.SourceTaskID != nil && delegated[*comment.SourceTaskID] {
			pending = append(pending, candidate.ID)
		}
	}
	return pending, nil
}

// WaitForDelegatedWork completes only the leader's physical turn. Its shared
// coordinator node remains waiting and the worker outcome owns the next wake.
// The status comment deliberately has no routes, avoiding self-wake loops.
func (s *Service) WaitForDelegatedWork(ctx context.Context, taskID uuid.UUID, summary string) (*controlmodel.AgentTask, *controlmodel.Comment, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, nil, err
	}
	pending, err := s.PendingDelegationTasks(ctx, task)
	if err != nil {
		return nil, nil, err
	}
	if len(pending) == 0 {
		return nil, nil, fmt.Errorf("waiting requires outstanding work delegated by this Team leader or its queued outcome; read task.get and decide the current result")
	}
	summary = strings.TrimSpace(summary)
	if summary == "" {
		summary = "Waiting for delegated work; the coordinator will resume when its outcome arrives."
	}
	result, _ := json.Marshal(map[string]any{"outcome": "waiting", "summary": summary, "waitingForTaskIds": pending})
	completion := store.TaskCompletion{ExpectedVersion: task.Version, Summary: summary, Result: result}
	for _, input := range task.Inputs {
		completion.ProcessedInputIDs = append(completion.ProcessedInputIDs, input.ID)
	}
	if task.CurrentAttemptID != nil {
		attempt, err := s.Store.ExecutionAttempts().Get(ctx, *task.CurrentAttemptID)
		if err != nil {
			return nil, nil, err
		}
		completion.AttemptID = attempt.ID
		completion.DispatchGeneration = attempt.DispatchGeneration
	}
	actor := controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
	completed, comment, err := s.Store.Collaboration().CompleteAgentTaskWithComment(ctx, task.ID, completion, &controlmodel.Comment{IssueID: task.IssueID, Author: actor, Type: controlmodel.CommentStatus, Content: summary, SourceTaskID: &task.ID}, nil)
	if err != nil {
		return completed, comment, err
	}
	_, err = s.Store.Orchestration().AppendRunEvent(context.WithoutCancel(ctx), &controlmodel.RunEvent{RunID: task.OrchestrationRunID, Tenant: task.Tenant, Namespace: task.Namespace, NodeID: &task.RunNodeID, AgentTaskID: &task.ID, AttemptID: task.CurrentAttemptID, Type: "coordinator.waiting", Actor: actor, Payload: result, IdempotencyKey: "coordinator-waiting:" + task.ID.String()})
	return completed, comment, err
}

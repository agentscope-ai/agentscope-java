// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// concludeCoordinator completes the leader's semantic obligation before
// exposing a successful coordinator node. It is deliberately a convergent
// composite until the store grows a single cross-aggregate transaction: if the
// second step fails, a completed task plus waiting node is safe and retryable;
// the inverse order would expose a successful Run with a live task/attempt.
func (s *Server) concludeCoordinator(ctx context.Context, task *controlmodel.AgentTask,
	output json.RawMessage, actor controlmodel.Actor) (*controlmodel.AgentTask, *controlmodel.RunNode, error) {
	if task == nil {
		return nil, nil, fmt.Errorf("Team leader task is required")
	}
	current, err := s.store.Collaboration().GetAgentTask(ctx, task.ID)
	if err != nil {
		return nil, nil, err
	}
	var resultComment *controlmodel.Comment
	if current.Status != controlmodel.AgentTaskCompleted {
		if controlmodel.IsAgentTaskTerminal(current.Status) {
			return current, nil, fmt.Errorf("coordinator leader task ended in state %s", current.Status)
		}
		if _, _, err = s.orchestrationService().ValidateCoordinatorNodeCompletion(ctx, current.ID); err != nil {
			return current, nil, err
		}
		current, resultComment, err = s.collaborationService().CompleteTask(ctx, current.ID, store.TaskCompletion{
			ExpectedVersion: current.Version,
			Summary:         "Team coordinator converged.",
			Result:          output,
		}, actor)
		if err != nil {
			return current, nil, err
		}
	}

	// Completing an Attempt may cause its runtime to close the incoming request.
	// Finish the durable coordinator transition independently of that transport.
	commitCtx := context.WithoutCancel(ctx)
	node, err := s.orchestrationService().CompleteCoordinatorNode(commitCtx, current.ID, output, actor)
	if err != nil {
		return current, nil, err
	}
	if err = s.projectCoordinatorOutcomeToRoot(commitCtx, current, output, "", "", actor); err != nil {
		return current, node, err
	}
	if projectionErr := s.projectMCPTaskTerminal(ctx, current, resultComment); projectionErr != nil {
		return current, node, projectionErr
	}
	return current, node, nil
}

// failCoordinator applies the same safe ordering as concludeCoordinator: the
// leader obligation and physical Attempt become terminal before the Run can be
// exposed as failed. A retry of the second step is authorized by the fenced
// terminal coordinator token.
func (s *Server) failCoordinator(ctx context.Context, task *controlmodel.AgentTask,
	code, message string, actor controlmodel.Actor) (*controlmodel.AgentTask, *controlmodel.RunNode, error) {
	if task == nil {
		return nil, nil, fmt.Errorf("Team leader task is required")
	}
	current, err := s.store.Collaboration().GetAgentTask(ctx, task.ID)
	if err != nil {
		return nil, nil, err
	}
	if current.Status != controlmodel.AgentTaskFailed {
		if controlmodel.IsAgentTaskTerminal(current.Status) {
			return current, nil, fmt.Errorf("coordinator leader task ended in state %s", current.Status)
		}
		current, err = s.collaborationService().FailTask(ctx, current.ID, current.Version, code, message)
		if err != nil {
			return current, nil, err
		}
	}
	if err = s.projectCoordinatorOutcomeToRoot(context.WithoutCancel(ctx), current, nil, code, message, actor); err != nil {
		return current, nil, err
	}
	node, err := s.orchestrationService().FailCoordinatorNode(
		context.WithoutCancel(ctx), current.ID, code, message, actor)
	if err != nil {
		return current, nil, err
	}
	if err = s.projectCoordinatorOutcomeToRoot(context.WithoutCancel(ctx), current, nil, code, message, actor); err != nil {
		return current, node, err
	}
	if projectionErr := s.projectMCPTaskTerminal(ctx, current, nil); projectionErr != nil {
		return current, node, projectionErr
	}
	return current, node, nil
}

// projectCoordinatorOutcomeToRoot ensures that a follow-up running on a child
// Issue still leaves the Team's final decision on the user-facing root Issue.
// The shared publisher deduplicates against engine recovery and never routes
// the delivery back into another AgentTask.
func (s *Server) projectCoordinatorOutcomeToRoot(ctx context.Context, task *controlmodel.AgentTask,
	output json.RawMessage, code, message string, actor controlmodel.Actor) error {
	run, err := s.store.Orchestration().GetRun(ctx, task.OrchestrationRunID)
	if err != nil {
		return err
	}
	return s.collaborationService().PublishCoordinatorSummary(ctx, run, task, output, code, message)
}

func coordinatorOutcomeText(output json.RawMessage) string {
	return collaboration.CoordinatorOutcomeText(output)
}

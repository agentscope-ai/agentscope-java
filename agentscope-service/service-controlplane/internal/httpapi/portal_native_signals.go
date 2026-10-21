// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"strconv"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/orchestration"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeportal"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

func (b *portalNativeBackend) applyControls(ctx context.Context, turn runtimeportal.Turn) (int, bool, error) {
	if turn.ControlCommandID == "" {
		return 0, false, nil
	}
	interaction, err := b.server.store.RuntimePortal().Interaction(ctx, turn.Command.Context.SessionID, turn.ControlCommandID)
	if err != nil {
		return 0, false, err
	}
	if interaction.Command.Kind != "signal" {
		return b.applyApprovals(ctx, turn)
	}
	if interaction.ActivationGeneration != turn.Generation || interaction.ActivationFence != turn.Fence {
		return 0, false, store.ErrConflict
	}
	answers, _ := interaction.Command.Payload["answers"].([]any)
	index := turn.ControlAnswerIndex
	if index < 0 || index > len(answers) {
		return 0, false, store.ErrConflict
	}
	actor, err := serviceapi.PortalSignalActor(interaction.Command.Context.Actor)
	if err != nil {
		return index, false, err
	}
	for end := min(len(answers), index+8); index < end; index++ {
		answer, _ := answers[index].(map[string]any)
		node, err := b.server.store.Orchestration().GetNode(ctx, uuid.MustParse(answer["request_id"].(string)))
		if err != nil {
			return index, false, err
		}
		if err = b.validateSignalScope(ctx, node, turn, false); err != nil {
			return index, false, err
		}
		var cfg orchestration.DefinitionNode
		if err = json.Unmarshal(node.Config, &cfg); err != nil {
			return index, false, err
		}
		payload, err := json.Marshal(map[string]any{"portal_command_id": turn.ControlCommandID, "portal_generation": interaction.ActivationGeneration, "portal_fence": interaction.ActivationFence, "value": answer["value"]})
		if err != nil {
			return index, false, err
		}
		event, err := b.server.store.Orchestration().FirstRunSignal(ctx, node.RunID, node.ID, cfg.SignalName, false)
		if err == store.ErrNotFound {
			if err = b.validateAgentSignalTask(ctx, interaction.Command, turn, true, node); err != nil {
				if errors.Is(err, store.ErrForbidden) || errors.Is(err, store.ErrNotFound) {
					return index, false, runtimeportal.ErrPermission
				}
				return index, false, err
			}
			if err = b.validateSignalScope(ctx, node, turn, true); err != nil {
				return index, false, err
			}
			event, err = b.server.store.Orchestration().AppendRunEvent(ctx, &model.RunEvent{RunID: node.RunID, NodeID: &node.ID, Tenant: node.Tenant, Namespace: node.Namespace, Type: "run.signal." + cfg.SignalName, Actor: actor, Payload: payload, IdempotencyKey: "portal-signal:" + node.ID.String()})
		}
		if err != nil {
			return index, false, err
		}
		if event.Actor != actor || !sameJSON(event.Payload, payload) {
			return index, false, store.ErrConflict
		}
	}
	return index, index == len(answers), nil
}

func (b *portalNativeBackend) validateSignalScope(ctx context.Context, node *model.RunNode, turn runtimeportal.Turn, pending bool) error {
	if node.Type != model.RunNodeSignal || node.Tenant != turn.Command.Context.Tenant || node.Namespace != turn.Command.Context.Namespace {
		return store.ErrForbidden
	}
	run, err := b.server.store.Orchestration().GetRun(ctx, node.RunID)
	if err != nil {
		return err
	}
	if pending && (node.State != model.RunNodeWaiting || model.IsOrchestrationRunTerminal(run.State) || run.State == model.RunCancelling) {
		return store.ErrConflict
	}
	contract, err := serviceapi.ContractForIssue(serviceapi.WithContract(ctx, nil), b.server.store, run.RootIssueID)
	if err != nil {
		return err
	}
	if contract == nil || contract.PortalCommandID != turn.Command.Context.CommandID || contract.PublicSessionID.String() != turn.Command.Context.SessionID {
		return store.ErrForbidden
	}
	return nil
}

func (b *portalNativeBackend) validateSignalAdmission(ctx context.Context, command runtimeprotocol.InteractionCommand) error {
	turn, err := b.server.store.RuntimePortal().Turn(ctx, *command.Context.TurnID)
	if err != nil {
		return err
	}
	if err = b.validateAgentSignalTask(ctx, command, *turn, false); err != nil {
		return err
	}
	answers, ok := command.Payload["answers"].([]any)
	if !ok || len(answers) < 1 || len(answers) > 100 {
		return runtimeportal.ErrInvalid
	}
	for _, raw := range answers {
		answer, ok := raw.(map[string]any)
		if !ok {
			return runtimeportal.ErrInvalid
		}
		id, err := uuid.Parse(firstPayloadString(answer, "request_id"))
		if err != nil {
			return runtimeportal.ErrInvalid
		}
		node, err := b.server.store.Orchestration().GetNode(ctx, id)
		if err != nil {
			return err
		}
		if err = b.validateAgentSignalTask(ctx, command, *turn, false, node); err != nil {
			return err
		}
		if err = b.validateSignalScope(ctx, node, *turn, true); err != nil {
			return err
		}
	}
	return nil
}

func (b *portalNativeBackend) nodeSignalFacts(ctx context.Context, turn runtimeportal.Turn, node *model.RunNode, state *portalNodeCursor) ([]runtimeprotocol.RuntimeFact, error) {
	var cfg orchestration.DefinitionNode
	if err := json.Unmarshal(node.Config, &cfg); err != nil {
		return nil, err
	}
	facts := []runtimeprotocol.RuntimeFact{}
	if node.State != model.RunNodeWaiting && node.State != model.RunNodeSucceeded && !state.ActionCreated {
		return facts, nil
	}
	if !state.ActionCreated {
		facts = append(facts, runtimeprotocol.RuntimeFact{EventID: "native:signal:" + node.ID.String() + ":created", Type: "required_action.created", Payload: map[string]any{"request_id": node.ID.String(), "kind": "signal", "signal_name": cfg.SignalName, "step_id": node.ID.String()}})
		state.ActionCreated = true
	}
	if !model.IsRunNodeTerminal(node.State) || state.ActionResolved {
		return facts, nil
	}
	payload := map[string]any{"request_id": node.ID.String(), "kind": "signal", "step_id": node.ID.String(), "resolution_status": "cancelled"}
	fact := runtimeprotocol.RuntimeFact{EventID: "native:signal:" + node.ID.String() + ":resolved", Type: "required_action.resolved", Payload: payload}
	if node.State == model.RunNodeSucceeded {
		event, err := b.server.store.Orchestration().FirstRunSignal(ctx, node.RunID, node.ID, cfg.SignalName, false)
		if err != nil {
			return nil, err
		}
		run, err := b.server.store.Orchestration().GetRun(ctx, node.RunID)
		if err != nil {
			return nil, err
		}
		contract, err := serviceapi.ContractForIssue(serviceapi.WithContract(ctx, nil), b.server.store, run.RootIssueID)
		if err != nil {
			return nil, err
		}
		if _, err = serviceapi.PortalSignalOutput(ctx, b.server.store, contract, node, event); err != nil {
			return nil, err
		}
		var proof map[string]any
		if err = json.Unmarshal(event.Payload, &proof); err != nil {
			return nil, err
		}
		activation, _ := proof["portal_command_id"].(string)
		interaction, err := b.server.store.RuntimePortal().Interaction(ctx, turn.Command.Context.SessionID, activation)
		if err != nil {
			return nil, err
		}
		generation, fence := strconv.FormatInt(interaction.ActivationGeneration, 10), interaction.ActivationFence
		fact.ExecutionID, fact.ExecutionFence = &generation, &fence
		payload["activation_command_id"], payload["resolution_status"] = activation, "signalled"
	}
	state.ActionResolved = true
	return append(facts, fact), nil
}

func (b *portalNativeBackend) validateAgentSignalTask(ctx context.Context, command runtimeprotocol.InteractionCommand, turn runtimeportal.Turn, requireGrant bool, targets ...*model.RunNode) error {
	proof, err := runtimeprotocol.ParseAgentSignalProof(command.Context.Actor)
	if err != nil {
		return store.ErrForbidden
	}
	if proof == nil {
		return nil
	}
	taskID, err := uuid.Parse(proof.TaskID)
	if err != nil {
		return store.ErrForbidden
	}
	attemptID, err := uuid.Parse(proof.AttemptID)
	if err != nil {
		return store.ErrForbidden
	}
	task, err := b.server.store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return err
	}
	if task.AgentRef != proof.AgentRef || task.Tenant != turn.Command.Context.Tenant || task.Namespace != turn.Command.Context.Namespace ||
		(requireGrant && (task.CurrentAttemptID == nil || *task.CurrentAttemptID != attemptID)) {
		return store.ErrForbidden
	}
	for _, node := range targets {
		if node.RunID != task.OrchestrationRunID {
			return store.ErrForbidden
		}
	}
	attempt, err := b.server.store.ExecutionAttempts().Get(ctx, attemptID)
	if err != nil {
		return err
	}
	// The originating courier checked the live lease before Portal admission.
	// Its durable intent survives task completion; a replaced attempt/fence does not.
	if attempt.AgentTaskID != task.ID || attempt.Tenant != task.Tenant || attempt.Namespace != task.Namespace ||
		(requireGrant && (attempt.FencingToken != proof.Fence || attempt.HostID == nil)) {
		return store.ErrForbidden
	}
	contract, err := serviceapi.ContractForIssue(serviceapi.WithContract(ctx, nil), b.server.store, task.IssueID)
	if err != nil {
		return err
	}
	if contract == nil || contract.PortalCommandID != turn.Command.Context.CommandID || contract.PublicSessionID.String() != turn.Command.Context.SessionID {
		return store.ErrForbidden
	}
	return serviceapi.CheckPortalWaiting(ctx, b.server.store, contract, task.Tenant, task.Namespace, requireGrant)
}

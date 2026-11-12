// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package httpapi

import (
	"context"
	"encoding/json"
	"strconv"

	"github.com/google/uuid"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/agentservice"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	serviceapi "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/invocation"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

// The command is already durable. A bounded batch records immutable human
// decisions; the Host acknowledges delivery before an applied fact is emitted.
func (b *agentServiceNativeBackend) applyApprovals(ctx context.Context, turn agentservice.Turn) (int, bool, error) {
	if turn.ControlCommandID == "" {
		return 0, false, nil
	}
	interaction, err := b.server.store.RuntimeAgentService().Interaction(ctx, turn.Command.Context.SessionID, turn.ControlCommandID)
	if err != nil {
		return 0, false, err
	}
	if interaction.Command.Kind != "approval" || interaction.ActivationGeneration != turn.Generation || interaction.ActivationFence != turn.Fence {
		return 0, false, store.ErrConflict
	}
	answers, _ := interaction.Command.Payload["answers"].([]any)
	index := turn.ControlAnswerIndex
	if index < 0 || index > len(answers) {
		return 0, false, store.ErrConflict
	}
	for end := min(len(answers), index+8); index < end; index++ {
		answer, _ := answers[index].(map[string]any)
		id, err := uuid.Parse(firstPayloadString(answer, "request_id"))
		if err != nil {
			return index, false, err
		}
		approval, err := b.server.store.Collaboration().GetApproval(ctx, id)
		if err != nil {
			return index, false, err
		}
		if err = b.validateApprovalScope(ctx, approval, turn, approval.Status == model.ApprovalPending); err != nil {
			return index, false, err
		}
		user, _ := interaction.Command.Context.Actor["user"].(string)
		actor := model.Actor{Type: model.ActorHuman, Ref: user}
		if actor.Ref == "" || approval.ApproverRef != actor.Ref {
			return index, false, store.ErrForbidden
		}
		allow, _ := answer["allow"].(bool)
		status := model.ApprovalRejected
		if allow {
			status = model.ApprovalApproved
		}
		decision, err := json.Marshal(map[string]any{"allow": allow, "reason": answer["reason"],
			"portal_command_id": interaction.Command.Context.CommandID,
			"portal_generation": interaction.ActivationGeneration, "portal_fence": interaction.ActivationFence})
		if err != nil {
			return index, false, err
		}
		if approval.Status != model.ApprovalPending {
			var previous map[string]any
			if json.Unmarshal(approval.Decision, &previous) != nil || previous["portal_command_id"] != interaction.Command.Context.CommandID || previous["portal_fence"] != interaction.ActivationFence || firstMetadataInt64(previous, "portal_generation") != interaction.ActivationGeneration || previous["allow"] != allow || previous["reason"] != answer["reason"] || approval.Status != status || approval.DecidedBy == nil || *approval.DecidedBy != actor {
				return index, false, store.ErrConflict
			}
			continue
		}
		if _, err = b.server.store.Collaboration().DecideApproval(ctx, id, approval.Version, status, actor, decision); err != nil {
			return index, false, err
		}
	}
	return index, index == len(answers), nil
}

func (b *agentServiceNativeBackend) approvalEvent(ctx context.Context, turn agentservice.Turn, event *model.RunEvent) (*runtimeprotocol.RuntimeFact, error) {
	if event.Type != "attempt.waiting_for_approval" && event.Type != "approval.delivery_delivered" {
		return nil, nil
	}
	var data map[string]any
	if err := json.Unmarshal(event.Payload, &data); err != nil {
		return nil, err
	}
	id, err := uuid.Parse(firstPayloadString(data, "approvalId"))
	if err != nil {
		return nil, err
	}
	approval, err := b.server.store.Collaboration().GetApproval(ctx, id)
	if err != nil {
		return nil, err
	}
	envelope, err := parseManagedToolApproval(approval)
	if err != nil {
		return nil, err
	}
	if envelope.BackendKind != model.DataPlaneHostedRuntime {
		return nil, nil
	}
	attempt, err := b.server.store.ExecutionAttempts().Get(ctx, envelope.AttemptID)
	if err != nil {
		return nil, err
	}
	if err = b.approvalTurnScope(ctx, attempt.AgentTaskID, turn); err != nil {
		return nil, err
	}
	if approval.Tenant != turn.Command.Context.Tenant || approval.Namespace != turn.Command.Context.Namespace || event.AttemptID == nil || *event.AttemptID != attempt.ID {
		return nil, store.ErrForbidden
	}
	payload := map[string]any{"request_id": id.String(), "kind": "confirmation"}
	fact := &runtimeprotocol.RuntimeFact{Type: "required_action.created", Payload: payload}
	if event.Type == "attempt.waiting_for_approval" {
		var input any
		if len(envelope.InputPreview) > 0 {
			if err = json.Unmarshal(envelope.InputPreview, &input); err != nil {
				return nil, err
			}
		}
		payload["tool_call"] = map[string]any{"id": attempt.ID.String() + ":" + envelope.ToolUseID, "name": envelope.ToolName, "input": input}
		return fact, nil
	}
	var decision map[string]any
	if err = json.Unmarshal(approval.Decision, &decision); err != nil {
		return nil, err
	}
	activation, _ := decision["portal_command_id"].(string)
	if activation == "" {
		return nil, store.ErrForbidden
	}
	interaction, err := b.server.store.RuntimeAgentService().Interaction(ctx, turn.Command.Context.SessionID, activation)
	if err != nil {
		return nil, err
	}
	if firstMetadataInt64(data, "decisionVersion") != approval.Version || interaction.Command.Kind != "approval" {
		return nil, store.ErrConflict
	}
	fact.Type = "required_action.resolved"
	payload["activation_command_id"], payload["resolution_status"] = activation, "rejected"
	if approval.Status == model.ApprovalApproved {
		payload["resolution_status"] = "approved"
	}
	generation := strconv.FormatInt(interaction.ActivationGeneration, 10)
	fence := interaction.ActivationFence
	fact.ExecutionID, fact.ExecutionFence = &generation, &fence
	return fact, nil
}

func (b *agentServiceNativeBackend) approvalTurnScope(ctx context.Context, taskID uuid.UUID, turn agentservice.Turn) error {
	task, err := b.server.store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return err
	}
	contract, err := serviceapi.ContractForIssue(serviceapi.WithContract(ctx, nil), b.server.store, task.IssueID)
	if err != nil {
		return err
	}
	if contract == nil || contract.AgentServiceCommandID != turn.Command.Context.CommandID || contract.PublicSessionID.String() != turn.Command.Context.SessionID {
		return store.ErrForbidden
	}
	return nil
}

func (b *agentServiceNativeBackend) validateApprovalAdmission(ctx context.Context, command runtimeprotocol.InteractionCommand) error {
	turn, err := b.server.store.RuntimeAgentService().Turn(ctx, *command.Context.TurnID)
	if err != nil {
		return err
	}
	answers, ok := command.Payload["answers"].([]any)
	if !ok || len(answers) < 1 || len(answers) > 100 {
		return agentservice.ErrInvalid
	}
	user, _ := command.Context.Actor["user"].(string)
	for _, raw := range answers {
		answer, ok := raw.(map[string]any)
		if !ok {
			return agentservice.ErrInvalid
		}
		id, err := uuid.Parse(firstPayloadString(answer, "request_id"))
		if err != nil {
			return agentservice.ErrInvalid
		}
		approval, err := b.server.store.Collaboration().GetApproval(ctx, id)
		if err != nil {
			return err
		}
		if approval.Status != model.ApprovalPending {
			return store.ErrConflict
		}
		if approval.ApproverRef != user {
			return store.ErrForbidden
		}
		if err = b.validateApprovalScope(ctx, approval, *turn, true); err != nil {
			return err
		}
	}
	return nil
}

func (s *Server) agentServiceOwnsApproval(ctx context.Context, envelope managedToolApprovalEnvelope) (bool, error) {
	task, err := s.store.Collaboration().GetAgentTask(ctx, envelope.AgentTaskID)
	if err != nil {
		return false, err
	}
	contract, err := serviceapi.ContractForIssue(serviceapi.WithContract(ctx, nil), s.store, task.IssueID)
	return contract != nil && contract.AgentServiceCommandID != "", err
}

// Both graph approvals and physical tool confirmations retain their own native
// scope. Never use an inherited contract to prove the target belongs to a Turn.
func (b *agentServiceNativeBackend) validateApprovalScope(ctx context.Context, approval *model.Approval, turn agentservice.Turn, pending bool) error {
	if approval.Tenant != turn.Command.Context.Tenant || approval.Namespace != turn.Command.Context.Namespace {
		return store.ErrForbidden
	}
	if approval.TargetType == "run-node" {
		if approval.RunID == nil || approval.RunNodeID == nil || approval.TargetRef != approval.RunNodeID.String() {
			return store.ErrForbidden
		}
		node, err := b.server.store.Orchestration().GetNode(ctx, *approval.RunNodeID)
		if err != nil {
			return err
		}
		run, err := b.server.store.Orchestration().GetRun(ctx, node.RunID)
		if err != nil {
			return err
		}
		if node.RunID != *approval.RunID || node.Type != model.RunNodeApproval || node.Tenant != approval.Tenant || node.Namespace != approval.Namespace {
			return store.ErrForbidden
		}
		if pending && (node.State != model.RunNodeWaiting || model.IsOrchestrationRunTerminal(run.State) || run.State == model.RunCancelling) {
			return store.ErrConflict
		}
		contract, err := serviceapi.ContractForIssue(serviceapi.WithContract(ctx, nil), b.server.store, run.RootIssueID)
		if err != nil {
			return err
		}
		if contract == nil || contract.AgentServiceCommandID != turn.Command.Context.CommandID || contract.PublicSessionID.String() != turn.Command.Context.SessionID {
			return store.ErrForbidden
		}
		return nil
	}
	envelope, err := parseManagedToolApproval(approval)
	if err != nil {
		return err
	}
	if envelope.BackendKind != model.DataPlaneHostedRuntime {
		return store.ErrForbidden
	}
	if err = b.approvalTurnScope(ctx, envelope.AgentTaskID, turn); err != nil {
		return err
	}
	if pending {
		_, err = b.server.validateManagedApprovalCurrent(ctx, approval)
	}
	return err
}

func (s *Server) agentServiceOwnsNodeApproval(ctx context.Context, approval *model.Approval) (bool, error) {
	if approval.TargetType != "run-node" || approval.RunID == nil {
		return false, nil
	}
	run, err := s.store.Orchestration().GetRun(ctx, *approval.RunID)
	if err != nil {
		return false, err
	}
	contract, err := serviceapi.ContractForIssue(serviceapi.WithContract(ctx, nil), s.store, run.RootIssueID)
	return contract != nil && contract.AgentServiceCommandID != "", err
}

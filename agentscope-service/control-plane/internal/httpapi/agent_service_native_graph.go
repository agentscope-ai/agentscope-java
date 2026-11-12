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
	"strconv"

	"github.com/google/uuid"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/agentservice"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	serviceapi "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/invocation"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

type agentServiceNodeCursor struct {
	Version        int64 `json:"version"`
	ActionCreated  bool  `json:"action_created,omitempty"`
	ActionResolved bool  `json:"action_resolved,omitempty"`
}

type agentServiceGraphCursor struct {
	AfterID         uuid.UUID `json:"after_id,omitempty"`
	Version         int64     `json:"version,omitempty"`
	RunSequence     int64     `json:"run_sequence,omitempty"`
	TerminalVersion int64     `json:"terminal_version,omitempty"`
	TerminalDrained bool      `json:"terminal_drained,omitempty"`
}

// One graph and at most 16 nodes/events are observed per advancement. Shared
// root Issue identity discovers nested Workflow subruns without recursive scans.
// A complete final round includes every subrun before public termination.
func (b *agentServiceNativeBackend) observeGraph(ctx context.Context, turn agentservice.Turn, root *model.OrchestrationRun, cursor *agentServiceNativeCursor, progress *agentservice.Progress) (bool, bool, bool, error) {
	if cursor.Graphs == nil {
		cursor.Graphs = map[string]*agentServiceGraphCursor{}
	}
	if model.IsOrchestrationRunTerminal(root.State) && cursor.GraphRootTerminalVersion != root.Version {
		cursor.GraphRootTerminalVersion = root.Version
		cursor.GraphRoundStarted, cursor.GraphSummaryReady = false, false
	}
	if !cursor.GraphRoundStarted {
		cursor.GraphRoundStarted, cursor.GraphRoundReady = true, true
		cursor.GraphRoundAutonomous, cursor.GraphRoundHuman = false, false
		cursor.GraphRunAfterAt, cursor.GraphRunAfterID = nil, uuid.Nil
	}
	runs, err := b.server.store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{
		Tenant: root.Tenant, Namespace: root.Namespace, RootIssueID: root.RootIssueID, OldestFirst: true,
		AfterCreatedAt: cursor.GraphRunAfterAt, AfterID: cursor.GraphRunAfterID, Limit: 2})
	if err != nil {
		return false, false, false, err
	}
	if len(runs) == 0 {
		return false, false, false, store.ErrConflict
	}
	run := runs[0]
	state := cursor.Graphs[run.ID.String()]
	if state == nil {
		if len(cursor.Graphs) >= 8192 {
			return false, false, false, agentServiceObservationCapacityError("native_graphs")
		}
		state = &agentServiceGraphCursor{}
		cursor.Graphs[run.ID.String()] = state
	}
	if state.Version != run.Version {
		payload := map[string]any{"step_id": run.ID.String(), "graph_id": run.ID.String(), "kind": "graph", "status": string(run.State), "version": run.Version}
		if run.ParentNodeID != nil {
			payload["parent_step_id"] = run.ParentNodeID.String()
		}
		if run.ParentRunID != nil {
			payload["parent_graph_id"] = run.ParentRunID.String()
		}
		if run.FailureCode != "" {
			payload["failure_code"] = run.FailureCode
		}
		progress.Facts = append(progress.Facts, runtimeprotocol.RuntimeFact{EventID: "native:graph:" + run.ID.String() + ":" + strconv.FormatInt(run.Version, 10), Type: "step.updated", Payload: payload})
		state.Version = run.Version
	}
	events, err := b.server.store.Orchestration().ListRunEvents(ctx, run.ID, state.RunSequence, 16)
	if err != nil {
		return false, false, false, err
	}
	for _, event := range events {
		fact, err := b.approvalEvent(ctx, turn, event)
		if err != nil {
			return false, false, false, err
		}
		if fact != nil {
			if err = cursor.trackHostedAction(*fact); err != nil {
				return false, false, false, err
			}
			fact.EventID = "native:" + turn.NativeInvocationID + ":run:" + run.ID.String() + ":" + strconv.FormatInt(event.Sequence, 10) + ":approval"
			fact.Payload["graph_id"] = run.ID.String()
			progress.Facts = append(progress.Facts, *fact)
		}
		state.RunSequence = event.Sequence
	}
	firstFact := len(progress.Facts)
	drained, autonomous, err := b.observeGraphNodes(ctx, turn, run, cursor, state, progress)
	if err != nil {
		return false, false, false, err
	}
	for index := firstFact; index < len(progress.Facts); index++ {
		progress.Facts[index].Payload["graph_id"] = run.ID.String()
		progress.Facts[index].Payload["parent_step_id"] = run.ID.String()
	}
	human, err := b.server.store.Orchestration().ListNodesPage(ctx, run.ID, store.RunNodeFilter{HumanOnly: true, Limit: 1})
	if err != nil {
		return false, false, false, err
	}
	cursor.GraphRoundReady = cursor.GraphRoundReady && model.IsOrchestrationRunTerminal(run.State)
	cursor.GraphRoundAutonomous = cursor.GraphRoundAutonomous || autonomous
	cursor.GraphRoundHuman = cursor.GraphRoundHuman || len(human) > 0 || run.State == model.RunPaused
	if drained && len(events) < 16 {
		at := run.CreatedAt
		cursor.GraphRunAfterAt, cursor.GraphRunAfterID = &at, run.ID
		if len(runs) == 1 {
			cursor.GraphRoundStarted, cursor.GraphSummaryReady = false, true
			cursor.GraphAutonomous, cursor.GraphHuman = cursor.GraphRoundAutonomous, cursor.GraphRoundHuman
			return cursor.GraphRoundReady, cursor.GraphAutonomous, cursor.GraphHuman, nil
		}
	}
	// Until the first complete round, keep autonomous admission conservative.
	// Later partial rounds retain the previous complete wait classification.
	return false, !cursor.GraphSummaryReady || cursor.GraphAutonomous, cursor.GraphSummaryReady && cursor.GraphHuman, nil
}

// Nodes have their own bounded keyset observation. Native transition events are
// not assumed to exist for every graph state change. A final complete pass is
// required before the public Turn can settle.
func (b *agentServiceNativeBackend) observeGraphNodes(ctx context.Context, turn agentservice.Turn, run *model.OrchestrationRun, cursor *agentServiceNativeCursor, graph *agentServiceGraphCursor, progress *agentservice.Progress) (bool, bool, error) {
	if cursor.Nodes == nil {
		cursor.Nodes = map[string]*agentServiceNodeCursor{}
	}
	if model.IsOrchestrationRunTerminal(run.State) && graph.TerminalVersion != run.Version {
		graph.TerminalVersion = run.Version
		graph.AfterID = uuid.Nil
		graph.TerminalDrained = false
	}
	drained := graph.TerminalDrained
	if !drained {
		nodes, err := b.server.store.Orchestration().ListNodesPage(ctx, run.ID, store.RunNodeFilter{AfterID: graph.AfterID, Limit: 16})
		if err != nil {
			return false, false, err
		}
		for _, node := range nodes {
			state := cursor.Nodes[node.ID.String()]
			if state == nil {
				if len(cursor.Nodes) >= 8192 {
					return false, false, agentServiceObservationCapacityError("native_nodes")
				}
				state = &agentServiceNodeCursor{}
				cursor.Nodes[node.ID.String()] = state
			}
			if state.Version != node.Version {
				payload := map[string]any{"step_id": node.ID.String(), "graph_id": run.ID.String(), "parent_step_id": run.ID.String(), "name": node.NodeKey, "kind": string(node.Type), "status": string(node.State), "version": node.Version}
				if node.FailureCode != "" {
					payload["failure_code"] = node.FailureCode
				}
				progress.Facts = append(progress.Facts, runtimeprotocol.RuntimeFact{EventID: "native:node:" + node.ID.String() + ":" + strconv.FormatInt(node.Version, 10), Type: "step.updated", Payload: payload})
				if node.Type == model.RunNodeApproval {
					facts, err := b.nodeApprovalFacts(ctx, turn, node, state)
					if err != nil {
						return false, false, err
					}
					progress.Facts = append(progress.Facts, facts...)
				}
				if node.Type == model.RunNodeSignal {
					facts, err := b.nodeSignalFacts(ctx, turn, node, state)
					if err != nil {
						return false, false, err
					}
					progress.Facts = append(progress.Facts, facts...)
				}
				state.Version = node.Version
			}
			graph.AfterID = node.ID
		}
		drained = len(nodes) < 16
		if drained {
			graph.AfterID = uuid.Nil
			graph.TerminalDrained = model.IsOrchestrationRunTerminal(run.State)
		}
	}
	// A pending answer in one branch must not suspend parallel autonomous work.
	autonomous, err := b.server.store.Orchestration().ListNodesPage(ctx, run.ID, store.RunNodeFilter{AutonomousOnly: true, Limit: 1})
	if err != nil {
		return false, false, err
	}
	if len(autonomous) == 0 {
		answered, err := b.server.store.Orchestration().ListNodesPage(ctx, run.ID, store.RunNodeFilter{AnsweredOnly: true, Limit: 1})
		if err != nil {
			return false, false, err
		}
		return drained, len(answered) > 0, nil
	}
	return drained, len(autonomous) > 0, nil
}

func (b *agentServiceNativeBackend) nodeApprovalFacts(ctx context.Context, turn agentservice.Turn, node *model.RunNode, state *agentServiceNodeCursor) ([]runtimeprotocol.RuntimeFact, error) {
	approvals, err := b.server.store.Collaboration().ListApprovals(ctx, store.ApprovalFilter{Tenant: node.Tenant, Namespace: node.Namespace, TargetType: "run-node", TargetRef: node.ID.String(), Limit: 1})
	if err != nil || len(approvals) == 0 {
		return nil, err
	}
	approval := approvals[0]
	if err = b.validateApprovalScope(ctx, approval, turn, false); err != nil {
		return nil, err
	}
	facts := []runtimeprotocol.RuntimeFact{}
	if !state.ActionCreated {
		var input any
		if len(approval.Request) > 0 {
			if err = json.Unmarshal(approval.Request, &input); err != nil {
				return nil, err
			}
		}
		preview, _, err := managedApprovalInput(map[string]any{"input": input})
		if err != nil {
			return nil, err
		}
		if len(preview) > 0 {
			if err = json.Unmarshal(preview, &input); err != nil {
				return nil, err
			}
		}
		facts = append(facts, runtimeprotocol.RuntimeFact{EventID: "native:approval:" + approval.ID.String() + ":created", Type: "required_action.created", Payload: map[string]any{
			"request_id": approval.ID.String(), "kind": "confirmation", "step_id": node.ID.String(), "approver_ref": approval.ApproverRef,
			"tool_call": map[string]any{"id": node.ID.String() + ":approval", "name": "workflow.approval", "input": input},
		}})
		state.ActionCreated = true
	}
	if !model.IsRunNodeTerminal(node.State) || state.ActionResolved {
		return facts, nil
	}
	payload := map[string]any{"request_id": approval.ID.String(), "kind": "confirmation", "step_id": node.ID.String(), "resolution_status": "cancelled"}
	fact := runtimeprotocol.RuntimeFact{EventID: "native:approval:" + approval.ID.String() + ":resolved", Type: "required_action.resolved", Payload: payload}
	if (node.State == model.RunNodeSucceeded && approval.Status == model.ApprovalApproved) ||
		(node.State == model.RunNodeFailed && node.FailureCode == "approval_rejected" && approval.Status == model.ApprovalRejected) {
		run, err := b.server.store.Orchestration().GetRun(ctx, node.RunID)
		if err != nil {
			return nil, err
		}
		contract, err := serviceapi.ContractForIssue(serviceapi.WithContract(ctx, nil), b.server.store, run.RootIssueID)
		if err != nil {
			return nil, err
		}
		if _, err = serviceapi.AgentServiceApprovalOutput(ctx, b.server.store, contract, approval); err != nil {
			return nil, err
		}
		var decision map[string]any
		if err = json.Unmarshal(approval.Decision, &decision); err != nil {
			return nil, err
		}
		activation, _ := decision["portal_command_id"].(string)
		interaction, err := b.server.store.RuntimeAgentService().Interaction(ctx, turn.Command.Context.SessionID, activation)
		if err != nil {
			return nil, err
		}
		if interaction.Command.Kind != "approval" || firstMetadataInt64(decision, "portal_generation") != interaction.ActivationGeneration || decision["portal_fence"] != interaction.ActivationFence {
			return nil, store.ErrForbidden
		}
		generation, fence := strconv.FormatInt(interaction.ActivationGeneration, 10), interaction.ActivationFence
		fact.ExecutionID, fact.ExecutionFence = &generation, &fence
		payload["activation_command_id"] = activation
		payload["resolution_status"] = "rejected"
		if approval.Status == model.ApprovalApproved {
			payload["resolution_status"] = "approved"
		}
	}
	facts = append(facts, fact)
	state.ActionResolved = true
	return facts, nil
}

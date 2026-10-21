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
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeportal"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

type portalObservationCapacityError string

func (e portalObservationCapacityError) Error() string {
	return "native observation capacity exceeded: " + string(e)
}

const portalCapacityFailureCode = "native_observation_capacity_exceeded"

// Persist the failure and stop intent with all already mapped facts. Subsequent
// observations use a constant-space drainage path, retaining raw native logs
// instead of retrying an event or allocating more cursor entries forever.
func portalCapacityProgress(cursor *portalNativeCursor, progress runtimeportal.Progress, capacity portalObservationCapacityError) (runtimeportal.Progress, error) {
	cursor.CapacityFailure = string(capacity)
	cursor.AfterCreatedAt, cursor.AfterID, cursor.RoundReady = nil, uuid.Nil, true
	for _, state := range cursor.Attempts {
		cursor.FailureReportedInput += state.InputTokens
		cursor.FailureReportedOutput += state.OutputTokens
	}
	progress.Status, progress.StopRequested, progress.ObservationOnly = "running", true, true
	progress.ReportedTokens = cursor.FailureReportedInput + cursor.FailureReportedOutput
	raw, err := json.Marshal(cursor)
	progress.Cursor = string(raw)
	return progress, err
}

// Capacity failure still holds the reservation until every graph is terminal
// and every Hosted Attempt has sealed its stream with a physical stop proof.
// Accounting uses native final totals and the previously committed provider
// deltas. Each 16-Attempt keyset pass produces only the positive aggregate gap.
func (b *portalNativeBackend) observeCapacityStop(ctx context.Context, turn runtimeportal.Turn, inv *model.EndpointInvocation, cursor *portalNativeCursor) (runtimeportal.Progress, error) {
	progress := runtimeportal.Progress{Status: "running", StopRequested: true, ObservationOnly: true, NativeStarted: inv.RunID != nil || inv.ConversationID != nil}
	graphsStopped := true
	var root *model.OrchestrationRun
	if inv.RunID != nil {
		run, err := b.server.store.Orchestration().GetRun(ctx, *inv.RunID)
		if err != nil {
			return runtimeportal.Progress{}, err
		}
		active, err := b.server.store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{Tenant: run.Tenant, Namespace: run.Namespace,
			RootIssueID: run.RootIssueID, ActiveOnly: true, Limit: 1})
		if err != nil {
			return runtimeportal.Progress{}, err
		}
		graphsStopped = len(active) == 0
		root = run
	}
	if root == nil {
		cursor.FailureActionsDrained = true
	} else if graphsStopped && !cursor.FailureActionsDrained {
		if err := b.observeCapacityActions(ctx, turn, root, cursor, &progress); err != nil {
			return runtimeportal.Progress{}, err
		}
	}
	attempts, err := b.server.store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{Tenant: turn.Command.Context.Tenant, Namespace: turn.Command.Context.Namespace,
		PortalCommandID: turn.Command.Context.CommandID, AfterCreatedAt: cursor.AfterCreatedAt, AfterID: cursor.AfterID, Limit: 16})
	if err != nil {
		return runtimeportal.Progress{}, err
	}
	for _, attempt := range attempts {
		if !model.IsExecutionAttemptTerminal(attempt.State) {
			cursor.RoundReady = false
		}
		if attempt.BackendKind == model.DataPlaneHostedRuntime && attempt.HostID != nil {
			stopped, err := b.server.portalNativeStopped(ctx, attempt)
			if err != nil {
				return runtimeportal.Progress{}, err
			}
			if !stopped {
				cursor.RoundReady = false
			}
		}
		var usage map[string]any
		if err = json.Unmarshal(attempt.Usage, &usage); len(attempt.Usage) > 0 && err != nil {
			return runtimeportal.Progress{}, err
		}
		input, output := portalUsage(usage)
		if state := cursor.Attempts[attempt.ID.String()]; state != nil {
			input, output = max(input, state.InputTokens), max(output, state.OutputTokens)
		}
		cursor.FailureRoundInput += input
		cursor.FailureRoundOutput += output
		at := attempt.CreatedAt
		cursor.AfterCreatedAt, cursor.AfterID = &at, attempt.ID
	}
	if len(attempts) < 16 {
		input, output := max(cursor.FailureReportedInput, cursor.FailureRoundInput), max(cursor.FailureReportedOutput, cursor.FailureRoundOutput)
		if input > cursor.FailureReportedInput || output > cursor.FailureReportedOutput {
			progress.Facts = append(progress.Facts, runtimeprotocol.RuntimeFact{EventID: "native:" + inv.ID.String() + ":capacity-usage:" + strconv.FormatInt(input, 10) + ":" + strconv.FormatInt(output, 10), Type: "usage.recorded",
				Payload: map[string]any{"model_call_id": inv.ID.String() + ":capacity-reported-total:" + strconv.FormatInt(input, 10) + ":" + strconv.FormatInt(output, 10), "usage": map[string]any{"inputTokens": input - cursor.FailureReportedInput, "outputTokens": output - cursor.FailureReportedOutput}}})
		}
		cursor.FailureReportedInput, cursor.FailureReportedOutput = input, output
		hostedDrained := len(cursor.HostedActions) == 0
		if graphsStopped && cursor.RoundReady && cursor.FailureActionsDrained {
			hostedDrained, err = b.resolveStoppedHostedActions(ctx, turn, cursor, &progress)
			if err != nil {
				return runtimeportal.Progress{}, err
			}
		}
		if graphsStopped && cursor.RoundReady && cursor.FailureActionsDrained && hostedDrained {
			err = b.server.store.WithSessionLock(ctx, "service-invocation:"+inv.ID.String(), func(ctx context.Context) error {
				current, err := b.server.store.Endpoints().GetInvocation(ctx, inv.ID)
				if err != nil {
					return err
				}
				current.Status, current.ErrorCode = model.EndpointInvocationFailed, portalCapacityFailureCode
				current.ConsumedTokens = max(current.ConsumedTokens, input+output)
				now := time.Now().UTC()
				current.CompletedAt = &now
				_, err = b.server.store.Endpoints().UpdateInvocation(ctx, current)
				return err
			})
			if err != nil {
				return runtimeportal.Progress{}, err
			}
			progress.Status = "failed"
			progress.Facts = append(progress.Facts, runtimeprotocol.RuntimeFact{EventID: "native:" + inv.ID.String() + ":capacity-failed", Type: "turn.failed",
				Payload: map[string]any{"reason": portalCapacityFailureCode, "capacity": cursor.CapacityFailure, "native_started": progress.NativeStarted}})
		}
		cursor.AfterCreatedAt, cursor.AfterID, cursor.RoundReady = nil, uuid.Nil, true
		cursor.FailureRoundInput, cursor.FailureRoundOutput = 0, 0
	}
	progress.ReportedTokens = cursor.FailureReportedInput + cursor.FailureReportedOutput
	raw, err := json.Marshal(cursor)
	progress.Cursor = string(raw)
	return progress, err
}

// Cancel already exposed graph actions in finite pages. Unknown nodes need no
// new cursor allocation or public action during a capacity abort. Their full
// internal logs remain available for diagnosis and future archival.
func (b *portalNativeBackend) observeCapacityActions(ctx context.Context, turn runtimeportal.Turn, root *model.OrchestrationRun, cursor *portalNativeCursor, progress *runtimeportal.Progress) error {
	runs, err := b.server.store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{Tenant: root.Tenant, Namespace: root.Namespace,
		RootIssueID: root.RootIssueID, OldestFirst: true, AfterCreatedAt: cursor.FailureGraphAfterAt, AfterID: cursor.FailureGraphAfterID, Limit: 2})
	if err != nil {
		return err
	}
	if len(runs) == 0 {
		cursor.FailureActionsDrained = true
		return nil
	}
	run := runs[0]
	nodes, err := b.server.store.Orchestration().ListNodesPage(ctx, run.ID, store.RunNodeFilter{AfterID: cursor.FailureNodeAfterID, Limit: 16})
	if err != nil {
		return err
	}
	for _, node := range nodes {
		state := cursor.Nodes[node.ID.String()]
		if state != nil && state.ActionCreated && !state.ActionResolved && model.IsRunNodeTerminal(node.State) {
			var facts []runtimeprotocol.RuntimeFact
			if node.Type == model.RunNodeApproval {
				facts, err = b.nodeApprovalFacts(ctx, turn, node, state)
			} else if node.Type == model.RunNodeSignal {
				facts, err = b.nodeSignalFacts(ctx, turn, node, state)
			}
			if err != nil {
				return err
			}
			for index := range facts {
				facts[index].Payload["graph_id"], facts[index].Payload["parent_step_id"] = run.ID.String(), run.ID.String()
			}
			progress.Facts = append(progress.Facts, facts...)
		}
		cursor.FailureNodeAfterID = node.ID
	}
	if len(nodes) < 16 {
		at := run.CreatedAt
		cursor.FailureGraphAfterAt, cursor.FailureGraphAfterID, cursor.FailureNodeAfterID = &at, run.ID, uuid.Nil
		cursor.FailureActionsDrained = len(runs) == 1
	}
	return nil
}

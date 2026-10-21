// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/orchestration"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeportal"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

type portalNativeBackend struct{ server *Server }

// PortalBackend adapts existing durable native execution to the Portal inbox.
// The legacy public Invocation projector and its unbounded graph scans are not
// used to export facts. Each observation reads at most 16 attempts and 64 events.
func (s *Server) PortalBackend() runtimeportal.NativeBackend { return &portalNativeBackend{server: s} }

type portalAttemptCursor struct {
	Sequence        int                             `json:"sequence"`
	EventFactOffset int                             `json:"event_fact_offset,omitempty"`
	InputTokens     int64                           `json:"input_tokens"`
	OutputTokens    int64                           `json:"output_tokens"`
	ProviderUsage   map[string]*portalProviderUsage `json:"provider_usage,omitempty"`
}

// Reserve space for status, result and stop receipts appended after observation.
// The inbox transaction accepts at most 128 facts, independent of source rows.
const portalObservationFactLimit = 120

type portalNativeCursor struct {
	Running                  bool                            `json:"running"`
	Generation               int64                           `json:"generation,omitempty"`
	RunSequence              int64                           `json:"run_sequence"`
	AfterCreatedAt           *time.Time                      `json:"after_created_at,omitempty"`
	AfterID                  uuid.UUID                       `json:"after_id"`
	RoundReady               bool                            `json:"round_ready"`
	Attempts                 map[string]*portalAttemptCursor `json:"attempts"`
	Nodes                    map[string]*portalNodeCursor    `json:"nodes,omitempty"`
	Graphs                   map[string]*portalGraphCursor   `json:"graphs,omitempty"`
	GraphRunAfterAt          *time.Time                      `json:"graph_run_after_at,omitempty"`
	GraphRunAfterID          uuid.UUID                       `json:"graph_run_after_id,omitempty"`
	GraphRoundStarted        bool                            `json:"graph_round_started,omitempty"`
	GraphRoundReady          bool                            `json:"graph_round_ready,omitempty"`
	GraphRoundAutonomous     bool                            `json:"graph_round_autonomous,omitempty"`
	GraphRoundHuman          bool                            `json:"graph_round_human,omitempty"`
	GraphSummaryReady        bool                            `json:"graph_summary_ready,omitempty"`
	GraphAutonomous          bool                            `json:"graph_autonomous,omitempty"`
	GraphHuman               bool                            `json:"graph_human,omitempty"`
	GraphRootTerminalVersion int64                           `json:"graph_root_terminal_version,omitempty"`
	CapacityFailure          string                          `json:"capacity_failure,omitempty"`
	FailureRoundInput        int64                           `json:"failure_round_input,omitempty"`
	FailureRoundOutput       int64                           `json:"failure_round_output,omitempty"`
	FailureReportedInput     int64                           `json:"failure_reported_input,omitempty"`
	FailureReportedOutput    int64                           `json:"failure_reported_output,omitempty"`
	FailureGraphAfterAt      *time.Time                      `json:"failure_graph_after_at,omitempty"`
	FailureGraphAfterID      uuid.UUID                       `json:"failure_graph_after_id,omitempty"`
	FailureNodeAfterID       uuid.UUID                       `json:"failure_node_after_id,omitempty"`
	FailureActionsDrained    bool                            `json:"failure_actions_drained,omitempty"`
	HostedActions            map[string]bool                 `json:"hosted_actions,omitempty"`
	ArtifactAfterAt          *time.Time                      `json:"artifact_after_at,omitempty"`
	ArtifactAfterID          uuid.UUID                       `json:"artifact_after_id,omitempty"`
	Artifacts                map[string]bool                 `json:"artifacts,omitempty"`
}

func (b *portalNativeBackend) Step(ctx context.Context, session *runtimeportal.Session, turn runtimeportal.Turn) (runtimeportal.Progress, error) {
	var configuration struct {
		Contract json.RawMessage `json:"contract"`
	}
	if err := json.Unmarshal(session.Bootstrap.Configuration, &configuration); err != nil {
		return runtimeportal.Progress{}, err
	}
	frozen, err := serviceapi.ReadContract(configuration.Contract)
	if err != nil {
		return runtimeportal.Progress{}, err
	}
	frozen.PortalCommandID = turn.Command.Context.CommandID
	if err = serviceapi.CheckPortalExecution(ctx, b.server.store, frozen, frozen.Endpoint.Tenant, frozen.Endpoint.Namespace); err != nil {
		return runtimeportal.Progress{}, err
	}
	ctx = serviceapi.WithContract(ctx, frozen)
	id, err := uuid.Parse(turn.NativeInvocationID)
	if err != nil {
		return runtimeportal.Progress{}, err
	}
	inv, err := b.server.store.Endpoints().GetInvocation(ctx, id)
	if errors.Is(err, store.ErrNotFound) {
		// Keep resolved credentials and business input in the encrypted inbox.
		// Native rows contain an immutable reference and logical scope only.
		reference, _ := json.Marshal(serviceapi.Contract{PublicSessionID: frozen.PublicSessionID, PortalCommandID: frozen.PortalCommandID, Endpoint: frozen.Endpoint})
		actor := model.Actor{Type: model.ActorHuman, Ref: fmt.Sprint(turn.Command.Context.Actor["user"])}
		if actor.Ref == "" || actor.Ref == "<nil>" {
			actor = model.Actor{Type: model.ActorSystem, Ref: "portal-admission"}
		}
		inv, _, err = b.server.store.Endpoints().ReserveInvocation(ctx, &model.EndpointInvocation{ID: id, EndpointID: frozen.Endpoint.ID,
			Mode: frozen.Endpoint.InvocationMode, PrincipalType: "portal", PrincipalRef: "portal-session:" + frozen.PublicSessionID.String(),
			IdempotencyKey: turn.Command.Context.CommandID, Status: model.EndpointInvocationAccepted, Actor: actor,
			Contract: reference, CorrelationID: turn.Command.Context.TraceID})
	}
	if err != nil {
		return runtimeportal.Progress{}, err
	}
	if inv.ID != id || inv.EndpointID != frozen.Endpoint.ID {
		return runtimeportal.Progress{}, store.ErrConflict
	}
	controlIndex, controlDone, err := b.applyControls(ctx, turn)
	if err != nil {
		return runtimeportal.Progress{}, err
	}
	if inv.Mode == model.EndpointConversationMode {
		err = b.server.dispatchServiceConversation(ctx, id)
	} else {
		err = b.server.dispatchEndpointInvocation(ctx, id)
	}
	if err != nil {
		return runtimeportal.Progress{}, err
	}
	if frozen.Endpoint.TargetType != model.EndpointTargetAgent {
		inv, err = b.server.store.Endpoints().GetInvocation(ctx, id)
		if err != nil {
			return runtimeportal.Progress{}, err
		}
		if inv.RunID != nil {
			if err = (&orchestration.Engine{Store: b.server.store, RequirePortal: b.server.portalExecution()}).ReconcileRun(ctx, *inv.RunID); err != nil {
				return runtimeportal.Progress{}, err
			}
		}
	}
	progress, err := b.observe(ctx, turn, true)
	if turn.ControlCommandID != "" {
		progress.ControlAnswerIndex, progress.ControlComplete = &controlIndex, controlDone
		// Continue finite batches under a fresh grant while decisions remain.
		if !controlDone {
			progress.Status = "running"
			facts := progress.Facts[:0]
			for _, fact := range progress.Facts {
				if fact.Type != "turn.requires_action" {
					facts = append(facts, fact)
				}
			}
			progress.Facts = facts
		}
	}
	return progress, err
}

func (b *portalNativeBackend) Observe(ctx context.Context, _ *runtimeportal.Session, turn runtimeportal.Turn) (runtimeportal.Progress, error) {
	return b.observe(ctx, turn, true)
}

func (b *portalNativeBackend) Stop(ctx context.Context, _ *runtimeportal.Session, turn runtimeportal.Turn) (runtimeportal.Progress, error) {
	if turn.NativeInvocationID == "" {
		return runtimeportal.Progress{Status: "cancelled"}, nil
	}
	id, err := uuid.Parse(turn.NativeInvocationID)
	if err != nil {
		return runtimeportal.Progress{}, err
	}
	if _, err = b.server.store.Endpoints().GetInvocation(ctx, id); errors.Is(err, store.ErrNotFound) {
		return runtimeportal.Progress{Status: "cancelled"}, nil
	}
	if err != nil {
		return runtimeportal.Progress{}, err
	}
	if err = b.server.cancelServiceInvocation(ctx, id, false); err != nil {
		return runtimeportal.Progress{}, err
	}
	return b.observe(ctx, turn, true)
}

func (b *portalNativeBackend) observe(ctx context.Context, turn runtimeportal.Turn, terminalFact bool) (runtimeportal.Progress, error) {
	s := b.server
	id, err := uuid.Parse(turn.NativeInvocationID)
	if err != nil {
		return runtimeportal.Progress{}, err
	}
	inv, err := s.store.Endpoints().GetInvocation(ctx, id)
	if err != nil {
		return runtimeportal.Progress{}, err
	}
	cursor := portalNativeCursor{RoundReady: true, Attempts: map[string]*portalAttemptCursor{}}
	if turn.NativeCursor != "" {
		if err = json.Unmarshal([]byte(turn.NativeCursor), &cursor); err != nil {
			return runtimeportal.Progress{}, err
		}
	}
	if cursor.Attempts == nil {
		cursor.Attempts = map[string]*portalAttemptCursor{}
	}
	if cursor.CapacityFailure != "" {
		return b.observeCapacityStop(ctx, turn, inv, &cursor)
	}
	progress := runtimeportal.Progress{Status: "running", NativeStarted: inv.RunID != nil || inv.ConversationID != nil}
	emit := func(key, kind string, payload map[string]any) {
		progress.Facts = append(progress.Facts, runtimeprotocol.RuntimeFact{EventID: "native:" + id.String() + ":" + key, Type: kind, Payload: payload})
	}
	if !cursor.Running || cursor.Generation != turn.Generation {
		cursor.Running = true
		cursor.Generation = turn.Generation
		cursor.GraphRoundStarted, cursor.GraphSummaryReady = false, false
		emit("running:"+strconv.FormatInt(turn.Generation, 10), "turn.running", map[string]any{"native_started": progress.NativeStarted})
	}
	nativeStatus := "running"
	eventBudget := 64
	graphDrained, graphAutonomous, graphHuman, graph := true, false, false, false
	contract, err := serviceapi.ReadContract(inv.Contract)
	if err != nil {
		return runtimeportal.Progress{}, err
	}
	graph = contract.Endpoint.TargetType != model.EndpointTargetAgent
	if inv.RunID != nil {
		run, err := s.store.Orchestration().GetRun(ctx, *inv.RunID)
		if err != nil {
			return runtimeportal.Progress{}, err
		}
		var events []*model.RunEvent
		if !graph {
			events, err = s.store.Orchestration().ListRunEvents(ctx, run.ID, cursor.RunSequence, 16)
			if err != nil {
				return runtimeportal.Progress{}, err
			}
		}
		for _, event := range events {
			approvalFact, factErr := b.approvalEvent(ctx, turn, event)
			if factErr != nil {
				return runtimeportal.Progress{}, factErr
			}
			if approvalFact != nil {
				if err = cursor.trackHostedAction(*approvalFact); err != nil {
					return portalCapacityProgress(&cursor, progress, portalObservationCapacityError("hosted_actions"))
				}
				approvalFact.EventID = "native:" + id.String() + ":run:" + run.ID.String() + ":" + strconv.FormatInt(event.Sequence, 10) + ":approval"
				progress.Facts = append(progress.Facts, *approvalFact)
			}
			if !graph && strings.HasPrefix(event.Type, "node.") || strings.HasPrefix(event.Type, "attempt.") && event.Type != "attempt.provider_event" {
				step := run.ID.String()
				if event.NodeID != nil {
					step = event.NodeID.String()
				}
				emit("run:"+run.ID.String()+":"+strconv.FormatInt(event.Sequence, 10), "step.updated", map[string]any{"step_id": step, "status": event.Type})
			}
			cursor.RunSequence = event.Sequence
		}
		eventBudget -= len(events)
		switch run.State {
		case model.RunPaused:
			nativeStatus = "requires_action"
		case model.RunSucceeded, model.RunPartialSucceeded:
			nativeStatus = "completed"
		case model.RunFailed:
			nativeStatus = "failed"
		case model.RunCancelled:
			nativeStatus = "cancelled"
		}
		if graph {
			graphDrained, graphAutonomous, graphHuman, err = b.observeGraph(ctx, turn, run, &cursor, &progress)
			if err != nil {
				var capacity portalObservationCapacityError
				if errors.As(err, &capacity) {
					return portalCapacityProgress(&cursor, progress, capacity)
				}
				return runtimeportal.Progress{}, err
			}
			eventBudget -= 16
			if !runtimeportal.Terminal(nativeStatus) && run.State != model.RunPaused && !graphAutonomous && graphHuman {
				nativeStatus = "requires_action"
			}
		}
		progress.ObservationOnly = runtimeportal.Terminal(nativeStatus)
		if len(events) == 16 && runtimeportal.Terminal(nativeStatus) {
			nativeStatus = "running"
		}
	} else if serviceapi.Terminal(inv.Status) {
		if inv.Status == model.EndpointInvocationCompleted || inv.Status == model.EndpointInvocationPartialSucceeded {
			nativeStatus = "completed"
		} else if inv.Status == model.EndpointInvocationCancelled {
			nativeStatus = "cancelled"
		} else {
			nativeStatus = "failed"
		}
	}
	attempts, err := s.store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{Tenant: turn.Command.Context.Tenant, Namespace: turn.Command.Context.Namespace,
		PortalCommandID: turn.Command.Context.CommandID, AfterCreatedAt: cursor.AfterCreatedAt, AfterID: cursor.AfterID, Limit: 16})
	if err != nil {
		return runtimeportal.Progress{}, err
	}
	pageDrained := true
	for _, attempt := range attempts {
		key := attempt.ID.String()
		state := cursor.Attempts[key]
		if state == nil {
			if len(cursor.Attempts) >= 8192 {
				return portalCapacityProgress(&cursor, progress, portalObservationCapacityError("native_attempts"))
			}
			state = &portalAttemptCursor{}
			cursor.Attempts[key] = state
		}
		if !model.IsExecutionAttemptTerminal(attempt.State) {
			cursor.RoundReady = false
		}
		if attempt.State == model.ExecutionWaiting && !graph {
			nativeStatus = "requires_action"
		}
		if attempt.BackendKind == model.DataPlaneHostedRuntime && model.IsExecutionAttemptTerminal(attempt.State) && attempt.HostID != nil {
			stopped, err := s.portalNativeStopped(ctx, attempt)
			if err != nil {
				return runtimeportal.Progress{}, err
			}
			if !stopped {
				cursor.RoundReady = false
			}
		}
		sessions, err := s.store.Sessions().List(ctx, store.SessionFilter{Tenant: attempt.Tenant, Namespace: attempt.Namespace, SessionID: attempt.SessionID, Limit: 2})
		if err != nil {
			return runtimeportal.Progress{}, err
		}
		if len(sessions) == 1 && eventBudget > 0 && len(progress.Facts) < portalObservationFactLimit {
			events, err := s.store.Events().List(ctx, sessions[0].ID, store.WithEventAfterSeq(state.Sequence), store.WithEventLimit(eventBudget))
			if err != nil {
				return runtimeportal.Progress{}, err
			}
			more := len(events) == eventBudget
			eventBudget -= len(events)
			for _, event := range events {
				offset := state.EventFactOffset
				facts, drained, err := portalNativeEventPage(event, attempt, inv, state, portalObservationFactLimit-len(progress.Facts))
				if err != nil {
					var capacity portalObservationCapacityError
					if errors.As(err, &capacity) {
						return portalCapacityProgress(&cursor, progress, capacity)
					}
					return runtimeportal.Progress{}, err
				}
				for index, fact := range facts {
					fact.EventID = "native:session:" + sessions[0].ID.String() + ":" + strconv.Itoa(event.Seq) + ":" + strconv.Itoa(offset+index)
					progress.Facts = append(progress.Facts, fact)
				}
				if !drained {
					pageDrained = false
					break
				}
			}
			if more || !pageDrained {
				pageDrained = false
				break
			}
		} else if eventBudget <= 0 || len(progress.Facts) >= portalObservationFactLimit {
			pageDrained = false
			break
		}
		// Completion usage can include totals also reported in model events.
		// Emit only the positive remainder, with a stable cumulative identity.
		var usage map[string]any
		_ = json.Unmarshal(attempt.Usage, &usage)
		input, output := portalUsage(usage)
		if input > state.InputTokens || output > state.OutputTokens {
			if len(progress.Facts) >= portalObservationFactLimit {
				pageDrained = false
				break
			}
			deltaIn, deltaOut := max(0, input-state.InputTokens), max(0, output-state.OutputTokens)
			reportID := key + ":reported-total:" + strconv.FormatInt(input, 10) + ":" + strconv.FormatInt(output, 10)
			emit("usage:"+key+":"+strconv.FormatInt(input, 10)+":"+strconv.FormatInt(output, 10), "usage.recorded", map[string]any{"model_call_id": reportID, "usage": map[string]any{"inputTokens": deltaIn, "outputTokens": deltaOut}})
			state.InputTokens, state.OutputTokens = max(input, state.InputTokens), max(output, state.OutputTokens)
		}
		at := attempt.CreatedAt
		cursor.AfterCreatedAt, cursor.AfterID = &at, attempt.ID
	}
	roundDone := pageDrained && len(attempts) < 16
	if runtimeportal.Terminal(nativeStatus) && (!roundDone || !cursor.RoundReady || !graphDrained) {
		nativeStatus = "running"
	}
	if roundDone {
		cursor.AfterCreatedAt, cursor.AfterID = nil, uuid.Nil
		cursor.RoundReady = true
	}
	artifactsDrained, err := b.observeArtifacts(ctx, turn, &cursor, &progress)
	if err != nil {
		var capacity portalObservationCapacityError
		if errors.As(err, &capacity) {
			return portalCapacityProgress(&cursor, progress, capacity)
		}
		return runtimeportal.Progress{}, err
	}
	if runtimeportal.Terminal(nativeStatus) && !artifactsDrained {
		nativeStatus = "running"
	}
	if nativeStatus == "requires_action" && turn.Status != "requires_action" {
		emit("waiting:"+strconv.FormatInt(turn.Generation, 10), "turn.requires_action", map[string]any{})
	}
	if nativeStatus == "running" && turn.Status == "requires_action" && !progress.ObservationOnly {
		emit("resumed:"+strconv.FormatInt(turn.Generation, 10), "turn.running", map[string]any{"native_started": progress.NativeStarted})
	}
	if runtimeportal.Terminal(nativeStatus) && terminalFact {
		drained, err := b.resolveStoppedHostedActions(ctx, turn, &cursor, &progress)
		if err != nil {
			return runtimeportal.Progress{}, err
		}
		if !drained {
			nativeStatus = "running"
		}
	}
	if runtimeportal.Terminal(nativeStatus) && terminalFact {
		var result json.RawMessage
		if nativeStatus == "completed" {
			result = inv.Result
			if inv.RunID != nil {
				run, err := s.store.Orchestration().GetRun(ctx, *inv.RunID)
				if err != nil {
					return runtimeportal.Progress{}, err
				}
				result = run.Output
			}
		}
		inv, err = b.finalizeInvocation(ctx, inv, nativeStatus, result, cursor.Attempts)
		if err != nil {
			return runtimeportal.Progress{}, err
		}
		if inv.Status == model.EndpointInvocationFailed {
			nativeStatus = "failed"
		}
		if nativeStatus == "completed" && len(inv.Result) > 0 {
			emit("result", "item.completed", map[string]any{"item_id": turn.Command.Context.CommandID + ":result", "final_output": true, "item": map[string]any{"role": "assistant", "content": []any{map[string]any{"type": "text", "text": string(inv.Result)}}}})
		}
		emit("terminal", "turn."+nativeStatus, map[string]any{"native_started": progress.NativeStarted, "native_stopped": true, "reason": inv.ErrorCode})
	}
	progress.Status = nativeStatus
	for _, state := range cursor.Attempts {
		progress.ReportedTokens += state.InputTokens + state.OutputTokens
	}
	raw, err := json.Marshal(cursor)
	if err != nil {
		return runtimeportal.Progress{}, err
	}
	progress.Cursor = string(raw)
	return progress, nil
}

// A source event can contain many public facts. Keep its stable fact offset in
// the same transaction as emitted facts, and advance the native sequence only
// when the whole event has drained. Provider cumulative usage is speculative
// until then; remapping a deferred event must not consume its usage update.
func portalNativeEventPage(event *store.SessionEvent, attempt *model.ExecutionAttempt, inv *model.EndpointInvocation, state *portalAttemptCursor, limit int) ([]runtimeprotocol.RuntimeFact, bool, error) {
	if limit <= 0 {
		return nil, false, nil
	}
	candidate := *state
	if state.ProviderUsage != nil {
		candidate.ProviderUsage = make(map[string]*portalProviderUsage, len(state.ProviderUsage))
		for key, usage := range state.ProviderUsage {
			value := *usage
			candidate.ProviderUsage[key] = &value
		}
	}
	facts, err := portalNativeEvents(event, attempt, inv, &candidate)
	if err != nil {
		return nil, false, err
	}
	if state.EventFactOffset < 0 || state.EventFactOffset > len(facts) {
		return nil, false, fmt.Errorf("invalid native event fact offset")
	}
	end := min(len(facts), state.EventFactOffset+limit)
	page := facts[state.EventFactOffset:end]
	for _, fact := range page {
		if fact.Type == "usage.recorded" {
			input, output := portalUsage(fact.Payload["usage"])
			state.InputTokens += input
			state.OutputTokens += output
		}
	}
	drained := end == len(facts)
	if drained {
		state.Sequence, state.EventFactOffset = event.Seq, 0
		state.ProviderUsage = candidate.ProviderUsage
	} else {
		state.EventFactOffset = end
	}
	return page, drained, nil
}

func (b *portalNativeBackend) finalizeInvocation(ctx context.Context, inv *model.EndpointInvocation, status string, result json.RawMessage, attempts map[string]*portalAttemptCursor) (*model.EndpointInvocation, error) {
	var finalized *model.EndpointInvocation
	var tokens int64
	for _, state := range attempts {
		tokens += state.InputTokens + state.OutputTokens
	}
	err := b.server.store.WithSessionLock(ctx, "service-invocation:"+inv.ID.String(), func(ctx context.Context) error {
		current, err := b.server.store.Endpoints().GetInvocation(ctx, inv.ID)
		if err != nil {
			return err
		}
		if serviceapi.Terminal(current.Status) {
			finalized = current
			if tokens > current.ConsumedTokens {
				current.ConsumedTokens = tokens
				finalized, err = b.server.store.Endpoints().UpdateInvocation(ctx, current)
			}
			return err
		}
		switch status {
		case "completed":
			current.Status = model.EndpointInvocationCompleted
			endpoint, err := serviceapi.BoundEndpoint(ctx, b.server.store, current)
			if err != nil {
				return err
			}
			serviceapi.CompleteResult(endpoint, current, result)
		case "cancelled":
			current.Status = model.EndpointInvocationCancelled
		default:
			current.Status = model.EndpointInvocationFailed
		}
		current.ConsumedTokens = max(current.ConsumedTokens, tokens)
		now := time.Now().UTC()
		current.CompletedAt = &now
		finalized, err = b.server.store.Endpoints().UpdateInvocation(ctx, current)
		return err
	})
	return finalized, err
}

func portalUsage(value any) (int64, int64) {
	usage, _ := value.(map[string]any)
	counter := func(keys ...string) int64 {
		for _, key := range keys {
			switch value := usage[key].(type) {
			case float64:
				return max(0, int64(value))
			case json.Number:
				n, _ := value.Int64()
				return max(0, n)
			case int64:
				return max(0, value)
			case int:
				return max(0, int64(value))
			}
		}
		return 0
	}
	return counter("inputTokens", "input_tokens", "promptTokens", "prompt_tokens"), counter("outputTokens", "output_tokens", "completionTokens", "completion_tokens")
}

func portalNativeEvents(event *store.SessionEvent, attempt *model.ExecutionAttempt, inv *model.EndpointInvocation, state *portalAttemptCursor) ([]runtimeprotocol.RuntimeFact, error) {
	var meta map[string]any
	if len(event.FrameworkMeta) > 0 {
		decoder := json.NewDecoder(bytes.NewReader(event.FrameworkMeta))
		decoder.UseNumber()
		if err := decoder.Decode(&meta); err != nil {
			return nil, err
		}
	}
	if id, ok := meta["attemptId"].(string); ok && id != "" && id != attempt.ID.String() {
		return nil, nil
	}
	if id, ok := meta["endpointInvocationId"].(string); ok && id != "" && id != inv.ID.String() {
		return nil, nil
	}
	if meta["attemptId"] != attempt.ID.String() && meta["endpointInvocationId"] != inv.ID.String() && event.OccurredAt.Before(inv.CreatedAt) {
		return nil, nil
	}
	payload := map[string]any{"step_id": attempt.AgentTaskID.String(), "native_execution_id": attempt.ID.String(), "agent_id": attempt.AgentID.String()}
	if name, ok := meta["provider"].(string); ok && name != "" {
		facts, err := portalHostedEvents(event, name, meta, state)
		for index := range facts {
			for key, value := range payload {
				facts[index].Payload[key] = value
			}
			if item, ok := facts[index].Payload["item_id"].(string); ok {
				facts[index].Payload["item_id"] = attempt.ID.String() + ":" + item
			}
			if call, ok := facts[index].Payload["tool_call_id"].(string); ok {
				facts[index].Payload["tool_call_id"] = attempt.ID.String() + ":" + call
			}
			if call, ok := facts[index].Payload["model_call_id"].(string); ok {
				facts[index].Payload["model_call_id"] = attempt.ID.String() + ":" + call
			}
		}
		return facts, err
	}
	kind := ""
	if public, ok := meta["public_event"].(map[string]any); ok {
		kind, _ = public["type"].(string)
		if !publicServiceRuntimeType(kind) || strings.HasPrefix(kind, "required_action.") {
			return nil, nil
		}
		values, _ := public["payload"].(map[string]any)
		for key, value := range values {
			if key != "source" && key != "run_id" && key != "turn_id" && key != "execution_id" && key != "agent_id" {
				payload[key] = value
			}
		}
		if kind == "run.ended" {
			kind = "execution.ended"
		}
	} else if event.ToolName != "" {
		kind = "tool.completed"
		if event.ToolOutput == "" {
			kind = "tool.requested"
		}
		payload["tool_call_id"] = attempt.ID.String() + ":" + strconv.Itoa(event.Seq)
		payload["name"], payload["input"], payload["result"] = event.ToolName, event.ToolInput, event.ToolOutput
	} else if strings.EqualFold(event.Role, "assistant") && event.Content != "" && !strings.Contains(event.EventType, "thinking") && !strings.Contains(event.EventType, "reasoning") {
		kind = "item.completed"
		payload["item_id"] = attempt.ID.String() + ":" + strconv.Itoa(event.Seq)
		payload["item"] = map[string]any{"role": "assistant", "content": []any{map[string]any{"type": "text", "text": event.Content}}}
	}
	if kind == "" {
		return nil, nil
	}
	if item, ok := payload["item_id"].(string); ok {
		payload["item_id"] = attempt.ID.String() + ":" + item
	}
	return []runtimeprotocol.RuntimeFact{{Type: kind, Payload: payload}}, nil
}

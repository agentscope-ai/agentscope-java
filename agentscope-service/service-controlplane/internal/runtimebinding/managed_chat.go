// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package runtimebinding

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/conversation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
)

// DispatchManagedChatTurn admits a console chat turn on a DataPlaneManaged
// session through the same AgentTask machinery the task scheduler uses: one
// operational Issue + Run + Node + AgentTask per turn, a claimed execution
// attempt, the runtime-session binding, the managed runtime fence, and only
// then the data-plane wake.
//
// The wake alone is not enough. The data plane resolves the session scope from
// the claimed attempt; without it the session is treated as unmanaged and the
// event mirror never runs, so the chat panel stays empty even though the turn
// executes. Hosting walks this exact ceremony per conversation turn; managed
// chat skipped it and lost the whole event loop.
//
// The wake carries the raw chat message. Unlike scheduler dispatch, the turn
// is not driven by a task envelope — the data plane consumes the wake text as
// the user message.
func (r *Resolver) DispatchManagedChatTurn(ctx context.Context, session *store.Session,
	binding controlmodel.RuntimeBinding, ownerRef, message string) (
	*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	if r.Managed == nil {
		return nil, nil, fmt.Errorf("managed runtime adapter is not configured")
	}
	var task *controlmodel.AgentTask
	var attempt *controlmodel.ExecutionAttempt
	lockKey := session.Tenant + "\x00" + session.Namespace + "\x00" +
		session.AgentID.String() + "\x00" + session.SessionID
	err := r.Store.WithSessionLock(ctx, lockKey, func(lockCtx context.Context) error {
		admittedTask, admittedAttempt, admitErr := r.admitManagedChatTurnLocked(
			lockCtx, session, binding, ownerRef, message)
		if admitErr != nil {
			return admitErr
		}
		task, attempt = admittedTask, admittedAttempt
		return nil
	})
	if err != nil {
		return nil, nil, err
	}
	return task, attempt, nil
}

// admitManagedChatTurnLocked runs the full admission ceremony while holding the
// session lock. Ordering matters: the fence must be claimed before the wake so
// the data plane's very first resolve already sees a managed scope.
func (r *Resolver) admitManagedChatTurnLocked(ctx context.Context, session *store.Session,
	binding controlmodel.RuntimeBinding, ownerRef, message string) (
	*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	current, err := r.Store.Sessions().GetByID(ctx, session.ID)
	if err != nil {
		return nil, nil, err
	}
	if err := conversation.CheckChatActive(ctx, r.Store, current); err != nil {
		return nil, nil, err
	}
	if current.AgentTaskID != nil {
		open, openErr := r.Store.Collaboration().GetAgentTask(ctx, *current.AgentTaskID)
		if openErr == nil && !controlmodel.IsAgentTaskTerminal(open.Status) {
			return nil, nil, fmt.Errorf("conversation already has a turn in progress: %w", store.ErrConflict)
		}
	}
	turnUUID := uuid.New()
	turnID := turnUUID.String()

	actor := controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "chat"}
	if current.OriginType == "chat" {
		chatID, parseErr := uuid.Parse(current.OriginRef)
		if parseErr != nil {
			return nil, nil, fmt.Errorf("conversation Chat identity is invalid: %w", parseErr)
		}
		chat, chatErr := r.Store.Chats().Get(ctx, chatID)
		if chatErr != nil || chat.SessionID != current.ID || chat.AgentID != current.AgentID ||
			chat.Tenant != current.Tenant || chat.Namespace != current.Namespace {
			return nil, nil, fmt.Errorf("conversation Chat is unavailable: %w", store.ErrNotFound)
		}
		// Private turn work belongs to the Chat's owner, mirroring hosted
		// conversations so diagnostics attribute the turn to a human.
		actor = controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: chat.CreatorRef}
	}

	issueID := uuid.NewSHA1(turnUUID, []byte("conversation-issue"))
	runID := uuid.NewSHA1(turnUUID, []byte("conversation-run"))
	nodeID := uuid.NewSHA1(runID, []byte("target"))
	contextRefs, _ := json.Marshal(map[string]any{"prompt": message})

	issue, err := r.Store.Collaboration().GetIssue(ctx, issueID)
	if errors.Is(err, store.ErrNotFound) {
		issue, err = r.Store.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
			ID: issueID, Tenant: current.Tenant, Namespace: current.Namespace,
			Title:               "Conversation turn",
			Description:         message,
			ContextRefs:         contextRefs,
			Status:              controlmodel.IssueInProgress,
			Priority:            "normal",
			Kind:                controlmodel.IssueKindConversationTurn,
			Visibility:          controlmodel.IssueVisibilityOperational,
			CompletionPolicy:    controlmodel.IssueCompletionAutomatic,
			Creator:             actor,
			SourceType:          "chat",
			SourceRef:           current.OriginRef,
			ExecutionTargetType: string(controlmodel.EndpointTargetAgent),
			ExecutionTargetRef:  current.AgentID.String(),
		})
		if err != nil && !errors.Is(err, store.ErrConflict) {
			return nil, nil, err
		}
		if issue == nil {
			issue, err = r.Store.Collaboration().GetIssue(ctx, issueID)
		}
	}
	if err != nil {
		return nil, nil, err
	}
	if issue.AssigneeType != controlmodel.AssigneeAgent || issue.AssigneeRef != current.AgentID.String() {
		issue.AssigneeType, issue.AssigneeRef = controlmodel.AssigneeAgent, current.AgentID.String()
		issue, err = r.Store.Collaboration().UpdateIssue(ctx, issue, issue.Version, actor)
		if err != nil {
			return nil, nil, err
		}
	}

	run, err := r.Store.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{
		ID: runID, Tenant: current.Tenant, Namespace: current.Namespace, RootIssueID: issueID,
		Mode: controlmodel.RunModeDirect, TriggerType: "chat", TriggerRef: current.OriginRef,
		IdempotencyKey: "conversation:" + current.SessionID + ":" + turnID,
		Input:          contextRefs, State: controlmodel.RunRunning, CreatedBy: actor,
	})
	if err != nil && !errors.Is(err, store.ErrConflict) {
		return nil, nil, err
	}
	if run == nil {
		run, err = r.Store.Orchestration().GetRun(ctx, runID)
	}
	if err != nil {
		return nil, nil, err
	}

	node, err := r.Store.Orchestration().CreateNode(ctx, &controlmodel.RunNode{ID: nodeID, RunID: run.ID,
		Tenant: current.Tenant, Namespace: current.Namespace, NodeKey: "target",
		Type: controlmodel.RunNodeAgent, IssueID: &issueID, State: controlmodel.RunNodeReady, Iteration: 1})
	if err != nil && !errors.Is(err, store.ErrConflict) {
		return nil, nil, err
	}
	if node == nil {
		node, err = r.Store.Orchestration().GetNode(ctx, nodeID)
	}
	if err != nil {
		return nil, nil, err
	}

	task, err := r.Store.Collaboration().CreateRunAgentTask(ctx, store.RunTaskRequest{
		RunID: run.ID, NodeID: node.ID, IssueID: issueID,
		AgentRef: current.AgentID.String(), Originator: actor,
	})
	if err != nil {
		return nil, nil, err
	}

	snapshot, _ := json.Marshal(controlmodel.RuntimeDispatchSnapshot{
		Binding: binding, SessionID: current.SessionID, ResolvedAt: time.Now().UTC()})
	dispatched, admitted, err := r.Store.Collaboration().ClaimAgentTaskWithAttempt(ctx, store.TaskClaim{
		TaskID: task.ID, ExpectedVersion: task.Version, RuntimeBinding: snapshot,
		SessionID: current.SessionID,
	}, &controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
		AgentID: binding.AgentID, BindingID: binding.BindingID,
		State: controlmodel.ExecutionAssigned, ManagedOwnerRef: ownerRef,
		ManagedAgentRef: binding.ManagedDefinitionRef, SessionID: current.SessionID, TurnID: turnID})
	if err != nil {
		return nil, nil, err
	}

	// Bind the runtime session to the task while keeping its chat identity:
	// overwriting OriginType/OriginRef would break CheckChatActive on the next
	// message. This mirrors persistSession minus the agent-task origin.
	now := time.Now().UTC()
	current.Phase, current.AgentTaskID, current.LastActiveAt = store.SessionPhaseActive, &dispatched.ID, &now
	if _, err = r.Store.Sessions().Upsert(ctx, current); err != nil {
		return nil, nil, err
	}
	if err = r.Store.Turns().SyncOnPhase(ctx, current.ID, store.SessionPhaseActive); err != nil {
		return nil, nil, err
	}

	// The fence is what turns the data plane's event mirror on. If it cannot be
	// claimed the turn would run unmirrored, so put the task back in the queue
	// instead of waking into a blind session.
	if err = r.Managed.ClaimManagedRuntimeFence(ctx, current.SessionID, dispatched.ID, admitted.ID,
		admitted.DispatchGeneration, admitted.TurnID); err != nil {
		_, _, _ = r.Store.Collaboration().RequeueAgentTaskAfterAttemptFailure(ctx, dispatched.ID, store.TaskFailure{
			ExpectedVersion: dispatched.Version, AttemptID: admitted.ID,
			DispatchGeneration: admitted.DispatchGeneration,
			Code:               "managed_runtime_fence_claim_failed",
			Message:            err.Error()})
		return nil, nil, err
	}

	// Record the user turn in the runtime store immediately. The data plane
	// records its own copy behind the admission, so it never mirrors this event
	// back and the local copy is the only one the chat panel needs.
	if err = r.appendManagedChatEvent(ctx, current.ID, "user:"+turnID, &store.SessionEvent{
		EventType: "user.message", Role: "user", Content: message, OccurredAt: now,
		FrameworkMeta: managedChatJSON(map[string]any{"turnId": turnID, "sourceType": "chat"}),
	}); err != nil {
		return nil, nil, err
	}
	if err = r.appendManagedChatEvent(ctx, current.ID, "turn-started:"+turnID, &store.SessionEvent{
		EventType: "turn.started", OccurredAt: now,
		FrameworkMeta: managedChatJSON(map[string]any{"turnId": turnID}),
	}); err != nil {
		return nil, nil, err
	}

	// Last step: the data plane must resolve a fully admitted session.
	if err = r.Managed.PostSessionWakeEvent(ctx, current.SessionID, ownerRef, message); err != nil {
		return nil, nil, err
	}
	return dispatched, admitted, nil
}

// appendManagedChatEvent appends a deduplicated session event. It mirrors the
// hosted conversation event writer: the source key makes retries no-ops and the
// sequence continues the session's existing history.
func (r *Resolver) appendManagedChatEvent(ctx context.Context, sessionFK uuid.UUID,
	sourceKey string, event *store.SessionEvent) error {
	events, err := r.Store.Events().List(ctx, sessionFK)
	if err != nil {
		return err
	}
	maxSeq := 0
	for _, existing := range events {
		if existing.Seq > maxSeq {
			maxSeq = existing.Seq
		}
		var metadata map[string]any
		if json.Unmarshal(existing.FrameworkMeta, &metadata) == nil && metadata["sourceKey"] == sourceKey {
			return nil
		}
	}
	metadata := map[string]any{}
	_ = json.Unmarshal(event.FrameworkMeta, &metadata)
	metadata["sourceKey"] = sourceKey
	event.FrameworkMeta, _ = json.Marshal(metadata)
	event.SessionFK, event.Seq = sessionFK, maxSeq+1
	if event.OccurredAt.IsZero() {
		event.OccurredAt = time.Now().UTC()
	}
	return r.Store.Events().Append(ctx, event)
}

func managedChatJSON(value map[string]any) json.RawMessage {
	raw, _ := json.Marshal(value)
	return raw
}

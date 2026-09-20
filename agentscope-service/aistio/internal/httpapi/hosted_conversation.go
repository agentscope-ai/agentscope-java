// Copyright 2024-2026 the original author or authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/conversation"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/taskplane"
)

const (
	maxConversationReplayEvents = 32
	maxConversationReplayBytes  = 48 * 1024
)

type hostedTurnResult struct {
	IssueID     uuid.UUID
	RunID       uuid.UUID
	AgentTaskID uuid.UUID
	AttemptID   uuid.UUID
}

func (s *Server) hostedConversationCandidate(ctx context.Context, agent *controlmodel.Agent,
	candidate controlmodel.RuntimeBindingCandidate) bool {
	if candidate.Binding.Kind != controlmodel.DataPlaneHostedRuntime ||
		candidate.Binding.AgentID != agent.ID {
		return false
	}
	profile, err := s.store.RuntimeRegistry().GetRuntimeProfileByID(ctx, candidate.Binding.RuntimeProfileID)
	if err != nil || profile.Tenant != agent.Tenant || profile.Namespace != agent.Namespace {
		return false
	}
	pool, err := s.store.RuntimeRegistry().GetRuntimePoolByID(ctx, candidate.Binding.RuntimePoolID)
	if err != nil || pool.Tenant != agent.Tenant || pool.Namespace != agent.Namespace {
		return false
	}
	hosts, err := s.store.RuntimeRegistry().ListRuntimeHosts(ctx, agent.Tenant, agent.Namespace,
		pool.Name, controlmodel.RuntimeHostOnline)
	if err != nil {
		return false
	}
	for _, host := range hosts {
		// Conversation turns are queueable. Capacity gates the Runtime Host's
		// claim, not whether the logical conversation capability exists.
		if controlmodel.RuntimeHostMatchesProfile(host.Capabilities, profile) &&
			controlmodel.RuntimeHostMatchesPool(host.Labels, pool) &&
			controlmodel.JSONContains(host.Capabilities, candidate.RequiredCapabilities) &&
			controlmodel.RuntimeSecurityMatches(candidate.Binding.Kind, host.Labels,
				candidate.SecurityConstraints) {
			return true
		}
	}
	return false
}

func (s *Server) hostedCandidateForBinding(ctx context.Context, session *store.Session,
	binding *controlmodel.AgentBinding) (controlmodel.RuntimeBindingCandidate, error) {
	agent, err := s.store.AgentCatalog().GetAgent(ctx, session.AgentID)
	if err != nil || agent.Status != controlmodel.AgentActive {
		return controlmodel.RuntimeBindingCandidate{}, fmt.Errorf("conversation Agent is unavailable")
	}
	policy, err := s.store.Orchestration().GetRuntimePolicy(ctx, session.Tenant, session.Namespace,
		session.AgentID.String())
	if err != nil || policy.SelectionMode != "ordered" {
		return controlmodel.RuntimeBindingCandidate{}, fmt.Errorf("Agent has no runtime policy")
	}
	for _, candidate := range policy.Candidates {
		if candidate.Binding.BindingID == binding.ID && candidate.Binding.Kind == binding.Kind &&
			s.hostedConversationCandidate(ctx, agent, candidate) {
			return candidate, nil
		}
	}
	return controlmodel.RuntimeBindingCandidate{}, fmt.Errorf("Hosted runtime is not currently available")
}

func (s *Server) latestHostedAttempt(ctx context.Context, session *store.Session) (*controlmodel.ExecutionAttempt, error) {
	attempts, err := s.store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{
		Tenant: session.Tenant, Namespace: session.Namespace, AgentID: session.AgentID,
		BindingID: session.BindingID, SessionID: session.SessionID, NewestFirst: true, Limit: 100,
	})
	if err != nil {
		return nil, err
	}
	if len(attempts) == 0 {
		return nil, nil
	}
	// A failed resume attempt may contain the same provider session ID paired
	// with a newly-created, incorrect workspace. Never let that failed attempt
	// poison later turns; resume from the newest successful checkpoint instead.
	for _, attempt := range attempts {
		if attempt.State == controlmodel.ExecutionSucceeded {
			return attempt, nil
		}
	}
	// With no successful checkpoint there is no safe provider resume token.
	// Reusing the most recent host/workspace is still useful for transcript
	// replay and preserves any files produced before the failure.
	fallback := *attempts[0]
	fallback.ProviderSessionID = ""
	return &fallback, nil
}

func (s *Server) hostedResumeState(ctx context.Context, session *store.Session,
	candidate controlmodel.RuntimeBindingCandidate) (string, string, *uuid.UUID, error) {
	previous, err := s.latestHostedAttempt(ctx, session)
	if err != nil || previous == nil || previous.HostID == nil {
		return "", "", nil, err
	}
	host, err := s.store.RuntimeRegistry().GetRuntimeHost(ctx, *previous.HostID)
	if err != nil || host.State != controlmodel.RuntimeHostOnline {
		// A provider session and its working directory are host-local. When
		// affinity cannot be restored, replay the durable transcript on any
		// eligible host instead of sending a foreign resume token.
		return "", "", nil, nil
	}
	profile, profileErr := s.store.RuntimeRegistry().GetRuntimeProfileByID(ctx,
		candidate.Binding.RuntimeProfileID)
	pool, poolErr := s.store.RuntimeRegistry().GetRuntimePoolByID(ctx, candidate.Binding.RuntimePoolID)
	if profileErr != nil || poolErr != nil || host.PoolName != pool.Name ||
		!controlmodel.RuntimeHostMatchesProfile(host.Capabilities, profile) ||
		!controlmodel.RuntimeHostMatchesPool(host.Labels, pool) ||
		!controlmodel.JSONContains(host.Capabilities, candidate.RequiredCapabilities) ||
		!controlmodel.RuntimeSecurityMatches(candidate.Binding.Kind, host.Labels,
			candidate.SecurityConstraints) {
		return "", "", nil, nil
	}
	providerSessionID := previous.ProviderSessionID
	if !hostProviderSupportsResume(host.Capabilities, profile.Provider) {
		providerSessionID = ""
	}
	return providerSessionID, previous.WorkspaceKey, previous.HostID, nil
}

func hostProviderSupportsResume(capabilities json.RawMessage, providerName string) bool {
	var advertised struct {
		ProviderCapabilities map[string]struct {
			Resume bool `json:"resume"`
		} `json:"providerCapabilities"`
	}
	if json.Unmarshal(capabilities, &advertised) != nil {
		return false
	}
	descriptor, ok := advertised.ProviderCapabilities[providerName]
	return ok && descriptor.Resume
}

func (s *Server) dispatchHostedConversationTurn(ctx context.Context, session *store.Session,
	binding *controlmodel.AgentBinding, message, turnID, sourceType, sourceRef string) (*hostedTurnResult, error) {
	var result *hostedTurnResult
	lockKey := session.Tenant + "\x00" + session.Namespace + "\x00" +
		session.AgentID.String() + "\x00" + session.SessionID
	err := s.store.WithSessionLock(ctx, lockKey, func(lockCtx context.Context) error {
		current, err := s.store.Sessions().GetByID(lockCtx, session.ID)
		if err != nil {
			return err
		}
		if current.AgentTaskID != nil {
			task, taskErr := s.store.Collaboration().GetAgentTask(lockCtx, *current.AgentTaskID)
			if taskErr == nil && !controlmodel.IsAgentTaskTerminal(task.Status) {
				return fmt.Errorf("conversation already has a turn in progress: %w", store.ErrConflict)
			}
		}
		if err := conversation.CheckChatActive(lockCtx, s.store, current); err != nil {
			return err
		}
		candidate, err := s.hostedCandidateForBinding(lockCtx, current, binding)
		if err != nil {
			return err
		}
		providerSessionID, workspaceKey, preferredHostID, err := s.hostedResumeState(lockCtx, current, candidate)
		if err != nil {
			return err
		}
		prompt := message
		if providerSessionID == "" {
			prompt = s.conversationReplayPrompt(lockCtx, current.ID, message)
		}
		turnUUID, parseErr := uuid.Parse(turnID)
		if parseErr != nil {
			turnUUID = uuid.NewSHA1(uuid.NameSpaceURL, []byte(turnID))
		}
		issueID := uuid.NewSHA1(turnUUID, []byte("conversation-issue"))
		runID := uuid.NewSHA1(turnUUID, []byte("conversation-run"))
		actor := controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: sourceType}
		if current.OriginType == "chat" {
			chatID, parseErr := uuid.Parse(current.OriginRef)
			if parseErr != nil {
				return fmt.Errorf("conversation Chat identity is invalid: %w", parseErr)
			}
			chat, chatErr := s.store.Chats().Get(lockCtx, chatID)
			if chatErr != nil || chat.SessionID != current.ID || chat.AgentID != current.AgentID ||
				chat.Tenant != current.Tenant || chat.Namespace != current.Namespace {
				return fmt.Errorf("conversation Chat is unavailable: %w", store.ErrNotFound)
			}
			// Private turn work belongs to the Chat's owner. A system-created
			// private Issue is invisible to that owner during materialization
			// and later diagnostics, even though the Chat itself is accessible.
			actor = controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: chat.CreatorRef}
		}
		contextRefs, _ := json.Marshal(map[string]any{"prompt": prompt})
		issue := &controlmodel.Issue{
			ID: issueID, Tenant: current.Tenant, Namespace: current.Namespace,
			Title: "Conversation turn", Description: message, ContextRefs: contextRefs,
			Status: controlmodel.IssueInProgress, Priority: "normal",
			Kind: controlmodel.IssueKindConversationTurn, Visibility: controlmodel.IssueVisibilityOperational,
			CompletionPolicy: controlmodel.IssueCompletionAutomatic, Creator: actor,
			SourceType: sourceType, SourceRef: sourceRef,
			ExecutionTargetType: string(controlmodel.EndpointTargetAgent),
			ExecutionTargetRef:  current.AgentID.String(),
		}
		createdIssue, err := s.store.Collaboration().CreateIssue(lockCtx, issue)
		if err != nil && !errors.Is(err, store.ErrConflict) {
			return err
		}
		if createdIssue == nil {
			createdIssue, err = s.store.Collaboration().GetIssue(lockCtx, issueID)
		}
		if err != nil {
			return err
		}
		if createdIssue.AssigneeType != controlmodel.AssigneeAgent ||
			createdIssue.AssigneeRef != current.AgentID.String() {
			createdIssue.AssigneeType, createdIssue.AssigneeRef =
				controlmodel.AssigneeAgent, current.AgentID.String()
			createdIssue, err = s.store.Collaboration().UpdateIssue(lockCtx, createdIssue,
				createdIssue.Version, actor)
			if err != nil {
				return err
			}
		}
		run, err := s.store.Orchestration().CreateRun(lockCtx, &controlmodel.OrchestrationRun{
			ID: runID, Tenant: current.Tenant, Namespace: current.Namespace, RootIssueID: issueID,
			Mode: controlmodel.RunModeDirect, TriggerType: sourceType, TriggerRef: sourceRef,
			IdempotencyKey: "conversation:" + current.SessionID + ":" + turnID,
			Input:          contextRefs, State: controlmodel.RunRunning, CreatedBy: actor,
		})
		if err != nil && !errors.Is(err, store.ErrConflict) {
			return err
		}
		if run == nil {
			run, err = s.store.Orchestration().GetRun(lockCtx, runID)
		}
		if err != nil {
			return err
		}
		endpoint := &controlmodel.Endpoint{TargetType: controlmodel.EndpointTargetAgent,
			TargetRef: current.AgentID}
		if err = s.materializeEndpointTarget(lockCtx, endpoint, run, issueID, actor); err != nil {
			return err
		}
		tasks, err := s.store.Collaboration().ListAgentTasks(lockCtx, store.AgentTaskFilter{
			RunID: run.ID, AgentRef: current.AgentID.String(), Limit: 1,
		})
		if err != nil || len(tasks) == 0 {
			if err == nil {
				err = fmt.Errorf("conversation AgentTask was not materialized")
			}
			return err
		}
		task := tasks[0]
		now := time.Now().UTC()
		current.Phase, current.AgentTaskID, current.LastActiveAt = store.SessionPhaseActive, &task.ID, &now
		if _, err = s.store.Sessions().Upsert(lockCtx, current); err != nil {
			return err
		}
		if err = s.store.Turns().SyncOnPhase(lockCtx, current.ID, store.SessionPhaseActive); err != nil {
			return err
		}
		if err = s.appendSessionEventLocked(lockCtx, current.ID, "user:"+turnID, &store.SessionEvent{
			EventType: "user.message", Role: "user", Content: message, OccurredAt: now,
			FrameworkMeta: mustJSON(map[string]any{"turnId": turnID, "sourceType": sourceType}),
		}); err != nil {
			return err
		}
		if err = s.appendSessionEventLocked(lockCtx, current.ID, "turn-started:"+turnID, &store.SessionEvent{
			EventType: "turn.started", OccurredAt: now,
			FrameworkMeta: mustJSON(map[string]any{"turnId": turnID, "agentTaskId": task.ID, "runId": run.ID}),
		}); err != nil {
			return err
		}
		_, attempt, err := s.taskPlane.DispatchHostedConversationCandidate(lockCtx, task.ID, candidate,
			taskplane.HostedConversationContext{SessionID: current.SessionID, TurnID: turnID,
				ProviderSessionID: providerSessionID, WorkspaceKey: workspaceKey,
				PreferredHostID: preferredHostID})
		if err != nil {
			current.Phase = store.SessionPhaseIdle
			_, _ = s.store.Sessions().Upsert(lockCtx, current)
			_ = s.store.Turns().SyncOnPhase(lockCtx, current.ID, store.TurnStatusFailed)
			_ = s.appendSessionEventLocked(lockCtx, current.ID, "turn-dispatch-failed:"+turnID,
				&store.SessionEvent{EventType: "turn.failed", Role: "error", Content: err.Error(),
					FrameworkMeta: mustJSON(map[string]any{"turnId": turnID, "stage": "dispatch"})})
			return err
		}
		result = &hostedTurnResult{IssueID: issueID, RunID: run.ID, AgentTaskID: task.ID,
			AttemptID: attempt.ID}
		return nil
	})
	return result, err
}

func (s *Server) conversationReplayPrompt(ctx context.Context, sessionFK uuid.UUID, message string) string {
	events, err := s.store.Events().List(ctx, sessionFK, store.WithEventNewestFirst(),
		store.WithEventLimit(maxConversationReplayEvents))
	if err != nil || len(events) == 0 {
		return message
	}
	var lines []string
	total := 0
	for _, event := range events {
		if event.Content == "" || (event.Role != "user" && event.Role != "assistant") {
			continue
		}
		line := strings.ToUpper(event.Role[:1]) + event.Role[1:] + ": " + event.Content
		if total+len(line) > maxConversationReplayBytes {
			continue
		}
		lines, total = append(lines, line), total+len(line)
	}
	if len(lines) == 0 {
		return message
	}
	return "Conversation history:\n" + strings.Join(lines, "\n\n") + "\n\nUser: " + message
}

func (s *Server) appendSessionEventLocked(ctx context.Context, sessionFK uuid.UUID, sourceKey string,
	event *store.SessionEvent) error {
	events, err := s.store.Events().List(ctx, sessionFK)
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
	event.FrameworkMeta = mustJSON(metadata)
	event.SessionFK, event.Seq = sessionFK, maxSeq+1
	if event.OccurredAt.IsZero() {
		event.OccurredAt = time.Now().UTC()
	}
	return s.store.Events().Append(ctx, event)
}

func (s *Server) appendHostedSessionEvent(ctx context.Context, attempt *controlmodel.ExecutionAttempt,
	sourceKey string, event *store.SessionEvent) error {
	if attempt == nil || attempt.SessionID == "" || attempt.AgentID == uuid.Nil {
		return nil
	}
	if _, err := s.ensureHostedTaskSession(ctx, attempt); err != nil {
		return err
	}
	sessions, err := s.store.Sessions().List(ctx, store.SessionFilter{Tenant: attempt.Tenant,
		Namespace: attempt.Namespace, AgentID: attempt.AgentID, SessionID: attempt.SessionID, Limit: 1})
	if err != nil || len(sessions) == 0 {
		return err
	}
	session := sessions[0]
	lockKey := session.Tenant + "\x00" + session.Namespace + "\x00" +
		session.AgentID.String() + "\x00" + session.SessionID
	return s.store.WithSessionLock(ctx, lockKey, func(lockCtx context.Context) error {
		return s.appendSessionEventLocked(lockCtx, session.ID, sourceKey, event)
	})
}

func (s *Server) projectHostedProviderEvent(ctx context.Context, attempt *controlmodel.ExecutionAttempt,
	providerName, eventType, providerSessionID string, ordinal int64, raw json.RawMessage) error {
	if attempt == nil || attempt.SessionID == "" {
		return nil
	}
	return s.appendHostedSessionEvent(ctx, attempt,
		fmt.Sprintf("provider-event:%s:%d", attempt.ID, ordinal),
		hostedProviderSessionEvent(attempt, providerName, eventType, providerSessionID, ordinal, raw))
}

func hostedProviderSessionEvent(attempt *controlmodel.ExecutionAttempt, providerName, eventType, providerSessionID string, ordinal int64, raw json.RawMessage) *store.SessionEvent {
	summary := providerEventSummary(raw)
	if len(summary) > 4096 {
		summary = summary[:4096]
	}
	return &store.SessionEvent{
		EventType: "provider." + strings.ReplaceAll(eventType, "/", "."), Content: summary,
		FrameworkMeta: mustJSON(map[string]any{"provider": providerName, "providerEventType": eventType, "providerSessionId": providerSessionID, "attemptId": attempt.ID, "turnId": attempt.TurnID, "ordinal": ordinal, "raw": json.RawMessage(raw)}),
	}
}

func providerEventSummary(raw json.RawMessage) string {
	var value map[string]any
	if json.Unmarshal(raw, &value) != nil {
		return ""
	}
	for _, key := range []string{"result", "output", "text", "message"} {
		if text, ok := value[key].(string); ok && strings.TrimSpace(text) != "" {
			return text
		}
	}
	if item, ok := value["item"].(map[string]any); ok {
		if text, ok := item["text"].(string); ok {
			return text
		}
	}
	if params, ok := value["params"].(map[string]any); ok {
		for _, key := range []string{"message", "text"} {
			if text, ok := params[key].(string); ok && strings.TrimSpace(text) != "" {
				return text
			}
		}
		if item, ok := params["item"].(map[string]any); ok {
			if text, ok := item["text"].(string); ok {
				return text
			}
		}
	}
	return ""
}

func (s *Server) projectHostedAttemptTerminal(ctx context.Context, attempt *controlmodel.ExecutionAttempt) error {
	return s.projectHostedAttemptTerminalWithOutput(ctx, attempt, "")
}

func (s *Server) projectHostedAttemptTerminalWithOutput(ctx context.Context,
	attempt *controlmodel.ExecutionAttempt, responseOutput string) error {
	if attempt == nil || attempt.SessionID == "" {
		return nil
	}
	turnID := attempt.TurnID
	switch attempt.State {
	case controlmodel.ExecutionSucceeded:
		output := strings.TrimSpace(responseOutput)
		if output == "" {
			output = hostedResultOutput(attempt.Result)
		}
		if output != "" {
			if err := s.appendHostedSessionEvent(ctx, attempt, "assistant:"+attempt.ID.String(),
				&store.SessionEvent{EventType: "assistant.message", Role: "assistant", Content: output,
					FrameworkMeta: mustJSON(map[string]any{"turnId": turnID, "attemptId": attempt.ID})}); err != nil {
				return err
			}
		}
		if err := s.appendHostedSessionEvent(ctx, attempt, "turn-completed:"+attempt.ID.String(),
			&store.SessionEvent{EventType: "turn.completed",
				FrameworkMeta: mustJSON(map[string]any{"turnId": turnID, "attemptId": attempt.ID})}); err != nil {
			return err
		}
	case controlmodel.ExecutionFailed:
		if err := s.appendHostedSessionEvent(ctx, attempt, "turn-failed:"+attempt.ID.String(),
			&store.SessionEvent{EventType: "turn.failed", Role: "error", Content: attempt.FailureMessage,
				FrameworkMeta: mustJSON(map[string]any{"turnId": turnID, "attemptId": attempt.ID,
					"failureCode": attempt.FailureCode})}); err != nil {
			return err
		}
	case controlmodel.ExecutionCancelled:
		if err := s.appendHostedSessionEvent(ctx, attempt, "turn-cancelled:"+attempt.ID.String(),
			&store.SessionEvent{EventType: "turn.cancelled",
				FrameworkMeta: mustJSON(map[string]any{"turnId": turnID, "attemptId": attempt.ID})}); err != nil {
			return err
		}
	default:
		return nil
	}
	if err := s.markHostedSessionIdle(ctx, attempt); err != nil {
		return err
	}
	return s.projectHostedEndpointInvocation(ctx, attempt)
}

func (s *Server) markHostedSessionIdle(ctx context.Context, attempt *controlmodel.ExecutionAttempt) error {
	sessions, err := s.store.Sessions().List(ctx, store.SessionFilter{Tenant: attempt.Tenant,
		Namespace: attempt.Namespace, AgentID: attempt.AgentID, SessionID: attempt.SessionID, Limit: 1})
	if err != nil {
		return err
	}
	if len(sessions) == 0 {
		return nil
	}
	session := sessions[0]
	lockKey := session.Tenant + "\x00" + session.Namespace + "\x00" +
		session.AgentID.String() + "\x00" + session.SessionID
	return s.store.WithSessionLock(ctx, lockKey, func(lockCtx context.Context) error {
		current, err := s.store.Sessions().GetByID(lockCtx, session.ID)
		if err != nil || current.AgentTaskID == nil || *current.AgentTaskID != attempt.AgentTaskID {
			return err
		}
		now := time.Now().UTC()
		current.Phase, current.LastActiveAt = store.SessionPhaseIdle, &now
		_, err = s.store.Sessions().Upsert(lockCtx, current)
		if err != nil {
			return err
		}
		phase := store.SessionPhaseIdle
		if attempt.State == controlmodel.ExecutionFailed {
			phase = store.TurnStatusFailed
		}
		if attempt.State == controlmodel.ExecutionCancelled {
			phase = store.SessionPhaseTerminated
		}
		return s.store.Turns().SyncOnPhase(lockCtx, current.ID, phase)
	})
}

func (s *Server) projectHostedEndpointInvocation(ctx context.Context, attempt *controlmodel.ExecutionAttempt) error {
	task, err := s.store.Collaboration().GetAgentTask(ctx, attempt.AgentTaskID)
	if err != nil {
		return err
	}
	issue, err := s.store.Collaboration().GetIssue(ctx, task.IssueID)
	if err != nil {
		return err
	}
	if issue.SourceType != "endpoint_conversation" {
		return nil
	}
	invocationID, err := uuid.Parse(issue.SourceRef)
	if err != nil {
		return err
	}
	invocation, err := s.store.Endpoints().GetInvocation(ctx, invocationID)
	if err != nil {
		return err
	}
	if endpointInvocationTerminal(invocation.Status) {
		return nil
	}
	now := time.Now().UTC()
	invocation.CompletedAt = &now
	switch attempt.State {
	case controlmodel.ExecutionSucceeded:
		invocation.Status, invocation.Result = controlmodel.EndpointInvocationCompleted, attempt.Result
	case controlmodel.ExecutionFailed:
		invocation.Status, invocation.ErrorCode, invocation.ErrorMessage =
			controlmodel.EndpointInvocationFailed, attempt.FailureCode, attempt.FailureMessage
	case controlmodel.ExecutionCancelled:
		invocation.Status = controlmodel.EndpointInvocationCancelled
	}
	_, err = s.store.Endpoints().UpdateInvocation(ctx, invocation)
	return err
}

func hostedResultOutput(result json.RawMessage) string {
	var value struct {
		Output string `json:"output"`
	}
	if json.Unmarshal(result, &value) == nil && value.Output != "" {
		return value.Output
	}
	return strings.TrimSpace(string(result))
}

func mustJSON(value any) json.RawMessage {
	encoded, _ := json.Marshal(value)
	return encoded
}

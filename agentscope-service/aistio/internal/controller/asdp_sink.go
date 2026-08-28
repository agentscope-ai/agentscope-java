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

package controller

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/taskauth"
)

// ObservedSession is a neutral, transport-agnostic session snapshot reported by
// the data plane (via the HTTP prober or the ASDP gRPC stream).
type ObservedSession struct {
	ID                    string
	Phase                 string
	Busy                  *bool
	MessageCount          int32
	PromptTokens          int64
	CompletionTokens      int64
	ContextPressure       float64
	StartedAt             string
	LastActiveAt          string
	Framework             string
	FrameworkVersion      string
	ContextHash           string
	IsCompacted           bool
	EffectiveMessageCount int32
	InstanceRef           string
	InstanceIP            string
}

// ApplyExecutionAttemptReport projects a fenced external-runtime report into
// the shared Attempt/Task/Node/Run transaction boundary.
func (s *SessionEventSink) ApplyExecutionAttemptReport(ctx context.Context, tenant, namespace, agentIDRaw, bindingIDRaw, instanceKey string, instanceGeneration int64,
	attemptID, taskID, runID, nodeID uuid.UUID, generation int64, action string, inputIDs []uuid.UUID,
	content string, result, checkpoint, usage json.RawMessage, errorCode, errorMessage, attemptToken string) error {
	if s == nil || s.Store == nil {
		return fmt.Errorf("session event sink store is required")
	}
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return err
	}
	agentID, parseErr := uuid.Parse(agentIDRaw)
	bindingID, bindingParseErr := uuid.Parse(bindingIDRaw)
	if parseErr != nil || task.Tenant != tenant || task.Namespace != namespace || task.AgentRef != agentID.String() ||
		bindingParseErr != nil ||
		task.OrchestrationRunID != runID || task.RunNodeID != nodeID || task.CurrentAttemptID == nil || *task.CurrentAttemptID != attemptID {
		return store.ErrNotFound
	}
	attempt, err := s.Store.ExecutionAttempts().Get(ctx, attemptID)
	if err != nil || attempt.DispatchGeneration != generation || attempt.BackendKind != controlmodel.DataPlaneExternalApplication ||
		attempt.AgentID != agentID || attempt.BindingID != bindingID || attempt.AgentInstanceID == nil {
		return store.ErrNotFound
	}
	if s.AttemptTokens != nil {
		if err := s.AttemptTokens.VerifyAttempt(attemptToken, attempt.ID, generation,
			string(controlmodel.DataPlaneExternalApplication), attempt.AgentInstanceID.String(), time.Now()); err != nil {
			return store.ErrNotFound
		}
	}
	instances, err := s.Store.RuntimeRegistry().ListAgentInstances(ctx, tenant, namespace, agentID)
	if err != nil {
		return err
	}
	selected := false
	for _, instance := range instances {
		if instance.ID == *attempt.AgentInstanceID && instance.BindingID == bindingID && instance.InstanceKey == instanceKey &&
			instance.Generation == instanceGeneration {
			selected = true
			break
		}
	}
	if !selected {
		return store.ErrNotFound
	}
	service := &collaboration.Service{Store: s.Store}
	actor := controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
	switch action {
	case "ack":
		_, err = s.Store.Collaboration().AcknowledgeTaskInputs(ctx, task.ID, inputIDs)
	case "preparing":
		_, err = s.Store.ExecutionAttempts().Report(ctx, store.ExecutionAttemptReport{AttemptID: attempt.ID,
			AgentInstanceID: *attempt.AgentInstanceID, DispatchGeneration: generation,
			BackendKind: controlmodel.DataPlaneExternalApplication, State: controlmodel.ExecutionPreparing})
	case "start":
		_, err = s.Store.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
	case "heartbeat":
		_, err = s.Store.ExecutionAttempts().Report(ctx, store.ExecutionAttemptReport{AttemptID: attempt.ID,
			AgentInstanceID: *attempt.AgentInstanceID, DispatchGeneration: generation,
			BackendKind: controlmodel.DataPlaneExternalApplication, Checkpoint: checkpoint, Usage: usage})
	case "waiting":
		_, err = s.Store.ExecutionAttempts().Report(ctx, store.ExecutionAttemptReport{AttemptID: attempt.ID,
			AgentInstanceID: *attempt.AgentInstanceID, DispatchGeneration: generation,
			BackendKind: controlmodel.DataPlaneExternalApplication, State: controlmodel.ExecutionWaiting,
			Checkpoint: checkpoint, Usage: usage})
	case "progress", "respond":
		commentType := controlmodel.CommentResult
		if action == "progress" {
			commentType = controlmodel.CommentProgress
		}
		_, err = service.AddComment(ctx, collaboration.AddCommentRequest{IssueID: task.IssueID,
			Author: actor, Content: content, Type: commentType, SourceTaskID: &task.ID, SourceAttemptID: &attempt.ID})
	case "complete":
		_, _, err = service.CompleteTask(ctx, task.ID, store.TaskCompletion{
			ExpectedVersion: task.Version, AttemptID: attempt.ID, DispatchGeneration: generation,
			Result: result, Checkpoint: checkpoint, Usage: usage, Summary: content,
			ProcessedInputIDs: inputIDs,
		}, actor)
	case "fail":
		_, _, err = s.Store.Collaboration().FailAgentTaskWithAttempt(ctx, task.ID, store.TaskFailure{
			ExpectedVersion: task.Version, AttemptID: attempt.ID, DispatchGeneration: generation,
			Code: errorCode, Message: errorMessage, Checkpoint: checkpoint, Usage: usage})
	case "cancelled":
		_, err = s.Store.ExecutionAttempts().Report(ctx, store.ExecutionAttemptReport{AttemptID: attempt.ID,
			AgentInstanceID: *attempt.AgentInstanceID, DispatchGeneration: generation,
			BackendKind: controlmodel.DataPlaneExternalApplication, State: controlmodel.ExecutionCancelled})
	default:
		return fmt.Errorf("unsupported ExecutionAttempt action %q", action)
	}
	return err
}

// ObservedEvent is a neutral, transport-agnostic Level-2 session event
// reported by the data plane (via ASDP EventReport).
type ObservedEvent struct {
	SessionID     string
	Seq           int32
	EventType     string
	OccurredAt    time.Time
	Role          string
	Content       string
	ToolName      string
	ToolInput     json.RawMessage
	ToolOutput    string
	TokensIn      int32
	TokensOut     int32
	DurationMs    int32
	FrameworkMeta json.RawMessage
}

// ObservedContext is a neutral, transport-agnostic Level-4 effective-context
// report (via ASDP ContextReport or the HTTP contract /context endpoint).
type ObservedContext struct {
	SessionID            string
	ContextHash          string
	CapturedAt           time.Time
	SystemPrompt         string
	Messages             json.RawMessage
	Tools                json.RawMessage
	IsCompacted          bool
	CompactionSummary    string
	OriginalMessageCount int32
	CompactedAt          *time.Time
	TotalTokens          int32
	MaxTokens            int32
	Framework            string
	FrameworkState       json.RawMessage
}

// ObservedSubagent mirrors the ASDP SubagentInfo inventory entry.
type ObservedSubagent struct {
	Name          string
	Description   string
	Tools         []string
	WorkspaceMode string
	URL           string
	InvokeCount   int64
	LastInvokedAt *time.Time
}

// ObservedWorkspace mirrors the ASDP WorkspaceInfo inventory entry.
type ObservedWorkspace struct {
	Path      string
	Mode      string
	SizeBytes int64
	OwnerRef  string
}

// ObservedInventory is a neutral, transport-agnostic instance inventory report.
type ObservedInventory struct {
	Subagents      []ObservedSubagent
	Workspaces     []ObservedWorkspace
	Healthy        bool
	HealthReason   string
	ActiveSessions int32
}

// SessionEventSink applies data-plane session reports to the runtime Store.
type SessionEventSink struct {
	Client        client.Client
	Store         store.Store
	AttemptTokens *taskauth.Manager
}

// ApplyInstanceConnect observes an already registered ASDP application. ASDP
// never creates or claims a logical Agent; registration credentials do that.
func (s *SessionEventSink) ApplyInstanceConnect(ctx context.Context, tenant, namespace, agentIDRaw, bindingIDRaw, agentKey, instanceKey string, generation int64, runtimeName, sdkVersion string, capabilities []string) {
	if s == nil || s.Store == nil || agentIDRaw == "" || bindingIDRaw == "" || instanceKey == "" || generation <= 0 {
		return
	}
	if tenant == "" {
		tenant = "default"
	}
	if namespace == "" {
		namespace = "default"
	}
	agentID, err := uuid.Parse(agentIDRaw)
	if err != nil {
		return
	}
	bindingID, err := uuid.Parse(bindingIDRaw)
	if err != nil {
		return
	}
	agent, err := s.Store.AgentCatalog().GetAgent(ctx, agentID)
	if err != nil || agent.Status != controlmodel.AgentActive || agent.AgentKey != agentKey ||
		agent.Tenant != tenant || agent.Namespace != namespace {
		return
	}
	encoded, _ := json.Marshal(capabilities)
	instances, err := s.Store.RuntimeRegistry().ListAgentInstances(ctx, tenant, namespace, agentID)
	if err != nil {
		return
	}
	var instance *controlmodel.AgentInstance
	for _, candidate := range instances {
		if candidate.BindingID == bindingID && candidate.InstanceKey == instanceKey && candidate.Generation == generation {
			instance = candidate
			break
		}
	}
	if instance == nil {
		return
	}
	_, err = s.Store.RuntimeRegistry().HeartbeatAgentInstance(ctx, instance.ID, generation, instance.ActiveSessions, encoded)
	if err != nil {
		log.FromContext(ctx).Error(err, "failed to update registered ASDP application", "instance", instanceKey,
			"runtime", runtimeName, "sdkVersion", sdkVersion)
	}
}

func (s *SessionEventSink) ApplyInstanceDisconnect(ctx context.Context, tenant, namespace, agentIDRaw, bindingIDRaw, instanceKey string, generation int64) {
	if s == nil || s.Store == nil {
		return
	}
	if tenant == "" {
		tenant = "default"
	}
	agentID, err := uuid.Parse(agentIDRaw)
	if err != nil {
		return
	}
	bindingID, err := uuid.Parse(bindingIDRaw)
	if err != nil {
		return
	}
	instances, err := s.Store.RuntimeRegistry().ListAgentInstances(ctx, tenant, namespace, agentID)
	if err != nil {
		return
	}
	for _, instance := range instances {
		if instance.BindingID == bindingID && instance.InstanceKey == instanceKey && instance.Generation == generation {
			_, _ = s.Store.RuntimeRegistry().SetAgentInstanceHealth(ctx, instance.ID, generation, controlmodel.RuntimeHealthUnhealthy)
			return
		}
	}
}

// ApplySessionReport upserts each reported session into the Store.
func (s *SessionEventSink) ApplySessionReport(ctx context.Context, tenant, namespace, agentName, instanceID string, sessions []ObservedSession) {
	logger := log.FromContext(ctx).WithName("asdp-session-sink")
	logger = logger.WithValues("tenant", tenant)

	var agent v1alpha1.Agent
	if s.Client != nil {
		if err := s.Client.Get(ctx, types.NamespacedName{Name: agentName, Namespace: namespace}, &agent); err != nil {
			logger.V(1).Info("agent definition not found; accepting standalone application session report",
				"agent", agentName, "namespace", namespace, "error", err.Error())
		}
	}
	// Application ASDP is independent of Kubernetes. A self-registered
	// application can report sessions before an Agent definition is projected.
	agent.Name = agentName
	agent.Namespace = namespace

	for i := range sessions {
		o := sessions[i]
		if o.Framework == "" {
			o.Framework = agent.Spec.Runtime
		}
		if o.InstanceRef == "" {
			o.InstanceRef = instanceID
		}
		if _, err := upsertObservedSession(ctx, s.Store, tenant, &agent, o); err != nil {
			logger.Error(err, "failed to upsert reported session", "sessionID", o.ID)
			continue
		}
	}
}

// ApplyEventReport appends a batch of Level-2 events to the Store.
// Duplicate (session, seq) appends are treated as idempotent success.
func (s *SessionEventSink) ApplyEventReport(ctx context.Context, tenant, namespace, agentName, instanceID string, events []ObservedEvent) {
	logger := log.FromContext(ctx).WithName("asdp-event-sink")
	logger = logger.WithValues("tenant", tenant)
	if s.Store == nil || len(events) == 0 {
		return
	}

	// Group by session so each session FK is resolved once per batch.
	fks := map[string]uuid.UUID{} // sessionID -> session FK
	failed := map[string]bool{}
	for i := range events {
		e := events[i]
		if e.SessionID == "" || failed[e.SessionID] {
			continue
		}
		fk, ok := fks[e.SessionID]
		if !ok {
			resolved, err := s.resolveSessionFK(ctx, tenant, namespace, agentName, instanceID, e.SessionID)
			if err != nil {
				logger.Error(err, "failed to resolve session for events", "sessionID", e.SessionID)
				failed[e.SessionID] = true
				continue
			}
			fk = resolved
			fks[e.SessionID] = fk
		}
		occurredAt := e.OccurredAt
		if occurredAt.IsZero() {
			occurredAt = time.Now().UTC()
		}
		err := s.Store.Events().Append(ctx, &store.SessionEvent{
			SessionFK:     fk,
			Seq:           int(e.Seq),
			EventType:     e.EventType,
			Role:          e.Role,
			Content:       e.Content,
			ToolName:      e.ToolName,
			ToolInput:     e.ToolInput,
			ToolOutput:    e.ToolOutput,
			TokensIn:      int(e.TokensIn),
			TokensOut:     int(e.TokensOut),
			DurationMs:    int(e.DurationMs),
			FrameworkMeta: e.FrameworkMeta,
			OccurredAt:    occurredAt,
		})
		switch {
		case err == nil:
		case errors.Is(err, store.ErrConflict):
			// duplicate (session, seq) — idempotent success
		default:
			logger.Error(err, "failed to append session event", "sessionID", e.SessionID, "seq", e.Seq)
		}
	}
}

// ApplyContextReport writes a Level-4 effective-context snapshot to the Store.
// Snapshots with an unchanged context_hash are skipped by the Store.
func (s *SessionEventSink) ApplyContextReport(ctx context.Context, tenant, namespace, agentName, instanceID string, oc ObservedContext) {
	logger := log.FromContext(ctx).WithName("asdp-context-sink")
	logger = logger.WithValues("tenant", tenant)
	if s.Store == nil || oc.SessionID == "" {
		return
	}

	fk, err := s.resolveSessionFK(ctx, tenant, namespace, agentName, instanceID, oc.SessionID)
	if err != nil {
		logger.Error(err, "failed to resolve session for context report", "sessionID", oc.SessionID)
		return
	}
	capturedAt := oc.CapturedAt
	if capturedAt.IsZero() {
		capturedAt = time.Now().UTC()
	}
	messages := oc.Messages
	if len(messages) == 0 {
		messages = json.RawMessage("[]")
	}
	inserted, err := s.Store.ContextSnapshots().PutIfChanged(ctx, &store.ContextSnapshot{
		SessionFK:            fk,
		CapturedAt:           capturedAt,
		ContextHash:          oc.ContextHash,
		SystemPrompt:         oc.SystemPrompt,
		Messages:             messages,
		Tools:                oc.Tools,
		IsCompacted:          oc.IsCompacted,
		CompactionSummary:    oc.CompactionSummary,
		OriginalMessageCount: int(oc.OriginalMessageCount),
		CompactedAt:          oc.CompactedAt,
		TotalTokens:          int(oc.TotalTokens),
		MaxTokens:            int(oc.MaxTokens),
		Framework:            oc.Framework,
		FrameworkState:       oc.FrameworkState,
	})
	if err != nil {
		logger.Error(err, "failed to store context snapshot", "sessionID", oc.SessionID)
		return
	}
	if inserted {
		logger.V(1).Info("stored context snapshot", "sessionID", oc.SessionID, "contextHash", oc.ContextHash)
	}
}

// ApplyInventoryReport processes an instance inventory report. The transport
// registry (asdp.Server) retains the latest report for queries; here we log
// and record the reported active session count as an agent metric.
func (s *SessionEventSink) ApplyInventoryReport(ctx context.Context, tenant, namespace, agentName, instanceID string, inv ObservedInventory) {
	logger := log.FromContext(ctx).WithName("asdp-inventory-sink")
	logger.V(1).Info("inventory report",
		"tenant", tenant, "agent", agentName, "instance", instanceID,
		"subagents", len(inv.Subagents), "workspaces", len(inv.Workspaces),
		"healthy", inv.Healthy, "activeSessions", inv.ActiveSessions)
	if s.Store == nil {
		return
	}
	if err := s.Store.Metrics().RecordAgentMetric(ctx, &store.AgentMetric{
		Tenant:         tenant,
		AgentName:      agentName,
		Namespace:      namespace,
		ActiveSessions: inv.ActiveSessions,
	}); err != nil {
		logger.Error(err, "failed to record agent metric from inventory")
	}
}

// resolveSessionFK maps a framework-reported session ID to the store primary
// key, creating a minimal session row when the session is not known yet
// (events/context may arrive before the first Level-1 snapshot).
func (s *SessionEventSink) resolveSessionFK(ctx context.Context, tenant, namespace, agentName, instanceID, sessionID string) (uuid.UUID, error) {
	sess, err := s.Store.Sessions().Get(ctx, tenant, agentName, namespace, sessionID)
	if err == nil {
		return sess.ID, nil
	}
	if !errors.Is(err, store.ErrNotFound) {
		return uuid.Nil, err
	}
	saved, err := s.Store.Sessions().Upsert(ctx, &store.Session{
		Tenant:      tenant,
		SessionID:   sessionID,
		AgentName:   agentName,
		Namespace:   namespace,
		Phase:       store.SessionPhaseActive,
		InstanceRef: instanceID,
	})
	if err != nil {
		return uuid.Nil, fmt.Errorf("creating placeholder session %s: %w", sessionID, err)
	}
	return saved.ID, nil
}

// upsertObservedSession writes a session + Level-1 snapshot (+ optional token metric)
// into the Store. Shared by SessionPoller (HTTP pull) and ASDP gRPC sink (push).
// It returns the saved session so callers can chain context/event writes.
func upsertObservedSession(ctx context.Context, st store.Store, tenant string, agent *v1alpha1.Agent, o ObservedSession) (*store.Session, error) {
	if st == nil {
		return nil, fmt.Errorf("store is nil")
	}
	phase := normalizePhase(o.Phase)
	framework := o.Framework
	if framework == "" {
		framework = agent.Spec.Runtime
	}

	sess := &store.Session{
		Tenant:           tenant,
		SessionID:        o.ID,
		AgentName:        agent.Name,
		Namespace:        agent.Namespace,
		Framework:        framework,
		FrameworkVersion: o.FrameworkVersion,
		Phase:            phase,
		Busy:             resolveObservedBusy(o.Busy, phase),
		InstanceRef:      o.InstanceRef,
		InstanceIP:       o.InstanceIP,
		StartedAt:        parseTimePtr(o.StartedAt),
		LastActiveAt:     parseTimePtr(o.LastActiveAt),
	}
	saved, err := st.Sessions().Upsert(ctx, sess)
	if err != nil {
		return nil, fmt.Errorf("upserting session %s: %w", o.ID, err)
	}
	if err := st.Turns().SyncOnPhase(ctx, saved.ID, phase); err != nil {
		return nil, fmt.Errorf("syncing turn for session %s: %w", o.ID, err)
	}

	snap := &store.SessionSnapshot{
		SessionFK:             saved.ID,
		MessageCount:          o.MessageCount,
		PromptTokens:          o.PromptTokens,
		CompletionTokens:      o.CompletionTokens,
		TotalTokens:           o.PromptTokens + o.CompletionTokens,
		ContextPressure:       o.ContextPressure,
		IsCompacted:           o.IsCompacted,
		EffectiveMessageCount: o.EffectiveMessageCount,
		ContextHash:           o.ContextHash,
	}
	prevSnap, _ := st.Metrics().LatestSnapshot(ctx, saved.ID)
	dPrompt, dCompletion := store.TokenUsageDelta(prevSnap, o.PromptTokens, o.CompletionTokens)
	if err := st.Metrics().RecordSnapshot(ctx, snap); err != nil {
		return nil, fmt.Errorf("recording snapshot for session %s: %w", o.ID, err)
	}
	// Narrow transcript index: absolute DP snapshot aggregates (not event recomputation).
	_ = store.UpsertTranscriptIndexFromSnapshot(ctx, st, saved.ID, o.MessageCount, o.PromptTokens, o.CompletionTokens)

	if dPrompt > 0 || dCompletion > 0 {
		fk := saved.ID
		if err := st.Metrics().RecordTokenUsage(ctx, &store.TokenUsageMetric{
			Tenant:           tenant,
			SessionFK:        &fk,
			AgentName:        agent.Name,
			Namespace:        agent.Namespace,
			PromptTokens:     dPrompt,
			CompletionTokens: dCompletion,
			TotalTokens:      dPrompt + dCompletion,
		}); err != nil {
			return nil, fmt.Errorf("recording token usage for session %s: %w", o.ID, err)
		}
	}
	return saved, nil
}

func normalizePhase(p string) string {
	switch p {
	case "", "Active", "active":
		return store.SessionPhaseActive
	case "Idle", "idle":
		return store.SessionPhaseIdle
	case "Compressing", "compressing":
		return store.SessionPhaseCompressing
	case "Archived", "archived":
		return store.SessionPhaseArchived
	case "Terminated", "terminated":
		return store.SessionPhaseTerminated
	default:
		return strings.ToLower(p)
	}
}

// resolveObservedBusy: DP-reported busy wins; otherwise derive from phase;
// empty phase → unknown (nil).
func resolveObservedBusy(reported *bool, phase string) *bool {
	if reported != nil {
		return reported
	}
	if phase == "" {
		return nil
	}
	b := phase == store.SessionPhaseActive
	return &b
}

func parseTimePtr(s string) *time.Time {
	if s == "" {
		return nil
	}
	for _, layout := range []string{time.RFC3339Nano, time.RFC3339} {
		if t, err := time.Parse(layout, s); err == nil {
			u := t.UTC()
			return &u
		}
	}
	return nil
}

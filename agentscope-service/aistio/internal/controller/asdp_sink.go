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
	"github.com/spring-ai-alibaba/aistio/internal/store"
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
	TeamID                string
	TeamRole              string
	ContextHash           string
	IsCompacted           bool
	EffectiveMessageCount int32
	InstanceRef           string
	InstanceIP            string
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
	Client client.Client
	Store  store.Store
}

// ApplySessionReport upserts each reported session into the Store.
func (s *SessionEventSink) ApplySessionReport(ctx context.Context, namespace, agentName, instanceID string, sessions []ObservedSession) {
	logger := log.FromContext(ctx).WithName("asdp-session-sink")

	var agent v1alpha1.Agent
	if err := s.Client.Get(ctx, types.NamespacedName{Name: agentName, Namespace: namespace}, &agent); err != nil {
		logger.V(1).Info("agent not found for session report; skipping",
			"agent", agentName, "namespace", namespace, "error", err.Error())
		return
	}

	for i := range sessions {
		o := sessions[i]
		if o.Framework == "" {
			o.Framework = agent.Spec.Runtime
		}
		if o.InstanceRef == "" {
			o.InstanceRef = instanceID
		}
		if _, err := upsertObservedSession(ctx, s.Store, &agent, o); err != nil {
			logger.Error(err, "failed to upsert reported session", "sessionID", o.ID)
		}
	}
}

// ApplyEventReport appends a batch of Level-2 events to the Store.
// Duplicate (session, seq) appends are treated as idempotent success.
func (s *SessionEventSink) ApplyEventReport(ctx context.Context, namespace, agentName, instanceID string, events []ObservedEvent) {
	logger := log.FromContext(ctx).WithName("asdp-event-sink")
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
			resolved, err := s.resolveSessionFK(ctx, namespace, agentName, instanceID, e.SessionID)
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
func (s *SessionEventSink) ApplyContextReport(ctx context.Context, namespace, agentName, instanceID string, oc ObservedContext) {
	logger := log.FromContext(ctx).WithName("asdp-context-sink")
	if s.Store == nil || oc.SessionID == "" {
		return
	}

	fk, err := s.resolveSessionFK(ctx, namespace, agentName, instanceID, oc.SessionID)
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
func (s *SessionEventSink) ApplyInventoryReport(ctx context.Context, namespace, agentName, instanceID string, inv ObservedInventory) {
	logger := log.FromContext(ctx).WithName("asdp-inventory-sink")
	logger.V(1).Info("inventory report",
		"agent", agentName, "instance", instanceID,
		"subagents", len(inv.Subagents), "workspaces", len(inv.Workspaces),
		"healthy", inv.Healthy, "activeSessions", inv.ActiveSessions)
	if s.Store == nil {
		return
	}
	if err := s.Store.Metrics().RecordAgentMetric(ctx, &store.AgentMetric{
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
func (s *SessionEventSink) resolveSessionFK(ctx context.Context, namespace, agentName, instanceID, sessionID string) (uuid.UUID, error) {
	sess, err := s.Store.Sessions().Get(ctx, agentName, namespace, sessionID)
	if err == nil {
		return sess.ID, nil
	}
	if !errors.Is(err, store.ErrNotFound) {
		return uuid.Nil, err
	}
	saved, err := s.Store.Sessions().Upsert(ctx, &store.Session{
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
func upsertObservedSession(ctx context.Context, st store.Store, agent *v1alpha1.Agent, o ObservedSession) (*store.Session, error) {
	if st == nil {
		return nil, fmt.Errorf("store is nil")
	}
	phase := normalizePhase(o.Phase)
	framework := o.Framework
	if framework == "" {
		framework = agent.Spec.Runtime
	}

	sess := &store.Session{
		SessionID:        o.ID,
		AgentName:        agent.Name,
		Namespace:        agent.Namespace,
		Framework:        framework,
		FrameworkVersion: o.FrameworkVersion,
		Phase:            phase,
		Busy:             resolveObservedBusy(o.Busy, phase),
		InstanceRef:      o.InstanceRef,
		InstanceIP:       o.InstanceIP,
		TeamID:           o.TeamID,
		TeamRole:         o.TeamRole,
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

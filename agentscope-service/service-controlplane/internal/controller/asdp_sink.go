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
	"sort"
	"strings"
	"time"

	"github.com/google/uuid"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/api/v1alpha1"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/collaboration"
	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/conversation"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/taskauth"
)

// ObservedSession is a neutral, transport-agnostic session snapshot reported by
// the data plane (via the HTTP prober or the ASDP gRPC stream).
type ObservedSession struct {
	TokenUsageReported      bool
	ContextPressureReported bool
	ID                      string
	Phase                   string
	Busy                    *bool
	MessageCount            int32
	PromptTokens            int64
	CompletionTokens        int64
	ContextPressure         float64
	StartedAt               string
	LastActiveAt            string
	Framework               string
	FrameworkVersion        string
	ContextHash             string
	IsCompacted             bool
	EffectiveMessageCount   int32
	InstanceRef             string
	InstanceIP              string
}

type ObservedConversationTurn struct {
	InvocationID   string
	ConversationID string
	TurnID         string
	SessionID      string
	Generation     int64
	Action         string
	Sequence       int64
	Payload        json.RawMessage
	ErrorCode      string
	ErrorMessage   string
}

// ApplyConversationTurnReport applies a report only when the authenticated
// connection still owns the frozen Endpoint conversation identity.
func (s *SessionEventSink) ApplyConversationTurnReport(ctx context.Context, identity RuntimeReportIdentity, report ObservedConversationTurn) error {
	resolved, err := s.resolveRuntimeReportIdentity(ctx, identity)
	if err != nil {
		return err
	}
	invocationID, err := uuid.Parse(report.InvocationID)
	if err != nil {
		return store.ErrNotFound
	}
	conversationID, err := uuid.Parse(report.ConversationID)
	if err != nil {
		return store.ErrNotFound
	}
	turnID, err := uuid.Parse(report.TurnID)
	if err != nil {
		return store.ErrNotFound
	}
	return s.Store.WithSessionLock(ctx, "service-invocation:"+invocationID.String(), func(ctx context.Context) error {
		inv, err := s.Store.Endpoints().GetInvocation(ctx, invocationID)
		if errors.Is(err, store.ErrNotFound) {
			session, err := s.Store.Sessions().GetByID(ctx, conversationID)
			if err != nil {
				return err
			}
			if session.Tenant != identity.Tenant || session.Namespace != identity.Namespace || session.SessionID != report.SessionID || session.AgentID != resolved.AgentUUID || session.BindingID != resolved.BindingUUID || session.AgentInstanceID != resolved.AgentInstanceUUID || session.InstanceGeneration != identity.InstanceGeneration || report.Generation != identity.InstanceGeneration {
				return store.ErrNotFound
			}
			return conversation.Apply(ctx, s.Store, session, conversation.Report{InvocationID: invocationID, ConversationID: conversationID, TurnID: turnID, AgentID: resolved.AgentUUID, BindingID: resolved.BindingUUID, InstanceID: resolved.AgentInstanceUUID, Generation: report.Generation, Action: report.Action, Sequence: report.Sequence, Payload: report.Payload, ErrorCode: report.ErrorCode, ErrorMessage: report.ErrorMessage}, time.Now().UTC())
		}
		if err != nil {
			return err
		}
		if inv.Mode != controlmodel.EndpointConversationMode || inv.ConversationID == nil || *inv.ConversationID != conversationID || inv.TurnID == nil || *inv.TurnID != turnID || inv.SessionID != report.SessionID {
			return store.ErrNotFound
		}
		conv, err := s.Store.Endpoints().GetConversation(ctx, conversationID)
		if err != nil {
			return err
		}
		if conv.EndpointID != inv.EndpointID || conv.AgentID != resolved.AgentUUID || conv.BindingID != resolved.BindingUUID || conv.AgentInstanceID != resolved.AgentInstanceUUID || conv.InstanceGeneration != identity.InstanceGeneration || report.Generation != identity.InstanceGeneration || conv.SessionID != report.SessionID {
			return store.ErrNotFound
		}
		// Terminal state was published only after its events committed. Delayed starts and replayed
		// terminal reports are acknowledgements, never a transition back to running.
		if serviceapi.Terminal(inv.Status) {
			return nil
		}
		now := time.Now().UTC()
		old := inv.Status
		switch report.Action {
		case "accepted", "started":
			inv.Status = controlmodel.EndpointInvocationRunning
			if inv.StartedAt == nil {
				inv.StartedAt = &now
			}
		case "delta":
			if report.Sequence <= 0 {
				return fmt.Errorf("%w: delta sequence must be positive", store.ErrForbidden)
			}
			if inv.Status == controlmodel.EndpointInvocationAccepted || inv.Status == controlmodel.EndpointInvocationDispatching {
				inv.Status = controlmodel.EndpointInvocationRunning
			}
		case "completed":
			ep, err := serviceapi.BoundEndpoint(ctx, s.Store, inv)
			if err != nil {
				return err
			}
			inv.Status, inv.CompletedAt = controlmodel.EndpointInvocationCompleted, &now
			serviceapi.CompleteResult(ep, inv, report.Payload)
		case "failed":
			inv.Status, inv.ErrorCode, inv.ErrorMessage, inv.CompletedAt = controlmodel.EndpointInvocationFailed, report.ErrorCode, report.ErrorMessage, &now
		case "cancelled":
			inv.Status, inv.CompletedAt = controlmodel.EndpointInvocationCancelled, &now
		default:
			return fmt.Errorf("%w: unsupported ConversationTurn action %q", store.ErrForbidden, report.Action)
		}
		if old == controlmodel.EndpointInvocationCancelRequested && !serviceapi.Terminal(inv.Status) {
			inv.Status = old
		}
		if serviceapi.Terminal(inv.Status) && inv.ErrorCode == "endpoint_timeout" {
			inv.Status = controlmodel.EndpointInvocationTimedOut
		}
		if err = s.appendEndpointConversationReport(ctx, identity.Tenant, resolved.AgentUUID, inv, turnID, report); err != nil {
			return err
		}
		conv.LastTurnAt = &now
		if _, err = s.Store.Endpoints().UpdateConversation(ctx, conv); err != nil {
			return err
		}
		if _, err = s.Store.Endpoints().UpdateInvocation(ctx, inv); err != nil {
			return err
		}
		return s.Store.Endpoints().ScheduleInvocation(ctx, inv.ID, now)
	})
}

// Lifecycle reports use the invocation journal directly. Observer event sequences remain
// exclusively owned by the runtime; no second writer allocates session_events sequence numbers.
func (s *SessionEventSink) appendEndpointConversationReport(ctx context.Context, tenant string, agentID uuid.UUID, inv *controlmodel.EndpointInvocation, turnID uuid.UUID, report ObservedConversationTurn) error {
	journal := serviceapi.Journal{Store: s.Store, Tenant: tenant, InvocationID: inv.ID}
	accepted := serviceapi.View(inv)
	accepted["status"] = "accepted"
	delete(accepted, "result")
	delete(accepted, "error")
	if _, err := journal.Append(ctx, "admission:"+inv.ID.String(), "invocation.accepted", accepted); err != nil {
		return err
	}
	source := fmt.Sprintf("conversation:%s:%s:%d", turnID, report.Action, report.Sequence)
	data := map[string]any{"execution_id": inv.ID.String(), "agent_id": agentID.String()}
	text := ""
	if json.Unmarshal(report.Payload, &text) != nil {
		var payload struct {
			Content string `json:"content"`
		}
		_ = json.Unmarshal(report.Payload, &payload)
		text = payload.Content
	}
	itemID := inv.ID.String() + ":response"
	content := []any{map[string]any{"type": "text", "text": text}}
	appendEvent := func(suffix, kind string, value map[string]any) error {
		_, err := journal.Append(ctx, source+suffix, kind, value)
		return err
	}
	switch report.Action {
	case "accepted", "started":
		return appendEvent("", "execution.started", data)
	case "delta":
		data["item_id"], data["content"] = itemID, content
		return appendEvent("", "item.delta", data)
	case "completed":
		if text != "" {
			item := map[string]any{"execution_id": inv.ID.String(), "agent_id": agentID.String(), "item_id": itemID, "item": map[string]any{"id": itemID, "type": "message", "role": "assistant", "content": content}}
			if err := appendEvent(":item", "item.completed", item); err != nil {
				return err
			}
		}
	}
	data["status"] = inv.Status
	if inv.ErrorCode != "" {
		data["error"] = map[string]any{"code": inv.ErrorCode, "message": inv.ErrorMessage}
	}
	return appendEvent("", "execution.ended", data)
}

// ApplyExecutionAttemptReport projects a fenced external-runtime report into
// the shared Attempt/Task/Node/Run transaction boundary.
func (s *SessionEventSink) ApplyExecutionAttemptReport(ctx context.Context, tenant, namespace, agentIDRaw, bindingIDRaw, instanceKey string, instanceGeneration int64,
	attemptID, taskID, runID, nodeID uuid.UUID, generation int64, action string, inputIDs []uuid.UUID,
	content string, result, checkpoint, usage json.RawMessage, errorCode, errorMessage, attemptToken, idempotencyKey string) error {
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
	if err != nil {
		return err
	}
	if attempt.DispatchGeneration != generation || attempt.BackendKind != controlmodel.DataPlaneExternalApplication ||
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
	if controlmodel.IsExecutionAttemptTerminal(attempt.State) {
		return nil
	}
	if (action == "start" || action == "preparing") && (attempt.State == controlmodel.ExecutionRunning || attempt.State == controlmodel.ExecutionWaiting || attempt.State == controlmodel.ExecutionCancelRequested) {
		return nil
	}
	if action == "waiting" && attempt.State == controlmodel.ExecutionCancelRequested {
		return nil
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
		if strings.TrimSpace(idempotencyKey) == "" {
			return fmt.Errorf("%w: progress/respond requires idempotency_key", store.ErrForbidden)
		}
		commentID := uuid.NewSHA1(attempt.ID, []byte(action+":"+idempotencyKey))
		if previous, err := s.Store.Collaboration().GetComment(ctx, commentID); err == nil {
			if previous.Content != strings.TrimSpace(content) {
				return fmt.Errorf("%w: idempotency_key was reused with different content", store.ErrForbidden)
			}
			return nil
		} else if !errors.Is(err, store.ErrNotFound) {
			return err
		}
		commentType := controlmodel.CommentResult
		if action == "progress" {
			commentType = controlmodel.CommentProgress
		}
		_, err = service.AddComment(ctx, collaboration.AddCommentRequest{ID: commentID, IssueID: task.IssueID,
			Author: actor, Content: content, Type: commentType, SourceTaskID: &task.ID, SourceAttemptID: &attempt.ID,
			SuppressImplicitRouting: action == "progress"})
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
		return fmt.Errorf("%w: unsupported ExecutionAttempt action %q", store.ErrForbidden, action)
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

// RuntimeReportIdentity is copied from the authenticated ASDP stream metadata.
// It is resolved against the Catalog before any runtime report is persisted.
type RuntimeReportIdentity struct {
	Tenant             string
	Namespace          string
	AgentID            string
	BindingID          string
	AgentKey           string
	InstanceKey        string
	InstanceGeneration int64
}

type resolvedRuntimeReportIdentity struct {
	RuntimeReportIdentity
	AgentUUID         uuid.UUID
	BindingUUID       uuid.UUID
	AgentInstanceUUID uuid.UUID
}

func (s *SessionEventSink) resolveRuntimeReportIdentity(ctx context.Context, identity RuntimeReportIdentity) (*resolvedRuntimeReportIdentity, error) {
	if s == nil || s.Store == nil {
		return nil, fmt.Errorf("runtime Store is unavailable")
	}
	agentID, err := uuid.Parse(identity.AgentID)
	if err != nil {
		return nil, fmt.Errorf("%w: invalid agentId", store.ErrForbidden)
	}
	bindingID, err := uuid.Parse(identity.BindingID)
	if err != nil {
		return nil, fmt.Errorf("%w: invalid bindingId", store.ErrForbidden)
	}
	agent, err := s.Store.AgentCatalog().GetAgent(ctx, agentID)
	if err != nil {
		return nil, err
	}
	if agent.Status != controlmodel.AgentActive || agent.Tenant != identity.Tenant ||
		agent.Namespace != identity.Namespace || agent.AgentKey != identity.AgentKey {
		return nil, fmt.Errorf("%w: runtime report Agent identity does not match the Catalog", store.ErrForbidden)
	}
	binding, err := s.Store.AgentCatalog().GetBinding(ctx, bindingID)
	if err != nil {
		return nil, err
	}
	if binding.AgentID != agentID || !binding.Enabled || binding.ArchivedAt != nil {
		return nil, fmt.Errorf("%w: runtime report Binding is disabled or does not match the Agent", store.ErrForbidden)
	}
	instances, err := s.Store.RuntimeRegistry().ListAgentInstances(ctx, identity.Tenant, identity.Namespace, agentID)
	if err != nil {
		return nil, err
	}
	for _, instance := range instances {
		if instance.BindingID == bindingID && instance.InstanceKey == identity.InstanceKey &&
			instance.Generation == identity.InstanceGeneration {
			return &resolvedRuntimeReportIdentity{RuntimeReportIdentity: identity, AgentUUID: agentID,
				BindingUUID: bindingID, AgentInstanceUUID: instance.ID}, nil
		}
	}
	return nil, fmt.Errorf("%w: runtime report instance or generation does not match the Catalog", store.ErrForbidden)
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
	encoded := controlmodel.CapabilityFlags(capabilities)
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
func (s *SessionEventSink) ApplySessionReport(ctx context.Context, identity RuntimeReportIdentity, sessions []ObservedSession) {
	logger := log.FromContext(ctx).WithName("asdp-session-sink")
	logger = logger.WithValues("tenant", identity.Tenant, "agentId", identity.AgentID)
	resolved, err := s.resolveRuntimeReportIdentity(ctx, identity)
	if err != nil {
		logger.Error(err, "rejected runtime session report")
		return
	}

	var agent v1alpha1.Agent
	if s.Client != nil {
		if err := s.Client.Get(ctx, types.NamespacedName{Name: identity.AgentKey, Namespace: identity.Namespace}, &agent); err != nil {
			logger.V(1).Info("agent definition not found; accepting standalone application session report",
				"agent", identity.AgentKey, "namespace", identity.Namespace, "error", err.Error())
		}
	}
	// Application ASDP is independent of Kubernetes. A self-registered
	// application can report sessions before an Agent definition is projected.
	agent.Name = identity.AgentKey
	agent.Namespace = identity.Namespace

	for i := range sessions {
		o := sessions[i]
		if o.Framework == "" {
			o.Framework = agent.Spec.Runtime
		}
		if o.InstanceRef == "" {
			o.InstanceRef = identity.InstanceKey
		}
		if _, err := upsertObservedSession(ctx, s.Store, identity.Tenant, &agent, o, resolved); err != nil {
			logger.Error(err, "failed to upsert reported session", "sessionID", o.ID)
			continue
		}
	}
}

// ApplyEventReport appends a batch of Level-2 events to the Store.
// Duplicate (session, seq) appends are treated as idempotent success.
func (s *SessionEventSink) ApplyEventReport(ctx context.Context, identity RuntimeReportIdentity, events []ObservedEvent) (map[string]int32, error) {
	logger := log.FromContext(ctx).WithName("asdp-event-sink")
	logger = logger.WithValues("tenant", identity.Tenant, "agentId", identity.AgentID)
	committed := map[string]int32{}
	if s.Store == nil || len(events) == 0 {
		return committed, nil
	}
	resolvedIdentity, err := s.resolveRuntimeReportIdentity(ctx, identity)
	if err != nil {
		logger.Error(err, "rejected runtime event report")
		return committed, err
	}

	// Process each session in sequence order and stop at the first gap/failure.
	// This makes every returned watermark a contiguous durable prefix.
	grouped := map[string][]ObservedEvent{}
	for _, event := range events {
		if event.SessionID != "" && event.Seq > 0 {
			grouped[event.SessionID] = append(grouped[event.SessionID], event)
		}
	}
	failed := map[string]bool{}
	for sessionID, sessionEvents := range grouped {
		sort.SliceStable(sessionEvents, func(i, j int) bool { return sessionEvents[i].Seq < sessionEvents[j].Seq })
		fk, err := s.resolveSessionFK(ctx, resolvedIdentity, sessionID)
		if err != nil {
			logger.Error(err, "failed to resolve session for events", "sessionID", sessionID)
			failed[sessionID] = true
			continue
		}
		err = s.Store.WithSessionLock(ctx, "service-session-events:"+fk.String(), func(ctx context.Context) error {
			latest := int32(0)
			stored, err := s.listSessionReportEvents(ctx, fk)
			if err != nil {
				logger.Error(err, "failed to read session event watermark", "sessionID", sessionID)
				return err
			}
			// Derive the largest contiguous prefix, not merely MAX(seq). Older
			// writers may have left a hole; a replay that fills it must not be
			// mistaken for an already committed duplicate.
			storedSeqs := make(map[int32]struct{}, len(stored))
			for _, persisted := range stored {
				if persisted.Seq > 0 {
					storedSeqs[int32(persisted.Seq)] = struct{}{}
				}
			}
			for {
				if _, ok := storedSeqs[latest+1]; !ok {
					break
				}
				latest++
			}
			for _, event := range sessionEvents {
				if event.Seq <= latest {
					// Re-run the idempotent diagnostic projection on replay. This heals
					// the case where the session event was committed but its RunEvent
					// projection briefly failed.
					if toolFailure, ok := observedToolFailure(event); ok {
						if projectionErr := s.projectToolFailure(ctx, fk, event, toolFailure); projectionErr != nil {
							logger.Error(projectionErr, "failed to repair tool failure projection", "sessionID", sessionID,
								"seq", event.Seq, "tool", event.ToolName)
						}
					}
					committed[sessionID] = latest // replay of an already committed prefix
					continue
				}
				if event.Seq != latest+1 {
					logger.Info("refusing non-contiguous session event", "sessionID", sessionID, "expected", latest+1, "seq", event.Seq)
					failed[sessionID] = true
					break
				}
				occurredAt := event.OccurredAt
				if occurredAt.IsZero() {
					occurredAt = time.Now().UTC()
				}
				err = s.Store.Events().Append(ctx, &store.SessionEvent{
					SessionFK: fk, Seq: int(event.Seq), EventType: event.EventType, Role: event.Role,
					Content: event.Content, ToolName: event.ToolName, ToolInput: event.ToolInput,
					ToolOutput: event.ToolOutput, TokensIn: int(event.TokensIn), TokensOut: int(event.TokensOut),
					DurationMs: int(event.DurationMs), FrameworkMeta: event.FrameworkMeta, OccurredAt: occurredAt,
				})
				if err != nil && !errors.Is(err, store.ErrConflict) {
					logger.Error(err, "failed to append session event", "sessionID", sessionID, "seq", event.Seq)
					failed[sessionID] = true
					break
				}
				if toolFailure, ok := observedToolFailure(event); ok {
					if projectionErr := s.projectToolFailure(ctx, fk, event, toolFailure); projectionErr != nil {
						// Session durability is authoritative and must not be retried just
						// because an operator-facing diagnostic projection failed.
						logger.Error(projectionErr, "failed to project tool failure", "sessionID", sessionID,
							"seq", event.Seq, "tool", event.ToolName)
					}
				}
				latest = event.Seq
				for {
					if _, ok := storedSeqs[latest+1]; !ok {
						break
					}
					latest++
				}
				committed[sessionID] = latest
			}
			return nil
		})
		if err != nil {
			failed[sessionID] = true
		}

	}
	if len(grouped) == 0 && len(events) > 0 {
		return committed, fmt.Errorf("event report contains no valid session sequence")
	}
	if len(failed) > 0 {
		return committed, fmt.Errorf("one or more session event streams were not committed")
	}
	return committed, nil
}

type observedToolFailureDetails struct {
	State      string
	ToolCallID string
}

func observedToolFailure(event ObservedEvent) (observedToolFailureDetails, bool) {
	if event.EventType != "tool_result" {
		return observedToolFailureDetails{}, false
	}
	var metadata struct {
		State      string `json:"state"`
		ToolCallID string `json:"toolCallId"`
	}
	_ = json.Unmarshal(event.FrameworkMeta, &metadata)
	metadata.State = strings.ToLower(strings.TrimSpace(metadata.State))
	failed := metadata.State == "error" || metadata.State == "denied" || metadata.State == "interrupted"
	return observedToolFailureDetails{State: metadata.State, ToolCallID: metadata.ToolCallID}, failed
}

func (s *SessionEventSink) projectToolFailure(ctx context.Context, sessionRef uuid.UUID,
	event ObservedEvent, failure observedToolFailureDetails) error {
	session, err := s.Store.Sessions().GetByID(ctx, sessionRef)
	if err != nil {
		return err
	}
	if session.AgentTaskID == nil {
		return fmt.Errorf("session %s is not linked to an AgentTask", session.ID)
	}
	task, err := s.Store.Collaboration().GetAgentTask(ctx, *session.AgentTaskID)
	if err != nil {
		return err
	}
	var attemptID *uuid.UUID
	if task.CurrentAttemptID != nil {
		if attempt, attemptErr := s.Store.ExecutionAttempts().Get(ctx, *task.CurrentAttemptID); attemptErr == nil &&
			attempt.SessionID == session.SessionID {
			attemptID = &attempt.ID
		}
	}
	payload, err := json.Marshal(map[string]any{
		"toolName": event.ToolName, "toolCallId": failure.ToolCallID, "state": failure.State,
		"message": event.ToolOutput, "sessionId": session.SessionID, "sessionRef": session.ID,
		"sessionEventSeq": event.Seq,
	})
	if err != nil {
		return err
	}
	idempotencyKey := fmt.Sprintf("agent-tool-failed:%s:%d", session.ID, event.Seq)
	if failure.ToolCallID != "" {
		idempotencyKey = fmt.Sprintf("agent-tool-failed:%s:%s", task.ID, failure.ToolCallID)
	}
	_, err = s.Store.Orchestration().AppendRunEvent(ctx, &controlmodel.RunEvent{
		RunID: task.OrchestrationRunID, Tenant: task.Tenant, Namespace: task.Namespace,
		NodeID: &task.RunNodeID, AgentTaskID: &task.ID, AttemptID: attemptID, Type: "agent_tool.failed",
		Actor: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}, Payload: payload,
		CausationID: failure.ToolCallID, CorrelationID: task.CorrelationID,
		IdempotencyKey: idempotencyKey,
	})
	return err
}

// ApplyContextReport writes a Level-4 effective-context snapshot to the Store.
// Snapshots with an unchanged context_hash are skipped by the Store.
func (s *SessionEventSink) ApplyContextReport(ctx context.Context, identity RuntimeReportIdentity, oc ObservedContext) {
	logger := log.FromContext(ctx).WithName("asdp-context-sink")
	logger = logger.WithValues("tenant", identity.Tenant, "agentId", identity.AgentID)
	if s.Store == nil || oc.SessionID == "" {
		return
	}
	resolvedIdentity, err := s.resolveRuntimeReportIdentity(ctx, identity)
	if err != nil {
		logger.Error(err, "rejected runtime context report")
		return
	}

	fk, err := s.resolveSessionFK(ctx, resolvedIdentity, oc.SessionID)
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
func (s *SessionEventSink) ApplyInventoryReport(ctx context.Context, identity RuntimeReportIdentity, inv ObservedInventory) {
	logger := log.FromContext(ctx).WithName("asdp-inventory-sink")
	logger.V(1).Info("inventory report",
		"tenant", identity.Tenant, "agent", identity.AgentKey, "instance", identity.InstanceKey,
		"subagents", len(inv.Subagents), "workspaces", len(inv.Workspaces),
		"healthy", inv.Healthy, "activeSessions", inv.ActiveSessions)
	if s.Store == nil {
		return
	}
	resolved, err := s.resolveRuntimeReportIdentity(ctx, identity)
	if err != nil {
		logger.Error(err, "rejected runtime inventory report")
		return
	}
	if err := s.Store.Metrics().RecordAgentMetric(ctx, &store.AgentMetric{
		Tenant:         identity.Tenant,
		AgentID:        resolved.AgentUUID,
		AgentName:      identity.AgentKey,
		Namespace:      identity.Namespace,
		ActiveSessions: inv.ActiveSessions,
	}); err != nil {
		logger.Error(err, "failed to record agent metric from inventory")
	}
}

// resolveSessionFK maps a framework-reported session ID to the store primary
// key, creating a minimal session row when the session is not known yet
// (events/context may arrive before the first Level-1 snapshot).
func (s *SessionEventSink) resolveSessionFK(ctx context.Context, identity *resolvedRuntimeReportIdentity, sessionID string) (uuid.UUID, error) {
	sessions, err := s.Store.Sessions().List(ctx, store.SessionFilter{Tenant: identity.Tenant, AgentID: identity.AgentUUID,
		SessionID: sessionID, Limit: 1})
	if err == nil && len(sessions) > 0 {
		return sessions[0].ID, nil
	}
	if !errors.Is(err, store.ErrNotFound) {
		if err != nil {
			return uuid.Nil, err
		}
	}
	saved, err := s.Store.Sessions().Upsert(ctx, &store.Session{
		Tenant:             identity.Tenant,
		SessionID:          sessionID,
		AgentID:            identity.AgentUUID,
		BindingID:          identity.BindingUUID,
		AgentInstanceID:    identity.AgentInstanceUUID,
		InstanceGeneration: identity.InstanceGeneration,
		AgentName:          identity.AgentKey,
		Namespace:          identity.Namespace,
		Phase:              store.SessionPhaseActive,
		InstanceRef:        identity.InstanceKey,
		OriginType:         "runtime",
	})
	if err != nil {
		return uuid.Nil, fmt.Errorf("creating placeholder session %s: %w", sessionID, err)
	}
	return saved.ID, nil
}

// upsertObservedSession writes a session + Level-1 snapshot (+ optional token metric)
// into the Store. Shared by SessionPoller (HTTP pull) and ASDP gRPC sink (push).
// It returns the saved session so callers can chain context/event writes.
func upsertObservedSession(ctx context.Context, st store.Store, tenant string, agent *v1alpha1.Agent, o ObservedSession, identity ...*resolvedRuntimeReportIdentity) (*store.Session, error) {
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
	if len(identity) > 0 && identity[0] != nil {
		resolved := identity[0]
		sess.AgentID = resolved.AgentUUID
		sess.BindingID = resolved.BindingUUID
		sess.AgentInstanceID = resolved.AgentInstanceUUID
		sess.InstanceGeneration = resolved.InstanceGeneration
		sess.AgentName = resolved.AgentKey
		sess.OriginType = "runtime"
	}
	saved, err := st.Sessions().Upsert(ctx, sess)
	if err != nil {
		return nil, fmt.Errorf("upserting session %s: %w", o.ID, err)
	}
	if err := st.Turns().SyncOnPhase(ctx, saved.ID, phase); err != nil {
		return nil, fmt.Errorf("syncing turn for session %s: %w", o.ID, err)
	}

	snap := &store.SessionSnapshot{
		TokenUsageReported:      o.TokenUsageReported || o.PromptTokens != 0 || o.CompletionTokens != 0,
		ContextPressureReported: o.ContextPressureReported || o.ContextPressure != 0,
		SessionFK:               saved.ID,
		MessageCount:            o.MessageCount,
		PromptTokens:            o.PromptTokens,
		CompletionTokens:        o.CompletionTokens,
		TotalTokens:             o.PromptTokens + o.CompletionTokens,
		ContextPressure:         o.ContextPressure,
		IsCompacted:             o.IsCompacted,
		EffectiveMessageCount:   o.EffectiveMessageCount,
		ContextHash:             o.ContextHash,
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
			AgentID:          saved.AgentID,
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

func (s *SessionEventSink) listSessionReportEvents(ctx context.Context, sessionID uuid.UUID) ([]*store.SessionEvent, error) {
	result := []*store.SessionEvent{}
	after := 0
	for {
		page, err := s.Store.Events().List(ctx, sessionID, store.WithEventAfterSeq(after), store.WithEventLimit(1000))
		if err != nil {
			return nil, err
		}
		result = append(result, page...)
		if len(page) < 1000 {
			return result, nil
		}
		next := page[len(page)-1].Seq
		if next <= after {
			return nil, fmt.Errorf("session event cursor did not advance")
		}
		after = next
	}
}

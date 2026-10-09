/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package httpapi

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/orchestration"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

func (s *Server) refreshServiceInvocation(ctx context.Context, id uuid.UUID) (*model.EndpointInvocation, error) {
	var result *model.EndpointInvocation
	err := s.store.WithSessionLock(ctx, "service-invocation:"+id.String(), func(ctx context.Context) error {
		inv, err := s.store.Endpoints().GetInvocation(ctx, id)
		if err != nil {
			return err
		}
		result = inv
		ep, err := serviceapi.BoundEndpoint(ctx, s.store, inv)
		if err != nil {
			return err
		}
		if serviceapi.Terminal(inv.Status) {
			return nil
		}
		if inv.Status != model.EndpointInvocationCancelRequested && ep.TimeoutSeconds > 0 && time.Now().After(inv.CreatedAt.Add(time.Duration(ep.TimeoutSeconds)*time.Second)) {
			if err = s.expireServiceInvocation(ctx, inv); err != nil {
				return err
			}
			result, err = s.store.Endpoints().GetInvocation(ctx, id)
			return err
		}
		if inv.RunID == nil {
			result, err = s.refreshNativeServiceTurn(ctx, ep, inv)
			return err
		}
		run, err := s.store.Orchestration().GetRun(ctx, *inv.RunID)
		if errors.Is(err, store.ErrNotFound) {
			return nil
		}
		if err != nil {
			return err
		}
		prior := *inv
		old := inv.Status
		switch run.State {
		case model.RunWaiting, model.RunPaused:
			if inv.Status != model.EndpointInvocationCancelRequested {
				inv.Status = model.EndpointInvocationWaiting
			}
		case model.RunRunning:
			if inv.Status != model.EndpointInvocationCancelRequested {
				inv.Status = model.EndpointInvocationRunning
			}
		case model.RunCancelling:
			inv.Status = model.EndpointInvocationCancelRequested
		case model.RunSucceeded, model.RunPartialSucceeded:
			nodes, err := s.store.Orchestration().ListNodes(ctx, run.ID)
			if err != nil {
				return err
			}
			inv.Status = model.EndpointInvocationCompleted
			if run.State == model.RunPartialSucceeded {
				inv.Status = model.EndpointInvocationPartialSucceeded
			}
			serviceapi.CompleteResult(ep, inv, orchestration.CompletedRunOutput(run, nodes))
		case model.RunFailed:
			inv.Status = model.EndpointInvocationFailed
			inv.ErrorCode, inv.ErrorMessage = run.FailureCode, run.FailureMessage
		case model.RunCancelled:
			active, err := s.serviceActiveAttempts(ctx, run.ID)
			if err != nil {
				return err
			}
			if active {
				inv.Status = model.EndpointInvocationCancelRequested
			} else {
				inv.Status = model.EndpointInvocationCancelled
			}
		}
		if serviceapi.Terminal(inv.Status) {
			ready, err := s.serviceMirrorsReady(ctx, run.ID)
			if err != nil {
				return err
			}
			if !ready {
				*inv = prior
				return nil
			}
		}
		if serviceapi.Terminal(inv.Status) && inv.ErrorCode == "endpoint_timeout" {
			inv.Status = model.EndpointInvocationTimedOut
		}
		if old == inv.Status {
			return nil
		}
		if serviceapi.Terminal(inv.Status) {
			now := time.Now().UTC()
			inv.CompletedAt = &now
		}
		result, err = s.store.Endpoints().UpdateInvocation(ctx, inv)
		return err
	})
	return result, err
}
func (s *Server) serviceActiveAttempts(ctx context.Context, runID uuid.UUID) (bool, error) {
	tasks, err := s.serviceTasks(ctx, runID)
	if err != nil {
		return false, err
	}
	for _, task := range tasks {
		attempts, err := s.store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{AgentTaskID: task.ID, Limit: 1000})
		if err != nil {
			return false, err
		}
		for _, a := range attempts {
			switch a.State {
			case model.ExecutionSucceeded, model.ExecutionFailed, model.ExecutionCancelled:
			default:
				return true, nil
			}
		}
	}
	return false, nil
}

// A terminal public event is a delivery barrier: late Managed reports from
// the original Attempt must be durable in the control plane before SSE closes.
func (s *Server) serviceMirrorsReady(ctx context.Context, runID uuid.UUID) (bool, error) {
	tasks, err := s.serviceTasks(ctx, runID)
	if err != nil {
		return false, err
	}
	for _, task := range tasks {
		attempts, err := s.store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{AgentTaskID: task.ID, Limit: 1000})
		if err != nil {
			return false, err
		}
		for _, attempt := range attempts {
			if attempt.BackendKind != model.DataPlaneManaged || attempt.SessionID == "" {
				continue
			}
			if s.product == nil {
				return false, fmt.Errorf("Managed event mirror unavailable")
			}
			_, ready, err := s.product.ManagedMirrorStatus(ctx, attempt.ManagedOwnerRef, attempt.SessionID, attempt.ID.String())
			if err != nil {
				return false, err
			}
			if !ready {
				return false, nil
			}
		}
	}
	return true, nil
}
func serviceFingerprint(value any) string {
	raw, _ := json.Marshal(value)
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:])
}
func serviceJournal(st store.Store, ep *model.Endpoint, inv *model.EndpointInvocation) serviceapi.Journal {
	return serviceapi.Journal{Store: st, Tenant: ep.Tenant, InvocationID: inv.ID}
}
func serviceInvocationView(inv *model.EndpointInvocation) map[string]any { return serviceapi.View(inv) }

func (s *Server) projectServiceInvocation(ctx context.Context, id uuid.UUID) error {
	return s.store.WithSessionLock(ctx, "service-project:"+id.String(), func(ctx context.Context) error { return s.projectServiceInvocationLocked(ctx, id) })
}
func (s *Server) projectServiceInvocationLocked(ctx context.Context, id uuid.UUID) error {
	inv, err := s.store.Endpoints().GetInvocation(ctx, id)
	if err != nil {
		return err
	}
	ep, err := serviceapi.BoundEndpoint(ctx, s.store, inv)
	if err != nil {
		return err
	}
	journal := serviceJournal(s.store, ep, inv)
	if serviceapi.Terminal(inv.Status) {
		snapshot, err := journal.Snapshot(ctx)
		if err != nil {
			return err
		}
		if serviceFingerprint(snapshot.Invocation) == serviceFingerprint(serviceInvocationView(inv)) {
			return nil
		}
	}
	emit := func(source, kind string, data map[string]any) error {
		_, err := journal.Append(ctx, source, kind, data)
		return err
	}
	accepted := serviceInvocationView(inv)
	accepted["status"] = "accepted"
	delete(accepted, "result")
	delete(accepted, "error")
	if err = emit("admission:"+inv.ID.String(), "invocation.accepted", accepted); err != nil {
		return err
	}
	if inv.RunID != nil {
		ids, err := s.serviceRunIDs(ctx, *inv.RunID)
		if err != nil {
			return err
		}
		for _, id := range ids {
			if err = s.projectServiceRunHistory(ctx, ep, inv, journal, id); err != nil {
				return err
			}
		}
		tasks, err := s.serviceTasks(ctx, *inv.RunID)
		if err != nil {
			return err
		}
		for _, task := range tasks {
			data := map[string]any{"step_id": task.ID.String(), "agent_id": task.AgentRef, "role": task.TeamRole, "status": task.Status}
			if err = emit("task:"+task.ID.String()+":"+fmt.Sprint(task.Version), "step.updated", data); err != nil {
				return err
			}
			attempts, err := s.store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{AgentTaskID: task.ID, Limit: 1000})
			if err != nil {
				return err
			}
			sort.Slice(attempts, func(i, j int) bool { return attempts[i].CreatedAt.Before(attempts[j].CreatedAt) })
			for _, a := range attempts {
				if len(a.Usage) > 0 {
					if err = emit("usage:"+a.ID.String()+":"+serviceFingerprint(a.Usage), "usage.recorded", map[string]any{"execution_id": a.ID.String(), "usage": a.Usage}); err != nil {
						return err
					}
				}
				if a.SessionID != "" {
					if err = s.projectServiceSession(ctx, ep, inv, journal, a.SessionID, a.ID.String()); err != nil {
						return err
					}
				}
			}
		}
		runIDs, err := s.serviceRunIDs(ctx, *inv.RunID)
		if err != nil {
			return err
		}
		for _, runID := range runIDs {
			nodes, err := s.store.Orchestration().ListNodes(ctx, runID)
			if err != nil {
				return err
			}
			for _, node := range nodes {
				if node.State == model.RunNodeFailed {
					if err = emit("failed-node:"+node.ID.String()+":"+fmt.Sprint(node.Version), "step.failed", map[string]any{"step_id": node.ID.String(), "status": node.State, "message": node.FailureMessage}); err != nil {
						return err
					}
				}
				if node.Type != model.RunNodeSignal {
					continue
				}
				kind := "required_action.resolved"
				if node.State == model.RunNodeWaiting && strings.HasPrefix(node.WaitReason, "signal:") {
					kind = "required_action.created"
				}
				data := map[string]any{"request_id": "signal:" + node.ID.String(), "kind": "input", "status": node.State, "version": node.Version, "name": strings.TrimPrefix(node.WaitReason, "signal:")}
				if err = emit("signal:"+node.ID.String()+":"+fmt.Sprint(node.Version), kind, data); err != nil {
					return err
				}
			}
		}
		for offset := 0; ; offset += 1000 {
			approvals, err := s.store.Collaboration().ListApprovals(ctx, store.ApprovalFilter{Tenant: ep.Tenant, Namespace: ep.Namespace, Limit: 1000, Offset: offset})
			if err != nil {
				return err
			}
			for _, a := range approvals {
				if !s.serviceApprovalBelongs(ctx, inv, a) {
					continue
				}
				kind := "required_action.created"
				if a.Status != model.ApprovalPending {
					kind = "required_action.resolved"
				}
				data := map[string]any{"request_id": a.ID.String(), "kind": "approval", "status": a.Status, "version": a.Version, "reason": a.Reason, "approver": a.ApproverRef}
				if err = emit("approval:"+a.ID.String()+":"+fmt.Sprint(a.Version), kind, data); err != nil {
					return err
				}
			}
			if len(approvals) < 1000 {
				break
			}
		}
	}
	if inv.SessionID != "" {
		if err = s.projectServiceSession(ctx, ep, inv, journal, inv.SessionID, inv.ID.String()); err != nil {
			return err
		}
	}
	if inv.IssueID != nil {
		artifacts, err := s.serviceArtifacts(ctx, inv, ep)
		if err != nil {
			return err
		}
		for _, a := range artifacts {
			data := map[string]any{"artifact_id": a.ID.String(), "name": a.Filename, "media_type": a.ContentType, "size": a.SizeBytes, "download_url": "/invoke/v1/invocations/" + id.String() + "/artifacts/" + a.ID.String()}
			if err = emit("artifact:"+a.ID.String(), "artifact.published", data); err != nil {
				return err
			}
		}
	}
	data := serviceInvocationView(inv)
	return emit("status:"+inv.UpdatedAt.UTC().Format(time.RFC3339Nano)+":"+serviceFingerprint(data), "invocation."+string(inv.Status), data)
}
func (s *Server) projectServiceSession(ctx context.Context, ep *model.Endpoint, inv *model.EndpointInvocation, j serviceapi.Journal, sessionID, executionID string) error {
	if inv.RunID == nil && s.product != nil {
		if turn, err := s.nativeServiceTurn(ctx, ep, inv); err == nil {
			return s.projectNativeServiceEvents(ctx, ep, inv, j, turn)
		} else if err != store.ErrNotFound {
			return err
		}
	}
	sessions, err := s.store.Sessions().List(ctx, store.SessionFilter{Tenant: ep.Tenant, Namespace: ep.Namespace, SessionID: sessionID, Limit: 2})
	if err != nil {
		return err
	}
	if len(sessions) != 1 {
		return nil
	}
	session := sessions[0]
	ns := "service-api/projections/" + inv.ID.String()
	key := session.ID.String() + ":" + executionID
	after := 0
	if item, err := s.store.KV().Get(ctx, ep.Tenant, ns, key); err == nil {
		if err = json.Unmarshal(item.Value, &after); err != nil {
			return err
		}
	} else if !errors.Is(err, store.ErrNotFound) {
		return err
	}
	for {
		events, err := s.store.Events().List(ctx, session.ID, store.WithEventAfterSeq(after), store.WithEventLimit(100))
		if err != nil {
			return err
		}
		for _, event := range events {
			var meta map[string]any
			_ = json.Unmarshal(event.FrameworkMeta, &meta)
			if attempt, ok := meta["attemptId"].(string); ok && attempt != "" && executionID != inv.ID.String() && attempt != executionID {
				after = event.Seq
				continue
			}
			if ref, ok := meta["endpointInvocationId"].(string); ok && ref != "" && ref != inv.ID.String() {
				after = event.Seq
				continue
			}
			// Immutable execution correlation is authoritative across clock skew
			// and delayed delivery. Time bounds apply only to unscoped observers.
			correlated := meta["attemptId"] == executionID || meta["endpointInvocationId"] == inv.ID.String()
			if !correlated && (event.OccurredAt.Before(inv.CreatedAt) || (inv.CompletedAt != nil && event.OccurredAt.After(*inv.CompletedAt))) {
				after = event.Seq
				continue
			}
			data := map[string]any{"execution_id": executionID, "agent_id": session.AgentID.String()}
			kind := ""
			if pub, ok := meta["public_event"].(map[string]any); ok {
				kind, _ = pub["type"].(string)
				if inv.RunID == nil {
					if turn, e := s.nativeServiceTurn(ctx, ep, inv); e == nil {
						payload, _ := pub["payload"].(map[string]any)
						if native, ok := payload["turn_id"].(string); ok && native != "" && native != turn.ID {
							after = event.Seq
							continue
						}
					}
				}
				payload, _ := pub["payload"].(map[string]any)
				if publicServiceRuntimeType(kind) {
					if strings.HasPrefix(kind, "required_action.") && inv.RunID != nil {
						kind = ""
					}
					for k, v := range payload {
						if k != "source" && k != "run_id" && k != "turn_id" && k != "execution_id" && k != "agent_id" {
							data[k] = v
						}
					}
				} else {
					kind = ""
				}
				if item, ok := data["item_id"].(string); ok {
					data["item_id"] = executionID + ":" + item
				}
				if strings.HasPrefix(kind, "required_action.") {
					if request, ok := data["request_id"].(string); ok {
						data["request_id"] = "native:" + request
					}
				}
				if kind == "run.ended" {
					kind = "execution.ended"
				}
			} else {
				switch {
				case event.ToolName == "" && (event.Role == "assistant" || event.Role == "user") && event.Content != "" && !strings.Contains(event.EventType, "thinking") && !strings.Contains(event.EventType, "reasoning"):
					kind = "item.completed"
					data["item_id"] = executionID + ":" + fmt.Sprint(event.Seq)
					data["item"] = map[string]any{"role": event.Role, "content": []any{map[string]any{"type": "text", "text": event.Content}}}
				case event.ToolName != "":
					kind = "tool.completed"
					if event.ToolOutput == "" {
						kind = "tool.requested"
					}
					call, _ := meta["toolCallId"].(string)
					if call == "" {
						call, _ = meta["tool_use_id"].(string)
					}
					if call == "" {
						call = fmt.Sprint(event.Seq)
					}
					data["tool_call_id"], data["name"], data["input"], data["result"] = call, event.ToolName, event.ToolInput, event.ToolOutput
				}
			}
			if kind != "" {
				if _, err = j.Append(ctx, "session:"+session.ID.String()+":"+fmt.Sprint(event.Seq), kind, data); err != nil {
					return err
				}
			}
			after = event.Seq
		}
		raw, _ := json.Marshal(after)
		if _, err = s.store.KV().Put(ctx, ep.Tenant, ns, key, raw); err != nil {
			return err
		}
		if len(events) < 100 {
			return nil
		}
	}
}
func publicServiceRuntimeType(kind string) bool {
	switch kind {
	case "required_action.created", "required_action.resolved", "run.ended", "item.started", "item.delta", "item.completed", "tool.requested", "tool.dispatched", "tool.delta", "tool.completed", "usage.recorded", "model.completed":
		return true
	}
	return false
}

// Replay the durable execution history as public progress facts. This preserves
// short-lived steps even when no SSE observer was connected at execution time.
func (s *Server) projectServiceRunHistory(ctx context.Context, ep *model.Endpoint, inv *model.EndpointInvocation, j serviceapi.Journal, id uuid.UUID) error {
	path := "service-api/run-cursors/" + inv.ID.String()
	after := int64(0)
	if row, err := s.store.KV().Get(ctx, ep.Tenant, path, id.String()); err == nil {
		if err = json.Unmarshal(row.Value, &after); err != nil {
			return err
		}
	} else if !errors.Is(err, store.ErrNotFound) {
		return err
	}
	for {
		events, err := s.store.Orchestration().ListRunEvents(ctx, id, after, 100)
		if err != nil {
			return err
		}
		for _, event := range events {
			if strings.HasPrefix(event.Type, "attempt.") || strings.HasPrefix(event.Type, "node.") || strings.HasPrefix(event.Type, "run.") && !strings.HasPrefix(event.Type, "run.signal.") {
				stepID, kind := id.String(), "workflow"
				if event.NodeID != nil {
					stepID, kind = event.NodeID.String(), "step"
				}
				if event.AttemptID != nil {
					stepID, kind = event.AttemptID.String(), "execution"
				}
				data := map[string]any{"step_id": stepID, "kind": kind, "status": event.Type[strings.Index(event.Type, ".")+1:], "occurred_at": event.OccurredAt.UnixMilli()}
				if event.AgentTaskID != nil {
					data["parent_step_id"] = event.AgentTaskID.String()
				}
				if _, err = j.Append(ctx, "run-event:"+event.ID.String(), "step.updated", data); err != nil {
					return err
				}
			}
			after = event.Sequence
		}
		raw, _ := json.Marshal(after)
		if _, err = s.store.KV().Put(ctx, ep.Tenant, path, id.String(), raw); err != nil {
			return err
		}
		if len(events) < 100 {
			return nil
		}
	}
}

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
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/collaboration"
	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionops"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type serviceCommand struct {
	Actor       model.Actor     `json:"actor"`
	ID          uuid.UUID       `json:"id"`
	Kind        string          `json:"kind"`
	Status      string          `json:"status"`
	Principal   string          `json:"-"`
	Payload     json.RawMessage `json:"payload"`
	Error       string          `json:"error,omitempty"`
	Attempts    int             `json:"attempts"`
	NextAttempt time.Time       `json:"next_attempt,omitempty"`
}
type serviceActionInput struct {
	RequestID string               `json:"request_id"`
	Version   int64                `json:"expected_version"`
	Decision  model.ApprovalStatus `json:"decision"`
	Payload   json.RawMessage      `json:"payload"`
	Message   string               `json:"message"`
}

func serviceCommandPath(id uuid.UUID) string { return "service-api/commands/" + id.String() }
func (s *Server) createServiceCommand(c *gin.Context) {
	kind := c.FullPath()[strings.LastIndex(c.FullPath(), "/")+1:]
	if override := c.GetString("service-command-kind"); override != "" {
		kind = override
	}
	scope := "interact"
	if kind == "cancel" {
		scope = "cancel"
	} else if kind == "actions" {
		scope = "approve"
	}
	inv, ep, ok := s.loadServiceInvocation(c, scope)
	if !ok {
		return
	}
	key := strings.TrimSpace(c.GetHeader("Idempotency-Key"))
	if key == "" {
		c.JSON(400, ErrorResponse{Error: "Idempotency-Key is required"})
		return
	}
	var input serviceActionInput
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 1<<20)
	if c.Request.ContentLength != 0 {
		if err := c.ShouldBindJSON(&input); err != nil {
			c.JSON(400, ErrorResponse{Error: err.Error()})
			return
		}
	}
	payload, _ := json.Marshal(input)
	command := serviceCommand{ID: uuid.NewSHA1(inv.ID, []byte("command:"+key)), Kind: kind, Status: "accepted", Principal: commandActorPrincipal(endpointActor(c)), Actor: endpointActor(c), Payload: payload}
	path := serviceCommandPath(inv.ID)
	raw, err := s.store.KV().Get(c, ep.Tenant, path, command.ID.String())
	if err == nil {
		old, decodeErr := decodeServiceCommand(raw.Value)
		if decodeErr != nil {
			s.writeControlPlaneError(c, decodeErr)
			return
		}
		if old.Kind != kind || !sameJSON(old.Payload, payload) {
			c.JSON(409, ErrorResponse{Error: "Idempotency-Key was used for another command"})
			return
		}
		c.JSON(202, gin.H{"command": old, "status_url": publicCommandBase(inv) + "/commands/" + old.ID.String()})
		return
	}
	if !errors.Is(err, store.ErrNotFound) {
		s.writeControlPlaneError(c, err)
		return
	}
	if serviceapi.Terminal(inv.Status) {
		c.JSON(409, ErrorResponse{Error: "invocation has ended; submit a new invocation"})
		return
	}
	if err = s.validateServiceCommand(c, inv, ep, &command, input); err != nil {
		c.JSON(409, ErrorResponse{Error: err.Error()})
		return
	}
	// Principal is private to storage, never reflected in the public command resource.
	encoded, _ := json.Marshal(map[string]any{"command": command, "principal": command.Principal})
	_, written, err := s.store.KV().PutIfVersion(c, ep.Tenant, path, command.ID.String(), encoded, 0)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !written {
		c.JSON(409, ErrorResponse{Error: "command concurrently submitted; retry the same key"})
		return
	}
	_ = s.store.Endpoints().ScheduleInvocation(c, inv.ID, time.Now().UTC())
	_, _ = serviceJournal(s.store, ep, inv).Append(c, "command:"+command.ID.String()+":accepted", "command.accepted", map[string]any{"command_id": command.ID, "kind": kind, "actor": command.Actor})
	c.JSON(202, gin.H{"command": command, "status_url": publicCommandBase(inv) + "/commands/" + command.ID.String()})
}
func decodeServiceCommand(raw json.RawMessage) (serviceCommand, error) {
	var v struct {
		Command   serviceCommand `json:"command"`
		Principal string         `json:"principal"`
	}
	err := json.Unmarshal(raw, &v)
	v.Command.Principal = v.Principal
	return v.Command, err
}
func (s *Server) getServiceCommand(c *gin.Context) {
	inv, ep, ok := s.loadServiceInvocation(c, "read")
	if !ok {
		return
	}
	id, err := uuid.Parse(c.Param("commandId"))
	if err != nil {
		c.JSON(400, ErrorResponse{Error: "invalid commandId"})
		return
	}
	item, err := s.store.KV().Get(c, ep.Tenant, serviceCommandPath(inv.ID), id.String())
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	command, err := decodeServiceCommand(item.Value)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(200, gin.H{"command": command})
}
func (s *Server) validateServiceCommand(ctx context.Context, inv *model.EndpointInvocation, ep *model.Endpoint, cmd *serviceCommand, in serviceActionInput) error {
	switch cmd.Kind {
	case "actions":
		if strings.HasPrefix(in.RequestID, "native:") {
			return s.nativeServiceAction(ctx, ep, inv, cmd, in, false)
		}
		if in.RequestID == "" {
			return fmt.Errorf("request_id is required")
		}
		if strings.HasPrefix(in.RequestID, "signal:") {
			return s.serviceSignal(ctx, inv, in, cmd.ID.String(), cmd.Actor, false)
		}
		id, err := uuid.Parse(in.RequestID)
		if err != nil {
			return fmt.Errorf("invalid request_id")
		}
		a, err := s.store.Collaboration().GetApproval(ctx, id)
		if err != nil {
			return err
		}
		if !s.serviceApprovalBelongs(ctx, inv, a) {
			return store.ErrNotFound
		}
		if in.Version <= 0 || a.Version != in.Version || a.Status != model.ApprovalPending {
			return fmt.Errorf("approval is no longer pending at expected_version")
		}
		if in.Decision != model.ApprovalApproved && in.Decision != model.ApprovalRejected {
			return fmt.Errorf("decision must be approved or rejected")
		}
		actor := strings.TrimPrefix(cmd.Principal, "platform-user:")
		if !strings.HasPrefix(cmd.Principal, "platform-user:") || (a.ApproverRef != cmd.Principal && a.ApproverRef != actor) {
			return fmt.Errorf("only the designated approver may decide this request")
		}
		if envelope, parseErr := parseManagedToolApproval(a); parseErr == nil {
			if !strings.HasPrefix(cmd.Principal, "platform-user:") {
				return fmt.Errorf("managed tool approval requires its designated human")
			}
			if _, err = s.validateManagedApprovalCurrent(ctx, a); err != nil {
				return err
			}
			if !envelope.ExpiresAt.IsZero() && !time.Now().Before(envelope.ExpiresAt) {
				return fmt.Errorf("approval expired")
			}
		}
	case "inputs":
		if in.RequestID != "" {
			return s.serviceSignal(ctx, inv, in, cmd.ID.String(), cmd.Actor, false)
		}
		if strings.TrimSpace(in.Message) == "" {
			return fmt.Errorf("message or a pending signal request_id is required")
		}
		if inv.Mode == model.EndpointConversationMode {
			_, err := s.nativeServiceTurn(ctx, ep, inv)
			return err
		}
		if inv.IssueID == nil {
			return fmt.Errorf("execution has not created a work context; retry when running")
		}
		if inv.Mode != model.EndpointJobMode {
			return fmt.Errorf("submit conversation messages using conversations/{id}/turns")
		}
	case "resume":
		if inv.RunID == nil {
			turn, err := s.nativeServiceTurn(ctx, ep, inv)
			if err != nil {
				return err
			}
			raw, err := s.product.ManagedServiceRequest(ctx, turn.Owner, inv.SessionID, "GET", "/turns/"+url.PathEscape(turn.ID), "", nil)
			if err != nil {
				return err
			}
			var state struct {
				Status string `json:"status"`
			}
			_ = json.Unmarshal(raw, &state)
			if state.Status != "interrupted" {
				return fmt.Errorf("resume requires an interrupted native turn")
			}
			return nil
		}
		if inv.RunID == nil {
			return fmt.Errorf("invocation has no resumable workflow")
		}
		run, err := s.store.Orchestration().GetRun(ctx, *inv.RunID)
		if err != nil {
			return err
		}
		if run.State != model.RunPaused {
			return fmt.Errorf("resume requires a paused workflow; answer pending actions to continue a waiting workflow")
		}
	case "cancel":
	default:
		return fmt.Errorf("unsupported command")
	}
	return nil
}
func (s *Server) serviceApprovalBelongs(ctx context.Context, inv *model.EndpointInvocation, a *model.Approval) bool {
	if inv.RunID == nil {
		return false
	}
	runs, err := s.serviceRunIDs(ctx, *inv.RunID)
	if err != nil {
		return false
	}
	for _, id := range runs {
		if a.RunID != nil && *a.RunID == id {
			return true
		}
	}
	return a.IssueID != nil && inv.IssueID != nil && *a.IssueID == *inv.IssueID
}
func (s *Server) serviceSignal(ctx context.Context, inv *model.EndpointInvocation, in serviceActionInput, key string, actor model.Actor, apply bool) error {
	id, err := uuid.Parse(strings.TrimPrefix(in.RequestID, "signal:"))
	if err != nil || inv.RunID == nil {
		return fmt.Errorf("invalid signal request_id")
	}
	node, err := s.store.Orchestration().GetNode(ctx, id)
	if err != nil {
		return err
	}
	ids, err := s.serviceRunIDs(ctx, *inv.RunID)
	if err != nil {
		return err
	}
	owned := false
	for _, id := range ids {
		if id == node.RunID {
			owned = true
		}
	}
	if !owned {
		return store.ErrNotFound
	}
	if !strings.HasPrefix(node.WaitReason, "signal:") || node.State != model.RunNodeWaiting {
		return fmt.Errorf("signal is no longer waiting")
	}
	if in.Version > 0 && node.Version != in.Version {
		return store.ErrConflict
	}
	if apply {
		return s.orchestrationService().Signal(ctx, node.RunID, strings.TrimPrefix(node.WaitReason, "signal:"), key, in.Payload, actor)
	}
	return nil
}
func (s *Server) processServiceCommands(ctx context.Context, inv *model.EndpointInvocation, ep *model.Endpoint) error {
	return s.store.WithSessionLock(ctx, "service-commands:"+inv.ID.String(), func(ctx context.Context) error {
		for offset := 0; ; offset += 100 {
			rows, err := s.store.KV().Search(ctx, ep.Tenant, serviceCommandPath(inv.ID), 100, offset)
			if err != nil {
				return err
			}
			for _, row := range rows {
				cmd, err := decodeServiceCommand(row.Value)
				if err != nil {
					return err
				}
				// Recover the accepted event if the process died after storing the command.
				if _, err = serviceJournal(s.store, ep, inv).Append(ctx, "command:"+cmd.ID.String()+":accepted", "command.accepted", map[string]any{"command_id": cmd.ID, "kind": cmd.Kind, "actor": cmd.Actor}); err != nil {
					return err
				}
				if cmd.Status != "accepted" {
					if _, err = serviceJournal(s.store, ep, inv).Append(ctx, "command:"+cmd.ID.String()+":"+cmd.Status, "command."+cmd.Status, map[string]any{"command_id": cmd.ID, "kind": cmd.Kind, "error": cmd.Error, "actor": cmd.Actor}); err != nil {
						return err
					}
				}
				if cmd.Status != "accepted" || time.Now().Before(cmd.NextAttempt) {
					continue
				}
				var in serviceActionInput
				_ = json.Unmarshal(cmd.Payload, &in)
				err = s.applyServiceCommand(ctx, inv, ep, &cmd, in)
				cmd.Attempts++
				if err == nil {
					cmd.Status = "completed"
					cmd.Error = ""
				} else {
					cmd.Error = err.Error()
					cmd.NextAttempt = time.Now().Add(time.Duration(min(cmd.Attempts*2, 60)) * time.Second)
					if cmd.Attempts >= 10 || errors.Is(err, store.ErrConflict) || errors.Is(err, store.ErrNotFound) {
						cmd.Status = "failed"
					}
				}
				raw, _ := json.Marshal(map[string]any{"command": cmd, "principal": cmd.Principal})
				if _, err = s.store.KV().Put(ctx, ep.Tenant, serviceCommandPath(inv.ID), row.Key, raw); err != nil {
					return err
				}
				if cmd.Status != "accepted" {
					if _, err = serviceJournal(s.store, ep, inv).Append(ctx, "command:"+cmd.ID.String()+":"+cmd.Status, "command."+cmd.Status, map[string]any{"command_id": cmd.ID, "kind": cmd.Kind, "error": cmd.Error, "actor": cmd.Actor}); err != nil {
						return err
					}
				}
			}
			if len(rows) < 100 {
				return nil
			}
		}
	})
}
func (s *Server) applyServiceCommand(ctx context.Context, inv *model.EndpointInvocation, ep *model.Endpoint, cmd *serviceCommand, in serviceActionInput) error {
	if !s.authorizeServiceCommandActor(ctx, inv, ep, cmd) {
		return fmt.Errorf("%w: command actor authorization was revoked", store.ErrConflict)
	}
	switch cmd.Kind {
	case "cancel":
		return s.cancelServiceInvocation(ctx, inv.ID, false)
	case "resume":
		if inv.RunID == nil {
			turn, err := s.nativeServiceTurn(ctx, ep, inv)
			if err != nil {
				return err
			}
			_, err = s.product.ManagedServiceRequest(ctx, turn.Owner, inv.SessionID, "POST", "/turns/"+url.PathEscape(turn.ID)+"/resume", cmd.ID.String(), map[string]any{})
			return err
		}
		run, err := s.store.Orchestration().GetRun(ctx, *inv.RunID)
		if err != nil {
			return err
		}
		if run.State != model.RunPaused {
			return nil
		}
		_, err = s.orchestrationService().Resume(ctx, run.ID)
		return err
	case "actions":
		if strings.HasPrefix(in.RequestID, "native:") {
			return s.nativeServiceAction(ctx, ep, inv, cmd, in, true)
		}
		if strings.HasPrefix(in.RequestID, "signal:") {
			return s.applyServiceSignal(ctx, inv, in, cmd.ID.String(), cmd.Actor)
		}
		id, _ := uuid.Parse(in.RequestID)
		a, err := s.store.Collaboration().GetApproval(ctx, id)
		if err != nil {
			return err
		}
		actor := model.Actor{Type: model.ActorSystem, Ref: cmd.Principal}
		if strings.HasPrefix(cmd.Principal, "platform-user:") {
			actor.Type = model.ActorHuman
			actor.Ref = strings.TrimPrefix(cmd.Principal, "platform-user:")
		}
		if a.Status == in.Decision && a.DecidedBy != nil && a.DecidedBy.Ref == actor.Ref && sameJSON(a.Decision, in.Payload) {
			return nil
		}
		if err = s.validateServiceCommand(ctx, inv, ep, cmd, in); err != nil {
			return fmt.Errorf("%w: %s", store.ErrConflict, err)
		}
		_, err = s.store.Collaboration().DecideApproval(ctx, id, in.Version, in.Decision, actor, in.Payload)
		return err
	case "inputs":
		if inv.Mode == model.EndpointConversationMode {
			turn, err := s.nativeServiceTurn(ctx, ep, inv)
			if err != nil {
				return err
			}
			_, err = s.product.ManagedServiceRequest(ctx, turn.Owner, inv.SessionID, "POST", "/turns/"+url.PathEscape(turn.ID)+"/steer", cmd.ID.String(), map[string]any{"message": in.Message})
			return err
		}
		if in.RequestID != "" {
			return s.applyServiceSignal(ctx, inv, in, cmd.ID.String(), cmd.Actor)
		}
		if inv.IssueID == nil {
			return store.ErrConflict
		}
		if _, err := s.store.Collaboration().GetComment(ctx, cmd.ID); err == nil {
			return nil
		} else if !errors.Is(err, store.ErrNotFound) {
			return err
		}
		current, err := s.refreshServiceInvocation(ctx, inv.ID)
		if err != nil {
			return err
		}
		if serviceapi.Terminal(current.Status) || current.Status == model.EndpointInvocationCancelRequested {
			return fmt.Errorf("%w: invocation no longer accepts inputs", store.ErrConflict)
		}
		_, err = s.collaborationService().AddComment(ctx, collaboration.AddCommentRequest{ID: cmd.ID, IssueID: *inv.IssueID, Author: cmd.Actor, Content: in.Message})
		return err
	}
	return fmt.Errorf("unsupported command")
}
func (s *Server) applyServiceSignal(ctx context.Context, inv *model.EndpointInvocation, in serviceActionInput, key string, actor model.Actor) error {
	// Existing signal records make retry after a lost response safe, even after the node advances.
	id, err := uuid.Parse(strings.TrimPrefix(in.RequestID, "signal:"))
	if err != nil {
		return store.ErrConflict
	}
	node, err := s.store.Orchestration().GetNode(ctx, id)
	if err != nil {
		return err
	}
	for after := int64(0); ; {
		events, err := s.store.Orchestration().ListRunEvents(ctx, node.RunID, after, 1000)
		if err != nil {
			return err
		}
		for _, e := range events {
			if strings.HasSuffix(e.IdempotencyKey, ":"+key) {
				return nil
			}
			after = e.Sequence
		}
		if len(events) < 1000 {
			break
		}
	}
	return s.serviceSignal(ctx, inv, in, key, actor, true)
}
func (s *Server) cancelServiceInvocation(ctx context.Context, id uuid.UUID, timeout bool) error {
	return s.store.WithSessionLock(ctx, "service-invocation:"+id.String(), func(ctx context.Context) error {
		inv, err := s.store.Endpoints().GetInvocation(ctx, id)
		if err != nil {
			return err
		}
		return s.requestServiceCancellation(ctx, inv, timeout)
	})
}
func (s *Server) requestServiceCancellation(ctx context.Context, inv *model.EndpointInvocation, timeout bool) error {
	id := inv.ID
	var err error
	if serviceapi.Terminal(inv.Status) {
		return nil
	}
	inv.Status = model.EndpointInvocationCancelRequested
	if timeout {
		inv.ErrorCode = "endpoint_timeout"
		inv.ErrorMessage = "Service invocation exceeded its deadline"
	}
	if _, err = s.store.Endpoints().UpdateInvocation(ctx, inv); err != nil {
		return err
	}
	if inv.RunID != nil {
		ids, loadErr := s.serviceRunIDs(ctx, *inv.RunID)
		if loadErr != nil {
			return loadErr
		}
		for i := len(ids) - 1; i >= 0; i-- {
			run, loadErr := s.store.Orchestration().GetRun(ctx, ids[i])
			if loadErr != nil {
				return loadErr
			}
			if !model.IsOrchestrationRunTerminal(run.State) {
				if _, err = s.orchestrationService().Cancel(ctx, run.ID); err != nil {
					return err
				}
			}
		}
		return nil
	}
	if inv.ConversationID == nil {
		now := time.Now().UTC()
		inv.Status = model.EndpointInvocationCancelled
		if timeout {
			inv.Status = model.EndpointInvocationTimedOut
		}
		inv.CompletedAt = &now
		_, err = s.store.Endpoints().UpdateInvocation(ctx, inv)
		return err
	}
	ep, err := serviceapi.BoundEndpoint(ctx, s.store, inv)
	if err != nil {
		return err
	}
	conv, sess, err := s.loadEndpointConversationSession(ctx, ep, *inv.ConversationID, inv.PrincipalRef)
	if err != nil {
		return err
	}
	binding, err := s.store.AgentCatalog().GetBinding(ctx, conv.BindingID)
	if err != nil {
		return err
	}
	if binding.Kind == model.DataPlaneManaged && s.product != nil {
		if turn, e := s.nativeServiceTurn(ctx, ep, inv); e == nil {
			_, err = s.product.ManagedServiceRequest(ctx, turn.Owner, inv.SessionID, "POST", "/turns/"+url.PathEscape(turn.ID)+"/cancel", "", map[string]any{})
			return err
		}
		var cfg model.ManagedBindingConfiguration
		if err = json.Unmarshal(binding.Configuration, &cfg); err != nil {
			return err
		}
		return s.product.AbortManagedSession(ctx, conv.SessionID, cfg.OwnerRef)
	}
	if s.sessionOps != nil {
		_, err = s.sessionOps.Execute(ctx, sess, sessionops.Request{Command: sessionops.CommandAbort, Operator: inv.PrincipalRef, Source: "endpoint", CommandID: "invocation-cancel:" + id.String()})
		return err
	}
	if s.asdpCommands != nil {
		return s.asdpCommands.SendSessionCommand(ep.Tenant, ep.Namespace, sess.AgentID.String(), sess.InstanceRef, sess.SessionID, "abort")
	}
	return fmt.Errorf("runtime does not support cancellation")
}

func commandActorPrincipal(actor model.Actor) string {
	if actor.Type == model.ActorHuman {
		return "platform-user:" + actor.Ref
	}
	return actor.Ref
}

// Durable commands retain the submitting actor and recheck revocation before each attempt.
func (s *Server) authorizeServiceCommandActor(ctx context.Context, inv *model.EndpointInvocation, ep *model.Endpoint, cmd *serviceCommand) bool {
	if inv.ApplicationID == nil {
		return cmd.Principal == inv.PrincipalRef
	}
	scope := "interact"
	if cmd.Kind == "cancel" {
		scope = "cancel"
	} else if cmd.Kind == "actions" {
		scope = "approve"
	}
	if cmd.Actor.Type == model.ActorHuman {
		return s.authorizeApplicationActor(ctx, inv, ep, cmd.Principal, scope)
	}
	if cmd.Actor.Type == model.ActorAutomation && strings.HasPrefix(cmd.Actor.Ref, "session-key:") {
		app, err := s.store.Applications().Get(ctx, *inv.ApplicationID)
		if err != nil || app.Status != "active" {
			return false
		}
		row, err := s.store.KV().Get(ctx, ep.Tenant, applicationCredentialPath(app.ID), strings.TrimPrefix(cmd.Actor.Ref, "session-key:"))
		if err != nil {
			return false
		}
		var key sessionCredential
		if json.Unmarshal(row.Value, &key) != nil || key.Status != "active" || key.ExpiresAt != nil && !key.ExpiresAt.After(time.Now()) {
			return false
		}
		if scope == "approve" {
			scope = "interact"
		}
		for _, granted := range key.Scopes {
			if granted == scope {
				return true
			}
		}
		return false
	}
	if cmd.Actor.Type != model.ActorAutomation || !strings.HasPrefix(cmd.Actor.Ref, "api-key:") {
		return false
	}
	app, err := s.store.Applications().Get(ctx, *inv.ApplicationID)
	if err != nil || app.Status != "active" || app.Tenant != ep.Tenant || app.Namespace != ep.Namespace {
		return false
	}
	credentials, err := s.store.Endpoints().ListCredentials(ctx, ep.ID)
	if err != nil {
		return false
	}
	for _, credential := range credentials {
		if "api-key:"+credential.ID.String() != cmd.Actor.Ref || credential.ApplicationID != app.ID || credential.Status != model.EndpointCredentialActive || credential.ExpiresAt != nil && !credential.ExpiresAt.After(time.Now()) {
			continue
		}
		scopes, err := validateEndpointScopes(credential.Scopes)
		if err != nil {
			return false
		}
		if scope == "approve" {
			scope = "interact"
		}
		for _, allowed := range scopes {
			if allowed == scope {
				return true
			}
		}
	}
	return false
}

func publicCommandBase(inv *model.EndpointInvocation) string {
	if c, err := serviceapi.ReadContract(inv.Contract); err == nil && c.PublicSessionID != uuid.Nil {
		return "/api/v1/agent-sessions/" + c.PublicSessionID.String() + "/turns/" + inv.ID.String()
	}
	return "/invoke/v1/invocations/" + inv.ID.String()
}

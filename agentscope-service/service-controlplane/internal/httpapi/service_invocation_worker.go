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
	"sync"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/collaboration"
	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/orchestration"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/product"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

func (s *Server) endpointSubmissionContract(ctx context.Context, ep *model.Endpoint, conversationID *uuid.UUID) (*model.Endpoint, json.RawMessage, error) {
	releaseID := ep.ActiveReleaseID
	var raw json.RawMessage
	if conversationID != nil && *conversationID != uuid.Nil {
		c, err := s.store.Endpoints().GetConversation(ctx, *conversationID)
		if err != nil {
			return nil, nil, err
		}
		if c.EndpointID != ep.ID {
			return nil, nil, store.ErrNotFound
		}
		releaseID, raw = c.ReleaseID, c.Contract
	}
	if len(raw) == 0 && releaseID != nil {
		release, err := s.store.Endpoints().GetRelease(ctx, ep.ID, *releaseID)
		if err != nil {
			return nil, nil, err
		}
		raw = release.Contract
	}
	if len(raw) == 0 {
		return nil, nil, fmt.Errorf("Endpoint has no published service contract")
	}
	contract, err := serviceapi.ReadContract(raw)
	if err != nil {
		return nil, nil, err
	}
	contract.Endpoint.ActiveReleaseID = releaseID
	return &contract.Endpoint, raw, nil
}

// The reservation is the durable work queue. Deterministic child identities and
// a cross-replica lock make every materialization step safe to repeat.
func (s *Server) dispatchEndpointInvocation(ctx context.Context, id uuid.UUID) error {
	return s.store.WithSessionLock(ctx, "service-invocation:"+id.String(), func(ctx context.Context) error {
		inv, err := s.store.Endpoints().GetInvocation(ctx, id)
		if err != nil {
			return err
		}
		if inv.Mode != model.EndpointJobMode || serviceapi.Terminal(inv.Status) || inv.Status == model.EndpointInvocationCancelRequested {
			return nil
		}
		if inv.RunID != nil && inv.Status != model.EndpointInvocationAccepted && inv.Status != model.EndpointInvocationDispatching {
			if _, err = s.store.Orchestration().GetRun(ctx, *inv.RunID); err == nil {
				return nil
			}
		}
		contract, err := serviceapi.ReadContract(inv.Contract)
		if err != nil {
			return err
		}
		ctx = serviceapi.WithContract(ctx, contract)
		ep, err := serviceapi.BoundEndpoint(ctx, s.store, inv)
		if err != nil {
			return err
		}
		if ep.TimeoutSeconds > 0 && time.Now().After(inv.CreatedAt.Add(time.Duration(ep.TimeoutSeconds)*time.Second)) {
			return s.expireServiceInvocation(ctx, inv)
		}
		var req struct {
			Title       string          `json:"title"`
			Description string          `json:"description"`
			Input       json.RawMessage `json:"input"`
			Message     string          `json:"message"`
		}
		if err = json.Unmarshal(inv.Input, &req); err != nil {
			return err
		}
		if contract.PublicSessionID != uuid.Nil {
			if req.Title == "" {
				req.Title = "Session task"
			}
			if req.Description == "" {
				req.Description = req.Message
			}
			if len(req.Input) == 0 {
				req.Input, _ = json.Marshal(req.Message)
			}
		}
		sourceType := "endpoint"
		if contract.PublicSessionID != uuid.Nil {
			sourceType = "session_turn"
		}
		issueID := uuid.NewSHA1(inv.ID, []byte("issue"))
		actor := model.Actor{Type: model.ActorSystem, Ref: "endpoint:" + ep.ID.String()}
		issue, err := s.store.Collaboration().GetIssue(ctx, issueID)
		if errors.Is(err, store.ErrNotFound) {
			issue, err = s.store.Collaboration().CreateIssue(ctx, &model.Issue{ID: issueID, Tenant: ep.Tenant, Namespace: ep.Namespace,
				Title: req.Title, Description: collaboration.EndpointIssueDescription(req.Description, req.Input), Status: model.IssueInProgress, Priority: "normal",
				Kind: model.IssueKindEndpointJob, Visibility: model.IssueVisibilityOperational, CompletionPolicy: model.IssueCompletionAutomatic, Creator: actor,
				SourceType: sourceType, SourceRef: inv.ID.String(), ExecutionTargetType: string(ep.TargetType), ExecutionTargetRef: ep.TargetRef.String()})
			if errors.Is(err, store.ErrConflict) {
				issue, err = s.store.Collaboration().GetIssue(ctx, issueID)
			}
		}
		if err != nil {
			return err
		}
		if err = s.attachPublicSessionFiles(ctx, inv, issueID); err != nil {
			return err
		}
		kind := model.AssigneeType("")
		if ep.TargetType == model.EndpointTargetAgent {
			kind = model.AssigneeAgent
		}
		if ep.TargetType == model.EndpointTargetTeam {
			kind = model.AssigneeTeam
		}
		if kind != "" && (issue.AssigneeType != kind || issue.AssigneeRef != ep.TargetRef.String()) {
			issue.AssigneeType, issue.AssigneeRef = kind, ep.TargetRef.String()
			issue, err = s.store.Collaboration().UpdateIssue(ctx, issue, issue.Version, actor)
			if err != nil {
				return err
			}
		}
		var run *model.OrchestrationRun
		if ep.TargetType == model.EndpointTargetOrchestrationRevision {
			revision, err := serviceapi.Revision(ctx, s.store, issueID, ep.TargetRef)
			if err != nil {
				return err
			}
			run, err = s.orchestrationService().Start(ctx, revision.DefinitionID, orchestration.StartRequest{RevisionID: &revision.ID,
				IdempotencyKey: "endpoint-invocation:" + inv.ID.String(), Input: req.Input, IssueID: &issueID, TriggerType: "endpoint", TriggerRef: ep.ID.String(), Actor: actor})
			if err != nil {
				return err
			}
		} else {
			runID := uuid.NewSHA1(inv.ID, []byte("run"))
			// Save the association before materialization; a restart can repair the missing Run.
			inv.IssueID, inv.RunID = &issueID, &runID
			if _, err = s.store.Endpoints().UpdateInvocation(ctx, inv); err != nil {
				return err
			}
			run, err = s.store.Orchestration().GetRun(ctx, runID)
			if errors.Is(err, store.ErrNotFound) {
				mode := model.RunModeDirect
				if ep.TargetType == model.EndpointTargetTeam {
					mode = model.RunModeAdaptive
				}
				run, err = s.store.Orchestration().CreateRun(ctx, &model.OrchestrationRun{ID: runID, Tenant: ep.Tenant, Namespace: ep.Namespace, RootIssueID: issueID,
					Mode: mode, TriggerType: "endpoint", TriggerRef: ep.ID.String(), IdempotencyKey: "endpoint-invocation:" + inv.ID.String(), Input: req.Input, State: model.RunRunning, CreatedBy: actor})
			}
			if err != nil {
				return err
			}
			if ep.TargetType == model.EndpointTargetTeam && len(inv.Contract) > 0 {
				frozen, err := serviceapi.ReadContract(inv.Contract)
				if err != nil {
					return err
				}
				team := frozen.Teams[ep.TargetRef.String()]
				if team == nil {
					return fmt.Errorf("published Team snapshot is missing")
				}
				_, _, err = orchestration.MaterializeTeamCoordinator(ctx, s.store, orchestration.MaterializeTeamRequest{Run: run, IssueID: issueID, Team: team,
					NodeID: uuid.NewSHA1(run.ID, []byte("target")), NodeKey: "target", Actor: actor})
				if err != nil {
					return err
				}
				err = (&orchestration.Engine{Store: s.store}).ReconcileRun(ctx, run.ID)
				if err != nil {
					return err
				}
			} else if err = s.materializeEndpointTarget(ctx, ep, run, issueID, actor); err != nil {
				return err
			}
		}
		inv.IssueID, inv.RunID = &issueID, &run.ID
		inv.Status = model.EndpointInvocationRunning
		if inv.StartedAt == nil {
			now := time.Now().UTC()
			inv.StartedAt = &now
		}
		_, err = s.store.Endpoints().UpdateInvocation(ctx, inv)
		return err
	})
}

func (s *Server) expireServiceInvocation(ctx context.Context, inv *model.EndpointInvocation) error {
	return s.requestServiceCancellation(ctx, inv, true)
}

// SweepServiceInvocations runs independently of readers and covers accepted
// reservations that have not created their internal execution yet.
func (s *Server) SweepServiceInvocations(ctx context.Context) error {
	cutoff := time.Now().UTC()
	for {
		invs, err := s.store.Endpoints().ListInvocations(ctx, store.EndpointInvocationFilter{Limit: 100, DueBefore: &cutoff, OldestFirst: true})
		if err != nil {
			return err
		}

		var workers sync.WaitGroup
		failures := make(chan error, 2*len(invs))
		slots := make(chan struct{}, 4)
		for _, inv := range invs {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			slots <- struct{}{}
			workers.Add(1)
			go func(inv *model.EndpointInvocation) {
				defer workers.Done()
				defer func() { <-slots }()
				lease := time.Now().UTC().Add(time.Minute).Truncate(time.Microsecond)
				claimed, err := s.store.Endpoints().ClaimInvocationPoll(ctx, inv.ID, inv.NextPollAt, lease)
				if err != nil {
					failures <- err
					return
				}
				if !claimed {
					return
				}
				next := time.Now().UTC().Add(5 * time.Second)
				if err = s.sweepServiceInvocation(ctx, inv); err != nil {
					failures <- fmt.Errorf("invocation %s: %w", inv.ID, err)
				} else if next, err = s.nextServicePoll(ctx, inv.ID); err != nil {
					failures <- err
					next = time.Now().UTC().Add(5 * time.Second)
				}
				if _, err = s.store.Endpoints().ClaimInvocationPoll(ctx, inv.ID, lease, next); err != nil {
					failures <- err
				}
			}(inv)
		}
		workers.Wait()
		close(failures)
		var firstFailure error
		for err := range failures {
			if firstFailure == nil {
				firstFailure = err
			}
			gin.DefaultErrorWriter.Write([]byte(err.Error() + "\n"))
		}
		if firstFailure != nil {
			return firstFailure
		}

		if len(invs) < 100 {
			return nil
		}
	}
}
func (s *Server) runServiceInvocations(ctx context.Context) {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := s.SweepServiceInvocations(ctx); err != nil && ctx.Err() == nil {
				gin.DefaultErrorWriter.Write([]byte("service invocation sweep: " + err.Error() + "\n"))
			}
		}
	}
}

func (s *Server) freezeServiceContract(ctx context.Context, ep *model.Endpoint) (json.RawMessage, error) {
	raw, err := serviceapi.Freeze(ctx, s.store, ep)
	if err != nil {
		return nil, err
	}
	contract, err := serviceapi.ReadContract(raw)
	if err != nil {
		return nil, err
	}
	for _, id := range contract.Agents {
		agentID, err := uuid.Parse(id)
		if err != nil {
			return nil, err
		}
		var definition json.RawMessage
		if s.taskPlane != nil && s.taskPlane.ResolveDefinition != nil {
			definition, err = s.taskPlane.ResolveDefinition(ctx, agentID)
		} else if s.product != nil {
			if policy := contract.Policies[id]; policy != nil {
				for _, candidate := range policy.Candidates {
					if candidate.Binding.Kind != model.DataPlaneManaged {
						continue
					}
					binding, e := s.store.AgentCatalog().GetBinding(ctx, candidate.Binding.BindingID)
					if e != nil {
						return nil, e
					}
					var config model.ManagedBindingConfiguration
					if e = json.Unmarshal(binding.Configuration, &config); e != nil {
						return nil, e
					}
					snapshot, e := s.product.RuntimeDefinition(ctx, config.OwnerRef, config.ManagedDefinitionRef)
					if e != nil {
						return nil, e
					}
					definition, err = json.Marshal(snapshot)
					break
				}
			}
		}
		if err != nil {
			return nil, err
		}
		if len(definition) > 0 {
			contract.Definitions[id] = definition
		}
	}
	return json.Marshal(contract)
}

func (s *Server) sweepServiceInvocation(ctx context.Context, inv *model.EndpointInvocation) error {
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	var err error
	ep, loadErr := serviceapi.BoundEndpoint(ctx, s.store, inv)
	if loadErr != nil {
		return loadErr
	}
	if err = s.processServiceCommands(ctx, inv, ep); err != nil {
		return err
	}
	inv, err = s.store.Endpoints().GetInvocation(ctx, inv.ID)
	if err != nil {
		return err
	}
	if !serviceapi.Terminal(inv.Status) {
		if frozen, e := serviceapi.ReadContract(inv.Contract); e == nil && frozen.PublicSessionID != uuid.Nil && inv.Status == model.EndpointInvocationAccepted {
			pending, e := s.store.Endpoints().ListInvocations(ctx, store.EndpointInvocationFilter{EndpointID: inv.EndpointID, ActiveOnly: true, OldestFirst: true, Limit: 1})
			if e != nil {
				return e
			}
			if len(pending) > 0 && pending[0].ID != inv.ID {
				return s.projectServiceInvocation(ctx, inv.ID)
			}
		}
		if inv.Mode == model.EndpointConversationMode {
			err = s.dispatchServiceConversation(ctx, inv.ID)
		} else {
			err = s.dispatchEndpointInvocation(ctx, inv.ID)
		}
		if err != nil {
			var rejected *product.ManagedServiceError
			if errors.As(err, &rejected) && (rejected.StatusCode == 400 || rejected.StatusCode == 413 || rejected.StatusCode == 422) {
				inv, err = s.store.Endpoints().GetInvocation(ctx, inv.ID)
				if err != nil {
					return err
				}
				inv.Status = model.EndpointInvocationFailed
				inv.ErrorCode = "invalid_runtime_input"
				inv.ErrorMessage = rejected.Message
				now := time.Now().UTC()
				inv.CompletedAt = &now
				if _, err = s.store.Endpoints().UpdateInvocation(ctx, inv); err != nil {
					return err
				}
			} else {
				return err
			}
		}

		if inv, err = s.refreshServiceInvocation(ctx, inv.ID); err != nil {
			return err
		}
	}
	// Projection and delivery also run for completed work and commands.
	if err = s.projectServiceInvocation(ctx, inv.ID); err != nil {
		return err
	}
	if err = s.enforceServiceBudget(ctx, inv, ep); err != nil {
		return err
	}
	if err = s.deliverServiceWebhooks(ctx, inv, ep); err != nil {
		return err
	}
	return nil
}

// Terminal work is parked until a webhook retry or its retention deadline.
// Accepting a command or webhook wakes it explicitly; CAS preserves that wake.
func (s *Server) nextServicePoll(ctx context.Context, id uuid.UUID) (time.Time, error) {
	now := time.Now().UTC()
	inv, err := s.store.Endpoints().GetInvocation(ctx, id)
	if err != nil {
		return now, err
	}
	if !serviceapi.Terminal(inv.Status) {
		return now.Add(2 * time.Second), nil
	}
	ep, err := serviceapi.BoundEndpoint(ctx, s.store, inv)
	if err != nil {
		return now, err
	}
	next := time.Date(9999, 1, 1, 0, 0, 0, 0, time.UTC)
	pending := false
	// A command accepted before completion may still await a transient-failure retry.
	for offset := 0; ; offset += 100 {
		rows, err := s.store.KV().Search(ctx, ep.Tenant, serviceCommandPath(inv.ID), 100, offset)
		if err != nil {
			return now, err
		}
		for _, row := range rows {
			command, err := decodeServiceCommand(row.Value)
			if err != nil {
				return now, err
			}
			if command.Status != "accepted" {
				continue
			}
			pending = true
			due := command.NextAttempt
			if due.Before(now) {
				due = now.Add(2 * time.Second)
			}
			if due.Before(next) {
				next = due
			}
		}
		if len(rows) < 100 {
			break
		}
	}
	for offset := 0; ; offset += 100 {
		rows, err := s.store.KV().Search(ctx, ep.Tenant, webhookPath(inv.ID), 100, offset)
		if err != nil {
			return now, err
		}
		for _, row := range rows {
			var w serviceWebhook
			if err = json.Unmarshal(row.Value, &w); err != nil {
				return now, err
			}
			if w.Status != "active" {
				continue
			}
			events, _, _, err := serviceJournal(s.store, ep, inv).Events(ctx, w.Cursor, 1)
			if errors.Is(err, serviceapi.ErrCursorExpired) {
				continue
			}
			if err != nil {
				return now, err
			}
			if len(events) > 0 {
				pending = true
				due := w.NextAttempt
				if due.Before(now) {
					due = now.Add(2 * time.Second)
				}
				if due.Before(next) {
					next = due
				}
			}
		}
		if len(rows) < 100 {
			break
		}
	}
	if s.serviceEventRetention > 0 && inv.CompletedAt != nil && !pending {
		expires := inv.CompletedAt.Add(s.serviceEventRetention)
		if now.Before(expires) {
			if expires.Before(next) {
				next = expires
			}
		} else {
			frozen, err := serviceapi.ReadContract(inv.Contract)
			if err != nil {
				return now, err
			}
			if frozen.PublicSessionID != uuid.Nil {
				session, e := sessionapi.Get(ctx, s.store, ep.Tenant, frozen.PublicSessionID)
				if e != nil {
					return now, e
				}
				// Commit the aggregate before pruning the source Turn journal.
				if e = s.projectPublicSession(ctx, session); e != nil {
					return now, e
				}
			}
			done, err := serviceJournal(s.store, ep, inv).PruneEvents(ctx)
			if err != nil {
				return now, err
			}
			if !done {
				return now.Add(2 * time.Second), nil
			}
		}
	}
	return next, nil
}

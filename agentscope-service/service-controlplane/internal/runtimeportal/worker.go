// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package runtimeportal

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
	"github.com/google/uuid"
)

type Progress struct {
	ControlAnswerIndex *int
	ControlComplete    bool
	ObservationOnly    bool
	StopRequested      bool
	Status             string
	NativeStarted      bool
	Cursor             string
	ReportedTokens     int64
	Facts              []runtimeprotocol.RuntimeFact
}

// NativeBackend advances deterministic, durable execution identities by a
// bounded step. Step must never blindly replay an uncertain provider side effect.
// Stop returns a terminal status only after physical execution has stopped or
// after proving the native identity was never materialized. A terminal Step
// also proves that final items, usage and actions have been drained into Facts.
type NativeBackend interface {
	Step(context.Context, *Session, Turn) (Progress, error)
	// Observe may read committed progress, but cannot dispatch or resume work.
	Observe(context.Context, *Session, Turn) (Progress, error)
	Stop(context.Context, *Session, Turn) (Progress, error)
}

type Worker struct {
	Repository  Repository
	Client      *Client
	Backend     NativeBackend
	Slots       int
	ReportError func(error)
}

func (w *Worker) report(err error) {
	if err != nil && w.ReportError != nil {
		w.ReportError(err)
	}
}

// Start has finite prefetch equal to currently free local slots. Each claimed
// Turn is serial within its Session; another replica takes over only after the
// shared claim expires. Authorization and fact delivery use independent loops.
func (w *Worker) Start(ctx context.Context) error {
	if w.Repository == nil || w.Client == nil || w.Backend == nil || w.Slots < 1 || w.Slots > 128 {
		return fmt.Errorf("invalid Portal runtime worker configuration")
	}
	var active atomic.Int32
	var group sync.WaitGroup
	ticker := time.NewTicker(200 * time.Millisecond)
	defer ticker.Stop()
	group.Add(1)
	go func() { defer group.Done(); w.deliverLoop(ctx) }()
	defer group.Wait()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			free := w.Slots - int(active.Load())
			if free < 1 {
				continue
			}
			claimCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
			rows, err := w.Repository.ClaimTurns(claimCtx, time.Now().UTC(), 45*time.Second, free)
			cancel()
			if err != nil {
				w.report(err)
				continue
			}
			for _, row := range rows {
				active.Add(1)
				group.Add(1)
				go func(turn Turn) {
					defer group.Done()
					defer active.Add(-1)
					stepCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
					defer cancel()
					if err := w.Advance(stepCtx, turn); err != nil {
						w.report(err)
					}
				}(row)
			}
		}
	}
}

func (w *Worker) Advance(ctx context.Context, row Turn) error {
	session, err := w.Repository.Session(ctx, row.Command.Context.SessionID)
	if err != nil {
		return err
	}
	if row.StopRequested {
		return w.stop(ctx, session, row)
	}
	if row.Generation == 0 {
		row.Generation = 1
		row.Fence = uuid.NewString()
		row.ActivationCommandID = row.Command.Context.CommandID
		// Public Turn IDs are hashes. Native Invocation UUIDs are stable adapters,
		// never replacements for the public identity or bindings to a replica.
		row.NativeInvocationID = uuid.NewSHA1(uuid.NameSpaceURL, []byte("portal:"+row.Command.Context.Tenant+":"+row.Command.Context.SessionID+":"+row.Command.Context.CommandID)).String()
		// Allocate the fence before the remote grant. An ACK-loss retry must ask
		// for the same generation/fence, including after this process exits.
		if err = w.Repository.SaveTurn(ctx, row, nil, time.Now().UTC(), false); err != nil {
			return err
		}
	}
	// Completed native work and final fact drainage do not start another model
	// call. Observe them before renewing: a budget consumed by the final call
	// must not turn successful work into an authorization interruption.
	if row.NativeStarted && row.ControlCommandID == "" {
		observed, observeErr := w.Backend.Observe(ctx, session, row)
		if observeErr != nil {
			return w.retry(ctx, row, observeErr)
		}
		if Terminal(observed.Status) || observed.Status == "requires_action" || observed.ObservationOnly || observed.StopRequested {
			return w.commit(ctx, row, observed)
		}
	}
	// A suspended native task needs observation but no permission to start a
	// model call. Any subsequent executable step still requires a fresh grant.
	if row.Status != "requires_action" {
		grant, grantErr := w.Client.Grant(ctx, row, row.GrantUntil > 0)
		if grantErr != nil {
			var rejection *ResponseError
			if errors.As(grantErr, &rejection) && (rejection.Status == 403 || rejection.Status == 404 || rejection.Status == 409 || rejection.Status == 429) {
				row.StopRequested = true
				if err = w.Repository.SaveTurn(ctx, row, nil, time.Now().UTC(), false); err != nil {
					return err
				}
				return w.stop(ctx, session, row)
			}
			return w.retry(ctx, row, grantErr)
		}
		row.GrantUntil = grant.ValidUntil
		row.ObservationCommandID, row.ObservationGeneration, row.ObservationFence = row.ActivationCommandID, row.Generation, row.Fence
		if err = w.Repository.SaveTurn(ctx, row, nil, time.Now().UTC(), false); err != nil {
			return err
		}
	}
	var progress Progress
	if row.Status == "requires_action" {
		progress, err = w.Backend.Observe(ctx, session, row)
	} else {
		progress, err = w.Backend.Step(ctx, session, row)
	}
	if err != nil {
		if errors.Is(err, ErrPermission) && row.ControlCommandID != "" {
			// A replaced task cannot apply its old control. Drain native work
			// before publishing interruption rather than retrying forever.
			row.StopRequested = true
			if saveErr := w.Repository.SaveTurn(ctx, row, nil, time.Now().UTC(), false); saveErr != nil {
				return saveErr
			}
			return w.stop(ctx, session, row)
		}
		return w.retry(ctx, row, err)
	}
	return w.commit(ctx, row, progress)
}

func (w *Worker) stop(ctx context.Context, session *Session, row Turn) error {
	progress, err := w.Backend.Stop(ctx, session, row)
	if err != nil {
		return w.retry(ctx, row, err)
	}
	if Terminal(progress.Status) {
		// Stop proves physical completion even when the next control activation
		// was cancelled or refused before receiving a grant. Only that terminal
		// receipt may settle the current public activation without a new grant.
		for index := range progress.Facts {
			if Terminal(strings.TrimPrefix(progress.Facts[index].Type, "turn.")) {
				if progress.Facts[index].Payload == nil {
					progress.Facts[index].Payload = map[string]any{}
				}
				progress.Facts[index].Payload["native_stopped"] = true
				if row.StopCommandID != "" {
					progress.Facts[index].Payload["cancel_command_id"] = row.StopCommandID
				}
				if row.GrantUntil == 0 && row.ControlCommandID != "" {
					progress.Facts[index].Payload["control_stop"] = true
				}
			}
		}
		started := row.NativeStarted || progress.NativeStarted
		// Completion may win the race with revocation/cancellation. Preserve its
		// final items and usage instead of changing a known outcome to interrupted.
		if progress.Status == "completed" || progress.Status == "failed" {
			return w.commit(ctx, row, progress)
		}
		reason, eventID := "execution_authorization_rejected", "authorization-stop:"+row.ActivationCommandID
		if row.StopCommandID != "" {
			progress.Status, reason, eventID = "cancelled", "cancel_requested", "cancellation-stop:"+row.StopCommandID
		} else if !started {
			progress.Status = "failed"
		} else {
			progress.Status = "interrupted"
		}
		facts := progress.Facts[:0]
		for _, fact := range progress.Facts {
			if !Terminal(strings.TrimPrefix(fact.Type, "turn.")) {
				facts = append(facts, fact)
			}
		}
		progress.Facts = append(facts, runtimeprotocol.RuntimeFact{EventID: eventID,
			Type: "turn." + progress.Status, Payload: map[string]any{"reason": reason, "native_started": started, "cancel_command_id": row.StopCommandID,
				"control_stop": row.GrantUntil == 0 && row.ControlCommandID != "", "native_stopped": true}})
	} else if row.StopCommandID != "" {
		progress.Status = "cancel_requested"
	}
	return w.commit(ctx, row, progress)
}

func (w *Worker) retry(ctx context.Context, row Turn, cause error) error {
	row.NextAttempt = time.Now().UTC().Add(2 * time.Second)
	if err := w.Repository.SaveTurn(ctx, row, nil, time.Now().UTC(), true); err != nil {
		return err
	}
	return cause
}

func (w *Worker) commit(ctx context.Context, row Turn, progress Progress) error {
	switch progress.Status {
	case "queued", "running", "requires_action", "cancel_requested", "completed", "failed", "interrupted", "cancelled":
	default:
		return fmt.Errorf("native progress has an unsupported status")
	}
	row.Status = progress.Status
	row.NativeStarted = row.NativeStarted || progress.NativeStarted
	row.StopRequested = row.StopRequested || progress.StopRequested
	if progress.Cursor != "" {
		row.NativeCursor = progress.Cursor
	}
	row.ReportedTokens = max(row.ReportedTokens, progress.ReportedTokens)
	if progress.ControlAnswerIndex != nil {
		row.ControlAnswerIndex = *progress.ControlAnswerIndex
	}
	if progress.ControlComplete {
		row.ControlCommandID, row.ControlAnswerIndex = "", 0
	}
	row.NextAttempt = time.Now().UTC().Add(time.Second)
	for index := range progress.Facts {
		fact := &progress.Facts[index]
		fact.Context = row.Command.Context
		fact.Source = "orchestration"
		fact.SourceSequence = 0
		if fact.Payload == nil {
			fact.Payload = map[string]any{}
		}
		fact.Payload["turn_id"] = *row.Command.Context.TurnID
		if fact.Type == "required_action.resolved" && fact.ExecutionID != nil && fact.ExecutionFence != nil {
			continue
		}
		fact.Payload["activation_command_id"] = row.ActivationCommandID
		if row.GrantUntil > 0 {
			generation := strconv.FormatInt(row.Generation, 10)
			fence := row.Fence
			fact.ExecutionID = &generation
			fact.ExecutionFence = &fence
		} else {
			fact.ExecutionID = nil
			fact.ExecutionFence = nil
			if fact.Payload["control_stop"] != true && row.ObservationGeneration > 0 {
				generation, fence := strconv.FormatInt(row.ObservationGeneration, 10), row.ObservationFence
				fact.ExecutionID, fact.ExecutionFence = &generation, &fence
				fact.Payload["activation_command_id"] = row.ObservationCommandID
			}
		}
	}
	return w.Repository.SaveTurn(ctx, row, progress.Facts, time.Now().UTC(), true)
}

func (w *Worker) deliverLoop(ctx context.Context) {
	ticker := time.NewTicker(200 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			// A busy Session can drain several contiguous positions in this pass,
			// while the whole pass still has a strict upper bound.
			for delivered := 0; delivered < 128 && ctx.Err() == nil; {
				claimCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
				rows, err := w.Repository.ClaimFacts(claimCtx, time.Now().UTC(), 30*time.Second, min(8, 128-delivered))
				cancel()
				if err != nil {
					w.report(err)
					break
				}
				if len(rows) == 0 {
					break
				}
				var group sync.WaitGroup
				for _, row := range rows {
					group.Add(1)
					go func(fact Fact) {
						defer group.Done()
						if err := w.Client.DeliverFact(ctx, fact.Value); err != nil {
							w.report(err)
							return
						}
						ackCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
						defer cancel()
						w.report(w.Repository.AcknowledgeFact(ackCtx, fact.Value.Context.SessionID, fact.Value.SourceSequence, fact.ClaimToken, time.Now().UTC()))
					}(row)
				}
				group.Wait()
				delivered += len(rows)
			}
		}
	}
}

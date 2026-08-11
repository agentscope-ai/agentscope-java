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

package sessionops

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// Router dispatches session control commands with capability/busy gates,
// per-session serialization (store advisory lock across replicas), optional
// queue-until-idle, transport selection, post-refresh, and audit.
type Router struct {
	Registry *dataplane.Registry
	Store    store.Store
	Prober   prober.DataPlaneProber
	ASDP     ASDPSender
	HTTP     *http.Client

	locks *keyedMutex // fallback when Store is nil
}

// NewRouter constructs a Session Command Router.
func NewRouter(registry *dataplane.Registry, st store.Store, p prober.DataPlaneProber, asdp ASDPSender) *Router {
	return &Router{
		Registry: registry,
		Store:    st,
		Prober:   p,
		ASDP:     asdp,
		HTTP:     &http.Client{},
		locks:    newKeyedMutex(),
	}
}

// Execute runs the full command pipeline for a session.
func (r *Router) Execute(ctx context.Context, sess *store.Session, req Request) (*Result, error) {
	if sess == nil {
		return nil, errNotFound("session is nil")
	}
	cmd := strings.ToLower(strings.TrimSpace(req.Command))
	if cmd == "" {
		return nil, errUnsupported("command is required")
	}
	req.Command = cmd

	// Idempotency: return cached audit row when X-Command-Id already exists.
	if req.CommandID != "" && r.Store != nil {
		if prev, err := r.Store.Commands().GetByCommandID(ctx, req.CommandID); err == nil && prev != nil {
			return cachedOutcome(prev)
		}
	}

	entry, errUnreach := checkInstanceReachable(r.Registry, sess)
	if errUnreach != nil {
		r.auditRejected(ctx, sess, req, false, errUnreach)
		return nil, errUnreach
	}

	if errCap := checkCapability(entry, cmd); errCap != nil {
		r.auditRejected(ctx, sess, req, false, errCap)
		return nil, errCap
	}

	forced, errGate := checkGate(sess, cmd, req.Force)
	if errGate != nil {
		// Mid-turn / compressing on idle-required command → queue by default.
		if errGate.Code == CodeBusy && errGate.Hint == HintWaitIdle &&
			isIdleRequired(cmd) && phaseIsActive(sess) && shouldQueue(req) {
			return r.enqueue(ctx, sess, req)
		}
		r.auditRejected(ctx, sess, req, forced, errGate)
		return nil, errGate
	}

	var result *Result
	var execErr error
	lockErr := r.withLock(ctx, sess.ID.String(), func(ctx context.Context) error {
		// Re-resolve under lock in case affinity changed.
		entry, errUnreach = checkInstanceReachable(r.Registry, sess)
		if errUnreach != nil {
			execErr = errUnreach
			return nil
		}
		if entry.InstanceID != "" && entry.InstanceID != sess.InstanceRef {
			sess.InstanceRef = entry.InstanceID
		}
		result, execErr = r.executeLocked(ctx, sess, entry, req, forced)
		return nil
	})
	if lockErr != nil {
		return nil, errFailed("session lock: " + lockErr.Error())
	}
	return result, execErr
}

// ExecuteQueued re-runs a previously queued command once the session is idle.
// It does not re-queue on busy (returns busy error for the worker to retry).
func (r *Router) ExecuteQueued(ctx context.Context, sess *store.Session, queued *store.SessionCommand) (*Result, error) {
	if sess == nil || queued == nil {
		return nil, errNotFound("session or queued command missing")
	}
	noQueue := false
	req := Request{
		Command:   queued.Command,
		Operator:  queued.Operator,
		Source:    firstNonEmpty(queued.Source, "queue"),
		Force:     queued.Forced,
		Queue:     &noQueue,
		CommandID: queued.CommandID,
	}

	entry, errUnreach := checkInstanceReachable(r.Registry, sess)
	if errUnreach != nil {
		return nil, errUnreach
	}
	if errCap := checkCapability(entry, req.Command); errCap != nil {
		return nil, errCap
	}
	forced, errGate := checkGate(sess, req.Command, req.Force)
	if errGate != nil {
		return nil, errGate
	}

	var result *Result
	var execErr error
	lockErr := r.withLock(ctx, sess.ID.String(), func(ctx context.Context) error {
		// Promote queued row to accepted then dispatch.
		if r.Store != nil {
			queued.Status = store.CommandStatusAccepted
			_ = r.Store.Commands().Update(ctx, queued)
		}
		result, execErr = r.dispatch(ctx, sess, entry, req, forced, queued)
		return nil
	})
	if lockErr != nil {
		return nil, errFailed("session lock: " + lockErr.Error())
	}
	return result, execErr
}

func (r *Router) withLock(ctx context.Context, key string, fn func(context.Context) error) error {
	if r.Store != nil {
		return r.Store.WithSessionLock(ctx, key, fn)
	}
	unlock := r.locks.Lock(key)
	defer unlock()
	return fn(ctx)
}

func (r *Router) executeLocked(ctx context.Context, sess *store.Session, entry *dataplane.Entry, req Request, forced bool) (*Result, error) {
	// Re-check idempotency under the lock.
	if req.CommandID != "" && r.Store != nil {
		if prev, err := r.Store.Commands().GetByCommandID(ctx, req.CommandID); err == nil && prev != nil {
			return cachedOutcome(prev)
		}
	}

	commandID := req.CommandID
	if commandID == "" {
		commandID = "cmd-" + uuid.NewString()[:8]
	}

	audit := &store.SessionCommand{
		SessionFK:   &sess.ID,
		AgentName:   sess.AgentName,
		Namespace:   sess.Namespace,
		SessionID:   sess.SessionID,
		Command:     req.Command,
		Operator:    req.Operator,
		Source:      firstNonEmpty(req.Source, "http"),
		InstanceRef: sess.InstanceRef,
		Status:      store.CommandStatusAccepted,
		Forced:      forced,
		CommandID:   commandID,
		RequestedAt: time.Now().UTC(),
	}
	if r.Store != nil {
		_ = r.Store.Commands().Insert(ctx, audit)
	}
	return r.dispatch(ctx, sess, entry, req, forced, audit)
}

func (r *Router) dispatch(ctx context.Context, sess *store.Session, entry *dataplane.Entry, req Request, forced bool, audit *store.SessionCommand) (*Result, error) {
	commandID := audit.CommandID
	timeout := commandTimeout(req.Command)
	cmdCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	started := time.Now()
	var (
		dpResp *dpCommandResponse
		opErr  *Error
	)

	usedASDP := false
	if r.ASDP != nil && sess.InstanceRef != "" {
		if err := r.ASDP.SendSessionCommand(sess.Namespace, sess.InstanceRef, sess.SessionID, req.Command); err == nil {
			usedASDP = true
			dpResp = &dpCommandResponse{
				Accepted:  true,
				CommandID: commandID,
				Phase:     expectedPhase(req.Command, sess.Phase),
				Result:    json.RawMessage(`{}`),
			}
		}
	}
	if !usedASDP {
		dpResp, opErr = sendHTTP(cmdCtx, r.HTTP, entry.BaseURL, sess.SessionID, req.Command, commandID)
	}

	completed := time.Now().UTC()
	durationMs := completed.Sub(started).Milliseconds()

	if opErr != nil {
		r.finishAudit(ctx, audit, store.CommandStatusFailed, opErr.Code, opErr.Msg, &completed, durationMs)
		return nil, opErr
	}

	phase := firstNonEmpty(dpResp.Phase, expectedPhase(req.Command, sess.Phase))
	r.finishAudit(ctx, audit, store.CommandStatusSucceeded, "", "", &completed, durationMs)
	r.postRefresh(ctx, entry.BaseURL, sess, phase)

	resultBody := dpResp.Result
	if len(resultBody) == 0 {
		resultBody = json.RawMessage(`{}`)
	}
	return &Result{
		Accepted:  true,
		CommandID: firstNonEmpty(dpResp.CommandID, commandID),
		Phase:     phase,
		Result:    resultBody,
		Forced:    forced,
		Audit:     audit,
	}, nil
}

func (r *Router) enqueue(ctx context.Context, sess *store.Session, req Request) (*Result, error) {
	commandID := req.CommandID
	if commandID == "" {
		commandID = "cmd-" + uuid.NewString()[:8]
	}
	if r.Store != nil {
		if prev, err := r.Store.Commands().GetByCommandID(ctx, commandID); err == nil && prev != nil {
			return cachedOutcome(prev)
		}
	}
	audit := &store.SessionCommand{
		SessionFK:   &sess.ID,
		AgentName:   sess.AgentName,
		Namespace:   sess.Namespace,
		SessionID:   sess.SessionID,
		Command:     req.Command,
		Operator:    req.Operator,
		Source:      firstNonEmpty(req.Source, "http"),
		InstanceRef: sess.InstanceRef,
		Status:      store.CommandStatusQueued,
		Code:        CodeQueued,
		Forced:      req.Force,
		CommandID:   commandID,
		RequestedAt: time.Now().UTC(),
	}
	if r.Store != nil {
		_ = r.Store.Commands().Insert(ctx, audit)
	}
	return &Result{
		Accepted:  true,
		CommandID: commandID,
		Phase:     sess.Phase,
		Result:    json.RawMessage(`{"queued":true}`),
		Forced:    req.Force,
		Queued:    true,
		Audit:     audit,
	}, nil
}

func isIdleRequired(command string) bool {
	switch command {
	case CommandCompress, CommandUndo, CommandRedo, CommandPlan:
		return true
	default:
		return false
	}
}

func (r *Router) postRefresh(ctx context.Context, baseURL string, sess *store.Session, phase string) {
	if r.Store == nil {
		return
	}
	updated := *sess
	if phase != "" {
		updated.Phase = phase
	}
	if r.Prober != nil && baseURL != "" {
		if state, err := r.Prober.FetchSessionState(ctx, baseURL, sess.SessionID); err == nil && state != nil {
			if state.Phase != "" {
				updated.Phase = state.Phase
			}
			if state.Busy != nil {
				updated.Busy = state.Busy
			} else if phase != "" {
				b := normalizePhase(updated.Phase) == store.SessionPhaseActive
				updated.Busy = &b
			}
		}
	}
	if normalizePhase(phase) == store.SessionPhaseTerminated {
		falseBusy := false
		updated.Busy = &falseBusy
	}
	if normalizePhase(phase) == store.SessionPhaseCompressing {
		falseBusy := false
		updated.Busy = &falseBusy
	}
	_, _ = r.Store.Sessions().Upsert(ctx, &updated)
}

func expectedPhase(command, current string) string {
	switch command {
	case CommandCompress:
		return store.SessionPhaseCompressing
	case CommandTerminate:
		return store.SessionPhaseTerminated
	default:
		return current
	}
}

func (r *Router) auditRejected(ctx context.Context, sess *store.Session, req Request, forced bool, opErr *Error) {
	if r.Store == nil || opErr == nil {
		return
	}
	now := time.Now().UTC()
	cmdID := req.CommandID
	if cmdID == "" {
		cmdID = "cmd-" + uuid.NewString()[:8]
	}
	row := &store.SessionCommand{
		SessionFK:   &sess.ID,
		AgentName:   sess.AgentName,
		Namespace:   sess.Namespace,
		SessionID:   sess.SessionID,
		Command:     req.Command,
		Operator:    req.Operator,
		Source:      firstNonEmpty(req.Source, "http"),
		InstanceRef: sess.InstanceRef,
		Status:      store.CommandStatusRejected,
		Code:        opErr.Code,
		Error:       opErr.Msg,
		Forced:      forced,
		CommandID:   cmdID,
		RequestedAt: now,
		CompletedAt: &now,
	}
	_ = r.Store.Commands().Insert(ctx, row)
}

func (r *Router) finishAudit(ctx context.Context, audit *store.SessionCommand, status, code, errMsg string, completed *time.Time, durationMs int64) {
	if r.Store == nil || audit == nil {
		return
	}
	audit.Status = status
	audit.Code = code
	audit.Error = errMsg
	audit.CompletedAt = completed
	audit.DurationMs = durationMs
	_ = r.Store.Commands().Update(ctx, audit)
}

func cachedOutcome(prev *store.SessionCommand) (*Result, error) {
	switch prev.Status {
	case store.CommandStatusRejected, store.CommandStatusFailed:
		hint := ""
		if prev.Code == CodeBusy {
			hint = HintWaitIdle
		}
		return nil, &Error{
			Status: statusForCode(prev.Code),
			Code:   prev.Code,
			Msg:    firstNonEmpty(prev.Error, prev.Status),
			Hint:   hint,
		}
	case store.CommandStatusQueued:
		return &Result{
			Accepted:  true,
			CommandID: prev.CommandID,
			Result:    json.RawMessage(`{"queued":true}`),
			Forced:    prev.Forced,
			Queued:    true,
			Cached:    true,
			Audit:     prev,
		}, nil
	default:
		return &Result{
			Accepted:  true,
			CommandID: prev.CommandID,
			Phase:     "",
			Result:    json.RawMessage(`{}`),
			Forced:    prev.Forced,
			Cached:    true,
			Audit:     prev,
		}, nil
	}
}

func statusForCode(code string) int {
	switch code {
	case CodeBusy:
		return http.StatusConflict
	case CodeUnsupported:
		return http.StatusNotImplemented
	case CodeUnreachable:
		return http.StatusServiceUnavailable
	case CodeNotFound:
		return http.StatusNotFound
	default:
		return http.StatusInternalServerError
	}
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package agentservice

import (
	"context"

	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
)

func applyLifecycle(session *Session, request runtimeprotocol.RuntimeSessionLifecycle) (*runtimeprotocol.RuntimeLifecycleReceipt, error) {
	if request.Context.Validate() != nil || request.Context.TurnID != nil || request.LifecycleRevision < 2 ||
		request.Lifecycle != "active" && request.Lifecycle != "archived" && request.Lifecycle != "deleted" {
		return nil, ErrInvalid
	}
	if !SameScope(session.Bootstrap.Context, request.Context) {
		return nil, ErrNotFound
	}
	digest, err := runtimeprotocol.Digest(request)
	if err != nil {
		return nil, err
	}
	status := "applied"
	switch {
	case request.LifecycleRevision < session.LifecycleRevision:
		status = "superseded"
	case request.LifecycleRevision == session.LifecycleRevision && session.LifecycleCommandID != "":
		if session.LifecycleCommandID != request.Context.CommandID || session.LifecycleDigest != digest {
			return nil, ErrConflict
		}
	case request.LifecycleRevision == session.LifecycleRevision && request.Lifecycle != session.Lifecycle:
		return nil, ErrConflict
	default:
		if session.Lifecycle == "deleted" {
			return nil, ErrConflict
		}
		session.Lifecycle, session.LifecycleRevision = request.Lifecycle, request.LifecycleRevision
		session.LifecycleCommandID, session.LifecycleDigest = request.Context.CommandID, digest
	}
	return &runtimeprotocol.RuntimeLifecycleReceipt{Version: runtimeprotocol.Version, SessionID: request.Context.SessionID,
		CommandID: request.Context.CommandID, LifecycleRevision: request.LifecycleRevision, Lifecycle: request.Lifecycle, PayloadDigest: digest, Status: status}, nil
}

func (r *Memory) ApplyLifecycle(_ context.Context, request runtimeprotocol.RuntimeSessionLifecycle) (*runtimeprotocol.RuntimeLifecycleReceipt, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.init()
	session := r.sessions[request.Context.SessionID]
	if session == nil {
		return nil, ErrNotFound
	}
	return applyLifecycle(session, request)
}

func (r *Postgres) ApplyLifecycle(ctx context.Context, request runtimeprotocol.RuntimeSessionLifecycle) (*runtimeprotocol.RuntimeLifecycleReceipt, error) {
	tx, err := r.Pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	session, err := r.scanSession(tx.QueryRow(ctx, `SELECT id,payload,last_sequence FROM orchestration_portal_sessions WHERE id=$1 FOR UPDATE`, request.Context.SessionID))
	if err != nil {
		return nil, err
	}
	receipt, err := applyLifecycle(session, request)
	if err != nil {
		return nil, err
	}
	if receipt.Status == "applied" {
		raw, err := r.Cipher.encode("session:"+request.Context.SessionID, session)
		if err != nil {
			return nil, err
		}
		if _, err = tx.Exec(ctx, `UPDATE orchestration_portal_sessions SET payload=$2 WHERE id=$1`, request.Context.SessionID, raw); err != nil {
			return nil, err
		}
	}
	return receipt, tx.Commit(ctx)
}

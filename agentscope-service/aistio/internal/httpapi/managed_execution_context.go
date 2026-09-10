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
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// managedExecutionContextForSession is intentionally internal-only. The task
// token is scoped to the current attempt and must never be persisted in the
// public session row or emitted as a user message.
func (s *Server) managedExecutionContextForSession(ctx context.Context, sessionID string) json.RawMessage {
	if s == nil || s.store == nil || sessionID == "" {
		return nil
	}
	sessions, err := s.store.Sessions().List(ctx, store.SessionFilter{SessionID: sessionID, Limit: 2})
	if err != nil || len(sessions) != 1 || sessions[0].AgentTaskID == nil {
		return nil
	}
	session := sessions[0]
	task, err := s.store.Collaboration().GetAgentTask(ctx, *session.AgentTaskID)
	if err != nil || task.CurrentAttemptID == nil {
		return nil
	}
	attempt, err := s.store.ExecutionAttempts().Get(ctx, *task.CurrentAttemptID)
	if err != nil || attempt.BackendKind != controlmodel.DataPlaneManaged ||
		attempt.SessionID != sessionID || controlmodel.IsExecutionAttemptTerminal(attempt.State) {
		return nil
	}
	envelope, err := (&collaboration.Service{Store: s.store}).BuildContext(ctx, task.ID)
	if err != nil {
		return nil
	}
	envelope.TaskToken, err = s.taskTokens.MintScoped(
		task.ID, attempt.ID, attempt.DispatchGeneration, time.Now().UTC())
	if err != nil {
		return nil
	}
	raw, err := json.Marshal(map[string]any{
		"taskContext":          envelope,
		"attemptId":            attempt.ID,
		"dispatchGeneration":   attempt.DispatchGeneration,
		"turnId":               attempt.TurnID,
		"collaborationMcpPath": "/mcp/collaboration",
	})
	if err != nil {
		return nil
	}
	return raw
}

// validateManagedSessionRuntimeFence protects the product Session status read
// model from delayed finally/status callbacks. The runtime Store is the
// authority for whether this is a personal Session or a physical AgentTask
// Attempt; the product schema persists a second monotonic marker to close A/B
// update ordering races across the two stores.
func (s *Server) validateManagedSessionRuntimeFence(ctx context.Context, sessionID string,
	fence product.ManagedRuntimeFence) (bool, error) {
	if s == nil || s.store == nil || sessionID == "" {
		return false, product.ErrManagedRuntimeFenceConflict
	}
	sessions, err := s.store.Sessions().List(ctx, store.SessionFilter{SessionID: sessionID, Limit: 2})
	if err != nil {
		return false, err
	}
	if len(sessions) == 0 {
		if fence.Empty() {
			return false, nil
		}
		return false, product.ErrManagedRuntimeFenceGone
	}
	if len(sessions) != 1 {
		return false, product.ErrManagedRuntimeFenceConflict
	}
	session := sessions[0]
	if session.AgentTaskID == nil {
		if fence.Empty() {
			return false, nil
		}
		return false, product.ErrManagedRuntimeFenceConflict
	}
	if !fence.Complete() {
		return true, product.ErrManagedRuntimeFenceConflict
	}
	taskID, taskParseErr := uuid.Parse(fence.AgentTaskID)
	attemptID, attemptParseErr := uuid.Parse(fence.AttemptID)
	if taskParseErr != nil || attemptParseErr != nil || taskID != *session.AgentTaskID {
		return true, product.ErrManagedRuntimeFenceConflict
	}
	task, err := s.store.Collaboration().GetAgentTask(ctx, taskID)
	if errors.Is(err, store.ErrNotFound) {
		return true, product.ErrManagedRuntimeFenceGone
	}
	if err != nil {
		return true, err
	}
	if task.CurrentAttemptID == nil || *task.CurrentAttemptID != attemptID {
		return true, product.ErrManagedRuntimeFenceGone
	}
	attempt, err := s.store.ExecutionAttempts().Get(ctx, attemptID)
	if errors.Is(err, store.ErrNotFound) {
		return true, product.ErrManagedRuntimeFenceGone
	}
	if err != nil {
		return true, err
	}
	if attempt.AgentTaskID != task.ID || attempt.BackendKind != controlmodel.DataPlaneManaged ||
		attempt.SessionID != sessionID || attempt.DispatchGeneration != fence.DispatchGeneration ||
		attempt.TurnID != fence.TurnID {
		return true, product.ErrManagedRuntimeFenceConflict
	}
	if task.Status == controlmodel.AgentTaskCompleted && attempt.State == controlmodel.ExecutionSucceeded {
		return true, nil
	}
	if task.Status == controlmodel.AgentTaskFailed || task.Status == controlmodel.AgentTaskCancelled ||
		task.Status == controlmodel.AgentTaskQueued ||
		controlmodel.IsExecutionAttemptTerminal(attempt.State) || attempt.State == controlmodel.ExecutionCancelRequested {
		return true, product.ErrManagedRuntimeFenceGone
	}
	if task.Status != controlmodel.AgentTaskDispatched && task.Status != controlmodel.AgentTaskRunning &&
		task.Status != controlmodel.AgentTaskWaiting {
		return true, product.ErrManagedRuntimeFenceConflict
	}
	return true, nil
}

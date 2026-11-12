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

	"github.com/google/uuid"

	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/collaboration"
	controlmodel "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/sessionapi"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
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
	// Native configuration carries a stable, private workload credential. Its
	// authority is checked against the current Attempt and native Turn per call.
	nativeID, err := uuid.Parse(sessionID)
	if err != nil {
		return nil
	}
	native, err := sessionapi.Get(ctx, s.store, task.Tenant, nativeID)
	if err != nil || native.TaskAttemptID == nil || *native.TaskAttemptID != attempt.ID {
		return nil
	}
	envelope.TaskToken, err = s.taskTokens.MintManaged(task.ID, attempt.ID, attempt.DispatchGeneration, nativeID)
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

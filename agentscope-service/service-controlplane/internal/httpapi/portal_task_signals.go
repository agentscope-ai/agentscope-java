// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeportal"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

func (s *Server) submitTaskRunSignal(ctx context.Context, task *model.AgentTask, token, name, requestID, key string, value json.RawMessage) (map[string]any, error) {
	contract, err := serviceapi.ContractForIssue(serviceapi.WithContract(ctx, nil), s.store, task.IssueID)
	if err != nil {
		return nil, err
	}
	if contract == nil || contract.PortalCommandID == "" {
		err = s.orchestrationService().Signal(ctx, task.OrchestrationRunID, name, key, value, model.Actor{Type: model.ActorAgent, Ref: task.AgentRef})
		return map[string]any{"accepted": err == nil}, err
	}
	if s.portalClient == nil {
		return nil, fmt.Errorf("Portal signal courier is unavailable")
	}
	if name == "" || key == "" || len(key) > 256 || !json.Valid(value) || len(value) > 65536 {
		return nil, runtimeportal.ErrInvalid
	}
	nodeID, err := uuid.Parse(requestID)
	if err != nil {
		return nil, runtimeportal.ErrInvalid
	}
	claims, err := s.taskTokens.VerifyClaims(token, time.Now())
	if err != nil || claims.Fence < 1 {
		return nil, store.ErrForbidden
	}
	fresh, err := s.verifyActiveTaskToken(ctx, token, task.ID)
	if err != nil {
		return nil, store.ErrForbidden
	}
	node, err := s.store.Orchestration().GetNode(ctx, nodeID)
	if err != nil {
		return nil, err
	}
	var cfg struct {
		SignalName string `json:"signalName"`
	}
	if json.Unmarshal(node.Config, &cfg) != nil || cfg.SignalName != name || node.RunID != fresh.OrchestrationRunID {
		return nil, store.ErrForbidden
	}
	turn, err := s.store.RuntimePortal().Turn(ctx, contract.PortalCommandID)
	if err != nil {
		return nil, err
	}
	commandID, err := runtimeprotocol.Digest(map[string]any{
		"tenant": turn.Command.Context.Tenant, "namespace": turn.Command.Context.Namespace,
		"session": turn.Command.Context.SessionID, "turn": turn.Command.Context.CommandID, "kind": "actions", "key": key,
		"principal": "agent:" + fresh.ID.String() + ":" + claims.AttemptID.String() + ":" + strconv.FormatInt(claims.Fence, 10),
	})
	if err != nil {
		return nil, err
	}
	prior, err := s.store.RuntimePortal().Interaction(ctx, turn.Command.Context.SessionID, commandID)
	replay := err == nil && prior.Command.Kind == "signal"
	if err != nil && !errors.Is(err, runtimeportal.ErrNotFound) {
		return nil, err
	}
	if restricted, _ := ctx.Value(runtimeSignalReplayKey{}).(bool); restricted && !replay {
		return nil, store.ErrForbidden
	}
	if !replay {
		if err = serviceapi.CheckTaskPortalExecution(ctx, s.store, fresh); err != nil {
			return nil, err
		}
	}
	// New signals need a pending node. A durable retry may read its original receipt after resolution.
	if err = (&portalNativeBackend{server: s}).validateSignalScope(ctx, node, *turn, !replay); err != nil {
		return nil, err
	}
	return s.portalClient.SubmitAgentSignal(ctx, runtimeprotocol.RuntimeAgentSignal{
		Version: 1, SessionID: turn.Command.Context.SessionID, TurnID: turn.Command.Context.CommandID,
		ExecutionGeneration: turn.Generation, ExecutionFence: turn.Fence,
		TaskID: fresh.ID.String(), AttemptID: claims.AttemptID.String(), TaskFence: claims.Fence,
		AgentRef: fresh.AgentRef, RequestID: nodeID.String(), IdempotencyKey: key, Value: value,
	})
}

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

package asdp

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	otelTrace "go.opentelemetry.io/otel/trace"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/internal/tracing"
)

// Distributor handles config push distribution to connected data plane instances.
type Distributor struct {
	server    *Server
	snapshots *SnapshotStore
}

// NewDistributor creates a new Distributor.
func NewDistributor(server *Server, snapshots *SnapshotStore) *Distributor {
	return &Distributor{
		server:    server,
		snapshots: snapshots,
	}
}

// PushConfig pushes a config update to all connected instances of an agent.
func (d *Distributor) PushConfig(tenant, namespace, agentName string, cfgType ConfigType, resources interface{}) error {
	logger := log.Log.WithName("asdp-distributor")

	_, span := tracing.Tracer().Start(context.Background(), "asdp.PushConfig",
		otelTrace.WithAttributes(
			attribute.String("tenant", tenant),
			attribute.String("agent", agentName),
			attribute.String("namespace", namespace),
			attribute.String("config_type", fmt.Sprintf("%d", cfgType)),
		))
	defer span.End()

	snapshot, changed, err := d.snapshots.UpdateSnapshot(tenant, namespace, agentName, cfgType, resources)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, err.Error())
		return err
	}
	if !changed {
		logger.V(1).Info("config unchanged, skipping push",
			"agent", agentName, "configType", cfgType)
		span.SetAttributes(attribute.Bool("skipped", true))
		return nil
	}

	conns := d.server.GetConnectionsForAgentForTenant(tenant, namespace, agentName)
	if len(conns) == 0 {
		logger.Info("no connected instances, config will be pushed on reconnect",
			"agent", agentName, "configType", cfgType, "version", snapshot.Version)
		span.SetAttributes(attribute.Int("instances", 0))
		return nil
	}

	span.SetAttributes(attribute.Int("instances", len(conns)),
		attribute.String("version", snapshot.Version))

	logger.Info("pushing config to connected instances",
		"agent", agentName,
		"configType", cfgType,
		"version", snapshot.Version,
		"instances", len(conns),
	)

	push := &ConfigPush{
		ConfigType: cfgType,
		Version:    snapshot.Version,
		Resources:  snapshot.Resources,
		Nonce:      snapshot.Nonce,
	}
	down := &Downstream{
		Payload: &Downstream_ConfigPush{ConfigPush: push},
	}

	for _, conn := range conns {
		if err := conn.Send(down); err != nil {
			logger.Error(err, "failed to push config",
				"instance", conn.InstanceID, "configType", cfgType)
		}
	}

	return nil
}

// ForgetAgent drops all cached config snapshots for a deleted agent so a
// re-created agent of the same name starts from a clean version counter and
// stale config is not re-pushed on reconnect.
func (d *Distributor) ForgetAgent(namespace, agentName string) {
	d.snapshots.DeleteAgent("default", namespace, agentName)
}

// PushFullSync pushes all current config snapshots to a newly connected instance.
func (d *Distributor) PushFullSync(tenant, namespace, agentID, agentName, instanceID string) {
	logger := log.Log.WithName("asdp-distributor")

	_, span := tracing.Tracer().Start(context.Background(), "asdp.PushFullSync",
		otelTrace.WithAttributes(
			attribute.String("agent", agentName),
			attribute.String("namespace", namespace),
			attribute.String("instance", instanceID),
		))
	defer span.End()

	snapshots := d.snapshots.GetAllSnapshots(tenant, namespace, agentName)
	if len(snapshots) == 0 {
		logger.Info("no config snapshots to push on connect",
			"agent", agentName, "instance", instanceID)
		span.SetAttributes(attribute.Int("snapshot_count", 0))
		return
	}

	conn, ok := d.server.GetConnectionForAgentInstance(tenant, namespace, agentID, instanceID)
	if !ok {
		logger.Info("instance not connected for full sync",
			"agent", agentName, "instance", instanceID)
		span.SetStatus(codes.Error, "instance not connected")
		return
	}

	span.SetAttributes(attribute.Int("snapshot_count", len(snapshots)))

	logger.Info("pushing full config sync to reconnected instance",
		"agent", agentName,
		"instance", instanceID,
		"snapshotCount", len(snapshots),
	)

	for _, snap := range snapshots {
		push := &ConfigPush{
			ConfigType: snap.CfgType,
			Version:    snap.Version,
			Resources:  snap.Resources,
			Nonce:      snap.Nonce,
		}
		down := &Downstream{
			Payload: &Downstream_ConfigPush{ConfigPush: push},
		}
		if err := conn.Send(down); err != nil {
			logger.Error(err, "full sync push failed",
				"instance", instanceID, "configType", snap.CfgType)
			span.RecordError(err)
		}
	}
}

// ErrInstanceNotConnected is returned when the target instance has no live
// ASDP stream on this replica; callers may fall back to the HTTP contract.
var ErrInstanceNotConnected = errors.New("asdp: instance not connected")

// SendSessionCommand sends a session command to a specific instance.
// It returns ErrInstanceNotConnected when the instance has no live stream.
func (d *Distributor) SendSessionCommand(tenant, namespace, agentID, instanceID, sessionID, command string) error {
	return d.SendSessionCommandWithParams(tenant, namespace, agentID, instanceID, sessionID, command, nil)
}

// SendSessionCommandWithParams is like SendSessionCommand but includes params
// (for example an AgentTask locator and task-scoped token).
func (d *Distributor) SendSessionCommandWithParams(tenant, namespace, agentID, instanceID, sessionID, command string, params []byte) error {
	logger := log.Log.WithName("asdp-distributor")

	conn, ok := d.server.GetConnectionForAgentInstance(tenant, namespace, agentID, instanceID)
	if !ok {
		logger.Info("instance not connected for session command",
			"instance", instanceID, "session", sessionID, "command", command)
		return ErrInstanceNotConnected
	}

	cmd := &SessionCommand{
		SessionId: sessionID,
		Command:   command,
	}
	if len(params) > 0 {
		cmd.Params = params
	}

	down := &Downstream{
		Payload: &Downstream_SessionCmd{
			SessionCmd: cmd,
		},
	}

	return conn.Send(down)
}

// SendConversationTurn delivers one fenced online turn to a connected Agent
// instance. Unlike SessionCommand this command carries a public Invocation ID
// and must be acknowledged through ConversationTurnReport.
func (d *Distributor) SendConversationTurn(tenant, namespace, instanceID string, command *ConversationTurnCommand) error {
	conn, ok := d.server.GetConnectionForAgentInstance(tenant, namespace, command.GetAgentId(), instanceID)
	if !ok {
		return ErrInstanceNotConnected
	}
	return conn.Send(&Downstream{Payload: &Downstream_ConversationTurn{ConversationTurn: command}})
}

// SendExecutionAttemptCommand sends a task wake only to the selected tenant's stream.
func (d *Distributor) SendExecutionAttemptCommand(tenant, namespace, agentID, instanceID, sessionID, command string, params []byte) error {
	conn, ok := d.server.GetConnectionForAgentInstance(tenant, namespace, agentID, instanceID)
	if !ok {
		return ErrInstanceNotConnected
	}
	var payload struct {
		AttemptID, AgentTaskID, RunID, NodeID string
		Generation                            int64
		ContextURL, TaskToken, AttemptToken   string
		RuntimeBinding                        json.RawMessage
	}
	if err := json.Unmarshal(params, &payload); err != nil {
		return err
	}
	if command != "dispatch" && command != "cancel" {
		return fmt.Errorf("unsupported execution attempt command %q", command)
	}
	cmd := &ExecutionAttemptCommand{AttemptId: payload.AttemptID, AgentTaskId: payload.AgentTaskID,
		RunId: payload.RunID, NodeId: payload.NodeID, Generation: payload.Generation, Command: command,
		ContextUrl: payload.ContextURL, TaskToken: payload.TaskToken, AttemptToken: payload.AttemptToken,
		RuntimeBinding: payload.RuntimeBinding, Payload: params, Timestamp: time.Now().UnixMilli()}
	return conn.Send(&Downstream{Payload: &Downstream_ExecutionAttempt{ExecutionAttempt: cmd}})
}

// GetConnectedInstance returns the instance ID of a connected instance for the
// given agent on THIS replica, if any.
func (d *Distributor) GetConnectedInstance(tenant, namespace, agentName string) (string, bool) {
	conns := d.server.GetConnectionsForAgentForTenant(tenant, namespace, agentName)
	if len(conns) == 0 {
		return "", false
	}
	return conns[0].InstanceID, true
}

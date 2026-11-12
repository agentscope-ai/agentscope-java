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

package invocation

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

type contractContextKey struct{}

func WithContract(ctx context.Context, c *Contract) context.Context {
	return context.WithValue(ctx, contractContextKey{}, c)
}
func ContextContract(ctx context.Context) *Contract {
	c, _ := ctx.Value(contractContextKey{}).(*Contract)
	return c
}
func ContextAgentServiceCommandID(ctx context.Context) string {
	if c := ContextContract(ctx); c != nil {
		return c.AgentServiceCommandID
	}
	return ""
}
func ContextDefinition(ctx context.Context, id string) (json.RawMessage, bool) {
	c := ContextContract(ctx)
	if c == nil {
		return nil, false
	}
	raw, ok := c.Definitions[id]
	return raw, ok
}
func ContextVersion(ctx context.Context, id string) int {
	raw, ok := ContextDefinition(ctx, id)
	if !ok {
		return 0
	}
	var d struct {
		Version int `json:"version"`
	}
	_ = json.Unmarshal(raw, &d)
	return d.Version
}

// ReadInvocationContract resolves a Agent Service-owned immutable snapshot through
// the shared runtime store. Neither a control-plane HTTP call nor live resource
// definitions are needed for replica recovery.
func ReadInvocationContract(ctx context.Context, st store.Store, inv *model.EndpointInvocation) (*Contract, error) {
	c, err := ReadContract(inv.Contract)
	if err != nil || c.AgentServiceCommandID == "" {
		return c, err
	}
	turn, err := st.RuntimeAgentService().Turn(ctx, c.AgentServiceCommandID)
	if err != nil {
		return nil, err
	}
	if turn.NativeInvocationID != inv.ID.String() || turn.Command.Context.SessionID != c.PublicSessionID.String() {
		return nil, store.ErrForbidden
	}
	session, err := st.RuntimeAgentService().Session(ctx, turn.Command.Context.SessionID)
	if err != nil {
		return nil, err
	}
	var configuration struct {
		Contract json.RawMessage `json:"contract"`
	}
	if err = json.Unmarshal(session.Bootstrap.Configuration, &configuration); err != nil {
		return nil, err
	}
	frozen, err := ReadContract(configuration.Contract)
	if err != nil {
		return nil, err
	}
	if frozen.PublicSessionID != c.PublicSessionID || frozen.Endpoint.ID != inv.EndpointID || frozen.Endpoint.Tenant != turn.Command.Context.Tenant || frozen.Endpoint.Namespace != turn.Command.Context.Namespace {
		return nil, store.ErrForbidden
	}
	frozen.AgentServiceCommandID = c.AgentServiceCommandID
	return frozen, nil
}

// CheckAgentServiceExecution gates new work, including Host lease renewal. Reporting
// and cancellation are deliberately allowed after expiry to drain final facts.
func CheckAgentServiceExecution(ctx context.Context, st store.Store, c *Contract, tenant, namespace string) error {
	return checkAgentServiceRuntime(ctx, st, c, tenant, namespace, false, true)
}

// Waiting maintenance preserves a suspended physical process without renewing
// its root execution grant. Approval delivery still requires a live grant.
func CheckAgentServiceWaiting(ctx context.Context, st store.Store, c *Contract, tenant, namespace string, delivery bool) error {
	return checkAgentServiceRuntime(ctx, st, c, tenant, namespace, true, delivery)
}

func checkAgentServiceRuntime(ctx context.Context, st store.Store, c *Contract, tenant, namespace string, waiting, grant bool) error {
	if c == nil || c.AgentServiceCommandID == "" {
		return nil
	}
	turn, err := st.RuntimeAgentService().Turn(ctx, c.AgentServiceCommandID)
	if err != nil {
		return err
	}
	if turn.Command.Context.SessionID != c.PublicSessionID.String() || turn.Command.Context.Tenant != tenant || turn.Command.Context.Namespace != namespace || turn.Generation < 1 || turn.Fence == "" || (grant && turn.GrantUntil <= time.Now().UnixMilli()) || turn.StopRequested || (turn.Status != "queued" && turn.Status != "running" && !(waiting && turn.Status == "requires_action")) {
		return store.ErrForbidden
	}
	session, err := st.RuntimeAgentService().Session(ctx, turn.Command.Context.SessionID)
	if err != nil {
		return err
	}
	if session.Lifecycle != "active" {
		return store.ErrForbidden
	}
	return nil
}

func CheckTaskAgentServiceExecution(ctx context.Context, st store.Store, task *model.AgentTask) error {
	c, err := ContractForIssue(ctx, st, task.IssueID)
	if err != nil {
		return err
	}
	if c != nil && c.AgentServiceCommandID != "" && task.Status == model.AgentTaskWaiting {
		return store.ErrForbidden
	}
	// A delivered approval resumes the physical Task before its observation
	// reaches Agent Service. Already-running Tasks may use that fresh grant during this
	// interval; waiting Tasks and new dispatch still cannot execute.
	if task.Status == model.AgentTaskRunning {
		return CheckAgentServiceWaiting(ctx, st, c, task.Tenant, task.Namespace, true)
	}
	return CheckAgentServiceExecution(ctx, st, c, task.Tenant, task.Namespace)
}

func InvocationInput(ctx context.Context, st store.Store, inv *model.EndpointInvocation) (json.RawMessage, error) {
	c, err := ReadContract(inv.Contract)
	if err != nil {
		return nil, err
	}
	if c.AgentServiceCommandID == "" {
		return inv.Input, nil
	}
	turn, err := st.RuntimeAgentService().Turn(ctx, c.AgentServiceCommandID)
	if err != nil {
		return nil, err
	}
	if turn.NativeInvocationID != inv.ID.String() || turn.Command.Context.SessionID != c.PublicSessionID.String() {
		return nil, store.ErrForbidden
	}
	return json.Marshal(turn.Command.Input)
}
func ContractForIssue(ctx context.Context, st store.Store, id uuid.UUID) (*Contract, error) {
	if c := ContextContract(ctx); c != nil {
		return c, nil
	}
	inv, err := InvocationForIssue(ctx, st, id)
	if err != nil || inv == nil {
		return nil, err
	}
	return ReadInvocationContract(ctx, st, inv)
}

// InvocationForIssue resolves the original service caller through delegated Issues.
func InvocationForIssue(ctx context.Context, st store.Store, id uuid.UUID) (*model.EndpointInvocation, error) {
	seen := map[uuid.UUID]bool{}
	const maxAncestors = 256
	for id != uuid.Nil {
		if seen[id] {
			return nil, fmt.Errorf("%w: execution issue ancestry contains a cycle", store.ErrForbidden)
		}
		if len(seen) >= maxAncestors {
			return nil, fmt.Errorf("%w: execution issue ancestry exceeds %d ancestors", store.ErrForbidden, maxAncestors)
		}
		seen[id] = true
		issue, err := st.Collaboration().GetIssue(ctx, id)
		if errors.Is(err, store.ErrNotFound) {
			return nil, nil
		}
		if err != nil {
			return nil, err
		}
		if issue.SourceType == "endpoint" || issue.SourceType == "session_turn" || issue.SourceType == "endpoint_conversation" {
			invID, err := uuid.Parse(issue.SourceRef)
			if err != nil {
				return nil, err
			}
			inv, err := st.Endpoints().GetInvocation(ctx, invID)
			if err != nil {
				return nil, err
			}
			return inv, nil
		}
		if issue.ParentIssueID == nil {
			break
		}
		id = *issue.ParentIssueID
	}
	return nil, nil
}
func TaskContext(ctx context.Context, st store.Store, task *model.AgentTask) (context.Context, error) {
	c, err := ContractForIssue(ctx, st, task.IssueID)
	if err != nil {
		return ctx, err
	}
	if c != nil {
		ctx = WithContract(ctx, c)
	}
	return ctx, nil
}
func RuntimePolicy(ctx context.Context, st store.Store, task *model.AgentTask) (*model.AgentRuntimePolicy, error) {
	c, err := ContractForIssue(ctx, st, task.IssueID)
	if err != nil {
		return nil, err
	}
	if c != nil {
		if p, ok := c.Policies[task.AgentRef]; ok {
			return p, nil
		}
		return nil, store.ErrNotFound
	}
	return st.Orchestration().GetRuntimePolicy(ctx, task.Tenant, task.Namespace, task.AgentRef)
}

// AgentServiceApprovalOutput proves the immutable human command before an ordinary
// graph gate advances. Transport proof remains internal to the approval row.
func AgentServiceApprovalOutput(ctx context.Context, st store.Store, c *Contract, approval *model.Approval) (json.RawMessage, error) {
	if c == nil || c.AgentServiceCommandID == "" {
		return approval.Decision, nil
	}
	var decision map[string]any
	if err := json.Unmarshal(approval.Decision, &decision); err != nil {
		return nil, err
	}
	activation, _ := decision["portal_command_id"].(string)
	interaction, err := st.RuntimeAgentService().Interaction(ctx, c.PublicSessionID.String(), activation)
	if err != nil {
		return nil, err
	}
	command := interaction.Command
	if proof, err := runtimeprotocol.ParseAgentSignalProof(command.Context.Actor); err != nil || proof != nil {
		return nil, store.ErrForbidden
	}
	if command.Kind != "approval" || command.Context.SessionID != c.PublicSessionID.String() || command.Context.Tenant != approval.Tenant || command.Context.Namespace != approval.Namespace || command.Context.TurnID == nil || *command.Context.TurnID != c.AgentServiceCommandID || command.Payload["target_command_id"] != c.AgentServiceCommandID || decision["portal_fence"] != interaction.ActivationFence {
		return nil, store.ErrForbidden
	}
	var generation int64
	switch value := decision["portal_generation"].(type) {
	case float64:
		generation = int64(value)
	case json.Number:
		generation, _ = value.Int64()
	}
	if generation < 1 || generation != interaction.ActivationGeneration || approval.DecidedBy == nil || approval.DecidedBy.Type != model.ActorHuman || approval.DecidedBy.Ref != approval.ApproverRef || command.Context.Actor["user"] != approval.DecidedBy.Ref {
		return nil, store.ErrForbidden
	}
	answers, _ := command.Payload["answers"].([]any)
	for _, raw := range answers {
		answer, _ := raw.(map[string]any)
		if answer["kind"] != "confirmation" || answer["request_id"] != approval.ID.String() {
			continue
		}
		allow, ok := answer["allow"].(bool)
		if !ok || decision["allow"] != allow || decision["reason"] != answer["reason"] ||
			allow && approval.Status != model.ApprovalApproved || !allow && approval.Status != model.ApprovalRejected {
			return nil, store.ErrForbidden
		}
		return json.Marshal(map[string]any{"allow": allow, "reason": answer["reason"]})
	}
	return nil, store.ErrForbidden
}

// AgentServiceSignalOutput distinguishes a targeted, authorized reply from legacy
// broadcast signals. Only the business value enters the workflow context.
func AgentServiceSignalOutput(ctx context.Context, st store.Store, c *Contract, node *model.RunNode, event *model.RunEvent) (json.RawMessage, error) {
	if c == nil || c.AgentServiceCommandID == "" {
		return event.Payload, nil
	}
	if event.NodeID == nil || *event.NodeID != node.ID || event.RunID != node.RunID {
		return nil, store.ErrForbidden
	}
	var payload struct {
		CommandID  string          `json:"portal_command_id"`
		Generation int64           `json:"portal_generation"`
		Fence      string          `json:"portal_fence"`
		Value      json.RawMessage `json:"value"`
	}
	if err := json.Unmarshal(event.Payload, &payload); err != nil {
		return nil, err
	}
	interaction, err := st.RuntimeAgentService().Interaction(ctx, c.PublicSessionID.String(), payload.CommandID)
	if err != nil {
		return nil, err
	}
	command := interaction.Command
	if command.Kind != "signal" || command.Context.TurnID == nil || *command.Context.TurnID != c.AgentServiceCommandID || command.Context.Tenant != node.Tenant || command.Context.Namespace != node.Namespace || command.Payload["target_command_id"] != c.AgentServiceCommandID || payload.Generation < 1 || payload.Generation != interaction.ActivationGeneration || payload.Fence != interaction.ActivationFence {
		return nil, store.ErrForbidden
	}
	actor, err := AgentServiceSignalActor(command.Context.Actor)
	if err != nil {
		return nil, err
	}
	if event.Actor != actor {
		return nil, store.ErrForbidden
	}
	answers, _ := command.Payload["answers"].([]any)
	for _, raw := range answers {
		answer, _ := raw.(map[string]any)
		if answer["kind"] == "signal" && answer["request_id"] == node.ID.String() {
			value, err := json.Marshal(answer["value"])
			if err != nil {
				return nil, err
			}
			var a, b any
			left, right := json.NewDecoder(bytes.NewReader(value)), json.NewDecoder(bytes.NewReader(payload.Value))
			left.UseNumber()
			right.UseNumber()
			if left.Decode(&a) != nil || right.Decode(&b) != nil {
				return nil, store.ErrForbidden
			}
			da, _ := runtimeprotocol.Digest(a)
			db, _ := runtimeprotocol.Digest(b)
			if da != db {
				return nil, store.ErrForbidden
			}
			return payload.Value, nil
		}
	}
	return nil, store.ErrForbidden
}

// An originating user's permission remains in the command, but task signals are attributed to the Agent.
func AgentServiceSignalActor(actor map[string]any) (model.Actor, error) {
	proof, err := runtimeprotocol.ParseAgentSignalProof(actor)
	if err != nil {
		return model.Actor{}, store.ErrForbidden
	}
	if proof != nil {
		if _, err = uuid.Parse(proof.TaskID); err != nil {
			return model.Actor{}, store.ErrForbidden
		}
		if _, err = uuid.Parse(proof.AttemptID); err != nil {
			return model.Actor{}, store.ErrForbidden
		}
		return model.Actor{Type: model.ActorAgent, Ref: proof.AgentRef}, nil
	}
	if user, ok := actor["user"].(string); ok && user != "" {
		return model.Actor{Type: model.ActorHuman, Ref: user}, nil
	}
	return model.Actor{Type: model.ActorSystem, Ref: "portal-application"}, nil
}

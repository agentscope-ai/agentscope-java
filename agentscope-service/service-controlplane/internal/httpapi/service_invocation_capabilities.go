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
	"net/url"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type serviceCapabilities map[string]bool

func serviceBindingCapabilities(ep *model.Endpoint, kind model.DataPlaneKind, required json.RawMessage) serviceCapabilities {
	caps := serviceCapabilities{"events": true, "snapshot": true, "actions": false, "cancel": false, "inputs": false, "resume": false, "checkpoint_restore": false}
	if ep.InvocationMode == model.EndpointJobMode {
		// Job inputs are durable work-context comments and approvals belong to the control plane.
		caps["actions"], caps["cancel"], caps["inputs"] = true, true, true
		caps["resume"] = ep.TargetType == model.EndpointTargetOrchestrationRevision
		return caps
	}
	switch kind {
	case model.DataPlaneManaged:
		caps["actions"], caps["cancel"], caps["inputs"], caps["resume"] = true, true, true, true
	case model.DataPlaneHostedRuntime:
		caps["cancel"] = true
	case model.DataPlaneExternalApplication:
		caps["cancel"] = model.JSONContains(required, json.RawMessage(`{"session-abort":true}`))
	}
	return caps
}

func guaranteedServiceCapabilities(contract *serviceapi.Contract) serviceCapabilities {
	ep := &contract.Endpoint
	if ep.InvocationMode == model.EndpointJobMode {
		return serviceBindingCapabilities(ep, "", nil)
	}
	out := serviceBindingCapabilities(ep, "", nil)
	policy := contract.Policies[ep.TargetRef.String()]
	if policy == nil || len(policy.Candidates) == 0 {
		return out
	}
	for i, candidate := range policy.Candidates {
		current := serviceBindingCapabilities(ep, candidate.Binding.Kind, candidate.RequiredCapabilities)
		if i == 0 {
			out = current
			continue
		}
		for capability := range out {
			out[capability] = out[capability] && current[capability]
		}
	}
	return out
}

func (s *Server) selectedServiceCapabilities(ctx context.Context, inv *model.EndpointInvocation, contract *serviceapi.Contract) (serviceCapabilities, bool, error) {
	ep := &contract.Endpoint
	if inv.Mode == model.EndpointConversationMode && inv.ConversationID != nil {
		conv, err := s.store.Endpoints().GetConversation(ctx, *inv.ConversationID)
		if err != nil {
			return nil, false, err
		}
		policy := contract.Policies[ep.TargetRef.String()]
		if policy != nil {
			for _, candidate := range policy.Candidates {
				if candidate.Binding.BindingID == conv.BindingID {
					required := candidate.RequiredCapabilities
					if conv.AgentInstanceID != uuid.Nil {
						if instance, e := s.store.RuntimeRegistry().GetAgentInstance(ctx, conv.AgentInstanceID); e == nil {
							required = instance.Capabilities
						}
					}
					return serviceBindingCapabilities(ep, candidate.Binding.Kind, required), true, nil
				}
			}
		}
	}
	if inv.RunID != nil {
		tasks, err := s.serviceTasks(ctx, *inv.RunID)
		if err != nil {
			return nil, false, err
		}
		var out serviceCapabilities
		for _, task := range tasks {
			attempts, err := s.store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{AgentTaskID: task.ID, NewestFirst: true, Limit: 1})
			if err != nil {
				return nil, false, err
			}
			if len(attempts) == 0 {
				continue
			}
			current := serviceBindingCapabilities(ep, attempts[0].BackendKind, attempts[0].RequiredCapabilities)
			if out == nil {
				out = current
			} else {
				for capability := range out {
					out[capability] = out[capability] && current[capability]
				}
			}
		}
		if out != nil {
			return out, true, nil
		}
	}
	return guaranteedServiceCapabilities(contract), false, nil
}

func (s *Server) getServiceInvocationCapabilities(c *gin.Context) {
	inv, ep, ok := s.loadServiceInvocation(c, "read")
	if !ok {
		return
	}
	inv, err := s.refreshServiceInvocation(c, inv.ID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	contract, err := serviceapi.ReadContract(inv.Contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	caps, selected, err := s.selectedServiceCapabilities(c, inv, contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	available := []string{}
	if !serviceapi.Terminal(inv.Status) && inv.Status != model.EndpointInvocationCancelRequested {
		// Cancellation before selection cancels durable admission without requiring a runtime.
		if caps["cancel"] || inv.Status == model.EndpointInvocationAccepted {
			available = append(available, "cancel")
		}
		if caps["inputs"] && (inv.IssueID != nil || inv.Mode == model.EndpointConversationMode && selected) {
			available = append(available, "inputs")
		}
		if caps["actions"] {
			if err := s.projectServiceInvocation(c, inv.ID); err != nil {
				s.writeControlPlaneError(c, err)
				return
			}
			snapshot, err := serviceJournal(s.store, ep, inv).Snapshot(c)
			if err != nil {
				s.writeControlPlaneError(c, err)
				return
			}
			if len(snapshot.Actions) > 0 {
				available = append(available, "actions")
			}
		}
		if caps["resume"] {
			resumable := false
			if inv.RunID != nil {
				run, e := s.store.Orchestration().GetRun(c, *inv.RunID)
				resumable = e == nil && run.State == model.RunPaused
			} else if s.product != nil {
				if turn, e := s.nativeServiceTurn(c, ep, inv); e == nil {
					raw, e := s.product.ManagedServiceRequest(c, turn.Owner, inv.SessionID, "GET", "/turns/"+url.PathEscape(turn.ID), "", nil)
					var state struct {
						Status string `json:"status"`
					}
					if e == nil && json.Unmarshal(raw, &state) == nil {
						resumable = state.Status == "interrupted"
					}
				}
			}
			if resumable {
				available = append(available, "resume")
			}
		}
	}
	key := "invocation_id"
	if contract.PublicSessionID != uuid.Nil {
		key = "turn_id"
	}
	c.JSON(200, gin.H{key: inv.ID, "status": inv.Status, "binding_selected": selected, "capabilities": caps, "available_commands": available})
}

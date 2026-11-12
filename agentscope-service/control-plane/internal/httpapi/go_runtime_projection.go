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
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	serviceapi "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/invocation"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/sessionapi"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

func safelyManagedContract(contract *serviceapi.Contract) bool {
	if contract.Endpoint.TargetType != model.EndpointTargetAgent {
		return false
	}
	policy := contract.Policies[contract.Endpoint.TargetRef.String()]
	if policy == nil || len(policy.Candidates) == 0 {
		return false
	}
	for _, candidate := range policy.Candidates {
		if candidate.Binding.Kind != model.DataPlaneManaged {
			return false
		}
	}
	return true
}

func frozenWorkflowDependencies(contract *serviceapi.Contract, raw json.RawMessage) ([]string, error) {
	var spec struct {
		Nodes []struct {
			Type     model.RunNodeType `json:"type"`
			Agent    string            `json:"agentId"`
			Team     string            `json:"teamRef"`
			Revision string            `json:"definitionRevisionId"`
		} `json:"nodes"`
	}
	if err := json.Unmarshal(raw, &spec); err != nil {
		return nil, err
	}
	out := []string{}
	for _, node := range spec.Nodes {
		switch node.Type {
		case model.RunNodeAgent:
			out = append(out, "agent:"+node.Agent)
		case model.RunNodeTeam:
			out = append(out, "team:"+node.Team)
		case model.RunNodeSubrun:
			revision := contract.Revisions[node.Revision]
			if revision == nil {
				return nil, fmt.Errorf("frozen Workflow revision is missing")
			}
			out = append(out, "workflow:"+revision.DefinitionID.String())
		}
	}
	return out, nil
}

// Current grants and resource availability are evaluated against this Session's
// frozen graph. Later Team membership and Workflow drafts cannot add authority.
func (s *Server) goRuntimeResources(ctx context.Context, n *model.Namespace, v *sessionapi.Session, contract *serviceapi.Contract) (map[string]model.ResourceDescriptor, error) {
	out := map[string]model.ResourceDescriptor{}
	if n == nil {
		return out, nil
	}
	if len(contract.Agents)+len(contract.Teams)+len(contract.Revisions) > 2048 {
		return nil, fmt.Errorf("runtime authorization graph exceeds 2048 resources")
	}
	for _, ref := range contract.Agents {
		id, err := uuid.Parse(ref)
		if err != nil {
			return nil, err
		}
		a, err := s.store.AgentCatalog().GetAgent(ctx, id)
		if errors.Is(err, store.ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		if a.Tenant != n.Tenant || a.Namespace != n.Name || a.Status != model.AgentActive || a.ArchivedAt != nil {
			continue
		}
		policy := contract.Policies[ref]
		if policy == nil {
			continue
		}
		managed := false
		available := false
		for _, candidate := range policy.Candidates {
			if candidate.Binding.Kind == model.DataPlaneManaged {
				managed = true
			}
			binding, err := s.store.AgentCatalog().GetBinding(ctx, candidate.Binding.BindingID)
			if errors.Is(err, store.ErrNotFound) {
				continue
			}
			if err != nil {
				return nil, err
			}
			if binding.Enabled && binding.ArchivedAt == nil && binding.AgentID == id && binding.Tenant == n.Tenant && binding.Namespace == n.Name && binding.Kind == candidate.Binding.Kind {
				available = true
			}
		}
		if !available {
			continue
		}
		if managed {
			child := *v
			child.Target = sessionapi.Target{Type: "agent", ID: id}
			resources, err := s.managedRuntimeResources(ctx, n, &child, contract)
			if err != nil {
				return nil, err
			}
			for key, value := range resources {
				out[key] = value
			}
		} else {
			out["agent:"+ref] = model.ResourceDescriptor{Kind: "agent", ID: ref, Name: a.DisplayName, Dependencies: []string{}}
		}
	}
	for ref, frozen := range contract.Teams {
		team, err := s.store.Collaboration().GetTeam(ctx, frozen.ID)
		if errors.Is(err, store.ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		if team.Tenant != n.Tenant || team.Namespace != n.Name || team.Status != model.TeamActive || team.ArchivedAt != nil {
			continue
		}
		deps := []string{"agent:" + frozen.LeaderAgentRef}
		for _, member := range frozen.Members {
			if member.ArchivedAt == nil {
				deps = append(deps, "agent:"+member.AgentRef)
			}
		}
		out["team:"+ref] = model.ResourceDescriptor{Kind: "team", ID: ref, Name: team.Name, Dependencies: deps}
	}
	for _, revision := range contract.Revisions {
		ref := revision.DefinitionID.String()
		key := "workflow:" + ref
		definition, err := s.store.Orchestration().GetDefinition(ctx, revision.DefinitionID)
		if errors.Is(err, store.ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		if definition.Tenant != n.Tenant || definition.Namespace != n.Name || definition.ArchivedAt != nil {
			continue
		}
		resource := out[key]
		resource.Kind = "workflow"
		resource.ID = ref
		resource.Name = definition.Name
		deps, err := frozenWorkflowDependencies(contract, revision.Spec)
		if err != nil {
			return nil, err
		}
		resource.Dependencies = append(resource.Dependencies, deps...)
		out[key] = resource
	}
	return out, nil
}

func (s *Server) goRuntimeConfiguration(ctx context.Context, v *sessionapi.Session, contract *serviceapi.Contract) (gin.H, error) {
	owner := strings.TrimPrefix(v.Principal, "platform-user:")
	if v.ApplicationID != nil {
		app, err := s.store.Applications().Get(ctx, *v.ApplicationID)
		if err != nil {
			return nil, err
		}
		owner = app.OwnerUserID
	} else if !strings.HasPrefix(v.Principal, "platform-user:") {
		return nil, fmt.Errorf("runtime configuration has no accountable owner")
	}
	return gin.H{"session": gin.H{"id": v.ID.String(), "ownerId": owner, "environmentId": contract.EnvironmentID,
		"createdAt": v.CreatedAt.UnixMilli(), "updatedAt": v.UpdatedAt.UnixMilli()}, "contract": contract}, nil
}

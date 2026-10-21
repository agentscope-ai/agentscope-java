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
	"slices"
	"strings"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/product"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

// Authorization follows the frozen definition and selected mounts; edits to an unrelated Agent
// or later definition cannot expand an existing Session's dependency graph.
func (s *Server) managedRuntimeResources(ctx context.Context, n *model.Namespace, v *sessionapi.Session, contract *serviceapi.Contract) (map[string]model.ResourceDescriptor, error) {
	out := map[string]model.ResourceDescriptor{}
	if n == nil {
		return out, nil
	}
	agent, err := s.store.AgentCatalog().GetAgent(ctx, v.Target.ID)
	if errors.Is(err, store.ErrNotFound) {
		return out, nil
	}
	if err != nil {
		return nil, err
	}
	if agent.Tenant != n.Tenant || agent.Namespace != n.Name || agent.Status != model.AgentActive || agent.ArchivedAt != nil {
		return out, nil
	}
	root := model.ResourceDescriptor{Kind: "agent", ID: v.Target.ID.String(), Name: agent.DisplayName, Dependencies: []string{}}
	frozen := contract.Policies[root.ID]
	if frozen == nil {
		return nil, fmt.Errorf("Managed Session has no frozen runtime policy")
	}
	dependencies, err := managedFrozenDependencies(contract, root.ID)
	if err != nil {
		return nil, err
	}
	for _, candidate := range frozen.Candidates {
		binding := candidate.Binding
		if binding.Kind != model.DataPlaneManaged {
			continue
		}
		if binding.ManagedOwnerRef != "" && binding.ManagedOwnerRef != namespaceResourceOwner(n) {
			return nil, fmt.Errorf("Managed binding has a foreign resource owner")
		}
		ref := binding.ManagedDefinitionRef
		if ref == "" {
			ref = root.ID
		}
		root.Dependencies = append(root.Dependencies, "managed-agent:"+ref)
		if binding.BindingID != uuid.Nil {
			live, e := s.store.AgentCatalog().GetBinding(ctx, binding.BindingID)
			if errors.Is(e, store.ErrNotFound) {
				continue
			}
			if e != nil {
				return nil, e
			}
			if !live.Enabled || live.ArchivedAt != nil || live.AgentID != v.Target.ID || live.Tenant != n.Tenant || live.Namespace != n.Name || live.Kind != binding.Kind {
				continue
			}
		}
		if s.product == nil {
			if !s.localDev {
				return nil, fmt.Errorf("Managed resource directory unavailable")
			}
			continue
		}
		definition, e := s.product.ResourceIdentity(ctx, namespaceResourceOwner(n), "managed-agent", ref)
		if errors.Is(e, store.ErrNotFound) {
			continue
		}
		if e != nil {
			return nil, e
		}
		definition.Dependencies = dependencies
		out[definition.Key()] = definition
	}
	for _, key := range dependencies {
		kind, id, ok := strings.Cut(key, ":")
		if !ok {
			return nil, fmt.Errorf("invalid Managed dependency")
		}
		if s.product == nil {
			if s.localDev {
				continue
			}
			return nil, fmt.Errorf("Managed resource directory unavailable")
		}
		resource, e := s.product.ResourceIdentity(ctx, namespaceResourceOwner(n), kind, id)
		if errors.Is(e, store.ErrNotFound) {
			continue
		}
		if e != nil {
			return nil, e
		}
		out[key] = resource
	}
	out[root.Key()] = root
	return out, nil
}

func managedFrozenDependencies(contract *serviceapi.Contract, agent string) ([]string, error) {
	var definition, options map[string]json.RawMessage
	if raw := contract.Definitions[agent]; len(raw) > 0 {
		if err := json.Unmarshal(raw, &definition); err != nil {
			return nil, err
		}
	}
	if len(contract.SessionOptions) > 0 {
		if err := json.Unmarshal(contract.SessionOptions, &options); err != nil {
			return nil, err
		}
	}
	dependencies := []string{}
	add := func(kind, id string) {
		if strings.TrimSpace(id) != "" {
			dependencies = append(dependencies, kind+":"+id)
		}
	}
	var workspace string
	_ = json.Unmarshal(definition["workspaceId"], &workspace)
	add("workspace", workspace)
	add("environment", contract.EnvironmentID)
	for _, field := range []struct{ selected, defaultField, kind string }{{"memoryStoreIds", "defaultMemoryStoreIds", "memory"}, {"vaultIds", "defaultVaultIds", "vault"}} {
		raw, present := options[field.selected]
		if !present {
			raw = definition[field.defaultField]
		}
		if len(raw) == 0 {
			continue
		}
		var ids []string
		if err := json.Unmarshal(raw, &ids); err != nil {
			return nil, err
		}
		for _, id := range ids {
			add(field.kind, id)
		}
	}
	var mcp any
	_ = json.Unmarshal(definition["mcpServers"], &mcp)
	var overrides map[string]json.RawMessage
	_ = json.Unmarshal(options["agentOverrides"], &overrides)
	if raw, present := overrides["mcpServers"]; present {
		if err := json.Unmarshal(raw, &mcp); err != nil {
			return nil, err
		}
	}
	dependencies = append(dependencies, product.ResourceVaultRefs(mcp)...)
	slices.Sort(dependencies)
	dependencies = slices.Compact(dependencies)
	if len(dependencies) > 256 {
		return nil, fmt.Errorf("Managed authorization graph exceeds 256 dependencies")
	}
	return dependencies, nil
}

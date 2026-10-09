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
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

// Freeze follows declared references, including nested Workflow revisions. It
// does not interpret arbitrary strings inside a task's business input as refs.
func Freeze(ctx context.Context, st store.Store, ep *model.Endpoint) (json.RawMessage, error) {
	c := Contract{Endpoint: *ep, Policies: map[string]*model.AgentRuntimePolicy{}, Definitions: map[string]json.RawMessage{}, Teams: map[string]*model.CollaborationTeam{}, Revisions: map[string]*model.OrchestrationRevision{}}
	agents := map[string]bool{}
	addAgent := func(ref string) error {
		id, err := uuid.Parse(ref)
		if err != nil || id == uuid.Nil {
			return fmt.Errorf("invalid published Agent reference %q", ref)
		}
		agents[id.String()] = true
		return nil
	}
	addTeam := func(id uuid.UUID) error {
		if c.Teams[id.String()] != nil {
			return nil
		}
		team, err := st.Collaboration().GetTeam(ctx, id)
		if err != nil {
			return err
		}
		if team.Tenant != ep.Tenant || team.Namespace != ep.Namespace {
			return store.ErrForbidden
		}
		c.Teams[id.String()] = team
		if err = addAgent(team.LeaderAgentRef); err != nil {
			return err
		}
		for _, m := range team.Members {
			if err = addAgent(m.AgentRef); err != nil {
				return err
			}
		}
		return nil
	}
	visiting := map[uuid.UUID]bool{}
	var addRevision func(uuid.UUID) error
	addRevision = func(id uuid.UUID) error {
		if visiting[id] {
			return fmt.Errorf("published Workflow dependency cycle at %s", id)
		}
		if c.Revisions[id.String()] != nil {
			return nil
		}
		if len(c.Revisions) >= 1024 {
			return fmt.Errorf("published Workflow exceeds 1024 revisions")
		}
		r, err := st.Orchestration().GetRevision(ctx, id)
		if err != nil {
			return err
		}
		if r.Tenant != ep.Tenant || r.Namespace != ep.Namespace {
			return store.ErrForbidden
		}
		c.Revisions[id.String()] = r
		visiting[id] = true
		defer delete(visiting, id)
		var spec struct {
			Nodes []struct {
				Type     model.RunNodeType `json:"type"`
				AgentID  string            `json:"agentId"`
				TeamRef  string            `json:"teamRef"`
				Revision string            `json:"definitionRevisionId"`
			} `json:"nodes"`
		}
		if err = json.Unmarshal(r.Spec, &spec); err != nil {
			return err
		}
		for _, n := range spec.Nodes {
			switch n.Type {
			case model.RunNodeAgent:
				err = addAgent(n.AgentID)
			case model.RunNodeTeam:
				var ref uuid.UUID
				ref, err = uuid.Parse(n.TeamRef)
				if err == nil {
					err = addTeam(ref)
				}
			case model.RunNodeSubrun:
				var ref uuid.UUID
				ref, err = uuid.Parse(n.Revision)
				if err == nil {
					err = addRevision(ref)
				}
			}
			if err != nil {
				return err
			}
		}
		return nil
	}
	var err error
	switch ep.TargetType {
	case model.EndpointTargetAgent:
		err = addAgent(ep.TargetRef.String())
	case model.EndpointTargetTeam:
		err = addTeam(ep.TargetRef)
	case model.EndpointTargetOrchestrationRevision:
		err = addRevision(ep.TargetRef)
	default:
		err = fmt.Errorf("unsupported published target %q", ep.TargetType)
	}
	if err != nil {
		return nil, err
	}
	for id := range agents {
		c.Agents = append(c.Agents, id)
		policy, err := st.Orchestration().GetRuntimePolicy(ctx, ep.Tenant, ep.Namespace, id)
		if err != nil && !errors.Is(err, store.ErrNotFound) {
			return nil, err
		}
		if policy != nil {
			c.Policies[id] = policy
		}
	}
	sort.Strings(c.Agents)
	if err = CheckSchema(ep.InputSchema); err != nil {
		return nil, fmt.Errorf("input schema: %w", err)
	}
	if err = CheckSchema(ep.OutputSchema); err != nil {
		return nil, fmt.Errorf("output schema: %w", err)
	}
	if err = CheckResultMapping(ep.ResultMapping); err != nil {
		return nil, err
	}
	return json.Marshal(c)
}

func Revision(ctx context.Context, st store.Store, issueID, revisionID uuid.UUID) (*model.OrchestrationRevision, error) {
	c, err := ContractForIssue(ctx, st, issueID)
	if err != nil {
		return nil, err
	}
	if c != nil {
		if revision := c.Revisions[revisionID.String()]; revision != nil {
			return revision, nil
		}
		return nil, fmt.Errorf("Workflow revision %s is not in the published contract", revisionID)
	}
	// Internal runs outside a published service use the immutable revision store.
	return st.Orchestration().GetRevision(ctx, revisionID)
}

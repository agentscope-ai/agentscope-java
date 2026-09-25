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
	"fmt"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func (s *Server) authorizeExecutionResources(c *gin.Context, body map[string]json.RawMessage) bool {
	a := accessFrom(c)
	if a == nil || len(a.Namespace.Resources) == 0 {
		return true
	}
	value := func(k string) string { var v string; _ = json.Unmarshal(body[k], &v); return v }
	keys := []string{}
	kind, id, action := resourceRoute(c)
	if kind != "" && id != "" && (action == "use" || action == "publish") && !(kind == "workflow" && action == "use") {
		keys = append(keys, kind+":"+id)
	}
	if c.Request.Method != "GET" {
		if kind := value("assigneeType"); kind == "agent" || kind == "team" {
			if id := value("assigneeRef"); id != "" {
				keys = append(keys, kind+":"+id)
			}
		}
		if id := value("agentId"); id != "" {
			keys = append(keys, "agent:"+id)
		}
	}
	for _, key := range keys {
		items, e := s.resourceInventory(c.Request.Context(), a.Namespace)
		rootAction := "use"
		if action == "publish" && key == kind+":"+id {
			rootAction = "publish"
		}
		if e == nil {
			e = checkResourceGraphAction(a.Namespace, resourceMap(items), a.User, key, rootAction)
		}
		if e != nil {
			c.AbortWithStatusJSON(403, ErrorResponse{Error: e.Error()})
			return false
		}
	}
	return true
}
func (s *Server) authorizeTaskResources(ctx context.Context, task *model.AgentTask, candidate model.RuntimeBindingCandidate) error {
	n, e := s.store.Access().GetNamespace(ctx, task.Tenant, task.Namespace)
	if e == store.ErrNotFound {
		return nil
	}
	if e != nil {
		return e
	}
	if len(n.Resources) == 0 {
		return nil
	}
	user := task.AccountableHumanRef
	if user == "" && task.Originator.Type == model.ActorHuman {
		user = task.Originator.Ref
	}
	if user == "" {
		return fmt.Errorf("resource policy requires an accountable user")
	}
	inventory, e := s.resourceInventory(ctx, n)
	if e != nil {
		return e
	}
	items := resourceMap(inventory)
	// Use the selected binding, not unrelated enabled alternatives.
	if candidate.Binding.Kind == model.DataPlaneManaged {
		if candidate.Binding.ManagedOwnerRef != namespaceResourceOwner(n) {
			return fmt.Errorf("managed binding is outside the task namespace")
		}
		key := "agent:" + task.AgentRef
		item := items[key]
		item.Dependencies = []string{"managed-agent:" + candidate.Binding.ManagedDefinitionRef}
		items[key] = item
	}
	// Team composition is immutable for a Run; authorization uses current grants.
	if task.OrchestrationRunID != uuid.Nil {
		snapshots, err := s.store.Orchestration().ListTeamSnapshots(ctx, task.OrchestrationRunID)
		if err != nil {
			return err
		}
		for _, snapshot := range snapshots {
			var team model.CollaborationTeam
			if err := json.Unmarshal(snapshot.Snapshot, &team); err != nil {
				return err
			}
			key := "team:" + snapshot.TeamID.String()
			item, exists := items[key]
			if !exists {
				continue
			}
			item.Dependencies = []string{"agent:" + team.LeaderAgentRef}
			for _, member := range team.Members {
				if member.ArchivedAt == nil {
					item.Dependencies = append(item.Dependencies, "agent:"+member.AgentRef)
				}
			}
			items[key] = item
		}
	}
	root := "agent:" + task.AgentRef
	if task.TeamID != nil {
		root = "team:" + task.TeamID.String()
	}
	if task.OrchestrationRunID != uuid.Nil {
		run, e := s.store.Orchestration().GetRun(ctx, task.OrchestrationRunID)
		if e != nil {
			return e
		}
		if run.DefinitionRevisionID != nil {
			revision, e := s.store.Orchestration().GetRevision(ctx, *run.DefinitionRevisionID)
			if e != nil {
				return e
			}
			root = "workflow:" + revision.DefinitionID.String()
			if err := s.applyPublishedResourceDependencies(ctx, n, items, revision, map[uuid.UUID]bool{}); err != nil {
				return err
			}
		}
	}
	if e := checkResourceGraph(n, items, user, root); e != nil {
		return e
	}
	// Dynamically delegated agents are not automatically covered by a root's
	// authorization. They need a verified dependency path or their own use grant.
	target := "agent:" + task.AgentRef
	if !resourceReachable(items, root, target, map[string]bool{}) {
		return checkResourceGraph(n, items, user, target)
	}
	return nil
}
func resourceReachable(items map[string]model.ResourceDescriptor, root, target string, seen map[string]bool) bool {
	if root == target {
		return true
	}
	if seen[root] {
		return false
	}
	seen[root] = true
	for _, dep := range items[root].Dependencies {
		if resourceReachable(items, dep, target, seen) {
			return true
		}
	}
	return false
}
func excludedResourceIDs(c *gin.Context, kind string) []uuid.UUID {
	ids := []uuid.UUID{}
	a := accessFrom(c)
	if a == nil {
		return ids
	}
	for key := range a.Namespace.Resources {
		k, id, ok := model.ResourceKey(key)
		if ok && k == kind && !a.Namespace.Decide(a.User, key, "discover").Allowed {
			if u, e := uuid.Parse(id); e == nil {
				ids = append(ids, u)
			}
		}
	}
	return ids
}

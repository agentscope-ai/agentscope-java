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
	"slices"
	"sort"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/orchestration"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

func (s *Server) resourceInventory(ctx context.Context, n *model.Namespace) ([]model.ResourceDescriptor, error) {
	out := []model.ResourceDescriptor{}
	for offset := 0; ; offset += 500 {
		agents, err := s.store.AgentCatalog().ListAgents(ctx, store.AgentFilter{Tenant: n.Tenant, Namespace: n.Name, Limit: 500, Offset: offset})
		if err != nil {
			return nil, err
		}
		for _, a := range agents {
			r := model.ResourceDescriptor{Kind: "agent", ID: a.ID.String(), Name: a.DisplayName, Dependencies: []string{}}
			bs, e := s.store.AgentCatalog().ListBindings(ctx, a.ID, false)
			if e != nil {
				return nil, e
			}
			for _, b := range bs {
				if b.Enabled && b.Kind == model.DataPlaneManaged {
					var cfg model.ManagedBindingConfiguration
					if json.Unmarshal(b.Configuration, &cfg) == nil {
						if cfg.OwnerRef != namespaceResourceOwner(n) {
							return nil, fmt.Errorf("Agent %s has a foreign managed binding", a.DisplayName)
						}
						r.Dependencies = append(r.Dependencies, "managed-agent:"+cfg.ManagedDefinitionRef)
					}
				}
			}
			out = append(out, r)
		}
		if len(agents) < 500 {
			break
		}
	}
	teams, err := s.store.Collaboration().ListTeams(ctx, n.Tenant, n.Name)
	if err != nil {
		return nil, err
	}
	for _, t := range teams {
		deps := []string{"agent:" + t.LeaderAgentRef}
		for _, m := range t.Members {
			if m.ArchivedAt == nil {
				deps = append(deps, "agent:"+m.AgentRef)
			}
		}
		out = append(out, model.ResourceDescriptor{Kind: "team", ID: t.ID.String(), Name: t.Name, Dependencies: deps})
	}
	for offset := 0; ; offset += 500 {
		definitions, e := s.store.Orchestration().ListDefinitions(ctx, store.OrchestrationDefinitionFilter{Tenant: n.Tenant, Namespace: n.Name, Limit: 500, Offset: offset})
		if e != nil {
			return nil, e
		}
		for _, d := range definitions {
			deps, err := s.workflowResourceDependencies(ctx, n, d.DraftSpec)
			if err != nil {
				return nil, err
			}
			out = append(out, model.ResourceDescriptor{Kind: "workflow", ID: d.ID.String(), Name: d.Name, Dependencies: deps})
		}
		if len(definitions) < 500 {
			break
		}
	}
	if s.product != nil {
		items, e := s.product.ResourceInventory(ctx, namespaceResourceOwner(n))
		if e != nil {
			return nil, e
		}
		out = append(out, items...)
	}
	if s.client != nil {
		var models v1alpha1.ModelConfigList
		if e := s.client.List(ctx, &models, client.InNamespace(n.Name)); e != nil {
			return nil, e
		}
		for _, v := range models.Items {
			out = append(out, model.ResourceDescriptor{Kind: "model", ID: v.Name, Name: v.Name, Dependencies: []string{}})
		}
		var mcps v1alpha1.MCPServerList
		if e := s.client.List(ctx, &mcps, client.InNamespace(n.Name)); e != nil {
			return nil, e
		}
		for _, v := range mcps.Items {
			out = append(out, model.ResourceDescriptor{Kind: "mcp", ID: v.Name, Name: v.Name, Dependencies: []string{}})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Key() < out[j].Key() })
	for i := range out {
		slices.Sort(out[i].Dependencies)
		out[i].Dependencies = slices.Compact(out[i].Dependencies)
	}
	return out, nil
}
func definitionDependencies(raw json.RawMessage) []string {
	var value any
	_ = json.Unmarshal(raw, &value)
	deps := []string{}
	var walk func(any)
	walk = func(v any) {
		switch x := v.(type) {
		case map[string]any:
			for k, v := range x {
				if id, ok := v.(string); ok && id != "" {
					switch k {
					case "agentId", "agentRef", "leaderAgentId", "leaderAgentRef":
						deps = append(deps, "agent:"+id)
					case "teamRef", "teamId":
						deps = append(deps, "team:"+id)
					case "assigneeRef":
						if kind, ok := x["assigneeType"].(string); ok && (kind == "agent" || kind == "team") {
							deps = append(deps, kind+":"+id)
						}
					}
				}
				// Only structural objects; prompts, input data and tools' arbitrary payloads
				// are not interpreted as dependency declarations.
				if slices.Contains([]string{"nodes", "members", "spec", "execution", "definition", "children"}, k) {
					walk(v)
				}
			}
		case []any:
			for _, v := range x {
				walk(v)
			}
		}
	}
	walk(value)
	slices.Sort(deps)
	return slices.Compact(deps)
}
func resourceMap(items []model.ResourceDescriptor) map[string]model.ResourceDescriptor {
	m := map[string]model.ResourceDescriptor{}
	for _, r := range items {
		m[r.Key()] = r
	}
	return m
}

func checkResourceGraph(n *model.Namespace, items map[string]model.ResourceDescriptor, user, key string) error {
	return checkResourceGraphAction(n, items, user, key, "use")
}
func checkResourceGraphAction(n *model.Namespace, items map[string]model.ResourceDescriptor, user, key, rootAction string) error {
	visiting := map[string]bool{}
	checked := map[string]bool{}
	var visit func(string, string) error
	visit = func(key, parent string) error {
		r, ok := items[key]
		if !ok {
			return fmt.Errorf("Dependency %s is unavailable", key)
		}
		delegated := parent != "" && slices.Contains(n.Resources[key].Consumers, parent)
		action := "use"
		if parent == "" {
			action = rootAction
		}
		if !delegated && !n.Decide(user, key, action).Allowed {
			return fmt.Errorf("%s permission required for %s", action, key)
		}
		if visiting[key] {
			return fmt.Errorf("Dependency cycle at %s", key)
		}
		if checked[key] {
			return nil
		}
		visiting[key] = true
		for _, dep := range r.Dependencies {
			if err := visit(dep, key); err != nil {
				return err
			}
		}
		visiting[key] = false
		checked[key] = true
		return nil
	}
	return visit(key, "")
}
func (s *Server) checkResourceUse(ctx context.Context, n *model.Namespace, user, key string) error {
	items, err := s.resourceInventory(ctx, n)
	if err != nil {
		return err
	}
	return checkResourceGraph(n, resourceMap(items), user, key)
}
func (s *Server) resourceNamespace(c *gin.Context) (*model.Namespace, bool) {
	n, err := s.store.Access().GetNamespace(c.Request.Context(), s.defaultTenant, c.Param("namespaceName"))
	if err != nil || len(n.Roles(c.GetString("userId"))) == 0 && !roleSet(c)["admin"] {
		s.accessFailure(c, store.ErrNotFound)
		return nil, false
	}
	return n, true
}
func (s *Server) listNamespaceResources(c *gin.Context) {
	n, ok := s.resourceNamespace(c)
	if !ok {
		return
	}
	items, err := s.resourceInventory(c.Request.Context(), n)
	if err != nil {
		c.JSON(503, ErrorResponse{Error: err.Error()})
		return
	}
	views := []gin.H{}
	user := c.GetString("userId")
	for _, r := range items {
		if !n.Decide(user, r.Key(), "discover").Allowed && !n.Decide(user, r.Key(), "manage").Allowed && !roleSet(c)["admin"] {
			continue
		}
		actions := []string{}
		for _, action := range model.ResourceActions {
			if n.Decide(user, r.Key(), action).Allowed {
				actions = append(actions, action)
			}
		}
		// The resource catalog is a safe projection; dependency identifiers are
		// shown only to callers allowed to inspect or manage this resource.
		if !n.Decide(user, r.Key(), "inspect").Allowed && !n.Decide(user, r.Key(), "manage").Allowed && !roleSet(c)["admin"] {
			r.Dependencies = nil
		}
		views = append(views, gin.H{"resource": r, "actions": actions})
	}
	c.JSON(200, gin.H{"items": views, "version": n.Version})
}
func (s *Server) getResourceAccess(c *gin.Context) {
	n, ok := s.resourceNamespace(c)
	if !ok {
		return
	}
	key := c.Param("kind") + ":" + c.Param("resourceId")
	user := c.GetString("userId")
	items, e := s.resourceInventory(c.Request.Context(), n)
	if e != nil {
		c.JSON(503, ErrorResponse{Error: e.Error()})
		return
	}
	r, exists := resourceMap(items)[key]
	if !exists {
		s.accessFailure(c, store.ErrNotFound)
		return
	}
	manager := n.Decide(user, key, "manage").Allowed || roleSet(c)["admin"]
	if !n.Decide(user, key, "discover").Allowed && !manager {
		s.accessFailure(c, store.ErrNotFound)
		return
	}
	decisions := []model.AccessDecision{}
	for _, action := range model.ResourceActions {
		decisions = append(decisions, n.Decide(user, key, action))
	}
	out := gin.H{"resource": r, "decisions": decisions, "version": n.Version, "canManage": manager}
	if manager {
		p, ok := n.Resources[key]
		if !ok {
			p.Mode = "inherit"
		}
		out["policy"] = p
		out["groups"] = n.Groups
		dependents := []model.ResourceDescriptor{}
		for _, other := range items {
			if slices.Contains(other.Dependencies, key) {
				dependents = append(dependents, other)
			}
		}
		out["dependents"] = dependents
	}
	if !manager && !n.Decide(user, key, "inspect").Allowed {
		r.Dependencies = nil
		out["resource"] = r
	}
	if n.Decide(user, key, "use").Allowed {
		if e := checkResourceGraph(n, resourceMap(items), user, key); e != nil {
			out["dependencyError"] = e.Error()
		}
	}
	c.JSON(200, out)
}
func (s *Server) updateResourceAccess(c *gin.Context) {
	n, ok := s.resourceNamespace(c)
	if !ok {
		return
	}
	key := c.Param("kind") + ":" + c.Param("resourceId")
	user := c.GetString("userId")
	if !n.Decide(user, key, "manage").Allowed && !roleSet(c)["admin"] {
		s.accessFailure(c, store.ErrNotFound)
		return
	}
	var in struct {
		Version int64                `json:"version"`
		Policy  model.ResourcePolicy `json:"policy"`
	}
	if c.ShouldBindJSON(&in) != nil || in.Version <= 0 {
		c.JSON(400, ErrorResponse{Error: "Policy and expected version required"})
		return
	}
	inventory, e := s.resourceInventory(c.Request.Context(), n)
	if e != nil {
		c.JSON(503, ErrorResponse{Error: e.Error()})
		return
	}
	resources := resourceMap(inventory)
	if _, ok := resources[key]; !ok {
		s.accessFailure(c, store.ErrNotFound)
		return
	}
	for _, consumer := range in.Policy.Consumers {
		r, ok := resources[consumer]
		if !ok || !slices.Contains(r.Dependencies, key) {
			c.JSON(400, ErrorResponse{Error: "Consumer must actually reference this resource"})
			return
		}
	}
	if !slices.Equal(in.Policy.ExportTo, n.Resources[key].ExportTo) && !n.Decide(user, key, "publish").Allowed && !roleSet(c)["admin"] {
		c.JSON(403, ErrorResponse{Error: "Publish permission required to change exports"})
		return
	}
	for _, target := range in.Policy.ExportTo {
		dest, e := s.store.Access().GetNamespace(c.Request.Context(), n.Tenant, target)
		if e != nil || dest.Archived {
			c.JSON(400, ErrorResponse{Error: "Export namespace is unavailable"})
			return
		}
	}
	if n.Resources == nil {
		n.Resources = map[string]model.ResourcePolicy{}
	}
	n.Resources[key] = in.Policy
	if e = n.Validate(); e != nil {
		c.JSON(400, ErrorResponse{Error: e.Error()})
		return
	}
	for id := range in.Policy.Users {
		if len(n.Roles(id)) == 0 {
			c.JSON(400, ErrorResponse{Error: "Resource grantees must be namespace members"})
			return
		}
	}
	updated, e := s.store.Access().PutNamespace(c.Request.Context(), n, in.Version, user)
	if e != nil {
		s.writeCollaborationError(c, e)
		return
	}
	c.JSON(200, gin.H{"version": updated.Version, "policy": in.Policy})
}

// resourceRoute identifies resource operations, independently of work ACLs.
func resourceRoute(c *gin.Context) (kind, id, action string) {
	path := strings.TrimPrefix(c.Request.URL.Path, "/api/v1/")
	prefix, _, _ := strings.Cut(path, "/")
	switch prefix {
	case "agents", "agent-runtime-policies":
		kind = "agent"
		id = c.Param("agentId")
	case "teams":
		kind = "team"
		id = c.Param("teamId")
	case "orchestration-definitions":
		kind = "workflow"
		id = c.Param("definitionId")
	case "modelconfigs":
		kind = "model"
		id = c.Param("name")
	case "mcpservers":
		kind = "mcp"
		id = c.Param("name")
	}
	action = "edit"
	if c.Request.Method == "GET" || c.Request.Method == "HEAD" {
		action = "inspect"
		if (kind == "agent" || kind == "team") && !strings.Contains(strings.TrimPrefix(path, prefix+"/"), "/") {
			action = "discover"
		}
	}
	if strings.HasSuffix(path, "/publish") {
		action = "publish"
	}
	if strings.HasSuffix(path, "/runs") && c.Request.Method == "POST" {
		action = "use"
	}
	return
}

// Nested definitions retain their namespace boundary and are real dependencies.
func (s *Server) workflowResourceDependencies(ctx context.Context, n *model.Namespace, raw json.RawMessage) ([]string, error) {
	deps := definitionDependencies(raw)
	var spec orchestration.DefinitionSpec
	if json.Unmarshal(raw, &spec) != nil {
		return deps, nil
	}
	for _, node := range spec.Nodes {
		if node.DefinitionRevID == "" {
			continue
		}
		id, err := uuid.Parse(node.DefinitionRevID)
		if err != nil {
			return nil, err
		}
		revision, err := s.store.Orchestration().GetRevision(ctx, id)
		if err != nil {
			return nil, err
		}
		if revision.Tenant != n.Tenant || revision.Namespace != n.Name {
			return nil, fmt.Errorf("nested Workflow is outside the namespace")
		}
		deps = append(deps, "workflow:"+revision.DefinitionID.String())
	}
	return deps, nil
}
func (s *Server) applyPublishedResourceDependencies(ctx context.Context, n *model.Namespace, items map[string]model.ResourceDescriptor, revision *model.OrchestrationRevision, seen map[uuid.UUID]bool) error {
	if seen[revision.ID] {
		return nil
	}
	alreadyResolved := false
	for id := range seen {
		prior, err := s.store.Orchestration().GetRevision(ctx, id)
		if err != nil {
			return err
		}
		if prior.DefinitionID == revision.DefinitionID {
			alreadyResolved = true
			break
		}
	}
	seen[revision.ID] = true
	key := "workflow:" + revision.DefinitionID.String()
	item, exists := items[key]
	if !exists {
		return fmt.Errorf("Workflow is unavailable")
	}
	deps, err := s.workflowResourceDependencies(ctx, n, revision.Spec)
	if err != nil {
		return err
	}
	if alreadyResolved {
		deps = append(item.Dependencies, deps...)
	}
	item.Dependencies = deps
	items[key] = item
	var spec orchestration.DefinitionSpec
	if err := json.Unmarshal(revision.Spec, &spec); err != nil {
		return err
	}
	for _, node := range spec.Nodes {
		if node.DefinitionRevID == "" {
			continue
		}
		id, err := uuid.Parse(node.DefinitionRevID)
		if err != nil {
			return err
		}
		child, err := s.store.Orchestration().GetRevision(ctx, id)
		if err != nil {
			return err
		}
		if err := s.applyPublishedResourceDependencies(ctx, n, items, child, seen); err != nil {
			return err
		}
	}
	return nil
}

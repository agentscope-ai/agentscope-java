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
	"encoding/json"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/orchestration"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"slices"
	"strings"
)

func (s *Server) sharedWorkflowTemplates(c *gin.Context) {
	target, ok := s.resourceNamespace(c)
	if !ok {
		return
	}
	if len(target.Roles(c.GetString("userId"))) == 0 {
		s.accessFailure(c, store.ErrNotFound)
		return
	}
	spaces, e := s.allManagedNamespaces(c.Request.Context())
	if e != nil {
		s.accessFailure(c, e)
		return
	}
	items := []gin.H{}
	for _, source := range spaces {
		if source.Archived || source.Name == target.Name {
			continue
		}
		for key, p := range source.Resources {
			kind, id, ok := model.ResourceKey(key)
			if !ok || kind != "workflow" || !slices.Contains(p.ExportTo, target.Name) {
				continue
			}
			uid, e := uuid.Parse(id)
			if e != nil {
				continue
			}
			d, e := s.store.Orchestration().GetDefinition(c.Request.Context(), uid)
			if e != nil || d.ArchivedAt != nil || d.Namespace != source.Name || d.Tenant != source.Tenant {
				continue
			}
			revisions, e := s.store.Orchestration().ListRevisions(c.Request.Context(), uid)
			if e != nil {
				c.JSON(503, ErrorResponse{Error: "Unable to load published templates"})
				return
			}
			if len(revisions) == 0 {
				continue
			}
			latest := revisions[0]
			for _, r := range revisions {
				if r.Revision > latest.Revision {
					latest = r
				}
			}
			items = append(items, gin.H{"sourceNamespace": source.Name, "id": id, "name": d.Name, "description": d.Description, "revisionId": latest.ID, "revision": latest.Revision, "sourceVersion": source.Version, "dependencies": definitionDependencies(latest.Spec)})
		}
	}
	c.JSON(200, gin.H{"items": items, "canImport": model.NamespaceAllows(target.Roles(c.GetString("userId")), "configure")})
}
func (s *Server) importWorkflowTemplate(c *gin.Context) {
	target, ok := s.resourceNamespace(c)
	if !ok {
		return
	}
	user := c.GetString("userId")
	if !model.NamespaceAllows(target.Roles(user), "configure") {
		c.JSON(403, ErrorResponse{Error: "Target namespace development permission required"})
		return
	}
	var in struct {
		SourceNamespace string            `json:"sourceNamespace"`
		ID              string            `json:"id"`
		RevisionID      string            `json:"revisionId"`
		SourceVersion   int64             `json:"sourceVersion"`
		Name            string            `json:"name"`
		Bindings        map[string]string `json:"bindings"`
	}
	if c.ShouldBindJSON(&in) != nil || strings.TrimSpace(in.Name) == "" || len(in.Name) > 200 {
		c.JSON(400, ErrorResponse{Error: "Template, revision and destination name required"})
		return
	}
	source, e := s.store.Access().GetNamespace(c.Request.Context(), target.Tenant, in.SourceNamespace)
	if e != nil || source.Archived || !slices.Contains(source.Resources["workflow:"+in.ID].ExportTo, target.Name) {
		s.accessFailure(c, store.ErrNotFound)
		return
	}
	if source.Version != in.SourceVersion {
		c.JSON(409, ErrorResponse{Error: "Source authorization changed; reload the template"})
		return
	}
	revID, e := uuid.Parse(in.RevisionID)
	if e != nil {
		c.JSON(400, ErrorResponse{Error: "Invalid revision"})
		return
	}
	revision, e := s.store.Orchestration().GetRevision(c.Request.Context(), revID)
	if e != nil || revision.DefinitionID.String() != in.ID || revision.Namespace != source.Name || revision.Tenant != source.Tenant {
		s.accessFailure(c, store.ErrNotFound)
		return
	}
	definition, e := s.store.Orchestration().GetDefinition(c.Request.Context(), revision.DefinitionID)
	if e != nil || definition.ArchivedAt != nil {
		s.accessFailure(c, store.ErrNotFound)
		return
	}
	var spec orchestration.DefinitionSpec
	if json.Unmarshal(revision.Spec, &spec) != nil {
		c.JSON(400, ErrorResponse{Error: "Invalid published Workflow"})
		return
	}
	inventory, e := s.resourceInventory(c.Request.Context(), target)
	if e != nil {
		c.JSON(503, ErrorResponse{Error: e.Error()})
		return
	}
	resources := resourceMap(inventory)
	remap := func(kind, id string) (string, bool) {
		key := in.Bindings[kind+":"+id]
		k, v, ok := model.ResourceKey(key)
		if !ok || k != kind {
			return "", false
		}
		if checkResourceGraph(target, resources, user, key) != nil {
			return "", false
		}
		return v, true
	}
	for i := range spec.Nodes {
		node := &spec.Nodes[i]
		if node.DefinitionRevID != "" {
			c.JSON(400, ErrorResponse{Error: "Import nested Workflows separately before sharing this template"})
			return
		}
		if node.AgentID != "" {
			value, ok := remap("agent", node.AgentID)
			if !ok {
				c.JSON(400, ErrorResponse{Error: "Select an authorized local Agent for every dependency"})
				return
			}
			node.AgentID = value
		}
		if node.TeamRef != "" {
			value, ok := remap("team", node.TeamRef)
			if !ok {
				c.JSON(400, ErrorResponse{Error: "Select an authorized local Team for every dependency"})
				return
			}
			node.TeamRef = value
		}
		node.RuntimeCandidate = nil // Bindings and provider configuration never cross namespaces.
	}
	raw, _ := json.Marshal(spec)
	if _, e = s.orchestrationService().ValidateDefinition(raw); e != nil {
		c.JSON(400, ErrorResponse{Error: e.Error()})
		return
	}
	created, e := s.store.Orchestration().CreateDefinition(c.Request.Context(), &model.OrchestrationDefinition{Tenant: target.Tenant, Namespace: target.Name, Name: strings.TrimSpace(in.Name), Description: definition.Description, DraftSpec: raw, CreatedBy: model.Actor{Type: model.ActorHuman, Ref: user}})
	if e != nil {
		s.writeCollaborationError(c, e)
		return
	}
	c.JSON(201, gin.H{"definition": created, "sourceNamespace": source.Name, "sourceRevision": revision.ID})
}

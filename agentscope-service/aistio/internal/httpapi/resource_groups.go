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
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"slices"
	"time"
)

func (s *Server) registerResourceManagement(r gin.IRouter) {
	r.GET("/namespaces/:namespaceName/resources", s.listNamespaceResources)
	r.GET("/namespaces/:namespaceName/resources/:kind/:resourceId/access", s.getResourceAccess)
	r.PUT("/namespaces/:namespaceName/resources/:kind/:resourceId/access", s.updateResourceAccess)
	r.GET("/namespaces/:namespaceName/groups", s.listAccessGroups)
	r.PUT("/namespaces/:namespaceName/groups", s.updateAccessGroups)
	r.GET("/namespaces/:namespaceName/requests", s.listAccessRequests)
	r.POST("/namespaces/:namespaceName/requests", s.createAccessRequest)
	r.POST("/namespaces/:namespaceName/requests/:requestId/review", s.reviewAccessRequest)
	r.GET("/namespaces/:namespaceName/shared-templates", s.sharedWorkflowTemplates)
	r.POST("/namespaces/:namespaceName/import-template", s.importWorkflowTemplate)
}
func (s *Server) listAccessGroups(c *gin.Context) {
	n, ok := s.resourceNamespace(c)
	if !ok {
		return
	}
	// User group membership is shown to managers; ordinary members get
	// group names/roles, not a directory of everyone in the namespace.
	manager := n.Manages(c.GetString("userId")) || roleSet(c)["admin"]
	groups := map[string]model.AccessGroup{}
	for id, g := range n.Groups {
		if manager {
			groups[id] = g
		} else {
			g.Members = nil
			groups[id] = g
		}
	}
	c.JSON(200, gin.H{"groups": groups, "version": n.Version, "canManage": manager})
}
func sensitiveGrantChanged(old, n *model.Namespace) bool {
	ids := map[string]bool{}
	for id := range old.Members {
		ids[id] = true
	}
	for id := range n.Members {
		ids[id] = true
	}
	for _, g := range old.Groups {
		for _, id := range g.Members {
			ids[id] = true
		}
	}
	for _, g := range n.Groups {
		for _, id := range g.Members {
			ids[id] = true
		}
	}
	a, b := *old, *n
	a.Archived = false
	b.Archived = false
	for id := range ids {
		if slices.Contains(a.Roles(id), "auditor") != slices.Contains(b.Roles(id), "auditor") {
			return true
		}
	}
	return false
}
func (s *Server) updateAccessGroups(c *gin.Context) {
	n, ok := s.namespaceForManagement(c)
	if !ok {
		return
	}
	var in struct {
		Version int64                        `json:"version"`
		Groups  map[string]model.AccessGroup `json:"groups"`
	}
	if c.ShouldBindJSON(&in) != nil || in.Version <= 0 || in.Groups == nil {
		c.JSON(400, ErrorResponse{Error: "Groups and expected version required"})
		return
	}
	old := *namespaceCopy(n)
	n.Groups = in.Groups
	for key, p := range n.Resources {
		for id := range p.Groups {
			if _, ok := n.Groups[id]; !ok {
				delete(p.Groups, id)
			}
		}
		n.Resources[key] = p
	}
	if sensitiveGrantChanged(&old, n) && n.Owner != c.GetString("userId") && !roleSet(c)["admin"] {
		c.JSON(403, ErrorResponse{Error: "Only the owner or platform administrator can change auditor grants"})
		return
	}
	if err := n.Validate(); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	// Validate the actual group members through the same account directory as
	// individual grants. Removing a group removes its resource grants atomically.
	members := map[string][]string{}
	for _, g := range n.Groups {
		for _, id := range g.Members {
			members[id] = g.Roles
		}
	}
	check := *n
	check.Members = members
	if err := s.validateNamespaceAccounts(c.Request.Context(), &check, nil); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	updated, err := s.store.Access().PutNamespace(c.Request.Context(), n, in.Version, c.GetString("userId"))
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(200, gin.H{"groups": updated.Groups, "version": updated.Version})
}
func (s *Server) listAccessRequests(c *gin.Context) {
	n, ok := s.resourceNamespace(c)
	if !ok {
		return
	}
	items := []model.AccessRequest{}
	manager := n.Manages(c.GetString("userId")) || roleSet(c)["admin"]
	for i := len(n.Requests) - 1; i >= 0; i-- {
		r := n.Requests[i]
		if manager || r.User == c.GetString("userId") {
			items = append(items, r)
		}
	}
	c.JSON(200, gin.H{"items": items, "version": n.Version, "canManage": manager})
}
func (s *Server) createAccessRequest(c *gin.Context) {
	n, ok := s.resourceNamespace(c)
	if !ok {
		return
	}
	user := c.GetString("userId")
	var in struct {
		Resource string `json:"resource"`
		Action   string `json:"action"`
		Reason   string `json:"reason"`
		Version  int64  `json:"version"`
	}
	if c.ShouldBindJSON(&in) != nil || in.Version <= 0 || !slices.Contains(model.ResourceActions, in.Action) || len(in.Reason) < 3 || len(in.Reason) > 1000 {
		c.JSON(400, ErrorResponse{Error: "Resource, action, reason and expected version required"})
		return
	}
	if len(n.Roles(user)) == 0 {
		s.accessFailure(c, store.ErrNotFound)
		return
	}
	inventory, e := s.resourceInventory(c.Request.Context(), n)
	if e != nil {
		c.JSON(503, ErrorResponse{Error: e.Error()})
		return
	}
	if _, ok := resourceMap(inventory)[in.Resource]; !ok {
		s.accessFailure(c, store.ErrNotFound)
		return
	}
	for _, r := range n.Requests {
		if r.User == user && r.Resource == in.Resource && r.Action == in.Action && r.Status == "pending" {
			c.JSON(409, ErrorResponse{Error: "An access request is already pending"})
			return
		}
	}
	n.Requests = append(n.Requests, model.AccessRequest{ID: uuid.NewString(), User: user, Resource: in.Resource, Action: in.Action, Reason: in.Reason, Status: "pending", CreatedAt: time.Now().UTC()})
	updated, e := s.store.Access().PutNamespace(c.Request.Context(), n, in.Version, user)
	if e != nil {
		s.writeCollaborationError(c, e)
		return
	}
	c.JSON(201, gin.H{"request": updated.Requests[len(updated.Requests)-1], "version": updated.Version})
}
func (s *Server) reviewAccessRequest(c *gin.Context) {
	n, ok := s.namespaceForManagement(c)
	if !ok {
		return
	}
	var in struct {
		Version int64 `json:"version"`
		Approve *bool `json:"approve"`
	}
	if c.ShouldBindJSON(&in) != nil || in.Version <= 0 || in.Approve == nil {
		c.JSON(400, ErrorResponse{Error: "Decision and expected version required"})
		return
	}
	found := false
	for i := range n.Requests {
		r := &n.Requests[i]
		if r.ID != c.Param("requestId") {
			continue
		}
		found = true
		if r.Status != "pending" {
			c.JSON(409, ErrorResponse{Error: "Request already reviewed"})
			return
		}
		r.Status = "denied"
		r.ReviewedBy = c.GetString("userId")
		if *in.Approve {
			if len(n.Roles(r.User)) == 0 {
				c.JSON(409, ErrorResponse{Error: "Requester is no longer a namespace member"})
				return
			}
			inventory, err := s.resourceInventory(c.Request.Context(), n)
			if err != nil {
				c.JSON(503, ErrorResponse{Error: "Unable to resolve requested resource"})
				return
			}
			if _, exists := resourceMap(inventory)[r.Resource]; !exists {
				c.JSON(409, ErrorResponse{Error: "Requested resource no longer exists"})
				return
			}
			r.Status = "approved"
			if n.Resources == nil {
				n.Resources = map[string]model.ResourcePolicy{}
			}
			p, ok := n.Resources[r.Resource]
			if !ok {
				p.Mode = "inherit"
			}
			if p.Users == nil {
				p.Users = map[string][]string{}
			}
			if !slices.Contains(p.Users[r.User], r.Action) {
				p.Users[r.User] = append(p.Users[r.User], r.Action)
			}
			n.Resources[r.Resource] = p
		}
	}
	if !found {
		s.accessFailure(c, store.ErrNotFound)
		return
	}
	updated, e := s.store.Access().PutNamespace(c.Request.Context(), n, in.Version, c.GetString("userId"))
	if e != nil {
		s.writeCollaborationError(c, e)
		return
	}
	c.JSON(200, gin.H{"version": updated.Version})
}

// Store copies are detached; use this for reviewing both sides of a namespace
// change without aliasing maps during tests and compound updates.
func namespaceCopy(n *model.Namespace) *model.Namespace {
	raw, _ := json.Marshal(n)
	var copy model.Namespace
	_ = json.Unmarshal(raw, &copy)
	return &copy
}

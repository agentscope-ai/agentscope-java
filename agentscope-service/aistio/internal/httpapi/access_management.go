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
	"fmt"
	"slices"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type AccountDirectory interface {
	LookupAccounts(context.Context, string, []string, int) ([]product.AccountSummary, error)
}

func (s *Server) registerAccessManagement(r gin.IRouter) {
	s.registerResourceManagement(r)
	r.GET("/namespaces", s.listManagedNamespaces)
	r.GET("/namespaces/:namespaceName/accounts", s.namespaceAccountDirectory)
	r.GET("/namespaces/:namespaceName/audit", s.namespaceAudit)
	r.POST("/namespaces/:namespaceName/transfer", s.transferNamespace)
	r.GET("/access/accounts", s.platformAccountDirectory)
	r.GET("/access/users/:accountId/namespaces", s.accountNamespaces)
	r.GET("/access/audit", s.namespaceAudit)
	r.GET("/me/preferences", s.getMyPreferences)
	r.PUT("/me/preferences", s.updateMyPreferences)
}

func (s *Server) platformAdmin(c *gin.Context) bool {
	if c.GetString("userId") == "" || !roleSet(c)["admin"] {
		c.JSON(403, ErrorResponse{Error: "Platform administrator access required"})
		return false
	}
	return true
}

func (s *Server) listManagedNamespaces(c *gin.Context) {
	user := c.GetString("userId")
	if user == "" {
		c.JSON(403, ErrorResponse{Error: "Console account required"})
		return
	}
	items, err := s.allManagedNamespaces(c.Request.Context())
	if err != nil {
		s.accessFailure(c, err)
		return
	}
	views := []gin.H{}
	for _, n := range items {
		if !roleSet(c)["admin"] && len(n.Roles(user)) == 0 && n.Owner != user && !n.Manages(user) {
			continue
		}
		manager := n.Kind != "global" && (n.Owner == c.GetString("userId") || n.Manages(c.GetString("userId")) || roleSet(c)["admin"])
		views = append(views, gin.H{"tenant": n.Tenant, "name": n.Name, "displayName": n.DisplayName, "kind": n.Kind, "owner": n.Owner, "archived": n.Archived, "version": n.Version, "roles": n.Roles(c.GetString("userId")), "memberCount": namespaceMemberCount(n), "canManage": manager})
	}
	c.JSON(200, gin.H{"items": views})
}
func namespaceMemberCount(n *model.Namespace) int {
	members := map[string]bool{n.Owner: true}
	for id := range n.Members {
		members[id] = true
	}
	for _, group := range n.Groups {
		for _, id := range group.Members {
			members[id] = true
		}
	}
	return len(members)
}

func (s *Server) directory(c *gin.Context) {
	if s.accounts == nil {
		c.JSON(503, ErrorResponse{Error: "Account directory unavailable"})
		return
	}
	var ids []string
	if raw := c.Query("ids"); raw != "" {
		ids = strings.Split(raw, ",")
	}
	if len(ids) > 1000 {
		c.JSON(400, ErrorResponse{Error: "Too many account IDs"})
		return
	}
	limit := 50
	if len(ids) > 0 {
		limit = len(ids)
	}
	items, err := s.accounts.LookupAccounts(c.Request.Context(), c.Query("q"), ids, limit)
	if err != nil {
		c.JSON(503, ErrorResponse{Error: "Unable to load accounts"})
		return
	}
	// Only expose the account identity needed to select a namespace member.
	views := []gin.H{}
	for _, a := range items {
		views = append(views, gin.H{"userId": a.UserID, "username": a.Username, "displayName": a.DisplayName, "disabled": a.Disabled})
	}
	c.JSON(200, gin.H{"items": views})
}
func (s *Server) platformAccountDirectory(c *gin.Context) {
	if s.platformAdmin(c) {
		s.directory(c)
	}
}
func (s *Server) namespaceAccountDirectory(c *gin.Context) {
	n, err := s.store.Access().GetNamespace(c.Request.Context(), s.defaultTenant, c.Param("namespaceName"))
	if err != nil {
		s.accessFailure(c, err)
		return
	}
	user := c.GetString("userId")
	allowed := n.Manages(user) || roleSet(c)["admin"]
	for key := range n.Resources {
		allowed = allowed || n.Decide(user, key, "manage").Allowed
	}
	if !allowed {
		s.accessFailure(c, store.ErrNotFound)
		return
	}
	s.directory(c)
}

// Validate changed grants against real, active accounts. Unchanged grants may
// reference disabled accounts so administrators can still remove them.
func (s *Server) validateNamespaceAccounts(ctx context.Context, n, old *model.Namespace) error {
	ids := []string{}
	if old == nil || old.Owner != n.Owner {
		ids = append(ids, n.Owner)
	}
	for id, roles := range n.Members {
		if old == nil || !sameRoles(roles, old.Members[id]) {
			if !slices.Contains(ids, id) {
				ids = append(ids, id)
			}
		}
	}
	if len(ids) == 0 {
		return nil
	}
	if s.accounts == nil {
		return fmt.Errorf("Account directory unavailable; no membership changes were saved")
	}
	items, err := s.accounts.LookupAccounts(ctx, "", ids, 1000)
	if err != nil {
		return fmt.Errorf("Unable to validate accounts")
	}
	active := map[string]bool{}
	for _, a := range items {
		active[a.UserID] = !a.Disabled
	}
	for _, id := range ids {
		if !active[id] {
			return fmt.Errorf("Account %s does not exist or is disabled", id)
		}
	}
	return nil
}
func sameRoles(a, b []string) bool {
	a = slices.Clone(a)
	b = slices.Clone(b)
	slices.Sort(a)
	slices.Sort(b)
	return slices.Equal(a, b)
}

func (s *Server) accountNamespaces(c *gin.Context) {
	if !s.platformAdmin(c) {
		return
	}
	items, err := s.allManagedNamespaces(c.Request.Context())
	if err != nil {
		s.accessFailure(c, err)
		return
	}
	views := []gin.H{}
	user := c.Param("accountId")
	for _, n := range items {
		if n.Kind == "global" || n.Owner == user || len(n.Members[user]) > 0 {
			views = append(views, gin.H{"tenant": n.Tenant, "name": n.Name, "displayName": n.DisplayName, "kind": n.Kind, "owner": n.Owner, "archived": n.Archived, "version": n.Version, "roles": n.Roles(user), "assignedRoles": n.Members[user], "groups": n.GroupIDs(user)})
		}
	}
	c.JSON(200, gin.H{"items": views})
}

func (s *Server) namespaceAudit(c *gin.Context) {
	name := c.Param("namespaceName")
	if name == "" {
		if !s.platformAdmin(c) {
			return
		}
	} else {
		if _, ok := s.namespaceForManagement(c); !ok {
			return
		}
	}
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	if offset < 0 {
		offset = 0
	}
	items, err := s.store.Access().ListNamespaceAudit(c.Request.Context(), s.defaultTenant, name, 50, offset)
	if err != nil {
		s.accessFailure(c, err)
		return
	}
	c.JSON(200, gin.H{"items": items})
}

func (s *Server) transferNamespace(c *gin.Context) {
	n, ok := s.namespaceForManagement(c)
	if !ok {
		return
	}
	if n.Owner != c.GetString("userId") && !roleSet(c)["admin"] {
		c.JSON(403, ErrorResponse{Error: "Only the owner or a platform administrator can transfer ownership"})
		return
	}
	var in struct {
		Owner   string `json:"owner"`
		Version int64  `json:"version"`
	}
	if c.ShouldBindJSON(&in) != nil || in.Version <= 0 || in.Owner == "" || n.Kind != "shared" || n.Archived {
		c.JSON(400, ErrorResponse{Error: "An active shared namespace, new owner and expected version are required"})
		return
	}
	copy := *n
	copy.Owner = in.Owner
	if err := s.validateNamespaceAccounts(c.Request.Context(), &copy, n); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	updated, err := s.store.Access().TransferNamespace(c.Request.Context(), n.Tenant, n.Name, in.Owner, in.Version, c.GetString("userId"))
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(200, gin.H{"namespace": updated})
}

func (s *Server) getMyPreferences(c *gin.Context) {
	if c.GetString("userId") == "" || s.product == nil {
		c.JSON(503, ErrorResponse{Error: "Account preferences unavailable"})
		return
	}
	values, err := s.product.GetAccountPreferences(c.Request.Context(), c.GetString("userId"))
	if err != nil {
		c.JSON(503, ErrorResponse{Error: "Unable to load preferences"})
		return
	}
	c.JSON(200, gin.H{"preferences": values})
}
func (s *Server) updateMyPreferences(c *gin.Context) {
	user := c.GetString("userId")
	if user == "" || s.product == nil {
		c.JSON(503, ErrorResponse{Error: "Account preferences unavailable"})
		return
	}
	var in struct {
		DefaultNamespace string `json:"defaultNamespace"`
	}
	if c.ShouldBindJSON(&in) != nil {
		c.JSON(400, ErrorResponse{Error: "Invalid preferences"})
		return
	}
	if in.DefaultNamespace != "" {
		n, err := s.store.Access().GetNamespace(c.Request.Context(), s.defaultTenant, in.DefaultNamespace)
		if err != nil || len(n.Roles(user)) == 0 {
			c.JSON(400, ErrorResponse{Error: "Choose a namespace you can access"})
			return
		}
	}
	values, err := s.product.GetAccountPreferences(c.Request.Context(), user)
	if err != nil {
		c.JSON(503, ErrorResponse{Error: "Unable to load preferences"})
		return
	}
	if values == nil {
		values = map[string]string{}
	}
	values["defaultNamespace"] = in.DefaultNamespace
	if err = s.product.SetAccountPreferences(c.Request.Context(), user, values); err != nil {
		c.JSON(503, ErrorResponse{Error: "Unable to save preferences"})
		return
	}
	c.JSON(200, gin.H{"preferences": values})
}

// Inventory is paged in storage so ownership and management checks never silently
// omit namespaces beyond the first page.
func (s *Server) allManagedNamespaces(ctx context.Context) ([]*model.Namespace, error) {
	var items []*model.Namespace
	for offset := 0; ; offset += 500 {
		page, err := s.store.Access().ListNamespaces(ctx, s.defaultTenant, "", 500, offset)
		if err != nil {
			return nil, err
		}
		items = append(items, page...)
		if len(page) < 500 {
			return items, nil
		}
	}
}

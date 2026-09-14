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
	"github.com/gin-gonic/gin"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"net/http"
	"strings"
)

func namespaceResourceOwner(n *controlmodel.Namespace) string {
	if n.Kind == "personal" {
		return n.Owner
	}
	return "namespace:" + n.Tenant + ":" + n.Name
}

// Product resources use the same namespace membership as the canonical API.
// Their existing owner partitions remain intact for personal accounts; shared
// namespace resources get a durable namespace owner, independent of the creator.
func (s *Server) productNamespaceMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		user := c.GetString("userId")
		if user == "" || s.store == nil || strings.HasPrefix(c.Request.URL.Path, "/api/internal/") {
			c.Next()
			return
		}
		resource, _, _ := strings.Cut(strings.TrimPrefix(c.Request.URL.Path, "/api/"), "/")
		switch resource {
		case "sessions":
			if s.authorizePersonalSessionMutation(c) {
				c.Next()
			}
			return
		case "agents", "workspaces", "toolsets", "environments", "memory-stores", "vaults", "channels", "deployments", "files", "marketplaces":
		default:
			c.Next()
			return
		}
		personal, err := s.ensurePersonalNamespace(c.Request.Context(), user)
		if err != nil {
			s.accessFailure(c, err)
			return
		}
		tenant, namespace := c.Query("tenant"), c.Query("namespace")
		if tenant == "" {
			tenant = c.GetHeader("X-AgentScope-Tenant")
		}
		if namespace == "" {
			namespace = c.GetHeader("X-AgentScope-Namespace")
		}
		if tenant == "" {
			tenant = personal.Tenant
		}
		if namespace == "" {
			namespace = personal.Name
		}
		n, err := s.store.Access().GetNamespace(c.Request.Context(), tenant, namespace)
		if err != nil {
			s.accessFailure(c, err)
			return
		}
		roles := n.Roles(user)
		if len(roles) == 0 {
			s.accessFailure(c, store.ErrNotFound)
			return
		}
		action := "configure"
		if c.Request.Method != http.MethodGet && c.Request.Method != http.MethodHead {
			action = "resource.write"
		}
		if resource == "channels" {
			tail := strings.TrimPrefix(c.Request.URL.Path, "/api/channels")
			read := c.Request.Method == http.MethodGet || c.Request.Method == http.MethodHead
			if read && (tail == "" || tail == "/types" || strings.HasSuffix(tail, "/activity") || strings.HasSuffix(tail, "/collaboration")) {
				action = "read"
			}
			if strings.HasSuffix(tail, "/pairing") || strings.Contains(tail, "/deliveries/") || strings.Contains(tail, "/messages/") {
				action = "work.write"
			}
			if strings.HasSuffix(tail, "/identity") || strings.Contains(tail, "/links/") {
				action = "read"
			}
		}
		kind := map[string]string{"agents": "managed-agent", "workspaces": "workspace", "environments": "environment", "vaults": "vault", "memory-stores": "memory", "channels": "channel"}[resource]
		id := c.Param("id")
		if resource == "channels" {
			id = c.Param("channelId")
		}
		resourceAction := "edit"
		if c.Request.Method == "GET" || c.Request.Method == "HEAD" {
			resourceAction = "inspect"
		}
		if action == "read" {
			resourceAction = "discover"
		}
		if action == "work.write" {
			resourceAction = "use"
		}
		if strings.HasSuffix(c.Request.URL.Path, "/publish") {
			resourceAction = "publish"
		}
		allowed := controlmodel.NamespaceAllows(roles, action)
		if kind != "" && id != "" {
			allowed = n.Decide(user, kind+":"+id, resourceAction).Allowed
			if kind == "managed-agent" {
				items, e := s.resourceInventory(c.Request.Context(), n)
				if e != nil {
					c.AbortWithStatusJSON(503, ErrorResponse{Error: "Unable to resolve resource access"})
					return
				}
				allowed = productResourceAllowed(n, items, user, kind+":"+id, resourceAction)
			}
		}
		if kind != "" && id == "" && c.Request.Method == "GET" {
			inventory, e := s.resourceInventory(c.Request.Context(), n)
			if e != nil {
				c.AbortWithStatusJSON(503, ErrorResponse{Error: "Unable to resolve resource access"})
				return
			}
			ids := []string{}
			for _, r := range inventory {
				if r.Kind == kind && productResourceAllowed(n, inventory, user, r.Key(), resourceAction) {
					ids = append(ids, r.ID)
				}
			}
			product.SetResourceFilter(c, ids)
			allowed = allowed || len(ids) > 0
		}
		if !allowed {
			c.AbortWithStatusJSON(403, ErrorResponse{Error: "namespace role does not allow resource configuration"})
			return
		}
		if !s.authorizeProductDependencies(c, n, kind, id) {
			return
		}
		// Product memory content is shared namespace knowledge, not an implicit
		// grant to another user's private execution transcript.
		product.SetResourceOwner(c, namespaceResourceOwner(n))
		c.Next()
	}
}

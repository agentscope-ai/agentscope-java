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
	"bytes"
	"encoding/json"
	"github.com/gin-gonic/gin"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"io"
	"slices"
)

func productResourceAllowed(n *model.Namespace, items []model.ResourceDescriptor, user, key, action string) bool {
	if !n.Decide(user, key, action).Allowed {
		return false
	}
	kind, _, _ := model.ResourceKey(key)
	if kind == "managed-agent" {
		for _, r := range items {
			if r.Kind == "agent" && slices.Contains(r.Dependencies, key) {
				if !n.Decide(user, r.Key(), action).Allowed {
					return false
				}
			}
		}
	}
	return true
}

// Structural dependency fields are checked before materialization or mutation;
// permissions on a parent never authorize arbitrary replacement dependencies.
func (s *Server) authorizeProductDependencies(c *gin.Context, n *model.Namespace, kind, id string) bool {
	if c.Request.Method == "GET" || c.Request.Method == "HEAD" || c.Request.Method == "DELETE" || c.Request.Body == nil {
		return true
	}
	if kind != "managed-agent" && kind != "workspace" {
		return true
	}
	data, e := io.ReadAll(io.LimitReader(c.Request.Body, (16<<20)+1))
	if e != nil || len(data) > 16<<20 {
		c.AbortWithStatusJSON(400, ErrorResponse{Error: "Invalid resource request"})
		return false
	}
	c.Request.Body = io.NopCloser(bytes.NewReader(data))
	var body map[string]json.RawMessage
	if json.Unmarshal(data, &body) != nil {
		return true
	} // Typed handler reports malformed input.
	deps := []string{}
	for field, k := range map[string]string{"workspaceId": "workspace", "defaultEnvironmentId": "environment"} {
		var value string
		_ = json.Unmarshal(body[field], &value)
		if value != "" {
			deps = append(deps, k+":"+value)
		}
	}
	for field, k := range map[string]string{"defaultVaultIds": "vault", "defaultMemoryStoreIds": "memory"} {
		var values []string
		_ = json.Unmarshal(body[field], &values)
		for _, value := range values {
			deps = append(deps, k+":"+value)
		}
	}
	var mcp any
	if json.Unmarshal(body["mcpServers"], &mcp) == nil {
		deps = append(deps, product.ResourceVaultRefs(mcp)...)
	}
	if len(deps) == 0 {
		return true
	}
	items, e := s.resourceInventory(c.Request.Context(), n)
	if e != nil {
		c.AbortWithStatusJSON(503, ErrorResponse{Error: e.Error()})
		return false
	}
	inventory := resourceMap(items)
	for _, dep := range deps {
		if _, ok := inventory[dep]; !ok {
			c.AbortWithStatusJSON(400, ErrorResponse{Error: "Dependency is outside this namespace or unavailable"})
			return false
		}
		allowed := n.Decide(c.GetString("userId"), dep, "use").Allowed
		if id != "" && slices.Contains(n.Resources[dep].Consumers, kind+":"+id) {
			allowed = true
		}
		if !allowed {
			c.AbortWithStatusJSON(403, ErrorResponse{Error: "Use permission required for selected dependency"})
			return false
		}
	}
	return true
}

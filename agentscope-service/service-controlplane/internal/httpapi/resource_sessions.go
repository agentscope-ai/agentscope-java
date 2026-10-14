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
	"io"
)

// The legacy personal session API is another resource invocation surface.
func (s *Server) authorizePersonalSessionMutation(c *gin.Context) bool {
	if c.Request.Method != "PATCH" && !(c.Request.Method == "POST" && c.Request.URL.Path == "/api/sessions") {
		return true
	}
	n, err := s.ensurePersonalNamespace(c.Request.Context(), c.GetString("userId"))
	if err != nil {
		s.accessFailure(c, err)
		return false
	}
	if len(n.Resources) == 0 {
		return true
	}
	data, err := io.ReadAll(io.LimitReader(c.Request.Body, (16<<20)+1))
	if err != nil || len(data) > 16<<20 {
		c.AbortWithStatusJSON(400, ErrorResponse{Error: "Invalid session request"})
		return false
	}
	c.Request.Body = io.NopCloser(bytes.NewReader(data))
	var body map[string]json.RawMessage
	if json.Unmarshal(data, &body) != nil {
		return true
	}
	var agent string
	deps := []string{}
	if id := c.Param("id"); id != "" {
		agent, deps, err = s.product.PersonalSessionResources(c.Request.Context(), c.GetString("userId"), id)
		if err != nil {
			c.AbortWithStatusJSON(404, ErrorResponse{Error: "Session unavailable"})
			return false
		}
	} else {
		_ = json.Unmarshal(body["agent"], &agent)
		if agent == "" {
			var ref struct {
				ID string `json:"id"`
			}
			_ = json.Unmarshal(body["agent"], &ref)
			agent = ref.ID
		}
	}
	if agent == "" {
		return true
	}
	var environment string
	_ = json.Unmarshal(body["environmentId"], &environment)
	if environment != "" {
		deps = append(deps, "environment:"+environment)
	}
	for field, kind := range map[string]string{"memoryStoreIds": "memory", "vaultIds": "vault"} {
		var ids []string
		_ = json.Unmarshal(body[field], &ids)
		for _, id := range ids {
			deps = append(deps, kind+":"+id)
		}
	}
	inventory, err := s.resourceInventory(c.Request.Context(), n)
	if err != nil {
		c.AbortWithStatusJSON(503, ErrorResponse{Error: "Unable to resolve session resources"})
		return false
	}
	key := "managed-agent:" + agent
	if !productResourceAllowed(n, inventory, c.GetString("userId"), key, "use") {
		c.AbortWithStatusJSON(403, ErrorResponse{Error: "Agent use permission required"})
		return false
	}
	items := resourceMap(inventory)
	root, exists := items[key]
	if !exists {
		c.AbortWithStatusJSON(404, ErrorResponse{Error: "Agent unavailable"})
		return false
	}
	root.Dependencies = append(root.Dependencies, deps...)
	items[key] = root
	if err = checkResourceGraph(n, items, c.GetString("userId"), key); err != nil {
		c.AbortWithStatusJSON(403, ErrorResponse{Error: err.Error()})
		return false
	}
	return true
}

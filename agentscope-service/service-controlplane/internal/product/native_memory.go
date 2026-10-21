// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//     http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package product

import (
	"encoding/json"
	"net/http"

	"github.com/gin-gonic/gin"
)

// Native Brain validates mount membership against its sealed Session configuration.
// This internal resource API rechecks live resource ownership and access without
// requiring a second Session record in the control plane. The internal middleware
// authenticates the caller before its resource-owner header can be used.
func (s *Server) nativeMemory(next gin.HandlerFunc) gin.HandlerFunc {
	return func(c *gin.Context) {
		owner := c.GetHeader("X-Builder-Internal-User")
		if owner == "" || currentUserID(c) != owner {
			writeErr(c, http.StatusForbidden, "memory resource owner is required")
			return
		}
		storeID := c.Param("id")
		if _, err := s.buildMemoryMount(c.Request.Context(), storeID, owner); err != nil {
			writeErr(c, http.StatusNotFound, "memory store not found")
			return
		}
		if environment := c.GetHeader("X-Builder-Internal-Environment"); environment != "" {
			env, err := s.loadEnv(c.Request.Context(), environment)
			if err != nil || env.OwnerID != owner || env.ArchivedAt != nil {
				writeErr(c, http.StatusConflict, "environment unavailable")
				return
			}
			var config struct {
				MemoryAccess map[string]string `json:"memoryAccess"`
			}
			if env.ConfigJSON != nil {
				if err := json.Unmarshal([]byte(*env.ConfigJSON), &config); err != nil {
					writeErr(c, http.StatusConflict, "environment configuration unavailable")
					return
				}
			}
			if c.Request.Method != http.MethodGet && config.MemoryAccess[storeID] == "read_only" {
				writeErr(c, http.StatusForbidden, "memory store is mounted read_only")
				return
			}
		}
		SetResourceOwner(c, owner)
		next(c)
	}
}

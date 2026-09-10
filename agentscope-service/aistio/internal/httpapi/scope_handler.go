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
	"io"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

const (
	ScopeModeSingle = "single"
	ScopeModeMulti  = "multi"
)

// scopeMiddleware makes the configured scope authoritative in single-tenant
// deployments. Query parameters and top-level JSON scope fields are
// canonicalized before authorization and handlers run, so hidden UI fields do
// not weaken the storage isolation boundary.
func (s *Server) scopeMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		if console, _ := c.Get(ctxConsoleAuth); console == true {
			c.Next()
			return
		}
		if s.scopeMode != ScopeModeSingle {
			c.Next()
			return
		}
		query := c.Request.URL.Query()
		query.Set("tenant", s.defaultTenant)
		query.Set("namespace", s.defaultNamespace)
		c.Request.URL.RawQuery = query.Encode()

		if c.Request.Body != nil && c.Request.ContentLength != 0 &&
			strings.Contains(c.GetHeader("Content-Type"), "application/json") {
			body, err := io.ReadAll(io.LimitReader(c.Request.Body, 16<<20))
			if err == nil {
				var object map[string]json.RawMessage
				if json.Unmarshal(body, &object) == nil && object != nil {
					object["tenant"], _ = json.Marshal(s.defaultTenant)
					object["namespace"], _ = json.Marshal(s.defaultNamespace)
					body, _ = json.Marshal(object)
				}
				c.Request.Body = io.NopCloser(bytes.NewReader(body))
				c.Request.ContentLength = int64(len(body))
			}
		}
		c.Next()
	}
}

func (s *Server) getCurrentScope(c *gin.Context) {
	if c.GetString("userId") != "" && s.store != nil {
		user := c.GetString("userId")
		global, err := s.ensureGlobalDefaultNamespace(c.Request.Context())
		if err != nil {
			s.accessFailure(c, err)
			return
		}
		personal, err := s.ensurePersonalNamespace(c.Request.Context(), user)
		if err != nil {
			s.accessFailure(c, err)
			return
		}
		items, err := s.store.Access().ListNamespaces(c.Request.Context(), s.defaultTenant, user, 500, 0)
		if err != nil {
			s.accessFailure(c, err)
			return
		}
		selected := global.Name
		if s.product != nil {
			if preferences, e := s.product.GetAccountPreferences(c.Request.Context(), user); e == nil {
				for _, n := range items {
					if n.Name == preferences["defaultNamespace"] {
						selected = n.Name
						break
					}
				}
			}
		}
		c.JSON(http.StatusOK, gin.H{"mode": "multi", "tenant": personal.Tenant, "namespace": selected, "selectorVisible": true, "namespaces": namespaceSummaries(items, user)})
		return
	}

	tenant := c.DefaultQuery("tenant", s.defaultTenant)
	namespace := c.DefaultQuery("namespace", s.defaultNamespace)
	if s.scopeMode == ScopeModeSingle {
		tenant, namespace = s.defaultTenant, s.defaultNamespace
	}
	c.JSON(http.StatusOK, gin.H{
		"mode":            s.scopeMode,
		"tenant":          tenant,
		"namespace":       namespace,
		"selectorVisible": s.scopeMode == ScopeModeMulti,
	})
}

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

package product

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

const (
	ctxUserID   = "userId"
	ctxUsername = "username"
	ctxRoles    = "roles"
)

func (s *Server) jwtMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		path := c.Request.URL.Path
		if path == "/api/auth/login" ||
			(c.Request.Method == http.MethodGet && strings.HasPrefix(path, oauthCallbackPrefix)) ||
			path == "/actuator/health" ||
			path == "/healthz" ||
			strings.HasPrefix(path, "/api/deployments/webhook/") ||
			strings.HasPrefix(path, "/api/internal/") {
			c.Next()
			return
		}
		if !strings.HasPrefix(path, "/api/") {
			c.Next()
			return
		}
		auth := c.GetHeader("Authorization")
		if !strings.HasPrefix(auth, "Bearer ") {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing bearer token"})
			return
		}
		claims, err := s.VerifyAccountToken(c.Request.Context(), strings.TrimPrefix(auth, "Bearer "))
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid token"})
			return
		}
		c.Set(ctxUserID, claims.Subject)
		c.Set(ctxUsername, claims.Username)
		c.Set(ctxRoles, claims.Roles)
		c.Next()
	}
}

func (s *Server) internalMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		if !strings.HasPrefix(c.Request.URL.Path, "/api/internal/") {
			c.Next()
			return
		}
		tok := c.GetHeader("X-Builder-Internal-Token")
		if s.cfg.InternalToken == "" || tok != s.cfg.InternalToken {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid internal token"})
			return
		}
		if u := c.GetHeader("X-Builder-Internal-User"); u != "" {
			c.Set(ctxUserID, u)
		}
		c.Next()
	}
}

func currentUserID(c *gin.Context) string {
	v, _ := c.Get(ctxUserID)
	s, _ := v.(string)
	return s
}

// SetResourceOwner is called only by the control plane after namespace
// authorization. It does not replace the authenticated account identity.
func SetResourceOwner(c *gin.Context, owner string) { c.Set("resourceOwner", owner) }
func currentResourceOwner(c *gin.Context) string {
	if owner := c.GetString("resourceOwner"); owner != "" {
		return owner
	}
	return currentUserID(c)
}

func currentUsername(c *gin.Context) string {
	v, _ := c.Get(ctxUsername)
	s, _ := v.(string)
	return s
}

func currentRoles(c *gin.Context) []string {
	v, _ := c.Get(ctxRoles)
	roles, _ := v.([]string)
	return roles
}

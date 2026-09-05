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

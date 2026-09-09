// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package httpapi

import (
	"github.com/gin-gonic/gin"
	"net/http/httptest"
	"testing"
)

func TestChannelProductActionsUseNamespaceRoles(t *testing.T) {
	s, _ := accessTestServer(t)
	r := gin.New()
	r.Use(func(c *gin.Context) { c.Set("userId", c.GetHeader("X-Test-User")) })
	r.Use(s.productNamespaceMiddleware())
	for _, path := range []string{"/api/channels", "/api/channels/:id/collaboration", "/api/channels/:id/pairing", "/api/channels/:id/activity", "/api/channels/:id/identity"} {
		r.Any(path, func(c *gin.Context) { c.Status(204) })
	}
	for _, tc := range []struct {
		user, method, path string
		status             int
	}{{"bob", "GET", "/api/channels", 204}, {"bob", "POST", "/api/channels", 403}, {"developer", "POST", "/api/channels", 204}, {"bob", "GET", "/api/channels/ch/collaboration", 204}, {"bob", "PUT", "/api/channels/ch/collaboration", 403}, {"developer", "PUT", "/api/channels/ch/collaboration", 204}, {"bob", "POST", "/api/channels/ch/pairing", 204}, {"carol", "POST", "/api/channels/ch/pairing", 403}, {"bob", "GET", "/api/channels/ch/activity", 204}, {"bob", "DELETE", "/api/channels/ch/identity", 204}, {"outsider", "GET", "/api/channels", 404}} {
		req := httptest.NewRequest(tc.method, tc.path+"?tenant=default&namespace=engineering", nil)
		req.Header.Set("X-Test-User", tc.user)
		w := httptest.NewRecorder()
		r.ServeHTTP(w, req)
		if w.Code != tc.status {
			t.Errorf("%s %s %s got %d: %s", tc.user, tc.method, tc.path, w.Code, w.Body.String())
		}
	}
}

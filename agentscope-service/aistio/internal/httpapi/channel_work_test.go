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

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

package httpapi

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	authzv1 "k8s.io/api/authorization/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"sigs.k8s.io/controller-runtime/pkg/log"
)

// resourceMapping maps REST path segments to Kubernetes API resources and
// their API group for SubjectAccessReview authorization checks.
var resourceMapping = map[string]struct {
	resource string
	group    string
}{
	"teams":  {resource: "agentteams", group: "agentscope.io"},
	"agents": {resource: "agents", group: "agentscope.io"},
	// Sessions are store-backed (no dedicated CRD); authorize against the
	// owning "agents" resource.
	"sessions":     {resource: "agents", group: "agentscope.io"},
	"sandboxes":    {resource: "sandboxclaims", group: "agentscope.io"},
	"modelconfigs": {resource: "modelconfigs", group: "agentscope.io"},
	"mcpservers":   {resource: "mcpservers", group: "agentscope.io"},
}

// ctxConsoleAuth marks a request authenticated by a console JWT rather than
// a Kubernetes identity.
const ctxConsoleAuth = "consoleAuth"

// ctxInternalAuth marks a request authenticated by X-Builder-Internal-Token
// (data-plane trust boundary).
const ctxInternalAuth = "internalAuth"

// authzMiddleware performs SubjectAccessReview-based authorization.
// It runs after authMiddleware and expects the "username" key to be set in the
// Gin context. When kubeClient is nil (static token mode), authorization is
// skipped because SAR requires a Kubernetes API connection.
func (s *Server) authzMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		if s.kubeClient == nil {
			// Static token mode -- no Kubernetes API available for SAR.
			c.Next()
			return
		}
		if ok, _ := c.Get(ctxConsoleAuth); ok == true {
			// Console users are not Kubernetes subjects; the product control
			// plane owns their authorization model.
			c.Next()
			return
		}
		if ok, _ := c.Get(ctxInternalAuth); ok == true {
			// Data-plane callers use the shared internal token; not K8s subjects.
			c.Next()
			return
		}

		username, _ := c.Get("username")
		user, _ := username.(string)
		if user == "" {
			c.AbortWithStatusJSON(http.StatusForbidden, ErrorResponse{Error: "forbidden: no authenticated user"})
			return
		}

		var groups []string
		if g, ok := c.Get("groups"); ok {
			groups, _ = g.([]string)
		}

		resource, group := resolveResource(c)
		if resource == "" {
			// Unknown resource path; let the handler return 404.
			c.Next()
			return
		}

		verb := httpMethodToVerb(c)
		namespace := resolveNamespace(c)

		sar := &authzv1.SubjectAccessReview{
			Spec: authzv1.SubjectAccessReviewSpec{
				User:   user,
				Groups: groups,
				ResourceAttributes: &authzv1.ResourceAttributes{
					Namespace: namespace,
					Verb:      verb,
					Group:     group,
					Resource:  resource,
				},
			},
		}

		logger := log.FromContext(c.Request.Context()).WithName("authz")

		result, err := s.kubeClient.AuthorizationV1().SubjectAccessReviews().Create(
			c.Request.Context(), sar, metav1.CreateOptions{},
		)
		if err != nil {
			logger.V(1).Info("SubjectAccessReview call failed", "user", user, "error", err)
			c.AbortWithStatusJSON(http.StatusForbidden, ErrorResponse{Error: "authorization check failed"})
			return
		}

		if !result.Status.Allowed {
			logger.V(1).Info("authorization denied",
				"user", user, "verb", verb, "resource", resource,
				"namespace", namespace, "reason", result.Status.Reason)
			c.AbortWithStatusJSON(http.StatusForbidden, ErrorResponse{
				Error:   "forbidden",
				Message: result.Status.Reason,
			})
			return
		}

		logger.V(1).Info("authorization allowed",
			"user", user, "verb", verb, "resource", resource, "namespace", namespace)
		c.Next()
	}
}

// resolveResource extracts the Kubernetes resource name and API group from
// the request's route pattern. It looks at the first path segment after
// /api/v1/ to determine the REST resource type.
func resolveResource(c *gin.Context) (resource, group string) {
	// c.FullPath() returns the route pattern, e.g. "/api/v1/agents/:name".
	pattern := c.FullPath()
	// Strip the /api/v1/ prefix to get the resource segment.
	const prefix = "/api/v1/"
	if !strings.HasPrefix(pattern, prefix) {
		return "", ""
	}
	rest := pattern[len(prefix):]
	// The resource type is the first segment (e.g. "agents" from "agents/:name").
	seg, _, _ := strings.Cut(rest, "/")
	if seg == "" {
		return "", ""
	}

	mapping, ok := resourceMapping[seg]
	if !ok {
		return "", ""
	}
	return mapping.resource, mapping.group
}

// httpMethodToVerb converts an HTTP method + route shape into a Kubernetes RBAC
// verb. Collection endpoints (no resource name in path) use "list" for GET;
// single-resource endpoints use "get".
func httpMethodToVerb(c *gin.Context) string {
	switch c.Request.Method {
	case http.MethodGet:
		// If the route has a named parameter for the resource (e.g. /:name,
		// /:team), it targets a single resource -> "get".
		// Otherwise it is a collection endpoint -> "list".
		if isCollectionRoute(c) {
			return "list"
		}
		return "get"
	case http.MethodPost:
		return "create"
	case http.MethodPut, http.MethodPatch:
		return "update"
	case http.MethodDelete:
		return "delete"
	default:
		return "get"
	}
}

// isCollectionRoute returns true when the route pattern ends with the resource
// segment itself (no further /:param), indicating a collection (list) endpoint.
func isCollectionRoute(c *gin.Context) bool {
	pattern := c.FullPath()
	const prefix = "/api/v1/"
	if !strings.HasPrefix(pattern, prefix) {
		return false
	}
	rest := pattern[len(prefix):]
	// e.g. "agents" -> collection; "agents/:name" -> not collection
	_, after, found := strings.Cut(rest, "/")
	return !found || after == ""
}

// resolveNamespace extracts the target namespace from the request. It checks
// the query parameter first, then falls back to empty (cluster-scoped).
func resolveNamespace(c *gin.Context) string {
	if ns := c.Query("namespace"); ns != "" {
		return ns
	}
	return ""
}

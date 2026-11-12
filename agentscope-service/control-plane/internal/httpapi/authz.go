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
	"errors"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
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
	"teams":                          {resource: "teams", group: "managed.agentcore.internal"},
	"issues":                         {resource: "issues", group: "managed.agentcore.internal"},
	"agent-tasks":                    {resource: "agenttasks", group: "managed.agentcore.internal"},
	"artifacts":                      {resource: "artifacts", group: "managed.agentcore.internal"},
	"inbox":                          {resource: "inbox", group: "managed.agentcore.internal"},
	"approvals":                      {resource: "approvals", group: "managed.agentcore.internal"},
	"automations":                    {resource: "automations", group: "managed.agentcore.internal"},
	"orchestration-definitions":      {resource: "orchestrationdefinitions", group: "managed.agentcore.internal"},
	"orchestration-runs":             {resource: "orchestrationruns", group: "managed.agentcore.internal"},
	"execution-attempts":             {resource: "executionattempts", group: "managed.agentcore.internal"},
	"agent-runtime-policies":         {resource: "agentruntimepolicies", group: "managed.agentcore.internal"},
	"events":                         {resource: "issues", group: "managed.agentcore.internal"},
	"agents":                         {resource: "agents", group: "managed.agentcore.internal"},
	"agent-instances":                {resource: "agents", group: "managed.agentcore.internal"},
	"runtime-profiles":               {resource: "agents", group: "managed.agentcore.internal"},
	"runtime-pools":                  {resource: "agents", group: "managed.agentcore.internal"},
	"runtime-hosts":                  {resource: "agents", group: "managed.agentcore.internal"},
	"runtime-host-enrollments":       {resource: "agents", group: "managed.agentcore.internal"},
	"runtime-host-enrollment-tokens": {resource: "agents", group: "managed.agentcore.internal"},
	// Sessions are store-backed (no dedicated CRD); authorize against the
	// owning "agents" resource.
	"sessions":     {resource: "agents", group: "managed.agentcore.internal"},
	"sandboxes":    {resource: "sandboxclaims", group: "managed.agentcore.internal"},
	"modelconfigs": {resource: "modelconfigs", group: "managed.agentcore.internal"},
	"mcpservers":   {resource: "mcpservers", group: "managed.agentcore.internal"},
}

// ctxConsoleAuth marks a request authenticated by a console JWT rather than
// a Kubernetes identity.
const ctxConsoleAuth = "consoleAuth"

// ctxInternalAuth marks a request authenticated by X-Managed-Agents-Internal-Token
// (data-plane trust boundary).
const ctxInternalAuth = "internalAuth"
const (
	ctxTaskAuth                 = "taskAuth"
	ctxCompletedCoordinatorAuth = "completedCoordinatorAuth"
	ctxManagedTaskReceiptAuth   = "managedTaskReceiptAuth"
)

// authzMiddleware performs SubjectAccessReview-based authorization.
// It runs after authMiddleware and expects the "username" key to be set in the
// Gin context. When kubeClient is nil (static token mode), authorization is
// skipped because SAR requires a Kubernetes API connection.
func (s *Server) authzMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		if s.localDev || s.kubeClient == nil {
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
		resourceName := ""
		if storedTenant, storedNamespace, storedName, found, err := s.resolveStoredResourceScope(c); err != nil {
			if errors.Is(err, store.ErrNotFound) {
				c.AbortWithStatusJSON(http.StatusNotFound, ErrorResponse{Error: "not found"})
			} else {
				c.AbortWithStatusJSON(http.StatusForbidden, ErrorResponse{Error: "authorization scope resolution failed"})
			}
			return
		} else if found {
			// A caller cannot override an object's persisted namespace with a
			// different query parameter. UUID-addressed resources are always
			// authorized against their stored scope.
			if tenant := c.Query("tenant"); tenant != "" && tenant != storedTenant || namespace != "" && namespace != storedNamespace {
				c.AbortWithStatusJSON(http.StatusNotFound, ErrorResponse{Error: "not found"})
				return
			}
			namespace, resourceName = storedNamespace, storedName
		}

		sar := &authzv1.SubjectAccessReview{
			Spec: authzv1.SubjectAccessReviewSpec{
				User:   user,
				Groups: groups,
				ResourceAttributes: &authzv1.ResourceAttributes{
					Namespace: namespace,
					Verb:      verb,
					Group:     group,
					Resource:  resource,
					Name:      resourceName,
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

// resolveStoredResourceScope resolves the authoritative namespace for
// UUID-addressed collaboration resources before SubjectAccessReview. This is
// intentionally performed in middleware so every current and future handler
// under the route receives the same fail-closed behavior.
func (s *Server) resolveStoredResourceScope(c *gin.Context) (tenant, namespace, name string, found bool, err error) {
	if s.store == nil {
		return "", "", "", false, nil
	}
	parse := func(param string) (uuid.UUID, bool, error) {
		raw := c.Param(param)
		if raw == "" {
			return uuid.Nil, false, nil
		}
		id, parseErr := uuid.Parse(raw)
		if parseErr != nil {
			return uuid.Nil, true, store.ErrNotFound
		}
		return id, true, nil
	}
	if id, ok, parseErr := parse("issueId"); ok {
		if parseErr != nil {
			return "", "", "", true, parseErr
		}
		item, loadErr := s.store.Collaboration().GetIssue(c.Request.Context(), id)
		if loadErr != nil {
			return "", "", "", true, loadErr
		}
		return item.Tenant, item.Namespace, item.ID.String(), true, nil
	}
	if id, ok, parseErr := parse("commentId"); ok {
		if parseErr != nil {
			return "", "", "", true, parseErr
		}
		item, loadErr := s.store.Collaboration().GetComment(c.Request.Context(), id)
		if loadErr != nil {
			return "", "", "", true, loadErr
		}
		return item.Tenant, item.Namespace, item.ID.String(), true, nil
	}
	if id, ok, parseErr := parse("taskId"); ok {
		if parseErr != nil {
			return "", "", "", true, parseErr
		}
		item, loadErr := s.store.Collaboration().GetAgentTask(c.Request.Context(), id)
		if loadErr != nil {
			return "", "", "", true, loadErr
		}
		return item.Tenant, item.Namespace, item.ID.String(), true, nil
	}
	if id, ok, parseErr := parse("teamId"); ok {
		if parseErr != nil {
			return "", "", "", true, parseErr
		}
		item, loadErr := s.store.Collaboration().GetTeam(c.Request.Context(), id)
		if loadErr != nil {
			return "", "", "", true, loadErr
		}
		return item.Tenant, item.Namespace, item.ID.String(), true, nil
	}
	if id, ok, parseErr := parse("artifactId"); ok {
		if parseErr != nil {
			return "", "", "", true, parseErr
		}
		item, _, loadErr := s.store.Collaboration().GetArtifact(c.Request.Context(), id)
		if loadErr != nil {
			return "", "", "", true, loadErr
		}
		return item.Tenant, item.Namespace, item.ID.String(), true, nil
	}
	if id, ok, parseErr := parse("inboxId"); ok {
		if parseErr != nil {
			return "", "", "", true, parseErr
		}
		item, loadErr := s.store.Collaboration().GetInbox(c.Request.Context(), id, s.operatorFromContext(c))
		if loadErr != nil {
			return "", "", "", true, loadErr
		}
		return item.Tenant, item.Namespace, item.ID.String(), true, nil
	}
	if id, ok, parseErr := parse("approvalId"); ok {
		if parseErr != nil {
			return "", "", "", true, parseErr
		}
		item, loadErr := s.store.Collaboration().GetApproval(c.Request.Context(), id)
		if loadErr != nil {
			return "", "", "", true, loadErr
		}
		return item.Tenant, item.Namespace, item.ID.String(), true, nil
	}
	if id, ok, parseErr := parse("automationId"); ok {
		if parseErr != nil {
			return "", "", "", true, parseErr
		}
		item, loadErr := s.store.Collaboration().GetAutomation(c.Request.Context(), id)
		if loadErr != nil {
			return "", "", "", true, loadErr
		}
		return item.Tenant, item.Namespace, item.ID.String(), true, nil
	}
	if id, ok, parseErr := parse("definitionId"); ok {
		if parseErr != nil {
			return "", "", "", true, parseErr
		}
		item, loadErr := s.store.Orchestration().GetDefinition(c.Request.Context(), id)
		if loadErr != nil {
			return "", "", "", true, loadErr
		}
		return item.Tenant, item.Namespace, item.ID.String(), true, nil
	}
	if id, ok, parseErr := parse("runId"); ok {
		if parseErr != nil {
			return "", "", "", true, parseErr
		}
		item, loadErr := s.store.Orchestration().GetRun(c.Request.Context(), id)
		if loadErr != nil {
			return "", "", "", true, loadErr
		}
		return item.Tenant, item.Namespace, item.ID.String(), true, nil
	}
	if id, ok, parseErr := parse("attemptId"); ok {
		if parseErr != nil {
			return "", "", "", true, parseErr
		}
		item, loadErr := s.store.ExecutionAttempts().Get(c.Request.Context(), id)
		if loadErr != nil {
			return "", "", "", true, loadErr
		}
		return item.Tenant, item.Namespace, item.ID.String(), true, nil
	}
	return "", "", "", false, nil
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

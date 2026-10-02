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
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"slices"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

const ctxNamespaceAccess = "namespaceAccess"

const globalNamespaceOwner = "system"

type namespaceAccess struct {
	User      string
	Refs      []string
	Namespace *controlmodel.Namespace
	Roles     []string
}

func accessFrom(c *gin.Context) *namespaceAccess {
	value, _ := c.Get(ctxNamespaceAccess)
	a, _ := value.(*namespaceAccess)
	return a
}
func personalNamespace(user string) string {
	sum := sha256.Sum256([]byte(user))
	return "personal-" + hex.EncodeToString(sum[:10])
}

func (s *Server) ensureGlobalDefaultNamespace(ctx context.Context) (*controlmodel.Namespace, error) {
	n, err := s.store.Access().GetNamespace(ctx, s.defaultTenant, s.defaultNamespace)
	if err == nil {
		if n.Kind != "global" {
			return nil, errors.New("configured default namespace already exists but is not global")
		}
		return n, nil
	}
	if !errors.Is(err, store.ErrNotFound) {
		return nil, err
	}
	n, err = s.store.Access().PutNamespace(ctx, &controlmodel.Namespace{
		Tenant:      s.defaultTenant,
		Name:        s.defaultNamespace,
		DisplayName: "Default",
		Kind:        "global",
		Owner:       globalNamespaceOwner,
		Members:     map[string][]string{},
	}, 0, globalNamespaceOwner)
	if errors.Is(err, store.ErrConflict) {
		n, err = s.store.Access().GetNamespace(ctx, s.defaultTenant, s.defaultNamespace)
		if err == nil && n.Kind != "global" {
			return nil, errors.New("configured default namespace already exists but is not global")
		}
	}
	return n, err
}

func (s *Server) ensurePersonalNamespace(ctx context.Context, user string) (*controlmodel.Namespace, error) {
	name := personalNamespace(user)
	n, err := s.store.Access().GetNamespace(ctx, s.defaultTenant, name)
	if !errors.Is(err, store.ErrNotFound) {
		return n, err
	}
	n, err = s.store.Access().PutNamespace(ctx, &controlmodel.Namespace{Tenant: s.defaultTenant, Name: name, DisplayName: "Personal", Kind: "personal", Owner: user, Members: map[string][]string{}}, 0, user)
	if errors.Is(err, store.ErrConflict) {
		return s.store.Access().GetNamespace(ctx, s.defaultTenant, name)
	}
	return n, err
}

func namespaceAction(c *gin.Context) string {
	path := strings.TrimPrefix(c.Request.URL.Path, "/api/v1/")
	resource, rest, _ := strings.Cut(path, "/")
	read := c.Request.Method == http.MethodGet || c.Request.Method == http.MethodHead
	switch resource {
	case "me":
		return "read"
	case "issues", "inbox", "chats", "approvals", "automations":
		if read {
			return "read"
		}
		return "work.write"
	case "events", "chat-agents", "entity-identities:resolve":
		return "read"
	case "artifacts":
		if read || strings.HasSuffix(rest, "/download") {
			return "read"
		}
		return "work.write"
	case "agent-tasks", "orchestration-runs", "execution-attempts", "sessions":
		if resource == "sessions" && strings.HasSuffix(rest, "/context") {
			return "configure"
		}
		if read {
			return "read"
		}
		return "work.write"
	case "agents", "teams":
		if rest == "runtime-options" {
			return "configure"
		}
		if !read {
			return "resource.write"
		}
		if rest == "" || !strings.Contains(rest, "/") {
			return "discover"
		}
		return "configure"
	case "orchestration-definitions":
		if strings.HasSuffix(rest, "/runs") && !read {
			return "use"
		}
		if read && rest == "" {
			return "discover"
		}
		if read {
			return "configure"
		}
		return "resource.write"
	case "endpoints", "work-sources", "modelconfigs", "mcpservers", "agent-registrations", "agent-runtime-policies":
		if read {
			return "configure"
		}
		return "resource.write"
	case "overview", "metrics", "agent-instances", "runtime-profiles", "runtime-pools", "runtime-hosts", "runtime-host-enrollments", "runtime-host-enrollment-tokens", "runtime-bindings", "dead-letters", "dataplanes", "commands", "sandboxes", "audit", "usage", "budgets":
		return "operate"
	default:
		return ""
	}
}

// namespaceAccessMiddleware is the console's resource authority. The legacy
// navigation roles and Kubernetes SAR are not substitutes for this decision.
func (s *Server) namespaceAccessMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		if console, _ := c.Get(ctxConsoleAuth); console != true || s.store == nil {
			c.Next()
			return
		}
		user := c.GetString("userId")
		if user == "" {
			c.AbortWithStatusJSON(403, ErrorResponse{Error: "authenticated account ID is required"})
			return
		}
		personal, err := s.ensurePersonalNamespace(c.Request.Context(), user)
		if err != nil {
			s.accessFailure(c, err)
			return
		}
		path := strings.TrimPrefix(c.Request.URL.Path, "/api/v1/")
		if path == "me/preferences" || strings.HasPrefix(path, "access/") || path == "me/scope" || path == "me/namespaces" || path == "me/navigation" || path == "namespaces" || strings.HasPrefix(path, "namespaces/") {
			c.Next()
			return
		}
		body := map[string]json.RawMessage{}
		if c.Request.Body != nil && strings.Contains(c.ContentType(), "application/json") {
			data, readErr := io.ReadAll(io.LimitReader(c.Request.Body, (16<<20)+1))
			if readErr != nil || len(data) > 16<<20 {
				c.AbortWithStatusJSON(400, ErrorResponse{Error: "request body is invalid or too large"})
				return
			}
			c.Request.Body = io.NopCloser(bytes.NewReader(data))
			if len(data) > 0 {
				_ = json.Unmarshal(data, &body)
			}
		}
		bodyString := func(key string) string { var v string; _ = json.Unmarshal(body[key], &v); return strings.TrimSpace(v) }
		tenant, namespace := strings.TrimSpace(c.Query("tenant")), strings.TrimSpace(c.Query("namespace"))
		if tenant == "" {
			tenant = c.GetHeader("X-AgentScope-Tenant")
		}
		if namespace == "" {
			namespace = c.GetHeader("X-AgentScope-Namespace")
		}
		for key, current := range map[string]*string{"tenant": &tenant, "namespace": &namespace} {
			if b := bodyString(key); b != "" {
				if *current != "" && *current != b {
					c.AbortWithStatusJSON(400, ErrorResponse{Error: "request scope conflicts with body scope"})
					return
				}
				*current = b
			}
		}
		storedTenant, storedNamespace, _, found, err := s.resolveAccessObjectScope(c)
		if err != nil {
			s.accessFailure(c, err)
			return
		}
		if found {
			if tenant != "" && tenant != storedTenant || namespace != "" && namespace != storedNamespace {
				s.accessFailure(c, store.ErrNotFound)
				return
			}
			tenant, namespace = storedTenant, storedNamespace
		}
		if tenant == "" {
			tenant = personal.Tenant
		}
		if namespace == "" {
			namespace = personal.Name
		}
		n, err := s.store.Access().GetNamespace(c.Request.Context(), tenant, namespace)
		if err != nil {
			s.accessFailure(c, err)
			return
		}
		roles := n.Roles(user)
		if len(roles) == 0 {
			s.accessFailure(c, store.ErrNotFound)
			return
		}
		a := &namespaceAccess{User: user, Refs: []string{user}, Namespace: n, Roles: roles}
		// Legacy Issue actors were usernames. Only the authenticated account's
		// own username is accepted during the transition to stable IDs.
		if name := c.GetString("username"); name != "" && name != user {
			a.Refs = append(a.Refs, name)
		}
		c.Set(ctxNamespaceAccess, a)
		kind, resourceID, resourceAction := resourceRoute(c)
		allowed := controlmodel.NamespaceAllows(roles, namespaceAction(c))
		if kind != "" && resourceID != "" {
			allowed = n.Decide(user, kind+":"+resourceID, resourceAction).Allowed
		}
		if !allowed {
			c.AbortWithStatusJSON(403, ErrorResponse{Error: "namespace role does not allow this action"})
			return
		}
		q := c.Request.URL.Query()
		q.Set("tenant", tenant)
		q.Set("namespace", namespace)
		c.Request.URL.RawQuery = q.Encode()
		if len(body) > 0 {
			body["tenant"], _ = json.Marshal(tenant)
			body["namespace"], _ = json.Marshal(namespace)
			data, _ := json.Marshal(body)
			c.Request.Body = io.NopCloser(bytes.NewReader(data))
			c.Request.ContentLength = int64(len(data))
		}
		c.Request = c.Request.WithContext(store.WithWorkAccess(c.Request.Context(), store.WorkAccess{Refs: a.Refs, Restricted: !controlmodel.NamespaceAllows(roles, "work.audit")}))
		if !s.authorizeWorkObject(c, body) {
			return
		}
		c.Next()
	}
}

func (s *Server) accessFailure(c *gin.Context, err error) {
	status := http.StatusForbidden
	message := "authorization check failed"
	if errors.Is(err, store.ErrNotFound) {
		status = http.StatusNotFound
		message = "resource not found in authorized scope"
	}
	c.AbortWithStatusJSON(status, ErrorResponse{Error: message})
}

func (s *Server) resolveAccessObjectScope(c *gin.Context) (string, string, string, bool, error) {
	ctx := c.Request.Context()
	for _, key := range []string{"agentId", "sessionId", "chatId", "endpointId", "workSourceId", "instanceId", "hostId", "bindingId", "proposalId"} {
		raw := c.Param(key)
		if raw == "" {
			continue
		}
		id, err := uuid.Parse(raw)
		if err != nil {
			return "", "", "", true, store.ErrNotFound
		}
		switch key {
		case "agentId":
			v, e := s.store.AgentCatalog().GetAgent(ctx, id)
			if e != nil {
				return "", "", "", true, e
			}
			return v.Tenant, v.Namespace, raw, true, nil
		case "sessionId":
			v, e := s.store.Sessions().GetByID(ctx, id)
			if e != nil {
				return "", "", "", true, e
			}
			return v.Tenant, v.Namespace, raw, true, nil
		case "chatId":
			v, e := s.store.Chats().Get(ctx, id)
			if e != nil {
				return "", "", "", true, e
			}
			return v.Tenant, v.Namespace, raw, true, nil
		case "endpointId":
			v, e := s.store.Endpoints().Get(ctx, id)
			if e != nil {
				return "", "", "", true, e
			}
			return v.Tenant, v.Namespace, raw, true, nil
		case "workSourceId":
			v, e := s.store.WorkSources().GetWorkSource(ctx, id)
			if e != nil {
				return "", "", "", true, e
			}
			return v.Tenant, v.Namespace, raw, true, nil
		case "instanceId":
			v, e := s.store.RuntimeRegistry().GetAgentInstance(ctx, id)
			if e != nil {
				return "", "", "", true, e
			}
			return v.Tenant, v.Namespace, raw, true, nil
		case "hostId":
			v, e := s.store.RuntimeRegistry().GetRuntimeHost(ctx, id)
			if e != nil {
				return "", "", "", true, e
			}
			return v.Tenant, v.Namespace, raw, true, nil
		case "bindingId":
			v, e := s.store.AgentCatalog().GetBinding(ctx, id)
			if e != nil {
				return "", "", "", true, e
			}
			return v.Tenant, v.Namespace, raw, true, nil
		case "proposalId":
			v, e := s.store.TeamProposals().Get(ctx, id)
			if e != nil {
				return "", "", "", true, e
			}
			return v.Tenant, v.Namespace, raw, true, nil
		}
	}
	return s.resolveStoredResourceScope(c)
}

func (s *Server) canAccessIssue(ctx context.Context, a *namespaceAccess, id uuid.UUID, write bool) (*controlmodel.Issue, error) {
	seen := map[uuid.UUID]bool{}
	for !seen[id] {
		seen[id] = true
		v, err := s.store.Collaboration().GetIssue(ctx, id)
		if err != nil {
			return nil, err
		}
		if v.Tenant != a.Namespace.Tenant || v.Namespace != a.Namespace.Name {
			return nil, store.ErrNotFound
		}
		if v.ParentIssueID != nil {
			id = *v.ParentIssueID
			continue
		}
		if v.Access.Allows(v.Creator, a.Refs, write) || !write && controlmodel.NamespaceAllows(a.Roles, "work.audit") {
			return v, nil
		}
		return nil, store.ErrNotFound
	}
	return nil, store.ErrNotFound
}

func (s *Server) listMyNamespaces(c *gin.Context) {
	user := c.GetString("userId")
	if user == "" {
		c.JSON(403, ErrorResponse{Error: "console account required"})
		return
	}
	items, err := s.store.Access().ListNamespaces(c.Request.Context(), s.defaultTenant, user, 500, 0)
	if err != nil {
		s.accessFailure(c, err)
		return
	}
	c.JSON(200, gin.H{"items": namespaceSummaries(items, user)})
}
func namespaceSummaries(items []*controlmodel.Namespace, user string) []gin.H {
	out := []gin.H{}
	for _, n := range items {
		out = append(out, gin.H{"tenant": n.Tenant, "name": n.Name, "displayName": n.DisplayName, "kind": n.Kind, "roles": n.Roles(user), "owner": n.Owner, "accessVersion": n.Version, "groups": n.GroupIDs(user)})
	}
	return out
}
func (s *Server) createNamespace(c *gin.Context) {
	user := c.GetString("userId")
	if user == "" || !roleSet(c)["admin"] {
		c.JSON(403, ErrorResponse{Error: "platform admin is required to provision shared namespaces"})
		return
	}
	var in struct {
		Name        string              `json:"name"`
		DisplayName string              `json:"displayName"`
		Owner       string              `json:"owner"`
		Members     map[string][]string `json:"members"`
	}
	if c.ShouldBindJSON(&in) != nil || strings.HasPrefix(in.Name, "personal-") {
		c.JSON(400, ErrorResponse{Error: "valid shared namespace name required"})
		return
	}
	owner := in.Owner
	if owner == "" {
		owner = user
	}
	n := &controlmodel.Namespace{Tenant: s.defaultTenant, Name: in.Name, DisplayName: in.DisplayName, Kind: "shared", Owner: owner, Members: in.Members}
	if n.Members == nil {
		n.Members = map[string][]string{}
	}
	if owner != user && !n.Manages(user) {
		n.Members[user] = append(n.Members[user], "admin")
	}
	if err := n.Validate(); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	if err := s.validateNamespaceAccounts(c.Request.Context(), n, nil); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	v, err := s.store.Access().PutNamespace(c.Request.Context(), n, 0, user)
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(201, gin.H{"namespace": v})
}
func (s *Server) namespaceForManagement(c *gin.Context) (*controlmodel.Namespace, bool) {
	user := c.GetString("userId")
	if user == "" {
		s.accessFailure(c, store.ErrNotFound)
		return nil, false
	}
	n, err := s.store.Access().GetNamespace(c.Request.Context(), s.defaultTenant, c.Param("namespaceName"))
	if err != nil {
		s.accessFailure(c, err)
		return nil, false
	}
	if n.Owner != user && !n.Manages(user) && !roleSet(c)["admin"] {
		s.accessFailure(c, store.ErrNotFound)
		return nil, false
	}
	return n, true
}
func (s *Server) getNamespaceAccess(c *gin.Context) {
	n, ok := s.namespaceForManagement(c)
	if ok {
		c.JSON(200, gin.H{"namespace": n})
	}
}
func (s *Server) updateNamespaceAccess(c *gin.Context) {
	n, ok := s.namespaceForManagement(c)
	if !ok {
		return
	}
	if n.Kind == "global" {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "global default namespace is managed by the platform"})
		return
	}
	var in struct {
		Members     *map[string][]string `json:"members"`
		DisplayName *string              `json:"displayName"`
		Archived    *bool                `json:"archived"`
		Version     int64                `json:"version"`
	}
	if c.ShouldBindJSON(&in) != nil || in.Version <= 0 {
		c.JSON(400, ErrorResponse{Error: "Expected version required"})
		return
	}
	old := *n
	if in.Members != nil {
		n.Members = *in.Members
	}
	if in.DisplayName != nil {
		n.DisplayName = *in.DisplayName
	}
	if in.Archived != nil {
		if n.Owner != c.GetString("userId") && !roleSet(c)["admin"] {
			c.JSON(403, ErrorResponse{Error: "Only the owner or a platform administrator can archive or restore a namespace"})
			return
		}
		n.Archived = *in.Archived
	}
	if sensitiveGrantChanged(&old, n) && n.Owner != c.GetString("userId") && !roleSet(c)["admin"] {
		c.JSON(403, ErrorResponse{Error: "Only the owner or platform administrator can change auditor grants"})
		return
	}
	if err := n.Validate(); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	if err := s.validateNamespaceAccounts(c.Request.Context(), n, &old); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	v, err := s.store.Access().PutNamespace(c.Request.Context(), n, in.Version, c.GetString("userId"))
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(200, gin.H{"namespace": v})
}

func (s *Server) updateIssueAccess(c *gin.Context) {
	a := accessFrom(c)
	if a == nil {
		c.JSON(403, ErrorResponse{Error: "console account required"})
		return
	}
	id, ok := parseUUIDParam(c, "issueId")
	if !ok {
		return
	}
	root, err := s.canAccessIssue(c.Request.Context(), a, id, true)
	if err != nil || root.ID != id || root.Creator.Type != controlmodel.ActorHuman || !slices.Contains(a.Refs, root.Creator.Ref) {
		c.JSON(403, ErrorResponse{Error: "only the root Issue creator may change sharing"})
		return
	}
	var in struct {
		Access  controlmodel.IssueAccess `json:"access"`
		Version int64                    `json:"version"`
	}
	if c.ShouldBindJSON(&in) != nil || in.Version <= 0 {
		c.JSON(400, ErrorResponse{Error: "access policy and expected version required"})
		return
	}
	if err := in.Access.Validate(); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	for member := range in.Access.Members {
		if len(a.Namespace.Roles(member)) == 0 {
			c.JSON(400, ErrorResponse{Error: "collaborators must be namespace members"})
			return
		}
	}
	root.Access = in.Access
	v, err := s.store.Collaboration().UpdateIssue(c.Request.Context(), root, in.Version, humanActor(c, s))
	if err != nil {
		s.writeCollaborationError(c, err)
		return
	}
	c.JSON(200, gin.H{"issue": v})
}

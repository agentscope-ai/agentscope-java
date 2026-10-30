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
package model

import (
	"fmt"
	"slices"
	"strings"
	"time"
)

// AccessGroup groups people, independently of execution Teams. Membership is
// namespace-local and grants no implicit access to another namespace.
type AccessGroup struct {
	Name    string   `json:"name"`
	Members []string `json:"members"`
	Roles   []string `json:"roles"`
}
type ResourcePolicy struct {
	Mode   string              `json:"mode"` // inherit or restricted
	Users  map[string][]string `json:"users,omitempty"`
	Groups map[string][]string `json:"groups,omitempty"`
	// Consumers permits named resources to use this dependency without exposing
	// its configuration to their callers. Keys are kind:id in this namespace.
	Consumers []string `json:"consumers,omitempty"`
	// Workflow exports permit explicit template import, never ambient execution.
	ExportTo []string `json:"exportTo,omitempty"`
}
type ResourceDescriptor struct {
	Kind         string   `json:"kind"`
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	Dependencies []string `json:"dependencies"`
}

func (r ResourceDescriptor) Key() string { return r.Kind + ":" + r.ID }

var ResourceActions = []string{"discover", "use", "inspect", "edit", "publish", "manage"}
var ResourceKinds = []string{"agent", "managed-agent", "team", "workflow", "channel", "workspace", "memory", "vault", "environment", "model", "mcp"}

func ResourceKey(key string) (string, string, bool) {
	kind, id, ok := strings.Cut(key, ":")
	return kind, id, ok && slices.Contains(ResourceKinds, kind) && id != "" && len(id) <= 200 && !strings.ContainsAny(id, "/\\\x00")
}

type AccessRequest struct {
	ID         string    `json:"id"`
	User       string    `json:"user"`
	Resource   string    `json:"resource"`
	Action     string    `json:"action"`
	Reason     string    `json:"reason"`
	Status     string    `json:"status"`
	ReviewedBy string    `json:"reviewedBy,omitempty"`
	CreatedAt  time.Time `json:"createdAt"`
}
type AccessDecision struct {
	Allowed bool     `json:"allowed"`
	Action  string   `json:"action"`
	Reason  string   `json:"reason"`
	Sources []string `json:"sources"`
}

func (n Namespace) GroupIDs(user string) []string {
	ids := []string{}
	for id, g := range n.Groups {
		if slices.Contains(g.Members, user) {
			ids = append(ids, id)
		}
	}
	slices.Sort(ids)
	return ids
}
func (n Namespace) Decide(user, key, action string) AccessDecision {
	d := AccessDecision{Action: action, Sources: []string{}}
	if len(n.Roles(user)) == 0 {
		d.Reason = "Namespace membership is required"
		return d
	}
	if !slices.Contains(ResourceActions, action) {
		d.Reason = "Unknown resource action"
		return d
	}
	p, configured := n.Resources[key]
	roles := n.Roles(user)
	if action == "manage" && NamespaceAllows(roles, "members.manage") {
		d.Allowed = true
		d.Reason = "Namespace administrators manage resource grants"
		d.Sources = []string{"namespace:admin"}
		return d
	}
	baseline := false
	switch action {
	case "discover":
		baseline = true
	case "use":
		baseline = NamespaceAllows(roles, "use")
	case "inspect", "edit", "publish":
		baseline = NamespaceAllows(roles, "configure")
	case "manage":
		baseline = NamespaceAllows(roles, "members.manage")
	}
	if (!configured || p.Mode == "inherit") && baseline {
		d.Allowed = true
		d.Sources = append(d.Sources, "namespace roles")
	}
	allows := func(actions []string) bool {
		return slices.Contains(actions, action) || action == "discover" && len(actions) > 0 || action == "inspect" && slices.Contains(actions, "edit")
	}
	if allows(p.Users[user]) {
		d.Allowed = true
		d.Sources = append(d.Sources, "direct resource grant")
	}
	for _, id := range n.GroupIDs(user) {
		if allows(p.Groups[id]) {
			d.Allowed = true
			d.Sources = append(d.Sources, "group:"+id)
		}
	}
	if d.Allowed {
		d.Reason = "Allowed by " + strings.Join(d.Sources, ", ")
	} else {
		d.Reason = "Ask a resource manager for " + action + " access"
	}
	return d
}
func (n Namespace) validateResourceAccess() error {
	if len(n.Groups) > 200 || len(n.Resources) > 5000 || len(n.Requests) > 2000 {
		return fmt.Errorf("namespace access configuration is too large")
	}
	if n.Kind != "shared" && len(n.Groups) > 0 {
		return fmt.Errorf("user groups require a shared namespace")
	}
	validRoles := []string{"viewer", "member", "developer", "operator", "admin", "auditor"}
	for id, g := range n.Groups {
		if !namespaceName.MatchString(id) || strings.TrimSpace(g.Name) == "" || len(g.Name) > 100 || len(g.Members) > 1000 || len(g.Roles) == 0 {
			return fmt.Errorf("invalid user group %s", id)
		}
		for _, role := range g.Roles {
			if !slices.Contains(validRoles, role) {
				return fmt.Errorf("unknown group role %s", role)
			}
		}
		for _, user := range g.Members {
			if strings.TrimSpace(user) == "" {
				return fmt.Errorf("empty group member")
			}
		}
	}
	for key, p := range n.Resources {
		kind, _, ok := ResourceKey(key)
		if !ok || p.Mode != "inherit" && p.Mode != "restricted" {
			return fmt.Errorf("invalid resource policy %s", key)
		}
		for group := range p.Groups {
			if _, ok := n.Groups[group]; !ok {
				return fmt.Errorf("unknown user group %s", group)
			}
		}
		for _, grants := range []map[string][]string{p.Users, p.Groups} {
			for principal, actions := range grants {
				if principal == "" || len(actions) == 0 {
					return fmt.Errorf("principal and actions are required")
				}
				for _, action := range actions {
					if !slices.Contains(ResourceActions, action) {
						return fmt.Errorf("unknown resource action %s", action)
					}
				}
			}
		}
		for _, consumer := range p.Consumers {
			if _, _, ok := ResourceKey(consumer); !ok || consumer == key {
				return fmt.Errorf("invalid dependency consumer")
			}
		}
		if len(p.ExportTo) > 0 && kind != "workflow" {
			return fmt.Errorf("only Workflow templates support cross-namespace export")
		}
		for _, target := range p.ExportTo {
			if !namespaceName.MatchString(target) || target == n.Name {
				return fmt.Errorf("invalid export namespace")
			}
		}
	}
	return nil
}

func (n Namespace) Manages(user string) bool {
	n.Archived = false
	return n.Owner == user || NamespaceAllows(n.Roles(user), "members.manage")
}

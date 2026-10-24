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
	"regexp"
	"slices"
	"strings"
	"time"
)

// Namespace is a product authorization boundary, independent of runtime files.
// Members are keyed by stable account IDs, never display names.
type Namespace struct {
	Groups      map[string]AccessGroup    `json:"groups,omitempty"`
	Resources   map[string]ResourcePolicy `json:"resources,omitempty"`
	Requests    []AccessRequest           `json:"requests,omitempty"`
	Tenant      string                    `json:"tenant"`
	Name        string                    `json:"name"`
	DisplayName string                    `json:"displayName"`
	Kind        string                    `json:"kind"`
	Owner       string                    `json:"owner"`
	Members     map[string][]string       `json:"members"`
	Version     int64                     `json:"version"`
	Archived    bool                      `json:"archived"`
}

type NamespaceAudit struct {
	ID        int64     `json:"id"`
	Tenant    string    `json:"tenant"`
	Name      string    `json:"name"`
	Actor     string    `json:"actor"`
	Namespace Namespace `json:"namespace"`
	Version   int64     `json:"version"`
	CreatedAt time.Time `json:"createdAt"`
}

var namespaceName = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,62}$`)

func (n Namespace) Validate() error {
	if !namespaceName.MatchString(n.Tenant) || !namespaceName.MatchString(n.Name) || strings.TrimSpace(n.DisplayName) == "" || len(n.DisplayName) > 200 || n.Owner == "" {
		return fmt.Errorf("valid tenant, namespace name, display name and owner are required")
	}
	if n.Kind != "personal" && n.Kind != "shared" && n.Kind != "global" {
		return fmt.Errorf("namespace kind must be personal, shared or global")
	}
	if n.Kind == "personal" && (len(n.Members) > 0 || n.Archived) {
		return fmt.Errorf("personal namespace membership cannot be changed")
	}
	if n.Kind == "global" && (len(n.Members) > 0 || n.Archived) {
		return fmt.Errorf("global namespace membership and lifecycle are platform managed")
	}
	if len(n.Members) > 1000 {
		return fmt.Errorf("namespace supports at most 1000 members")
	}
	for user, roles := range n.Members {
		if strings.TrimSpace(user) == "" || len(roles) == 0 {
			return fmt.Errorf("member identity and roles are required")
		}
		for _, role := range roles {
			if !slices.Contains([]string{"viewer", "member", "developer", "operator", "admin", "auditor"}, role) {
				return fmt.Errorf("unknown namespace role %q", role)
			}
		}
	}
	return n.validateResourceAccess()
}

func (n Namespace) Roles(user string) []string {
	if n.Archived || user == "" {
		return nil
	}
	if n.Kind == "global" {
		return []string{"member", "developer", "operator"}
	}
	if n.Owner == user {
		roles := []string{"admin", "member", "developer", "operator"}
		auditor := slices.Contains(n.Members[user], "auditor")
		for _, id := range n.GroupIDs(user) {
			auditor = auditor || slices.Contains(n.Groups[id].Roles, "auditor")
		}
		if auditor {
			roles = append(roles, "auditor")
		}
		return roles
	}
	roles := slices.Clone(n.Members[user])
	for _, id := range n.GroupIDs(user) {
		for _, role := range n.Groups[id].Roles {
			if !slices.Contains(roles, role) {
				roles = append(roles, role)
			}
		}
	}
	return roles
}

// NamespaceAllows separates invocation, definition management and private data.
func NamespaceAllows(roles []string, action string) bool {
	has := func(role string) bool { return slices.Contains(roles, role) }
	switch action {
	case "discover", "read":
		return len(roles) > 0
	case "use", "work.write":
		return has("member") || has("developer") || has("admin")
	case "configure", "resource.write":
		return has("developer") || has("admin")
	case "operate":
		return has("operator") || has("admin")
	case "members.manage":
		return has("admin")
	case "work.audit":
		return has("auditor")
	default:
		return false
	}
}

type IssueAccess struct {
	Mode    string            `json:"mode"`
	Members map[string]string `json:"members,omitempty"`
}

func (a IssueAccess) Validate() error {
	if a.Mode != "private" && a.Mode != "shared" && a.Mode != "namespace" {
		return fmt.Errorf("issue access mode must be private, shared or namespace")
	}
	if len(a.Members) > 1000 {
		return fmt.Errorf("issue supports at most 1000 collaborators")
	}
	if a.Mode != "shared" && len(a.Members) > 0 {
		return fmt.Errorf("collaborators require shared access")
	}
	for user, role := range a.Members {
		if strings.TrimSpace(user) == "" || role != "reader" && role != "contributor" {
			return fmt.Errorf("collaborator requires an account ID and reader or contributor role")
		}
	}
	return nil
}

// Allows is evaluated on the root Issue, after namespace membership is checked.
func (a IssueAccess) Allows(creator Actor, refs []string, write bool) bool {
	if a.Mode == "namespace" {
		return true
	}
	for _, ref := range refs {
		if ref != "" && creator.Type == ActorHuman && creator.Ref == ref {
			return true
		}
		if a.Mode == "shared" {
			role := a.Members[ref]
			if role == "contributor" || role == "reader" && !write {
				return true
			}
		}
	}
	return false
}

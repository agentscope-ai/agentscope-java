// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package model

import (
	"fmt"
	"regexp"
	"slices"
	"strings"
)

// Namespace is a product authorization boundary, independent of runtime files.
// Members are keyed by stable account IDs, never display names.
type Namespace struct {
	Tenant      string              `json:"tenant"`
	Name        string              `json:"name"`
	DisplayName string              `json:"displayName"`
	Kind        string              `json:"kind"`
	Owner       string              `json:"owner"`
	Members     map[string][]string `json:"members"`
	Version     int64               `json:"version"`
}

var namespaceName = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,62}$`)

func (n Namespace) Validate() error {
	if !namespaceName.MatchString(n.Tenant) || !namespaceName.MatchString(n.Name) || strings.TrimSpace(n.DisplayName) == "" || len(n.DisplayName) > 200 || n.Owner == "" {
		return fmt.Errorf("valid tenant, namespace name, display name and owner are required")
	}
	if n.Kind != "personal" && n.Kind != "shared" {
		return fmt.Errorf("namespace kind must be personal or shared")
	}
	if n.Kind == "personal" && len(n.Members) > 0 {
		return fmt.Errorf("personal namespace membership cannot be changed")
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
	return nil
}

func (n Namespace) Roles(user string) []string {
	if n.Owner == user {
		roles := []string{"admin", "member", "developer", "operator"}
		if slices.Contains(n.Members[user], "auditor") {
			roles = append(roles, "auditor")
		}
		return roles
	}
	return n.Members[user]
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

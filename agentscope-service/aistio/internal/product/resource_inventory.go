// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package product

import (
	"context"
	"encoding/json"
	"fmt"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"strings"
)

// ResourceInventory exposes identity and structural dependencies, never secret
// values, environment configuration, prompts or workspace file content.
func (s *Server) ResourceInventory(ctx context.Context, owner string) ([]model.ResourceDescriptor, error) {
	out := []model.ResourceDescriptor{}
	queries := []struct{ kind, query string }{
		{"managed-agent", `SELECT agent_id,name FROM agents WHERE owner_id=$1 AND archived_at IS NULL`},
		{"workspace", `SELECT workspace_id,name FROM workspaces WHERE owner_id=$1 AND archived_at IS NULL`},
		{"environment", `SELECT environment_id,name FROM environments WHERE owner_id=$1 AND archived_at IS NULL`},
		{"memory", `SELECT store_id,name FROM memory_stores WHERE owner_id=$1 AND archived_at IS NULL`},
		{"vault", `SELECT vault_id,display_name FROM vaults WHERE owner_id=$1 AND archived_at IS NULL`},
		{"channel", `SELECT channel_id,type FROM channels WHERE owner_id=$1`},
	}
	for _, q := range queries {
		rows, err := s.db.Pool.Query(ctx, q.query, owner)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			var r model.ResourceDescriptor
			r.Kind = q.kind
			r.Dependencies = []string{}
			if err = rows.Scan(&r.ID, &r.Name); err != nil {
				rows.Close()
				return nil, err
			}
			out = append(out, r)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return nil, err
		}
	}
	for i := range out {
		r := &out[i]
		if r.Kind == "managed-agent" {
			a, err := s.loadAgent(ctx, owner, r.ID)
			if err != nil {
				return nil, err
			}
			add := func(kind, id string) {
				if strings.TrimSpace(id) != "" {
					r.Dependencies = append(r.Dependencies, kind+":"+id)
				}
			}
			add("workspace", deref(a.WorkspaceID))
			add("environment", deref(a.DefaultEnvironmentID))
			for _, id := range parseStringSlice(deref(a.DefaultVaultIDsJSON)) {
				add("vault", id)
			}
			for _, id := range parseStringSlice(deref(a.DefaultMemoryStoreIDsJSON)) {
				add("memory", id)
			}
			// MCP connections can refer to an OAuth vault; that vault remains a
			// dependency even when no explicit session-default vault was selected.
			var raw any
			if json.Unmarshal([]byte(deref(a.McpServersJSON)), &raw) == nil {
				r.Dependencies = append(r.Dependencies, resourceVaultRefs(raw)...)
			}
		}
	}
	return out, nil
}
func resourceVaultRefs(v any) []string {
	refs := []string{}
	switch x := v.(type) {
	case map[string]any:
		for k, v := range x {
			if k == "vaultId" || k == "oauthVaultId" {
				if id, ok := v.(string); ok && id != "" {
					refs = append(refs, "vault:"+id)
				}
			}
			refs = append(refs, resourceVaultRefs(v)...)
		}
	case []any:
		for _, v := range x {
			refs = append(refs, resourceVaultRefs(v)...)
		}
	}
	return refs
}

// ResolveProductResourceOwner is intentionally not exposed over HTTP.
func (s *Server) ResolveProductResourceOwner(ctx context.Context, kind, id, owner string) (model.ResourceDescriptor, error) {
	items, e := s.ResourceInventory(ctx, owner)
	if e != nil {
		return model.ResourceDescriptor{}, e
	}
	for _, v := range items {
		if v.Kind == kind && v.ID == id {
			return v, nil
		}
	}
	return model.ResourceDescriptor{}, fmt.Errorf("resource not found")
}

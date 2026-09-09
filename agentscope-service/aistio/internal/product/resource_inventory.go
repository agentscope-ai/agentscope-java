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
package product

import (
	"context"
	"encoding/json"
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
		if r.Kind == "channel" {
			cfg, e := s.loadChannelWorkSettings(ctx, r.ID)
			if e != nil {
				return nil, e
			}
			if cfg.Enabled {
				for _, t := range append([]ChannelTarget{cfg.DefaultTarget}, func() []ChannelTarget {
					v := []ChannelTarget{}
					for _, route := range cfg.Routes {
						v = append(v, route.ChannelTarget)
					}
					return v
				}()...) {
					if t.TargetRef != "" {
						r.Dependencies = append(r.Dependencies, t.TargetType+":"+t.TargetRef)
					}
				}
			}
		}
		if r.Kind == "workspace" {
			w, err := s.loadWorkspace(ctx, owner, r.ID)
			if err != nil {
				return nil, err
			}
			var raw any
			if json.Unmarshal([]byte(deref(w.McpServersJSON)), &raw) == nil {
				r.Dependencies = append(r.Dependencies, ResourceVaultRefs(raw)...)
			}
		}
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
				r.Dependencies = append(r.Dependencies, ResourceVaultRefs(raw)...)
			}
		}
	}
	return out, nil
}
func ResourceVaultRefs(v any) []string {
	refs := []string{}
	switch x := v.(type) {
	case map[string]any:
		for k, v := range x {
			if k == "vaultId" || k == "oauthVaultId" {
				if id, ok := v.(string); ok && id != "" {
					refs = append(refs, "vault:"+id)
				}
			}
			refs = append(refs, ResourceVaultRefs(v)...)
		}
	case []any:
		for _, v := range x {
			refs = append(refs, ResourceVaultRefs(v)...)
		}
	}
	return refs
}

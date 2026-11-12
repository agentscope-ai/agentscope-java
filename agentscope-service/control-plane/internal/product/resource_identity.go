// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package product

import (
	"context"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

// ResourceIdentity verifies one live dependency without listing the owner's resource inventory.
func (s *Server) ResourceIdentity(ctx context.Context, owner, kind, id string) (model.ResourceDescriptor, error) {
	queries := map[string]string{
		"managed-agent": `SELECT name FROM agents WHERE owner_id=$1 AND agent_id=$2 AND archived_at IS NULL`,
		"workspace":     `SELECT name FROM workspaces WHERE owner_id=$1 AND workspace_id=$2 AND archived_at IS NULL`,
		"environment":   `SELECT name FROM environments WHERE owner_id=$1 AND environment_id=$2 AND archived_at IS NULL`,
		"memory":        `SELECT name FROM memory_stores WHERE owner_id=$1 AND store_id=$2 AND archived_at IS NULL`,
		"vault":         `SELECT display_name FROM vaults WHERE owner_id=$1 AND vault_id=$2 AND archived_at IS NULL`,
	}
	query, ok := queries[kind]
	if !ok {
		return model.ResourceDescriptor{}, fmt.Errorf("unsupported Managed authorization dependency")
	}
	out := model.ResourceDescriptor{Kind: kind, ID: id, Dependencies: []string{}}
	if err := s.db.Pool.QueryRow(ctx, query, owner, id).Scan(&out.Name); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return out, store.ErrNotFound
		}
		return out, err
	}
	return out, nil
}

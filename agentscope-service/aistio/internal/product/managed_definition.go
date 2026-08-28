// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package product

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"github.com/jackc/pgx/v5"
)

// ErrManagedDefinitionNotFound is returned when a v5 Catalog Agent has no
// corresponding Managed definition in the cp store.
var ErrManagedDefinitionNotFound = errors.New("managed definition not found")

// ManagedDefinitionInput is the Managed-only configuration accepted by the
// v5 Agent API. Logical identity and lifecycle remain owned by the rt Agent
// Catalog; this structure is persisted only in cp.
type ManagedDefinitionInput struct {
	Name                  string   `json:"name"`
	Description           string   `json:"description,omitempty"`
	System                string   `json:"system,omitempty"`
	Model                 string   `json:"model,omitempty"`
	MaxIters              int      `json:"maxIters,omitempty"`
	Tools                 any      `json:"tools,omitempty"`
	MCPServers            any      `json:"mcpServers,omitempty"`
	Skills                any      `json:"skills,omitempty"`
	Multiagent            any      `json:"multiagent,omitempty"`
	WorkspacePath         string   `json:"workspacePath,omitempty"`
	WorkspaceID           string   `json:"workspaceId,omitempty"`
	DefaultEnvironmentID  string   `json:"defaultEnvironmentId,omitempty"`
	DefaultVaultIDs       []string `json:"defaultVaultIds,omitempty"`
	DefaultMemoryStoreIDs []string `json:"defaultMemoryStoreIds,omitempty"`
}

// EnsureManagedDefinition implements the idempotent cp step of the v5
// cross-store creation workflow. agentID is the rt Catalog UUID string, so cp
// never creates a second logical identity.
func (s *Server) EnsureManagedDefinition(ctx context.Context, ownerID, agentID string, in ManagedDefinitionInput) (map[string]any, error) {
	if s == nil || s.db == nil {
		return nil, fmt.Errorf("managed control plane is unavailable")
	}
	if ownerID == "" || agentID == "" || in.Name == "" {
		return nil, fmt.Errorf("ownerRef, agentId, and definition name are required")
	}
	if existing, err := s.loadAgent(ctx, ownerID, agentID); err == nil {
		return map[string]any(existing.toJSON()), nil
	} else if !errors.Is(err, pgx.ErrNoRows) {
		return nil, err
	}

	maxIters := in.MaxIters
	if maxIters <= 0 {
		maxIters = 20
	}
	workspacePath := in.WorkspacePath
	if workspacePath == "" {
		workspacePath = filepath.Join(s.cfg.WorkspaceRoot, ownerID, agentID)
	}
	if err := os.MkdirAll(workspacePath, 0o755); err != nil {
		return nil, fmt.Errorf("create managed workspace: %w", err)
	}
	now := nowMillis()
	tag, err := s.db.Pool.Exec(ctx, `INSERT INTO agents (owner_id,agent_id,workspace_path,workspace_id,name,
		description,sys_prompt,model,max_iters,tools_json,mcp_servers_json,skills_json,multiagent_json,
		default_environment_id,default_vault_ids_json,default_memory_store_ids_json,head_version,created_at,updated_at)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,1,$17,$17)
		ON CONFLICT(owner_id,agent_id) DO NOTHING`, ownerID, agentID, workspacePath, nullStr(in.WorkspaceID),
		in.Name, nullStr(in.Description), nullStr(in.System), nullStr(in.Model), maxIters, mustJSON(in.Tools),
		mustJSON(in.MCPServers), mustJSON(in.Skills), mustJSON(in.Multiagent), nullStr(in.DefaultEnvironmentID),
		mustJSON(in.DefaultVaultIDs), mustJSON(in.DefaultMemoryStoreIDs), now)
	if err != nil {
		return nil, err
	}
	if tag.RowsAffected() > 0 {
		snapshot := s.agentSnapshot(ownerID, agentID, in.Name, in.Description, in.System, in.Model, maxIters,
			in.Tools, in.MCPServers, in.Skills, in.Multiagent, workspacePath, in.WorkspaceID,
			in.DefaultEnvironmentID, in.DefaultVaultIDs, in.DefaultMemoryStoreIDs, 1, now, now)
		if _, err = s.db.Pool.Exec(ctx, `INSERT INTO agent_versions(owner_id,agent_id,version,snapshot_json,created_at)
			VALUES($1,$2,1,$3,$4) ON CONFLICT(owner_id,agent_id,version) DO NOTHING`, ownerID, agentID,
			mustJSON(snapshot), now); err != nil {
			return nil, err
		}
	}
	definition, err := s.loadAgent(ctx, ownerID, agentID)
	if err != nil {
		return nil, err
	}
	return map[string]any(definition.toJSON()), nil
}

// ManagedDefinition reads the cp definition associated with one Catalog Agent.
func (s *Server) ManagedDefinition(ctx context.Context, ownerID, agentID string) (map[string]any, error) {
	definition, err := s.loadAgent(ctx, ownerID, agentID)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrManagedDefinitionNotFound
	}
	if err != nil {
		return nil, err
	}
	return map[string]any(definition.toJSON()), nil
}

// ManagedDefinitionVersions returns immutable cp snapshots newest first.
func (s *Server) ManagedDefinitionVersions(ctx context.Context, ownerID, agentID string) ([]map[string]any, error) {
	rows, err := s.db.Pool.Query(ctx, `SELECT version,snapshot_json,created_at FROM agent_versions
		WHERE owner_id=$1 AND agent_id=$2 ORDER BY version DESC`, ownerID, agentID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]map[string]any, 0)
	for rows.Next() {
		var version int
		var raw string
		var createdAt int64
		if err = rows.Scan(&version, &raw, &createdAt); err != nil {
			return nil, err
		}
		var snapshot any
		if err = json.Unmarshal([]byte(raw), &snapshot); err != nil {
			return nil, err
		}
		out = append(out, map[string]any{"version": version, "snapshot": snapshot, "createdAt": createdAt})
	}
	return out, rows.Err()
}

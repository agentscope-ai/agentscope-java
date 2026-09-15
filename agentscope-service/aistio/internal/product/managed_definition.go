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
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/jackc/pgx/v5"
)

// ErrManagedDefinitionNotFound is returned when a v5 Catalog Agent has no
// corresponding Managed definition in the cp store.
var ErrManagedDefinitionNotFound = errors.New("managed definition not found")
var ErrManagedDefinitionConflict = errors.New("managed definition version conflict")

// ManagedDefinitionInput is the historical name of the portable Agent
// definition accepted by the v5 Agent API. Managed and Hosted runtimes share
// this definition; logical identity and lifecycle remain owned by the rt
// Agent Catalog.
type ManagedDefinitionInput struct {
	Name                  string            `json:"name"`
	Description           string            `json:"description,omitempty"`
	System                string            `json:"system,omitempty"`
	Model                 string            `json:"model,omitempty"`
	MaxIters              int               `json:"maxIters,omitempty"`
	Tools                 any               `json:"tools,omitempty"`
	MCPServers            any               `json:"mcpServers,omitempty"`
	Skills                any               `json:"skills,omitempty"`
	Multiagent            any               `json:"multiagent,omitempty"`
	WorkspacePath         string            `json:"workspacePath,omitempty"`
	WorkspaceID           string            `json:"workspaceId,omitempty"`
	WorkspaceBinding      *WorkspaceBinding `json:"workspaceBinding,omitempty"`
	DefaultEnvironmentID  string            `json:"defaultEnvironmentId,omitempty"`
	DefaultVaultIDs       []string          `json:"defaultVaultIds,omitempty"`
	DefaultMemoryStoreIDs []string          `json:"defaultMemoryStoreIds,omitempty"`
	// ProvisionDefaultEnvironment is set by the Managed binding workflow. A
	// Hosted Agent may share this portable definition but does not execute via
	// a Managed data-plane Environment.
	ProvisionDefaultEnvironment bool `json:"-"`
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
	defaultEnvironmentID := strings.TrimSpace(in.DefaultEnvironmentID)
	if in.ProvisionDefaultEnvironment {
		var err error
		defaultEnvironmentID, err = s.defaultEnvironmentForAgentCreate(ctx, ownerID, defaultEnvironmentID)
		if err != nil {
			return nil, err
		}
	} else if defaultEnvironmentID != "" {
		if _, err := s.validateEnvironmentBinding(ctx, ownerID, defaultEnvironmentID); err != nil {
			return nil, err
		}
	}

	maxIters := in.MaxIters
	if maxIters <= 0 {
		maxIters = 20
	}
	if _, err := s.resolveWorkspaceInput(ctx, ownerID, &in); err != nil {
		return nil, err
	}
	tools, mcpServers, skills, system := in.Tools, in.MCPServers, in.Skills, in.System
	workspacePath := in.WorkspacePath
	if workspacePath == "" {
		workspacePath = filepath.Join(s.cfg.WorkspaceRoot, ownerID, agentID)
	}

	if err := validateManagedTools(tools, mcpServers); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(workspacePath, 0o755); err != nil {
		return nil, fmt.Errorf("create managed workspace: %w", err)
	}
	tx, err := s.db.Pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	now := nowMillis()
	tag, err := tx.Exec(ctx, `INSERT INTO agents (owner_id,agent_id,workspace_path,workspace_id,name,
		description,sys_prompt,model,max_iters,tools_json,mcp_servers_json,skills_json,multiagent_json,
		default_environment_id,default_vault_ids_json,default_memory_store_ids_json,head_version,created_at,updated_at)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,1,$17,$17)
		ON CONFLICT(owner_id,agent_id) DO NOTHING`, ownerID, agentID, workspacePath, nullStr(in.WorkspaceID),
		in.Name, nullStr(in.Description), nullStr(system), nullStr(in.Model), maxIters, mustJSON(tools),
		mustJSON(mcpServers), mustJSON(skills), mustJSON(in.Multiagent), nullStr(defaultEnvironmentID),
		mustJSON(in.DefaultVaultIDs), mustJSON(in.DefaultMemoryStoreIDs), now)
	if err != nil {
		return nil, err
	}
	if tag.RowsAffected() > 0 {
		snapshot, err := s.agentSnapshot(ctx, ownerID, agentID, in.Name, in.Description, system, in.Model, maxIters,
			tools, mcpServers, skills, in.Multiagent, workspacePath, in.WorkspaceID,
			defaultEnvironmentID, in.DefaultVaultIDs, in.DefaultMemoryStoreIDs, 1, now, now, in.WorkspaceBinding)
		if err != nil {
			return nil, err
		}
		if _, err = tx.Exec(ctx, `INSERT INTO agent_versions(owner_id,agent_id,version,snapshot_json,created_at)
			VALUES($1,$2,1,$3,$4) ON CONFLICT(owner_id,agent_id,version) DO NOTHING`, ownerID, agentID,
			mustJSON(snapshot), now); err != nil {
			return nil, err
		}
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	definition, err := s.loadAgent(ctx, ownerID, agentID)
	if err != nil {
		return nil, err
	}
	out := map[string]any(definition.toJSON())
	if snapshot, err := s.definitionSnapshot(ctx, ownerID, agentID, definition.HeadVersion); err == nil {
		for _, key := range []string{"workspaceBinding", "workspaceVersion", "definitionDigest"} {
			if value, ok := snapshot[key]; ok {
				out[key] = value
			}
		}
	}
	return out, nil
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
	out := map[string]any(definition.toJSON())
	if snapshot, err := s.definitionSnapshot(ctx, ownerID, agentID, definition.HeadVersion); err == nil {
		for _, key := range []string{"workspaceBinding", "workspaceVersion", "definitionDigest"} {
			if value, ok := snapshot[key]; ok {
				out[key] = value
			}
		}
	}
	return out, nil
}

// RuntimeDefinition returns the portable definition plus its linked
// Workspace files. Runtime Hosts materialize the files inside the task
// workspace, so provider adapters do not need access to the product database
// or the control plane's local filesystem.
func (s *Server) RuntimeDefinition(ctx context.Context, ownerID, agentID string) (map[string]any, error) {
	agent, err := s.loadAgent(ctx, ownerID, agentID)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrManagedDefinitionNotFound
	}
	if err != nil {
		return nil, err
	}
	definition, err := s.definitionSnapshot(ctx, ownerID, agentID, agent.HeadVersion)
	if err != nil {
		return nil, err
	}
	definition["files"] = enabledDefinitionFiles(definition)
	delete(definition, "definitionFiles")
	return definition, nil
}

func (s *Server) definitionSnapshot(ctx context.Context, ownerID, agentID string, version int) (map[string]any, error) {
	var raw string
	err := s.db.Pool.QueryRow(ctx, `SELECT snapshot_json FROM agent_versions WHERE owner_id=$1 AND agent_id=$2 AND version=$3`, ownerID, agentID, version).Scan(&raw)
	if err != nil {
		return nil, err
	}
	var snapshot map[string]any
	err = json.Unmarshal([]byte(raw), &snapshot)
	return snapshot, err
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

func (s *Server) ManagedDefinitionVersion(ctx context.Context, ownerID, agentID string, version int) (map[string]any, error) {
	var raw string
	var createdAt int64
	err := s.db.Pool.QueryRow(ctx, `SELECT snapshot_json,created_at FROM agent_versions
		WHERE owner_id=$1 AND agent_id=$2 AND version=$3`, ownerID, agentID, version).Scan(&raw, &createdAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrManagedDefinitionNotFound
	}
	if err != nil {
		return nil, err
	}
	var snapshot any
	if err = json.Unmarshal([]byte(raw), &snapshot); err != nil {
		return nil, err
	}
	return map[string]any{"version": version, "snapshot": snapshot, "createdAt": createdAt}, nil
}

// UpdateManagedDefinition writes the next immutable cp definition version.
func (s *Server) UpdateManagedDefinition(ctx context.Context, ownerID, agentID string, in ManagedDefinitionInput, expectedVersion int) (map[string]any, error) {
	a, err := s.loadAgent(ctx, ownerID, agentID)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrManagedDefinitionNotFound
	}
	if err != nil {
		return nil, err
	}
	if expectedVersion <= 0 || a.HeadVersion != expectedVersion {
		return nil, ErrManagedDefinitionConflict
	}
	if in.Name == "" {
		return nil, fmt.Errorf("definition name is required")
	}
	if id := strings.TrimSpace(in.DefaultEnvironmentID); id != "" {
		if _, err = s.validateEnvironmentBinding(ctx, ownerID, id); err != nil {
			return nil, err
		}
		in.DefaultEnvironmentID = id
	}
	if in.WorkspaceID != "" && in.WorkspaceID == deref(a.WorkspaceID) && in.WorkspaceBinding == nil {
		snapshot, e := s.definitionSnapshot(ctx, ownerID, agentID, a.HeadVersion)
		if e != nil {
			return nil, e
		}
		if raw := snapshot["workspaceBinding"]; raw != nil {
			var binding WorkspaceBinding
			if e = json.Unmarshal([]byte(mustJSON(raw)), &binding); e != nil {
				return nil, e
			}
			if in.System != deref(a.SysPrompt) {
				binding.Instructions = in.System
			}
			in.WorkspaceBinding = &binding
		}
	}
	maxIters := in.MaxIters
	if maxIters <= 0 {
		maxIters = 20
	}
	workspacePath := in.WorkspacePath
	if workspacePath == "" {
		workspacePath = filepath.Join(s.cfg.WorkspaceRoot, ownerID, agentID)
	}
	if _, err := s.resolveWorkspaceInput(ctx, ownerID, &in); err != nil {
		return nil, err
	}
	tools, mcpServers, skills, system := in.Tools, in.MCPServers, in.Skills, in.System

	if err := validateManagedTools(tools, mcpServers); err != nil {
		return nil, err
	}
	nextVersion, now := a.HeadVersion+1, nowMillis()
	tx, err := s.db.Pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	tag, err := tx.Exec(ctx, `UPDATE agents SET name=$1,description=$2,sys_prompt=$3,model=$4,
		max_iters=$5,tools_json=$6,mcp_servers_json=$7,skills_json=$8,multiagent_json=$9,
		workspace_path=$10,workspace_id=$11,default_environment_id=$12,default_vault_ids_json=$13,
		default_memory_store_ids_json=$14,head_version=$15,updated_at=$16
		WHERE owner_id=$17 AND agent_id=$18 AND head_version=$19`, in.Name, nullStr(in.Description),
		nullStr(system), nullStr(in.Model), maxIters, mustJSON(tools), mustJSON(mcpServers), mustJSON(skills),
		mustJSON(in.Multiagent), nullStr(workspacePath), nullStr(in.WorkspaceID), nullStr(in.DefaultEnvironmentID),
		mustJSON(in.DefaultVaultIDs), mustJSON(in.DefaultMemoryStoreIDs), nextVersion, now, ownerID, agentID, expectedVersion)
	if err != nil {
		return nil, err
	}
	if tag.RowsAffected() == 0 {
		return nil, ErrManagedDefinitionConflict
	}
	snapshot, err := s.agentSnapshot(ctx, ownerID, agentID, in.Name, in.Description, system, in.Model, maxIters,
		tools, mcpServers, skills, in.Multiagent, workspacePath, in.WorkspaceID,
		in.DefaultEnvironmentID, in.DefaultVaultIDs, in.DefaultMemoryStoreIDs, nextVersion, a.CreatedAt, now, in.WorkspaceBinding)
	if err != nil {
		return nil, err
	}
	if _, err = tx.Exec(ctx, `INSERT INTO agent_versions(owner_id,agent_id,version,snapshot_json,created_at)
		VALUES($1,$2,$3,$4,$5)`, ownerID, agentID, nextVersion, mustJSON(snapshot), now); err != nil {
		return nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	return s.ManagedDefinition(ctx, ownerID, agentID)
}

// enabledDefinitionFiles prevents provider filesystem discovery from bypassing skill selection.
// Keep definitionFiles intact for revision inspection; only files is the runtime projection.
func enabledDefinitionFiles(definition map[string]any) map[string]any {
	enabled := map[string]bool{}
	if skills, ok := definition["skills"].([]any); ok {
		for _, value := range skills {
			if skill, ok := value.(map[string]any); ok {
				name, _ := skill["name"].(string)
				if name == "" {
					name, _ = skill["id"].(string)
				}
				enabled[name] = true
			}
		}
	}
	files := map[string]any{}
	if source, ok := definition["definitionFiles"].(map[string]any); ok {
		for path, content := range source {
			if !definitionFile(path) {
				continue
			}
			parts := strings.Split(strings.ReplaceAll(path, "\\", "/"), "/")
			if len(parts) > 1 && strings.EqualFold(parts[0], "skills") && !enabled[parts[1]] {
				continue
			}
			files[path] = content
		}
	}
	return files
}

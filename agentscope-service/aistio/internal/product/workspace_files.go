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

package product

import (
	"context"
	"os"
	"path/filepath"
	"strings"
)

const (
	scopeTypeAgent     = "agent"
	scopeTypeWorkspace = "workspace"
)

// putWorkspaceFile persists text content to the shared workspace_files table and
// mirrors it onto the local workspace disk when diskRoot is non-empty.
func (s *Server) putWorkspaceFile(ctx context.Context, owner, scopeType, scopeID, relPath, content, diskRoot string) error {
	rel, err := cleanRelPath(relPath)
	if err != nil || rel == "" {
		return errPathTraversal
	}
	now := nowMillis()
	_, err = s.db.Pool.Exec(ctx,
		`INSERT INTO workspace_files (owner_id, scope_type, scope_id, path, content, updated_at)
		 VALUES ($1,$2,$3,$4,$5,$6)
		 ON CONFLICT (owner_id, scope_type, scope_id, path)
		 DO UPDATE SET content=EXCLUDED.content, updated_at=EXCLUDED.updated_at`,
		owner, scopeType, scopeID, rel, content, now)
	if err != nil {
		return err
	}
	if diskRoot != "" {
		full, jerr := joinWorkspace(diskRoot, rel)
		if jerr != nil {
			return jerr
		}
		if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
			return err
		}
		if err := os.WriteFile(full, []byte(content), 0o644); err != nil {
			return err
		}
	}
	return nil
}

func (s *Server) getWorkspaceFile(ctx context.Context, owner, scopeType, scopeID, relPath string) (string, bool, error) {
	rel, err := cleanRelPath(relPath)
	if err != nil || rel == "" {
		return "", false, errPathTraversal
	}
	var content string
	err = s.db.Pool.QueryRow(ctx,
		`SELECT content FROM workspace_files
		 WHERE owner_id=$1 AND scope_type=$2 AND scope_id=$3 AND path=$4`,
		owner, scopeType, scopeID, rel).Scan(&content)
	if err != nil {
		return "", false, nil
	}
	return content, true, nil
}

func (s *Server) deleteWorkspaceFile(ctx context.Context, owner, scopeType, scopeID, relPath, diskRoot string) error {
	rel, err := cleanRelPath(relPath)
	if err != nil || rel == "" {
		return errPathTraversal
	}
	_, err = s.db.Pool.Exec(ctx,
		`DELETE FROM workspace_files
		 WHERE owner_id=$1 AND scope_type=$2 AND scope_id=$3 AND path=$4`,
		owner, scopeType, scopeID, rel)
	if err != nil {
		return err
	}
	if diskRoot != "" {
		full, jerr := joinWorkspace(diskRoot, rel)
		if jerr == nil {
			_ = os.Remove(full)
		}
	}
	return nil
}

func (s *Server) deleteWorkspaceFilePrefix(ctx context.Context, owner, scopeType, scopeID, prefix, diskRoot string) error {
	prefix = strings.Trim(strings.TrimSpace(prefix), "/")
	_, err := s.db.Pool.Exec(ctx,
		`DELETE FROM workspace_files
		 WHERE owner_id=$1 AND scope_type=$2 AND scope_id=$3
		   AND (path=$4 OR path LIKE $5)`,
		owner, scopeType, scopeID, prefix, prefix+"/%")
	if err != nil {
		return err
	}
	if diskRoot != "" && prefix != "" {
		full := filepath.Join(diskRoot, filepath.FromSlash(prefix))
		_ = os.RemoveAll(full)
	}
	return nil
}

func (s *Server) listWorkspaceFilePaths(ctx context.Context, owner, scopeType, scopeID, prefix string) ([]string, error) {
	prefix = strings.Trim(strings.TrimSpace(prefix), "/")
	rows, err := s.db.Pool.Query(ctx,
		`SELECT path FROM workspace_files
		 WHERE owner_id=$1 AND scope_type=$2 AND scope_id=$3
		   AND ($4='' OR path=$4 OR path LIKE $5)
		 ORDER BY path`,
		owner, scopeType, scopeID, prefix, prefix+"/%")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var p string
		if err := rows.Scan(&p); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

func (s *Server) listWorkspaceFileContents(ctx context.Context, owner, scopeType, scopeID, prefix string) (map[string]string, error) {
	prefix = strings.Trim(strings.TrimSpace(prefix), "/")
	rows, err := s.db.Pool.Query(ctx,
		`SELECT path, content FROM workspace_files
		 WHERE owner_id=$1 AND scope_type=$2 AND scope_id=$3
		   AND ($4='' OR path=$4 OR path LIKE $5)
		 ORDER BY path`,
		owner, scopeType, scopeID, prefix, prefix+"/%")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]string{}
	for rows.Next() {
		var p, c string
		if err := rows.Scan(&p, &c); err != nil {
			return nil, err
		}
		out[p] = c
	}
	return out, rows.Err()
}

// resolveDefinitionScope returns the scope used for shared definition files for an agent.
// When the agent is linked to a first-class Workspace, that workspace is the truth source.
func (a agentRow) resolveDefinitionScope() (scopeType, scopeID string) {
	if a.WorkspaceID != nil && strings.TrimSpace(*a.WorkspaceID) != "" {
		return scopeTypeWorkspace, strings.TrimSpace(*a.WorkspaceID)
	}
	return scopeTypeAgent, a.AgentID
}

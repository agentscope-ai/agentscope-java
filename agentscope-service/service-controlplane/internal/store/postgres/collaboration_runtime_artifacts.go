// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
package postgres

import (
	"context"
	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
)

func (r *collaborationRepo) ListRuntimeArtifacts(ctx context.Context, f store.RuntimeArtifactFilter) ([]*model.Artifact, error) {
	if !store.ValidRuntimeArtifactFilter(f) {
		return nil, store.ErrConflict
	}
	rows, err := r.pool.Query(ctx, `SELECT `+artifactColumns+` FROM artifacts
 WHERE tenant=$1 AND namespace=$2 AND portal_command_id=$3 AND portal_command_id<>''
 AND ($4::timestamptz IS NULL OR (created_at,id)>($4,$5::uuid))
 ORDER BY created_at,id LIMIT $6`, f.Tenant, f.Namespace, f.PortalCommandID, f.AfterCreatedAt, f.AfterID, f.Limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*model.Artifact, 0, f.Limit)
	for rows.Next() {
		item, err := scanArtifact(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, item)
	}
	return out, rows.Err()
}

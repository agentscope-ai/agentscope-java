// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
package memory

import (
	"context"
	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"sort"
)

func (r *collaborationRepo) ListRuntimeArtifacts(_ context.Context, f store.RuntimeArtifactFilter) ([]*model.Artifact, error) {
	if !store.ValidRuntimeArtifactFilter(f) {
		return nil, store.ErrConflict
	}
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := make([]*model.Artifact, 0)
	for _, item := range r.s.artifacts {
		if item.Tenant != f.Tenant || item.Namespace != f.Namespace || item.PortalCommandID != f.PortalCommandID {
			continue
		}
		if f.AfterCreatedAt != nil && (item.CreatedAt.Before(*f.AfterCreatedAt) || item.CreatedAt.Equal(*f.AfterCreatedAt) && item.ID.String() <= f.AfterID.String()) {
			continue
		}
		out = append(out, cloneArtifact(item))
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].CreatedAt.Equal(out[j].CreatedAt) {
			return out[i].ID.String() < out[j].ID.String()
		}
		return out[i].CreatedAt.Before(out[j].CreatedAt)
	})
	if len(out) > f.Limit {
		out = out[:f.Limit]
	}
	return out, nil
}

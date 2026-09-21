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
package memory

import (
	"context"
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"time"
)

type teamProposalRepo struct{ s *Store }

func cloneProposal(v *controlmodel.TeamProposal) *controlmodel.TeamProposal {
	c := *v
	c.Requirements = cloneJSON(v.Requirements)
	c.Members = append([]controlmodel.TeamProposalMember(nil), v.Members...)
	for i := range c.Members {
		c.Members[i].Reasons = append([]string(nil), v.Members[i].Reasons...)
	}
	return &c
}
func (r *teamProposalRepo) Create(_ context.Context, in *controlmodel.TeamProposal) (*controlmodel.TeamProposal, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	c := cloneProposal(in)
	if c.ID == uuid.Nil {
		c.ID = uuid.New()
	}
	now := time.Now().UTC()
	c.Status = controlmodel.TeamProposalProposed
	c.Version = 1
	c.CreatedAt = now
	c.UpdatedAt = now
	r.s.teamProposals[c.ID] = c
	return cloneProposal(c), nil
}
func (r *teamProposalRepo) Get(_ context.Context, id uuid.UUID) (*controlmodel.TeamProposal, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	v := r.s.teamProposals[id]
	if v == nil {
		return nil, store.ErrNotFound
	}
	return cloneProposal(v), nil
}
func (r *teamProposalRepo) Confirm(_ context.Context, id uuid.UUID, expected int64, runID uuid.UUID) (*controlmodel.TeamProposal, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	v := r.s.teamProposals[id]
	if v == nil {
		return nil, store.ErrNotFound
	}
	if v.Version != expected || v.Status != controlmodel.TeamProposalProposed {
		return nil, store.ErrConflict
	}
	v.Status = controlmodel.TeamProposalConfirmed
	v.RunID = &runID
	v.Version++
	v.UpdatedAt = time.Now().UTC()
	return cloneProposal(v), nil
}

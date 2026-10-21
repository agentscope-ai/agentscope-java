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

package invocation

import (
	"context"
	"errors"
	"strings"
	"testing"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

type ancestryStore struct {
	store.Store
	repository *ancestryRepository
}

func (s ancestryStore) Collaboration() store.CollaborationRepository { return s.repository }

type ancestryRepository struct {
	store.CollaborationRepository
	reads int
	cycle bool
	depth int
}

func (r *ancestryRepository) GetIssue(_ context.Context, id uuid.UUID) (*model.Issue, error) {
	r.reads++
	issue := &model.Issue{ID: id}
	if r.depth > 0 && r.reads == r.depth {
		return issue, nil
	}
	next := uuid.New()
	if r.cycle {
		next = id
	}
	issue.ParentIssueID = &next
	return issue, nil
}

func TestExecutionAncestryBoundsQueriesAndRejectsCycles(t *testing.T) {
	for _, scenario := range []struct {
		name, errorText string
		cycle           bool
		depth, reads    int
	}{
		{"normal", "", false, 3, 3},
		{"cycle", "cycle", true, 0, 1},
		{"oversized", "exceeds", false, 0, 256},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			repo := &ancestryRepository{cycle: scenario.cycle, depth: scenario.depth}
			contract, err := ContractForIssue(t.Context(), ancestryStore{repository: repo}, uuid.New())
			if contract != nil || repo.reads != scenario.reads {
				t.Fatalf("unexpected ancestry lookup: contract=%+v reads=%d", contract, repo.reads)
			}
			if scenario.errorText == "" {
				if err != nil {
					t.Fatal(err)
				}
			} else if !errors.Is(err, store.ErrForbidden) || !strings.Contains(err.Error(), scenario.errorText) {
				t.Fatalf("unsafe ancestry accepted: %v", err)
			}
		})
	}
}

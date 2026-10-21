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

package orchestration

import (
	"context"
	"sort"
	"testing"

	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

type recordingRunRepository struct {
	store.OrchestrationRepository
	reads [][]*controlmodel.OrchestrationRun
}

func (r *recordingRunRepository) ListRuns(ctx context.Context, filter store.OrchestrationRunFilter) ([]*controlmodel.OrchestrationRun, error) {
	runs, err := r.OrchestrationRepository.ListRuns(ctx, filter)
	r.reads = append(r.reads, runs)
	return runs, err
}

type recordingRunStore struct {
	store.Store
	repository *recordingRunRepository
}

func (s *recordingRunStore) Orchestration() store.OrchestrationRepository { return s.repository }

func TestRuntimeWorkerBoundsEachPassAndDoesNotSkipRunsAfterEarlierRowsClose(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.DefaultConfig())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	var runs []*controlmodel.OrchestrationRun
	for i := 0; i < 5; i++ {
		run, err := st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{
			Tenant: "tenant", Namespace: "space", RootIssueID: uuid.New(), State: controlmodel.RunPlanned,
		})
		if err != nil {
			t.Fatal(err)
		}
		runs = append(runs, run)
	}
	sort.Slice(runs, func(i, j int) bool {
		if runs[i].CreatedAt.Equal(runs[j].CreatedAt) {
			return runs[i].ID.String() < runs[j].ID.String()
		}
		return runs[i].CreatedAt.Before(runs[j].CreatedAt)
	})
	repo := &recordingRunRepository{OrchestrationRepository: st.Orchestration()}
	worker := &Worker{Store: &recordingRunStore{Store: st, repository: repo}, Batch: 2}
	worker.ReconcileOnce(ctx)
	if len(repo.reads) != 1 || len(repo.reads[0]) != 2 {
		t.Fatalf("one pass traversed more than its budget: %+v", repo.reads)
	}
	for _, run := range runs[:2] {
		if _, err := st.Orchestration().TransitionRun(ctx, run.ID, run.Version, controlmodel.RunCancelled, nil, "", ""); err != nil {
			t.Fatal(err)
		}
	}
	worker.ReconcileOnce(ctx)
	worker.ReconcileOnce(ctx)
	if len(repo.reads) != 3 || len(repo.reads[1]) != 2 || len(repo.reads[2]) != 1 {
		t.Fatalf("closed rows made later runs disappear: %+v", repo.reads)
	}
	for i, run := range repo.reads[1] {
		if run.ID != runs[i+2].ID {
			t.Fatalf("skipped run: got=%s expected=%s", run.ID, runs[i+2].ID)
		}
	}
	if repo.reads[2][0].ID != runs[4].ID {
		t.Fatal("last run was skipped")
	}
	worker.ReconcileOnce(ctx)
	if len(repo.reads[3]) != 2 || repo.reads[3][0].ID != runs[2].ID {
		t.Fatal("worker did not revisit unfinished work after wrapping")
	}
}

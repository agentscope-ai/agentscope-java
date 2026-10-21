// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package memory

import (
	"errors"
	"strings"
	"testing"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/taskplane"
	"github.com/google/uuid"
)

func TestTaskInputCapacityPreservesCommentAndCancellation(t *testing.T) {
	facade, err := Open(t.Context(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	st := facade.(*Store)
	defer st.Close()
	issue, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "capacity", Creator: model.Actor{Type: model.ActorHuman, Ref: "owner"}, AssigneeType: model.AssigneeAgent, AssigneeRef: "worker"})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(t.Context(), store.AgentTaskFilter{IssueID: issue.ID, Limit: 1})
	if err != nil || len(tasks) != 1 {
		t.Fatal(tasks, err)
	}
	task := tasks[0]
	st.mu.Lock()
	for i := 0; i < store.MaxTaskInputs; i++ {
		st.taskInputs[task.ID] = append(st.taskInputs[task.ID], model.AgentTaskInput{ID: uuid.New(), TaskID: task.ID, CommentID: uuid.New(), CommentVersion: 1, Sequence: int64(i + 1), State: model.TaskInputPlanned, CreatedAt: time.Now().UTC()})
	}
	st.mu.Unlock()
	routed, err := st.Collaboration().CreateComment(t.Context(), store.CreateCommentRequest{Comment: &model.Comment{IssueID: issue.ID, Author: issue.Creator, Content: "overflow"}, Targets: []store.CommentTarget{{TargetType: model.AssigneeAgent, TargetRef: "worker", AgentRef: "worker"}}})
	if err != nil || len(routed.Routes) != 1 || routed.Routes[0].Outcome != model.RouteBlocked || routed.Routes[0].ReasonCode != "task_input_capacity" || routed.Routes[0].TaskID != nil {
		t.Fatal("overflow route", routed, err)
	}
	if _, err := st.Collaboration().GetComment(t.Context(), routed.Comment.ID); err != nil {
		t.Fatal("blocked comment lost", err)
	}
	current, err := st.Collaboration().GetAgentTask(t.Context(), task.ID)
	if err != nil || len(current.Inputs) != store.MaxTaskInputs || current.Version != task.Version {
		t.Fatal("overflow mutated Task", current, err)
	}
	st.mu.Lock()
	old := st.taskInputs[task.ID][0]
	repo := st.Collaboration().(*collaborationRepo)
	idempotent, _, err := repo.routeTaskLocked(issue, &model.Comment{ID: old.CommentID, Version: old.CommentVersion}, store.CommentTarget{AgentRef: "worker"})
	st.mu.Unlock()
	if err != nil || idempotent.ID != task.ID {
		t.Fatal("idempotent delivery blocked at capacity", err)
	}
	st.mu.Lock()
	st.taskInputs[task.ID] = st.taskInputs[task.ID][:1]
	st.taskInputs[task.ID][0].LastError = strings.Repeat("x", store.MaxRunGraphBytes)
	st.mu.Unlock()
	if _, err = st.Collaboration().GetAgentTask(t.Context(), task.ID); !errors.Is(err, store.ErrTaskInputCapacity) {
		t.Fatal("oversized read accepted", err)
	}
	if _, _, err = st.Collaboration().ClaimAgentTaskWithAttempt(t.Context(), store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version, RuntimeBinding: []byte(`{}`)}, &model.ExecutionAttempt{BackendKind: model.DataPlaneHostedRuntime}); !errors.Is(err, store.ErrTaskInputCapacity) {
		t.Fatal("oversized history claimed", err)
	}
	cancelled, err := (&taskplane.Service{Store: st}).CancelTask(t.Context(), task.ID, task.Version)
	if err != nil || cancelled.Status != model.AgentTaskCancelled || cancelled.CurrentAttemptID != nil {
		t.Fatal("history blocked cancellation or created orphan attempt", cancelled, err)
	}
}

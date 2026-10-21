// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package postgres

import (
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/taskplane"
	"github.com/google/uuid"
)

func taskInputFixture(t *testing.T, st store.Store) (*model.Issue, *model.AgentTask) {
	t.Helper()
	issue, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "inputs", Creator: model.Actor{Type: model.ActorHuman, Ref: "owner"}, AssigneeType: model.AssigneeAgent, AssigneeRef: "worker"})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(t.Context(), store.AgentTaskFilter{IssueID: issue.ID, Limit: 1})
	if err != nil || len(tasks) != 1 {
		t.Fatal(tasks, err)
	}
	return issue, tasks[0]
}

func seedTaskInputs(t *testing.T, st *Store, task *model.AgentTask, count int) {
	t.Helper()
	_, err := st.pool.Exec(t.Context(), `INSERT INTO agent_task_inputs(id,tenant,namespace,task_id,comment_id,comment_version,sequence,state)
 SELECT md5($1::text||':input:'||g::text)::uuid,'t','n',$1::uuid,md5($1::text||':comment:'||g::text)::uuid,1,g,'planned'
 FROM generate_series(1,$2) g`, task.ID, count)
	if err != nil {
		t.Fatal(err)
	}
}

func TestTaskInputCapacityIsSharedAndKeepsCommentAndDeliveryIdentity(t *testing.T) {
	cfg := serviceMigrationConfig(t)
	first, err := store.Open(t.Context(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	second, err := store.Open(t.Context(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	issue, task := taskInputFixture(t, first)
	seedTaskInputs(t, first.(*Store), task, store.MaxTaskInputs-1)
	start := make(chan struct{})
	results := make(chan *store.CreateCommentResult, 8)
	errorsOut := make(chan error, 8)
	var group sync.WaitGroup
	for i := 0; i < 8; i++ {
		group.Add(1)
		go func(i int) {
			defer group.Done()
			<-start
			st := first
			if i%2 == 1 {
				st = second
			}
			result, err := st.Collaboration().CreateComment(t.Context(), store.CreateCommentRequest{Comment: &model.Comment{IssueID: issue.ID, Author: issue.Creator, Content: fmt.Sprintf("input-%d", i)}, Targets: []store.CommentTarget{{TargetType: model.AssigneeAgent, TargetRef: task.AgentRef, AgentRef: task.AgentRef}}})
			results <- result
			errorsOut <- err
		}(i)
	}
	close(start)
	group.Wait()
	close(results)
	close(errorsOut)
	for err := range errorsOut {
		if err != nil {
			t.Fatal(err)
		}
	}
	accepted, blocked := 0, 0
	for result := range results {
		if result == nil || len(result.Routes) != 1 {
			t.Fatal(result)
		}
		route := result.Routes[0]
		switch route.Outcome {
		case model.RouteCoalesced:
			accepted++
		case model.RouteBlocked:
			if route.ReasonCode != "task_input_capacity" || route.TaskID != nil || len(result.Tasks) != 0 {
				t.Fatal("capacity result omitted or orphaned work", result)
			}
			blocked++
		default:
			t.Fatal("unexpected route", route)
		}
		if _, err := first.Collaboration().GetComment(t.Context(), result.Comment.ID); err != nil {
			t.Fatal("blocked comment lost", err)
		}
	}
	current, err := first.Collaboration().GetAgentTask(t.Context(), task.ID)
	if err != nil || len(current.Inputs) != store.MaxTaskInputs || current.Version != task.Version+1 || accepted != 1 || blocked != 7 {
		t.Fatal("replica oversubscription", accepted, blocked, err)
	}
	// Re-delivery of an existing identity is safe after all slots are occupied.
	tx, err := first.(*Store).pool.Begin(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(t.Context())
	old := current.Inputs[0]
	routed, input, coalesced, err := routeAgentTaskInputTx(t.Context(), tx, issue, &model.Comment{ID: old.CommentID, Version: old.CommentVersion}, store.CommentTarget{AgentRef: task.AgentRef})
	if err != nil || !coalesced || routed.ID != task.ID || input.ID != old.ID || routed.Version != current.Version {
		t.Fatal("capacity blocked idempotent delivery", routed, input, err)
	}
}

func TestOversizedTaskInputHistoryRejectsReadsAndClaimsButAllowsCancellation(t *testing.T) {
	cfg := serviceMigrationConfig(t)
	cfg.MaxOpenConns = 1
	st, err := store.Open(t.Context(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	_, task := taskInputFixture(t, st)
	seedTaskInputs(t, st.(*Store), task, store.MaxTaskInputs+1)
	if _, err := st.Collaboration().GetAgentTask(t.Context(), task.ID); !errors.Is(err, store.ErrTaskInputCapacity) {
		t.Fatal("count overflow read was partial", err)
	}
	if _, _, err := st.Collaboration().ClaimAgentTaskWithAttempt(t.Context(), store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version, RuntimeBinding: []byte(`{}`)}, &model.ExecutionAttempt{BackendKind: model.DataPlaneHostedRuntime}); !errors.Is(err, store.ErrTaskInputCapacity) {
		t.Fatal("oversized history claimed physical work", err)
	}
	current, err := st.Collaboration().GetAgentTaskWithoutInputs(t.Context(), task.ID)
	if err != nil || current.Status != model.AgentTaskQueued || current.Version != task.Version || current.CurrentAttemptID != nil {
		t.Fatal("rejected claim changed task", current, err)
	}
	if _, err := st.(*Store).pool.Exec(t.Context(), `DELETE FROM agent_task_inputs WHERE task_id=$1 AND sequence>1`, task.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := st.(*Store).pool.Exec(t.Context(), `UPDATE agent_task_inputs SET last_error=$2 WHERE task_id=$1`, task.ID, strings.Repeat("x", store.MaxRunGraphBytes)); err != nil {
		t.Fatal(err)
	}
	if _, err := st.Collaboration().GetAgentTask(t.Context(), task.ID); !errors.Is(err, store.ErrTaskInputCapacity) {
		t.Fatal("byte overflow read accepted", err)
	}
	cancelled, err := (&taskplane.Service{Store: st}).CancelTask(t.Context(), task.ID, task.Version)
	if err != nil || cancelled.Status != model.AgentTaskCancelled {
		t.Fatal("unreadable history blocked cancellation", cancelled, err)
	}
	var attempts int
	if err := st.(*Store).pool.QueryRow(t.Context(), `SELECT count(*) FROM execution_attempts WHERE agent_task_id=$1`, task.ID).Scan(&attempts); err != nil || attempts != 0 {
		t.Fatal("rejected claim left an attempt", attempts, err)
	}
	if _, err := st.Collaboration().AcknowledgeTaskInputs(t.Context(), uuid.New(), nil); !errors.Is(err, store.ErrNotFound) {
		t.Fatal("missing Task acknowledged", err)
	}
}

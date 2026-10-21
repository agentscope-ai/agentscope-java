// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package orchestration

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

func TestDeclaredCoordinatorKeepsRetriedWorkerInItsCompletionScope(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			st := cancellationStore(t, driver)
			root, _, leader := coordinatorIntentFixture(t, st, model.RunModeDeclared)
			actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
			routed, err := st.Collaboration().CreateComment(t.Context(), store.CreateCommentRequest{
				Comment: &model.Comment{IssueID: root.ID, Author: actor, Content: "delegate"},
				Targets: []store.CommentTarget{{TargetType: model.AssigneeAgent, TargetRef: "worker", AgentRef: "worker", ParentTaskID: &leader.ID}},
			})
			if err != nil || len(routed.Tasks) != 1 {
				t.Fatal(routed, err)
			}
			worker := routed.Tasks[0]
			failed, err := st.Collaboration().FailAgentTask(t.Context(), worker.ID, worker.Version, "temporary", "retry worker")
			if err != nil {
				t.Fatal(err)
			}
			retry, err := st.Collaboration().RetryAgentTask(t.Context(), failed.ID, actor)
			if err != nil {
				t.Fatal(err)
			}
			if retry.OrchestrationRunID != leader.OrchestrationRunID || retry.ParentTaskID == nil || *retry.ParentTaskID != failed.ID || retry.DelegatedFromTaskID == nil || *retry.DelegatedFromTaskID != failed.ID {
				t.Fatal("retry escaped the original Team", retry)
			}
			service := &Service{Store: st}
			if _, _, err = service.ValidateCoordinatorNodeCompletion(t.Context(), leader.ID); !errors.Is(err, store.ErrConflict) {
				t.Fatal("active retry omitted from Team completion", err)
			}
			claimed, err := st.Collaboration().ClaimAgentTask(t.Context(), store.TaskClaim{TaskID: retry.ID, ExpectedVersion: retry.Version})
			if err != nil || len(claimed.Inputs) != 1 {
				t.Fatal(claimed, err)
			}
			inputID := claimed.Inputs[0].ID
			claimed, err = st.Collaboration().StartAgentTask(t.Context(), claimed.ID, claimed.Version)
			if err != nil {
				t.Fatal(err)
			}
			if _, err = st.Collaboration().CompleteAgentTask(t.Context(), retry.ID, store.TaskCompletion{ExpectedVersion: claimed.Version, ProcessedInputIDs: []uuid.UUID{inputID}, Result: json.RawMessage(`{"ok":true}`)}); err != nil {
				t.Fatal(err)
			}
			if _, _, err = service.ValidateCoordinatorNodeCompletion(t.Context(), leader.ID); err != nil {
				t.Fatal("successful retry still blocks completion", err)
			}
			stored, err := st.Collaboration().GetAgentTask(t.Context(), retry.ID)
			if err != nil || len(stored.Inputs) != 1 || stored.Inputs[0].Sequence != 1 || stored.Inputs[0].Attempts != 0 || stored.Inputs[0].LastError != "" {
				t.Fatal("retry lost its obligation or retained delivery failures", stored, err)
			}
		})
	}
}

func TestCommentAdmissionRejectsExcessiveFanoutAndEscapedPayloadBeforeWriting(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			st := cancellationStore(t, driver)
			issue, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "comment admission", Creator: model.Actor{Type: model.ActorHuman, Ref: "owner"}})
			if err != nil {
				t.Fatal(err)
			}
			for _, req := range []store.CreateCommentRequest{
				{Comment: &model.Comment{ID: uuid.New(), IssueID: issue.ID, Author: issue.Creator, Content: "too many targets"}, Mentions: make([]model.Mention, store.MaxCommentTargets+1)},
				{Comment: &model.Comment{ID: uuid.New(), IssueID: issue.ID, Author: issue.Creator, Content: strings.Repeat("<", 3<<20)}},
			} {
				if _, err := st.Collaboration().CreateComment(t.Context(), req); !errors.Is(err, store.ErrCommentCapacity) {
					t.Fatal("unbounded comment accepted", err)
				}
				if _, err := st.Collaboration().GetComment(t.Context(), req.Comment.ID); !errors.Is(err, store.ErrNotFound) {
					t.Fatal("rejected comment persisted", err)
				}
			}
		})
	}
}

func TestInputDiagnosticPreviewStaysReadableAndReplayReordersObligations(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			st := cancellationStore(t, driver)
			issue, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "delivery", Creator: model.Actor{Type: model.ActorHuman, Ref: "owner"}, AssigneeType: model.AssigneeAgent, AssigneeRef: "worker"})
			if err != nil {
				t.Fatal(err)
			}
			var task model.AgentTask
			for i := 0; i < 3; i++ {
				routed, err := st.Collaboration().CreateComment(t.Context(), store.CreateCommentRequest{Comment: &model.Comment{IssueID: issue.ID, Author: issue.Creator, Content: "delivery"}, Targets: []store.CommentTarget{{TargetType: model.AssigneeAgent, TargetRef: "worker", AgentRef: "worker"}}})
				if err != nil || len(routed.Tasks) != 1 {
					t.Fatal(routed, err)
				}
				task = routed.Tasks[0]
			}
			claimed, err := st.Collaboration().ClaimAgentTask(t.Context(), store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version})
			if err != nil || len(claimed.Inputs) != 3 {
				t.Fatal(claimed, err)
			}
			ids := []uuid.UUID{claimed.Inputs[0].ID, claimed.Inputs[2].ID}
			inputs, err := st.Collaboration().FailTaskInputDelivery(t.Context(), task.ID, ids, strings.Repeat("雪<\n\"", 2000), 1)
			if err != nil || len(inputs) != 3 {
				t.Fatal(inputs, err)
			}
			for _, i := range []int{0, 2} {
				raw, _ := json.Marshal(inputs[i].LastError)
				if inputs[i].State != model.TaskInputDeadLetter || !strings.Contains(inputs[i].LastError, "diagnostic preview") || !strings.Contains(inputs[i].LastError, "sha256=") || len(raw) > 1026 {
					t.Fatal("diagnostic overflow or unmarked shortening", inputs[i])
				}
			}
			if inputs[1].State != model.TaskInputDelivered || inputs[1].LastError != "" {
				t.Fatal("unselected input changed", inputs[1])
			}
			replay, err := st.Collaboration().ReplayDeadLetterInputs(t.Context(), task.ID, ids, issue.Creator)
			if err != nil {
				t.Fatal(err)
			}
			replay, err = st.Collaboration().GetAgentTask(t.Context(), replay.ID)
			if err != nil || len(replay.Inputs) != 2 || replay.ParentTaskID == nil || *replay.ParentTaskID != task.ID {
				t.Fatal("replay lineage or obligations lost", replay, err)
			}
			for i, input := range replay.Inputs {
				if input.Sequence != int64(i+1) || input.State != model.TaskInputPlanned || input.LastError != "" || input.Attempts != 0 || input.DeliveredAt != nil {
					t.Fatal("replay retained old delivery state", input)
				}
			}
		})
	}
}

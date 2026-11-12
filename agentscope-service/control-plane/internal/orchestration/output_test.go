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
	"bytes"
	"encoding/json"
	"github.com/google/uuid"
	controlmodel "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
	"testing"
)

func TestCompletedRunOutputKeepsCoordinatorDelivery(t *testing.T) {
	root, child := uuid.New(), uuid.New()
	run := &controlmodel.OrchestrationRun{RootIssueID: root}
	nodes := []*controlmodel.RunNode{
		{NodeKey: "worker", Type: controlmodel.RunNodeAgent, IssueID: &child, State: controlmodel.RunNodeSucceeded, Output: json.RawMessage(`"worker answer"`)},
		{NodeKey: "leader", Type: controlmodel.RunNodeTeam, IssueID: &root, State: controlmodel.RunNodeSucceeded, Output: json.RawMessage(`{"answer":"combined"}`)},
	}
	if got := string(CompletedRunOutput(run, nodes)); got != `{"answer":"combined"}` {
		t.Fatal(got)
	}
	run.Output = json.RawMessage(`{"explicit":"output"}`)
	if got := string(CompletedRunOutput(run, nodes)); got != string(run.Output) {
		t.Fatal(got)
	}
}

func TestLastWorkflowAgentCompletionKeepsOtherNodeOutputs(t *testing.T) {
	st, err := store.Open(t.Context(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	issue, err := st.Collaboration().CreateIssue(t.Context(), &controlmodel.Issue{Tenant: "t", Namespace: "n", Title: "workflow", Creator: actor})
	if err != nil {
		t.Fatal(err)
	}
	run, err := st.Orchestration().CreateRun(t.Context(), &controlmodel.OrchestrationRun{Tenant: "t", Namespace: "n", RootIssueID: issue.ID,
		Mode: controlmodel.RunModeDeclared, State: controlmodel.RunRunning, CreatedBy: actor})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.Orchestration().CreateNode(t.Context(), &controlmodel.RunNode{Tenant: "t", Namespace: "n", RunID: run.ID,
		NodeKey: "after", Type: controlmodel.RunNodeCondition, State: controlmodel.RunNodeSucceeded, Output: json.RawMessage(`{"answer":9007199254740993}`)}); err != nil {
		t.Fatal(err)
	}
	node, err := st.Orchestration().CreateNode(t.Context(), &controlmodel.RunNode{Tenant: "t", Namespace: "n", RunID: run.ID,
		NodeKey: "sender", Type: controlmodel.RunNodeAgent, IssueID: &issue.ID, State: controlmodel.RunNodeWaiting})
	if err != nil {
		t.Fatal(err)
	}
	task, err := st.Collaboration().CreateRunAgentTask(t.Context(), store.RunTaskRequest{RunID: run.ID, NodeID: node.ID, IssueID: issue.ID, AgentRef: "sender", Originator: actor})
	if err != nil {
		t.Fatal(err)
	}
	task, err = st.Collaboration().ClaimAgentTask(t.Context(), store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version})
	if err == nil {
		task, err = st.Collaboration().StartAgentTask(t.Context(), task.ID, task.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.Collaboration().CompleteAgentTask(t.Context(), task.ID, store.TaskCompletion{ExpectedVersion: task.Version, Summary: "sent", Result: json.RawMessage(`{"output":"sent"}`)}); err != nil {
		t.Fatal(err)
	}
	run, err = st.Orchestration().GetRun(t.Context(), run.ID)
	if err != nil {
		t.Fatal(err)
	}
	if controlmodel.IsOrchestrationRunTerminal(run.State) || len(run.Output) != 0 {
		t.Fatalf("task overwrote workflow completion: %+v", run)
	}
	nodes, err := st.Orchestration().ListNodes(t.Context(), run.ID)
	if err != nil {
		t.Fatal(err)
	}
	output := CompletedRunOutput(run, nodes)
	if !bytes.Contains(output, []byte(`"after":{"answer":9007199254740993}`)) || !bytes.Contains(output, []byte(`"sender":{"output":"sent"}`)) {
		t.Fatalf("missing workflow results: %s", output)
	}
}

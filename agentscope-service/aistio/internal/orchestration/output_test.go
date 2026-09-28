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
	"encoding/json"
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
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

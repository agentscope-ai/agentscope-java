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

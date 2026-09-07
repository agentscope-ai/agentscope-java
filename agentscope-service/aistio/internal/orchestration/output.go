package orchestration

import (
	"encoding/json"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

// CompletedRunOutput preserves an explicit output, or the root coordinator's
// final delivery. A multi-node workflow without a root delivery exposes each
// completed output by node key instead of silently discarding them.
func CompletedRunOutput(run *controlmodel.OrchestrationRun, nodes []*controlmodel.RunNode) json.RawMessage {
	if len(run.Output) > 0 && string(run.Output) != "null" {
		return run.Output
	}
	outputs := map[string]json.RawMessage{}
	for _, node := range nodes {
		if node.State != controlmodel.RunNodeSucceeded || len(node.Output) == 0 || string(node.Output) == "null" {
			continue
		}
		if node.Type == controlmodel.RunNodeTeam && node.IssueID != nil && *node.IssueID == run.RootIssueID {
			return node.Output
		}
		outputs[node.NodeKey] = node.Output
	}
	if len(outputs) == 1 {
		for _, output := range outputs {
			return output
		}
	}
	if len(outputs) == 0 {
		return nil
	}
	raw, _ := json.Marshal(outputs)
	return raw
}

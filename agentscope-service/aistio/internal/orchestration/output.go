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

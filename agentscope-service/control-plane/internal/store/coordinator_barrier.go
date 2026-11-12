// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package store

import (
	"bytes"
	"encoding/json"
	"fmt"
	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
)

// ValidateCoordinatorBarrier uses the same scope for preflight and atomic commit.
// Commit rejects even empty duplicate leaders; preflight may retire them first.
// Callers must supply complete bounded views, never a truncated first page.
func ValidateCoordinatorBarrier(run *model.OrchestrationRun, node *model.RunNode, task *model.AgentTask,
	tasks []*model.AgentTask, nodes []*model.RunNode, children []*model.Issue, allowEmptyLeaders bool) error {
	if len(tasks) > MaxRunTaskView || len(nodes) > MaxRunNodes || len(children) > MaxRunNodes {
		return ErrResourceLimit
	}
	// Declared Team nodes own their delegated task tree, not sibling Workflow
	// steps. A downstream step cannot finish before this coordinator completes.
	scopedTasks := map[uuid.UUID]bool{}
	scopedNodes := map[uuid.UUID]bool{node.ID: true}
	byParent := map[uuid.UUID][]*model.AgentTask{}
	queue := []*model.AgentTask{}
	for _, candidate := range tasks {
		if candidate.ParentTaskID != nil {
			byParent[*candidate.ParentTaskID] = append(byParent[*candidate.ParentTaskID], candidate)
		}
		if candidate.RunNodeID == node.ID {
			scopedTasks[candidate.ID] = true
			queue = append(queue, candidate)
		}
	}
	// Visit each task once; deep delegation does not repeatedly rescan the Run.
	for head := 0; head < len(queue); head++ {
		candidate := queue[head]
		scopedNodes[candidate.RunNodeID] = true
		for _, child := range byParent[candidate.ID] {
			if !scopedTasks[child.ID] {
				scopedTasks[child.ID] = true
				queue = append(queue, child)
			}
		}
	}

	for _, candidate := range tasks {
		if run.Mode != model.RunModeAdaptive && !scopedTasks[candidate.ID] {
			continue
		}
		if candidate.ID == task.ID || model.IsAgentTaskTerminal(candidate.Status) {
			continue
		}
		if candidate.LeaderTask && candidate.RunNodeID == node.ID {
			// A routed leader follow-up owns worker outcome inputs. It is safe to
			// retire an empty duplicate, but never cancel an unconsumed outcome.
			if !allowEmptyLeaders || len(candidate.Inputs) > 0 {
				return fmt.Errorf("%w: coordinator has pending leader outcome task %s", ErrConflict, candidate.ID)
			}
			continue
		}
		return fmt.Errorf("%w: coordinator has active worker task %s", ErrConflict, candidate.ID)
	}
	for _, candidate := range nodes {
		if run.Mode != model.RunModeAdaptive && !scopedNodes[candidate.ID] {
			continue
		}
		if candidate.ID != node.ID && !model.IsRunNodeTerminal(candidate.State) {
			return fmt.Errorf("%w: coordinator has active node %s", ErrConflict, candidate.NodeKey)
		}
	}
	for _, child := range children {
		if run.Mode != model.RunModeAdaptive {
			sourceID, _ := uuid.Parse(child.SourceRef)
			if child.SourceType != "agent-task" || !scopedTasks[sourceID] {
				continue
			}
		}
		if child.Status != model.IssueDone && child.Status != model.IssueCancelled {
			return fmt.Errorf("%w: coordinator has active child issue %s", ErrConflict, child.ID)
		}
	}
	return nil
}

func CoordinatorCompletionIntentPath(namespace string) string {
	return "orchestration/coordinator-completion/" + namespace
}

func CanonicalCoordinatorOutput(raw json.RawMessage) (json.RawMessage, error) {
	if len(raw) > MaxRunGraphBytes {
		return nil, ErrResourceLimit
	}
	if len(raw) == 0 {
		raw = json.RawMessage(`null`)
	}
	if !json.Valid(raw) {
		return nil, fmt.Errorf("invalid coordinator output JSON")
	}
	var value any
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	if err := decoder.Decode(&value); err != nil {
		return nil, err
	}
	return json.Marshal(value)
}

func CoordinatorIntentAccepted(raw json.RawMessage) (bool, error) {
	var value struct {
		Accepted bool `json:"accepted"`
	}
	if err := json.Unmarshal(raw, &value); err != nil {
		return false, err
	}
	return value.Accepted, nil
}

func SetCoordinatorIntentAccepted(raw json.RawMessage, accepted bool) (json.RawMessage, error) {
	if len(raw) > MaxRunGraphBytes+4096 {
		return nil, ErrResourceLimit
	}
	var value map[string]json.RawMessage
	if err := json.Unmarshal(raw, &value); err != nil {
		return nil, err
	}
	if value == nil {
		return nil, ErrConflict
	}
	value["accepted"] = json.RawMessage(`false`)
	if accepted {
		value["accepted"] = json.RawMessage(`true`)
	}
	return json.Marshal(value)
}

func CoordinatorIntentMatchesOutput(raw, output json.RawMessage) (bool, error) {
	var value struct {
		Output json.RawMessage `json:"output"`
	}
	if err := json.Unmarshal(raw, &value); err != nil {
		return false, err
	}
	expected, err := CanonicalCoordinatorOutput(value.Output)
	if err != nil {
		return false, err
	}
	actual, err := CanonicalCoordinatorOutput(output)
	if err != nil {
		return false, err
	}
	return bytes.Equal(expected, actual), nil
}

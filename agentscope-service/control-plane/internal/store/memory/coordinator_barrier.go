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
	"context"
	"encoding/json"
	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
	"time"
)

func (s *Store) coordinatorRunOpenLocked(runID uuid.UUID) error {
	run := s.runs[runID]
	if run == nil {
		return store.ErrNotFound
	}
	if run.State == model.RunCancelling {
		return store.ErrConflict
	}
	if run.Mode == model.RunModeAdaptive && !model.IsOrchestrationRunTerminal(run.State) {
		for _, node := range s.runNodes {
			if node.RunID == runID && node.Type == model.RunNodeTeam && node.IssueID != nil && *node.IssueID == run.RootIssueID && model.IsRunNodeTerminal(node.State) {
				return store.ErrConflict
			}
		}
	}
	return nil
}

func (s *Store) guardTaskCoordinatorLocked(taskID uuid.UUID, allowContinuation bool) error {
	task := s.agentTasks[taskID]
	if task == nil {
		return store.ErrNotFound
	}
	if err := s.coordinatorRunOpenLocked(task.OrchestrationRunID); err != nil {
		return err
	}
	if model.IsOrchestrationRunTerminal(s.runs[task.OrchestrationRunID].State) {
		if allowContinuation {
			return nil
		}
		return store.ErrConflict
	}
	seen := map[uuid.UUID]bool{}
	for current := task; current != nil && current.OrchestrationRunID == task.OrchestrationRunID; {
		if seen[current.ID] || len(seen) >= 256 {
			return store.ErrConflict
		}
		seen[current.ID] = true
		if node := s.runNodes[current.RunNodeID]; node != nil && node.Type == model.RunNodeTeam && model.IsRunNodeTerminal(node.State) {
			return store.ErrConflict
		}
		if current.ParentTaskID == nil {
			break
		}
		current = s.agentTasks[*current.ParentTaskID]
	}
	return nil
}

func (s *Store) guardCoordinatorChildLocked(issue *model.Issue) error {
	return s.guardCoordinatorChildWriteLocked(issue, false)
}

func (s *Store) guardCoordinatorChildWriteLocked(issue *model.Issue, allowTerminalSource bool) error {
	if issue.SourceType == "agent-task" {
		id, err := uuid.Parse(issue.SourceRef)
		if err != nil {
			return store.ErrNotFound
		}
		source := s.agentTasks[id]
		if source == nil || source.Tenant != issue.Tenant || source.Namespace != issue.Namespace {
			return store.ErrNotFound
		}
		if err = s.guardTaskCoordinatorLocked(id, allowTerminalSource); err != nil {
			return err
		}
	}
	if issue.ParentIssueID == nil {
		return nil
	}
	for _, node := range s.runNodes {
		run := s.runs[node.RunID]
		if node.Type == model.RunNodeTeam && node.IssueID != nil && *node.IssueID == *issue.ParentIssueID && run != nil && run.Mode == model.RunModeAdaptive && !model.IsOrchestrationRunTerminal(run.State) {
			if err := s.coordinatorRunOpenLocked(run.ID); err != nil {
				return err
			}
			if model.IsRunNodeTerminal(node.State) {
				return store.ErrConflict
			}
		}
	}
	return nil
}

func (r *orchestrationRepo) FinalizeCoordinatorNode(_ context.Context, taskID uuid.UUID, expected int64, output json.RawMessage, actor model.Actor) (*model.RunNode, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	task := r.s.agentTasks[taskID]
	if task == nil {
		return nil, store.ErrNotFound
	}
	node, run := r.s.runNodes[task.RunNodeID], r.s.runs[task.OrchestrationRunID]
	if node == nil || run == nil {
		return nil, store.ErrNotFound
	}
	if !task.LeaderTask || task.Status != model.AgentTaskCompleted || node.Type != model.RunNodeTeam || node.State != model.RunNodeWaiting || node.Version != expected || model.IsOrchestrationRunTerminal(run.State) || run.State == model.RunCancelling {
		return nil, store.ErrConflict
	}
	var tasks []*model.AgentTask
	for _, v := range r.s.agentTasks {
		if v.OrchestrationRunID == run.ID {
			copy := &model.AgentTask{ID: v.ID, RunNodeID: v.RunNodeID, ParentTaskID: v.ParentTaskID, LeaderTask: v.LeaderTask, Status: v.Status}
			if len(r.s.taskInputs[v.ID]) > 0 {
				copy.Inputs = []model.AgentTaskInput{{}}
			}
			tasks = append(tasks, copy)
			if len(tasks) > store.MaxRunTaskView {
				return nil, store.ErrResourceLimit
			}
		}
	}
	var nodes []*model.RunNode
	for _, v := range r.s.runNodes {
		if v.RunID == run.ID {
			nodes = append(nodes, v)
			if len(nodes) > store.MaxRunNodes {
				return nil, store.ErrResourceLimit
			}
		}
	}
	issueID := task.IssueID
	if node.IssueID != nil {
		issueID = *node.IssueID
	}
	var children []*model.Issue
	for _, v := range r.s.issues {
		if v.ParentIssueID != nil && *v.ParentIssueID == issueID {
			children = append(children, v)
			if len(children) > store.MaxRunNodes {
				return nil, store.ErrResourceLimit
			}
		}
	}
	if err := store.ValidateCoordinatorBarrier(run, node, task, tasks, nodes, children, false); err != nil {
		return nil, err
	}
	now := time.Now().UTC()
	node.State = model.RunNodeSucceeded
	node.Version++
	node.UpdatedAt = now
	node.CompletedAt = &now
	node.WaitReason, node.FailureCode, node.FailureMessage = "", "", ""
	if output != nil {
		node.Output = cloneJSON(output)
	}
	r.s.runEvents[run.ID] = append(r.s.runEvents[run.ID], &model.RunEvent{ID: uuid.New(), RunID: run.ID, Tenant: task.Tenant, Namespace: task.Namespace, NodeID: &node.ID, AgentTaskID: &task.ID, Sequence: int64(len(r.s.runEvents[run.ID]) + 1), Type: "node.succeeded", Actor: actor, Payload: cloneJSON(output), IdempotencyKey: "coordinator-complete:" + node.ID.String(), OccurredAt: now})
	if signal := r.s.runEventSignals[run.ID]; signal != nil {
		close(signal)
	}
	r.s.runEventSignals[run.ID] = make(chan struct{})
	return cloneRunNode(node), nil
}

func (r *orchestrationRepo) SaveCoordinatorCompletionIntent(_ context.Context, taskID uuid.UUID, expected int64, raw json.RawMessage) (json.RawMessage, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	task := r.s.agentTasks[taskID]
	if task == nil {
		return nil, store.ErrNotFound
	}
	if !task.LeaderTask || task.Version != expected || model.IsAgentTaskTerminal(task.Status) && task.Status != model.AgentTaskCompleted {
		return nil, store.ErrConflict
	}
	path := store.CoordinatorCompletionIntentPath(task.Namespace)
	key := kvKey(task.Tenant, path, task.ID.String())
	previous := r.s.kv[key]
	if task.Status == model.AgentTaskCompleted && previous != nil {
		accepted, err := store.CoordinatorIntentAccepted(previous.Value)
		if err != nil {
			return nil, err
		}
		if accepted {
			return cloneJSON(previous.Value), nil
		}
	}
	value, err := store.SetCoordinatorIntentAccepted(raw, task.Status == model.AgentTaskCompleted)
	if err != nil {
		return nil, err
	}
	version := int64(1)
	if previous != nil {
		version = previous.Version + 1
	}
	r.s.kv[key] = &store.KVItem{Key: task.ID.String(), NsPath: path, Value: value, Version: version}
	return cloneJSON(value), nil
}

func (s *Store) validateCoordinatorIntentLocked(task *model.AgentTask, completion store.TaskCompletion) error {
	if !completion.CoordinatorConclusion {
		return nil
	}
	if !task.LeaderTask {
		return store.ErrConflict
	}
	item := s.kv[kvKey(task.Tenant, store.CoordinatorCompletionIntentPath(task.Namespace), task.ID.String())]
	if item == nil {
		return store.ErrNotFound
	}
	matches, err := store.CoordinatorIntentMatchesOutput(item.Value, completion.Result)
	if err != nil {
		return err
	}
	if !matches {
		return store.ErrConflict
	}
	return nil
}

func (s *Store) acceptCoordinatorIntentLocked(task *model.AgentTask, completion store.TaskCompletion) error {
	if !completion.CoordinatorConclusion {
		return nil
	}
	key := kvKey(task.Tenant, store.CoordinatorCompletionIntentPath(task.Namespace), task.ID.String())
	item := s.kv[key]
	accepted, err := store.SetCoordinatorIntentAccepted(item.Value, true)
	if err != nil {
		return err
	}
	item.Value = accepted
	item.Version++
	return nil
}

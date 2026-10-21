// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// https://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package orchestration

import (
	"context"
	"encoding/json"
	"errors"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/taskplane"
	"github.com/google/uuid"
)

const cancellationCursorPath = "runtime/cancellation-cursors"
const cancellationNodeBatch = 8
const cancellationWorkBatch = 16

// RequestCancellation commits only the intent. A parent never recursively
// cleans a child's graph while holding its own Run lock.
func (s *Service) RequestCancellation(ctx context.Context, id uuid.UUID) (*model.OrchestrationRun, error) {
	var result *model.OrchestrationRun
	err := s.Store.WithSessionLock(ctx, "workflow-run:"+id.String(), func(ctx context.Context) error {
		var err error
		result, err = s.markCancelling(ctx, id)
		return err
	})
	return result, err
}

func (s *Service) markCancelling(ctx context.Context, id uuid.UUID) (*model.OrchestrationRun, error) {
	run, err := s.Store.Orchestration().GetRun(ctx, id)
	if err != nil || model.IsOrchestrationRunTerminal(run.State) || run.State == model.RunCancelling {
		return run, err
	}
	return s.Store.Orchestration().TransitionRun(ctx, id, run.Version, model.RunCancelling, nil, "", "")
}

func (s *Service) advanceCancellation(ctx context.Context, run *model.OrchestrationRun) (*model.OrchestrationRun, error) {
	after := uuid.Nil
	row, err := s.Store.KV().Get(ctx, run.Tenant, cancellationCursorPath, run.ID.String())
	if err == nil {
		if err = json.Unmarshal(row.Value, &after); err != nil {
			return nil, err
		}
	} else if !errors.Is(err, store.ErrNotFound) {
		return nil, err
	}
	nodes, err := s.Store.Orchestration().ListNodesPage(ctx, run.ID, store.RunNodeFilter{AfterID: after, ActiveOnly: true, Limit: cancellationNodeBatch})
	if err != nil {
		return nil, err
	}
	for _, node := range nodes {
		done, err := s.stopCancellationNode(ctx, run, node)
		if err != nil {
			return nil, err
		}
		if done {
			if _, err = s.Store.Orchestration().TransitionNode(ctx, node.ID, node.Version, model.RunNodeCancelled, nil, "", ""); err != nil && !errors.Is(err, store.ErrConflict) {
				return nil, err
			}
		}
		after = node.ID
	}
	if len(nodes) < cancellationNodeBatch {
		after = uuid.Nil
	}
	raw, _ := json.Marshal(after)
	if _, err = s.Store.KV().Put(ctx, run.Tenant, cancellationCursorPath, run.ID.String(), raw); err != nil {
		return nil, err
	}
	remaining, err := s.Store.Orchestration().ListNodesPage(ctx, run.ID, store.RunNodeFilter{ActiveOnly: true, Limit: 1})
	if err != nil || len(remaining) != 0 {
		return run, err
	}
	// This is logical graph completion. Portal separately waits for each
	// physical Attempt's stop proof and final facts before releasing its quota.
	if run.FailureCode != "" {
		return s.Store.Orchestration().TransitionRun(ctx, run.ID, run.Version, model.RunFailed, nil, run.FailureCode, run.FailureMessage)
	}
	return s.Store.Orchestration().TransitionRun(ctx, run.ID, run.Version, model.RunCancelled, nil, "", "")
}

func (s *Service) stopCancellationNode(ctx context.Context, run *model.OrchestrationRun, node *model.RunNode) (bool, error) {
	children, err := s.Store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{Tenant: run.Tenant, Namespace: run.Namespace,
		ParentNodeID: node.ID, ActiveOnly: true, ExcludeCancelling: true, Limit: cancellationWorkBatch})
	if err != nil {
		return false, err
	}
	for _, child := range children {
		if _, err = s.RequestCancellation(ctx, child.ID); err != nil {
			return false, err
		}
	}
	approvals, err := s.Store.Collaboration().ListApprovals(ctx, store.ApprovalFilter{Tenant: run.Tenant, Namespace: run.Namespace,
		TargetType: "run-node", TargetRef: node.ID.String(), Status: model.ApprovalPending, Limit: cancellationWorkBatch})
	if err != nil {
		return false, err
	}
	for _, approval := range approvals {
		if _, err = s.Store.Collaboration().DecideApproval(ctx, approval.ID, approval.Version, model.ApprovalCancelled,
			model.Actor{Type: model.ActorSystem, Ref: "orchestration-cancel"}, nil); err != nil && !errors.Is(err, store.ErrConflict) {
			return false, err
		}
	}
	tasks, err := s.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{Tenant: run.Tenant, Namespace: run.Namespace,
		NodeID: node.ID, ActiveOnly: true, OmitInputs: true, Limit: cancellationWorkBatch})
	if err != nil {
		return false, err
	}
	plane := s.TaskPlane
	if plane == nil {
		plane = &taskplane.Service{Store: s.Store}
	}
	for _, task := range tasks {
		if _, err = plane.CancelTask(ctx, task.ID, task.Version); err != nil && !errors.Is(err, store.ErrConflict) {
			return false, err
		}
	}
	children, err = s.Store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{Tenant: run.Tenant, Namespace: run.Namespace,
		ParentNodeID: node.ID, ActiveOnly: true, Limit: 1})
	if err != nil {
		return false, err
	}
	approvals, err = s.Store.Collaboration().ListApprovals(ctx, store.ApprovalFilter{Tenant: run.Tenant, Namespace: run.Namespace,
		TargetType: "run-node", TargetRef: node.ID.String(), Status: model.ApprovalPending, Limit: 1})
	if err != nil {
		return false, err
	}
	tasks, err = s.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{Tenant: run.Tenant, Namespace: run.Namespace,
		NodeID: node.ID, ActiveOnly: true, OmitInputs: true, Limit: 1})
	return len(children) == 0 && len(approvals) == 0 && len(tasks) == 0, err
}

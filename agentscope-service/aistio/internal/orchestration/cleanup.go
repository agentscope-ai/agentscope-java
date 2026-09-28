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
	"context"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/taskplane"
)

// stopNodeWork closes durable obligations before their owning node becomes
// terminal. Retrying an interrupted cancellation also discovers children whose
// IDs had not yet been recorded in the node output.
func (s *Service) stopNodeWork(ctx context.Context, run *controlmodel.OrchestrationRun, node *controlmodel.RunNode) error {
	children, err := s.Store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{Tenant: run.Tenant, Namespace: run.Namespace, ParentNodeID: node.ID, ActiveOnly: true, Limit: 100})
	if err != nil {
		return err
	}
	for _, child := range children {
		if _, err := s.Cancel(ctx, child.ID); err != nil {
			return err
		}
	}
	approvals, err := s.Store.Collaboration().ListApprovals(ctx, store.ApprovalFilter{Tenant: run.Tenant, Namespace: run.Namespace, TargetType: "run-node", TargetRef: node.ID.String(), Limit: 100})
	if err != nil {
		return err
	}
	for _, approval := range approvals {
		if approval.Status == controlmodel.ApprovalPending {
			if _, err := s.Store.Collaboration().DecideApproval(ctx, approval.ID, approval.Version, controlmodel.ApprovalCancelled, controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "orchestration-cancel"}, nil); err != nil {
				return err
			}
		}
	}
	tasks, err := s.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{Tenant: run.Tenant, Namespace: run.Namespace, NodeID: node.ID, Limit: 500})
	if err != nil {
		return err
	}
	plane := s.TaskPlane
	if plane == nil {
		plane = &taskplane.Service{Store: s.Store}
	}
	for _, task := range tasks {
		if !controlmodel.IsAgentTaskTerminal(task.Status) {
			if _, err := plane.CancelTask(ctx, task.ID, task.Version); err != nil {
				return err
			}
		}
	}
	return nil
}

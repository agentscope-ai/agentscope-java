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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package orchestration

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type MaterializeTeamRequest struct {
	Run              *controlmodel.OrchestrationRun
	IssueID          uuid.UUID
	Team             *controlmodel.CollaborationTeam
	NodeID           uuid.UUID
	NodeKey          string
	RuntimeCandidate *controlmodel.RuntimeBindingCandidate
	Actor            controlmodel.Actor
}

// MaterializeTeamCoordinator is the single constructor for a persistent or
// dynamic Team participating in a Run. It freezes the Team before creating
// exactly one coordinator node and one initial leader obligation.
func MaterializeTeamCoordinator(ctx context.Context, st store.Store, req MaterializeTeamRequest) (*controlmodel.RunNode, *controlmodel.AgentTask, error) {
	if st == nil || req.Run == nil || req.Team == nil || req.IssueID == uuid.Nil {
		return nil, nil, fmt.Errorf("Run, Team, and issueId are required")
	}
	if req.Team.Status != controlmodel.TeamActive {
		return nil, nil, fmt.Errorf("Team %s is not active", req.Team.ID)
	}
	if req.Team.Tenant != req.Run.Tenant || req.Team.Namespace != req.Run.Namespace || req.Team.LeaderAgentRef == "" {
		return nil, nil, fmt.Errorf("Team is outside the Run scope or has no leader")
	}
	snapshot, err := json.Marshal(req.Team)
	if err != nil {
		return nil, nil, err
	}
	if _, err = st.Orchestration().PutTeamSnapshot(ctx, &controlmodel.RunTeamSnapshot{
		RunID: req.Run.ID, TeamID: req.Team.ID, Tenant: req.Run.Tenant, Namespace: req.Run.Namespace, Snapshot: snapshot,
	}); err != nil {
		return nil, nil, err
	}
	if req.NodeKey == "" {
		req.NodeKey = "team-coordinator"
	}
	if req.NodeID == uuid.Nil {
		req.NodeID = uuid.NewSHA1(req.Run.ID, []byte(req.NodeKey))
	}
	node, err := st.Orchestration().GetNode(ctx, req.NodeID)
	if errors.Is(err, store.ErrNotFound) {
		node, err = st.Orchestration().CreateNode(ctx, &controlmodel.RunNode{ID: req.NodeID, RunID: req.Run.ID,
			Tenant: req.Run.Tenant, Namespace: req.Run.Namespace, NodeKey: req.NodeKey,
			Type: controlmodel.RunNodeTeam, Role: "leader", IssueID: &req.IssueID,
			State: controlmodel.RunNodeReady, Iteration: 1})
		if errors.Is(err, store.ErrConflict) {
			node, err = st.Orchestration().GetNode(ctx, req.NodeID)
		}
	}
	if err != nil {
		return nil, nil, err
	}
	if node.RunID != req.Run.ID || node.NodeKey != req.NodeKey || node.Type != controlmodel.RunNodeTeam {
		return nil, nil, fmt.Errorf("existing coordinator node does not match the Team request")
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{
		RunID: req.Run.ID, NodeID: node.ID, AgentRef: req.Team.LeaderAgentRef, Limit: 1,
	})
	if err != nil {
		return nil, nil, err
	}
	if len(tasks) > 0 {
		return node, tasks[0], nil
	}
	task, err := st.Collaboration().CreateRunAgentTask(ctx, store.RunTaskRequest{RunID: req.Run.ID,
		NodeID: node.ID, IssueID: req.IssueID, AgentRef: req.Team.LeaderAgentRef, TeamID: &req.Team.ID,
		TeamRole: "leader", Leader: true, RuntimeCandidate: req.RuntimeCandidate, Originator: req.Actor})
	return node, task, err
}

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

package memory

import (
	"encoding/json"
	"fmt"
	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"time"
)

func (r *collaborationRepo) attachTeamContinuationLocked(issue *model.Issue, task, source *model.AgentTask) bool {
	if task.TeamID == nil || !source.LeaderTask || issue.ParentIssueID == nil {
		return false
	}
	old := r.s.runs[source.OrchestrationRunID]
	if old == nil || old.State != model.RunFailed || old.RootIssueID != *issue.ParentIssueID {
		return false
	}
	root := r.s.issues[old.RootIssueID]
	if root == nil || root.ArchivedAt != nil || root.AssigneeType != model.AssigneeTeam || root.AssigneeRef != task.TeamID.String() || (root.Status != model.IssueBlocked && root.Status != model.IssueInProgress) {
		return false
	}
	var run *model.OrchestrationRun
	for _, candidate := range r.s.runs {
		if candidate.RootIssueID == root.ID && !model.IsOrchestrationRunTerminal(candidate.State) {
			if candidate.RerunOfRunID == nil || *candidate.RerunOfRunID != old.ID {
				return false
			}
			run = candidate
		}
	}
	now := time.Now().UTC()
	if run == nil {
		run = &model.OrchestrationRun{ID: uuid.New(), Tenant: root.Tenant, Namespace: root.Namespace, RootIssueID: root.ID, Mode: model.RunModeAdaptive, RerunOfRunID: &old.ID, TriggerType: "team_continuation", TriggerRef: task.CausationID, State: model.RunRunning, Version: 1, CreatedBy: task.Originator, CreatedAt: now, UpdatedAt: now, StartedAt: &now, Input: cloneJSON(old.Input)}
		r.s.runs[run.ID] = run
		key := old.ID.String() + "\x00" + task.TeamID.String()
		if snapshot := r.s.runSnapshots[key]; snapshot != nil {
			copy := *snapshot
			copy.RunID = run.ID
			copy.CreatedAt = now
			copy.Snapshot = cloneJSON(snapshot.Snapshot)
			r.s.runSnapshots[run.ID.String()+"\x00"+task.TeamID.String()] = &copy
		}
		id := uuid.NewSHA1(run.ID, []byte("resumed-coordinator"))
		r.s.runNodes[id] = &model.RunNode{ID: id, RunID: run.ID, Tenant: root.Tenant, Namespace: root.Namespace, NodeKey: "resumed-coordinator", Type: model.RunNodeTeam, Role: "leader", IssueID: &root.ID, State: model.RunNodeWaiting, Iteration: 1, Version: 1, CreatedAt: now, UpdatedAt: now}
		payload, _ := json.Marshal(map[string]any{"previousRunId": old.ID, "rootIssueId": root.ID, "resumedIssueId": issue.ID})
		r.s.runEvents[run.ID] = append(r.s.runEvents[run.ID], &model.RunEvent{ID: uuid.New(), RunID: run.ID, Tenant: root.Tenant, Namespace: root.Namespace, Sequence: 1, Type: "team.continued", Actor: task.Originator, Payload: payload, OccurredAt: now, IdempotencyKey: "team-continued:" + run.ID.String()})
	}
	task.OrchestrationRunID = run.ID
	if task.LeaderTask {
		task.RunNodeID = uuid.NewSHA1(run.ID, []byte("resumed-coordinator"))
	}
	if root.Status == model.IssueBlocked {
		previous := root.Status
		root.Status = model.IssueInProgress
		root.Version++
		root.UpdatedAt = now
		reason := "Human input resumed delegated Team work"
		details, _ := json.Marshal(map[string]string{"from": string(previous), "to": string(root.Status), "reason": reason})
		r.appendActivityLocked(&model.Activity{Tenant: root.Tenant, Namespace: root.Namespace, IssueID: &root.ID, Actor: task.Originator, Action: "issue.status_changed", ObjectType: "issue", ObjectRef: root.ID.String(), Details: details})
		r.notifyIssueInboxLocked(root, previous, task.Originator, reason, task)
		r.enqueueEventLocked(root.Tenant, "issue", root.ID, "issue.status-changed.v1", map[string]any{"issue": root, "previousStatus": previous}, fmt.Sprintf("issue-status:%s:%d", root.ID, root.Version))
	}
	return true
}

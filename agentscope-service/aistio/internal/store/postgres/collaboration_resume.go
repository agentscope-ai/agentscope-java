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

package postgres

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

// attachTeamContinuationTx preserves the root coordinator when a human resumes
// delegated work after failure. The failed Run remains immutable. All resumed
// branches share one active continuation and return to its coordinator node.
func attachTeamContinuationTx(ctx context.Context, tx pgx.Tx, issue *model.Issue, task, source *model.AgentTask) (bool, error) {
	if task.TeamID == nil || !source.LeaderTask || issue.ParentIssueID == nil {
		return false, nil
	}
	old, err := scanRun(tx.QueryRow(ctx, `SELECT `+runCols+` FROM orchestration_runs WHERE id=$1`, source.OrchestrationRunID))
	if err != nil {
		return false, err
	}
	if old.State != model.RunFailed || old.RootIssueID != *issue.ParentIssueID {
		return false, nil
	}
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1,0))`, "team-continuation:"+old.RootIssueID.String()); err != nil {
		return false, err
	}
	root, err := scanIssue(tx.QueryRow(ctx, `SELECT `+issueColumns+` FROM issues WHERE id=$1 FOR UPDATE`, old.RootIssueID))
	if err != nil {
		return false, err
	}
	if root.ArchivedAt != nil || root.AssigneeType != model.AssigneeTeam || root.AssigneeRef != task.TeamID.String() || (root.Status != model.IssueBlocked && root.Status != model.IssueInProgress) {
		return false, nil
	}
	var runID uuid.UUID
	err = tx.QueryRow(ctx, `SELECT id FROM orchestration_runs WHERE root_issue_id=$1 AND rerun_of_run_id=$2 AND state IN ('running','waiting','paused') ORDER BY created_at DESC LIMIT 1`, root.ID, old.ID).Scan(&runID)
	if err != nil && err != pgx.ErrNoRows {
		return false, err
	}
	if err == pgx.ErrNoRows {
		// Do not silently join or compete with an unrelated replacement Run.
		var active bool
		if err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration_runs WHERE root_issue_id=$1 AND state NOT IN ('succeeded','partial_succeeded','failed','cancelled'))`, root.ID).Scan(&active); err != nil {
			return false, err
		}
		if active {
			return false, fmt.Errorf("root Issue already has a different active Run")
		}
		runID = uuid.New()
		_, err = tx.Exec(ctx, `INSERT INTO orchestration_runs(id,tenant,namespace,root_issue_id,mode,rerun_of_run_id,trigger_type,trigger_ref,state,version,created_by_type,created_by_ref,started_at,input)
   VALUES($1,$2,$3,$4,'adaptive',$5,'team_continuation',$6,'running',1,$7,$8,now(),$9)`, runID, root.Tenant, root.Namespace, root.ID, old.ID, task.CausationID, task.Originator.Type, nullStr(task.Originator.Ref), nullJSON(old.Input))
		if err != nil {
			return false, err
		}
		_, err = tx.Exec(ctx, `INSERT INTO orchestration_run_team_snapshots(run_id,team_id,tenant,namespace,snapshot) SELECT $1,team_id,tenant,namespace,snapshot FROM orchestration_run_team_snapshots WHERE run_id=$2 AND team_id=$3`, runID, old.ID, *task.TeamID)
		if err != nil {
			return false, err
		}
		_, err = tx.Exec(ctx, `INSERT INTO orchestration_run_nodes(id,run_id,tenant,namespace,node_key,type,role,issue_id,state,iteration,version)
   VALUES($1,$2,$3,$4,'resumed-coordinator','team','leader',$5,'waiting',1,1)`, uuid.NewSHA1(runID, []byte("resumed-coordinator")), runID, root.Tenant, root.Namespace, root.ID)
		if err != nil {
			return false, err
		}
		payload, _ := json.Marshal(map[string]any{"previousRunId": old.ID, "rootIssueId": root.ID, "resumedIssueId": issue.ID})
		if err = appendRunEventTx(ctx, tx, &model.RunEvent{RunID: runID, Tenant: root.Tenant, Namespace: root.Namespace, Type: "team.continued", Actor: task.Originator, Payload: payload, IdempotencyKey: "team-continued:" + runID.String()}); err != nil {
			return false, err
		}
	}
	task.OrchestrationRunID = runID
	if task.LeaderTask {
		task.RunNodeID = uuid.NewSHA1(runID, []byte("resumed-coordinator"))
	}
	if root.Status == model.IssueBlocked {
		previous := root.Status
		root, err = scanIssue(tx.QueryRow(ctx, `UPDATE issues SET status='in_progress',version=version+1,updated_at=now() WHERE id=$1 RETURNING `+issueColumns, root.ID))
		if err != nil {
			return false, err
		}
		reason := "Human input resumed delegated Team work"
		details, _ := json.Marshal(map[string]string{"from": string(previous), "to": string(root.Status), "reason": reason})
		if err = insertActivityTx(ctx, tx, &model.Activity{Tenant: root.Tenant, Namespace: root.Namespace, IssueID: &root.ID, Actor: task.Originator, Action: "issue.status_changed", ObjectType: "issue", ObjectRef: root.ID.String(), Details: details}); err != nil {
			return false, err
		}
		if err = notifyIssueInboxTx(ctx, tx, root, previous, task.Originator, reason, task); err != nil {
			return false, err
		}
		if err = enqueueCollaborationEventTx(ctx, tx, root.Tenant, "issue", root.ID, "issue.status-changed.v1", map[string]any{"issue": root, "previousStatus": previous}, fmt.Sprintf("issue-status:%s:%d", root.ID, root.Version)); err != nil {
			return false, err
		}
	}
	return true, nil
}

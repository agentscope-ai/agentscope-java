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
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type orchestrationRepo struct{ pool *pgxpool.Pool }

const runEventNotifyChannel = "aistio_run_events"

func orchNotFound(err error) error {
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "23505" {
		return store.ErrConflict
	}
	if errors.Is(err, pgx.ErrNoRows) {
		return store.ErrNotFound
	}
	return err
}

const definitionCols = `id,tenant,namespace,name,description,draft_spec,draft_version,version,created_by_type,created_by_ref,created_at,updated_at,archived_at`

func scanDefinition(row scannable) (*controlmodel.OrchestrationDefinition, error) {
	v := &controlmodel.OrchestrationDefinition{}
	var desc, ref *string
	err := row.Scan(&v.ID, &v.Tenant, &v.Namespace, &v.Name, &desc, &v.DraftSpec, &v.DraftVersion, &v.Version, &v.CreatedBy.Type, &ref, &v.CreatedAt, &v.UpdatedAt, &v.ArchivedAt)
	if err != nil {
		return nil, orchNotFound(err)
	}
	v.Description = deref(desc)
	v.CreatedBy.Ref = deref(ref)
	return v, nil
}
func (r *orchestrationRepo) CreateDefinition(ctx context.Context, in *controlmodel.OrchestrationDefinition) (*controlmodel.OrchestrationDefinition, error) {
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	if in.DraftVersion == 0 {
		in.DraftVersion = 1
	}
	v, err := scanDefinition(r.pool.QueryRow(ctx, `INSERT INTO orchestration_definitions(id,tenant,namespace,name,description,draft_spec,draft_version,version,created_by_type,created_by_ref) VALUES($1,$2,$3,$4,$5,$6,$7,1,$8,$9) RETURNING `+definitionCols, in.ID, in.Tenant, in.Namespace, in.Name, nullStr(in.Description), in.DraftSpec, in.DraftVersion, in.CreatedBy.Type, nullStr(in.CreatedBy.Ref)))
	return v, err
}
func (r *orchestrationRepo) GetDefinition(ctx context.Context, id uuid.UUID) (*controlmodel.OrchestrationDefinition, error) {
	return scanDefinition(r.pool.QueryRow(ctx, `SELECT `+definitionCols+` FROM orchestration_definitions WHERE id=$1`, id))
}
func (r *orchestrationRepo) ListDefinitions(ctx context.Context, f store.OrchestrationDefinitionFilter) ([]*controlmodel.OrchestrationDefinition, error) {
	limit := f.Limit
	if limit <= 0 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `SELECT `+definitionCols+` FROM orchestration_definitions WHERE ($1='' OR tenant=$1) AND ($2='' OR namespace=$2) AND ($3='' OR name=$3) AND ($4 OR archived_at IS NULL) AND NOT (id=ANY($7::uuid[])) ORDER BY updated_at DESC,id LIMIT $5 OFFSET $6`, f.Tenant, f.Namespace, f.Name, f.IncludeArchived, limit, max(0, f.Offset), nonNilResourceIDs(f.ExcludedIDs))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*controlmodel.OrchestrationDefinition{}
	for rows.Next() {
		v, e := scanDefinition(rows)
		if e != nil {
			return nil, e
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
func (r *orchestrationRepo) UpdateDefinition(ctx context.Context, in *controlmodel.OrchestrationDefinition, expected int64) (*controlmodel.OrchestrationDefinition, error) {
	v, err := scanDefinition(r.pool.QueryRow(ctx, `UPDATE orchestration_definitions SET name=$3,description=$4,draft_spec=$5,draft_version=draft_version+1,archived_at=$6,version=version+1,updated_at=now() WHERE id=$1 AND version=$2 RETURNING `+definitionCols, in.ID, expected, in.Name, nullStr(in.Description), in.DraftSpec, in.ArchivedAt))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return v, err
}

const revisionCols = `id,definition_id,tenant,namespace,revision,spec,checksum,published_by_type,published_by_ref,published_at`

func scanRevision(row scannable) (*controlmodel.OrchestrationRevision, error) {
	v := &controlmodel.OrchestrationRevision{}
	var ref *string
	err := row.Scan(&v.ID, &v.DefinitionID, &v.Tenant, &v.Namespace, &v.Revision, &v.Spec, &v.Checksum, &v.PublishedBy.Type, &ref, &v.PublishedAt)
	if err != nil {
		return nil, orchNotFound(err)
	}
	v.PublishedBy.Ref = deref(ref)
	return v, nil
}
func (r *orchestrationRepo) CreateRevision(ctx context.Context, in *controlmodel.OrchestrationRevision) (*controlmodel.OrchestrationRevision, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var version int64
	if err = tx.QueryRow(ctx, `SELECT version FROM orchestration_definitions WHERE id=$1 FOR UPDATE`, in.DefinitionID).Scan(&version); err != nil {
		return nil, orchNotFound(err)
	}
	if in.ExpectedDefinitionVersion != 0 && version != in.ExpectedDefinitionVersion {
		return nil, store.ErrConflict
	}
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	if in.Revision == 0 {
		if err = tx.QueryRow(ctx, `SELECT COALESCE(MAX(revision),0)+1 FROM orchestration_revisions WHERE definition_id=$1`, in.DefinitionID).Scan(&in.Revision); err != nil {
			return nil, err
		}
	}
	v, err := scanRevision(tx.QueryRow(ctx, `INSERT INTO orchestration_revisions(id,definition_id,tenant,namespace,revision,spec,checksum,published_by_type,published_by_ref) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9) RETURNING `+revisionCols, in.ID, in.DefinitionID, in.Tenant, in.Namespace, in.Revision, in.Spec, in.Checksum, in.PublishedBy.Type, nullStr(in.PublishedBy.Ref)))
	if err != nil {
		return nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	return v, nil
}
func (r *orchestrationRepo) GetRevision(ctx context.Context, id uuid.UUID) (*controlmodel.OrchestrationRevision, error) {
	return scanRevision(r.pool.QueryRow(ctx, `SELECT `+revisionCols+` FROM orchestration_revisions WHERE id=$1`, id))
}
func (r *orchestrationRepo) ListRevisions(ctx context.Context, id uuid.UUID) ([]*controlmodel.OrchestrationRevision, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+revisionCols+` FROM orchestration_revisions WHERE definition_id=$1 ORDER BY revision DESC`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*controlmodel.OrchestrationRevision{}
	for rows.Next() {
		v, e := scanRevision(rows)
		if e != nil {
			return nil, e
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

const runCols = `id,tenant,namespace,root_issue_id,mode,definition_revision_id,parent_run_id,parent_node_id,rerun_of_run_id,trigger_type,trigger_ref,idempotency_key,input,variables,output,policy_snapshot,usage,state,wait_reason,failure_code,failure_message,version,created_by_type,created_by_ref,created_at,updated_at,started_at,completed_at`

func scanRun(row scannable) (*controlmodel.OrchestrationRun, error) {
	v := &controlmodel.OrchestrationRun{}
	var triggerRef, idem, wait, code, msg, actorRef *string
	err := row.Scan(&v.ID, &v.Tenant, &v.Namespace, &v.RootIssueID, &v.Mode, &v.DefinitionRevisionID, &v.ParentRunID, &v.ParentNodeID, &v.RerunOfRunID, &v.TriggerType, &triggerRef, &idem, &v.Input, &v.Variables, &v.Output, &v.PolicySnapshot, &v.Usage, &v.State, &wait, &code, &msg, &v.Version, &v.CreatedBy.Type, &actorRef, &v.CreatedAt, &v.UpdatedAt, &v.StartedAt, &v.CompletedAt)
	if err != nil {
		return nil, orchNotFound(err)
	}
	v.TriggerRef = deref(triggerRef)
	v.IdempotencyKey = deref(idem)
	v.WaitReason = deref(wait)
	v.FailureCode = deref(code)
	v.FailureMessage = deref(msg)
	v.CreatedBy.Ref = deref(actorRef)
	return v, nil
}
func (r *orchestrationRepo) CreateRun(ctx context.Context, in *controlmodel.OrchestrationRun) (*controlmodel.OrchestrationRun, error) {
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	if in.State == "" {
		in.State = controlmodel.RunPlanned
	}
	v, err := scanRun(r.pool.QueryRow(ctx, `INSERT INTO orchestration_runs(id,tenant,namespace,root_issue_id,mode,definition_revision_id,parent_run_id,parent_node_id,rerun_of_run_id,trigger_type,trigger_ref,idempotency_key,input,variables,output,policy_snapshot,usage,state,wait_reason,failure_code,failure_message,version,created_by_type,created_by_ref,started_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,1,$22,$23,CASE WHEN $18='running' THEN now() END) ON CONFLICT(tenant,namespace,idempotency_key) WHERE idempotency_key IS NOT NULL DO UPDATE SET idempotency_key=EXCLUDED.idempotency_key RETURNING `+runCols, in.ID, in.Tenant, in.Namespace, in.RootIssueID, in.Mode, in.DefinitionRevisionID, in.ParentRunID, in.ParentNodeID, in.RerunOfRunID, in.TriggerType, nullStr(in.TriggerRef), nullStr(in.IdempotencyKey), nullJSON(in.Input), nullJSON(in.Variables), nullJSON(in.Output), nullJSON(in.PolicySnapshot), nullJSON(in.Usage), in.State, nullStr(in.WaitReason), nullStr(in.FailureCode), nullStr(in.FailureMessage), in.CreatedBy.Type, nullStr(in.CreatedBy.Ref)))
	return v, err
}
func (r *orchestrationRepo) GetRun(ctx context.Context, id uuid.UUID) (*controlmodel.OrchestrationRun, error) {
	return scanRun(r.pool.QueryRow(ctx, `SELECT `+runCols+` FROM orchestration_runs WHERE id=$1`, id))
}
func (r *orchestrationRepo) ListRuns(ctx context.Context, f store.OrchestrationRunFilter) ([]*controlmodel.OrchestrationRun, error) {
	limit := f.Limit
	if limit <= 0 {
		limit = 100
	}
	order := "DESC"
	if f.OldestFirst {
		order = "ASC"
	}
	rows, err := r.pool.Query(ctx, `SELECT `+runCols+` FROM orchestration_runs r WHERE ($1='' OR r.tenant=$1) AND ($2='' OR r.namespace=$2) AND ($3::uuid='00000000-0000-0000-0000-000000000000' OR r.root_issue_id=$3) AND ($4='' OR r.state=$4) AND (NOT $5 OR r.state NOT IN('succeeded','partial_succeeded','failed','cancelled')) AND ($6::uuid='00000000-0000-0000-0000-000000000000' OR r.root_issue_id=$6 OR EXISTS (SELECT 1 FROM agent_tasks t WHERE t.orchestration_run_id=r.id AND t.issue_id=$6)) AND ($8::uuid='00000000-0000-0000-0000-000000000000' OR EXISTS (SELECT 1 FROM orchestration_revisions rev WHERE rev.id=r.definition_revision_id AND rev.definition_id=$8)) AND ($10::uuid='00000000-0000-0000-0000-000000000000' OR r.parent_node_id=$10) AND (NOT $11 OR issue_access_allowed(r.root_issue_id,$12::text[])) ORDER BY r.created_at `+order+`,r.id LIMIT $7 OFFSET $9`, f.Tenant, f.Namespace, f.RootIssueID, f.State, f.ActiveOnly, f.IssueID, limit, f.DefinitionID, max(0, f.Offset), f.ParentNodeID, store.WorkAccessFrom(ctx).Restricted, store.WorkAccessFrom(ctx).Refs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*controlmodel.OrchestrationRun{}
	for rows.Next() {
		v, e := scanRun(rows)
		if e != nil {
			return nil, e
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
func (r *orchestrationRepo) TransitionRun(ctx context.Context, id uuid.UUID, expected int64, to controlmodel.OrchestrationRunState, output json.RawMessage, code, message string) (*controlmodel.OrchestrationRun, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	current, err := scanRun(tx.QueryRow(ctx, `SELECT `+runCols+` FROM orchestration_runs WHERE id=$1 FOR UPDATE`, id))
	if err != nil {
		return nil, err
	}
	if current.Version != expected || !controlmodel.CanTransitionOrchestrationRun(current.State, to) {
		return nil, store.ErrConflict
	}
	v, err := scanRun(tx.QueryRow(ctx, `UPDATE orchestration_runs SET state=$3,output=COALESCE($4,output),
		wait_reason=CASE WHEN $3='waiting' THEN $5 ELSE NULL END,
		failure_code=CASE WHEN $3='waiting' THEN NULL ELSE $5 END,
		failure_message=CASE WHEN $3='waiting' THEN NULL ELSE $6 END,
		version=version+1,updated_at=now(),started_at=CASE WHEN $3='running' THEN COALESCE(started_at,now()) ELSE started_at END,
		completed_at=CASE WHEN $3 IN('succeeded','partial_succeeded','failed','cancelled') THEN now() ELSE completed_at END
		WHERE id=$1 AND version=$2 RETURNING `+runCols, id, expected, to, nullJSON(output), nullStr(code), nullStr(message)))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	if err != nil {
		return nil, err
	}
	if controlmodel.IsOrchestrationRunTerminal(to) {
		payload, _ := json.Marshal(map[string]any{"output": v.Output, "failureCode": v.FailureCode, "failureMessage": v.FailureMessage})
		if err := appendRunEventTx(ctx, tx, &controlmodel.RunEvent{RunID: v.ID, Tenant: v.Tenant, Namespace: v.Namespace, Type: "run." + string(to), Actor: controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "orchestration"}, Payload: payload, IdempotencyKey: "run-" + string(to) + ":" + v.ID.String()}); err != nil {
			return nil, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return v, nil
}

const nodeCols = `id,run_id,tenant,namespace,node_key,definition_node_key,type,role,issue_id,state,config,input,output,iteration,wait_reason,failure_code,failure_message,version,created_at,updated_at,started_at,completed_at`

func scanNode(row scannable) (*controlmodel.RunNode, error) {
	v := &controlmodel.RunNode{}
	var defKey, role, wait, code, msg *string
	err := row.Scan(&v.ID, &v.RunID, &v.Tenant, &v.Namespace, &v.NodeKey, &defKey, &v.Type, &role, &v.IssueID, &v.State, &v.Config, &v.Input, &v.Output, &v.Iteration, &wait, &code, &msg, &v.Version, &v.CreatedAt, &v.UpdatedAt, &v.StartedAt, &v.CompletedAt)
	if err != nil {
		return nil, orchNotFound(err)
	}
	v.DefinitionNodeKey = deref(defKey)
	v.Role = deref(role)
	v.WaitReason = deref(wait)
	v.FailureCode = deref(code)
	v.FailureMessage = deref(msg)
	return v, nil
}
func (r *orchestrationRepo) CreateNode(ctx context.Context, in *controlmodel.RunNode) (*controlmodel.RunNode, error) {
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	if in.State == "" {
		in.State = controlmodel.RunNodePending
	}
	if in.Iteration == 0 {
		in.Iteration = 1
	}
	return scanNode(r.pool.QueryRow(ctx, `INSERT INTO orchestration_run_nodes(id,run_id,tenant,namespace,node_key,definition_node_key,type,role,issue_id,state,config,input,output,iteration,wait_reason,failure_code,failure_message,version) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,1) RETURNING `+nodeCols, in.ID, in.RunID, in.Tenant, in.Namespace, in.NodeKey, nullStr(in.DefinitionNodeKey), in.Type, nullStr(in.Role), in.IssueID, in.State, nullJSON(in.Config), nullJSON(in.Input), nullJSON(in.Output), in.Iteration, nullStr(in.WaitReason), nullStr(in.FailureCode), nullStr(in.FailureMessage)))
}
func (r *orchestrationRepo) GetNode(ctx context.Context, id uuid.UUID) (*controlmodel.RunNode, error) {
	return scanNode(r.pool.QueryRow(ctx, `SELECT `+nodeCols+` FROM orchestration_run_nodes WHERE id=$1`, id))
}
func (r *orchestrationRepo) ListNodes(ctx context.Context, runID uuid.UUID) ([]*controlmodel.RunNode, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+nodeCols+` FROM orchestration_run_nodes WHERE run_id=$1 ORDER BY node_key,iteration`, runID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*controlmodel.RunNode{}
	for rows.Next() {
		v, e := scanNode(rows)
		if e != nil {
			return nil, e
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
func (r *orchestrationRepo) TransitionNode(ctx context.Context, id uuid.UUID, expected int64, to controlmodel.RunNodeState, output json.RawMessage, code, message string) (*controlmodel.RunNode, error) {
	current, err := r.GetNode(ctx, id)
	if err != nil {
		return nil, err
	}
	if current.Version != expected || !controlmodel.CanTransitionRunNode(current.State, to) {
		return nil, store.ErrConflict
	}
	v, err := scanNode(r.pool.QueryRow(ctx, `UPDATE orchestration_run_nodes SET state=$3,output=COALESCE($4,output),
		wait_reason=CASE WHEN $3='waiting' THEN $5 ELSE NULL END,
		failure_code=CASE WHEN $3='waiting' THEN NULL ELSE $5 END,
		failure_message=CASE WHEN $3='waiting' THEN NULL ELSE $6 END,
		version=version+1,updated_at=now(),started_at=CASE WHEN $3 IN('running','waiting') THEN COALESCE(started_at,now()) ELSE started_at END,
		completed_at=CASE WHEN $3 IN('succeeded','skipped','failed','cancelled') THEN now() ELSE completed_at END
		WHERE id=$1 AND version=$2 RETURNING `+nodeCols, id, expected, to, nullJSON(output), nullStr(code), nullStr(message)))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return v, err
}

const edgeCols = `id,run_id,tenant,namespace,from_node_id,to_node_id,on_states,condition,ordinal,metadata,created_at`

func scanEdge(row scannable) (*controlmodel.RunEdge, error) {
	v := &controlmodel.RunEdge{}
	var states []byte
	var condition *string
	err := row.Scan(&v.ID, &v.RunID, &v.Tenant, &v.Namespace, &v.FromNodeID, &v.ToNodeID, &states, &condition, &v.Ordinal, &v.Metadata, &v.CreatedAt)
	if err != nil {
		return nil, orchNotFound(err)
	}
	if err = json.Unmarshal(states, &v.OnStates); err != nil {
		return nil, err
	}
	v.Condition = deref(condition)
	return v, nil
}
func (r *orchestrationRepo) CreateEdges(ctx context.Context, edges []*controlmodel.RunEdge) error {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	for _, e := range edges {
		if e.ID == uuid.Nil {
			e.ID = uuid.New()
		}
		states, _ := json.Marshal(e.OnStates)
		if _, err = tx.Exec(ctx, `INSERT INTO orchestration_run_edges(id,run_id,tenant,namespace,from_node_id,to_node_id,on_states,condition,ordinal,metadata) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`, e.ID, e.RunID, e.Tenant, e.Namespace, e.FromNodeID, e.ToNodeID, states, nullStr(e.Condition), e.Ordinal, nullJSON(e.Metadata)); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}
func (r *orchestrationRepo) ListEdges(ctx context.Context, runID uuid.UUID) ([]*controlmodel.RunEdge, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+edgeCols+` FROM orchestration_run_edges WHERE run_id=$1 ORDER BY ordinal,id`, runID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*controlmodel.RunEdge{}
	for rows.Next() {
		v, e := scanEdge(rows)
		if e != nil {
			return nil, e
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
func (r *orchestrationRepo) PutTeamSnapshot(ctx context.Context, in *controlmodel.RunTeamSnapshot) (*controlmodel.RunTeamSnapshot, error) {
	v := &controlmodel.RunTeamSnapshot{}
	err := r.pool.QueryRow(ctx, `INSERT INTO orchestration_run_team_snapshots(run_id,team_id,tenant,namespace,snapshot) VALUES($1,$2,$3,$4,$5) ON CONFLICT(run_id,team_id) DO UPDATE SET snapshot=orchestration_run_team_snapshots.snapshot RETURNING run_id,team_id,tenant,namespace,snapshot,created_at`, in.RunID, in.TeamID, in.Tenant, in.Namespace, in.Snapshot).Scan(&v.RunID, &v.TeamID, &v.Tenant, &v.Namespace, &v.Snapshot, &v.CreatedAt)
	return v, err
}
func (r *orchestrationRepo) ListTeamSnapshots(ctx context.Context, runID uuid.UUID) ([]*controlmodel.RunTeamSnapshot, error) {
	rows, err := r.pool.Query(ctx, `SELECT run_id,team_id,tenant,namespace,snapshot,created_at FROM orchestration_run_team_snapshots WHERE run_id=$1`, runID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*controlmodel.RunTeamSnapshot{}
	for rows.Next() {
		v := &controlmodel.RunTeamSnapshot{}
		if e := rows.Scan(&v.RunID, &v.TeamID, &v.Tenant, &v.Namespace, &v.Snapshot, &v.CreatedAt); e != nil {
			return nil, e
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

const eventCols = `id,run_id,tenant,namespace,sequence,node_id,agent_task_id,attempt_id,type,actor_type,actor_ref,payload,causation_id,correlation_id,idempotency_key,occurred_at`

func scanRunEvent(row scannable) (*controlmodel.RunEvent, error) {
	v := &controlmodel.RunEvent{}
	var ref, cause, corr, idem *string
	err := row.Scan(&v.ID, &v.RunID, &v.Tenant, &v.Namespace, &v.Sequence, &v.NodeID, &v.AgentTaskID, &v.AttemptID, &v.Type, &v.Actor.Type, &ref, &v.Payload, &cause, &corr, &idem, &v.OccurredAt)
	if err != nil {
		return nil, orchNotFound(err)
	}
	v.Actor.Ref = deref(ref)
	v.CausationID = deref(cause)
	v.CorrelationID = deref(corr)
	v.IdempotencyKey = deref(idem)
	return v, nil
}
func (r *orchestrationRepo) AppendRunEvent(ctx context.Context, in *controlmodel.RunEvent) (*controlmodel.RunEvent, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if in.IdempotencyKey != "" {
		if v, e := scanRunEvent(tx.QueryRow(ctx, `SELECT `+eventCols+` FROM orchestration_run_events WHERE run_id=$1 AND idempotency_key=$2`, in.RunID, in.IdempotencyKey)); e == nil {
			return v, nil
		} else if !errors.Is(e, store.ErrNotFound) {
			return nil, e
		}
	}
	if _, err = tx.Exec(ctx, `SELECT id FROM orchestration_runs WHERE id=$1 FOR UPDATE`, in.RunID); err != nil {
		return nil, err
	}
	if err = tx.QueryRow(ctx, `SELECT COALESCE(MAX(sequence),0)+1 FROM orchestration_run_events WHERE run_id=$1`, in.RunID).Scan(&in.Sequence); err != nil {
		return nil, err
	}
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	v, err := scanRunEvent(tx.QueryRow(ctx, `INSERT INTO orchestration_run_events(id,run_id,tenant,namespace,sequence,node_id,agent_task_id,attempt_id,type,actor_type,actor_ref,payload,causation_id,correlation_id,idempotency_key) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15) RETURNING `+eventCols, in.ID, in.RunID, in.Tenant, in.Namespace, in.Sequence, in.NodeID, in.AgentTaskID, in.AttemptID, in.Type, in.Actor.Type, nullStr(in.Actor.Ref), nullJSON(in.Payload), nullStr(in.CausationID), nullStr(in.CorrelationID), nullStr(in.IdempotencyKey)))
	if err != nil {
		return nil, err
	}
	if _, err = tx.Exec(ctx, "SELECT pg_notify('"+runEventNotifyChannel+"', $1)", in.RunID.String()); err != nil {
		return nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	return v, nil
}
func (r *orchestrationRepo) ListRunEvents(ctx context.Context, runID uuid.UUID, after int64, limit int) ([]*controlmodel.RunEvent, error) {
	if limit <= 0 {
		limit = 200
	}
	rows, err := r.pool.Query(ctx, `SELECT `+eventCols+` FROM orchestration_run_events WHERE run_id=$1 AND sequence>$2 ORDER BY sequence LIMIT $3`, runID, after, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*controlmodel.RunEvent{}
	for rows.Next() {
		v, e := scanRunEvent(rows)
		if e != nil {
			return nil, e
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
func (r *orchestrationRepo) WaitForRunEvent(ctx context.Context, runID uuid.UUID, after int64) error {
	conn, err := r.pool.Acquire(ctx)
	if err != nil {
		return fmt.Errorf("postgres run events acquire listener: %w", err)
	}
	defer conn.Release()
	if _, err := conn.Exec(ctx, "LISTEN "+runEventNotifyChannel); err != nil {
		return fmt.Errorf("postgres run events listen: %w", err)
	}
	defer func() {
		cleanupCtx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		_, _ = conn.Exec(cleanupCtx, "UNLISTEN "+runEventNotifyChannel)
	}()

	var available bool
	if err := conn.QueryRow(ctx,
		"SELECT EXISTS (SELECT 1 FROM orchestration_run_events WHERE run_id=$1 AND sequence>$2)",
		runID, after).Scan(&available); err != nil {
		return fmt.Errorf("postgres run events check listener cursor: %w", err)
	}
	if available {
		return nil
	}
	for {
		notification, err := conn.Conn().WaitForNotification(ctx)
		if err != nil {
			return err
		}
		if notification.Payload == runID.String() {
			return nil
		}
	}
}

const policyCols = `id,tenant,namespace,agent_id,candidates,selection_mode,fallback_mode,max_concurrency,queue_timeout_seconds,attempt_timeout_seconds,retry_policy,version,created_at,updated_at,archived_at`

func scanPolicy(row scannable) (*controlmodel.AgentRuntimePolicy, error) {
	v := &controlmodel.AgentRuntimePolicy{}
	var candidates []byte
	err := row.Scan(&v.ID, &v.Tenant, &v.Namespace, &v.AgentRef, &candidates, &v.SelectionMode, &v.FallbackMode, &v.MaxConcurrency, &v.QueueTimeoutSeconds, &v.AttemptTimeoutSeconds, &v.RetryPolicy, &v.Version, &v.CreatedAt, &v.UpdatedAt, &v.ArchivedAt)
	if err != nil {
		return nil, orchNotFound(err)
	}
	if err = json.Unmarshal(candidates, &v.Candidates); err != nil {
		return nil, err
	}
	return v, nil
}
func (r *orchestrationRepo) PutRuntimePolicy(ctx context.Context, in *controlmodel.AgentRuntimePolicy) (*controlmodel.AgentRuntimePolicy, error) {
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	if in.SelectionMode == "" {
		in.SelectionMode = "ordered"
	}
	if in.FallbackMode == "" {
		in.FallbackMode = "disabled"
	}
	candidates, err := json.Marshal(in.Candidates)
	if err != nil {
		return nil, err
	}
	v, err := scanPolicy(r.pool.QueryRow(ctx, `INSERT INTO agent_runtime_policies(id,tenant,namespace,agent_id,candidates,selection_mode,fallback_mode,max_concurrency,queue_timeout_seconds,attempt_timeout_seconds,retry_policy,version) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,1) ON CONFLICT(tenant,namespace,agent_id) WHERE archived_at IS NULL DO UPDATE SET candidates=EXCLUDED.candidates,selection_mode=EXCLUDED.selection_mode,fallback_mode=EXCLUDED.fallback_mode,max_concurrency=EXCLUDED.max_concurrency,queue_timeout_seconds=EXCLUDED.queue_timeout_seconds,attempt_timeout_seconds=EXCLUDED.attempt_timeout_seconds,retry_policy=EXCLUDED.retry_policy,version=agent_runtime_policies.version+1,updated_at=now(),archived_at=EXCLUDED.archived_at WHERE $12=0 OR agent_runtime_policies.version=$12 RETURNING `+policyCols, in.ID, in.Tenant, in.Namespace, in.AgentRef, candidates, in.SelectionMode, in.FallbackMode, in.MaxConcurrency, in.QueueTimeoutSeconds, in.AttemptTimeoutSeconds, nullJSON(in.RetryPolicy), in.Version))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return v, err
}
func (r *orchestrationRepo) GetRuntimePolicy(ctx context.Context, tenant, namespace, agentRef string) (*controlmodel.AgentRuntimePolicy, error) {
	return scanPolicy(r.pool.QueryRow(ctx, `SELECT `+policyCols+` FROM agent_runtime_policies WHERE tenant=$1 AND namespace=$2 AND agent_id=$3 AND archived_at IS NULL`, tenant, namespace, agentRef))
}
func (r *orchestrationRepo) ListRuntimePolicies(ctx context.Context, tenant, namespace string, limit int) ([]*controlmodel.AgentRuntimePolicy, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `SELECT `+policyCols+` FROM agent_runtime_policies WHERE ($1='' OR tenant=$1) AND ($2='' OR namespace=$2) AND archived_at IS NULL ORDER BY agent_id LIMIT $3`, tenant, namespace, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*controlmodel.AgentRuntimePolicy{}
	for rows.Next() {
		v, e := scanPolicy(rows)
		if e != nil {
			return nil, e
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

var _ store.OrchestrationRepository = (*orchestrationRepo)(nil)
var _ = fmt.Sprintf
var _ = time.Second

func (r *orchestrationRepo) SetNodeInput(ctx context.Context, id uuid.UUID, expected int64, input json.RawMessage) (*controlmodel.RunNode, error) {
	node, err := scanNode(r.pool.QueryRow(ctx, `UPDATE orchestration_run_nodes SET input=$3,version=version+1,updated_at=now() WHERE id=$1 AND version=$2 RETURNING `+nodeCols, id, expected, nullJSON(input)))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return node, err
}

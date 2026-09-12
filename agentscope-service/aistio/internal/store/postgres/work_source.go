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
	"errors"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type workSourceRepo struct{ pool *pgxpool.Pool }

const workSourceCols = `id,tenant,namespace,kind,name,configuration,enabled,version,created_at,updated_at,archived_at`

func workSourceError(err error) error {
	if errors.Is(err, pgx.ErrNoRows) {
		return store.ErrNotFound
	}
	return err
}

func scanWorkSource(row scannable) (*controlmodel.WorkSource, error) {
	v := &controlmodel.WorkSource{}
	if err := row.Scan(&v.ID, &v.Tenant, &v.Namespace, &v.Kind, &v.Name, &v.Configuration, &v.Enabled, &v.Version, &v.CreatedAt, &v.UpdatedAt, &v.ArchivedAt); err != nil {
		return nil, workSourceError(err)
	}
	return v, nil
}

func (r *workSourceRepo) CreateWorkSource(ctx context.Context, in *controlmodel.WorkSource) (*controlmodel.WorkSource, error) {
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	v, err := scanWorkSource(r.pool.QueryRow(ctx, `INSERT INTO work_sources(id,tenant,namespace,kind,name,configuration,enabled) VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING `+workSourceCols, in.ID, in.Tenant, in.Namespace, in.Kind, in.Name, nullJSON(in.Configuration), in.Enabled))
	return v, catalogConflict(err)
}
func (r *workSourceRepo) GetWorkSource(ctx context.Context, id uuid.UUID) (*controlmodel.WorkSource, error) {
	return scanWorkSource(r.pool.QueryRow(ctx, `SELECT `+workSourceCols+` FROM work_sources WHERE id=$1`, id))
}
func (r *workSourceRepo) ListWorkSources(ctx context.Context, tenant, namespace string) ([]*controlmodel.WorkSource, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+workSourceCols+` FROM work_sources WHERE ($1='' OR tenant=$1) AND ($2='' OR namespace=$2) AND archived_at IS NULL ORDER BY updated_at DESC`, tenant, namespace)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*controlmodel.WorkSource{}
	for rows.Next() {
		v, e := scanWorkSource(rows)
		if e != nil {
			return nil, e
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
func (r *workSourceRepo) UpdateWorkSource(ctx context.Context, in *controlmodel.WorkSource, expected int64) (*controlmodel.WorkSource, error) {
	v, err := scanWorkSource(r.pool.QueryRow(ctx, `UPDATE work_sources SET name=$3,configuration=$4,enabled=$5,archived_at=$6,version=version+1,updated_at=now() WHERE id=$1 AND version=$2 RETURNING `+workSourceCols, in.ID, expected, in.Name, nullJSON(in.Configuration), in.Enabled, in.ArchivedAt))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return v, err
}

func scanDelivery(row scannable) (*controlmodel.WebhookDelivery, error) {
	v := &controlmodel.WebhookDelivery{}
	var lastError *string
	err := row.Scan(&v.ID, &v.WorkSourceID, &v.DeliveryID, &v.PayloadHash, &v.Status, &v.Attempts, &lastError, &v.ReceivedAt, &v.ProcessedAt)
	if err != nil {
		return nil, workSourceError(err)
	}
	if lastError != nil {
		v.LastError = *lastError
	}
	return v, nil
}
func (r *workSourceRepo) BeginWebhookDelivery(ctx context.Context, sourceID uuid.UUID, deliveryID, payloadHash string) (*controlmodel.WebhookDelivery, bool, error) {
	id := uuid.New()
	v, err := scanDelivery(r.pool.QueryRow(ctx, `INSERT INTO webhook_deliveries(id,work_source_id,delivery_id,payload_hash,status) VALUES($1,$2,$3,$4,'processing') ON CONFLICT(work_source_id,delivery_id) DO UPDATE SET status='processing',attempts=webhook_deliveries.attempts+1,last_error=NULL,processed_at=NULL WHERE webhook_deliveries.status='failed' AND webhook_deliveries.payload_hash=EXCLUDED.payload_hash RETURNING id,work_source_id,delivery_id,payload_hash,status,attempts,last_error,received_at,processed_at`, id, sourceID, deliveryID, payloadHash))
	if err == nil {
		return v, true, nil
	}
	if !errors.Is(err, store.ErrNotFound) {
		return nil, false, err
	}
	v, err = scanDelivery(r.pool.QueryRow(ctx, `SELECT id,work_source_id,delivery_id,payload_hash,status,attempts,last_error,received_at,processed_at FROM webhook_deliveries WHERE work_source_id=$1 AND delivery_id=$2`, sourceID, deliveryID))
	if err == nil && v.PayloadHash != payloadHash {
		return nil, false, store.ErrConflict
	}
	return v, false, err
}
func (r *workSourceRepo) CompleteWebhookDelivery(ctx context.Context, id uuid.UUID) error {
	ct, err := r.pool.Exec(ctx, `UPDATE webhook_deliveries SET status='processed',last_error=NULL,processed_at=now() WHERE id=$1`, id)
	if err != nil {
		return err
	}
	if ct.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	return nil
}
func (r *workSourceRepo) FailWebhookDelivery(ctx context.Context, id uuid.UUID, message string) error {
	ct, err := r.pool.Exec(ctx, `UPDATE webhook_deliveries SET status='failed',last_error=$2,processed_at=now() WHERE id=$1`, id, message)
	if err != nil {
		return err
	}
	if ct.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	return nil
}

func scanIssueExternalRef(row scannable) (*controlmodel.IssueExternalRef, error) {
	v := &controlmodel.IssueExternalRef{}
	var externalNumber, externalURL, externalVersion *string
	err := row.Scan(&v.WorkSourceID, &v.IssueID, &v.ExternalID, &externalNumber, &externalURL, &externalVersion, &v.Projection, &v.UpdatedAt)
	if err != nil {
		return nil, workSourceError(err)
	}
	if externalNumber != nil {
		v.ExternalNumber = *externalNumber
	}
	if externalURL != nil {
		v.ExternalURL = *externalURL
	}
	if externalVersion != nil {
		v.ExternalVersion = *externalVersion
	}
	return v, nil
}
func (r *workSourceRepo) PutIssueExternalRef(ctx context.Context, in *controlmodel.IssueExternalRef) (*controlmodel.IssueExternalRef, error) {
	return scanIssueExternalRef(r.pool.QueryRow(ctx, `INSERT INTO issue_external_refs(work_source_id,issue_id,external_id,external_number,external_url,external_version,projection) VALUES($1,$2,$3,$4,$5,$6,$7) ON CONFLICT(work_source_id,external_id) DO UPDATE SET issue_id=EXCLUDED.issue_id,external_number=EXCLUDED.external_number,external_url=EXCLUDED.external_url,external_version=EXCLUDED.external_version,projection=EXCLUDED.projection,updated_at=now() RETURNING work_source_id,issue_id,external_id,external_number,external_url,external_version,projection,updated_at`, in.WorkSourceID, in.IssueID, in.ExternalID, nullStr(in.ExternalNumber), nullStr(in.ExternalURL), nullStr(in.ExternalVersion), nullJSON(in.Projection)))
}
func (r *workSourceRepo) GetIssueExternalRef(ctx context.Context, sourceID uuid.UUID, externalID string) (*controlmodel.IssueExternalRef, error) {
	return scanIssueExternalRef(r.pool.QueryRow(ctx, `SELECT work_source_id,issue_id,external_id,external_number,external_url,external_version,projection,updated_at FROM issue_external_refs WHERE work_source_id=$1 AND external_id=$2`, sourceID, externalID))
}
func (r *workSourceRepo) GetIssueExternalRefByIssue(ctx context.Context, sourceID, issueID uuid.UUID) (*controlmodel.IssueExternalRef, error) {
	return scanIssueExternalRef(r.pool.QueryRow(ctx, `SELECT work_source_id,issue_id,external_id,external_number,external_url,external_version,projection,updated_at FROM issue_external_refs WHERE work_source_id=$1 AND issue_id=$2`, sourceID, issueID))
}
func (r *workSourceRepo) ListIssueExternalRefs(ctx context.Context, sourceID uuid.UUID, limit int) ([]*controlmodel.IssueExternalRef, error) {
	if limit <= 0 || limit > 1000 {
		limit = 500
	}
	rows, err := r.pool.Query(ctx, `SELECT work_source_id,issue_id,external_id,external_number,external_url,external_version,projection,updated_at FROM issue_external_refs WHERE work_source_id=$1 ORDER BY updated_at LIMIT $2`, sourceID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.IssueExternalRef, 0)
	for rows.Next() {
		ref, scanErr := scanIssueExternalRef(rows)
		if scanErr != nil {
			return nil, scanErr
		}
		out = append(out, ref)
	}
	return out, rows.Err()
}
func (r *workSourceRepo) PutCommentExternalRef(ctx context.Context, in *controlmodel.CommentExternalRef) (*controlmodel.CommentExternalRef, error) {
	v := &controlmodel.CommentExternalRef{}
	var externalID, externalVersion, lastError *string
	err := r.pool.QueryRow(ctx, `INSERT INTO comment_external_refs(work_source_id,comment_id,external_id,external_version,sync_state,last_error,attempts) VALUES($1,$2,$3,$4,$5,$6,$7) ON CONFLICT(work_source_id,comment_id) DO UPDATE SET external_id=EXCLUDED.external_id,external_version=EXCLUDED.external_version,sync_state=EXCLUDED.sync_state,last_error=EXCLUDED.last_error,attempts=EXCLUDED.attempts,updated_at=now() RETURNING work_source_id,comment_id,external_id,external_version,sync_state,last_error,attempts,updated_at`, in.WorkSourceID, in.CommentID, nullStr(in.ExternalID), nullStr(in.ExternalVersion), in.SyncState, nullStr(in.LastError), in.Attempts).Scan(&v.WorkSourceID, &v.CommentID, &externalID, &externalVersion, &v.SyncState, &lastError, &v.Attempts, &v.UpdatedAt)
	if externalID != nil {
		v.ExternalID = *externalID
	}
	if externalVersion != nil {
		v.ExternalVersion = *externalVersion
	}
	if lastError != nil {
		v.LastError = *lastError
	}
	return v, err
}
func scanCommentExternalRef(row scannable) (*controlmodel.CommentExternalRef, error) {
	v := &controlmodel.CommentExternalRef{}
	var externalID, externalVersion, lastError *string
	err := row.Scan(&v.WorkSourceID, &v.CommentID, &externalID, &externalVersion, &v.SyncState, &lastError, &v.Attempts, &v.UpdatedAt)
	if err != nil {
		return nil, workSourceError(err)
	}
	if externalID != nil {
		v.ExternalID = *externalID
	}
	if externalVersion != nil {
		v.ExternalVersion = *externalVersion
	}
	if lastError != nil {
		v.LastError = *lastError
	}
	return v, nil
}
func (r *workSourceRepo) GetCommentExternalRef(ctx context.Context, sourceID, commentID uuid.UUID) (*controlmodel.CommentExternalRef, error) {
	return scanCommentExternalRef(r.pool.QueryRow(ctx, `SELECT work_source_id,comment_id,external_id,external_version,sync_state,last_error,attempts,updated_at FROM comment_external_refs WHERE work_source_id=$1 AND comment_id=$2`, sourceID, commentID))
}
func (r *workSourceRepo) GetCommentExternalRefByExternalID(ctx context.Context, sourceID uuid.UUID, externalID string) (*controlmodel.CommentExternalRef, error) {
	return scanCommentExternalRef(r.pool.QueryRow(ctx, `SELECT work_source_id,comment_id,external_id,external_version,sync_state,last_error,attempts,updated_at FROM comment_external_refs WHERE work_source_id=$1 AND external_id=$2`, sourceID, externalID))
}
func (r *workSourceRepo) ListCommentExternalRefs(ctx context.Context, states []controlmodel.CommentSyncState, limit int) ([]*controlmodel.CommentExternalRef, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	stateNames := make([]string, len(states))
	for i := range states {
		stateNames[i] = string(states[i])
	}
	rows, err := r.pool.Query(ctx, `SELECT work_source_id,comment_id,external_id,external_version,sync_state,last_error,attempts,updated_at FROM comment_external_refs WHERE (cardinality($1::text[])=0 OR sync_state=ANY($1::text[])) ORDER BY updated_at LIMIT $2`, stateNames, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.CommentExternalRef, 0)
	for rows.Next() {
		v, scanErr := scanCommentExternalRef(rows)
		if scanErr != nil {
			return nil, scanErr
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
func (r *workSourceRepo) ListExternalLinks(ctx context.Context, issueID uuid.UUID) ([]*controlmodel.ExternalLink, error) {
	rows, err := r.pool.Query(ctx, `SELECT id,work_source_id,issue_id,type,external_id,url,title,metadata,created_at,updated_at FROM external_links WHERE issue_id=$1 ORDER BY created_at`, issueID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*controlmodel.ExternalLink{}
	for rows.Next() {
		v := &controlmodel.ExternalLink{}
		var externalID, title *string
		if err = rows.Scan(&v.ID, &v.WorkSourceID, &v.IssueID, &v.Type, &externalID, &v.URL, &title, &v.Metadata, &v.CreatedAt, &v.UpdatedAt); err != nil {
			return nil, err
		}
		if externalID != nil {
			v.ExternalID = *externalID
		}
		if title != nil {
			v.Title = *title
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
func (r *workSourceRepo) PutExternalLink(ctx context.Context, in *controlmodel.ExternalLink) (*controlmodel.ExternalLink, error) {
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	v := &controlmodel.ExternalLink{}
	var externalID, title *string
	err := r.pool.QueryRow(ctx, `INSERT INTO external_links(id,work_source_id,issue_id,type,external_id,url,title,metadata) VALUES($1,$2,$3,$4,$5,$6,$7,$8) ON CONFLICT(work_source_id,issue_id,type,external_id) DO UPDATE SET url=EXCLUDED.url,title=EXCLUDED.title,metadata=EXCLUDED.metadata,updated_at=now() RETURNING id,work_source_id,issue_id,type,external_id,url,title,metadata,created_at,updated_at`, in.ID, in.WorkSourceID, in.IssueID, in.Type, nullStr(in.ExternalID), in.URL, nullStr(in.Title), nullJSON(in.Metadata)).Scan(&v.ID, &v.WorkSourceID, &v.IssueID, &v.Type, &externalID, &v.URL, &title, &v.Metadata, &v.CreatedAt, &v.UpdatedAt)
	if externalID != nil {
		v.ExternalID = *externalID
	}
	if title != nil {
		v.Title = *title
	}
	return v, err
}

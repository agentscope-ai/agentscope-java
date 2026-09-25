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
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type endpointRepo struct{ pool *pgxpool.Pool }

const endpointCols = `id,tenant,namespace,name,slug,description,target_type,target_ref,invocation_mode,
	input_schema,output_schema,event_schema_version,timeout_seconds,max_payload_bytes,
	auth_policy,rate_limit,active_release_id,active_release,status,version,created_at,updated_at,archived_at`

func scanEndpoint(row scannable) (*controlmodel.Endpoint, error) {
	v := &controlmodel.Endpoint{}
	if err := row.Scan(&v.ID, &v.Tenant, &v.Namespace, &v.Name, &v.Slug, &v.Description,
		&v.TargetType, &v.TargetRef, &v.InvocationMode, &v.InputSchema, &v.OutputSchema,
		&v.EventSchemaVersion, &v.TimeoutSeconds, &v.MaxPayloadBytes, &v.AuthPolicy, &v.RateLimit,
		&v.ActiveReleaseID, &v.ActiveRelease, &v.Status, &v.Version, &v.CreatedAt, &v.UpdatedAt, &v.ArchivedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	return v, nil
}

func (r *endpointRepo) Create(ctx context.Context, in *controlmodel.Endpoint) (*controlmodel.Endpoint, error) {
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	if in.Status == "" {
		in.Status = controlmodel.EndpointDraft
	}
	if in.EventSchemaVersion == "" {
		in.EventSchemaVersion = "v1"
	}
	if in.TimeoutSeconds <= 0 {
		in.TimeoutSeconds = 300
	}
	if in.MaxPayloadBytes <= 0 {
		in.MaxPayloadBytes = 1 << 20
	}
	v, err := scanEndpoint(r.pool.QueryRow(ctx, `INSERT INTO endpoints(
		id,tenant,namespace,name,slug,description,target_type,target_ref,invocation_mode,
		input_schema,output_schema,event_schema_version,timeout_seconds,max_payload_bytes,
		auth_policy,rate_limit,status)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17)
		RETURNING `+endpointCols, in.ID, in.Tenant, in.Namespace, in.Name, in.Slug, in.Description,
		in.TargetType, in.TargetRef, in.InvocationMode, nullJSON(in.InputSchema), nullJSON(in.OutputSchema),
		in.EventSchemaVersion, in.TimeoutSeconds, in.MaxPayloadBytes, in.AuthPolicy, nullJSON(in.RateLimit), in.Status))
	return v, catalogConflict(err)
}

func (r *endpointRepo) Get(ctx context.Context, id uuid.UUID) (*controlmodel.Endpoint, error) {
	return scanEndpoint(r.pool.QueryRow(ctx, `SELECT `+endpointCols+` FROM endpoints WHERE id=$1`, id))
}

func (r *endpointRepo) GetBySlug(ctx context.Context, slug string) (*controlmodel.Endpoint, error) {
	return scanEndpoint(r.pool.QueryRow(ctx, `SELECT `+endpointCols+` FROM endpoints WHERE slug=$1`, slug))
}

func (r *endpointRepo) List(ctx context.Context, tenant, namespace string) ([]*controlmodel.Endpoint, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+endpointCols+` FROM endpoints
		WHERE ($1='' OR tenant=$1) AND ($2='' OR namespace=$2) AND status<>'archived'
		ORDER BY updated_at DESC`, tenant, namespace)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*controlmodel.Endpoint{}
	for rows.Next() {
		v, scanErr := scanEndpoint(rows)
		if scanErr != nil {
			return nil, scanErr
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

func (r *endpointRepo) Update(ctx context.Context, in *controlmodel.Endpoint, expected int64) (*controlmodel.Endpoint, error) {
	v, err := scanEndpoint(r.pool.QueryRow(ctx, `UPDATE endpoints SET
		name=$3,slug=$4,description=$5,target_type=$6,target_ref=$7,invocation_mode=$8,
		input_schema=$9,output_schema=$10,event_schema_version=$11,timeout_seconds=$12,
		max_payload_bytes=$13,auth_policy=$14,rate_limit=$15,active_release_id=$16,
		active_release=$17,status=$18,archived_at=$19,
		version=version+1,updated_at=now()
		WHERE id=$1 AND version=$2 RETURNING `+endpointCols,
		in.ID, expected, in.Name, in.Slug, in.Description, in.TargetType, in.TargetRef, in.InvocationMode,
		nullJSON(in.InputSchema), nullJSON(in.OutputSchema), in.EventSchemaVersion, in.TimeoutSeconds,
		in.MaxPayloadBytes, in.AuthPolicy, nullJSON(in.RateLimit), in.ActiveReleaseID, in.ActiveRelease,
		in.Status, in.ArchivedAt))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return v, catalogConflict(err)
}

const endpointReleaseCols = `id,endpoint_id,release_number,target_type,target_ref,
	created_by_type,COALESCE(created_by_ref,''),reason,created_at,activated_at`

func scanEndpointRelease(row scannable) (*controlmodel.EndpointRelease, error) {
	v := &controlmodel.EndpointRelease{}
	if err := row.Scan(&v.ID, &v.EndpointID, &v.Number, &v.TargetType, &v.TargetRef,
		&v.CreatedBy.Type, &v.CreatedBy.Ref, &v.Reason, &v.CreatedAt, &v.ActivatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	return v, nil
}

func (r *endpointRepo) DeployRelease(ctx context.Context, endpointID uuid.UUID,
	targetType controlmodel.EndpointTargetType, targetRef uuid.UUID, expected int64,
	actor controlmodel.Actor, reason string) (*controlmodel.Endpoint, *controlmodel.EndpointRelease, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var lockedID uuid.UUID
	if err = tx.QueryRow(ctx, `SELECT id FROM endpoints
		WHERE id=$1 AND version=$2 AND target_type=$3 AND status<>'archived' FOR UPDATE`,
		endpointID, expected, targetType).Scan(&lockedID); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, nil, store.ErrConflict
		}
		return nil, nil, err
	}
	var number int
	if err = tx.QueryRow(ctx, `SELECT COALESCE(MAX(release_number),0)+1
		FROM endpoint_releases WHERE endpoint_id=$1`, endpointID).Scan(&number); err != nil {
		return nil, nil, err
	}
	releaseID := uuid.New()
	release, err := scanEndpointRelease(tx.QueryRow(ctx, `INSERT INTO endpoint_releases(
		id,endpoint_id,release_number,target_type,target_ref,created_by_type,created_by_ref,reason)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8) RETURNING `+endpointReleaseCols,
		releaseID, endpointID, number, targetType, targetRef, actor.Type, nullStr(actor.Ref), reason))
	if err != nil {
		return nil, nil, catalogConflict(err)
	}
	endpoint, err := scanEndpoint(tx.QueryRow(ctx, `UPDATE endpoints SET target_ref=$3,
		active_release_id=$4,active_release=$5,version=version+1,updated_at=now()
		WHERE id=$1 AND version=$2 RETURNING `+endpointCols,
		endpointID, expected, targetRef, releaseID, number))
	if err != nil {
		return nil, nil, catalogConflict(err)
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, nil, err
	}
	return endpoint, release, nil
}

func (r *endpointRepo) GetRelease(ctx context.Context, endpointID, releaseID uuid.UUID) (*controlmodel.EndpointRelease, error) {
	return scanEndpointRelease(r.pool.QueryRow(ctx, `SELECT `+endpointReleaseCols+`
		FROM endpoint_releases WHERE endpoint_id=$1 AND id=$2`, endpointID, releaseID))
}

func (r *endpointRepo) ListReleases(ctx context.Context, endpointID uuid.UUID) ([]*controlmodel.EndpointRelease, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+endpointReleaseCols+`
		FROM endpoint_releases WHERE endpoint_id=$1 ORDER BY release_number DESC`, endpointID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*controlmodel.EndpointRelease{}
	for rows.Next() {
		release, scanErr := scanEndpointRelease(rows)
		if scanErr != nil {
			return nil, scanErr
		}
		out = append(out, release)
	}
	return out, rows.Err()
}

const endpointCredentialCols = `id,endpoint_id,name,key_prefix,secret_hash,secret_ciphertext,status,scopes,expires_at,
	last_used_at,rotated_from,created_at,revoked_at`

func scanEndpointCredential(row scannable) (*controlmodel.EndpointCredential, error) {
	v := &controlmodel.EndpointCredential{}
	if err := row.Scan(&v.ID, &v.EndpointID, &v.Name, &v.KeyPrefix, &v.SecretHash, &v.SecretCiphertext, &v.Status,
		&v.Scopes, &v.ExpiresAt, &v.LastUsedAt, &v.RotatedFrom, &v.CreatedAt, &v.RevokedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	v.Recoverable = len(v.SecretCiphertext) > 0
	return v, nil
}

func (r *endpointRepo) CreateCredential(ctx context.Context, in *controlmodel.EndpointCredential) (*controlmodel.EndpointCredential, error) {
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	if in.Status == "" {
		in.Status = controlmodel.EndpointCredentialActive
	}
	v, err := scanEndpointCredential(r.pool.QueryRow(ctx, `INSERT INTO endpoint_credentials(
		id,endpoint_id,name,key_prefix,secret_hash,secret_ciphertext,status,scopes,expires_at,rotated_from)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) RETURNING `+endpointCredentialCols,
		in.ID, in.EndpointID, in.Name, in.KeyPrefix, in.SecretHash, in.SecretCiphertext, in.Status, nullJSON(in.Scopes),
		in.ExpiresAt, in.RotatedFrom))
	return v, catalogConflict(err)
}

func (r *endpointRepo) ListCredentials(ctx context.Context, endpointID uuid.UUID) ([]*controlmodel.EndpointCredential, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+endpointCredentialCols+`
		FROM endpoint_credentials WHERE endpoint_id=$1 ORDER BY created_at DESC`, endpointID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*controlmodel.EndpointCredential{}
	for rows.Next() {
		v, scanErr := scanEndpointCredential(rows)
		if scanErr != nil {
			return nil, scanErr
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

func (r *endpointRepo) GetCredentialByPrefix(ctx context.Context, endpointID uuid.UUID, prefix string) (*controlmodel.EndpointCredential, error) {
	return scanEndpointCredential(r.pool.QueryRow(ctx, `SELECT `+endpointCredentialCols+`
		FROM endpoint_credentials WHERE endpoint_id=$1 AND key_prefix=$2`, endpointID, prefix))
}

func (r *endpointRepo) UpdateCredential(ctx context.Context, in *controlmodel.EndpointCredential) (*controlmodel.EndpointCredential, error) {
	return scanEndpointCredential(r.pool.QueryRow(ctx, `UPDATE endpoint_credentials SET
		name=$2,status=$3,scopes=$4,expires_at=$5,last_used_at=$6,revoked_at=$7
		WHERE id=$1 RETURNING `+endpointCredentialCols, in.ID, in.Name, in.Status,
		nullJSON(in.Scopes), in.ExpiresAt, in.LastUsedAt, in.RevokedAt))
}

func (r *endpointRepo) ConsumeRateLimit(ctx context.Context, endpointID uuid.UUID, principal string,
	limit, windowSeconds int, now time.Time) (bool, time.Duration, error) {
	if windowSeconds <= 0 {
		windowSeconds = 60
	}
	duration := time.Duration(windowSeconds) * time.Second
	windowStart := now.Truncate(duration)
	var count int
	err := r.pool.QueryRow(ctx, `INSERT INTO endpoint_rate_windows(endpoint_id,principal_ref,window_start,request_count)
		VALUES($1,$2,$3,1)
		ON CONFLICT(endpoint_id,principal_ref,window_start)
		DO UPDATE SET request_count=endpoint_rate_windows.request_count+1
		RETURNING request_count`, endpointID, principal, windowStart).Scan(&count)
	if err != nil {
		return false, 0, catalogConflict(err)
	}
	return count <= limit, duration - now.Sub(windowStart), nil
}

const endpointInvocationCols = `id,endpoint_id,mode,principal_type,principal_ref,idempotency_key,status,
	conversation_id,turn_id,COALESCE(session_id,''),issue_id,run_id,input,result,
	COALESCE(error_code,''),COALESCE(error_message,''),correlation_id,
	created_at,started_at,completed_at,updated_at`

func scanEndpointInvocation(row scannable) (*controlmodel.EndpointInvocation, error) {
	v := &controlmodel.EndpointInvocation{}
	if err := row.Scan(&v.ID, &v.EndpointID, &v.Mode, &v.PrincipalType, &v.PrincipalRef,
		&v.IdempotencyKey, &v.Status, &v.ConversationID, &v.TurnID, &v.SessionID, &v.IssueID,
		&v.RunID, &v.Input, &v.Result, &v.ErrorCode, &v.ErrorMessage, &v.CorrelationID,
		&v.CreatedAt, &v.StartedAt, &v.CompletedAt, &v.UpdatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	return v, nil
}

func (r *endpointRepo) insertInvocation(ctx context.Context, in *controlmodel.EndpointInvocation, onConflict string) (*controlmodel.EndpointInvocation, error) {
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	if in.Status == "" {
		in.Status = controlmodel.EndpointInvocationAccepted
	}
	return scanEndpointInvocation(r.pool.QueryRow(ctx, `INSERT INTO endpoint_invocations(
		id,endpoint_id,mode,principal_type,principal_ref,idempotency_key,status,conversation_id,
		turn_id,session_id,issue_id,run_id,input,result,error_code,error_message,correlation_id,
		started_at,completed_at)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19) `+onConflict+`
		RETURNING `+endpointInvocationCols, in.ID, in.EndpointID, in.Mode, in.PrincipalType,
		in.PrincipalRef, in.IdempotencyKey, in.Status, in.ConversationID, in.TurnID, nullStr(in.SessionID),
		in.IssueID, in.RunID, nullJSON(in.Input), nullJSON(in.Result), nullStr(in.ErrorCode),
		nullStr(in.ErrorMessage), in.CorrelationID, in.StartedAt, in.CompletedAt))
}

func (r *endpointRepo) ReserveInvocation(ctx context.Context, in *controlmodel.EndpointInvocation) (*controlmodel.EndpointInvocation, bool, error) {
	if in.IdempotencyKey == "" {
		v, err := r.insertInvocation(ctx, in, "")
		return v, true, catalogConflict(err)
	}
	v, err := r.insertInvocation(ctx, in, `ON CONFLICT(endpoint_id,mode,principal_ref,idempotency_key)
		WHERE idempotency_key<>'' DO NOTHING`)
	if err == nil {
		return v, true, nil
	}
	if !errors.Is(err, store.ErrNotFound) {
		return nil, false, catalogConflict(err)
	}
	v, err = scanEndpointInvocation(r.pool.QueryRow(ctx, `SELECT `+endpointInvocationCols+`
		FROM endpoint_invocations WHERE endpoint_id=$1 AND mode=$2 AND principal_ref=$3 AND idempotency_key=$4`,
		in.EndpointID, in.Mode, in.PrincipalRef, in.IdempotencyKey))
	return v, false, err
}

func (r *endpointRepo) GetInvocation(ctx context.Context, id uuid.UUID) (*controlmodel.EndpointInvocation, error) {
	return scanEndpointInvocation(r.pool.QueryRow(ctx, `SELECT `+endpointInvocationCols+`
		FROM endpoint_invocations WHERE id=$1`, id))
}

func (r *endpointRepo) ListInvocations(ctx context.Context, filter store.EndpointInvocationFilter) ([]*controlmodel.EndpointInvocation, error) {
	limit := filter.Limit
	if limit <= 0 || limit > 1000 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `SELECT `+endpointInvocationCols+` FROM endpoint_invocations
		WHERE ($1::uuid IS NULL OR endpoint_id=$1)
		AND ($2::uuid IS NULL OR run_id=$2)
		AND ($3='' OR mode=$3) AND ($4='' OR status=$4)
		AND (NOT $5 OR status NOT IN ('completed','failed','cancelled','timed_out'))
		ORDER BY created_at DESC LIMIT $6`, nullUUID(filter.EndpointID), nullUUID(filter.RunID),
		filter.Mode, filter.Status, filter.ActiveOnly, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*controlmodel.EndpointInvocation{}
	for rows.Next() {
		v, scanErr := scanEndpointInvocation(rows)
		if scanErr != nil {
			return nil, scanErr
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

func (r *endpointRepo) UpdateInvocation(ctx context.Context, in *controlmodel.EndpointInvocation) (*controlmodel.EndpointInvocation, error) {
	return scanEndpointInvocation(r.pool.QueryRow(ctx, `UPDATE endpoint_invocations SET
		status=$2,conversation_id=$3,turn_id=$4,session_id=$5,issue_id=$6,run_id=$7,
		input=$8,result=$9,error_code=$10,error_message=$11,started_at=$12,completed_at=$13,
		updated_at=now() WHERE id=$1 RETURNING `+endpointInvocationCols, in.ID, in.Status,
		in.ConversationID, in.TurnID, nullStr(in.SessionID), in.IssueID, in.RunID, nullJSON(in.Input),
		nullJSON(in.Result), nullStr(in.ErrorCode), nullStr(in.ErrorMessage), in.StartedAt, in.CompletedAt))
}

const endpointConversationCols = `id,endpoint_id,agent_id,session_id,binding_id,agent_instance_id,
	instance_generation,COALESCE(external_session_ref,''),status,principal_ref,last_turn_at,created_at,updated_at`

func scanEndpointConversation(row scannable) (*controlmodel.EndpointConversation, error) {
	v := &controlmodel.EndpointConversation{}
	var instanceID *uuid.UUID
	if err := row.Scan(&v.ID, &v.EndpointID, &v.AgentID, &v.SessionID, &v.BindingID, &instanceID,
		&v.InstanceGeneration, &v.ExternalSessionRef, &v.Status, &v.PrincipalRef, &v.LastTurnAt,
		&v.CreatedAt, &v.UpdatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	v.AgentInstanceID = derefUUID(instanceID)
	return v, nil
}

func (r *endpointRepo) CreateConversation(ctx context.Context, in *controlmodel.EndpointConversation) (*controlmodel.EndpointConversation, error) {
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	if in.Status == "" {
		in.Status = controlmodel.EndpointConversationActive
	}
	v, err := scanEndpointConversation(r.pool.QueryRow(ctx, `INSERT INTO endpoint_conversations(
		id,endpoint_id,agent_id,session_id,binding_id,agent_instance_id,instance_generation,
		external_session_ref,status,principal_ref,last_turn_at)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11) RETURNING `+endpointConversationCols,
		in.ID, in.EndpointID, in.AgentID, in.SessionID, in.BindingID, nullUUID(in.AgentInstanceID),
		in.InstanceGeneration, nullStr(in.ExternalSessionRef), in.Status, in.PrincipalRef, in.LastTurnAt))
	return v, catalogConflict(err)
}

func (r *endpointRepo) GetConversation(ctx context.Context, id uuid.UUID) (*controlmodel.EndpointConversation, error) {
	return scanEndpointConversation(r.pool.QueryRow(ctx, `SELECT `+endpointConversationCols+`
		FROM endpoint_conversations WHERE id=$1`, id))
}

func (r *endpointRepo) UpdateConversation(ctx context.Context, in *controlmodel.EndpointConversation) (*controlmodel.EndpointConversation, error) {
	return scanEndpointConversation(r.pool.QueryRow(ctx, `UPDATE endpoint_conversations SET
		session_id=$2,binding_id=$3,agent_instance_id=$4,instance_generation=$5,
		external_session_ref=$6,status=$7,last_turn_at=$8,updated_at=now()
		WHERE id=$1 RETURNING `+endpointConversationCols, in.ID, in.SessionID, in.BindingID,
		nullUUID(in.AgentInstanceID), in.InstanceGeneration, nullStr(in.ExternalSessionRef),
		in.Status, in.LastTurnAt))
}

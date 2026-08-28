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

type agentEndpointRepo struct{ pool *pgxpool.Pool }

const endpointCols = `id,tenant,namespace,name,slug,target_type,target_ref,invocation_mode,auth_policy,rate_limit,runtime_policy_ref,credential_hash,enabled,version,created_at,updated_at`

func scanEndpoint(row scannable) (*controlmodel.AgentEndpoint, error) {
	v := &controlmodel.AgentEndpoint{}
	if err := row.Scan(&v.ID, &v.Tenant, &v.Namespace, &v.Name, &v.Slug, &v.TargetType, &v.TargetRef, &v.InvocationMode, &v.AuthPolicy, &v.RateLimit, &v.RuntimePolicyRef, &v.CredentialHash, &v.Enabled, &v.Version, &v.CreatedAt, &v.UpdatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	return v, nil
}
func (r *agentEndpointRepo) Create(ctx context.Context, in *controlmodel.AgentEndpoint) (*controlmodel.AgentEndpoint, error) {
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	v, err := scanEndpoint(r.pool.QueryRow(ctx, `INSERT INTO agent_endpoints(id,tenant,namespace,name,slug,target_type,target_ref,invocation_mode,auth_policy,rate_limit,runtime_policy_ref,credential_hash,enabled) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13) RETURNING `+endpointCols, in.ID, in.Tenant, in.Namespace, in.Name, in.Slug, in.TargetType, in.TargetRef, in.InvocationMode, in.AuthPolicy, nullJSON(in.RateLimit), in.RuntimePolicyRef, in.CredentialHash, in.Enabled))
	return v, catalogConflict(err)
}
func (r *agentEndpointRepo) Get(ctx context.Context, id uuid.UUID) (*controlmodel.AgentEndpoint, error) {
	return scanEndpoint(r.pool.QueryRow(ctx, `SELECT `+endpointCols+` FROM agent_endpoints WHERE id=$1`, id))
}
func (r *agentEndpointRepo) GetBySlug(ctx context.Context, slug string) (*controlmodel.AgentEndpoint, error) {
	return scanEndpoint(r.pool.QueryRow(ctx, `SELECT `+endpointCols+` FROM agent_endpoints WHERE slug=$1`, slug))
}
func (r *agentEndpointRepo) List(ctx context.Context, tenant, namespace string) ([]*controlmodel.AgentEndpoint, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+endpointCols+` FROM agent_endpoints WHERE ($1='' OR tenant=$1) AND ($2='' OR namespace=$2) ORDER BY updated_at DESC`, tenant, namespace)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*controlmodel.AgentEndpoint{}
	for rows.Next() {
		v, e := scanEndpoint(rows)
		if e != nil {
			return nil, e
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
func (r *agentEndpointRepo) Update(ctx context.Context, in *controlmodel.AgentEndpoint, expected int64) (*controlmodel.AgentEndpoint, error) {
	v, err := scanEndpoint(r.pool.QueryRow(ctx, `UPDATE agent_endpoints SET name=$3,slug=$4,target_type=$5,target_ref=$6,invocation_mode=$7,auth_policy=$8,rate_limit=$9,runtime_policy_ref=$10,credential_hash=$11,enabled=$12,version=version+1,updated_at=now() WHERE id=$1 AND version=$2 RETURNING `+endpointCols, in.ID, expected, in.Name, in.Slug, in.TargetType, in.TargetRef, in.InvocationMode, in.AuthPolicy, nullJSON(in.RateLimit), in.RuntimePolicyRef, in.CredentialHash, in.Enabled))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return v, catalogConflict(err)
}

func scanEndpointJob(row scannable) (*controlmodel.EndpointJob, error) {
	v := &controlmodel.EndpointJob{}
	if err := row.Scan(&v.ID, &v.EndpointID, &v.IdempotencyKey, &v.IssueID, &v.RunID, &v.CreatedAt, &v.UpdatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	return v, nil
}
func (r *agentEndpointRepo) ReserveJob(ctx context.Context, endpointID uuid.UUID, key string) (*controlmodel.EndpointJob, bool, error) {
	id := uuid.New()
	v, err := scanEndpointJob(r.pool.QueryRow(ctx, `INSERT INTO endpoint_jobs(id,endpoint_id,idempotency_key) VALUES($1,$2,$3) ON CONFLICT(endpoint_id,idempotency_key) DO NOTHING RETURNING id,endpoint_id,idempotency_key,issue_id,run_id,created_at,updated_at`, id, endpointID, key))
	if err == nil {
		return v, true, nil
	}
	if !errors.Is(err, store.ErrNotFound) {
		return nil, false, err
	}
	v, err = scanEndpointJob(r.pool.QueryRow(ctx, `SELECT id,endpoint_id,idempotency_key,issue_id,run_id,created_at,updated_at FROM endpoint_jobs WHERE endpoint_id=$1 AND idempotency_key=$2`, endpointID, key))
	return v, false, err
}
func (r *agentEndpointRepo) CompleteJob(ctx context.Context, id, issueID, runID uuid.UUID) (*controlmodel.EndpointJob, error) {
	return scanEndpointJob(r.pool.QueryRow(ctx, `UPDATE endpoint_jobs SET issue_id=$2,run_id=$3,updated_at=now() WHERE id=$1 RETURNING id,endpoint_id,idempotency_key,issue_id,run_id,created_at,updated_at`, id, issueID, runID))
}
func (r *agentEndpointRepo) GetJobByIssue(ctx context.Context, issueID uuid.UUID) (*controlmodel.EndpointJob, error) {
	return scanEndpointJob(r.pool.QueryRow(ctx, `SELECT id,endpoint_id,idempotency_key,issue_id,run_id,created_at,updated_at FROM endpoint_jobs WHERE issue_id=$1`, issueID))
}

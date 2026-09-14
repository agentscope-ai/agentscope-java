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

type agentCatalogRepo struct{ pool *pgxpool.Pool }

const agentColumns = `id,tenant,namespace,agent_key,display_name,description,owner_type,owner_ref,
	status,capabilities,labels,metadata,version,created_at,updated_at,archived_at`

func scanAgent(row scannable) (*controlmodel.Agent, error) {
	in := &controlmodel.Agent{}
	var description, ownerType, ownerRef *string
	var capabilities, labels, metadata []byte
	if err := row.Scan(&in.ID, &in.Tenant, &in.Namespace, &in.AgentKey, &in.DisplayName,
		&description, &ownerType, &ownerRef, &in.Status, &capabilities, &labels, &metadata,
		&in.Version, &in.CreatedAt, &in.UpdatedAt, &in.ArchivedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	in.Description, in.OwnerType, in.OwnerRef = deref(description), deref(ownerType), deref(ownerRef)
	in.Capabilities, in.Labels, in.Metadata = capabilities, labels, metadata
	return in, nil
}

func catalogConflict(err error) error {
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && (pgErr.Code == "23505" || pgErr.Code == "40001") {
		return store.ErrConflict
	}
	return err
}

func (r *agentCatalogRepo) CreateAgent(ctx context.Context, in *controlmodel.Agent) (*controlmodel.Agent, error) {
	if in == nil || in.Tenant == "" || in.Namespace == "" || in.AgentKey == "" {
		return nil, fmt.Errorf("agent requires tenant, namespace, and agentKey")
	}
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	if in.DisplayName == "" {
		in.DisplayName = in.AgentKey
	}
	if in.Status == "" {
		in.Status = controlmodel.AgentProvisioning
	}
	agent, err := scanAgent(r.pool.QueryRow(ctx, `INSERT INTO agents
		(id,tenant,namespace,agent_key,display_name,description,owner_type,owner_ref,status,
		 capabilities,labels,metadata,version) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,1)
		RETURNING `+agentColumns, in.ID, in.Tenant, in.Namespace, in.AgentKey, in.DisplayName,
		nullStr(in.Description), nullStr(in.OwnerType), nullStr(in.OwnerRef), in.Status,
		nullJSON(in.Capabilities), nullJSON(in.Labels), nullJSON(in.Metadata)))
	return agent, catalogConflict(err)
}

func (r *agentCatalogRepo) GetAgent(ctx context.Context, id uuid.UUID) (*controlmodel.Agent, error) {
	return scanAgent(r.pool.QueryRow(ctx, `SELECT `+agentColumns+` FROM agents WHERE id=$1`, id))
}

func (r *agentCatalogRepo) GetAgentByKey(ctx context.Context, tenant, namespace, key string) (*controlmodel.Agent, error) {
	return scanAgent(r.pool.QueryRow(ctx, `SELECT `+agentColumns+` FROM agents
		WHERE tenant=$1 AND namespace=$2 AND agent_key=$3 AND archived_at IS NULL`, tenant, namespace, key))
}

func (r *agentCatalogRepo) ListAgents(ctx context.Context, filter store.AgentFilter) ([]*controlmodel.Agent, error) {
	limit := filter.Limit
	if limit <= 0 || limit > 1000 {
		limit = 200
	}
	rows, err := r.pool.Query(ctx, `SELECT `+agentColumns+` FROM agents WHERE
		($1='' OR tenant=$1) AND ($2='' OR namespace=$2) AND ($3='' OR status=$3)
		AND ($4 OR archived_at IS NULL) AND NOT (id=ANY($6::uuid[])) ORDER BY agent_key LIMIT $5 OFFSET $7`, filter.Tenant, filter.Namespace,
		string(filter.Status), filter.IncludeArchived, limit, nonNilResourceIDs(filter.ExcludedIDs), max(0, filter.Offset))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.Agent, 0)
	for rows.Next() {
		agent, err := scanAgent(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, agent)
	}
	return out, rows.Err()
}

func (r *agentCatalogRepo) UpdateAgent(ctx context.Context, in *controlmodel.Agent, expected int64) (*controlmodel.Agent, error) {
	if in == nil || expected <= 0 {
		return nil, store.ErrConflict
	}
	agent, err := scanAgent(r.pool.QueryRow(ctx, `UPDATE agents SET display_name=$2,description=$3,
		owner_type=$4,owner_ref=$5,status=$6,capabilities=$7,labels=$8,metadata=$9,
		archived_at=$10,version=version+1,updated_at=now() WHERE id=$1 AND version=$11
		RETURNING `+agentColumns, in.ID, in.DisplayName, nullStr(in.Description), nullStr(in.OwnerType),
		nullStr(in.OwnerRef), in.Status, nullJSON(in.Capabilities), nullJSON(in.Labels),
		nullJSON(in.Metadata), in.ArchivedAt, expected))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return agent, err
}

const bindingColumns = `id,agent_id,tenant,namespace,kind,configuration,priority,enabled,version,
	created_at,updated_at,archived_at`

func scanAgentBinding(row scannable) (*controlmodel.AgentBinding, error) {
	in := &controlmodel.AgentBinding{}
	var configuration []byte
	if err := row.Scan(&in.ID, &in.AgentID, &in.Tenant, &in.Namespace, &in.Kind, &configuration,
		&in.Priority, &in.Enabled, &in.Version, &in.CreatedAt, &in.UpdatedAt, &in.ArchivedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	in.Configuration = configuration
	return in, nil
}

func createBindingRow(ctx context.Context, q interface {
	QueryRow(context.Context, string, ...any) pgx.Row
}, in *controlmodel.AgentBinding) (*controlmodel.AgentBinding, error) {
	if err := in.Validate(); err != nil {
		return nil, err
	}
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	return scanAgentBinding(q.QueryRow(ctx, `INSERT INTO agent_bindings
		(id,agent_id,tenant,namespace,kind,configuration,priority,enabled,version)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,1) RETURNING `+bindingColumns,
		in.ID, in.AgentID, in.Tenant, in.Namespace, in.Kind, in.Configuration, in.Priority, in.Enabled))
}

func (r *agentCatalogRepo) CreateBinding(ctx context.Context, in *controlmodel.AgentBinding) (*controlmodel.AgentBinding, error) {
	binding, err := createBindingRow(ctx, r.pool, in)
	return binding, catalogConflict(err)
}

func (r *agentCatalogRepo) GetBinding(ctx context.Context, id uuid.UUID) (*controlmodel.AgentBinding, error) {
	return scanAgentBinding(r.pool.QueryRow(ctx, `SELECT `+bindingColumns+` FROM agent_bindings WHERE id=$1`, id))
}

func (r *agentCatalogRepo) ListBindings(ctx context.Context, agentID uuid.UUID, includeDisabled bool) ([]*controlmodel.AgentBinding, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+bindingColumns+` FROM agent_bindings
		WHERE agent_id=$1 AND archived_at IS NULL AND ($2 OR enabled) ORDER BY priority DESC,id`, agentID, includeDisabled)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.AgentBinding, 0)
	for rows.Next() {
		binding, err := scanAgentBinding(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, binding)
	}
	return out, rows.Err()
}

func (r *agentCatalogRepo) UpdateBinding(ctx context.Context, in *controlmodel.AgentBinding, expected int64) (*controlmodel.AgentBinding, error) {
	if in == nil || expected <= 0 {
		return nil, store.ErrConflict
	}
	if err := in.Validate(); err != nil {
		return nil, err
	}
	binding, err := scanAgentBinding(r.pool.QueryRow(ctx, `UPDATE agent_bindings SET
		configuration=$3,priority=$4,enabled=$5,archived_at=$6,version=version+1,updated_at=now()
		WHERE id=$1 AND agent_id=$2 AND version=$7 RETURNING `+bindingColumns,
		in.ID, in.AgentID, in.Configuration, in.Priority, in.Enabled, in.ArchivedAt, expected))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return binding, err
}

const credentialColumns = `id,agent_id,token_hash,status,expires_at,rotated_at,created_at,updated_at`

func scanCredential(row scannable) (*controlmodel.AgentRegistrationCredential, error) {
	in := &controlmodel.AgentRegistrationCredential{}
	if err := row.Scan(&in.ID, &in.AgentID, &in.TokenHash, &in.Status, &in.ExpiresAt,
		&in.RotatedAt, &in.CreatedAt, &in.UpdatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	return in, nil
}

func insertCredential(ctx context.Context, tx pgx.Tx, agentID uuid.UUID, hash []byte, expires *time.Time) (*controlmodel.AgentRegistrationCredential, error) {
	return scanCredential(tx.QueryRow(ctx, `INSERT INTO agent_registration_credentials
		(id,agent_id,token_hash,status,expires_at) VALUES ($1,$2,$3,$4,$5) RETURNING `+credentialColumns,
		uuid.New(), agentID, hash, controlmodel.RegistrationCredentialActive, expires))
}

func (r *agentCatalogRepo) RegisterExternal(ctx context.Context, req store.ExternalAgentRegistration) (*store.ExternalAgentRegistrationResult, error) {
	if req.Tenant == "" || req.Namespace == "" || req.AgentKey == "" || req.InstanceKey == "" {
		return nil, fmt.Errorf("registration requires tenant, namespace, agentKey, and instanceKey")
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	// There is no row to lock while the logical Agent is being created. Serialize
	// registrations for the same identity so concurrent first-time instances do
	// not race on the unique Agent key and fail spuriously.
	registrationKey := fmt.Sprintf("%d:%s%d:%s%s", len(req.Tenant), req.Tenant,
		len(req.Namespace), req.Namespace, req.AgentKey)
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1,0))`, registrationKey); err != nil {
		return nil, err
	}

	agent, err := scanAgent(tx.QueryRow(ctx, `SELECT `+agentColumns+` FROM agents
		WHERE tenant=$1 AND namespace=$2 AND agent_key=$3 AND archived_at IS NULL FOR UPDATE`,
		req.Tenant, req.Namespace, req.AgentKey))
	newAgent := errors.Is(err, store.ErrNotFound)
	if err != nil && !newAgent {
		return nil, err
	}
	var binding *controlmodel.AgentBinding
	var credential *controlmodel.AgentRegistrationCredential
	if newAgent {
		if len(req.NewCredentialHash) == 0 {
			return nil, store.ErrForbidden
		}
		agentID := uuid.New()
		displayName := req.DisplayName
		if displayName == "" {
			displayName = req.AgentKey
		}
		agent, err = scanAgent(tx.QueryRow(ctx, `INSERT INTO agents
			(id,tenant,namespace,agent_key,display_name,description,owner_type,owner_ref,status,
			 capabilities,labels,version) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,1)
			RETURNING `+agentColumns, agentID, req.Tenant, req.Namespace, req.AgentKey, displayName,
			nullStr(req.Description), nullStr(req.OwnerType), nullStr(req.OwnerRef), controlmodel.AgentActive,
			nullJSON(req.Capabilities), nullJSON(req.Labels)))
		if err != nil {
			return nil, catalogConflict(err)
		}
		configuration, _ := json.Marshal(controlmodel.ExternalBindingConfiguration{})
		binding, err = createBindingRow(ctx, tx, &controlmodel.AgentBinding{AgentID: agent.ID,
			Tenant: agent.Tenant, Namespace: agent.Namespace, Kind: controlmodel.DataPlaneExternalApplication,
			Configuration: configuration, Priority: 100, Enabled: true})
		if err != nil {
			return nil, err
		}
		runtimeBinding, _ := binding.RuntimeBinding()
		candidates, _ := json.Marshal([]controlmodel.RuntimeBindingCandidate{{Binding: runtimeBinding}})
		if _, err = tx.Exec(ctx, `INSERT INTO agent_runtime_policies
			(id,tenant,namespace,agent_id,candidates,selection_mode,fallback_mode,version)
			VALUES ($1,$2,$3,$4,$5,'ordered','disabled',1)`, uuid.New(), agent.Tenant,
			agent.Namespace, agent.ID.String(), candidates); err != nil {
			return nil, err
		}
	} else {
		if agent.Status != controlmodel.AgentActive {
			return nil, store.ErrForbidden
		}
		binding, err = scanAgentBinding(tx.QueryRow(ctx, `SELECT `+bindingColumns+` FROM agent_bindings
			WHERE agent_id=$1 AND kind=$2 AND enabled AND archived_at IS NULL ORDER BY priority DESC,id LIMIT 1`,
			agent.ID, controlmodel.DataPlaneExternalApplication))
		if err != nil {
			if errors.Is(err, store.ErrNotFound) {
				return nil, store.ErrConflict
			}
			return nil, err
		}
	}
	credential, err = insertCredential(ctx, tx, agent.ID, req.NewCredentialHash, req.CredentialExpiresAt)
	if err != nil {
		return nil, catalogConflict(err)
	}

	instanceID := uuid.New()
	instance, err := scanAgentInstance(tx.QueryRow(ctx, `INSERT INTO agent_instances
		(id,tenant,namespace,agent_id,binding_id,backend_kind,instance_key,framework,framework_version,
		 sdk_version,capabilities,labels,routing_key,health,capacity,active_sessions,last_seen_at,generation)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,0,now(),1)
		ON CONFLICT (agent_id,binding_id,instance_key) DO UPDATE SET
		 framework=EXCLUDED.framework,framework_version=EXCLUDED.framework_version,
		 sdk_version=EXCLUDED.sdk_version,capabilities=EXCLUDED.capabilities,labels=EXCLUDED.labels,
		 routing_key=EXCLUDED.routing_key,health=EXCLUDED.health,capacity=EXCLUDED.capacity,
		 last_seen_at=now(),generation=agent_instances.generation+1,updated_at=now()
		RETURNING `+agentInstanceColumns, instanceID, agent.Tenant, agent.Namespace, agent.ID, binding.ID,
		controlmodel.DataPlaneExternalApplication, req.InstanceKey, nullStr(req.Framework),
		nullStr(req.FrameworkVersion), nullStr(req.SDKVersion), nullJSON(req.Capabilities), nullJSON(req.Labels),
		nullStr(req.RoutingKey), controlmodel.RuntimeHealthHealthy, req.Capacity))
	if err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, catalogConflict(err)
	}
	return &store.ExternalAgentRegistrationResult{Agent: agent, Binding: binding, Instance: instance,
		Credential: credential, CredentialCreated: true}, nil
}

func (r *agentCatalogRepo) RotateRegistrationCredential(ctx context.Context, agentID uuid.UUID, hash []byte, expires *time.Time) (*controlmodel.AgentRegistrationCredential, error) {
	if len(hash) == 0 {
		return nil, fmt.Errorf("credential hash is required")
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var exists bool
	if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM agents WHERE id=$1 AND archived_at IS NULL)`, agentID).Scan(&exists); err != nil {
		return nil, err
	}
	if !exists {
		return nil, store.ErrNotFound
	}
	if _, err := tx.Exec(ctx, `UPDATE agent_registration_credentials SET status=$2,rotated_at=now(),updated_at=now()
		WHERE agent_id=$1 AND status=$3`, agentID, controlmodel.RegistrationCredentialRevoked,
		controlmodel.RegistrationCredentialActive); err != nil {
		return nil, err
	}
	credential, err := insertCredential(ctx, tx, agentID, hash, expires)
	if err != nil {
		return nil, catalogConflict(err)
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return credential, nil
}

func (r *agentCatalogRepo) RevokeRegistrationCredential(ctx context.Context, agentID, credentialID uuid.UUID) error {
	tag, err := r.pool.Exec(ctx, `UPDATE agent_registration_credentials SET status=$3,rotated_at=now(),updated_at=now()
		WHERE id=$1 AND agent_id=$2`, credentialID, agentID, controlmodel.RegistrationCredentialRevoked)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	return nil
}

func (r *agentCatalogRepo) ValidateInstanceClaim(ctx context.Context, claim store.AgentInstanceClaim) (*controlmodel.AgentInstance, error) {
	if claim.AgentID == uuid.Nil || claim.BindingID == uuid.Nil || claim.Generation <= 0 || claim.InstanceKey == "" {
		return nil, store.ErrForbidden
	}
	instance, err := scanAgentInstance(r.pool.QueryRow(ctx, `SELECT i.id,i.tenant,i.namespace,i.agent_id,i.binding_id,
		i.backend_kind,i.instance_key,i.framework,i.framework_version,i.sdk_version,i.capabilities,i.labels,
		i.routing_key,i.health,i.capacity,i.active_sessions,i.last_seen_at,i.generation,i.created_at,i.updated_at
		FROM agent_instances i
		JOIN agents a ON a.id=i.agent_id
		JOIN agent_bindings b ON b.id=i.binding_id
		WHERE i.agent_id=$1 AND i.binding_id=$2 AND i.instance_key=$3 AND i.generation=$4
		AND i.tenant=$5 AND i.namespace=$6 AND a.agent_key=$7 AND a.status=$8 AND a.archived_at IS NULL
		AND b.enabled AND b.archived_at IS NULL AND b.kind=$9`, claim.AgentID, claim.BindingID,
		claim.InstanceKey, claim.Generation, claim.Tenant, claim.Namespace, claim.AgentKey,
		controlmodel.AgentActive, controlmodel.DataPlaneExternalApplication))
	if err != nil {
		return nil, store.ErrForbidden
	}
	if claim.TrustedWorkloadIdentity {
		return instance, nil
	}
	var valid bool
	if len(claim.CredentialHash) > 0 {
		err = r.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM agent_registration_credentials
			WHERE agent_id=$1 AND token_hash=$2 AND status=$3 AND (expires_at IS NULL OR expires_at>now()))`,
			claim.AgentID, claim.CredentialHash, controlmodel.RegistrationCredentialActive).Scan(&valid)
	}
	if err != nil || !valid {
		return nil, store.ErrForbidden
	}
	return instance, nil
}

func nonNilResourceIDs(ids []uuid.UUID) []uuid.UUID {
	if ids == nil {
		return []uuid.UUID{}
	}
	return ids
}

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

package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type runtimeRegistryRepo struct{ pool *pgxpool.Pool }

const agentInstanceColumns = `id, tenant, namespace, agent_id, binding_id, backend_kind, instance_key,
	framework, framework_version, sdk_version, capabilities, labels, routing_key, health,
	capacity, active_sessions, last_seen_at, generation, created_at, updated_at`

func scanAgentInstance(row scannable) (*controlmodel.AgentInstance, error) {
	in := &controlmodel.AgentInstance{}
	var framework, frameworkVersion, sdkVersion, routingKey *string
	var capabilities, labels []byte
	err := row.Scan(&in.ID, &in.Tenant, &in.Namespace, &in.AgentID, &in.BindingID, &in.BackendKind, &in.InstanceKey,
		&framework, &frameworkVersion, &sdkVersion, &capabilities, &labels, &routingKey, &in.Health,
		&in.Capacity, &in.ActiveSessions, &in.LastSeenAt, &in.Generation, &in.CreatedAt, &in.UpdatedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	in.Framework = deref(framework)
	in.FrameworkVersion = deref(frameworkVersion)
	in.SDKVersion = deref(sdkVersion)
	in.RoutingKey = deref(routingKey)
	in.Capabilities = capabilities
	in.Labels = labels
	return in, nil
}

func (r *runtimeRegistryRepo) UpsertAgentInstance(ctx context.Context, in *controlmodel.AgentInstance) (*controlmodel.AgentInstance, error) {
	if in == nil || in.AgentID == uuid.Nil || in.BindingID == uuid.Nil || in.InstanceKey == "" || in.BackendKind == "" {
		return nil, fmt.Errorf("agent instance requires agentId, bindingId, instanceKey, and backendKind")
	}
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	if in.Health == "" {
		in.Health = controlmodel.RuntimeHealthHealthy
	}
	if in.LastSeenAt.IsZero() {
		in.LastSeenAt = time.Now().UTC()
	}
	return scanAgentInstance(r.pool.QueryRow(ctx, `
		INSERT INTO agent_instances (id,tenant,namespace,agent_id,binding_id,backend_kind,instance_key,
			framework,framework_version,sdk_version,capabilities,labels,routing_key,health,
			capacity,active_sessions,last_seen_at,generation)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,1)
		ON CONFLICT (agent_id,binding_id,instance_key) DO UPDATE SET
			backend_kind=EXCLUDED.backend_kind,
			framework=EXCLUDED.framework, framework_version=EXCLUDED.framework_version,
			sdk_version=EXCLUDED.sdk_version, capabilities=EXCLUDED.capabilities,
			labels=EXCLUDED.labels, routing_key=EXCLUDED.routing_key, health=EXCLUDED.health,
			capacity=EXCLUDED.capacity, active_sessions=EXCLUDED.active_sessions,
			last_seen_at=EXCLUDED.last_seen_at, generation=agent_instances.generation+1,
			updated_at=now()
		RETURNING `+agentInstanceColumns,
		in.ID, in.Tenant, in.Namespace, in.AgentID, in.BindingID, in.BackendKind, in.InstanceKey,
		nullStr(in.Framework), nullStr(in.FrameworkVersion), nullStr(in.SDKVersion),
		nullJSON(in.Capabilities), nullJSON(in.Labels), nullStr(in.RoutingKey), in.Health,
		in.Capacity, in.ActiveSessions, in.LastSeenAt))
}

func (r *runtimeRegistryRepo) GetAgentInstance(ctx context.Context, id uuid.UUID) (*controlmodel.AgentInstance, error) {
	return scanAgentInstance(r.pool.QueryRow(ctx, `SELECT `+agentInstanceColumns+` FROM agent_instances WHERE id=$1`, id))
}

func (r *runtimeRegistryRepo) ListAgentInstances(ctx context.Context, tenant, namespace string, agentID uuid.UUID) ([]*controlmodel.AgentInstance, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+agentInstanceColumns+` FROM agent_instances
		WHERE ($1='' OR tenant=$1) AND ($2='' OR namespace=$2)
		AND ($3::uuid='00000000-0000-0000-0000-000000000000'::uuid OR agent_id=$3)
		ORDER BY updated_at DESC`, tenant, namespace, agentID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.AgentInstance, 0)
	for rows.Next() {
		in, err := scanAgentInstance(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, in)
	}
	return out, rows.Err()
}

func (r *runtimeRegistryRepo) HeartbeatAgentInstance(ctx context.Context, id uuid.UUID, generation int64, activeSessions int32, capabilities json.RawMessage) (*controlmodel.AgentInstance, error) {
	instance, err := scanAgentInstance(r.pool.QueryRow(ctx, `UPDATE agent_instances SET
		active_sessions=$3,capabilities=COALESCE($4,capabilities),health=$5,last_seen_at=now(),updated_at=now()
		WHERE id=$1 AND ($2<=0 OR generation=$2) RETURNING `+agentInstanceColumns,
		id, generation, activeSessions, nullJSON(capabilities), controlmodel.RuntimeHealthHealthy))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return instance, err
}

func (r *runtimeRegistryRepo) SetAgentInstanceHealth(ctx context.Context, id uuid.UUID, generation int64, health string) (*controlmodel.AgentInstance, error) {
	instance, err := scanAgentInstance(r.pool.QueryRow(ctx, `UPDATE agent_instances SET
		health=$3,updated_at=now() WHERE id=$1 AND ($2<=0 OR generation=$2)
		RETURNING `+agentInstanceColumns, id, generation, health))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return instance, err
}

func (r *runtimeRegistryRepo) MarkAgentInstancesOffline(ctx context.Context, before time.Time) (int64, error) {
	tag, err := r.pool.Exec(ctx, `UPDATE agent_instances SET health=$2, updated_at=now()
		WHERE last_seen_at < $1 AND health <> $2`, before, controlmodel.RuntimeHealthUnhealthy)
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}

const runtimeProfileColumns = `id, tenant, namespace, name, provider, runtime, configuration,
	requirements, version, created_at, updated_at`

func scanRuntimeProfile(row scannable) (*controlmodel.RuntimeProfile, error) {
	in := &controlmodel.RuntimeProfile{}
	var runtime *string
	var configuration, requirements []byte
	err := row.Scan(&in.ID, &in.Tenant, &in.Namespace, &in.Name, &in.Provider, &runtime,
		&configuration, &requirements, &in.Version, &in.CreatedAt, &in.UpdatedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	in.Runtime = deref(runtime)
	in.Configuration = configuration
	in.Requirements = requirements
	return in, nil
}

func (r *runtimeRegistryRepo) UpsertRuntimeProfile(ctx context.Context, in *controlmodel.RuntimeProfile) (*controlmodel.RuntimeProfile, error) {
	if in == nil || in.Name == "" || in.Provider == "" {
		return nil, fmt.Errorf("runtime profile requires name and provider")
	}
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	return scanRuntimeProfile(r.pool.QueryRow(ctx, `
		INSERT INTO runtime_profiles (id,tenant,namespace,name,provider,runtime,configuration,requirements,version)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,1)
		ON CONFLICT (tenant,namespace,name) DO UPDATE SET
			provider=EXCLUDED.provider, runtime=EXCLUDED.runtime,
			configuration=EXCLUDED.configuration, requirements=EXCLUDED.requirements,
			version=runtime_profiles.version+1, updated_at=now()
		RETURNING `+runtimeProfileColumns,
		in.ID, in.Tenant, in.Namespace, in.Name, in.Provider, nullStr(in.Runtime),
		nullJSON(in.Configuration), nullJSON(in.Requirements)))
}

func (r *runtimeRegistryRepo) GetRuntimeProfile(ctx context.Context, tenant, namespace, name string) (*controlmodel.RuntimeProfile, error) {
	return scanRuntimeProfile(r.pool.QueryRow(ctx, `SELECT `+runtimeProfileColumns+`
		FROM runtime_profiles WHERE tenant=$1 AND namespace=$2 AND name=$3`, tenant, namespace, name))
}

func (r *runtimeRegistryRepo) GetRuntimeProfileByID(ctx context.Context, id uuid.UUID) (*controlmodel.RuntimeProfile, error) {
	return scanRuntimeProfile(r.pool.QueryRow(ctx, `SELECT `+runtimeProfileColumns+`
		FROM runtime_profiles WHERE id=$1`, id))
}

func (r *runtimeRegistryRepo) ListRuntimeProfiles(ctx context.Context, tenant, namespace string) ([]*controlmodel.RuntimeProfile, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+runtimeProfileColumns+` FROM runtime_profiles
		WHERE ($1='' OR tenant=$1) AND ($2='' OR namespace=$2) ORDER BY name`, tenant, namespace)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.RuntimeProfile, 0)
	for rows.Next() {
		in, err := scanRuntimeProfile(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, in)
	}
	return out, rows.Err()
}

const runtimePoolColumns = `id, tenant, namespace, name, host_selector, configuration,
	version, created_at, updated_at`

func scanRuntimePool(row scannable) (*controlmodel.RuntimePool, error) {
	in := &controlmodel.RuntimePool{}
	var hostSelector, configuration []byte
	err := row.Scan(&in.ID, &in.Tenant, &in.Namespace, &in.Name, &hostSelector,
		&configuration, &in.Version, &in.CreatedAt, &in.UpdatedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	in.HostSelector = hostSelector
	in.Configuration = configuration
	return in, nil
}

func (r *runtimeRegistryRepo) UpsertRuntimePool(ctx context.Context, in *controlmodel.RuntimePool) (*controlmodel.RuntimePool, error) {
	if in == nil || in.Name == "" {
		return nil, fmt.Errorf("runtime pool requires name")
	}
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	return scanRuntimePool(r.pool.QueryRow(ctx, `
		INSERT INTO runtime_pools (id,tenant,namespace,name,host_selector,configuration,version)
		VALUES ($1,$2,$3,$4,$5,$6,1)
		ON CONFLICT (tenant,namespace,name) DO UPDATE SET
			host_selector=EXCLUDED.host_selector, configuration=EXCLUDED.configuration,
			version=runtime_pools.version+1, updated_at=now()
		RETURNING `+runtimePoolColumns,
		in.ID, in.Tenant, in.Namespace, in.Name, nullJSON(in.HostSelector), nullJSON(in.Configuration)))
}

func (r *runtimeRegistryRepo) GetRuntimePool(ctx context.Context, tenant, namespace, name string) (*controlmodel.RuntimePool, error) {
	return scanRuntimePool(r.pool.QueryRow(ctx, `SELECT `+runtimePoolColumns+`
		FROM runtime_pools WHERE tenant=$1 AND namespace=$2 AND name=$3`, tenant, namespace, name))
}

func (r *runtimeRegistryRepo) GetRuntimePoolByID(ctx context.Context, id uuid.UUID) (*controlmodel.RuntimePool, error) {
	return scanRuntimePool(r.pool.QueryRow(ctx, `SELECT `+runtimePoolColumns+`
		FROM runtime_pools WHERE id=$1`, id))
}

func (r *runtimeRegistryRepo) ListRuntimePools(ctx context.Context, tenant, namespace string) ([]*controlmodel.RuntimePool, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+runtimePoolColumns+` FROM runtime_pools
		WHERE ($1='' OR tenant=$1) AND ($2='' OR namespace=$2) ORDER BY name`, tenant, namespace)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.RuntimePool, 0)
	for rows.Next() {
		in, err := scanRuntimePool(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, in)
	}
	return out, rows.Err()
}

const runtimeHostColumns = `id, tenant, namespace, host_key, pool_name, daemon_version, os,
	arch, labels, capabilities, state, capacity, active, last_seen_at, lease_generation,
	created_at, updated_at, capacity_managed`

func scanRuntimeHost(row scannable) (*controlmodel.RuntimeHost, error) {
	in := &controlmodel.RuntimeHost{}
	var daemonVersion, osName, arch *string
	var labels, capabilities []byte
	err := row.Scan(&in.ID, &in.Tenant, &in.Namespace, &in.HostKey, &in.PoolName,
		&daemonVersion, &osName, &arch, &labels, &capabilities, &in.State, &in.Capacity,
		&in.Active, &in.LastSeenAt, &in.LeaseGeneration, &in.CreatedAt, &in.UpdatedAt, &in.CapacityManaged)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	in.DaemonVersion = deref(daemonVersion)
	in.OS = deref(osName)
	in.Arch = deref(arch)
	in.Labels = labels
	in.Capabilities = capabilities
	return in, nil
}

func (r *runtimeRegistryRepo) UpsertRuntimeHost(ctx context.Context, in *controlmodel.RuntimeHost) (*controlmodel.RuntimeHost, error) {
	if in == nil || in.HostKey == "" || in.PoolName == "" {
		return nil, fmt.Errorf("runtime host requires hostKey and poolName")
	}
	if in.ID == uuid.Nil {
		in.ID = uuid.New()
	}
	if in.State == "" {
		in.State = controlmodel.RuntimeHostOnline
	}
	if in.LastSeenAt.IsZero() {
		in.LastSeenAt = time.Now().UTC()
	}
	return scanRuntimeHost(r.pool.QueryRow(ctx, `
		INSERT INTO runtime_hosts (id,tenant,namespace,host_key,pool_name,daemon_version,os,arch,
			labels,capabilities,state,capacity,active,last_seen_at,lease_generation)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,1)
		ON CONFLICT (tenant,namespace,host_key) DO UPDATE SET
			pool_name=EXCLUDED.pool_name, daemon_version=EXCLUDED.daemon_version,
			os=EXCLUDED.os, arch=EXCLUDED.arch, labels=EXCLUDED.labels,
			capabilities=EXCLUDED.capabilities, state=EXCLUDED.state,
			capacity=CASE WHEN runtime_hosts.capacity_managed THEN runtime_hosts.capacity ELSE EXCLUDED.capacity END, active=EXCLUDED.active,
			last_seen_at=EXCLUDED.last_seen_at,
			lease_generation=runtime_hosts.lease_generation+1, updated_at=now()
		RETURNING `+runtimeHostColumns,
		in.ID, in.Tenant, in.Namespace, in.HostKey, in.PoolName, nullStr(in.DaemonVersion),
		nullStr(in.OS), nullStr(in.Arch), nullJSON(in.Labels), nullJSON(in.Capabilities),
		in.State, in.Capacity, in.Active, in.LastSeenAt))
}

func (r *runtimeRegistryRepo) GetRuntimeHost(ctx context.Context, id uuid.UUID) (*controlmodel.RuntimeHost, error) {
	return scanRuntimeHost(r.pool.QueryRow(ctx, `SELECT `+runtimeHostColumns+` FROM runtime_hosts WHERE id=$1`, id))
}

func (r *runtimeRegistryRepo) GetRuntimeHostByKey(ctx context.Context, tenant, namespace, hostKey string) (*controlmodel.RuntimeHost, error) {
	return scanRuntimeHost(r.pool.QueryRow(ctx, `SELECT `+runtimeHostColumns+`
		FROM runtime_hosts WHERE tenant=$1 AND namespace=$2 AND host_key=$3`, tenant, namespace, hostKey))
}

func (r *runtimeRegistryRepo) ListRuntimeHosts(ctx context.Context, tenant, namespace, poolName, state string) ([]*controlmodel.RuntimeHost, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+runtimeHostColumns+` FROM runtime_hosts
		WHERE ($1='' OR tenant=$1) AND ($2='' OR namespace=$2)
		AND ($3='' OR pool_name=$3) AND ($4='' OR state=$4)
		ORDER BY last_seen_at DESC`, tenant, namespace, poolName, state)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.RuntimeHost, 0)
	for rows.Next() {
		host, err := scanRuntimeHost(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, host)
	}
	return out, rows.Err()
}

func (r *runtimeRegistryRepo) HeartbeatRuntimeHost(ctx context.Context, id uuid.UUID, generation int64, active int32, capabilities json.RawMessage) (*controlmodel.RuntimeHost, error) {
	host, err := scanRuntimeHost(r.pool.QueryRow(ctx, `UPDATE runtime_hosts SET
		active=$3, capabilities=COALESCE($4,capabilities),
		state=CASE WHEN state=$5 THEN $6 ELSE state END,
		last_seen_at=now(), updated_at=now()
		WHERE id=$1 AND lease_generation=$2 RETURNING `+runtimeHostColumns,
		id, generation, active, nullJSON(capabilities), controlmodel.RuntimeHostOffline, controlmodel.RuntimeHostOnline))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return host, err
}

func (r *runtimeRegistryRepo) SetRuntimeHostCapacity(ctx context.Context, id uuid.UUID, expectedCapacity, capacity int32) (*controlmodel.RuntimeHost, error) {
	if capacity < 1 || capacity > controlmodel.MaxRuntimeHostCapacity {
		return nil, fmt.Errorf("capacity must be between 1 and %d", controlmodel.MaxRuntimeHostCapacity)
	}
	host, err := scanRuntimeHost(r.pool.QueryRow(ctx, `UPDATE runtime_hosts SET capacity=$3,capacity_managed=true,updated_at=now()
  WHERE id=$1 AND capacity=$2 RETURNING `+runtimeHostColumns, id, expectedCapacity, capacity))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return host, err
}

func (r *runtimeRegistryRepo) SetRuntimeHostState(ctx context.Context, id uuid.UUID, generation int64, state string) (*controlmodel.RuntimeHost, error) {
	host, err := scanRuntimeHost(r.pool.QueryRow(ctx, `UPDATE runtime_hosts SET state=$3,updated_at=now()
		WHERE id=$1 AND lease_generation=$2 RETURNING `+runtimeHostColumns, id, generation, state))
	if errors.Is(err, store.ErrNotFound) {
		return nil, store.ErrConflict
	}
	return host, err
}

func (r *runtimeRegistryRepo) MarkRuntimeHostsOffline(ctx context.Context, before time.Time) (int64, error) {
	tag, err := r.pool.Exec(ctx, `UPDATE runtime_hosts SET state=$2,updated_at=now()
		WHERE last_seen_at < $1 AND state <> $2`, before, controlmodel.RuntimeHostOffline)
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}

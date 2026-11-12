#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
if [[ -e .env ]]; then
    echo '.env already exists; credentials and version were preserved.'
    exit 0
fi
if [[ $# != 2 || ! "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?$ || ! "$2" =~ ^[a-z0-9][a-z0-9./:_-]+$ ]]; then
    echo 'Usage: ./init-env.sh VERSION REGISTRY/NAMESPACE' >&2
    exit 2
fi
command -v openssl >/dev/null
umask 077
# Generate every value before creating the file; noclobber also protects concurrent starts.
pg=$(openssl rand -hex 24)
jwt=$(openssl rand -hex 32)
internal=$(openssl rand -hex 32)
vault=$(openssl rand -hex 32)
admin=$(openssl rand -hex 16)
control_db=$(openssl rand -hex 24)
portal_db=$(openssl rand -hex 24)
data_db=$(openssl rand -hex 24)
hands_db=$(openssl rand -hex 24)
query_signing=$(openssl rand -hex 32)
set -o noclobber
cat > .env <<ENV
IMAGE_REPOSITORY=$2
SERVICE_VERSION=$1
POSTGRES_DB=managed-agents
POSTGRES_PASSWORD=$pg
CONTROL_DB_PASSWORD=$control_db
AGENT_SERVICE_DB_PASSWORD=$portal_db
AGENT_HARNESS_RUNTIME_DB_PASSWORD=$data_db
TOOL_WORKER_DB_PASSWORD=$hands_db
MANAGED_AGENTS_JWT_SECRET=$jwt
MANAGED_AGENTS_INTERNAL_TOKEN=$internal
MANAGED_AGENTS_VAULT_MASTER_KEY=$vault
MANAGED_AGENTS_RUNTIME_REDIS_URI=
MANAGED_AGENTS_RUNTIME_MQ_ENDPOINTS=
MANAGED_AGENTS_QUERY_STORE_PROVIDER=postgres
MANAGED_AGENTS_QUERY_STORE_JDBC_URL=
MANAGED_AGENTS_QUERY_STORE_USER=
MANAGED_AGENTS_QUERY_STORE_PASSWORD=
MANAGED_AGENTS_QUERY_STORE_SIGNING_KEY=$query_signing
MANAGED_AGENTS_QUERY_STORE_BUILD=v3
CONTROL_PLANE_BOOTSTRAP_ADMIN=admin
CONTROL_PLANE_BOOTSTRAP_PASSWORD=$admin
BIND_ADDRESS=127.0.0.1
GATEWAY_PORT=18080
MANAGED_AGENTS_OAUTH_PUBLIC_URL=http://localhost:18080
MANAGED_AGENTS_LOCAL_DEV=false
MANAGED_AGENTS_ALLOW_LOCAL_ENVIRONMENT=false
DASHSCOPE_API_KEY=
MANAGED_AGENTS_E2B_API_KEY=
ENV
echo 'Set Redis, RocketMQ Cluster Proxy and independent query database settings in .env before startup.'
echo 'Created .env (mode 600). Read CONTROL_PLANE_BOOTSTRAP_PASSWORD locally to sign in as admin.'

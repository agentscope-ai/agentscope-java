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
set -o noclobber
cat > .env <<ENV
IMAGE_REPOSITORY=$2
SERVICE_VERSION=$1
POSTGRES_DB=agentscope
POSTGRES_PASSWORD=$pg
BUILDER_JWT_SECRET=$jwt
BUILDER_INTERNAL_TOKEN=$internal
BUILDER_VAULT_MASTER_KEY=$vault
AISTIO_BOOTSTRAP_ADMIN=admin
AISTIO_BOOTSTRAP_PASSWORD=$admin
BIND_ADDRESS=127.0.0.1
GATEWAY_PORT=18080
BUILDER_OAUTH_PUBLIC_URL=http://localhost:18080
BUILDER_ALLOW_LOCAL_ENVIRONMENT=false
DASHSCOPE_API_KEY=
BUILDER_E2B_API_KEY=
ENV
echo 'Created .env (mode 600). Read AISTIO_BOOTSTRAP_PASSWORD locally to sign in as admin.'

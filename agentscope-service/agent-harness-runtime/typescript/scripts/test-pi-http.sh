#!/usr/bin/env bash
set -euo pipefail

pi_workspace="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pi_repository="$(cd "$pi_workspace/../../.." && pwd)"

cd "$pi_workspace"
pnpm -r build
cd "$pi_repository"
mvn -B -ntp -T1 -pl managed-agents/agent-service -am test \
  -Dtest=PiBrainHttpFlowTest,HarnessProtocolStoreTest,HarnessRunConfigurationTest -Dmanaged.pi.http=true \
  -Dsurefire.failIfNoSpecifiedTests=false "$@"

#!/usr/bin/env bash
# Uninstall the AgentScope Control Plane.
#
# Usage:
#   ./install/uninstall.sh [-n namespace] [-r release] [--purge-crds]
#
# By design Helm does NOT remove CRDs (and the custom resources they own) on
# uninstall. Pass --purge-crds to also delete the CRDs and every Agent/ModelConfig/
# MCPServer/AgentTeam/SandboxClaim in the cluster.
#
# Note: session, team message, and team task data now live in the runtime
# Store (memory or PostgreSQL), not as CRDs. Purging that data is a matter of
# dropping the postgres database/schema (or simply restarting when using the
# in-memory driver); it is not affected by this script.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CRD_DIR="$(cd "${SCRIPT_DIR}/../config/crd" && pwd)"

NAMESPACE="agentscope-system"
RELEASE="agentscope"
PURGE_CRDS="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    -n) NAMESPACE="$2"; shift 2 ;;
    -r) RELEASE="$2"; shift 2 ;;
    --purge-crds) PURGE_CRDS="true"; shift ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

command -v helm >/dev/null || { echo "ERROR: helm not found" >&2; exit 1; }

echo ">> uninstalling release '${RELEASE}' from namespace '${NAMESPACE}'"
helm uninstall "${RELEASE}" --namespace "${NAMESPACE}" || true

if [[ "${PURGE_CRDS}" == "true" ]]; then
  echo ">> deleting CRDs (this removes ALL agentscope.io custom resources)"
  kubectl delete -f "${CRD_DIR}" --ignore-not-found
else
  echo ">> CRDs were left in place. Re-run with --purge-crds to remove them."
fi

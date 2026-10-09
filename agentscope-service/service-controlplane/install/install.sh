#!/usr/bin/env bash
# AgentScope Control Plane installer (Helm-based).
#
# Usage:
#   ./install/install.sh [-n namespace] [-p profile] [-r release] [extra helm args...]
#
# Examples:
#   ./install/install.sh                       # default profile
#   ./install/install.sh -p experimental       # enable experimental features
#   ./install/install.sh -p ha --set api.authToken=$(openssl rand -hex 24)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHART_DIR="$(cd "${SCRIPT_DIR}/../helm/agentscope-controlplane" && pwd)"

NAMESPACE="agentscope-system"
RELEASE="agentscope"
PROFILE=""

while getopts ":n:p:r:h" opt; do
  case "${opt}" in
    n) NAMESPACE="${OPTARG}" ;;
    p) PROFILE="${OPTARG}" ;;
    r) RELEASE="${OPTARG}" ;;
    h) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: -${OPTARG}" >&2; exit 1 ;;
  esac
done
shift $((OPTIND - 1))

command -v kubectl >/dev/null || { echo "ERROR: kubectl not found" >&2; exit 1; }
command -v helm >/dev/null || { echo "ERROR: helm not found (https://helm.sh/docs/intro/install/)" >&2; exit 1; }

VALUES_ARGS=()
if [[ -n "${PROFILE}" ]]; then
  PROFILE_FILE="${CHART_DIR}/profiles/${PROFILE}.yaml"
  [[ -f "${PROFILE_FILE}" ]] || { echo "ERROR: unknown profile '${PROFILE}' (${PROFILE_FILE} not found)" >&2; exit 1; }
  VALUES_ARGS+=(-f "${PROFILE_FILE}")
  echo ">> using profile: ${PROFILE}"
fi

echo ">> installing release '${RELEASE}' into namespace '${NAMESPACE}'"
helm upgrade --install "${RELEASE}" "${CHART_DIR}" \
  --namespace "${NAMESPACE}" \
  --create-namespace \
  "${VALUES_ARGS[@]}" \
  "$@"

echo ">> waiting for the controller to become ready"
kubectl -n "${NAMESPACE}" rollout status "deploy/${RELEASE}-controller" --timeout=120s

echo ">> installed CRDs:"
kubectl get crds | grep agentscope.io || true

echo ">> done. Verify with:"
echo "   kubectl -n ${NAMESPACE} get pods"
echo "   kubectl -n ${NAMESPACE} port-forward svc/${RELEASE}-controller 8080:8080 &"
echo "   curl http://localhost:8080/api/v1/version"

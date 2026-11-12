#!/usr/bin/env bash
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.

set -euo pipefail
umask 077

login_mode=cluster
TEST_PROFILE=text
for option in "$@"; do
    case "$option" in
        --password) login_mode=password ;;
        --long-tool) TEST_PROFILE=long-tool ;;
        *) printf '用法：bash cluster-api-shell.sh [--password] [--long-tool]\n' >&2; exit 2 ;;
    esac
done
for dependency in curl jq uuidgen; do
    if ! command -v "$dependency" >/dev/null; then
        printf '缺少命令：%s，请安装后重试。\n' "$dependency" >&2
        exit 1
    fi
done
service_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture_config="$service_root/tests/http-mcp-long-tool/cluster-test.json"
BASE=$(jq -er '.baseUrl' "$fixture_config")
AGENT_ID=$(jq -er '.agentId' "$fixture_config")
ENV_ID=$(jq -er '.environmentId' "$fixture_config")
if [ "$TEST_PROFILE" = long-tool ]; then
    SESSION_ID=$(jq -er '.preparedSessionId' "$fixture_config")
fi
RUN_ID="$(date +%Y%m%d-%H%M%S)-$(uuidgen | tr '[:upper:]' '[:lower:]')"
D="$HOME/managed-agents-api-test/$RUN_ID"
mkdir -p "$D"
USERNAME=admin
if [ "$login_mode" = cluster ]; then
    cluster_config="${MANAGED_AGENTS_TEST_KUBECONFIG:-$service_root/release/dist/deploy-agencore-ma/kubeconfig}"
    if ! command -v kubectl >/dev/null || [ ! -r "$cluster_config" ]; then
        printf '无法读取本机集群凭据；设置 MANAGED_AGENTS_TEST_KUBECONFIG，或加 --password 手动登录。\n' >&2
        exit 1
    fi
    namespace=$(jq -er '.kubernetesNamespace' "$fixture_config")
    credential_secret=$(jq -er '.credentialSecret' "$fixture_config")
    credentials=$(kubectl --kubeconfig "$cluster_config" --request-timeout=15s \
        -n "$namespace" get secret "$credential_secret" -o json)
    USERNAME=$(printf '%s' "$credentials" | jq -er '.data.CONTROL_PLANE_BOOTSTRAP_ADMIN | @base64d')
    PASSWORD=$(printf '%s' "$credentials" | jq -er '.data.CONTROL_PLANE_BOOTSTRAP_PASSWORD | @base64d')
    unset credentials
    printf '使用本机集群凭据登录 %s（%s）\n' "$BASE" "$USERNAME"
else
    printf '登录 %s（%s）\n' "$BASE" "$USERNAME"
    read -r -s -p '管理员密码: ' PASSWORD
    printf '\n'
fi
if ! printf '%s' "$PASSWORD" | jq -Rs --arg username "$USERNAME" '{username:$username,password:.}' \
    | curl --fail-with-body -sS --connect-timeout 10 --max-time 30 \
        "$BASE/api/auth/login" -H 'Content-Type: application/json' \
        --data-binary @- > "$D/login.json"; then
    unset PASSWORD
    printf '登录失败，请检查密码及服务连接后重新运行。\n' >&2
    exit 1
fi
unset PASSWORD
TOKEN=$(jq -er '.token | select(type=="string" and length>0)' "$D/login.json")

api() {
    local path="$1"
    shift
    curl --fail-with-body -sS --connect-timeout 10 --max-time 30 \
        -H "Authorization: Bearer $TOKEN" \
        -H 'X-Managed-Agents-Tenant: default' \
        -H 'X-Managed-Agents-Namespace: default' \
        -H 'Content-Type: application/json' \
        -H 'Managed-Agents-Extensions: turn-inputs-v1' \
        "$BASE$path" "$@"
}
save_state() {
    local name
    for name in BASE RUN_ID D TOKEN AGENT_ID ENV_ID SESSION_ID TURN1 TURN2 TEST_PROFILE; do
        printf 'export %s=%q\n' "$name" "${!name-}"
    done > "$D/active.env"
    declare -f api save_state >> "$D/active.env"
    printf '%s\n' 'set -o pipefail' 'umask 077' "PS1='managed-agents-test> '" >> "$D/active.env"
}
api /api/v1/me/scope > "$D/scope.json"
if [ "$TEST_PROFILE" = long-tool ]; then
    api "/v1/sessions/$SESSION_ID" > "$D/session.json"
    printf '\n已选中 MCP 长工具手动测试 Session：%s\n' "$SESSION_ID"
    printf '从 managed-mcp-long-tool-cluster-walkthrough.md 第 2 节提交 T1/T2。\n'
else
    printf '\n已登录；使用同一 Agent 进行文本测试时，不要求调用 MCP 工具。\n'
fi
save_state
printf '测试记录：%s\n输入 exit 退出测试终端。\n\n' "$D"
exec bash --noprofile --rcfile "$D/active.env" -i

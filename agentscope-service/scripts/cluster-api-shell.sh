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

BASE=http://121.43.66.28
RUN_ID="$(date +%Y%m%d-%H%M%S)-$(uuidgen | tr '[:upper:]' '[:lower:]')"
D="$HOME/agentscope-api-test/$RUN_ID"
AGENT_ID=b65bc0ac-80a1-4979-81b9-da61f358de3b
ENV_ID=env_1bec5a78c20a
mkdir -p "$D"
if [ "$TEST_PROFILE" = long-tool ]; then
    fixture_config="$(cd "$(dirname "${BASH_SOURCE[0]}")/../tests/http-mcp-long-tool" && pwd)/cluster-test.json"
    AGENT_ID=$(jq -er '.agentId' "$fixture_config")
    ENV_ID=$(jq -er '.environmentId' "$fixture_config")
    SESSION_ID=$(jq -er '.preparedSessionId' "$fixture_config")
fi

USERNAME=admin
if [ "$login_mode" = cluster ]; then
    cluster_config="$HOME/.codex/attachments/79ff8960-68d7-448c-b6c9-61839c411b5f/已粘贴的文本.txt"
    if ! command -v kubectl >/dev/null || [ ! -r "$cluster_config" ]; then
        printf '无法读取本机测试集群凭据；如已知管理员密码，可加 --password 手动登录。\n' >&2
        exit 1
    fi
    cluster=(kubectl --kubeconfig "$cluster_config" --request-timeout=15s -n agentscope-test)
    control_secret=$("${cluster[@]}" get deployment service-agentscope-control -o json \
        | jq -er '.spec.template.spec.containers[0].envFrom[] | .secretRef.name // empty')
    credentials=$("${cluster[@]}" get secret "$control_secret" -o json)
    USERNAME=$(printf '%s' "$credentials" \
        | jq -er '.data.CONTROL_PLANE_BOOTSTRAP_ADMIN | @base64d')
    PASSWORD=$(printf '%s' "$credentials" \
        | jq -er '.data.CONTROL_PLANE_BOOTSTRAP_PASSWORD | @base64d')
    unset credentials
    printf '使用本机集群凭据登录 %s（%s）\n' "$BASE" "$USERNAME"
else
    printf '登录 %s（%s）\n' "$BASE" "$USERNAME"
    read -r -s -p '管理员密码（不是用户名 admin）: ' PASSWORD
    printf '\n'
fi
if ! printf '%s' "$PASSWORD" \
    | jq -Rs --arg username "$USERNAME" '{username:$username,password:.}' \
    | curl --fail-with-body -sS --connect-timeout 10 --max-time 30 \
        "$BASE/api/auth/login" -H 'Content-Type: application/json' \
        --data-binary @- > "$D/login.json"; then
    unset PASSWORD
    printf '登录失败，请检查密码及服务连接后重新运行。\n' >&2
    exit 1
fi
unset PASSWORD
if ! TOKEN=$(jq -er '.token | select(type=="string" and length>0)' "$D/login.json"); then
    printf '登录响应缺少有效令牌，请检查 %s/login.json。\n' "$D" >&2
    exit 1
fi

api() {
    local path="$1"
    shift
    curl --fail-with-body -sS --connect-timeout 10 --max-time 30 \
        -H "Authorization: Bearer $TOKEN" \
        -H 'X-AgentScope-Tenant: default' \
        -H 'X-AgentScope-Namespace: default' \
        -H 'Content-Type: application/json' \
        "$BASE$path" "$@"
}

save_state() {
    local name
    for name in BASE RUN_ID D TOKEN AGENT_ID ENV_ID SESSION_ID TURN1 TURN2 TEST_PROFILE; do
        printf 'export %s=%q\n' "$name" "${!name-}"
    done > "$D/active.env"
    declare -f api save_state >> "$D/active.env"
    printf '%s\n' 'set -o pipefail' 'umask 077' "PS1='agentscope-test> '" >> "$D/active.env"
}

if [ "$TEST_PROFILE" = long-tool ]; then
    api "/v1/sessions/$SESSION_ID" > "$D/session.json"
    printf '\n已登录并选中 MCP 长工具专用 Session：%s\n' "$SESSION_ID"
    printf '直接从手册第 10 节提交 T1、T2。\n'
else
    printf '\n已登录，可以从手册第 2 节继续操作。\n'
fi
save_state
printf '测试记录：%s\n' "$D"
printf '输入 exit 退出测试终端。\n\n'
exec bash --noprofile --rcfile "$D/active.env" -i

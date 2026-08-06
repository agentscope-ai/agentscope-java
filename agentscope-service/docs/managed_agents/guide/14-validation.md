# 产品验证清单 · 部署后怎么验收

[← 部署运维](13-operations.md) · [回目录](README.md)

---

面向 **用户侧实操与体验验证**：服务起来之后，用三条路径确认 Managed Agents 主能力可用。  
先完成 [Quickstart](03-quickstart.md)（local + 一轮对话），再按需跑下面进阶场景。

下文假设：

```bash
export BASE=http://localhost:8080
TOKEN=$(curl -s -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | jq -r .token)
AUTH=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
```

---

## 路径 A · local（默认，最快）

**目标**：Brain 本机 FS + 模型 turn 通。

1. 按 [Quickstart](03-quickstart.md) 创建 Agent（`local` Environment）→ Session → `user.message`。
2. 断言：

```bash
curl -s "$BASE/api/sessions/$SESSION_ID/events" "${AUTH[@]}" \
  | jq '[.[] | .type] | unique'
# 期望包含 session.status_running、agent.message、session.status_idle（名称以实际落库为准）
```

3. 失败排查：无 `DASHSCOPE_API_KEY`（或自备 Model Bean）→ turn 无法生成；看 `session.error`。

---

## 路径 B · sandbox = E2B（平台托管 hands）

**目标**：shell/文件工具在 **E2B 云沙箱**执行，不依赖本机 Docker。

### 前置

```bash
export BUILDER_E2B_API_KEY=ek_xxx   # 或 E2B_API_KEY；重启 Brain 使全局配置生效
# 也可在创建 Environment 时把 apiKey 放进 config（勿提交到仓库）
```

缺 key 时创建 `type=sandbox` 环境应 **400**。

### 步骤

```bash
# 1) 创建 E2B 环境（无全局 key 时在 config 里带 apiKey）
ENV=$(curl -s -X POST "$BASE/api/environments" "${AUTH[@]}" \
  -d '{
    "name": "trial-e2b",
    "type": "sandbox",
    "config": {
      "templateId": "base",
      "isolationScope": "SESSION",
      "sandboxTimeoutSeconds": 300
    }
  }')
echo "$ENV" | jq '{id,type,config}'
ENV_ID=$(echo "$ENV" | jq -r .id)

# 2) Agent：打开 shell 类工具（名称以默认 toolset 为准，常见为 execute）
AGENT=$(curl -s -X POST "$BASE/api/agents" "${AUTH[@]}" \
  -d '{
    "name": "E2B Trial",
    "system": "Use tools when needed. Prefer execute for shell.",
    "tools": [{
      "type": "agent_toolset",
      "defaultConfig": {
        "enabled": true,
        "permissionPolicy": { "type": "always_allow" }
      }
    }]
  }')
AGENT_ID=$(echo "$AGENT" | jq -r .id)

# 3) Session + 触发工具的消息
SESSION=$(curl -s -X POST "$BASE/api/sessions" "${AUTH[@]}" \
  -d "{\"agent\":\"$AGENT_ID\",\"environmentId\":\"$ENV_ID\"}")
SESSION_ID=$(echo "$SESSION" | jq -r .id)

curl -s -X POST "$BASE/api/sessions/$SESSION_ID/events" "${AUTH[@]}" \
  -d '{
    "events": [{
      "type": "user.message",
      "payload": { "text": "Run: echo hello-from-e2b && uname -a" }
    }]
  }' | jq .

# 4) 等待 idle 后看事件（应有 tool_use / tool_result 或等价落库）
sleep 15
curl -s "$BASE/api/sessions/$SESSION_ID/events" "${AUTH[@]}" \
  | jq '[.[] | {type, payload}]'
```

### 验收标准

| 检查 | 期望 |
|---|---|
| 创建 sandbox 无 key | HTTP 400，文案含 E2B API key |
| turn 成功 | 最终 `session.status_idle`（或等价），工具输出含 `hello-from-e2b` |
| 执行面 | **不是** Brain 宿主机 Docker；E2B 控制台可见 sandbox 活动（有账号时） |

缺口（packages / limited networking）见 [SANDBOX_GAPS.md](../WIP/SANDBOX_GAPS.md)。

---

## 路径 C · self_hosted（生产 Hands：纯出站 Worker）

**目标**：独立 `HandsWorkerMain` 执行外化工具并续跑（进程内 Worker 已随四层拆分移除，无需任何开关）。  
**不要**与 `sandbox`（E2B）混淆。

### Brain

按 [部署运维](13-operations.md) 起好四平面（`scripts/dev-up.sh` 或 compose）即可——数据面从不在本机执行 self_hosted 工具。

### 创建 self_hosted 环境（保存 environmentKey）

```bash
ENV=$(curl -s -X POST "$BASE/api/environments" "${AUTH[@]}" \
  -d '{"name":"trial-self-hosted","type":"self_hosted","config":{}}')
ENV_ID=$(echo "$ENV" | jq -r .id)
ENV_KEY=$(echo "$ENV" | jq -r .environmentKey)
echo "ENV_ID=$ENV_ID"
echo "ENV_KEY=$ENV_KEY"   # 只出现一次！
```

### 启动 Worker（另进程，仅出站）

```bash
java -cp service-scheduler/target/service-scheduler-*.jar io.agentscope.builder.worker.HandsWorkerMain \
  --base-url "$BASE" \
  --environment-id "$ENV_ID" \
  --environment-key "$ENV_KEY" \
  --hands-root /tmp/agentscope-hands \
  --worker-id worker-trial-1
```

（兼容别名：`--token` = `--environment-key`。）

### Session + 工具消息

创建绑定该 `ENV_ID` 的 Agent/Session，发送会触发 `execute` / 文件工具的指令（同路径 B）。观察：

1. Session 进入 `requires_action`，事件含 `agent.tool_use`（带 `input`）。
2. Worker 日志出现 `Executed …`；随后 Brain 续跑到 `idle`。
3. **关 Worker 再发消息**：应停在 `queued` / `requires_action`，**不会**在 Brain 本机执行 shell。

运维队列：

```bash
curl -s "$BASE/api/environments/$ENV_ID/work/stats" "${AUTH[@]}" | jq .
```

协议细节：[08-hands-worker.md](08-hands-worker.md)、[events/worker.md](../events/worker.md)。剩余缺口：[SELF_HOSTED_GAPS.md](../WIP/SELF_HOSTED_GAPS.md)。

---

## 路径 D · HITL（可选）

Agent tool 设 `permissionPolicy.type=always_ask` → turn 出现确认事件 → 客户端发 `user.tool_confirmation`。见 [Events](07-events.md)。

---

## 部署形态对照（验收选哪条）

| 验收目标 | Environment | Brain 配置 | 额外进程 |
|---|---|---|---|
| 对话 / 事件流 | `local` | 默认 | 无 |
| 云端隔离执行 | `sandbox` | `BUILDER_E2B_API_KEY` | 无 |
| 客户机执行 / NAT | `self_hosted` | 默认 | `HandsWorkerMain` |
| 开发同机 self_hosted | `self_hosted` | 默认 | 同机起一个 `HandsWorkerMain` |

---

## 常见踩坑

| 现象 | 原因 |
|---|---|
| 创建 sandbox 400 | 未配置 E2B API key |
| self_hosted 一直 idle / 无工具执行 | Worker 未启动 / environment key 错误；或 work 租约被旧 Worker 占用（看 `…/work/stats`） |
| 文档里的 Docker / `builder.sandbox.*` | **已废弃**；Managed sandbox 只认 E2B |
| 多副本 SSE 丢 delta | 正常：`event_deltas` 仅 turn-owner best-effort；以 `GET …/events` 为准 |
| 把 E2B 当 self_hosted | 产品边界反了：E2B=`sandbox`，客户 Worker=`self_hosted` |

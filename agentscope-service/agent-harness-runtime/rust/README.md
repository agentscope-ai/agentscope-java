# Codex Rust Brain

[English](README.en.md)

这是直接链接开源 `codex-core` 的独立 Rust Brain。运行时不需要 Node.js，不启动 Codex CLI 或 app-server。Session Service 仍是事务、租约、工具 Outbox 和接受结果的权威；Hands 仍由现有 Tool Worker 执行。

## 模块与构建

- `crates/runtime`：协议 0.1.0、JCS/确定性 ID、HTTP 对象校验、回执恢复、租约、工具/确认挂起、结果应用、预算和 worker。
- `crates/adapter-codex`：原生 ThreadManager、动态工具、rollout 检查点、原 call_id 注入、原 Turn 恢复，以及 Rust 进程内的模型 HTTP 出口。
- `crates/brain-codex`：配置、单二进制入口、健康检查、SIGTERM/SIGINT。

固定上游提交 `e742db9f7ba98e63e502b310857d49d047888518`，Rust 1.95.0。`scripts/prepare-codex.sh` 校验源码归档 SHA-256 后解包到被 Git 忽略的 `target/upstream`；没有内核补丁。`Cargo.lock` 固定依赖解析。不要单独替换上游目录或接受其他版本的原生检查点。

```sh
cd managed-agents/agent-harness-runtime/rust
sh scripts/prepare-codex.sh
cargo test --locked --workspace
cargo build --locked -p managed-brain-codex
python3 scripts/smoke-worker.py
cargo fmt -p managed-brain-runtime -p managed-adapter-codex -p managed-brain-codex -- --check
```

需要联网下载源码和 Cargo 依赖；Linux 构建需要 C/C++ 工具链、CMake、pkg-config、OpenSSL headers、protoc。上游依赖较大，禁用增量编译并默认限制为 3 个编译任务。执行 `cargo clean` 后需要重新准备上游源码。测试线程和服务 worker 使用 16 MiB 栈以容纳内核的 debug futures。

## 持久化边界

1. 模型请求先经过租约检查、`models/reserve`、原生检查点与 model dispatch 提交；确认回执后才发往模型提供方。
2. 模型响应在进程内 Rust HTTP 出口完整校验后交给内核；只允许配置的 `hands_*` 动态工具。执行环境使用原生 `EnvironmentManager::without_environments`。
3. 一轮中的动态工具全部出现后，捕获原始 rollout，再调用 `suspend_turn_and_shutdown`，并等待 ThreadManager 关闭。取消清理可能生成的失败记录只留在该实例的临时 store，永远不覆盖已捕获的恢复点。
4. 在原生线程空闲时解决工具权限；`always_ask` 提交确认请求并挂起。工具调用、确定性 taskId/executionId 和完整检查点一起提交后，Session Service 才能驱动 Hands。
5. A 已完成、B 未完成时，将 A 的原始结果注入空闲线程并提交 `resultApplications`；只将 B 列为待执行任务。等待超时提交 suspension 并释放所有权。
6. 新实例从 Session Service 加载检查点和已接受结果，校验身份，以 `inject_response_items` 写回原 `call_id`，持久化应用位置，再用 `recover_turn_if_idle` 继续原 Turn。不会新建用户输入，不依赖上一个进程中的 oneshot，不重新派发已授权工具。
7. 检查点记录平台 Turn 绑定。Session 的下一轮消息保留原线程历史，并通过 `start_turn_if_idle` 开启新原生 Turn；上一轮取消留下的未完成 call 只补历史结束记录，不再次派发到 Hands。

Adapter 的 `ScratchStore` 实现原生 ThreadStore 接口，作为当前实例的暂存区；Session Service 的带 fence 检查点提交才是跨进程持久化边界。丢失回执时查询原 commitId，只在明确 `receipt_not_found` 后重发完全相同的请求。租约失效或结果身份冲突时停止该实例。

## 运行配置

| 变量 | 说明 |
| --- | --- |
| `SESSION_SERVICE_URL` | 例如 `http://agent-service:8084/internal/session-service/v0` |
| `MANAGED_AGENTS_INTERNAL_TOKEN` | Session Service 内部凭证 |
| `CODEX_MODEL_API_KEY` | 默认 provider 的可选凭证；使用显式 credentialEnv 时不需要此变量 |
| `CODEX_MODEL_BASE_URL` | Responses HTTP API 基础 URL，默认 `https://api.openai.com/v1` |
| `CODEX_MODEL_PROVIDER` | 默认模型绑定的 provider，默认 `openai` |
| `BRAIN_CONCURRENCY` | 同时持有的 Session 数，默认 4，范围 1–64 |
| `BRAIN_INLINE_WAIT_MS` | 等工具结果的时间，默认 1000，范围 0–600000 |
| `BRAIN_INSTANCE_ID` | 实例名称；每次启动另生成 incarnationId |
| `PORT` | 健康服务端口，默认 8082 |

Agent Service 配置 `MANAGED_AGENTS_HARNESS_TYPE=codex`、`MANAGED_AGENTS_MODEL_PROVIDER` 和 `MANAGED_AGENTS_RUNTIME_SESSION_QUEUE_ENABLED=true`。已有 Session 的内核绑定不能直接切换；使用新 Session。Brain 不需要平台数据库、Redis、MQ、对象存储或 Hands 的凭证，也不挂载 Hands workspace。

从仓库根目录构建镜像：

```sh
docker build -f managed-agents/agent-harness-runtime/images/codex/Dockerfile \
  -t managed-agents-brain-codex:dev .
```

Helm 支持 `brain.type=codex`。设置 `brain.codex.existingSecret` 为只包含内部 token 和模型凭据的专用 Secret，并开启 `runtime.sessionQueue.enabled=true`。`brain.codex.modelProvider`、`modelOptions`、`modelBindings` 传入 Agent Service，模型凭据只传入 Brain；`modelBaseUrl` 是 Brain 的默认 Responses endpoint。生成的 Brain 使用独立 Rust 镜像、非 root UID 10001、只读根文件系统和临时目录，不运行 Java dataplane schema Job，不挂载 DB/Redis/MQ/OSS 凭据。Service、Runtime 和 Control Plane 的 schema 初始化仍须执行。

配套 Compose 文件为 `images/codex/compose.override.yaml`。`--version` 输出上游提交和协议版本，`--health-check` 检查 `/readyz`。`/healthz` 检查进程存活；`/readyz` 在成功连接 Session Service 后就绪，排空或连接失败时返回 503。停止进程不取消 Hands 已接受的任务，后续实例按平台恢复。

## 模型绑定与字段映射

Agent Service 的 `MANAGED_AGENTS_MODEL_BINDINGS` 是 model 别名到完整 `ModelConfiguration` 的 JSON 对象，适用于通用 PI / PI Durable / Codex 配置路径。非空目录中找不到别名时拒绝，不回退到默认 provider。目录为空时沿用定义中的 model ID、`MANAGED_AGENTS_MODEL_PROVIDER` 和 `MANAGED_AGENTS_MODEL_OPTIONS`；后者兼容原有 `MANAGED_AGENTS_PI_MODEL_OPTIONS`。本 Turn 首次编译后连同配置摘要固定，恢复不会重读更新后的目录。AgentScope prepared 工厂接入此目录仍待完成。

控制面的 `system` 映射到原生 `base_instructions`，替换 Codex CLI 默认身份；不再注入对应于已关闭的本地执行/提权工具的原生权限提示。实际工具权限由平台 Hands 链路执行。独立 ACS 镜像与真实功能验收见[部署记录](../../../docs/acs-pi-codex-acceptance-20261030.md)。

例如，在 Service 中配置以下非敏感目录，并在 Brain 部署 Secret 中注入 `MANAGED_MODEL_FAST`。自定义名称须显式加入容器环境；Compose 不会自动转发宿主机的全部环境变量。

```json
{
  "managed-fast": {
    "provider": "managed",
    "id": "served-model",
    "api": "openai-responses",
    "baseUrl": "https://models.example.invalid/v1",
    "credentialEnv": "MANAGED_MODEL_FAST",
    "contextWindow": 32000,
    "outputLimit": 2048,
    "reasoning": false,
    "parameters": { "temperature": 0.2, "maxTokens": 512 }
  }
}
```

| 字段 | Codex 消费方式 |
| --- | --- |
| provider / id | provider 选择凭据绑定，id 设置原生模型并校验出口请求一致 |
| api | 仅支持 `openai-responses`；其他 API 明确拒绝 |
| baseUrl / credentialEnv | 每个实例独立模型出口；自定义 endpoint 必须有受限凭据引用，不能复用默认凭据；支持 `MANAGED_MODEL_` 与兼容的 `PI_MODEL_` 前缀 |
| contextWindow | 写入实例的原生模型目录，覆盖默认窗口及其最大值；自定义 endpoint 必须同时声明 contextWindow / outputLimit |
| outputLimit / parameters.maxTokens | 转换为 Responses `max_output_tokens`；maxTokens 不能超过显式限额，原生更小的单次请求上限仍保留 |
| parameters.temperature / reasoning | 分别写入 `temperature` / `reasoning.effort`；reasoning 元数据同步到原生目录；显式关闭 reasoning 时不发送 reasoning 参数与对应 include |
| cost | 校验并保留模型价格元数据；平台仍由自身价格配置结算，不能据此声称按该字段完成计费 |

endpoint 必须是无用户信息、query 或 fragment 的 HTTP(S) URL。凭据正文仅在出口内存中解析，不进入配置、模型预算请求或原生 checkpoint。缺失引用或 API / 限额 / reasoning 配置不一致时拒绝执行。没有显式绑定时，默认 provider 可继续使用 `CODEX_MODEL_BASE_URL` / `CODEX_MODEL_API_KEY`；非默认 provider 必须提供独立 endpoint 和凭据引用。

模型绑定定向验证与剩余范围见[验证记录](../testing/conformance/verification-model-bindings-20261029.md)。

## 当前支持范围与限制

首版支持单 root 线程及连续用户 Turn、文本 Responses HTTP、动态 function tools、完整原生历史检查点、工具确认/拒绝、部分结果、原 Turn 冷恢复、取消和模型调用预算。每个内核实例推进一个模型响应；工具边界重建实例，因此没有恢复 Tokio Future 的需求。模型 SSE 的公开文本通过有界预览通道增量发布；可执行响应仍整段校验后才进入 Core，响应上限 64 MiB。参见[预览协议与验证](../testing/conformance/verification-previews-20261029.md)。

尚未扩展子 Agent 树恢复、Code Mode、原生本地 shell/filesystem、ChatGPT 登录、WebSocket/Realtime、多模态工具内容转换、跨版本检查点迁移和长历史分段/增量快照。原生自动压缩等需要一个实例连续发出多个模型请求的路径尚未接入。普通运行中追加消息/steer 尚未接入原生输入通道，终结时会明确拒绝未应用输入。正式上线仍须真实 Session Service + MQ + Hands 的容器故障、网络分区和容量验收；库内核/协议测试不替代这些验收。

本轮执行结果见 [Codex Rust 验收记录](../testing/conformance/verification-codex-rust-20261028.md)。

Codex 的 Runtime、Adapter 和 Brain 入口均位于本 Rust workspace；TypeScript workspace 只用于 PI / PI Durable。

# PI Brain 镜像与部署

[English](README.en.md)

同一 Node 22 镜像包含 `pi` 和 `pi-durable`，由 `MANAGED_AGENTS_HARNESS_TYPE` 选择；Session 的既有内核绑定不会随部署配置改变。Brain 只访问 Session Service 和模型 endpoint；DB、MQ、对象存储仍由 Service/Hands 持有。平台能力和剩余工作见 [实施文档](../../typescript/PI-IMPLEMENTATION.md)。

## 构建与发布入口

从仓库根目录执行，默认只构建并加载本地单架构镜像：

```sh
python3 managed-agents/release/release.py images-pi \
  --version 2.1.0-BETA1 --repository registry.example/team \
  --platforms linux/amd64 --output /tmp/pi-image-release
```

`images-pi` 只处理 `managed-agents-brain-pi`；普通 `images` 保持现有五组件行为。输出包含 OCI version/revision 标签和 `image-brain-pi.json` 构建元数据。正式发布可显式添加 `--push`、使用 `linux/amd64,linux/arm64`；这会沿用干净工作区要求并生成 SBOM/provenance。`--skip-existing --push` 验证已有镜像源码/架构并写入独立 `images-pi.json`，不覆盖五组件的发布清单。2026-10-30 已发布 Linux amd64 镜像，在独立 ACS namespace 完成 `pi` 基础验收，见 [部署记录](../../../../docs/acs-pi-codex-acceptance-20261030.md)。本次采用 Linux 构建 Pod 与 OCI 组装，不代表上述标准发布流水线、多架构或 `pi-durable` 验收已完成。

Dockerfile 固定 pnpm 10.28.2，安装锁文件依赖、构建并执行 `pnpm deploy --legacy --prod`。运行阶段是非 root Node，不需要 Maven/JVM、工具执行二进制或本地 SDK 凭证文件。

## Helm

创建一个专用 Secret，只包含 `MANAGED_AGENTS_INTERNAL_TOKEN` 和选定模型凭证（如 `PI_MODEL_KEY`，或内置 provider 的 `OPENAI_API_KEY`）。internal token 必须与 Service 一致。示例值覆盖：

```yaml
brain:
  type: pi-durable
  pi:
    existingSecret: managed-pi-brain
    modelProvider: managed-openai
    modelOptions:
      api: openai-completions
      baseUrl: https://models.example/v1
      credentialEnv: PI_MODEL_KEY
      contextWindow: 128000
      outputLimit: 4096
      cost: {input: 1, output: 2, cacheRead: 0.1, cacheWrite: 1}
      parameters: {temperature: 0.2, maxTokens: 2048}
runtime:
  sessionQueue:
    enabled: true
```

模型 `id` 仍来自封存 Agent 版本的 `model`。`cost` 是每百万 token 的价格，须按供应商实际配置；自定义模型不能省略。自定义 endpoint 要求显式 `credentialEnv`，只允许 `PI_MODEL_` 前缀，不能引用平台内部 token。凭证值只进入内存 CredentialStore，不写入 Turn 配置、原生快照或模型请求事实。`parameters.maxTokens` 同时限制普通推理和辅助推理；更小的原生请求上限仍保留。当前只提供安装级默认配置，在首次加载 Turn 时封存；尚未接 Control Plane 的逐模型配置和租户 Vault 凭证动态下发。

新 PI Deployment 使用 `/healthz`、`/readyz`、`/metrics`，不挂载数据库/MQ/对象存储 Secret、共享工作区 PVC 或 Java init container。根文件系统只读，`/workspace`、`/tmp` 为临时目录。PI 不运行 Java Brain 的 dataplane schema Job；Service、Managed Runtime、Control Plane 的迁移仍保留。默认 `brain.type=agentscope-java` 保持现有部署。

默认退出窗口为 drain 30 秒 + abort grace 2 秒，Kubernetes 宽限期 40 秒；模板要求后者大于前两者之和。`replicas/resources` 继续使用 `agent-harness-runtime` 值。必须先升级 Session Service 的分页/延后协议，再升级 PI Brain。分段状态可读取旧完整快照；写入新分段状态后，旧 Brain 镜像会拒绝读取，因此不能直接回退到不支持分段格式的镜像。

## Compose 与运行参数

仓库提供 `compose.override.yaml`；使用 Compose 支持 `!override` 的版本与基础 `managed-agents/deploy/compose.yaml` 合并。设置 `MANAGED_AGENTS_PI_PROVIDER` 和可选 `MANAGED_AGENTS_PI_MODEL_OPTIONS`（同上 `modelOptions` 的 JSON），凭证通过环境/Secret 注入。示例只透传 `PI_MODEL_KEY` 和常见内置 provider key；使用其他凭证名时需显式增加映射。

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `SESSION_SERVICE_URL` / `MANAGED_AGENTS_INTERNAL_TOKEN` | 必填 | 内部协议 URL 与凭证 |
| `BRAIN_CONCURRENCY` | 4 | 1..64，挂起后释放并发槽 |
| `BRAIN_INLINE_WAIT_MS` | 1000 | 0..600000，Turn 封存值优先 |
| `BRAIN_POLL_MS` / `BRAIN_HTTP_TIMEOUT_MS` | 500 / 30000 | 轮询间隔 / 单次 HTTP 超时 |
| `BRAIN_DRAIN_MS` / `BRAIN_ABORT_GRACE_MS` | 30000 / 2000 | 停止接单后的排空与中断期限 |
| `BRAIN_RETRY_BASE_MS` / `BRAIN_RETRY_MAX_MS` | 1000 / 300000 | 带抖动的指数退避范围 |
| `BRAIN_RETRY_BUDGET` | 8 | 达到次数后按最大间隔冷却；不会把结果不确定的工具转成失败 |

重试次数是进程内有界缓存；Service 的 not-before 持久化，但累计次数、告警及人工隔离队列尚未实现。延后必须使用原 delivery 和同一 deferralId；有 owner 时携带 fence，不释放租约、不修改工具身份。readiness 依赖最近一次 Service 轮询成功，liveness 只表示进程可响应。`/metrics` 提供 active、ready、认领/完成执行、重试、owner 冲突、延后失败计数；完整模型/工具 Trace 仍待接入。

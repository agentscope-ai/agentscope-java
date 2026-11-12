# Agent Harness Runtime

> 历史导入说明，仅用于来源追踪。以下路径、构建命令和支持状态属于原仓库，
> 不适用于当前 Durable；请阅读 [当前说明](README.md)。

[English](README.en.md)

Brain 实现统一在此目录；每次部署只选一个内核。协议与实现范围见 [implementation-map](protocol/implementation-map.md)。

Codex 发布版 app-server 的未来验证方向见 [durable-harness 集成方式备选方案](../docs/design/codex-app-server-alternative.md)。该方案尚未实施，当前继续使用 Rust Brain。

```text
protocol/                    HTTP、逻辑消息、Schema、交接样例
java/runtime/                平台 HTTP 客户端、JCS、Schema 校验
java/codec-agentscope/        原生 checkpoint/事件 codec，无执行器和平台存储依赖
java/adapter-agentscope/      AgentScope 原生存储、执行、恢复与转换
java/brain-agentscope/        Java Brain 组装、原调度与服务入口
typescript/packages/runtime/ 协议客户端、租约、提交、工具等待、输入与 worker
  adapter-pi/                普通 PI SessionManager / PI durable Storage
  brain-pi/                  PI / PI Durable 启动入口
rust/                        独立 Codex Rust 内核、平台 runtime 与 Brain 入口
images/                      各 Brain 镜像
testing/conformance/         共享行为验收入口
```

Agent Service 显式使用轻量 `codec-agentscope` 处理 checkpoint 管理；它不加载完整 Adapter 或启动 Brain。TaskRepository、原生 StateStore 和 SkillRepository 接口实现归 Adapter，公共预览层只接收文本增量。

Session Service 的接口、事务与后台流程统一位于 `../agent-service/src/`：`agentservice/sessionservice` 包承载接口与装配，`runtime/managed` 包承载事务与后台流程。它们属于同一个 Agent Service Maven 模块、镜像和 JVM。Hands 在 `../tool-worker/`，部署使用 `*-exec.jar`。TypeScript 不加入 Maven reactor。

`common` 保留跨角色共享的实体、工具契约、结果发布、基础设施与账本访问。Hands 异步执行、Session Service 子任务执行、AgentScope 原生辅助类及 Brain 本地装配各归所属模块；具体类与剩余依赖见 [实现映射](protocol/implementation-map.md#java-模块职责收拢)。

从仓库根目录构建 Java：

```sh
mvn -B -ntp -T1 -pl managed-agents/agent-service,managed-agents/agent-harness-runtime/java/brain-agentscope,managed-agents/tool-worker -am package -DskipTests
```

Java Brain 配置 `MANAGED_AGENTS_SESSION_SERVICE_URL=http://agent-service:8084/internal/session-service/v0`，沿用内部服务 token。启动时先注册远程进程租约；调度协调已走 Service。Redis/MQ 传输、本地 JPA 目录/记忆及部分基础设施装配仍需要原配置。


AgentScope 本轮链路核对与修复见 [implementation-map](protocol/implementation-map.md#agentscope-完整链路核对)。独立 Codex 实现见 [Rust Brain](rust/README.md)；各内核仍需分别完成真实环境验收。

PI 使用 Node 22+、pnpm 10.28.2：

```sh
cd managed-agents/agent-harness-runtime/typescript
pnpm install --frozen-lockfile --ignore-scripts
pnpm -r build
SESSION_SERVICE_URL=http://localhost:8084/internal/session-service/v0 \
  MANAGED_AGENTS_HARNESS_TYPE=pi node packages/brain-pi/dist/index.js
```

同时配置 `MANAGED_AGENTS_INTERNAL_TOKEN` 和所选模型提供方凭证。Agent Service 的 `MANAGED_AGENTS_HARNESS_TYPE` 必须相同；启用 `MANAGED_AGENTS_RUNTIME_SESSION_QUEUE_ENABLED=true`。`MANAGED_AGENTS_PI_PROVIDER` 指定 PI provider，模型 ID 来自 Agent 定义。`pi-durable` 使用同一 Brain 入口，替换内核类型即可。TypeScript Brain 不需要数据库、Redis、MQ 或对象存储凭证。

`images/pi/compose.override.yaml` 可与现有 deploy/compose.yaml 合并；镜像构建上下文是仓库根目录。这里只提供配置，尚未构建或部署容器。Codex 使用直接链接开源内核的独立 Rust 镜像，构建、运行配置与支持范围见 [Rust Brain](rust/README.md)。TypeScript workspace 仅包含 Runtime、PI Adapter 和 PI Brain 三个包。

`/healthz` 和 `/readyz` 位于 Node Brain 的 8082 端口。SIGTERM/SIGINT 停止接单并取消本进程的内核工作；原工具执行由 Hands 保留。

轻量检查见 [conformance](testing/conformance/README.md)。上线前仍需真实依赖与故障验收。

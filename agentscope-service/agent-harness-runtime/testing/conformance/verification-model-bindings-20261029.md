# 模型绑定与 Codex 参数消费验证

[English](verification-model-bindings-20261029.en.md)

日期：2026-10-29。目录 `/Users/ken/agentcore/agentcore-managed-agents`，分支 `master`，HEAD `ed482512993468e8dd68c9dac0a06f63b752fde5` 加已有工作区改动。本记录覆盖 W06 的通用模型目录、PI 凭据兼容和 Codex 模型字段消费；W06 尚未整体关闭。

## 实现与行为

- Service 新增 `managed-agents.runtime.model-bindings` / `MANAGED_AGENTS_MODEL_BINDINGS`，按有效定义的 model 别名选择完整绑定；非空目录拒绝未知别名，不混入另一 provider 的默认 options。空目录沿用原默认方式，并提供中性的 `model-options`，兼容 PI 旧属性。
- 模型绑定进入既有按 Turn 固定且带摘要的配置。配置测试实际从加密封存定义和 Session override 选中别名，检查原生 ID / provider / credentialEnv；更换编译器及部署默认值仍返回原配置。
- Java 共同模型契约补 Schema 之外的 endpoint、凭据引用、上下文/输出上限及 reasoning 约束；Codex 拒绝非 Responses API，自定义 endpoint 需要显式窗口及输出限额。校验在 MCP 封存和模型派发前完成，但不是完整控制面能力准入。
- 协议和 PI 接受 `MANAGED_MODEL_` 与原有 `PI_MODEL_` 引用。PI 校验请求输出上限和原生 reasoning 能力，并验证同 provider 的独立实例不串 endpoint 或密钥。
- Rust 不再丢弃 ModelConfiguration 的已声明字段，拒绝未知字段及不合法语义。每个原生实例独立解析 endpoint 与凭据；显式引用缺失不回退到进程默认 key。key 不进入预算请求或持久化对象。
- Codex 原生模型目录使用配置的 contextWindow 和 reasoning 信息；HTTP 出口映射 temperature、max_output_tokens 和 reasoning.effort。映射发生在预算 gate 前，预算记录与实际请求一致；reasoning=false 不发送对应参数。保留工具 allowlist、单实例单模型响应、预览及恢复边界。
- 默认 provider 继续支持进程默认 endpoint/key；显式 credentialEnv 不再要求设置 CODEX_MODEL_API_KEY。Compose 转发模型目录及 options，自定义 Secret 环境名仍由部署显式注入。

## 验证

| 检查 | 结果与范围 |
| --- | --- |
| Java 定向 Maven | 11 项、零失败/跳过：配置 7、PI HTTP 4；相关模块构建及格式检查通过 |
| TypeScript workspace | build 通过；Runtime 37、PI Adapter 37 项通过；历史 TS Codex probe 3 项不计 Rust 验收 |
| Rust workspace | 31 项通过：Adapter 单元 7、真实内核集成文件 7、Runtime 单元 5、预览 3、恢复 9 |
| Rust 二进制 | 构建与 smoke 通过；无 CODEX_MODEL_API_KEY、使用命名凭据环境的进程可启动，健康状态恢复、队列鉴权、格式错误和 SIGTERM 正常 |
| 协议 | 10 Schema、19 operation、110 正例、46 反例；各 4 个 JCS / identity 向量通过 |
| Compose | 使用合成配置与空默认 key 完成 `docker compose config --quiet`；未部署 |

新增真实 codex-core 用例在同一个 Kernel 上并发运行两个配置，访问不同 HTTP 路径，检查 provider 对应的凭据、原生 model ID、不同 temperature / 输出限额、reasoning 开关、最终成功以及持久化对象不含密钥。模型出口为本地确定性 fixture，Session Service 为协议替身；并非 Rust 与真实 Service 的完整控制面链路。

原生模型目录测试通过 Core 的 ModelsManager 读取结果，确认显式窗口不会被默认 maximum 静默截小。首次回归曾因对所有 fallback metadata 强制窗口而破坏原默认模型路径；现仅对显式自定义 endpoint 要求窗口，最终全套通过。未修改固定版本上游内核。

```sh
# 仓库根目录
make -C managed-agents/agent-harness-runtime/protocol check PYTHON=/tmp/managed-agents-protocol-venv/bin/python
mvn -B -ntp -T1 -pl managed-agents/agent-service -am test -Dtest=HarnessRunConfigurationTest,PiBrainHttpFlowTest -Dmanaged.pi.http=true -Dsurefire.failIfNoSpecifiedTests=false

# typescript 目录
pnpm -r build
pnpm -r test

# rust 目录
/Users/ken/.cargo/bin/cargo test --locked --workspace
/Users/ken/.cargo/bin/cargo build --locked -p managed-brain-codex
python3 scripts/smoke-worker.py
/Users/ken/.cargo/bin/cargo fmt -p managed-brain-runtime -p managed-adapter-codex -p managed-brain-codex -- --check
```

本地日志 `/tmp/managed-brain-model-java.log`、`/tmp/managed-brain-model-ts.log`、`/tmp/managed-brain-model-rust.log`。没有全仓 clean verify / spotless、镜像构建、容器部署、提交或推送。

## 剩余范围

- 后续已接入 AgentScope prepared 共同模型选择及工厂，见[AgentScope 模型配置验证](verification-agentscope-models-20261029.md)。本记录保留当时的测试范围；子 Agent 模型/参数、原生注册模型固定语义等仍需接续。
- W07 的控制面按内核版本准入、能力查询和全部字段覆盖仍待实施。当前拒绝发生在通用配置编译/worker/Adapter，不能等同 Session 创建前已拒绝。
- cost 只作为模型元数据保留，平台定价仍独立；需要统一模型别名与计费身份、缓存价格和 unknown 策略。凭据当前来自受限环境引用，模型 Vault 绑定/撤销语义仍需统一。
- Codex 当前原生传输为 Responses API；其他 API 若要支持需要额外的协议转换或上游扩展，不能只修改 api 枚举。该限制已有明确拒绝，不宣称多 API 等价。
- 真实控制面与 Service 的同定义全链路、真实模型参数兼容、公共 SSE、资源/长上下文/子 Agent 和发布验收按 W05—W20 继续。

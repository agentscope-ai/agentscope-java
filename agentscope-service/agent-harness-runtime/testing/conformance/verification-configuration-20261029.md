# 有效运行配置身份与原生组装验证

[English](verification-configuration-20261029.en.md)

日期：2026-10-29。目录 `/Users/ken/agentcore/agentcore-managed-agents`，分支 `master`，HEAD `ed482512993468e8dd68c9dac0a06f63b752fde5` 加已有未提交工作区。本记录覆盖 W05 的通用配置身份和 W19 的部分组装验证；W05 / W07 尚未整体完成。

## 实现

- Service 从加密封存的定义读取 Agent ID / version，通过公共合并器应用 overrides，编译模型、工具和指令。通用配置响应新增 `managed-effective-agent-v1` identity；原始定义、有效定义和完整响应分别使用 RFC 8785 / SHA-256 摘要。
- 整个响应按 Session / Turn 缓存。读取时验证 Schema、摘要、scope、Turn 和数据库中的内核绑定；重新创建编译器或改变部署模型默认值不改变当前 Turn。未带 identity 的旧开发缓存明确拒绝，不在恢复时自动替换配置。
- PI / PI Durable 与 Codex worker 在读取恢复状态、创建原生实例前验证配置响应；模型或工具内容改变、元数据改变、错 Session / Turn、错内核和未知格式会拒绝。错误不回显配置内容。该校验依赖服务鉴权，不替代授权或提供独立签名。
- `PiBrainHttpFlowTest` 删除预填运行配置，实际经过 `ManagedCatalog` 解密、公共 overrides 合并、配置编译、HTTP、Node worker 与原生 PI SDK。provider 校验实际收到的模型 ID 和 transcript system 消息；普通 / durable 模式分别覆盖持续执行与工具挂起后换进程。
- 原有真实 Hands shell、工具只执行一次、每个 commit 丢回执后恢复、公开文本预览与最终消息身份、大消息及原生恢复断言保留。测试中的模型和空 MCP 发现使用 fixture，存储/消息使用 H2 和内存替身；封存定义由测试构造，尚未运行真实控制面创建 API。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| 协议 `make check` | 10 Schema、19 operation、109 正例、44 反例；各 4 个 JCS / identity 向量通过 |
| TypeScript workspace | build 通过；Runtime 37、PI Adapter 36 项通过；旧 TS Codex probe 另有 3 项，不计入 Rust 验收 |
| Rust workspace | 27 项通过：Adapter 单元 5、真实内核集成文件 6、Runtime 单元 4、预览 3、恢复 9；新增配置向量和拒绝用例 |
| Java 定向回归 | 10 项通过、零失败、零跳过：配置 6、PI HTTP 4；相关模块构建及格式检查通过 |

三种语言均验证同一个协议配置样例。Java 配置回归另外覆盖真实封存定义、有效 overrides、默认权限、缓存被修改、跨 Turn 复用拒绝，以及新编译器仍复用原配置。TS worker 测试确认无有效 identity 时，恢复读取、原生运行、模型派发和提交均未发生。

新增 provider 断言最初读取了 PI 归一化前的 `Context.systemPrompt`，导致测试模型返回错误；依据已安装 SDK 的 `TranscriptContext` 契约改为检查 provider 收到的 system 消息后，通过全部四种 HTTP 场景。该失败是测试观察点错误，不是新增的原生适配缺陷。

```sh
# 仓库根目录
make -C managed-agents/agent-harness-runtime/protocol check PYTHON=/tmp/managed-agents-protocol-venv/bin/python
mvn -B -ntp -T1 -pl managed-agents/agent-service -am test -Dtest=HarnessRunConfigurationTest,PiBrainHttpFlowTest -Dmanaged.pi.http=true -Dsurefire.failIfNoSpecifiedTests=false

# typescript 目录
pnpm -r build
pnpm -r test

# rust 目录
/Users/ken/.cargo/bin/cargo test --locked --workspace
/Users/ken/.cargo/bin/cargo fmt -p managed-brain-runtime -p managed-adapter-codex -p managed-brain-codex -- --check
```

本地日志：`/tmp/managed-brain-config-java.log`、`/tmp/managed-brain-config-ts.log`、`/tmp/managed-brain-config-rust.log`。Maven 使用 Java 17 编译目标；没有执行全仓 clean verify / 全仓 spotless、镜像构建或部署。未提交、推送。

## 剩余事项

- W05 仍需完整字段映射、资源引用、模型绑定、内核版本要求和 AgentScope prepared 路径的共同配置表达。摘要覆盖定义不等于 Adapter 已消费每个字段。
- W07 仍需按内核版本暴露能力并在派发前拒绝未映射字段；contextPolicy / jev 等不能继续静默丢失。W06 的 Codex 完整模型字段仍待映射。
- W19 / W20 仍需真实控制面创建与授权、Java 与 Rust 的同定义全链路、真实设施和公共 SSE HTTP 的共同验收。当前证据不能替代这些门禁。

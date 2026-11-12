# 跨内核在线文本预览验证记录

[English](verification-previews-20261029.en.md)

日期：2026-10-29。实际目录 `/Users/ken/agentcore/agentcore-managed-agents`，分支 `master`，HEAD `ed482512993468e8dd68c9dac0a06f63b752fde5` 加已有未提交工作区。记录对应 W09 / W10 的实现与定向验证，不代表完整计划或生产验收完成。

## 实现与边界

- 新增受服务鉴权保护的 `previews/interest` / `previews/publish`。服务验证 scope、当前 owner/fence、模型 dispatch 的 reasoning purpose、模型尚无已提交 completion；不只依赖可能仍为 reserved 的计费状态。Redis 操作移出 Session 事务锁，预览不写执行账本或持久化历史。
- TypeScript 与 Rust 使用独立有界通道，保留主执行的取消/回执语义。队列溢出、拒绝、无订阅和超时只终止预览；不重试。每批最多 8 个 1024 码点 chunk，待发预算 64 KiB / 256 个 chunk，单请求 250 ms，结束最多等待 200 ms。
- 普通 PI 从主回答 message_update 发布；PI Durable 对主回答 stream 发布、排除 complete/completeSimple 辅助调用。两者把模型调用身份写入原生可持久化的 Adapter 元数据，用于 delta、公开事实和最终消息；既有无元数据的恢复状态仍用原 entry 身份。
- Codex 在模型 HTTP 响应结束前解析完整 SSE 帧，只发布已声明 assistant item 的 output_text delta；处理分片 UTF-8 和 CRLF，排除 reasoning、analysis 和工具参数。预览 ID 由 native thread / item ID 派生，与 rollout 提取的消息相同。工具及其他可执行响应仍经完整 allowlist 校验后才交给 Core。
- 共同预览接收端继续使用 `event_start` / `event_delta`、epoch、序号、订阅兴趣、有界发送与最终消息 reconcile。支持一个模型调用多个消息的独立预览状态。首次批次的序号包含 start；迟到订阅、重复/缺口、旧 epoch 不拼接出错误尾部。

## 已执行验证

| 检查 | 结果与覆盖 |
| --- | --- |
| 协议 `make check` | 10 Schema、19 operation、109 正例、39 反例通过；各 4 个 JCS / identity 向量 |
| TypeScript 构建与测试 | 构建通过；Runtime 34、PI Adapter 36 项通过；旧 TS Codex probe 另有 3 项，不计入 Rust 验收 |
| Java 定向 Maven | 32 项通过、零跳过：共同预览 7、协议 Store 21、PI HTTP 4 |
| Rust `cargo test --locked --workspace` | 25 项通过：Adapter 单元 5、原生集成文件 6（含子进程入口）、Runtime 基础 2、预览 3、恢复 9 |

PI 的四项 HTTP 用例启动真实 Node worker、SDK、Adapter 和 Java Session Service，运行真实 Hands shell（H2 与内存设施替代生产设施）；覆盖普通 / durable、持续执行 / 工具挂起后换进程。预览在模型结果提交前经 HTTP 发布，接收端生成 SSE 帧，其 ID 与最终持久化消息一致。原有每次 commit 回执丢失、大消息和工具只执行一次断言保持通过。

Codex 新用例启动真实 codex-core 和分块 HTTP 模型出口。模型服务先发送文本增量，并在收到预览发布且模型尚未 complete 后才发送 response.completed；测试核对预览文本和最终消息身份。Session Service 使用协议 transport 替身，未证明 Rust worker 与真实 Java 服务/Redis/SSE 入口的全链路。

其他定向断言覆盖：无订阅不传文本、私有辅助输出不公开、队列与请求大小有界、传输失败/挂起不取消执行、未 dispatch/已 complete/非 reasoning/旧 owner 拒绝、预览不新增持久化事实、晚订阅、重复序号、旧 epoch、最终 span 关闭和慢消费者。

```sh
# 仓库根目录
make -C managed-agents/agent-harness-runtime/protocol check PYTHON=/tmp/managed-agents-protocol-venv/bin/python
mvn -B -ntp -T1 -pl managed-agents/agent-service -am test -Dtest=ManagedSessionPreviewsTest,HarnessProtocolStoreTest,PiBrainHttpFlowTest -Dmanaged.pi.http=true -Dsurefire.failIfNoSpecifiedTests=false

# typescript 目录
pnpm -r build
pnpm -r test

# rust 目录，使用固定 Rust 1.95.0
/Users/ken/.cargo/bin/cargo test --locked --workspace
/Users/ken/.cargo/bin/cargo fmt -p managed-brain-runtime -p managed-adapter-codex -p managed-brain-codex -- --check
```

本地日志：`/tmp/managed-brain-preview-protocol.log`、`/tmp/managed-brain-preview-ts-build.log`、`/tmp/managed-brain-preview-ts-tests.log`、`/tmp/managed-brain-preview-java.log`、`/tmp/managed-brain-preview-rust.log`。日志临时保存，本文记录可复核的范围。

## 剩余工作

- W09 / W10 尚未整体关闭：真实 Redis、多实例兴趣/epoch 切换、公共 SSE HTTP 的最终事件交错、并发容量和 Rust→真实服务组合仍需验收；AgentScope 原生调用与新共同身份规则的跨内核测试也要纳入 W19。
- 当前公开范围是文本预览。最终完整消息权威；不提供 delta 重放、SSE resume、多模态或工具参数增量。
- PI Durable 当前公开 profile 只允许 root；开放子 Agent 时须重新验证主/子调用隔离，不能把当前 stream 方法过滤扩大解释为任意子调用安全。
- 崩溃或上游中断留下的 model span、unknown accounting、资源/上下文/配置完整度仍按 W05—W20 推进。本次没有完成完整控制面装配、真实模型评估或所有故障路径。
- 未运行全仓 `clean verify` / 全仓 `spotless:check`、镜像构建或部署；只对本轮 Java 文件应用格式化并通过相关模块构建时的格式检查。未提交或推送。

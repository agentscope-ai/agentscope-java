# 跨内核持久化事件与用量回归记录

[English](verification-public-events-20261029.en.md)

日期：2026-10-29。目录：`/Users/ken/agentcore/agentcore-managed-agents`，分支 `master`，HEAD `ed482512993468e8dd68c9dac0a06f63b752fde5` 加当前未提交工作区。记录覆盖实施计划 W08 和 W14 的部分内容，不代表整套计划验收完成。

## 本次实现

- 通用模型 dispatch / complete 提交生成公共模型开始/结束事实，投影成配对 span。真实 usage 映射到统一字段；unknown 不伪装为零用量。
- PI 从原生 SessionManager 新增的 assistant 消息提取公开文本；PI Durable 从根 conversation 的 AssistantEntry 写入提取；Codex 从当前原生 Turn 的 rollout Message 提取。事实与对应原生状态同一次提交，不发布 thinking、工具参数或 compaction 摘要。
- 新增可选 `terminal.messageId`，让最终输出引用原公开消息；恢复保留消息身份，不依赖文本比较或临时 commitId。
- 大于 16 KiB 的公开事实内容和最终输出使用同 scope 对象引用；单次公开事实的内联内容预算为 128 KiB。原内容参与对象摘要，不截断文本。
- Codex 从 Responses completed usage 提取总输入、输出与缓存子计数；缺失、不合法或矛盾的用量保持 unknown。PI 将互斥的 uncached input、cache read、cache write 相加得到平台总输入，防止预算漏计缓存上下文。

## 已执行验证

| 检查 | 结果和覆盖 |
| --- | --- |
| Protocol `make check` | 9 个 Schema、17 个 API operation、105 正例、31 反例通过；Node 校验各 4 个 JCS / identity 向量 |
| TypeScript `pnpm -r build` / `pnpm -r test` | 构建通过；Runtime 29 项、PI Adapter 34 项通过；历史 TS Codex probe 的 3 项另列，不计入真实 Rust 内核验收 |
| Rust `cargo test --locked --workspace` | 19 项通过：代理 3、原生集成文件 5（包含子进程测试入口）、Runtime 2、恢复 9；实际场景包括双工具部分结果接管、后续 Turn、SIGKILL 后恢复和 300 KB 消息 |
| Java 定向 Maven | `HarnessProtocolStoreTest` 18 项、`PiBrainHttpFlowTest` 4 项通过，零失败/跳过；HTTP 测试显式开启 |

新增行为断言覆盖：三种通用协议模式的 span 配对、提交回执重试不重复投影、unknown 不产生 model_usage、最终输出只选择最终消息、原生恢复不重新发布已提交文本、私有 thinking 不进入公开消息，以及 300 KB 公开内容不膨胀 commit 元数据。PI HTTP 四种组合在真实 Node SDK 与 Java HTTP/事务链路上检查中间消息、最终消息、两个模型 span 和唯一最终输出身份。

执行命令：

```sh
# 根目录
make -C managed-agents/agent-harness-runtime/protocol check PYTHON=/tmp/managed-agents-protocol-venv/bin/python
mvn -B -ntp -T1 -pl managed-agents/agent-service -am -Dtest=HarnessProtocolStoreTest,PiBrainHttpFlowTest -Dsurefire.failIfNoSpecifiedTests=false -Dmanaged.pi.http=true test

# typescript 目录
pnpm -r build
pnpm -r test

# rust 目录，使用 rust-toolchain.toml 固定的 1.95.0
/Users/ken/.cargo/bin/cargo test --locked --workspace
/Users/ken/.cargo/bin/cargo fmt -p managed-brain-runtime -p managed-adapter-codex -p managed-brain-codex -- --check
```

本地日志：`/tmp/managed-brain-events-protocol.log`、`/tmp/managed-brain-events-ts-final.log`、`/tmp/managed-brain-events-rust-final.log`、`/tmp/managed-brain-events-java-final.log`。日志是临时文件，本文保存验证范围及结果。

## 尚未验证或实现

- 本记录形成时在线 preview / delta 尚未接入；后续实现和定向验证见[预览验证记录](verification-previews-20261029.md)。Codex 可执行响应仍完整校验后才交给 Core；本文的历史结果只证明持久化事件。
- 上游断流或进程崩溃造成未完成 model dispatch 时，模型 span 和 unknown accounting 的完整收敛仍需补齐；不能将正常 complete 的配对验证扩大到这些路径。
- 模型服务使用确定性替身；PI 使用 H2 和测试设施，Codex 使用 Session Service transport 替身。未完成真实控制面到三个内核的完整装配、真实存储/MQ/Redis/沙箱组合或真实模型效果评估。
- Codex 的完整历史 checkpoint、长历史成本、压缩、增量恢复和公共 checkpoint 管理仍按 W14/W15 推进；新增公开消息元数据也应纳入容量检查。
- 未执行本阶段全仓 `clean verify`、全仓 `spotless:check`、镜像构建或部署。没有提交或推送代码。

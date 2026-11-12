# Codex Rust 首版实现与验证

[English](verification-codex-rust-20261028.en.md)

工作目录 `/Users/ken/agentcore/agentcore-managed-agents`，分支 `master`。直接在主目录实现，没有 worktree、提交、推送或部署；保留工作区既有及并行修改。

## 实现

`rust/` 包含协议 runtime、原生 Codex Adapter 和单二进制 Brain。直接链接上游 `e742db9f7ba98e63e502b310857d49d047888518` 的 codex-core，没有内核补丁和 Node/CLI/app-server 依赖。Session Service 开放显式 codex 绑定及中立模型 provider 配置；镜像配套为 `images/codex/`。

工具边界先捕获原生历史，再挂起/关闭内核；Session Service 提交检查点及工具授权，沿现有 Outbox 交给 Hands。结果以原 call_id 注入空闲线程，并与 resultApplications 一起提交，然后恢复原 Turn。A 已完成、B 未完成时保存 A；取消清理产生的临时失败记录不覆盖恢复点。下一用户 Turn 保留 Session 历史，单独开启原生 Turn。

## 已执行检查

| 检查 | 结果 |
| --- | --- |
| `cargo test --locked --workspace` | 通过：17 个测试入口，包含 1 个被故障测试调用的子进程辅助入口。真实内核覆盖 A/B 部分结果、owner 更换、丢回执、SIGKILL 冷恢复、连续用户 Turn，以及取消之后的新 Turn。 |
| `cargo clippy --locked --workspace --all-targets --no-deps -- -D warnings` | 通过；上游依赖自身仍有 PTY 弃用提示及未使用 crossterm patch 提示，没有修改上游。 |
| 三个本地 crate 的 `cargo fmt -- --check` | 通过。 |
| `cargo build --locked -p managed-brain-codex` | 通过，产物为 macOS 本机 debug 二进制；不是 Linux 镜像构建。 |
| `scripts/smoke-worker.py` | 通过：版本、带鉴权队列轮询、存活/就绪、服务不可用及畸形响应后的恢复、健康检查入口、SIGTERM 退出。 |
| 实际请求独立 Schema 校验 | 41 个真实 Rust/原生内核流程请求通过共享 0.1.0 契约。 |
| protocol `make check` | 8 个 Schema、16 个 API、86 个正例、25 个反例；Node 校验 4 个 JCS 和 4 个身份向量。Node 仅用于共享协议检查，不进入 Codex 镜像。 |
| `HarnessProtocolStoreTest` | 完整构建日志中 7 个测试通过，包括 `codexClaimsAndCommitsItsOwnNativeState`。 |
| Docker Compose 合并配置 | 使用测试占位配置通过；Rust 镜像与健康检查生效，没有继承 DB/MQ 配置或 Hands 挂载。 |

## 未通过或未执行

- 根目录 `mvn -B -ntp -T1 clean verify`：Java 编译和已运行测试通过，随后 Agent Service Javadoc 找不到项目依赖包，构建失败。
- 补充 `verify -Dmaven.javadoc.skip=true` 与最终 `spotless:check`：被工作区并行变更的 `HarnessProtocolStore.java` 格式检查阻塞。未覆盖或重排其他工作中的代码，不能将根目录检查标记为通过。
- Docker daemon 在限时检查内未响应，因此没有构建或运行 Linux 镜像，也没有部署。
- 测试使用真实 codex-core，但模型、Session Service transport 和 Hands 结果是替身。尚未验证 Rust 二进制与真实 Java Session Service、PostgreSQL、RocketMQ、Redis、外部 Hands 的整链故障和容量行为。

支持范围及后续工作见 [Rust Brain](../../rust/README.md)。上述结果证明首版核心闭环与本机二进制可运行，不构成生产上线验收。

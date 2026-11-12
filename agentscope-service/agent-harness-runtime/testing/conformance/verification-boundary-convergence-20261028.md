# AgentScope 边界收敛验证

[English](verification-boundary-convergence-20261028.en.md)

目录更新（2026-11-02）：下文记录当时的模块结构。原 Session Service 子模块现已合并到 `agent-service/src/`，相关跨角色联调测试也归入 Agent Service；历史验证结果不代表本次合并的验证结果。

2026-10-28；主目录 `/Users/ken/agentcore/agentcore-managed-agents`，分支 `master`。未提交、未部署。

## 改动

- 新增 `java/codec-agentscope`，只解释原生格式；通过 `NativeCheckpointCodec` 为 Session Service 提供 checkpoint 校验和基线转换。私有注册表显式支持收尾事件，不依赖另一个进程的 Adapter 初始化；仍拒绝未知必需事件。
- checkpoint 管理事务移入 `agent-service/session-service`，共享层仅保留 admission hold。TaskRepository、JPA AgentStateStore 与 SkillRepository 适配归 Adapter。模型预览传输接收中立文本增量，子任务结果按不透明原生 JSON 传递。
- Brain 的工具提案发布、提案/MCP 批次读取、Resume 状态读取和延期经 Session Service；取消转发只由 Service 启动。HTTP 409 保留原平台冲突码，继续驱动原恢复判断。
- 删除未发布的旧 Hands 通知升级路径，只保留 Service 产生的标准 Resume。新增 prepared 接口、Schema、OpenAPI、正反样例和路由清单；工具授权/执行/结果与 ACK 原边界不变。

## 验证

- 独立 codec 模块测试：执行 Adapter 不在 classpath，SDK 全局注册表仍拒绝收尾事件，私有 codec 可以正确校验；未知必需事件、非法收尾事件仍失败。
- checkpoint 回归：收尾记录参与校验并完成分叉；非支持内核不能占用 Session hold；既有权限、工作区导入、原子基线和回滚用例保留。
- HTTP 回归：短/长工具与重建 Agent；提案发布响应丢失后重试，提案仍未授权且没有重复账本；旧 owner 和缺失 Resume 返回原冲突码。提案客户端使用禁止连接的 DataSource 验证不会访问 Brain SQL。
- 协议离线检查：8 份 Schema、16 个 OpenAPI 操作、86 个正例、25 个反例；JCS 和身份向量通过。

- 清理构建后，`mvn -B -ntp -T1 verify` 全部 13 个模块通过：当前 reactor 共 1,368 项，991 通过、377 跳过、0 失败/错误。首次 `clean verify` 因工作区 `HarnessRunConfiguration` 的格式变化中止；保留逻辑并格式化后，完整 `verify` 通过。
- `mvn -B -ntp -T1 spotless:check`、`git diff --check` 通过。
- 生产包检查通过：Agent Service 只增加轻量 codec，不包含执行 Adapter/Hands；Brain 不包含 Session Service/Hands；Hands 不包含 codec/Adapter/Session Service。common 不残留迁出的 checkpoint 事务、原生 Repository/StateStore 或旧通知类。
- 验证日志：`/tmp/managed-converge-final-verify.log`、`/tmp/managed-converge-spotless.log`、`/tmp/managed-converge-protocol.log`。

## 保留项

旧 Turn 认领/接管、进程租约、FIFO ACK、MCP 目录和子任务协调仍有共享账本访问；公共原生封装尚未全部中立化。真实基础设施、容器故障和部署验收未执行，不能以离线成功替代。

命名审计仍报告已有 SQL 冻结摘要、旧源码链接及工作区 PI 测试中的旧命名问题；本次不修改无关 SQL 或 PI 功能。新增路由清单已更新。

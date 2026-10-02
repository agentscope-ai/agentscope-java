---
title: "本轮实现与验证记录"
---


验证日期：2026-09-25。实际目录 `/Users/ken/agentscope-3/agentscope-java`，分支 `main`。未创建其他工作树，未提交、部署或调用真实计费模型。

## 已完成验证

- JEV 模块：88 项测试，零失败、零跳过。包含执行预算、取消、观察器异常、工具 none 与回退、失败拒绝、确认参数变化、模型粘性及应用 API。
- 权限组合测试使用真实 ReActAgent、Toolkit 与 PermissionEngine，验证 JEV 允许不能覆盖权限拒绝，允许工具恰好执行一次。
- 两个离线主程序 `JevHarnessExample` 与 `JevApplicationExample` 已运行成功，不依赖密钥。
- Service 全量 83 项测试通过；定向测试及包含文档示例的 38 模块 verify 通过；Go `go test ./internal/product` 通过。
- 站点测试 12 项通过，站点 validate 与 broken-links 通过。专题独立检查涵盖源码链接和合成样本。

## 全量回归说明

曾执行根目录 `mvn clean verify`，发现的阶段路由编译错误和新增测试缺失导入已修复。上游 Core、Harness 及 Service 公共模块回归通过后，安装本地依赖并从 JEV 模块续跑；不将分段回归称作一次完整 clean verify 成功。

Service 全量测试发现工具确认测试在异步回调期间重设 Mock 的竞争：单测独跑通过，整套可复现。测试现改为先配置 Mock、通过原子引用切换 scope，不修改生产确认逻辑。

Service 修复后全量 83 项测试通过；续跑的 Scheduler、模型及多个扩展模块亦通过。后续 MongoDB 契约测试停在 Testcontainers 镜像拉取，线程栈显示 `RemoteDockerImage.pullImage` 等待；本次主动停止扩展回归。因此全仓 `clean verify` 尚未完整通过，MongoDB 及其后续模块不计入本轮通过范围。

## 尚未验证与使用边界

- 未进行本轮真实 JEV/Qwen 对照，不能给出准确率、P50/P95、用量或成本收益结论。现有历史证据不是新增能力的效果证明。
- Service 尚未部署，实际会话、确认与取消链路需要运行环境验收。
- 首轮交付为显式应用 API；本轮已新增 Agent 响应中间件、Knowledge 兼容适配及评估接口。生产业务接线、持久归档和真实浏览器驱动仍待完成。
- 中间件默认 OFF；阈值未经过业务金标校准，实际试点应先启用 SHADOW。

API、案例与下一步分别见[专题入口](/v2/zh/jev/overview)和[实施计划](/v2/zh/jev/implementation-plan)。

## 四项 Agent 集成补齐（2026-09-25）

本轮新增内容策略、Agent 响应中间件、Knowledge 兼容适配及检索工具、扩展级评估接口与固定样本 runner。Service 新增 content/quality 独立用途配置。

- JEV：105 项测试通过，零失败、零跳过；包含 17 项新增集成测试。
- Service：19 项相关测试通过，涵盖新配置装配及已有工具确认、构建缓存。
- 实际离线 ReActAgent 组合案例通过：工具成功返回授权证据，草稿经安全和质量审核后仅用无工具模型修订，最终文本与 AgentResult 一致。
- 内容拒绝时原模型不调用/已提出工具不执行；SHADOW 不替换；超限、超时、取消、非法响应、并发草稿隔离均有测试。
- Knowledge 验证 ACL 先于 JEV、文档身份保留、跨用户隔离、失败不包装成无证据；评估验证证据映射、错误/弃权留在分母及报告顺序。
- 专题校验：39 页、159 个本地链接、5 条合成样本通过。代码和文档差异空白检查通过。

本轮未进行真实 JEV/Qwen 调用和部署。离线 fixture 的准确率与延迟不得用于宣传模型效果；修订调用的用量需模型侧另行核算。

本轮最终 Maven 验证成功：

```bash
mvn -pl agentscope-service/service-dataplane,agentscope-examples/documentation -am verify \
  -Dtest='Jev*Test,ToolConfirmationMiddlewareTest,HarnessAgentBuildServiceCacheKeyTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

该命令完成相关依赖、Service 和文档示例的编译、格式检查、打包及上述定向测试；未重新执行全仓全部测试，不替代前述全仓回归限制。

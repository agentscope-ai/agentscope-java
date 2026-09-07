# Automation 第一版验收

日期：2026-09-08。代码位置：`/Users/ken/agentscope-2/agentscope-java`；分支：`agentscope-service-v5`。直接修改主工作区，保留已有暂存和未提交修改，未创建 worktree、未提交 Git。

## 交付范围

Runbook 创建/编辑、Agent/Team、Create issue/Run only、验收策略、上下文链接、自订阅、多触发器、Cron/时区/下次执行预览、Webhook 认证/轮换/过滤/去重/投递重放、暂停/恢复/归档/试运行、并发跳过/串行排队/超时、持久化快照/租约恢复、实际结果与人工验收状态、失败 Inbox、运行详情及执行链路入口。

迁移 `0106`、`0107` 以新增列/表/索引实现，无业务数据重置。实际模型取舍及下一阶段边界见 `../../automation-capability-design-20260907.md` 第 10 节。

## 验证结果

| 层次 | 验证 | 结果 |
|---|---|---|
| Go | `go test ./...`、`go vet ./...` | 通过 |
| 并发 | `go test -race ./internal/automation ./internal/httpapi ./internal/store/memory` | 通过 |
| PostgreSQL | 独立新验收库，`go test -p 1 ./internal/store/postgres ./internal/automation -count=1` | 通过 |
| 前端 | Vitest 全量 | 23 文件、98 测试通过 |
| 浏览器回归 | `npm run test:e2e -- e2e/automations.e2e.ts` | 3 测试通过 |
| 前端构建 | `npm run build`（TypeScript + Vite） | 通过 |
| Gateway | `mvn -pl agentscope-service/service-gateway -am package -q` | 测试、格式检查与打包通过 |
| 控制面构建 | `go build -o bin/aistiod ./cmd/aistiod` | 通过 |
| 本地部署 | `BUILDER_REBUILD=0 BUILDER_RESET_DB=0 scripts/dev-up.sh` | 保留 cp/rt/dp 数据，四个服务健康 |
| 真实浏览器 | 本地 18080 创建表单、时区预览、Team 结果页 | 正常显示，无模拟 API |

PostgreSQL 验收覆盖实际迁移和事务、8 个并发调度者、同幂等键去重、不同输入冲突、配置快照、游标推进、部分派发后恢复、排队/跳过/取消、目标失效通知、队列超时、Webhook 已受理未更新投递状态后的恢复与重放去重。测试数据库与主业务数据库分离；通用存储套件需要新库，复用含旧夹具的库曾导致冲突，最终在新库顺序执行通过。

浏览器回归使用 API mock，验证完整表单提交、保存冲突保留草稿、Run only 自动完成策略、网络失败后复用手动触发幂等键，以及等待验收/结果/带 scope 链接。`evidence/*browser-test.png` 为这组回归的截图；真实运行结果单独记录。

## 真实 Agent / Team 验收

通过已部署 Gateway 18080 创建合成验收规则，使用现有 MA2 及 team1。任务仅要求计算 `17 + 25 = 42` 与返回测试标识，不读取/修改项目文件、不发送外部消息。Team 明确要求 leader 委派 MA2 后等待和汇总。

| 场景 | 实际结果 |
|---|---|
| Agent × Run only | operational automation_job 自动完成，返回 42 和正确标识 |
| Agent × Create issue | 返回结果后 waiting/review；接受合成 Issue 后 Run completed |
| Team × Run only | leader/worker 两节点、三次 Task/Attempt，自动完成并返回正确标识 |
| Team × Create issue | leader/worker 两节点、三次 Task/Attempt；等待人工验收，接受后完成 |
| Webhook | 经 Gateway 认证受理并真正执行；同事件重传返回同 Delivery、同 Run |
| Cron | 等待真实一分钟调度，source=schedule 且记录 scheduledAt，实际执行完成 |

六条规则均已暂停，保留运行记录供复查；没有留下继续触发的验收计划。可从 `evidence/live-results.json` 的 automationId / runId 定位页面。Team 节点与任务摘要在 `evidence/team-*.json`；Webhook 幂等证据在 `evidence/webhook-dedupe-and-trigger-ids.json`。证据不包含登录 token 或 Webhook secret。

## 验收中修复的问题

- 内存存储 JSON 克隆复用了指针/切片，引发读操作之间的 race；已真正深拷贝，并通过 race 检测。
- PostgreSQL 索引迁移不能在仓库现有事务迁移器中使用 CONCURRENTLY；改为普通迁移索引，并在真库验证。
- Team 等待成员时，根任务完成不代表整个编排排队；运行状态现同步编排状态，详情包含同编排的成员任务。
- 派发前失败与 Automation 超时缺少负责人 Inbox；新增事务内通知，避免与普通任务失败重复。
- 补齐 Gateway Webhook 路由、PATCH 省略字段保留、并发编辑时游标保留、详情链接 scope 和桌面双列滚动。

## 明确边界

这是第一版已验收闭环，不覆盖 Channel、供应商 HMAC 协议、分布式共享限流、连续失败自动暂停、独立项目/Workspace 覆盖、历史清理。Webhook 使用共享密钥认证；未认证请求不保存正文。实际运行验收覆盖本机 Managed Agent 与 Team；其他 External runtime 的 Automation 全链路未在本轮逐一重跑。故障恢复通过内存和真实 PostgreSQL 的中断窗口注入验证，未执行操作系统级随机 kill 压力测试。没有运行全仓 Java `mvn clean verify`；Java 改动仅 Gateway 路由，对应模块测试与打包已完成。

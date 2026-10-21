# Session Playground / 独立会话测试页

简化的本地 Web 小程序，用于手工发送用户指令、读取 Session 快照并观察 SSE。它有独立入口、端口和构建目录，自带测试用的 Session API v1 类型与事件归并器，不导入主前端代码，也不依赖 Console 登录或页面路由。Agent、Environment、Session 等资源继续通过终端创建。

## 启动

在仓库根目录运行：

```bash
cd agentscope-service/tests/session-playground
# 首次使用：npm ci
npm run dev
```

打开 <http://127.0.0.1:18082/>，填写 Session ID 或 `http://127.0.0.1:18081/api/v1/agent-sessions/{id}`，点击「连接并观察」。也可以通过 `?session={id}` 预填。当前本地 Service 的 dev 模式无需登录；页面不会因为连接或选择示例而发起模型调用。

默认通过 Vite `/api` 代理连接 `http://127.0.0.1:18081`。连接其他 Gateway 时，以下面的方式启动，无需修改后端 CORS：

```bash
PLAYGROUND_API_TARGET=http://127.0.0.1:18080 npm run dev
```

界面展示 API 代理地址。完整 Session URL 必须属于配置的 Gateway；同端口的 localhost、127.0.0.1 和 IPv6 loopback 可互换。可选的 Bearer Token / Tenant / Namespace 在重新连接后生效，Token 仅保留在当前页面内存。

## 手工体验

1. 「连接并观察」读取 Session 元数据和 `GET /snapshot?limit=200`，然后从快照的 `as_of` 连接 Session SSE。
2. 输入指令，或选择「普通对话」「一次工具执行」「三阶段观察」示例，再手工点击「发送指令」。支持 Ctrl / ⌘ + Enter。示例工具需要 Agent 的 Environment 支持 `execute`。
3. 左侧显示用户消息和逐步返回的回复。顶部可筛选 Turn、复制 Turn ID、调用 `GET /turns/{id}`。回执的 queued 只表示受理；实时状态来自事件。
4. 工具卡展示参数、等待状态和真实输出。waiting / suspended 不代表成功；历史 unknown 原样保留，页面不推断或修复历史状态。
5. 时间线展示本次连接后的 SSE，默认隐藏逐字 delta；可取消隐藏、搜索并展开完整 JSON。历史消息和工具由快照恢复，时间线不伪造历史 SSE。
6. 「快照 / 状态」展示原始快照、归并视图、用量、待处理动作和游标。历史资源按固定 snapshot_id 加载更多；遇到 410 或显示上限时刷新快照。
7. 「断开 SSE」只停止浏览器观察；任务继续执行。「连接 SSE」从已应用游标续接。刷新快照会重新建立观察。

发送使用随机 Idempotency-Key。POST 前把 Session、内容和键保存在当前标签页的 sessionStorage；30 秒未确认或响应丢失后，可「以原幂等键重试」，刷新页面后仍可恢复。放弃本地收据不会取消已受理的任务。页面不自动重发用户指令。

SSE 的 EOF、网络错误和 429/5xx 按 0.5–10 秒退避续接，不把断流视为完成。权限、游标和 schema 错误需要处理后重新连接。消息按 item_id 更新，item.finalized 不增加重复回复；工具按 Turn + tool_call_id 归并。时间线保留最近 400 条事件；SSE 单帧上限 1 MiB，归并器有 4096 条 live 事件 / 16 MiB 显示限制。用量展示 Session 完整汇总，不按加载的资源条数估算。

验证后端实时投递时，可用两个标签页观察同一 Session，断开其中一个再续接，确认后续事件继续到达。Portal 内共享该 Session 的实时读取，历史续传游标仍按连接独立保存。跨副本、漏通知和数据库监听恢复的自动化测试位于 `ManagedSessionEventStreamsPostgresTest`；为新建的空 PostgreSQL 测试库设置 `PORTAL_SSE_TEST_DATABASE_URI`，从仓库根目录运行：

```bash
mvn -T1 -pl agentscope-service/service-session-portal -am test \
  -Dtest=ManagedSessionEventStreamsTest,ManagedSessionEventStreamsPostgresTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

测试采用 60 秒补偿周期，并要求通知恢复在数秒内完成，以免定时补读掩盖实时链路故障。

## 构建与验证

```bash
npm run build
npm run preview
# 静态产物：agentscope-service/tests/session-playground/dist/
# preview 也使用相同 /api 代理；改目标时设置 PLAYGROUND_API_TARGET。

npm test
npm run test:e2e
npm run lint
```

浏览器测试使用本地 HTTP fixture，不调用真实模型或 Sandbox。此构建不会覆盖 Console 的 UI 产物。后补测试记录在 `agentscope-service/docs/session-portal-test-backlog.md`。

## English

This test-only application has its own package, lockfile, types, event reducer and build output, with no imports from the production frontend. It sends user instructions through the Session API and displays snapshots, streaming replies, tool state and raw SSE events. Create control-plane resources in your terminal. From the repository root, run `cd agentscope-service/tests/session-playground` and `npm run dev`; open <http://127.0.0.1:18082/>. Set `PLAYGROUND_API_TARGET` to change the default Gateway at port 18081. Paste a Session ID or its root URL, then connect. The local dev deployment requires no login; optional credentials stay in page memory.

Connection reads a snapshot before streaming from its as_of cursor. Transport loss retries observation; disconnecting does not cancel execution. POST receipts indicate admission, not completion. Unconfirmed submissions retain the exact body and idempotency key in the tab's sessionStorage for explicit retry, including after reload. Examples only fill the editor. Turn filters operate on the Session view. The timeline contains the latest 400 received SSE frames; historical resources come from snapshots. Finalized replies update the same item, and waiting tools remain pending until a real result arrives. Display limits require refreshing the snapshot, without stopping server execution.

Observe the same Session in two tabs, disconnect one and resume it to check continued live delivery. Portal shares live reads for that Session while keeping each connection's replay cursor independent. The Maven command above runs reader regressions; set `PORTAL_SSE_TEST_DATABASE_URI` to a newly created empty PostgreSQL test database to enable cross-replica, missed-notification and listener-recovery cases. These cases use a 60-second recovery interval and require notification delivery within a few seconds so polling cannot conceal notification failures.

`npm run build` writes to `agentscope-service/tests/session-playground/dist` independently of Console assets. `npm run preview` serves it with the same API proxy. Unit and browser commands above test parser boundaries, cursor replay, tool waiting/finalization and same-key submission retry using fixtures. Deferred tests remain in the test backlog.

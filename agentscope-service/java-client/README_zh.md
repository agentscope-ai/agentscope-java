# Managed Agents Java 客户端

Managed Session Draft 3 的交互统一写入 `/v1/sessions/{id}/events`。
使用 send_events/sendEvents、submit_turn/submitTurn、steer/inject/cancel 帮助方法，
通过 output/final_output/finalOutput 独立读取最终结果。列表、生命周期、快照、文件、
工具答复和可靠续传暂不属于首批能力，调用可选 API 前检查 capabilities。
协议与固定 SDK 验证位于 `managed-agents/tests/session-protocol/`。


Java 17 的 Agent Service 公开 API 客户端，仅依赖 Jackson，不依赖 Spring、Harness
或服务端模块。Maven 坐标为 `io.agentcore.managed:managed-agents-client`，版本选用 Service
发布清单中的 Java revision。本地制品及发布打包结果不代表 Maven 仓库已经发布。

在仓库根目录构建并安装：

```bash
mvn -T1 -pl managed-agents/java-client -am install
```

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.client.ServiceClient;

var mapper = new ObjectMapper();
var client = ServiceClient.builder(System.getenv("BASE_URL"))
        .apiKey(System.getenv("APPLICATION_API_KEY"))
        .tenant("my-tenant")
        .namespace("my-namespace")
        .build().managedSessions();
var definition = mapper.createObjectNode().put("agent", "agent-id")
        .put("environment_id", "environment-id");
var session = client.create(definition, "create-order-123");
var sessionId = session.path("id").asText();
var turn = client.submitMessage(sessionId, "总结这个订单", "turn-order-123");
var turnId = turn.path("id").asText();
try (var events = client.stream(sessionId, "")) {
    for (var event = events.nextEvent(); event != null; event = events.nextEvent()) {
        // 应用事件成功后持久化 event.path("cursor").asText()。
        if (event.path("type").asText().equals("turn.completed")
                && event.path("data").path("turn_id").asText().equals(turnId)) break;
    }
}
```

已授权用户使用 `apiToken(...)` 替代 `apiKey(...)`；免鉴权本地开发可省略两者。
客户端保留原始身份，拒绝 HTTP 重定向，不接收 Service 内部 token。调用会阻塞，
应放在应用线程执行，不能放在响应式 event-loop 上。

`managedSessions()` 返回 `/v1/sessions` 原生 Managed 客户端：创建固定定义，提交包装后的
Turn input，并通过 `input(session, turn, type, payload, actionId, key)` 提交 steer / inject /
reply / cancel。读取 `inputReceipt` 区分 accepted、applied、rejected；取消受理不表示已停止。
`lifecycle(session, kind, key)` 保留明确幂等键，通过 `lifecycleReceipt` 查询清理完成。
SSE 覆盖整个 Session 并校验每条事件的 Session ID。文件、显式 Artifact、预算、用量与
固定资源页已经接入；Fork / 检查点恢复仍待实现。Turn 快照继续通过 Session resources
路径翻页并保持同一个 snapshot ID；原生 usage 保留 data / as_of。

以下平铺 `ServiceClient` API 仍用于其他运行类型。其 Session、command、export 协议与
原生 Managed 分开，不提供自动回退或兼容转换。

支持当前 Agent Service 的 Session/Turn 读取、keyset 列表、capabilities、快照、资源分页、
事件分页与 SSE、人工答复、steer、inject、取消、恢复、工具/Workspace 恢复回执、
生命周期回执、用量、文件、产物与 Session Webhook。文件输入前读取 capabilities。
预算修改、checkpoint 恢复、fork 仍等待服务端实现。

`exportEvents(session, turn, after, through, limit)` 流式读取一段公开事件 JSONL，
limit 为 1..1000。用快照 `as_of` 作为 through 固定历史。读到 `nextEvent()` 返回
null 后，若 `hasMore()` 为 true，以 `nextCursor()` 和原 `throughCursor()` 继续。
每个窗口用 try-with-resources 关闭；失败时从最后已应用事件游标恢复，不能提前
采用窗口的 next cursor。不完整记录和数量不符会明确报错。

快照后续页保留 `snapshot_id`，SSE 从快照 `as_of` 或最后已应用的游标继续。
命令查询会校验 Turn 归属。网络重试由调用者明确执行，并复用原幂等键及输入。
归档/删除返回受理操作，使用 `lifecycleCommand(...)` 查询应用回执。

单次请求含响应体或整条 SSE 连接都有总超时，默认 30 秒。停止消费时必须关闭流，
超时或 EOF 后从最后已应用游标重连。JSON 和上传限制为 16 MiB，错误与单个 SSE
事件为 1 MiB，缓冲下载的文件/产物为 100 MiB。大文件流式下载与完整客户端故障矩阵
另列实现/测试欠项。

真实 Agent Service/PostgreSQL fixture 的 SDK 构建步骤：

```bash
mvn -T1 -pl managed-agents/java-client -am package -DskipTests
mvn -T1 -pl managed-agents/java-client dependency:build-classpath \
  -Dmdep.outputFile=target/client-classpath.txt
```

再按 `tests/orchestration_runtime_team_smoke.py` 的说明，在专用本地 PostgreSQL
schema 上运行。先构建 Agent Service、Go 制品和 DSH SDK。该 fixture 使用本地配置来源及
带 fence 的假 Host，执行前停止种子控制面，不调用真实模型供应商。

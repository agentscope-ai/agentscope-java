# Personal Weixin channel

This module connects AgentScope to Tencent's official **iLink** personal Weixin API using native
JDK HTTP clients. It provides reusable QR-login primitives plus a standard AgentScope `Channel`
for direct text messages, long polling, context-token replies, cursor persistence, and
account-scoped leases.

The module contains provider protocol and Channel runtime behavior only. A host application owns
credential persistence, durable runtime state, authorization workflows, and user-facing status.

## Standalone configuration

```json
{
  "type": "weixin",
  "defaultAgentId": "main",
  "properties": {
    "accountId": "wx-account-1",
    "botToken": "token-issued-by-ilink",
    "ilinkUserId": "your-ilink-user-id",
    "baseUrl": "https://ilinkai.weixin.qq.com"
  }
}
```

The properties factory is convenient for a standalone process and keeps credentials only in that
process. Managed hosts should construct the Channel with `WeixinChannel.create(...)` and inject a
`WeixinCredentialProvider`, `WeixinStateStore`, and `WeixinRuntimeListener`. These interfaces do
not assume where credentials or state are stored.

The Channel never writes credentials to the local filesystem.

The iLink service does not publish a fixed bot-token TTL. A token may be revoked or invalidated by
the service; response code `-14` stops polling and is reported through
`WeixinRuntimeListener.onCredentialRejected(...)`. The host decides how to persist and present
that observation.

Implement `WeixinStateStore` with a durable store to persist batches and `get_updates_buf` in
one transaction, plus peer context, monotonically increasing account leases and unique message
claims. All state mutations validate the current unexpired lease. The included in-memory
implementation uses the same contract and is suitable only for standalone development and tests.

The Channel renews its lease independently of polling and Agent execution. Standby instances
keep trying to acquire the account; a new owner recovers unfinished messages before polling.
Lease loss cancels the local dispatch subscription and prevents new sends after the loss is
detected. A network request already in flight cannot be recalled.

Processing is **at least once**: a crash after an Agent or provider side effect but before inbox
completion may repeat that side effect. Provider `client_id` deduplication has not been verified,
so this module does not promise exactly-once replies. Completed payloads are cleared immediately;
message-ID tombstones are retained for seven days and removed during later batch acceptance.
Pending messages remain until successfully processed. Context tokens are retained per peer.

Custom stores must implement the entire state contract; cursor-only adapters and default
successful no-op implementations are no longer supported. Runtime listeners can use
`onLeaseAcquired` to attach the lease generation to host-specific status reports.

`WeixinLoginClient` is stateless. `start(...)` returns a one-time `WeixinLoginChallenge` containing
the QR image and a portable `WeixinLoginSession`; every `poll(...)` or `verify(...)` call returns a
`WeixinLoginStep` containing the session to use for the next call. This allows login attempts to be
interleaved or resumed by another host process without carrying the QR image on every request.

The first release intentionally handles direct text chats. Group/media/event message types are
ignored until they have explicit routing and security semantics.

## 本地收发测试

`WeixinChannelLoopbackTest` 会启动微信 Channel，给假的 Gateway 发送“你好”，由 Gateway
回复“收到”。它验证 Agent 调用次数、发送请求的收件人、回复内容、`context_token` 和下一次
轮询的 `get_updates_buf`，不需要真实微信账号、模型 API Key 或外部凭证存储。

在仓库根目录运行：

```bash
mvn \
  -pl agentscope-extensions/agentscope-extensions-channel/agentscope-extensions-channel-weixin \
  -am test -Dtest=WeixinChannelLoopbackTest -Dsurefire.failIfNoSpecifiedTests=false
```

测试自带一个本地 iLink HTTP 服务，不访问外网，也不需要 WireMock 等外部桩服务。
看到 `Failures: 0, Errors: 0, Skipped: 0` 和 `BUILD SUCCESS` 表示这条模拟收发链路通过。
此测试不验证真实微信授权，也不验证数据库持久化或多实例租约。

## 真实微信冒烟测试

`WeixinLiveSmokeTest` 使用腾讯正式 iLink 服务完成扫码登录和双向文本收发。它不会把
`bot_token` 写入文件或日志，凭证仅存在于测试 JVM 内存中，进程退出后即丢失。
运行时需要人工扫码，并按终端提示发送两条一次性校验文本：

```bash
WEIXIN_LIVE_TEST=true mvn \
  -pl agentscope-extensions/agentscope-extensions-channel/agentscope-extensions-channel-weixin \
  -Dtest=WeixinLiveSmokeTest -DforkCount=0 -Dsurefire.useFile=false test
```

测试输出 `WEIXIN_QR_URL` 后，用微信打开或扫描该地址展示的二维码并确认授权。如果微信
要求输入配对数字，测试会输出 `WEIXIN_VERIFY_CODE_REQUIRED`，在运行测试的终端输入手机
显示的数字并回车。登录成功后，向新连接的微信助手发送 `WEIXIN_SEND_CHALLENGE` 对应的文本；
看到回复后，再发送 `WEIXIN_EXPECT_ACK` 对应的文本。最终出现 `BUILD SUCCESS` 表示真实登录、
长轮询、入站映射、上下文回复和再次收消息均已通过。

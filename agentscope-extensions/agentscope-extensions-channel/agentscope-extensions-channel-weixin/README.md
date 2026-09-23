# Personal Weixin channel

This module connects AgentScope to Tencent's official **iLink** personal Weixin API using native
JDK HTTP clients. It provides reusable QR-login primitives plus a standard AgentScope `Channel`
for direct text messages, long polling, context-token replies, cursor persistence, and
account-scoped leases.

The module contains provider protocol and Channel runtime behavior only. A host application owns
credential persistence, durable runtime state, authorization workflows, and user-facing status.

## Compliance and scope

This extension drives a **personal** Weixin account through Tencent's iLink service, not an
enterprise bot account. Personal-account automation is governed by the applicable WeChat terms and
by an operator's own policy, and an account used this way can be restricted by the provider.
Adopting this module is therefore a deployment decision, not just a technical one: the host is
responsible for confirming that it may automate the account, and for telling end users that a bot
answers on it. The first release intentionally supports direct text conversations only; group,
media and event messages are ignored until they have explicit routing and security semantics.

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
process. It logs a warning on construction, because it pairs the fixed credential provider with
`WeixinStateStore.inMemory()`: the cursor, peer context tokens and lease are held in that process
only, so a restart replays or drops inbound messages and forgets the conversation context, and a
second instance cannot see the first. Use it for local development, not deployment.

Managed hosts should construct the Channel with `WeixinChannel.create(...)` and inject a
`WeixinCredentialProvider`, a durable `WeixinStateStore`, and a `WeixinRuntimeListener`. These
interfaces do not assume where credentials or state are stored; the module ships no Redis or JDBC
adapter, so a host that needs restart recovery and horizontal scaling implements the store
contract itself. `InMemoryWeixinStateStore` is the reference implementation of that contract and
is single-JVM state: it forgets accounts that never held anything (ids seen only while validating
a request) and expires completed message tombstones after seven days, but an account that has
actually consumed a message keeps its cursor and peer context until the host retires it with
`removeAccount(accountId)`. Dropping that state automatically would make the consumer replay the
provider backlog from the beginning.

`requestTimeoutMs` bounds the control calls and `longPollTimeoutMs` bounds the `getupdates` long
poll; the client adds a short grace to the latter, because a deadline shorter than the provider's
poll window would abort every poll.

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

Reply delivery belongs to the host. On the inbound path the channel does not send the reply itself:
a host that owns durable delivery persists it and returns no reply to the channel, so a provider
outage delays the reply instead of failing the message and replaying the Agent. `deliverWithReceipt`
sends one host-persisted reply and returns the provider's receipt; a failure propagates so the host
keeps the notification pending. Standalone deployments keep the inline path, where a single attempt
is made and a refusal is reported through `WeixinRuntimeListener.onDeliveryFailed`.

An inbound message whose dispatch keeps failing is abandoned after a small number of attempts and
tombstoned, reported through `WeixinRuntimeListener.onDispatchFailed`: one poison message can neither
run the Agent forever nor block the account's other messages.

Custom stores must implement the entire state contract; cursor-only adapters and default
successful no-op implementations are no longer supported. Runtime listeners can use
`onLeaseAcquired` to attach the lease generation to host-specific status reports.

### Login transport

The QR-login flow follows the provider protocol: `get_bot_qrcode` is a POST, while
`get_qrcode_status` is a GET whose `qrcode` — and, when the provider asks for one, `verify_code`
— travel as query parameters. The status URL is therefore a credential: treat it as a secret and
do not enable URI-level request logging (provider access logs, reverse proxies, or
`jdk.httpclient` debug logging) for this client. The module itself never logs a request URI or a
response body: provider failures are reported by status code, parse failures by line and column,
and every warning goes through `safeMessage(...)`, which reports the exception type plus a control-character-stripped, length-capped message — those messages are built from constants and numeric provider codes, never from a request URI, credential or payload.

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

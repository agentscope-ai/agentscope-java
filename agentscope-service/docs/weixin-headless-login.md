# Personal Weixin 控制面扫码登录

提供控制台与命令行两种登录入口：创建禁用渠道 → 扫码 → 可选验证码 → 确认安装 → Vault → Scheduler 配置刷新。个人微信协议仍由 Java 扩展实现，Go 只管理流程、归属和加密存储。

## 控制台操作

1. 在个人空间的 Channels 页面选择 New channel，平台选择 Personal Weixin，并选择自己的默认 Agent。Agent 详情的「连接微信」入口也会进入此流程，预选当前 Agent。
2. 点击「创建并连接微信」后，详情页显示本地生成的授权二维码。创建成功但二维码获取失败时，继续在该详情页重试，不重复创建 Channel。
3. 用微信扫码并确认。页面串行检查扫码状态；需要验证码时显示输入框。到达 AUTHORIZED 后点击「完成连接」，才会安装凭据并启用渠道。
4. 接收服务进入 RUNNING 后，在「关联聊天账号」中生成绑定码，将命令发送给微信机器人，再刷新绑定状态。扫码连接和聊天身份绑定是两个独立步骤；普通微信私聊仅支持个人空间内已绑定的账号。
5. 已连接的详情页支持重新授权、暂停/恢复接收以及断开连接。单纯打开页面不会开始授权。重新授权保留现有凭据，取消本次授权不会断开原连接。

二维码 URL 在浏览器内编码，不会提交给第三方二维码服务。页面刷新只保留按账号、空间和 Channel 隔离的 flowId，二维码和验证码不写入浏览器持久存储；刷新后可继续确认或提交验证码，仍需扫码时可重新生成二维码。授权过期、提供方故障和账号冲突均显示可重试状态。

## 启动配置

重新构建并启动 control 与 scheduler 后使用。控制面新增 `BUILDER_SCHEDULER_URL=http://scheduler:8083`；开发用 `docker-compose.yml` 已包含。其他部署方式需要设置可从控制面访问的 Scheduler 内部地址。两端必须使用相同的 `BUILDER_INTERNAL_TOKEN`。Vault 加密沿用控制面的 `BUILDER_VAULT_MASTER_KEY` 配置；该密钥不需要提供给 Scheduler。

控制面启动会创建 `cp.weixin_connections` 和 `cp.weixin_link_flows`，并每分钟清理过期流程的临时密文。登录流程最长 5 分钟；iLink 自身的二维码有效期可能更短。控制面重启后，持有 flowId 的同一登录用户可以继续操作。二维码图片只在开始时返回，请在有效期内保留显示；丢失二维码后重新开始登录即可。

## 命令行操作

以下示例假定已有控制面 Bearer token（`TOKEN`）和一个可运行的 Agent。`BASE` 是公开入口；不要从浏览器调用 `/api/internal/**`。团队命名空间需要在每次请求中保持相同的 `X-AgentScope-Tenant` / `X-AgentScope-Namespace` 请求头。

当前 Agent 创建入口为 `POST /api/v1/agents`，请求中的 `tenant` / `namespace` 必须与登录用户的授权范围一致；个人空间也有独立的 namespace，不能直接假定为 `default`。使用返回的 Agent UUID 作为渠道的 `defaultAgentId`。真实自动回复还需要为 `data` 服务配置可用模型。当前镜像包含 DashScope 和 DeepSeek provider：DeepSeek 使用 `DEEPSEEK_API_KEY`，Agent 的模型字段填写 `deepseek:deepseek-chat` 或 `deepseek:deepseek-reasoner`；不要只填写 `deepseek`。当前 Vault 工具凭据不会自动成为模型 API Key。

```bash
BASE=http://localhost:18080
CHANNEL=weixin-canary

# 将 your-agent-id 替换为现有 Agent id。新渠道始终禁用，直到授权完成。
curl --fail-with-body -sS "$BASE/api/channels" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"channelId":"weixin-canary","type":"weixin","defaultAgentId":"your-agent-id","dmScope":"PER_PEER"}'

# 返回 flowId、qrcodeImage、expiresAt 和 WAITING_SCAN。
curl --fail-with-body -sS -X POST "$BASE/api/channels/$CHANNEL/weixin/link-flows" \
  -H "Authorization: Bearer $TOKEN"

# 填入上一响应的 flowId。将 qrcodeImage 的 URL 内容本地编码成二维码，用微信扫码。
FLOW=returned-flow-id

# 至少间隔 2 秒轮询；单次请求可能等待约 45 秒。
curl --fail-with-body -sS -X POST "$BASE/api/channels/$CHANNEL/weixin/link-flows/$FLOW/poll" \
  -H "Authorization: Bearer $TOKEN"

# 仅当状态为 NEED_VERIFY_CODE 时提交。验证码不落库。
curl --fail-with-body -sS -X POST "$BASE/api/channels/$CHANNEL/weixin/link-flows/$FLOW/verify" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"verifyCode":"code-from-weixin"}'

# 到达 AUTHORIZED 后确认安装。可以重复调用；不会重复创建凭据。
curl --fail-with-body -sS -X POST "$BASE/api/channels/$CHANNEL/weixin/link-flows/$FLOW/complete" \
  -H "Authorization: Bearer $TOKEN"

# 等待 Scheduler 的配置刷新，默认约 15 秒。
curl --fail-with-body -sS "$BASE/api/channels/$CHANNEL/weixin/status" \
  -H "Authorization: Bearer $TOKEN"
```

`qrcodeImage` 是协议提供的扫码展示内容（可能是 URL 或图片内容）。它是一次性挑战；普通响应不包含 `botToken`、原始轮询会话或 `credentialRef`。`AUTHORIZED` 表示暂存授权成功，`COMPLETED` 才表示已经原子安装凭据并启用渠道。

连接状态响应会在已关联的连接上返回 `accountId`，它就是 iLink 返回的稳定 `ilink_bot_id`，用于确认当前 Channel 关联的是哪个微信 Bot；未连接时为 `null`。该字段不是 token 或凭据引用，可以展示在控制台，但不能用它替代 Vault 中的凭据。

同一 flow 的操作要求命名空间、渠道、发起用户和流程代次一致。正在进行的轮询禁止并发；遇到 429 时至少等 2 秒再重试。开始扫码每个渠道至少间隔 5 秒。提供方暂时不可用时流程保留原状态并返回 `errorCode`，可在有效期内重试。

## 后续操作

| 操作 | 请求 | 行为 |
| --- | --- | --- |
| 查询某次流程 | `GET /api/channels/{channelId}/weixin/link-flows/{flowId}` | 返回状态和有效期，不重复返回二维码 |
| 取消 | `POST .../link-flows/{flowId}/cancel` | 清除临时密文，延迟响应不能恢复流程 |
| 重连 | `POST /api/channels/{channelId}/weixin/relink` | 开始新流程，旧流程失效；现有凭据在确认新授权前保留 |
| 暂停 | `POST /api/channels/{channelId}/disable` | 禁用渠道并取消待完成流程，保留已安装凭据 |
| 恢复 | `POST /api/channels/{channelId}/enable` | 仅允许已经有可用授权的渠道 |
| 断开 | `POST /api/channels/{channelId}/weixin/disconnect` | 禁用渠道、删除专用凭据、清除临时授权；返回 `remoteRevoked: false` |
| 删除 | `DELETE /api/channels/{channelId}` | 同时删除专用凭据及登录流程，保留空 Vault |

重连仅接受当前已绑定的 iLink 机器人账号；更换账号需要先断开。每个 Channel 最多安装一个连接，同一 `ilink_bot_id` 只能安装到一个 Channel；它不同于扫码者的 `ilink_user_id`，不能据此推断一个自然人的微信只能创建一个 Channel。确认时自动创建专用 Vault，后续在其中轮换同一凭据并递增 revision。Vault 被归档或删除会使凭据解析失败，重新授权前需恢复 Vault，或删除并重建该渠道。

微信 provider 属性由授权事务生成。通用渠道修改和旧 Agent presence 的凭据表单不能覆盖它们；当前使用 Channels 接口管理微信连接。内部凭据解析同时核对渠道、归属、凭据类型、目标和 revision，旧 Scheduler 实例无法使用新授权的凭据。

## 验证范围

数据库集成测试使用独立 PostgreSQL 和假的 Scheduler，验证跨控制面实例恢复、验证码、幂等完成、轮换、账号唯一性、事务回滚、归属隔离、取消/过期/禁用与延迟响应竞争。Java 测试验证内部鉴权、真实 HTTP 协议调用、可携带会话、URL 校验和错误脱敏。

Scheduler 使用独立的运行状态表保存消息、游标、上下文和带版本的账号租约；建表及升级由 Scheduler 的 `WeixinStateSchema` 执行。消息与游标在同一事务内保存，重启后优先恢复未完成消息。处理期间独立续租，备用实例持续尝试接管，发送前验证租约。已完成消息立即清除正文，去重标识保留七天并在后续接收时清理；未完成消息及每个对话的最新上下文保留。

运行上报携带账号、凭据 revision、租约 generation 和序号，控制面拒绝旧版本及乱序报告；备用实例不覆盖主实例状态。iLink `-14` 映射为 `REAUTH_REQUIRED`。升级时需先更新控制面，再更新并重启所有 Scheduler 副本；旧版无版本运行报告不再更新微信状态。

消息处理采用至少一次语义：外部 Agent 执行或回复成功后、收件箱完成前崩溃，可能重复执行或回复；发送前检查不能撤回已经发出的网络请求。当前没有宣称 iLink 或 Agent 提供端到端恰好一次保证。

控制台连接流程已实现；自动化浏览器验证覆盖扫码状态、验证码、确认、过期重试、取消、账号冲突、暂停恢复、断开和聊天身份绑定。`RUNNING` 表示运行观察结果，不能替代真实消息收发验收。真实账号复验进展见下方各次验收记录。

### 2026-09-16 运行时修复验证

- Java 定向回归：30 个测试通过，需人工扫码的真实微信冒烟测试跳过；包含 Spring Scheduler 启动装配、慢消息续租、失租后抑制回复、备用实例接管和状态上报。
- JDBC 的 8 个测试分别通过 H2 和独立 PostgreSQL 验证，覆盖消息/游标事务回滚、重启恢复、并发租约、过期领取、旧持有者隔离、旧表升级和已完成消息保留周期。
- 控制面 `go test ./internal/product -count=1` 全量通过。测试数据库必须显式提供运行时 `search_path=rt`；产品连接自行使用 `cp`，不能继承开发账号的 `dp,cp,public` 默认搜索路径。
- 本轮完成源码与自动化验证，尚未将这些修复部署到现有容器，也未进行本轮真实微信收发复验。

### 2026-09-15 本地部署验收

- 已备份开发数据库并保留更新前镜像。使用本机编译的 Linux Go 程序和新 Scheduler JAR 更新运行镜像，重建 `control` / `scheduler` 容器；两者健康检查通过。
- 启动自动创建 `cp.weixin_connections`（12 列）与 `cp.weixin_link_flows`（16 列），5 个索引、账号唯一约束及级联外键均存在，原有 3 个用户保留。
- 控制面内部配置接口拒绝浏览器 JWT（401），接受内部凭据（200）。Scheduler 登录接口拒绝匿名请求（401）和浏览器 JWT（403），内部凭据可到达参数校验（400）。
- 已在开发管理员的个人空间创建 `Weixin Canary` Agent 和 `weixin-canary` 渠道。新渠道强制禁用，授权前启用返回 409。
- 真实 iLink 登录开始请求通过新 Go → Scheduler 链路返回 `WAITING_SCAN` 和扫码展示地址，数据库中会话为密文。
- 尚待用户扫码确认、Vault 凭据安装、真实消息收发和已安装凭据的重启恢复。当前开发环境未配置模型密钥，Agent 自动回复验收尚不可执行。

### 2026-09-16 微信会话在控制台不可见的修复

本轮在上述部署之后继续验证。真实微信会话 `sess_ff134f124fa4` 已有 7 轮对话，但控制台会话列表没有记录，原因有两层：

1. Scheduler 原先调用产品的 `/api/internal/sessions/find-or-create`，只创建 `cp.sessions`。数据面已有事件同步逻辑，但由于缺少 `rt.sessions`，42 次事件投影均返回 `runtime session not found`。
2. 补登记后，使用真实登录账号查询仍被隐藏。控制台会话权限只识别关联 Issue 的任务会话和用户创建的 Chat，没有渠道会话所有者的判断。

Scheduler 现在先调用控制面新增的 `/api/internal/managed-sessions/find-or-create`。控制面验证 Agent 所有者和 Managed binding，沿用原始 externalKey 复用产品会话，并在数据面执行前登记运行时会话。接口只返回会话 ID；Scheduler 使用独立的最小响应类型，避免依赖完整会话 DTO 的时间字段。登记使用跨控制面实例的会话锁，重复请求保留已有阶段、忙碌状态和时间戳。

渠道会话在创建时保存经过验证的稳定账号 ID（`task_context.channelOwnerRef`），列表和详情权限均使用该归属，不依赖后续 Agent 所有者变化。普通命名空间管理员不会因此获得其他用户的私人消息；显式审计角色沿用原有读取规则。该流程不创建 `rt.chat_conversations`，历史入口为 Agent 的运行时会话详情。

本地验收结果：

- 已保留原会话 ID，并通过现有内部事件投影接口补回 42 条历史事件；数据面事件 ID 与运行时事件逐条匹配。补录没有再次执行 Agent，也没有重发微信消息。
- 已为本轮先前补登记的唯一历史会话，根据 `cp.sessions` 的所有者、Agent 和 externalKey 精确补齐渠道归属。
- control 与 scheduler 已更新为 `weixin-session-20260916` 镜像；运行中的 Scheduler JAR 哈希与测试产物一致。重建后刷新了 gateway / data 的服务连接，所有服务健康。
- 使用实际登录账号通过 `http://localhost:18080` 验证：会话列表、详情、事件和消息接口成功；14 条消息对应 7 条用户消息和 7 条助手回复，最后回复为 `AS-WX-0916-C`。另一账号读取返回 404，内部登记接口的匿名请求返回 401。再次登记仍返回原 ID，完整运行时会话记录保持不变。
- Java 的 `ManagedSessionChannelBridgeTest` 与 `ChannelWorkBridgeTest` 共 5 项测试通过，覆盖先登记后执行、消息序列轮询及登记失败不执行。PostgreSQL 定向测试覆盖新旧会话、重复事件、状态保持；内存库和 PostgreSQL 权限测试覆盖所有者、其他成员、命名空间管理员、外部用户及审计角色。
- `go test ./internal/httpapi ./internal/product ./internal/store/memory ./internal/store/postgres` 通过；真实数据库定向验证使用独立测试库和临时 schema。

本轮完成历史恢复和部署后的接口验收，尚未发送新的真实微信消息进行整链路收发复验。

### 2026-09-16 控制台扫码流程验收

- 前端构建与 TypeScript 检查通过，涉及文件的 ESLint 无错误或警告；5 项单测和 11 项浏览器测试通过，其中包含原有飞书工作配置与成员绑定回归。
- 浏览器测试覆盖创建时选择默认 Agent、二维码像素检查、显式确认安装、验证码和刷新恢复、过期重试、提供方故障、账号冲突、串行长轮询与限流、取消、暂停恢复、断开、身份绑定，以及 Agent 入口预选。桌面与 390px 手机宽度均已检查。
- 本地 control 已更新为 `agentscope-service-control:weixin-ui-20260916`，保留此前运行时修复的服务程序与部署配置；服务健康。
- 使用实际登录账号访问现有 `weixin-canary`，页面显示已连接、身份已关联；没有对该渠道发起任何变更请求。
- 通过已部署页面创建临时 Channel，真实 Go → Scheduler → iLink 请求返回二维码，浏览器本地渲染成功；完成取消授权后删除测试 Channel。浏览器未出现脚本错误，现有连接仍为 `RUNNING`。
- 本轮没有执行新的手机扫码确认或发送真实微信消息。扫码后的状态流转由自动化模拟响应验证，真实消息收发仍需手机端验收。

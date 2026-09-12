# MCP OAuth 账号连接

本实现把获取初始 access token / refresh token 的过程接入控制台。使用者在 MCP connection 上选择 Vault、配置 OAuth 应用、点击 **Connect account**，在服务商窗口登录并同意授权，回到控制台后凭证自动加密保存。在 Agent 页面操作时，所选 Vault 会自动加入该 Agent 的 `defaultVaultIds`，新 Session 默认使用。

GitHub 官方远程 MCP 现已提供[管理员统一配置与一键连接流程](github-account-connection.md)。以下手工应用配置用于其他服务商及已有自定义连接。

## 部署与首次配置

控制面 aistiod 配置：

```sh
BUILDER_OAUTH_PUBLIC_URL=https://console.example.com
```

该值必须是浏览器实际访问控制台的 origin（含协议和必要的端口，不含路径）。控制台与 `/api` 必须同源；不能配成浏览器不能访问的容器地址。生产环境要求 HTTPS。本地开发可使用 `http://localhost:18080` 或其他 loopback origin；未配置时仅允许从 loopback 请求推导 origin，不信任 `X-Forwarded-Host`。

沿用已有 Vault 加密密钥配置 `BUILDER_VAULT_MASTER_KEY`；多副本应共享稳定密钥。启动时自动创建 `mcp_oauth_connections` 和 `mcp_oauth_flows` 两张表。需重新构建、发布控制面及前端，无需为这次授权流程改动 Java Brain。

OAuth 回调 GET `/api/oauth/mcp/callback/{connectionId}` 需要从浏览器可达。其余 OAuth API 仍要求登录和命名空间权限。控制面访问日志跳过回调请求；部署方的网关/反向代理也应避免记录该路径的 query string，以免记录授权码和 state。

## 完整操作例子

以支持 OAuth Authorization Code + PKCE 的 CRM MCP 服务为例（以下地址和权限名为示例，以服务商实际文档为准）：

1. 在 Agent → Definition → Tools & MCP 添加 `crm`，Endpoint 为 `https://crm.example.com/mcp`，保存连接和工具权限策略。
2. 点击该连接的 **Connect account**，选择已有 Vault，或点击 **Create Vault**。此账号的 MCP 权限将供使用该 Vault 的 Agent/Session 使用；需要个人隔离时选择个人命名空间内的 Vault。
3. 在 CRM 的开发者控制台创建 OAuth 应用。在平台填写：

   | 字段 | 示例 |
   | --- | --- |
   | Authorization endpoint | `https://auth.crm.example.com/oauth/authorize` |
   | Token endpoint | `https://auth.crm.example.com/oauth/token` |
   | Client ID | CRM 分配的应用 ID |
   | Client authentication | `client_secret_basic`（以服务商要求为准） |
   | Client secret | CRM 分配的应用密钥；保存后不回显 |
   | Scopes | `crm.read offline_access`（以服务商要求为准） |
   | Resource | `https://crm.example.com/mcp`（默认当前 MCP 地址） |
   | Additional authorization parameters | `{}`；部分服务商需 `{"access_type":"offline","prompt":"consent"}` |

4. 点击 **Save application**，复制出现的 **Callback URL**。每个 OAuth connection 有独立且稳定的回调路径。把该 URL 加入 CRM OAuth 应用的允许回调列表。有些提供商创建应用时就要求回调，可先登记其允许的临时回调，取得 client ID 后再替换为这里生成的精确地址。
5. 回到平台点击 **Connect account**。浏览器打开 CRM 登录/同意授权页面，用户在 CRM 页面操作，不向 AgentScope 提交 CRM 密码。
6. CRM 跳回平台回调地址；控制台自动完成确认，显示 **Account connected and Vault added to this agent**。无需复制 token，也无需在 Agent Headers 中配置 Authorization。
7. 创建新 Session。所选 Vault 默认挂载，凭证以 MCP 完整 endpoint URL 为 Target，Brain 只收到 access token，并将其作为 `Authorization: Bearer …` 发给匹配的 MCP 服务。若创建 Session 时显式指定 `vaultIds`，需把这个 Vault 包含进去。

Workspace → Tools & MCP 同样提供账号连接入口，但不会自动修改所有关联 Agent。完成后需要在相应 Agent 的 Runtime configuration 或创建 Session 时选择该 Vault。关联 Workspace 的 Agent 仍可在自己的 Tools & MCP 页面连接账号，工具定义仍由 Workspace 管理。

## 顺序与安全边界

1. 已登录控制台发起 authorize。服务端生成随机 state、浏览器绑定 cookie、PKCE verifier；数据库只保存 state/cookie 哈希，verifier 与应用配置快照加密保存。流程有效期 10 分钟。
2. 浏览器携带 `code_challenge_method=S256`、challenge、scope/resource 等访问服务商 authorize endpoint。cookie 是 HttpOnly / SameSite=Lax，HTTPS 环境启用 Secure，仅限该连接的回调路径。
3. 回调验证 state、浏览器 cookie、时效、连接配置代次和 Vault 状态；若配置了 issuer，还必须匹配响应的 `iss`。同一个 flow 只能被领取一次，避免并发/重复回调重复换码。
4. 服务端使用 code + verifier 向 token endpoint 换 token；不跟随重定向，不将服务商原始错误、授权码或 token 回显给控制台。支持 public client（none）、client_secret_basic、client_secret_post；token 类型必须是 Bearer。
5. 回调暂存加密 token。原控制台轮询后，使用原登录身份调用 complete，再次校验 namespace / Vault 权限和发起人。回调本身不会绕过当前用户权限直接写入 Vault。
6. complete 在事务内写入 `mcp_oauth` 凭证，清除临时 token/verifier，并返回不含密钥的元数据。重复 complete 幂等；重新授权更新同一凭证并递增 revision。客户端密钥保存在加密的 OAuth 配置和必要的 refresh 配置中，不写入 Agent/Workspace definition。
7. 现有控制面凭证解析在 token 即将过期时刷新，并持久化服务商返回的新 refresh token。新增 resource 参数在换码和刷新时保持一致。Brain 不收到 refresh token 或 client secret。

同一 Vault、同一完整 endpoint 复用一个 OAuth 配置。重新开始授权、修改配置、断开连接会使旧流程失效；取消会清除临时敏感数据。过期流程拒绝使用，在后续授权发起时清理数据库记录。修改 client ID、token endpoint 或客户端认证方式时，必须重新输入 secret，不能将保存的密钥转发到新地址。

## 断开、异常与当前边界

- **Reconnect account** 重新登录授权；成功前保留已有有效凭证。**Disconnect from Vault** 删除本地凭证并取消未完成流程，保留应用配置，方便再次连接。它不调用服务商 revoke endpoint；若需撤销服务商端授权，请在服务商账号中撤销。已运行 Session 持有的 access token 不会被瞬间收回。
- 弹窗被拦截时需允许本控制台弹窗。授权时保持原对话框打开；关闭对话框会尽力取消流程。页面刷新/关闭后，本次临时流程不会自动恢复，可重新连接。
- 若账号已连接但更新 Agent 失败（例如版本冲突），凭证仍在 Vault，点击 **Retry adding Vault to agent** 重试，无需再次登录。已有连接可通过 **Use for this agent** 关联。
- 同一 Vault 内已存在同 endpoint/连接名的其他 bearer 凭证时拒绝覆盖，需先处理冲突；跨 Vault 重复凭证仍由运行时的歧义检测拒绝。不要同时给同一 MCP 服务挂载多份 bearer 凭证。
- **Connected** 表示本地存在授权凭证，不代表持续健康检查。服务商未返回 refresh token 时，过期后需要重新授权；未返回 expiry 时无法预先判断过期。
- 自定义服务商支持手工注册 OAuth 应用和配置 endpoints；GitHub 支持管理员统一配置及账号身份展示。尚未实现 MCP authorization metadata 自动发现、Dynamic Client Registration、Client ID Metadata Document、通用 Provider registry、自动 provider revoke、通用 OIDC 登录身份展示，以及 RFC 8693 token exchange。不是任意 MCP URL 的零配置自动 OAuth。
- 本次只改变获取初始授权的流程。单轮长任务中的 MCP 客户端 token 热替换 / 401 自动恢复仍未实现，下一轮解析或新 Session 使用更新后的凭证。
- OAuth endpoint 为 HTTPS；控制面网络需要能访问 token endpoint，Brain 网络需要能访问 MCP endpoint。组织级 egress 策略和私网代理属于独立能力。

协议参考：[OAuth 2.0 Security Best Current Practice](https://www.rfc-editor.org/rfc/rfc9700.html)、[MCP Authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)。

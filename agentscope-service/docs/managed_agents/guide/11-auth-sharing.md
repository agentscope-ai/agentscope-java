# Auth & Sharing

[← Deployments](10-deployments.md) · [回目录](README.md) · [下一页：Limitations →](12-limitations.md)

---

## 三种凭证

| 客户端 | 凭证 | 用途 |
|---|---|---|
| Console / CLI / 前端 | `Authorization: Bearer <JWT>` | 几乎所有 `/api/**` |
| Environment Worker | `X-Builder-Environment-Key` | `…/work/poll|ack|heartbeat|stop` 等 |
| Deployment Webhook | 路径中的 webhook token | `POST /api/deployments/webhook/{token}` |

登录：

```bash
POST /api/auth/login
{"username":"admin","password":"admin"}
→ { "token", "userId", "username", "roles" }
```

JWT secret：`builder.jwt.secret`（≥32 字符，生产必改）。

Environment key：仅 create / `POST /api/environments/{id}/keys/rotate` 响应中的 `environmentKey` 明文一次；库内只存 hash。

## 分享 ACL

Agent、Environment、Memory Store、Vault 等支持 shares：

- `GET/POST/DELETE …/{id}/shares`
- 档位常见为 **RUN** / **EDIT**（以 `AgentAclService.Tier` 为准）
- 可分享给用户或 workspace 级 grantee（具体 body 见各 Controller / ShareRequest）

**Session 多为 owner-only**：创建者才能读写该会话事件；不要假设组织级隐式 ACL。

## 安全建议

- 生产关闭默认密码；轮换 JWT secret 与 vault master key。
- Worker 只持有 environment key，不持有用户密码。
- Webhook token 视为密钥，泄露即轮换/重建 Deployment。

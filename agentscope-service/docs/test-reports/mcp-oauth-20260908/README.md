# MCP OAuth 验证 — 2026-09-08

代码位置：主项目 `/Users/ken/agentscope-2/agentscope-java`，分支 `agentscope-service-v5`。未使用独立 worktree，未重启/部署现有服务。

## 通过的检查

- `go test ./internal/product ./internal/httpapi`：使用隔离的 PostgreSQL 17 容器，验证资源/控制面现有测试与 OAuth 新测试。见 [日志](go-tests.log)。
- `go test -race ./internal/product -run 'TestMcpOAuth|TestRefreshOAuth' -count=1`：本地模拟 TLS provider，验证 PKCE、三种 client authentication、browser cookie/state、回调并发领取/重放、拒绝授权、过期、取消、issuer、配置变化、namespace/发起人隔离、Vault archive/disconnect 竞态、重复凭证保护、加密保存、refresh token 轮换、仅 access token 下发。见 [日志](go-race.log)。
- `npm run build`：TypeScript 检查与 Vite 生产构建通过，更新主目录 `aistio/ui`。见 [日志](frontend-build.log)。
- `go build -o /tmp/managed-oauth-aistiod ./cmd/aistiod`：构建嵌入新 UI 的控制面二进制。
- Playwright：`e2e/mcp-oauth.e2e.ts` 与 `e2e/managed-mcp.e2e.ts` 共 4 项通过。覆盖 Agent（关联 Workspace）授权后自动追加 Vault、保留已有资源定义、取消/拒绝授权和原 MCP 编辑器回归。见 [日志](browser-tests.log) 和 [授权完成截图](connected.png)。

浏览器使用模拟 API 与服务商登录页面，Go 集成测试使用真实 HTTP/TLS token endpoint 和 PostgreSQL；没有使用用户的真实 OAuth 应用或账号。尚未声称通过某个外部 SaaS 的实网验收。Java Brain 沿用已有 Vault 注入路径，本次未修改 Java 源码，因此未重跑整个 Maven reactor。

部署配置与操作步骤见 [账号连接说明](../../controlplane/mcp-oauth-account-connection.md)。

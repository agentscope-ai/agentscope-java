# Managed Agent 本地验证

- [Java 专项汇总](java-targeted-summary.log)：13 类、81 个测试通过，相关 reactor verify 成功（跳过 Javadoc）。
- [Go 测试](go-tests.log)：product 与 httpapi 通过，使用独立 PostgreSQL 测试实例。
- [Go 构建](go-build.log)：退出码 0，输出可执行文件 `/tmp/managed-aistiod`；成功构建无文本输出。
- [前端构建](frontend-build.log)：TypeScript 与 Vite 通过。
- [浏览器回归](browser-tests.log)：MCP 连接与工具策略保存/刷新/删除通过。
- [页面截图](mcp-editor.png)：浏览器测试中的模拟配置，不是真实服务或凭证。

完整验证限制及剩余实现边界见 [实施记录](../../controlplane/managed-agent-implementation-2026-09-08.md)。没有执行生产部署。

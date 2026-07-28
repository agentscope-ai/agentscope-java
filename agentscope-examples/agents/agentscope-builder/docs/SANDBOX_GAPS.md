# Managed sandbox（E2B）· 待完善清单

> Builder `Environment.type=sandbox` **已接线** [`agentscope-extensions-sandbox-e2b`](../../../../agentscope-extensions/agentscope-extensions-sandbox/agentscope-extensions-sandbox-e2b/)（`E2bFilesystemSpec`）。  
> 本文对照 Claude Managed Agents [`cloud` environment](https://platform.claude.com/docs/en/managed-agents/environments) 记录仍缺能力。  
> 用法见 [guide/05-environments.md](guide/05-environments.md)；self_hosted 缺口见 [SELF_HOSTED_GAPS.md](SELF_HOSTED_GAPS.md)。

最后更新：2026-07-22

---

## 0. 已落地

| 能力 | 落点 |
|---|---|
| `type=sandbox` → E2B only | `EnvironmentSpecFactory` + `agentscope-extensions-sandbox-e2b` |
| 凭证 | `config.apiKey` → `builder.e2b.api-key` / `BUILDER_E2B_API_KEY` / `E2B_API_KEY` |
| template / timeout / workspace / persistence | 映射到 `E2bFilesystemSpec` fluent API |
| 创建 env 缺 key 时 400 | `EnvironmentService.normalizeConfig` |
| 忽略本机 Docker 配置键 | `image` / `cpus` / … 仅 warn，不生效 |

---

## 1. 相对 Claude `cloud` 仍缺

| 项 | 说明 | 优先级 |
|---|---|---|
| **packages 真预装 + 按 env 缓存** | Claude：`packages.{pip,npm,apt,…}` 在 agent 启动前安装并跨同环境 Session 缓存。E2B 侧请用自定义 `templateId`；Builder 不执行安装流水线 | P1 |
| **networking.limited / allowed_hosts** | Claude egress 策略；E2B create API 当前扩展仅 `templateID`+`timeout`，无 Builder 侧 allowlist | P1 |
| **allow_package_managers / allow_mcp_servers** | Claude limited 网络附属开关 | P2 |
| **标准 runtime 参考清单** | Claude 有 Ubuntu + 语言/工具固定表；我们依赖所选 E2B template | P2 |
| **Environment update API** | 改 packages/networking 需新建 env 或手改 config JSON | P2 |
| **规格 SLA（mem/disk）产品化** | Claude 文档有上限；E2B 由模板/账号配额决定 | P3 |
| **API 命名别名 `config.type=cloud`** | 对外仍用顶层 `type=sandbox` | P3 |

---

## 2. 建议下一迭代

1. （可选）扩展 `E2bPlatformHttp.createSandbox` 支持 E2B 原生网络/元数据字段后，再映射 Claude `networking`。  
2. packages：要么文档强制「只走 template」，要么在 sandbox 首次 ready 后 `exec` 安装并缓存层。  
3. 公开一份「推荐 E2B template」对照 Claude cloud sandbox reference。

---

## 3. 相关代码

| 区域 | 路径 |
|---|---|
| 装配 | `…/managed/EnvironmentSpecFactory.java` |
| 校验 | `…/managed/EnvironmentService.java` |
| 全局配置 | `…/config/BuilderE2bProperties.java`、`application.yml` → `builder.e2b.*` |
| 扩展 | `agentscope-extensions-sandbox-e2b` |

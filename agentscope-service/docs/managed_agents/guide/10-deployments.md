# Deployments

[← Memory & Vault](09-memory-vault.md) · [回目录](README.md) · [下一页：Auth & Sharing →](11-auth-sharing.md)

---

**Deployment** 用定时、Webhook 或手动触发：创建 managed Session 并跑一轮 turn。

基路径：`/api/deployments`。

| Method | Path | 说明 |
|---|---|---|
| `GET` | `/api/deployments` | 列表 |
| `POST` | `/api/deployments` | 创建（绑定 agent / env / 触发配置） |
| `GET` | `/api/deployments/{id}` | 详情 |
| `POST` | `/api/deployments/{id}/archive` | 归档 |
| `DELETE` | `/api/deployments/{id}` | 删除 |
| `POST` | `/api/deployments/{id}/run` | 手动跑一次 |
| `POST` | `/api/deployments/webhook/{token}` | Webhook 触发（**无需用户 JWT**，靠路径 token） |

内部可对缺失环境调用 `ensureDefaultEnvironment`；与 HTTP 手建 Session 必须显式 `environmentId` 不同。

Cron 触发带协调层 fire lease，避免多副本重复开火。无 pause/unpause 专用动词时，可用 archive / 删除触发配置代替。

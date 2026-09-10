---
title: "Docker：单机部署与远程访问"
---

[English](/v2/en/service/docker)

按[本地安装](/v2/zh/service/quickstart)启动后，本页说明如何配置远程访问、持久存储和日常维护。生产使用前落实 HTTPS、执行隔离和备份恢复。

## 入口与网络

| 组件 | 容器端口 | 暴露方式 |
| --- | --- | --- |
| Gateway | 8080 | 默认宿主 `127.0.0.1:18080` |
| Control | 8081 | 内部网络 |
| Dataplane | 8082 | 内部网络 |
| Scheduler | 8083 | 内部网络 |
| PostgreSQL | 5432 | 内部网络 |

同机反向代理可以转发到 `127.0.0.1:18080`。代理运行在另一个容器时，localhost 指代理容器自身，需要配置共享网络或可达的宿主地址。只向用户暴露 Gateway，内部组件和数据库保留在私网。

## 启用远程访问

准备域名和 TLS 证书，将 HTTPS 代理指向 Gateway。在 `.env` 设置 `BUILDER_OAUTH_PUBLIC_URL=https://agentscope.example.com`，如需监听其他地址再设置 `BIND_ADDRESS` 和 `GATEWAY_PORT`，之后重建容器。

代理必须支持 SSE：及时转发事件、不缓存事件流，并设置足够长的读取超时。验证登录、长回复、刷新重连及 OAuth/Channel 回调，不能只验证首页可打开。

## 数据持久化

三个命名卷分别保存 PostgreSQL、共享 Workspace 和 Artifact。使用 `docker volume ls` 找到 Compose 项目卷，按存储策略备份。`.env` 中的 Vault master key 必须和加密数据一起保存。

需要接入宿主目录时显式配置挂载，并保证容器用户 `65532:65532` 有必要访问权限。不要把本地路径写入 Agent 指令后就假定容器能够读取。业务资料的访问应与所选 Environment 保持一致。

## 更新配置和版本

编辑 `.env` 后：

```bash
docker compose up -d --wait --wait-timeout 600
docker compose ps
```

版本升级还需先拉取新镜像。`init-env.sh` 不覆盖已有配置，因此修改版本应编辑 `SERVICE_VERSION`。密钥变更需要协调组件；Vault master key 不是可随意替换的普通密码。

完整 Compose 运行 standalone HTTP。需要 ASDP 的 External SDK 请使用[对应接入部署](/v2/zh/service/external-agent)。多节点调度与生产存储编排见 [Helm](/v2/zh/service/kubernetes)。升级前完成[备份恢复演练](/v2/zh/service/operations)。

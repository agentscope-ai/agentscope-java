# Docker 部署

发布部署使用 `agentscope-service/deploy/compose.yaml` 中的镜像引用。仓库原有 `agentscope-service/docker-compose.yml` 用于从源码构建的开发环境，两者使用不同的初始化配置。

## 服务与存储

| 服务 | 容器端口 | 对外暴露 |
| --- | --- | --- |
| Gateway | 8080 | 默认 `127.0.0.1:18080` |
| Control | 8081 | 仅内部网络 |
| Dataplane | 8082 | 仅内部网络 |
| Scheduler | 8083 | 仅内部网络 |
| PostgreSQL | 5432 | 仅内部网络 |

Compose 等待数据库和上游组件健康后再启动依赖组件。`docker compose up -d --wait --wait-timeout 600` 等待整栈健康；单个 Gateway 健康接口不能替代完整业务检查。

三个命名卷分别保存 PostgreSQL、共享 Workspace 与 Artifact。不要用开发环境的数据库重置脚本维护发布安装。

## 远程访问

将 HTTPS 反向代理指向 Gateway，允许 SSE 长连接并关闭事件流缓冲。在 `.env` 中把 `BUILDER_OAUTH_PUBLIC_URL` 设置为用户访问的公开 origin，再重建对应容器。回调平台配置必须使用同一个公开地址体系。

需要改变监听地址或端口时配置 `BIND_ADDRESS` 和 `GATEWAY_PORT`。数据库和内部组件无需开放到公网。

## 更新配置

编辑 `.env` 后运行：

```bash
docker compose up -d --wait --wait-timeout 600
docker compose ps
```

`init-env.sh` 不覆盖现有文件，也不会重新生成密钥。JWT、内部令牌和 Vault 密钥必须在使用它们的组件间一致。Vault 密钥变更不能当作普通配置刷新处理。

升级版本前先完成[备份](operations.md)。镜像发布方式参见[发布指南](releasing.md)。

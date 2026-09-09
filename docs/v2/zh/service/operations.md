# 备份、升级与恢复

一份可恢复的备份包括数据库、Workspace、Artifact 和解密这些数据所需的密钥。

## Docker 备份

安排维护窗口，先停止四个应用组件，保留数据库运行：

```bash
docker compose stop gateway scheduler data control
mkdir -p backup
chmod 700 backup
docker compose exec -T db pg_dump -U agentscope -d agentscope -Fc > backup/database.dump
```

使用你的卷备份工具为 `workspaces`、`artifacts` 命名卷制作快照，保存 `.env`，并记录 `SERVICE_VERSION`、镜像 digest 和快照时间。备份目录应位于安装目录之外的受保护持久存储中。确认数据库备份与文件快照属于同一维护窗口后再启动应用。

```bash
docker compose up -d --wait --wait-timeout 600
```

Kubernetes 同样需要数据库备份与 PVC 快照；根据存储提供程序选择快照方式，保存配置 Secret 的受保护副本。

## 升级

阅读新版本的迁移与兼容性说明，先在测试数据库副本上演练。完成备份后，Docker 修改 `.env` 中的 `SERVICE_VERSION`，拉取镜像并重建容器；Helm 使用指定版本的 Chart 执行 upgrade。

Go 在启动时执行产品与运行状态迁移，Java 当前通过 Hibernate 更新表结构。组件镜像回退不保证旧版本可以读取新 schema。

## 恢复

需要回退数据时，先停止应用写入。在独立空数据库中恢复备份，恢复同一批 Workspace / Artifact 快照与原 Vault 密钥，再用备份对应的组件版本启动。不要把 `pg_restore --clean` 指向仍在使用的业务数据库。

对新建的恢复数据库执行示例：

```bash
pg_restore --no-owner --no-acl --dbname="$RESTORE_DATABASE_URL" backup/database.dump
```

恢复到同一 PostgreSQL 实例中的新数据库后，在 Compose `.env` 设置 `POSTGRES_DB` 为恢复库名称，并使用原密钥和同批文件快照启动。数据库容器只在数据目录为空时初始化数据库；已有实例中的恢复库需要预先创建。

## 恢复验收

检查管理员登录、已有 Agent 与 Session 历史、Workspace 文件、Vault 凭据解密、运行时连接，以及一项新的小任务。Task/Issue 的验收状态应与备份时刻相符。

只有数据库、文件和密钥都能共同恢复，备份才算通过验证。当前发布准备不承诺多副本 HA 或无停机升级。

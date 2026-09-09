# 账号、Namespace 与个人设置改造

> 2026-09-09：在账号持续可用的前提下，已扩展用户组、资源权限、依赖授权、申请审批与跨空间模板导入。后续实现与管理入口见 [Namespace 权限与配套资源管理](resource-permissions-management.md)。

日期：2026-09-08。代码位于主目录 `/Users/ken/agentscope-2/agentscope-java`，分支 `agentscope-service-v5`。

## 定位与入口

本次把已有权限模型做成可操作的管理流程，沿用现有 Namespace 与 Issue 授权基础。

| 入口 | 职责 | 访问者 |
| --- | --- | --- |
| Management → Namespaces | 创建共享空间、管理成员与角色、调整名称、转交所有权、归档与恢复、查看空间授权历史 | 普通成员查看自己的权限；空间管理员管理成员；所有者或平台管理员管理生命周期 |
| Management → Users | 创建账号、平台角色、重置密码、停用与恢复、从用户视角分配空间权限 | 平台管理员 |
| Management → Access log | 空间授权快照和账号操作记录 | 平台管理员；空间自己的记录也可从空间详情查看 |
| Profile | 显示名称、我的空间与有效权限、默认空间、密码与登录会话、个人 IM 身份和通知订阅 | 当前用户 |

左侧 Namespace 切换器增加 Manage namespace 和平台管理员可见的 Create 入口。Management 不再混在 Resources 中。旧 `/work/permissions` 跳转到当前授权空间的管理页；旧 `/managed/admin/users`、`/managed/profile` 分别跳转到新的 Users、Profile 页面。

创建共享空间需要平台管理员，可指定另一位有效账号为所有者，并一次选好初始成员。创建人保留空间管理员权限。成员选择通过账号目录搜索名字或用户名，存储仍使用稳定账号 ID。普通空间管理员只能使用管理所需的最小账号目录，不能借此调用平台账号管理接口。

## 统一的授权关系

Users 中给某人分配一个空间，与 Namespaces 中给某空间添加成员，写入同一份 Namespace.members。没有新增第二套授权记录，也没有把 Profile 变成授权入口。

| 空间角色 | 能力 |
| --- | --- |
| viewer | 发现资源、读取自己有权查看的工作 |
| member | 使用 Agent、Team、Workflow，创建和运行工作 |
| developer | 配置资源，以及创建和运行工作 |
| operator | 查看并操作空间基础设施 |
| admin | 管理空间成员、资源与基础设施 |
| auditor | 读取空间内全部业务工作，包括私有 Issue 与执行数据 |

角色可组合。所有者隐含 admin、member、developer、operator；auditor 仍需显式授予。平台管理员管理账号和空间配置，不因此自动获得所有私有工作内容。

Agent、Team、Workflow、Channel 等资源仍归属 Namespace；Issue、Task、Run、Session 在 Namespace 之外继续受工作自身的访问策略约束。调用资源不意味着能查看其他调用者的工作。既有 private/shared/namespace 模式和子 Issue 继承关系不变；跨 Namespace 调用、任务分配和数据访问仍需遵守现有边界。

## 生命周期与生效方式

- 共享空间所有权可转交给有效账号；旧所有者保留 admin，可在转交后另行移除。普通空间管理员不能转交或归档。
- 归档保留资源、成员配置和历史，暂停这些成员权限的生效，并从工作空间选择器移除；所有者或平台管理员可以恢复。归档不是取消所有正在运行的任务。个人空间不能归档或转交，成员固定为所有者。
- 用户停用保留账号 ID 和历史工作，阻止登录、既有登录会话和以该账号身份访问 Channel 工作。恢复后需要重新登录，旧凭据不会复活。停用前必须转交其拥有的共享空间，包含已归档空间。
- 禁止停用自己或移除最后一位有效平台管理员。账号角色/状态、空间成员/设置/所有权更新使用版本检查；遇到其他管理员已修改时返回冲突，要求刷新。
- 后端每次请求检查当前账号与空间权限。前端不再把 JWT 内旧角色当作菜单权限来源；每 30 秒及窗口重新聚焦时刷新权限。切换账号、空间或有效权限改变时清理缓存并重新挂载页面，避免保留旧数据和未保存表单。
- 默认空间存储在账号偏好中，只能选当前有权进入的空间。显式 URL 和最近的有效选择优先；默认空间失去权限后回退到个人空间。

## Profile 与 Channel 的连接

Profile 展示当前用户的 Channel 身份绑定，可解除自己的 IM 身份；显示本人活跃的 Issue 通知订阅，可退订。查询与修改均按当前用户过滤，不返回 Channel 密钥或 Issue 内容。即使用户失去空间成员资格，也可在个人设置解除自己已有的绑定。

新的身份配对从对应 Channel 页面发起。Channel 继续承担 IM 双向接入：用户身份用于校验从聊天窗口发来的工作请求，通知订阅决定事件回到哪个窗口。Profile 提供用户视角的管理，Channel 页面负责集成配置与路由。

密码修改保留当前登录并撤销其他登录；管理员重置密码撤销该账号全部登录。Profile 可查看登录设备描述、最近活动时间和过期时间，撤销单个其他会话或全部其他会话。注销也会在服务端撤销当前会话。历史 JWT 按需登记；用户撤销其他会话或改密后，禁止未登记的历史凭据再次登记。

## 数据与 API

Namespace 的 archived 字段保存在现有 JSON payload；所有权转交与授权审计在同一个存储事务内完成，memory/PostgreSQL 行为保持一致。管理库存按页遍历，避免只检查前 500 个空间。

Product 启动迁移幂等增加 `cp.users` 的 display_name、disabled、version、auth_version、legacy_sessions_closed、preferences；新增 `cp.account_login_sessions` 和 `cp.account_access_audit`。会话标识采用 token 的 SHA-256 摘要，审计不保存密码或原始 token。没有删除旧用户或自动迁移既有资源归属。

新增或扩展的接口：

- `GET/POST /api/v1/namespaces`，`GET/PUT /api/v1/namespaces/:name`。
- `GET /api/v1/namespaces/:name/accounts`、`GET /api/v1/namespaces/:name/audit`、`POST /api/v1/namespaces/:name/transfer`。
- `GET /api/v1/access/accounts`、`GET /api/v1/access/users/:id/namespaces`、`GET /api/v1/access/audit`。
- `GET/PUT /api/v1/me/preferences`，仅接受 defaultNamespace。
- `PATCH /api/admin/users/:id/status`、`PATCH /api/admin/users/:id/roles` 均要求 version；`GET /api/admin/access-audit`。
- `PUT /api/user/profile` 仅修改 displayName；密码接口保留原路径。
- `GET /api/user/login-sessions`、`DELETE /api/user/login-sessions/:id`、`POST /api/user/login-sessions/revoke-others`、`POST /api/auth/logout`。
- `GET/DELETE /api/user/channel-connections`、`DELETE /api/user/channel-subscriptions/:id`。

兼容性变化：旧删除用户接口现在执行停用并要求版本；客户端修改账号角色也必须携带版本。原有全局 agent_developer/operator 角色保留兼容，资源授权以空间角色为准。服务身份、Task token、内部令牌与 Kubernetes 认证仍保留原边界。

本次没有重启、部署服务或修改运行中的业务数据库。构建产物在主目录中更新，下一次使用新服务启动时应用幂等迁移。验证记录见 验收报告（历史本地验收记录，保存在发布前备份中）。

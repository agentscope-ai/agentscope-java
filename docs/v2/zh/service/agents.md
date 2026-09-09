# Agent、工具与技能

Agent 是可重复使用的配置。先选择执行方式，再配置它需要的资料与能力。

## 选择执行方式

| 方式 | 适用场景 | 前置条件 |
| --- | --- | --- |
| Managed | 平台托管 Harness 会话 | 模型、Environment、所需资源 |
| External application | 已有 Agent 应用继续自主管理进程 | 应用接入 SDK 与可达的合约地址 |
| Hosted runtime | 在指定主机运行 Coding Agent | 在线 Runtime Host 与已安装 provider |

## 配置与验证

在 Agents 中设置名称、指令和模型。绑定所需 Workspace、Memory、Vault 及工具；不要把凭据直接写入共享指令。工具确认策略决定是否需要用户审批。

Skill 适合保存可复用的任务说明及辅助文件。新增或修改技能后，在一个新 Session 中验证文件可见性、工具权限与执行结果，再交给 Team 使用。

Agent 的定义、运行实例和 Session 是不同对象。修改配置后检查新 Session 使用的配置版本；不要假设所有正在执行的会话都会立即切换。

## 给其他人使用

在相应 Namespace 中配置资源授权，同时检查依赖的 Workspace、模型或 Vault 是否可用。能够看到 Agent 不等于能够访问所有依赖，更不代表可以查看其他人的私有工作。

接下来：[Workspace 与 Environment](workspaces.md)、[权限与账号](access.md)。

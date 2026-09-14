---
title: "AgentScope 框架：注册应用"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

将已开发并运行的 AgentScope 应用注册为 External Agent，保留应用自身的部署方式。先完成目录与会话接入，再验证所需的任务能力。

## 准备接入信息

向管理员获取注册范围、应用可访问的 Service HTTP 地址和首次注册凭据。为每个应用副本设置稳定的 instance key，并准备控制面可回连的应用 HTTP 合约地址。

| 信息 | 验证要点 |
| --- | --- |
| tenant / namespace | 与目标 Agent 目录的范围一致 |
| 注册凭据 | 使用受信任 workload/bootstrap 或后续 registration credential；不是 Endpoint API key |
| instance key | 不同副本使用不同值，普通重启保持稳定 |
| 合约 URL | 控制面能访问应用；容器 localhost 通常只指当前容器 |

## 注册 Java 应用

1. 在应用中添加与 SDK 版本配套的 `agentscope-extensions-aistio` 依赖。
2. 在 Agent 初始化后调用 `Aistio.instrument(agent, config)`，保留返回的 `SessionBridge`。
3. 对标准 Service 部署，配置 `controlPlaneHttp`、身份凭据、范围和 `publicBaseUrl`，启用 `startHttpRegister(true)` 与 HTTP 合约，设置 `startGrpc(false)`。
4. 启动应用，在 **DESIGN → Agents** 查看注册的 Agent、实例和合约地址。
5. 在应用自身运行一轮对话，检查平台能读取适配器提供的会话信息；应用退出时关闭 bridge。

可复制的 Maven 与 Java 接入片段见参考手册的[Java HTTP 注册与合约](/v2/zh/service/external-agent#java添加-http-注册与合约)。

## 接受平台任务

需要接收 Issue 或加入 Team 时，配置 `AgentTaskStarter` 等真实任务入口，并验证一次执行的成功、失败和取消回报。仅注册目录和暴露会话信息不会自动获得任务执行能力。

Python 的自动注册与 ASDP 初始化关联，先按参考手册准备支持 ASDP 的部署，再使用 `aistio.instrument()`。[框架与适配能力](/v2/zh/service/external-agent-frameworks)说明各适配器已实现的范围。

接入完成后，按能力选择[Endpoint](/v2/zh/service/endpoints)或[控制台 Issue](/v2/zh/service/issues)；参数和生命周期见[External Agent 参考手册](/v2/zh/service/external-agent)。

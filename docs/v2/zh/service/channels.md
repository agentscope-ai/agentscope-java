---
title: "Channels：连接消息入口"
---

[English](/v2/en/service/channels)

**DESIGN → Channels** 把外部消息平台连接到 AgentScope Service。Channel 负责平台连接和消息路由，Agent 负责执行；需要面向你自己的应用提供稳定 HTTP 调用时，使用 [Endpoint](/v2/zh/service/endpoints)。

## 建立连接

先准备消息平台应用、所需权限及凭据。在 Channels 新建入口，从当前安装提供的平台列表中选择类型，填写该平台表单要求的字段。不同平台使用不同连接方式，按页面提供的回调、验证或连接信息完成平台侧配置。

远程回调地址必须能从外部平台访问。管理员应配置公开 HTTPS 地址；不能把容器内部地址或本机 localhost 填入外部平台。

## 配置路由

在 Channel 详情的规则中添加目标 Agent，并按需要设置匹配范围。检查默认规则和具体规则是否符合预期，避免把不同会话意外路由到同一个不合适的 Agent。也可以从 Agent 的 Connections → Channels 查看关联。

保存后先从测试用户发送一条只读请求，确认消息到达、正确 Agent 回复，再验证同一外部会话的后续消息和文件处理能力。

## 从接待转为工作

Channel 详情提供接待与工作回传视图，用于检查消息对应的工作、关联和回传结果。需要持续跟进时查看生成或关联的 Issue，并在 Issue Source 追溯入口。外部工作回传是否成功要看实际回传状态，不能只依据 Agent 完成。

## 排查

没有入站消息时检查平台事件订阅、回调可达性和凭据；消息已收到但没有执行时检查绑定、Agent 就绪度和权限；执行完成但没有回复时检查平台发送权限与回传错误。更换凭据后重新验证双向消息。

下一步：[Agents](/v2/zh/service/agents) · [Issues](/v2/zh/service/issues)。
